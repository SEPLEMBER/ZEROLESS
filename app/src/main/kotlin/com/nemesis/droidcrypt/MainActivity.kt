package com.nemesis.droidcrypt

import android.animation.ObjectAnimator
import android.graphics.BitmapFactory
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.preference.PreferenceManager
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.documentfile.provider.DocumentFile
import java.io.InputStreamReader
import java.util.Locale
import java.util.Random
import kotlin.math.roundToInt

/**
 * ChatActivity — версия с исправленным парсером randomreply.txt
 */
class ChatActivity : AppCompatActivity() {

    companion object {
        private const val PREF_KEY_FOLDER_URI = "pref_folder_uri"
        private const val PREF_KEY_DISABLE_SCREENSHOTS = "pref_disable_screenshots"
        private const val MAX_CONTEXT_SWITCH = 6
        private const val MAX_MESSAGES = 400
    }

    private var folderUri: Uri? = null
    private lateinit var scrollView: ScrollView
    private lateinit var queryInput: AutoCompleteTextView
    private lateinit var sendButton: Button
    private var clearButton: Button? = null
    private var mascotImage: ImageView? = null
    private lateinit var messagesContainer: LinearLayout
    private var adapter: ArrayAdapter<String>? = null

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
            arrayOf(
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

        scrollView = findViewById(R.id.scrollView)
        queryInput = findViewById(R.id.queryInput)
        sendButton = findViewById(R.id.sendButton)
        clearButton = findViewById(R.id.clearButton)
        mascotImage = findViewById(R.id.mascot_image)
        messagesContainer = findViewById(R.id.chatMessagesContainer)

        folderUri = intent?.getParcelableExtra("folderUri")

        if (folderUri == null) {
            for (permission in contentResolver.persistedUriPermissions) {
                if (permission.isReadPermission) {
                    folderUri = permission.uri
                    break
                }
            }
        }

        try {
            val prefs = PreferenceManager.getDefaultSharedPreferences(this)
            if (folderUri == null) {
                val saved = prefs.getString(PREF_KEY_FOLDER_URI, null)
                if (saved != null) {
                    try {
                        folderUri = Uri.parse(saved)
                    } catch (ignored: Exception) {
                        folderUri = null
                    }
                }
            }
        } catch (ignored: Exception) { }

        try {
            val prefs = PreferenceManager.getDefaultSharedPreferences(this)
            val disableScreenshots = prefs.getBoolean(PREF_KEY_DISABLE_SCREENSHOTS, false)
            if (disableScreenshots) {
                window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
            } else {
                window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
            }
        } catch (ignored: Exception) { }

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

        idleCheckRunnable = object : Runnable {
            override fun run() {
                val idle = System.currentTimeMillis() - lastUserInputTime
                if (idle >= 25000) {
                    if (dialogs.isNotEmpty()) {
                        startRandomDialog()
                    } else if (dialogLines.isNotEmpty()) {
                        triggerRandomDialog()
                    }
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
    }

    override fun onPause() {
        super.onPause()
        stopDialog()
        dialogHandler.removeCallbacksAndMessages(null)
    }

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
            try {
                setTextColor(Color.parseColor(currentThemeColor))
            } catch (e: Exception) {
                setTextColor(Color.WHITE)
            }
        }
        item.addView(tv, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        messagesContainer.addView(item)

        if (messagesContainer.childCount > MAX_MESSAGES) {
            val removeCount = messagesContainer.childCount - MAX_MESSAGES
            repeat(removeCount) {
                messagesContainer.removeViewAt(0)
            }
        }

        scrollView.post {
            scrollView.smoothScrollTo(0, messagesContainer.bottom)
        }
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
            val dir = DocumentFile.fromTreeUri(this, folderUri!!)
            if (dir == null || !dir.exists() || !dir.isDirectory) {
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
                                if (keyword.isNotEmpty() && contextFile.isNotEmpty()) {
                                    contextMap[keyword] = contextFile
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
                                val keyword = parts[0].trim().lowercase(Locale.ROOT)
                                val responses = parts[1].split("\\|".toRegex())
                                val responseList = mutableListOf<String>()
                                for (r in responses) {
                                    val rr = r.trim()
                                    if (rr.isNotEmpty()) responseList.add(rr)
                                }
                                if (keyword.isNotEmpty() && responseList.isNotEmpty()) {
                                    keywordResponses[keyword] = responseList
                                }
                            }
                        }
                        return@forEachLine
                    }

                    if (!l.contains("=")) return@forEachLine
                    val parts = l.split("=", limit = 2)
                    if (parts.size == 2) {
                        val trigger = parts[0].trim().lowercase(Locale.ROOT)
                        val responses = parts[1].split("\\|".toRegex())
                        val responseList = mutableListOf<String>()
                        for (r in responses) {
                            val rr = r.trim()
                            if (rr.isNotEmpty()) responseList.add(rr)
                        }
                        if (trigger.isNotEmpty() && responseList.isNotEmpty()) {
                            templatesMap[trigger] = responseList
                        }
                    }
                }
            }

