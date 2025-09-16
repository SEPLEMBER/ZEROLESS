package com.nemesis.droidcrypt

import android.content.Intent
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.media.MediaPlayer
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

class ChatActivity : AppCompatActivity() {

    companion object {
        private const val PREF_KEY_FOLDER_URI = "pref_folder_uri"
        private const val PREF_KEY_DISABLE_SCREENSHOTS = "pref_disable_screenshots"
        private const val MAX_MESSAGES = 250
    }

    private lateinit var logic: Logic
    private lateinit var ui: UIManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_chat)

        logic = Logic()
        ui = UIManager(this, logic)
        logic.setUIBridge(ui)

        ui.initViewsAndLoad()
    }

    override fun onResume() {
        super.onResume()
        ui.onResume()
    }

    override fun onPause() {
        super.onPause()
        ui.onPause()
    }

    override fun onDestroy() {
        super.onDestroy()
        logic.shutdown()
        ui.shutdown()
    }

    // -----------------------
    // UIManager implements Logic.UIBridge
    // -----------------------
    inner class UIManager(
        private val activity: ChatActivity,
        private val logic: Logic
    ) : Logic.UIBridge {

        // Views
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

        private val handler = Handler(Looper.getMainLooper())

        fun initViewsAndLoad() {
            // bind views
            scrollView = activity.findViewById(R.id.scrollView)
            queryInput = activity.findViewById(R.id.queryInput)
            envelopeInputButton = activity.findViewById(R.id.envelope_button)
            mascotTopImage = activity.findViewById(R.id.mascot_top_image)
            btnLock = activity.findViewById(R.id.btn_lock)
            btnTrash = activity.findViewById(R.id.btn_trash)
            btnEnvelopeTop = activity.findViewById(R.id.btn_envelope_top)
            btnSettings = activity.findViewById(R.id.btn_settings)
            messagesContainer = activity.findViewById(R.id.chatMessagesContainer)

            // restore SAF Uri
            folderUri = activity.intent?.getParcelableExtra("folderUri")
            if (folderUri == null) {
                val persisted = activity.contentResolver.persistedUriPermissions
                if (persisted.isNotEmpty()) folderUri = persisted[0].uri
            }
            if (folderUri == null) {
                val prefs = PreferenceManager.getDefaultSharedPreferences(activity)
                prefs.getString(PREF_KEY_FOLDER_URI, null)?.let { folderUri = try { Uri.parse(it) } catch (_: Exception) { null } }
            }

            // window background and secure flag
            try {
                val resId = resources.getIdentifier("background_black", "color", activity.packageName)
                val bgColor = if (resId != 0) activity.getColor(resId) else Color.BLACK
                activity.window.setBackgroundDrawable(ColorDrawable(bgColor))
            } catch (_: Exception) {
                activity.window.setBackgroundDrawable(ColorDrawable(Color.BLACK))
            }
            try {
                val prefs = PreferenceManager.getDefaultSharedPreferences(activity)
                if (prefs.getBoolean(PREF_KEY_DISABLE_SCREENSHOTS, false)) {
                    activity.window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
                } else {
                    activity.window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
                }
            } catch (_: Exception) {}

            // toolbar icons + listeners
            loadToolbarIcons()
            setupIconTouchEffect(btnLock)
            setupIconTouchEffect(btnTrash)
            setupIconTouchEffect(btnEnvelopeTop)
            setupIconTouchEffect(btnSettings)
            setupIconTouchEffect(envelopeInputButton)

            btnLock?.setOnClickListener { activity.finish() }
            btnTrash?.setOnClickListener { clearChat() }
            btnSettings?.setOnClickListener { activity.startActivity(Intent(activity, SettingsActivity::class.java)) }
            btnEnvelopeTop?.setOnClickListener { activity.startActivity(Intent(activity, PostsActivity::class.java)) }

            envelopeInputButton?.setOnClickListener {
                val input = queryInput.text?.toString()?.trim() ?: ""
                if (input.isNotEmpty()) {
                    logic.processUserQuery(input)
                    queryInput.setText("")
                }
            }

            queryInput.setOnItemClickListener { parent, _, position, _ ->
                val selected = parent.getItemAtPosition(position) as? String ?: return@setOnItemClickListener
                queryInput.setText(selected)
                logic.processUserQuery(selected)
            }

            // initial templates load
            if (folderUri == null) {
                showCustomToast("Папка не выбрана! Открой настройки и выбери папку.")
                loadFallbackTemplatesToLogic()
                logic.rebuildInvertedIndexAsync()
                updateUI()
            } else {
                loadTemplatesFromFile("base.txt")
            }

            addChatMessage(logic.currentMascotName, "Добро пожаловать!")
            logic.startIdleTimer()
        }

        fun onResume() {
            folderUri?.let { loadTemplatesFromFile(logic.currentContext) }
            logic.startIdleTimer()
            loadToolbarIcons()
        }

        fun onPause() {
            handler.removeCallbacksAndMessages(null)
        }

        fun shutdown() {
            handler.removeCallbacksAndMessages(null)
        }

        // --- Logic.UIBridge implementation ---
        override fun addChatMessage(sender: String, text: String) {
            handler.post {
                try {
                    val isUser = sender.equals("Ты", ignoreCase = true)
                    val row = LinearLayout(activity).apply {
                        orientation = LinearLayout.HORIZONTAL
                        val pad = dpToPx(6)
                        setPadding(pad, pad / 2, pad, pad / 2)
                        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                        gravity = if (isUser) Gravity.END else Gravity.START
                    }

                    val bubble = createMessageBubble(sender, text)
                    if (isUser) {
                        val indicator = TextView(activity).apply {
                            this.text = "/"
                            textSize = 14f
                            setTextColor(Color.parseColor("#CCCCCC"))
                            setPadding(dpToPx(3), dpToPx(1), dpToPx(3), dpToPx(1))
                            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                                .apply { setMargins(dpToPx(6), 0, 0, 0); gravity = Gravity.BOTTOM }
                        }
                        row.addView(bubble)
                        row.addView(indicator)
                        handler.postDelayed({
                            try { indicator.text = "//" } catch (_: Exception) {}
                        }, 2000L)
                    } else {
                        val avatarView = ImageView(activity).apply {
                            val size = dpToPx(64)
                            layoutParams = LinearLayout.LayoutParams(size, size).apply { marginEnd = dpToPx(8) }
                            scaleType = ImageView.ScaleType.CENTER_CROP
                            loadAvatarInto(this, sender)
                        }
                        row.addView(avatarView)
                        row.addView(bubble)
                    }

                    messagesContainer.addView(row)
                    if (messagesContainer.childCount > MAX_MESSAGES) {
                        try { messagesContainer.removeViewAt(0) } catch (_: Exception) {}
                    }
                    scrollView.post { scrollView.fullScroll(View.FOCUS_DOWN) }
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }

        override fun showTypingIndicator(durationMs: Long, colorHex: String) {
            handler.post {
                try {
                    val typingView = TextView(activity).apply {
                        text = "печатает..."
                        textSize = 14f
                        try { setTextColor(Color.parseColor(colorHex)) } catch (_: Exception) { setTextColor(Color.WHITE) }
                        setBackgroundColor(0x80000000.toInt())
                        alpha = 0.9f
                        setPadding(16, 8, 16, 8)
                        layoutParams = LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.WRAP_CONTENT,
                            LinearLayout.LayoutParams.WRAP_CONTENT
                        ).apply { setMargins(0, 16, 0, 0) }
                    }
                    messagesContainer.addView(typingView, 0)
                    if (durationMs > 0) {
                        handler.postDelayed({
                            try { messagesContainer.removeView(typingView) } catch (_: Exception) {}
                        }, durationMs)
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }

        override fun playNotifySound() {
            handler.post {
                try {
                    val dir = folderUri?.let { DocumentFile.fromTreeUri(activity, it) } ?: return@post
                    val candidates = listOf("Notify.ogg", "notify.ogg", "Notify.mp3", "notify.mp3")
                    var soundFile: DocumentFile? = null
                    for (name in candidates) {
                        val f = dir.findFile(name)
                        if (f != null && f.exists()) {
                            soundFile = f
                            break
                        }
                    }
                    val fileUri = soundFile?.uri ?: return@post
                    var mp: MediaPlayer? = null
                    try {
                        activity.contentResolver.openFileDescriptor(fileUri, "r")?.use { pfd ->
                            mp = MediaPlayer().apply {
                                setDataSource(pfd.fileDescriptor)
                                setOnPreparedListener { it.start() }
                                setOnCompletionListener { it.release() }
                                prepareAsync()
                            }
                        }
                    } catch (e: Exception) {
                        try { mp?.release() } catch (_: Exception) {}
                        e.printStackTrace()
                    }
                } catch (_: Exception) {}
            }
        }

        override fun updateAutoCompleteSuggestions(suggestions: List<String>) {
            handler.post {
                try {
                    val list = suggestions.toMutableList()
                    if (adapter == null) {
                        adapter = object : ArrayAdapter<String>(activity, android.R.layout.simple_dropdown_item_1line, list) {
                            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                                return super.getView(position, convertView, parent).apply {
                                    (this as? TextView)?.setTextColor(Color.WHITE)
                                }
                            }
                        }
                        queryInput.setAdapter(adapter)
                        queryInput.threshold = 1
                    } else {
                        adapter?.clear()
                        adapter?.addAll(list)
                        adapter?.notifyDataSetChanged()
                    }
                    try { queryInput.dropDownBackground = ColorDrawable(0xCC000000.toInt()) } catch (_: Exception) {}
                } catch (_: Exception) {}
            }
        }

        override fun loadTemplatesFromFileRequest(filename: String) {
            handler.post { loadTemplatesFromFile(filename) }
        }

        // --- SAF IO and parsing (same as before, but uses logic setters) ---
        private fun loadTemplatesFromFile(filename: String) {
            if (folderUri == null) {
                loadFallbackTemplatesToLogic()
                logic.rebuildInvertedIndexAsync()
                updateUI()
                return
            }
            val dir = try { DocumentFile.fromTreeUri(activity, folderUri!!) } catch (_: Exception) { null } ?: run {
                loadFallbackTemplatesToLogic()
                logic.rebuildInvertedIndexAsync()
                updateUI()
                return
            }

            logic.clearTemplatesAndState()
            if (filename == "base.txt") {
                logic.setMascotFromMetadata("Racky", "raccoon_icon.png", "#00FF00", "#000000")
            }

            dir.findFile(filename)?.uri?.let { fileUri ->
                try {
                    activity.contentResolver.openInputStream(fileUri)?.bufferedReader()?.useLines { lines ->
                        lines.forEach { rawLine -> parseTemplateLineToLogic(rawLine, filename) }
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                    showCustomToast("Ошибка чтения файла ${filename}: ${e.message}")
                    loadFallbackTemplatesToLogic()
                }
            } ?: run {
                loadFallbackTemplatesToLogic()
            }

            val metadataFilename = filename.replace(".txt", "_metadata.txt")
            dir.findFile(metadataFilename)?.uri?.let { metaUri ->
                try {
                    activity.contentResolver.openInputStream(metaUri)?.bufferedReader()?.useLines { lines ->
                        lines.forEach { parseMetadataLineToLogic(it) }
                    }
                } catch (_: Exception) {}
            }

            dir.findFile("randomreply.txt")?.uri?.let { dialogsUri ->
                try {
                    activity.contentResolver.openInputStream(dialogsUri)?.bufferedReader()?.use { r ->
                        parseDialogsFileToLogic(r.readLines())
                    }
                } catch (_: Exception) {}
            }

            dir.findFile("synonims.txt")?.uri?.let { synUri ->
                try {
                    val map = mutableMapOf<String, String>()
                    activity.contentResolver.openInputStream(synUri)?.bufferedReader()?.useLines { lines ->
                        lines.forEach { line ->
                            val parts = line.removeSurrounding("*").split(";").map { normalizeText(it) }.filter { it.isNotEmpty() }
                            if (parts.size > 1) {
                                val canonical = parts.last()
                                parts.forEach { map[it] = canonical }
                            }
                        }
                    }
                    logic.setSynonyms(map)
                } catch (_: Exception) {}
            }

            dir.findFile("stopwords.txt")?.uri?.let { swUri ->
                try {
                    val set = mutableSetOf<String>()
                    activity.contentResolver.openInputStream(swUri)?.bufferedReader()?.use { r ->
                        val text = r.readText()
                        text.split("^").map { normalizeText(it) }.filter { it.isNotEmpty() }.forEach { set.add(it) }
                    }
                    logic.setStopwords(set)
                } catch (_: Exception) {}
            }

            logic.rebuildInvertedIndexAsync()
            updateUI()
        }

        private fun parseTemplateLineToLogic(raw: String, filename: String) {
            val l = raw.trim()
            if (l.isEmpty()) return

            try {
                if (filename == "base.txt" && l.startsWith(":") && l.endsWith(":")) {
                    val parts = l.substring(1, l.length - 1).split("=", limit = 2)
                    if (parts.size == 2) logic.addContextMapping(parts[0].trim().lowercase(Locale.getDefault()), parts[1].trim())
                    return
                }

                if (l.startsWith("-")) {
                    val parts = l.substring(1).split("=", limit = 2)
                    if (parts.size == 2) {
                        val key = parts[0].trim().lowercase(Locale.getDefault())
                        val responses = parts[1].split("|").map { it.trim() }.filter { it.isNotEmpty() }
                        logic.addKeywordResponse(key, responses)
                    }
                    return
                }

                val parts = l.split("=", limit = 2)
                if (parts.size == 2) {
                    val trigger = filterStopwordsAndMapSynonymsForUI(parts[0])
                    val responses = parts[1].split("|").map { it.trim() }.filter { it.isNotEmpty() }
                    if (trigger.isNotBlank() && responses.isNotEmpty()) logic.addTemplate(trigger, responses)
                }
            } catch (_: Exception) {}
        }

        private fun parseMetadataLineToLogic(line: String) {
            val t = line.trim()
            when {
                t.startsWith("mascot_list=") -> {
                    val value = t.substring("mascot_list=".length).trim()
                    value.split("|").forEach {
                        val parts = it.split(":")
                        if (parts.size >= 4) {
                            logic.addMascotEntry(parts[0].trim(), parts[1].trim(), parts[2].trim(), parts[3].trim())
                        }
                    }
                }
                t.startsWith("mascot_name=") -> {
                    logic.setMascotFromMetadata(t.substring("mascot_name=".length).trim(), null, null, null)
                }
                t.startsWith("mascot_icon=") -> {
                    logic.setMascotFromMetadata(null, t.substring("mascot_icon=".length).trim(), null, null)
                }
                t.startsWith("theme_color=") -> {
                    logic.setMascotFromMetadata(null, null, t.substring("theme_color=".length).trim(), null)
                }
                t.startsWith("theme_background=") -> {
                    logic.setMascotFromMetadata(null, null, null, t.substring("theme_background=".length).trim())
                }
                t.startsWith("dialog_lines=") -> {
                    val lines = t.substring("dialog_lines=".length).trim().split("|")
                    lines.map { it.trim() }.filter { it.isNotEmpty() }.forEach { logic.addDialogLine(it) }
                }
            }
        }

        private fun parseDialogsFileToLogic(lines: List<String>) {
            var currentDialogParserName: String? = null
            val replies = mutableListOf<Pair<String, String>>()
            for (raw in lines) {
                val l = raw.trim()
                if (l.isEmpty()) continue
                if (l.startsWith(";")) {
                    if (currentDialogParserName != null && replies.isNotEmpty()) {
                        logic.addDialog(currentDialogParserName, replies.toList())
                    }
                    currentDialogParserName = l.substring(1).trim()
                    replies.clear()
                } else if (l.contains(">")) {
                    val parts = l.split(">", limit = 2)
                    if (parts.size == 2) {
                        val mascot = parts[0].trim()
                        val text = parts[1].trim()
                        if (mascot.isNotEmpty() && text.isNotEmpty()) replies.add(Pair(mascot, text))
                    }
                } else {
                    logic.addDialogLine(l)
                }
            }
            if (currentDialogParserName != null && replies.isNotEmpty()) {
                logic.addDialog(currentDialogParserName, replies.toList())
            }
        }

        private fun loadFallbackTemplatesToLogic() {
            logic.clearTemplatesAndState()
            logic.addTemplate(filterStopwordsAndMapSynonymsForUI("привет"), listOf("Привет! Чем могу помочь?", "Здравствуй!"))
            logic.addTemplate(filterStopwordsAndMapSynonymsForUI("как дела"), listOf("Всё отлично, а у тебя?", "Нормально, как дела?"))
            logic.addKeywordResponse("спасибо", listOf("Рад, что помог!", "Всегда пожалуйста!"))
        }

        // (UI helper methods: createMessageBubble, loadAvatarInto, etc. — оставлены без изменений)
        // Для краткости — см. ваш предыдущий код: createMessageBubble, createBubbleDrawable,
        // loadAvatarInto, loadToolbarIcons, setupIconTouchEffect, updateUI, clearChat, showCustomToast и пр.
        // Они используют logic.currentTheme..., logic.getSynonyms(), logic.getStopwords() где нужно.

        // Небольшой helper, чтобы UI пользовалась синхронными геттер-методами Logic:
        private fun filterStopwordsAndMapSynonymsForUI(input: String): String {
            val synonyms = logic.getSynonyms()
            val stop = logic.getStopwords()
            val tokens = input
                .lowercase(Locale.getDefault())
                .replace(Regex("[^\\p{L}\\p{Nd}\\s]"), " ")
                .split(Regex("\\s+"))
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .map { synonyms[it] ?: it }
                .filter { !stop.contains(it) }
            return tokens.joinToString(" ")
        }

        private fun normalizeText(s: String): String {
            val lower = s.lowercase(Locale.getDefault())
            val cleaned = lower.replace(Regex("[^\\p{L}\\p{Nd}\\s]"), " ")
            return cleaned.replace(Regex("\\s+"), " ").trim()
        }

        // сюда вставьте реализацию createMessageBubble, createBubbleDrawable, loadAvatarInto, loadToolbarIcons,
        // setupIconTouchEffect, updateUI, clearChat, showCustomToast, dpToPx, blendColors, safeParseColorOrDefault
        // — они остались такими же, как у вас в предыдущем коде, за исключением вызовов logic.* вместо доступа к приватным полям.
    }
}
