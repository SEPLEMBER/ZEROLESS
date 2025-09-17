package com.nemesis.droidcrypf

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
import java.text.DateFormat
import java.util.*
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.random.Random

class ChatActivity : AppCompatActivity(), TextToSpeech.OnInitListener {

    companion object {
        private const val PREF_KEY_FOLDER_URI = "pref_folder_uri"
        private const val PREF_KEY_DISABLE_SCREENSHOTS = "pref_disable_screenshots"
        private const val MAX_MESSAGES = 250
        private const val CANDIDATE_TOKEN_THRESHOLD = 2
        private const val MAX_SUBQUERY_RESPONSES = 3
        private const val SUBQUERY_RESPONSE_DELAY = 1500L
        private const val MAX_CANDIDATES_FOR_LEV = 25
        private const val JACCARD_THRESHOLD = 0.50
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
    private lateinit var scrollView: ScrollView
    private lateinit var queryInput: AutoCompleteTextView
    private var envelopeInputButton: ImageButton? = null
    private var btnLock: ImageButton? = null
    private var btnTrash: ImageButton? = null
    private var btnEnvelopeTop: ImageButton? = null
    private var btnSettings: ImageButton? = null
    private var btnCharging: ImageButton? = null
    private lateinit var messagesContainer: LinearLayout
    private var adapter: ArrayAdapter<String>? = null
    private var batteryImageView: ImageView? = null
    private var batteryPercentView: TextView? = null
    private var timeTextView: TextView? = null
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
    private val queryCountMap = HashMap<String, Int>()
    private var currentMascotName = "Racky"
    private var currentMascotIcon = "raccoon_icon.png"
    private var currentThemeColor = "#00FF00"
    private var currentThemeBackground = "#000000"
    private var currentContext = "base.txt"
    private var lastQuery = ""
    private var userActivityCount = 0
    private var lastSendTime = 0L
    private var lastBatteryWarningStage = Int.MAX_VALUE
    private var batteryReceiver: BroadcastReceiver? = null
    private val timeHandler = Handler(Looper.getMainLooper())
    private var timeUpdaterRunnable: Runnable? = null
    private val bitmapCache = HashMap<String, Bitmap>()
    private var typingView: TextView? = null
    private var lastAddMessageTime = 0L

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
        showLoadingScreen(true)
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
        } catch (_: Exception) {}

        lifecycleScope.launch(Dispatchers.IO) {
            loadSynonymsAndStopwords()
            try {
                val prefs = PreferenceManager.getDefaultSharedPreferences(this@ChatActivity)
                val disable = prefs.getBoolean(PREF_KEY_DISABLE_SCREENSHOTS, false)
                withContext(Dispatchers.Main) {
                    if (disable) window.addFlags(WindowManager.LayoutParams.FLAG_SECURE) else window.clearFlags(
                        WindowManager.LayoutParams.FLAG_SECURE
                    )
                }
            } catch (_: Exception) {}

            if (folderUri == null) {
                loadFallbackTemplates()
                rebuildInvertedIndex()
                withContext(Dispatchers.Main) {
                    showCustomToast("Папка не выбрана! Открой настройки и выбери папку.")
                    updateAutoComplete()
                    addChatMessage(currentMascotName, "Добро пожаловать!")
                    showLoadingScreen(false)
                }
            } else {
                loadTemplatesFromFile(currentContext)
                rebuildInvertedIndex()
                withContext(Dispatchers.Main) {
                    updateAutoComplete()
                    addChatMessage(currentMascotName, "Добро пожаловать!")
                    showLoadingScreen(false)
                }
            }
        }

        loadToolbarIcons()
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

        tts = TextToSpeech(this, this)

