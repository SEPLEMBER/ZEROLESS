package app.pawstribe.assistant

import android.animation.ObjectAnimator
import android.content.Intent
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.media.MediaPlayer
import android.net.Uri
import android.os.*
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
import androidx.preference.PreferenceManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import java.io.InputStreamReader
import java.text.Normalizer
import java.util.*
import kotlin.collections.ArrayList
import kotlin.collections.HashMap
import kotlin.math.roundToInt
import app.pawstribe.assistant.Engine
import app.pawstribe.assistant.ChatCore
import app.pawstribe.assistant.MemoryManager
import app.pawstribe.assistant.R
import java.util.Arrays

class ChatActivity : AppCompatActivity(), TextToSpeech.OnInitListener {

    companion object {
        private const val PREF_KEY_FOLDER_URI = "pref_folder_uri"
        private const val PREF_KEY_DISABLE_SCREENSHOTS = "pref_disable_screenshots"
        // (Пароль берём из SharedPreferences под этим ключом)
        const val PREF_KEY_ENCRYPTION_PASSWORD = "pref_encryption_password"
    }

    private lateinit var engine: Engine

    private var folderUri: Uri? = null
    private lateinit var scrollView: ScrollView
    private lateinit var queryInput: AutoCompleteTextView
    private var envelopeInputButton: ImageButton? = null
    private var btnLock: ImageButton? = null
    private var btnTrash: ImageButton? = null
    private lateinit var messagesContainer: LinearLayout
    private var adapter: ArrayAdapter<String>? = null

    private var tts: TextToSpeech? = null

    private val templatesMap = HashMap<String, MutableList<String>>()
    private val contextMap = HashMap<String, String>()
    private val keywordResponses = HashMap<String, MutableList<String>>()
    private val mascotList = mutableListOf<Map<String, String>>()
    private val invertedIndex = HashMap<String, MutableList<String>>()
    private val synonymsMap = HashMap<String, String>()
    private val stopwords = HashSet<String>()
    private var currentMascotName = "Racky"
    private var currentMascotIcon = "raccoon_icon.png"
    private var currentThemeColor = "#00FFFF"
    private var currentThemeBackground = "#0A0A0A"
    private var currentContext = "base.txt"
    private var isContextLocked = false

    // Overlay view shown on startup
    private var startupOverlay: FrameLayout? = null

