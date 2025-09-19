package com.nemesis.droidcrypt

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.*
import kotlin.collections.HashMap
import kotlin.collections.HashSet
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.roundToInt

class ChatEngine(private val context: Context, private var folderUri: Uri?) {

    companion object {
        private const val CANDIDATE_TOKEN_THRESHOLD = 2
        private const val MAX_SUBQUERY_RESPONSES = 3
        private const val MAX_CANDIDATES_FOR_LEV = 8
        private const val MAX_CACHE_SIZE = 100
        private const val SPAM_WINDOW_MS = 60000L
        private const val MAX_TOKENS_PER_INDEX = 50
        private const val MIN_TOKEN_LENGTH = 3
        private const val MAX_TEMPLATES_SIZE = 5000
    }

    // Data structures
    val templatesMap = HashMap<String, MutableList<String>>()
    val fallback = arrayOf("Привет", "Как дела?", "Расскажи о себе", "Выход")
    private val contextMap = HashMap<String, String>()
    private val keywordResponses = HashMap<String, MutableList<String>>()
    private val antiSpamResponses = mutableListOf<String>()
    private val mascotList = mutableListOf<Map<String, String>>()
    private val invertedIndex = HashMap<String, MutableList<String>>()
    private val synonymsMap = HashMap<String, String>()
    private val stopwords = HashSet<String>()
    private val queryTimestamps = HashMap<String, MutableList<Long>>()
    private val queryCountMap = HashMap<String, Int>()
    private val tokenWeights = HashMap<String, Double>()

    var currentMascotName = "Racky"
        private set
    var currentMascotIcon = "raccoon_icon.png"
        private set
    var currentThemeColor = "#00FF00"
        private set
    var currentThemeBackground = "#000000"
        private set
    var currentContext = "base.txt"
        private set

