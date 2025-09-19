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
import java.util.*
import kotlin.math.roundToInt
import com.nemesis.droidcrypt.ChatManager

interface ChatCallback {
    fun addChatMessage(sender: String, text: String)
    fun showTypingIndicator()
    fun startIdleTimer()
    fun updateUI(mascotName: String, mascotIcon: String, themeColor: String, themeBackground: String)
    fun updateAutoComplete()
}

class ChatActivity : AppCompatActivity(), TextToSpeech.OnInitListener, ChatCallback {

    companion object {
        private const val PREF_KEY_FOLDER_URI = "pref_folder_uri"
        private const val PREF_KEY_DISABLE_SCREENSHOTS = "pref_disable_screenshots"
    }

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

    private var currentMascotName = "Racky"
    private var currentMascotIcon = "raccoon_icon.png"
    private var currentThemeColor = "#00FF00"
    private var currentThemeBackground = "#000000"
    private var currentContext = "base.txt"
    private var lastQuery = ""

    private val dialogHandler = Handler(Looper.getMainLooper())
    private var idleCheckRunnable: Runnable? = null
    private var lastUserInputTime = System.currentTimeMillis()
    private val random = Random()
    private var lastSendTime = 0L

    private lateinit var chatManager: ChatManager

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

        chatManager = ChatManager(this)
        chatManager.folderUri = folderUri

        chatManager.loadSynonymsAndStopwords()
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
            if (now - lastSendTime < chatManager.SEND_DEBOUNCE_MS) return@setOnClickListener
            lastSendTime = now
            val input = queryInput.text.toString().trim()
            if (input.isNotEmpty()) {
                processUserQuery(input)
                queryInput.setText("")
            }
        }

        queryInput.setOnEditorActionListener { _, _, _ ->
            val now = System.currentTimeMillis()
            if (now - lastSendTime < chatManager.SEND_DEBOUNCE_MS) return@setOnEditorActionListener true
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
            chatManager.loadFallbackTemplates()
            chatManager.rebuildInvertedIndex()
            chatManager.computeTokenWeights()
            updateAutoComplete()
            addChatMessage(currentMascotName, "Добро пожаловать!")
        } else {
            chatManager.loadTemplatesFromFile(currentContext)
            updateUI(chatManager.currentMascotName, chatManager.currentMascotIcon, chatManager.currentThemeColor, chatManager.currentThemeBackground)
            chatManager.rebuildInvertedIndex()
            chatManager.computeTokenWeights()
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
                if (System.currentTimeMillis() - lastUserInputTime > chatManager.IDLE_TIMEOUT_MS) {
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
        folderUri?.let { 
            chatManager.loadTemplatesFromFile(currentContext) 
            updateUI(chatManager.currentMascotName, chatManager.currentMascotIcon, chatManager.currentThemeColor, chatManager.currentThemeBackground)
        }
        chatManager.rebuildInvertedIndex()
        chatManager.computeTokenWeights()
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
        chatManager.processUserQuery(userInput, lifecycleScope, this)
    }

    private fun handleCommand(cmdRaw: String) {
        val cmd = cmdRaw.trim().lowercase(Locale.getDefault())
        when {
            cmd == "/reload" -> {
                addChatMessage(currentMascotName, "Перезагружаю шаблоны...")
                chatManager.loadTemplatesFromFile(currentContext)
                updateUI(chatManager.currentMascotName, chatManager.currentMascotIcon, chatManager.currentThemeColor, chatManager.currentThemeBackground)
                chatManager.rebuildInvertedIndex()
                chatManager.computeTokenWeights()
                updateAutoComplete()
                addChatMessage(currentMascotName, "Шаблоны перезагружены.")
            }
            cmd == "/stats" -> {
                val templatesCount = chatManager.templatesMap.size
                val keywordsCount = chatManager.keywordResponses.size
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
            chatManager.clearCaches()
            lastQuery = ""
            currentContext = "base.txt"
            chatManager.loadTemplatesFromFile(currentContext)
            updateUI(chatManager.currentMascotName, chatManager.currentMascotIcon, chatManager.currentThemeColor, chatManager.currentThemeBackground)
            chatManager.rebuildInvertedIndex()
            chatManager.computeTokenWeights()
            updateAutoComplete()
            addChatMessage(currentMascotName, "Чат очищен. Возвращаюсь к началу.")
        }
    }

    override fun addChatMessage(sender: String, text: String) {
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
            if (messagesContainer.childCount > chatManager.MAX_MESSAGES) {
                val removeCount = messagesContainer.childCount - chatManager.MAX_MESSAGES
                repeat(removeCount) { messagesContainer.removeViewAt(0) }
            }
            scrollView.post { scrollView.smoothScrollTo(0, messagesContainer.bottom) }
            if (!isUser) {
                playNotificationSound()
                speakText(text)
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

    override fun updateUI(mascotName: String, mascotIcon: String, themeColor: String, themeBackground: String) {
        currentMascotName = mascotName
        currentMascotIcon = mascotIcon
        currentThemeColor = themeColor
        currentThemeBackground = themeBackground
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

    override fun startIdleTimer() {
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

    override fun updateAutoComplete() {
        val suggestions = chatManager.getAutoCompleteSuggestions()
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
}
