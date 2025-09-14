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
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.graphics.ColorUtils
import androidx.documentfile.provider.DocumentFile
import java.io.InputStreamReader
import java.util.*
import kotlin.math.roundToInt

/**
 * ChatActivity — полный файл.
 * - объединены все функции: парсинг, dialogs, idle, metadata, UI.
 * - toolbar icons: lock/trash/envelope/settings (загружаются из SAF как lock.png / trash.png / envelope.png / settings.png)
 * - bubble styling: каждое сообщение оформляется как bubble с неоновой обводкой цвета currentThemeColor
 * - MAX_MESSAGES = 250
 *
 * Примечание: логика парсинга и маршрутизации оставлена близкой к твоему коду.
 */
class ChatActivity : AppCompatActivity() {

    companion object {
        private const val PREF_KEY_FOLDER_URI = "pref_folder_uri"
        private const val PREF_KEY_DISABLE_SCREENSHOTS = "pref_disable_screenshots"
        private const val MAX_CONTEXT_SWITCH = 6
        private const val MAX_MESSAGES = 250
    }

    // UI
    private var folderUri: Uri? = null
    private lateinit var scrollView: ScrollView
    private lateinit var queryInput: AutoCompleteTextView
    private lateinit var sendButton: Button
    private var clearButton: Button? = null
    private var mascotImage: ImageView? = null
    private lateinit var messagesContainer: LinearLayout
    private var adapter: ArrayAdapter<String>? = null

    // toolbar icons
    private var btnLock: ImageButton? = null
    private var btnTrash: ImageButton? = null
    private var btnEnvelope: ImageButton? = null
    private var btnSettings: ImageButton? = null

    // data
    private val fallback = arrayOf("Привет", "Как дела?", "Расскажи о себе", "Выход")
    private val templatesMap = HashMap<String, MutableList<String>>()
    private val contextMap = HashMap<String, String>()
    private val keywordResponses = HashMap<String, MutableList<String>>()
    private val antiSpamResponses = mutableListOf<String>()
    private val mascotList = mutableListOf<Map<String, String>>()
    private val dialogLines = mutableListOf<String>()
    private val dialogs = mutableListOf<Dialog>()

    private var currentMascotName = "Racky"
    private var currentMascotIcon = "raccoon_icon.png"
    private var currentThemeColor = "#00FF00"
    private var currentThemeBackground = "#000000"
    private var currentContext = "base.txt"
    private var lastQuery = ""

    // dialogs idle
    private var currentDialog: Dialog? = null
    private var currentDialogIndex = 0
    private val dialogHandler = Handler(Looper.getMainLooper())
    private var dialogRunnable: Runnable? = null
    private var idleCheckRunnable: Runnable? = null
    private var lastUserInputTime = System.currentTimeMillis()

    private val random = Random()
    private val queryCountMap = HashMap<String, Int>()

    private data class Dialog(
        val name: String,
        val replies: MutableList<Map<String, String>> = mutableListOf()
    )

