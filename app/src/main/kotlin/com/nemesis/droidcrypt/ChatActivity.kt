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
import android.provider.Settings
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
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
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.*
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.roundToInt
import android.bluetooth.BluetoothAdapter
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.text.format.DateFormat
import android.net.wifi.WifiManager
import android.widget.TextView
import androidx.core.view.isVisible

class ChatActivity : AppCompatActivity(), TextToSpeech.OnInitListener {

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

    // UI
    private var folderUri: Uri? = null
    private lateinit var recyclerView: RecyclerView
    private lateinit var queryInput: AutoCompleteTextView
    private var envelopeInputButton: ImageButton? = null
    private var mascotTopImage: ImageView? = null
    private var btnLock: ImageButton? = null
    private var btnTrash: ImageButton? = null
    private var btnEnvelopeTop: ImageButton? = null
    private var btnSettings: ImageButton? = null
    private var btnCharging: ImageButton? = null
    private var messagesAdapter: MessagesAdapter? = null
    private val messagesList = mutableListOf<MessageData>()
    private var adapter: ArrayAdapter<String>? = null

    // Added UI elements (status bar overlay)
    private var batteryImageView: ImageView? = null
    private var batteryPercentView: TextView? = null
    private var wifiImageView: ImageView? = null
    private var bluetoothImageView: ImageView? = null
    private var timeTextView: TextView? = null
    private var infoIconButton: ImageButton? = null // оставляем поле, но не добавляем в тулбар

     // TTS
    private var tts: TextToSpeech? = null
    
    // Data structures
    private val fallback = arrayOf("Привет", "Как дела?", "Расскажи о себе", "Выход")
    private val templatesMap = HashMap<String, MutableList<String>>()
    private val contextMap = HashMap<String, String>()
    private val keywordResponses = HashMap<String, MutableList<String>>()
    private val antiSpamResponses = mutableListOf<String>()
    private val mascotList = mutableListOf<Map<String, String>>()
    private val invertedIndex = HashMap<String, MutableList<String>>()
    private val synonymsMap = HashMap<String, String>()
    private val stopwords = HashSet<String>()
    private var currentMascotName = "Racky"
    private var currentMascotIcon = "raccoon_icon.png"
    private var currentThemeColor = "#00FF00"
    private var currentThemeBackground = "#000000"
    private var currentContext = "base.txt"
    private var lastQuery = ""
    private var userActivityCount = 0

    // Dialogs / idle
    private val dialogHandler = Handler(Looper.getMainLooper())
    private var idleCheckRunnable: Runnable? = null
    private var lastUserInputTime = System.currentTimeMillis()
    private val random = Random()
    private val queryCountMap = HashMap<String, Int>()
    private var lastSendTime = 0L
    private var lastBatteryWarningStage = Int.MAX_VALUE
    private var batteryReceiver: BroadcastReceiver? = null
    private var networkReceiver: BroadcastReceiver? = null
    private var bluetoothReceiver: BroadcastReceiver? = null
    private val timeHandler = Handler(Looper.getMainLooper())
    private var timeUpdaterRunnable: Runnable? = null
    private var isTypingIndicatorShown = false // Флаг для предотвращения дублирования индикатора

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

    data class MessageData(val sender: String, val text: String)

    inner class MessagesAdapter(private val messages: MutableList<MessageData>) :
        RecyclerView.Adapter<MessagesAdapter.MessageViewHolder>() {

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): MessageViewHolder {
            val view = LayoutInflater.from(parent.context).inflate(R.layout.item_message, parent, false)
            return MessageViewHolder(view)
        }

        override fun onBindViewHolder(holder: MessageViewHolder, position: Int) {
            holder.bind(messages[position])
        }

        override fun getItemCount(): Int = messages.size

        inner class MessageViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
            private val senderText: TextView = itemView.findViewById(R.id.senderText)
            private val messageText: TextView = itemView.findViewById(R.id.messageText)
            private val avatar: ImageView = itemView.findViewById(R.id.avatar)

