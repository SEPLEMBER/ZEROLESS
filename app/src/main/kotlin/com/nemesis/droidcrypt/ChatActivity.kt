package com.nemesis.droidcrypt

import android.animation.ObjectAnimator
import android.content.*
import android.graphics.*
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.ColorDrawable
import android.media.MediaPlayer
import android.net.Uri
import android.os.BatteryManager
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
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.*
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.roundToInt

class ChatActivity : AppCompatActivity() {

    companion object {
        private const val PREF_KEY_FOLDER_URI = "pref_folder_uri"
        private const val PREF_KEY_DISABLE_SCREENSHOTS = "pref_disable_screenshots"
        private const val MAX_CONTEXT_SWITCH = 6 // Note: This is now unused due to logic simplification
        private const val MAX_MESSAGES = 250
        private const val CANDIDATE_TOKEN_THRESHOLD = 2 // минимальное число общих токенов для кандидата
        private const val MAX_SUBQUERY_RESPONSES = 3 // Ограничение на количество подответов
        private const val SUBQUERY_RESPONSE_DELAY = 1500L // Задержка для индикатора "печатает..."
        private const val MAX_CANDIDATES_FOR_LEV = 25 // ограничение числа кандидатов для Levenshtein
        private const val JACCARD_THRESHOLD = 0.50

        // Debounce for send button
        private const val SEND_DEBOUNCE_MS = 400L
    }

    private fun getFuzzyDistance(word: String): Int {
        return when {
            word.length <= 4 -> 2
            word.length <= 8 -> 2
            else -> 3
        }
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
    private var btnCharging: ImageButton? = null
    private lateinit var messagesContainer: LinearLayout
    private var adapter: ArrayAdapter<String>? = null

    // Added UI elements (status bar overlay)
    private var batteryImageView: ImageView? = null
    private var batteryPercentView: TextView? = null
    private var timeTextView: TextView? = null
    private var infoIconButton: ImageButton? = null

    // Data structures
    private val fallback = arrayOf("Привет", "Как дела?", "Расскажи о себе", "Выход")
    private val templatesMap = HashMap<String, MutableList<String>>() // keys are normalized triggers now
    private val contextMap = HashMap<String, String>()
    private val keywordResponses = HashMap<String, MutableList<String>>()
    private val antiSpamResponses = mutableListOf<String>()
    private val mascotList = mutableListOf<Map<String, String>>()
    private val dialogLines = mutableListOf<String>()
    private val dialogs = mutableListOf<Dialog>()

    // inverted index token -> list of triggers (normalized)
    private val invertedIndex = HashMap<String, MutableList<String>>() // token -> list of trigger keys

    // synonyms and stopwords storage
    private val synonymsMap = HashMap<String, String>() // synonym -> canonical
    private val stopwords = HashSet<String>() // normalized stopwords set

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

    // send debounce
    private var lastSendTime = 0L

    // battery/watch
    private var lastBatteryWarningStage = Int.MAX_VALUE // high sentinel; we will detect downward crossing
    private var batteryReceiver: BroadcastReceiver? = null
    private val timeHandler = Handler(Looper.getMainLooper())
    private var timeUpdaterRunnable: Runnable? = null

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

    /// SECTION: Lifecycle — Инициализация Activity (onCreate, onResume, onPause, onDestroy)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_chat)

        // Полноэкранный режим
        window.addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN)

        // Установка полупрозрачного тёмного фона для action bar
        supportActionBar?.setBackgroundDrawable(ColorDrawable(Color.argb(128, 0, 0, 0)))

        // Установка чёрного фона для окна, чтобы избежать белого фона при открытии клавиатуры
        window.setBackgroundDrawable(ColorDrawable(Color.BLACK))

        // Настройка тулбара
        setupToolbar()

        // refs
        scrollView = findViewById(R.id.scrollView)
        queryInput = findViewById(R.id.queryInput)
        envelopeInputButton = findViewById(R.id.envelope_button)
        // mascotTopImage = findViewById(R.id.mascot_top_image)  // Убрано: ID отсутствует в layout, изображение загружается динамически из SAF при необходимости
        btnLock = findViewById(R.id.btn_lock)
        btnTrash = findViewById(R.id.btn_trash)
        btnEnvelopeTop = findViewById(R.id.btn_envelope_top)
        btnSettings = findViewById(R.id.btn_settings)
        messagesContainer = findViewById(R.id.chatMessagesContainer)

        // SAF Uri: Intent -> persisted -> prefs
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

        // load synonyms & stopwords early (if folderUri available this will read files)
        loadSynonymsAndStopwords()

        // screenshots lock from prefs
        try {
            val prefs = PreferenceManager.getDefaultSharedPreferences(this)
            val disable = prefs.getBoolean(PREF_KEY_DISABLE_SCREENSHOTS, false)
            if (disable) window.addFlags(WindowManager.LayoutParams.FLAG_SECURE) else window.clearFlags(
                WindowManager.LayoutParams.FLAG_SECURE
            )
        } catch (_: Exception) {
        }

        // load icons
        loadToolbarIcons()
        setupIconTouchEffect(btnLock)
        setupIconTouchEffect(btnTrash)
        setupIconTouchEffect(btnEnvelopeTop)
        setupIconTouchEffect(btnSettings)
        setupIconTouchEffect(envelopeInputButton)
        setupIconTouchEffect(infoIconButton)
        setupIconTouchEffect(btnCharging)

        // icon actions
        btnLock?.setOnClickListener { finish() }
        btnTrash?.setOnClickListener { clearChat() }
        btnSettings?.setOnClickListener { startActivity(Intent(this, SettingsActivity::class.java)) }
        btnEnvelopeTop?.setOnClickListener { startActivity(Intent(this, PostsActivity::class.java)) }
        infoIconButton?.setOnClickListener { startActivity(Intent(this, PostsActivity::class.java)) }

