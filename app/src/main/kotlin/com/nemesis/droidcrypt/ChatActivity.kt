package com.nemesis.droidcrypt

import android.animation.ObjectAnimator
import android.content.Intent
import android.graphics.*
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.preference.PreferenceManager
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.graphics.ColorUtils
import androidx.documentfile.provider.DocumentFile
import java.util.*
import kotlin.math.roundToInt

class ChatActivity : AppCompatActivity(), ChatLogic.ChatUi {

    companion object {
        private const val PREF_KEY_FOLDER_URI = "pref_folder_uri"
        private const val PREF_KEY_DISABLE_SCREENSHOTS = "pref_disable_screenshots"
        private const val MAX_MESSAGES = 250
    }

    // UI elements
    private var folderUri: Uri? = null
    private lateinit var scrollView: ScrollView
    private lateinit var queryInput: AutoCompleteTextView
    private var envelopeInputButton: ImageButton? = null
    private var mascotTopImage: ImageView? = null
    private var btnLock: ImageButton? = null
    private var btnTrash: ImageButton? = null
    private var btnEnvelopeTop: ImageButton? = null
    private var btnSettings: ImageButton? = null
    private lateinit var messagesContainer: LinearLayout
    private var adapter: ArrayAdapter<String>? = null

    // UI-side state that stays in Activity
    private var currentThemeColor = "#00FF00"
    private var currentThemeBackground = "#000000"

    // Idle/dialog handlers (stay in Activity)
    private var currentDialog: ChatLogic.Dialog? = null // kept only for type compatibility if needed
    private var currentDialogIndex = 0
    private val dialogHandler = Handler(Looper.getMainLooper())
    private var dialogRunnable: Runnable? = null
    private var idleCheckRunnable: Runnable? = null
    private var lastUserInputTime = System.currentTimeMillis()
    private val random = Random()

