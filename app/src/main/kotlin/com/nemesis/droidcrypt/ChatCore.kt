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
    /**
     * Возвращает пары:
     * templates: Map<canonicalTrigger, responses>
     * keywords: Map<canonicalKeyword, responses>
     *
     * canonical — это sorted tokens join (после нормализации/синонимов/удаления стоп-слов)
     */
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

            context.contentResolver.openInputStream(file.uri)?.bufferedReader()?.useLines { lines ->
                lines.forEach { raw ->
                    val l = raw.trim()
                    if (l.isEmpty()) return@forEach

                    try {
                        if (l.startsWith("-")) {
                            val (keyRaw, respRaw) = l.substring(1).split("=", limit = 2).map { it.trim() }
                            val keyCanonical = Engine.canonicalize(keyRaw, synonymsSnapshot, stopwordsSnapshot)
                            val responses = respRaw.split("|").map { it.trim() }.filter { it.isNotEmpty() }
                            if (keyCanonical.isNotEmpty() && responses.isNotEmpty()) {
                                keywords[keyCanonical] = responses.toMutableList()
                            } else {
                                Log.d(TAG, "Skipped keyword (empty after mapping) in $filename: rawKey='$keyRaw' -> canonical='$keyCanonical'")
                            }
                        } else if (l.contains("=")) {
                            val (triggerRaw, respRaw) = l.split("=", limit = 2).map { it.trim() }
                            val triggerCanonical = Engine.canonicalize(triggerRaw, synonymsSnapshot, stopwordsSnapshot)
                            val responses = respRaw.split("|").map { it.trim() }.filter { it.isNotEmpty() }
                            if (triggerCanonical.isNotEmpty() && responses.isNotEmpty()) {
                                templates[triggerCanonical] = responses.toMutableList()
                            } else {
                                Log.d(TAG, "Skipped template (empty after mapping) in $filename: rawTrigger='$triggerRaw' -> canonical='$triggerCanonical'")
                            }
                        }
                    } catch (pe: Exception) {
                        Log.e(TAG, "Error parsing line in $filename: '$l'", pe)
                    }
                }
            }

            Log.d(TAG, "Parsed $filename: templates=${templates.size}, keywords=${keywords.size}")
            templates.keys.take(5).forEach { Log.d(TAG, "TPL_KEY: '$it'") }
            keywords.keys.take(5).forEach { Log.d(TAG, "KW_KEY: '$it'") }

        } catch (e: Exception) {
            Log.e(TAG, "Error parsing templates from $filename", e)
        }
        return Pair(templates, keywords)
    }

    // --- Поиск ---
    /**
     * Поиск ответа по core-файлам (core1..core9).
     * Использует каноническое представление (sorted tokens) повсеместно.
     */
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

            Log.d(TAG, "Search start: qFiltered='$qFiltered' tokens=$qMappedTokens canonical='$qCanonical' jaccardThreshold=$dynamicJaccardThreshold")

            fun resolvePotentialFileResponse(respRaw: String): String {
                val respTrim = respRaw.trim()
                // более строгий поиск имени файла в строке
                val fileRegex = Regex("""([\w\-\._]+\.txt)""", RegexOption.IGNORE_CASE)
                val match = fileRegex.find(respTrim)
                if (match != null) {
                    val filename = match.groupValues[1].trim()
                    if (filename.isNotEmpty()) {
                        val (tpls, keywords) = parseTemplatesFromFile(context, folderUri, filename, synonymsSnapshot, stopwordsSnapshot)

                        // 1) exact match внутри файла по canonical
                        if (qCanonical.isNotEmpty()) {
                            tpls[qCanonical]?.let { if (it.isNotEmpty()) return it.random() }
                        }

                        // 2) keyword match (требуем хотя бы 1 общий токен; можно усилить до 2)
                        for ((k, v) in keywords) {
                            if (k.isBlank() || v.isEmpty()) continue
                            val kTokens = k.split(" ").filter { it.isNotEmpty() }.toSet()
                            val common = qSet.intersect(kTokens).size
                            if (common >= 1) return v.random()
                        }

                        // 3) Jaccard внутри файла
                        var best: String? = null
                        var bestScore = 0.0
                        for (key in tpls.keys) {
                            val keyTokens = if (key.isEmpty()) emptySet<String>() else key.split(" ").toSet()
                            val score = engine.weightedJaccard(qSet, keyTokens)
                            if (score > bestScore) {
                                bestScore = score
                                best = key
                            }
                        }
                        if (best != null && bestScore >= dynamicJaccardThreshold) {
                            return tpls[best]?.random() ?: ""
                        }

                        // 4) Levenshtein внутри файла (по всем ключам)
                        var bestLev: String? = null
                        var bestDist = Int.MAX_VALUE
                        for (key in tpls.keys) {
                            val d = engine.levenshtein(qCanonical, key, qCanonical)
                            if (d < bestDist) { bestDist = d; bestLev = key }
                        }
                        if (bestLev != null && bestDist <= engine.getFuzzyDistance(qCanonical)) {
                            return tpls[bestLev]?.random() ?: ""
                        }

                        // 5) fallback — случайный ответ из файла
                        val allResponses = mutableListOf<String>()
                        tpls.values.forEach { allResponses.addAll(it) }
                        keywords.values.forEach { allResponses.addAll(it) }
                        if (allResponses.isNotEmpty()) return allResponses.random()
                    }
                }
                return respRaw
            }

            for (i in 1..9) {
                val filename = "core$i.txt"
                val file = dir.findFile(filename) ?: continue
                if (!file.exists()) continue
                val (templates, keywords) = parseTemplatesFromFile(context, folderUri, filename, synonymsSnapshot, stopwordsSnapshot)

                // exact match по canonical ключу
                if (qCanonical.isNotEmpty()) {
                    templates[qCanonical]?.let {
                        Log.d(TAG, "Exact match in $filename for '$qCanonical'")
                        return resolvePotentialFileResponse(it.random())
                    }
                }

                // keyword match: проверяем пересечение токенов (keywords хранятся как canonical строки)
                for ((k, v) in keywords) {
                    if (k.isBlank()) continue
                    val kTokens = k.split(" ").filter { it.isNotEmpty() }.toSet()
                    if (qSet.intersect(kTokens).size >= 1) {
                        Log.d(TAG, "Keyword match in $filename: key='$k' tokens=$kTokens")
                        return resolvePotentialFileResponse(v.random())
                    }
                }

                // Соберём кандидатов через простой локальный inverted по tokens запроса
                val candidateCounts = HashMap<String, Int>()
                for (tok in qSet) {
                    for (key in templates.keys) {
                        if (key.contains(tok)) {
                            candidateCounts[key] = candidateCounts.getOrDefault(key, 0) + 1
                        }
                    }
                }

                val candidates: List<String> = if (candidateCounts.isNotEmpty()) {
                    candidateCounts.entries
                        .filter { it.value >= Engine.CANDIDATE_TOKEN_THRESHOLD }
                        .sortedByDescending { it.value }
                        .map { it.key }
                        .take(Engine.MAX_CANDIDATES_FOR_LEV * 4) // расширяем пул
                } else {
                    // fallback: ближайшие по длине (увеличиваем охват)
                    val maxDist = engine.getFuzzyDistance(qCanonical)
                    templates.keys.sortedBy { kotlin.math.abs(it.length - qCanonical.length) }
                        .take(Engine.MAX_CANDIDATES_FOR_LEV * 6)
                }

                // weighted Jaccard по кандидатам
                var best: String? = null
                var bestScore = 0.0
                for (key in candidates) {
                    val keyTokens = if (key.isEmpty()) emptySet<String>() else key.split(" ").toSet()
                    val score = engine.weightedJaccard(qSet, keyTokens)
                    if (score > bestScore) {
                        bestScore = score
                        best = key
                    }
                }
                if (best != null && bestScore >= dynamicJaccardThreshold) {
                    Log.d(TAG, "Jaccard match in $filename: best='$best' score=$bestScore threshold=$dynamicJaccardThreshold")
                    return resolvePotentialFileResponse(templates[best]?.random() ?: "")
                }

                // Levenshtein по кандидатам
                var bestLev: String? = null
                var bestDist = Int.MAX_VALUE
                for (key in candidates) {
                    val d = engine.levenshtein(qCanonical, key, qCanonical)
                    if (d < bestDist) {
                        bestDist = d
                        bestLev = key
                    }
                }
                if (bestLev != null && bestDist <= engine.getFuzzyDistance(qCanonical)) {
                    Log.d(TAG, "Levenshtein match in $filename: bestLev='$bestLev' dist=$bestDist fuzzy=${engine.getFuzzyDistance(qCanonical)}")
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