        // envelope near input — отправка (с дебаунсом)
        envelopeInputButton?.setOnClickListener {
            val now = System.currentTimeMillis()
            if (now - lastSendTime < SEND_DEBOUNCE_MS) return@setOnClickListener
            lastSendTime = now
            val input = queryInput.text.toString().trim()
            if (input.isNotEmpty()) {
                processUserQuery(input)
                queryInput.setText("")
            }
        }

        // allow Enter key press to send (optional)
        queryInput.setOnEditorActionListener { _, _, _ ->
            val now = System.currentTimeMillis()
            if (now - lastSendTime < SEND_DEBOUNCE_MS) return@setOnEditorActionListener true
            lastSendTime = now
            val input = queryInput.text.toString().trim()
            if (input.isNotEmpty()) {
                processUserQuery(input)
                queryInput.setText("")
            }
            true
        }

        // initial parse / fallback
        if (folderUri == null) {
            showCustomToast("Папка не выбрана! Открой настройки и выбери папку.")
            loadFallbackTemplates()
            rebuildInvertedIndex()
            updateAutoComplete()
            addChatMessage(currentMascotName, "Добро пожаловать!")
        } else {
            loadTemplatesFromFile(currentContext)
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

        // hide big avatar (теперь динамически, если нужно)
        loadMascotTopImage()
        mascotTopImage?.visibility = View.GONE
    }

    private fun setupToolbar() {
        val topBar = findViewById<LinearLayout>(R.id.topBar)

        // Настраиваем левую секцию для иконки батареи и процента
        val leftLayout = topBar.getChildAt(0) as LinearLayout
        leftLayout.removeAllViews()
        leftLayout.orientation = LinearLayout.HORIZONTAL
        leftLayout.gravity = Gravity.CENTER_VERTICAL

        batteryImageView = ImageView(this).apply {
            val iconSize = dpToPx(56)
            layoutParams = LinearLayout.LayoutParams(iconSize, iconSize)
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            adjustViewBounds = true
        }
        batteryPercentView = TextView(this).apply {
            text = "--%"
            textSize = 16f
            setTextColor(Color.parseColor("#00BFFF"))
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                marginStart = dpToPx(8)
            }
            layoutParams = lp
        }
        leftLayout.addView(batteryImageView)
        leftLayout.addView(batteryPercentView)

        // Заменяем спейсер на часы по центру
        val spacerIndex = 1
        val spacer = topBar.getChildAt(spacerIndex)
        topBar.removeViewAt(spacerIndex)
        timeTextView = TextView(this).apply {
            text = "--:--"
            textSize = 20f
            setTextColor(Color.parseColor("#FFA500"))
            gravity = Gravity.CENTER
            val lp = LinearLayout.LayoutParams(
                0,
                dpToPx(56),
                1f
            )
            layoutParams = lp
        }
        topBar.addView(timeTextView, spacerIndex)

        // Добавляем кнопку info перед lock
        infoIconButton = ImageButton(this).apply {
            background = null
            val iconSize = dpToPx(56)
            val lp = LinearLayout.LayoutParams(iconSize, iconSize)
            layoutParams = lp
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            adjustViewBounds = true
        }
        topBar.addView(infoIconButton, 2) // Вставляем перед btn_lock (теперь на позиции 3)