    private val queryCache = object : LinkedHashMap<String, String>(MAX_CACHE_SIZE, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?): Boolean {
            return size > MAX_CACHE_SIZE
        }
    }

    init {
        antiSpamResponses.addAll(
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
        )
        loadDataForContext(currentContext)
    }

    fun loadDataForContext(filename: String) {
        currentContext = filename
        loadTemplatesFromFile(filename)
        rebuildInvertedIndex()
        computeTokenWeights()
    }

    fun getStats(): String {
        return "Контекст: $currentContext. Шаблонов: ${templatesMap.size}. Ключевых ответов: ${keywordResponses.size}."
    }

    fun clearHistory() {
        queryCountMap.clear()
        queryTimestamps.clear()
        queryCache.clear()
        currentContext = "base.txt"
        loadDataForContext(currentContext)
    }

    // Основной метод обработки запроса
    suspend fun processQuery(userInput: String): String {
        val qOrigRaw = userInput.trim()
        val qOrig = normalizeText(qOrigRaw)
        val (qTokensFiltered, qFiltered) = filterStopwordsAndMapSynonyms(qOrig)
        val qKeyForCount = qFiltered

        if (qFiltered.isEmpty()) return ""

        queryCache[qKeyForCount]?.let { return it }

        // Anti-spam
        val now = System.currentTimeMillis()
        val timestamps = queryTimestamps.getOrPut(qKeyForCount) { mutableListOf() }
        timestamps.add(now)
        timestamps.removeAll { it < now - SPAM_WINDOW_MS }
        if (timestamps.size >= 5) return antiSpamResponses.random()

        val templatesSnapshot = HashMap(templatesMap)
        val invertedIndexSnapshot = HashMap(invertedIndex)
        val synonymsSnapshot = HashMap(synonymsMap)
        val stopwordsSnapshot = HashSet(stopwords)
        val keywordResponsesSnapshot = HashMap(keywordResponses)

        // Exact match
        templatesSnapshot[qFiltered]?.let { possible ->
            if (possible.isNotEmpty()) return possible.random().also { cacheResponse(qKeyForCount, it) }
        }

        // Subquery match
        val subqueryResponses = findSubqueryResponses(qTokensFiltered, qFiltered, templatesSnapshot, keywordResponsesSnapshot)
        if (subqueryResponses.isNotEmpty()) {
            return subqueryResponses.joinToString(". ").also { cacheResponse(qKeyForCount, it) }
        }

        // Keyword match
        for ((keyword, responses) in keywordResponsesSnapshot) {
            if (qFiltered.contains(keyword) && responses.isNotEmpty()) {
                return responses.random().also { cacheResponse(qKeyForCount, it) }
            }
        }

        // Jaccard and Levenshtein search
        val qTokens = qTokensFiltered.ifEmpty { tokenize(qFiltered) }
        val candidates = findCandidates(qTokens, qFiltered, invertedIndexSnapshot, templatesSnapshot)
        val qSet = qTokens.toSet()
        val jaccardThreshold = getJaccardThreshold(qFiltered)

        val bestByJaccard = findBestMatchByJaccard(candidates, qSet, synonymsSnapshot, stopwordsSnapshot, jaccardThreshold)
        if (bestByJaccard != null) {
            templatesSnapshot[bestByJaccard]?.let {
                if (it.isNotEmpty()) return it.random().also { r -> cacheResponse(qKeyForCount, r) }
            }
        }

        val bestByLevenshtein = findBestMatchByLevenshtein(candidates, qFiltered)
        if (bestByLevenshtein != null) {
            templatesSnapshot[bestByLevenshtein]?.let {
                if (it.isNotEmpty()) return it.random().also { r -> cacheResponse(qKeyForCount, r) }
            }
        }

        // Context detection and switch
        val detectedContext = detectContext(normalizeText(qFiltered))
        if (detectedContext != null && detectedContext != currentContext) {
            withContext(Dispatchers.Main) {
                loadDataForContext(detectedContext)
            }
            // Повторяем поиск в новом контексте
            val responseInNewContext = processQuery(userInput)
            if (responseInNewContext != getDummyResponse(qOrig)) {
                return responseInNewContext
            }
        }

        // Search in core files as a last resort
        val coreResult = searchInCoreFiles(qFiltered, qTokens, qSet, jaccardThreshold, synonymsSnapshot, stopwordsSnapshot)
        if (coreResult != null) {
            return coreResult.also { cacheResponse(qKeyForCount, it) }
        }

        return getDummyResponse(qOrig).also { cacheResponse(qKeyForCount, it) }
    }


    // --- Вспомогательные и приватные методы ---
    private fun findSubqueryResponses(
        qTokensFiltered: List<String>,
        qFiltered: String,
        templatesSnapshot: Map<String, List<String>>,
        keywordResponsesSnapshot: Map<String, List<String>>
    ): List<String> {
        val subqueryResponses = mutableListOf<String>()
        val processedSubqueries = mutableSetOf<String>()

        val tokens = qTokensFiltered.ifEmpty { tokenize(qFiltered) }
        for (token in tokens) {
            if (subqueryResponses.size >= MAX_SUBQUERY_RESPONSES) break
            if (processedSubqueries.contains(token) || token.length < 2) continue
            templatesSnapshot[token]?.let {
                subqueryResponses.add(it.random())
                processedSubqueries.add(token)
            }
            keywordResponsesSnapshot[token]?.let {
                if (subqueryResponses.size < MAX_SUBQUERY_RESPONSES) {
                    subqueryResponses.add(it.random())
                    processedSubqueries.add(token)
                }
            }
        }
        return subqueryResponses
    }

    private fun findCandidates(
        qTokens: List<String>,
        qFiltered: String,
        invertedIndexSnapshot: Map<String, List<String>>,
        templatesSnapshot: Map<String, List<String>>
    ): List<String> {
        val candidateCounts = HashMap<String, Int>()
        qTokens.forEach { tok ->
            invertedIndexSnapshot[tok]?.forEach { trig ->
                candidateCounts[trig] = candidateCounts.getOrDefault(trig, 0) + 1
            }
        }

        return if (candidateCounts.isNotEmpty()) {
            candidateCounts.entries
                .filter { it.value >= CANDIDATE_TOKEN_THRESHOLD }
                .sortedByDescending { it.value }
                .map { it.key }
                .take(MAX_CANDIDATES_FOR_LEV)
        } else {
            val maxDist = getFuzzyDistance(qFiltered)
            templatesSnapshot.keys.filter { abs(it.length - qFiltered.length) <= maxDist }
                .take(MAX_CANDIDATES_FOR_LEV)
        }
    }

    private fun findBestMatchByJaccard(candidates: List<String>, qSet: Set<String>, synonyms: Map<String, String>, stopwords: Set<String>, threshold: Double): String? {
        var bestKey: String? = null
        var bestScore = 0.0
        for (key in candidates) {
            val keyTokens = filterStopwordsAndMapSynonyms(key, synonyms, stopwords).first.toSet()
            if (keyTokens.isEmpty()) continue
            val score = weightedJaccard(qSet, keyTokens)
            if (score > bestScore) {
                bestScore = score
                bestKey = key
            }
        }
        return if (bestKey != null && bestScore >= threshold) bestKey else null
    }

    private fun findBestMatchByLevenshtein(candidates: List<String>, qFiltered: String): String? {
        var bestKey: String? = null
        var bestDist = Int.MAX_VALUE
        val maxDist = getFuzzyDistance(qFiltered)
        for (key in candidates) {
            if (abs(key.length - qFiltered.length) > maxDist + 1) continue
            val d = levenshtein(qFiltered, key)
            if (d < bestDist) {
                bestDist = d
                bestKey = key
            }
            if (bestDist == 0) break
        }
        return if (bestKey != null && bestDist <= maxDist) bestKey else null
    }

    private fun parseTemplatesFromFile(
        filename: String,
        synonyms: Map<String, String>,
        stopwords: Set<String>
    ): Pair<HashMap<String, MutableList<String>>, HashMap<String, MutableList<String>>> {
        val localTemplates = HashMap<String, MutableList<String>>()
        val localKeywords = HashMap<String, MutableList<String>>()
        val uriLocal = folderUri ?: return Pair(localTemplates, localKeywords)

        try {
            val dir = DocumentFile.fromTreeUri(context, uriLocal) ?: return Pair(localTemplates, localKeywords)
            val file = dir.findFile(filename) ?: return Pair(localTemplates, localKeywords)

            context.contentResolver.openInputStream(file.uri)?.bufferedReader()?.use { reader ->
                reader.forEachLine { raw ->
                    val line = raw.trim()
                    if (line.isEmpty()) return@forEachLine

                    if (line.startsWith("-")) {
                        val parts = line.substring(1).split("=", limit = 2)
                        if (parts.size == 2) {
                            val keyword = parts[0].trim().lowercase(Locale.ROOT)
                            val responses = parts[1].split("|").mapNotNull { it.trim().takeIf(String::isNotEmpty) }.toMutableList()
                            if (keyword.isNotEmpty() && responses.isNotEmpty()) localKeywords[keyword] = responses
                        }
                    } else if (line.contains("=")) {
                        val parts = line.split("=", limit = 2)
                        if (parts.size == 2) {
                            val trigger = normalizeText(parts[0].trim())
                            val triggerFiltered = filterStopwordsAndMapSynonyms(trigger, synonyms, stopwords).second
                            val responses = parts[1].split("|").mapNotNull { it.trim().takeIf(String::isNotEmpty) }.toMutableList()
                            if (triggerFiltered.isNotEmpty() && responses.isNotEmpty()) localTemplates[triggerFiltered] = responses
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e("ChatEngine", "Error parsing templates from $filename", e)
        }
        return Pair(localTemplates, localKeywords)
    }

    private fun searchInCoreFiles(
        qFiltered: String,
        qTokens: List<String>,
        qSet: Set<String>,
        jaccardThreshold: Double,
        synonyms: Map<String, String>,
        stopwords: Set<String>
    ): String? {
        val uriLocal = folderUri ?: return null
        try {
            val dir = DocumentFile.fromTreeUri(context, uriLocal) ?: return null
            for (i in 1..9) {
                val filename = "core$i.txt"
                val file = dir.findFile(filename) ?: continue
                val (coreTemplates, coreKeywords) = parseTemplatesFromFile(filename, synonyms, stopwords)
                if (coreTemplates.isEmpty() && coreKeywords.isEmpty()) continue

                coreTemplates[qFiltered]?.let { if (it.isNotEmpty()) return it.random() }

                for ((keyword, responses) in coreKeywords) {
                    if (qFiltered.contains(keyword) && responses.isNotEmpty()) return responses.random()
                }

                val candidates = coreTemplates.keys.toList()
                findBestMatchByJaccard(candidates, qSet, synonyms, stopwords, jaccardThreshold)?.let { key ->
                    coreTemplates[key]?.let { if (it.isNotEmpty()) return it.random() }
                }
                findBestMatchByLevenshtein(candidates, qFiltered)?.let { key ->
                    coreTemplates[key]?.let { if (it.isNotEmpty()) return it.random() }
                }
            }
        } catch (e: Exception) {
            Log.e("ChatEngine", "Error searching in core files", e)
        }
        return null
    }

    private fun getJaccardThreshold(query: String): Double {
        return when {
            query.length <= 10 -> 0.3
            query.length <= 20 -> 0.4
            else -> 0.75
        }
    }

    private fun computeTokenWeights() {
        tokenWeights.clear()
        val tokenCounts = HashMap<String, Int>()
        var totalTokens = 0
        for (key in templatesMap.keys) {
            val tokens = filterStopwordsAndMapSynonyms(key).first
            for (token in tokens) {
                tokenCounts[token] = tokenCounts.getOrDefault(token, 0) + 1
                totalTokens++
            }
        }
        tokenCounts.forEach { (token, count) ->
            tokenWeights[token] = if (totalTokens == 0) 1.0 else log10(totalTokens.toDouble() / count).coerceAtLeast(1.0)
        }
    }

    private fun weightedJaccard(qSet: Set<String>, keyTokens: Set<String>): Double {
        val intersection = qSet.intersect(keyTokens)
        val union = qSet.union(keyTokens)
        val interWeight = intersection.sumOf { tokenWeights.getOrDefault(it, 1.0) }
        val unionWeight = union.sumOf { tokenWeights.getOrDefault(it, 1.0) }
        return if (unionWeight == 0.0) 0.0 else interWeight / unionWeight
    }

    private fun cacheResponse(qKey: String, response: String) {
        queryCache[qKey] = response
    }

    private fun detectContext(input: String): String? {
        val tokens = tokenize(input)
        val contextScores = HashMap<String, Int>()
        for ((keyword, value) in contextMap) {
            val keywordTokens = tokenize(keyword)
            val matches = tokens.count { it in keywordTokens }
            if (matches > 0) contextScores[value] = contextScores.getOrDefault(value, 0) + matches
        }
        return contextScores.maxByOrNull { it.value }?.key
    }

    private fun getDummyResponse(query: String): String {
        val lower = query.lowercase(Locale.ROOT)
        return when {
            lower.contains("привет") -> "Привет! Чем могу помочь?"
            lower.contains("как дела") -> "Всё отлично, а у тебя?"
            else -> "Не понял запрос. Попробуй другой вариант."
        }
    }

    private fun getFuzzyDistance(word: String): Int {
        return when {
            word.length <= 4 -> 1
            word.length <= 8 -> 2
            else -> 3
        }
    }

    private fun normalizeText(s: String): String {
        val lower = s.lowercase(Locale.getDefault())
        val cleaned = lower.replace(Regex("[^\\p{L}\\p{Nd}\\s]"), " ")
        return cleaned.replace(Regex("\\s+"), " ").trim()
    }

    private fun tokenize(s: String): List<String> {
        return s.split(Regex("\\s+")).map { it.trim() }.filter { it.isNotEmpty() }
    }

    private fun loadSynonymsAndStopwords() {
        synonymsMap.clear()
        stopwords.clear()
        val uri = folderUri ?: return
        try {
            val dir = DocumentFile.fromTreeUri(context, uri) ?: return

            dir.findFile("synonims.txt")?.uri?.let { fileUri ->
                context.contentResolver.openInputStream(fileUri)?.bufferedReader()?.useLines { lines ->
                    lines.forEach { raw ->
                        var line = raw.trim().removeSurrounding("*")
                        val parts = line.split(";").map { normalizeText(it).trim() }.filter { it.isNotEmpty() }
                        if (parts.isNotEmpty()) {
                            val canonical = parts.last()
                            parts.forEach { synonymsMap[it] = canonical }
                        }
                    }
                }
            }

            dir.findFile("stopwords.txt")?.uri?.let { fileUri ->
                context.contentResolver.openInputStream(fileUri)?.bufferedReader()?.use { reader ->
                    val all = reader.readText()
                    if (all.isNotEmpty()) {
                        all.split("^").map { normalizeText(it).trim() }.filter { it.isNotEmpty() }.forEach { stopwords.add(it) }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e("ChatEngine", "Error loading synonyms/stopwords", e)
        }
    }
    
    private fun filterStopwordsAndMapSynonyms(input: String, customSynonyms: Map<String, String>? = null, customStopwords: Set<String>? = null): Pair<List<String>, String> {
        val currentSynonyms = customSynonyms ?: synonymsMap
        val currentStopwords = customStopwords ?: stopwords
        val toks = tokenize(input)
        val mapped = toks.map { tok ->
            val n = normalizeText(tok)
            currentSynonyms[n] ?: n
        }.filter { it.isNotEmpty() && !currentStopwords.contains(it) }
        return Pair(mapped, mapped.joinToString(" "))
    }


    private fun rebuildInvertedIndex() {
        invertedIndex.clear()
        val tempIndex = HashMap<String, MutableList<String>>()
        templatesMap.forEach { (key, _) ->
            val toks = filterStopwordsAndMapSynonyms(key).first.filter { it.length >= MIN_TOKEN_LENGTH || keywordResponses.containsKey(it) }
            toks.forEach { t ->
                val list = tempIndex.getOrPut(t) { mutableListOf() }
                if (!list.contains(key)) list.add(key)
                if (list.size > MAX_TOKENS_PER_INDEX) {
                    list.sortByDescending { templatesMap[it]?.size ?: 0 }
                    list.subList(MAX_TOKENS_PER_INDEX, list.size).clear()
                }
            }
        }
        invertedIndex.putAll(tempIndex)
        trimTemplatesMap()
    }

    private fun trimTemplatesMap() {
        if (templatesMap.size > MAX_TEMPLATES_SIZE) {
            val leastUsed = templatesMap.keys.sortedBy { queryCountMap.getOrDefault(it, 0) }.take(templatesMap.size - MAX_TEMPLATES_SIZE)
            leastUsed.forEach { templatesMap.remove(it) }
        }
    }

    private fun levenshtein(s: String, t: String): Int {
        if (s == t) return 0
        val n = s.length
        val m = t.length
        if (n == 0) return m
        if (m == 0) return n

        var prev = IntArray(m + 1) { it }
        var curr = IntArray(m + 1)

        for (i in 1..n) {
            curr[0] = i
            for (j in 1..m) {
                val cost = if (s[i - 1] == t[j - 1]) 0 else 1
                curr[j] = minOf(
                    prev[j] + 1,          // deletion
                    curr[j - 1] + 1,      // insertion
                    prev[j - 1] + cost    // substitution
                )
            }
            prev = curr.clone()
        }
        return prev[m]
    }

    private fun loadTemplatesFromFile(filename: String) {
        templatesMap.clear()
        keywordResponses.clear()
        mascotList.clear()
        if (filename == "base.txt") contextMap.clear()

        currentMascotName = "Racky"
        currentMascotIcon = "raccoon_icon.png"
        currentThemeColor = "#00FF00"
        currentThemeBackground = "#000000"

        loadSynonymsAndStopwords()

        val uri = folderUri ?: run { loadFallbackTemplates(); return }
        try {
            val dir = DocumentFile.fromTreeUri(context, uri) ?: run { loadFallbackTemplates(); return }
            val file = dir.findFile(filename)
            if (file == null || !file.exists()) {
                loadFallbackTemplates()
                return
            }

            context.contentResolver.openInputStream(file.uri)?.bufferedReader()?.useLines { lines ->
                lines.forEach { raw ->
                    val l = raw.trim()
                    if (l.isEmpty()) return@forEach
                    if (filename == "base.txt" && l.startsWith(":") && l.endsWith(":")) {
                        val parts = l.substring(1, l.length - 1).split("=", limit = 2)
                        if (parts.size == 2) contextMap[parts[0].trim().lowercase(Locale.ROOT)] = parts[1].trim()
                    } else if (l.startsWith("-")) {
                        val parts = l.substring(1).split("=", limit = 2)
                        if (parts.size == 2) {
                            val keyword = parts[0].trim().lowercase(Locale.ROOT)
                            val responses = parts[1].split("|").mapNotNull { it.trim().takeIf(String::isNotEmpty) }.toMutableList()
                            if (keyword.isNotEmpty() && responses.isNotEmpty()) keywordResponses[keyword] = responses
                        }
                    } else if (l.contains("=")) {
                        val parts = l.split("=", limit = 2)
                        if (parts.size == 2) {
                            val trigger = normalizeText(parts[0].trim())
                            val triggerFiltered = filterStopwordsAndMapSynonyms(trigger).second
                            val responses = parts[1].split("|").mapNotNull { it.trim().takeIf(String::isNotEmpty) }.toMutableList()
                            if (triggerFiltered.isNotEmpty() && responses.isNotEmpty()) templatesMap[triggerFiltered] = responses
                        }
                    }
                }
            }

            // Load metadata
            dir.findFile(filename.replace(".txt", "_metadata.txt"))?.uri?.let { metadataUri ->
                context.contentResolver.openInputStream(metadataUri)?.bufferedReader()?.useLines { lines ->
                    lines.forEach { raw ->
                        val line = raw.trim()
                        when {
                            line.startsWith("mascot_name=") -> currentMascotName = line.substringAfter("=")
                            line.startsWith("mascot_icon=") -> currentMascotIcon = line.substringAfter("=")
                            line.startsWith("theme_color=") -> currentThemeColor = line.substringAfter("=")
                            line.startsWith("theme_background=") -> currentThemeBackground = line.substringAfter("=")
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e("ChatEngine", "Error loading templates from $filename", e)
            loadFallbackTemplates()
        }
    }

    private fun loadFallbackTemplates() {
        templatesMap.clear()
        contextMap.clear()
        keywordResponses.clear()
        mascotList.clear()
        templatesMap[normalizeText("привет")] = mutableListOf("Привет! Чем могу помочь?", "Здравствуй!")
        templatesMap[normalizeText("как дела")] = mutableListOf("Всё отлично, а у тебя?", "Нормально, как дела?")
        keywordResponses["спасибо"] = mutableListOf("Рад, что помог!", "Всегда пожалуйста!")
    }
}