    private val dialogHandler = Handler(Looper.getMainLooper())
    private var idleCheckRunnable: Runnable? = null
    private var lastUserInputTime = System.currentTimeMillis()
    private val random = Random()
    private val queryTimestamps = HashMap<String, MutableList<Long>>()
    private var lastSendTime = 0L
    private val queryCache = object : LinkedHashMap<String, String>(Engine.MAX_CACHE_SIZE, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?): Boolean {
            return size > Engine.MAX_CACHE_SIZE
        }
    }

    private fun canonicalKeyFromTokens(tokens: List<String>): String = tokens.sorted().joinToString(" ")
    private fun canonicalKeyFromTextStatic(text: String, synonyms: Map<String, String>, stopwords: Set<String>): String {
        val normalized = normalizeForProcessing(text)
        val toks = Engine.filterStopwordsAndMapSynonymsStatic(normalized, synonyms, stopwords).first
        return canonicalKeyFromTokens(toks)
    }

    // Normalize unicode and unify whitespace for downstream processing
    private fun normalizeForProcessing(s: String): String {
        val norm = Normalizer.normalize(s, Normalizer.Form.NFC)
        return norm.replace(Regex("\\s+"), " ").trim()
    }

    // Basic script-based language hint (best-effort)
    private fun detectLanguageTagByScript(text: String): String {
        val t = text.trim()
        if (t.isEmpty()) return Locale.getDefault().language
        val counts = mapOf(
            "CYRILLIC" to Regex("\\p{InCyrillic}").findAll(t).count(),
            "LATIN" to Regex("\\p{IsLatin}").findAll(t).count(),
            "HAN" to Regex("\\p{IsHan}").findAll(t).count(),
            "HIRAGANA" to Regex("\\p{IsHiragana}").findAll(t).count(),
            "KATAKANA" to Regex("\\p{IsKatakana}").findAll(t).count()
        )
        val maxEntry = counts.maxByOrNull { it.value } ?: return Locale.getDefault().language
        return when (maxEntry.key) {
            "CYRILLIC" -> "ru"
            "LATIN" -> "en"
            "HAN" -> "zh"
            "HIRAGANA", "KATAKANA" -> "ja"
            else -> Locale.getDefault().language
        }
    }

    private fun setTTSLanguageForText(text: String) {
        try {
            val langTag = detectLanguageTagByScript(text)
            val loc = Locale.forLanguageTag(langTag)
            val available = tts?.isLanguageAvailable(loc) ?: TextToSpeech.LANG_NOT_SUPPORTED
            if (available >= TextToSpeech.LANG_AVAILABLE) {
                tts?.language = loc
            } else {
                // fallback to system default
                tts?.language = Locale.getDefault()
            }
        } catch (_: Exception) {
            try { tts?.language = Locale.getDefault() } catch (_: Exception) {}
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        ChatCore.init(applicationContext)

        setContentView(R.layout.activity_chat)
        window.addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN)
        supportActionBar?.setBackgroundDrawable(ColorDrawable(Color.argb(128, 0, 0, 0)))
        window.setBackgroundDrawable(ColorDrawable(Color.BLACK))
        setupToolbar()

        // show the startup overlay immediately (find it in layout or create it)
        showStartupOverlay()

        scrollView = findViewById(R.id.scrollView)
        queryInput = findViewById(R.id.queryInput)
        envelopeInputButton = findViewById(R.id.envelope_button)
        btnLock = findViewById(R.id.btn_lock)
        btnTrash = findViewById(R.id.btn_trash)
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
        }

        engine = Engine(templatesMap, synonymsMap, stopwords)

        try {
            val prefs = getSharedPreferences("PawsTribePrefs", MODE_PRIVATE)
            val disable = prefs.getBoolean("disableScreenshots", false)
            if (disable) window.addFlags(WindowManager.LayoutParams.FLAG_SECURE) else window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        } catch (e: Exception) {
        }

        // NOTE: removed touch effect and click handlers for lock/trash per request
        setupIconTouchEffect(envelopeInputButton)

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

        tts = TextToSpeech(this, this)

        // Autocomplete item click handling removed because autocomplete is disabled.
        // queryInput.setOnItemClickListener { ... } was intentionally removed.

        idleCheckRunnable = object : Runnable {
            override fun run() {
                if (System.currentTimeMillis() - lastUserInputTime > Engine.IDLE_TIMEOUT_MS) {
                    val idleMessage = listOf(
                        getString(R.string.idle_msg_1),
                        getString(R.string.idle_msg_2),
                        getString(R.string.idle_msg_3)
                    ).random()
                    addChatMessage(currentMascotName, idleMessage)
                }
                dialogHandler.postDelayed(this, 500000)
            }
        }
        idleCheckRunnable?.let { dialogHandler.postDelayed(it, 500000) }

        lifecycleScope.launch(Dispatchers.IO) {
            ChatCore.loadSynonymsAndStopwords(this@ChatActivity, folderUri, synonymsMap, stopwords)
            MemoryManager.init(this@ChatActivity)
            MemoryManager.loadTemplatesFromFolder(this@ChatActivity, folderUri)
            if (folderUri == null) {
                ChatCore.loadFallbackTemplates(templatesMap, keywordResponses, mascotList, contextMap)
            } else {
                loadTemplatesFromFile(currentContext)
                MemoryManager.loadTemplatesFromFolder(this@ChatActivity, folderUri)
            }
            rebuildInvertedIndex()
            engine.computeTokenWeights()
            withContext(Dispatchers.Main) {
                // loadToolbarIcons теперь сама делает IO в фоне
                loadToolbarIcons()
                updateAutoComplete()
                addChatMessage(currentMascotName, getString(R.string.welcome_message))
                if (folderUri == null) {
                    showCustomToast(getString(R.string.toast_folder_not_selected))
                }
            }
        }
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            // start with system default, we'll switch per-text when speaking
            try {
                tts?.language = Locale.getDefault()
            } catch (_: Exception) { }
            tts?.setPitch(1.0f)
            tts?.setSpeechRate(1.0f)
        } else {
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
        lifecycleScope.launch(Dispatchers.IO) {
            folderUri?.let { loadTemplatesFromFile(currentContext) }
            try {
                MemoryManager.loadTemplatesFromFolder(this@ChatActivity, folderUri)
            } catch (e: Exception) {
            }
            rebuildInvertedIndex()
            engine.computeTokenWeights()
            withContext(Dispatchers.Main) {
                updateAutoComplete()
                idleCheckRunnable?.let {
                    dialogHandler.removeCallbacks(it)
                    dialogHandler.postDelayed(it, 500000)
                }
                loadToolbarIcons()
            }
        }
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
        // Переносим реальное чтение/декодирование иконки в IO-поток
        val uri = folderUri ?: run {
            tryLoadSendIconFromFolder(null)
            return
        }
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val dir = DocumentFile.fromTreeUri(this@ChatActivity, uri) ?: run {
                    tryLoadSendIconFromFolder(null)
                    return@launch
                }
                tryLoadSendIconFromFolder(dir)
            } catch (_: Exception) {
            }
        }
    }

    private fun tryLoadSendIconFromFolder(dir: DocumentFile?) {
        // Этот хелпер делает IO асинхронно (если dir != null).
        try {
            val target = envelopeInputButton ?: return
            if (dir == null) return
            lifecycleScope.launch(Dispatchers.IO) {
                try {
                    val file = dir.findFile("send.png") ?: return@launch
                    if (!file.exists()) return@launch
                    contentResolver.openInputStream(file.uri)?.use { ins ->
                        val bmp = BitmapFactory.decodeStream(ins)
                        withContext(Dispatchers.Main) {
                            target.setImageBitmap(bmp)
                        }
                    }
                } catch (_: Exception) {
                }
            }
        } catch (e: Exception) {
        }
    }

    private suspend fun matchInCurrent(
        qOrigRaw: String,
        qTokensFiltered: List<String>,
        qFiltered: String,
        qCanonical: String,
        qKeyForCount: String,
        templatesSnapshot: HashMap<String, MutableList<String>>,
        invertedIndexSnapshot: HashMap<String, MutableList<String>>,
        synonymsSnapshot: HashMap<String, String>,
        stopwordsSnapshot: HashSet<String>,
        keywordResponsesSnapshot: HashMap<String, MutableList<String>>,
        jaccardThreshold: Double
    ): String? {
        val subqueryResponses = mutableListOf<String>()
        val processedSubqueries = mutableSetOf<String>()

        templatesSnapshot[qCanonical]?.let { possible ->
            if (possible.isNotEmpty()) {
                subqueryResponses.add(possible.random())
                processedSubqueries.add(qCanonical)
            }
        }

        if (subqueryResponses.size < Engine.MAX_SUBQUERY_RESPONSES) {
            val tokens = if (qTokensFiltered.isNotEmpty()) qTokensFiltered else Engine.tokenizeStatic(qFiltered)

            try { engine.updateMemory(tokens) } catch (_: Exception) {}

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
            return subqueryResponses.joinToString(". ")
        }

        val qTokenSet = if (qTokensFiltered.isNotEmpty()) qTokensFiltered.toSet() else Engine.tokenizeStatic(qFiltered).toSet()
        for ((keyword, responses) in keywordResponsesSnapshot) {
            if (keyword.isBlank() || responses.isEmpty()) continue
            val kwTokens = keyword.split(" ").filter { it.isNotEmpty() }.toSet()
            if (qTokenSet.intersect(kwTokens).isNotEmpty()) {
                return responses.random()
            }
        }

        if (isContextLocked) {
            return null
        }

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

        var bestByJaccard: String? = null
        var bestJaccard = 0.0
        val qSet = qTokens.toSet()
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
                return possible.random()
            }
        }

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
                return possible.random()
            }
        }

        return null
    }

    // ---------------- NEW HELPER ----------------
    // Читает текстовый DocumentFile и, если файл в формате Secure (начинается с "v1:"),
    // пытается расшифровать его используя пароль из SharedPreferences (ключ PREF_KEY_ENCRYPTION_PASSWORD).
    // Возвращает plaintext или null при ошибке/отсутствии пароля.
    // Вызовы этого метода делаются из IO-потока.
    private fun readDocumentFileTextMaybeDecrypt(file: DocumentFile): String? {
        try {
            contentResolver.openInputStream(file.uri)?.use { ins ->
                val rawBytes = ins.readBytes()
                val rawText = try {
                    String(rawBytes, Charsets.UTF_8)
                } catch (_: Exception) {
                    return null
                }
                val trimmed = rawText.trim()
                if (trimmed.startsWith("v1:")) {
                    val prefs = PreferenceManager.getDefaultSharedPreferences(this)
                    val passwordStr = prefs.getString(PREF_KEY_ENCRYPTION_PASSWORD, null)
                    if (passwordStr.isNullOrEmpty()) {
                        // нет пароля — не можем расшифровать
                        return null
                    }
                    val passChars = passwordStr.toCharArray()
                    try {
                        return try {
                            Secure.decrypt(passChars, trimmed)
                        } catch (_: Exception) {
                            null
                        }
                    } finally {
                        Arrays.fill(passChars, '\u0000')
                    }
                } else {
                    return rawText
                }
            }
        } catch (_: Exception) {
            return null
        }
        return null
    }
    // -------------- END HELPER -----------------

    private fun processUserQuery(userInput: String) {
        if (userInput.startsWith("/")) {
            handleCommand(userInput.trim())
            return
        }

        val qOrigRaw = userInput.trim()
        val qOrig = Engine.normalizeText(qOrigRaw)
        val (qTokensFiltered, qFiltered) = engine.filterStopwordsAndMapSynonyms(qOrig)
        val qCanonical = if (qTokensFiltered.isNotEmpty()) canonicalKeyFromTokens(qTokensFiltered) else canonicalKeyFromTextStatic(qFiltered, synonymsMap, stopwords)
        val qKeyForCount = qCanonical

        if (qFiltered.isEmpty()) return

        queryCache[qKeyForCount]?.let { cachedResponse ->
            try { MemoryManager.processIncoming(this, qOrigRaw) } catch (_: Exception) {}
            addChatMessage(currentMascotName, cachedResponse)
            startIdleTimer()
            return
        }

        lastUserInputTime = System.currentTimeMillis()

        addChatMessage(getString(R.string.user_label), userInput)
        showTypingIndicator()

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

        val contextMapSnapshot = HashMap(contextMap)

        fun recordMemorySideEffect(inputText: String) {
            try {
                MemoryManager.processIncoming(this, inputText)
            } catch (e: Exception) {
            }
        }

        // ---- Запускаем обработку на Dispatcher.IO (вместо Default) —
        // все heavy IO (парсинг, чтение файлов, дешифрование) внутри теперь выполняется безопасно в IO-потоке.
        lifecycleScope.launch(Dispatchers.IO) {
            var fallbackFromLocked = false
            var attempt = 0
            var response: String? = null
            while (attempt < 2 && response == null) {
                val templatesSnapshot = HashMap(templatesMap)
                val invertedIndexSnapshot = HashMap<String, MutableList<String>>()
                for ((k, v) in invertedIndex) invertedIndexSnapshot[k] = ArrayList(v)
                val synonymsSnapshot = HashMap(synonymsMap)
                val stopwordsSnapshot = HashSet(stopwords)
                val keywordResponsesSnapshot = HashMap<String, MutableList<String>>()
                for ((k, v) in keywordResponses) keywordResponsesSnapshot[k] = ArrayList(v)
                val jaccardThreshold = engine.getJaccardThreshold(qFiltered)

                response = matchInCurrent(
                    qOrigRaw, qTokensFiltered, qFiltered, qCanonical, qKeyForCount,
                    templatesSnapshot, invertedIndexSnapshot, synonymsSnapshot, stopwordsSnapshot,
                    keywordResponsesSnapshot, jaccardThreshold
                )

                if (response != null) {
                    withContext(Dispatchers.Main) {
                        addChatMessage(currentMascotName, response!!)
                        recordMemorySideEffect(qOrigRaw)
                        startIdleTimer()
                        cacheResponse(qKeyForCount, response!!)
                    }
                    return@launch
                }

                if (isContextLocked) {
                    withContext(Dispatchers.Main) {
                        currentContext = "base.txt"
                        isContextLocked = false
                        // loadTemplatesFromFile выполняет IO внутри себя (и ожидается на IO-потоке)
                        // поэтому вызываем его с withContext(Dispatchers.IO) — но мы уже в IO, так что прямой вызов OK
                        loadTemplatesFromFile(currentContext)
                        rebuildInvertedIndex()
                        engine.computeTokenWeights()
                        updateAutoComplete()
                    }
                    fallbackFromLocked = true
                    attempt++
                } else {
                    break
                }
            }

            val detectedContext = ChatCore.detectContext(qFiltered, contextMapSnapshot, engine)

            if (detectedContext != null) {
                // Если обнаружен новый контекст — подгружаем его шаблоны (в IO), обновляем индекс/веса,
                // затем переключаем currentContext и UI на Main
                if (detectedContext != currentContext) {
                    // загрузка шаблонов и пересчёт делаем в IO
                    withContext(Dispatchers.IO) {
                        try {
                            loadTemplatesFromFile(detectedContext)
                            rebuildInvertedIndex()
                            engine.computeTokenWeights()
                        } catch (_: Exception) {
                            // не фатально — дальше попробуем парсить напрямую файл контекста
                        }
                    }
                    withContext(Dispatchers.Main) {
                        currentContext = detectedContext
                        updateAutoComplete()
                    }
                }

                // Парсим файл контекста (дополнительно) — это чтение/парсинг (IO)
                val (localTemplates, localKeywords) = withContext(Dispatchers.IO) {
                    ChatCore.parseTemplatesFromFile(
                        this@ChatActivity, folderUri, detectedContext, HashMap(synonymsMap), HashSet(stopwords)
                    )
                }

                localTemplates[qCanonical]?.let { possible ->
                    if (possible.isNotEmpty()) {
                        val resp = possible.random()
                        withContext(Dispatchers.Main) {
                            addChatMessage(currentMascotName, resp)
                            recordMemorySideEffect(qOrigRaw)
                            startIdleTimer()
                            cacheResponse(qKeyForCount, resp)
                        }
                        return@launch
                    }
                }

                for ((keyword, responses) in localKeywords) {
                    if (keyword.isBlank() || responses.isEmpty()) continue
                    val kwTokens = keyword.split(" ").filter { it.isNotEmpty() }.toSet()
                    val qTokenSetLocal = if (qTokensFiltered.isNotEmpty()) qTokensFiltered.toSet() else Engine.tokenizeStatic(qFiltered).toSet()
                    if (qTokenSetLocal.intersect(kwTokens).isNotEmpty()) {
                        val resp = responses.random()
                        withContext(Dispatchers.Main) {
                            addChatMessage(currentMascotName, resp)
                            recordMemorySideEffect(qOrigRaw)
                            startIdleTimer()
                            cacheResponse(qKeyForCount, resp)
                        }
                        return@launch
                    }
                }

                if (isContextLocked) {
                    // Skip fuzzy matching in locked context
                } else {
                    val localInverted = HashMap<String, MutableList<String>>()
                    for ((k, v) in localTemplates) {
                        val toks = if (k.isBlank()) emptyList<String>() else k.split(" ").filter { it.isNotEmpty() }
                        for (t in toks) {
                            val list = localInverted.getOrPut(t) { mutableListOf() }
                            if (!list.contains(k)) list.add(k)
                        }
                    }
                    val localCandidateCounts = HashMap<String, Int>()
                    val tokensLocal = if (qTokensFiltered.isNotEmpty()) qTokensFiltered else Engine.tokenizeStatic(qFiltered)
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
                        val keyTokens = Engine.filterStopwordsAndMapSynonymsStatic(key, HashMap(synonymsMap), HashSet(stopwords)).first.toSet()
                        if (keyTokens.isEmpty()) continue
                        val weightedJ = engine.weightedJaccard(qSetLocal, keyTokens)
                        if (weightedJ > bestLocalJ) {
                            bestLocalJ = weightedJ
                            bestLocal = key
                        }
                    }
                    if (bestLocal != null && bestLocalJ >= engine.getJaccardThreshold(qFiltered)) {
                        val possible = localTemplates[bestLocal]
                        if (!possible.isNullOrEmpty()) {
                            val resp = possible.random()
                            withContext(Dispatchers.Main) {
                                addChatMessage(currentMascotName, resp)
                                recordMemorySideEffect(qOrigRaw)
                                startIdleTimer()
                                cacheResponse(qKeyForCount, resp)
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
                            val resp = possible.random()
                            withContext(Dispatchers.Main) {
                                addChatMessage(currentMascotName, resp)
                                recordMemorySideEffect(qOrigRaw)
                                startIdleTimer()
                                cacheResponse(qKeyForCount, resp)
                            }
                            return@launch
                        }
                    }
                }

                val coreResult = withContext(Dispatchers.IO) {
                    ChatCore.searchInCoreFiles(
                        this@ChatActivity, folderUri, qFiltered, if (qTokensFiltered.isNotEmpty()) qTokensFiltered else Engine.tokenizeStatic(qFiltered), engine,
                        HashMap(synonymsMap), HashSet(stopwords), engine.getJaccardThreshold(qFiltered)
                    )
                }
                if (coreResult != null) {
                    withContext(Dispatchers.Main) {
                        addChatMessage(currentMascotName, coreResult)
                        recordMemorySideEffect(qOrigRaw)
                        startIdleTimer()
                        cacheResponse(qKeyForCount, coreResult)
                    }
                    return@launch
                }

                val memBeforeDummy = try { MemoryManager.processIncoming(this@ChatActivity, qOrigRaw) } catch (_: Exception) { null }
                if (!memBeforeDummy.isNullOrBlank()) {
                    withContext(Dispatchers.Main) {
                        addChatMessage(currentMascotName, memBeforeDummy)
                        startIdleTimer()
                        cacheResponse(qKeyForCount, memBeforeDummy)
                    }
                    return@launch
                }

                val dummy = ChatCore.getDummyResponse(qOrig)
                withContext(Dispatchers.Main) {
                    addChatMessage(currentMascotName, dummy)
                    recordMemorySideEffect(qOrigRaw)
                    startIdleTimer()
                    cacheResponse(qKeyForCount, dummy)
                }
                return@launch
            }

            val coreResult = withContext(Dispatchers.IO) {
                ChatCore.searchInCoreFiles(
                    this@ChatActivity, folderUri, qFiltered, if (qTokensFiltered.isNotEmpty()) qTokensFiltered else Engine.tokenizeStatic(qFiltered), engine,
                    HashMap(synonymsMap), HashSet(stopwords), engine.getJaccardThreshold(qFiltered)
                )
            }
            if (coreResult != null) {
                withContext(Dispatchers.Main) {
                    addChatMessage(currentMascotName, coreResult)
                    recordMemorySideEffect(qOrigRaw)
                    startIdleTimer()
                    cacheResponse(qKeyForCount, coreResult)
                }
                return@launch
            }

            val memResp = try { MemoryManager.processIncoming(this@ChatActivity, qOrigRaw) } catch (_: Exception) { null }
            if (!memResp.isNullOrBlank()) {
                withContext(Dispatchers.Main) {
                    addChatMessage(currentMascotName, memResp)
                    startIdleTimer()
                    cacheResponse(qKeyForCount, memResp)
                }
                return@launch
            }

            val dummy = ChatCore.getDummyResponse(qOrig)
            withContext(Dispatchers.Main) {
                addChatMessage(currentMascotName, dummy)
                recordMemorySideEffect(qOrigRaw)
                startIdleTimer()
                cacheResponse(qKeyForCount, dummy)
            }
        }
    }

    private fun cacheResponse(qKey: String, response: String) {
        queryCache[qKey] = response
    }

    private fun handleCommand(cmdRaw: String) {
        val cmd = cmdRaw.trim().lowercase(Locale.ROOT)
        when {
            cmd == "/reload" -> {
                addChatMessage(currentMascotName, getString(R.string.reloading_templates))
                lifecycleScope.launch(Dispatchers.IO) {
                    loadTemplatesFromFile(currentContext)
                    rebuildInvertedIndex()
                    engine.computeTokenWeights()
                    withContext(Dispatchers.Main) {
                        updateAutoComplete()
                        addChatMessage(currentMascotName, getString(R.string.templates_reloaded))
                    }
                }
            }
            cmd == "/stats" -> {
                val templatesCount = templatesMap.size
                val keywordsCount = keywordResponses.size
                val msg = getString(R.string.stats_template, currentContext, templatesCount, keywordsCount)
                addChatMessage(currentMascotName, msg)
            }
            cmd == "/clear" || cmd == "очисти чат" -> {
                clearChat()
            }
            else -> {
                addChatMessage(currentMascotName, getString(R.string.unknown_command, cmdRaw))
            }
        }
    }

    private fun showTypingIndicator() {
        lifecycleScope.launch {
            withContext(Dispatchers.Main) {
                val existing = messagesContainer.findViewWithTag<View>("typingView")
                if (existing != null) return@withContext
                val typingView = TextView(this@ChatActivity).apply {
                    text = getString(R.string.typing)
                    textSize = 14f
                    setTextColor(getColor(R.color.neon_cyan))
                    setBackgroundColor(0x800A0A0A.toInt())
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
                    lifecycleScope.launch {
                        withContext(Dispatchers.Main) {
                            messagesContainer.findViewWithTag<View>("typingView")?.let { messagesContainer.removeView(it) }
                        }
                    }
                }, (1000..3000).random().toLong())
            }
        }
    }

    private fun clearChat() {
        lifecycleScope.launch {
            withContext(Dispatchers.Main) {
                messagesContainer.removeAllViews()
                queryTimestamps.clear()
                queryCache.clear()
                currentContext = "base.txt"
                isContextLocked = false
                loadTemplatesFromFile(currentContext)
                rebuildInvertedIndex()
                engine.computeTokenWeights()
                updateAutoComplete()
                addChatMessage(currentMascotName, getString(R.string.chat_cleared))
            }
        }
    }

    private fun rebuildInvertedIndex() {
        invertedIndex.clear()
        val tempIndex = engine.rebuildInvertedIndex()
        invertedIndex.putAll(tempIndex)
    }

    // Загружает Bitmap аватара для заданного sender в блокирующем режиме.
    // Если вызывается на фоновой нити — выполняет I/O прямо (не блокирует UI).
    // Если вызывается на main — использует runBlocking(Dispatchers.IO) чтобы выполнить I/O в IO-пуле и дождаться результата.
    private fun loadAvatarBitmapBlocking(sender: String): android.graphics.Bitmap? {
        fun loadImpl(): android.graphics.Bitmap? {
            val uriLocal = folderUri ?: return null
            try {
                val dir = DocumentFile.fromTreeUri(this, uriLocal) ?: return null
                val s = sender.lowercase(Locale.ROOT)
                val candidates = listOf("${s}_icon.png", "${s}_avatar.png", "${s}.png", currentMascotIcon)
                for (name in candidates) {
                    val f = dir.findFile(name) ?: continue
                    if (f.exists()) {
                        contentResolver.openInputStream(f.uri)?.use { ins ->
                            return BitmapFactory.decodeStream(ins)
                        }
                    }
                }
            } catch (_: Exception) {
            }
            return null
        }

        return if (Looper.myLooper() == Looper.getMainLooper()) {
            try {
                runBlocking(Dispatchers.IO) {
                    loadImpl()
                }
            } catch (_: Exception) {
                null
            }
        } else {
            try {
                loadImpl()
            } catch (_: Exception) {
                null
            }
        }
    }

    // Основная точка входа: гарантирует, что аватарка загружена (блокирующе при необходимости),
    // затем синхронно (на main) вставляет UI-элемент.
    private fun addChatMessage(sender: String, text: String) {
        val isUser = sender.equals(getString(R.string.user_label), ignoreCase = true)

        // Для сообщений не от пользователя — заранее загрузим bitmap аватарки.
        val avatarBitmap: android.graphics.Bitmap? = if (!isUser) {
            loadAvatarBitmapBlocking(sender)
        } else {
            null
        }

        if (Looper.myLooper() == Looper.getMainLooper()) {
            addChatMessageOnMain(sender, text, avatarBitmap)
        } else {
            runOnUiThread { addChatMessageOnMain(sender, text, avatarBitmap) }
        }
    }

    // UI-логика — всегда выполняется на main; получает заранее загруженный avatarBitmap (или null).
    private fun addChatMessageOnMain(sender: String, text: String, avatarBitmap: android.graphics.Bitmap?) {
        val row = LinearLayout(this@ChatActivity).apply {
            orientation = LinearLayout.HORIZONTAL
            val pad = dpToPx(6)
            setPadding(pad, pad / 2, pad, pad / 2)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }

        val isUser = sender.equals(getString(R.string.user_label), ignoreCase = true)
        if (isUser) {
            val bubble = createMessageBubble(sender, text, isUser)
            val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            lp.gravity = Gravity.END
            lp.marginStart = dpToPx(48)
            row.addView(spaceView(), LinearLayout.LayoutParams(0, 0, 1f))
            row.addView(bubble, lp)
        } else {
            val avatarView = ImageView(this@ChatActivity).apply {
                val size = dpToPx(64)
                layoutParams = LinearLayout.LayoutParams(size, size)
                scaleType = ImageView.ScaleType.CENTER_CROP
                adjustViewBounds = true
                if (avatarBitmap != null) {
                    setImageBitmap(avatarBitmap)
                } else {
                    // Резервная быстрая попытка (на крайний случай).
                    try { loadAvatarInto(this, sender) } catch (_: Exception) {}
                }

                setOnClickListener { view ->
                    view.isEnabled = false
                    val scaleX = ObjectAnimator.ofFloat(view, "scaleX", 1f, 1.08f, 1f)
                    val scaleY = ObjectAnimator.ofFloat(view, "scaleY", 1f, 1.08f, 1f)
                    scaleX.duration = 250
                    scaleY.duration = 250
                    scaleX.start()
                    scaleY.start()
                    lifecycleScope.launch {
                        delay(260)
                        loadAndSendOuchMessage(sender)
                        runOnUiThread { view.isEnabled = true }
                    }
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

    private fun speakText(text: String) {
        setTTSLanguageForText(text)
        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "message_${System.currentTimeMillis()}")
    }

    private suspend fun loadAndSendOuchMessage(mascot: String) {
        withContext(Dispatchers.IO) {
            val uri = folderUri ?: return@withContext
            try {
                val dir = DocumentFile.fromTreeUri(this@ChatActivity, uri) ?: return@withContext
                val mascotFilename = "${mascot.lowercase(Locale.ROOT)}.txt"
                val mascotOuch = dir.findFile(mascotFilename) ?: dir.findFile("ouch.txt")
                if (mascotOuch != null && mascotOuch.exists()) {
                    // <<< CHANGED: read via helper (may decrypt)
                    val allText = readDocumentFileTextMaybeDecrypt(mascotOuch)
                    if (allText != null) {
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
                    showCustomToast(getString(R.string.error_loading_ouch, e.message ?: ""))
                }
            }
        }
    }

    private fun playNotificationSound() {
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
                    player.prepareAsync()
                    player.setOnPreparedListener { it.start() }
                    player.setOnCompletionListener { it.reset(); it.release() }
                    player.setOnErrorListener { mp, _, _ -> try { mp.reset(); mp.release() } catch (_: Exception) {}; true }
                } catch (e: Exception) {
                    try { afd.close() } catch (_: Exception) {}
                    try { player.reset(); player.release() } catch (_: Exception) {}
                }
            } catch (e: Exception) {
            }
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
                Color.parseColor("#00FFFF")
            } else {
                safeParseColorOrDefault(currentThemeColor, Color.parseColor("#00FFFF"))
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
        // Загружаем аватар асинхронно в IO-потоке, чтобы не блокировать UI
        target.setImageResource(android.R.color.transparent) // placeholder / clear
        val uriLocal = folderUri ?: return
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val dir = DocumentFile.fromTreeUri(this@ChatActivity, uriLocal) ?: return@launch
                val s = sender.lowercase(Locale.ROOT)
                val candidates = listOf("${s}_icon.png", "${s}_avatar.png", "${s}.png", currentMascotIcon)
                for (name in candidates) {
                    val f = dir.findFile(name) ?: continue
                    if (f.exists()) {
                        contentResolver.openInputStream(f.uri)?.use { ins ->
                            val bmp = BitmapFactory.decodeStream(ins)
                            withContext(Dispatchers.Main) {
                                try { target.setImageBitmap(bmp) } catch (_: Exception) {}
                            }
                            return@launch
                        }
                    }
                }
            } catch (_: Exception) {
            }
            withContext(Dispatchers.Main) {
                try { target.setImageResource(android.R.color.transparent) } catch (_: Exception) {}
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
            currentThemeColor = "#00FFFF"
            currentThemeBackground = "#0A0A0A"
            isContextLocked = false

            ChatCore.loadSynonymsAndStopwords(this@ChatActivity, folderUri, synonymsMap, stopwords)

            if (folderUri == null) {
                ChatCore.loadFallbackTemplates(templatesMap, keywordResponses, mascotList, contextMap)
                rebuildInvertedIndex()
                engine.computeTokenWeights()
                withContext(Dispatchers.Main) {
                    updateUI(currentMascotName, currentMascotIcon, currentThemeColor, currentThemeBackground)
                }
                return@withContext
            }
            try {
                val dir = DocumentFile.fromTreeUri(this@ChatActivity, folderUri!!) ?: run {
                    ChatCore.loadFallbackTemplates(templatesMap, keywordResponses, mascotList, contextMap)
                    rebuildInvertedIndex()
                    engine.computeTokenWeights()
                    withContext(Dispatchers.Main) {
                        updateUI(currentMascotName, currentMascotIcon, currentThemeColor, currentThemeBackground)
                    }
                    return@withContext
                }
                val file = dir.findFile(filename)
                if (file == null || !file.exists()) {
                    ChatCore.loadFallbackTemplates(templatesMap, keywordResponses, mascotList, contextMap)
                    rebuildInvertedIndex()
                    engine.computeTokenWeights()
                    withContext(Dispatchers.Main) {
                        updateUI(currentMascotName, currentMascotIcon, currentThemeColor, currentThemeBackground)
                    }
                    return@withContext
                }
                var firstNonEmptyLineChecked = false

                // <<< CHANGED: читаем файл целиком (возможно зашифрованный), затем парсим построчно
                val fileContent = readDocumentFileTextMaybeDecrypt(file)
                if (fileContent == null) {
                    // не удалось прочитать/расшифровать -> fallback аналогично отсутствию файла
                    ChatCore.loadFallbackTemplates(templatesMap, keywordResponses, mascotList, contextMap)
                    rebuildInvertedIndex()
                    engine.computeTokenWeights()
                    withContext(Dispatchers.Main) {
                        updateUI(currentMascotName, currentMascotIcon, currentThemeColor, currentThemeBackground)
                    }
                    return@withContext
                }
                fileContent.lines().forEach { raw ->
                    val l = raw.trim()
                    if (l.isEmpty()) return@forEach
                    if (!firstNonEmptyLineChecked) {
                        firstNonEmptyLineChecked = true
                        if (l.equals("#CONTEXT", ignoreCase = true)) {
                            isContextLocked = true
                            return@forEach
                        }
                    }
                    if (filename == "base.txt" && l.startsWith(":" ) && l.endsWith(":")) {
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
                        return@forEach
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
                        return@forEach
                    }
                    if (!l.contains("=")) return@forEach
                    val parts = l.split("=", limit = 2)
                    if (parts.size == 2) {
                        val triggerRaw = parts[0].trim()
                        val triggerTokens = Engine.filterStopwordsAndMapSynonymsStatic(normalizeForProcessing(triggerRaw), synonymsMap, stopwords).first
                        val triggerCanonical = triggerTokens.sorted().joinToString(" ")
                        val responses = parts[1].split("|")
                        val responseList = responses.mapNotNull { it.trim().takeIf { s -> s.isNotEmpty() } }.toMutableList()
                        if (triggerCanonical.isNotEmpty() && responseList.isNotEmpty()) templatesMap[triggerCanonical] = responseList
                    }
                }

                val metadataFilename = filename.replace(".txt", "_metadata.txt")
                val metadataFile = dir.findFile(metadataFilename)
                if (metadataFile != null && metadataFile.exists()) {
                    // <<< CHANGED: аналогично читаем метаданные через helper (возможно зашифрованы)
                    val metadataContent = readDocumentFileTextMaybeDecrypt(metadataFile)
                    if (metadataContent != null) {
                        metadataContent.lines().forEach { raw ->
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
                withContext(Dispatchers.Main) {
                    updateUI(currentMascotName, currentMascotIcon, currentThemeColor, currentThemeBackground)
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    showCustomToast(getString(R.string.error_reading_file, e.message ?: ""))
                }
                ChatCore.loadFallbackTemplates(templatesMap, keywordResponses, mascotList, contextMap)
                rebuildInvertedIndex()
                engine.computeTokenWeights()
                withContext(Dispatchers.Main) {
                    updateUI(currentMascotName, currentMascotIcon, currentThemeColor, currentThemeBackground)
                }
            }
        }
    }

    private fun updateAutoComplete() {
        // Autocomplete / dropdown suggestions disabled per request.
        // Ensure no adapter is attached and the threshold is set high to prevent suggestions.
        adapter = null
        runOnUiThread {
            try {
                queryInput.setAdapter(null)
                queryInput.threshold = Int.MAX_VALUE
                // Make dropdown invisible / transparent if any attempt is made to show it.
                queryInput.setDropDownBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
                // Also disable long-click suggestion popup (if any)
                queryInput.isLongClickable = true
            } catch (_: Exception) { }
        }
    }

    private suspend fun loadMascotMetadata(mascotName: String) {
        withContext(Dispatchers.IO) {
            if (folderUri == null) return@withContext
            val metadataFilename = "${mascotName.lowercase(Locale.ROOT)}_metadata.txt"
            val dir = DocumentFile.fromTreeUri(this@ChatActivity, folderUri!!) ?: return@withContext
            val metadataFile = dir.findFile(metadataFilename)
            if (metadataFile != null && metadataFile.exists()) {
                try {
                    // <<< CHANGED: читаем возможный зашифрованный metadata файл
                    val content = readDocumentFileTextMaybeDecrypt(metadataFile) ?: return@withContext
                    content.lines().forEach { raw ->
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
                } catch (e: Exception) {
                    withContext(Dispatchers.Main) {
                        showCustomToast(getString(R.string.error_loading_mascot_metadata, e.message ?: ""))
                    }
                }
            }
        }
    }

    private fun updateUI(mascotName: String, mascotIcon: String, themeColor: String, themeBackground: String) {
        lifecycleScope.launch {
            withContext(Dispatchers.Main) {
                title = getString(R.string.app_title_prefix, mascotName)
                try {
                    messagesContainer.setBackgroundColor(Color.parseColor(themeBackground))
                } catch (e: Exception) {
                }
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
            Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
        }
    }

    private fun startIdleTimer() {
        lastUserInputTime = System.currentTimeMillis()
        idleCheckRunnable?.let {
            dialogHandler.removeCallbacks(it)
            dialogHandler.postDelayed(it, 500000)
        }
    }

    // ---------- STARTUP OVERLAY IMPLEMENTATION ----------
    private fun showStartupOverlay() {
        try {
            // Try to find overlay in inflated layout first (preferred)
            val existingOverlay = try { findViewById<FrameLayout>(R.id.startup_overlay) } catch (_: Exception) { null }
            val root = findViewById<ViewGroup>(android.R.id.content)

            if (existingOverlay != null) {
                startupOverlay = existingOverlay
                // ensure overlay is on top and intercepts touches
                startupOverlay?.bringToFront()
                startupOverlay?.isClickable = true
                startupOverlay?.isFocusable = true
                startupOverlay?.setOnTouchListener { _, _ -> true }
                // ensure visible and alpha reset
                startupOverlay?.alpha = 1f
                startupOverlay?.visibility = View.VISIBLE
            } else {
                // No overlay in layout — create programmatically and add to root
                val overlay = FrameLayout(this).apply {
                    layoutParams = FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.MATCH_PARENT,
                        FrameLayout.LayoutParams.MATCH_PARENT
                    )
                    setBackgroundColor(Color.parseColor("#0A0A0A"))
                    isClickable = true
                    isFocusable = true
                    setOnTouchListener { _, _ -> true }
                }

                val tv = TextView(this).apply {
                    text = "initialization..." // hardcoded per request
                    textSize = 20f
                    typeface = Typeface.DEFAULT_BOLD
                    gravity = Gravity.CENTER
                    try {
                        setTextColor(getColor(R.color.neon_cyan))
                    } catch (_: Exception) {
                        setTextColor(Color.parseColor("#00E5FF"))
                    }
                    layoutParams = FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.WRAP_CONTENT,
                        FrameLayout.LayoutParams.WRAP_CONTENT,
                        Gravity.CENTER
                    )
                }

                overlay.addView(tv)
                startupOverlay = overlay
                root.addView(overlay)
            }

            // Schedule hide after 5 seconds on the main dispatcher
            lifecycleScope.launch(Dispatchers.Main) {
                try {
                    delay(5000)
                    // Fade out and then hide/remove
                    startupOverlay?.animate()?.alpha(0f)?.setDuration(300)?.withEndAction {
                        try {
                            // If overlay was defined in layout, just hide it (so layout remains stable)
                            val wasFromLayout = try { findViewById<FrameLayout>(R.id.startup_overlay) != null } catch (_: Exception) { false }
                            if (wasFromLayout) {
                                startupOverlay?.visibility = View.GONE
                                startupOverlay?.alpha = 1f // reset for potential future use
                            } else {
                                // programmatically added — remove from root
                                try {
                                    (startupOverlay?.parent as? ViewGroup)?.removeView(startupOverlay)
                                } catch (_: Exception) {}
                            }
                        } catch (_: Exception) {
                        } finally {
                            startupOverlay = null
                        }
                    }
                } catch (_: Exception) {
                    // On any error, ensure overlay is hidden so UI isn't blocked
                    try {
                        startupOverlay?.visibility = View.GONE
                    } catch (_: Exception) {}
                    startupOverlay = null
                }
            }
        } catch (e: Exception) {
            // If anything fails, ensure app keeps working
            try {
                startupOverlay?.visibility = View.GONE
            } catch (_: Exception) {}
            startupOverlay = null
        }
    }
}
    // ---------- END STARTUP OVERLAY IMPLEMENTATION ----------