    init {
        antiSpamResponses.addAll(
            listOf(
                "Ты надоел, давай что-то новенькое!",
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

        // UI refs
        scrollView = findViewById(R.id.scrollView)
        queryInput = findViewById(R.id.queryInput)
        sendButton = findViewById(R.id.sendButton)
        clearButton = findViewById(R.id.clearButton)
        mascotImage = findViewById(R.id.mascot_image)
        messagesContainer = findViewById(R.id.chatMessagesContainer)

        btnLock = findViewById(R.id.btn_lock)
        btnTrash = findViewById(R.id.btn_trash)
        btnEnvelope = findViewById(R.id.btn_envelope)
        btnSettings = findViewById(R.id.btn_settings)

        // SAF folder retrieval (Intent -> persisted)
        folderUri = intent?.getParcelableExtra("folderUri")
        if (folderUri == null) {
            for (perm in contentResolver.persistedUriPermissions) {
                if (perm.isReadPermission) {
                    folderUri = perm.uri
                    break
                }
            }
        }

        // if saved in prefs
        try {
            val prefs = PreferenceManager.getDefaultSharedPreferences(this)
            if (folderUri == null) {
                val saved = prefs.getString(PREF_KEY_FOLDER_URI, null)
                if (saved != null) {
                    try { folderUri = Uri.parse(saved) } catch (_: Exception) { folderUri = null }
                }
            }
        } catch (_: Exception) { }

        // screenshot lock
        try {
            val prefs = PreferenceManager.getDefaultSharedPreferences(this)
            val disable = prefs.getBoolean(PREF_KEY_DISABLE_SCREENSHOTS, false)
            if (disable) window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
            else window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        } catch (_: Exception) { }

        // toolbar icons load
        loadToolbarIcons()
        setupIconTouchEffect(btnLock)
        setupIconTouchEffect(btnTrash)
        setupIconTouchEffect(btnEnvelope)
        setupIconTouchEffect(btnSettings)

        // icon actions
        btnTrash?.setOnClickListener { clearChat() }
        btnSettings?.setOnClickListener {
            try { startActivity(Intent(this, SettingsActivity::class.java)) } catch (e: Exception) {
                showCustomToast("Не могу открыть настройки: ${e.message}")
            }
        }
        btnEnvelope?.setOnClickListener { showCustomToast("Envelope — пока заглушка") }
        btnLock?.setOnClickListener { toggleScreenshotLock() }

        // initial load
        if (folderUri == null) {
            showCustomToast("Папка не выбрана! Откройте настройки и выберите папку.")
            loadFallbackTemplates()
            updateAutoComplete()
            addChatMessage(currentMascotName, "Добро пожаловать!")
        } else {
            loadTemplatesFromFile(currentContext)
            updateAutoComplete()
            addChatMessage(currentMascotName, "Добро пожаловать!")
        }

        queryInput.onItemClickListener = AdapterView.OnItemClickListener { parent, _, position, _ ->
            val selected = parent.getItemAtPosition(position) as String
            queryInput.setText(selected)
            processUserQuery(selected)
        }

        sendButton.setOnClickListener {
            val input = queryInput.text.toString().trim()
            if (input.isNotEmpty()) {
                processUserQuery(input)
                queryInput.setText("")
            }
        }

        clearButton?.setOnClickListener { clearChat() }

        // idle runnable (checks every 5s)
        idleCheckRunnable = object : Runnable {
            override fun run() {
                val idle = System.currentTimeMillis() - lastUserInputTime
                if (idle >= 25000) {
                    if (dialogs.isNotEmpty()) startRandomDialog()
                    else if (dialogLines.isNotEmpty()) triggerRandomDialog()
                }
                dialogHandler.postDelayed(this, 5000)
            }
        }
        idleCheckRunnable?.let { dialogHandler.postDelayed(it, 5000) }
    }

    override fun onResume() {
        super.onResume()
        folderUri?.let { loadTemplatesFromFile(currentContext) }
        updateAutoComplete()
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

    // ---------------- toolbar helpers ----------------
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
                } catch (_: Exception) {}
            }
            tryLoad("lock.png", btnLock)
            tryLoad("trash.png", btnTrash)
            tryLoad("envelope.png", btnEnvelope)
            tryLoad("settings.png", btnSettings)
        } catch (_: Exception) {}
    }

    private fun toggleScreenshotLock() {
        try {
            val prefs = PreferenceManager.getDefaultSharedPreferences(this)
            val current = prefs.getBoolean(PREF_KEY_DISABLE_SCREENSHOTS, false)
            val next = !current
            prefs.edit().putBoolean(PREF_KEY_DISABLE_SCREENSHOTS, next).apply()
            if (next) window.addFlags(WindowManager.LayoutParams.FLAG_SECURE) else window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
            showCustomToast(if (next) "Скриншоты отключены" else "Скриншоты разрешены")
        } catch (e: Exception) {
            showCustomToast("Ошибка: ${e.message}")
        }
    }

    // ================= core: processing user queries =================
    private fun processUserQuery(userInput: String) {
        val qOrig = userInput.trim().lowercase(Locale.ROOT)
        if (qOrig.isEmpty()) return

        lastUserInputTime = System.currentTimeMillis()
        stopDialog()

        if (qOrig == lastQuery) {
            val cnt = queryCountMap[qOrig] ?: 0
            queryCountMap[qOrig] = cnt + 1
        } else {
            queryCountMap.clear()
            queryCountMap[qOrig] = 1
            lastQuery = qOrig
        }

        addChatMessage("Ты", userInput)

        val repeats = queryCountMap[qOrig] ?: 0
        if (repeats >= 5) {
            val spamResp = antiSpamResponses[random.nextInt(antiSpamResponses.size)]
            addChatMessage(currentMascotName, spamResp)
            startIdleTimer()
            return
        }

        val visited = HashSet<String>()
        val startContext = currentContext
        var context = startContext
        var answered = false
        var switches = 0

        while (switches <= MAX_CONTEXT_SWITCH && !answered) {
            visited.add(context)

            if (context != currentContext) {
                currentContext = context
                loadTemplatesFromFile(currentContext)
                updateAutoComplete()
            }

            val possible = templatesMap[qOrig]
            if (possible != null && possible.isNotEmpty()) {
                val resp = possible[random.nextInt(possible.size)]
                addChatMessage(currentMascotName, resp)
                triggerRandomDialog()
                startIdleTimer()
                answered = true
                break
            }

            var handledByKeyword = false
            for ((keyword, responses) in keywordResponses) {
                if (qOrig.contains(keyword)) {
                    if (responses.isNotEmpty()) {
                        val resp = responses[random.nextInt(responses.size)]
                        addChatMessage(currentMascotName, resp)
                        triggerRandomDialog()
                        startIdleTimer()
                        handledByKeyword = true
                        answered = true
                        break
                    }
                }
            }
            if (handledByKeyword) break

            if (context != "base.txt") {
                if (!visited.contains("base.txt")) {
                    context = "base.txt"
                    switches++
                    continue
                } else break
            }

            var foundRoute = false
            for ((keyword, mappedFile) in contextMap) {
                if (qOrig.contains(keyword) && mappedFile != null && !visited.contains(mappedFile)) {
                    context = mappedFile
                    switches++
                    foundRoute = true
                    break
                }
            }
            if (foundRoute) continue

            val fallbackResp = getDummyResponse(qOrig)
            addChatMessage(currentMascotName, fallbackResp)
            triggerRandomDialog()
            startIdleTimer()
            answered = true
            break
        }

        if (!answered) {
            addChatMessage(currentMascotName, "Не могу найти ответ, попробуй переформулировать.")
        }
    }

    // ========== UI: add message as bubble ==========
    private fun addChatMessage(sender: String, text: String) {
        val item = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val pad = dpToPx(8)
            setPadding(pad, pad / 2, pad, pad / 2)
        }

        val tvSender = TextView(this).apply {
            this.text = "$sender:"
            textSize = 12f
            setTextColor(Color.parseColor("#AAAAAA"))
        }
        item.addView(tvSender, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        val tv = TextView(this).apply {
            this.text = text
            textSize = 16f
            setTextIsSelectable(true)
            // bubble style: background with rounded corners + neon stroke (accent)
            val accent = safeParseColorOrDefault(currentThemeColor, Color.parseColor("#00FF00"))
            background = createBubbleDrawable(accent)
            val pad = dpToPx(10)
            setPadding(pad, pad, pad, pad)
            try { setTextColor(Color.parseColor(currentThemeColor)) } catch (e: Exception) { setTextColor(Color.WHITE) }
        }
        val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        lp.setMargins(0, dpToPx(4), 0, dpToPx(6))
        item.addView(tv, lp)

        messagesContainer.addView(item)

        if (messagesContainer.childCount > MAX_MESSAGES) {
            val removeCount = messagesContainer.childCount - MAX_MESSAGES
            repeat(removeCount) { messagesContainer.removeViewAt(0) }
        }

        // smooth scroll
        scrollView.post { scrollView.smoothScrollTo(0, messagesContainer.bottom) }
    }

    private fun createBubbleDrawable(accentColor: Int): GradientDrawable {
        val drawable = GradientDrawable()
        // background: slightly dark, slightly tinted by accent
        val bg = blendColors(Color.parseColor("#0A0A0A"), accentColor, 0.06f)
        drawable.setColor(bg)
        val radius = dpToPx(8).toFloat()
        drawable.cornerRadius = radius
        // stroke neon accent with low alpha
        val strokeColor = ColorUtils.setAlphaComponent(accentColor, 180)
        drawable.setStroke(dpToPx(2), strokeColor)
        return drawable
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

    private fun clearChat() {
        messagesContainer.removeAllViews()
        queryCountMap.clear()
        lastQuery = ""
        currentContext = "base.txt"
        loadTemplatesFromFile(currentContext)
        updateAutoComplete()
        addChatMessage(currentMascotName, "Чат очищен. Возвращаюсь к началу.")
    }

    // ========================== Загрузка шаблонов ==========================
    private fun loadTemplatesFromFile(filename: String) {
        templatesMap.clear()
        keywordResponses.clear()
        mascotList.clear()
        dialogLines.clear()
        dialogs.clear()

        currentMascotName = "Racky"
        currentMascotIcon = "raccoon_icon.png"
        currentThemeColor = "#00FF00"
        currentThemeBackground = "#000000"

        if (folderUri == null) {
            loadFallbackTemplates()
            updateUI(currentMascotName, currentMascotIcon, currentThemeColor, currentThemeBackground)
            return
        }

        try {
            val dir = DocumentFile.fromTreeUri(this, folderUri!!) ?: run {
                loadFallbackTemplates()
                updateUI(currentMascotName, currentMascotIcon, currentThemeColor, currentThemeBackground)
                return
            }

            val file = dir.findFile(filename)
            if (file == null || !file.exists()) {
                loadFallbackTemplates()
                updateUI(currentMascotName, currentMascotIcon, currentThemeColor, currentThemeBackground)
                return
            }

            // read main file
            contentResolver.openInputStream(file.uri)?.bufferedReader()?.use { reader ->
                reader.forEachLine { raw ->
                    val l = raw.trim()
                    if (l.isEmpty()) return@forEachLine

                    // context line in base: :key=file.txt:
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

                    // keyword responses: -keyword=resp1|resp2
                    if (l.startsWith("-")) {
                        val keywordLine = l.substring(1)
                        if (keywordLine.contains("=")) {
                            val parts = keywordLine.split("=", limit = 2)
                            if (parts.size == 2) {
                                val keyword = parts[0].trim().lowercase(Locale.ROOT)
                                val responses = parts[1].split("\\|".toRegex())
                                val responseList = responses.mapNotNull { it.trim().takeIf { s -> s.isNotEmpty() } }.toMutableList()
                                if (keyword.isNotEmpty() && responseList.isNotEmpty()) keywordResponses[keyword] = responseList
                            }
                        }
                        return@forEachLine
                    }

                    if (!l.contains("=")) return@forEachLine
                    val parts = l.split("=", limit = 2)
                    if (parts.size == 2) {
                        val trigger = parts[0].trim().lowercase(Locale.ROOT)
                        val responses = parts[1].split("\\|".toRegex())
                        val responseList = responses.mapNotNull { it.trim().takeIf { s -> s.isNotEmpty() } }.toMutableList()
                        if (trigger.isNotEmpty() && responseList.isNotEmpty()) templatesMap[trigger] = responseList
                    }
                }
            }

            // metadata file
            val metadataFilename = filename.replace(".txt", "_metadata.txt")
            val metadataFile = dir.findFile(metadataFilename)
            if (metadataFile != null && metadataFile.exists()) {
                contentResolver.openInputStream(metadataFile.uri)?.bufferedReader()?.use { reader ->
                    reader.forEachLine { raw ->
                        val line = raw
                        when {
                            line.startsWith("mascot_list=") -> {
                                val mascots = line.substring("mascot_list=".length).split("\\|".toRegex())
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
                            line.startsWith("dialog_lines=") -> {
                                val lines = line.substring("dialog_lines=".length).split("\\|".toRegex())
                                for (d in lines) { val t = d.trim(); if (t.isNotEmpty()) dialogLines.add(t) }
                            }
                        }
                    }
                }
            }

            // ---- randomreply.txt parsing (robust)
            val dialogFile = dir.findFile("randomreply.txt")
            if (dialogFile != null && dialogFile.exists()) {
                try {
                    contentResolver.openInputStream(dialogFile.uri)?.bufferedReader()?.use { reader ->
                        var current: Dialog? = null
                        reader.forEachLine { raw ->
                            val l = raw.trim()
                            if (l.isEmpty()) return@forEachLine

                            if (l.startsWith(";")) {
                                current?.takeIf { it.replies.isNotEmpty() }?.let { dialogs.add(it) }
                                current = Dialog(l.substring(1).trim())
                                return@forEachLine
                            }

                            if (l.contains(">")) {
                                val parts = l.split(">", limit = 2)
                                if (parts.size == 2) {
                                    val mascot = parts[0].trim()
                                    val text = parts[1].trim()
                                    if (mascot.isNotEmpty() && text.isNotEmpty()) {
                                        val cur = current ?: Dialog("default").also { current = it }
                                        cur.replies.add(mapOf("mascot" to mascot, "text" to text))
                                    }
                                }
                                return@forEachLine
                            }

                            // fallback short dialog line
                            dialogLines.add(l)
                        }
                        current?.takeIf { it.replies.isNotEmpty() }?.let { dialogs.add(it) }
                    }
                } catch (e: Exception) {
                    showCustomToast("Ошибка чтения randomreply.txt: ${e.message}")
                }
            }

            // choose random mascot for base
            if (filename == "base.txt" && mascotList.isNotEmpty()) {
                val selected = mascotList[random.nextInt(mascotList.size)]
                selected["name"]?.let { currentMascotName = it }
                selected["icon"]?.let { currentMascotIcon = it }
                selected["color"]?.let { currentThemeColor = it }
                selected["background"]?.let { currentThemeBackground = it }
            }

            updateUI(currentMascotName, currentMascotIcon, currentThemeColor, currentThemeBackground)

        } catch (e: Exception) {
            e.printStackTrace()
            showCustomToast("Ошибка чтения файла: ${e.message}")
            loadFallbackTemplates()
            updateUI(currentMascotName, currentMascotIcon, currentThemeColor, currentThemeBackground)
        }
    }

    private fun loadFallbackTemplates() {
        templatesMap.clear()
        contextMap.clear()
        keywordResponses.clear()
        dialogs.clear()
        dialogLines.clear()
        mascotList.clear()
        templatesMap["привет"] = mutableListOf("Привет! Чем могу помочь?", "Здравствуй!")
        templatesMap["как дела"] = mutableListOf("Всё отлично, а у тебя?", "Нормально, как дела?")
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
            adapter = ArrayAdapter(this, android.R.layout.simple_dropdown_item_1line, suggestions)
            queryInput.setAdapter(adapter)
            queryInput.threshold = 1
        } else {
            adapter?.clear()
            adapter?.addAll(suggestions)
            adapter?.notifyDataSetChanged()
        }
    }

    // ======= Idle & random dialogs =======
    private fun triggerRandomDialog() {
        if (dialogLines.isNotEmpty() && random.nextDouble() < 0.3) {
            dialogHandler.postDelayed({
                if (dialogLines.isEmpty()) return@postDelayed
                val dialog = dialogLines[random.nextInt(dialogLines.size)]
                if (mascotList.isNotEmpty()) {
                    val rnd = mascotList[random.nextInt(mascotList.size)]
                    val rndName = rnd["name"] ?: currentMascotName
                    loadMascotMetadata(rndName)
                    addChatMessage(rndName, dialog)
                } else addChatMessage(currentMascotName, dialog)
            }, 1500)
        }
        if (mascotList.isNotEmpty() && random.nextDouble() < 0.1) {
            dialogHandler.postDelayed({
                val rnd = mascotList[random.nextInt(mascotList.size)]
                val rndName = rnd["name"] ?: currentMascotName
                loadMascotMetadata(rndName)
                addChatMessage(rndName, "Эй, мы не закончили!")
            }, 2500)
        }
    }

    private fun startRandomDialog() {
        if (dialogs.isEmpty()) return
        stopDialog()
        currentDialog = dialogs[random.nextInt(dialogs.size)]
        currentDialogIndex = 0
        dialogRunnable = object : Runnable {
            override fun run() {
                val dialog = currentDialog ?: return
                if (currentDialogIndex < dialog.replies.size) {
                    val reply = dialog.replies[currentDialogIndex]
                    val mascot = reply["mascot"] ?: ""
                    val text = reply["text"] ?: ""
                    loadMascotMetadata(mascot)
                    addChatMessage(mascot, text)
                    currentDialogIndex++
                    dialogHandler.postDelayed(this, (random.nextInt(15000) + 10000).toLong())
                } else {
                    dialogHandler.postDelayed({ startRandomDialog() }, (random.nextInt(20000) + 5000).toLong())
                }
            }
        }
        dialogRunnable?.let { dialogHandler.postDelayed(it, (random.nextInt(15000) + 10000).toLong()) }
    }

    private fun stopDialog() {
        dialogRunnable?.let {
            dialogHandler.removeCallbacks(it)
            dialogRunnable = null
        }
    }

    private fun loadMascotMetadata(mascotName: String) {
        if (folderUri == null) return
        val metadataFilename = "${mascotName.lowercase(Locale.ROOT)}_metadata.txt"
        val dir = DocumentFile.fromTreeUri(this, folderUri!!) ?: return
        val metadataFile = dir.findFile(metadataFilename)
        if (metadataFile != null && metadataFile.exists()) {
            try {
                contentResolver.openInputStream(metadataFile.uri)?.bufferedReader()?.use { reader ->
                    reader.forEachLine { raw ->
                        val line = raw
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
        title = "Pawstribe - $mascotName"
        mascotImage?.let { imageView ->
            folderUri?.let { uri ->
                try {
                    val dir = DocumentFile.fromTreeUri(this, uri)
                    val iconFile = dir?.findFile(mascotIcon)
                    if (iconFile != null && iconFile.exists()) {
                        contentResolver.openInputStream(iconFile.uri)?.use { inputStream ->
                            val bitmap = BitmapFactory.decodeStream(inputStream)
                            imageView.setImageBitmap(bitmap)
                            imageView.alpha = 0f
                            ObjectAnimator.ofFloat(imageView, "alpha", 0f, 1f).setDuration(450).start()
                        }
                    }
                } catch (_: Exception) {}
            }
        }
        try { messagesContainer.setBackgroundColor(Color.parseColor(themeBackground)) } catch (_: Exception) {}
    }

    private fun detectContext(input: String): String? {
        val lower = input.lowercase(Locale.ROOT)
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
        idleCheckRunnable?.let { dialogHandler.removeCallbacks(it); dialogHandler.postDelayed(it, 5000) }
    }
}
