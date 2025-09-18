package com.nemesis.droidcrypt

import android.animation.ObjectAnimator
import android.content.*
import android.graphics.*
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.ColorDrawable
import android.media.MediaPlayer
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.preference.PreferenceManager
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.delay
import java.util.*
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.min
import kotlin.math.roundToInt

class ChatActivity : AppCompatActivity(), TextToSpeech.OnInitListener {

    companion object {
        private const val PREF_KEY_FOLDER_URI = "pref_folder_uri"
        private const val PREF_KEY_DISABLE_SCREENSHOTS = "pref_disable_screenshots"
        private const val MAX_CONTEXT_SWITCH = 6
        private const val MAX_MESSAGES = 250
        private const val CANDIDATE_TOKEN_THRESHOLD = 2
        private const val MAX_SUBQUERY_RESPONSES = 3
        private const val SUBQUERY_RESPONSE_DELAY = 1500L
        private const val MAX_CANDIDATES_FOR_LEV = 25
        private const val JACCARD_THRESHOLD = 0.50
        private const val SEND_DEBOUNCE_MS = 400L
        private const val IDLE_TIMEOUT_MS = 30000L
        private const val MAX_CACHE_SIZE = 100
        private const val SPAM_WINDOW_MS = 60000L
        private const val MAX_TOKENS_PER_INDEX = 50
        private const val MIN_TOKEN_LENGTH = 3
        private const val MAX_TEMPLATES_SIZE = 5000
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

    // Data structures
    private val fallback = arrayOf("Привет", "Как дела?", "Расскажи о себе", "Выход")
    private val templatesMap = HashMap<String, MutableList<String>>()
    private val contextMap = HashMap<String, String>() // keyword -> filename (parsed from base.txt lines like :золото=resourcesmoney.txt:)
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
    private val queryTimestamps = HashMap<String, MutableList<Long>>()
    private var lastSendTime = 0L // Для дебouncing
    private val queryCache = object : LinkedHashMap<String, String>(MAX_CACHE_SIZE, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?): Boolean {
            return size > MAX_CACHE_SIZE
        }
    }
    private val tokenWeights = HashMap<String, Double>()

    // New: user variables and last topic for context
    private val userVariables = HashMap<String, String>()
    private var lastTopic: String? = null

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

        loadSynonymsAndStopwords()
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

