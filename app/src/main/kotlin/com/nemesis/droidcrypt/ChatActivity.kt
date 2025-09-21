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
import android.view.WindowManager
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.graphics.ColorUtils
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.lifecycleScope
import androidx.preference.PreferenceManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.*
import kotlin.collections.ArrayList
import kotlin.collections.HashMap
import kotlin.math.roundToInt
import com.nemesis.droidcrypt.Engine
import com.nemesis.droidcrypt.ChatCore
import com.nemesis.droidcrypt.MemoryManager
import com.nemesis.droidcrypt.R

class ChatActivity : AppCompatActivity(), TextToSpeech.OnInitListener {

    companion object {
        private const val PREF_KEY_FOLDER_URI = "folderUri"
        private const val PREF_KEY_DISABLE_SCREENSHOTS = "disableScreenshots"
    }

    private lateinit var engine: Engine

    private var folderUri: Uri? = null
    private lateinit var messagesRecycler: RecyclerView
    private lateinit var queryInputLayout: TextInputLayout
    private lateinit var queryInput: TextInputEditText
    private var envelopeInputButton: ImageButton? = null
    private var btnLock: ImageButton? = null
    private var btnTrash: ImageButton? = null
    private var btnEnvelopeTop: ImageButton? = null
    private var btnSettings: ImageButton? = null
    private val messages = mutableListOf<Pair<String, String>>()
    private lateinit var messagesAdapter: MessagesAdapter

    private var tts: TextToSpeech? = null

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

