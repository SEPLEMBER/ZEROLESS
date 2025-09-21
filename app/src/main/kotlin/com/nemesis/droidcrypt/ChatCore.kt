package com.nemesis.droidcrypt

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.documentfile.provider.DocumentFile
import com.nemesis.droidcrypt.Engine
import com.nemesis.droidcrypt.MemoryManager
import java.util.*
import kotlin.collections.HashMap
import com.nemesis.droidcrypt.R

object ChatCore {
    private const val TAG = "ChatCore"

    // optional application context — инициализируется при старте приложения
    private var appContext: Context? = null

    /**
     * Инициализируйте ChatCore в Application.onCreate() или в ранней Activity:
     * ChatCore.init(applicationContext)
     */
    fun init(context: Context) {
        appContext = context.applicationContext
    }

    // Helper: получить строковый ресурс, если доступен, иначе вернуть fallback
    private fun safeGetString(context: Context?, resId: Int, fallback: String): String {
        return try {
            (context ?: appContext)?.getString(resId) ?: fallback
        } catch (e: Exception) {
            fallback
        }
    }

    // --- Anti-spam / fallback lists (читаются из ресурсов при наличии context) ---
    private val antiSpamResponses: List<String>
        get() {
            val c = appContext
            return if (c != null) {
                try {
                    listOf(
                        c.getString(R.string.antispam_1),
                        c.getString(R.string.antispam_2),
                        c.getString(R.string.antispam_3),
                        c.getString(R.string.antispam_4),
                        c.getString(R.string.antispam_5),
                        c.getString(R.string.antispam_6),
                        c.getString(R.string.antispam_7),
                        c.getString(R.string.antispam_8),
                        c.getString(R.string.antispam_9),
                        c.getString(R.string.antispam_10)
                    )
                } catch (e: Exception) {
                    listOf(
                        "Ты надоел, давай что-то новенького!",
                        "Спамить нехорошо, попробуй другой запрос.",
                        "Я устал от твоих повторений!",
                        "Хватит спамить, придумай что-то интересное.",
                        "Эй, не зацикливайся, попробуй другой вопрос!",
                        "Повторяешь одно и то же? Давай разнообразие!",
                        "Слишком много повторов, я же не робот... ну, почти.",
                        "Не спамь, пожалуйста, задай новый вопрос!",
                        "Пять раз одно и то же? Попробуй что-то другое.",
                        "Я уже ответил, давай новый запрос!"
                    )
                }
            } else {
                listOf(
                    "Ты надоел, давай что-то новенького!",
                    "Спамить нехорошо, попробуй другой запрос.",
                    "Я устал от твоих повторений!",
                    "Хватит спамить, придумай что-то интересное.",
                    "Эй, не зацикливайся, попробуй другой вопрос!",
                    "Повторяешь одно и то же? Давай разнообразие!",
                    "Слишком много повторов, я же не робот... ну, почти.",
                    "Не спамь, пожалуйста, задай новый вопрос!",
                    "Пять раз одно и то же? Попробуй что-то другое.",
                    "Я уже ответил, давай новый запрос!"
                )
            }
        }

    fun getAntiSpamResponse(): String = antiSpamResponses.random()

    val fallbackReplies: List<String>
        get() {
            val c = appContext
            return if (c != null) {
                try {
                    listOf(
                        c.getString(R.string.fallback_1),
                        c.getString(R.string.fallback_2),
                        c.getString(R.string.fallback_3),
                        c.getString(R.string.fallback_4)
                    )
                } catch (e: Exception) {
                    listOf("Привет!", "Как дела?", "Расскажи о себе", "Выход")
                }
            } else {
                listOf("Привет!", "Как дела?", "Расскажи о себе", "Выход")
            }
        }

    // --- Context.txt / temporary topic in RAM support ---
    // ContextPattern: left tokens before {}, right tokens after {}
    private data class ContextPattern(val left: List<String>, val right: List<String>, val response: String?)

    // cached context patterns (reloaded per findBestResponse call)
    private val contextPatterns: MutableList<ContextPattern> = mutableListOf()

