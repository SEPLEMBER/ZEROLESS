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
        stopwordsSnapshot: Set<String>
    ): Triple<HashMap<String, MutableList<String>>, HashMap<String, MutableList<String>>, HashMap<String, MutableList<String>>> {
        val templates = HashMap<String, MutableList<String>>()
        val keywords = HashMap<String, MutableList<String>>()
        val strictTemplates = HashMap<String, MutableList<String>>() // Для ">>"
        val uriLocal = folderUri ?: return Triple(templates, keywords, strictTemplates)
        try {
            val dir = DocumentFile.fromTreeUri(context, uriLocal) ?: return Triple(templates, keywords, strictTemplates)
            val file = dir.findFile(filename) ?: return Triple(templates, keywords, strictTemplates)
            if (!file.exists()) return Triple(templates, keywords, strictTemplates)

            context.contentResolver.openInputStream(file.uri)?.bufferedReader()?.useLines { lines ->
                lines.forEach { raw ->
                    val l = raw.trim()
                    if (l.isEmpty()) return@forEach

                    if (l.startsWith(">>")) {
                        val (trigger, respRaw) = l.substring(2).split("=", limit = 2).map { it.trim() }
                        val triggerMapped = Engine.normalizeText(trigger).lowercase(Locale.ROOT)
                        val responses = respRaw.split("|").map { it.trim() }.filter { it.isNotEmpty() }
                        if (triggerMapped.isNotEmpty() && responses.isNotEmpty()) strictTemplates[triggerMapped] = responses.toMutableList()
                    } else if (l.startsWith("-")) {
                        val (key, respRaw) = l.substring(1).split("=", limit = 2).map { it.trim() }
                        val keyMapped = Engine.normalizeText(key).lowercase(Locale.ROOT)
                        val responses = respRaw.split("|").map { it.trim() }.filter { it.isNotEmpty() }
                        if (keyMapped.isNotEmpty() && responses.isNotEmpty()) keywords[keyMapped] = responses.toMutableList()
                    } else if (l.contains("=")) {
                        val (trigger, respRaw) = l.split("=", limit = 2).map { it.trim() }
                        val triggerMapped = Engine.filterStopwordsAndMapSynonymsStatic(trigger, synonymsSnapshot, stopwordsSnapshot).second
                        val responses = respRaw.split("|").map { it.trim() }.filter { it.isNotEmpty() }
                        if (triggerMapped.isNotEmpty() && responses.isNotEmpty()) templates[triggerMapped] = responses.toMutableList()
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing templates from $filename", e)
        }
        return Triple(templates, keywords, strictTemplates)
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
            val qTokensList = qMappedTokens

            fun resolvePotentialFileResponse(respRaw: String): String {
                val respTrim = respRaw.trim()
                val resp = respTrim.removeSuffix(":").trim()
                if (resp.contains(".txt", ignoreCase = true)) {
                    val filename = resp.substringAfterLast('/').trim()
                    if (filename.isNotEmpty()) {
                        val (tpls, kws, _) = parseTemplatesFromFile(context, folderUri, filename, synonymsSnapshot, stopwordsSnapshot)
                        val allResponses = mutableListOf<String>()
                        tpls.values.forEach { allResponses.addAll(it) }
                        kws.values.forEach { allResponses.addAll(it) }
                        if (allResponses.isNotEmpty()) return allResponses.random()
                    }
                }
                return respRaw
            }

            for (i in 1..9) {
                val filename = "core$i.txt"
                val file = dir.findFile(filename) ?: continue
                if (!file.exists()) continue
                val (templates, keywords, strictTemplates) = parseTemplatesFromFile(context, folderUri, filename, synonymsSnapshot, stopwordsSnapshot)

                // Сначала strict match
                strictTemplates[qMapped.lowercase(Locale.ROOT)]?.let { return it.random() }

                templates[qMapped]?.let { return resolvePotentialFileResponse(it.random()) }
                for ((k, v) in keywords) {
                    if (qMapped.lowercase(Locale.ROOT).contains(k)) return resolvePotentialFileResponse(v.random())
                }

                var best: String? = null
                var bestScore = 0.0
                for (key in templates.keys) {
                    val keyTokensList = Engine.filterStopwordsAndMapSynonymsStatic(key, synonymsSnapshot, stopwordsSnapshot).first
                    val score = engine.weightedJaccard(qTokensList, keyTokensList)
                    if (score > bestScore) {
                        bestScore = score
                        best = key
                    }
                }
                if (best != null && bestScore >= jaccardThreshold) {
                    return resolvePotentialFileResponse(templates[best]?.random() ?: "")
                }

                var bestLev: String? = null
                var bestDist = Int.MAX_VALUE
                for (key in templates.keys.take(20)) {
                    val d = engine.damerauLevenshtein(qMapped, key, qFiltered)
                    if (d < bestDist) {
                        bestDist = d
                        bestLev = key
                    }
                }
                if (bestLev != null && bestDist <= engine.getFuzzyDistance(qFiltered)) {
                    return resolvePotentialFileResponse(templates[bestLev]?.random() ?: "")
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
        val normalized = Engine.normalizeText(userInput)
        val tokens = Engine.tokenizeStatic(normalized)

        val resp = searchInCoreFiles(
            context,
            folderUri,
            normalized,
            tokens,
            engine,
            engine.synonymsMap,
            engine.stopwords,
            jaccardThreshold = Engine.JACCARD_THRESHOLD
        )
        return resp ?: getDummyResponse(userInput)
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
        val tokens = Engine.tokenizeStatic(Engine.normalizeText(input))
        return contextMap.maxByOrNull { (k, _) ->
            val kw = Engine.tokenizeStatic(Engine.normalizeText(k))
            tokens.count { it in kw }
        }?.value
    }
}