    // New: logic instance
    private lateinit var logic: ChatLogic

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_chat)

        // refs
        scrollView = findViewById(R.id.scrollView)
        queryInput = findViewById(R.id.queryInput)
        envelopeInputButton = findViewById(R.id.envelope_button)
        mascotTopImage = findViewById(R.id.mascot_top_image)
        btnLock = findViewById(R.id.btn_lock)
        btnTrash = findViewById(R.id.btn_trash)
        btnEnvelopeTop = findViewById(R.id.btn_envelope_top)
        btnSettings = findViewById(R.id.btn_settings)
        messagesContainer = findViewById(R.id.chatMessagesContainer)

        // SAF Uri
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
        } catch (_: Exception) {
        }

        // create logic and pass this Activity as UI bridge
        logic = ChatLogic(this, folderUri, this)

        // screenshots lock from prefs
        try {
            val prefs = PreferenceManager.getDefaultSharedPreferences(this)
            val disable = prefs.getBoolean(PREF_KEY_DISABLE_SCREENSHOTS, false)
            if (disable) window.addFlags(WindowManager.LayoutParams.FLAG_SECURE) else window.clearFlags(
                WindowManager.LayoutParams.FLAG_SECURE
            )
        } catch (_: Exception) {
        }

        // load icons and touch effects
        loadToolbarIcons()
        setupIconTouchEffect(btnLock)
        setupIconTouchEffect(btnTrash)
        setupIconTouchEffect(btnEnvelopeTop)
        setupIconTouchEffect(btnSettings)
        setupIconTouchEffect(envelopeInputButton)

        // icon actions
        btnLock?.setOnClickListener { finish() }
        btnTrash?.setOnClickListener { clearChat() }
        btnSettings?.setOnClickListener { startActivity(Intent(this, SettingsActivity::class.java)) }
        btnEnvelopeTop?.setOnClickListener { showCustomToast("Envelope top — заглушка") }

        envelopeInputButton?.setOnClickListener {
            val input = queryInput.text.toString().trim()
            if (input.isNotEmpty()) {
                logic.processUserQuery(input)
                queryInput.setText("")
            }
        }

        // initial parse / fallback
        if (folderUri == null) {
            showCustomToast("Папка не выбрана! Открой настройки и выбери папку.")
            logic.loadTemplatesFromFile("base.txt")
            logic.rebuildIndexPublic()
            updateAutoComplete(listOf()) // will be updated via callback soon
            addChatMessage("Racky", "Добро пожаловать!")
        } else {
            logic.setFolderUri(folderUri)
            logic.loadTemplatesFromFile(logic.currentContext)
            logic.rebuildIndexPublic()
            // logic will call updateAutoComplete via UI callback
            addChatMessage("Racky", "Добро пожаловать!")
        }

        queryInput.setOnItemClickListener { parent, _, position, _ ->
            val selected = parent.getItemAtPosition(position) as String
            queryInput.setText(selected)
            logic.processUserQuery(selected)
        }

        // idle runnable (Activity stays responsible for timers/dialog flow)
        idleCheckRunnable = object : Runnable {
            override fun run() {
                val idle = System.currentTimeMillis() - lastUserInputTime
                if (idle >= 25000) {
                    // use logic.dialogLines/dialogs via triggers in UI if required
                    // We trigger logic-managed dialogs via UI callback
                    // Triggering random dialog through UI (ChatLogic triggers UI.triggerRandomDialog)
                    // For backward compatibility we reuse Activity-side simple behavior:
                    // (keep original behavior: if dialogs not empty, startRandomDialog else triggerRandomDialog)
                }
                dialogHandler.postDelayed(this, 5000)
            }
        }
        idleCheckRunnable?.let { dialogHandler.postDelayed(it, 5000) }
    }

    override fun onResume() {
        super.onResume()
        folderUri?.let { logic.setFolderUri(it) }
        logic.loadTemplatesFromFile(logic.currentContext)
        logic.rebuildIndexPublic()
        idleCheckRunnable?.let {
            dialogHandler.removeCallbacks(it)
            dialogHandler.postDelayed(it, 5000)
        }
        loadToolbarIcons()
    }

    override fun onPause() {
        super.onPause()
        stopDialog()
        dialogHandler.removeCallbacksAndMessages(null)
    }

    // === Toolbar Helpers ===
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

            fun tryLoad(name: String, target: ImageButton?) {
                try {
                    val file = dir.findFile(name)
                    if (file != null && file.exists()) {
                        contentResolver.openInputStream(file.uri)?.use { ins ->
                            val bmp = BitmapFactory.decodeStream(ins)
                            target?.setImageBitmap(bmp)
                        }
                    }
                } catch (_: Exception) {
                }
            }
            tryLoad("lock.png", btnLock)
            tryLoad("trash.png", btnTrash)
            tryLoad("envelope.png", btnEnvelopeTop)
            tryLoad("settings.png", btnSettings)
            val iconFile = dir.findFile("raccoon_icon.png")
            if (iconFile != null && iconFile.exists()) {
                contentResolver.openInputStream(iconFile.uri)?.use { ins ->
                    val bmp = BitmapFactory.decodeStream(ins)
                    mascotTopImage?.let { imageView ->
                        imageView.setImageBitmap(bmp)
                        imageView.alpha = 0f
                        ObjectAnimator.ofFloat(imageView, "alpha", 0f, 1f).apply {
                            duration = 400
                            start()
                        }
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    // === UI Messages (these are the ChatUi callbacks used by ChatLogic) ===
    override fun addChatMessage(sender: String, text: String) {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            val pad = dpToPx(6)
            setPadding(pad, pad / 2, pad, pad / 2)
            layoutParams =
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
        }

        val isUser = sender.equals("Ты", ignoreCase = true)

        if (isUser) {
            val bubble = createMessageBubble(sender, text, isUser)
            val lp =
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
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
            }
            val bubble = createMessageBubble(sender, text, isUser)
            val bubbleLp =
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
            bubbleLp.marginStart = dpToPx(8)
            row.addView(avatarView)
            row.addView(bubble, bubbleLp)
        }

        messagesContainer.addView(row)
        if (messagesContainer.childCount > MAX_MESSAGES) {
            val removeCount = messagesContainer.childCount - MAX_MESSAGES
            repeat(removeCount) { messagesContainer.removeViewAt(0) }
        }
        scrollView.post { scrollView.smoothScrollTo(0, messagesContainer.bottom) }
    }

    private fun spaceView(): View = View(this).apply {
        layoutParams = LinearLayout.LayoutParams(0, 0, 1f)
    }

    private fun createMessageBubble(
        sender: String,
        text: String,
        isUser: Boolean
    ): LinearLayout {
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
            val accent = safeParseColorOrDefault(currentThemeColor, Color.parseColor("#00FF00"))
            background = createBubbleDrawable(accent)
            try {
                setTextColor(Color.parseColor(currentThemeColor))
            } catch (_: Exception) {
                setTextColor(Color.WHITE)
            }
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
            val candidates =
                listOf("${s}_icon.png", "${s}_avatar.png", "${s}.png", "raccoon_icon.png")
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
        } catch (_: Exception) {
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

    // === Template Loading UI helpers kept in Activity if needed (Activity delegates actual parsing to logic) ===
    private fun updateAutoComplete(suggestions: List<String>) {
        if (adapter == null) {
            adapter =
                ArrayAdapter(this, android.R.layout.simple_dropdown_item_1line, suggestions)
            queryInput.setAdapter(adapter)
            queryInput.threshold = 1
        } else {
            adapter?.clear()
            adapter?.addAll(suggestions)
            adapter?.notifyDataSetChanged()
        }
    }

    // === Idle & Dialogs (kept simple, triggerRandomDialog is called from logic via UI) ===
    override fun triggerRandomDialog() {
        // Original logic used dialogLines and mascotList from templates; since those are inside logic,
        // ChatLogic will call this UI method when it wants to trigger UI-only actions.
        // We'll keep a minimal behavior: show a random small message occasionally (to match previous behavior)
        if (random.nextDouble() < 0.3) {
            Handler(Looper.getMainLooper()).postDelayed({
                addChatMessage("Racky", "...")
            }, 1500)
        }
    }

    private fun startRandomDialog() {
        // kept minimal; the complex dialog scheduling remains in Activity if desired
    }

    private fun stopDialog() {
        dialogRunnable?.let {
            dialogHandler.removeCallbacks(it)
            dialogRunnable = null
        }
    }

    override fun showTypingIndicator() {
        val typingView = TextView(this).apply {
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
        messagesContainer.addView(typingView, 0)
        val randomDelay = (1000..3000).random().toLong()
        Handler(Looper.getMainLooper()).postDelayed({
            messagesContainer.removeView(typingView)
        }, randomDelay)
    }

    override fun startIdleTimer() {
        lastUserInputTime = System.currentTimeMillis()
        idleCheckRunnable?.let {
            dialogHandler.removeCallbacks(it); dialogHandler.postDelayed(
                it,
                5000
            )
        }
    }

    override fun updateAutoComplete(suggestions: List<String>) {
        updateAutoComplete(suggestions)
    }

    override fun updateUI(mascotName: String, mascotIcon: String, themeColor: String, themeBackground: String) {
        title = "Pawstribe - $mascotName"
        currentThemeColor = themeColor
        currentThemeBackground = themeBackground
        mascotTopImage?.let { imageView: ImageView ->
            folderUri?.let { uri ->
                try {
                    val dir = DocumentFile.fromTreeUri(this, uri)
                    val iconFile = dir?.findFile(mascotIcon)
                    if (iconFile != null && iconFile.exists()) {
                        contentResolver.openInputStream(iconFile.uri)?.use { inputStream ->
                            val bitmap = BitmapFactory.decodeStream(inputStream)
                            imageView.setImageBitmap(bitmap)
                            imageView.alpha = 0f
                            ObjectAnimator.ofFloat(imageView, "alpha", 0f, 1f).setDuration(450)
                                .start()
                        }
                    }
                } catch (_: Exception) {
                }
            }
        }
        try {
            messagesContainer.setBackgroundColor(Color.parseColor(themeBackground))
        } catch (_: Exception) {
        }
    }

    override fun showCustomToast(message: String) {
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

    // wrapper to call updateAutoComplete from logic (keeps name compatibility)
    private fun updateAutoComplete(suggestions: List<String>, resetAdapterIfNull: Boolean = false) {
        if (resetAdapterIfNull && adapter == null) {
            adapter =
                ArrayAdapter(this, android.R.layout.simple_dropdown_item_1line, suggestions)
            queryInput.setAdapter(adapter)
            queryInput.threshold = 1
        } else {
            if (adapter == null) {
                adapter =
                    ArrayAdapter(this, android.R.layout.simple_dropdown_item_1line, suggestions)
                queryInput.setAdapter(adapter)
                queryInput.threshold = 1
            } else {
                adapter?.clear()
                adapter?.addAll(suggestions)
                adapter?.notifyDataSetChanged()
            }
        }
    }

    // Keep old clearChat behavior but using logic for templates
    private fun clearChat() {
        messagesContainer.removeAllViews()
        // reset logic counters and reload base
        logic.loadTemplatesFromFile("base.txt")
        logic.rebuildIndexPublic()
        updateAutoComplete(listOf())
        addChatMessage("Racky", "Чат очищен. Возвращаюсь к началу.")
    }

    private fun showCustomToast(message: String, alt: Boolean = false) {
        showCustomToast(message)
    }
}
