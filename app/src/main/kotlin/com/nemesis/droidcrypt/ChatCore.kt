package com.nemesis.droidcrypt

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.documentfile.provider.DocumentFile
import java.util.*
import kotlin.collections.HashMap
import com.nemesis.droidcrypt.Engine

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
        stopwordsSnapshot: Set<String>,
        contextMap: MutableMap<String, String>? = null // Добавлен параметр для заполнения contextMap
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

                    when {
                        l.startsWith(":") && l.contains("=") -> {
                            // Обработка :ключ=файл для contextMap
                            val (key, fileTarget) = l.substring(1).split("=", limit = 2).map { it.trim() }
                            val keyMapped = Engine.filterStopwordsAndMapSynonymsStatic(key, synonymsSnapshot, stopwordsSnapshot).second
                            if (keyMapped.isNotEmpty() && fileTarget.isNotEmpty()) {
                                contextMap?.put(keyMapped, fileTarget)
                            }
                        }
                        l.startsWith("-") -> {
                            val (key, respRaw) = l.substring(1).split("=", limit = 2).map { it.trim() }
                            val (mappedTokens, keyMapped) = Engine.filterStopwordsAndMapSynonymsStatic(key, synonymsSnapshot, stopwordsSnapshot)
                            val responses = respRaw.split("|").map { it.trim() }.filter { it.isNotEmpty() }
                            if (keyMapped.isNotEmpty() && responses.isNotEmpty()) keywords[keyMapped] = responses.toMutableList()
                        }
                        l.contains("=") -> {
                            val (trigger, respRaw) = l.split("=", limit = 2).map { it.trim() }
                            val (mappedTokens, triggerMapped) = Engine.filterStopwordsAndMapSynonymsStatic(trigger, synonymsSnapshot, stopwordsSnapshot)
                            val responses = respRaw.split("|").map { it.trim() }.filter { it.isNotEmpty() }
                            if (triggerMapped.isNotEmpty() && responses.isNotEmpty()) templates[triggerMapped] = responses.toMutableList()
                        }
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
            val (qMappedTokens, qMapped) = Engine.filterStopwordsAndMapSynonymsStatic(qFiltered, synonymsSnapshot, stopwordsSnapshot)
            val qSet = qMappedTokens.toSet()

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
                    val keyTokens = key.split(" ")
                    val keySet = keyTokens.toSet()
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
                    val d = engine.levenshtein(qMapped, key, qFiltered)
                    if (d < bestDist) {
                        bestDist = d
                        bestLev = key
                    }
                }
                if (bestLev != null && bestDist <= engine.getFuzzyDistance(qFiltered)) {
                    return templates[bestLev]?.random()
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
        filename: String = "base.txt",
        contextMap: Map<String, String> // Добавлен параметр для использования contextMap
    ): String {
        val normalized = Engine.normalizeText(userInput)
        val tokens = Engine.tokenizeStatic(normalized)
        val (qTokensFiltered, qFiltered) = engine.filterStopwordsAndMapSynonyms(normalized)

        // Сначала ищем в текущем контексте
        val currentContextResult = searchInCoreFiles(
            context, folderUri, qFiltered, tokens, engine,
            engine.synonymsMap, engine.stopwords, Engine.JACCARD_THRESHOLD
        )
        if (currentContextResult != null) return currentContextResult

        // Проверяем base.txt для ключевых слов
        val tempContextMap = HashMap<String, String>()
        val (baseTemplates, baseKeywords) = parseTemplatesFromFile(
            context, folderUri, "base.txt", engine.synonymsMap, engine.stopwords, tempContextMap
        )
        baseTemplates[qFiltered]?.let { return it.random() }
        for ((k, v) in baseKeywords) {
            if (qFiltered.contains(k)) return v.random()
        }

        // Проверяем contextMap для переключения контекста
        val detectedContext = detectContext(normalized, contextMap, engine)
        if (detectedContext != null && detectedContext != filename) {
            // Переключаемся в новый контекст и ищем там
            val (newTemplates, newKeywords) = parseTemplatesFromFile(
                context, folderUri, detectedContext, engine.synonymsMap, engine.stopwords
            )
            newTemplates[qFiltered]?.let { return it.random() }
            for ((k, v) in newKeywords) {
                if (qFiltered.contains(k)) return v.random()
            }

            // Jaccard/Levenshtein в новом контексте
            var best: String? = null
            var bestScore = 0.0
            val qSet = qTokensFiltered.toSet()
            for (key in newTemplates.keys) {
                val keyTokens = Engine.filterStopwordsAndMapSynonymsStatic(key, engine.synonymsMap, engine.stopwords).first.toSet()
                val score = engine.weightedJaccard(qSet, keyTokens)
                if (score > bestScore) {
                    bestScore = score
                    best = key
                }
            }
            if (best != null && bestScore >= Engine.JACCARD_THRESHOLD) {
                return newTemplates[best]?.random() ?: getDummyResponse(userInput)
            }

            var bestLev: String? = null
            var bestDist = Int.MAX_VALUE
            for (key in newTemplates.keys.take(20)) {
                val d = engine.levenshtein(qFiltered, key, qFiltered)
                if (d < bestDist) {
                    bestDist = d
                    bestLev = key
                }
            }
            if (bestLev != null && bestDist <= engine.getFuzzyDistance(qFiltered)) {
                return newTemplates[bestLev]?.random() ?: getDummyResponse(userInput)
            }
        }

        // Если ничего не найдено, ищем в core1-core9
        val coreResult = searchInCoreFiles(
            context, folderUri, qFiltered, tokens, engine,
            engine.synonymsMap, engine.stopwords, Engine.JACCARD_THRESHOLD
        )
        return coreResult ?: "Извините, ничего не нашел. Попробуйте другой запрос."
    }

    // --- loadTemplatesFromFile ---
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
        metadataOut: MutableMap<String, String>,
        contextMap: MutableMap<String, String>
    ): Pair<Boolean, Int> {
        return try {
            val (parsedTemplates, parsedKeywords) = parseTemplatesFromFile(
                context,
                folderUri,
                filename,
                synonymsMap,
                stopwords,
                contextMap // Передаём contextMap для заполнения
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
        templatesMap[Engine.normalizeText("привет")] = mutableListOf("Привет! Чем могу помочь?", "Здравствуй!")
        templatesMap[Engine.normalizeText("как дела")] = mutableListOf("Всё отлично, а у тебя?", "Нормально, как дела?")
        keywordResponses[Engine.normalizeText("спасибо")] = mutableListOf("Рад, что помог!", "Всегда пожалуйста!")
    }

    fun getDummyResponse(query: String): String =
        if (query.lowercase(Locale.getDefault()).contains("привет"))
            "Привет! Чем могу помочь?"
        else
            "Не понял запрос. Попробуй другой вариант."

    fun detectContext(input: String, contextMap: Map<String, String>, engine: Engine): String? {
        val (tokens, _) = engine.filterStopwordsAndMapSynonyms(input)
        return contextMap.maxByOrNull { (k, _) ->
            val (kw, _) = engine.filterStopwordsAndMapSynonyms(k)
            tokens.count { it in kw }
        }?.value
    }
}