    private val dialogHandler = Handler(Looper.getMainLooper())
    private var idleCheckRunnable: Runnable? = null
    private var lastUserInputTime = System.currentTimeMillis()
    private val random = Random()
    private val queryTimestamps = HashMap<String, MutableList<Long>>()
    private var lastSendTime = 0L
    private val queryCache = object : LinkedHashMap<String, String>(Engine.MAX_CACHE_SIZE, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?): Boolean {
            return size > Engine.MAX_CACHE_SIZE
        }
    }

    private fun canonicalKeyFromTokens(tokens: List<String>): String = tokens.sorted().joinToString(" ")
    private fun canonicalKeyFromTextStatic(text: String, synonyms: Map<String, String>, stopwords: Set<String>): String {
        val toks = Engine.filterStopwordsAndMapSynonymsStatic(text, synonyms, stopwords).first
        return canonicalKeyFromTokens(toks)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_chat)
        window.addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN)
        supportActionBar?.hide() // Скрываем ActionBar для полноэкранного режима

        ChatCore.init(applicationContext)

        messagesRecycler = findViewById(R.id.messagesRecycler)
        queryInputLayout = findViewById(R.id.inputContainer)
        queryInput = findViewById(R.id.queryInput)
        envelopeInputButton = findViewById(R.id.envelope_button) // Если есть в новом макете
        btnLock = findViewById(R.id.btn_lock)
        btnTrash = findViewById(R.id.btn_trash)
        btnEnvelopeTop = findViewById(R.id.btn_envelope_top)
        btnSettings = findViewById(R.id.btn_settings)

        // Настройка RecyclerView
        messagesRecycler.layoutManager = LinearLayoutManager(this)
        messagesAdapter = MessagesAdapter(messages)
        messagesRecycler.adapter = messagesAdapter

        folderUri = intent?.getParcelableExtra("folderUri")
        if (folderUri == null) {
            for (perm in contentResolver.persistedUriPermissions) {
                if (perm.isReadPermission) {
                    folderUri = perm.uri; break
                }
            }
        }
        
        val prefs = PreferenceManager.getDefaultSharedPreferences(this)
        if (folderUri == null) {
            val saved = prefs.getString(PREF_KEY_FOLDER_URI, null)
            if (saved != null) folderUri = Uri.parse(saved)
        }
        
        ChatCore.loadSynonymsAndStopwords(this, folderUri, synonymsMap, stopwords)

        engine = Engine(templatesMap, synonymsMap, stopwords)

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                MemoryManager.init(this@ChatActivity)
                MemoryManager.loadTemplatesFromFolder(this@ChatActivity, folderUri)
            } catch (e: Exception) {
            }
            withContext(Dispatchers.Main) {
                rebuildInvertedIndex()
                engine.computeTokenWeights()
            }
        }
        
        val disable = prefs.getBoolean(PREF_KEY_DISABLE_SCREENSHOTS, false)
        if (disable) window.addFlags(WindowManager.LayoutParams.FLAG_SECURE) else window.clearFlags(
            WindowManager.LayoutParams.FLAG_SECURE
        )

        loadToolbarIcons()
        setupIconTouchEffect(btnLock)
        setupIconTouchEffect(btnTrash)
        setupIconTouchEffect(btnEnvelopeTop)
        setupIconTouchEffect(btnSettings)
        setupIconTouchEffect(envelopeInputButton)

        btnLock?.setOnClickListener { finish() }
        btnTrash?.setOnClickListener { clearChat() }
        btnEnvelopeTop?.setOnClickListener { startActivity(Intent(this, SetupActivity::class.java)) }
        btnSettings?.setOnClickListener { startActivity(Intent(this, SettingsActivity::class.java)) }

        envelopeInputButton?.setOnClickListener {
            val now = System.currentTimeMillis()
            if (now - lastSendTime < Engine.SPAM_WINDOW_MS) return@setOnClickListener
            lastSendTime = now
            val input = queryInput.text.toString().trim()
            if (input.isNotEmpty()) {
                processUserQuery(input)
                queryInput.setText("")
            }
        }

        queryInput.setOnEditorActionListener { _, _, _ ->
            val now = System.currentTimeMillis()
            if (now - lastSendTime < Engine.SPAM_WINDOW_MS) return@setOnEditorActionListener true
            lastSendTime = now
            val input = queryInput.text.toString().trim()
            if (input.isNotEmpty()) {
                processUserQuery(input)
                queryInput.setText("")
            }
            true
        }

        tts = TextToSpeech(this, this)

        if (folderUri == null) {
            showCustomToast(getString(R.string.toast_folder_not_selected))
            ChatCore.loadFallbackTemplates(templatesMap, keywordResponses, mascotList, contextMap)
            rebuildInvertedIndex()
            engine.computeTokenWeights()
            updateAutoComplete()
            addChatMessage(currentMascotName, getString(R.string.welcome_message))
        } else {
            loadTemplatesFromFile(currentContext)
            try {
                MemoryManager.loadTemplatesFromFolder(this, folderUri)
            } catch (_: Exception) {}
            rebuildInvertedIndex()
            engine.computeTokenWeights()
            updateAutoComplete()
            addChatMessage(currentMascotName, getString(R.string.welcome_message))
        }

        queryInput.setOnItemClickListener { parent, _, position, _ ->
            val selected = parent.getItemAtPosition(position) as String
            queryInput.setText(selected)
            processUserQuery(selected)
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

    // Остальной код остаётся без изменений, но с оптимизацией в тяжёлых методах (например, loadTemplatesFromFile в корутинах)

    private fun loadTemplatesFromFile(filename: String) {
        lifecycleScope.launch(Dispatchers.IO) {
            templatesMap.clear()
            keywordResponses.clear()
            mascotList.clear()
            if (filename == "base.txt") {
                contextMap.clear()
            }
            currentMascotName = "Racky"
            currentMascotIcon = "raccoon_icon.png"
            currentThemeColor = "#00FF00"
            currentThemeBackground = "#000000"

            ChatCore.loadSynonymsAndStopwords(this@ChatActivity, folderUri, synonymsMap, stopwords)

            if (folderUri == null) {
                ChatCore.loadFallbackTemplates(templatesMap, keywordResponses, mascotList, contextMap)
                withContext(Dispatchers.Main) {
                    rebuildInvertedIndex()
                    engine.computeTokenWeights()
                    updateUI(currentMascotName, currentMascotIcon, currentThemeColor, currentThemeBackground)
                }
                return@launch
            }
            try {
                val dir = DocumentFile.fromTreeUri(this@ChatActivity, folderUri!!) ?: run {
                    withContext(Dispatchers.Main) {
                        ChatCore.loadFallbackTemplates(templatesMap, keywordResponses, mascotList, contextMap)
                        rebuildInvertedIndex()
                        engine.computeTokenWeights()
                        updateUI(currentMascotName, currentMascotIcon, currentThemeColor, currentThemeBackground)
                    }
                    return@launch
                }
                val file = dir.findFile(filename)
                if (file == null || !file.exists()) {
                    withContext(Dispatchers.Main) {
                        ChatCore.loadFallbackTemplates(templatesMap, keywordResponses, mascotList, contextMap)
                        rebuildInvertedIndex()
                        engine.computeTokenWeights()
                        updateUI(currentMascotName, currentMascotIcon, currentThemeColor, currentThemeBackground)
                    }
                    return@launch
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
                withContext(Dispatchers.Main) {
                    rebuildInvertedIndex()
                    engine.computeTokenWeights()
                    updateUI(currentMascotName, currentMascotIcon, currentThemeColor, currentThemeBackground)
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    showCustomToast(getString(R.string.error_reading_file, e.message ?: ""))
                    ChatCore.loadFallbackTemplates(templatesMap, keywordResponses, mascotList, contextMap)
                    rebuildInvertedIndex()
                    engine.computeTokenWeights()
                    updateUI(currentMascotName, currentMascotIcon, currentThemeColor, currentThemeBackground)
                }
            }
        }
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
                title = getString(R.string.app_title_prefix, mascotName)
                try {
                    messagesContainer.setBackgroundColor(Color.parseColor(themeBackground))
                } catch (e: Exception) {
                }
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
        lastUserInputTime = System.currentTimeMillis()
        idleCheckRunnable?.let {
            dialogHandler.removeCallbacks(it)
            dialogHandler.postDelayed(it, 5000)
        }
    }

}