    // recall map (canonical key -> response template with {topic})
    private val contextRecallMap: MutableMap<String, String> = HashMap()

    // temporary topic in RAM (ОЗУ)
    private var currentTopic: String? = null

    // Helper to match a context pattern against input tokens, returns captured topic or null
    private fun matchContextPattern(p: ContextPattern, tokensMapped: List<String>): String? {
        // require at least one token captured
        // FIX: We expect tokensMapped already to be filtered/mapped (same format as p.left/p.right)
        if (p.left.isNotEmpty()) {
            if (tokensMapped.size <= p.left.size) return null
            if (tokensMapped.subList(0, p.left.size) != p.left) return null
            if (p.right.isEmpty()) {
                val captured = tokensMapped.drop(p.left.size).joinToString(" ")
                return captured.ifBlank { null }
            } else {
                if (tokensMapped.size <= p.left.size + p.right.size) return null
                val tail = tokensMapped.takeLast(p.right.size)
                if (tail != p.right) return null
                val captured = tokensMapped.drop(p.left.size).dropLast(p.right.size).joinToString(" ")
                return captured.ifBlank { null }
            }
        } else {
            // left empty -> require right at end
            if (p.right.isEmpty()) return null
            if (tokensMapped.size <= p.right.size) return null
            val tail = tokensMapped.takeLast(p.right.size)
            if (tail != p.right) return null
            val captured = tokensMapped.dropLast(p.right.size).joinToString(" ")
            return captured.ifBlank { null }
        }
    }

    // load context.txt (and recall lines starting with $) into memory (lightweight)
    private fun loadContextTemplatesFromFolder(context: Context, folderUri: Uri?, synonymsSnapshot: Map<String, String>, stopwordsSnapshot: Set<String>) {
        contextPatterns.clear()
        contextRecallMap.clear()
        // currentTopic should be preserved across loads; no-op here

        val uri = folderUri ?: return
        try {
            val dir = DocumentFile.fromTreeUri(context, uri) ?: return
            val file = dir.findFile("context.txt") ?: return
            if (!file.exists()) return
            context.contentResolver.openInputStream(file.uri)?.bufferedReader()?.useLines { lines ->
                lines.forEach { raw ->
                    val l = raw.trim()
                    if (l.isEmpty()) return@forEach
                    try {
                        if (l.startsWith("$")) {
                            // recall line: $key=resp (key uses natural language)
                            val parts = l.substring(1).split("=", limit = 2).map { it.trim() }
                            if (parts.size == 2) {
                                val keyNorm = Engine.normalizeText(parts[0])
                                val keyTokens = Engine.filterStopwordsAndMapSynonymsStatic(keyNorm, synonymsSnapshot, stopwordsSnapshot).first
                                val keyCanon = keyTokens.sorted().joinToString(" ")
                                if (keyCanon.isNotEmpty()) contextRecallMap[keyCanon] = parts[1]
                            }
                        } else {
                            // pattern line: patternWith{} optionally = response (response may contain {topic})
                            val parts = l.split("=", limit = 2).map { it.trim() }
                            val patternRaw = parts[0]
                            val response = parts.getOrNull(1)
                            if (!patternRaw.contains("{}")) return@forEach
                            val segs = patternRaw.split("{}", limit = 2)
                            val leftNorm = Engine.normalizeText(segs[0])
                            val rightNorm = Engine.normalizeText(segs.getOrNull(1) ?: "")
                            val leftTokens = Engine.filterStopwordsAndMapSynonymsStatic(leftNorm, synonymsSnapshot, stopwordsSnapshot).first
                            val rightTokens = Engine.filterStopwordsAndMapSynonymsStatic(rightNorm, synonymsSnapshot, stopwordsSnapshot).first
                            contextPatterns.add(ContextPattern(leftTokens, rightTokens, response))
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "Failed parse context.txt line: $l", e)
                    }
                }
            }
            Log.d(TAG, "Loaded context patterns=${contextPatterns.size} recallKeys=${contextRecallMap.size}")
        } catch (e: Exception) {
            Log.w(TAG, "Error loading context.txt", e)
        }
    }

