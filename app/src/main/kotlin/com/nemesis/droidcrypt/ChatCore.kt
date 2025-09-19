package com.nemesis.droidcrypt

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.documentfile.provider.DocumentFile
import java.util.*
import kotlin.collections.HashMap

object ChatCore {
    private const val TAG = "ChatCore"

    // Вшитые антиспам-ответы
    private val antiSpamResponses = listOf(
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

    val fallbackReplies = listOf("Привет!", "Как дела?", "Расскажи о себе", "Выход")

    fun getAntiSpamResponse(): String = antiSpamResponses.random()

    // --- Универсальная нормализация ---
    fun normalizeForIndex(s: String): String {
        val lower = s.lowercase(Locale.getDefault())
        val cleaned = lower.replace(Regex("[^\\p{L}\\p{Nd}\\s]"), " ")
        return cleaned.replace(Regex("\\s+"), " ").trim()
    }

    fun normalizeToken(t: String): String = normalizeForIndex(t)

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
                        val parts = l.split(";").map { normalizeForIndex(it) }.filter { it.isNotEmpty() }
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
                        .map { normalizeForIndex(it) }
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

            fun filterAndMap(input: String): String {
                val toks = input.split(Regex("\\s+")).map { normalizeForIndex(it) }
                return toks.map { synonymsSnapshot[it] ?: it }.filter { it !in stopwordsSnapshot }.joinToString(" ")
            }

            context.contentResolver.openInputStream(file.uri)?.bufferedReader()?.useLines { lines ->
                lines.forEach { raw ->
                    val l = raw.trim()
                    if (l.isEmpty() || !l.contains("=")) return@forEach

                    if (l.startsWith("-")) {
                        val (key, respRaw) = l.substring(1).split("=", limit = 2).map { it.trim() }
                        val keyMapped = filterAndMap(key)
                        val responses = respRaw.split("|").map { it.trim() }.filter { it.isNotEmpty() }
                        if (keyMapped.isNotEmpty() && responses.isNotEmpty()) keywords[keyMapped] = responses.toMutableList()
                    } else {
                        val (trigger, respRaw) = l.split("=", limit = 2).map { it.trim() }
                        val triggerMapped = filterAndMap(trigger)
                        val responses = respRaw.split("|").map { it.trim() }.filter { it.isNotEmpty() }
                        if (triggerMapped.isNotEmpty() && responses.isNotEmpty()) templates[triggerMapped] = responses.toMutableList()
                    }
                }
            }
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
            fun filterAndMap(input: String): String {
                val toks = input.split(Regex("\\s+")).map { normalizeForIndex(it) }
                return toks.map { synonymsSnapshot[it] ?: it }.filter { it !in stopwordsSnapshot }.joinToString(" ")
            }
            val qMapped = filterAndMap(qFiltered)
            val qSet = qMapped.split(" ").toSet()

            for (i in 1..9) {
                val filename = "core$i.txt"
                val file = dir.findFile(filename) ?: continue
                if (!file.exists()) continue
                val (templates, keywords) = parseTemplatesFromFile(context, folderUri, filename, synonymsSnapshot, stopwordsSnapshot)

                templates[qMapped]?.let { return it.random() }
                for ((k, v) in keywords) {
                    if (qMapped.contains(k)) return v.random()
                }

                var best: String? = null
                var bestScore = 0.0
                for (key in templates.keys) {
                    val keySet = key.split(" ").toSet()
                    val score = engine.weightedJaccard(qSet, keySet)
                    if (score > bestScore) {
                        bestScore = score
                        best = key
                    }
                }
                if (best != null && bestScore >= jaccardThreshold) {
                    return templates[best]?.random()
                }

                var bestLev: String? = null
                var bestDist = Int.MAX_VALUE
                for (key in templates.keys.take(20)) {
                    val d = engine.levenshtein(qMapped, key, qMapped)
                    if (d < bestDist) {
                        bestDist = d
                        bestLev = key
                    }
                }
                if (bestLev != null && bestDist <= engine.getFuzzyDistance(qMapped)) {
                    return templates[bestLev]?.random()
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error searching in core files", e)
        }
        return null
    }

    // --- Заглушки ---
    fun loadFallbackTemplates(
        templatesMap: MutableMap<String, MutableList<String>>,
        keywordResponses: MutableMap<String, MutableList<String>>,
        mascotList: MutableList<Map<String, String>>,
        contextMap: MutableMap<String, String>
    ) {
        templatesMap.clear(); keywordResponses.clear(); mascotList.clear(); contextMap.clear()
        templatesMap[normalizeForIndex("привет")] = mutableListOf("Привет! Чем могу помочь?", "Здравствуй!")
        templatesMap[normalizeForIndex("как дела")] = mutableListOf("Всё отлично, а у тебя?", "Нормально, как дела?")
        keywordResponses[normalizeForIndex("спасибо")] = mutableListOf("Рад, что помог!", "Всегда пожалуйста!")
    }

    fun getDummyResponse(query: String): String = when (val q = query.lowercase(Locale.getDefault())) {
        in q -> "Привет! Чем могу помочь?"
        else -> "Не понял запрос. Попробуй другой вариант."
    }

    fun detectContext(input: String, contextMap: Map<String, String>, engine: Engine): String? {
        val tokens = engine.filterStopwordsAndMapSynonyms(input).first
        return contextMap.maxByOrNull { (k, _) ->
            val kw = engine.filterStopwordsAndMapSynonyms(k).first
            tokens.count { it in kw }
        }?.value
    }
}
