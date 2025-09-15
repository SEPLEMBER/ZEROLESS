package com.nemesis.droidcrypt

import android.animation.ObjectAnimator
import android.content.Intent
import android.graphics.*
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.media.MediaPlayer
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
import androidx.core.graphics.ColorUtils
import androidx.documentfile.provider.DocumentFile
import java.util.*
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.roundToInt

class ChatActivity : AppCompatActivity() {

    companion object {
        private const val PREF_KEY_FOLDER_URI = "pref_folder_uri"
        private const val PREF_KEY_DISABLE_SCREENSHOTS = "pref_disable_screenshots"
        private const val MAX_MESSAGES = 250
        private const val MAX_FUZZY_DISTANCE = 5 // допустимая дистанция для фаззи-матчинга
        private const val CANDIDATE_TOKEN_THRESHOLD = 1 // минимальное число общих токенов для кандидата
        private const val MAX_CANDIDATES_FOR_LEV = 40 // ограничение числа кандидатов для Levenshtein
        private const val JACCARD_THRESHOLD = 0.35 // Jaccard threshold
    }

    /// SECTION: UI и Data — Объявление переменных (UI-элементы, карты шаблонов, состояния маскотов/контекста, idle-данные)
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

    // Data structures
    private val fallback = arrayOf("Привет", "Как дела?", "Расскажи о себе", "Выход")
    private val templatesMap = HashMap<String, MutableList<String>>()
    private val contextMap = HashMap<String, String>()
    private val keywordResponses = HashMap<String, MutableList<String>>()
    private val antiSpamResponses = mutableListOf<String>()
    private val mascotList = mutableListOf<Map<String, String>>()
    private val dialogLines = mutableListOf<String>()
    private val dialogs = mutableListOf<Dialog>()
    private val invertedIndex = HashMap<String, MutableList<String>>()
    private val synonymsMap = HashMap<String, String>()
    private val stopwords = HashSet<String>()

    // State
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
    private val responseHandler = Handler(Looper.getMainLooper())
    private var dialogRunnable: Runnable? = null
    private var idleCheckRunnable: Runnable? = null
    private var lastUserInputTime = System.currentTimeMillis()
    private val random = Random()
    private val queryCountMap = HashMap<String, Int>()

    private data class Dialog(val name: String, val replies: MutableList<Map<String, String>> = mutableListOf())

    init {
        antiSpamResponses.addAll(
            listOf(
                "Ты надоел, давай что-то новенького!", "Спамить нехорошо, попробуй другой запрос.",
                "Я устал от твоих повторений!", "Хватит спамить, придумай что-то интересное.",
                "Эй, не зацикливайся, попробуй другой вопрос!", "Повторяешь одно и то же? Давай разнообразие!",
                "Слишком много повторов, я же не робот... ну, почти.", "Не спамь, пожалуйста, задай новый запрос!",
                "Пять раз одно и то же? Попробуй что-то другое.", "Я уже ответил, давай новый запрос!"
            )
        )
    }

    /// SECTION: Lifecycle — Инициализация Activity (onCreate, onResume, onPause)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_chat)

        // UI refs
        scrollView = findViewById(R.id.scrollView)
        queryInput = findViewById(R.id.queryInput)
        envelopeInputButton = findViewById(R.id.envelope_button)
        mascotTopImage = findViewById(R.id.mascot_top_image)
        btnLock = findViewById(R.id.btn_lock)
        btnTrash = findViewById(R.id.btn_trash)
        btnEnvelopeTop = findViewById(R.id.btn_envelope_top)
        btnSettings = findViewById(R.id.btn_settings)
        messagesContainer = findViewById(R.id.chatMessagesContainer)

