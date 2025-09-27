package com.nemesis.droidcrypt

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import com.nemesis.droidcrypt.Engine
import com.nemesis.droidcrypt.MemoryManager
import java.io.InputStreamReader
import com.nemesis.droidcrypt.R
import java.util.Locale
import android.util.Log
import android.content.ClipData
import android.content.ClipboardManager
import java.text.SimpleDateFormat
import java.util.Date
import java.util.concurrent.atomic.AtomicInteger

object ChatCore {

    private var appContext: Context? = null

    // simple in-memory ring buffer for logs (keeps last N entries)
    private val logBuffer = ArrayDeque<String>()
    private const val LOG_BUFFER_MAX = 200
    private val logCounter = AtomicInteger(0)

    fun init(context: Context) {
        appContext = context.applicationContext
        appendLog("ChatCore.init")
    }

    private fun timestamp(): String {
        val df = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
        return df.format(Date())
    }

    private fun appendLogLine(line: String) {
        synchronized(logBuffer) {
            val entry = "${timestamp()} ${line}"
            logBuffer.addLast(entry)
            if (logBuffer.size > LOG_BUFFER_MAX) logBuffer.removeFirst()
            // also log to Logcat for devices where it's possible
            try {
                Log.i("ChatCore", entry)
            } catch (_: Exception) {}
        }
    }

    private fun appendLog(tag: String, message: String? = null, ex: Exception? = null) {
        val idx = logCounter.incrementAndGet()
        val msg = buildString {
            append("[$idx] ")
            append(tag)
            if (!message.isNullOrBlank()) {
                append(" - ")
                append(message)
            }
            ex?.let {
                append(" : ")
                append(it::class.java.simpleName)
                append(" - ")
                append(it.message ?: "")
            }
        }
        appendLogLine(msg)
        // persist last logs to clipboard for debugging (best-effort)
        try {
            val c = appContext ?: return
            val clipboard = c.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val whole = synchronized(logBuffer) { logBuffer.joinToString("\n") }
            val clip = ClipData.newPlainText("RacoonTalkLogs", whole)
            clipboard.setPrimaryClip(clip)
        } catch (_: Exception) {
            // ignore clipboard failures
        }
    }

