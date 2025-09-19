package com.nemesis.droidcrypt

import android.animation.ObjectAnimator
import android.content.Intent
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.media.MediaPlayer
import android.net.Uri
import android.os.*
import android.speech.tts.TextToSpeech
import android.util.Log
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
import androidx.preference.PreferenceManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.*
import kotlin.collections.ArrayList
import kotlin.collections.HashMap
import com.nemesis.droidcrypt.Engine
import com.nemesis.droidcrypt.ChatCore
import kotlin.math.roundToInt

class ChatActivity : AppCompatActivity(), TextToSpeech.OnInitListener {

    companion object {
        private const val PREF_KEY_FOLDER_URI = "pref_folder_uri"
        private const val PREF_KEY_DISABLE_SCREENSHOTS = "pref_disable_screenshots"
    }

    // Engine instance (инициализируется в onCreate после загрузки synonyms/stopwords)
    private lateinit var engine: Engine

    // UI
    private var folderUri: Uri? = null
    private lateinit var scrollView: ScrollView
    private lateinit var queryInput: AutoCompleteTextView
    private var envelopeInputButton: ImageButton? = null
    private var btnLock: ImageButton? = null
    private var btnTrash: ImageButton? = null
    private var btnEnvelopeTop: ImageButton? = null
    private var btnSettings: ImageButton? = null
    private lateinit var messagesContainer: LinearLayout
    private var adapter: ArrayAdapter<String>? = null

    // TTS
    private var tts: TextToSpeech? = null