        // Restore SAF Uri
        folderUri = intent?.getParcelableExtra("folderUri")
        if (folderUri == null) {
            val persistedUris = contentResolver.persistedUriPermissions
            if (persistedUris.isNotEmpty()) {
                folderUri = persistedUris[0].uri
            }
        }
        if (folderUri == null) {
            val prefs = PreferenceManager.getDefaultSharedPreferences(this)
            prefs.getString(PREF_KEY_FOLDER_URI, null)?.let {
                folderUri = Uri.parse(it)
            }
        }

        loadSynonymsAndStopwords()

        try {
            val resId = resources.getIdentifier("background_black", "color", packageName)
            val bgColor = if (resId != 0) getColor(resId) else Color.BLACK
            window.setBackgroundDrawable(ColorDrawable(bgColor))
        } catch (_: Exception) {
            window.setBackgroundDrawable(ColorDrawable(Color.BLACK))
        }

        // Screenshots lock
        val prefs = PreferenceManager.getDefaultSharedPreferences(this)
        if (prefs.getBoolean(PREF_KEY_DISABLE_SCREENSHOTS, false)) {
            window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        }

        // Load icons and setup listeners
        loadToolbarIcons()
        setupIconTouchEffect(btnLock)
        setupIconTouchEffect(btnTrash)
        setupIconTouchEffect(btnEnvelopeTop)
        setupIconTouchEffect(btnSettings)
        setupIconTouchEffect(envelopeInputButton)

        btnLock?.setOnClickListener { finish() }
        btnTrash?.setOnClickListener { clearChat() }
        btnSettings?.setOnClickListener { startActivity(Intent(this, SettingsActivity::class.java)) }
        btnEnvelopeTop?.setOnClickListener { startActivity(Intent(this, PostsActivity::class.java)) }
        envelopeInputButton?.setOnClickListener {
            val input = queryInput.text.toString().trim()
            if (input.isNotEmpty()) {
                processUserQuery(input)
                queryInput.setText("")
            }
        }
        queryInput.setOnItemClickListener { parent, _, position, _ ->
            val selected = parent.getItemAtPosition(position) as String
            queryInput.setText(selected)
            processUserQuery(selected)
        }

        // Initial data load
        if (folderUri == null) {
            showCustomToast("Папка не выбрана! Открой настройки и выбери папку.")
            onTemplateLoadFailed()
        } else {
            loadTemplatesFromFile(currentContext)
        }
        addChatMessage(currentMascotName, "Добро пожаловать!")