        queryInput.setOnItemClickListener { parent, _, position, _ ->
            val selected = parent.getItemAtPosition(position) as String
            queryInput.setText(selected)
            processUserQuery(selected)
        }
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            tts?.language = Locale("ru", "RU")
            tts?.setPitch(1.0f)
            tts?.setSpeechRate(1.0f)
        }
    }

    private fun showLoadingScreen(show: Boolean) {
        runOnUi {
            var loadingView = findViewById<TextView>(R.id.loadingView)
            if (loadingView == null && show) {
                loadingView = TextView(this).apply {
                    id = R.id.loadingView
                    text = "Подождите..."
                    textSize = 18f
                    setTextColor(Color.WHITE)
                    setBackgroundColor(Color.BLACK)
                    gravity = Gravity.CENTER
                    layoutParams = ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT
                    )
                    (findViewById<ViewGroup>(android.R.id.content)).addView(this)
                }
            }
            loadingView?.visibility = if (show) View.VISIBLE else View.GONE
        }
    }

    private fun setupToolbar() {
        val topBar = findViewById<LinearLayout>(R.id.topBar)
        val leftLayout = topBar.getChildAt(0) as LinearLayout
        leftLayout.removeAllViews()
        leftLayout.orientation = LinearLayout.HORIZONTAL
        leftLayout.gravity = Gravity.CENTER_VERTICAL

        batteryImageView = ImageView(this).apply {
            val iconSize = dpToPx(56)
            layoutParams = LinearLayout.LayoutParams(iconSize, iconSize).apply {
                marginStart = dpToPx(6)
            }
            scaleType = ImageView.ScaleType.CENTER_CROP
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

        btnCharging = ImageButton(this).apply {
            background = null
            val iconSize = dpToPx(56)
            val lp = LinearLayout.LayoutParams(iconSize, iconSize)
            layoutParams = lp
            scaleType = ImageView.ScaleType.CENTER_CROP
            adjustViewBounds = true
            visibility = View.GONE
        }
        topBar.addView(btnCharging)
    }

    override fun onResume() {
        super.onResume()
        lifecycleScope.launch(Dispatchers.IO) {
            folderUri?.let { loadTemplatesFromFile(currentContext) }
            rebuildInvertedIndex()
            withContext(Dispatchers.Main) {
                updateAutoComplete()
            }
        }
        loadToolbarIcons()
        registerBatteryReceiver()
        startTimeUpdater()
    }

    override fun onPause() {
        super.onPause()
        unregisterBatteryReceiver()
        stopTimeUpdater()
    }

    override fun onDestroy() {
        super.onDestroy()
        unregisterBatteryReceiver()
        stopTimeUpdater()
        tts?.shutdown()
        tts = null
        bitmapCache.values.forEach { it.recycle() }
        bitmapCache.clear()
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
        lifecycleScope.launch(Dispatchers.IO) {
            val uri = folderUri ?: return@launch
            val dir = DocumentFile.fromTreeUri(this@ChatActivity, uri) ?: return@launch

            fun tryLoadBitmap(name: String): Bitmap? {
                if (bitmapCache.containsKey(name)) return bitmapCache[name]
                return try {
                    val file = dir.findFile(name)
                    if (file != null && file.exists()) {
                        contentResolver.openInputStream(file.uri)?.use { ins ->
                            BitmapFactory.decodeStream(ins)?.also { bitmapCache[name] = it }
                        }
                    } else null
                } catch (_: Exception) { null }
            }

            val lockBmp = tryLoadBitmap("lock.png")
            val trashBmp = tryLoadBitmap("trash.png")
            val envelopeBmp = tryLoadBitmap("envelope.png")
            val settingsBmp = tryLoadBitmap("settings.png")
            val chargingBmp = tryLoadBitmap("charging.png")
            val sendBmp = tryLoadBitmap("send.png")
            val batteryBmp = tryLoadBitmap("battery_5.png")

            withContext(Dispatchers.Main) {
                lockBmp?.let { btnLock?.setImageBitmap(it) }
                trashBmp?.let { btnTrash?.setImageBitmap(it) }
                envelopeBmp?.let { btnEnvelopeTop?.setImageBitmap(it) }
                settingsBmp?.let { btnSettings?.setImageBitmap(it) }
                chargingBmp?.let { btnCharging?.setImageBitmap(it) }
                sendBmp?.let { envelopeInputButton?.setImageBitmap(it) }
                batteryBmp?.let { batteryImageView?.setImageBitmap(it) }
            }
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

        userActivityCount++

        val calendar = Calendar.getInstance()
        val hour = calendar.get(Calendar.HOUR_OF_DAY)
        val minute = calendar.get(Calendar.MINUTE)
        if ((hour >= 23 || hour < 6) && userActivityCount == 2) {
            val sleepResponse = loadTimeToSleepResponse()
            if (sleepResponse != null) {
                addChatMessage("You", userInput)
                showTypingIndicator()
                addChatMessage(currentMascotName, sleepResponse)
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
            return
        }

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
                    val s = synonymsMap[n] ?: n
                    s
                }.filter { it.isNotEmpty() && !stopwords.contains(it) }
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
                } catch (_: Exception) {}
                return Pair(localTemplates, localKeywords)
            }

            var answered = false
            val subqueryResponses = mutableListOf<String>()
            val processedSubqueries = mutableSetOf<String>()

            templatesMap[qFiltered]?.let { possible ->
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
                    templatesMap[token]?.let { possible ->
                        if (possible.isNotEmpty()) {
                            subqueryResponses.add(possible.random())
                            processedSubqueries.add(token)
                        }
                    }
                    if (subqueryResponses.size < MAX_SUBQUERY_RESPONSES) {
                        keywordResponses[token]?.let { possible ->
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
                        templatesMap[twoTokens]?.let { possible ->
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
                return@launch withContext(Dispatchers.Main) {
                    addChatMessage(currentMascotName, combined)
                }
            }

            for ((keyword, responses) in keywordResponses) {
                if (qFiltered.contains(keyword) && responses.isNotEmpty()) {
                    return@launch withContext(Dispatchers.Main) {
                        addChatMessage(currentMascotName, responses.random())
                    }
                }
            }

            val qTokens = if (qTokensFiltered.isNotEmpty()) qTokensFiltered else tokenizeLocal(qFiltered)
            val candidateCounts = HashMap<String, Int>()
            for (tok in qTokens) {
                invertedIndex[tok]?.forEach { trig ->
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
                templatesMap.keys.filter { abs(it.length - qFiltered.length) <= maxDist }
                    .take(MAX_CANDIDATES_FOR_LEV)
            }

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
                val possible = templatesMap[bestByJaccard]
                if (!possible.isNullOrEmpty()) {
                    return@launch withContext(Dispatchers.Main) {
                        addChatMessage(currentMascotName, possible.random())
                    }
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
            val maxDist = getFuzzyDistance(qFiltered)
            if (bestKey != null && bestDist <= maxDist) {
                val possible = templatesMap[bestKey]
                if (!possible.isNullOrEmpty()) {
                    return@launch withContext(Dispatchers.Main) {
                        addChatMessage(currentMascotName, possible.random())
                    }
                }
            }

            val lower = normalizeLocal(qFiltered)
            for ((keyword, value) in contextMap) {
                if (lower.contains(keyword)) {
                    return@launch withContext(Dispatchers.Main) {
                        if (value != currentContext) {
                            currentContext = value
                            loadTemplatesFromFile(currentContext)
                            rebuildInvertedIndex()
                            updateAutoComplete()
                        }
                    }.let {
                        val (localTemplates, localKeywords) = parseTemplatesFromFile(value)
                        localTemplates[qFiltered]?.let { possible ->
                            if (possible.isNotEmpty()) {
                                return@launch withContext(Dispatchers.Main) {
                                    addChatMessage(currentMascotName, possible.random())
                                }
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
                                return@launch withContext(Dispatchers.Main) {
                                    addChatMessage(currentMascotName, possible.random())
                                }
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
                                return@launch withContext(Dispatchers.Main) {
                                    addChatMessage(currentMascotName, possible.random())
                                }
                            }
                        }
                        return@launch withContext(Dispatchers.Main) {
                            addChatMessage(currentMascotName, getDummyResponse(qOrig))
                        }
                    }
                }
            }

            return@launch withContext(Dispatchers.Main) {
                addChatMessage(currentMascotName, getDummyResponse(qOrig))
            }
        }
    }

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

    private fun showTypingIndicator() {
        runOnUi {
            if (typingView != null) return@runOnUi
            typingView = TextView(this).apply {
                text = "печатает..."
                textSize = 14f
                setTextColor(getColor(android.R.color.white))
                setBackgroundColor(0x80000000.toInt())
                alpha = 0.7f
                setPadding(16, 8, 16, 8)
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
                runOnUi {
                    typingView?.let { messagesContainer.removeView(it); typingView = null }
                }
            }, (1000..3000).random().toLong())
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

    private fun loadSynonymsAndStopwords() {
        synonymsMap.clear()
        stopwords.clear()
        val uri = folderUri ?: return
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val dir = DocumentFile.fromTreeUri(this@ChatActivity, uri) ?: return@launch
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
        val now = System.currentTimeMillis()
        if (now - lastAddMessageTime < 100) return
        lastAddMessageTime = now
        runOnUi {
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
                    val size = dpToPx(40)
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
            typingView?.let { messagesContainer.removeView(it); typingView = null }
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
                        addChatMessage(mascot, randomResponse)
                    }
                }
            }
        } catch (e: Exception) {
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
                try { afd.close() } catch (_: Exception) {}
                try { player.reset(); player.release() } catch (_: Exception) {}
            }
        } catch (_: Exception) {}
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
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val dir = DocumentFile.fromTreeUri(this@ChatActivity, uri) ?: return@launch
                val s = sender.lowercase(Locale.getDefault())
                val candidates = listOf("${s}_icon.png", "${s}_avatar.png", "${s}.png", currentMascotIcon)
                for (name in candidates) {
                    if (bitmapCache.containsKey(name)) {
                        withContext(Dispatchers.Main) { target.setImageBitmap(bitmapCache[name]) }
                        return@launch
                    }
                    val f = dir.findFile(name) ?: continue
                    if (f.exists()) {
                        contentResolver.openInputStream(f.uri)?.use { ins ->
                            val bmp = BitmapFactory.decodeStream(ins)
                            bitmapCache[name] = bmp
                            withContext(Dispatchers.Main) { target.setImageBitmap(bmp) }
                            return@launch
                        }
                    }
                }
                withContext(Dispatchers.Main) { target.setImageResource(android.R.color.transparent) }
            } catch (_: Exception) {
                withContext(Dispatchers.Main) { target.setImageResource(android.R.color.transparent) }
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
            updateUI(currentMascotName, currentMascotIcon, currentThemeColor, currentThemeBackground)
            return
        }
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val dir = DocumentFile.fromTreeUri(this@ChatActivity, folderUri!!) ?: run {
                    loadFallbackTemplates()
                    rebuildInvertedIndex()
                    withContext(Dispatchers.Main) {
                        updateUI(currentMascotName, currentMascotIcon, currentThemeColor, currentThemeBackground)
                    }
                    return@launch
                }
                val file = dir.findFile(filename)
                if (file == null || !file.exists()) {
                    loadFallbackTemplates()
                    rebuildInvertedIndex()
                    withContext(Dispatchers.Main) {
                        updateUI(currentMascotName, currentMascotIcon, currentThemeColor, currentThemeBackground)
                    }
                    return@launch
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
                withContext(Dispatchers.Main) {
                    updateUI(currentMascotName, currentMascotIcon, currentThemeColor, currentThemeBackground)
                }
            } catch (e: Exception) {
                e.printStackTrace()
                loadFallbackTemplates()
                rebuildInvertedIndex()
                withContext(Dispatchers.Main) {
                    showCustomToast("Ошибка чтения файла: ${e.message}")
                    updateUI(currentMascotName, currentMascotIcon, currentThemeColor, currentThemeBackground)
                }
            }
        }
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
                showCustomToast("Ошибка загрузки метаданных маскота: ${e.message}")
            }
        }
    }

    private fun updateUI(mascotName: String, mascotIcon: String, themeColor: String, themeBackground: String) {
        runOnUi {
            title = "Pawstribe - $mascotName"
            try {
                messagesContainer.setBackgroundColor(Color.parseColor(themeBackground))
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
            val loaded = tryLoadBitmapFromFolder("battery_$iconIndex.png")
            if (loaded != null) {
                batteryImageView?.setImageBitmap(loaded)
            } else {
                tryLoadBitmapFromFolder("battery.png")?.let { batteryImageView?.setImageBitmap(it) }
            }
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
                    if (allText.isNotEmpty()) return allText
                }
            }
        } catch (e: Exception) {
            showCustomToast("Ошибка загрузки batterycare.txt: ${e.message}")
        }
        return null
    }

    private fun tryLoadBitmapFromFolder(name: String): Bitmap? {
        val uri = folderUri ?: return null
        if (bitmapCache.containsKey(name)) return bitmapCache[name]
        return try {
            val dir = DocumentFile.fromTreeUri(this, uri) ?: return null
            val f = dir.findFile(name) ?: return null
            if (!f.exists()) return null
            contentResolver.openInputStream(f.uri)?.use { ins ->
                BitmapFactory.decodeStream(ins)?.also { bitmapCache[name] = it }
            }
        } catch (e: Exception) {
            null
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
                        return responses.random()
                    }
                }
            }
        } catch (e: Exception) {
            showCustomToast("Ошибка загрузки cldsys.txt: ${e.message}")
        }
        return getDefaultDateTimeResponse(query, currentDate, currentDay, currentYear)
    }

    private fun getDefaultDateTimeResponse(query: String, currentDate: String, currentDay: String, currentYear: String): String {
        return when {
            query.contains("какое сегодня число") -> "Сегодня $currentDate."
            query.contains("какой сегодня день") || query.contains("какой сейчас день") -> "Сегодня $currentDay."
            query.contains("какой сейчас год") -> "Сейчас $currentYear год."
            else -> "Сегодня $currentDate, $currentDay."
        }
    }

}
