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
        contextMap: MutableMap<String, String>? = null
    ): Pair<HashMap<String, MutableList<String>>, HashMap<String, MutableList<String>>> {
        val templates = HashMap<String, MutableList<String>>()
        val keywords = HashMap<String, MutableList<String>>()
        val uriLocal = folderUri ?: return Pair(templates, keywords)
        try {
            val dir = DocumentFile.fromTreeUri(context, uriLocal) ?: return Pair(templates, keywords)
            val file = dir.findFile(filename) ?: return Pair(templates, keywords)
            if (!file.exists()) {
                Log.w(TAG, "File $filename does not exist")
                return Pair(templates, keywords)
            }

            context.contentResolver.openInputStream(file.uri)?.bufferedReader()?.useLines { lines ->
                lines.forEach { raw ->
                    val l = raw.trim()
                    if (l.isEmpty()) return@forEach

                    if (contextMap != null && l.startsWith(":") && l.endsWith(":")) {
                        val contextLine = l.substring(1, l.length - 1)
                        if (contextLine.contains("=")) {
                            val (key, fileTarget) = contextLine.split("=", limit = 2).map { it.trim() }
                            val keyLower = key.lowercase(Locale.ROOT)
                            if (keyLower.isNotEmpty() && fileTarget.isNotEmpty()) {
                                contextMap[keyLower] = fileTarget
                                Log.d(TAG, "Parsed context mapping: $keyLower -> $fileTarget")
                            }
                        }
                        return@forEach
                    }

                    if (l.startsWith("-")) {
                        val keywordLine = l.substring(1)
                        if (keywordLine.contains("=")) {
                            val (key, respRaw) = keywordLine.split("=", limit = 2).map { it.trim() }
                            val (mappedTokens, keyMapped) = Engine.filterStopwordsAndMapSynonymsStatic(key, synonymsSnapshot, stopwordsSnapshot)
                            val responses = respRaw.split("|").map { it.trim() }.filter { it.isNotEmpty() }
                            if (keyMapped.isNotEmpty() && responses.isNotEmpty()) {
                                keywords[keyMapped] = responses.toMutableList()
                                Log.d(TAG, "Parsed keyword: $keyMapped -> $responses")
                            }
                        }
                    } else if (l.contains("=")) {
                        val (trigger, respRaw) = l.split("=", limit = 2).map { it.trim() }
                        val (mappedTokens, triggerMapped) = Engine.filterStopwordsAndMapSynonymsStatic(trigger, synonymsSnapshot, stopwordsSnapshot)
                        val responses = respRaw.split("|").map { it.trim() }.filter { it.isNotEmpty() }
                        if (triggerMapped.isNotEmpty() && responses.isNotEmpty()) {
                            templates[triggerMapped] = responses.toMutableList()
                            Log.d(TAG, "Parsed template: $triggerMapped -> $responses")
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing templates from $filename", e)
        }
        return Pair(templates, keywords)
    }

    // --- Поиск в core-файлах ---
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
            val qSet = qTokens.toSet()

            fun resolvePotentialFileResponse(respRaw: String, context: Context): String {
                var respTrim = respRaw.trim()
                Log.d(TAG, "Resolving response: '$respRaw'")

                // Удаляем завершающее двоеточие, если есть
                if (respTrim.endsWith(":")) {
                    respTrim = respTrim.dropLast(1).trim()
                    Log.d(TAG, "Removed trailing colon: '$respTrim'")
                }

                // Проверяем, выглядит ли ответ как имя файла (.txt)
                if (respTrim.contains(".txt", ignoreCase = true)) {
                    // Извлекаем имя файла (после последнего /, если есть)
                    val filename = respTrim.substringAfterLast('/', respTrim).trim()
                    Log.d(TAG, "Detected potential file: '$filename'")

                    if (filename.isNotEmpty()) {
                        // Проверяем существование файла
                        val file = dir.findFile(filename)
                        if (file != null && file.exists()) {
                            Log.d(TAG, "File $filename exists, parsing...")
                            val (tpls, kws) = parseTemplatesFromFile(context, folderUri, filename, synonymsSnapshot, stopwordsSnapshot)
                            val allResponses = mutableListOf<String>()
                            tpls.values.forEach { allResponses.addAll(it) }
                            kws.values.forEach { allResponses.addAll(it) }
                            if (allResponses.isNotEmpty()) {
                                val selectedResponse = allResponses.random()
                                Log.d(TAG, "Selected response from $filename: '$selectedResponse'")
                                return selectedResponse
                            } else {
                                Log.w(TAG, "File $filename parsed but no responses found")
                            }
                        } else {
                            Log.w(TAG, "File $filename does not exist")
                        }
                    }
                }
                // Fallback: возвращаем исходный ответ
                Log.d(TAG, "Returning raw response as fallback: '$respRaw'")
                return respRaw
            }

            for (i in 1..9) {
                val filename = "core$i.txt"
                val file = dir.findFile(filename)
                if (file == null || !file.exists()) {
                    Log.d(TAG, "Core file $filename not found or does not exist")
                    continue
                }
                Log.d(TAG, "Processing core file: $filename")

                val (templates, keywords) = parseTemplatesFromFile(context, folderUri, filename, synonymsSnapshot, stopwordsSnapshot)

                // Точное совпадение
                templates[qFiltered]?.let { possible ->
                    if (possible.isNotEmpty()) {
                        val response = possible.random()
                        Log.d(TAG, "Exact match found in $filename: $qFiltered -> $response")
                        return resolvePotentialFileResponse(response, context)
                    }
                }

                // Поиск по ключевым словам
                for ((k, v) in keywords) {
                    if (qFiltered.contains(k)) {
                        val response = v.random()
                        Log.d(TAG, "Keyword match found in $filename: $k -> $response")
                        return resolvePotentialFileResponse(response, context)
                    }
                }

                // Jaccard
                var best: String? = null
                var bestScore = 0.0
                for (key in templates.keys) {
                    val keyTokens = Engine.filterStopwordsAndMapSynonymsStatic(key, synonymsSnapshot, stopwordsSnapshot).first.toSet()
                    if (keyTokens.isEmpty()) continue
                    val score = engine.weightedJaccard(qSet, keyTokens)
                    if (score > bestScore) {
                        bestScore = score
                        best = key
                    }
                }
                if (best != null && bestScore >= jaccardThreshold) {
                    val response = templates[best]?.random() ?: ""
                    Log.d(TAG, "Jaccard match found in $filename: $best (score=$bestScore) -> $response")
                    return resolvePotentialFileResponse(response, context)
                }

                // Levenshtein
                var bestLev: String? = null
                var bestDist = Int.MAX_VALUE
                for (key in templates.keys.take(20)) {
                    val d = engine.levenshtein(qFiltered, key, qFiltered)
                    if (d < bestDist) {
                        bestDist = d
                        bestLev = key
                    }
                }
                if (bestLev != null && bestDist <= engine.getFuzzyDistance(qFiltered)) {
                    val response = templates[bestLev]?.random() ?: ""
                    Log.d(TAG, "Levenshtein match found in $filename: $bestLev (dist=$bestDist) -> $response")
                    return resolvePotentialFileResponse(response, context)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error searching in core files", e)
        }
        Log.d(TAG, "No match found in core files")
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
                stopwords,
                if (filename == "base.txt") mutableMapOf() else null
            )
            templatesMap.clear()
            templatesMap.putAll(parsedTemplates)
            keywords.clear()
            keywords.putAll(parsedKeywords)
            // Парсинг метаданных
            val uri = folderUri ?: return true to (parsedTemplates.size + parsedKeywords.size)
            val dir = DocumentFile.fromTreeUri(context, uri) ?: return true to (parsedTemplates.size + parsedKeywords.size)
            val metadataFile = dir.findFile(filename.replace(".txt", "_metadata.txt"))
            if (metadataFile != null && metadataFile.exists()) {
                context.contentResolver.openInputStream(metadataFile.uri)?.bufferedReader()?.useLines { lines ->
                    lines.forEach { raw ->
                        val line = raw.trim()
                        when {
                            line.startsWith("mascot_name=") -> metadataOut["mascot_name"] = line.substringAfter("mascot_name=").trim()
                            line.startsWith("mascot_icon=") -> metadataOut["mascot_icon"] = line.substringAfter("mascot_icon=").trim()
                            line.startsWith("theme_color=") -> metadataOut["theme_color"] = line.substringAfter("theme_color=").trim()
                            line.startsWith("theme_background=") -> metadataOut["theme_background"] = line.substringAfter("theme_background=").trim()
                            line.startsWith("mascot_list=") -> {
                                val mascots = line.substringAfter("mascot_list=").split("|")
                                metadataOut["mascot_list"] = mascots.joinToString("|")
                            }
                        }
                    }
                }
            }
            Log.d(TAG, "Loaded templates from $filename: ${parsedTemplates.size} templates, ${parsedKeywords.size} keywords")
            true to (parsedTemplates.size + parsedKeywords.size)
        } catch (e: Exception) {
            Log.e(TAG, "Error loading templates from $filename", e)
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
        templatesMap.clear()
        keywordResponses.clear()
        mascotList.clear()
        contextMap.clear()
        templatesMap[Engine.normalizeText("привет")] = mutableListOf("Привет! Чем могу помочь?", "Здравствуй!")
        templatesMap[Engine.normalizeText("как дела")] = mutableListOf("Всё отлично, а у тебя?", "Нормально, как дела?")
        keywordResponses[Engine.normalizeText("спасибо")] = mutableListOf("Рад, что помог!", "Всегда пожалуйста!")
        Log.d(TAG, "Loaded fallback templates")
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