        // Добавляем пятую иконку для зарядки в конец
        btnCharging = ImageButton(this).apply {
            background = null
            val iconSize = dpToPx(56)
            val lp = LinearLayout.LayoutParams(iconSize, iconSize).apply {
                marginStart = dpToPx(6)
            }
            layoutParams = lp
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            adjustViewBounds = true
            visibility = View.GONE
        }
        topBar.addView(btnCharging)
    }

    override fun onResume() {
        super.onResume()
        folderUri?.let { loadTemplatesFromFile(currentContext) }
        rebuildInvertedIndex()
        updateAutoComplete()
        idleCheckRunnable?.let {
            dialogHandler.removeCallbacks(it)
            dialogHandler.postDelayed(it, 5000)
        }
        loadToolbarIcons()

        // register battery receiver
        registerBatteryReceiver()
        // start time updater
        startTimeUpdater()
    }

    override fun onPause() {
        super.onPause()
        stopDialog()
        dialogHandler.removeCallbacksAndMessages(null)
        unregisterBatteryReceiver()
        stopTimeUpdater()
    }

    override fun onDestroy() {
        super.onDestroy()
        dialogHandler.removeCallbacksAndMessages(null)
        unregisterBatteryReceiver()
        stopTimeUpdater()
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

            fun tryLoadToImageButton(name: String, target: ImageButton?) {
                if (target == null) return
                try {
                    val file = dir.findFile(name)
                    if (file != null && file.exists()) {
                        contentResolver.openInputStream(file.uri)?.use { ins ->
                            val bmp = BitmapFactory.decodeStream(ins)
                            target.setImageBitmap(bmp)
                        }
                    }
                } catch (_: Exception) {
                }
            }

            fun tryLoadToImageView(name: String, target: ImageView?) {
                if (target == null) return
                try {
                    val file = dir.findFile(name)
                    if (file != null && file.exists()) {
                        contentResolver.openInputStream(file.uri)?.use { ins ->
                            val bmp = BitmapFactory.decodeStream(ins)
                            target.setImageBitmap(bmp)
                        }
                    }
                } catch (_: Exception) {
                }
            }

            // top icons expected names
            tryLoadToImageButton("info.png", infoIconButton)
            tryLoadToImageButton("lock.png", btnLock)
            tryLoadToImageButton("trash.png", btnTrash)
            tryLoadToImageButton("envelope.png", btnEnvelopeTop)
            tryLoadToImageButton("settings.png", btnSettings)
            tryLoadToImageButton("charging.png", btnCharging)

            // send button near input (envelopeInputButton) — try load send.png
            tryLoadToImageButton("send.png", envelopeInputButton)

            // battery image is loaded dynamically by updateBatteryUI using battery_N.png
            tryLoadToImageView("battery_5.png", batteryImageView) // attempt to load a default full image if present

        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    // Новый метод для динамической загрузки верхнего изображения маскота из SAF (если файл существует)
    private fun loadMascotTopImage() {
        val uri = folderUri ?: return
        try {
            val dir = DocumentFile.fromTreeUri(this, uri) ?: return
            val candidates = listOf("${currentMascotName.lowercase(Locale.getDefault())}.png", "mascot_top.png", currentMascotIcon)
            for (name in candidates) {
                val file = dir.findFile(name)
                if (file != null && file.exists()) {
                    contentResolver.openInputStream(file.uri)?.use { ins ->
                        val bmp = BitmapFactory.decodeStream(ins)
                        // Создаем ImageView динамически, если его нет в layout
                        if (mascotTopImage == null) {
                            mascotTopImage = ImageView(this).apply {
                                val size = dpToPx(120)
                                layoutParams = LinearLayout.LayoutParams(size, size).apply {
                                    gravity = Gravity.CENTER_HORIZONTAL
                                    topMargin = dpToPx(8)
                                }
                                scaleType = ImageView.ScaleType.CENTER_CROP
                                adjustViewBounds = true
                            }
                            // Добавляем в подходящий контейнер, например, в messagesContainer или root
                            val root = findViewById<ViewGroup>(android.R.id.content)
                            root.addView(mascotTopImage, 0) // Добавляем сверху
                        }
                        mascotTopImage?.setImageBitmap(bmp)
                        return
                    }
                }
            }
        } catch (_: Exception) {
            // Игнорируем: fallback не нужен, так как visibility = GONE
        }
    }

    // === SECTION core: process user query ===
    private fun processUserQuery(userInput: String) {
        // command handling: if starts with '/', treat as admin/cli command
        if (userInput.startsWith("/")) {
            handleCommand(userInput.trim())
            return
        }

        // normalize input early (remove punctuation, collapse spaces)
        val qOrigRaw = userInput.trim()
        val qOrig = normalizeText(qOrigRaw)

        // filter stopwords and map synonyms producing tokens and joined string
        val (qTokensFiltered, qFiltered) = filterStopwordsAndMapSynonyms(qOrig)

        // store/use normalized keys in queryCountMap to count repeats robustly
        val qKeyForCount = qFiltered

        if (qFiltered.isEmpty()) return

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

        // show user message immediately (UI)
        addChatMessage("Ты", userInput)

        // show typing indicator
        showTypingIndicator()

        val repeats = queryCountMap.getOrDefault(qKeyForCount, 0)
        if (repeats >= 5) {
            val spamResp = antiSpamResponses.random()
            addChatMessage(currentMascotName, spamResp)
            startIdleTimer()
            return
        }

        // Snapshot necessary structures to avoid races while computing in background
        val templatesSnapshot = HashMap(templatesMap)
        val invertedIndexSnapshot = HashMap<String, MutableList<String>>()
        // Deep copy lists for invertedIndex
        for ((k, v) in invertedIndex) invertedIndexSnapshot[k] = ArrayList(v)
        val synonymsSnapshot = HashMap(synonymsMap)
        val stopwordsSnapshot = HashSet(stopwords)
        val keywordResponsesSnapshot = HashMap<String, MutableList<String>>()
        for ((k, v) in keywordResponses) keywordResponsesSnapshot[k] = ArrayList(v)
        val contextMapSnapshot = HashMap(contextMap)

        // launch background computation
        lifecycleScope.launch(Dispatchers.Default) {
            data class ResponseResult(val text: String? = null, val wantsContextSwitch: String? = null)

            // local helpers using snapshots
            fun tokenizeLocal(s: String): List<String> {
                if (s.isBlank()) return emptyList()
                return s.split(Regex("\\s+")).map { it.trim() }.filter { it.isNotEmpty() }
            }

            fun normalizeLocal(s: String): String {
                val lower = s.lowercase(Locale.getDefault())
                val cleaned = lower.replace(Regex("[^\\p{L}\\p{Nd}\\s]"), " ")
                val collapsed = cleaned.replace(Regex("\\s+"), " ").trim()
                return collapsed
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

            // Begin matching logic (based on original algorithm, but using snapshots)
            var answered = false
            val subqueryResponses = mutableListOf<String>()
            val processedSubqueries = mutableSetOf<String>()

            // 1. exact match in templatesSnapshot
            templatesSnapshot[qFiltered]?.let { possible ->
                if (possible.isNotEmpty()) {
                    subqueryResponses.add(possible.random())
                    answered = true
                    processedSubqueries.add(qFiltered)
                }
            }

            // 2. subqueries (tokens & two-token combos)
            if (subqueryResponses.size < MAX_SUBQUERY_RESPONSES) {
                val tokens = if (qTokensFiltered.isNotEmpty()) qTokensFiltered else tokenizeLocal(qFiltered)

                for (token in tokens) {
                    if (subqueryResponses.size >= MAX_SUBQUERY_RESPONSES) break
                    if (processedSubqueries.contains(token) || token.length < 2) continue

                    templatesSnapshot[token]?.let { possible ->
                        if (possible.isNotEmpty()) {
                            subqueryResponses.add(possible.random())
                            processedSubqueries.add(token)
                        }
                    }
                    if (subqueryResponses.size < MAX_SUBQUERY_RESPONSES) {
                        keywordResponsesSnapshot[token]?.let { possible ->
                            if (possible.isNotEmpty()) {
                                subqueryResponses.add(possible.random())
                                processedSubqueries.add(token)
                            }
                        }
                    }
                }

                if (subqueryResponses.size < MAX_SUBQUERY_RESPONSES && tokens.size > 1) {
                    for (i in 0 until tokens.size - 1) {
                        if (subqueryResponses.size >= MAX_SUBQUERY_RESPONSES) break
                        val twoTokens = "${tokens[i]} ${tokens[i + 1]}"
                        if (processedSubqueries.contains(twoTokens)) continue

                        templatesSnapshot[twoTokens]?.let { possible ->
                            if (possible.isNotEmpty()) {
                                subqueryResponses.add(possible.random())
                                processedSubqueries.add(twoTokens)
                            }
                        }
                    }
                }
            }

            if (subqueryResponses.isNotEmpty()) {
                val combined = subqueryResponses.joinToString(". ")
                // return combined result
                return@launch withContext(Dispatchers.Main) {
                    addChatMessage(currentMascotName, combined)
                    startIdleTimer()
                }
            }

            // 4. keyword responses
            for ((keyword, responses) in keywordResponsesSnapshot) {
                if (qFiltered.contains(keyword) && responses.isNotEmpty()) {
                    return@launch withContext(Dispatchers.Main) {
                        addChatMessage(currentMascotName, responses.random())
                        startIdleTimer()
                    }
                }
            }

            // 5. FUZZY matching (invertedIndexSnapshot -> Jaccard -> Levenshtein)
            val qTokens = if (qTokensFiltered.isNotEmpty()) qTokensFiltered else tokenizeLocal(qFiltered)
            val candidateCounts = HashMap<String, Int>()
            for (tok in qTokens) {
                invertedIndexSnapshot[tok]?.forEach { trig ->
                    candidateCounts[trig] = candidateCounts.getOrDefault(trig, 0) + 1
                }
            }

            val candidates: List<String> = if (candidateCounts.isNotEmpty()) {
                candidateCounts.entries
                    .filter { it.value >= CANDIDATE_TOKEN_THRESHOLD }
                    .sortedByDescending { it.value }
                    .map { it.key }
                    .take(MAX_CANDIDATES_FOR_LEV)
            } else {
                val maxDist = getFuzzyDistance(qFiltered)
                templatesSnapshot.keys.filter { abs(it.length - qFiltered.length) <= maxDist }
                    .take(MAX_CANDIDATES_FOR_LEV)
            }

            // Try Jaccard first
            var bestByJaccard: String? = null
            var bestJaccard = 0.0
            val qSet = qTokens.toSet()
            for (key in candidates) {
                val keyTokens = filterStopwordsAndMapSynonymsLocal(key).first.toSet()
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
                val possible = templatesSnapshot[bestByJaccard]
                if (!possible.isNullOrEmpty()) {
                    return@launch withContext(Dispatchers.Main) {
                        addChatMessage(currentMascotName, possible.random())
                        startIdleTimer()
                    }
                }
            }

            // fallback to Levenshtein
            var bestKey: String? = null
            var bestDist = Int.MAX_VALUE
            for (key in candidates) {
                val maxDist = getFuzzyDistance(qFiltered)
                if (abs(key.length - qFiltered.length) > maxDist + 1) continue
                val d = levenshtein(qFiltered, key, qFiltered)
                if (d < bestDist) {
                    bestDist = d
                    bestKey = key
                }
                if (bestDist == 0) break
            }
            val maxDist = getFuzzyDistance(qFiltered)
            if (bestKey != null && bestDist <= maxDist) {
                val possible = templatesSnapshot[bestKey]
                if (!possible.isNullOrEmpty()) {
                    return@launch withContext(Dispatchers.Main) {
                        addChatMessage(currentMascotName, possible.random())
                        startIdleTimer()
                    }
                }
            }

            // 6. If still no answer, request a context switch suggestion (do not load files here)
            val lower = normalizeLocal(qFiltered)
            for ((keyword, value) in contextMapSnapshot) {
                if (lower.contains(keyword)) {
                    // signal to main thread to possibly load new context and re-check
                    return@launch withContext(Dispatchers.Main) {
                        // Attempt to switch context on main thread (commit)
                        if (value != currentContext) {
                            currentContext = value
                            loadTemplatesFromFile(currentContext)
                            rebuildInvertedIndex()
                            updateAutoComplete()
                            // Re-check for an answer in the new context after switching
                            templatesMap[qFiltered]?.let { possible ->
                                if (possible.isNotEmpty()) {
                                    addChatMessage(currentMascotName, possible.random())
                                    startIdleTimer()
                                    return@withContext
                                }
                            }
                        }
                        // If still nothing, fallback
                        addChatMessage(currentMascotName, getDummyResponse(qOrig))
                        startIdleTimer()
                    }
                }
            }

            // 7. Final fallback
            return@launch withContext(Dispatchers.Main) {
                addChatMessage(currentMascotName, getDummyResponse(qOrig))
                startIdleTimer()
            }
        }
    }

    // Command handler
    private fun handleCommand(cmdRaw: String) {
        val cmd = cmdRaw.trim().lowercase(Locale.getDefault())
        when {
            cmd == "/reload" -> {
                addChatMessage(currentMascotName, "Перезагружаю шаблоны...")
                loadTemplatesFromFile(currentContext)
                rebuildInvertedIndex()
                updateAutoComplete()
                addChatMessage(currentMascotName, "Шаблоны перезагружены.")
            }
            cmd == "/stats" -> {
                val templatesCount = templatesMap.size
                val keywordsCount = keywordResponses.size
                val msg = "Контекст: $currentContext. Шаблонов: $templatesCount. Ключевых ответов: $keywordsCount."
                addChatMessage(currentMascotName, msg)
            }
            cmd == "/clear" -> {
                clearChat()
            }
            else -> {
                addChatMessage(currentMascotName, "Неизвестная команда: $cmdRaw")
            }
        }
    }

    // Новый метод для показа полупрозрачного уведомления "Печатает..."
    private fun showTypingIndicator() {
        runOnUi {
            val existing = messagesContainer.findViewWithTag<View>("typingView")
            if (existing != null) return@runOnUi

            val typingView = TextView(this).apply {
                text = "печатает..."
                textSize = 14f
                setTextColor(getColor(android.R.color.white))
                setBackgroundColor(0x80000000.toInt()) // Полупрозрачный чёрный фон
                alpha = 0.7f // Дополнительная полупрозрачность
                setPadding(16, 8, 16, 8)
                tag = "typingView"
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    setMargins(0, 16, 0, 0)
                }
            }
            // Добавляем в конец (нижняя часть)
            messagesContainer.addView(typingView)
            scrollView.post { scrollView.smoothScrollTo(0, messagesContainer.bottom) }

            // Рандомная задержка 1–3 секунды перед удалением
            val randomDelay = (1000..3000).random().toLong()
            Handler(Looper.getMainLooper()).postDelayed({
                runOnUi {
                    messagesContainer.findViewWithTag<View>("typingView")?.let { messagesContainer.removeView(it) }
                }
            }, randomDelay)
        }
    }

    private fun removeTypingIndicator() {
        runOnUi {
            messagesContainer.findViewWithTag<View>("typingView")?.let { messagesContainer.removeView(it) }
        }
    }

    private fun clearChat() {
        runOnUi {
            messagesContainer.removeAllViews()
            queryCountMap.clear()
            lastQuery = ""
            currentContext = "base.txt"
            loadTemplatesFromFile(currentContext)
            rebuildInvertedIndex()
            updateAutoComplete()
            addChatMessage(currentMascotName, "Чат очищен. Возвращаюсь к началу.")
        }
    }

    private fun detectContext(input: String): String? {
        val lower = normalizeText(input) // normalize when detecting context
        for ((keyword, value) in contextMap) {
            if (lower.contains(keyword)) return value
        }
        return null
    }

    private fun getDummyResponse(query: String): String {
        val lower = query.lowercase(Locale.ROOT)
        return when {
            lower.contains("привет") -> "Привет! Чем могу помочь?"
            lower.contains("как дела") -> "Всё отлично, а у тебя?"
            else -> "Не понял запрос. Попробуй другой вариант."
        }
    }

    // normalize text (remove punctuation, collapse spaces)
    private fun normalizeText(s: String): String {
        // keep letters, digits and spaces only
        val lower = s.lowercase(Locale.getDefault())
        val cleaned = lower.replace(Regex("[^\\p{L}\\p{Nd}\\s]"), " ")
        val collapsed = cleaned.replace(Regex("\\s+"), " ").trim()
        return collapsed
    }

    // tokenize (split normalized text to tokens)
    private fun tokenize(s: String): List<String> {
        if (s.isBlank()) return emptyList()
        return s.split(Regex("\\s+")).map { it.trim() }.filter { it.isNotEmpty() }
    }

    // load synonyms and stopwords from folder via SAF
    private fun loadSynonymsAndStopwords() {
        synonymsMap.clear()
        stopwords.clear()
        val uri = folderUri ?: return
        try {
            val dir = DocumentFile.fromTreeUri(this, uri) ?: return

            // synonims.txt (note: filename spelled as in your request)
            val synFile = dir.findFile("synonims.txt")
            if (synFile != null && synFile.exists()) {
                contentResolver.openInputStream(synFile.uri)?.bufferedReader()?.use { reader ->
                    reader.forEachLine { raw ->
                        var l = raw.trim()
                        if (l.isEmpty()) return@forEachLine
                        // allow lines wrapped in * or not
                        if (l.startsWith("*") && l.endsWith("*") && l.length > 1) {
                            l = l.substring(1, l.length - 1)
                        }
                        val parts = l.split(";").map { normalizeText(it).trim() }.filter { it.isNotEmpty() }
                        if (parts.isEmpty()) return@forEachLine
                        // choose last element as canonical
                        val canonical = parts.last()
                        for (p in parts) {
                            synonymsMap[p] = canonical
                        }
                    }
                }
            }

            // stopwords.txt with ^ separators: ^я^бы^хотел^узнать^ ...
            val stopFile = dir.findFile("stopwords.txt")
            if (stopFile != null && stopFile.exists()) {
                contentResolver.openInputStream(stopFile.uri)?.bufferedReader()?.use { reader ->
                    val all = reader.readText()
                    if (all.isNotEmpty()) {
                        val parts = all.split("^").map { normalizeText(it).trim() }.filter { it.isNotEmpty() }
                        for (p in parts) stopwords.add(p)
                    }
                }
            }
        } catch (e: Exception) {
            // ignore silently — allow app to continue without synonyms/stopwords
        }
    }

    // filter stopwords and map synonyms for an input string
    // returns Pair(listOfTokens, joinedNormalizedString)
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

    // rebuild inverted index from current templatesMap (respect stopwords & synonyms)
    private fun rebuildInvertedIndex() {
        invertedIndex.clear()
        for (key in templatesMap.keys) {
            // key already normalized, further filter and map synonyms
            val toks = filterStopwordsAndMapSynonyms(key).first
            for (t in toks) {
                val list = invertedIndex.getOrPut(t) { mutableListOf() }
                // avoid duplicates
                if (!list.contains(key)) list.add(key)
            }
        }
    }

    // Levenshtein implementation (optimized with rolling rows and early exit)
    private fun levenshtein(s: String, t: String, qFiltered: String): Int {
        // quick shortcuts
        if (s == t) return 0
        val n = s.length
        val m = t.length
        if (n == 0) return m
        if (m == 0) return n

        val maxDist = getFuzzyDistance(qFiltered)
        if (abs(n - m) > maxDist + 2) return Int.MAX_VALUE / 2

        // use two rolling rows to save memory
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

            val maxDistRow = getFuzzyDistance(qFiltered)
            if (minInRow > maxDistRow + 2) return Int.MAX_VALUE / 2

            for (k in 0..m) prev[k] = curr[k]
        }

        return prev[m]
    }

    /// SECTION: UI Messages — Создание и добавление сообщений в чат
    private fun addChatMessage(sender: String, text: String) {
        runOnUi {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                val pad = dpToPx(6)
                setPadding(pad, pad / 2, pad, pad / 2)
                layoutParams =
                    LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                    )
            }

            val isUser = sender.equals("Ты", ignoreCase = true)

            if (isUser) {
                val bubble = createMessageBubble(sender, text, isUser)
                val lp =
                    LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                    )
                lp.gravity = Gravity.END
                lp.marginStart = dpToPx(48)
                row.addView(spaceView(), LinearLayout.LayoutParams(0, 0, 1f))
                row.addView(bubble, lp)
            } else {
                val avatarView = ImageView(this).apply {
                    val size = dpToPx(64)
                    layoutParams = LinearLayout.LayoutParams(size, size)
                    scaleType = ImageView.ScaleType.CENTER_CROP
                    adjustViewBounds = true
                    loadAvatarInto(this, sender)
                }
                val bubble = createMessageBubble(sender, text, isUser)
                val bubbleLp =
                    LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                    )
                bubbleLp.marginStart = dpToPx(8)
                row.addView(avatarView)
                row.addView(bubble, bubbleLp)
            }

            messagesContainer.addView(row)
            // remove typing indicator if present
            messagesContainer.findViewWithTag<View>("typingView")?.let { messagesContainer.removeView(it) }

            if (messagesContainer.childCount > MAX_MESSAGES) {
                val removeCount = messagesContainer.childCount - MAX_MESSAGES
                repeat(removeCount) { messagesContainer.removeViewAt(0) }
            }
            scrollView.post { scrollView.smoothScrollTo(0, messagesContainer.bottom) }

            // Проигрывание звука уведомления для входящих сообщений (не от пользователя)
            if (!isUser) {
                playNotificationSound()
            }
        }
    }

    private fun playNotificationSound() {
        val uri = folderUri ?: return
        try {
            val dir = DocumentFile.fromTreeUri(this, uri) ?: return
            val soundFile = dir.findFile("notify.ogg") ?: return
            if (!soundFile.exists()) return

            // safer MediaPlayer usage
            val afd = contentResolver.openAssetFileDescriptor(soundFile.uri, "r") ?: return
            val player = MediaPlayer()
            try {
                player.setDataSource(afd.fileDescriptor, afd.startOffset, afd.length)
                afd.close()
                player.prepareAsync()
                player.setOnPreparedListener { it.start() }
                player.setOnCompletionListener { it.reset(); it.release() }
                player.setOnErrorListener { mp, _, _ -> try { mp.reset(); mp.release() } catch (_: Exception) {}; true }
            } catch (e: Exception) {
                try { afd.close() } catch (_: Exception) {}
                try { player.reset(); player.release() } catch (_: Exception) {}
            }
        } catch (_: Exception) {
            // Игнорируем ошибки проигрывания
        }
    }

    private fun spaceView(): View = View(this).apply {
        layoutParams = LinearLayout.LayoutParams(0, 0, 1f)
    }

    private fun createMessageBubble(
        sender: String,
        text: String,
        isUser: Boolean
    ): LinearLayout {
        val container = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val tvSender = TextView(this).apply {
            this.text = "$sender:"
            textSize = 12f
            setTextColor(Color.parseColor("#AAAAAA"))
        }
        val tv = TextView(this).apply {
            this.text = text
            textSize = 16f
            setTextIsSelectable(true)
            val pad = dpToPx(10)
            setPadding(pad, pad, pad, pad)
            val accent = safeParseColorOrDefault(currentThemeColor, Color.parseColor("#00FF00"))
            background = createBubbleDrawable(accent)
            try {
                setTextColor(Color.parseColor(currentThemeColor))
            } catch (_: Exception) {
                setTextColor(Color.WHITE)
            }
        }
        container.addView(tvSender)
        container.addView(tv)
        return container
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
        val uri = folderUri ?: return
        try {
            val dir = DocumentFile.fromTreeUri(this, uri) ?: return
            val s = sender.lowercase(Locale.getDefault())
            val candidates =
                listOf("${s}_icon.png", "${s}_avatar.png", "${s}.png", currentMascotIcon)
            for (name in candidates) {
                val f = dir.findFile(name) ?: continue
                if (f.exists()) {
                    contentResolver.openInputStream(f.uri)?.use { ins ->
                        val bmp = BitmapFactory.decodeStream(ins)
                        target.setImageBitmap(bmp)
                        return
                    }
                }
            }
        } catch (_: Exception) {
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
        return try {
            Color.parseColor(spec ?: "")
        } catch (_: Exception) {
            fallback
        }
    }

    private fun dpToPx(dp: Int): Int {
        val density = resources.displayMetrics.density
        return (dp * density).roundToInt()
    }

    /// SECTION: Template Loading
    private fun loadTemplatesFromFile(filename: String) {
        templatesMap.clear()
        keywordResponses.clear()
        mascotList.clear()
        dialogLines.clear()
        dialogs.clear()

        // Reset to default before parsing, especially contextMap for base.txt
        if (filename == "base.txt") {
            contextMap.clear()
        }

        currentMascotName = "Racky"
        currentMascotIcon = "raccoon_icon.png"
        currentThemeColor = "#00FF00"
        currentThemeBackground = "#000000"

        // ensure synonyms/stopwords are loaded (in case folderUri was set later)
        loadSynonymsAndStopwords()

        if (folderUri == null) {
            loadFallbackTemplates()
            rebuildInvertedIndex()
            updateUI(
                currentMascotName,
                currentMascotIcon,
                currentThemeColor,
                currentThemeBackground
            )
            return
        }

        try {
            val dir = DocumentFile.fromTreeUri(this, folderUri!!) ?: run {
                loadFallbackTemplates()
                rebuildInvertedIndex()
                updateUI(
                    currentMascotName,
                    currentMascotIcon,
                    currentThemeColor,
                    currentThemeBackground
                )
                return
            }

            val file = dir.findFile(filename)
            if (file == null || !file.exists()) {
                loadFallbackTemplates()
                rebuildInvertedIndex()
                updateUI(
                    currentMascotName,
                    currentMascotIcon,
                    currentThemeColor,
                    currentThemeBackground
                )
                return
            }

            // read main file
            contentResolver.openInputStream(file.uri)?.bufferedReader()?.use { reader ->
                reader.forEachLine { raw ->
                    val l = raw.trim()
                    if (l.isEmpty()) return@forEachLine

                    // context line in base: :key=file.txt:
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

                    // keyword responses: -keyword=resp1|resp2
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
                        // normalize trigger key (remove punctuation etc)
                        val triggerRaw = parts[0].trim()
                        val trigger = normalizeText(triggerRaw)
                        // additionally filter stopwords and map synonyms for trigger key when storing
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

            // metadata file
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
                            line.startsWith("mascot_name=") -> currentMascotName =
                                line.substring("mascot_name=".length).trim()
                            line.startsWith("mascot_icon=") -> currentMascotIcon =
                                line.substring("mascot_icon=".length).trim()
                            line.startsWith("theme_color=") -> currentThemeColor =
                                line.substring("theme_color=".length).trim()
                            line.startsWith("theme_background=") -> currentThemeBackground =
                                line.substring("theme_background=".length).trim()
                            line.startsWith("dialog_lines=") -> {
                                val lines =
                                    line.substring("dialog_lines=".length).split("|")
                                for (d in lines) {
                                    val t = d.trim(); if (t.isNotEmpty()) dialogLines.add(t)
                                }
                            }
                        }
                    }
                }
            }

            // randomreply.txt parsing (robust)
            val dialogFile = dir.findFile("randomreply.txt")
            if (dialogFile != null && dialogFile.exists()) {
                try {
                    contentResolver.openInputStream(dialogFile.uri)?.bufferedReader()
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

                                // fallback short dialog line
                                dialogLines.add(l)
                            }
                            currentDialogParser?.takeIf { it.replies.isNotEmpty() }
                                ?.let { dialogs.add(it) }
                        }
                } catch (e: Exception) {
                    showCustomToast("Ошибка чтения randomreply.txt: ${e.message}")
                }
            }

            // choose random mascot for base
            if (filename == "base.txt" && mascotList.isNotEmpty()) {
                val selected = mascotList.random()
                selected["name"]?.let { currentMascotName = it }
                selected["icon"]?.let { currentMascotIcon = it }
                selected["color"]?.let { currentThemeColor = it }
                selected["background"]?.let { currentThemeBackground = it }
            }

            // build inverted index once templatesMap filled
            rebuildInvertedIndex()

            updateUI(
                currentMascotName,
                currentMascotIcon,
                currentThemeColor,
                currentThemeBackground
            )

        } catch (e: Exception) {
            e.printStackTrace()
            showCustomToast("Ошибка чтения файла: ${e.message}")
            loadFallbackTemplates()
            rebuildInvertedIndex()
            updateUI(
                currentMascotName,
                currentMascotIcon,
                currentThemeColor,
                currentThemeBackground
            )
        }
    }

    private fun loadFallbackTemplates() {
        templatesMap.clear()
        contextMap.clear()
        keywordResponses.clear()
        dialogs.clear()
        dialogLines.clear()
        mascotList.clear()
        // store normalized keys in fallback as well (respect stopwords/synonyms)
        val t1 = filterStopwordsAndMapSynonyms(normalizeText("привет")).second
        templatesMap[t1] = mutableListOf("Привет! Чем могу помочь?", "Здравствуй!")
        val t2 = filterStopwordsAndMapSynonyms(normalizeText("как дела")).second
        templatesMap[t2] = mutableListOf("Всё отлично, а у тебя?", "Нормально, как дела?")
        keywordResponses["спасибо"] = mutableListOf("Рад, что помог!", "Всегда пожалуйста!")
    }

    private fun updateAutoComplete() {
        val suggestions = mutableListOf<String>()
        suggestions.addAll(templatesMap.keys) // keys are normalized triggers
        for (s in fallback) {
            val low = s.lowercase(Locale.ROOT)
            if (!suggestions.contains(low)) suggestions.add(low)
        }
        if (adapter == null) {
            adapter = object : ArrayAdapter<String>(this, android.R.layout.simple_dropdown_item_1line, suggestions) {
                override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                    val v = super.getView(position, convertView, parent) as TextView
                    v.setTextColor(Color.WHITE)
                    return v
                }
            }
            queryInput.setAdapter(adapter)
            queryInput.threshold = 1
            // set dropdown background to semi-transparent dark so white text is readable
            queryInput.setDropDownBackgroundDrawable(ColorDrawable(Color.parseColor("#80000000")))
        } else {
            adapter?.clear()
            adapter?.addAll(suggestions)
            adapter?.notifyDataSetChanged()
        }
    }

    /// SECTION: Idle & Dialogs
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
                    dialogHandler.postDelayed(
                        { startRandomDialog() },
                        (random.nextInt(20000) + 5000).toLong()
                    )
                }
            }
        }
        dialogRunnable?.let {
            dialogHandler.postDelayed(
                it,
                (random.nextInt(15000) + 10000).toLong()
            )
        }
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
        val metadataFile = dir.findFile(metadataFilename)
        if (metadataFile != null && metadataFile.exists()) {
            try {
                contentResolver.openInputStream(metadataFile.uri)?.bufferedReader()?.use { reader ->
                    reader.forEachLine { raw ->
                        val line = raw.trim()
                        when {
                            line.startsWith("mascot_name=") -> currentMascotName =
                                line.substring("mascot_name=".length).trim()
                            line.startsWith("mascot_icon=") -> currentMascotIcon =
                                line.substring("mascot_icon=".length).trim()
                            line.startsWith("theme_color=") -> currentThemeColor =
                                line.substring("theme_color=".length).trim()
                            line.startsWith("theme_background=") -> currentThemeBackground =
                                line.substring("theme_background=".length).trim()
                        }
                    }
                    updateUI(
                        currentMascotName,
                        currentMascotIcon,
                        currentThemeColor,
                        currentThemeBackground
                    )
                }
            } catch (e: Exception) {
                showCustomToast("Ошибка загрузки метаданных маскота: ${e.message}")
            }
        }
    }

    private fun updateUI(
        mascotName: String,
        mascotIcon: String,
        themeColor: String,
        themeBackground: String
    ) {
        runOnUi {
            title = "Pawstribe - $mascotName"
            // Перезагружаем изображение маскота из SAF при смене UI
            loadMascotTopImage()
            mascotTopImage?.visibility = View.GONE

            try {
                messagesContainer.setBackgroundColor(Color.parseColor(themeBackground))
            } catch (_: Exception) {
            }
        }
    }

    /// SECTION: Utils
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
            dialogHandler.removeCallbacks(it); dialogHandler.postDelayed(
                it,
                5000
            )
        }
    }

    // helper to ensure code runs on UI thread
    private fun runOnUi(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block()
        else runOnUiThread(block)
    }

    // -----------------------
    // STATUS BAR / BATTERY
    // -----------------------

    private fun registerBatteryReceiver() {
        if (batteryReceiver != null) return
        batteryReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (intent == null) return
                val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
                val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
                val plugged = intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, -1)
                val percent = if (level >= 0 && scale > 0) ((level * 100) / scale) else -1
                if (percent >= 0) {
                    updateBatteryUI(percent, plugged)
                }
            }
        }
        registerReceiver(batteryReceiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        // also fetch immediately by sticky intent
        val sticky = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        sticky?.let {
            val level = it.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
            val scale = it.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
            val plugged = it.getIntExtra(BatteryManager.EXTRA_PLUGGED, -1)
            val percent = if (level >= 0 && scale > 0) ((level * 100) / scale) else -1
            if (percent >= 0) updateBatteryUI(percent, plugged)
        }
    }

    private fun unregisterBatteryReceiver() {
        try {
            batteryReceiver?.let { unregisterReceiver(it) }
        } catch (_: Exception) {
        }
        batteryReceiver = null
    }

    private fun updateBatteryUI(percent: Int, plugged: Int) {
        // UI update + possible bot messages when reaching thresholds
        runOnUi {
            // percent text
            batteryPercentView?.text = "$percent%"

            // color decisions
            val lowThreshold = 25
            val warningThreshold = 15
            val urgentThreshold = 5

            val normalBlue = Color.parseColor("#00BFFF")
            val red = Color.RED

            val textColor = if (percent <= lowThreshold) red else normalBlue
            batteryPercentView?.setTextColor(textColor)

            // choose icon index: map percent -> 1..5 (5 maps to >=80)
            val iconIndex = when {
                percent >= 80 -> 5
                percent >= 60 -> 4
                percent >= 40 -> 3
                percent >= 20 -> 2
                else -> 1
            }

            // load battery_{index}.png from SAF if available
            val loaded = tryLoadBitmapFromFolder("battery_$iconIndex.png")
            if (loaded != null) {
                batteryImageView?.setImageBitmap(loaded)
            } else {
                // fallback attempt: battery_full or battery.png
                tryLoadBitmapFromFolder("battery.png")?.let { batteryImageView?.setImageBitmap(it) }
            }

            // tint icon: blue normally, red when <= lowThreshold
            try {
                batteryImageView?.setColorFilter(if (percent <= lowThreshold) red else normalBlue)
            } catch (_: Exception) {
            }

            // Показываем/скрываем иконку зарядки
            if (plugged > 0) {
                btnCharging?.visibility = View.VISIBLE
            } else {
                btnCharging?.visibility = View.GONE
            }

            // Bot messages on thresholds (only once per crossing)
            // if we moved downward across warningThreshold and lastBatteryWarningStage > warningThreshold -> send warning
            if (percent <= urgentThreshold && lastBatteryWarningStage > urgentThreshold) {
                // urgent message at 5%
                addChatMessage(currentMascotName, "Это не шутки. Поставь на зарядку.")
            } else if (percent <= warningThreshold && lastBatteryWarningStage > warningThreshold) {
                // pick one of three variations
                val variants = listOf(
                    "Пожалуйста, поставь устройство на зарядку — батарейка почти села.",
                    "Аккумулятор низкий, лучше подключить зарядку.",
                    "Осталось немного заряда, поставь телефон на заряд."
                )
                addChatMessage(currentMascotName, variants.random())
            }
            // update lastBatteryWarningStage to current percent
            lastBatteryWarningStage = percent
        }
    }

    // helper to read bitmap from SAF folder by filename
    private fun tryLoadBitmapFromFolder(name: String): Bitmap? {
        val uri = folderUri ?: return null
        return try {
            val dir = DocumentFile.fromTreeUri(this, uri) ?: return null
            val f = dir.findFile(name) ?: return null
            if (!f.exists()) return null
            contentResolver.openInputStream(f.uri)?.use { ins ->
                BitmapFactory.decodeStream(ins)
            }
        } catch (e: Exception) {
            null
        }
    }

    // -----------------------
    // TIME UPDATER
    // -----------------------
    private fun startTimeUpdater() {
        stopTimeUpdater()
        timeUpdaterRunnable = object : Runnable {
            override fun run() {
                val now = Date()
                val fmt = SimpleDateFormat("HH:mm", Locale.getDefault())
                val s = fmt.format(now)
                runOnUi {
                    timeTextView?.text = s
                }
                // schedule next update at next minute boundary
                val delay = 60000L
                timeHandler.postDelayed(this, delay)
            }
        }
        timeHandler.post(timeUpdaterRunnable!!)
    }

    private fun stopTimeUpdater() {
        timeUpdaterRunnable?.let { timeHandler.removeCallbacks(it) }
        timeUpdaterRunnable = null
    }
}
