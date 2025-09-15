package com.nemesis.droidcrypt

import android.animation.ObjectAnimator
import android.content.Intent
import android.graphics.BitmapFactory
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.preference.PreferenceManager
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.documentfile.provider.DocumentFile
import java.util.*
import kotlin.math.abs
import kotlin.math.min

class ChatActivity : AppCompatActivity() {

    companion object {
        private const val PREF_KEY_FOLDER_URI = "pref_folder_uri"
        private const val PREF_KEY_DISABLE_SCREENSHOTS = "pref_disable_screenshots"
        private const val MAX_MESSAGES = 250
        private const val MAX_FUZZY_DISTANCE = 2
        private const val CANDIDATE_TOKEN_THRESHOLD = 1
        private const val MAX_CANDIDATES_FOR_LEV = 40
    }

    // UI
    private var folderUri: Uri? = null
    private lateinit var scrollView: ScrollView
    private lateinit var queryInput: AutoCompleteTextView
    private var envelopeInputButton: ImageButton? = null
    private var mascotTopImage: ImageView? = null
    private var btnLock: ImageButton? = null
    private var btnTrash: ImageButton? = null
    private var btnEnvelopeTop: ImageButton? = null
    private var btnSettings: ImageButton? = null
    private lateinit var messagesContainer: LinearLayout
    private var adapter: ArrayAdapter<String>? = null

    // Data
    private val fallback = arrayOf("Привет", "Как дела?", "Расскажи о себе", "Выход")
    private val templatesMap = HashMap<String, MutableList<String>>() // normalized triggers -> responses
    private val contextMap = HashMap<String, String>()
    private val keywordResponses = HashMap<String, MutableList<String>>()
    private val antiSpamResponses = mutableListOf<String>()
    private val mascotList = mutableListOf<Map<String, String>>()
    private val dialogLines = mutableListOf<String>()
    private val dialogs = mutableListOf<Dialog>()

    // synonyms map (MUST exist)
    private val synonymsMap = HashMap<String, MutableList<String>>()

    // inverted index: token -> list of normalized triggers
    private val invertedIndex = HashMap<String, MutableList<String>>()

    private var currentMascotName = "Racky"
    private var currentMascotIcon = "raccoon_icon.png"
    private var currentThemeColor = "#00FF00"
    private var currentThemeBackground = "#000000"
    private var currentContext = "base.txt"
    private var lastQuery = ""

