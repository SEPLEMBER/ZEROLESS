package com.nemesis.droidcrypt

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.documentfile.provider.DocumentFile
import java.util.*
import kotlin.collections.HashMap

/**
 * ChatCore — утилиты для работы с файловой системой: загрузка синонимов/стопслов, парсинг шаблонов, core-файлы.
 *
 * Все методы работают с переданными mutable коллекциями и context/uri, не зависят от UI.
 */
object ChatCore {
    private const val TAG = "ChatCore"

    /**
     * Загружает synonims.txt и stopwords.txt в переданные коллекции (очищая их).
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
                        val parts = l.split(";").map { normalizeTextLocal(it).trim() }.filter { it.isNotEmpty() }
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
                        val parts = all.split("^").map { normalizeTextLocal(it).trim() }.filter { it.isNotEmpty() }
                        for (p in parts) stopwords.add(p)
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error loading synonyms/stopwords", e)
        }
    }

    private fun normalizeTextLocal(s: String): String {
        val lower = s.lowercase(Locale.getDefault())
        val cleaned = lower.replace(Regex("[^\\p{L}\\p{Nd}\\s]"), " ")
        val collapsed = cleaned.replace(Regex("\\s+"), " ").trim()
        return collapsed
    }

    /**
     * Парсит один файл шаблонов (filename) и возвращает:
     *  - map triggers -> responses
     *  - map keywords -> responses (строки, начинающиеся с '-')
     *
     * Использует переданные snapshots для синонимов/стоп-слов.
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

            fun normalizeLocal(s: String): String {
                val lower = s.lowercase(Locale.getDefault())
                val cleaned = lower.replace(Regex("[^\\p{L}\\p{Nd}\\s]"), " ")
                return cleaned.replace(Regex("\\s+"), " ").trim()
            }

            fun tokenizeLocal(s: String): List<String> {
                if (s.isBlank()) return emptyList()
                return s.split(Regex("\\s+")).map { it.trim() }.filter { it.isNotEmpty() }
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

            context.contentResolver.openInputStream(file.uri)?.bufferedReader()?.use { reader ->
                reader.forEachLine { raw ->
                    val l = raw.trim()
                    if (l.isEmpty()) return@forEachLine
                    if (l.startsWith("-")) {
                        val keywordLine = l.substring(1)
                        if (keywordLine.contains("=")) {
                            val parts = keywordLine.split("=", limit = 2)
                            if (parts.size == 2) {
                                val keyword = parts[0].trim().lowercase(Locale.getDefault())
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
            Log.e(TAG, "Error parsing templates from $filename", e)
        }
        return Pair(localTemplates, localKeywords)
    }

    /**
     * Поиск по core1..core9.txt. Возвращает найденную строку ответа (или null).
     * Использует engine для weightedJaccard/levenshtein/getFuzzyDistance.
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

            fun normalizeLocal(s: String): String {
                val lower = s.lowercase(Locale.getDefault())
                val cleaned = lower.replace(Regex("[^\\p{L}\\p{Nd}\\s]"), " ")
                return cleaned.replace(Regex("\\s+"), " ").trim()
            }

            fun tokenizeLocal(s: String): List<String> {
                if (s.isBlank()) return emptyList()
                return s.split(Regex("\\s+")).map { it.trim() }.filter { it.isNotEmpty() }
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

            for (i in 1..9) {
                val filename = "core$i.txt"
                val file = dir.findFile(filename) ?: continue
                if (!file.exists()) continue

                val (coreTemplates, coreKeywords) = parseTemplatesFromFile(context, folderUri, filename, synonymsSnapshot, stopwordsSnapshot)
                if (coreTemplates.isEmpty() && coreKeywords.isEmpty()) continue

                // Exact
                coreTemplates[qFiltered]?.let { possible ->
                    if (possible.isNotEmpty()) {
                        val chosen = possible.random()
                        return resolvePotentialFileResponse(chosen)
                    }
                }

                // keyword search
                for ((keyword, responses) in coreKeywords) {
                    if (qFiltered.contains(keyword) && responses.isNotEmpty()) {
                        val chosen = responses.random()
                        return resolvePotentialFileResponse(chosen)
                    }
                }

                // Jaccard
                var bestByJaccard: String? = null
                var bestJaccard = 0.0
                for (key in coreTemplates.keys) {
                    val keyTokens = filterStopwordsAndMapSynonymsLocal(key).first.toSet()
                    if (keyTokens.isEmpty()) continue
                    val weightedJ = engine.weightedJaccard(qSet, keyTokens)
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

                // Levenshtein
                var bestKey: String? = null
                var bestDist = Int.MAX_VALUE
                val candidates = coreTemplates.keys.filter { kotlin.math.abs(it.length - qFiltered.length) <= engine.getFuzzyDistance(qFiltered) }
                    .take(7)
                for (key in candidates) {
                    val maxDist = engine.getFuzzyDistance(qFiltered)
                    if (kotlin.math.abs(key.length - qFiltered.length) > maxDist + 1) continue
                    val d = engine.levenshtein(qFiltered, key, qFiltered)
                    if (d < bestDist) {
                        bestDist = d
                        bestKey = key
                    }
                    if (bestDist == 0) break
                }
                if (bestKey != null && bestDist <= engine.getFuzzyDistance(qFiltered)) {
                    val possible = coreTemplates[bestKey]
                    if (!possible.isNullOrEmpty()) {
                        val chosen = possible.random()
                        return resolvePotentialFileResponse(chosen)
                    }
                }
            }

            return null
        } catch (e: Exception) {
            Log.e(TAG, "Error searching in core files", e)
            return null
        }
    }

    /**
     * Загружает filename и заполняет переданные структуры:
     * templatesMap, keywordResponses, mascotList, contextMap.
     *
     * Возвращает Pair<Boolean,String?>, где Boolean — успех (true если файл прочитан),
     * второе — сообщение ошибки (или null).
     *
     * metadataOut: mutable map куда будут записаны значения:
     *  - "mascot_name"
     *  - "mascot_icon"
     *  - "theme_color"
     *  - "theme_background"
     *
     * synonymsSnapshot/stopwordsSnapshot используются для фильтрации триггеров (та же логика, что раньше).
     */
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

            fun normalizeLocal(s: String): String {
                val lower = s.lowercase(Locale.getDefault())
                val cleaned = lower.replace(Regex("[^\\p{L}\\p{Nd}\\s]"), " ")
                return cleaned.replace(Regex("\\s+"), " ").trim()
            }

            fun tokenizeLocal(s: String): List<String> {
                if (s.isBlank()) return emptyList()
                return s.split(Regex("\\s+")).map { it.trim() }.filter { it.isNotEmpty() }
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

            context.contentResolver.openInputStream(file.uri)?.bufferedReader()?.use { reader ->
                reader.forEachLine { raw ->
                    val l = raw.trim()
                    if (l.isEmpty()) return@forEachLine
                    if (filename == "base.txt" && l.startsWith(":") && l.endsWith(":")) {
                        val contextLine = l.substring(1, l.length - 1)
                        if (contextLine.contains("=")) {
                            val parts = contextLine.split("=", limit = 2)
                            if (parts.size == 2) {
                                val keyword = parts[0].trim().lowercase(Locale.getDefault())
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
                                val keyword = parts[0].trim().lowercase(Locale.getDefault())
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
                        val trigger = normalizeLocal(triggerRaw)
                        val triggerFiltered = filterStopwordsAndMapSynonymsLocal(trigger).second
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
}
