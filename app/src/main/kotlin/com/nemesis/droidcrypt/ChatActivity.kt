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
import java.util.*
import kotlin.math.roundToInt
import com.nemesis.droidcrypt.Engine
import com.nemesis.droidcrypt.ChatCore

class ChatActivity : AppCompatActivity(), TextToSpeech.OnInitListener {

    companion object {
        private const val PREF_KEY_FOLDER_URI = "pref_folder_uri"
        private const val PREF_KEY_DISABLE_SCREENSHOTS = "pref_disable_screenshots"
    }

    // Engine
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

    // State
    private var currentMascotName = "Racky"
    private var currentMascotIcon = "raccoon_icon.png"
    private var currentThemeColor = "#00FF00"
    private var currentThemeBackground = "#000000"
    private var currentContext = "base.txt"

    private var lastSendTime = 0L
    private val dialogHandler = Handler(Looper.getMainLooper())
    private var idleCheckRunnable: Runnable? = null
    private var lastUserInputTime = System.currentTimeMillis()

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

        // Определяем папку
        folderUri = intent?.getParcelableExtra("folderUri") ?: getPersistedFolderUri()

        // Загружаем словари
        val synonymsMap = HashMap<String, String>()
        val stopwords = HashSet<String>()
        ChatCore.loadSynonymsAndStopwords(this, folderUri, synonymsMap, stopwords)

        // Engine
        engine = Engine(HashMap(), synonymsMap, stopwords)
        engine.computeTokenWeights()

        // Скриншоты
        handleScreenshotSecurity()

        // UI listeners
        setupIconTouchEffect(btnLock)
        setupIconTouchEffect(btnTrash)
        setupIconTouchEffect(btnEnvelopeTop)
        setupIconTouchEffect(btnSettings)
        setupIconTouchEffect(envelopeInputButton)

        btnLock?.setOnClickListener { finish() }
        btnTrash?.setOnClickListener { clearChat() }
        btnSettings?.setOnClickListener { startActivity(Intent(this, SettingsActivity::class.java)) }
        btnEnvelopeTop?.setOnClickListener { startActivity(Intent(this, PostsActivity::class.java)) }

        envelopeInputButton?.setOnClickListener { handleUserInput() }
        queryInput.setOnEditorActionListener { _, _, _ -> handleUserInput(); true }

        // TTS
        tts = TextToSpeech(this, this)

        // Загрузка шаблонов
        loadTemplatesFromFile(currentContext)
        addChatMessage(currentMascotName, "Добро пожаловать!")