            // metadata
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
                            line.startsWith("mascot_name=") -> {
                                currentMascotName = line.substring("mascot_name=".length).trim()
                            }
                            line.startsWith("mascot_icon=") -> {
                                currentMascotIcon = line.substring("mascot_icon=".length).trim()
                            }
                            line.startsWith("theme_color=") -> {
                                currentThemeColor = line.substring("theme_color=".length).trim()
                            }
                            line.startsWith("theme_background=") -> {
                                currentThemeBackground = line.substring("theme_background=".length).trim()
                            }
                            line.startsWith("dialog_lines=") -> {
                                val lines = line.substring("dialog_lines=".length).split("\\|".toRegex())
                                for (dialog in lines) {
                                    val d = dialog.trim()
                                    if (d.isNotEmpty()) dialogLines.add(d)
                                }
                            }
                        }
                    }
                }
            }

            // ---------- исправленный парсер randomreply.txt ----------
            val dialogFile = dir.findFile("randomreply.txt")
            if (dialogFile != null && dialogFile.exists()) {
                try {
                    contentResolver.openInputStream(dialogFile.uri)?.bufferedReader()?.use { reader ->
                        var current: Dialog? = null
                        reader.forEachLine { raw ->
                            val l = raw.trim()
                            if (l.isEmpty()) return@forEachLine

                            // section header
                            if (l.startsWith(";")) {
                                if (current != null && current.replies.isNotEmpty()) {
                                    dialogs.add(current)
                                }
                                current = Dialog(l.substring(1).trim())
                                return@forEachLine
                            }

                            // mascot>text
                            if (l.contains(">")) {
                                val parts = l.split(">", limit = 2)
                                if (parts.size == 2) {
                                    val mascot = parts[0].trim()
                                    val text = parts[1].trim()
                                    if (mascot.isNotEmpty() && text.isNotEmpty()) {
                                        if (current == null) current = Dialog("default")
                                        val reply = mapOf(
                                            "mascot" to mascot,
                                            "text" to text
                                        )
                                        current.replies.add(reply)
                                    }
                                }
                                return@forEachLine
                            }

                            // fallback — treat as dialog line (unstructured short line)
                            dialogLines.add(l)
                        }

                        if (current != null && current.replies.isNotEmpty()) {
                            dialogs.add(current)
                        }
                    }
                } catch (e: Exception) {
                    showCustomToast("Ошибка чтения randomreply.txt: ${e.message}")
                }
            }

            // pick random mascot for base
            if (filename == "base.txt" && mascotList.isNotEmpty()) {
                val selectedMascot = mascotList[random.nextInt(mascotList.size)]
                selectedMascot["name"]?.let { currentMascotName = it }
                selectedMascot["icon"]?.let { currentMascotIcon = it }
                selectedMascot["color"]?.let { currentThemeColor = it }
                selectedMascot["background"]?.let { currentThemeBackground = it }
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
        val suggestionsList = mutableListOf<String>()
        suggestionsList.addAll(templatesMap.keys)
        for (s in fallback) {
            val low = s.lowercase(Locale.ROOT)
            if (!suggestionsList.contains(low)) {
                suggestionsList.add(low)
            }
        }

        if (adapter == null) {
            adapter = ArrayAdapter(this, android.R.layout.simple_dropdown_item_1line, suggestionsList)
            queryInput.setAdapter(adapter)
            queryInput.threshold = 1
        } else {
            adapter?.clear()
            adapter?.addAll(suggestionsList)
            adapter?.notifyDataSetChanged()
        }
    }

    // ======= Idle / random dialogs =======
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
                } else {
                    addChatMessage(currentMascotName, dialog)
                }
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
                    dialogHandler.postDelayed({
                        startRandomDialog()
                    }, (random.nextInt(20000) + 5000).toLong())
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
        title = "ChatTribe - $mascotName"
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
                } catch (ignored: Exception) { }
            }
        }
        try {
            messagesContainer.setBackgroundColor(Color.parseColor(themeBackground))
        } catch (e: Exception) {
            // ignore
        }
    }

    private fun detectContext(input: String): String? {
        val lower = input.lowercase(Locale.ROOT)
        for ((keyword, value) in contextMap) {
            if (lower.contains(keyword)) {
                return value
            }
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
