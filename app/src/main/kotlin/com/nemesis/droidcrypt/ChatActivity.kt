package com.nemesis.droidcrypt

import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.media.MediaPlayer
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.widget.AutoCompleteTextView
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.PopupMenu
import androidx.core.graphics.ColorUtils
import androidx.core.view.ViewCompat
import androidx.core.view.isVisible
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.lifecycleScope
import androidx.preference.PreferenceManager
import com.google.android.material.progressindicator.LinearProgressIndicator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.*
import kotlin.collections.ArrayList
import kotlin.collections.HashMap
import kotlin.collections.set
import kotlin.math.roundToInt

// Local project classes (assumed present)
import com.nemesis.droidcrypt.Engine
import com.nemesis.droidcrypt.ChatCore
import com.nemesis.droidcrypt.MemoryManager
import com.nemesis.droidcrypt.R

class ChatActivity : AppCompatActivity(), TextToSpeech.OnInitListener {

    companion object {
        private const val PREF_KEY_FOLDER_URI = "pref_folder_uri"
        private const val PREF_KEY_DISABLE_SCREENSHOTS = "pref_disable_screenshots"
    }

    // Core
    private lateinit var engine: Engine

    // UI (some views optional because layout is simplified)
    private var folderUri: Uri? = null
    private var loadingIndicator: LinearProgressIndicator? = null
    // inputContainer (TextInputLayout) removed in simplified XML -> keep nullable for compatibility
    // private var inputContainer: TextInputLayout? = null
    private lateinit var queryInput: AutoCompleteTextView
    private var envelopeInputButton: ImageButton? = null
    private var btnLock: ImageButton? = null
    private var btnTrash: ImageButton? = null
    private var btnEnvelopeTop: ImageButton? = null
    private var btnSettings: ImageButton? = null
    private var btnOverflow: ImageButton? = null
    // topBar exists in xml but minimal; no MaterialToolbar required
    // container for messages
    private lateinit var chatMessagesContainer: LinearLayout
    private lateinit var scrollView: ScrollView

    private var tts: TextToSpeech? = null

    // data stores
    private val templatesMap = HashMap<String, MutableList<String>>()
    private val contextMap = HashMap<String, String>()
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

    // timers / handlers
    private val dialogHandler = Handler(Looper.getMainLooper())
    private var idleCheckRunnable: Runnable? = null
    private var lastUserInputTime = System.currentTimeMillis()
    private val random = Random()
    private val queryTimestamps = HashMap<String, MutableList<Long>>()
    private var lastSendTime = 0L