    // Data structures (Activity держит коллекции — Engine работает с ними через ссылку)
    private val templatesMap = HashMap<String, MutableList<String>>()
    private val contextMap = HashMap<String, String>() // keys in canonical form
    private val keywordResponses = HashMap<String, MutableList<String>>()
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
    private val queryTimestamps = HashMap<String, MutableList<Long>>()
    private var lastSendTime = 0L // Для дебouncing
    private val queryCache = object : LinkedHashMap<String, String>(Engine.MAX_CACHE_SIZE, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?): Boolean {
            return size > Engine.MAX_CACHE_SIZE
        }
    }

    // --- helper для canonical ключа (использует глобальные snapshots)
    private fun canonicalKeyFromTokens(tokens: List<String>): String = tokens.sorted().joinToString(" ")
    private fun canonicalKeyFromTextStatic(text: String, synonyms: Map<String, String>, stopwords: Set<String>): String {
        val toks = Engine.filterStopwordsAndMapSynonymsStatic(text, synonyms, stopwords).first
        return canonicalKeyFromTokens(toks)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_chat)
        window.addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN)
        supportActionBar?.setBackgroundDrawable(ColorDrawable(Color.argb(128, 0, 0, 0)))
        window.setBackgroundDrawable(ColorDrawable(Color.BLACK))
        setupToolbar()

        scrollView = findViewById(R.id.scrollView)
        queryInput = findViewById(R.id.queryInput)
        envelopeInputButton = findViewById(R.id.envelope_button)
        btnLock = findViewById(R.id.btn_lock)
        btnTrash = findViewById(R.id.btn_trash)
        btnEnvelopeTop = findViewById(R.id.btn_envelope_top)
        btnSettings = findViewById(R.id.btn_settings)
        messagesContainer = findViewById(R.id.chatMessagesContainer)

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
        } catch (e: Exception) {
            Log.e("ChatActivity", "Error loading folder URI", e)
        }

        // Загрузка синонимов/стоп-слов — нужно до инициализации engine
        ChatCore.loadSynonymsAndStopwords(this, folderUri, synonymsMap, stopwords)

        // Инициализация Engine (он работает с теми же коллекциями)
        engine = Engine(templatesMap, synonymsMap, stopwords)

        try {
            val prefs = PreferenceManager.getDefaultSharedPreferences(this)
            val disable = prefs.getBoolean(PREF_KEY_DISABLE_SCREENSHOTS, false)
            if (disable) window.addFlags(WindowManager.LayoutParams.FLAG_SECURE) else window.clearFlags(
                WindowManager.LayoutParams.FLAG_SECURE
            )
        } catch (e: Exception) {
            Log.e("ChatActivity", "Error setting screenshot flag", e)
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
        btnEnvelopeTop?.setOnClickListener { startActivity(Intent(this, PostsActivity::class.java)) }

        envelopeInputButton?.setOnClickListener {
            val now = System.currentTimeMillis()
            if (now - lastSendTime < Engine.SEND_DEBOUNCE_MS) return@setOnClickListener
            lastSendTime = now
            val input = queryInput.text.toString().trim()
            if (input.isNotEmpty()) {
                processUserQuery(input)
                queryInput.setText("")
            }
        }

        queryInput.setOnEditorActionListener { _, _, _ ->
            val now = System.currentTimeMillis()
            if (now - lastSendTime < Engine.SEND_DEBOUNCE_MS) return@setOnEditorActionListener true
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

        // Загрузка шаблонов
        if (folderUri == null) {
            showCustomToast("Папка не выбрана! Открой настройки и выбери папку.")
            ChatCore.loadFallbackTemplates(templatesMap, keywordResponses, mascotList, contextMap, synonymsMap, stopwords)
            rebuildInvertedIndex()
            engine.computeTokenWeights()
            updateAutoComplete()
            addChatMessage(currentMascotName, "Добро пожаловать!")
        } else {
            loadTemplatesFromFile(currentContext)
            rebuildInvertedIndex()
            engine.computeTokenWeights()
            updateAutoComplete()
            addChatMessage(currentMascotName, "Добро пожаловать!")
        }

        queryInput.setOnItemClickListener { parent, _, position, _ ->
            val selected = parent.getItemAtPosition(position) as String
            queryInput.setText(selected)
            processUserQuery(selected)
        }

        idleCheckRunnable = object : Runnable {
            override fun run() {
                if (System.currentTimeMillis() - lastUserInputTime > Engine.IDLE_TIMEOUT_MS) {
                    val idleMessage = listOf("Эй, ты здесь?", "Что-то тихо стало...", "Расскажи, о чём думаешь?").random()
                    addChatMessage(currentMascotName, idleMessage)
                }
                dialogHandler.postDelayed(this, 5000)
            }
        }
        idleCheckRunnable?.let { dialogHandler.postDelayed(it, 5000) }
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            tts?.language = Locale("ru", "RU")
            tts?.setPitch(1.0f)
            tts?.setSpeechRate(1.0f)
        } else {
            Log.e("ChatActivity", "TextToSpeech initialization failed with status $status")
        }
    }

    private fun setupToolbar() {
        val topBar = findViewById<LinearLayout>(R.id.topBar)
        val leftLayout = topBar.getChildAt(0) as LinearLayout
        leftLayout.removeAllViews()
        leftLayout.orientation = LinearLayout.HORIZONTAL
        leftLayout.gravity = Gravity.CENTER_VERTICAL
    }

    override fun onResume() {
        super.onResume()
        folderUri?.let { loadTemplatesFromFile(currentContext) }
        rebuildInvertedIndex()
        engine.computeTokenWeights()
        updateAutoComplete()
        idleCheckRunnable?.let {
            dialogHandler.removeCallbacks(it)
            dialogHandler.postDelayed(it, 5000)
        }
        loadToolbarIcons()
    }

    override fun onPause() {
        super.onPause()
        dialogHandler.removeCallbacksAndMessages(null)
    }

    override fun onDestroy() {
        super.onDestroy()
        dialogHandler.removeCallbacksAndMessages(null)
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
                } catch (e: Exception) {
                    Log.e("ChatActivity", "Error loading icon $name", e)
                }
            }
            tryLoadToImageButton("lock.png", btnLock)
            tryLoadToImageButton("trash.png", btnTrash)
            tryLoadToImageButton("envelope.png", btnEnvelopeTop)
            tryLoadToImageButton("settings.png", btnSettings)
            tryLoadToImageButton("send.png", envelopeInputButton)
        } catch (e: Exception) {
            Log.e("ChatActivity", "Error in loadToolbarIcons", e)
        }
    }

    private fun processUserQuery(userInput: String) {
        if (userInput.startsWith("/")) {
            handleCommand(userInput.trim())
            return
        }

        val qOrigRaw = userInput.trim()
        val qOrig = Engine.normalizeText(qOrigRaw)
        val (qTokensFiltered, qFiltered) = engine.filterStopwordsAndMapSynonyms(qOrig)
        // canonical ключ для точечных lookup'ов / кэша
        val qCanonical = if (qTokensFiltered.isNotEmpty()) canonicalKeyFromTokens(qTokensFiltered) else canonicalKeyFromTextStatic(qFiltered, synonymsMap, stopwords)
        val qKeyForCount = qCanonical

        if (qFiltered.isEmpty()) return

        // Кэш: проверка на повторный запрос (используем canonical)
        queryCache[qKeyForCount]?.let { cachedResponse ->
            addChatMessage(currentMascotName, cachedResponse)
            startIdleTimer()
            return
        }

        lastUserInputTime = System.currentTimeMillis()
        userActivityCount++

        addChatMessage("You", userInput)
        showTypingIndicator()

        // Антиспам с временным окном
        val now = System.currentTimeMillis()
        val timestamps = queryTimestamps.getOrPut(qKeyForCount) { mutableListOf() }
        timestamps.add(now)
        timestamps.removeAll { it < now - Engine.SPAM_WINDOW_MS }
        if (timestamps.size >= 5) {
            val spamResp = ChatCore.getAntiSpamResponse()
            addChatMessage(currentMascotName, spamResp)
            startIdleTimer()
            return
        }

        // Снэпшоты (чтобы не гонять коллекции по ссылке внутри background-работ)
        val templatesSnapshot = HashMap(templatesMap)
        val invertedIndexSnapshot = HashMap<String, MutableList<String>>()
        for ((k, v) in invertedIndex) invertedIndexSnapshot[k] = ArrayList(v)
        val synonymsSnapshot = HashMap(synonymsMap)
        val stopwordsSnapshot = HashSet(stopwords)
        val keywordResponsesSnapshot = HashMap<String, MutableList<String>>()
        for ((k, v) in keywordResponses) keywordResponsesSnapshot[k] = ArrayList(v)
        val contextMapSnapshot = HashMap(contextMap)

        lifecycleScope.launch(Dispatchers.Default) {
            // Попытки простых совпадений / subqueries
            var answered = false
            val subqueryResponses = mutableListOf<String>()
            val processedSubqueries = mutableSetOf<String>()

            // exact match по canonical ключу
            templatesSnapshot[qCanonical]?.let { possible ->
                if (possible.isNotEmpty()) {
                    subqueryResponses.add(possible.random())
                    answered = true
                    processedSubqueries.add(qCanonical)
                }
            }

            if (subqueryResponses.size < Engine.MAX_SUBQUERY_RESPONSES) {
                val tokens = if (qTokensFiltered.isNotEmpty()) qTokensFiltered else Engine.tokenizeStatic(qFiltered)
                for (token in tokens) {
                    if (subqueryResponses.size >= Engine.MAX_SUBQUERY_RESPONSES) break
                    if (processedSubqueries.contains(token) || token.length < 2) continue
                    templatesSnapshot[token]?.let { possible ->
                        if (possible.isNotEmpty()) {
                            subqueryResponses.add(possible.random())
                            processedSubqueries.add(token)
                        }
                    }
                    if (subqueryResponses.size < Engine.MAX_SUBQUERY_RESPONSES) {
                        keywordResponsesSnapshot[token]?.let { possible ->
                            if (possible.isNotEmpty()) {
                                subqueryResponses.add(possible.random())
                                processedSubqueries.add(token)
                            }
                        }
                    }
                }
                if (subqueryResponses.size < Engine.MAX_SUBQUERY_RESPONSES && tokens.size > 1) {
                    for (i in 0 until tokens.size - 1) {
                        if (subqueryResponses.size >= Engine.MAX_SUBQUERY_RESPONSES) break
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

            // Ключевые ответы (keywordResponsesSnapshot keys — canonical strings)
            val qTokenSet = if (qTokensFiltered.isNotEmpty()) qTokensFiltered.toSet() else Engine.tokenizeStatic(qFiltered).toSet()
            for ((keyword, responses) in keywordResponsesSnapshot) {
                if (keyword.isBlank() || responses.isEmpty()) continue
                val kwTokens = keyword.split(" ").filter { it.isNotEmpty() }.toSet()
                if (qTokenSet.intersect(kwTokens).isNotEmpty()) {
                    withContext(Dispatchers.Main) {
                        addChatMessage(currentMascotName, responses.random())
                        startIdleTimer()
                    }
                    return@launch
                }
            }

            // Поиск кандидатов через invertedIndexSnapshot
            val qTokens = if (qTokensFiltered.isNotEmpty()) qTokensFiltered else Engine.tokenizeStatic(qFiltered)
            val candidateCounts = HashMap<String, Int>()
            for (tok in qTokens) {
                invertedIndexSnapshot[tok]?.forEach { trig ->
                    candidateCounts[trig] = candidateCounts.getOrDefault(trig, 0) + 1
                }
            }

            val candidates: List<String> = if (candidateCounts.isNotEmpty()) {
                candidateCounts.entries
                    .filter { it.value >= Engine.CANDIDATE_TOKEN_THRESHOLD }
                    .sortedByDescending { it.value }
                    .map { it.key }
                    .take(Engine.MAX_CANDIDATES_FOR_LEV)
            } else {
                val maxDist = engine.getFuzzyDistance(qCanonical)
                templatesSnapshot.keys.filter { kotlin.math.abs(it.length - qCanonical.length) <= maxDist }
                    .take(Engine.MAX_CANDIDATES_FOR_LEV)
            }

            // Jaccard
            var bestByJaccard: String? = null
            var bestJaccard = 0.0
            val qSet = qTokens.toSet()
            val jaccardThreshold = engine.getJaccardThreshold(qFiltered)
            for (key in candidates) {
                val keyTokens = Engine.filterStopwordsAndMapSynonymsStatic(key, synonymsSnapshot, stopwordsSnapshot).first.toSet()
                if (keyTokens.isEmpty()) continue
                val weightedJ = engine.weightedJaccard(qSet, keyTokens)
                if (weightedJ > bestJaccard) {
                    bestJaccard = weightedJ
                    bestByJaccard = key
                }
            }
            if (bestByJaccard != null && bestJaccard >= jaccardThreshold) {
                val possible = templatesSnapshot[bestByJaccard]
                if (!possible.isNullOrEmpty()) {
                    val response = possible.random()
                    withContext(Dispatchers.Main) {
                        addChatMessage(currentMascotName, response)
                        startIdleTimer()
                        cacheResponse(qKeyForCount, response)
                    }
                    return@launch
                }
            }

            // Levenshtein
            var bestKey: String? = null
            var bestDist = Int.MAX_VALUE
            for (key in candidates) {
                val maxDist = engine.getFuzzyDistance(qCanonical)
                if (kotlin.math.abs(key.length - qCanonical.length) > maxDist + 1) continue
                val d = engine.levenshtein(qCanonical, key, qCanonical)
                if (d < bestDist) {
                    bestDist = d
                    bestKey = key
                }
                if (bestDist == 0) break
            }
            val maxDistLocal = engine.getFuzzyDistance(qCanonical)
            if (bestKey != null && bestDist <= maxDistLocal) {
                val possible = templatesSnapshot[bestKey]
                if (!possible.isNullOrEmpty()) {
                    val response = possible.random()
                    withContext(Dispatchers.Main) {
                        addChatMessage(currentMascotName, response)
                        startIdleTimer()
                        cacheResponse(qKeyForCount, response)
                    }
                    return@launch
                }
            }

            // Попытка детекта контекста (по contextMapSnapshot)
            val detectedContext = ChatCore.detectContext(qFiltered, contextMapSnapshot, engine)

            if (detectedContext != null) {
                withContext(Dispatchers.Main) {
                    if (detectedContext != currentContext) {
                        currentContext = detectedContext
                        loadTemplatesFromFile(currentContext)
                        rebuildInvertedIndex()
                        engine.computeTokenWeights()
                        updateAutoComplete()
                    }
                }

                // Сначала попытка найти точный/локальный ответ в файле контекста
                val (localTemplates, localKeywords) = ChatCore.parseTemplatesFromFile(
                    this@ChatActivity, folderUri, detectedContext, synonymsSnapshot, stopwordsSnapshot
                )
                localTemplates[qCanonical]?.let { possible ->
                    if (possible.isNotEmpty()) {
                        val response = possible.random()
                        withContext(Dispatchers.Main) {
                            addChatMessage(currentMascotName, response)
                            startIdleTimer()
                            cacheResponse(qKeyForCount, response)
                        }
                        return@launch
                    }
                }

                // local inverted + jaccard/levenshtein аналогично (упрощённо)
                val localInverted = HashMap<String, MutableList<String>>()
                for ((k, v) in localTemplates) {
                    val toks = if (k.isBlank()) emptyList<String>() else k.split(" ").filter { it.isNotEmpty() }
                    for (t in toks) {
                        val list = localInverted.getOrPut(t) { mutableListOf() }
                        if (!list.contains(k)) list.add(k)
                    }
                }
                val localCandidateCounts = HashMap<String, Int>()
                val tokensLocal = qTokens
                for (tok in tokensLocal) {
                    localInverted[tok]?.forEach { trig ->
                        localCandidateCounts[trig] = localCandidateCounts.getOrDefault(trig, 0) + 1
                    }
                }
                val localCandidates: List<String> = if (localCandidateCounts.isNotEmpty()) {
                    localCandidateCounts.entries
                        .filter { it.value >= Engine.CANDIDATE_TOKEN_THRESHOLD }
                        .sortedByDescending { it.value }
                        .map { it.key }
                        .take(Engine.MAX_CANDIDATES_FOR_LEV)
                } else {
                    val md = engine.getFuzzyDistance(qCanonical)
                    localTemplates.keys.filter { kotlin.math.abs(it.length - qCanonical.length) <= md }
                        .take(Engine.MAX_CANDIDATES_FOR_LEV)
                }

                var bestLocal: String? = null
                var bestLocalJ = 0.0
                val qSetLocal = tokensLocal.toSet()
                for (key in localCandidates) {
                    val keyTokens = Engine.filterStopwordsAndMapSynonymsStatic(key, synonymsSnapshot, stopwordsSnapshot).first.toSet()
                    if (keyTokens.isEmpty()) continue
                    val weightedJ = engine.weightedJaccard(qSetLocal, keyTokens)
                    if (weightedJ > bestLocalJ) {
                        bestLocalJ = weightedJ
                        bestLocal = key
                    }
                }
                if (bestLocal != null && bestLocalJ >= jaccardThreshold) {
                    val possible = localTemplates[bestLocal]
                    if (!possible.isNullOrEmpty()) {
                        val response = possible.random()
                        withContext(Dispatchers.Main) {
                            addChatMessage(currentMascotName, response)
                            startIdleTimer()
                            cacheResponse(qKeyForCount, response)
                        }
                        return@launch
                    }
                }

                var bestLocalKey: String? = null
                var bestLocalDist = Int.MAX_VALUE
                for (key in localCandidates) {
                    val maxD = engine.getFuzzyDistance(qCanonical)
                    if (kotlin.math.abs(key.length - qCanonical.length) > maxD + 1) continue
                    val d = engine.levenshtein(qCanonical, key, qCanonical)
                    if (d < bestLocalDist) {
                        bestLocalDist = d
                        bestLocalKey = key
                    }
                    if (bestLocalDist == 0) break
                }
                if (bestLocalKey != null && bestLocalDist <= engine.getFuzzyDistance(qCanonical)) {
                    val possible = localTemplates[bestLocalKey]
                    if (!possible.isNullOrEmpty()) {
                        val response = possible.random()
                        withContext(Dispatchers.Main) {
                            addChatMessage(currentMascotName, response)
                            startIdleTimer()
                            cacheResponse(qKeyForCount, response)
                        }
                        return@launch
                    }
                }

                // Поиск по core-файлам через ChatCore (использует engine для веса/levenshtein)
                val coreResult = ChatCore.searchInCoreFiles(
                    this@ChatActivity, folderUri, qFiltered, qTokens, engine,
                    synonymsSnapshot, stopwordsSnapshot, jaccardThreshold
                )
                if (coreResult != null) {
                    withContext(Dispatchers.Main) {
                        addChatMessage(currentMascotName, coreResult)
                        startIdleTimer()
                        cacheResponse(qKeyForCount, coreResult)
                    }
                    return@launch
                }

                val dummy = ChatCore.getDummyResponse(qOrig)
                withContext(Dispatchers.Main) {
                    addChatMessage(currentMascotName, dummy)
                    startIdleTimer()
                    cacheResponse(qKeyForCount, dummy)
                }
                return@launch
            }

            // Ищем по core, если не найдено
            val coreResult = ChatCore.searchInCoreFiles(
                this@ChatActivity, folderUri, qFiltered, qTokens, engine,
                synonymsSnapshot, stopwordsSnapshot, jaccardThreshold
            )
            if (coreResult != null) {
                withContext(Dispatchers.Main) {
                    addChatMessage(currentMascotName, coreResult)
                    startIdleTimer()
                    cacheResponse(qKeyForCount, coreResult)
                }
                return@launch
            }

            val dummy = ChatCore.getDummyResponse(qOrig)
            withContext(Dispatchers.Main) {
                addChatMessage(currentMascotName, dummy)
                startIdleTimer()
                cacheResponse(qKeyForCount, dummy)
            }
        }
    }

    // --- Делегирующие/адаптирующие методы, чтобы не менять остальной код ---

    private fun cacheResponse(qKey: String, response: String) {
        queryCache[qKey] = response
    }

    private fun handleCommand(cmdRaw: String) {
        val cmd = cmdRaw.trim().lowercase(Locale.getDefault())
        when {
            cmd == "/reload" -> {
                addChatMessage(currentMascotName, "Перезагружаю шаблоны...")
                loadTemplatesFromFile(currentContext)
                rebuildInvertedIndex()
                engine.computeTokenWeights()
                updateAutoComplete()
                addChatMessage(currentMascotName, "Шаблоны перезагружены.")
            }
            cmd == "/stats" -> {
                val templatesCount = templatesMap.size
                val keywordsCount = keywordResponses.size
                val msg = "Контекст: $currentContext. Шаблонов: $templatesCount. Ключевых ответов: $keywordsCount."
                addChatMessage(currentMascotName, msg)
            }
            cmd == "/clear" || cmd == "очисти чат" -> {
                clearChat()
            }
            else -> {
                addChatMessage(currentMascotName, "Неизвестная команда: $cmdRaw")
            }
        }
    }

    private fun showTypingIndicator() {
        runOnUiThread {
            val existing = messagesContainer.findViewWithTag<View>("typingView")
            if (existing != null) return@runOnUiThread
            val typingView = TextView(this).apply {
                text = "печатает..."
                textSize = 14f
                setTextColor(getColor(android.R.color.white))
                setBackgroundColor(0x80000000.toInt())
                alpha = 0.7f
                setPadding(16, 8, 16, 8)
                tag = "typingView"
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    setMargins(0, 16, 0, 0)
                }
            }
            messagesContainer.addView(typingView)
            scrollView.post { scrollView.smoothScrollTo(0, messagesContainer.bottom) }
            Handler(Looper.getMainLooper()).postDelayed({
                runOnUiThread {
                    messagesContainer.findViewWithTag<View>("typingView")?.let { messagesContainer.removeView(it) }
                }
            }, (1000..3000).random().toLong())
        }
    }

    private fun clearChat() {
        runOnUiThread {
            messagesContainer.removeAllViews()
            queryCountMap.clear()
            queryTimestamps.clear()
            queryCache.clear()
            lastQuery = ""
            currentContext = "base.txt"
            loadTemplatesFromFile(currentContext)
            rebuildInvertedIndex()
            engine.computeTokenWeights()
            updateAutoComplete()
            addChatMessage(currentMascotName, "Чат очищен. Возвращаюсь к началу.")
        }
    }

    private fun rebuildInvertedIndex() {
        invertedIndex.clear()
        val tempIndex = engine.rebuildInvertedIndex()
        invertedIndex.putAll(tempIndex)
    }

    private fun addChatMessage(sender: String, text: String) {
        runOnUiThread {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                val pad = dpToPx(6)
                setPadding(pad, pad / 2, pad, pad / 2)
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            }
            val isUser = sender.equals("You", ignoreCase = true)
            if (isUser) {
                val bubble = createMessageBubble(sender, text, isUser)
                val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
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
                    setOnClickListener { view ->
                        view.isEnabled = false
                        val scaleX = ObjectAnimator.ofFloat(view, "scaleX", 1f, 1.08f, 1f)
                        val scaleY = ObjectAnimator.ofFloat(view, "scaleY", 1f, 1.08f, 1f)
                        scaleX.duration = 250
                        scaleY.duration = 250
                        scaleX.start()
                        scaleY.start()
                        Handler(Looper.getMainLooper()).postDelayed({
                            loadAndSendOuchMessage(sender)
                            view.isEnabled = true
                        }, 260)
                    }
                }
                val bubble = createMessageBubble(sender, text, isUser)
                val bubbleLp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                bubbleLp.marginStart = dpToPx(8)
                row.addView(avatarView)
                row.addView(bubble, bubbleLp)
            }
            messagesContainer.addView(row)
            messagesContainer.findViewWithTag<View>("typingView")?.let { messagesContainer.removeView(it) }
            if (messagesContainer.childCount > Engine.MAX_MESSAGES) {
                val removeCount = messagesContainer.childCount - Engine.MAX_MESSAGES
                repeat(removeCount) { messagesContainer.removeViewAt(0) }
            }
            scrollView.post { scrollView.smoothScrollTo(0, messagesContainer.bottom) }
            if (!isUser) {
                playNotificationSound()
            }
        }
    }

    private fun speakText(text: String) {
        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "message_${System.currentTimeMillis()}")
    }

    private fun loadAndSendOuchMessage(mascot: String) {
        val uri = folderUri ?: return
        try {
            val dir = DocumentFile.fromTreeUri(this, uri) ?: return
            val mascotOuch = dir.findFile("${mascot.lowercase(Locale.getDefault())}.txt") ?: dir.findFile("ouch.txt")
            if (mascotOuch != null && mascotOuch.exists()) {
                contentResolver.openInputStream(mascotOuch.uri)?.bufferedReader()?.use { reader ->
                    val allText = reader.readText()
                    val responses = allText.split("|").map { it.trim() }.filter { it.isNotEmpty() }
                    if (responses.isNotEmpty()) {
                        val randomResponse = responses.random()
                        addChatMessage(mascot, randomResponse)
                    }
                }
            }
        } catch (e: Exception) {
            Log.e("ChatActivity", "Error loading ouch message", e)
            showCustomToast("Ошибка загрузки ouch.txt: ${e.message}")
        }
    }

    private fun playNotificationSound() {
        val uri = folderUri ?: return
        try {
            val dir = DocumentFile.fromTreeUri(this, uri) ?: return
            val soundFile = dir.findFile("notify.ogg") ?: return
            if (!soundFile.exists()) return
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
                Log.e("ChatActivity", "Error playing notification sound", e)
                try { afd.close() } catch (_: Exception) {}
                try { player.reset(); player.release() } catch (_: Exception) {}
            }
        } catch (e: Exception) {
            Log.e("ChatActivity", "Error in playNotificationSound", e)
        }
    }

    private fun spaceView(): View = View(this).apply {
        layoutParams = LinearLayout.LayoutParams(0, 0, 1f)
    }

    private fun createMessageBubble(sender: String, text: String, isUser: Boolean): LinearLayout {
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
            val accent = if (isUser) {
                Color.parseColor("#00FF00")
            } else {
                safeParseColorOrDefault(currentThemeColor, Color.parseColor("#00FF00"))
            }
            background = createBubbleDrawable(accent)
            try {
                setTextColor(if (isUser) Color.WHITE else Color.parseColor(currentThemeColor))
            } catch (_: Exception) {
                setTextColor(Color.WHITE)
            }
            setOnClickListener { speakText(text) }
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
            val candidates = listOf("${s}_icon.png", "${s}_avatar.png", "${s}.png", currentMascotIcon)
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
        } catch (e: Exception) {
            Log.e("ChatActivity", "Error loading avatar", e)
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

    private fun loadTemplatesFromFile(filename: String) {
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

        // Обновляем синонимы/стоп-слова
        ChatCore.loadSynonymsAndStopwords(this, folderUri, synonymsMap, stopwords)

        if (folderUri == null) {
            ChatCore.loadFallbackTemplates(templatesMap, keywordResponses, mascotList, contextMap, synonymsMap, stopwords)
            rebuildInvertedIndex()
            engine.computeTokenWeights()
            updateUI(currentMascotName, currentMascotIcon, currentThemeColor, currentThemeBackground)
            return
        }
        try {
            val dir = DocumentFile.fromTreeUri(this, folderUri!!) ?: run {
                ChatCore.loadFallbackTemplates(templatesMap, keywordResponses, mascotList, contextMap, synonymsMap, stopwords)
                rebuildInvertedIndex()
                engine.computeTokenWeights()
                updateUI(currentMascotName, currentMascotIcon, currentThemeColor, currentThemeBackground)
                return
            }
            val file = dir.findFile(filename)
            if (file == null || !file.exists()) {
                ChatCore.loadFallbackTemplates(templatesMap, keywordResponses, mascotList, contextMap, synonymsMap, stopwords)
                rebuildInvertedIndex()
                engine.computeTokenWeights()
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
                                val keyword = parts[0].trim()
                                val contextFile = parts[1].trim()
                                if (keyword.isNotEmpty() && contextFile.isNotEmpty()) {
                                    val keyCanon = canonicalKeyFromTextStatic(keyword, synonymsMap, stopwords)
                                    if (keyCanon.isNotEmpty()) contextMap[keyCanon] = contextFile
                                }
                            }
                        }
                        return@forEachLine
                    }
                    if (l.startsWith("-")) {
                        val keywordLine = l.substring(1)
                        if (keywordLine.contains("=")) {
                            val parts = keywordLine.split("=", limit = 2)
                            if (parts.size == 2) {
                                val keyword = parts[0].trim()
                                val responses = parts[1].split("|")
                                val responseList = responses.mapNotNull { it.trim().takeIf { s -> s.isNotEmpty() } }.toMutableList()
                                if (keyword.isNotEmpty() && responseList.isNotEmpty()) {
                                    val keyCanon = canonicalKeyFromTextStatic(keyword, synonymsMap, stopwords)
                                    if (keyCanon.isNotEmpty()) keywordResponses[keyCanon] = responseList
                                }
                            }
                        }
                        return@forEachLine
                    }
                    if (!l.contains("=")) return@forEachLine
                    val parts = l.split("=", limit = 2)
                    if (parts.size == 2) {
                        val triggerRaw = parts[0].trim()
                        val triggerTokens = Engine.filterStopwordsAndMapSynonymsStatic(triggerRaw, synonymsMap, stopwords).first
                        val triggerCanonical = triggerTokens.sorted().joinToString(" ")
                        val responses = parts[1].split("|")
                        val responseList = responses.mapNotNull { it.trim().takeIf { s -> s.isNotEmpty() } }.toMutableList()
                        if (triggerCanonical.isNotEmpty() && responseList.isNotEmpty()) templatesMap[triggerCanonical] = responseList
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
            rebuildInvertedIndex()
            engine.computeTokenWeights()
            updateUI(currentMascotName, currentMascotIcon, currentThemeColor, currentThemeBackground)
        } catch (e: Exception) {
            Log.e("ChatActivity", "Error loading templates from $filename", e)
            showCustomToast("Ошибка чтения файла: ${e.message}")
            ChatCore.loadFallbackTemplates(templatesMap, keywordResponses, mascotList, contextMap, synonymsMap, stopwords)
            rebuildInvertedIndex()
            engine.computeTokenWeights()
            updateUI(currentMascotName, currentMascotIcon, currentThemeColor, currentThemeBackground)
        }
    }

    private fun updateAutoComplete() {
        val suggestions = mutableListOf<String>()
        // templatesMap keys — canonical strings; показываем их прямо (можно заменить на prettier rendering)
        suggestions.addAll(templatesMap.keys)
        for (s in ChatCore.fallbackReplies) {
            val low = Engine.normalizeText(s)
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
        val dir = DocumentFile.fromTreeUri(this, folderUri!!) ?: return
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
                    updateUI(currentMascotName, currentMascotIcon, currentThemeColor, currentThemeBackground)
                }
            } catch (e: Exception) {
                Log.e("ChatActivity", "Error loading mascot metadata", e)
                showCustomToast("Ошибка загрузки метаданных маскота: ${e.message}")
            }
        }
    }

    private fun updateUI(mascotName: String, mascotIcon: String, themeColor: String, themeBackground: String) {
        runOnUiThread {
            title = "Pawstribe - $mascotName"
            try {
                messagesContainer.setBackgroundColor(Color.parseColor(themeBackground))
            } catch (e: Exception) {
                Log.e("ChatActivity", "Error updating UI background", e)
            }
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
            Log.e("ChatActivity", "Error showing custom toast", e)
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

    private fun runOnUiThread(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block()
        else Handler(Looper.getMainLooper()).post(block)
    }
}