        // Idle
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
        }
    }

    override fun onResume() {
        super.onResume()
        loadTemplatesFromFile(currentContext)
        idleCheckRunnable?.let {
            dialogHandler.removeCallbacks(it)
            dialogHandler.postDelayed(it, 5000)
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

    // --- Ввод ---
    private fun handleUserInput() {
        val now = System.currentTimeMillis()
        if (now - lastSendTime < Engine.SEND_DEBOUNCE_MS) return
        lastSendTime = now
        val input = queryInput.text.toString().trim()
        if (input.isNotEmpty()) {
            processUserQuery(input)
            queryInput.setText("")
        }
    }

    private fun processUserQuery(userInput: String) {
        addChatMessage("You", userInput)
        showTypingIndicator()

        lifecycleScope.launch(Dispatchers.Default) {
            val response = ChatCore.findBestResponse(
                this@ChatActivity,
                folderUri,
                engine,
                userInput,
                currentContext
            )

            withContext(Dispatchers.Main) {
                addChatMessage(currentMascotName, response)
                startIdleTimer()
            }
        }
    }

    // --- Helpers ---
    private fun getPersistedFolderUri(): Uri? {
        for (perm in contentResolver.persistedUriPermissions) {
            if (perm.isReadPermission) return perm.uri
        }
        val prefs = PreferenceManager.getDefaultSharedPreferences(this)
        return prefs.getString(PREF_KEY_FOLDER_URI, null)?.let { Uri.parse(it) }
    }

    private fun handleScreenshotSecurity() {
        val prefs = PreferenceManager.getDefaultSharedPreferences(this)
        val disable = prefs.getBoolean(PREF_KEY_DISABLE_SCREENSHOTS, false)
        if (disable) window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        else window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
    }

    private fun setupToolbar() {
        val topBar = findViewById<LinearLayout>(R.id.topBar)
        val leftLayout = topBar.getChildAt(0) as LinearLayout
        leftLayout.removeAllViews()
        leftLayout.orientation = LinearLayout.HORIZONTAL
        leftLayout.gravity = Gravity.CENTER_VERTICAL
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

    private fun clearChat() {
        runOnUiThread {
            messagesContainer.removeAllViews()
            loadTemplatesFromFile("base.txt")
            addChatMessage(currentMascotName, "Чат очищен. Возвращаюсь к началу.")
        }
    }

    // --- UI ---
    private fun addChatMessage(sender: String, text: String) {
        runOnUiThread {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            }
            val isUser = sender.equals("You", ignoreCase = true)
            val bubble = TextView(this).apply {
                this.text = "$sender: $text"
                textSize = 16f
                setTextIsSelectable(true)
                setPadding(20, 12, 20, 12)
                setTextColor(Color.WHITE)
                background = GradientDrawable().apply {
                    setColor(if (isUser) Color.DKGRAY else Color.parseColor(currentThemeColor))
                    cornerRadius = dpToPx(10).toFloat()
                }
            }
            row.addView(bubble)
            messagesContainer.addView(row)
            scrollView.post { scrollView.smoothScrollTo(0, messagesContainer.bottom) }
        }
    }

    private fun showTypingIndicator() {
        runOnUiThread {
            val typingView = TextView(this).apply {
                text = "печатает..."
                setTextColor(Color.GRAY)
                tag = "typingView"
            }
            messagesContainer.addView(typingView)
            scrollView.post { scrollView.smoothScrollTo(0, messagesContainer.bottom) }
            Handler(Looper.getMainLooper()).postDelayed({
                messagesContainer.findViewWithTag<View>("typingView")?.let { messagesContainer.removeView(it) }
            }, (1000..3000).random().toLong())
        }
    }

    private fun loadTemplatesFromFile(filename: String) {
    val templatesMap = HashMap<String, MutableList<String>>()
    val keywordResponses = HashMap<String, MutableList<String>>()
    val metadataOut = HashMap<String, String>()

    val (ok, _) = ChatCore.loadTemplatesFromFile(
        context = this,
        folderUri = folderUri,
        filename = filename,
        templatesMap = templatesMap,
        keywordResponses = keywordResponses,
        mascotList = mutableListOf(),
        contextMap = HashMap(),
        synonymsSnapshot = engine.synonymsMap,
        stopwordsSnapshot = engine.stopwords,
        metadataOut = metadataOut
    )

    if (ok) {
        engine.templatesMap.clear()
        engine.templatesMap.putAll(templatesMap)
        // При желании можно сохранить keywordResponses в движке
        metadataOut["mascot_name"]?.let { currentMascotName = it }
        metadataOut["mascot_icon"]?.let { currentMascotIcon = it }
        metadataOut["theme_color"]?.let { currentThemeColor = it }
        metadataOut["theme_background"]?.let { currentThemeBackground = it }
    } else {
        ChatCore.loadFallbackTemplates(
            templatesMap = engine.templatesMap,
            keywordResponses = HashMap(),
            mascotList = mutableListOf(),
            contextMap = HashMap()
        )
    }
}

private fun dpToPx(dp: Int): Int {
    val density = resources.displayMetrics.density
    return (dp * density).roundToInt()
}

private fun startIdleTimer() {
    lastUserInputTime = System.currentTimeMillis()
    idleCheckRunnable?.let {
        dialogHandler.removeCallbacks(it)
        dialogHandler.postDelayed(it, 5000)
    }
}
