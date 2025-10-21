package app.pawstribe.assistant

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import app.pawstribe.assistant.Engine
import app.pawstribe.assistant.MemoryManager
import java.io.InputStreamReader
import app.pawstribe.assistant.R
import java.util.Locale
import java.util.Arrays
import androidx.preference.PreferenceManager

object ChatCore {

    private var appContext: Context? = null

    fun init(context: Context) {
        appContext = context.applicationContext
    }

    private fun safeGetString(context: Context?, resId: Int, fallback: String): String {
        return try {
            (context ?: appContext)?.getString(resId) ?: fallback
        } catch (_: Exception) {
            fallback
        }
    }

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
                } catch (_: Exception) {
                    listOf(
                        "Please stop spamming.",
                        "Too many repeats, try something new.",
                        "I'm tired of the repeats."
                    )
                }
            } else {
                listOf(
                    "Please stop spamming.",
                    "Too many repeats, try something new.",
                    "I'm tired of the repeats."
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
                } catch (_: Exception) {
                    listOf("Hello!", "How are you?", "Tell me about yourself", "Exit")
                }
            } else {
                listOf("Hello!", "How are you?", "Tell me about yourself", "Exit")
            }
        }

    private data class ContextPattern(val left: List<String>, val right: List<String>, val response: String?)

    private val contextPatterns: MutableList<ContextPattern> = mutableListOf()
    private val contextRecallMap: MutableMap<String, String> = mutableMapOf()
    private var currentTopic: String? = null

    private fun matchContextPattern(p: ContextPattern, tokensMapped: List<String>): String? {
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
            if (p.right.isEmpty()) return null
            if (tokensMapped.size <= p.right.size) return null
            val tail = tokensMapped.takeLast(p.right.size)
            if (tail != p.right) return null
            val captured = tokensMapped.dropLast(p.right.size).joinToString(" ")
            return captured.ifBlank { null }
        }
    }

    // ----------------- NEW: helper for reading and optional decryption -----------------
    // If file content starts with the Secure FORMAT_VERSION ("v1:"), try to decrypt using
    // password read from SharedPreferences under key "pref_encryption_password".
    // Returns plaintext (possibly decrypted) or null if reading/decryption failed.
    private fun readDocumentFileTextMaybeDecrypt(context: Context, file: DocumentFile): String? {
        try {
            context.contentResolver.openInputStream(file.uri)?.use { ins ->
                val rawBytes = ins.readBytes()
                val rawText = try {
                    String(rawBytes, Charsets.UTF_8)
                } catch (_: Exception) {
                    return null
                }
                val trimmed = rawText.trim()
                // Recognize Secure format by prefix "v1:"
                if (trimmed.startsWith("v1:")) {
                    // Get password from SharedPreferences (open/plain per spec)
                    val prefs = PreferenceManager.getDefaultSharedPreferences(context)
                    val passwordStr = prefs.getString("pref_encryption_password", null)
                    if (passwordStr.isNullOrEmpty()) {
                        // No password available -> cannot decrypt
                        return null
                    }
                    val passChars = passwordStr.toCharArray()
                    try {
                        return try {
                            Secure.decrypt(passChars, trimmed)
                        } catch (_: Exception) {
                            // decryption failed
                            null
                        }
                    } finally {
                        Arrays.fill(passChars, '\u0000')
                    }
                } else {
                    // Plaintext file
                    return rawText
                }
            }
        } catch (_: Exception) {
            // silent failure (no logs)
            return null
        }
        return null
    }
    // ----------------- END NEW helper -----------------

    /**
     * Найти DocumentFile внутри дерева `root` по относительному пути `pathRaw`.
     * Поддерживает пути вида "sub/folder/file.txt" или "/sub/folder/file.txt".
     * Возвращает null если не найдено.
     *
     * Работает быстро в большинстве случаев, и использует case-insensitive fallback
     * (перебор детей) если прямой findFile не находит сегмент.
     */
    private fun findDocumentFileByPath(root: DocumentFile, pathRaw: String?): DocumentFile? {
        if (pathRaw == null) return null
        var path = pathRaw.trim()
        if (path.startsWith("/")) path = path.substring(1)
        if (path.isEmpty()) return null

        val segments = path.split("/").mapNotNull { seg ->
            val s = seg.trim()
            if (s.isEmpty()) null
            else {
                try {
                    java.net.URLDecoder.decode(s, "UTF-8")
                } catch (_: Exception) {
                    s
                }
            }
        }
        if (segments.isEmpty()) return null

        var cursor: DocumentFile? = root
        try {
            for (seg in segments) {
                if (cursor == null) return null
                val next = cursor.findFile(seg)
                if (next != null) {
                    cursor = next
                    continue
                }
                // fallback: case-insensitive match among children (может быть медленнее, но надёжнее)
                val children = cursor.listFiles()
                val matched = children.firstOrNull { it.name != null && it.name.equals(seg, ignoreCase = true) }
                if (matched != null) {
                    cursor = matched
                    continue
                }
                // not found
                return null
            }
            return cursor
        } catch (_: Exception) {
            return null
        }
    }

    private fun loadContextTemplatesFromFolder(
        context: Context,
        folderUri: Uri?,
        synonymsSnapshot: Map<String, String>,
        stopwordsSnapshot: Set<String>
    ) {
        contextPatterns.clear()
        contextRecallMap.clear()
        val uri = folderUri ?: return
        try {
            val dir = DocumentFile.fromTreeUri(context, uri) ?: return
            // support nested path resolution
            val file = findDocumentFileByPath(dir, "context.txt") ?: return
            if (!file.exists()) return

            // <<< CHANGED: read whole file (possibly decrypted) into memory, then iterate lines
            val content = readDocumentFileTextMaybeDecrypt(context, file) ?: return
            content.lines().forEach { raw ->
                val l = raw.trim()
                if (l.isEmpty()) return@forEach
                try {
                    if (l.startsWith("$")) {
                        val parts = l.substring(1).split("=", limit = 2).map { it.trim() }
                        if (parts.size == 2) {
                            val keyNorm = Engine.normalizeText(parts[0])
                            val keyTokens = Engine.filterStopwordsAndMapSynonymsStatic(keyNorm, synonymsSnapshot, stopwordsSnapshot).first
                            val keyCanon = keyTokens.sorted().joinToString(" ")
                            if (keyCanon.isNotEmpty()) contextRecallMap[keyCanon] = parts[1]
                        }
                    } else {
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
                } catch (_: Exception) {
                }
            }
        } catch (_: Exception) {
        }
    }

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
            // support nested paths
            findDocumentFileByPath(dir, "synonims.txt")?.takeIf { it.exists() }?.let { synFile ->
                try {
                    val content = readDocumentFileTextMaybeDecrypt(context, synFile) ?: return@let
                    content.lines().forEach { raw ->
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
                } catch (_: Exception) {
                    // silent
                }
            }
            findDocumentFileByPath(dir, "stopwords.txt")?.takeIf { it.exists() }?.let { stopFile ->
                try {
                    val content = readDocumentFileTextMaybeDecrypt(context, stopFile) ?: return@let
                    val parts = content.split(Regex("[\\r\\n\\t,|^;]+"))
                        .map { Engine.normalizeText(it) }
                        .filter { it.isNotEmpty() }
                    stopwords.addAll(parts)
                } catch (_: Exception) {
                    // silent
                }
            }
        } catch (_: Exception) {
        }
    }

    fun parseTemplatesFromFile(
        context: Context,
        folderUri: Uri?,
        filename: String,
        synonymsSnapshot: Map<String, String>,
        stopwordsSnapshot: Set<String>
    ): Pair<MutableMap<String, MutableList<String>>, MutableMap<String, MutableList<String>>> {
        val templates: MutableMap<String, MutableList<String>> = mutableMapOf()
        val keywords: MutableMap<String, MutableList<String>> = mutableMapOf()
        val uriLocal = folderUri ?: return Pair(templates, keywords)
        try {
            val dir = DocumentFile.fromTreeUri(context, uriLocal) ?: return Pair(templates, keywords)
            // support nested path for filename
            val file = findDocumentFileByPath(dir, filename) ?: return Pair(templates, keywords)
            if (!file.exists()) return Pair(templates, keywords)

            // <<< CHANGED: read whole file (possibly decrypted) into memory, then iterate lines
            val content = readDocumentFileTextMaybeDecrypt(context, file) ?: return Pair(templates, keywords)
            content.lines().forEach { raw ->
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
                        }
                    } else if (l.contains("=")) {
                        val (trigger, respRaw) = l.split("=", limit = 2).map { it.trim() }
                        val triggerTokens = Engine.filterStopwordsAndMapSynonymsStatic(trigger, synonymsSnapshot, stopwordsSnapshot).first
                        val triggerMapped = triggerTokens.sorted().joinToString(" ")
                        val responses = respRaw.split("|").map { it.trim() }.filter { it.isNotEmpty() }
                        if (triggerMapped.isNotEmpty() && responses.isNotEmpty()) {
                            templates[triggerMapped] = responses.toMutableList()
                        }
                    }
                } catch (_: Exception) {
                }
            }
        } catch (_: Exception) {
        }
        return Pair(templates, keywords)
    }

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

            fun getUnknownPlaceholder(): String {
                val lang = (appContext?.resources?.configuration?.locales?.get(0)?.language ?: Locale.getDefault().language).lowercase(Locale.ROOT)
                return if (lang == "ru") "неизвестно" else "unknown"
            }

            fun substitutePlaceholdersWithMemory(resp: String): String {
                var out = resp
                val ph = Regex("<([\\p{L}\\p{N}_]+)>")
                ph.findAll(resp).forEach { m ->
                    val name = m.groupValues[1]
                    val value = try {
                        if (name.equals("topic", ignoreCase = true)) currentTopic ?: MemoryManager.readSlot(name)
                        else MemoryManager.readSlot(name)
                    } catch (_: Exception) {
                        null
                    }
                    out = out.replace("<$name>", value?.takeIf { it.isNotBlank() } ?: getUnknownPlaceholder())
                }
                if (out.contains("|")) {
                    val parts = out.split("|").map { it.trim() }.filter { it.isNotEmpty() }
                    if (parts.isNotEmpty()) out = parts.random()
                }
                return out
            }

            fun resolvePotentialFileResponse(respRaw: String, qSetLocal: Set<String>, qCanonicalLocal: String): String {
                val respTrim = respRaw.trim()
                val resp = respTrim.trim(':', ' ', '\t')
                val fileRegex = Regex("""([\p{L}\p{N}\-._/\\]+\.txt)""", RegexOption.IGNORE_CASE)
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
                val file = findDocumentFileByPath(dir, filename) ?: continue
                if (!file.exists()) continue
                val (templates, keywords) = parseTemplatesFromFile(context, folderUri, filename, synonymsSnapshot, stopwordsSnapshot)

                if (qCanonical.isNotEmpty()) {
                    templates[qCanonical]?.let {
                        return resolvePotentialFileResponse(it.random(), qSet, qCanonical)
                    }
                }

                templates[qMappedRaw]?.let {
                    return resolvePotentialFileResponse(it.random(), qSet, qCanonical)
                }

                for ((k, v) in keywords) {
                    if (k.isBlank()) continue
                    val kTokens = k.split(" ").filter { it.isNotEmpty() }.toSet()
                    if (qSet.intersect(kTokens).isNotEmpty()) {
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
                    return resolvePotentialFileResponse(templates[bestLev]?.random() ?: "", qSet, qCanonical)
                }
            }
        } catch (_: Exception) {
        }
        return null
    }

    fun findBestResponse(
        context: Context,
        folderUri: Uri?,
        engine: Engine,
        userInput: String,
        filename: String = "core1.txt"
    ): String {
        try {
            MemoryManager.init(context)
            MemoryManager.loadTemplatesFromFolder(context, folderUri, engine.synonymsMap)
        } catch (_: Exception) {
        }

        try {
            loadContextTemplatesFromFolder(context, folderUri, engine.synonymsMap, engine.stopwords)
        } catch (_: Exception) {
        }

        val normalized = Engine.normalizeText(userInput)
        val (mappedTokens, mappedRaw) = Engine.filterStopwordsAndMapSynonymsStatic(normalized, engine.synonymsMap, engine.stopwords)
        val tokens = mappedTokens

        try {
            for (p in contextPatterns) {
                val topicMapped = matchContextPattern(p, tokens)
                if (topicMapped != null) {
                    currentTopic = topicMapped
                    try { MemoryManager.processIncoming(context, topicMapped) } catch (_: Exception) {}
                    val resp = p.response?.replace("{topic}", topicMapped) ?: run {
                        val lang = (appContext?.resources?.configuration?.locales?.get(0)?.language ?: Locale.getDefault().language).lowercase(Locale.ROOT)
                        if (lang == "ru") "Запомнил тему: $topicMapped" else "Remembered topic: $topicMapped"
                    }
                    return resp
                }
            }
            if (currentTopic != null && contextRecallMap.isNotEmpty()) {
                val normForKeyTokens = Engine.filterStopwordsAndMapSynonymsStatic(normalized, engine.synonymsMap, engine.stopwords).first
                val normForKey = normForKeyTokens.sorted().joinToString(" ")
                contextRecallMap[normForKey]?.let { templ ->
                    val result = templ.replace("{topic}", currentTopic ?: run {
                        val lang = (appContext?.resources?.configuration?.locales?.get(0)?.language ?: Locale.getDefault().language).lowercase(Locale.ROOT)
                        if (lang == "ru") "неизвестно" else "unknown"
                    })
                    return result
                }
            }
        } catch (_: Exception) {
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
            } catch (_: Exception) {
            }
            return resp
        }

        try {
            val memResp = MemoryManager.processIncoming(context, userInput)
            if (!memResp.isNullOrBlank()) return memResp
        } catch (_: Exception) {
        }

        return getDummyResponse(context, userInput)
    }

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
        } catch (_: Exception) {
            false to 0
        }
    }

    fun loadFallbackTemplates(
        templatesMap: MutableMap<String, MutableList<String>>,
        keywordResponses: MutableMap<String, MutableList<String>>,
        mascotList: MutableList<Map<String, String>>,
        contextMap: MutableMap<String, String>
    ) {
        templatesMap.clear()
        keywordResponses.clear()
        mascotList.clear()
        contextMap.clear()
        val c = appContext

        val triggerHello = safeGetString(c, R.string.trigger_hello, safeGetString(c, R.string.fallback_1, "hello"))
        val triggerHowAreYou = safeGetString(c, R.string.trigger_how_are_you, safeGetString(c, R.string.fallback_3, "how are you"))
        val triggerThanks = safeGetString(c, R.string.trigger_thanks, "thanks")

        templatesMap[Engine.normalizeText(triggerHello)] = mutableListOf(
            safeGetString(c, R.string.tpl_greeting_1, safeGetString(c, R.string.fallback_1, "Hello! How can I help?")),
            safeGetString(c, R.string.tpl_greeting_2, safeGetString(c, R.string.fallback_2, "Hi!"))
        )
        templatesMap[Engine.normalizeText(triggerHowAreYou)] = mutableListOf(
            safeGetString(c, R.string.tpl_howareyou_1, safeGetString(c, R.string.tpl_howareyou_1, "I'm great, and you?")),
            safeGetString(c, R.string.tpl_howareyou_2, safeGetString(c, R.string.tpl_howareyou_2, "Doing fine, how about you?"))
        )
        keywordResponses[Engine.normalizeText(triggerThanks)] = mutableListOf(
            safeGetString(c, R.string.tpl_thanks_1, "Glad I could help!"),
            safeGetString(c, R.string.tpl_thanks_2, "You're welcome!")
        )
    }

    fun getDummyResponse(query: String): String {
        val c = appContext
        return getDummyResponse(c, query)
    }

    fun getDummyResponse(context: Context?, query: String): String {
        val greeting = safeGetString(context, R.string.dummy_greeting, safeGetString(context, R.string.fallback_1, "Hello!"))
        val unknown = safeGetString(context, R.string.dummy_unknown, if ((context?.resources?.configuration?.locales?.get(0)?.language ?: Locale.getDefault().language).lowercase(Locale.ROOT) == "ru") "Не понял запрос. Попробуй другой вариант." else "Didn't understand. Try another phrasing.")
        val triggerHello = safeGetString(context, R.string.trigger_hello, safeGetString(context, R.string.fallback_1, "hello")).lowercase(Locale.ROOT)
        return if (query.lowercase(Locale.ROOT).contains(triggerHello)) greeting else unknown
    }

    fun detectContext(input: String, contextMap: Map<String, String>, engine: Engine): String? {
        try {
            val (mappedTokens, _) = Engine.filterStopwordsAndMapSynonymsStatic(
                Engine.normalizeText(input),
                engine.synonymsMap,
                engine.stopwords
            )
            if (mappedTokens.isEmpty()) return null
            val mappedSet = mappedTokens.toSet()
            return contextMap.maxByOrNull { (k, _) ->
                if (k.isBlank()) return@maxByOrNull 0
                val keyTokens = k.split(" ").filter { it.isNotEmpty() }.toSet()
                mappedSet.count { it in keyTokens }
            }?.value
        } catch (_: Exception) {
            return null
        }
    }
}
