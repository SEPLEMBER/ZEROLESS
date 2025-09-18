package com.nemesis.droidcrypt

import kotlin.math.abs
import kotlin.math.min
import kotlin.math.log10
import java.util.*
import kotlin.math.roundToInt
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sqrt
import kotlin.collections.HashMap

/**
 * Engine — чистая логика обработки текста/шаблонов.
 *
 * Он не знает про Android Context; принимает mutable структуры (templatesMap, synonymsMap, stopwords),
 * над которыми работает in-place. Это позволяет ChatActivity держать коллекции, а Engine — оперировать ими.
 */
class Engine(
    val templatesMap: MutableMap<String, MutableList<String>>,
    val synonymsMap: MutableMap<String, String>,
    val stopwords: MutableSet<String>
) {

    val tokenWeights: MutableMap<String, Double> = HashMap()

    /**
     * Нормализация строки: lowercase, удаление не букв/цифр, сжатие пробелов.
     */
    fun normalizeText(s: String): String {
        val lower = s.lowercase(Locale.getDefault())
        val cleaned = lower.replace(Regex("[^\\p{L}\\p{Nd}\\s]"), " ")
        val collapsed = cleaned.replace(Regex("\\s+"), " ").trim()
        return collapsed
    }

    /**
     * Разбивает строку на токены (по пробелам).
     */
    fun tokenize(s: String): List<String> {
        if (s.isBlank()) return emptyList()
        return s.split(Regex("\\s+")).map { it.trim() }.filter { it.isNotEmpty() }
    }

    /**
     * Преобразует токены через synonymsMap и убирает стоп-слова.
     * Возвращает пару: список токенов и собранную строку.
     */
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

    /**
     * Получить "fuzzy distance" по длине слова — используется для ограничения Levenshtein-поиска.
     */
    fun getFuzzyDistance(word: String): Int {
        return when {
            word.length <= 4 -> 1
            word.length <= 8 -> 2
            else -> 3
        }
    }

    /**
     * Левенштейн с оптимизациями (взят из оригинального кода).
     */
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

    /**
     * Возвращает порог Jaccard в зависимости от длины запроса.
     */
    fun getJaccardThreshold(query: String): Double {
        return when {
            query.length <= 10 -> 0.3
            query.length <= 20 -> 0.4
            else -> 0.75
        }
    }

    /**
     * Вычисление веса пересечения/объединения с учётом idf-подобных значений tokenWeights.
     * tokenWeights должен быть рассчитан перед этим (computeTokenWeights()).
     */
    fun weightedJaccard(qSet: Set<String>, keyTokens: Set<String>): Double {
        val intersection = qSet.intersect(keyTokens)
        val union = qSet.union(keyTokens)
        val interWeight = intersection.sumOf { tokenWeights.getOrDefault(it, 1.0) }
        val unionWeight = union.sumOf { tokenWeights.getOrDefault(it, 1.0) }
        return if (unionWeight == 0.0) 0.0 else interWeight / unionWeight
    }

    /**
     * Пересчитать веса токенов на основе текущего templatesMap.
     * Чем реже токен — тем выше вес (логарифмическая шкала). Минимум 1.0.
     */
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

    /**
     * Построение inverted index на основе templatesMap и stopwords/synonyms.
     * Возвращает Map token -> list<triggers>
     *
     * Параметры:
     *  - minTokenLength: минимальная длина токена для индексации (по умолчанию 3)
     *  - maxTokensPerIndex: максимально элементов в списке по токену (обрезается)
     */
    fun rebuildInvertedIndex(
        minTokenLength: Int = 3,
        maxTokensPerIndex: Int = 50
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

    /**
     * Утилита: trimming templatesMap до желаемого размера, по наименьшей частоте в queryCountMap.
     * Возвращает список удалённых ключей.
     */
    fun trimTemplatesMap(maxTemplatesSize: Int, queryCountMap: Map<String, Int>): List<String> {
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
