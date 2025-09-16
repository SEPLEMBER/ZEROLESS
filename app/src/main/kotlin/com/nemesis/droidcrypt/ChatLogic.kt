package com.nemesis.droidcrypt

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import java.util.*
import kotlin.math.abs
import kotlin.math.min

class ChatLogic(
    private val context: Context,
    var folderUri: Uri?,
    private val ui: ChatUi
) {

    companion object {
        private const val MAX_FUZZY_DISTANCE = 5
        private const val CANDIDATE_TOKEN_THRESHOLD = 1
        private const val MAX_CANDIDATES_FOR_LEV = 40
        private const val JACCARD_THRESHOLD = 0.50
    }

    // Data structures
    private val fallback = arrayOf("Привет", "Как дела?", "Расскажи о себе", "Выход")
    val templatesMap = HashMap<String, MutableList<String>>()
    private val contextMap = HashMap<String, String>()
    private val keywordResponses = HashMap<String, MutableList<String>>()
    private val antiSpamResponses = mutableListOf<String>()
    private val mascotList = mutableListOf<Map<String, String>>()
    private val dialogLines = mutableListOf<String>()
    private val dialogs = mutableListOf<Dialog>()

    private val invertedIndex = HashMap<String, MutableList<String>>()
    private val synonymsMap = HashMap<String, String>()
    private val stopwords = HashSet<String>()

    private var currentMascotName = "Racky"
    private var currentMascotIcon = "raccoon_icon.png"
    private var currentThemeColor = "#00FF00"
    private var currentThemeBackground = "#000000"
    var currentContext = "base.txt"
        private set

    private var lastQuery = ""
    private val random = Random()
    private val queryCountMap = HashMap<String, Int>()

    // Dialog data class kept private inside logic
    private data class Dialog(val name: String, val replies: MutableList<Map<String, String>> = mutableListOf())

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
        loadSynonymsAndStopwords()
    }

    interface ChatUi {
        fun addChatMessage(sender: String, text: String)
        fun showTypingIndicator()
        fun startIdleTimer()
        fun updateAutoComplete(suggestions: List<String>)
        fun updateUI(mascotName: String, mascotIcon: String, themeColor: String, themeBackground: String)
        fun showCustomToast(message: String)
        fun triggerRandomDialog()
    }

    fun setFolderUri(uri: Uri?) {
        folderUri = uri
    }

    fun processUserQuery(userInput: String) {
        val qOrigRaw = userInput.trim()
        val qOrig = normalizeText(qOrigRaw)
        val (qTokensFiltered, qFiltered) = filterStopwordsAndMapSynonyms(qOrig)

        val qKeyForCount = qFiltered
        if (qFiltered.isEmpty()) return

        if (qKeyForCount == lastQuery) {
            val cnt = queryCountMap.getOrDefault(qKeyForCount, 0)
            queryCountMap[qKeyForCount] = cnt + 1
        } else {
            queryCountMap.clear()
            queryCountMap[qKeyForCount] = 1
            lastQuery = qKeyForCount
        }
        ui.addChatMessage("Ты", userInput)
        ui.showTypingIndicator()

        val repeats = queryCountMap.getOrDefault(qKeyForCount, 0)
        if (repeats >= 5) {
            val spamResp = antiSpamResponses.random()
            ui.addChatMessage(currentMascotName, spamResp)
            ui.startIdleTimer()
            return
        }

        var answered = false

        // 1. Exact
        templatesMap[qFiltered]?.let { possible ->
            if (possible.isNotEmpty()) {
                ui.addChatMessage(currentMascotName, possible.random())
                answered = true
            }
        }

        // 2. Keywords
        if (!answered) {
            for ((keyword, responses) in keywordResponses) {
                if (qFiltered.contains(keyword) && responses.isNotEmpty()) {
                    ui.addChatMessage(currentMascotName, responses.random())
                    answered = true
                    break
                }
            }
        }

        // 2.5 Fuzzy (inverted index -> Jaccard -> Levenshtein)
        if (!answered) {
            val qTokens = if (qTokensFiltered.isNotEmpty()) qTokensFiltered else tokenize(qFiltered)
            val candidateCounts = HashMap<String, Int>()
            for (tok in qTokens) {
                invertedIndex[tok]?.forEach { trig ->
                    candidateCounts[trig] = candidateCounts.getOrDefault(trig, 0) + 1
                }
            }
            val candidates = if (candidateCounts.isNotEmpty()) {
                candidateCounts.entries
                    .filter { it.value >= CANDIDATE_TOKEN_THRESHOLD }
                    .sortedByDescending { it.value }
                    .map { it.key }
                    .take(MAX_CANDIDATES_FOR_LEV)
            } else {
                templatesMap.keys.filter { abs(it.length - qFiltered.length) <= MAX_FUZZY_DISTANCE }
                    .take(MAX_CANDIDATES_FOR_LEV)
            }

            var bestByJaccard: String? = null
            var bestJaccard = 0.0
            val qSet = qTokens.toSet()
            for (key in candidates) {
                val keyTokens = filterStopwordsAndMapSynonyms(key).first.toSet()
                if (keyTokens.isEmpty()) continue
                val inter = qSet.intersect(keyTokens).size.toDouble()
                val union = qSet.union(keyTokens).size.toDouble()
                val j = if (union == 0.0) 0.0 else inter / union
                if (j > bestJaccard) {
                    bestJaccard = j
                    bestByJaccard = key
                }
            }
            if (bestByJaccard != null && bestJaccard >= JACCARD_THRESHOLD) {
                val possible = templatesMap[bestByJaccard]
                if (!possible.isNullOrEmpty()) {
                    ui.addChatMessage(currentMascotName, possible.random())
                    answered = true
                }
            }

            if (!answered) {
                var bestKey: String? = null
                var bestDist = Int.MAX_VALUE
                for (key in candidates) {
                    if (abs(key.length - qFiltered.length) > MAX_FUZZY_DISTANCE + 1) continue
                    val d = levenshtein(qFiltered, key)
                    if (d < bestDist) {
                        bestDist = d
                        bestKey = key
                    }
                    if (bestDist == 0) break
                }
                if (bestKey != null && bestDist <= MAX_FUZZY_DISTANCE) {
                    val possible = templatesMap[bestKey]
                    if (!possible.isNullOrEmpty()) {
                        ui.addChatMessage(currentMascotName, possible.random())
                        answered = true
                    }
                }
            }
        }

        // 3. Context switch
        if (!answered) {
            detectContext(qFiltered)?.let { newContext ->
                if (newContext != currentContext) {
                    currentContext = newContext
                    loadTemplatesFromFile(currentContext)
                    ui.updateAutoComplete(buildAutoCompleteSuggestions())
                    ui.updateUI(currentMascotName, currentMascotIcon, currentThemeColor, currentThemeBackground)
                    templatesMap[qFiltered]?.let { possible ->
                        if (possible.isNotEmpty()) {
                            ui.addChatMessage(currentMascotName, possible.random())
                            answered = true
                        }
                    }
                }
            }
        }

        // 4. fallback
        if (!answered) {
            val fallbackResp = getDummyResponse(qOrig)
            ui.addChatMessage(currentMascotName, fallbackResp)
        }

        ui.triggerRandomDialog()
        ui.startIdleTimer()
    }

    private fun getDummyResponse(query: String): String {
        val lower = query.lowercase(Locale.ROOT)
        return when {
            lower.contains("привет") -> "Привет! Чем могу помочь?"
            lower.contains("как дела") -> "Всё отлично, а у тебя?"
            else -> "Не понял запрос. Попробуй другой вариант."
        }
    }

    private fun normalizeText(s: String): String {
        val lower = s.lowercase(Locale.getDefault())
        val cleaned = lower.replace(Regex("[^\\p{L}\\p{Nd}\\s]"), " ")
        val collapsed = cleaned.replace(Regex("\\s+"), " ").trim()
        return collapsed
    }

    private fun tokenize(s: String): List<String> {
        if (s.isBlank()) return emptyList()
        return s.split(Regex("\\s+")).map { it.trim() }.filter { it.isNotEmpty() }
    }

    private fun loadSynonymsAndStopwords() {
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
                        for (p in parts) synonymsMap[p] = canonical
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
            // ignore silently
        }
    }

    private fun filterStopwordsAndMapSynonyms(input: String): Pair<List<String>, String> {
        val toks = tokenize(input)
        val mapped = toks.map { tok ->
            val n = normalizeText(tok)
            val s = synonymsMap[n] ?: n
            s
        }.filter { it.isNotEmpty() && !stopwords.contains(it) }
        val joined = mapped.joinToString(" ")
        return Pair(mapped, joined)
    }

    private fun rebuildInvertedIndex() {
        invertedIndex.clear()
        for (key in templatesMap.keys) {
            val toks = filterStopwordsAndMapSynonyms(key).first
            for (t in toks) {
                val list = invertedIndex.getOrPut(t) { mutableListOf() }
                if (!list.contains(key)) list.add(key)
            }
        }
    }

    fun rebuildIndexPublic() {
        rebuildInvertedIndex()
    }

    private fun levenshtein(s: String, t: String): Int {
        if (s == t) return 0
        val n = s.length
        val m = t.length
        if (n == 0) return m
        if (m == 0) return n
        if (abs(n - m) > MAX_FUZZY_DISTANCE + 2) return Int.MAX_VALUE / 2

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
            if (minInRow > MAX_FUZZY_DISTANCE + 2) return Int.MAX_VALUE / 2
            for (k in 0..m) prev[k] = curr[k]
        }
        return prev[m]
    }

    fun loadTemplatesFromFile(filename: String) {
        templatesMap.clear()
        keywordResponses.clear()
        mascotList.clear()
        dialogLines.clear()
        dialogs.clear()

        if (filename == "base.txt") {
            contextMap.clear()
        }

        currentMascotName = "Racky"
        currentMascotIcon = "raccoon_icon.png"
        currentThemeColor = "#00FF00"
        currentThemeBackground = "#000000"

        loadSynonymsAndStopwords()

        if (folderUri == null) {
            loadFallbackTemplates()
            rebuildInvertedIndex()
            ui.updateUI(currentMascotName, currentMascotIcon, currentThemeColor, currentThemeBackground)
            ui.updateAutoComplete(buildAutoCompleteSuggestions())
            return
        }

        try {
            val dir = DocumentFile.fromTreeUri(context, folderUri!!) ?: run {
                loadFallbackTemplates()
                rebuildInvertedIndex()
                ui.updateUI(currentMascotName, currentMascotIcon, currentThemeColor, currentThemeBackground)
                ui.updateAutoComplete(buildAutoCompleteSuggestions())
                return
            }

            val file = dir.findFile(filename)
            if (file == null || !file.exists()) {
                loadFallbackTemplates()
                rebuildInvertedIndex()
                ui.updateUI(currentMascotName, currentMascotIcon, currentThemeColor, currentThemeBackground)
                ui.updateAutoComplete(buildAutoCompleteSuggestions())
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
                                if (keyword.isNotEmpty() && contextFile.isNotEmpty()) contextMap[keyword] =
                                    contextFile
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
                                val responseList =
                                    responses.mapNotNull { it.trim().takeIf { s -> s.isNotEmpty() } }
                                        .toMutableList()
                                if (keyword.isNotEmpty() && responseList.isNotEmpty()) keywordResponses[keyword] =
                                    responseList
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
                        val responseList =
                            responses.mapNotNull { it.trim().takeIf { s -> s.isNotEmpty() } }
                                .toMutableList()
                        if (triggerFiltered.isNotEmpty() && responseList.isNotEmpty()) templatesMap[triggerFiltered] =
                            responseList
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
                            line.startsWith("mascot_name=") -> currentMascotName =
                                line.substring("mascot_name=".length).trim()
                            line.startsWith("mascot_icon=") -> currentMascotIcon =
                                line.substring("mascot_icon=".length).trim()
                            line.startsWith("theme_color=") -> currentThemeColor =
                                line.substring("theme_color=".length).trim()
                            line.startsWith("theme_background=") -> currentThemeBackground =
                                line.substring("theme_background=".length).trim()
                            line.startsWith("dialog_lines=") -> {
                                val lines = line.substring("dialog_lines=".length).split("|")
                                for (d in lines) {
                                    val t = d.trim(); if (t.isNotEmpty()) dialogLines.add(t)
                                }
                            }
                        }
                    }
                }
            }

            val dialogFile = dir.findFile("randomreply.txt")
            if (dialogFile != null && dialogFile.exists()) {
                try {
                    context.contentResolver.openInputStream(dialogFile.uri)?.bufferedReader()
                        ?.use { reader ->
                            var currentDialogParser: Dialog? = null
                            reader.forEachLine { raw ->
                                val l = raw.trim()
                                if (l.isEmpty()) return@forEachLine

                                if (l.startsWith(";")) {
                                    currentDialogParser?.takeIf { it.replies.isNotEmpty() }
                                        ?.let { dialogs.add(it) }
                                    currentDialogParser = Dialog(l.substring(1).trim())
                                    return@forEachLine
                                }

                                if (l.contains(">")) {
                                    val parts = l.split(">", limit = 2)
                                    if (parts.size == 2) {
                                        val mascot = parts[0].trim()
                                        val text = parts[1].trim()
                                        if (mascot.isNotEmpty() && text.isNotEmpty()) {
                                            val cur = currentDialogParser
                                                ?: Dialog("default").also { currentDialogParser = it }
                                            cur.replies.add(mapOf("mascot" to mascot, "text" to text))
                                        }
                                    }
                                    return@forEachLine
                                }

                                dialogLines.add(l)
                            }
                            currentDialogParser?.takeIf { it.replies.isNotEmpty() }
                                ?.let { dialogs.add(it) }
                        }
                } catch (e: Exception) {
                    ui.showCustomToast("Ошибка чтения randomreply.txt: ${e.message}")
                }
            }

            if (filename == "base.txt" && mascotList.isNotEmpty()) {
                val selected = mascotList.random()
                selected["name"]?.let { currentMascotName = it }
                selected["icon"]?.let { currentMascotIcon = it }
                selected["color"]?.let { currentThemeColor = it }
                selected["background"]?.let { currentThemeBackground = it }
            }

            rebuildInvertedIndex()
            ui.updateUI(currentMascotName, currentMascotIcon, currentThemeColor, currentThemeBackground)
            ui.updateAutoComplete(buildAutoCompleteSuggestions())

        } catch (e: Exception) {
            ui.showCustomToast("Ошибка чтения файла: ${e.message}")
            loadFallbackTemplates()
            rebuildInvertedIndex()
            ui.updateUI(currentMascotName, currentMascotIcon, currentThemeColor, currentThemeBackground)
            ui.updateAutoComplete(buildAutoCompleteSuggestions())
        }
    }

    private fun loadFallbackTemplates() {
        templatesMap.clear()
        contextMap.clear()
        keywordResponses.clear()
        dialogs.clear()
        dialogLines.clear()
        mascotList.clear()

        val t1 = filterStopwordsAndMapSynonyms(normalizeText("привет")).second
        templatesMap[t1] = mutableListOf("Привет! Чем могу помочь?", "Здравствуй!")
        val t2 = filterStopwordsAndMapSynonyms(normalizeText("как дела")).second
        templatesMap[t2] = mutableListOf("Всё отлично, а у тебя?", "Нормально, как дела?")
        keywordResponses["спасибо"] = mutableListOf("Рад, что помог!", "Всегда пожалуйста!")
    }

    private fun detectContext(input: String): String? {
        val lower = normalizeText(input)
        for ((keyword, value) in contextMap) {
            if (lower.contains(keyword)) return value
        }
        return null
    }

    fun buildAutoCompleteSuggestions(): List<String> {
        val suggestions = mutableListOf<String>()
        suggestions.addAll(templatesMap.keys)
        for (s in fallback) {
            val low = s.lowercase(Locale.ROOT)
            if (!suggestions.contains(low)) suggestions.add(low)
        }
        return suggestions
    }
}
