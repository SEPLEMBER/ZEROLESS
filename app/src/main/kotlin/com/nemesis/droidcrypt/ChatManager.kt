package com.nemesis.droidcrypt

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.*
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.min

class ChatManager(private val context: Context) {

    companion object {
        const val MAX_CONTEXT_SWITCH = 6
        const val MAX_MESSAGES = 250
        const val CANDIDATE_TOKEN_THRESHOLD = 2
        const val MAX_SUBQUERY_RESPONSES = 3
        const val SUBQUERY_RESPONSE_DELAY = 1500L
        const val MAX_CANDIDATES_FOR_LEV = 8
        const val JACCARD_THRESHOLD = 0.75
        const val SEND_DEBOUNCE_MS = 400L
        const val IDLE_TIMEOUT_MS = 30000L
        const val MAX_CACHE_SIZE = 100
        const val SPAM_WINDOW_MS = 60000L
        const val MAX_TOKENS_PER_INDEX = 50
        const val MIN_TOKEN_LENGTH = 3
        const val MAX_TEMPLATES_SIZE = 5000
    }

    fun getFuzzyDistance(word: String): Int {
        return when {
            word.length <= 4 -> 1
            word.length <= 8 -> 2
            else -> 3
        }
    }

    var folderUri: Uri? = null