        // Idle runnable setup
        idleCheckRunnable = object : Runnable {
            override fun run() {
                val idle = System.currentTimeMillis() - lastUserInputTime
                if (idle >= 25000) {
                    if (dialogs.isNotEmpty()) startRandomDialog() else if (dialogLines.isNotEmpty()) triggerRandomDialog()
                }
                dialogHandler.postDelayed(this, 5000)
            }
        }
        startIdleTimer()
    }

    override fun onResume() {
        super.onResume()
        folderUri?.let {
            loadTemplatesFromFile(currentContext)
            updateAutoComplete()
        }
        startIdleTimer()
        loadToolbarIcons()
    }

    override fun onPause() {
        super.onPause()
        stopDialog()
        responseHandler.removeCallbacksAndMessages(null)
        idleCheckRunnable?.let { dialogHandler.removeCallbacks(it) }
    }

    /// SECTION: Toolbar Helpers
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
                dir.findFile(name)?.uri?.let { fileUri ->
                    contentResolver.openInputStream(fileUri)?.use { ins ->
                        val bmp = BitmapFactory.decodeStream(ins)
                        target?.setImageBitmap(bmp)
                    }
                }
            }
            tryLoad("lock.png", btnLock)
            tryLoad("trash.png", btnTrash)
            tryLoad("envelope.png", btnEnvelopeTop)
            tryLoad("settings.png", btnSettings)

            val sendIcon = dir.findFile("send.png")
            if (sendIcon != null && sendIcon.exists()) {
                tryLoad("send.png", envelopeInputButton)
            } else {
                tryLoad("envelope.png", envelopeInputButton)
            }
            
            mascotTopImage?.visibility = View.GONE
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    /// SECTION: Core Chat Logic
    private fun processUserQuery(userInput: String) {
        responseHandler.removeCallbacksAndMessages(null)
        
        val qOrigRaw = userInput.trim()
        val qOrig = normalizeText(qOrigRaw)
        val (qTokensFiltered, qFiltered) = filterStopwordsAndMapSynonyms(qOrig)

        if (qFiltered.isEmpty()) return
        lastUserInputTime = System.currentTimeMillis()
        stopDialog()
        
        if (qFiltered == lastQuery) {
            val cnt = queryCountMap.getOrDefault(qFiltered, 0) + 1
            queryCountMap[qFiltered] = cnt
        } else {
            queryCountMap.clear()
            queryCountMap[qFiltered] = 1
            lastQuery = qFiltered
        }
        
        addChatMessage("Ты", userInput)
        
        val repeats = queryCountMap.getOrDefault(qFiltered, 0)
        if (repeats >= 5) {
            val spamResp = antiSpamResponses.random()
            responseHandler.postDelayed({
                addChatMessage(currentMascotName, spamResp)
                playNotifySound()
            }, 1200)
            startIdleTimer()
            return
        }
        
        val computedResponse = computeResponse(qFiltered, qOrig, qTokensFiltered)
        val afterTypingDelay = (4000..7000).random().toLong()
        val typingDelay = 5000L
        
        responseHandler.postDelayed({
            val typingView = showTypingIndicator(durationMs = afterTypingDelay + 600L, colorHex = "#FFA500")
            responseHandler.postDelayed({
                typingView?.let { v ->
                    try { messagesContainer.removeView(v) } catch (_: Exception) {}
                }
                addChatMessage(currentMascotName, computedResponse)
                playNotifySound()
            }, afterTypingDelay)
        }, typingDelay)
        
        triggerRandomDialog()
        startIdleTimer()
    }

    private fun computeResponse(qFiltered: String, qOrig: String, qTokensFiltered: List<String>): String {
        templatesMap[qFiltered]?.randomOrNull()?.let { return it }

        for ((keyword, responses) in keywordResponses) {
            if (qFiltered.contains(keyword)) {
                responses.randomOrNull()?.let { return it }
            }
        }

        val qTokens = if (qTokensFiltered.isNotEmpty()) qTokensFiltered else tokenize(qFiltered)
        val candidates = findBestCandidates(qTokens, qFiltered)

        val qSet = qTokens.toSet()
        var bestByJaccard: String? = null
        var bestJaccard = 0.0
        for (key in candidates) {
            val keyTokens = filterStopwordsAndMapSynonyms(key).first.toSet()
            if (keyTokens.isEmpty()) continue
            val inter = qSet.intersect(keyTokens).size.toDouble()
            val union = qSet.union(keyTokens).size.toDouble()
            val j = if (union > 0) inter / union else 0.0
            if (j > bestJaccard) {
                bestJaccard = j
                bestByJaccard = key
            }
        }
        if (bestByJaccard != null && bestJaccard >= JACCARD_THRESHOLD) {
            templatesMap[bestByJaccard]?.randomOrNull()?.let { return it }
        }

        var bestKey: String? = null
        var bestDist = MAX_FUZZY_DISTANCE + 1
        for (key in candidates) {
            if (abs(key.length - qFiltered.length) > MAX_FUZZY_DISTANCE) continue
            val d = levenshtein(qFiltered, key)
            if (d < bestDist) {
                bestDist = d
                bestKey = key
            }
            if (bestDist == 0) break
        }
        if (bestKey != null && bestDist <= MAX_FUZZY_DISTANCE) {
            templatesMap[bestKey]?.randomOrNull()?.let { return it }
        }

        detectContext(qFiltered)?.let { newContext ->
            if (newContext != currentContext) {
                currentContext = newContext
                loadTemplatesFromFile(currentContext)
                templatesMap[qFiltered]?.randomOrNull()?.let { return it }
            }
        }

        return getDummyResponse(qOrig)
    }
    
    private fun findBestCandidates(qTokens: List<String>, qFiltered: String): List<String> {
        val candidateCounts = HashMap<String, Int>()
        qTokens.forEach { tok ->
            invertedIndex[tok]?.forEach { trig ->
                candidateCounts[trig] = candidateCounts.getOrDefault(trig, 0) + 1
            }
        }
        return if (candidateCounts.isNotEmpty()) {
            candidateCounts.entries
                .filter { it.value >= CANDIDATE_TOKEN_THRESHOLD }
                .sortedByDescending { it.value }
                .map { it.key }
                .take(MAX_CANDIDATES_FOR_LEV)
        } else {
            templatesMap.keys
                .filter { abs(it.length - qFiltered.length) <= MAX_FUZZY_DISTANCE }
                .take(MAX_CANDIDATES_FOR_LEV)
        }
    }

    private fun showTypingIndicator(durationMs: Long, colorHex: String): View {
        val typingView = TextView(this).apply {
            text = "печатает..."
            textSize = 14f
            try { setTextColor(Color.parseColor(colorHex)) } catch (_: Exception) { setTextColor(Color.WHITE) }
            setBackgroundColor(0x80000000.toInt())
            alpha = 0.9f
            setPadding(16, 8, 16, 8)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(0, 16, 0, 0) }
        }
        messagesContainer.addView(typingView, 0)
        if (durationMs > 0) {
            Handler(Looper.getMainLooper()).postDelayed({
                try { messagesContainer.removeView(typingView) } catch (_: Exception) {}
            }, durationMs)
        }
        return typingView
    }

    private fun clearChat() {
        messagesContainer.removeAllViews()
        queryCountMap.clear()
        lastQuery = ""
        currentContext = "base.txt"
        loadTemplatesFromFile(currentContext)
        addChatMessage(currentMascotName, "Чат очищен. Возвращаюсь к началу.")
    }

    private fun detectContext(input: String): String? {
        val lower = normalizeText(input)
        return contextMap.entries.find { lower.contains(it.key) }?.value
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
        return cleaned.replace(Regex("\\s+"), " ").trim()
    }

    private fun tokenize(s: String): List<String> {
        return s.split(Regex("\\s+")).filter { it.isNotEmpty() }
    }

    private fun loadSynonymsAndStopwords() {
        synonymsMap.clear()
        stopwords.clear()
        val dir = folderUri?.let { DocumentFile.fromTreeUri(this, it) } ?: return

        fun readFileLines(fileName: String, block: (String) -> Unit) {
            dir.findFile(fileName)?.uri?.let { fileUri ->
                try {
                    contentResolver.openInputStream(fileUri)?.bufferedReader()?.useLines { lines -> lines.forEach(block) }
                } catch (_: Exception) {}
            }
        }

        readFileLines("synonims.txt") { line ->
            val parts = line.removeSurrounding("*").split(";").map { normalizeText(it) }.filter { it.isNotEmpty() }
            if (parts.size > 1) {
                val canonical = parts.last()
                parts.forEach { synonymsMap[it] = canonical }
            }
        }
        
        dir.findFile("stopwords.txt")?.uri?.let { fileUri ->
            try {
                val text = contentResolver.openInputStream(fileUri)?.bufferedReader()?.use { it.readText() } ?: ""
                text.split("^").map { normalizeText(it) }.filter { it.isNotEmpty() }.forEach { stopwords.add(it) }
            } catch (_: Exception) {}
        }
    }
    
    private fun filterStopwordsAndMapSynonyms(input: String): Pair<List<String>, String> {
        val mapped = tokenize(input)
            .map { synonymsMap[it] ?: it }
            .filter { !stopwords.contains(it) }
        return Pair(mapped, mapped.joinToString(" "))
    }

    private fun rebuildInvertedIndex() {
        invertedIndex.clear()
        templatesMap.keys.forEach { key ->
            filterStopwordsAndMapSynonyms(key).first.forEach { token ->
                invertedIndex.getOrPut(token) { mutableListOf() }.apply { if (!contains(key)) add(key) }
            }
        }
    }
    
    private fun levenshtein(s: String, t: String): Int {
        if (s == t) return 0
        val n = s.length
        val m = t.length
        if (n == 0) return m
        if (m == 0) return n
        if (abs(n - m) > MAX_FUZZY_DISTANCE) return MAX_FUZZY_DISTANCE + 1

        val prev = IntArray(m + 1) { it }
        val curr = IntArray(m + 1)

        for (i in 1..n) {
            curr[0] = i
            val si = s[i - 1]
            var minInRow = curr[0]

            for (j in 1..m) {
                val cost = if (si == t[j - 1]) 0 else 1
                curr[j] = minOf(prev[j] + 1, curr[j - 1] + 1, prev[j - 1] + cost)
                if (curr[j] < minInRow) minInRow = curr[j]
            }

            if (minInRow > MAX_FUZZY_DISTANCE) return MAX_FUZZY_DISTANCE + 1
            System.arraycopy(curr, 0, prev, 0, m + 1)
        }
        return prev[m]
    }

    /// SECTION: UI Messages
    private fun addChatMessage(sender: String, text: String) {
        val isUser = sender.equals("Ты", ignoreCase = true)
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            val pad = dpToPx(6)
            setPadding(pad, pad / 2, pad, pad / 2)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            gravity = if (isUser) Gravity.END else Gravity.START
        }

        val bubble = createMessageBubble(sender, text)
        if (isUser) {
            val indicator = TextView(this).apply {
                this.text = "/"
                textSize = 14f
                setTextColor(Color.parseColor("#CCCCCC"))
                setPadding(dpToPx(3), dpToPx(1), dpToPx(3), dpToPx(1))
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                    .apply { setMargins(dpToPx(6), 0, 0, 0); gravity = Gravity.BOTTOM }
            }
            row.addView(bubble)
            row.addView(indicator)
            Handler(Looper.getMainLooper()).postDelayed({
                try { indicator.text = "//" } catch (_: Exception) {}
            }, 2000L)
        } else {
            val avatarView = ImageView(this).apply {
                val size = dpToPx(64)
                layoutParams = LinearLayout.LayoutParams(size, size).apply { marginEnd = dpToPx(8) }
                scaleType = ImageView.ScaleType.CENTER_CROP
                loadAvatarInto(this, sender)
            }
            row.addView(avatarView)
            row.addView(bubble)
        }

        messagesContainer.addView(row)
        if (messagesContainer.childCount > MAX_MESSAGES) {
            messagesContainer.removeViewAt(0)
        }
        scrollView.post { scrollView.fullScroll(View.FOCUS_DOWN) }
    }

    private fun createMessageBubble(sender: String, text: String): LinearLayout {
        val accent = safeParseColorOrDefault(currentThemeColor, Color.parseColor("#00FF00"))
        return LinearLayout(this@ChatActivity).apply {
            orientation = LinearLayout.VERTICAL
            background = createBubbleDrawable(accent)
            val pad = dpToPx(10)
            setPadding(pad, pad, pad, pad)

            addView(TextView(this@ChatActivity).apply {
                this.text = "$sender:"
                textSize = 12f
                setTextColor(Color.parseColor("#AAAAAA"))
            })
            addView(TextView(this@ChatActivity).apply {
                this.text = text
                textSize = 16f
                setTextIsSelectable(true)
                try { setTextColor(Color.parseColor(currentThemeColor)) } catch (_: Exception) { setTextColor(Color.WHITE) }
            })
        }
    }

    private fun createBubbleDrawable(accentColor: Int): GradientDrawable {
        return GradientDrawable().apply {
            val bg = blendColors(Color.parseColor("#0A0A0A"), accentColor, 0.06f)
            setColor(bg)
            cornerRadius = dpToPx(10).toFloat()
            setStroke(dpToPx(2), ColorUtils.setAlphaComponent(accentColor, 180))
        }
    }

    private fun loadAvatarInto(target: ImageView, sender: String) {
        val dir = folderUri?.let { DocumentFile.fromTreeUri(this, it) } ?: return
        val s = sender.lowercase(Locale.getDefault())
        val candidates = listOf("${s}_icon.png", "${s}_avatar.png", "${s}.png", currentMascotIcon)
        for (name in candidates) {
            dir.findFile(name)?.uri?.let { fileUri ->
                try {
                    contentResolver.openInputStream(fileUri)?.use {
                        target.setImageBitmap(BitmapFactory.decodeStream(it))
                        return
                    }
                } catch (_: Exception) {}
            }
        }
        target.setImageResource(android.R.color.transparent)
    }

    private fun blendColors(base: Int, accent: Int, ratio: Float): Int {
        val r = (Color.red(base) * (1 - ratio) + Color.red(accent) * ratio).roundToInt()
        val g = (Color.green(base) * (1 - ratio) + Color.green(accent) * ratio).roundToInt()
        val b = (Color.blue(base) * (1 - ratio) + Color.blue(accent) * ratio).roundToInt()
        return Color.rgb(r, g, b)
    }

    private fun safeParseColorOrDefault(spec: String?, fallback: Int): Int {
        return try { Color.parseColor(spec) } catch (_: Exception) { fallback }
    }

    private fun dpToPx(dp: Int): Int = (dp * resources.displayMetrics.density).roundToInt()

    /// SECTION: Template Loading
    private fun onTemplateLoadFailed() {
        loadFallbackTemplates()
        rebuildInvertedIndex()
        updateUI(currentMascotName, currentMascotIcon, currentThemeColor, currentThemeBackground)
    }

    private fun loadTemplatesFromFile(filename: String) {
        templatesMap.clear()
        keywordResponses.clear()
        mascotList.clear()
        dialogLines.clear()
        dialogs.clear()

        if (filename == "base.txt") {
            contextMap.clear()
            currentMascotName = "Racky"
            currentMascotIcon = "raccoon_icon.png"
            currentThemeColor = "#00FF00"
            currentThemeBackground = "#000000"
        }

        loadSynonymsAndStopwords()

        val dir = folderUri?.let { DocumentFile.fromTreeUri(this, it) } ?: return onTemplateLoadFailed()
        val file = dir.findFile(filename) ?: return onTemplateLoadFailed()

        try {
            contentResolver.openInputStream(file.uri)?.bufferedReader()?.useLines { lines ->
                lines.forEach { line -> parseTemplateLine(line, filename) }
            }

            val metadataFilename = filename.replace(".txt", "_metadata.txt")
            dir.findFile(metadataFilename)?.uri?.let { metadataUri ->
                contentResolver.openInputStream(metadataUri)?.bufferedReader()?.useLines { lines ->
                    lines.forEach(::parseMetadataLine)
                }
            }

            dir.findFile("randomreply.txt")?.uri?.let { dialogsUri ->
                contentResolver.openInputStream(dialogsUri)?.bufferedReader()?.use { reader ->
                    parseDialogsFile(reader.readLines())
                }
            }

            if (filename == "base.txt" && mascotList.isNotEmpty()) {
                mascotList.random().let {
                    currentMascotName = it["name"] ?: currentMascotName
                    currentMascotIcon = it["icon"] ?: currentMascotIcon
                    currentThemeColor = it["color"] ?: currentThemeColor
                    currentThemeBackground = it["background"] ?: currentThemeBackground
                }
            }

            rebuildInvertedIndex()
            updateUI(currentMascotName, currentMascotIcon, currentThemeColor, currentThemeBackground)

        } catch (e: Exception) {
            e.printStackTrace()
            showCustomToast("Ошибка чтения файла: ${e.message}")
            onTemplateLoadFailed()
        }
    }

    private fun parseTemplateLine(raw: String, filename: String) {
        val l = raw.trim()
        if (l.isEmpty()) return

        if (filename == "base.txt" && l.startsWith(":") && l.endsWith(":")) {
            val parts = l.substring(1, l.length - 1).split("=", limit = 2)
            if (parts.size == 2) contextMap[parts[0].trim().lowercase(Locale.ROOT)] = parts[1].trim()
            return
        }

        if (l.startsWith("-")) {
            val parts = l.substring(1).split("=", limit = 2)
            if (parts.size == 2) {
                val responses = parts[1].split("|").map { it.trim() }.filter { it.isNotEmpty() }.toMutableList()
                if (responses.isNotEmpty()) keywordResponses[parts[0].trim().lowercase(Locale.ROOT)] = responses
            }
            return
        }

        val parts = l.split("=", limit = 2)
        if (parts.size == 2) {
            val trigger = filterStopwordsAndMapSynonyms(normalizeText(parts[0])).second
            val responses = parts[1].split("|").map { it.trim() }.filter { it.isNotEmpty() }.toMutableList()
            if (trigger.isNotEmpty() && responses.isNotEmpty()) templatesMap[trigger] = responses
        }
    }
    
    // <-- FIXED: Simplified this function to resolve a persistent compiler error.
    private fun parseMetadataLine(line: String) {
        val t = line.trim()
        when {
            t.startsWith("mascot_list=") -> {
                val value = t.substring("mascot_list=".length).trim()
                value.split("|").forEach {
                    val parts = it.split(":")
                    if (parts.size == 4) {
                        mascotList.add(mapOf(
                            "name" to parts[0].trim(),
                            "icon" to parts[1].trim(),
                            "color" to parts[2].trim(),
                            "background" to parts[3].trim()
                        ))
                    }
                }
            }
            t.startsWith("mascot_name=") -> {
                currentMascotName = t.substring("mascot_name=".length).trim()
            }
            t.startsWith("mascot_icon=") -> {
                currentMascotIcon = t.substring("mascot_icon=".length).trim()
            }
            t.startsWith("theme_color=") -> {
                currentThemeColor = t.substring("theme_color=".length).trim()
            }
            t.startsWith("theme_background=") -> {
                currentThemeBackground = t.substring("theme_background=".length).trim()
            }
            t.startsWith("dialog_lines=") -> {
                val lines = t.substring("dialog_lines=".length).trim().split("|")
                dialogLines.addAll(lines.map { it.trim() }.filter { it.isNotEmpty() })
            }
        }
    }
    
    private fun parseDialogsFile(lines: List<String>) {
        var currentDialogParser: Dialog? = null
        lines.map { it.trim() }.filter { it.isNotEmpty() }.forEach { l ->
            if (l.startsWith(";")) {
                currentDialogParser?.takeIf { it.replies.isNotEmpty() }?.let { dialogs.add(it) }
                currentDialogParser = Dialog(l.substring(1).trim())
            } else if (l.contains(">")) {
                val parts = l.split(">", limit = 2)
                if (parts.size == 2) {
                    val mascot = parts[0].trim()
                    val text = parts[1].trim()
                    if (mascot.isNotEmpty() && text.isNotEmpty()) {
                        val cur = currentDialogParser ?: run {
    val newDialog = Dialog("default")
    currentDialogParser = newDialog
    newDialog
}
                        cur.replies.add(mapOf("mascot" to mascot, "text" to text))
                    }
                }
            } else {
                dialogLines.add(l)
            }
        }
        currentDialogParser?.takeIf { it.replies.isNotEmpty() }?.let { dialogs.add(it) }
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

    private fun updateAutoComplete() {
        val suggestions = templatesMap.keys.toMutableSet()
        fallback.forEach { suggestions.add(it.lowercase(Locale.ROOT)) }
        
        if (adapter == null) {
            adapter = object : ArrayAdapter<String>(this, android.R.layout.simple_dropdown_item_1line, suggestions.toList()) {
                override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                    return super.getView(position, convertView, parent).apply {
                        (this as? TextView)?.setTextColor(Color.WHITE)
                    }
                }
            }
            queryInput.setAdapter(adapter)
            queryInput.threshold = 1
        } else {
            adapter?.clear()
            adapter?.addAll(suggestions)
            adapter?.notifyDataSetChanged()
        }
        
        try {
            queryInput.dropDownBackground = ColorDrawable(0xCC000000.toInt())
        } catch (_: Exception) {}
    }

    /// SECTION: Idle & Dialogs
    private fun triggerRandomDialog() {
        if (dialogLines.isNotEmpty() && random.nextDouble() < 0.3) {
            dialogHandler.postDelayed({
                if (dialogLines.isEmpty()) return@postDelayed
                val dialog = dialogLines.random()
                val mascotName = if (mascotList.isNotEmpty()) {
                    val rnd = mascotList.random()
                    rnd["name"] ?: currentMascotName
                } else currentMascotName
                loadMascotMetadata(mascotName)
                addChatMessage(mascotName, dialog)
            }, 1500)
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
        dialogRunnable?.let { dialogHandler.removeCallbacks(it) }
        dialogRunnable = null
    }

    private fun loadMascotMetadata(mascotName: String) {
        folderUri?.let { uri ->
            val dir = DocumentFile.fromTreeUri(this, uri) ?: return
            val metadataFilename = "${mascotName.lowercase(Locale.ROOT)}_metadata.txt"
            dir.findFile(metadataFilename)?.uri?.let { metadataUri ->
                try {
                    contentResolver.openInputStream(metadataUri)?.bufferedReader()?.useLines { lines ->
                        lines.forEach(::parseMetadataLine)
                    }
                    updateUI(currentMascotName, currentMascotIcon, currentThemeColor, currentThemeBackground)
                } catch (e: Exception) {
                    showCustomToast("Ошибка загрузки метаданных маскота: ${e.message}")
                }
            }
        }
    }

    private fun updateUI(mascotName: String, mascotIcon: String, themeColor: String, themeBackground: String) {
        title = "Pawstribe - $mascotName"
        mascotTopImage?.visibility = View.GONE
        try {
            messagesContainer.setBackgroundColor(Color.parseColor(themeBackground))
        } catch (_: Exception) {}
        updateAutoComplete()
    }

    /// SECTION: Utils
    private fun showCustomToast(message: String) {
    try {
        // Для Android API 30+ используем обычный Toast
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
            Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
        } else {
            // Для старых версий Android можем использовать кастомный layout
            val layout = layoutInflater.inflate(R.layout.custom_toast, null)
            layout.findViewById<TextView>(R.id.customToastText).text = message
            
            val toast = Toast(applicationContext)
            toast.duration = Toast.LENGTH_SHORT
            @Suppress("DEPRECATION")
            toast.view = layout
            toast.show()
        }
    } catch (e: Exception) {
        // Fallback к обычному Toast при любых ошибках
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

    private fun playNotifySound() {
        val dir = folderUri?.let { DocumentFile.fromTreeUri(this, it) } ?: return
        val candidates = listOf("Notify.ogg", "notify.ogg", "Notify.mp3", "notify.mp3")
        var soundFile: DocumentFile? = null
        for (name in candidates) {
            val f = dir.findFile(name)
            if (f != null && f.exists()) {
                soundFile = f
                break
            }
        }
        val fileUri = soundFile?.uri ?: return

        try {
            contentResolver.openFileDescriptor(fileUri, "r")?.use { pfd ->
                MediaPlayer().apply {
                    setDataSource(pfd.fileDescriptor)
                    prepare()
                    start()
                    setOnCompletionListener { it.release() }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}
