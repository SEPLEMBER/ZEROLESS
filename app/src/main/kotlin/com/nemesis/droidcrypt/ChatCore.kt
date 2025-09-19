package com.nemesis.droidcrypt

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.documentfile.provider.DocumentFile
import java.util.*
import kotlin.collections.HashMap

object ChatCore {
    private const val TAG = "ChatCore"

    // Вшитые ответы
    val antiSpamResponses: List<String> = listOf(
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

    val fallbackReplies: List<String> = listOf("Привет", "Как дела?", "Расскажи о себе", "Выход")

    fun getAntiSpamResponse(): String = antiSpamResponses.random()

    // ------ Унифицированная нормализация (используется везде) ------
    fun normalizeForIndex(s: String): String {
        val lower = s.lowercase(Locale.getDefault())
        val cleaned = lower.replace(Regex("[^\\p{L}\\p{Nd}\\s]"), " ")
        return cleaned.replace(Regex("\\s+"), " ").trim()
    }

    // Короткая удобная нормализация слова (одиночный токен)
    fun normalizeToken(t: String): String = normalizeForIndex(t)

    /**
     * Загружает synonims.txt и stopwords.txt
     * - synonyms: строки разделяются `;`, canonical берётся последним
     * - stopwords: поддерживаем переносы, запятые, `^`, `|`, `;`
     */
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
            val synFile = dir.findFile("synonims.txt")
            if (synFile != null && synFile.exists()) {
                context.contentResolver.openInputStream(synFile.uri)?.bufferedReader()?.use { reader ->
                    reader.forEachLine { raw ->
                        var l = raw.trim()
                        if (l.isEmpty()) return@forEachLine
                        if (l.startsWith("*") && l.endsWith("*") && l.length > 1) {
                            l = l.substring(1, l.length - 1)
                        }
                        val parts = l.split(";").map { normalizeForIndex(it).trim() }.filter { it.isNotEmpty() }
                        if (parts.isEmpty()) return@forEachLine
                        val canonical = parts.last()
                        for (p in parts) {
                            // если ключ совпадает с canonical — всё равно пишем, но normalize перед записью
                            synonymsMap[normalizeForIndex(p)] = normalizeForIndex(canonical)
                        }
                    }
                }
            }

            val stopFile = dir.findFile("stopwords.txt")
            if (stopFile != null && stopFile.exists()) {
                context.contentResolver.openInputStream(stopFile.uri)?.bufferedReader()?.use { reader ->
                    val all = reader.readText()
                    if (all.isNotEmpty()) {
                        val parts = all.split(Regex("[\\r\\n\\t,|^;]+"))
                            .map { normalizeForIndex(it).trim() }
                            .filter { it.isNotEmpty() }
                        for (p in parts) stopwords.add(p)
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error loading synonyms/stopwords", e)
        }
    }

    /**
     * Парсит файл шаблонов: формирует map triggers->responses и map keywords->responses.
     * Всегда нормализует ключи одинаково (normalize + stopwords/synonyms mapping).
     */
    fun parseTemplatesFromFile(
        context: Context,
        folderUri: Uri?,
        filename: String,
        synonymsSnapshot: Map<String, String>,
        stopwordsSnapshot: Set<String>
    ): Pair<HashMap<String, MutableList<String>>, HashMap<String, MutableList<String>>> {
        val localTemplates = HashMap<String, MutableList<String>>()
        val localKeywords = HashMap<String, MutableList<String>>()
        val uriLocal = folderUri ?: return Pair(localTemplates, localKeywords)
        try {
            val dir = DocumentFile.fromTreeUri(context, uriLocal) ?: return Pair(localTemplates, localKeywords)
            val file = dir.findFile(filename) ?: return Pair(localTemplates, localKeywords)
            if (!file.exists()) return Pair(localTemplates, localKeywords)

            fun tokenizeLocal(s: String): List<String> {
                if (s.isBlank()) return emptyList()
                return s.split(Regex("\\s+")).map { it.trim() }.filter { it.isNotEmpty() }
            }

            fun filterStopwordsAndMapSynonymsLocal(input: String): Pair<List<String>, String> {
                val toks = tokenizeLocal(input)
                val mapped = toks.map { tok ->
                    val n = normalizeForIndex(tok)
                    val s = synonymsSnapshot[n] ?: n
                    s
                }.filter { it.isNotEmpty() && !stopwordsSnapshot.contains(it) }
                val joined = mapped.joinToString(" ")
                return Pair(mapped, joined)
            }

            context.contentResolver.openInputStream(file.uri)?.bufferedReader()?.use { reader ->
                reader.forEachLine { raw ->
                    val l = raw.trim()
                    if (l.isEmpty()) return@forEachLine

                    if (l.startsWith("-")) {
                        // keyword
                        val keywordLine = l.substring(1)
                        if (keywordLine.contains("=")) {
                            val parts = keywordLine.split("=", limit = 2)
                            if (parts.size == 2) {
                                val rawKey = parts[0].trim()
                                val keyMapped = filterStopwordsAndMapSynonymsLocal(rawKey).second
                                val responses = parts[1].split("|")
                                val responseList = responses.mapNotNull { it.trim().takeIf { s -> s.isNotEmpty() } }.toMutableList()
                                if (keyMapped.isNotEmpty() && responseList.isNotEmpty()) localKeywords[keyMapped] = responseList
                            }
                        }
                        return@forEachLine
                    }

                    if (!l.contains("=")) return@forEachLine
                    val parts = l.split("=", limit = 2)
                    if (parts.size == 2) {
                        val triggerRaw = parts[0].trim()
                        val triggerNormalized = normalizeForIndex(triggerRaw)
                        val triggerFiltered = filterStopwordsAndMapSynonymsLocal(triggerNormalized).second
                        val responses = parts[1].split("|")
                        val responseList = responses.mapNotNull { it.trim().takeIf { s -> s.isNotEmpty() } }.toMutableList()
                        if (triggerFiltered.isNotEmpty() && responseList.isNotEmpty()) localTemplates[triggerFiltered] = responseList
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing templates from $filename", e)
        }
        return Pair(localTemplates, localKeywords)
    }

    /**
     * Поиск по core1..core9.txt. Использует двухэтапный поиск: exact -> keyword -> jaccard -> jaccard-ranked Levenshtein
     */
    fun searchInCoreFiles(
        context: Context,
        folderUri: Uri?,
        qFiltered: String,
        qTokens: List<String>,
        qSet: Set<String>,
        jaccardThreshold: Double,
        synonymsSnapshot: Map<String, String>,
        stopwordsSnapshot: Set<String>,
        engine: Engine
    ): String? {
        val uriLocal = folderUri ?: return null
        try {
            val dir = DocumentFile.fromTreeUri(context, uriLocal) ?: return null

            fun tokenizeLocal(s: String): List<String> {
                if (s.isBlank()) return emptyList()
                return s.split(Regex("\\s+")).map { it.trim() }.filter { it.isNotEmpty() }
            }

            fun filterStopwordsAndMapSynonymsLocal(input: String): Pair<List<String>, String> {
                val toks = tokenizeLocal(input)
                val mapped = toks.map { tok ->
                    val n = normalizeForIndex(tok)
                    val s = synonymsSnapshot[n] ?: n
                    s
                }.filter { it.isNotEmpty() && !stopwordsSnapshot.contains(it) }
                val joined = mapped.joinToString(" ")
                return Pair(mapped, joined)
            }

            fun resolvePotentialFileResponse(respRaw: String): String {
                val respTrim = respRaw.trim()
                val resp = respTrim.removeSuffix(":").trim()
                if (resp.contains(".txt", ignoreCase = true)) {
                    val filename = resp.substringAfterLast('/').trim()
                    if (filename.isNotEmpty()) {
                        val (tpls, keywords) = parseTemplatesFromFile(context, folderUri, filename, synonymsSnapshot, stopwordsSnapshot)
                        val allResponses = mutableListOf<String>()
                        for (lst in tpls.values) allResponses.addAll(lst)
                        for (lst in keywords.values) allResponses.addAll(lst)
                        if (allResponses.isNotEmpty()) return allResponses.random()
                        return respTrim
                    }
                }
                return respRaw
            }

            // Приведём запрос к тому же виду, что и ключи
            val (qMappedTokensListLocal, qJoinedNormalizedLocal) = filterStopwordsAndMapSynonymsLocal(qFiltered)
            val qSetLocal = qMappedTokensListLocal.toSet()

            for (i in 1..9) {
                val filename = "core$i.txt"
                val file = dir.findFile(filename) ?: continue
                if (!file.exists()) continue

                val (coreTemplates, coreKeywords) = parseTemplatesFromFile(context, folderUri, filename, synonymsSnapshot, stopwordsSnapshot)
                if (coreTemplates.isEmpty() && coreKeywords.isEmpty()) continue

                // 1) Exact
                coreTemplates[qJoinedNormalizedLocal]?.let { possible ->
                    if (possible.isNotEmpty()) {
                        val chosen = possible.random()
                        Log.d(TAG, "Exact match in $filename for '$qJoinedNormalizedLocal' -> $chosen")
                        return resolvePotentialFileResponse(chosen)
                    }
                }

                // 2) keyword search
                for ((keyword, responses) in coreKeywords) {
                    if (qJoinedNormalizedLocal.contains(keyword) && responses.isNotEmpty()) {
                        val chosen = responses.random()
                        Log.d(TAG, "Keyword match '$keyword' in $filename -> $chosen")
                        return resolvePotentialFileResponse(chosen)
                    }
                }

                // 3) Jaccard best
                var bestByJaccard: String? = null
                var bestJaccard = 0.0
                for (key in coreTemplates.keys) {
                    val keyTokens = filterStopwordsAndMapSynonymsLocal(key).first.toSet()
                    if (keyTokens.isEmpty()) continue
                    val weightedJ = engine.weightedJaccard(qSetLocal, keyTokens)
                    if (weightedJ > bestJaccard) {
                        bestJaccard = weightedJ
                        bestByJaccard = key
                    }
                }
                if (bestByJaccard != null && bestJaccard >= jaccardThreshold) {
                    val possible = coreTemplates[bestByJaccard]
                    if (!possible.isNullOrEmpty()) {
                        val chosen = possible.random()
                        Log.d(TAG, "Jaccard match in $filename: $bestByJaccard ($bestJaccard) -> $chosen")
                        return resolvePotentialFileResponse(chosen)
                    }
                }

                // 4) Levenshtein: two-stage — сначала ранжируем кандидатов по weightedJaccard, затем применяем Levenshtein
                val scoredCandidates = coreTemplates.keys.map { key ->
                    val keyTokens = filterStopwordsAndMapSynonymsLocal(key).first.toSet()
                    val score = if (keyTokens.isEmpty()) 0.0 else engine.weightedJaccard(qSetLocal, keyTokens)
                    Pair(key, score)
                }.filter { it.second > 0.0 }
                    .sortedByDescending { it.second }
                    .take(maxOf(Engine.MAX_CANDIDATES_FOR_LEV, 20))

                if (scoredCandidates.isEmpty()) {
                    // fallback: если ничего не найдено по токенам, попробуем ограничиться коротким списком по длине
                    val fallbackCandidates = coreTemplates.keys
                        .filter { kotlin.math.abs(it.length - qJoinedNormalizedLocal.length) <= engine.getFuzzyDistance(qJoinedNormalizedLocal) + 2 }
                        .take(Engine.MAX_CANDIDATES_FOR_LEV)
                    for (key in fallbackCandidates) {
                        val d = engine.levenshtein(qJoinedNormalizedLocal, key, qJoinedNormalizedLocal)
                        if (d <= engine.getFuzzyDistance(qJoinedNormalizedLocal)) {
                            val possible = coreTemplates[key]
                            if (!possible.isNullOrEmpty()) {
                                val chosen = possible.random()
                                Log.d(TAG, "Levenshtein fallback match in $filename: $key (dist=$d) -> $chosen")
                                return resolvePotentialFileResponse(chosen)
                            }
                        }
                    }
                } else {
                    var bestKey: String? = null
                    var bestDist = Int.MAX_VALUE
                    val maxDist = engine.getFuzzyDistance(qJoinedNormalizedLocal)
                    for ((key, score) in scoredCandidates) {
                        val candidateNormalized = key
                        if (kotlin.math.abs(candidateNormalized.length - qJoinedNormalizedLocal.length) > maxDist + 2) continue
                        val d = engine.levenshtein(qJoinedNormalizedLocal, candidateNormalized, qJoinedNormalizedLocal)
                        if (d < bestDist) {
                            bestDist = d
                            bestKey = key
                        }
                        if (bestDist == 0) break
                    }
                    if (bestKey != null && bestDist <= engine.getFuzzyDistance(qJoinedNormalizedLocal)) {
                        val possible = coreTemplates[bestKey]
                        if (!possible.isNullOrEmpty()) {
                            val chosen = possible.random()
                            Log.d(TAG, "Levenshtein match in $filename: $bestKey (dist=$bestDist) -> $chosen")
                            return resolvePotentialFileResponse(chosen)
                        }
                    }
                }

            }

            return null
        } catch (e: Exception) {
            Log.e(TAG, "Error searching in core files", e)
            return null
        }
    }

    // ------------------ Остальное (loadTemplatesFromFile, loadFallbackTemplates, и т.д.) ------------------

    fun loadTemplatesFromFile(
        context: Context,
        folderUri: Uri?,
        filename: String,
        templatesMap: MutableMap<String, MutableList<String>>,
        keywordResponses: MutableMap<String, MutableList<String>>,
        mascotList: MutableList<Map<String, String>>,
        contextMap: MutableMap<String, String>,
        synonymsSnapshot: Map<String, String>,
        stopwordsSnapshot: Set<String>,
        metadataOut: MutableMap<String, String>
    ): Pair<Boolean, String?> {
        templatesMap.clear()
        keywordResponses.clear()
        mascotList.clear()
        if (filename == "base.txt") {
            contextMap.clear()
        }

        // defaults
        metadataOut["mascot_name"] = metadataOut["mascot_name"] ?: "Racky"
        metadataOut["mascot_icon"] = metadataOut["mascot_icon"] ?: "raccoon_icon.png"
        metadataOut["theme_color"] = metadataOut["theme_color"] ?: "#00FF00"
        metadataOut["theme_background"] = metadataOut["theme_background"] ?: "#000000"

        val uri = folderUri ?: return Pair(false, "folderUri == null")

        try {
            val dir = DocumentFile.fromTreeUri(context, uri) ?: return Pair(false, "cannot open dir")
            val file = dir.findFile(filename)
            if (file == null || !file.exists()) {
                return Pair(false, "file not found")
            }

            fun tokenizeLocal(s: String): List<String> {
                if (s.isBlank()) return emptyList()
                return s.split(Regex("\\s+")).map { it.trim() }.filter { it.isNotEmpty() }
            }

            fun filterStopwordsAndMapSynonymsLocal(input: String): Pair<List<String>, String> {
                val toks = tokenizeLocal(input)
                val mapped = toks.map { tok ->
                    val n = normalizeForIndex(tok)
                    val s = synonymsSnapshot[n] ?: n
                    s
                }.filter { it.isNotEmpty() && !stopwordsSnapshot.contains(it) }
                val joined = mapped.joinToString(" ")
                return Pair(mapped, joined)
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
                                val rawKey = parts[0].trim()
                                val contextFile = parts[1].trim()
                                val keyMapped = filterStopwordsAndMapSynonymsLocal(rawKey).second
                                if (keyMapped.isNotEmpty() && contextFile.isNotEmpty()) contextMap[keyMapped] = contextFile
                            }
                        }
                        return@forEachLine
                    }
                    if (l.startsWith("-")) {
                        val keywordLine = l.substring(1)
                        if (keywordLine.contains("=")) {
                            val parts = keywordLine.split("=", limit = 2)
                            if (parts.size == 2) {
                                val rawKey = parts[0].trim()
                                val keyMapped = filterStopwordsAndMapSynonymsLocal(rawKey).second
                                val responses = parts[1].split("|")
                                val responseList = responses.mapNotNull { it.trim().takeIf { s -> s.isNotEmpty() } }.toMutableList()
                                if (keyMapped.isNotEmpty() && responseList.isNotEmpty()) keywordResponses[keyMapped] = responseList
                            }
                        }
                        return@forEachLine
                    }
                    if (!l.contains("=")) return@forEachLine
                    val parts = l.split("=", limit = 2)
                    if (parts.size == 2) {
                        val triggerRaw = parts[0].trim()
                        val triggerNormalized = normalizeForIndex(triggerRaw)
                        val triggerFiltered = filterStopwordsAndMapSynonymsLocal(triggerNormalized).second
                        val responses = parts[1].split("|")
                        val responseList = responses.mapNotNull { it.trim().takeIf { s -> s.isNotEmpty() } }.toMutableList()
                        if (triggerFiltered.isNotEmpty() && responseList.isNotEmpty()) templatesMap[triggerFiltered] = responseList
                    }
                }
            }

            // metadata
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
                            line.startsWith("mascot_name=") -> metadataOut["mascot_name"] = line.substring("mascot_name=".length).trim()
                            line.startsWith("mascot_icon=") -> metadataOut["mascot_icon"] = line.substring("mascot_icon=".length).trim()
                            line.startsWith("theme_color=") -> metadataOut["theme_color"] = line.substring("theme_color=".length).trim()
                            line.startsWith("theme_background=") -> metadataOut["theme_background"] = line.substring("theme_background=".length).trim()
                        }
                    }
                }
            }

            // Если base и есть mascotList — выбрать случайного
            if (filename == "base.txt" && mascotList.isNotEmpty()) {
                val selected = mascotList.random()
                selected["name"]?.let { metadataOut["mascot_name"] = it }
                selected["icon"]?.let { metadataOut["mascot_icon"] = it }
                selected["color"]?.let { metadataOut["theme_color"] = it }
                selected["background"]?.let { metadataOut["theme_background"] = it }
            }

            return Pair(true, null)
        } catch (e: Exception) {
            Log.e(TAG, "Error loading templates from $filename", e)
            return Pair(false, e.message)
        }
    }

    fun loadFallbackTemplates(
        templatesMap: MutableMap<String, MutableList<String>>,
        keywordResponses: MutableMap<String, MutableList<String>>,
        mascotList: MutableList<Map<String, String>>,
        contextMap: MutableMap<String, String>
    ) {
        templatesMap.clear()
        contextMap.clear()
        keywordResponses.clear()
        mascotList.clear()

        val t1 = normalizeForIndex("привет")
        templatesMap[t1] = mutableListOf("Привет! Чем могу помочь?", "Здравствуй!")
        val t2 = normalizeForIndex("как дела")
        templatesMap[t2] = mutableListOf("Всё отлично, а у тебя?", "Нормально, как дела?")
        keywordResponses[normalizeForIndex("спасибо")] = mutableListOf("Рад, что помог!", "Всегда пожалуйста!")
    }

    fun getDummyResponse(query: String): String {
        val lower = query.lowercase(Locale.getDefault())
        return when {
            lower.contains("привет") -> "Привет! Чем могу помочь?"
            lower.contains("как дела") -> "Всё отлично, а у тебя?"
            else -> "Не понял запрос. Попробуй другой вариант."
        }
    }

    fun loadOuchMessage(context: Context, folderUri: Uri?, mascot: String): String? {
        val uri = folderUri ?: return null
        try {
            val dir = DocumentFile.fromTreeUri(context, uri) ?: return null
            val mascotFile = dir.findFile("${mascot.lowercase(Locale.getDefault())}.txt") ?: dir.findFile("ouch.txt")
            if (mascotFile != null && mascotFile.exists()) {
                context.contentResolver.openInputStream(mascotFile.uri)?.bufferedReader()?.use { reader ->
                    val allText = reader.readText()
                    val responses = allText.split("|").map { it.trim() }.filter { it.isNotEmpty() }
                    if (responses.isNotEmpty()) return responses.random()
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error loading ouch message", e)
        }
        return null
    }

    fun loadMascotMetadata(
        context: Context,
        folderUri: Uri?,
        mascotName: String,
        metadataOut: MutableMap<String, String>
    ): Pair<Boolean, String?> {
        val uri = folderUri ?: return Pair(false, "folderUri == null")
        try {
            val dir = DocumentFile.fromTreeUri(context, uri) ?: return Pair(false, "cannot open dir")
            val metadataFilename = "${mascotName.lowercase(Locale.getDefault())}_metadata.txt"
            val metadataFile = dir.findFile(metadataFilename) ?: return Pair(false, "file not found")
            if (!metadataFile.exists()) return Pair(false, "file not exists")
            context.contentResolver.openInputStream(metadataFile.uri)?.bufferedReader()?.use { reader ->
                reader.forEachLine { raw ->
                    val line = raw.trim()
                    when {
                        line.startsWith("mascot_name=") -> metadataOut["mascot_name"] = line.substring("mascot_name=".length).trim()
                        line.startsWith("mascot_icon=") -> metadataOut["mascot_icon"] = line.substring("mascot_icon=".length).trim()
                        line.startsWith("theme_color=") -> metadataOut["theme_color"] = line.substring("theme_color=".length).trim()
                        line.startsWith("theme_background=") -> metadataOut["theme_background"] = line.substring("theme_background=".length).trim()
                    }
                }
            }
            return Pair(true, null)
        } catch (e: Exception) {
            Log.e(TAG, "Error loading mascot metadata", e)
            return Pair(false, e.message)
        }
    }

    fun detectContext(input: String, contextMap: Map<String, String>, engine: Engine): String? {
        val inputTokens = engine.filterStopwordsAndMapSynonyms(input).first
        val contextScores = HashMap<String, Int>()
        for ((keyword, value) in contextMap) {
            val keywordTokens = engine.filterStopwordsAndMapSynonyms(keyword).first
            val matches = inputTokens.count { it in keywordTokens }
            if (matches > 0) contextScores[value] = contextScores.getOrDefault(value, 0) + matches
        }
        return contextScores.maxByOrNull { it.value }?.key
    }
}