            fun bind(data: MessageData) {
                val isUser = data.sender.equals("You", ignoreCase = true)
                senderText.text = "${data.sender}:"
                senderText.setTextColor(Color.parseColor("#AAAAAA"))
                messageText.text = data.text
                messageText.textSize = 16f
                messageText.setTextIsSelectable(true)
                messageText.setPadding(dpToPx(10), dpToPx(10), dpToPx(10), dpToPx(10))
                val accent = if (isUser) {
                    Color.parseColor("#00FF00")
                } else {
                    safeParseColorOrDefault(currentThemeColor, Color.parseColor("#00FF00"))
                }
                messageText.background = createBubbleDrawable(accent)
                try {
                    messageText.setTextColor(if (isUser) Color.WHITE else Color.parseColor(currentThemeColor))
                } catch (_: Exception) {
                    messageText.setTextColor(Color.WHITE)
                }
                messageText.setOnClickListener { speakText(data.text) }

                val layoutParams = itemView.layoutParams as RecyclerView.LayoutParams
                if (isUser) {
                    layoutParams.gravity = Gravity.END
                    layoutParams.marginStart = dpToPx(48)
                    layoutParams.marginEnd = 0
                    avatar.visibility = View.GONE
                } else {
                    layoutParams.gravity = Gravity.START
                    layoutParams.marginStart = 0
                    layoutParams.marginEnd = dpToPx(48)
                    avatar.visibility = View.VISIBLE
                    loadAvatarInto(avatar, data.sender)
                    avatar.setOnClickListener {
                        it.isEnabled = false
                        val scaleX = ObjectAnimator.ofFloat(it, "scaleX", 1f, 1.08f, 1f)
                        val scaleY = ObjectAnimator.ofFloat(it, "scaleY", 1f, 1.08f, 1f)
                        scaleX.duration = 250
                        scaleY.duration = 250
                        scaleX.start()
                        scaleY.start()
                        Handler(Looper.getMainLooper()).postDelayed({
                            loadAndSendOuchMessage(data.sender)
                            it.isEnabled = true
                        }, 260)
                    }
                }
                itemView.layoutParams = layoutParams
                itemView.requestLayout()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_chat)
        window.addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN)
        supportActionBar?.setBackgroundDrawable(ColorDrawable(Color.argb(128, 0, 0, 0)))
        window.setBackgroundDrawable(ColorDrawable(Color.BLACK))

        recyclerView = findViewById(R.id.chatMessagesContainer)
        queryInput = findViewById(R.id.queryInput)
        envelopeInputButton = findViewById(R.id.envelope_button)
        btnLock = findViewById(R.id.btn_lock)
        btnTrash = findViewById(R.id.btn_trash)
        btnEnvelopeTop = findViewById(R.id.btn_envelope_top)
        btnSettings = findViewById(R.id.btn_settings)
        btnCharging = findViewById(R.id.btn_charging)
        batteryImageView = findViewById(R.id.battery_image)
        batteryPercentView = findViewById(R.id.battery_percent)
        wifiImageView = findViewById(R.id.wifi_image)
        bluetoothImageView = findViewById(R.id.bluetooth_image)
        timeTextView = findViewById(R.id.time_text)
        infoIconButton = findViewById(R.id.info_icon_button)

        // Setup RecyclerView
        recyclerView.layoutManager = LinearLayoutManager(this)
        messagesAdapter = MessagesAdapter(messagesList)
        recyclerView.adapter = messagesAdapter

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
        } catch (_: Exception) {}

        try {
            val prefs = PreferenceManager.getDefaultSharedPreferences(this)
            val disable = prefs.getBoolean(PREF_KEY_DISABLE_SCREENSHOTS, false)
            if (disable) window.addFlags(WindowManager.LayoutParams.FLAG_SECURE) else window.clearFlags(
                WindowManager.LayoutParams.FLAG_SECURE
            )
        } catch (_: Exception) {}

        setupIconTouchEffect(btnLock)
        setupIconTouchEffect(btnTrash)
        setupIconTouchEffect(btnEnvelopeTop)
        setupIconTouchEffect(btnSettings)
        setupIconTouchEffect(envelopeInputButton)
        setupIconTouchEffect(btnCharging)

        btnLock?.setOnClickListener { finish() }
        btnTrash?.setOnClickListener { clearChat() }
        btnSettings?.setOnClickListener { startActivity(Intent(this, SettingsActivity::class.java)) }
        btnEnvelopeTop?.setOnClickListener { startActivity(Intent(this, PostsActivity::class.java)) }
        btnCharging?.setOnClickListener { startActivity(Intent(this, PostsActivity::class.java)) }

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

        // Инициализация TTS
        tts = TextToSpeech(this, this)
        
        // Async init data
        lifecycleScope.launch {
            loadSynonymsAndStopwords()
            if (folderUri == null) {
                showCustomToast("Папка не выбрана! Открой настройки и выбери папку.")
                loadFallbackTemplates()
            } else {
                loadTemplatesFromFile(currentContext)
            }
            rebuildInvertedIndex()
            withContext(Dispatchers.Main) {
                updateAutoComplete()
                addChatMessage(currentMascotName, "Добро пожаловать!")
            }
        }

        queryInput.setOnItemClickListener { parent, _, position, _ ->
            val selected = parent.getItemAtPosition(position) as String
            queryInput.setText(selected)
            processUserQuery(selected)
        }

        idleCheckRunnable = object : Runnable {
            override fun run() {
                dialogHandler.postDelayed(this, 5000)
            }
        }
        idleCheckRunnable?.let { dialogHandler.postDelayed(it, 5000) }

        loadMascotTopImage()
        mascotTopImage?.visibility = View.GONE
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            tts?.language = Locale("ru", "RU")
            tts?.setPitch(1.0f)
            tts?.setSpeechRate(1.0f)
        }
    }
    
    override fun onResume() {
        super.onResume()
        lifecycleScope.launch {
            folderUri?.let { loadTemplatesFromFile(currentContext) }
            rebuildInvertedIndex()
            withContext(Dispatchers.Main) {
                updateAutoComplete()
                loadToolbarIconsAsync()
            }
        }
        idleCheckRunnable?.let {
            dialogHandler.removeCallbacks(it)
            dialogHandler.postDelayed(it, 5000)
        }
        registerBatteryReceiver()
        registerNetworkReceiver()
        registerBluetoothReceiver()
        startTimeUpdater()
    }

    override fun onPause() {
        super.onPause()
        dialogHandler.removeCallbacksAndMessages(null)
        unregisterBatteryReceiver()
        unregisterNetworkReceiver()
        unregisterBluetoothReceiver()
        stopTimeUpdater()
    }

    override fun onDestroy() {
        super.onDestroy()
        dialogHandler.removeCallbacksAndMessages(null)
        unregisterBatteryReceiver()
        unregisterNetworkReceiver()
        unregisterBluetoothReceiver()
        stopTimeUpdater()
        tts?.shutdown()
        tts = null
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

    private fun loadToolbarIconsAsync() {
        lifecycleScope.launch(Dispatchers.IO) {
            val uri = folderUri ?: return@launch
            try {
                val dir = DocumentFile.fromTreeUri(this@ChatActivity, uri) ?: return@launch

                fun loadBitmapAsync(name: String, target: ImageView?, buttonTarget: ImageButton? = null) {
                    try {
                        val file = dir.findFile(name)
                        if (file != null && file.exists()) {
                            contentResolver.openInputStream(file.uri)?.use { ins ->
                                val bmp = BitmapFactory.decodeStream(ins)
                                withContext(Dispatchers.Main) {
                                    bmp?.let {
                                        if (target != null) target.setImageBitmap(it)
                                        if (buttonTarget != null) buttonTarget.setImageBitmap(it)
                                    }
                                }
                            }
                        }
                    } catch (_: Exception) {}
                }

                loadBitmapAsync("lock.png", null, btnLock)
                loadBitmapAsync("trash.png", null, btnTrash)
                loadBitmapAsync("envelope.png", null, btnEnvelopeTop)
                loadBitmapAsync("settings.png", null, btnSettings)
                loadBitmapAsync("charging.png", null, btnCharging)
                loadBitmapAsync("send.png", null, envelopeInputButton)
                loadBitmapAsync("battery_5.png", batteryImageView)
                loadBitmapAsync("wifi.png", wifiImageView)
                loadBitmapAsync("bluetooth.png", bluetoothImageView)
                loadBitmapAsync("mobile.png", null) // preload if needed
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    private fun loadMascotTopImage() {
        val uri = folderUri ?: return
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val dir = DocumentFile.fromTreeUri(this@ChatActivity, uri) ?: return@launch
                val candidates = listOf("${currentMascotName.lowercase(Locale.getDefault())}.png", "mascot_top.png", currentMascotIcon)
                for (name in candidates) {
                    val file = dir.findFile(name)
                    if (file != null && file.exists()) {
                        contentResolver.openInputStream(file.uri)?.use { ins ->
                            val bmp = BitmapFactory.decodeStream(ins)
                            withContext(Dispatchers.Main) {
                                if (mascotTopImage == null) {
                                    mascotTopImage = ImageView(this@ChatActivity).apply {
                                        val size = dpToPx(120)
                                        layoutParams = LinearLayout.LayoutParams(size, size).apply {
                                            gravity = Gravity.CENTER_HORIZONTAL
                                            topMargin = dpToPx(8)
                                        }
                                        scaleType = ImageView.ScaleType.CENTER_CROP
                                        adjustViewBounds = true
                                    }
                                    val root = findViewById<ViewGroup>(android.R.id.content)
                                    root.addView(mascotTopImage, 0)
                                }
                                mascotTopImage?.setImageBitmap(bmp)
                            }
                            return@launch
                        }
                    }
                }
            } catch (_: Exception) {}
        }
    }

    private fun processUserQuery(userInput: String) {
        if (userInput.startsWith("/")) {
            handleCommand(userInput.trim())
            return
        }

        val qOrigRaw = userInput.trim()
        val qOrig = normalizeText(qOrigRaw)
        val (qTokensFiltered, qFiltered) = filterStopwordsAndMapSynonyms(qOrig)
        val qKeyForCount = qFiltered

        if (qFiltered.isEmpty()) return

        lastUserInputTime = System.currentTimeMillis()
        userActivityCount++

        val calendar = Calendar.getInstance()
        val hour = calendar.get(Calendar.HOUR_OF_DAY)
        val minute = calendar.get(Calendar.MINUTE)
        // расширенная логика: если поздно (23:00-06:00), предложить лечь спать при втором вводе
        if ((hour >= 23 || hour < 6) && userActivityCount == 2) {
            val sleepResponse = loadTimeToSleepResponse()
            if (sleepResponse != null) {
                addChatMessage("You", userInput)
                showTypingIndicator()
                addChatMessage(currentMascotName, sleepResponse)
                startIdleTimer()
                return
            }
        }

        val dateKeywords = listOf("какое сегодня число", "какой сегодня день", "какой сейчас день", "какой сейчас год")
        val lowerInput = qOrig.lowercase(Locale.getDefault())
        var dateResponse: String? = null
        if (dateKeywords.any { lowerInput.contains(it) }) {
            dateResponse = processDateTimeQuery(lowerInput)
        }

        if (dateResponse != null) {
            addChatMessage("You", userInput)
            showTypingIndicator()
            addChatMessage(currentMascotName, dateResponse)
            startIdleTimer()
            return
        }

        addChatMessage("You", userInput)
        showTypingIndicator()

        if (qKeyForCount == lastQuery) {
            val cnt = queryCountMap.getOrDefault(qKeyForCount, 0)
            queryCountMap[qKeyForCount] = cnt + 1
        } else {
            queryCountMap.clear()
            queryCountMap[qKeyForCount] = 1
            lastQuery = qKeyForCount
        }

        val repeats = queryCountMap.getOrDefault(qKeyForCount, 0)
        if (repeats >= 5) {
            val spamResp = antiSpamResponses.random()
            addChatMessage(currentMascotName, spamResp)
            startIdleTimer()
            return
        }

        // Snapshot on Main, then process in background
        val templatesSnapshot = HashMap(templatesMap)
        val invertedIndexSnapshot = HashMap<String, MutableList<String>>()
        for ((k, v) in invertedIndex) invertedIndexSnapshot[k] = ArrayList(v)
        val synonymsSnapshot = HashMap(synonymsMap)
        val stopwordsSnapshot = HashSet(stopwords)
        val keywordResponsesSnapshot = HashMap<String, MutableList<String>>()
        for ((k, v) in keywordResponses) keywordResponsesSnapshot[k] = ArrayList(v)
        val contextMapSnapshot = HashMap(contextMap)

        lifecycleScope.launch(Dispatchers.Default) {
            data class ResponseResult(val text: String? = null, val wantsContextSwitch: String? = null)

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

            // helper: parses a templates file into maps (без изменения UI) — используется для тематического поиска
            suspend fun parseTemplatesFromFile(filename: String): Pair<HashMap<String, MutableList<String>>, HashMap<String, MutableList<String>>> {
                val localTemplates = HashMap<String, MutableList<String>>()
                val localKeywords = HashMap<String, MutableList<String>>()
                val uriLocal = folderUri ?: return Pair(localTemplates, localKeywords)
                try {
                    val dir = DocumentFile.fromTreeUri(this@ChatActivity, uriLocal) ?: return Pair(localTemplates, localKeywords)
                    val file = dir.findFile(filename) ?: return Pair(localTemplates, localKeywords)
                    if (!file.exists()) return Pair(localTemplates, localKeywords)
                    contentResolver.openInputStream(file.uri)?.bufferedReader()?.use { reader ->
                        reader.forEachLine { raw ->
                            val l = raw.trim()
                            if (l.isEmpty()) return@forEachLine
                            if (l.startsWith("-")) {
                                val keywordLine = l.substring(1)
                                if (keywordLine.contains("=")) {
                                    val parts = keywordLine.split("=", limit = 2)
                                    if (parts.size == 2) {
                                        val keyword = parts[0].trim().lowercase(Locale.ROOT)
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
                                // map synonyms/stopwords to produce filtered trigger key
                                val triggerFiltered = filterStopwordsAndMapSynonymsLocal(trigger).second
                                val responses = parts[1].split("|")
                                val responseList = responses.mapNotNull { it.trim().takeIf { s -> s.isNotEmpty() } }.toMutableList()
                                if (triggerFiltered.isNotEmpty() && responseList.isNotEmpty()) localTemplates[triggerFiltered] = responseList
                            }
                        }
                    }
                } catch (_: Exception) {}
                return Pair(localTemplates, localKeywords)
            }

            var answered = false
            val subqueryResponses = mutableListOf<String>()
            val processedSubqueries = mutableSetOf<String>()

            templatesSnapshot[qFiltered]?.let { possible ->
                if (possible.isNotEmpty()) {
                    subqueryResponses.add(possible.random())
                    answered = true
                    processedSubqueries.add(qFiltered)
                }
            }

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
                withContext(Dispatchers.Main) {
                    addChatMessage(currentMascotName, combined)
                    startIdleTimer()
                }
                return@launch
            }

            for ((keyword, responses) in keywordResponsesSnapshot) {
                if (qFiltered.contains(keyword) && responses.isNotEmpty()) {
                    withContext(Dispatchers.Main) {
                        addChatMessage(currentMascotName, responses.random())
                        startIdleTimer()
                    }
                    return@launch
                }
            }

            // основной candidate поиск (используем snapshot)
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

            // Jaccard на snapshot
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
                    withContext(Dispatchers.Main) {
                        addChatMessage(currentMascotName, possible.random())
                        startIdleTimer()
                    }
                    return@launch
                }
            }

            // Fuzzy / Levenshtein on snapshot
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
                    withContext(Dispatchers.Main) {
                        addChatMessage(currentMascotName, possible.random())
                        startIdleTimer()
                    }
                    return@launch
                }
            }

            // Попытка контекстного перехода: если найден ключ в base => переход в тематический файл.
            val lower = normalizeLocal(qFiltered)
            for ((keyword, value) in contextMapSnapshot) {
                if (lower.contains(keyword)) {
                    withContext(Dispatchers.Main) {
                        // если надо — переключаем глобальный контекст (UI thread)
                        if (value != currentContext) {
                            currentContext = value
                            lifecycleScope.launch {
                                loadTemplatesFromFile(currentContext)
                                rebuildInvertedIndex()
                                withContext(Dispatchers.Main) { updateAutoComplete() }
                            }
                        }
                    }
                    // после переключения — в background проверим тематический файл на совпадения
                    val (localTemplates, localKeywords) = parseTemplatesFromFile(value)
                    // сначала точное совпадение в тематическом файле
                    localTemplates[ qFiltered ]?.let { possible ->
                        if (possible.isNotEmpty()) {
                            withContext(Dispatchers.Main) {
                                addChatMessage(currentMascotName, possible.random())
                                startIdleTimer()
                            }
                            return@launch
                        }
                    }
                    // построим локальный inverted index для тематического файла
                    val localInverted = HashMap<String, MutableList<String>>()
                    for ((k, v) in localTemplates) {
                        val toks = filterStopwordsAndMapSynonymsLocal(k).first
                        for (t in toks) {
                            val list = localInverted.getOrPut(t) { mutableListOf() }
                            if (!list.contains(k)) list.add(k)
                        }
                    }
                    // кандидаты из локального inverted
                    val localCandidateCounts = HashMap<String, Int>()
                    val tokens = qTokens
                    for (tok in tokens) {
                        localInverted[tok]?.forEach { trig ->
                            localCandidateCounts[trig] = localCandidateCounts.getOrDefault(trig, 0) + 1
                        }
                    }
                    val localCandidates: List<String> = if (localCandidateCounts.isNotEmpty()) {
                        localCandidateCounts.entries
                            .filter { it.value >= CANDIDATE_TOKEN_THRESHOLD }
                            .sortedByDescending { it.value }
                            .map { it.key }
                            .take(MAX_CANDIDATES_FOR_LEV)
                    } else {
                        val md = getFuzzyDistance(qFiltered)
                        localTemplates.keys.filter { abs(it.length - qFiltered.length) <= md }
                            .take(MAX_CANDIDATES_FOR_LEV)
                    }
                    // Jaccard local
                    var bestLocal: String? = null
                    var bestLocalJ = 0.0
                    val qSetLocal = tokens.toSet()
                    for (key in localCandidates) {
                        val keyTokens = filterStopwordsAndMapSynonymsLocal(key).first.toSet()
                        if (keyTokens.isEmpty()) continue
                        val inter = qSetLocal.intersect(keyTokens).size.toDouble()
                        val union = qSetLocal.union(keyTokens).size.toDouble()
                        val j = if (union == 0.0) 0.0 else inter / union
                        if (j > bestLocalJ) {
                            bestLocalJ = j
                            bestLocal = key
                        }
                    }
                    if (bestLocal != null && bestLocalJ >= JACCARD_THRESHOLD) {
                        val possible = localTemplates[bestLocal]
                        if (!possible.isNullOrEmpty()) {
                            withContext(Dispatchers.Main) {
                                addChatMessage(currentMascotName, possible.random())
                                startIdleTimer()
                            }
                            return@launch
                        }
                    }
                    // Levenshtein local
                    var bestLocalKey: String? = null
                    var bestLocalDist = Int.MAX_VALUE
                    for (key in localCandidates) {
                        val maxD = getFuzzyDistance(qFiltered)
                        if (abs(key.length - qFiltered.length) > maxD + 1) continue
                        val d = levenshtein(qFiltered, key, qFiltered)
                        if (d < bestLocalDist) {
                            bestLocalDist = d
                            bestLocalKey = key
                        }
                        if (bestLocalDist == 0) break
                    }
                    if (bestLocalKey != null && bestLocalDist <= getFuzzyDistance(qFiltered)) {
                        val possible = localTemplates[bestLocalKey]
                        if (!possible.isNullOrEmpty()) {
                            withContext(Dispatchers.Main) {
                                addChatMessage(currentMascotName, possible.random())
                                startIdleTimer()
                            }
                            return@launch
                        }
                    }
                    // если ничего не нашлось — вернём дефолтный ответ
                    withContext(Dispatchers.Main) {
                        addChatMessage(currentMascotName, getDummyResponse(qOrig))
                        startIdleTimer()
                    }
                    return@launch
                }
            }

            withContext(Dispatchers.Main) {
                addChatMessage(currentMascotName, getDummyResponse(qOrig))
                startIdleTimer()
            }
        }
    }

    private fun handleCommand(cmdRaw: String) {
        val cmd = cmdRaw.trim().lowercase(Locale.getDefault())
        when {
            cmd == "/reload" -> {
                addChatMessage(currentMascotName, "Перезагружаю шаблоны...")
                lifecycleScope.launch {
                    loadTemplatesFromFile(currentContext)
                    rebuildInvertedIndex()
                    withContext(Dispatchers.Main) {
                        updateAutoComplete()
                        addChatMessage(currentMascotName, "Шаблоны перезагружены.")
                    }
                }
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

    private fun showTypingIndicator() {
        runOnUi {
            if (isTypingIndicatorShown) return@runOnUi
            isTypingIndicatorShown = true
            val typingPosition = messagesList.size
            messagesList.add(MessageData("", "печатает..."))
            messagesAdapter?.notifyItemInserted(typingPosition)
            recyclerView.smoothScrollToPosition(typingPosition)
            Handler(Looper.getMainLooper()).postDelayed({
                runOnUi {
                    if (isTypingIndicatorShown && messagesList.lastOrNull()?.text == "печатает...") {
                        messagesList.removeLast()
                        messagesAdapter?.notifyItemRemoved(messagesList.size)
                        isTypingIndicatorShown = false
                    }
                }
            }, SUBQUERY_RESPONSE_DELAY)
        }
    }

    private fun clearChat() {
        runOnUi {
            messagesList.clear()
            messagesAdapter?.notifyDataSetChanged()
            queryCountMap.clear()
            lastQuery = ""
            currentContext = "base.txt"
            lifecycleScope.launch {
                loadTemplatesFromFile(currentContext)
                rebuildInvertedIndex()
                withContext(Dispatchers.Main) { updateAutoComplete() }
                addChatMessage(currentMascotName, "Чат очищен. Возвращаюсь к началу.")
            }
        }
    }

    private fun detectContext(input: String): String? {
        val lower = normalizeText(input)
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

    private suspend fun loadSynonymsAndStopwords() {
        synonymsMap.clear()
        stopwords.clear()
        val uri = folderUri ?: return
        withContext(Dispatchers.IO) {
            try {
                val dir = DocumentFile.fromTreeUri(this@ChatActivity, uri) ?: return@withContext
                val synFile = dir.findFile("synonims.txt")
                if (synFile != null && synFile.exists()) {
                    contentResolver.openInputStream(synFile.uri)?.bufferedReader()?.use { reader ->
                        reader.forEachLine { raw ->
                            var l = raw.trim()
                            if (l.isEmpty()) return@forEachLine
                            if (l.startsWith("*") && l.endsWith("*") && l.length > 1) {
                                l = l.substring(1, l.length - 1)
                            }
                            val parts = l.split(";").map { normalizeText(it).trim() }.filter { it.isNotEmpty() }
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
                    contentResolver.openInputStream(stopFile.uri)?.bufferedReader()?.use { reader ->
                        val all = reader.readText()
                        if (all.isNotEmpty()) {
                            val parts = all.split("^").map { normalizeText(it).trim() }.filter { it.isNotEmpty() }
                            for (p in parts) stopwords.add(p)
                        }
                    }
                }
            } catch (e: Exception) {}
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

    private suspend fun rebuildInvertedIndex() {
        withContext(Dispatchers.Default) {
            invertedIndex.clear()
            for (key in templatesMap.keys) {
                val toks = filterStopwordsAndMapSynonyms(key).first
                for (t in toks) {
                    val list = invertedIndex.getOrPut(t) { mutableListOf() }
                    if (!list.contains(key)) list.add(key)
                }
            }
        }
    }

    private fun levenshtein(s: String, t: String, qFiltered: String): Int {
        if (s == t) return 0
        val n = s.length
        val m = t.length
        if (n == 0) return m
        if (m == 0) return n
        val maxDist = getFuzzyDistance(qFiltered)
        if (abs(n - m) > maxDist + 2) return Int.MAX_VALUE / 2
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

    private fun addChatMessage(sender: String, text: String) {
        runOnUi {
            val position = messagesList.size
            messagesList.add(MessageData(sender, text))
            if (messagesList.size > MAX_MESSAGES) {
                messagesList.removeAt(0)
                messagesAdapter?.notifyItemRemoved(0)
                messagesAdapter?.notifyItemRangeChanged(0, messagesList.size)
            }
            messagesAdapter?.notifyItemInserted(position)
            recyclerView.postDelayed({ recyclerView.smoothScrollToPosition(position) }, 50)
            if (!sender.equals("You", ignoreCase = true)) {
                playNotificationSoundAsync()
            }
        }
    }

    private fun speakText(text: String) {
        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "message_${System.currentTimeMillis()}")
  }

    private fun loadAndSendOuchMessage(mascot: String) {
        val uri = folderUri ?: return
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val dir = DocumentFile.fromTreeUri(this@ChatActivity, uri) ?: return@launch
                val mascotOuch = dir.findFile("${mascot.lowercase(Locale.getDefault())}.txt") ?: dir.findFile("ouch.txt")
                if (mascotOuch != null && mascotOuch.exists()) {
                    contentResolver.openInputStream(mascotOuch.uri)?.bufferedReader()?.use { reader ->
                        val allText = reader.readText()
                        val responses = allText.split("|").map { it.trim() }.filter { it.isNotEmpty() }
                        if (responses.isNotEmpty()) {
                            val randomResponse = responses.random()
                            withContext(Dispatchers.Main) {
                                addChatMessage(mascot, randomResponse)
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    showCustomToast("Ошибка загрузки ouch.txt: ${e.message}")
                }
            }
        }
    }

    private fun playNotificationSoundAsync() {
        val uri = folderUri ?: return
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val dir = DocumentFile.fromTreeUri(this@ChatActivity, uri) ?: return@launch
                val soundFile = dir.findFile("notify.ogg") ?: return@launch
                if (!soundFile.exists()) return@launch
                val afd = contentResolver.openAssetFileDescriptor(soundFile.uri, "r") ?: return@launch
                val player = MediaPlayer()
                try {
                    player.setDataSource(afd.fileDescriptor, afd.startOffset, afd.length)
                    afd.close()
                    withContext(Dispatchers.Main) {
                        player.prepareAsync()
                        player.setOnPreparedListener { it.start() }
                        player.setOnCompletionListener { it.reset(); it.release() }
                        player.setOnErrorListener { mp, _, _ -> try { mp.reset(); mp.release() } catch (_: Exception) {}; true }
                    }
                } catch (e: Exception) {
                    try { afd.close() } catch (_: Exception) {}
                    try { player.reset(); player.release() } catch (_: Exception) {}
                }
            } catch (_: Exception) {}
        }
    }

    private fun createMessageBubbleDrawable(accentColor: Int): GradientDrawable {
        return GradientDrawable().apply {
            val bg = blendColors(Color.parseColor("#0A0A0A"), accentColor, 0.06f)
            setColor(bg)
            cornerRadius = dpToPx(10).toFloat()
            setStroke(dpToPx(2), ColorUtils.setAlphaComponent(accentColor, 180))
        }
    }

    private fun loadAvatarInto(target: ImageView, sender: String) {
        val uri = folderUri ?: run { target.setImageResource(android.R.color.transparent); return }
        lifecycleScope.launch(Dispatchers.IO) {
            var bitmap: Bitmap? = null
            try {
                val dir = DocumentFile.fromTreeUri(this@ChatActivity, uri) ?: return@launch
                val s = sender.lowercase(Locale.getDefault())
                val candidates = listOf("${s}_icon.png", "${s}_avatar.png", "${s}.png", currentMascotIcon)
                for (name in candidates) {
                    val f = dir.findFile(name) ?: continue
                    if (f.exists()) {
                        contentResolver.openInputStream(f.uri)?.use { ins ->
                            bitmap = BitmapFactory.decodeStream(ins)
                            withContext(Dispatchers.Main) {
                                target.setImageBitmap(bitmap)
                            }
                            return@launch
                        }
                    }
                }
                withContext(Dispatchers.Main) {
                    target.setImageResource(android.R.color.transparent)
                }
            } catch (_: Exception) {
                withContext(Dispatchers.Main) {
                    target.setImageResource(android.R.color.transparent)
                }
            } finally {
                bitmap?.recycle()
            }
        }
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

    private suspend fun loadTemplatesFromFile(filename: String) {
        withContext(Dispatchers.IO) {
            templatesMap.clear()
            keywordResponses.clear()
            mascotList.clear()
            if (filename == "base.txt") {
                contextMap.clear()
            }
            currentMascotName = "Racky"
            currentMascotIcon = "raccoon_icon.png"
            currentThemeColor = "#00FF00"
            currentThemeBackground = "#000000"
            loadSynonymsAndStopwords() // already suspend
            if (folderUri == null) {
                loadFallbackTemplates()
                return@withContext
            }
            try {
                val dir = DocumentFile.fromTreeUri(this@ChatActivity, folderUri!!) ?: run {
                    loadFallbackTemplates()
                    return@withContext
                }
                val file = dir.findFile(filename)
                if (file == null || !file.exists()) {
                    loadFallbackTemplates()
                    return@withContext
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
                                    val keyword = parts[0].trim().lowercase(Locale.ROOT)
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
                                    val keyword = parts[0].trim().lowercase(Locale.ROOT)
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
                            val triggerFiltered = filterStopwordsAndMapSynonyms(trigger).second
                            val responses = parts[1].split("|")
                            val responseList = responses.mapNotNull { it.trim().takeIf { s -> s.isNotEmpty() } }.toMutableList()
                            if (triggerFiltered.isNotEmpty() && responseList.isNotEmpty()) templatesMap[triggerFiltered] = responseList
                        }
                    }
                }
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
                            }
                        }
                    }
                }
                if (filename == "base.txt" && mascotList.isNotEmpty()) {
                    val selected = mascotList.random()
                    selected["name"]?.let { currentMascotName = it }
                    selected["icon"]?.let { currentMascotIcon = it }
                    selected["color"]?.let { currentThemeColor = it }
                    selected["background"]?.let { currentThemeBackground = it }
                }
            } catch (e: Exception) {
                e.printStackTrace()
                withContext(Dispatchers.Main) {
                    showCustomToast("Ошибка чтения файла: ${e.message}")
                }
                loadFallbackTemplates()
            }
        }
        withContext(Dispatchers.Default) { rebuildInvertedIndex() }
        withContext(Dispatchers.Main) { updateUI(currentMascotName, currentMascotIcon, currentThemeColor, currentThemeBackground) }
    }

    private fun loadFallbackTemplates() {
        templatesMap.clear()
        contextMap.clear()
        keywordResponses.clear()
        mascotList.clear()
        val t1 = filterStopwordsAndMapSynonyms(normalizeText("привет")).second
        templatesMap[t1] = mutableListOf("Привет! Чем могу помочь?", "Здравствуй!")
        val t2 = filterStopwordsAndMapSynonyms(normalizeText("как дела")).second
        templatesMap[t2] = mutableListOf("Всё отлично, а у тебя?", "Нормально, как дела?")
        keywordResponses["спасибо"] = mutableListOf("Рад, что помог!", "Всегда пожалуйста!")
    }

    private fun updateAutoComplete() {
        val suggestions = mutableListOf<String>()
        suggestions.addAll(templatesMap.keys)
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
            queryInput.setDropDownBackgroundDrawable(ColorDrawable(Color.parseColor("#80000000")))
        } else {
            adapter?.clear()
            adapter?.addAll(suggestions)
            adapter?.notifyDataSetChanged()
        }
    }

    private fun loadMascotMetadata(mascotName: String) {
        if (folderUri == null) return
        val metadataFilename = "${mascotName.lowercase(Locale.getDefault())}_metadata.txt"
        lifecycleScope.launch(Dispatchers.IO) {
            val dir = DocumentFile.fromTreeUri(this@ChatActivity, folderUri!!) ?: return@launch
            val metadataFile = dir.findFile(metadataFilename)
            if (metadataFile != null && metadataFile.exists()) {
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
                        withContext(Dispatchers.Main) {
                            updateUI(currentMascotName, currentMascotIcon, currentThemeColor, currentThemeBackground)
                        }
                    }
                } catch (e: Exception) {
                    withContext(Dispatchers.Main) {
                        showCustomToast("Ошибка загрузки метаданных маскота: ${e.message}")
                    }
                }
            }
        }
    }

    private fun updateUI(mascotName: String, mascotIcon: String, themeColor: String, themeBackground: String) {
        runOnUi {
            title = "Pawstribe - $mascotName"
            loadMascotTopImage()
            mascotTopImage?.visibility = View.GONE
            try {
                recyclerView.setBackgroundColor(Color.parseColor(themeBackground))
            } catch (_: Exception) {}
        }
    }

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

    private fun runOnUi(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block()
        else runOnUiThread(block)
    }

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
        } catch (_: Exception) {}
        batteryReceiver = null
    }

    private fun updateBatteryUI(percent: Int, plugged: Int) {
        runOnUi {
            batteryPercentView?.text = "$percent%"
            val lowThreshold = 25
            val warningThreshold = 15
            val urgentThreshold = 5
            val normalBlue = Color.parseColor("#00BFFF")
            val red = Color.RED
            val textColor = if (percent <= lowThreshold) red else normalBlue
            batteryPercentView?.setTextColor(textColor)
            val iconIndex = when {
                percent >= 80 -> 5
                percent >= 60 -> 4
                percent >= 40 -> 3
                percent >= 20 -> 2
                else -> 1
            }
            lifecycleScope.launch(Dispatchers.IO) {
                var bitmap: Bitmap? = tryLoadBitmapFromFolder("battery_$iconIndex.png")
                if (bitmap == null) {
                    bitmap = tryLoadBitmapFromFolder("battery.png")
                }
                withContext(Dispatchers.Main) {
                    bitmap?.let { batteryImageView?.setImageBitmap(it) }
                }
                bitmap?.recycle()
            }
            // клик по батарее — фиксированное сообщение из batterycare.txt (без рандома)
            batteryImageView?.setOnClickListener {
                lifecycleScope.launch(Dispatchers.IO) {
                    val resp = loadBatteryCareResponse()
                    if (!resp.isNullOrBlank()) {
                        withContext(Dispatchers.Main) {
                            addChatMessage(currentMascotName, resp)
                        }
                    }
                }
            }
            if (plugged > 0) {
                btnCharging?.visibility = View.VISIBLE
            } else {
                btnCharging?.visibility = View.GONE
            }
            if (percent <= urgentThreshold && lastBatteryWarningStage > urgentThreshold) {
                addChatMessage(currentMascotName, "Это не шутки. Поставь на зарядку.")
            } else if (percent <= warningThreshold && lastBatteryWarningStage > warningThreshold) {
                val variants = listOf(
                    "Пожалуйста, поставь устройство на зарядку — батарейка почти села.",
                    "Аккумулятор низкий, лучше подключить зарядку.",
                    "Осталось немного заряда, поставь телефон на заряд."
                )
                addChatMessage(currentMascotName, variants.random())
            }
            lastBatteryWarningStage = percent
        }
    }

    private fun loadBatteryCareResponse(): String? {
        val uri = folderUri ?: return null
        try {
            val dir = DocumentFile.fromTreeUri(this, uri) ?: return null
            val file = dir.findFile("batterycare.txt")
            if (file != null && file.exists()) {
                contentResolver.openInputStream(file.uri)?.bufferedReader()?.use { reader ->
                    val allText = reader.readText().trim()
                    // не используем рандом — отправляем как единое сообщение
                    if (allText.isNotEmpty()) return allText
                }
            }
        } catch (e: Exception) {
            // не крашить — покажем тост
            showCustomToast("Ошибка загрузки batterycare.txt: ${e.message}")
        }
        return null
    }

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

    private fun registerNetworkReceiver() {
        if (networkReceiver != null) return
        networkReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                updateNetworkUI()
            }
        }
        val filter = IntentFilter().apply {
            addAction(ConnectivityManager.CONNECTIVITY_ACTION)
            addAction("android.intent.action.AIRPLANE_MODE")
            addAction(WifiManager.WIFI_STATE_CHANGED_ACTION)
        }
        registerReceiver(networkReceiver, filter)
        updateNetworkUI()
    }

    private fun registerBluetoothReceiver() {
        if (bluetoothReceiver != null) return
        bluetoothReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                updateBluetoothUI()
            }
        }
        registerReceiver(bluetoothReceiver, IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED))
        updateBluetoothUI()
    }

    private fun unregisterNetworkReceiver() {
        try {
            networkReceiver?.let { unregisterReceiver(it) }
        } catch (_: Exception) {}
        networkReceiver = null
    }

    private fun unregisterBluetoothReceiver() {
        try {
            bluetoothReceiver?.let { unregisterReceiver(it) }
        } catch (_: Exception) {}
        bluetoothReceiver = null
    }

    private fun updateNetworkUI() {
        runOnUi {
            try {
                val connectivityManager = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
                val airplaneMode = Settings.Global.getInt(contentResolver,
                    Settings.Global.AIRPLANE_MODE_ON, 0) != 0
                val network = connectivityManager.activeNetwork
                val caps = connectivityManager.getNetworkCapabilities(network)
                val hasWifi = caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
                val hasCell = caps?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) == true
                val isConnected = caps != null && (hasWifi || hasCell || caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET))

                // Три состояния: WiFi, Mobile, "airplane" (нет подключений). AirplaneMode OR !isConnected => offline state.
                lifecycleScope.launch(Dispatchers.IO) {
                    var bmp: Bitmap? = if (airplaneMode || !isConnected) {
                        tryLoadBitmapFromFolder("airplane.png")
                    } else if (hasWifi) {
                        tryLoadBitmapFromFolder("wifi.png")
                    } else if (hasCell) {
                        tryLoadBitmapFromFolder("mobile.png")
                    } else {
                        tryLoadBitmapFromFolder("airplane.png")
                    }
                    withContext(Dispatchers.Main) {
                        bmp?.let { wifiImageView?.setImageBitmap(it) }
                    }
                    bmp?.recycle()
                }
            } catch (_: Exception) {
                // silently ignore
            }
        }
    }

    private fun updateBluetoothUI() {
        runOnUi {
            val bluetoothAdapter = BluetoothAdapter.getDefaultAdapter()
            val isBluetoothEnabled = bluetoothAdapter?.isEnabled == true
            if (isBluetoothEnabled) {
                lifecycleScope.launch(Dispatchers.IO) {
                    var bmp: Bitmap? = tryLoadBitmapFromFolder("bluetooth.png")
                    withContext(Dispatchers.Main) {
                        bmp?.let {
                            bluetoothImageView?.setImageBitmap(it)
                            bluetoothImageView?.visibility = View.VISIBLE
                        }
                    }
                    bmp?.recycle()
                }
            } else {
                bluetoothImageView?.visibility = View.GONE
            }
        }
    }

    private fun loadTimeToSleepResponse(): String? {
        val uri = folderUri ?: return null
        try {
            val dir = DocumentFile.fromTreeUri(this, uri) ?: return null
            val sleepFile = dir.findFile("timetosleep.txt")
            if (sleepFile != null && sleepFile.exists()) {
                contentResolver.openInputStream(sleepFile.uri)?.bufferedReader()?.use { reader ->
                    val allText = reader.readText()
                    val responses = allText.split("|").map { it.trim() }.filter { it.isNotEmpty() }
                    if (responses.isNotEmpty()) {
                        return responses.random()
                    }
                }
            }
        } catch (e: Exception) {
            showCustomToast("Ошибка загрузки timetosleep.txt: ${e.message}")
        }
        return null
    }

    private fun processDateTimeQuery(query: String): String? {
        val calendar = Calendar.getInstance()
        val dateFormat = SimpleDateFormat("dd MMMM yyyy", Locale("ru"))
        val dayFormat = SimpleDateFormat("EEEE", Locale("ru"))
        val yearFormat = SimpleDateFormat("yyyy", Locale("ru"))
        val currentDate = dateFormat.format(calendar.time)
        val currentDay = dayFormat.format(calendar.time)
        val currentYear = yearFormat.format(calendar.time)
        val uri = folderUri ?: return getDefaultDateTimeResponse(query, currentDate, currentDay, currentYear)
        try {
            val dir = DocumentFile.fromTreeUri(this, uri) ?: return getDefaultDateTimeResponse(query, currentDate, currentDay, currentYear)
            val cldsysFile = dir.findFile("cldsys.txt")
            if (cldsysFile != null && cldsysFile.exists()) {
                contentResolver.openInputStream(cldsysFile.uri)?.bufferedReader()?.use { reader ->
                    val allText = reader.readText()
                    val responses = allText.split("|").map { it.trim() }.filter { it.isNotEmpty() }
                    if (responses.isNotEmpty()) {
                        val response = responses.random()
                        return when {
                            query.contains("число") -> response.replace("{date}", currentDate)
                            query.contains("день") -> response.replace("{day}", currentDay)
                            query.contains("год") -> response.replace("{year}", currentYear)
                            else -> response.replace("{date}", currentDate)
                        }
                    }
                }
            }
        } catch (e: Exception) {
            showCustomToast("Ошибка загрузки cldsys.txt: ${e.message}")
        }
        return getDefaultDateTimeResponse(query, currentDate, currentDay, currentYear)
    }

    private fun getDefaultDateTimeResponse(query: String, date: String, day: String, year: String): String {
        return when {
            query.contains("число") -> "Сегодня $date."
            query.contains("день") -> "Сегодня $day."
            query.contains("год") -> "Сейчас $year год."
            else -> "Сегодня $date, $day."
        }
    }

    private fun startTimeUpdater() {
        stopTimeUpdater()
        timeUpdaterRunnable = object : Runnable {
            override fun run() {
                val now = Date()
                val is24Hour = DateFormat.is24HourFormat(this@ChatActivity)
                val fmt = if (is24Hour) {
                    SimpleDateFormat("HH:mm", Locale.getDefault())
                } else {
                    SimpleDateFormat("hh:mm a", Locale.getDefault())
                }
                val s = fmt.format(now)
                runOnUi {
                    timeTextView?.text = s
                }
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
