package com.nemesis.droidcrypt

import android.content.Intent
import android.graphics.BitmapFactory
import android.graphics.Color
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
import kotlin.math.roundToInt

class ChatActivity : AppCompatActivity() {

    companion object {
        private const val PREF_KEY_FOLDER_URI = "pref_folder_uri"
        private const val PREF_KEY_DISABLE_SCREENSHOTS = "pref_disable_screenshots"
        private const val MAX_MESSAGES = 250
        private const val MAX_FUZZY_DISTANCE = 5
        private const val CANDIDATE_TOKEN_THRESHOLD = 1
        private const val MAX_CANDIDATES_FOR_LEV = 40
        private const val JACCARD_THRESHOLD = 0.35
    }

    // Переменные для инициализации UI и Logic
    private lateinit var logic: Logic
    private lateinit var ui: UIManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_chat)

        // Создаём логику (без доступа к Android-IO) и UI-менеджер, затем связываем
        logic = Logic()
        ui = UIManager(this, logic)
        logic.setUIBridge(ui)

        // Запускаем UI init (бинды + начальная загрузка файлов)
        ui.initViewsAndLoad()
    }

    override fun onResume() {
        super.onResume()
        ui.onResume()
    }

    override fun onPause() {
        super.onPause()
        ui.onPause()
    }

    override fun onDestroy() {
        super.onDestroy()
        logic.shutdown()
        ui.shutdown()
    }

    // -----------------------
    // SECTION: Logic (всё, что отвечает за поведение/поиск/парсинг строк/алгоритмы)
    // -----------------------

    inner class Logic {

        // Callback interface — UIManager реализует этот интерфейс
        interface UIBridge {
            fun addChatMessage(sender: String, text: String)
            fun showTypingIndicator(durationMs: Long, colorHex: String)
            fun playNotifySound()
            fun updateAutoCompleteSuggestions(suggestions: List<String>)
            fun loadTemplatesFromFileRequest(filename: String)
        }

        private var uiBridge: UIBridge? = null

        fun setUIBridge(bridge: UIBridge) {
            uiBridge = bridge
        }

        // Data structures (логика)
        private val templatesMap = HashMap<String, MutableList<String>>()
        private val keywordResponses = HashMap<String, MutableList<String>>()
        private val contextMap = HashMap<String, String>()
        private val dialogLines = mutableListOf<String>()
        private val dialogs = mutableListOf<Dialog>()
        private val mascotList = mutableListOf<Map<String, String>>()
        private val invertedIndex = HashMap<String, MutableList<String>>()
        private val synonymsMap = HashMap<String, String>()
        private val stopwords = HashSet<String>()
        private val antiSpamResponses = mutableListOf<String>()
        private val random = Random()

        // State
        var currentMascotName = "Racky"
            private set
        var currentMascotIcon = "raccoon_icon.png"
            private set
        var currentThemeColor = "#00FF00"
            private set
        var currentThemeBackground = "#000000"
            private set
        var currentContext = "base.txt"
            private set

        // Dialogs/idle stuff runs on UI/main looper
        private val handler = Handler(Looper.getMainLooper())
        private var dialogRunnable: Runnable? = null
        private var idleChecker: Runnable? = null
        private var lastUserInputTime = System.currentTimeMillis()
        private val queryCountMap = HashMap<String, Int>()
        private var lastQuery = ""
        private var currentDialog: Dialog? = null
        private var currentDialogIndex = 0

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
                    "Не спамь, пожалуйста, задай новый запрос!",
                    "Пять раз одно и то же? Попробуй что-то другое.",
                    "Я уже ответил, давай новый запрос!"
                )
            )
        }

        // --- API для UI: наполнение данных (UI читает файлы и вызывает эти методы) ---
        fun clearTemplatesAndState() {
            templatesMap.clear()
            keywordResponses.clear()
            contextMap.clear()
            dialogs.clear()
            dialogLines.clear()
            mascotList.clear()
            invertedIndex.clear()
            synonymsMap.clear()
            stopwords.clear()
        }

        fun addTemplate(trigger: String, responses: List<String>) {
            val t = trigger.trim().lowercase(Locale.getDefault())
            if (t.isEmpty() || responses.isEmpty()) return
            templatesMap.getOrPut(t) { mutableListOf() }.addAll(responses)
        }

        fun addKeywordResponse(keyword: String, responses: List<String>) {
            val k = keyword.trim().lowercase(Locale.getDefault())
            if (k.isEmpty() || responses.isEmpty()) return
            keywordResponses.getOrPut(k) { mutableListOf() }.addAll(responses)
        }

        fun addContextMapping(key: String, value: String) {
            if (key.isBlank()) return
            contextMap[key.trim().lowercase(Locale.getDefault())] = value.trim()
        }

        fun addDialogLine(line: String) {
            if (line.isNotBlank()) dialogLines.add(line.trim())
        }

        fun addDialog(name: String, replies: List<Pair<String, String>>) {
            val d = Dialog(name)
            replies.forEach { (mascot, text) -> d.replies.add(mapOf("mascot" to mascot, "text" to text)) }
            if (d.replies.isNotEmpty()) dialogs.add(d)
        }

        fun addMascotEntry(name: String, icon: String, color: String, background: String) {
            if (name.isBlank()) return
            mascotList.add(mapOf("name" to name, "icon" to icon, "color" to color, "background" to background))
        }

        fun setMascotFromMetadata(name: String?, icon: String?, color: String?, background: String?) {
            name?.let { if (it.isNotBlank()) currentMascotName = it }
            icon?.let { if (it.isNotBlank()) currentMascotIcon = it }
            color?.let { if (it.isNotBlank()) currentThemeColor = it }
            background?.let { if (it.isNotBlank()) currentThemeBackground = it }
        }

        fun setSynonyms(map: Map<String, String>) {
            synonymsMap.clear()
            synonymsMap.putAll(map)
        }

        fun setStopwords(set: Set<String>) {
            stopwords.clear()
            stopwords.addAll(set)
        }

        fun rebuildInvertedIndex() {
            invertedIndex.clear()
            templatesMap.keys.forEach { key ->
                filterStopwordsAndMapSynonyms(key).first.forEach { token ->
                    invertedIndex.getOrPut(token) { mutableListOf() }.apply { if (!contains(key)) add(key) }
                }
            }
            // Update UI autocomplete
            uiBridge?.updateAutoCompleteSuggestions((templatesMap.keys + listOf("Привет", "Как дела?", "Расскажи о себе", "Выход")).toList())
        }

        // --- Основная логика: обработка запроса ---
        fun processUserQuery(userInput: String) {
            val qOrigRaw = userInput.trim()
            val qOrig = normalizeText(qOrigRaw)
            val (qTokensFiltered, qFiltered) = filterStopwordsAndMapSynonyms(qOrig)
            if (qFiltered.isEmpty()) return

            lastUserInputTime = System.currentTimeMillis()
            stopDialog()

            // anti-spam counting
            if (qFiltered == lastQuery) {
                val cnt = queryCountMap.getOrDefault(qFiltered, 0) + 1
                queryCountMap[qFiltered] = cnt
            } else {
                queryCountMap.clear()
                queryCountMap[qFiltered] = 1
                lastQuery = qFiltered
            }

            uiBridge?.addChatMessage("Ты", userInput)

            val repeats = queryCountMap.getOrDefault(qFiltered, 0)
            if (repeats >= 5) {
                val spamResp = antiSpamResponses.random()
                handler.postDelayed({
                    uiBridge?.addChatMessage(currentMascotName, spamResp)
                    uiBridge?.playNotifySound()
                }, 1200)
                startIdleTimer()
                return
            }

            val computedResponse = computeResponse(qFiltered, qOrig, qTokensFiltered)

            // typing simulation
            val afterTypingDelay = (1500L + random.nextInt(3500)) // 1.5s .. 5s
            handler.post {
                uiBridge?.showTypingIndicator(afterTypingDelay + 600L, "#FFA500")
            }

            handler.postDelayed({
                uiBridge?.addChatMessage(currentMascotName, computedResponse)
                uiBridge?.playNotifySound()
            }, afterTypingDelay + 700L)

            triggerRandomDialog()
            startIdleTimer()
        }

        private fun computeResponse(qFiltered: String, qOrig: String, qTokensFiltered: List<String>): String {
            try {
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
                        // просим UI загрузить новый контекстный файл (UI выполнит SAF-IO)
                        uiBridge?.loadTemplatesFromFileRequest(newContext)
                        // после загрузки UI должен вызвать rebuildInvertedIndex()
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
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

        // Levenshtein (без переназначения val)
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
                    val insertion = prev[j] + 1
                    val deletion = curr[j - 1] + 1
                    val substitution = prev[j - 1] + cost
                    val v = minOf(insertion, deletion, substitution)
                    curr[j] = v
                    if (v < minInRow) minInRow = v
                }

                if (minInRow > MAX_FUZZY_DISTANCE) return MAX_FUZZY_DISTANCE + 1

                System.arraycopy(curr, 0, prev, 0, m + 1)
            }
            return prev[m]
        }

        // Text utilities
        private fun normalizeText(s: String): String {
            val lower = s.lowercase(Locale.getDefault())
            val cleaned = lower.replace(Regex("[^\\p{L}\\p{Nd}\\s]"), " ")
            return cleaned.replace(Regex("\\s+"), " ").trim()
        }

        private fun tokenize(s: String): List<String> {
            return s.split(Regex("\\s+")).filter { it.isNotEmpty() }
        }

        private fun filterStopwordsAndMapSynonyms(input: String): Pair<List<String>, String> {
            val mapped = tokenize(input)
                .map { synonymsMap[it] ?: it }
                .filter { !stopwords.contains(it) }
            return Pair(mapped, mapped.joinToString(" "))
        }

        private fun detectContext(input: String): String? {
            val lower = normalizeText(input)
            return try {
                contextMap.entries.find { lower.contains(it.key) }?.value
            } catch (_: Exception) {
                null
            }
        }

        private fun getDummyResponse(query: String): String {
            val lower = query.lowercase(Locale.ROOT)
            return when {
                lower.contains("привет") -> "Привет! Чем могу помочь?"
                lower.contains("как дела") -> "Всё отлично, а у тебя?"
                else -> "Не понял запрос. Попробуй другой вариант."
            }
        }

        // Idle / Dialogs
        fun startIdleTimer() {
            lastUserInputTime = System.currentTimeMillis()
            idleChecker?.let { handler.removeCallbacks(it) }
            idleChecker = object : Runnable {
                override fun run() {
                    try {
                        val idle = System.currentTimeMillis() - lastUserInputTime
                        if (idle >= 25_000) {
                            if (dialogs.isNotEmpty()) startRandomDialog() else if (dialogLines.isNotEmpty()) triggerRandomDialog()
                        }
                    } catch (_: Exception) {
                    } finally {
                        handler.postDelayed(this, 5_000)
                    }
                }
            }
            idleChecker?.let { handler.postDelayed(it, 5_000) }
        }

        fun shutdown() {
            try {
                handler.removeCallbacksAndMessages(null)
            } catch (_: Exception) {
            }
        }

        private fun triggerRandomDialog() {
            if (dialogLines.isNotEmpty() && random.nextDouble() < 0.3) {
                handler.postDelayed({
                    if (dialogLines.isEmpty()) return@postDelayed
                    val dialog = dialogLines.random()
                    val mascotName = if (mascotList.isNotEmpty()) mascotList.random()["name"] ?: currentMascotName else currentMascotName
                    // Notify UI
                    uiBridge?.addChatMessage(mascotName, dialog)
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
                    try {
                        val dialog = currentDialog ?: return
                        if (currentDialogIndex < dialog.replies.size) {
                            val reply = dialog.replies[currentDialogIndex]
                            val mascot = reply["mascot"] ?: ""
                            val text = reply["text"] ?: ""
                            uiBridge?.addChatMessage(mascot, text)
                            currentDialogIndex++
                            val delay = (random.nextInt(15_000) + 10_000).toLong()
                            handler.postDelayed(this, delay)
                        } else {
                            handler.postDelayed({ startRandomDialog() }, (random.nextInt(20_000) + 5_000).toLong())
                        }
                    } catch (_: Exception) {}
                }
            }
            dialogRunnable?.let { handler.postDelayed(it, (random.nextInt(15_000) + 10_000).toLong()) }
        }

        private fun stopDialog() {
            dialogRunnable?.let { handler.removeCallbacks(it) }
            dialogRunnable = null
        }
    }

    // -----------------------
    // SECTION END
    // -----------------------

    // -----------------------
    // SECTION: UIManager (вся Android-специфичная работа: view binding, SAF IO, рисование сообщений)
    // -----------------------

    inner class UIManager(
        private val activity: ChatActivity,
        private val logic: Logic
    ) : Logic.UIBridge {

        // Views
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

        private val handler = Handler(Looper.getMainLooper())

        init {
            // nothing heavy here
        }

        fun initViewsAndLoad() {
            // bind views
            scrollView = activity.findViewById(R.id.scrollView)
            queryInput = activity.findViewById(R.id.queryInput)
            envelopeInputButton = activity.findViewById(R.id.envelope_button)
            mascotTopImage = activity.findViewById(R.id.mascot_top_image)
            btnLock = activity.findViewById(R.id.btn_lock)
            btnTrash = activity.findViewById(R.id.btn_trash)
            btnEnvelopeTop = activity.findViewById(R.id.btn_envelope_top)
            btnSettings = activity.findViewById(R.id.btn_settings)
            messagesContainer = activity.findViewById(R.id.chatMessagesContainer)

            // restore SAF Uri
            folderUri = activity.intent?.getParcelableExtra("folderUri")
            if (folderUri == null) {
                val persisted = activity.contentResolver.persistedUriPermissions
                if (persisted.isNotEmpty()) folderUri = persisted[0].uri
            }
            if (folderUri == null) {
                val prefs = PreferenceManager.getDefaultSharedPreferences(activity)
                prefs.getString(PREF_KEY_FOLDER_URI, null)?.let { folderUri = try { Uri.parse(it) } catch (_: Exception) { null } }
            }

            // setup window background and secure flag
            try {
                val resId = resources.getIdentifier("background_black", "color", activity.packageName)
                val bgColor = if (resId != 0) activity.getColor(resId) else Color.BLACK
                activity.window.setBackgroundDrawable(ColorDrawable(bgColor))
            } catch (_: Exception) {
                activity.window.setBackgroundDrawable(ColorDrawable(Color.BLACK))
            }
            try {
                val prefs = PreferenceManager.getDefaultSharedPreferences(activity)
                if (prefs.getBoolean(PREF_KEY_DISABLE_SCREENSHOTS, false)) {
                    activity.window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
                } else {
                    activity.window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
                }
            } catch (_: Exception) {}

            // toolbar icons
            loadToolbarIcons()
            setupIconTouchEffect(btnLock)
            setupIconTouchEffect(btnTrash)
            setupIconTouchEffect(btnEnvelopeTop)
            setupIconTouchEffect(btnSettings)
            setupIconTouchEffect(envelopeInputButton)

            btnLock?.setOnClickListener { activity.finish() }
            btnTrash?.setOnClickListener { clearChat() }
            btnSettings?.setOnClickListener { activity.startActivity(Intent(activity, SettingsActivity::class.java)) }
            btnEnvelopeTop?.setOnClickListener { activity.startActivity(Intent(activity, PostsActivity::class.java)) }

            envelopeInputButton?.setOnClickListener {
                val input = queryInput.text?.toString()?.trim() ?: ""
                if (input.isNotEmpty()) {
                    logic.processUserQuery(input)
                    queryInput.setText("")
                }
            }

            queryInput.setOnItemClickListener { parent, _, position, _ ->
                val selected = parent.getItemAtPosition(position) as? String ?: return@setOnItemClickListener
                queryInput.setText(selected)
                logic.processUserQuery(selected)
            }

            // initial load templates
            if (folderUri == null) {
                showCustomToast("Папка не выбрана! Открой настройки и выбери папку.")
                loadFallbackTemplatesToLogic()
                logic.rebuildInvertedIndex()
                updateUI()
            } else {
                loadTemplatesFromFile("base.txt")
            }

            // welcome
            addChatMessage(logic.currentMascotName, "Добро пожаловать!")

            // start idle timer in logic
            logic.startIdleTimer()
        }

        fun onResume() {
            // reload templates if SAF present
            folderUri?.let { loadTemplatesFromFile(logic.currentContext) }
            logic.startIdleTimer()
            loadToolbarIcons()
        }

        fun onPause() {
            // stop any UI handlers
            handler.removeCallbacksAndMessages(null)
        }

        fun shutdown() {
            handler.removeCallbacksAndMessages(null)
        }

        // --- UIBridge implementation (Logic вызывает эти методы) ---
        override fun addChatMessage(sender: String, text: String) {
            handler.post {
                try {
                    val isUser = sender.equals("Ты", ignoreCase = true)
                    val row = LinearLayout(activity).apply {
                        orientation = LinearLayout.HORIZONTAL
                        val pad = dpToPx(6)
                        setPadding(pad, pad / 2, pad, pad / 2)
                        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                        gravity = if (isUser) Gravity.END else Gravity.START
                    }

                    val bubble = createMessageBubble(sender, text)
                    if (isUser) {
                        val indicator = TextView(activity).apply {
                            this.text = "/"
                            textSize = 14f
                            setTextColor(Color.parseColor("#CCCCCC"))
                            setPadding(dpToPx(3), dpToPx(1), dpToPx(3), dpToPx(1))
                            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                                .apply { setMargins(dpToPx(6), 0, 0, 0); gravity = Gravity.BOTTOM }
                        }
                        row.addView(bubble)
                        row.addView(indicator)
                        handler.postDelayed({
                            try { indicator.text = "//" } catch (_: Exception) {}
                        }, 2000L)
                    } else {
                        val avatarView = ImageView(activity).apply {
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
                        try { messagesContainer.removeViewAt(0) } catch (_: Exception) {}
                    }
                    scrollView.post { scrollView.fullScroll(View.FOCUS_DOWN) }
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }

        override fun showTypingIndicator(durationMs: Long, colorHex: String) {
            handler.post {
                try {
                    val typingView = TextView(activity).apply {
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
                        handler.postDelayed({
                            try { messagesContainer.removeView(typingView) } catch (_: Exception) {}
                        }, durationMs)
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }

        override fun playNotifySound() {
            handler.post {
                try {
                    val dir = folderUri?.let { DocumentFile.fromTreeUri(activity, it) } ?: return@post
                    val candidates = listOf("Notify.ogg", "notify.ogg", "Notify.mp3", "notify.mp3")
                    var soundFile: DocumentFile? = null
                    for (name in candidates) {
                        val f = dir.findFile(name)
                        if (f != null && f.exists()) {
                            soundFile = f
                            break
                        }
                    }
                    val fileUri = soundFile?.uri ?: return@post
                    var mp: MediaPlayer? = null
                    try {
                        activity.contentResolver.openFileDescriptor(fileUri, "r")?.use { pfd ->
                            mp = MediaPlayer().apply {
                                setDataSource(pfd.fileDescriptor)
                                setOnPreparedListener { it.start() }
                                setOnCompletionListener { it.release() }
                                prepareAsync()
                            }
                        }
                    } catch (e: Exception) {
                        try { mp?.release() } catch (_: Exception) {}
                        e.printStackTrace()
                    }
                } catch (_: Exception) {}
            }
        }

        override fun updateAutoCompleteSuggestions(suggestions: List<String>) {
            handler.post {
                try {
                    val list = suggestions.toMutableList()
                    if (adapter == null) {
                        adapter = object : ArrayAdapter<String>(activity, android.R.layout.simple_dropdown_item_1line, list) {
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
                        adapter?.addAll(list)
                        adapter?.notifyDataSetChanged()
                    }
                    try { queryInput.dropDownBackground = ColorDrawable(0xCC000000.toInt()) } catch (_: Exception) {}
                } catch (_: Exception) {}
            }
        }

        override fun loadTemplatesFromFileRequest(filename: String) {
            // UI отвечает за SAF IO — запускаем загрузку
            handler.post { loadTemplatesFromFile(filename) }
        }

        // --- UI: SAF I/O и парсинг файлов (UIManager читает файлы и передаёт данные в Logic) ---
        fun loadTemplatesFromFile(filename: String) {
            // здесь UI читает файлы из folderUri и наполняет Logic данными
            if (folderUri == null) {
                loadFallbackTemplatesToLogic()
                logic.rebuildInvertedIndex()
                updateUI()
                return
            }
            val dir = try { DocumentFile.fromTreeUri(activity, folderUri!!) } catch (_: Exception) { null } ?: run {
                loadFallbackTemplatesToLogic()
                logic.rebuildInvertedIndex()
                updateUI()
                return
            }

            // clear current logic templates for fresh load
            logic.clearTemplatesAndState()
            // if base, set defaults
            if (filename == "base.txt") {
                logic.setMascotFromMetadata("Racky", "raccoon_icon.png", "#00FF00", "#000000")
            }

            // read main file
            dir.findFile(filename)?.uri?.let { fileUri ->
                try {
                    activity.contentResolver.openInputStream(fileUri)?.bufferedReader()?.useLines { lines ->
                        lines.forEach { rawLine -> parseTemplateLineToLogic(rawLine, filename) }
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                    showCustomToast("Ошибка чтения файла ${filename}: ${e.message}")
                    loadFallbackTemplatesToLogic()
                }
            } ?: run {
                // file not found -> fallback
                loadFallbackTemplatesToLogic()
            }

            // metadata
            val metadataFilename = filename.replace(".txt", "_metadata.txt")
            dir.findFile(metadataFilename)?.uri?.let { metaUri ->
                try {
                    activity.contentResolver.openInputStream(metaUri)?.bufferedReader()?.useLines { lines ->
                        lines.forEach { parseMetadataLineToLogic(it) }
                    }
                } catch (_: Exception) {}
            }

            // optional dialogs file
            dir.findFile("randomreply.txt")?.uri?.let { dialogsUri ->
                try {
                    activity.contentResolver.openInputStream(dialogsUri)?.bufferedReader()?.use { r ->
                        val lines = r.readLines()
                        parseDialogsFileToLogic(lines)
                    }
                } catch (_: Exception) {}
            }

            // synonyms & stopwords
            // synonyms file
            dir.findFile("synonims.txt")?.uri?.let { synUri ->
                try {
                    val map = mutableMapOf<String, String>()
                    activity.contentResolver.openInputStream(synUri)?.bufferedReader()?.useLines { lines ->
                        lines.forEach { line ->
                            val parts = line.removeSurrounding("*").split(";").map { normalizeText(it) }.filter { it.isNotEmpty() }
                            if (parts.size > 1) {
                                val canonical = parts.last()
                                parts.forEach { map[it] = canonical }
                            }
                        }
                    }
                    logic.setSynonyms(map)
                } catch (_: Exception) {}
            }

            // stopwords file
            dir.findFile("stopwords.txt")?.uri?.let { swUri ->
                try {
                    val set = mutableSetOf<String>()
                    activity.contentResolver.openInputStream(swUri)?.bufferedReader()?.use { r ->
                        val text = r.readText()
                        text.split("^").map { normalizeText(it) }.filter { it.isNotEmpty() }.forEach { set.add(it) }
                    }
                    logic.setStopwords(set)
                } catch (_: Exception) {}
            }

            // choose random mascot if base and mascot list filled in logic
            // NOTE: mascots could be added during parseMetadataLineToLogic
            // logic already has mascotList if parsed; but we set defaults above

            // rebuild index and update UI
            logic.rebuildInvertedIndex()
            updateUI()
        }

        private fun parseTemplateLineToLogic(raw: String, filename: String) {
            val l = raw.trim()
            if (l.isEmpty()) return

            try {
                if (filename == "base.txt" && l.startsWith(":") && l.endsWith(":")) {
                    val parts = l.substring(1, l.length - 1).split("=", limit = 2)
                    if (parts.size == 2) logic.addContextMapping(parts[0].trim().lowercase(Locale.getDefault()), parts[1].trim())
                    return
                }

                if (l.startsWith("-")) {
                    val parts = l.substring(1).split("=", limit = 2)
                    if (parts.size == 2) {
                        val key = parts[0].trim().lowercase(Locale.getDefault())
                        val responses = parts[1].split("|").map { it.trim() }.filter { it.isNotEmpty() }
                        logic.addKeywordResponse(key, responses)
                    }
                    return
                }

                val parts = l.split("=", limit = 2)
                if (parts.size == 2) {
                    val trigger = filterStopwordsAndMapSynonymsForUI(parts[0])
                    val responses = parts[1].split("|").map { it.trim() }.filter { it.isNotEmpty() }
                    if (trigger.isNotBlank() && responses.isNotEmpty()) logic.addTemplate(trigger, responses)
                }
            } catch (_: Exception) {}
        }

        private fun parseMetadataLineToLogic(line: String) {
            val t = line.trim()
            when {
                t.startsWith("mascot_list=") -> {
                    val value = t.substring("mascot_list=".length).trim()
                    value.split("|").forEach {
                        val parts = it.split(":")
                        if (parts.size >= 4) {
                            logic.addMascotEntry(parts[0].trim(), parts[1].trim(), parts[2].trim(), parts[3].trim())
                        }
                    }
                }
                t.startsWith("mascot_name=") -> {
                    logic.setMascotFromMetadata(t.substring("mascot_name=".length).trim(), null, null, null)
                }
                t.startsWith("mascot_icon=") -> {
                    logic.setMascotFromMetadata(null, t.substring("mascot_icon=".length).trim(), null, null)
                }
                t.startsWith("theme_color=") -> {
                    logic.setMascotFromMetadata(null, null, t.substring("theme_color=".length).trim(), null)
                }
                t.startsWith("theme_background=") -> {
                    logic.setMascotFromMetadata(null, null, null, t.substring("theme_background=".length).trim())
                }
                t.startsWith("dialog_lines=") -> {
                    val lines = t.substring("dialog_lines=".length).trim().split("|")
                    lines.map { it.trim() }.filter { it.isNotEmpty() }.forEach { logic.addDialogLine(it) }
                }
            }
        }

        private fun parseDialogsFileToLogic(lines: List<String>) {
            var currentDialogParserName: String? = null
            val replies = mutableListOf<Pair<String, String>>()
            for (raw in lines) {
                val l = raw.trim()
                if (l.isEmpty()) continue
                if (l.startsWith(";")) {
                    if (currentDialogParserName != null && replies.isNotEmpty()) {
                        logic.addDialog(currentDialogParserName, replies.toList())
                    }
                    currentDialogParserName = l.substring(1).trim()
                    replies.clear()
                } else if (l.contains(">")) {
                    val parts = l.split(">", limit = 2)
                    if (parts.size == 2) {
                        val mascot = parts[0].trim()
                        val text = parts[1].trim()
                        if (mascot.isNotEmpty() && text.isNotEmpty()) replies.add(Pair(mascot, text))
                    }
                } else {
                    logic.addDialogLine(l)
                }
            }
            if (currentDialogParserName != null && replies.isNotEmpty()) {
                logic.addDialog(currentDialogParserName, replies.toList())
            }
        }

        // Helper: create fallback templates locally and push to logic
        private fun loadFallbackTemplatesToLogic() {
            logic.clearTemplatesAndState()
            logic.addTemplate(filterStopwordsAndMapSynonymsForUI("привет"), listOf("Привет! Чем могу помочь?", "Здравствуй!"))
            logic.addTemplate(filterStopwordsAndMapSynonymsForUI("как дела"), listOf("Всё отлично, а у тебя?", "Нормально, как дела?"))
            logic.addKeywordResponse("спасибо", listOf("Рад, что помог!", "Всегда пожалуйста!"))
        }

        // --- UI helper methods (draw bubble, load avatar, etc.) ---
        private fun createMessageBubble(sender: String, text: String): LinearLayout {
            val accent = safeParseColorOrDefault(logic.currentThemeColor, Color.parseColor("#00FF00"))
            return LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                background = createBubbleDrawable(accent)
                val pad = dpToPx(10)
                setPadding(pad, pad, pad, pad)

                addView(TextView(activity).apply {
                    this.text = "$sender:"
                    textSize = 12f
                    setTextColor(Color.parseColor("#AAAAAA"))
                })
                addView(TextView(activity).apply {
                    this.text = text
                    textSize = 16f
                    setTextIsSelectable(true)
                    try { setTextColor(Color.parseColor(logic.currentThemeColor)) } catch (_: Exception) { setTextColor(Color.WHITE) }
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
            try {
                val dir = folderUri?.let { DocumentFile.fromTreeUri(activity, it) } ?: return
                val s = sender.lowercase(Locale.getDefault())
                val candidates = listOf("${s}_icon.png", "${s}_avatar.png", "${s}.png", logic.currentMascotIcon)
                for (name in candidates) {
                    val file = dir.findFile(name) ?: continue
                    val fileUri = file.uri ?: continue
                    try {
                        activity.contentResolver.openInputStream(fileUri)?.use {
                            val bmp = BitmapFactory.decodeStream(it)
                            if (bmp != null) {
                                target.setImageBitmap(bmp)
                                return
                            }
                        }
                    } catch (_: Exception) {}
                }
                target.setImageResource(android.R.color.transparent)
            } catch (_: Exception) {}
        }

        private fun loadToolbarIcons() {
            val uri = folderUri ?: return
            try {
                val dir = DocumentFile.fromTreeUri(activity, uri) ?: return

                fun tryLoad(name: String, target: ImageButton?) {
                    try {
                        val file = dir.findFile(name) ?: return
                        val fileUri = file.uri ?: return
                        activity.contentResolver.openInputStream(fileUri)?.use { ins ->
                            val bmp = BitmapFactory.decodeStream(ins)
                            if (bmp != null) {
                                target?.setImageBitmap(bmp)
                            }
                        }
                    } catch (_: Exception) {}
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

        private fun setupIconTouchEffect(btn: ImageButton?) {
            btn?.setOnTouchListener { v, event ->
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> v.alpha = 0.6f
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> v.alpha = 1.0f
                }
                false
            }
        }

        private fun updateUI() {
            handler.post {
                try {
                    activity.title = "Pawstribe - ${logic.currentMascotName}"
                    mascotTopImage?.visibility = View.GONE
                    try { messagesContainer.setBackgroundColor(Color.parseColor(logic.currentThemeBackground)) } catch (_: Exception) {}
                    // refresh autocomplete
                    logic.rebuildInvertedIndex()
                } catch (_: Exception) {}
            }
        }

        private fun clearChat() {
            handler.post {
                try { messagesContainer.removeAllViews() } catch (_: Exception) {}
                logic.clearTemplatesAndState()
                logic.setMascotFromMetadata("Racky", "raccoon_icon.png", "#00FF00", "#000000")
                loadTemplatesFromFile("base.txt")
                addChatMessage(logic.currentMascotName, "Чат очищен. Возвращаюсь к началу.")
            }
        }

        private fun showCustomToast(message: String) {
            handler.post {
                try {
                    Toast.makeText(activity, message, Toast.LENGTH_SHORT).show()
                } catch (_: Exception) {}
            }
        }

        private fun dpToPx(dp: Int): Int = (dp * activity.resources.displayMetrics.density).roundToInt()
        private fun blendColors(base: Int, accent: Int, ratio: Float): Int {
            val r = ( (base shr 16 and 0xff) * (1 - ratio) + (accent shr 16 and 0xff) * ratio ).roundToInt()
            val g = ( (base shr 8 and 0xff) * (1 - ratio) + (accent shr 8 and 0xff) * ratio ).roundToInt()
            val b = ( (base and 0xff) * (1 - ratio) + (accent and 0xff) * ratio ).roundToInt()
            return Color.rgb(r, g, b)
        }
        private fun safeParseColorOrDefault(spec: String?, fallback: Int): Int {
            return try { Color.parseColor(spec) } catch (_: Exception) { fallback }
        }

        private fun filterStopwordsAndMapSynonymsForUI(input: String): String {
            // A light-weight helper for UI-side normalization when building triggers before passing to logic
            val tokens = input
                .lowercase(Locale.getDefault())
                .replace(Regex("[^\\p{L}\\p{Nd}\\s]"), " ")
                .split(Regex("\\s+"))
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .map { logic.synonymsMap[it] ?: it }
                .filter { !logic.stopwords.contains(it) }
            return tokens.joinToString(" ")
        }

        private fun normalizeText(s: String): String {
            val lower = s.lowercase(Locale.getDefault())
            val cleaned = lower.replace(Regex("[^\\p{L}\\p{Nd}\\s]"), " ")
            return cleaned.replace(Regex("\\s+"), " ").trim()
        }

        // --- end UIManager ---
    }
}