    val templatesMap = HashMap<String, MutableList<String>>()
    val contextMap = HashMap<String, String>()
    val keywordResponses = HashMap<String, MutableList<String>>()
    private val antiSpamResponses = mutableListOf<String>()
    private val mascotList = mutableListOf<Map<String, String>>()
    private val invertedIndex = HashMap<String, MutableList<String>>()
    private val synonymsMap = HashMap<String, String>()
    private val stopwords = HashSet<String>()
    var currentMascotName = "Racky"
    var currentMascotIcon = "raccoon_icon.png"
    var currentThemeColor = "#00FF00"
    var currentThemeBackground = "#000000"
    var currentContext = "base.txt"
    private val fallback = arrayOf("Привет", "Как дела?", "Расскажи о себе", "Выход")
    private var userActivityCount = 0
    private val queryCountMap = HashMap<String, Int>()
    private val queryTimestamps = HashMap<String, MutableList<Long>>()
    private val queryCache = object : LinkedHashMap<String, String>(MAX_CACHE_SIZE, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?): Boolean {
            return size > MAX_CACHE_SIZE
        }
    }
    private val tokenWeights = HashMap<String, Double>()

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
    }

    fun loadSynonymsAndStopwords() {
        synonymsMap.clear()
        stopwords.clear()
        val uri = folderUri ?: return
        try {
            val dir = DocumentFile.fromTreeUri(context, uri) ?: return
            val synFile = dir.findFile("synonims.txt")
            if (synFile != null && synFile.exists()) {
                context.contentResolver.openInputStream(synFile.uri)?.bufferedReader()?.use { reader ->
                    reader.forEachLine { raw ->
                        var l = raw.trim()
                        if (l.isEmpty()) return@forEachLine
                        if (l.startsWith("*") && l.endsWith("*") && l.length > 1) {
                            l = l.substring(1, l.length - 1)
                        }
                        val parts = l.split(";").map { normalizeText(it).trim() }.filter { it.isNotEmpty() }
                        if (parts.isEmpty()) return@forEachLine
                        val canonical = parts.last()
                        for (p in parts) {
                            synonymsMap[p] = canonical
                        }
                    }
                }
            }
            val stopFile = dir.findFile("stopwords.txt")
            if (stopFile != null && stopFile.exists()) {
                context.contentResolver.openInputStream(stopFile.uri)?.bufferedReader()?.use { reader ->
                    val all = reader.readText()
                    if (all.isNotEmpty()) {
                        val parts = all.split("^").map { normalizeText(it).trim() }.filter { it.isNotEmpty() }
                        for (p in parts) stopwords.add(p)
                    }
                }
            }
        } catch (e: Exception) {
            Log.e("ChatManager", "Error loading synonyms/stopwords", e)
        }
    }

    fun processUserQuery(userInput: String, scope: CoroutineScope, callback: ChatCallback) {
        val qOrigRaw = userInput.trim()
        val qOrig = normalizeText(qOrigRaw)
        val (qTokensFiltered, qFiltered) = filterStopwordsAndMapSynonyms(qOrig)
        val qKeyForCount = qFiltered

        if (qFiltered.isEmpty()) return

        // Кэш: проверка на повторный запрос
        queryCache[qKeyForCount]?.let { cachedResponse ->
            callback.addChatMessage(currentMascotName, cachedResponse)
            callback.startIdleTimer()
            return
        }

        userActivityCount++

        callback.addChatMessage("You", userInput)
        callback.showTypingIndicator()

        // Антиспам с временным окном
        val now = System.currentTimeMillis()
        val timestamps = queryTimestamps.getOrPut(qKeyForCount) { mutableListOf() }
        timestamps.add(now)
        timestamps.removeAll { it < now - SPAM_WINDOW_MS }
        if (timestamps.size >= 5) {
            val spamResp = antiSpamResponses.random()
            callback.addChatMessage(currentMascotName, spamResp)
            callback.startIdleTimer()
            return
        }

        val templatesSnapshot = HashMap(templatesMap)
        val invertedIndexSnapshot = HashMap<String, MutableList<String>>()
        for ((k, v) in invertedIndex) invertedIndexSnapshot[k] = ArrayList(v)
        val synonymsSnapshot = HashMap(synonymsMap)
        val stopwordsSnapshot = HashSet(stopwords)
        val keywordResponsesSnapshot = HashMap<String, MutableList<String>>()
        for ((k, v) in keywordResponses) keywordResponsesSnapshot[k] = ArrayList(v)
        val contextMapSnapshot = HashMap(contextMap)

        scope.launch(Dispatchers.Default) {
            data class ResponseResult(val text: String? = null, val wantsContextSwitch: String? = null)

            fun tokenizeLocal(s: String): List<String> {
                if (s.isBlank()) return emptyList()
                return s.split(Regex("\\s+")).map { it.trim() }.filter { it.isNotEmpty() }
            }

            fun normalizeLocal(s: String): String {
                val lower = s.lowercase(Locale.getDefault())
                val cleaned = lower.replace(Regex("[^\\p{L}\\p{Nd}\\s]"), " ")
                val collapsed = cleaned.replace(Regex("\\s+"), " ").trim()
                return collapsed
            }

            fun filterStopwordsAndMapSynonymsLocal(input: String): Pair<List<String>, String> {
                val toks = tokenizeLocal(input)
                val mapped = toks.map { tok ->
                    val n = normalizeLocal(tok)
                    val s = synonymsSnapshot[n] ?: n
                    s
                }.filter { it.isNotEmpty() && !stopwordsSnapshot.contains(it) }
                val joined = mapped.joinToString(" ")
                return Pair(mapped, joined)
            }

            fun parseTemplatesFromFile(filename: String): Pair<HashMap<String, MutableList<String>>, HashMap<String, MutableList<String>>> {
                val localTemplates = HashMap<String, MutableList<String>>()
                val localKeywords = HashMap<String, MutableList<String>>()
                val uriLocal = folderUri ?: return Pair(localTemplates, localKeywords)
                try {
                    val dir = DocumentFile.fromTreeUri(context, uriLocal) ?: return Pair(localTemplates, localKeywords)
                    val file = dir.findFile(filename) ?: return Pair(localTemplates, localKeywords)
                    if (!file.exists()) return Pair(localTemplates, localKeywords)
                    context.contentResolver.openInputStream(file.uri)?.bufferedReader()?.use { reader ->
                        reader.forEachLine { raw ->
                            val l = raw.trim()
                            if (l.isEmpty()) return@forEachLine
                            if (l.startsWith("-")) {
                                val keywordLine = l.substring(1)
                                if (keywordLine.contains("=")) {
                                    val parts = keywordLine.split("=", limit = 2)
                                    if (parts.size == 2) {
                                        val keyword = parts[0].trim().lowercase(Locale.ROOT)
                                        val responses = parts[1].split("|")
                                        val responseList = responses.mapNotNull { it.trim().takeIf { s -> s.isNotEmpty() } }.toMutableList()
                                        if (keyword.isNotEmpty() && responseList.isNotEmpty()) localKeywords[keyword] = responseList
                                    }
                                }
                                return@forEachLine
                            }
                            if (!l.contains("=")) return@forEachLine
                            val parts = l.split("=", limit = 2)
                            if (parts.size == 2) {
                                val triggerRaw = parts[0].trim()
                                val trigger = normalizeLocal(triggerRaw)
                                val triggerFiltered = filterStopwordsAndMapSynonymsLocal(trigger).second
                                val responses = parts[1].split("|")
                                val responseList = responses.mapNotNull { it.trim().takeIf { s -> s.isNotEmpty() } }.toMutableList()
                                if (triggerFiltered.isNotEmpty() && responseList.isNotEmpty()) localTemplates[triggerFiltered] = responseList
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.e("ChatManager", "Error parsing templates from $filename", e)
                }
                return Pair(localTemplates, localKeywords)
            }

            fun searchInCoreFiles(qFiltered: String, qTokens: List<String>, qSet: Set<String>, jaccardThreshold: Double): String? {
                val uriLocal = folderUri ?: return null
                try {
                    val dir = DocumentFile.fromTreeUri(context, uriLocal) ?: return null

                    fun resolvePotentialFileResponse(respRaw: String): String {
                        val respTrim = respRaw.trim()
                        val resp = respTrim.removeSuffix(":").trim()
                        if (resp.contains(".txt", ignoreCase = true)) {
                            val filename = resp.substringAfterLast('/').trim()
                            if (filename.isNotEmpty()) {
                                val (tpls, keywords) = parseTemplatesFromFile(filename)
                                val allResponses = mutableListOf<String>()
                                for (lst in tpls.values) allResponses.addAll(lst)
                                for (lst in keywords.values) allResponses.addAll(lst)
                                if (allResponses.isNotEmpty()) return allResponses.random()
                                return respTrim
                            }
                        }
                        return respRaw
                    }

                    for (i in 1..9) {
                        val filename = "core$i.txt"
                        val file = dir.findFile(filename) ?: continue
                        if (!file.exists()) continue

                        val (coreTemplates, coreKeywords) = parseTemplatesFromFile(filename)
                        if (coreTemplates.isEmpty() && coreKeywords.isEmpty()) continue

                        coreTemplates[qFiltered]?.let { possible ->
                            if (possible.isNotEmpty()) {
                                val chosen = possible.random()
                                return resolvePotentialFileResponse(chosen)
                            }
                        }

                        for ((keyword, responses) in coreKeywords) {
                            if (qFiltered.contains(keyword) && responses.isNotEmpty()) {
                                val chosen = responses.random()
                                return resolvePotentialFileResponse(chosen)
                            }
                        }

                        var bestByJaccard: String? = null
                        var bestJaccard = 0.0
                        for (key in coreTemplates.keys) {
                            val keyTokens = filterStopwordsAndMapSynonymsLocal(key).first.toSet()
                            if (keyTokens.isEmpty()) continue
                            val weightedJ = weightedJaccard(qSet, keyTokens)
                            if (weightedJ > bestJaccard) {
                                bestJaccard = weightedJ
                                bestByJaccard = key
                            }
                        }
                        if (bestByJaccard != null && bestJaccard >= jaccardThreshold) {
                            val possible = coreTemplates[bestByJaccard]
                            if (!possible.isNullOrEmpty()) {
                                val chosen = possible.random()
                                return resolvePotentialFileResponse(chosen)
                            }
                        }

                        var bestKey: String? = null
                        var bestDist = Int.MAX_VALUE
                        val candidates = coreTemplates.keys.filter { abs(it.length - qFiltered.length) <= getFuzzyDistance(qFiltered) }
                            .take(MAX_CANDIDATES_FOR_LEV)

                        for (key in candidates) {
                            val maxDist = getFuzzyDistance(qFiltered)
                            if (abs(key.length - qFiltered.length) > maxDist + 1) continue
                            val d = levenshtein(qFiltered, key, qFiltered)
                            if (d < bestDist) {
                                bestDist = d
                                bestKey = key
                            }
                            if (bestDist == 0) break
                        }

                        if (bestKey != null && bestDist <= getFuzzyDistance(qFiltered)) {
                            val possible = coreTemplates[bestKey]
                            if (!possible.isNullOrEmpty()) {
                                val chosen = possible.random()
                                return resolvePotentialFileResponse(chosen)
                            }
                        }
                    }

                    return null
                } catch (e: Exception) {
                    Log.e("ChatManager", "Error searching in core files", e)
                    return null
                }
            }

            var answered = false
            val subqueryResponses = mutableListOf<String>()
            val processedSubqueries = mutableSetOf<String>()

            templatesSnapshot[qFiltered]?.let { possible ->
                if (possible.isNotEmpty()) {
                    subqueryResponses.add(possible.random())
                    answered = true
                    processedSubqueries.add(qFiltered)
                }
            }

            if (subqueryResponses.size < MAX_SUBQUERY_RESPONSES) {
                val tokens = if (qTokensFiltered.isNotEmpty()) qTokensFiltered else tokenizeLocal(qFiltered)
                for (token in tokens) {
                    if (subqueryResponses.size >= MAX_SUBQUERY_RESPONSES) break
                    if (processedSubqueries.contains(token) || token.length < 2) continue
                    templatesSnapshot[token]?.let { possible ->
                        if (possible.isNotEmpty()) {
                            subqueryResponses.add(possible.random())
                            processedSubqueries.add(token)
                        }
                    }
                    if (subqueryResponses.size < MAX_SUBQUERY_RESPONSES) {
                        keywordResponsesSnapshot[token]?.let { possible ->
                            if (possible.isNotEmpty()) {
                                subqueryResponses.add(possible.random())
                                processedSubqueries.add(token)
                            }
                        }
                    }
                }
                if (subqueryResponses.size < MAX_SUBQUERY_RESPONSES && tokens.size > 1) {
                    for (i in 0 until tokens.size - 1) {
                        if (subqueryResponses.size >= MAX_SUBQUERY_RESPONSES) break
                        val twoTokens = "${tokens[i]} ${tokens[i + 1]}"
                        if (processedSubqueries.contains(twoTokens)) continue
                        templatesSnapshot[twoTokens]?.let { possible ->
                            if (possible.isNotEmpty()) {
                                subqueryResponses.add(possible.random())
                                processedSubqueries.add(twoTokens)
                            }
                        }
                    }
                }
            }

            if (subqueryResponses.isNotEmpty()) {
                val combined = subqueryResponses.joinToString(". ")
                withContext(Dispatchers.Main) {
                    callback.addChatMessage(currentMascotName, combined)
                    callback.startIdleTimer()
                }
                return@launch
            }

            for ((keyword, responses) in keywordResponsesSnapshot) {
                if (qFiltered.contains(keyword) && responses.isNotEmpty()) {
                    withContext(Dispatchers.Main) {
                        callback.addChatMessage(currentMascotName, responses.random())
                        callback.startIdleTimer()
                    }
                    return@launch
                }
            }

            val qTokens = if (qTokensFiltered.isNotEmpty()) qTokensFiltered else tokenizeLocal(qFiltered)
            val candidateCounts = HashMap<String, Int>()
            for (tok in qTokens) {
                invertedIndexSnapshot[tok]?.forEach { trig ->
                    candidateCounts[trig] = candidateCounts.getOrDefault(trig, 0) + 1
                }
            }

            val candidates: List<String> = if (candidateCounts.isNotEmpty()) {
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

            var bestByJaccard: String? = null
            var bestJaccard = 0.0
            val qSet = qTokens.toSet()
            val jaccardThreshold = getJaccardThreshold(qFiltered)
            for (key in candidates) {
                val keyTokens = filterStopwordsAndMapSynonymsLocal(key).first.toSet()
                if (keyTokens.isEmpty()) continue
                val weightedJ = weightedJaccard(qSet, keyTokens)
                if (weightedJ > bestJaccard) {
                    bestJaccard = weightedJ
                    bestByJaccard = key
                }
            }
            if (bestByJaccard != null && bestJaccard >= jaccardThreshold) {
                val possible = templatesSnapshot[bestByJaccard]
                if (!possible.isNullOrEmpty()) {
                    val response = possible.random()
                    withContext(Dispatchers.Main) {
                        callback.addChatMessage(currentMascotName, response)
                        callback.startIdleTimer()
                        cacheResponse(qKeyForCount, response)
                    }
                    return@launch
                }
            }

            var bestKey: String? = null
            var bestDist = Int.MAX_VALUE
            for (key in candidates) {
                val maxDist = getFuzzyDistance(qFiltered)
                if (abs(key.length - qFiltered.length) > maxDist + 1) continue
                val d = levenshtein(qFiltered, key, qFiltered)
                if (d < bestDist) {
                    bestDist = d
                    bestKey = key
                }
                if (bestDist == 0) break
            }
            val maxDistLocal = getFuzzyDistance(qFiltered)
            if (bestKey != null && bestDist <= maxDistLocal) {
                val possible = templatesSnapshot[bestKey]
                if (!possible.isNullOrEmpty()) {
                    val response = possible.random()
                    withContext(Dispatchers.Main) {
                        callback.addChatMessage(currentMascotName, response)
                        callback.startIdleTimer()
                        cacheResponse(qKeyForCount, response)
                    }
                    return@launch
                }
            }

            val lower = normalizeLocal(qFiltered)
            val detectedContext = detectContext(lower)
            if (detectedContext != null) {
                withContext(Dispatchers.Main) {
                    if (detectedContext != currentContext) {
                        currentContext = detectedContext
                        loadTemplatesFromFile(currentContext)
                        rebuildInvertedIndex()
                        computeTokenWeights()
                        callback.updateAutoComplete()
                        callback.updateUI(currentMascotName, currentMascotIcon, currentThemeColor, currentThemeBackground)
                    }
                }
                val (localTemplates, localKeywords) = parseTemplatesFromFile(detectedContext)
                localTemplates[qFiltered]?.let { possible ->
                    if (possible.isNotEmpty()) {
                        val response = possible.random()
                        withContext(Dispatchers.Main) {
                            callback.addChatMessage(currentMascotName, response)
                            callback.startIdleTimer()
                            cacheResponse(qKeyForCount, response)
                        }
                        return@launch
                    }
                }
                val localInverted = HashMap<String, MutableList<String>>()
                for ((k, v) in localTemplates) {
                    val toks = filterStopwordsAndMapSynonymsLocal(k).first
                    for (t in toks) {
                        val list = localInverted.getOrPut(t) { mutableListOf() }
                        if (!list.contains(k)) list.add(k)
                    }
                }
                val localCandidateCounts = HashMap<String, Int>()
                val tokensLocal = qTokens
                for (tok in tokensLocal) {
                    localInverted[tok]?.forEach { trig ->
                        localCandidateCounts[trig] = localCandidateCounts.getOrDefault(trig, 0) + 1
                    }
                }
                val localCandidates: List<String> = if (localCandidateCounts.isNotEmpty()) {
                    localCandidateCounts.entries
                        .filter { it.value >= CANDIDATE_TOKEN_THRESHOLD }
                        .sortedByDescending { it.value }
                        .map { it.key }
                        .take(MAX_CANDIDATES_FOR_LEV)
                } else {
                    val md = getFuzzyDistance(qFiltered)
                    localTemplates.keys.filter { abs(it.length - qFiltered.length) <= md }
                        .take(MAX_CANDIDATES_FOR_LEV)
                }
                var bestLocal: String? = null
                var bestLocalJ = 0.0
                val qSetLocal = tokensLocal.toSet()
                for (key in localCandidates) {
                    val keyTokens = filterStopwordsAndMapSynonymsLocal(key).first.toSet()
                    if (keyTokens.isEmpty()) continue
                    val weightedJ = weightedJaccard(qSetLocal, keyTokens)
                    if (weightedJ > bestLocalJ) {
                        bestLocalJ = weightedJ
                        bestLocal = key
                    }
                }
                if (bestLocal != null && bestLocalJ >= jaccardThreshold) {
                    val possible = localTemplates[bestLocal]
                    if (!possible.isNullOrEmpty()) {
                        val response = possible.random()
                        withContext(Dispatchers.Main) {
                            callback.addChatMessage(currentMascotName, response)
                            callback.startIdleTimer()
                            cacheResponse(qKeyForCount, response)
                        }
                        return@launch
                    }
                }
                var bestLocalKey: String? = null
                var bestLocalDist = Int.MAX_VALUE
                for (key in localCandidates) {
                    val maxD = getFuzzyDistance(qFiltered)
                    if (abs(key.length - qFiltered.length) > maxD + 1) continue
                    val d = levenshtein(qFiltered, key, qFiltered)
                    if (d < bestLocalDist) {
                        bestLocalDist = d
                        bestLocalKey = key
                    }
                    if (bestLocalDist == 0) break
                }
                if (bestLocalKey != null && bestLocalDist <= getFuzzyDistance(qFiltered)) {
                    val possible = localTemplates[bestLocalKey]
                    if (!possible.isNullOrEmpty()) {
                        val response = possible.random()
                        withContext(Dispatchers.Main) {
                            callback.addChatMessage(currentMascotName, response)
                            callback.startIdleTimer()
                            cacheResponse(qKeyForCount, response)
                        }
                        return@launch
                    }
                }

                val coreResult = searchInCoreFiles(qFiltered, tokensLocal, qSetLocal, jaccardThreshold)
                if (coreResult != null) {
                    withContext(Dispatchers.Main) {
                        callback.addChatMessage(currentMascotName, coreResult)
                        callback.startIdleTimer()
                        cacheResponse(qKeyForCount, coreResult)
                    }
                    return@launch
                }

                val dummy = getDummyResponse(qOrig)
                withContext(Dispatchers.Main) {
                    callback.addChatMessage(currentMascotName, dummy)
                    callback.startIdleTimer()
                    cacheResponse(qKeyForCount, dummy)
                }
                return@launch
            }

            val coreResult = searchInCoreFiles(qFiltered, qTokens, qSet, jaccardThreshold)
            if (coreResult != null) {
                withContext(Dispatchers.Main) {
                    callback.addChatMessage(currentMascotName, coreResult)
                    callback.startIdleTimer()
                    cacheResponse(qKeyForCount, coreResult)
                }
                return@launch
            }

            val dummy = getDummyResponse(qOrig)
            withContext(Dispatchers.Main) {
                callback.addChatMessage(currentMascotName, dummy)
                callback.startIdleTimer()
                cacheResponse(qKeyForCount, dummy)
            }
        }
    }

    fun getJaccardThreshold(query: String): Double {
        return when {
            query.length <= 10 -> 0.3
            query.length <= 20 -> 0.4
            else -> JACCARD_THRESHOLD
        }
    }

    fun computeTokenWeights() {
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

    fun weightedJaccard(qSet: Set<String>, keyTokens: Set<String>): Double {
        val intersection = qSet.intersect(keyTokens)
        val union = qSet.union(keyTokens)
        val interWeight = intersection.sumOf { tokenWeights.getOrDefault(it, 1.0) }
        val unionWeight = union.sumOf { tokenWeights.getOrDefault(it, 1.0) }
        return if (unionWeight == 0.0) 0.0 else interWeight / unionWeight
    }

    fun cacheResponse(qKey: String, response: String) {
        queryCache[qKey] = response
    }

    fun detectContext(input: String): String? {
        val tokens = tokenize(input)
        val contextScores = HashMap<String, Int>()
        for ((keyword, value) in contextMap) {
            val keywordTokens = tokenize(keyword)
            val matches = tokens.count { it in keywordTokens }
            if (matches > 0) contextScores[value] = contextScores.getOrDefault(value, 0) + matches
        }
        return contextScores.maxByOrNull { it.value }?.key
    }

    fun getDummyResponse(query: String): String {
        val lower = query.lowercase(Locale.ROOT)
        return when {
            lower.contains("привет") -> "Привет! Чем могу помочь?"
            lower.contains("как дела") -> "Всё отлично, а у тебя?"
            else -> "Не понял запрос. Попробуй другой вариант."
        }
    }

    fun normalizeText(s: String): String {
        val lower = s.lowercase(Locale.getDefault())
        val cleaned = lower.replace(Regex("[^\\p{L}\\p{Nd}\\s]"), " ")
        val collapsed = cleaned.replace(Regex("\\s+"), " ").trim()
        return collapsed
    }

    fun tokenize(s: String): List<String> {
        if (s.isBlank()) return emptyList()
        return s.split(Regex("\\s+")).map { it.trim() }.filter { it.isNotEmpty() }
    }

    fun filterStopwordsAndMapSynonyms(input: String): Pair<List<String>, String> {
        val toks = tokenize(input)
        val mapped = toks.map { tok ->
            val n = normalizeText(tok)
            val s = synonymsMap[n] ?: n
            s
        }.filter { it.isNotEmpty() && !stopwords.contains(it) }
        val joined = mapped.joinToString(" ")
        return Pair(mapped, joined)
    }

    fun rebuildInvertedIndex() {
        invertedIndex.clear()
        val tempIndex = HashMap<String, MutableList<String>>()
        for (key in templatesMap.keys) {
            val toks = filterStopwordsAndMapSynonyms(key).first.filter { it.length >= MIN_TOKEN_LENGTH || keywordResponses.containsKey(it) }
            for (t in toks) {
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
            Log.d("ChatManager", "Trimmed templatesMap to $MAX_TEMPLATES_SIZE entries")
        }
    }

    fun levenshtein(s: String, t: String, qFiltered: String): Int {
        if (s == t) return 0
        val n = s.length
        val m = t.length
        if (n == 0) return m
        if (m == 0) return n
        val maxDist = getFuzzyDistance(qFiltered)
        if (abs(n - m) > maxDist + 2) return Int.MAX_VALUE / 2
        val prev = IntArray(m + 1) { it }
        val curr = IntArray(m + 1)
        for (i in 1..n) {
            curr[0] = i
            var minInRow = curr[0]
            val si = s[i - 1]
            for (j in 1..m) {
                val cost = if (si == t[j - 1]) 0 else 1
                val deletion = prev[j] + 1
                val insertion = curr[j - 1] + 1
                val substitution = prev[j - 1] + cost
                curr[j] = min(min(deletion, insertion), substitution)
                if (curr[j] < minInRow) minInRow = curr[j]
            }
            val maxDistRow = getFuzzyDistance(qFiltered)
            if (minInRow > maxDistRow + 2) return Int.MAX_VALUE / 2
            for (k in 0..m) prev[k] = curr[k]
        }
        return prev[m]
    }

    fun loadTemplatesFromFile(filename: String) {
        templatesMap.clear()
        keywordResponses.clear()
        mascotList.clear()
        if (filename == "base.txt") {
            contextMap.clear()
        }
        currentMascotName = "Racky"
        currentMascotIcon = "raccoon_icon.png"
        currentThemeColor = "#00FF00"
        currentThemeBackground = "#000000"
        if (folderUri == null) {
            loadFallbackTemplates()
            return
        }
        try {
            val dir = DocumentFile.fromTreeUri(context, folderUri!!) ?: run {
                loadFallbackTemplates()
                return
            }
            val file = dir.findFile(filename)
            if (file == null || !file.exists()) {
                loadFallbackTemplates()
                return
            }
            context.contentResolver.openInputStream(file.uri)?.bufferedReader()?.use { reader ->
                reader.forEachLine { raw ->
                    val l = raw.trim()
                    if (l.isEmpty()) return@forEachLine
                    if (filename == "base.txt" && l.startsWith(":") && l.endsWith(":")) {
                        val contextLine = l.substring(1, l.length - 1)
                        if (contextLine.contains("=")) {
                            val parts = contextLine.split("=", limit = 2)
                            if (parts.size == 2) {
                                val keyword = parts[0].trim().lowercase(Locale.ROOT)
                                val contextFile = parts[1].trim()
                                if (keyword.isNotEmpty() && contextFile.isNotEmpty()) contextMap[keyword] = contextFile
                            }
                        }
                        return@forEachLine
                    }
                    if (l.startsWith("-")) {
                        val keywordLine = l.substring(1)
                        if (keywordLine.contains("=")) {
                            val parts = keywordLine.split("=", limit = 2)
                            if (parts.size == 2) {
                                val keyword = parts[0].trim().lowercase(Locale.ROOT)
                                val responses = parts[1].split("|")
                                val responseList = responses.mapNotNull { it.trim().takeIf { s -> s.isNotEmpty() } }.toMutableList()
                                if (keyword.isNotEmpty() && responseList.isNotEmpty()) keywordResponses[keyword] = responseList
                            }
                        }
                        return@forEachLine
                    }
                    if (!l.contains("=")) return@forEachLine
                    val parts = l.split("=", limit = 2)
                    if (parts.size == 2) {
                        val triggerRaw = parts[0].trim()
                        val trigger = normalizeText(triggerRaw)
                        val triggerFiltered = filterStopwordsAndMapSynonyms(trigger).second
                        val responses = parts[1].split("|")
                        val responseList = responses.mapNotNull { it.trim().takeIf { s -> s.isNotEmpty() } }.toMutableList()
                        if (triggerFiltered.isNotEmpty() && responseList.isNotEmpty()) templatesMap[triggerFiltered] = responseList
                    }
                }
            }
            val metadataFilename = filename.replace(".txt", "_metadata.txt")
            val metadataFile = dir.findFile(metadataFilename)
            if (metadataFile != null && metadataFile.exists()) {
                context.contentResolver.openInputStream(metadataFile.uri)?.bufferedReader()?.use { reader ->
                    reader.forEachLine { raw ->
                        val line = raw.trim()
                        when {
                            line.startsWith("mascot_list=") -> {
                                val mascots = line.substring("mascot_list=".length).split("|")
                                for (mascot in mascots) {
                                    val parts = mascot.split(":")
                                    if (parts.size == 4) {
                                        val mascotData = mapOf(
                                            "name" to parts[0].trim(),
                                            "icon" to parts[1].trim(),
                                            "color" to parts[2].trim(),
                                            "background" to parts[3].trim()
                                        )
                                        mascotList.add(mascotData)
                                    }
                                }
                            }
                            line.startsWith("mascot_name=") -> currentMascotName = line.substring("mascot_name=".length).trim()
                            line.startsWith("mascot_icon=") -> currentMascotIcon = line.substring("mascot_icon=".length).trim()
                            line.startsWith("theme_color=") -> currentThemeColor = line.substring("theme_color=".length).trim()
                            line.startsWith("theme_background=") -> currentThemeBackground = line.substring("theme_background=".length).trim()
                        }
                    }
                }
            }
            if (filename == "base.txt" && mascotList.isNotEmpty()) {
                val selected = mascotList.random()
                selected["name"]?.let { currentMascotName = it }
                selected["icon"]?.let { currentMascotIcon = it }
                selected["color"]?.let { currentThemeColor = it }
                selected["background"]?.let { currentThemeBackground = it }
            }
        } catch (e: Exception) {
            Log.e("ChatManager", "Error loading templates from $filename", e)
            loadFallbackTemplates()
        }
    }

    fun loadFallbackTemplates() {
        templatesMap.clear()
        contextMap.clear()
        keywordResponses.clear()
        mascotList.clear()
        val t1 = normalizeText("привет")
        templatesMap[t1] = mutableListOf("Привет! Чем могу помочь?", "Здравствуй!")
        val t2 = normalizeText("как дела")
        templatesMap[t2] = mutableListOf("Всё отлично, а у тебя?", "Нормально, как дела?")
        keywordResponses["спасибо"] = mutableListOf("Рад, что помог!", "Всегда пожалуйста!")
    }

    fun getAutoCompleteSuggestions(): List<String> {
        val suggestions = mutableListOf<String>()
        suggestions.addAll(templatesMap.keys)
        for (s in fallback) {
            val low = normalizeText(s)
            if (!suggestions.contains(low)) suggestions.add(low)
        }
        return suggestions
    }

    fun clearCaches() {
        queryCountMap.clear()
        queryTimestamps.clear()
        queryCache.clear()
    }
}
