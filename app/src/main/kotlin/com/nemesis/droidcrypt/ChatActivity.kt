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
import java.io.InputStreamReader
import java.text.Normalizer
import java.util.*
import kotlin.collections.ArrayList
import kotlin.collections.HashMap
import kotlin.math.roundToInt
import kotlin.math.abs

class ChatActivity : AppCompatActivity(), TextToSpeech.OnInitListener {

    companion object {
        private const val PREF_KEY_FOLDER_URI = "pref_folder_uri"
        private const val PREF_KEY_DISABLE_SCREENSHOTS = "pref_disable_screenshots"
    }

    private lateinit var engine: Engine

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

    private var tts: TextToSpeech? = null

    // single mascot model
    private val templatesMap = HashMap<String, MutableList<String>>()
    private val contextMap = HashMap<String, String>()
    private val keywordResponses = HashMap<String, MutableList<String>>()
    private val invertedIndex = HashMap<String, MutableList<String>>()
    private val synonymsMap = HashMap<String, String>()
    private val stopwords = HashSet<String>()

    private var currentMascotName = "Racky"
    private var currentMascotIcon = "raccoon_icon.png"
    private var currentThemeColor = "#00FFFF"
    private var currentThemeBackground = "#0A0A0A"
    private var currentContext = "base.txt"
    private var isContextLocked = false