    private fun safeGetString(context: Context?, resId: Int, fallback: String): String {
        return try {
            (context ?: appContext)?.getString(resId) ?: fallback
        } catch (e: Exception) {
            appendLog("safeGetString", "resId=$resId", e)
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
                } catch (e: Exception) {
                    appendLog("antiSpamResponses", null, e)
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
                } catch (e: Exception) {
                    appendLog("fallbackReplies", null, e)
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
            val file = dir.findFile("context.txt") ?: return
            if (!file.exists()) return
            context.contentResolver.openInputStream(file.uri)?.use { ins ->
                InputStreamReader(ins, Charsets.UTF_8).buffered().useLines { lines ->
                    lines.forEach { raw ->
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
                        } catch (e: Exception) {
                            appendLog("loadContextTemplatesFromFolder", "parsing line failed: $raw", e)
                        }
                    }
                }
            }
        } catch (e: Exception) {
            appendLog("loadContextTemplatesFromFolder", "failed to load context.txt", e)
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
            dir.findFile("synonims.txt")?.takeIf { it.exists() }?.let { synFile ->
                context.contentResolver.openInputStream(synFile.uri)?.use { ins ->
                    InputStreamReader(ins, Charsets.UTF_8).buffered().useLines { lines ->
                        lines.forEach { raw ->
                            var l = raw.trim()
                            if (l.isEmpty()) return@forEach
                            try {
                                if (l.startsWith("*") && l.endsWith("*") && l.length > 1) {
                                    l = l.substring(1, l.length - 1)
                                }
                                val parts = l.split(";").map { Engine.normalizeText(it) }.filter { it.isNotEmpty() }
                                if (parts.isNotEmpty()) {
                                    val canonical = parts.last()
                                    parts.forEach { p -> synonymsMap[p] = canonical }
                                }
                            } catch (e: Exception) {
                                appendLog("loadSynonymsAndStopwords", "line parse error: $raw", e)
                            }
                        }
                    }
                }
            }
            dir.findFile("stopwords.txt")?.takeIf { it.exists() }?.let { stopFile ->
                context.contentResolver.openInputStream(stopFile.uri)?.use { ins ->
                    InputStreamReader(ins, Charsets.UTF_8).buffered().use { reader ->
                        val parts = reader.readText().split(Regex("[\\r\\n\\t,|^;]+"))
                            .map { Engine.normalizeText(it) }
                            .filter { it.isNotEmpty() }
                        stopwords.addAll(parts)
                    }
                }
            }
        } catch (e: Exception) {
            appendLog("loadSynonymsAndStopwords", "failed to load synonyms/stopwords", e)
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
            val file = dir.findFile(filename) ?: return Pair(templates, keywords)
            if (!file.exists()) return Pair(templates, keywords)
            context.contentResolver.openInputStream(file.uri)?.use { ins ->
                InputStreamReader(ins, Charsets.UTF_8).buffered().useLines { lines ->
                    lines.forEach { raw ->
                        val l = raw.replace("\uFEFF", "").trim()
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
                        } catch (e: Exception) {
                            appendLog("parseTemplatesFromFile", "parse error in $filename line: $raw", e)
                        }
                    }
                }
            }
        } catch (e: Exception) {
            appendLog("parseTemplatesFromFile", "failed to parse $filename", e)
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
                    } catch (e: Exception) {
                        appendLog("substitutePlaceholdersWithMemory", "mem read fail for $name", e)
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
                val fileRegex = Regex("""([\p{L}\p{N}\-._]+\.txt)""", RegexOption.IGNORE_CASE)
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
        } catch (e: Exception) {
            appendLog("searchInCoreFiles", "search failed", e)
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
        } catch (e: Exception) {
            appendLog("findBestResponse", "memory init/load failed", e)
        }

        try {
            loadContextTemplatesFromFolder(context, folderUri, engine.synonymsMap, engine.stopwords)
        } catch (e: Exception) {
            appendLog("findBestResponse", "loadContextTemplatesFromFolder failed", e)
        }

        val normalized = Engine.normalizeText(userInput)
        val (mappedTokens, mappedRaw) = Engine.filterStopwordsAndMapSynonymsStatic(normalized, engine.synonymsMap, engine.stopwords)
        val tokens = mappedTokens

        try {
            // pattern-based context capture (e.g. "my favorite topic is {}")
            for (p in contextPatterns) {
                val topicMapped = matchContextPattern(p, tokens)
                if (topicMapped != null) {
                    currentTopic = topicMapped
                    try { MemoryManager.processIncoming(context, topicMapped) } catch (e: Exception) { appendLog("findBestResponse", "memory write failed", e) }
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
        } catch (e: Exception) {
            appendLog("findBestResponse", "context pattern handling failed", e)
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
                appendLog("findBestResponse", "memory process incoming failed", e)
            }
            return resp
        }

        try {
            val memResp = MemoryManager.processIncoming(context, userInput)
            if (!memResp.isNullOrBlank()) return memResp
        } catch (e: Exception) {
            appendLog("findBestResponse", "memory fallback failed", e)
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
        } catch (e: Exception) {
            appendLog("loadTemplatesFromFile", "failed to load $filename", e)
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

    /**
     * Improved detectContext:
     * - uses mapped tokens (synonyms/stopwords applied)
     * - computes absolute overlap and relative overlap
     * - boosts score if tokens appear in engine.getContextTokens() (recent conversation)
     * - returns null if below thresholds (to avoid accidental switches)
     */
    fun detectContext(input: String, contextMap: Map<String, String>, engine: Engine): String? {
        try {
            val (mappedTokens, _) = Engine.filterStopwordsAndMapSynonymsStatic(
                Engine.normalizeText(input),
                engine.synonymsMap,
                engine.stopwords
            )
            if (mappedTokens.isEmpty()) return null
            val mappedSet = mappedTokens.toSet()
            val ctxTokensRecent = engine.getContextTokens()
            var bestKey: String? = null
            var bestScore = 0.0

            for ((k, v) in contextMap) {
                if (k.isBlank()) continue
                val keyTokens = k.split(" ").filter { it.isNotEmpty() }.toSet()
                if (keyTokens.isEmpty()) continue
                val common = mappedSet.intersect(keyTokens)
                val absCount = common.size
                if (absCount == 0) continue
                val rel = absCount.toDouble() / keyTokens.size.toDouble() // fraction of key matched
                var score = absCount.toDouble() + rel
                // boost if we have recent context tokens overlapping
                val recentOverlap = ctxTokensRecent.intersect(keyTokens).size
                if (recentOverlap > 0) {
                    score += recentOverlap * Engine.CONTEXT_BOOST
                }
                // prefer keys that are more specific (larger keyTokens) if relative match is good
                if (rel >= 0.6) score += 0.5

                if (score > bestScore) {
                    bestScore = score
                    bestKey = k
                }
            }

            // require either:
            // - absolute match >= 2 tokens, or
            // - relative match >= 0.6 (i.e., matched majority of small key)
            if (bestKey != null) {
                val keyTokens = bestKey.split(" ").filter { it.isNotEmpty() }.toSet()
                val matched = mappedSet.intersect(keyTokens)
                val absCount = matched.size
                val rel = if (keyTokens.isEmpty()) 0.0 else absCount.toDouble() / keyTokens.size.toDouble()
                if (absCount >= 2 || rel >= 0.6) {
                    return contextMap[bestKey]
                }
            }
            return null
        } catch (e: Exception) {
            appendLog("detectContext", "failed", e)
            return null
        }
    }
}