    // small LRU cache for responses
    private val queryCache = object : LinkedHashMap<String, String>(Engine.MAX_CACHE_SIZE, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?): Boolean {
            return size > Engine.MAX_CACHE_SIZE
        }
    }

    // message store (keeps logical history; UI is chatMessagesContainer)
    private val messages = mutableListOf<ChatMessage>()

    private fun canonicalKeyFromTokens(tokens: List<String>): String = tokens.sorted().joinToString(" ")
    private fun canonicalKeyFromTextStatic(text: String, synonyms: Map<String, String>, stopwords: Set<String>): String {
        val toks = Engine.filterStopwordsAndMapSynonymsStatic(text, synonyms, stopwords).first
        return canonicalKeyFromTokens(toks)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        ChatCore.init(applicationContext)

        setContentView(R.layout.activity_chat)

        // fullscreen feel but keep safe
        window.addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN)
        try { supportActionBar?.setBackgroundDrawable(ColorDrawable(Color.argb(128, 0, 0, 0))) } catch (_: Exception) {}
        window.setBackgroundDrawable(ColorDrawable(Color.BLACK))

        // find views - adapted to simplified xml
        // topBar is a plain LinearLayout in xml
        // find optional indicator (may be absent)
        loadingIndicator = findViewById(R.id.loadingIndicator)
        chatMessagesContainer = findViewById(R.id.chatMessagesContainer)
        scrollView = findViewById(R.id.scrollView)
        queryInput = findViewById(R.id.queryInput)
        envelopeInputButton = findViewById(R.id.envelope_button)
        btnLock = findViewById(R.id.btn_lock)
        btnTrash = findViewById(R.id.btn_trash)
        btnEnvelopeTop = findViewById(R.id.btn_envelope_top)
        btnSettings = findViewById(R.id.btn_settings)
        btnOverflow = findViewById(R.id.btn_overflow)

        // tint loading if present
        try { loadingIndicator?.setIndicatorColor(Color.parseColor("#00BCD4")) } catch (_: Exception) {}

        // restore persisted folder uri
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
            val prefs = PreferenceManager.getDefaultSharedPreferences(this)
            val disable = prefs.getBoolean(PREF_KEY_DISABLE_SCREENSHOTS, false)
            if (disable) window.addFlags(WindowManager.LayoutParams.FLAG_SECURE) else window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        } catch (_: Exception) {}

        // IME action send & Enter behavior
        queryInput.setOnEditorActionListener { _, actionId, event ->
            if (actionId == EditorInfo.IME_ACTION_SEND ||
                (event != null && event.keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_DOWN)
            ) {
                performSendFromInput(); true
            } else false
        }

        // send button (envelope)
        envelopeInputButton?.setOnClickListener { performSendFromInput() }

        // touch effects (safe no-op when buttons are null)
        setupIconTouchEffect(btnLock)
        setupIconTouchEffect(btnTrash)
        setupIconTouchEffect(btnEnvelopeTop)
        setupIconTouchEffect(btnSettings)
        setupIconTouchEffect(envelopeInputButton)
        setupIconTouchEffect(btnOverflow)

        btnLock?.setOnClickListener { finish() }
        btnTrash?.setOnClickListener { clearChat() }
        btnSettings?.setOnClickListener { startActivity(Intent(this, SettingsActivity::class.java)) }
        btnEnvelopeTop?.setOnClickListener { startActivity(Intent(this, SetupActivity::class.java)) }

        // overflow popup (uses string resources defined earlier)
        btnOverflow?.setOnClickListener { v ->
            try {
                val popup = PopupMenu(this, v)
                // Using strings from resources: ensure these exist in strings.xml
                popup.menu.add(0, 1, 0, getString(R.string.clear_chat))
                popup.menu.add(0, 2, 0, getString(R.string.convert))
                popup.menu.add(0, 3, 0, getString(R.string.settings))
                popup.setOnMenuItemClickListener { item ->
                    when (item.itemId) {
                        1 -> clearChat()
                        2 -> showCustomToast(getString(R.string.convert))
                        3 -> startActivity(Intent(this, SettingsActivity::class.java))
                    }
                    true
                }
                popup.show()
            } catch (e: Exception) {
                showCustomToast(getString(R.string.unknown_command, e.message ?: ""))
            }
        }

        tts = TextToSpeech(this, this)

        // initial load
        if (folderUri == null) {
            showCustomToast(getString(R.string.toast_folder_not_selected))
            ChatCore.loadFallbackTemplates(templatesMap, keywordResponses, mascotList, contextMap)
            rebuildInvertedIndex()
            engine.computeTokenWeights()
            updateAutoComplete()
            addChatMessage(currentMascotName, getString(R.string.welcome_message))
        } else {
            loadTemplatesFromFile(currentContext)
            try { MemoryManager.loadTemplatesFromFolder(this, folderUri) } catch (_: Exception) {}
            rebuildInvertedIndex()
            engine.computeTokenWeights()
            updateAutoComplete()
            addChatMessage(currentMascotName, getString(R.string.welcome_message))
        }

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
                dialogHandler.postDelayed(this, 5000)
            }
        }
        idleCheckRunnable?.let { dialogHandler.postDelayed(it, 5000) }
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            try {
                tts?.language = Locale("ru", "RU")
                tts?.setPitch(1.0f)
                tts?.setSpeechRate(1.0f)
            } catch (_: Exception) {}
        }
    }

    private fun performSendFromInput() {
        val now = System.currentTimeMillis()
        if (now - lastSendTime < Engine.SEND_DEBOUNCE_MS) return
        lastSendTime = now
        val input = queryInput.text?.toString()?.trim() ?: ""
        if (input.isNotEmpty()) {
            processUserQuery(input)
            queryInput.setText("")
        }
    }

    override fun onResume() {
        super.onResume()
        folderUri?.let { loadTemplatesFromFile(currentContext) }
        try { MemoryManager.loadTemplatesFromFolder(this, folderUri) } catch (_: Exception) {}
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
        tts?.shutdown(); tts = null
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

    // UI helpers (LinearLayout-based)
    private fun addChatMessage(sender: String, text: String) {
        lifecycleScope.launch {
            withContext(Dispatchers.Main) {
                loadingIndicator?.isVisible = false

                val row = LinearLayout(this@ChatActivity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    val pad = dpToPx(6)
                    setPadding(pad, pad / 2, pad, pad / 2)
                    layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                }

                val isUser = sender.equals(getString(R.string.user_label), ignoreCase = true)
                if (isUser) {
                    val bubble = createMessageBubble(sender, text, true)
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
                        // Keep square avatars
                        loadAvatarInto(this, sender)
                        setOnClickListener { view ->
                            view.isEnabled = false
                            val scaleX = ObjectAnimator.ofFloat(view, "scaleX", 1f, 1.08f, 1f)
                            val scaleY = ObjectAnimator.ofFloat(view, "scaleY", 1f, 1.08f, 1f)
                            scaleX.duration = 250; scaleY.duration = 250
                            scaleX.start(); scaleY.start()
                            Handler(Looper.getMainLooper()).postDelayed({
                                loadAndSendOuchMessage(sender)
                                view.isEnabled = true
                            }, 260)
                        }
                    }
                    val bubble = createMessageBubble(sender, text, false)
                    val bubbleLp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                    bubbleLp.marginStart = dpToPx(8)
                    row.addView(avatarView)
                    row.addView(bubble, bubbleLp)
                }

                chatMessagesContainer.addView(row)
                chatMessagesContainer.findViewWithTag<View>("typingView")?.let { chatMessagesContainer.removeView(it) }
                if (chatMessagesContainer.childCount > Engine.MAX_MESSAGES) {
                    val removeCount = chatMessagesContainer.childCount - Engine.MAX_MESSAGES
                    repeat(removeCount) { chatMessagesContainer.removeViewAt(0) }
                }
                scrollView.post { scrollView.smoothScrollTo(0, chatMessagesContainer.bottom) }
                if (!isUser) playNotificationSound()
                messages.add(ChatMessage(sender, text))
            }
        }
    }

    private fun showTypingIndicator() {
        runOnUiThread {
            val existing = chatMessagesContainer.findViewWithTag<TextView>("typingView")
            if (existing != null) return@runOnUiThread

            val typingView = TextView(this).apply {
                tag = "typingView"
                text = getString(R.string.typing)
                textSize = 14f
                try { setTextColor(Color.parseColor("#00FFFF")) } catch (_: Exception) { setTextColor(Color.CYAN) }
                setPadding(dpToPx(12), dpToPx(6), dpToPx(12), dpToPx(6))
                setShadowLayer(6f, 0f, 0f, Color.parseColor("#33E0FF"))
                alpha = 0.85f
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                    setMargins(0, dpToPx(8), 0, dpToPx(8))
                }
            }

            chatMessagesContainer.addView(typingView)
            scrollView.post { scrollView.smoothScrollTo(0, chatMessagesContainer.bottom) }

            val anim = ValueAnimator.ofFloat(4f, 12f).apply {
                duration = 800
                repeatMode = ValueAnimator.REVERSE
                repeatCount = ValueAnimator.INFINITE
                addUpdateListener { av ->
                    val r = av.animatedValue as Float
                    typingView.setShadowLayer(r, 0f, 0f, Color.parseColor("#40E0FF"))
                    typingView.alpha = 0.7f + (r - 4f) / 16f * 0.3f
                }
                start()
            }

            Handler(Looper.getMainLooper()).postDelayed({
                chatMessagesContainer.findViewWithTag<View>("typingView")?.let { chatMessagesContainer.removeView(it) }
                try { anim.cancel() } catch (_: Exception) {}
            }, (1000..3000).random().toLong())
        }
    }

    // *** The function you asked about: startIdleTimer() ***
    private fun startIdleTimer() {
        lastUserInputTime = System.currentTimeMillis()
        idleCheckRunnable?.let {
            dialogHandler.removeCallbacks(it)
            dialogHandler.postDelayed(it, 5000)
        }
    }

    private fun clearChat() {
        lifecycleScope.launch {
            withContext(Dispatchers.Main) {
                chatMessagesContainer.removeAllViews()
                queryTimestamps.clear()
                queryCache.clear()
                currentContext = "base.txt"
                loadTemplatesFromFile(currentContext)
                rebuildInvertedIndex()
                engine.computeTokenWeights()
                updateAutoComplete()
                addChatMessage(currentMascotName, getString(R.string.chat_cleared))
            }
        }
    }

    private fun cacheResponse(qKey: String, response: String) {
        if (qKey.isNotBlank()) queryCache[qKey] = response
    }

    private fun rebuildInvertedIndex() {
        invertedIndex.clear()
        try {
            val tempIndex = engine.rebuildInvertedIndex()
            invertedIndex.putAll(tempIndex)
        } catch (_: Exception) {
            for (key in templatesMap.keys) {
                val toks = Engine.filterStopwordsAndMapSynonymsStatic(key, synonymsMap, stopwords).first
                for (t in toks) {
                    val list = invertedIndex.getOrPut(t) { mutableListOf() }
                    if (!list.contains(key)) list.add(key)
                }
            }
        }
    }

    private fun updateAutoComplete() {
        // Minimal: keep for future; current layout uses AutoCompleteTextView which can be wired later.
    }

    private fun loadTemplatesFromFile(filename: String) {
        templatesMap.clear()
        keywordResponses.clear()
        mascotList.clear()
        if (filename == "base.txt") contextMap.clear()
        currentMascotName = "Racky"
        currentMascotIcon = "raccoon_icon.png"
        currentThemeColor = "#00FF00"
        currentThemeBackground = "#000000"

        ChatCore.loadSynonymsAndStopwords(this, folderUri, synonymsMap, stopwords)

        if (folderUri == null) {
            ChatCore.loadFallbackTemplates(templatesMap, keywordResponses, mascotList, contextMap)
            rebuildInvertedIndex()
            engine.computeTokenWeights()
            updateUI(currentMascotName, currentMascotIcon, currentThemeColor, currentThemeBackground)
            return
        }
        try {
            val dir = DocumentFile.fromTreeUri(this, folderUri!!) ?: run {
                ChatCore.loadFallbackTemplates(templatesMap, keywordResponses, mascotList, contextMap)
                rebuildInvertedIndex()
                engine.computeTokenWeights()
                updateUI(currentMascotName, currentMascotIcon, currentThemeColor, currentThemeBackground)
                return
            }
            val file = dir.findFile(filename)
            if (file == null || !file.exists()) {
                ChatCore.loadFallbackTemplates(templatesMap, keywordResponses, mascotList, contextMap)
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
            showCustomToast(getString(R.string.error_reading_file, e.message ?: ""))
            ChatCore.loadFallbackTemplates(templatesMap, keywordResponses, mascotList, contextMap)
            rebuildInvertedIndex()
            engine.computeTokenWeights()
            updateUI(currentMascotName, currentMascotIcon, currentThemeColor, currentThemeBackground)
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
                showCustomToast(getString(R.string.error_loading_mascot_metadata, e.message ?: ""))
            }
        }
    }

    private fun updateUI(mascotName: String, mascotIcon: String, themeColor: String, themeBackground: String) {
        lifecycleScope.launch {
            withContext(Dispatchers.Main) {
                try { title = getString(R.string.app_title_prefix, mascotName) } catch (_: Exception) {}
                try { chatMessagesContainer.setBackgroundColor(Color.parseColor(themeBackground)) } catch (_: Exception) {}
                // no TextInputLayout in simplified layout; skip endIcon tinting
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

    // Core logic preserved (no changes)...
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

        val templatesSnapshot = HashMap(templatesMap)
        val invertedIndexSnapshot = HashMap<String, MutableList<String>>()
        for ((k, v) in invertedIndex) invertedIndexSnapshot[k] = ArrayList(v)
        val synonymsSnapshot = HashMap(synonymsMap)
        val stopwordsSnapshot = HashSet(stopwords)
        val keywordResponsesSnapshot = HashMap<String, MutableList<String>>()
        for ((k, v) in keywordResponses) keywordResponsesSnapshot[k] = ArrayList(v)
        val contextMapSnapshot = HashMap(contextMap)

        fun recordMemorySideEffect(inputText: String) {
            try { MemoryManager.processIncoming(this, inputText) } catch (_: Exception) {}
        }

        lifecycleScope.launch(Dispatchers.Default) {
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
                val combined = subqueryResponses.joinToString(". ")
                withContext(Dispatchers.Main) {
                    addChatMessage(currentMascotName, combined)
                    recordMemorySideEffect(qOrigRaw)
                    startIdleTimer()
                }
                return@launch
            }

            val qTokenSet = if (qTokensFiltered.isNotEmpty()) qTokensFiltered.toSet() else Engine.tokenizeStatic(qFiltered).toSet()
            for ((keyword, responses) in keywordResponsesSnapshot) {
                if (keyword.isBlank() || responses.isEmpty()) continue
                val kwTokens = keyword.split(" ").filter { it.isNotEmpty() }.toSet()
                if (qTokenSet.intersect(kwTokens).isNotEmpty()) {
                    withContext(Dispatchers.Main) {
                        addChatMessage(currentMascotName, responses.random())
                        recordMemorySideEffect(qOrigRaw)
                        startIdleTimer()
                    }
                    return@launch
                }
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
                        recordMemorySideEffect(qOrigRaw)
                        startIdleTimer()
                        cacheResponse(qKeyForCount, response)
                    }
                    return@launch
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
                    val response = possible.random()
                    withContext(Dispatchers.Main) {
                        addChatMessage(currentMascotName, response)
                        recordMemorySideEffect(qOrigRaw)
                        startIdleTimer()
                        cacheResponse(qKeyForCount, response)
                    }
                    return@launch
                }
            }

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

                val (localTemplates, localKeywords) = ChatCore.parseTemplatesFromFile(
                    this@ChatActivity, folderUri, detectedContext, synonymsSnapshot, stopwordsSnapshot
                )
                localTemplates[qCanonical]?.let { possible ->
                    if (possible.isNotEmpty()) {
                        val response = possible.random()
                        withContext(Dispatchers.Main) {
                            addChatMessage(currentMascotName, response)
                            recordMemorySideEffect(qOrigRaw)
                            startIdleTimer()
                            cacheResponse(qKeyForCount, response)
                        }
                        return@launch
                    }
                }

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
                            recordMemorySideEffect(qOrigRaw)
                            startIdleTimer()
                            cacheResponse(qKeyForCount, response)
                        }
                        return@launch
                    }
                }

                // local Levenshtein fallback
                var bestLocalLev: String? = null
                var bestLocalDist = Int.MAX_VALUE
                val levCandidatesLocal = localTemplates.keys.take(Engine.MAX_CANDIDATES_FOR_LEV)
                for (key in levCandidatesLocal) {
                    val d = engine.levenshtein(qCanonical, key, qCanonical)
                    if (d < bestLocalDist) {
                        bestLocalDist = d
                        bestLocalLev = key
                    }
                }
                if (bestLocalLev != null && bestLocalDist <= engine.getFuzzyDistance(qCanonical)) {
                    val possible = localTemplates[bestLocalLev]
                    if (!possible.isNullOrEmpty()) {
                        val response = possible.random()
                        withContext(Dispatchers.Main) {
                            addChatMessage(currentMascotName, response)
                            recordMemorySideEffect(qOrigRaw)
                            startIdleTimer()
                            cacheResponse(qKeyForCount, response)
                        }
                        return@launch
                    }
                }

                val coreResult = ChatCore.searchInCoreFiles(
                    this@ChatActivity, folderUri, qFiltered, qTokens, engine,
                    synonymsSnapshot, stopwordsSnapshot, jaccardThreshold
                )
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

            val coreResult = ChatCore.searchInCoreFiles(
                this@ChatActivity, folderUri, qFiltered, qTokens, engine,
                synonymsSnapshot, stopwordsSnapshot, jaccardThreshold
            )
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

    private fun handleCommand(cmdRaw: String) {
        val cmd = cmdRaw.trim().lowercase(Locale.getDefault())
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

    // Message bubble creation
    data class ChatMessage(val sender: String, val text: String)

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
            val accent = try {
                if (isUser) Color.parseColor("#00BCD4") else safeParseColorOrDefault(currentThemeColor, Color.parseColor("#00BCD4"))
            } catch (_: Exception) { Color.parseColor("#00BCD4") }
            background = createBubbleDrawable(accent)
            try { setTextColor(if (isUser) Color.BLACK else Color.WHITE) } catch (_: Exception) { setTextColor(Color.WHITE) }
            setOnClickListener { speakText(text) }
        }
        ViewCompat.setElevation(tv, dpToPx(4).toFloat())
        container.addView(tvSender)
        container.addView(tv)
        return container
    }

    private fun createBubbleDrawable(accentColor: Int): GradientDrawable {
        return GradientDrawable().apply {
            val base = try { Color.parseColor("#0A0A0A") } catch (_: Exception) { Color.BLACK }
            val bg = blendColors(base, accentColor, 0.06f)
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dpToPx(10).toFloat()
            setColor(bg)
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
        } catch (_: Exception) {}
        try { target.setImageResource(android.R.color.transparent) } catch (_: Exception) {}
    }

    private fun blendColors(base: Int, accent: Int, ratio: Float): Int {
        val r = (Color.red(base) * (1 - ratio) + Color.red(accent) * ratio).roundToInt()
        val g = (Color.green(base) * (1 - ratio) + Color.green(accent) * ratio).roundToInt()
        val b = (Color.blue(base) * (1 - ratio) + Color.blue(accent) * ratio).roundToInt()
        return Color.rgb(r, g, b)
    }

    private fun safeParseColorOrDefault(spec: String?, fallback: Int): Int {
        return try { Color.parseColor(spec ?: "") } catch (_: Exception) { fallback }
    }

    private fun dpToPx(dp: Int): Int {
        val density = resources.displayMetrics.density
        return (dp * density).roundToInt()
    }

    private fun spaceView(): View = View(this).apply { layoutParams = LinearLayout.LayoutParams(0, 0, 1f) }

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
}
