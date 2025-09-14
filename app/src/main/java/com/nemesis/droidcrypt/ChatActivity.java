package com.nemesis.droidcrypt

import android.animation.ObjectAnimator
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.preference.PreferenceManager
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.documentfile.provider.DocumentFile
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.*
import kotlin.math.roundToInt
import kotlin.random.Random

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
    private lateinit var clearButton: Button
    private lateinit var mascotImage: ImageView
    private lateinit var messagesContainer: LinearLayout
    private var adapter: ArrayAdapter<String>? = null

    private val fallback = arrayOf("Привет", "Как дела?", "Расскажи о себе", "Выход")
    private val templatesMap = mutableMapOf<String, List<String>>()
    private val contextMap = mutableMapOf<String, String>()
    private val keywordResponses = mutableMapOf<String, List<String>>()
    private val antiSpamResponses = listOf(
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
    private val random = Random.Default
    private val queryCountMap = mutableMapOf<String, Int>()

    data class Dialog(val name: String, val replies: MutableList<Map<String, String>> = mutableListOf())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_chat)

        scrollView = findViewById(R.id.scrollView)
        queryInput = findViewById(R.id.queryInput)
        sendButton = findViewById(R.id.sendButton)
        clearButton = findViewById(R.id.clearButton)
        mascotImage = findViewById(R.id.mascot_image)
        messagesContainer = findViewById(R.id.chatMessagesContainer)

        folderUri = intent?.getParcelableExtra<Uri>("folderUri") ?: contentResolver.persistedUriPermissions
            .firstOrNull { it.isReadPermission }?.uri

        PreferenceManager.getDefaultSharedPreferences(this).run {
            if (folderUri == null) {
                getString(PREF_KEY_FOLDER_URI, null)?.let { savedUri ->
                    folderUri = try {
                        Uri.parse(savedUri)
                    } catch (e: Exception) {
                        null
                    }
                }
            }
            if (getBoolean(PREF_KEY_DISABLE_SCREENSHOTS, false)) {
                window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
            } else {
                window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
            }
        }

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

        queryInput.setOnItemClickListener { parent, _, position, _ ->
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

        clearButton.setOnClickListener { clearChat() }

        idleCheckRunnable = Runnable {
            val idle = System.currentTimeMillis() - lastUserInputTime
            if (idle >= 25_000) {
                if (dialogs.isNotEmpty()) startRandomDialog()
                else if (dialogLines.isNotEmpty()) triggerRandomDialog()
            }
            dialogHandler.postDelayed(this, 5_000)
        }
        dialogHandler.postDelayed(idleCheckRunnable!!, 5_000)
    }

    override fun onResume() {
        super.onResume()
        folderUri?.let { loadTemplatesFromFile(currentContext) }
        updateAutoComplete()
        dialogHandler.removeCallbacks(idleCheckRunnable!!)
        dialogHandler.postDelayed(idleCheckRunnable!!, 5_000)
    }

    override fun onPause() {
        super.onPause()
        stopDialog()
        dialogHandler.removeCallbacksAndMessages(null)
    }

    private fun processUserQuery(userInput: String) {
        val qOrig = userInput.trim().toLowerCase()
        if (qOrig.isEmpty()) return

        lastUserInputTime = System.currentTimeMillis()
        stopDialog()

        if (qOrig == lastQuery) {
            queryCountMap[qOrig] = queryCountMap.getOrDefault(qOrig, 0) + 1
        } else {
            queryCountMap.clear()
            queryCountMap[qOrig] = 1
            lastQuery = qOrig
        }

        addChatMessage("Ты", userInput)

        val repeats = queryCountMap.getOrDefault(qOrig, 0)
        if (repeats >= 5) {
            val spamResp = antiSpamResponses.random()
            addChatMessage(currentMascotName, spamResp)
            startIdleTimer()
            return
        }

        val visited = mutableSetOf<String>()
        var context = currentContext ?: "base.txt"
        var answered = false
        var switches = 0

        while (switches <= MAX_CONTEXT_SWITCH && !answered) {
            visited.add(context)

            if (context != currentContext) {
                currentContext = context
                loadTemplatesFromFile(currentContext)
                updateAutoComplete()
            }

            templatesMap[qOrig]?.let { possible ->
                if (possible.isNotEmpty()) {
                    val resp = possible.random()
                    addChatMessage(currentMascotName, resp)
                    triggerRandomDialog()
                    startIdleTimer()
                    answered = true
                    return@let
                }
            }

            keywordResponses.entries.firstOrNull { (keyword, _) ->
                qOrig.contains(keyword)
            }?.let { entry ->
                val responses = entry.value
                if (responses.isNotEmpty()) {
                    val resp = responses.random()
                    addChatMessage(currentMascotName, resp)
                    triggerRandomDialog()
                    startIdleTimer()
                    answered = true
                    return@let
                }
            }?.also { answered = true }

            if (answered) return

            if (context != "base.txt" && !visited.contains("base.txt")) {
                context = "base.txt"
                switches++
                continue
            }

            contextMap.entries.firstOrNull { (keyword, mappedFile) ->
                qOrig.contains(keyword) && mappedFile != null && !visited.contains(mappedFile)
            }?.let {
                context = it.value
                switches++
                return@let
            } ?: run {
                val fallbackResp = getDummyResponse(qOrig)
                addChatMessage(currentMascotName, fallbackResp)
                triggerRandomDialog()
                startIdleTimer()
                answered = true
            }
        }

        if (!answered) addChatMessage(currentMascotName, "Не могу найти ответ, попробуй переформулировать.")
    }

    private fun addChatMessage(sender: String, text: String) {
        messagesContainer ?: return

        LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val pad = dpToPx(8)
            setPadding(pad, pad / 2, pad, pad / 2)

            addView(TextView(this@ChatActivity).apply {
                text = "$sender:"
                textSize = 12f
                setTextColor(Color.parseColor("#AAAAAA"))
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            })

            addView(TextView(this@ChatActivity).apply {
                this.text = text
                textSize = 16f
                isTextSelectable = true
                setTextColor(try {
                    Color.parseColor(currentThemeColor)
                } catch (e: Exception) {
                    Color.WHITE
                })
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            })

            messagesContainer.addView(this)
        }

        if (messagesContainer.childCount > MAX_MESSAGES) {
            repeat(messagesContainer.childCount - MAX_MESSAGES) {
                messagesContainer.removeViewAt(0)
            }
        }

        scrollView.post { scrollView.smoothScrollTo(0, messagesContainer.bottom) }
    }

    private fun dpToPx(dp: Int): Int = (dp * resources.displayMetrics.density).roundToInt()

    private fun clearChat() {
        messagesContainer.removeAllViews()
        queryCountMap.clear()
        lastQuery = ""
        currentContext = "base.txt"
        loadTemplatesFromFile(currentContext)
        updateAutoComplete()
        addChatMessage(currentMascotName, "Чат очищен. Возвращаюсь к началу.")
    }

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
            val dir = DocumentFile.fromTreeUri(this, folderUri!!) ?: return loadFallbackTemplates().also {
                updateUI(currentMascotName, currentMascotIcon, currentThemeColor, currentThemeBackground)
            }
            if (!dir.exists() || !dir.isDirectory) {
                loadFallbackTemplates()
                updateUI(currentMascotName, currentMascotIcon, currentThemeColor, currentThemeBackground)
                return
            }

            val file = dir.findFile(filename) ?: return loadFallbackTemplates().also {
                updateUI(currentMascotName, currentMascotIcon, currentThemeColor, currentThemeBackground)
            }
            if (!file.exists()) {
                loadFallbackTemplates()
                updateUI(currentMascotName, currentMascotIcon, currentThemeColor, currentThemeBackground)
                return
            }

            contentResolver.openInputStream(file.uri).use { inputStream ->
                BufferedReader(InputStreamReader(inputStream)).use { reader ->
                    reader.lineSequence().forEach { line ->
                        val l = line.trim()
                        if (l.isEmpty()) return@forEach

                        if (filename == "base.txt" && l.startsWith(":") && l.endsWith(":")) {
                            val contextLine = l.substring(1, l.length - 1)
                            if ("=" in contextLine) {
                                val (keyword, contextFile) = contextLine.split("=", limit = 2).map { it.trim() }
                                if (keyword.isNotEmpty() && contextFile.isNotEmpty()) {
                                    contextMap[keyword.toLowerCase()] = contextFile
                                }
                            }
                            return@forEach
                        }

                        if (l.startsWith("-")) {
                            val keywordLine = l.substring(1)
                            if ("=" in keywordLine) {
                                val (keyword, responses) = keywordLine.split("=", limit = 2)
                                val responseList = responses.split("|").map { it.trim() }.filter { it.isNotEmpty() }
                                if (keyword.trim().isNotEmpty() && responseList.isNotEmpty()) {
                                    keywordResponses[keyword.trim().toLowerCase()] = responseList
                                }
                            }
                            return@forEach
                        }

                        if ("=" !in l) return@forEach
                        val (trigger, responses) = l.split("=", limit = 2)
                        val responseList = responses.split("|").map { it.trim() }.filter { it.isNotEmpty() }
                        if (trigger.trim().isNotEmpty() && responseList.isNotEmpty()) {
                            templatesMap[trigger.trim().toLowerCase()] = responseList
                        }
                    }
                }
            }

            val metadataFilename = filename.replace(".txt", "_metadata.txt")
            dir.findFile(metadataFilename)?.let { metadataFile ->
                if (metadataFile.exists()) {
                    contentResolver.openInputStream(metadataFile.uri).use { inputStream ->
                        BufferedReader(InputStreamReader(inputStream)).use { reader ->
                            reader.lineSequence().forEach { line ->
                                when {
                                    line.startsWith("mascot_list=") -> {
                                        line.substringAfter("mascot_list=").split("|").forEach { mascot ->
                                            val parts = mascot.split(":", limit = 4)
                                            if (parts.size == 4) {
                                                mascotList.add(
                                                    mapOf(
                                                        "name" to parts[0].trim(),
                                                        "icon" to parts[1].trim(),
                                                        "color" to parts[2].trim(),
                                                        "background" to parts[3].trim()
                                                    )
                                                )
                                            }
                                        }
                                    }
                                    line.startsWith("mascot_name=") -> currentMascotName = line.substringAfter("mascot_name=").trim()
                                    line.startsWith("mascot_icon=") -> currentMascotIcon = line.substringAfter("mascot_icon=").trim()
                                    line.startsWith("theme_color=") -> currentThemeColor = line.substringAfter("theme_color=").trim()
                                    line.startsWith("theme_background=") -> currentThemeBackground = line.substringAfter("theme_background=").trim()
                                    line.startsWith("dialog_lines=") -> {
                                        line.substringAfter("dialog_lines=").split("|").forEach { dialog ->
                                            dialog.trim().takeIf { it.isNotEmpty() }?.let { dialogLines.add(it) }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }

            dir.findFile("randomreply.txt")?.let { dialogFile ->
                if (dialogFile.exists()) {
                    contentResolver.openInputStream(dialogFile.uri).use { inputStream ->
                        BufferedReader(InputStreamReader(inputStream)).use { reader ->
                            var current: Dialog? = null
                            reader.lineSequence().forEach { line ->
                                val trimmedLine = line.trim()
                                if (trimmedLine.isEmpty()) return@forEach

                                if (trimmedLine.startsWith(";")) {
                                    current?.takeIf { it.replies.isNotEmpty() }?.let { dialogs.add(it) }
                                    current = Dialog(trimmedLine.substring(1).trim())
                                    return@forEach
                                }

                                if (">" in trimmedLine) {
                                    val (mascot, text) = trimmedLine.split(">", limit = 2).map { it.trim() }
                                    if (mascot.isNotEmpty() && text.isNotEmpty()) {
                                        if (current == null) current = Dialog("default")
                                        current!!.replies.add(mapOf("mascot" to mascot, "text" to text))
                                    }
                                    return@forEach
                                }

                                dialogLines.add(trimmedLine)
                            }
                            current?.takeIf { it.replies.isNotEmpty() }?.let { dialogs.add(it) }
                        }
                    }
                }
            }

            if (filename == "base.txt" && mascotList.isNotEmpty()) {
                val selectedMascot = mascotList.random()
                currentMascotName = selectedMascot["name"] ?: currentMascotName
                currentMascotIcon = selectedMascot["icon"] ?: currentMascotIcon
                currentThemeColor = selectedMascot["color"] ?: currentThemeColor
                currentThemeBackground = selectedMascot["background"] ?: currentThemeBackground
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
        templatesMap["привет"] = listOf("Привет! Чем могу помочь?", "Здравствуй!")
        templatesMap["как дела"] = listOf("Всё отлично, а у тебя?", "Нормально, как дела?")
        keywordResponses["спасибо"] = listOf("Рад, что помог!", "Всегда пожалуйста!")
    }

    private fun updateAutoComplete() {
        val suggestionsList = (templatesMap.keys + fallback.filterNot { it.toLowerCase() in templatesMap.keys }).toMutableList()
        adapter = adapter?.apply {
            clear()
            addAll(suggestionsList)
            notifyDataSetChanged()
        } ?: ArrayAdapter(this, android.R.layout.simple_dropdown_item_1line, suggestionsList).also {
            queryInput.setAdapter(it)
            queryInput.threshold = 1
        }
    }

    private fun triggerRandomDialog() {
        if (dialogLines.isNotEmpty() && Random.nextDouble() < 0.3) {
            dialogHandler.postDelayed({
                if (dialogLines.isEmpty()) return@postDelayed
                val dialog = dialogLines.random()
                val rnd = if (mascotList.isNotEmpty()) mascotList.random() else null
                val rndName = rnd?.get("name") ?: currentMascotName
                loadMascotMetadata(rndName)
                addChatMessage(rndName, dialog)
            }, 1_500)
        }
        if (mascotList.isNotEmpty() && Random.nextDouble() < 0.1) {
            dialogHandler.postDelayed({
                val rnd = mascotList.random()
                val rndName = rnd["name"] ?: currentMascotName
                loadMascotMetadata(rndName)
                addChatMessage(rndName, "Эй, мы не закончили!")
            }, 2_500)
        }
    }

    private fun startRandomDialog() {
        if (dialogs.isEmpty()) return
        stopDialog()
        currentDialog = dialogs.random()
        currentDialogIndex = 0
        dialogRunnable = Runnable {
            currentDialog?.let { dialog ->
                if (currentDialogIndex < dialog.replies.size) {
                    val reply = dialog.replies[currentDialogIndex]
                    val mascot = reply["mascot"] ?: return@let
                    val text = reply["text"] ?: return@let
                    loadMascotMetadata(mascot)
                    addChatMessage(mascot, text)
                    currentDialogIndex++
                    dialogHandler.postDelayed(this, Random.nextLong(10_000, 25_000))
                } else {
                    dialogHandler.postDelayed({ startRandomDialog() }, Random.nextLong(5_000, 25_000))
                }
            }
        }
        dialogHandler.postDelayed(dialogRunnable!!, Random.nextLong(10_000, 25_000))
    }

    private fun stopDialog() {
        dialogRunnable?.let { dialogHandler.removeCallbacks(it) }
        dialogRunnable = null
    }

    private fun loadMascotMetadata(mascotName: String) {
        folderUri ?: return
        val metadataFilename = "${mascotName.toLowerCase()}_metadata.txt"
        val dir = DocumentFile.fromTreeUri(this, folderUri!!) ?: return
        dir.findFile(metadataFilename)?.takeIf { it.exists() }?.let { metadataFile ->
            try {
                contentResolver.openInputStream(metadataFile.uri).use { inputStream ->
                    BufferedReader(InputStreamReader(inputStream)).use { reader ->
                        reader.lineSequence().forEach { line ->
                            when {
                                line.startsWith("mascot_name=") -> currentMascotName = line.substringAfter("mascot_name=").trim()
                                line.startsWith("mascot_icon=") -> currentMascotIcon = line.substringAfter("mascot_icon=").trim()
                                line.startsWith("theme_color=") -> currentThemeColor = line.substringAfter("theme_color=").trim()
                                line.startsWith("theme_background=") -> currentThemeBackground = line.substringAfter("theme_background=").trim()
                            }
                        }
                        updateUI(currentMascotName, currentMascotIcon, currentThemeColor, currentThemeBackground)
                    }
                }
            } catch (e: Exception) {
                showCustomToast("Ошибка загрузки метаданных маскота: ${e.message}")
            }
        }
    }

    private fun updateUI(mascotName: String, mascotIcon: String, themeColor: String, themeBackground: String) {
        title = "ChatTribe - $mascotName"
        folderUri?.let {
            try {
                val dir = DocumentFile.fromTreeUri(this, it)
                dir?.findFile(mascotIcon)?.takeIf { it.exists() }?.let { iconFile ->
                    contentResolver.openInputStream(iconFile.uri).use { inputStream ->
                        val bitmap = BitmapFactory.decodeStream(inputStream)
                        mascotImage.setImageBitmap(bitmap)
                        mascotImage.alpha = 0f
                        ObjectAnimator.ofFloat(mascotImage, "alpha", 0f, 1f).setDuration(450).start()
                    }
                }
            } catch (_: Exception) {}
        }
        try {
            messagesContainer.setBackgroundColor(Color.parseColor(themeBackground))
        } catch (_: Exception) {}
    }

    private fun getDummyResponse(query: String): String = when {
        "привет" in query -> "Привет! Чем могу помочь?"
        "как дела" in query -> "Всё отлично, а у тебя?"
        else -> "Не понял запрос. Попробуй другой вариант."
    }

    private fun showCustomToast(message: String) {
        try {
            val layout = layoutInflater.inflate(R.layout.custom_toast, null)
            layout.findViewById<TextView>(R.id.customToastText).text = message
            Toast(this).apply {
                duration = Toast.LENGTH_SHORT
                view = layout
                show()
            }
        } catch (e: Exception) {
            Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
        }
    }

    private fun startIdleTimer() {
        lastUserInputTime = System.currentTimeMillis()
        dialogHandler.removeCallbacks(idleCheckRunnable!!)
        dialogHandler.postDelayed(idleCheckRunnable!!, 5_000)
    }
}