    // --- Загрузка синонимов и стоп-слов ---
    fun loadSynonymsAndStopwords(
        context: Context,
        folderUri: Uri?,
        synonymsMap: MutableMap<String, String>,
        stopwords: MutableSet<String>
    ) {
        synonymsMap.clear()
        stopwords.clear()
        val uri = folderUri ?: return
        try {
            val dir = DocumentFile.fromTreeUri(context, uri) ?: return

            dir.findFile("synonims.txt")?.takeIf { it.exists() }?.let { synFile ->
                context.contentResolver.openInputStream(synFile.uri)?.bufferedReader()?.useLines { lines ->
                    lines.forEach { raw ->
                        var l = raw.trim()
                        if (l.isEmpty()) return@forEach
                        if (l.startsWith("*") && l.endsWith("*") && l.length > 1) {
                            l = l.substring(1, l.length - 1)
                        }
                        val parts = l.split(";").map { Engine.normalizeText(it) }.filter { it.isNotEmpty() }
                        if (parts.isNotEmpty()) {
                            val canonical = parts.last()
                            parts.forEach { p -> synonymsMap[p] = canonical }
                        }
                    }
                }
            }

            dir.findFile("stopwords.txt")?.takeIf { it.exists() }?.let { stopFile ->
                context.contentResolver.openInputStream(stopFile.uri)?.bufferedReader()?.use { reader ->
                    val parts = reader.readText().split(Regex("[\\r\\n\\t,|^;]+"))
                        .map { Engine.normalizeText(it) }
                        .filter { it.isNotEmpty() }
                    stopwords.addAll(parts)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error loading synonyms/stopwords", e)
        }
    }

    // --- Парсинг шаблонов ---
    fun parseTemplatesFromFile(
        context: Context,
        folderUri: Uri?,
        filename: String,
        synonymsSnapshot: Map<String, String>,
        stopwordsSnapshot: Set<String>
    ): Pair<HashMap<String, MutableList<String>>, HashMap<String, MutableList<String>>> {
        val templates = HashMap<String, MutableList<String>>()
        val keywords = HashMap<String, MutableList<String>>()
        val uriLocal = folderUri ?: return Pair(templates, keywords)
        try {
            val dir = DocumentFile.fromTreeUri(context, uriLocal) ?: return Pair(templates, keywords)
            val file = dir.findFile(filename) ?: return Pair(templates, keywords)
            if (!file.exists()) return Pair(templates, keywords)

            context.contentResolver.openInputStream(file.uri)?.bufferedReader()?.useLines { lines ->
                lines.forEach { raw ->
                    val l = raw.trim()
                    if (l.isEmpty()) return@forEach

                    try {
                        if (l.startsWith("-")) {
                            val (key, respRaw) = l.substring(1).split("=", limit = 2).map { it.trim() }
                            val keyTokens = Engine.filterStopwordsAndMapSynonymsStatic(key, synonymsSnapshot, stopwordsSnapshot).first
                            val keyMapped = keyTokens.sorted().joinToString(" ")
                            val responses = respRaw.split("|").map { it.trim() }.filter { it.isNotEmpty() }
                            if (keyMapped.isNotEmpty() && responses.isNotEmpty()) {
                                keywords[keyMapped] = responses.toMutableList()
                            } else {
                                Log.d(TAG, "Skipped keyword (empty after mapping) in $filename: rawKey='$key' -> tokens=$keyTokens")
                            }
                        } else if (l.contains("=")) {
                            val (trigger, respRaw) = l.split("=", limit = 2).map { it.trim() }
                            val triggerTokens = Engine.filterStopwordsAndMapSynonymsStatic(trigger, synonymsSnapshot, stopwordsSnapshot).first
                            val triggerMapped = triggerTokens.sorted().joinToString(" ")
                            val responses = respRaw.split("|").map { it.trim() }.filter { it.isNotEmpty() }
                            if (triggerMapped.isNotEmpty() && responses.isNotEmpty()) {
                                templates[triggerMapped] = responses.toMutableList()
                            } else {
                                Log.d(TAG, "Skipped template (empty after mapping) in $filename: rawTrigger='$trigger' -> tokens=$triggerTokens")
                            }
                        }
                    } catch (pe: Exception) {
                        Log.e(TAG, "Error parsing line in $filename: '$l'", pe)
                    }
                }
            }

            Log.d(TAG, "Parsed $filename: templates=${templates.size}, keywords=${keywords.size}")
            templates.keys.take(5).forEach { Log.d(TAG, "TPL_KEY: '$it'") }
            keywords.keys.take(5).forEach { Log.d(TAG, "KW_KEY: '$it'") }

        } catch (e: Exception) {
            Log.e(TAG, "Error parsing templates from $filename", e)
        }
        return Pair(templates, keywords)
    }

    // --- Поиск ---
    fun searchInCoreFiles(
        context: Context,
        folderUri: Uri?,
        qFiltered: String,
        qTokens: List<String>,
        engine: Engine,
        synonymsSnapshot: Map<String, String>,
        stopwordsSnapshot: Set<String>,
        jaccardThreshold: Double
    ): String? {
        val uriLocal = folderUri ?: return null
        try {
            val dir = DocumentFile.fromTreeUri(context, uriLocal) ?: return null
            val (qMappedTokens, qMappedRaw) = Engine.filterStopwordsAndMapSynonymsStatic(qFiltered, synonymsSnapshot, stopwordsSnapshot)
            val qSet = qMappedTokens.toSet()
            val qCanonical = qMappedTokens.sorted().joinToString(" ")
            val dynamicJaccardThreshold = engine.getJaccardThreshold(qFiltered)

            Log.d(TAG, "Search start: qFiltered='$qFiltered' qMappedTokens=$qMappedTokens qCanonical='$qCanonical' jaccardThreshold=$dynamicJaccardThreshold")

            // helper: substitute placeholders using MemoryManager slots and choose random pipe-option
            fun substitutePlaceholdersWithMemory(resp: String): String {
                var out = resp
                val ph = Regex("<([a-zA-Z0-9_]+)>")
                ph.findAll(resp).forEach { m ->
                    val name = m.groupValues[1]
                    val value = try {
                        // special-case: topic uses ChatCore.currentTopic first, then memory slot
                        if (name.equals("topic", ignoreCase = true)) currentTopic ?: MemoryManager.readSlot(name)
                        else MemoryManager.readSlot(name)
                    } catch (_: Exception) {
                        null
                    }
                    // fallback if not found
                    out = out.replace("<$name>", value?.takeIf { it.isNotBlank() } ?: "неизвестно")
                }
                // support | alternatives
                if (out.contains("|")) {
                    val parts = out.split("|").map { it.trim() }.filter { it.isNotEmpty() }
                    if (parts.isNotEmpty()) out = parts.random()
                }
                return out
            }

            // resolvePotentialFileResponse: попытка углубиться в referenced file
            fun resolvePotentialFileResponse(respRaw: String, qSetLocal: Set<String>, qCanonicalLocal: String): String {
                val respTrim = respRaw.trim()
                val resp = respTrim.trim(':', ' ', '\t')
                val fileRegex = Regex("""([\w\-\._]+\.txt)""", RegexOption.IGNORE_CASE)
                val match = fileRegex.find(resp)
                if (match != null) {
                    val filename = match.groupValues[1].trim()
                    if (filename.isNotEmpty()) {
                        val (tpls, keywords) = parseTemplatesFromFile(context, folderUri, filename, synonymsSnapshot, stopwordsSnapshot)

                        if (qCanonicalLocal.isNotEmpty()) {
                            tpls[qCanonicalLocal]?.let { return substitutePlaceholdersWithMemory(it.random()) }
                        }

                        val rawKey = Engine.filterStopwordsAndMapSynonymsStatic(qFiltered, synonymsSnapshot, stopwordsSnapshot).second
                        tpls[rawKey]?.let { return substitutePlaceholdersWithMemory(it.random()) }

                        for ((k, v) in keywords) {
                            if (k.isBlank()) continue
                            val kTokens = k.split(" ").filter { it.isNotEmpty() }.toSet()
                            if (qSetLocal.intersect(kTokens).isNotEmpty()) {
                                return substitutePlaceholdersWithMemory(v.random())
                            }
                        }

                        var best: String? = null
                        var bestScore = 0.0
                        for (key in tpls.keys) {
                            val keyTokens = if (key.isEmpty()) emptySet<String>() else key.split(" ").toSet()
                            val score = engine.weightedJaccard(qSetLocal, keyTokens)
                            if (score > bestScore) {
                                bestScore = score
                                best = key
                            }
                        }
                        if (best != null && bestScore >= dynamicJaccardThreshold) {
                            return substitutePlaceholdersWithMemory(tpls[best]?.random() ?: respRaw)
                        }

                        var bestLev: String? = null
                        var bestDist = Int.MAX_VALUE
                        val levCandidates = tpls.keys.take(Engine.MAX_CANDIDATES_FOR_LEV)
                        for (key in levCandidates) {
                            val d = engine.levenshtein(qCanonicalLocal, key, qCanonicalLocal)
                            if (d < bestDist) {
                                bestDist = d
                                bestLev = key
                            }
                        }
                        if (bestLev != null && bestDist <= engine.getFuzzyDistance(qCanonicalLocal)) {
                            return substitutePlaceholdersWithMemory(tpls[bestLev]?.random() ?: respRaw)
                        }

                        val allResponses = mutableListOf<String>()
                        tpls.values.forEach { allResponses.addAll(it) }
                        keywords.values.forEach { allResponses.addAll(it) }
                        if (allResponses.isNotEmpty()) return substitutePlaceholdersWithMemory(allResponses.random())
                    }
                }
                return substitutePlaceholdersWithMemory(respRaw)
            }

            for (i in 1..9) {
                val filename = "core$i.txt"
                val file = dir.findFile(filename) ?: continue
                if (!file.exists()) continue
                val (templates, keywords) = parseTemplatesFromFile(context, folderUri, filename, synonymsSnapshot, stopwordsSnapshot)

                if (qCanonical.isNotEmpty()) {
                    templates[qCanonical]?.let {
                        Log.d(TAG, "Exact (canonical) match in $filename for '$qCanonical'")
                        return resolvePotentialFileResponse(it.random(), qSet, qCanonical)
                    }
                }

                templates[qMappedRaw]?.let {
                    Log.d(TAG, "Exact (raw) match in $filename for '$qMappedRaw'")
                    return resolvePotentialFileResponse(it.random(), qSet, qCanonical)
                }

                for ((k, v) in keywords) {
                    if (k.isBlank()) continue
                    val kTokens = k.split(" ").filter { it.isNotEmpty() }.toSet()
                    if (qSet.intersect(kTokens).isNotEmpty()) {
                        Log.d(TAG, "Keyword match in $filename: key='$k' tokens=$kTokens")
                        return resolvePotentialFileResponse(v.random(), qSet, qCanonical)
                    }
                }

                var best: String? = null
                var bestScore = 0.0
                for (key in templates.keys) {
                    val keyTokens = if (key.isEmpty()) emptySet<String>() else key.split(" ").toSet()
                    val score = engine.weightedJaccard(qSet, keyTokens)
                    if (score > bestScore) {
                        bestScore = score
                        best = key
                    }
                }
                if (best != null && bestScore >= dynamicJaccardThreshold) {
                    Log.d(TAG, "Jaccard match in $filename: best='$best' score=$bestScore threshold=$dynamicJaccardThreshold")
                    return resolvePotentialFileResponse(templates[best]?.random() ?: "", qSet, qCanonical)
                }

                var bestLev: String? = null
                var bestDist = Int.MAX_VALUE
                val levCandidates = templates.keys.take(Engine.MAX_CANDIDATES_FOR_LEV)
                for (key in levCandidates) {
                    val d = engine.levenshtein(qCanonical, key, qCanonical)
                    if (d < bestDist) {
                        bestDist = d
                        bestLev = key
                    }
                }
                if (bestLev != null && bestDist <= engine.getFuzzyDistance(qCanonical)) {
                    Log.d(TAG, "Levenshtein match in $filename: bestLev='$bestLev' dist=$bestDist fuzzy=${engine.getFuzzyDistance(qCanonical)}")
                    return resolvePotentialFileResponse(templates[bestLev]?.random() ?: "", qSet, qCanonical)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error searching in core files", e)
        }
        return null
    }

    // --- findBestResponse ---
    fun findBestResponse(
        context: Context,
        folderUri: Uri?,
        engine: Engine,
        userInput: String,
        filename: String = "core1.txt"
    ): String {
        try {
            MemoryManager.init(context)
            // передаём synonyms в MemoryManager, чтобы память знала canonical формы
            MemoryManager.loadTemplatesFromFolder(context, folderUri, engine.synonymsMap)
        } catch (e: Exception) {
            Log.w(TAG, "MemoryManager init/load failed (continuing): ${e.message}")
        }

        // --- load context.txt templates (lightweight) ---
        try {
            loadContextTemplatesFromFolder(context, folderUri, engine.synonymsMap, engine.stopwords)
        } catch (e: Exception) {
            Log.w(TAG, "loadContextTemplatesFromFolder failed: ${e.message}")
        }

        // Normalize & tokens
        val normalized = Engine.normalizeText(userInput)

        // FIX: получаем mapped tokens (те же самые, что использовались при загрузке contextPatterns)
        val (mappedTokens, mappedRaw) = Engine.filterStopwordsAndMapSynonymsStatic(normalized, engine.synonymsMap, engine.stopwords)
        val tokens = mappedTokens // use mapped tokens for context pattern matching

        // 1) Try context patterns ({} templates) — if matched, set currentTopic and return response
        try {
            for (p in contextPatterns) {
                val topicMapped = matchContextPattern(p, tokens)
                if (topicMapped != null) {
                    // remember in RAM (topic is mapped canonical tokens joined). If you want original surface form,
                    // you'll need to reconstruct from userInput — currently we store mapped form.
                    currentTopic = topicMapped
                    // also save to memory slot 'topic' for persistence if desired
                    try { MemoryManager.processIncoming(context, topicMapped) } catch (_: Exception) {}
                    val resp = p.response?.replace("{topic}", topicMapped) ?: "Запомнил тему: $topicMapped"
                    Log.d(TAG, "Context pattern matched. topic='$topicMapped' -> resp='$resp'")
                    return resp
                }
            }
            // 2) Check recall keys from contextRecallMap
            if (currentTopic != null && contextRecallMap.isNotEmpty()) {
                val normForKeyTokens = Engine.filterStopwordsAndMapSynonymsStatic(normalized, engine.synonymsMap, engine.stopwords).first
                val normForKey = normForKeyTokens.sorted().joinToString(" ")
                contextRecallMap[normForKey]?.let { templ ->
                    val result = templ.replace("{topic}", currentTopic ?: "неизвестно")
                    Log.d(TAG, "Context recall matched key='$normForKey' -> '$result'")
                    return result
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Context pattern handling failed: ${e.message}")
        }

        val normalizedForSearch = normalized
        val tokensForSearch = Engine.tokenizeStatic(normalized)

        val resp = searchInCoreFiles(
            context,
            folderUri,
            normalizedForSearch,
            tokensForSearch,
            engine,
            engine.synonymsMap,
            engine.stopwords,
            jaccardThreshold = Engine.JACCARD_THRESHOLD
        )

        if (resp != null) {
            try {
                MemoryManager.processIncoming(context, userInput)
            } catch (e: Exception) {
                Log.w(TAG, "MemoryManager processing failed (ignored): ${e.message}")
            }
            return resp
        }

        try {
            val memResp = MemoryManager.processIncoming(context, userInput)
            if (!memResp.isNullOrBlank()) return memResp
        } catch (e: Exception) {
            Log.w(TAG, "MemoryManager processing failed (ignored): ${e.message}")
        }

        return getDummyResponse(context, userInput)
    }

    // --- loadTemplatesFromFile (используется в UI elsewhere) ---
    fun loadTemplatesFromFile(
        context: Context,
        folderUri: Uri?,
        filename: String,
        templatesMap: MutableMap<String, MutableList<String>>,
        keywords: MutableMap<String, MutableList<String>>,
        jaccardList: MutableList<Pair<String, Set<String>>>,
        levenshteinMap: MutableMap<String, String>,
        synonymsMap: MutableMap<String, String>,
        stopwords: MutableSet<String>,
        metadataOut: MutableMap<String, String>
    ): Pair<Boolean, Int> {
        return try {
            val (parsedTemplates, parsedKeywords) = parseTemplatesFromFile(
                context,
                folderUri,
                filename,
                synonymsMap,
                stopwords
            )
            templatesMap.clear()
            templatesMap.putAll(parsedTemplates)
            keywords.clear()
            keywords.putAll(parsedKeywords)
            true to (parsedTemplates.size + parsedKeywords.size)
        } catch (e: Exception) {
            Log.e(TAG, "Error loading templates", e)
            false to 0
        }
    }

    // --- Заглушки ---
    fun loadFallbackTemplates(
        templatesMap: MutableMap<String, MutableList<String>>,
        keywordResponses: MutableMap<String, MutableList<String>>,
        mascotList: MutableList<Map<String, String>>,
        contextMap: MutableMap<String, String>
    ) {
        templatesMap.clear(); keywordResponses.clear(); mascotList.clear(); contextMap.clear()
        val c = appContext

        templatesMap[Engine.normalizeText("привет")] = mutableListOf(
            safeGetString(c, R.string.tpl_greeting_1, "Привет! Чем могу помочь?"),
            safeGetString(c, R.string.tpl_greeting_2, "Здравствуй!")
        )
        templatesMap[Engine.normalizeText("как дела")] = mutableListOf(
            safeGetString(c, R.string.tpl_howareyou_1, "Всё отлично, а у тебя?"),
            safeGetString(c, R.string.tpl_howareyou_2, "Нормально, как дела?")
        )
        keywordResponses[Engine.normalizeText("спасибо")] = mutableListOf(
            safeGetString(c, R.string.tpl_thanks_1, "Рад, что помог!"),
            safeGetString(c, R.string.tpl_thanks_2, "Всегда пожалуйста!")
        )
    }

    // getDummyResponse: использует ресурсы, если контекст доступен
    fun getDummyResponse(query: String): String {
        val c = appContext
        return getDummyResponse(c, query)
    }

    fun getDummyResponse(context: Context?, query: String): String {
        val greeting = safeGetString(context, R.string.dummy_greeting, "Привет! Чем могу помочь?")
        val unknown = safeGetString(context, R.string.dummy_unknown, "Не понял запрос. Попробуй другой вариант.")
        return if (query.lowercase(Locale.getDefault()).contains("привет"))
            greeting
        else
            unknown
    }

    fun detectContext(input: String, contextMap: Map<String, String>, engine: Engine): String? {
    try {
        // Нормализуем и применяем mapping/stopwords — та же pipeline, что использовалась при создании ключей contextMap
        val (mappedTokens, _) = Engine.filterStopwordsAndMapSynonymsStatic(
            Engine.normalizeText(input),
            engine.synonymsMap,
            engine.stopwords
        )

        if (mappedTokens.isEmpty()) return null
        val mappedSet = mappedTokens.toSet()

        // Ключи в contextMap ожидаются в canonical форме: tokens sorted (space-separated).
        return contextMap.maxByOrNull { (k, _) ->
            if (k.isBlank()) return@maxByOrNull 0
            val keyTokens = k.split(" ").filter { it.isNotEmpty() }.toSet()
            // считаем пересечение mappedTokens с токенами ключа
            mappedSet.count { it in keyTokens }
        }?.value
    } catch (e: Exception) {
        Log.w(TAG, "detectContext failed: ${e.message}")
        return null
    }
    }
}
