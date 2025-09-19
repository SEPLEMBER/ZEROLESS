package com.nemesis.droidcrypt

import kotlin.math.abs
import kotlin.math.min
import kotlin.math.log10
import kotlin.math.max
import kotlin.collections.HashMap
import java.util.Locale
import kotlin.math.roundToInt

class Engine(
    val templatesMap: MutableMap<String, MutableList<String>>,
    val synonymsMap: MutableMap<String, String>,
    val stopwords: MutableSet<String>
) {

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

        fun normalizeText(s: String): String {
            // Убираем BOM, NBSP, zero-width / format chars и остальное "мусорное"
            var str = s.replace("\uFEFF", "") // BOM
            str = str.replace('\u00A0', ' ') // NBSP -> space
            // Zero-width and formatting characters
            str = str.replace(Regex("[\\u200B-\\u200F\\u2060-\\u206F]"), "")
            val lower = str.lowercase(Locale.getDefault())
            // Убираем всё кроме букв/цифр/пробелов
            val cleaned = lower.replace(Regex("[^\\p{L}\\p{Nd}\\s]"), " ")
            val collapsed = cleaned.replace(Regex("\\s+"), " ").trim()
            return collapsed
        }

        fun tokenizeStatic(s: String): List<String> {
            if (s.isBlank()) return emptyList()
            return s.split(Regex("\\s+")).map { it.trim() }.filter { it.isNotEmpty() }
        }

        fun filterStopwordsAndMapSynonymsStatic(input: String, synonymsSnapshot: Map<String, String>, stopwordsSnapshot: Set<String>): Pair<List<String>, String> {
            val toks = tokenizeStatic(input)
            val mapped = toks.map { tok ->
                val n = normalizeText(tok)
                val s = synonymsSnapshot[n] ?: n
                s
            }.filter { it.isNotEmpty() && !stopwordsSnapshot.contains(it) }
            val joined = mapped.joinToString(" ")
            return Pair(mapped, joined)
        }
    }

    val tokenWeights: MutableMap<String, Double> = HashMap()

    fun filterStopwordsAndMapSynonyms(input: String): Pair<List<String>, String> {
        return filterStopwordsAndMapSynonymsStatic(input, synonymsMap, stopwords)
    }

    fun getFuzzyDistance(word: String): Int {
        return when {
            word.length <= 4 -> 1
            word.length <= 8 -> 2
            else -> 3
        }
    }

    /**
     * Levenshtein distance with early exits but using symmetric fuzzy threshold:
     * threshold is max(getFuzzyDistance(s), getFuzzyDistance(t))
     */
    fun levenshtein(s: String, t: String, qFiltered: String): Int {
        if (s == t) return 0
        val n = s.length
        val m = t.length
        if (n == 0) return m
        if (m == 0) return n
        val maxDist = max(getFuzzyDistance(qFiltered), getFuzzyDistance(t))
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
            val maxDistRow = max(getFuzzyDistance(qFiltered), getFuzzyDistance(t))
            if (minInRow > maxDistRow + 2) return Int.MAX_VALUE / 2
            for (k in 0..m) prev[k] = curr[k]
        }
        return prev[m]
    }

    fun getJaccardThreshold(query: String): Double {
        return when {
            query.length <= 10 -> 0.3
            query.length <= 20 -> 0.4
            else -> JACCARD_THRESHOLD
        }
    }

    fun weightedJaccard(qSet: Set<String>, keyTokens: Set<String>): Double {
        val intersection = qSet.intersect(keyTokens)
        val union = qSet.union(keyTokens)
        val interWeight = intersection.sumOf { tokenWeights.getOrDefault(it, 1.0) }
        val unionWeight = union.sumOf { tokenWeights.getOrDefault(it, 1.0) }
        return if (unionWeight == 0.0) 0.0 else interWeight / unionWeight
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
            val weight = if (totalTokens == 0) 1.0 else log10(totalTokens.toDouble() / count)
            tokenWeights[token] = max(0.1, weight)
        }
    }

    fun rebuildInvertedIndex(
        minTokenLength: Int = MIN_TOKEN_LENGTH,
        maxTokensPerIndex: Int = MAX_TOKENS_PER_INDEX
    ): MutableMap<String, MutableList<String>> {
        val invertedIndex = HashMap<String, MutableList<String>>()
        for (key in templatesMap.keys) {
            val toks = filterStopwordsAndMapSynonyms(key).first.filter { it.length >= minTokenLength }
            for (t in toks) {
                val list = invertedIndex.getOrPut(t) { mutableListOf() }
                if (!list.contains(key)) list.add(key)
                if (list.size > maxTokensPerIndex) {
                    list.sortByDescending { templatesMap[it]?.size ?: 0 }
                    list.subList(maxTokensPerIndex, list.size).clear()
                }
            }
        }
        return invertedIndex
    }

    fun trimTemplatesMap(maxTemplatesSize: Int = MAX_TEMPLATES_SIZE, queryCountMap: Map<String, Int>): List<String> {
        val removed = mutableListOf<String>()
        if (templatesMap.size > maxTemplatesSize) {
            val leastUsed = templatesMap.keys.sortedBy { queryCountMap.getOrDefault(it, 0) }.take(templatesMap.size - maxTemplatesSize)
            leastUsed.forEach { k ->
                templatesMap.remove(k)
                removed.add(k)
            }
        }
        return removed
    }
}