        if (folderUri == null) {
            showCustomToast("Папка не выбрана! Открой настройки и выбери папку.")
            loadFallbackTemplates()
            rebuildInvertedIndex()
            computeTokenWeights()
            updateAutoComplete()
            addChatMessage(currentMascotName, "Добро пожаловать!")
        } else {
            // currentContext по умолчанию "base.txt" — загрузим его, чтобы заполнить contextMap
            loadTemplatesFromFile(currentContext)
            rebuildInvertedIndex()
            computeTokenWeights()
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
                if (System.currentTimeMillis() - lastUserInputTime > IDLE_TIMEOUT_MS) {
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
        computeTokenWeights()
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

    // processUserQuery now supports optional splitting control (allowSplit)
    private fun processUserQuery(userInput: String, allowSplit: Boolean = true) {
        if (userInput.startsWith("/")) {
            handleCommand(userInput.trim())
            return
        }

        val rawInput = userInput.trim()
        // Split compound queries early (commas/semicolons). Keep changes minimal: schedule subqueries.
        if (allowSplit) {
            val parts = rawInput.split(Regex("[,;]")).map { it.trim() }.filter { it.isNotEmpty() }
            if (parts.size > 1) {
                // Show single user message and schedule subqueries
                addChatMessage("You", rawInput)
                showTypingIndicator()
                for ((idx, part) in parts.withIndex()) {
                    val delayMs = idx * SUBQUERY_RESPONSE_DELAY
                    Handler(Looper.getMainLooper()).postDelayed({
                        processUserQuery(part, false)
                    }, delayMs)
                }
                return
            }
        }

        // Resolve pronouns 'оно/она/он' to lastTopic (context recall)
        var processedInput = rawInput
        lastTopic?.let { topic ->
            if (processedInput.contains(Regex("\\b(оно|она|он)\\b", RegexOption.IGNORE_CASE))) {
                processedInput = processedInput.replace(Regex("\\b(оно|она|он)\\b", RegexOption.IGNORE_CASE), topic)
            }
        }

        val qOrigRaw = processedInput
        val qOrig = normalizeText(qOrigRaw)
        val (qTokensFiltered, qFiltered) = filterStopwordsAndMapSynonyms(qOrig)
        val qKeyForCount = qFiltered

        if (qFiltered.isEmpty()) return

        // Кэш: проверка на повторный запрос
        queryCache[qKeyForCount]?.let { cachedResponse ->
            addChatMessage(currentMascotName, applyVariables(cachedResponse))
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
        timestamps.removeAll { it < now - SPAM_WINDOW_MS }
        if (timestamps.size >= 5) {
            val spamResp = antiSpamResponses.random()
            addChatMessage(currentMascotName, spamResp)
            startIdleTimer()
            return
        }

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

            fun parseTemplatesFromFile(filename: String): Pair<HashMap<String, MutableList<String>>, HashMap<String, MutableList<String>>> {
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
                            // preserve same parsing rules as loadTemplatesFromFile (but do not alter global maps)
                            if (filename == "base.txt" && l.startsWith(":") && l.endsWith(":")) {
                                // context mapping lines are handled in loadTemplatesFromFile; skip here
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
                    Log.e("ChatActivity", "Error parsing templates from $filename", e)
                }
                return Pair(localTemplates, localKeywords)
            }

            // helper: check if a response string looks like a file-redirect (contains .txt)
            fun looksLikeFilename(resp: String): Boolean {
                val t = resp.trim().lowercase(Locale.getDefault())
                return t.contains(".txt")
            }

            // recursive resolver: try to find a response for lookupKey inside filename (depth-limited)
            suspend fun resolveInFile(filename: String, lookupKey: String, depth: Int = 0): String? {
                if (depth > 3) return null
                val (fileTemplates, fileKeywords) = parseTemplatesFromFile(filename)
                // 1) exact in fileTemplates
                fileTemplates[lookupKey]?.let { list ->
                    if (list.isNotEmpty()) {
                        val candidate = list.random()
                        // if candidate is a redirect to another file, follow it
                        if (looksLikeFilename(candidate)) {
                            // extract filename (strip surrounding colons if any)
                            val f = candidate.replace(":", "").trim()
                            return resolveInFile(f, lookupKey, depth + 1) ?: candidate
                        }
                        return candidate
                    }
                }
                // 2) exact in fileKeywords
                fileKeywords[lookupKey]?.let { list ->
                    if (list.isNotEmpty()) {
                        val candidate = list.random()
                        if (looksLikeFilename(candidate)) {
                            val f = candidate.replace(":", "").trim()
                            return resolveInFile(f, lookupKey, depth + 1) ?: candidate
                        }
                        return candidate
                    }
                }
                // 3) fuzzy over fileTemplates keys
                if (fileTemplates.isNotEmpty()) {
                    val qSet = filterStopwordsAndMapSynonymsLocal(lookupKey).first.toSet()
                    var bestK: String? = null
                    var bestJ = 0.0
                    val jaccardThresholdLocal = getJaccardThreshold(lookupKey)
                    for (k in fileTemplates.keys) {
                        val ktoks = filterStopwordsAndMapSynonymsLocal(k).first.toSet()
                        if (ktoks.isEmpty()) continue
                        val wj = weightedJaccard(qSet, ktoks)
                        if (wj > bestJ) { bestJ = wj; bestK = k }
                    }
                    if (bestK != null && bestJ >= jaccardThresholdLocal) {
                        val list = fileTemplates[bestK]
                        if (!list.isNullOrEmpty()) {
                            val candidate = list.random()
                            if (looksLikeFilename(candidate)) {
                                val f = candidate.replace(":", "").trim()
                                return resolveInFile(f, lookupKey, depth + 1) ?: candidate
                            }
                            return candidate
                        }
                    }
                }
                return null
            }

            // --- Improved: detect "что такое" pattern and answer per term using contextMap + base.txt + referenced files
            val normalizedOrig = normalizeLocal(qOrigRaw)
            if (normalizedOrig.startsWith("что такое")) {
                val after = normalizedOrig.removePrefix("что такое").trim()
                if (after.isNotEmpty()) {
                    // Split terms by 'и', commas or semicolons
                    val termParts = after.split(Regex("\\s*(?:и|,|;)\\s*")).map { it.trim() }.filter { it.isNotEmpty() }
                    if (termParts.isNotEmpty()) {
                        // canonicalize terms via synonyms/stopwords
                        val canonicalTerms = termParts.map { tp ->
                            val mapped = filterStopwordsAndMapSynonymsLocal(tp).second
                            if (mapped.isNotEmpty()) mapped else tp
                        }

                        // remember first canonical topic for context recall
                        val firstTopic = canonicalTerms.firstOrNull()
                        if (!firstTopic.isNullOrEmpty()) {
                            lastTopic = firstTopic
                        }

                        for ((idx, rawTerm) in canonicalTerms.withIndex()) {
                            var respToSend: String? = null
                            val lookupKey = rawTerm

                            // 1) try current templates (templatesSnapshot)
                            templatesSnapshot[lookupKey]?.let { list ->
                                if (list.isNotEmpty()) respToSend = list.random()
                            }
                            if (respToSend == null) {
                                keywordResponsesSnapshot[lookupKey]?.let { list ->
                                    if (list.isNotEmpty()) respToSend = list.random()
                                }
                            }

                            // 2) if not found in current, consult contextMap (from base.txt). If contextMap has mapping, open that file and search inside it.
                            if (respToSend == null) {
                                val mappedContextFile = contextMapSnapshot[lookupKey]
                                if (mappedContextFile != null) {
                                    // mappedContextFile may be e.g. resourcesmoney.txt (without colons)
                                    val candidate = resolveInFile(mappedContextFile, lookupKey)
                                    if (!candidate.isNullOrEmpty()) respToSend = candidate
                                }
                            }

                            // 3) If still not found, try to resolve directly in base.txt (some projects keep definitions there)
                            if (respToSend == null) {
                                val candidate = resolveInFile("base.txt", lookupKey)
                                if (!candidate.isNullOrEmpty()) respToSend = candidate
                            }

                            // 4) fallback: try fuzzy in global templates
                            if (respToSend == null) {
                                val qSet = filterStopwordsAndMapSynonymsLocal(lookupKey).first.toSet()
                                var bestK: String? = null
                                var bestJ = 0.0
                                val jaccardThresholdLocal = getJaccardThreshold(lookupKey)
                                for (k in templatesSnapshot.keys) {
                                    val ktoks = filterStopwordsAndMapSynonymsLocal(k).first.toSet()
                                    if (ktoks.isEmpty()) continue
                                    val wj = weightedJaccard(qSet, ktoks)
                                    if (wj > bestJ) { bestJ = wj; bestK = k }
                                }
                                if (bestK != null && bestJ >= jaccardThresholdLocal) {
                                    templatesSnapshot[bestK]?.let { list -> if (list.isNotEmpty()) respToSend = list.random() }
                                }
                            }

                            // final fallback message
                            if (respToSend == null) respToSend = "Не знаю подробно про $rawTerm, но могу поискать в шаблонах."

                            withContext(Dispatchers.Main) {
                                addChatMessage(currentMascotName, applyVariables(respToSend!!))
                                startIdleTimer()
                            }
                            // space between replies if multiple terms
                            if (idx < canonicalTerms.size - 1) delay(SUBQUERY_RESPONSE_DELAY)
                        }
                        return@launch
                    }
                }
            }
            // --- End "что такое" handling

            // Save name if user introduced themselves: "меня зовут <Name>"
            val lowerRaw = qOrigRaw.lowercase(Locale.getDefault())
            if (lowerRaw.contains("меня зовут")) {
                try {
                    val marker = "меня зовут"
                    val idx = lowerRaw.indexOf(marker)
                    val afterPhrase = if (idx >= 0) qOrigRaw.substring(idx + marker.length).trim() else ""
                    val nameCandidate = afterPhrase.split(Regex("\\s+")).firstOrNull()?.trim()?.replace(Regex("[^\\p{L}\\-]"), "") ?: ""
                    if (nameCandidate.isNotEmpty()) {
                        val cleanName = nameCandidate.replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.getDefault()) else it.toString() }
                        userVariables["name"] = cleanName
                        // polite reply
                        withContext(Dispatchers.Main) {
                            addChatMessage(currentMascotName, applyVariables("Приятно, \$name!"))
                            startIdleTimer()
                        }
                        return@launch
                    }
                } catch (_: Exception) { /* non-fatal */ }
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
                    addChatMessage(currentMascotName, applyVariables(combined))
                    startIdleTimer()
                }
                return@launch
            }

            for ((keyword, responses) in keywordResponsesSnapshot) {
                if (qFiltered.contains(keyword) && responses.isNotEmpty()) {
                    withContext(Dispatchers.Main) {
                        addChatMessage(currentMascotName, applyVariables(responses.random()))
                        startIdleTimer()
                    }
                    return@launch
                }
            }

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

            var bestByJaccard: String? = null
            var bestJaccard = 0.0
            val qSet = qTokens.toSet()
            val jaccardThreshold = getJaccardThreshold(qFiltered)
            for (key in candidates) {
                val keyTokens = filterStopwordsAndMapSynonymsLocal(key).first.toSet()
                if (keyTokens.isEmpty()) continue
                val weightedJ = weightedJaccard(qSet, keyTokens)
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
                        addChatMessage(currentMascotName, applyVariables(response))
                        startIdleTimer()
                        cacheResponse(qKeyForCount, response)
                    }
                    return@launch
                }
            }

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
            val maxDistLocal = getFuzzyDistance(qFiltered)
            if (bestKey != null && bestDist <= maxDistLocal) {
                val possible = templatesSnapshot[bestKey]
                if (!possible.isNullOrEmpty()) {
                    val response = possible.random()
                    withContext(Dispatchers.Main) {
                        addChatMessage(currentMascotName, applyVariables(response))
                        startIdleTimer()
                        cacheResponse(qKeyForCount, response)
                    }
                    return@launch
                }
            }

            val lower = normalizeLocal(qFiltered)
            val detectedContext = detectContext(lower)
            if (detectedContext != null) {
                withContext(Dispatchers.Main) {
                    if (detectedContext != currentContext) {
                        currentContext = detectedContext
                        loadTemplatesFromFile(currentContext)
                        rebuildInvertedIndex()
                        computeTokenWeights()
                        updateAutoComplete()
                    }
                }
                val (localTemplates, localKeywords) = parseTemplatesFromFile(detectedContext)
                localTemplates[qFiltered]?.let { possible ->
                    if (possible.isNotEmpty()) {
                        val response = possible.random()
                        withContext(Dispatchers.Main) {
                            addChatMessage(currentMascotName, applyVariables(response))
                            startIdleTimer()
                            cacheResponse(qKeyForCount, response)
                        }
                        return@launch
                    }
                }
                val localInverted = HashMap<String, MutableList<String>>()
                for ((k, v) in localTemplates) {
                    val toks = filterStopwordsAndMapSynonymsLocal(k).first
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
                        .filter { it.value >= CANDIDATE_TOKEN_THRESHOLD }
                        .sortedByDescending { it.value }
                        .map { it.key }
                        .take(MAX_CANDIDATES_FOR_LEV)
                } else {
                    val md = getFuzzyDistance(qFiltered)
                    localTemplates.keys.filter { abs(it.length - qFiltered.length) <= md }
                        .take(MAX_CANDIDATES_FOR_LEV)
                }
                var bestLocal: String? = null
                var bestLocalJ = 0.0
                val qSetLocal = tokensLocal.toSet()
                for (key in localCandidates) {
                    val keyTokens = filterStopwordsAndMapSynonymsLocal(key).first.toSet()
                    if (keyTokens.isEmpty()) continue
                    val weightedJ = weightedJaccard(qSetLocal, keyTokens)
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
                            addChatMessage(currentMascotName, applyVariables(response))
                            startIdleTimer()
                            cacheResponse(qKeyForCount, response)
                        }
                        return@launch
                    }
                }
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
                        val response = possible.random()
                        withContext(Dispatchers.Main) {
                            addChatMessage(currentMascotName, applyVariables(response))
                            startIdleTimer()
                            cacheResponse(qKeyForCount, response)
                        }
                        return@launch
                    }
                }
                val dummy = getDummyResponse(qOrig)
                withContext(Dispatchers.Main) {
                    addChatMessage(currentMascotName, applyVariables(dummy))
                    startIdleTimer()
                    cacheResponse(qKeyForCount, dummy)
                }
                return@launch
            }

            val dummy = getDummyResponse(qOrig)
            withContext(Dispatchers.Main) {
                addChatMessage(currentMascotName, applyVariables(dummy))
                startIdleTimer()
                cacheResponse(qKeyForCount, dummy)
            }
        }
    }

    private fun getJaccardThreshold(query: String): Double {
        return when {
            query.length <= 10 -> 0.3
            query.length <= 20 -> 0.4
            else -> JACCARD_THRESHOLD
        }
    }

    private fun computeTokenWeights() {
        tokenWeights.clear()
        val tokenCounts = HashMap<String, Int>()
        var totalTokens = 0
        for (key in templatesMap.keys) {
            val tokens = filterStopwordsAndMapSynonyms(key).first
            for (token in tokens) {
                tokenCounts[token] = tokenCounts.getOrDefault(token, 0) + 1
                totalTokens++
            }
        }
        tokenCounts.forEach { (token, count) ->
            tokenWeights[token] = if (totalTokens == 0) 1.0 else log10(totalTokens.toDouble() / count).coerceAtLeast(1.0)
        }
    }

    private fun weightedJaccard(qSet: Set<String>, keyTokens: Set<String>): Double {
        val intersection = qSet.intersect(keyTokens)
        val union = qSet.union(keyTokens)
        val interWeight = intersection.sumOf { tokenWeights.getOrDefault(it, 1.0) }
        val unionWeight = union.sumOf { tokenWeights.getOrDefault(it, 1.0) }
        return if (unionWeight == 0.0) 0.0 else interWeight / unionWeight
    }

    private fun cacheResponse(qKey: String, response: String) {
        queryCache[qKey] = response
    }

    private fun detectContext(input: String): String? {
        val tokens = tokenize(input)
        val contextScores = HashMap<String, Int>()
        for ((keyword, value) in contextMap) {
            val keywordTokens = tokenize(keyword)
            val matches = tokens.count { it in keywordTokens }
            if (matches > 0) contextScores[value] = contextScores.getOrDefault(value, 0) + matches
        }
        return contextScores.maxByOrNull { it.value }?.key
    }

    private fun handleCommand(cmdRaw: String) {
        val cmd = cmdRaw.trim().lowercase(Locale.getDefault())
        when {
            cmd == "/reload" -> {
                addChatMessage(currentMascotName, "Перезагружаю шаблоны...")
                loadTemplatesFromFile(currentContext)
                rebuildInvertedIndex()
                computeTokenWeights()
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
            computeTokenWeights()
            updateAutoComplete()
            addChatMessage(currentMascotName, "Чат очищен. Возвращаюсь к началу.")
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
            val dir = DocumentFile.fromTreeUri(this, uri) ?: return
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
        } catch (e: Exception) {
            Log.e("ChatActivity", "Error loading synonyms/stopwords", e)
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
        val tempIndex = HashMap<String, MutableList<String>>()
        for (key in templatesMap.keys) {
            val toks = filterStopwordsAndMapSynonyms(key).first.filter { it.length >= MIN_TOKEN_LENGTH || keywordResponses.containsKey(it) }
            for (t in toks) {
                val list = tempIndex.getOrPut(t) { mutableListOf() }
                if (!list.contains(key)) list.add(key)
                if (list.size > MAX_TOKENS_PER_INDEX) {
                    list.sortByDescending { templatesMap[it]?.size ?: 0 }
                    list.subList(MAX_TOKENS_PER_INDEX, list.size).clear()
                }
            }
        }
        invertedIndex.putAll(tempIndex)
        trimTemplatesMap()
    }

    private fun trimTemplatesMap() {
        if (templatesMap.size > MAX_TEMPLATES_SIZE) {
            val leastUsed = templatesMap.keys.sortedBy { queryCountMap.getOrDefault(it, 0) }.take(templatesMap.size - MAX_TEMPLATES_SIZE)
            leastUsed.forEach { templatesMap.remove(it) }
            Log.d("ChatActivity", "Trimmed templatesMap to $MAX_TEMPLATES_SIZE entries")
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
            if (messagesContainer.childCount > MAX_MESSAGES) {
                val removeCount = messagesContainer.childCount - MAX_MESSAGES
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
                        addChatMessage(mascot, applyVariables(randomResponse))
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
        loadSynonymsAndStopwords()
        if (folderUri == null) {
            loadFallbackTemplates()
            rebuildInvertedIndex()
            computeTokenWeights()
            updateUI(currentMascotName, currentMascotIcon, currentThemeColor, currentThemeBackground)
            return
        }
        try {
            val dir = DocumentFile.fromTreeUri(this, folderUri!!) ?: run {
                loadFallbackTemplates()
                rebuildInvertedIndex()
                computeTokenWeights()
                updateUI(currentMascotName, currentMascotIcon, currentThemeColor, currentThemeBackground)
                return
            }
            val file = dir.findFile(filename)
            if (file == null || !file.exists()) {
                loadFallbackTemplates()
                rebuildInvertedIndex()
                computeTokenWeights()
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
            rebuildInvertedIndex()
            computeTokenWeights()
            updateUI(currentMascotName, currentMascotIcon, currentThemeColor, currentThemeBackground)
        } catch (e: Exception) {
            Log.e("ChatActivity", "Error loading templates from $filename", e)
            showCustomToast("Ошибка чтения файла: ${e.message}")
            loadFallbackTemplates()
            rebuildInvertedIndex()
            computeTokenWeights()
            updateUI(currentMascotName, currentMascotIcon, currentThemeColor, currentThemeBackground)
        }
    }

    private fun loadFallbackTemplates() {
        templatesMap.clear()
        contextMap.clear()
        keywordResponses.clear()
        mascotList.clear()
        val t1 = normalizeText("привет")
        templatesMap[t1] = mutableListOf("Привет! Чем могу помочь?", "Здравствуй!")
        val t2 = normalizeText("как дела")
        templatesMap[t2] = mutableListOf("Всё отлично, а у тебя?", "Нормально, как дела?")
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

    // --- New helper: apply user variables into response text (supports $name or ${name})
    private fun applyVariables(text: String): String {
        var out = text
        // replace ${var} patterns
        val braceRegex = Regex("\\$\\{([a-zA-Z0-9_]+)\\}")
        out = out.replace(braceRegex) { mr ->
            val key = mr.groupValues[1]
            userVariables[key] ?: mr.value
        }
        // replace $var patterns
        val simpleRegex = Regex("\\$([a-zA-Z0-9_]+)")
        out = out.replace(simpleRegex) { mr ->
            val key = mr.groupValues[1]
            userVariables[key] ?: mr.value
        }
        return out
    }
    // --- end helpers

    private fun runOnUiThread(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block()
        else Handler(Looper.getMainLooper()).post(block)
    }
}
