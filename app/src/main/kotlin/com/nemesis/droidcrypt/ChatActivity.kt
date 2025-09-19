package com.nemesis.droidcrypt

import android.animation.ObjectAnimator
import android.content.*
import android.graphics.*
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
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
import java.util.*
import kotlin.math.roundToInt
import com.nemesis.droidcrypt.ChatEngine

class ChatActivity : AppCompatActivity(), TextToSpeech.OnInitListener {

    companion object {
        private const val PREF_KEY_FOLDER_URI = "pref_folder_uri"
        private const val PREF_KEY_DISABLE_SCREENSHOTS = "pref_disable_screenshots"
        private const val MAX_MESSAGES = 250
        private const val SEND_DEBOUNCE_MS = 400L
        private const val IDLE_TIMEOUT_MS = 30000L
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

    // Engine
    private lateinit var chatEngine: ChatEngine

    // Idle
    private val dialogHandler = Handler(Looper.getMainLooper())
    private var idleCheckRunnable: Runnable? = null
    private var lastUserInputTime = System.currentTimeMillis()
    private var lastSendTime = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_chat)
        window.addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN)
        supportActionBar?.setBackgroundDrawable(ColorDrawable(Color.argb(128, 0, 0, 0)))
        window.setBackgroundDrawable(ColorDrawable(Color.BLACK))

        initializeUI()
        loadFolderUri()
        setupScreenshotFlag()

        // Инициализация движка
        chatEngine = ChatEngine(this, folderUri)

        setupListeners()
        loadToolbarIcons()

        tts = TextToSpeech(this, this)
        
        if (folderUri == null) {
            showCustomToast("Папка не выбрана! Открой настройки и выбери папку.")
        }

        updateUI()
        updateAutoComplete()
        addChatMessage(chatEngine.currentMascotName, "Добро пожаловать!")
        startIdleTimer()
    }

    private fun initializeUI() {
        scrollView = findViewById(R.id.scrollView)
        queryInput = findViewById(R.id.queryInput)
        envelopeInputButton = findViewById(R.id.envelope_button)
        btnLock = findViewById(R.id.btn_lock)
        btnTrash = findViewById(R.id.btn_trash)
        btnEnvelopeTop = findViewById(R.id.btn_envelope_top)
        btnSettings = findViewById(R.id.btn_settings)
        messagesContainer = findViewById(R.id.chatMessagesContainer)
    }

    private fun loadFolderUri() {
        folderUri = intent?.getParcelableExtra("folderUri")
        if (folderUri == null) {
            contentResolver.persistedUriPermissions.firstOrNull { it.isReadPermission }?.let { folderUri = it.uri }
        }
        if (folderUri == null) {
            val prefs = PreferenceManager.getDefaultSharedPreferences(this)
            prefs.getString(PREF_KEY_FOLDER_URI, null)?.let { folderUri = Uri.parse(it) }
        }
    }
    
    private fun setupScreenshotFlag() {
        try {
            val prefs = PreferenceManager.getDefaultSharedPreferences(this)
            val disable = prefs.getBoolean(PREF_KEY_DISABLE_SCREENSHOTS, false)
            if (disable) window.addFlags(WindowManager.LayoutParams.FLAG_SECURE) else window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        } catch (e: Exception) {
            Log.e("ChatActivity", "Error setting screenshot flag", e)
        }
    }

    private fun setupListeners() {
        setupIconTouchEffect(btnLock)
        setupIconTouchEffect(btnTrash)
        setupIconTouchEffect(btnEnvelopeTop)
        setupIconTouchEffect(btnSettings)
        setupIconTouchEffect(envelopeInputButton)

        btnLock?.setOnClickListener { finish() }
        btnTrash?.setOnClickListener { handleCommand("/clear") }
        btnSettings?.setOnClickListener { startActivity(Intent(this, SettingsActivity::class.java)) }
        btnEnvelopeTop?.setOnClickListener { startActivity(Intent(this, PostsActivity::class.java)) }
        
        val sendAction = {
            val now = System.currentTimeMillis()
            if (now - lastSendTime < SEND_DEBOUNCE_MS) {
                // Debounce
            } else {
                lastSendTime = now
                val input = queryInput.text.toString().trim()
                if (input.isNotEmpty()) {
                    processUserQuery(input)
                    queryInput.setText("")
                }
            }
        }
        
        envelopeInputButton?.setOnClickListener { sendAction() }
        queryInput.setOnEditorActionListener { _, _, _ -> sendAction(); true }
        queryInput.setOnItemClickListener { parent, _, position, _ ->
            val selected = parent.getItemAtPosition(position) as String
            queryInput.setText(selected)
            processUserQuery(selected)
        }
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            tts?.language = Locale("ru", "RU")
        } else {
            Log.e("ChatActivity", "TextToSpeech initialization failed with status $status")
        }
    }

    override fun onResume() {
        super.onResume()
        // Перезагрузка может быть необходима, если файлы изменились
        chatEngine.loadDataForContext(chatEngine.currentContext)
        updateUI()
        updateAutoComplete()
        startIdleTimer()
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

    private fun processUserQuery(userInput: String) {
        lastUserInputTime = System.currentTimeMillis()
        if (userInput.startsWith("/")) {
            handleCommand(userInput.trim())
            return
        }

        addChatMessage("You", userInput)
        showTypingIndicator()
        
        lifecycleScope.launch(Dispatchers.Default) {
            val response = chatEngine.processQuery(userInput)
            if (response.isNotEmpty()) {
                withContext(Dispatchers.Main) {
                    addChatMessage(chatEngine.currentMascotName, response)
                    if (chatEngine.currentContext != chatEngine.currentContext) {
                        updateUI()
                        updateAutoComplete()
                    }
                }
            }
        }
    }
    
    private fun handleCommand(cmdRaw: String) {
        val cmd = cmdRaw.trim().lowercase(Locale.getDefault())
        when {
            cmd == "/reload" -> {
                addChatMessage(chatEngine.currentMascotName, "Перезагружаю шаблоны...")
                chatEngine.loadDataForContext("base.txt")
                updateUI()
                updateAutoComplete()
                addChatMessage(chatEngine.currentMascotName, "Шаблоны перезагружены.")
            }
            cmd == "/stats" -> {
                addChatMessage(chatEngine.currentMascotName, chatEngine.getStats())
            }
            cmd == "/clear" -> {
                messagesContainer.removeAllViews()
                chatEngine.clearHistory()
                updateUI()
                updateAutoComplete()
                addChatMessage(chatEngine.currentMascotName, "Чат очищен. Возвращаюсь к началу.")
            }
            else -> addChatMessage(chatEngine.currentMascotName, "Неизвестная команда: $cmdRaw")
        }
    }

    private fun addChatMessage(sender: String, text: String) {
        val isUser = sender.equals("You", ignoreCase = true)
        val row = createMessageRow(sender, text, isUser)
        
        messagesContainer.findViewWithTag<View>("typingView")?.let { messagesContainer.removeView(it) }
        messagesContainer.addView(row)
        
        if (messagesContainer.childCount > MAX_MESSAGES) {
            messagesContainer.removeViewAt(0)
        }
        
        scrollView.post { scrollView.fullScroll(View.FOCUS_DOWN) }
        
        if (!isUser) {
            playNotificationSound()
        }
    }
    
    // --- UI Helpers ---

    private fun updateUI() {
        title = "Pawstribe - ${chatEngine.currentMascotName}"
        try {
            val bgColor = Color.parseColor(chatEngine.currentThemeBackground)
            window.setBackgroundDrawable(ColorDrawable(bgColor))
            scrollView.setBackgroundColor(bgColor)
        } catch (e: Exception) {
            Log.e("ChatActivity", "Error updating UI background", e)
        }
    }
    
    private fun updateAutoComplete() {
        val suggestions = mutableListOf<String>()
        suggestions.addAll(chatEngine.templatesMap.keys)
        suggestions.addAll(chatEngine.fallback)
        
        if (adapter == null) {
            adapter = object : ArrayAdapter<String>(this, android.R.layout.simple_dropdown_item_1line, suggestions.distinct()) {
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
            adapter?.addAll(suggestions.distinct())
            adapter?.notifyDataSetChanged()
        }
    }
    
    private fun showTypingIndicator() {
        val existing = messagesContainer.findViewWithTag<View>("typingView")
        if (existing != null) return
        val typingView = TextView(this).apply {
            text = "печатает..."
            tag = "typingView"
            setTextColor(Color.LTGRAY)
            setPadding(dpToPx(8), dpToPx(4), dpToPx(8), dpToPx(4))
        }
        messagesContainer.addView(typingView)
        scrollView.post { scrollView.fullScroll(View.FOCUS_DOWN) }
        
        Handler(Looper.getMainLooper()).postDelayed({
            messagesContainer.findViewWithTag<View>("typingView")?.let { messagesContainer.removeView(it) }
        }, (1000..2500).random().toLong())
    }
    
    private fun createMessageRow(sender: String, text: String, isUser: Boolean): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = if (isUser) Gravity.END else Gravity.START
            setPadding(dpToPx(6), dpToPx(3), dpToPx(6), dpToPx(3))
        }
        
        val bubble = createMessageBubble(sender, text, isUser)
        
        if (isUser) {
            row.addView(bubble)
        } else {
            val avatar = createAvatarView(sender)
            row.addView(avatar)
            row.addView(bubble)
            (bubble.layoutParams as? LinearLayout.LayoutParams)?.marginStart = dpToPx(8)
        }
        
        return row
    }

    private fun createAvatarView(sender: String): ImageView {
        return ImageView(this).apply {
            val size = dpToPx(48)
            layoutParams = LinearLayout.LayoutParams(size, size)
            scaleType = ImageView.ScaleType.CENTER_CROP
            loadAvatarInto(this, sender)
        }
    }
    
    private fun createMessageBubble(sender: String, text: String, isUser: Boolean): LinearLayout {
        val container = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        
        val tvSender = TextView(this).apply {
            this.text = if (isUser) "" else "$sender:"
            visibility = if (isUser) View.GONE else View.VISIBLE
            textSize = 12f
            setTextColor(Color.parseColor("#AAAAAA"))
        }

        val tv = TextView(this).apply {
            this.text = text
            textSize = 16f
            setTextIsSelectable(true)
            val pad = dpToPx(10)
            setPadding(pad, pad, pad, pad)

            val accent = safeParseColorOrDefault(
                if (isUser) "#00A040" else chatEngine.currentThemeColor,
                Color.GREEN
            )
            background = createBubbleDrawable(accent)
            setTextColor(Color.WHITE)
            setOnClickListener { tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, null) }
        }
        
        container.addView(tvSender)
        container.addView(tv)
        return container
    }
    
    private fun createBubbleDrawable(accentColor: Int): GradientDrawable {
        return GradientDrawable().apply {
            val bg = ColorUtils.blendARGB(Color.parseColor("#1A1A1A"), accentColor, 0.15f)
            setColor(bg)
            cornerRadius = dpToPx(12).toFloat()
            setStroke(dpToPx(1), ColorUtils.setAlphaComponent(accentColor, 180))
        }
    }
    
    private fun loadAvatarInto(target: ImageView, sender: String) {
        val uri = folderUri ?: return
        try {
            val dir = DocumentFile.fromTreeUri(this, uri) ?: return
            val s = sender.lowercase(Locale.getDefault())
            val candidates = listOf("${s}_icon.png", "${s}_avatar.png", "${s}.png", chatEngine.currentMascotIcon)
            for (name in candidates) {
                dir.findFile(name)?.uri?.let {
                    target.setImageURI(it); return
                }
            }
        } catch (e: Exception) {
            Log.e("ChatActivity", "Error loading avatar", e)
        }
    }

    private fun loadToolbarIcons() {
        val uri = folderUri ?: return
        fun tryLoadToImageButton(name: String, target: ImageButton?) {
            target ?: return
            try {
                DocumentFile.fromTreeUri(this, uri)?.findFile(name)?.uri?.let { target.setImageURI(it) }
            } catch (e: Exception) {
                Log.e("ChatActivity", "Error loading icon $name", e)
            }
        }
        tryLoadToImageButton("lock.png", btnLock)
        tryLoadToImageButton("trash.png", btnTrash)
        tryLoadToImageButton("envelope.png", btnEnvelopeTop)
        tryLoadToImageButton("settings.png", btnSettings)
        tryLoadToImageButton("send.png", envelopeInputButton)
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
    
    private fun playNotificationSound() {
        val uri = folderUri ?: return
        try {
            val soundFile = DocumentFile.fromTreeUri(this, uri)?.findFile("notify.ogg") ?: return
            val afd = contentResolver.openAssetFileDescriptor(soundFile.uri, "r") ?: return
            MediaPlayer().apply {
                setDataSource(afd.fileDescriptor, afd.startOffset, afd.length)
                afd.close()
                prepareAsync()
                setOnPreparedListener { it.start() }
                setOnCompletionListener { it.release() }
            }
        } catch (e: Exception) {
            Log.e("ChatActivity", "Error playing notification sound", e)
        }
    }

    private fun startIdleTimer() {
        idleCheckRunnable?.let { dialogHandler.removeCallbacks(it) }
        idleCheckRunnable = Runnable {
            if (System.currentTimeMillis() - lastUserInputTime > IDLE_TIMEOUT_MS) {
                val idleMessage = listOf("Эй, ты здесь?", "Что-то тихо стало...", "Расскажи, о чём думаешь?").random()
                addChatMessage(chatEngine.currentMascotName, idleMessage)
            }
            dialogHandler.postDelayed(idleCheckRunnable!!, IDLE_TIMEOUT_MS)
        }
        dialogHandler.postDelayed(idleCheckRunnable!!, IDLE_TIMEOUT_MS)
    }

    private fun showCustomToast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }
    
    private fun safeParseColorOrDefault(spec: String?, fallback: Int): Int {
        return try { Color.parseColor(spec) } catch (_: Exception) { fallback }
    }
    
    private fun dpToPx(dp: Int): Int {
        return (dp * resources.displayMetrics.density).roundToInt()
    }
}