    // Dialogs / idle
    private var currentDialog: Dialog? = null
    private var currentDialogIndex = 0
    private val dialogHandler = Handler(Looper.getMainLooper())
    private var dialogRunnable: Runnable? = null
    private var idleCheckRunnable: Runnable? = null
    private var lastUserInputTime = System.currentTimeMillis()
    private val random = Random()
    private val queryCountMap = HashMap<String, Int>()

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
    }

    // --- Lifecycle ---
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_chat)

        scrollView = findViewById(R.id.scrollView)
        queryInput = findViewById(R.id.queryInput)
        envelopeInputButton = findViewById(R.id.envelope_button)
        mascotTopImage = findViewById(R.id.mascot_top_image)
        btnLock = findViewById(R.id.btn_lock)
        btnTrash = findViewById(R.id.btn_trash)
        btnEnvelopeTop = findViewById(R.id.btn_envelope_top)
        btnSettings = findViewById(R.id.btn_settings)
        messagesContainer = findViewById(R.id.chatMessagesContainer)

        // SAF Uri
        folderUri = intent?.getParcelableExtra("folderUri")
        if (folderUri == null) {
            for (perm in contentResolver.persistedUriPermissions) {
                if (perm.isReadPermission) {
                    folderUri = perm.uri; break
                }
            }
        }
        try {
            val prefs = PreferenceManager.getDefaultSharedPreferences(this)
            if (folderUri == null) {
                val saved = prefs.getString(PREF_KEY_FOLDER_URI, null)
                if (saved != null) folderUri = Uri.parse(saved)
            }
        } catch (_: Exception) {
        }

        // screenshots lock
        try {
            val prefs = PreferenceManager.getDefaultSharedPreferences(this)
            val disable = prefs.getBoolean(PREF_KEY_DISABLE_SCREENSHOTS, false)
            if (disable) window.addFlags(WindowManager.LayoutParams.FLAG_SECURE) else window.clearFlags(
                WindowManager.LayoutParams.FLAG_SECURE
            )
        } catch (_: Exception) {
        }

        loadToolbarIcons()
        setupIconTouchEffect(btnLock)
        setupIconTouchEffect(btnTrash)
        setupIconTouchEffect(btnEnvelopeTop)
        setupIconTouchEffect(btnSettings)
        setupIconTouchEffect(envelopeInputButton)

        btnLock?.setOnClickListener { finish() }
        btnTrash?.setOnClickListener { clearChat() }
        btnSettings?.setOnClickListener { startActivity(Intent(this, SettingsActivity::class.java)) }
        btnEnvelopeTop?.setOnClickListener { showCustomToast("Envelope top — заглушка") }

        envelopeInputButton?.setOnClickListener {
            val input = queryInput.text.toString().trim()
            if (input.isNotEmpty()) {
                processUserQuery(input)
                queryInput.setText("")
            }
        }

        // initial parse / fallback
        if (folderUri == null) {
            showCustomToast("Папка не выбрана! Открой настройки и выбери папку.")
            loadFallbackTemplates()
            loadSynonyms()
            rebuildInvertedIndex()
            updateAutoComplete()
            addChatMessage(currentMascotName, "Добро пожаловать!")
        } else {
            loadTemplatesFromFile(currentContext)
            loadSynonyms()
            rebuildInvertedIndex()
            updateAutoComplete()
            addChatMessage(currentMascotName, "Добро пожаловать!")
        }

        queryInput.setOnItemClickListener { parent, _, position, _ ->
            val selected = parent.getItemAtPosition(position) as String
            queryInput.setText(selected)
            processUserQuery(selected)
        }

        // idle runnable
        idleCheckRunnable = object : Runnable {
            override fun run() {
                val idle = System.currentTimeMillis() - lastUserInputTime
                if (idle >= 25000) {
                    if (dialogs.isNotEmpty()) startRandomDialog() else if (dialogLines.isNotEmpty()) triggerRandomDialog()
                }
                dialogHandler.postDelayed(this, 5000)
            }
        }
        idleCheckRunnable?.let { dialogHandler.postDelayed(it, 5000) }
    }

    override fun onResume() {
        super.onResume()
        folderUri?.let { loadTemplatesFromFile(currentContext) }
        loadSynonyms()
        rebuildInvertedIndex()
        updateAutoComplete()
        idleCheckRunnable?.let {
            dialogHandler.removeCallbacks(it)
            dialogHandler.postDelayed(it, 5000)
        }
        loadToolbarIcons()
    }

    override fun onPause() {
        super.onPause()
        stopDialog()
        dialogHandler.removeCallbacksAndMessages(null)
    }

    // --- Toolbar Helpers ---
    private fun setupIconTouchEffect(btn: ImageButton?) {
        btn?.setOnTouchListener { v, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> v.alpha = 0.6f
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> v.alpha = 1.0f
            }
            false
        }
    }

    private fun loadToolbarIcons() {
        val uri = folderUri ?: return
        try {
            val dir = DocumentFile.fromTreeUri(this, uri) ?: return

            fun tryLoad(name: String, target: ImageButton?) {
                try {
                    val file = dir.findFile(name)
                    if (file != null && file.exists()) {
                        contentResolver.openInputStream(file.uri)?.use { ins ->
                            val bmp = BitmapFactory.decodeStream(ins)
                            target?.setImageBitmap(bmp)
                        }
                    }
                } catch (_: Exception) {}
            }
            tryLoad("lock.png", btnLock)
            tryLoad("trash.png", btnTrash)
            tryLoad("envelope.png", btnEnvelopeTop)
            tryLoad("settings.png", btnSettings)

            val iconFile = dir.findFile(currentMascotIcon)
            if (iconFile != null && iconFile.exists()) {
                contentResolver.openInputStream(iconFile.uri)?.use { ins ->
                    val bmp = BitmapFactory.decodeStream(ins)
                    mascotTopImage?.let { imageView ->
                        imageView.setImageBitmap(bmp)
                        imageView.alpha = 0f
                        ObjectAnimator.ofFloat(imageView, "alpha", 0f, 1f).apply {
                            duration = 400
                            start()
                        }
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    // --- Core chat logic ---
    private fun processUserQuery(userInput: String) {
        val qOrigRaw = userInput.trim()
        val qOrig = normalizeText(qOrigRaw)
        val qKeyForCount = qOrig

        if (qOrig.isEmpty()) return
        lastUserInputTime = System.currentTimeMillis()
        stopDialog()
        if (qKeyForCount == lastQuery) {
            val cnt = queryCountMap.getOrDefault(qKeyForCount, 0)
            queryCountMap[qKeyForCount] = cnt + 1
        } else {
            queryCountMap.clear()
            queryCountMap[qKeyForCount] = 1
            lastQuery = qKeyForCount
        }
        addChatMessage("Ты", userInput)

        showTypingIndicator()

        val repeats = queryCountMap.getOrDefault(qKeyForCount, 0)
        if (repeats >= 5) {
            val spamResp = antiSpamResponses.random()
            addChatMessage(currentMascotName, spamResp)
            startIdleTimer()
            return
        }
        var answered = false

        // 1. Exact match
        templatesMap[qOrig]?.let { possible ->
            if (possible.isNotEmpty()) {
                addChatMessage(currentMascotName, possible.random())
                answered = true
            }
        }

        // 2. Keyword responses
        if (!answered) {
            for ((keyword, responses) in keywordResponses) {
                if (qOrig.contains(keyword) && responses.isNotEmpty()) {
                    addChatMessage(currentMascotName, responses.random())
                    answered = true
                    break
                }
            }
        }

        // 2.1 Jaccard match
        if (!answered) {
            val matchResult = findBestJaccardMatch(qOrig)
            matchResult?.let { (bestKey, _) ->
                val possible = templatesMap[bestKey]
                if (!possible.isNullOrEmpty()) {
                    addChatMessage(currentMascotName, possible.random())
                    answered = true
                }
            }
        }

        // 2.5 Fuzzy via inverted index + Levenshtein
        if (!answered) {
            val qTokens = tokenize(qOrig)
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
                templatesMap.keys.filter { abs(it.length - qOrig.length) <= MAX_FUZZY_DISTANCE }
                    .take(MAX_CANDIDATES_FOR_LEV)
            }

            var bestKey: String? = null
            var bestDist = Int.MAX_VALUE
            for (key in candidates) {
                if (abs(key.length - qOrig.length) > MAX_FUZZY_DISTANCE + 1) continue
                val d = levenshtein(qOrig, key)
                if (d < bestDist) {
                    bestDist = d
                    bestKey = key
                }
                if (bestDist == 0) break
            }
            if (bestKey != null && bestDist <= MAX_FUZZY_DISTANCE) {
                val possible = templatesMap[bestKey]
                if (!possible.isNullOrEmpty()) {
                    addChatMessage(currentMascotName, possible.random())
                    answered = true
                }
            }
        }

        // 3. Try switch context
        if (!answered) {
            detectContext(qOrig)?.let { newContext ->
                if (newContext != currentContext) {
                    currentContext = newContext
                    loadTemplatesFromFile(currentContext)
                    loadSynonyms()
                    rebuildInvertedIndex()
                    updateAutoComplete()
                    templatesMap[qOrig]?.let { possible ->
                        if (possible.isNotEmpty()) {
                            addChatMessage(currentMascotName, possible.random())
                            answered = true
                        }
                    }
                }
            }
        }

        // 4. fallback
        if (!answered) {
            val fallbackResp = getDummyResponse(qOrig)
            addChatMessage(currentMascotName, fallbackResp)
        }

        // idle events
        triggerRandomDialog()
        startIdleTimer()
    }

    private fun showTypingIndicator() {
        // remove any existing typing indicator
        val childrenToRemove = mutableListOf<View>()
        for (i in 0 until messagesContainer.childCount) {
            val child = messagesContainer.getChildAt(i)
            if (child.tag == "typing") childrenToRemove.add(child)
        }
        for (c in childrenToRemove) messagesContainer.removeView(c)

        val typingView = TextView(this).apply {
            text = "печатает..."
            textSize = 14f
            setTextColor(Color.WHITE)
            setBackgroundColor(0x80000000.toInt())
            alpha = 0.9f
            setPadding(16, 8, 16, 8)
            tag = "typing"
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                setMargins(8, 8, 8, 8)
                gravity = Gravity.START
            }
        }
        messagesContainer.addView(typingView)
        scrollToBottomDelayed()

        // random delay 1-3s then remove
        val randomDelay = (1000..3000).random().toLong()
        Handler(Looper.getMainLooper()).postDelayed({
            messagesContainer.removeView(typingView)
        }, randomDelay)
    }

    private fun clearChat() {
        messagesContainer.removeAllViews()
        queryCountMap.clear()
        lastQuery = ""
        currentContext = "base.txt"
        loadTemplatesFromFile(currentContext)
        loadSynonyms()
        rebuildInvertedIndex()
        updateAutoComplete()
        addChatMessage(currentMascotName, "Чат очищен. Возвращаюсь к началу.")
    }

    private fun loadSynonyms() {
        synonymsMap.clear()
        val uri = folderUri ?: return
        try {
            val dir = DocumentFile.fromTreeUri(this, uri) ?: return
            val file = dir.findFile("synonims.txt") ?: return
            contentResolver.openInputStream(file.uri)?.bufferedReader()?.use { reader ->
                reader.forEachLine { raw ->
                    val line = raw.trim()
                    if (line.isEmpty() || line.startsWith("#")) return@forEachLine
                    if (line.contains("=")) {
                        val parts = line.split("=", limit = 2)
                        if (parts.size == 2) {
                            val mainWord = normalizeText(parts[0].trim())
                            val synonyms = parts[1].split(",", "|")
                                .mapNotNull { normalizeText(it.trim()).takeIf { s -> s.isNotEmpty() } }
                                .toMutableList()
                            if (mainWord.isNotEmpty() && synonyms.isNotEmpty()) synonymsMap[mainWord] = synonyms
                        }
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun detectContext(input: String): String? {
        val scores = mutableMapOf<String, Double>()
        for ((keyword, contextFile) in contextMap) {
            val jaccard = jaccardWithSynonyms(input, keyword)
            if (jaccard > 0.3) {
                scores[contextFile] = jaccard
            }
        }
        return scores.maxByOrNull { it.value }?.key
    }

    private fun getDummyResponse(query: String): String {
        val lower = query.lowercase(Locale.getDefault())
        return when {
            lower.contains("привет") -> "Привет! Чем могу помочь?"
            lower.contains("как дела") -> "Всё отлично, а у тебя?"
            else -> "Не понял запрос. Попробуй другой вариант."
        }
    }

    private fun normalizeText(s: String): String {
        val lower = s.lowercase(Locale.getDefault())
        val cleaned = lower.replace(Regex("[^\\p{L}\\p{Nd}\\s]"), " ")
        return cleaned.replace(Regex("\\s+"), " ").trim()
    }

    private fun tokenize(s: String): List<String> {
        if (s.isBlank()) return emptyList()
        return s.split(Regex("\\s+")).map { it.trim() }.filter { it.isNotEmpty() }
    }

    private fun expandWithSynonyms(tokens: List<String>): Set<String> {
        val expanded = mutableSetOf<String>()
        for (token in tokens) {
            expanded.add(token)
            synonymsMap[token]?.let { expanded.addAll(it) }
            // reverse lookup
            for ((mainWord, synonymList) in synonymsMap) {
                if (synonymList.contains(token)) {
                    expanded.add(mainWord)
                    expanded.addAll(synonymList)
                }
            }
        }
        return expanded
    }

    private fun jaccardWithSynonyms(text1: String, text2: String): Double {
        val tokens1 = expandWithSynonyms(tokenize(normalizeText(text1)))
        val tokens2 = expandWithSynonyms(tokenize(normalizeText(text2)))
        if (tokens1.isEmpty() && tokens2.isEmpty()) return 1.0
        if (tokens1.isEmpty() || tokens2.isEmpty()) return 0.0
        val intersection = tokens1.intersect(tokens2).size
        val union = tokens1.union(tokens2).size
        return intersection.toDouble() / union
    }

    private fun findBestJaccardMatch(query: String): Pair<String, Double>? {
        var bestKey: String? = null
        var bestScore = 0.0
        for (key in templatesMap.keys) {
            val minScore = if (tokenize(key).size <= 3) 0.7 else 0.6
            val score = jaccardWithSynonyms(query, key)
            if (score > bestScore && score >= minScore) {
                bestScore = score
                bestKey = key
            }
        }
        return if (bestKey != null) Pair(bestKey, bestScore) else null
    }

    private fun rebuildInvertedIndex() {
        invertedIndex.clear()
        for (key in templatesMap.keys) {
            val toks = expandWithSynonyms(tokenize(key))
            for (t in toks) {
                val list = invertedIndex.getOrPut(t) { mutableListOf() }
                if (!list.contains(key)) list.add(key)
            }
        }
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

    // --- Template loading ---
    private fun loadTemplatesFromFile(filename: String) {
        templatesMap.clear()
        keywordResponses.clear()
        mascotList.clear()
        dialogLines.clear()
        dialogs.clear()

        if (filename == "base.txt") contextMap.clear()

        currentMascotName = "Racky"
        currentMascotIcon = "raccoon_icon.png"
        currentThemeColor = "#00FF00"
        currentThemeBackground = "#000000"

        if (folderUri == null) {
            loadFallbackTemplates()
            rebuildInvertedIndex()
            updateUI(currentMascotName, currentMascotIcon, currentThemeColor, currentThemeBackground)
            return
        }

        try {
            val dir = DocumentFile.fromTreeUri(this, folderUri!!) ?: run {
                loadFallbackTemplates()
                rebuildInvertedIndex()
                updateUI(currentMascotName, currentMascotIcon, currentThemeColor, currentThemeBackground)
                return
            }

            val file = dir.findFile(filename)
            if (file == null || !file.exists()) {
                loadFallbackTemplates()
                rebuildInvertedIndex()
                updateUI(currentMascotName, currentMascotIcon, currentThemeColor, currentThemeBackground)
                return
            }

            contentResolver.openInputStream(file.uri)?.bufferedReader()?.use { reader ->
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
                        val trigger = normalizeText(triggerRaw)
                        val responses = parts[1].split("|")
                        val responseList = responses.mapNotNull { it.trim().takeIf { s -> s.isNotEmpty() } }.toMutableList()
                        if (trigger.isNotEmpty() && responseList.isNotEmpty()) templatesMap[trigger] = responseList
                    }
                }
            }

            // metadata
            val metadataFilename = filename.replace(".txt", "_metadata.txt")
            val metadataFile = dir.findFile(metadataFilename)
            if (metadataFile != null && metadataFile.exists()) {
                contentResolver.openInputStream(metadataFile.uri)?.bufferedReader()?.use { reader ->
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
                            line.startsWith("mascot_name=") -> currentMascotName = line.substring("mascot_name=".length).trim()
                            line.startsWith("mascot_icon=") -> currentMascotIcon = line.substring("mascot_icon=".length).trim()
                            line.startsWith("theme_color=") -> currentThemeColor = line.substring("theme_color=".length).trim()
                            line.startsWith("theme_background=") -> currentThemeBackground = line.substring("theme_background=".length).trim()
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

            // randomreply.txt parsing
            val dialogFile = dir.findFile("randomreply.txt")
            if (dialogFile != null && dialogFile.exists()) {
                try {
                    contentResolver.openInputStream(dialogFile.uri)?.bufferedReader()?.use { reader ->
                        var currentDialogParser: Dialog? = null
                        reader.forEachLine { raw ->
                            val l = raw.trim()
                            if (l.isEmpty()) return@forEachLine

                            if (l.startsWith(";")) {
                                currentDialogParser?.takeIf { it.replies.isNotEmpty() }?.let { dialogs.add(it) }
                                currentDialogParser = Dialog(l.substring(1).trim())
                                return@forEachLine
                            }

                            if (l.contains(">")) {
                                val parts = l.split(">", limit = 2)
                                if (parts.size == 2) {
                                    val mascot = parts[0].trim()
                                    val text = parts[1].trim()
                                    if (mascot.isNotEmpty() && text.isNotEmpty()) {
                                        val cur = currentDialogParser ?: Dialog("default").also { currentDialogParser = it }
                                        cur.replies.add(mapOf("mascot" to mascot, "text" to text))
                                    }
                                }
                                return@forEachLine
                            }

                            dialogLines.add(l)
                        }
                        currentDialogParser?.takeIf { it.replies.isNotEmpty() }?.let { dialogs.add(it) }
                    }
                } catch (e: Exception) {
                    showCustomToast("Ошибка чтения randomreply.txt: ${e.message}")
                }
            }

            if (filename == "base.txt" && mascotList.isNotEmpty()) {
                val selected = mascotList.random()
                selected["name"]?.let { currentMascotName = it }
                selected["icon"]?.let { currentMascotIcon = it }
                selected["color"]?.let { currentThemeColor = it }
                selected["background"]?.let { currentThemeBackground = it }
            }

            // reload synonyms and index
            loadSynonyms()
            rebuildInvertedIndex()
            updateUI(currentMascotName, currentMascotIcon, currentThemeColor, currentThemeBackground)

        } catch (e: Exception) {
            e.printStackTrace()
            showCustomToast("Ошибка чтения файла: ${e.message}")
            loadFallbackTemplates()
            rebuildInvertedIndex()
            updateUI(currentMascotName, currentMascotIcon, currentThemeColor, currentThemeBackground)
        }
    }

    private fun loadFallbackTemplates() {
        templatesMap.clear()
        contextMap.clear()
        keywordResponses.clear()
        dialogs.clear()
        dialogLines.clear()
        mascotList.clear()

        templatesMap[normalizeText("привет")] = mutableListOf("Привет! Чем могу помочь?", "Здравствуй!")
        templatesMap[normalizeText("как дела")] = mutableListOf("Всё отлично, а у тебя?", "Нормально, как дела?")
        keywordResponses["спасибо"] = mutableListOf("Рад, что помог!", "Всегда пожалуйста!")
    }

    private fun updateAutoComplete() {
        val suggestions = mutableListOf<String>()
        suggestions.addAll(templatesMap.keys)
        for (s in fallback) {
            val low = normalizeText(s)
            if (!suggestions.contains(low)) suggestions.add(low)
        }
        if (adapter == null) {
            adapter = ArrayAdapter(this, android.R.layout.simple_dropdown_item_1line, suggestions)
            queryInput.setAdapter(adapter)
            queryInput.threshold = 1
        } else {
            adapter?.clear()
            adapter?.addAll(suggestions)
            adapter?.notifyDataSetChanged()
        }
    }

    // --- Idle & dialogs ---
    private fun triggerRandomDialog() {
        if (dialogLines.isNotEmpty() && random.nextDouble() < 0.3) {
            dialogHandler.postDelayed({
                if (dialogLines.isEmpty()) return@postDelayed
                val dialog = dialogLines.random()
                if (mascotList.isNotEmpty()) {
                    val rnd = mascotList.random()
                    val rndName = rnd["name"] ?: currentMascotName
                    loadMascotMetadata(rndName)
                    addChatMessage(rndName, dialog)
                } else addChatMessage(currentMascotName, dialog)
            }, 1500)
        }
        if (mascotList.isNotEmpty() && random.nextDouble() < 0.1) {
            dialogHandler.postDelayed({
                val rnd = mascotList.random()
                val rndName = rnd["name"] ?: currentMascotName
                loadMascotMetadata(rndName)
                addChatMessage(rndName, "Эй, мы не закончили!")
            }, 2500)
        }
    }

    private fun startRandomDialog() {
        if (dialogs.isEmpty()) return
        stopDialog()
        currentDialog = dialogs.random()
        currentDialogIndex = 0
        dialogRunnable = object : Runnable {
            override fun run() {
                val dialog = currentDialog ?: return
                if (currentDialogIndex < dialog.replies.size) {
                    val reply = dialog.replies[currentDialogIndex]
                    val mascot = reply["mascot"] ?: ""
                    val text = reply["text"] ?: ""
                    loadMascotMetadata(mascot)
                    addChatMessage(mascot, text)
                    currentDialogIndex++
                    dialogHandler.postDelayed(this, (random.nextInt(15000) + 10000).toLong())
                } else {
                    dialogHandler.postDelayed({ startRandomDialog() }, (random.nextInt(20000) + 5000).toLong())
                }
            }
        }
        dialogRunnable?.let { dialogHandler.postDelayed(it, (random.nextInt(15000) + 10000).toLong()) }
    }

    private fun stopDialog() {
        dialogRunnable?.let {
            dialogHandler.removeCallbacks(it)
            dialogRunnable = null
        }
    }

    private fun loadMascotMetadata(mascotName: String) {
        if (folderUri == null) return
        val metadataFilename = "${mascotName.lowercase(Locale.getDefault())}_metadata.txt"
        val dir = DocumentFile.fromTreeUri(this, folderUri!!) ?: return
        val metadataFile = dir.findFile(metadataFilename) ?: return
        try {
            contentResolver.openInputStream(metadataFile.uri)?.bufferedReader()?.use { reader ->
                reader.forEachLine { raw ->
                    val line = raw.trim()
                    when {
                        line.startsWith("mascot_name=") -> currentMascotName = line.substring("mascot_name=".length).trim()
                        line.startsWith("mascot_icon=") -> currentMascotIcon = line.substring("mascot_icon=".length).trim()
                        line.startsWith("theme_color=") -> currentThemeColor = line.substring("theme_color=".length).trim()
                        line.startsWith("theme_background=") -> currentThemeBackground = line.substring("theme_background=".length).trim()
                    }
                }
                updateUI(currentMascotName, currentMascotIcon, currentThemeColor, currentThemeBackground)
            }
        } catch (e: Exception) {
            showCustomToast("Ошибка загрузки метаданных маскота: ${e.message}")
        }
    }

    private fun updateUI(mascotName: String, mascotIcon: String, themeColor: String, themeBackground: String) {
        title = "Pawstribe - $mascotName"
        mascotTopImage?.let { imageView ->
            folderUri?.let { uri ->
                try {
                    val dir = DocumentFile.fromTreeUri(this, uri)
                    val iconFile = dir?.findFile(mascotIcon)
                    if (iconFile != null && iconFile.exists()) {
                        contentResolver.openInputStream(iconFile.uri)?.use { inputStream ->
                            val bitmap = BitmapFactory.decodeStream(inputStream)
                            imageView.setImageBitmap(bitmap)
                            imageView.alpha = 0f
                            ObjectAnimator.ofFloat(imageView, "alpha", 0f, 1f).setDuration(450).start()
                        }
                    }
                } catch (_: Exception) {}
            }
        }
        try { messagesContainer.setBackgroundColor(Color.parseColor(themeBackground)) } catch (_: Exception) {}
    }

    // --- Utils ---
    private fun showCustomToast(message: String) {
        try {
            val inflater = layoutInflater
            val layout = inflater.inflate(R.layout.custom_toast, null)
            val text = layout.findViewById<TextView>(R.id.customToastText)
            text.text = message
            val toast = Toast(applicationContext)
            toast.duration = Toast.LENGTH_SHORT
            toast.view = layout
            toast.show()
        } catch (e: Exception) {
            Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
        }
    }

    private fun startIdleTimer() {
        lastUserInputTime = System.currentTimeMillis()
        idleCheckRunnable?.let {
            dialogHandler.removeCallbacks(it)
            dialogHandler.postDelayed(it, 5000)
        }
    }

    // --- Added: UI helper to add message views ---
    private fun addChatMessage(sender: String, text: String) {
        runOnUiThread {
            val tv = TextView(this).apply {
                this.text = "$sender: $text"
                textSize = 16f
                setPadding(20, 14, 20, 14)
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply {
                    val isUser = sender.lowercase(Locale.getDefault()) == "ты"
                    gravity = if (isUser) Gravity.END else Gravity.START
                    setMargins(8, 8, 8, 8)
                }
                // simple background for better readability (fallback)
                try {
                    val accent = safeParseColorOrDefault(currentThemeColor, Color.parseColor("#00FF00"))
tv.background = createBubbleDrawable(accent)
                } catch (_: Exception) {}
            }
            messagesContainer.addView(tv)
            // keep container not too big
            if (messagesContainer.childCount > MAX_MESSAGES) {
                messagesContainer.removeViewAt(0)
            }
            scrollToBottomDelayed()
        }
    }

    private fun scrollToBottomDelayed() {
        scrollView.postDelayed({
            try { scrollView.fullScroll(View.FOCUS_DOWN) } catch (_: Exception) {}
        }, 120)
    }
}