    private val dialogHandler = Handler(Looper.getMainLooper())
    private var lastUserInputTime = System.currentTimeMillis()
    private val random = Random()
    private val queryTimestamps = HashMap<String, MutableList<Long>>()
    private var lastSendTime = 0L
    private val queryCache = object : LinkedHashMap<String, String>(Engine.MAX_CACHE_SIZE, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?): Boolean {
            return size > Engine.MAX_CACHE_SIZE
        }
    }

    // helper canonical key builder
    private fun canonicalKeyFromTokens(tokens: List<String>): String = tokens.sorted().joinToString(" ")
    private fun canonicalKeyFromTextStatic(text: String, synonyms: Map<String, String>, stopwords: Set<String>): String {
        val normalized = normalizeForProcessing(text)
        val toks = Engine.filterStopwordsAndMapSynonymsStatic(normalized, synonyms, stopwords).first
        return canonicalKeyFromTokens(toks)
    }

    private fun normalizeForProcessing(s: String): String {
        val norm = Normalizer.normalize(s, Normalizer.Form.NFC)
        return norm.replace(Regex("\\s+"), " ").trim()
    }

    // language hint by script
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

        ChatCore.loadSynonymsAndStopwords(this, folderUri, synonymsMap, stopwords)
        engine = Engine(templatesMap, synonymsMap, stopwords)

        try {
            MemoryManager.init(this)
            MemoryManager.loadTemplatesFromFolder(this, folderUri)
        } catch (_: Exception) {}

        try {
            val prefs = getSharedPreferences("RacoonTalkPrefs", MODE_PRIVATE)
            val disable = prefs.getBoolean("disableScreenshots", false)
            if (disable) window.addFlags(WindowManager.LayoutParams.FLAG_SECURE) else window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        } catch (_: Exception) {}

        loadToolbarIcons()
        setupIconTouchEffect(btnLock)
        setupIconTouchEffect(btnTrash)
        setupIconTouchEffect(btnEnvelopeTop)
        setupIconTouchEffect(btnSettings)
        setupIconTouchEffect(envelopeInputButton)

        btnLock?.setOnClickListener { finish() }
        btnTrash?.setOnClickListener { clearChat() }
        btnSettings?.setOnClickListener { startActivity(Intent(this, SettingsActivity::class.java)) }
        btnEnvelopeTop?.setOnClickListener { startActivity(Intent(this, SetupActivity::class.java)) }

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

        // Load templates once at startup
        loadTemplatesFromFile(currentContext)
        rebuildInvertedIndex()
        engine.computeTokenWeights()
        updateAutoComplete()
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            try { tts?.language = Locale.getDefault() } catch (_: Exception) {}
            tts?.setPitch(1.0f)
            tts?.setSpeechRate(1.0f)
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
        try { MemoryManager.loadTemplatesFromFolder(this, folderUri) } catch (_: Exception) {}
        rebuildInvertedIndex()
        engine.computeTokenWeights()
        updateAutoComplete()
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
                } catch (_: Exception) {}
            }
            tryLoadToImageButton("lock.png", btnLock)
            tryLoadToImageButton("trash.png", btnTrash)
            tryLoadToImageButton("envelope.png", btnEnvelopeTop)
            tryLoadToImageButton("settings.png", btnSettings)
            tryLoadToImageButton("send.png", envelopeInputButton)
        } catch (_: Exception) {}
    }

    // Simplified processing: use ChatCore.findBestResponse (it handles core/context/memory)
    private fun processUserQuery(userInput: String) {
        if (userInput.startsWith("/")) {
            handleCommand(userInput.trim())
            return
        }
        val qOrigRaw = userInput.trim()
        if (qOrigRaw.isEmpty()) return

        val qOrig = Engine.normalizeText(qOrigRaw)
        val (qTokensFiltered, qFiltered) = engine.filterStopwordsAndMapSynonyms(qOrig)
        val qCanonical = if (qTokensFiltered.isNotEmpty()) canonicalKeyFromTokens(qTokensFiltered) else canonicalKeyFromTextStatic(qFiltered, synonymsMap, stopwords)
        val qKeyForCount = qCanonical.ifBlank { qFiltered }

        // spam protection / simple caching
        val now = System.currentTimeMillis()
        val timestamps = queryTimestamps.getOrPut(qKeyForCount) { mutableListOf() }
        timestamps.add(now)
        timestamps.removeAll { it < now - Engine.SPAM_WINDOW_MS }
        if (timestamps.size >= 6) {
            val spamResp = ChatCore.getAntiSpamResponse()
            addChatMessage(currentMascotName, spamResp)
            return
        }

        queryCache[qKeyForCount]?.let { cachedResponse ->
            try { MemoryManager.processIncoming(this, qOrigRaw) } catch (_: Exception) {}
            addChatMessage(currentMascotName, cachedResponse)
            return
        }

        lastUserInputTime = System.currentTimeMillis()
        addChatMessage(getString(R.string.user_label), userInput)
        showTypingIndicator()

        lifecycleScope.launch(Dispatchers.Default) {
            val response = try {
                ChatCore.findBestResponse(this@ChatActivity, folderUri, engine, qOrigRaw)
            } catch (e: Exception) {
                // if something goes wrong, log via ChatCore (it copies logs to clipboard)
                ChatCore.init(applicationContext) // ensure init
                e.printStackTrace()
                ChatCore.getDummyResponse(this@ChatActivity, qOrigRaw)
            }

            withContext(Dispatchers.Main) {
                addChatMessage(currentMascotName, response)
                try { MemoryManager.processIncoming(this@ChatActivity, qOrigRaw) } catch (_: Exception) {}
                cacheResponse(qKeyForCount, response)
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
                loadTemplatesFromFile(currentContext)
                rebuildInvertedIndex()
                engine.computeTokenWeights()
                updateAutoComplete()
                addChatMessage(currentMascotName, getString(R.string.templates_reloaded))
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
                    setTextColor(Color.parseColor("#00FFFF"))
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
                }, 800L)
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

    private fun addChatMessage(sender: String, text: String) {
        lifecycleScope.launch {
            withContext(Dispatchers.Main) {
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
                                // keep ouch behaviour but limited to raccoon/ouch.txt if present
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
    }

    private fun speakText(text: String) {
        setTTSLanguageForText(text)
        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "message_${System.currentTimeMillis()}")
    }

    private fun loadAndSendOuchMessage(mascot: String) {
        val uri = folderUri ?: return
        try {
            val dir = DocumentFile.fromTreeUri(this, uri) ?: return
            // since single mascot, prefer raccoon or ouch.txt
            val mascotFilename = "${mascot.lowercase(Locale.ROOT)}.txt"
            val mascotOuch = dir.findFile(mascotFilename) ?: dir.findFile("ouch.txt") ?: dir.findFile("raccoon.txt")
            if (mascotOuch != null && mascotOuch.exists()) {
                contentResolver.openInputStream(mascotOuch.uri)?.use { ins ->
                    InputStreamReader(ins, Charsets.UTF_8).buffered().use { reader ->
                        val allText = reader.readText()
                        val responses = allText.split("|").map { it.trim() }.filter { it.isNotEmpty() }
                        if (responses.isNotEmpty()) {
                            val randomResponse = responses.random()
                            addChatMessage(mascot, randomResponse)
                        }
                    }
                }
            }
        } catch (e: Exception) {
            showCustomToast(getString(R.string.error_loading_ouch, e.message ?: ""))
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
        val uri = folderUri ?: return
        try {
            val dir = DocumentFile.fromTreeUri(this, uri) ?: return
            val s = sender.lowercase(Locale.ROOT)
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
        } catch (_: Exception) {}
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

    // Loading templates: simplified; metadata for mascots removed
    private fun loadTemplatesFromFile(filename: String) {
        templatesMap.clear()
        keywordResponses.clear()
        if (filename == "base.txt") {
            contextMap.clear()
        }
        currentMascotName = "Racky"
        currentMascotIcon = "raccoon_icon.png"
        currentThemeColor = "#00FFFF"
        currentThemeBackground = "#0A0A0A"
        isContextLocked = false

        ChatCore.loadSynonymsAndStopwords(this, folderUri, synonymsMap, stopwords)

        if (folderUri == null) {
            ChatCore.loadFallbackTemplates(templatesMap, keywordResponses, mutableListOf(), contextMap)
            rebuildInvertedIndex()
            engine.computeTokenWeights()
            updateUI(currentMascotName, currentMascotIcon, currentThemeColor, currentThemeBackground)
            return
        }

        try {
            val dir = DocumentFile.fromTreeUri(this, folderUri!!) ?: run {
                ChatCore.loadFallbackTemplates(templatesMap, keywordResponses, mutableListOf(), contextMap)
                rebuildInvertedIndex()
                engine.computeTokenWeights()
                updateUI(currentMascotName, currentMascotIcon, currentThemeColor, currentThemeBackground)
                return
            }
            val file = dir.findFile(filename)
            if (file == null || !file.exists()) {
                ChatCore.loadFallbackTemplates(templatesMap, keywordResponses, mutableListOf(), contextMap)
                rebuildInvertedIndex()
                engine.computeTokenWeights()
                updateUI(currentMascotName, currentMascotIcon, currentThemeColor, currentThemeBackground)
                return
            }

            contentResolver.openInputStream(file.uri)?.use { ins ->
                InputStreamReader(ins, Charsets.UTF_8).buffered().useLines { lines ->
                    lines.forEach { raw ->
                        val l = raw.replace("\uFEFF", "").trim()
                        if (l.isEmpty()) return@forEach
                        // special base context mapping format :key=file.txt:
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
                }
            }

            // metadata files removed: do NOT read mascot metadata — single mascot only

            rebuildInvertedIndex()
            engine.computeTokenWeights()
            updateUI(currentMascotName, currentMascotIcon, currentThemeColor, currentThemeBackground)
        } catch (e: Exception) {
            showCustomToast(getString(R.string.error_reading_file, e.message ?: ""))
            ChatCore.loadFallbackTemplates(templatesMap, keywordResponses, mutableListOf(), contextMap)
            rebuildInvertedIndex()
            engine.computeTokenWeights()
            updateUI(currentMascotName, currentMascotIcon, currentThemeColor, currentThemeBackground)
        }
    }

    private fun updateAutoComplete() {
        val suggestions = mutableListOf<String>()
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

    private fun updateUI(mascotName: String, mascotIcon: String, themeColor: String, themeBackground: String) {
        lifecycleScope.launch {
            withContext(Dispatchers.Main) {
                title = getString(R.string.app_title_prefix, mascotName)
                try {
                    messagesContainer.setBackgroundColor(Color.parseColor(themeBackground))
                } catch (_: Exception) {}
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
        // idle auto-messages intentionally removed in simplified single-mascot version
        lastUserInputTime = System.currentTimeMillis()
    }
}
