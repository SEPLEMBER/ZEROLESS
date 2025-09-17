package com.nemesis.droidcrypt

import android.R.color
import android.animation.ObjectAnimator
import android.content.*
import android.graphics.*
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.ColorDrawable
import android.media.MediaPlayer
import android.net.Uri
import android.os.BatteryManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.preference.PreferenceManager
import android.provider.Settings
import android.speech.tts.TextToSpeech
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.graphics.ColorUtils
import androidx.core.view.children
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.*
import kotlin.math.abs
import kotlin.math.roundToInt
import android.bluetooth.BluetoothAdapter
import android.graphics.BitmapFactory
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.text.format.DateFormat
import com.nemesis.droidcrypt.databinding.ActivityChatBinding

data class DataSnapshots(
    val contextMap: Map<String, String>,
    val templatesMap: Map<String, List<String>>,
    val keywordResponses: Map<String, List<String>>
)

class ChatActivity : AppCompatActivity(), TextToSpeech.OnInitListener {

    companion object {
        private const val PREF_KEY_FOLDER_URI = "pref_folder_uri"
        private const val PREF_KEY_DISABLE_SCREENSHOTS = "pref_disable_screenshots"
        private const val MAX_MESSAGES = 250
        private const val MAX_CANDIDATES_FOR_LEV = 25
        private const val JACCARD_THRESHOLD = 0.50
        private const val SEND_DEBOUNCE_MS = 400L
    }

    // --- утилиты для безопасного доступа к topBar/include-элементам ---
    private fun findTopBar(): ViewGroup? {
        val id = resources.getIdentifier("topBar", "id", packageName)
        val v = if (id != 0) {
            binding.root.findViewById<View>(id) ?: run { try { findViewById(id) } catch (_: Exception) { null } }
        } else null
        return v as? ViewGroup
    }

    private fun findTopBarChildView(idName: String): View? {
        val top = findTopBar() ?: return null
        val childId = resources.getIdentifier(idName, "id", packageName)
        if (childId == 0) return null
        return top.findViewById(childId)
    }

    private fun findTopBarChildViewGroup(idName: String): ViewGroup? {
        return findTopBarChildView(idName) as? ViewGroup
    }

    private fun findTopBarChildImageButton(idName: String): ImageButton? {
        return findTopBarChildView(idName) as? ImageButton
    }
    // --- конец утилит ---

    private fun getFuzzyDistance(word: String): Int {
        return when {
            word.length <= 4 -> 2
            word.length <= 8 -> 2
            else -> 3
        }
    }

    private lateinit var binding: ActivityChatBinding
    private var folderUri: Uri? = null
    private var mascotTopImage: ImageView? = null

    private var tts: TextToSpeech? = null
    private val fallback = mutableListOf<String>()
    private val templatesMap = HashMap<String, MutableList<String>>()
    private val contextMap = HashMap<String, String>()
    private val keywordResponses = HashMap<String, MutableList<String>>()
    private val antiSpamResponses = mutableListOf<String>()
    private val mascotList = mutableListOf<Map<String, String>>()
    private val invertedIndex = HashMap<String, MutableList<String>>()
    private val synonymsMap = HashMap<String, String>()
    private val stopwords = HashSet<String>()
    private var currentMascotName: String = ""
    private var currentMascotIcon: String = ""
    private var currentThemeColor: String = ""
    private var currentThemeBackground: String = ""
    private var currentContext: String = ""
    private var lastQuery = ""
    private var userActivityCount = 0

    private val dialogHandler = Handler(Looper.getMainLooper())
    private var idleCheckRunnable: Runnable? = null
    private var lastUserInputTime = System.currentTimeMillis()
    private val random = Random()
    private val queryCountMap = HashMap<String, Int>()
    private var lastSendTime = 0L
    private var lastBatteryWarningStage = Int.MAX_VALUE

    private var batteryReceiver: BroadcastReceiver? = null
    private var networkReceiver: BroadcastReceiver? = null
    private var bluetoothReceiver: BroadcastReceiver? = null
    private val timeHandler = Handler(Looper.getMainLooper())
    private var timeUpdaterRunnable: Runnable? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityChatBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Инициализация строк/списков, которые требуют Context
        currentMascotName = getString(R.string.default_mascot_name)
        currentMascotIcon = getString(R.string.raccoon_icon)
        currentThemeColor = getString(R.string.default_theme_color)
        currentThemeBackground = getString(R.string.default_theme_background)
        currentContext = getString(R.string.base_context_file)

        fallback.clear()
        fallback.addAll(
            listOf(
                getString(R.string.suggestion_hello),
                getString(R.string.suggestion_how_are_you),
                getString(R.string.suggestion_tell_about),
                getString(R.string.suggestion_exit)
            )
        )

        antiSpamResponses.clear()
        antiSpamResponses.addAll(
            listOf(
                getString(R.string.anti_spam_1),
                getString(R.string.anti_spam_2),
                getString(R.string.anti_spam_3),
                getString(R.string.anti_spam_4),
                getString(R.string.anti_spam_5),
                getString(R.string.anti_spam_6),
                getString(R.string.anti_spam_7),
                getString(R.string.anti_spam_8),
                getString(R.string.anti_spam_9),
                getString(R.string.anti_spam_10)
            )
        )

        window.addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN)
        supportActionBar?.setBackgroundDrawable(ColorDrawable(Color.argb(128, 0, 0, 0)))
        window.setBackgroundDrawable(ColorDrawable(Color.BLACK))

        setupToolbar()
        initializeFolderUri()
        applySecuritySettings()
        loadToolbarIcons()
        setupListeners()

        tts = TextToSpeech(this, this)

        if (folderUri == null) {
            showCustomToast(getString(R.string.folder_not_selected))
            loadFallbackTemplates()
        } else {
            loadTemplatesFromFile(currentContext)
        }
        rebuildInvertedIndex()
        updateAutoComplete()
        addChatMessage(currentMascotName, getString(R.string.welcome_message))

        startIdleTimer()
        loadMascotTopImage()
        mascotTopImage?.visibility = View.GONE
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            tts?.language = Locale("ru", "RU")
            tts?.setPitch(1.0f)
            tts?.setSpeechRate(1.0f)
        }
    }

    private fun setupToolbar() {
        // Безопасный доступ к topBar/leftLayout
        val leftLayout = findTopBarChildViewGroup("leftLayout") ?: return
        leftLayout.removeAllViews()
        leftLayout.orientation = LinearLayout.HORIZONTAL
        leftLayout.gravity = Gravity.CENTER_VERTICAL

        val iconSize = dpToPx(56)

        val bluetoothImageView = ImageView(this).apply {
            layoutParams = LinearLayout.LayoutParams(iconSize, iconSize)
            scaleType = ImageView.ScaleType.CENTER_CROP
            adjustViewBounds = true
            visibility = View.GONE
        }
        leftLayout.addView(bluetoothImageView)

        val wifiImageView = ImageView(this).apply {
            layoutParams = LinearLayout.LayoutParams(iconSize, iconSize).apply { marginStart = dpToPx(6) }
            scaleType = ImageView.ScaleType.CENTER_CROP
            adjustViewBounds = true
        }
        leftLayout.addView(wifiImageView)

        val batteryImageView = ImageView(this).apply {
            layoutParams = LinearLayout.LayoutParams(iconSize, iconSize).apply { marginStart = dpToPx(6) }
            scaleType = ImageView.ScaleType.CENTER_CROP
            adjustViewBounds = true
        }
        val batteryPercentView = TextView(this).apply {
            text = getString(R.string.battery_percent_placeholder)
            textSize = 16f
            setTextColor(Color.parseColor("#00BFFF"))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { marginStart = dpToPx(8) }
        }
        leftLayout.addView(batteryImageView)
        leftLayout.addView(batteryPercentView)

        // Работаем с корнем topBar
        val topBarRoot = findTopBar() ?: return
        if (topBarRoot.childCount > 1) {
            // защищённо удаляем второй элемент, если он есть
            try { topBarRoot.removeViewAt(1) } catch (_: Exception) {}
        }
        val timeTextView = TextView(this).apply {
            text = getString(R.string.time_placeholder)
            textSize = 20f
            setTextColor(Color.parseColor("#FFA500"))
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(0, dpToPx(56), 1f)
        }
        topBarRoot.addView(timeTextView, 1)

        val btnCharging = ImageButton(this).apply {
            background = null
            layoutParams = LinearLayout.LayoutParams(iconSize, iconSize)
            scaleType = ImageView.ScaleType.CENTER_CROP
            adjustViewBounds = true
            visibility = View.GONE
        }
        topBarRoot.addView(btnCharging)
    }

    override fun onResume() {
        super.onResume()
        folderUri?.let { loadTemplatesFromFile(currentContext) }
        rebuildInvertedIndex()
        updateAutoComplete()
        startIdleTimer()
        loadToolbarIcons()
        registerSystemReceivers()
        startTimeUpdater()
    }

    override fun onPause() {
        super.onPause()
        dialogHandler.removeCallbacksAndMessages(null)
        unregisterSystemReceivers()
        stopTimeUpdater()
    }

    override fun onDestroy() {
        super.onDestroy()
        dialogHandler.removeCallbacksAndMessages(null)
        unregisterSystemReceivers()
        stopTimeUpdater()
        tts?.shutdown()
        tts = null
    }

    private fun setupListeners() {
        val buttons: List<ImageButton?> = listOf(
            findTopBarChildImageButton("btnLock") ?: binding.root.findViewById(resources.getIdentifier("btnLock","id",packageName)),
            findTopBarChildImageButton("btnTrash") ?: binding.root.findViewById(resources.getIdentifier("btnTrash","id",packageName)),
            findTopBarChildImageButton("btnEnvelopeTop") ?: binding.root.findViewById(resources.getIdentifier("btnEnvelopeTop","id",packageName)),
            findTopBarChildImageButton("btnSettings") ?: binding.root.findViewById(resources.getIdentifier("btnSettings","id",packageName)),
            binding.envelopeButton
        )
        for (btn in buttons) {
            btn?.let { setupIconTouchEffect(it) }
        }

        // если родные кнопки найдены — навешиваем действия
        findTopBarChildImageButton("btnLock")?.setOnClickListener { finish() }
        findTopBarChildImageButton("btnTrash")?.setOnClickListener { clearChat() }
        findTopBarChildImageButton("btnSettings")?.setOnClickListener {
            startActivity(Intent(this@ChatActivity, SettingsActivity::class.java))
        }
        findTopBarChildImageButton("btnEnvelopeTop")?.setOnClickListener {
            startActivity(Intent(this@ChatActivity, PostsActivity::class.java))
        }

        val sendAction = sendAction@{
            val now = System.currentTimeMillis()
            if (now - lastSendTime < SEND_DEBOUNCE_MS) {
                return@sendAction
            } else {
                lastSendTime = now
                val input = binding.queryInput.text.toString().trim()
                if (input.isNotEmpty()) {
                    processUserQuery(input)
                    binding.queryInput.setText("")
                }
            }
        }

        binding.envelopeButton.setOnClickListener { sendAction() }
        binding.queryInput.setOnEditorActionListener { _, _, _ ->
            sendAction()
            true
        }
        binding.queryInput.setOnItemClickListener { parent, _, position, _ ->
            val selected = parent.getItemAtPosition(position) as? String
            selected?.let {
                binding.queryInput.setText(it)
                processUserQuery(it)
            }
        }
    }

    private fun setupIconTouchEffect(btn: ImageButton) {
        btn.setOnTouchListener { v, event ->
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
                target?.setImageBitmap(loadBitmapFromFolder(name))
            }

            fun tryLoadToImageView(name: String, target: ImageView?) {
                target?.setImageBitmap(loadBitmapFromFolder(name))
            }

            // сначала пробуем найти кнопки внутри topBar, иначе ищем по корню binding.root
            tryLoadToImageButton(getString(R.string.lock_icon), findTopBarChildImageButton("btnLock") ?: binding.root.findViewById(resources.getIdentifier("btnLock","id",packageName)))
            tryLoadToImageButton(getString(R.string.trash_icon), findTopBarChildImageButton("btnTrash") ?: binding.root.findViewById(resources.getIdentifier("btnTrash","id",packageName)))
            tryLoadToImageButton(getString(R.string.envelope_icon), findTopBarChildImageButton("btnEnvelopeTop") ?: binding.root.findViewById(resources.getIdentifier("btnEnvelopeTop","id",packageName)))
            tryLoadToImageButton(getString(R.string.settings_icon), findTopBarChildImageButton("btnSettings") ?: binding.root.findViewById(resources.getIdentifier("btnSettings","id",packageName)))
            tryLoadToImageButton(getString(R.string.send_icon), binding.envelopeButton)

            val topBarRoot = findTopBar()
            val btnCharging = topBarRoot?.children?.find { v -> v is ImageButton && v.visibility == View.GONE } as? ImageButton
            btnCharging?.let { tryLoadToImageButton(getString(R.string.charging_icon), it) }

            val leftLayout = findTopBarChildViewGroup("leftLayout")
            val bluetoothImageView = leftLayout?.getChildAt(0) as? ImageView
            val wifiImageView = leftLayout?.getChildAt(1) as? ImageView
            val batteryImageView = leftLayout?.getChildAt(2) as? ImageView

            tryLoadToImageView(getString(R.string.bluetooth_icon), bluetoothImageView)
            tryLoadToImageView(getString(R.string.wifi_icon), wifiImageView)
            tryLoadToImageView(getString(R.string.battery_5_icon), batteryImageView)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun loadMascotTopImage() {
        val uri = folderUri ?: return
        try {
            val candidates = listOf(
                "${currentMascotName.lowercase(Locale.getDefault())}",
                getString(R.string.mascot_top_icon),
                currentMascotIcon
            )
            val bmp = candidates.firstNotNullOfOrNull { loadBitmapFromFolder(it) } ?: return

            if (mascotTopImage == null) {
                mascotTopImage = ImageView(this).apply {
                    val size = dpToPx(120)
                    layoutParams = LinearLayout.LayoutParams(size, size).apply {
                        gravity = Gravity.CENTER_HORIZONTAL
                        topMargin = dpToPx(8)
                    }
                    scaleType = ImageView.ScaleType.CENTER_CROP
                    adjustViewBounds = true
                    (window.decorView as ViewGroup).addView(this, 0)
                }
            }
            mascotTopImage?.setImageBitmap(bmp)
        } catch (_: Exception) {}
    }

    private fun processUserQuery(userInput: String) {
        if (userInput.startsWith("/")) {
            handleCommand(userInput.trim())
            return
        }

        val qOrigRaw = userInput.trim()
        val qOrig = normalizeText(qOrigRaw)
        val (qTokensFiltered, qFiltered) = filterStopwordsAndMapSynonyms(qOrig)

        if (qFiltered.isEmpty()) return

        lastUserInputTime = System.currentTimeMillis()
        userActivityCount++

        addChatMessage(getString(R.string.you), userInput)

        if (handleLateNightQuery() || handleDateTimeQuery(qOrig)) {
            return
        }

        showTypingIndicator()
        updateQuerySpamCount(qFiltered)

        if (queryCountMap.getOrDefault(qFiltered, 0) >= 5) {
            addChatMessage(currentMascotName, antiSpamResponses.random())
            startIdleTimer()
            return
        }

        val snapshots = createDataSnapshots()

        lifecycleScope.launch(Dispatchers.Default) {
            findResponse(qFiltered, qTokensFiltered, qOrig, snapshots)
        }
    }

    private suspend fun findResponse(
        qFiltered: String,
        qTokensFiltered: List<String>,
        qOrig: String,
        snapshots: DataSnapshots
    ) {
        val subqueryResponses = findSubqueryResponses(qFiltered, qTokensFiltered, snapshots)
        if (subqueryResponses.isNotEmpty()) {
            val combined = subqueryResponses.joinToString(". ")
            withContext(Dispatchers.Main) {
                addChatMessage(currentMascotName, combined)
                startIdleTimer()
            }
            return
        }

        val keywordResponse = findKeywordResponse(qFiltered, snapshots)
        if (keywordResponse != null) {
            withContext(Dispatchers.Main) {
                addChatMessage(currentMascotName, keywordResponse)
                startIdleTimer()
            }
            return
        }

        val fuzzyResponse = findFuzzyMatchResponse(qFiltered, qTokensFiltered, snapshots)
        if (fuzzyResponse != null) {
            withContext(Dispatchers.Main) {
                addChatMessage(currentMascotName, fuzzyResponse)
                startIdleTimer()
            }
            return
        }

        val contextSwitchFile = snapshots.contextMap[normalizeText(qFiltered)]
        if (contextSwitchFile != null) {
            handleContextSwitch(contextSwitchFile, qFiltered, qTokensFiltered, qOrig)
            return
        }

        withContext(Dispatchers.Main) {
            addChatMessage(currentMascotName, getDummyResponse(qOrig))
            startIdleTimer()
        }
    }

    private suspend fun handleContextSwitch(
        contextFile: String,
        qFiltered: String,
        qTokens: List<String>,
        qOrig: String
    ) {
        if (contextFile == getString(R.string.pictures_meta_file)) {
            val imageResult = loadImageFromMeta(qFiltered, qTokens)
            withContext(Dispatchers.Main) {
                if (imageResult != null) {
                    addImageMessage(currentMascotName, imageResult)
                } else {
                    addChatMessage(currentMascotName, getDummyResponse(qOrig))
                }
                startIdleTimer()
            }
        } else {
            withContext(Dispatchers.Main) {
                if (contextFile != currentContext) {
                    currentContext = contextFile
                    loadTemplatesFromFile(currentContext)
                    rebuildInvertedIndex()
                    updateAutoComplete()
                }
            }

            val (localTemplates, _) = parseTemplatesFromFile(contextFile)
            val response = findResponseInThemedFile(qFiltered, qTokens, localTemplates)
            withContext(Dispatchers.Main) {
                addChatMessage(currentMascotName, response ?: getDummyResponse(qOrig))
                startIdleTimer()
            }
        }
    }

    private fun findResponseInThemedFile(
        qFiltered: String,
        qTokens: List<String>,
        localTemplates: Map<String, List<String>>
    ): String? {
        localTemplates[qFiltered]?.let { return it.random() }

        val localInverted = buildLocalInvertedIndex(localTemplates)
        val localCandidates = findCandidates(qTokens, localInverted)
            .ifEmpty { localTemplates.keys.take(MAX_CANDIDATES_FOR_LEV) }

        val bestJaccardMatch = findBestJaccardMatch(qTokens, localCandidates, JACCARD_THRESHOLD)
        if (bestJaccardMatch != null) return localTemplates[bestJaccardMatch]?.random()

        val bestLevenshteinMatch = findBestLevenshteinMatch(qFiltered, localCandidates)
        if (bestLevenshteinMatch != null) return localTemplates[bestLevenshteinMatch]?.random()

        return null
    }

    private fun buildLocalInvertedIndex(templates: Map<String, List<String>>): Map<String, List<String>> {
        val localInverted = HashMap<String, MutableList<String>>()
        for (key in templates.keys) {
            val tokens = filterStopwordsAndMapSynonyms(key).first
            for (token in tokens) {
                val list = localInverted.getOrPut(token) { mutableListOf() }
                if (!list.contains(key)) list.add(key)
            }
        }
        return localInverted
    }

    private suspend fun loadImageFromMeta(qFiltered: String, qTokens: List<String>): Bitmap? {
        val imageMap = parseImageMetaFile()
        if (imageMap.isEmpty()) return null

        val bestJaccardMatch = findBestJaccardMatch(qTokens, imageMap.keys.toList(), JACCARD_THRESHOLD)
        if (bestJaccardMatch != null) {
            return imageMap[bestJaccardMatch]?.let { loadBitmapFromFolder(it) }
        }

        val bestLevenshteinMatch = findBestLevenshteinMatch(qFiltered, imageMap.keys.take(MAX_CANDIDATES_FOR_LEV).toList())
        if (bestLevenshteinMatch != null) {
            return imageMap[bestLevenshteinMatch]?.let { loadBitmapFromFolder(it) }
        }

        return null
    }

    private fun parseImageMetaFile(): Map<String, String> {
        val uriLocal = folderUri ?: return emptyMap()
        val dir = DocumentFile.fromTreeUri(this, uriLocal) ?: return emptyMap()
        val metaFile = dir.findFile(getString(R.string.pictures_meta_file))?.takeIf { it.exists() } ?: return emptyMap()

        val imageMap = HashMap<String, String>()
        contentResolver.openInputStream(metaFile.uri)?.bufferedReader()?.useLines { seq ->
            for (raw in seq) {
                val line = raw.trim()
                if (line.startsWith(":") && line.endsWith(":") && line.contains("=")) {
                    val content = line.substring(1, line.length - 1)
                    val parts = content.split("=", limit = 2)
                    if (parts.size == 2) {
                        val trigger = normalizeText(parts[0].trim())
                        val imgFile = parts[1].trim()
                        val triggerFiltered = filterStopwordsAndMapSynonyms(trigger).second
                        if (triggerFiltered.isNotEmpty() && imgFile.isNotEmpty()) {
                            imageMap[triggerFiltered] = imgFile
                        }
                    }
                }
            }
        }
        return imageMap
    }

    private fun loadBitmapFromFolder(name: String): Bitmap? {
        val uri = folderUri ?: return null
        return try {
            val dir = DocumentFile.fromTreeUri(this, uri) ?: return null
            val file = dir.findFile(name)?.takeIf { it.exists() } ?: return null
            contentResolver.openInputStream(file.uri)?.use { BitmapFactory.decodeStream(it) }
        } catch (e: Exception) {
            null
        }
    }

    private fun addImageMessage(sender: String, bitmap: Bitmap) {
        runOnUiThread {
            val row = createMessageRow(sender)
            val isUser = sender.equals(getString(R.string.you), ignoreCase = true)

            val imageView = ImageView(this).apply {
                setImageBitmap(bitmap)
                scaleType = ImageView.ScaleType.CENTER_CROP
                adjustViewBounds = true
                val accent = if (isUser) Color.RED else safeParseColorOrDefault(currentThemeColor, Color.GREEN)
                background = createBubbleDrawable(accent)
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { if (!isUser) marginStart = dpToPx(8) }
            }

            if (isUser) {
                row.gravity = Gravity.END
                row.setPadding(dpToPx(48), 0, 0, 0)
                row.addView(imageView)
            } else {
                val avatarView = createAvatarView(sender)
                row.addView(avatarView)
                row.addView(imageView)
            }

            addMessageViewToContainer(row, isUser)
        }
    }

    private fun handleCommand(cmdRaw: String) {
        val cmd = cmdRaw.trim().lowercase(Locale.getDefault())
        when (cmd) {
            getString(R.string.command_reload) -> {
                addChatMessage(currentMascotName, getString(R.string.reload_templates))
                loadTemplatesFromFile(currentContext)
                rebuildInvertedIndex()
                updateAutoComplete()
                addChatMessage(currentMascotName, getString(R.string.templates_reloaded))
            }
            getString(R.string.command_stats) -> {
                val msg = getString(
                    R.string.stats_message,
                    currentContext,
                    templatesMap.size,
                    keywordResponses.size
                )
                addChatMessage(currentMascotName, msg)
            }
            getString(R.string.command_clear) -> clearChat()
            else -> {
                val errorMsg = getString(R.string.unknown_command, cmdRaw)
                addChatMessage(currentMascotName, errorMsg)
            }
        }
    }

    private fun showTypingIndicator() {
        runOnUiThread {
            if (binding.chatMessagesContainer.findViewWithTag<View>("typingView") != null) return@runOnUiThread

            val typingView = TextView(this).apply {
                text = getString(R.string.typing_indicator)
                textSize = 14f
                setTextColor(Color.WHITE)
                setBackgroundColor(0x80000000.toInt())
                alpha = 0.7f
                setPadding(16, 8, 16, 8)
                tag = "typingView"
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { setMargins(0, 16, 0, 0) }
            }
            binding.chatMessagesContainer.addView(typingView)
            binding.scrollView.post { binding.scrollView.fullScroll(View.FOCUS_DOWN) }
        }
    }

    private fun clearChat() {
        runOnUiThread {
            binding.chatMessagesContainer.removeAllViews()
            queryCountMap.clear()
            lastQuery = ""
            currentContext = getString(R.string.base_context_file)
            loadTemplatesFromFile(currentContext)
            rebuildInvertedIndex()
            updateAutoComplete()
            addChatMessage(currentMascotName, getString(R.string.chat_cleared))
        }
    }

    private fun getDummyResponse(query: String): String {
        val lower = query.lowercase(Locale.ROOT)
        return when {
            lower.contains(getString(R.string.hello_keyword)) -> getString(R.string.dummy_response_hello)
            lower.contains(getString(R.string.how_are_you_keyword)) -> getString(R.string.dummy_response_how_are_you)
            else -> getString(R.string.dummy_response_unknown)
        }
    }

    private fun normalizeText(s: String): String {
        return s.lowercase(Locale.getDefault())
            .replace(Regex("[^\\p{L}\\p{Nd}\\s]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    private fun tokenize(s: String): List<String> {
        return s.split(Regex("\\s+")).map { it.trim() }.filter { it.isNotEmpty() }
    }

    private fun loadSynonymsAndStopwords() {
        synonymsMap.clear()
        stopwords.clear()
        val uri = folderUri ?: return
        try {
            val dir = DocumentFile.fromTreeUri(this, uri) ?: return

            dir.findFile(getString(R.string.synonyms_file))?.let { synFile ->
                contentResolver.openInputStream(synFile.uri)?.bufferedReader()?.useLines { seq ->
                    for (raw in seq) {
                        val parts = raw.trim().removeSurrounding("*").split(";")
                            .map { normalizeText(it).trim() }.filter { it.isNotEmpty() }
                        if (parts.size > 1) {
                            val canonical = parts.last()
                            for (p in parts) synonymsMap[p] = canonical
                        }
                    }
                }
            }

            dir.findFile(getString(R.string.stopwords_file))?.let { stopFile ->
                contentResolver.openInputStream(stopFile.uri)?.bufferedReader()?.use { reader ->
                    val all = reader.readText()
                    val pieces = all.split("^")
                    for (piece in pieces) {
                        val w = normalizeText(piece).trim()
                        if (w.isNotEmpty()) stopwords.add(w)
                    }
                }
            }
        } catch (_: Exception) {}
    }

    private fun filterStopwordsAndMapSynonyms(input: String): Pair<List<String>, String> {
        val tokens = tokenize(input)
        val mapped = tokens.map { tok -> synonymsMap[normalizeText(tok)] ?: tok }
            .filter { it.isNotEmpty() && !stopwords.contains(it) }
        return Pair(mapped, mapped.joinToString(" "))
    }

    private fun rebuildInvertedIndex() {
        invertedIndex.clear()
        for (key in templatesMap.keys) {
            val tokens = filterStopwordsAndMapSynonyms(key).first
            for (token in tokens) {
                val list = invertedIndex.getOrPut(token) { mutableListOf() }
                if (!list.contains(key)) list.add(key)
            }
        }
    }

    private fun levenshtein(s: String, t: String, qFiltered: String): Int {
        if (s == t) return 0
        val n = s.length
        val m = t.length
        if (n == 0) return m
        if (m == 0) return n

        val maxDist = getFuzzyDistance(qFiltered)
        if (abs(n - m) > maxDist + 2) return Int.MAX_VALUE / 2

        var prev = IntArray(m + 1) { it }
        var curr = IntArray(m + 1)

        for (i in 1..n) {
            curr[0] = i
            var minInRow = curr[0]
            val si = s[i - 1]
            for (j in 1..m) {
                val cost = if (si == t[j - 1]) 0 else 1
                val deletion = prev[j] + 1
                val insertion = curr[j - 1] + 1
                val substitution = prev[j - 1] + cost
                curr[j] = minOf(deletion, insertion, substitution)
                if (curr[j] < minInRow) minInRow = curr[j]
            }

            if (minInRow > maxDist + 2) return Int.MAX_VALUE / 2
            prev = curr.also { curr = prev }
        }
        return prev[m]
    }

    private fun addChatMessage(sender: String, text: String) {
        runOnUiThread {
            val row = createMessageRow(sender)
            val isUser = sender.equals(getString(R.string.you), ignoreCase = true)
            val bubble = createMessageBubble(sender, text, isUser)

            if (isUser) {
                row.gravity = Gravity.END
                row.setPadding(dpToPx(48), 0, 0, 0)
                row.addView(bubble)
            } else {
                val avatarView = createAvatarView(sender)
                row.addView(avatarView)
                row.addView(bubble)
            }

            addMessageViewToContainer(row, isUser)
        }
    }

    private fun createMessageRow(sender: String): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = if (sender.equals(getString(R.string.you), ignoreCase = true)) Gravity.END else Gravity.START
            val pad = dpToPx(6)
            setPadding(pad, pad / 2, pad, pad / 2)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }
    }

    private fun addMessageViewToContainer(row: View, isUserMessage: Boolean) {
        binding.chatMessagesContainer.findViewWithTag<View>("typingView")?.let {
            binding.chatMessagesContainer.removeView(it)
        }
        binding.chatMessagesContainer.addView(row)

        if (binding.chatMessagesContainer.childCount > MAX_MESSAGES) {
            val removeCount = binding.chatMessagesContainer.childCount - MAX_MESSAGES
            repeat(removeCount) { binding.chatMessagesContainer.removeViewAt(0) }
        }

        binding.scrollView.post { binding.scrollView.fullScroll(View.FOCUS_DOWN) }

        if (!isUserMessage) {
            playNotificationSound()
        }
    }

    private fun loadAndSendOuchMessage(mascot: String) {
        val uri = folderUri ?: return
        try {
            val dir = DocumentFile.fromTreeUri(this, uri) ?: return
            val ouchFile = dir.findFile("${mascot.lowercase(Locale.getDefault())}.txt")
                ?: dir.findFile(getString(R.string.ouch_file))

            ouchFile?.takeIf { it.exists() }?.let { file ->
                contentResolver.openInputStream(file.uri)?.bufferedReader()?.use { reader ->
                    val responses = reader.readText().split("|")
                        .map { it.trim() }.filter { it.isNotEmpty() }
                    if (responses.isNotEmpty()) {
                        addChatMessage(mascot, responses.random())
                    }
                }
            }
        } catch (e: Exception) {
            showCustomToast(getString(R.string.error_loading_ouch, e.message ?: ""))
        }
    }

    private fun speakText(text: String) {
        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "message_${System.currentTimeMillis()}")
    }

    private fun playNotificationSound() {
        folderUri?.let { uri ->
            try {
                val dir = DocumentFile.fromTreeUri(this, uri) ?: return
                val soundFile = dir.findFile(getString(R.string.notify_sound_file))?.takeIf { it.exists() } ?: return
                contentResolver.openAssetFileDescriptor(soundFile.uri, "r")?.use { afd ->
                    MediaPlayer().apply {
                        setDataSource(afd.fileDescriptor, afd.startOffset, afd.length)
                        prepareAsync()
                        setOnPreparedListener { start() }
                        setOnCompletionListener { release() }
                        setOnErrorListener { mp, _, _ -> mp.release(); true }
                    }
                }
            } catch (_: Exception) {}
        }
    }

    private fun createMessageBubble(sender: String, text: String, isUser: Boolean): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            if (!isUser) {
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { marginStart = dpToPx(8) }
            }

            val senderTextView = TextView(this).apply {
                text = getString(R.string.sender_label, sender)
                textSize = 12f
                setTextColor(Color.parseColor("#AAAAAA"))
            }

            val messageTextView = TextView(this).apply {
                this.text = text
                textSize = 16f
                setTextIsSelectable(true)
                val pad = dpToPx(10)
                setPadding(pad, pad, pad, pad)
                val accent = if (isUser) Color.RED else safeParseColorOrDefault(currentThemeColor, Color.GREEN)
                background = createBubbleDrawable(accent)
                setTextColor(if (isUser) Color.WHITE else try { Color.parseColor(currentThemeColor) } catch (_: Exception) { Color.WHITE })
                setOnClickListener { speakText(text) }
            }

            addView(senderTextView)
            addView(messageTextView)
        }
    }

    private fun createAvatarView(sender: String): ImageView {
        return ImageView(this).apply {
            val size = dpToPx(64)
            layoutParams = LinearLayout.LayoutParams(size, size)
            scaleType = ImageView.ScaleType.CENTER_CROP
            adjustViewBounds = true
            loadAvatarInto(this, sender)
            setOnClickListener { view ->
                view.isEnabled = false
                ObjectAnimator.ofFloat(view, "scaleX", 1f, 1.08f, 1f).setDuration(250).start()
                ObjectAnimator.ofFloat(view, "scaleY", 1f, 1.08f, 1f).setDuration(250).start()
                Handler(Looper.getMainLooper()).postDelayed({
                    loadAndSendOuchMessage(sender)
                    view.isEnabled = true
                }, 260)
            }
        }
    }

    private fun createBubbleDrawable(accentColor: Int): GradientDrawable {
        return GradientDrawable().apply {
            val bgColor = blendColors(Color.parseColor("#0A0A0A"), accentColor, 0.06f)
            setColor(bgColor)
            cornerRadius = dpToPx(10).toFloat()
            setStroke(dpToPx(2), ColorUtils.setAlphaComponent(accentColor, 180))
        }
    }

    private fun loadAvatarInto(target: ImageView, sender: String) {
        val s = sender.lowercase(Locale.getDefault())
        val candidates = listOf("${s}_icon.png", "${s}_avatar.png", "${s}.png", currentMascotIcon)
        val bmp = candidates.firstNotNullOfOrNull { loadBitmapFromFolder(it) }

        if (bmp != null) {
            target.setImageBitmap(bmp)
        } else {
            target.setImageResource(color.transparent)
        }
    }

    private fun blendColors(base: Int, accent: Int, ratio: Float): Int {
        val r = (Color.red(base) * (1 - ratio) + Color.red(accent) * ratio).roundToInt()
        val g = (Color.green(base) * (1 - ratio) + Color.green(accent) * ratio).roundToInt()
        val b = (Color.blue(base) * (1 - ratio) + Color.blue(accent) * ratio).roundToInt()
        return Color.rgb(r, g, b)
    }

    private fun safeParseColorOrDefault(spec: String?, fallback: Int): Int {
        return try { Color.parseColor(spec) } catch (_: Exception) { fallback }
    }

    private fun dpToPx(dp: Int): Int {
        return (dp * resources.displayMetrics.density).roundToInt()
    }

    private fun loadTemplatesFromFile(filename: String) {
        templatesMap.clear()
        keywordResponses.clear()
        mascotList.clear()
        if (filename == getString(R.string.base_context_file)) contextMap.clear()

        resetToDefaultMascot()
        loadSynonymsAndStopwords()

        val uri = folderUri ?: run { loadFallbackAndFinish(); return }
        val dir = DocumentFile.fromTreeUri(this, uri) ?: run { loadFallbackAndFinish(); return }
        val file = dir.findFile(filename)?.takeIf { it.exists() } ?: run { loadFallbackAndFinish(); return }

        try {
            contentResolver.openInputStream(file.uri)?.bufferedReader()?.useLines { seq ->
                for (raw in seq) processTemplateLine(raw, filename)
            }
            loadMetadataFor(filename, dir)
            if (filename == getString(R.string.base_context_file) && mascotList.isNotEmpty()) {
                selectRandomMascot()
            }
        } catch (e: Exception) {
            e.printStackTrace()
            showCustomToast(getString(R.string.error_reading_file, e.message ?: ""))
            loadFallbackAndFinish()
        } finally {
            rebuildInvertedIndex()
            updateUI()
        }
    }

    private fun processTemplateLine(rawLine: String, filename: String) {
        val line = rawLine.trim()
        if (line.isEmpty()) return

        when {
            filename == getString(R.string.base_context_file) && line.startsWith(":") && line.endsWith(":") -> {
                val content = line.substring(1, line.length - 1)
                val parts = content.split("=", limit = 2).map { it.trim() }
                if (parts.size == 2 && parts[0].isNotEmpty() && parts[1].isNotEmpty()) {
                    contextMap[parts[0].lowercase(Locale.ROOT)] = parts[1]
                }
            }
            line.startsWith("-") -> {
                val content = line.substring(1)
                val parts = content.split("=", limit = 2).map { it.trim() }
                if (parts.size == 2) {
                    val keyword = parts[0].lowercase(Locale.ROOT)
                    val responses = parts[1].split("|").mapNotNull { it.trim().takeIf { s -> s.isNotEmpty() } }
                    if (keyword.isNotEmpty() && responses.isNotEmpty()) {
                        keywordResponses[keyword] = responses.toMutableList()
                    }
                }
            }
            line.contains("=") -> {
                val parts = line.split("=", limit = 2).map { it.trim() }
                if (parts.size == 2) {
                    val triggerFiltered = filterStopwordsAndMapSynonyms(normalizeText(parts[0])).second
                    val responses = parts[1].split("|").mapNotNull { it.trim().takeIf { s -> s.isNotEmpty() } }
                    if (triggerFiltered.isNotEmpty() && responses.isNotEmpty()) {
                        templatesMap[triggerFiltered] = responses.toMutableList()
                    }
                }
            }
        }
    }

    private fun loadMetadataFor(baseFilename: String, dir: DocumentFile) {
        val metadataFilename = baseFilename.replace(".txt", "_metadata.txt")
        dir.findFile(metadataFilename)?.takeIf { it.exists() }?.let { metaFile ->
            contentResolver.openInputStream(metaFile.uri)?.bufferedReader()?.useLines { seq ->
                for (raw in seq) {
                    val line = raw.trim()
                    val value = line.substringAfter('=', "").trim()
                    if (value.isEmpty()) continue

                    when {
                        line.startsWith("mascot_list=") -> {
                            val parsed = value.split("|")
                                .mapNotNull { mascotString ->
                                    val parts = mascotString.split(":")
                                    if (parts.size == 4) mapOf(
                                        "name" to parts[0].trim(), "icon" to parts[1].trim(),
                                        "color" to parts[2].trim(), "background" to parts[3].trim()
                                    ) else null
                                }
                            mascotList.addAll(parsed)
                        }
                        line.startsWith("mascot_name=") -> currentMascotName = value
                        line.startsWith("mascot_icon=") -> currentMascotIcon = value
                        line.startsWith("theme_color=") -> currentThemeColor = value
                        line.startsWith("theme_background=") -> currentThemeBackground = value
                    }
                }
            }
        }
    }

    private fun selectRandomMascot() {
        if (mascotList.isEmpty()) return
        val chosen = mascotList.random()
        currentMascotName = chosen["name"] ?: currentMascotName
        currentMascotIcon = chosen["icon"] ?: currentMascotIcon
        currentThemeColor = chosen["color"] ?: currentThemeColor
        currentThemeBackground = chosen["background"] ?: currentThemeBackground
    }

    private fun resetToDefaultMascot() {
        currentMascotName = getString(R.string.default_mascot_name)
        currentMascotIcon = getString(R.string.raccoon_icon)
        currentThemeColor = getString(R.string.default_theme_color)
        currentThemeBackground = getString(R.string.default_theme_background)
    }

    private fun loadFallbackAndFinish() {
        loadFallbackTemplates()
        rebuildInvertedIndex()
        updateUI()
    }

    private fun loadFallbackTemplates() {
        templatesMap.clear()
        contextMap.clear()
        keywordResponses.clear()
        mascotList.clear()

        val helloKey = normalizeText(getString(R.string.hello_keyword))
        val t1 = filterStopwordsAndMapSynonyms(helloKey).second
        templatesMap[t1] = mutableListOf(getString(R.string.fallback_hello_1), getString(R.string.fallback_hello_2))
        val howAreYouKey = normalizeText(getString(R.string.how_are_you_keyword))
        val t2 = filterStopwordsAndMapSynonyms(howAreYouKey).second
        templatesMap[t2] = mutableListOf(getString(R.string.fallback_how_are_you_1), getString(R.string.fallback_how_are_you_2))
        val thanksKey = normalizeText(getString(R.string.thanks_keyword))
        keywordResponses[thanksKey] = mutableListOf(getString(R.string.fallback_thanks_1), getString(R.string.fallback_thanks_2))
    }

    private fun updateAutoComplete() {
        val suggestions = templatesMap.keys.toMutableSet()
        for (s in fallback) suggestions.add(s.lowercase(Locale.ROOT))

        val adapter = object : ArrayAdapter<String>(this@ChatActivity, android.R.layout.simple_dropdown_item_1line, suggestions.toList()) {
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                return (super.getView(position, convertView, parent) as TextView).apply {
                    setTextColor(Color.WHITE)
                }
            }
        }

        // Предполагаем, что queryInput это AutoCompleteTextView в layout
        binding.queryInput.apply {
            setAdapter(adapter)
            threshold = 1
            setDropDownBackgroundDrawable(ColorDrawable(Color.parseColor("#80000000")))
        }
    }

    private fun updateUI() {
        runOnUiThread {
            title = getString(R.string.app_title_template, currentMascotName)
            loadMascotTopImage()
            mascotTopImage?.visibility = View.GONE
            try {
                binding.chatMessagesContainer.setBackgroundColor(Color.parseColor(currentThemeBackground))
            } catch (_: Exception) {}
        }
    }

    private fun showCustomToast(message: String) {
        try {
            val layout = layoutInflater.inflate(R.layout.custom_toast, null)
            layout.findViewById<TextView>(R.id.customToastText).text = message
            Toast(applicationContext).apply {
                duration = Toast.LENGTH_SHORT
                view = layout
                show()
            }
        } catch (_: Exception) {
            Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
        }
    }

    private fun startIdleTimer() {
        lastUserInputTime = System.currentTimeMillis()
        idleCheckRunnable?.let { dialogHandler.removeCallbacks(it) }
        idleCheckRunnable = Runnable {
            // Check for idle actions if needed
            dialogHandler.postDelayed(idleCheckRunnable!!, 5000)
        }
        dialogHandler.postDelayed(idleCheckRunnable!!, 5000)
    }

    private fun initializeFolderUri() {
        val prefs = PreferenceManager.getDefaultSharedPreferences(this)
        val uriString = prefs.getString(PREF_KEY_FOLDER_URI, null)
        folderUri = uriString?.let { Uri.parse(it) }
    }

    private fun applySecuritySettings() {
        val prefs = PreferenceManager.getDefaultSharedPreferences(this)
        if (prefs.getBoolean(PREF_KEY_DISABLE_SCREENSHOTS, false)) {
            window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        }
    }

    private fun handleLateNightQuery(): Boolean {
        val calendar = Calendar.getInstance()
        val hour = calendar.get(Calendar.HOUR_OF_DAY)
        if (hour >= 22 || hour < 6) {
            val resp = loadTimeToSleepResponse()
            if (!resp.isNullOrBlank()) {
                addChatMessage(currentMascotName, resp)
                startIdleTimer()
                return true
            }
        }
        return false
    }

    private fun handleDateTimeQuery(query: String): Boolean {
        val response = processDateTimeQuery(query)
        if (response != null) {
            addChatMessage(currentMascotName, response)
            startIdleTimer()
            return true
        }
        return false
    }

    private fun registerSystemReceivers() {
        if (batteryReceiver == null) {
            batteryReceiver = object : BroadcastReceiver() {
                override fun onReceive(context: Context?, intent: Intent?) {
                    intent?.let { updateBatteryStatus(it) }
                }
            }
            registerReceiver(batteryReceiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))?.let {
                updateBatteryStatus(it)
            }
        }

        if (networkReceiver == null) {
            networkReceiver = object : BroadcastReceiver() {
                override fun onReceive(context: Context?, intent: Intent?) {
                    updateNetworkUI()
                }
            }
            val filter = IntentFilter().apply {
                addAction(ConnectivityManager.CONNECTIVITY_ACTION)
                addAction("android.intent.action.AIRPLANE_MODE")
                addAction(WifiManager.WIFI_STATE_CHANGED_ACTION)
            }
            registerReceiver(networkReceiver, filter)
            updateNetworkUI()
        }

        if (bluetoothReceiver == null) {
            bluetoothReceiver = object : BroadcastReceiver() {
                override fun onReceive(context: Context?, intent: Intent?) {
                    updateBluetoothUI()
                }
            }
            registerReceiver(bluetoothReceiver, IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED))
            updateBluetoothUI()
        }
    }

    private fun unregisterSystemReceivers() {
        try { batteryReceiver?.let { unregisterReceiver(it) } } catch (_: Exception) {}
        batteryReceiver = null

        try { networkReceiver?.let { unregisterReceiver(it) } } catch (_: Exception) {}
        networkReceiver = null

        try { bluetoothReceiver?.let { unregisterReceiver(it) } } catch (_: Exception) {}
        bluetoothReceiver = null
    }

    private fun updateBatteryStatus(intent: Intent) {
        val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
        val plugged = intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, -1)
        val percent = if (level >= 0 && scale > 0) (level * 100) / scale else -1
        if (percent >= 0) {
            updateBatteryUI(percent, plugged)
        }
    }

    private fun updateBatteryUI(percent: Int, plugged: Int) {
        runOnUiThread {
            val leftLayout = binding.topBar.leftLayout
            val batteryPercentView = leftLayout.getChildAt(3) as? TextView
            val batteryImageView = leftLayout.getChildAt(2) as? ImageView
            val btnCharging = binding.topBar.root.children.find { v -> v is ImageButton } as? ImageButton

            batteryPercentView?.text = getString(R.string.battery_percent, percent)
            val textColor = if (percent <= 25) Color.RED else Color.parseColor("#00BFFF")
            batteryPercentView?.setTextColor(textColor)

            val iconIndex = when {
                percent >= 80 -> 5
                percent >= 60 -> 4
                percent >= 40 -> 3
                percent >= 20 -> 2
                else -> 1
            }
            val iconName = "battery_$iconIndex.png"
            val icon = loadBitmapFromFolder(iconName)
                ?: loadBitmapFromFolder(getString(R.string.battery_icon))
            batteryImageView?.setImageBitmap(icon)

            batteryImageView?.setOnClickListener {
                lifecycleScope.launch(Dispatchers.IO) {
                    val resp = loadBatteryCareResponse()
                    if (!resp.isNullOrBlank()) {
                        withContext(Dispatchers.Main) { addChatMessage(currentMascotName, resp) }
                    }
                }
            }

            btnCharging?.visibility = if (plugged > 0) View.VISIBLE else View.GONE

            handleBatteryWarnings(percent)
        }
    }

    private fun handleBatteryWarnings(percent: Int) {
        val urgentThreshold = 5
        val warningThreshold = 15

        if (percent <= urgentThreshold && lastBatteryWarningStage > urgentThreshold) {
            addChatMessage(currentMascotName, getString(R.string.battery_urgent))
        } else if (percent <= warningThreshold && lastBatteryWarningStage > warningThreshold) {
            val variants = listOf(
                getString(R.string.battery_warning_1),
                getString(R.string.battery_warning_2),
                getString(R.string.battery_warning_3)
            )
            addChatMessage(currentMascotName, variants.random())
        }
        lastBatteryWarningStage = percent
    }

    private fun loadBatteryCareResponse(): String? {
        return try {
            folderUri?.let { uri ->
                val dir = DocumentFile.fromTreeUri(this, uri) ?: return null
                dir.findFile(getString(R.string.battery_care_file))?.takeIf { it.exists() }?.let { file ->
                    contentResolver.openInputStream(file.uri)?.bufferedReader()?.use { it.readText().trim() }
                }
            }
        } catch (e: Exception) {
            showCustomToast(getString(R.string.error_loading_battery_care, e.message ?: ""))
            null
        }
    }

    private fun updateNetworkUI() {
        runOnUiThread {
            try {
                val connectivityManager = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
                val isAirplaneMode = Settings.Global.getInt(contentResolver, Settings.Global.AIRPLANE_MODE_ON, 0) != 0
                val network = connectivityManager.activeNetwork
                val caps = connectivityManager.getNetworkCapabilities(network)
                val isConnected = caps != null

                val iconName = when {
                    isAirplaneMode || !isConnected -> getString(R.string.airplane_icon)
                    caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true -> getString(R.string.wifi_icon)
                    caps?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) == true -> getString(R.string.mobile_icon)
                    else -> getString(R.string.airplane_icon)
                }
                (binding.topBar.leftLayout.getChildAt(1) as? ImageView)?.setImageBitmap(loadBitmapFromFolder(iconName))
            } catch (_: Exception) {}
        }
    }

    private fun updateBluetoothUI() {
        runOnUiThread {
            val isEnabled = BluetoothAdapter.getDefaultAdapter()?.isEnabled == true
            val bluetoothImageView = binding.topBar.leftLayout.getChildAt(0) as? ImageView
            if (isEnabled) {
                bluetoothImageView?.setImageBitmap(loadBitmapFromFolder(getString(R.string.bluetooth_icon)))
                bluetoothImageView?.visibility = View.VISIBLE
            } else {
                bluetoothImageView?.visibility = View.GONE
            }
        }
    }

    private fun loadTimeToSleepResponse(): String? {
        return try {
            folderUri?.let { uri ->
                val dir = DocumentFile.fromTreeUri(this, uri) ?: return null
                dir.findFile(getString(R.string.time_to_sleep_file))?.takeIf { it.exists() }?.let { file ->
                    contentResolver.openInputStream(file.uri)?.bufferedReader()?.use { reader ->
                        reader.readText().split("|").map { it.trim() }.filter { it.isNotEmpty() }.randomOrNull()
                    }
                }
            }
        } catch (e: Exception) {
            showCustomToast(getString(R.string.error_loading_time_to_sleep, e.message ?: ""))
            null
        }
    }

    private fun processDateTimeQuery(query: String): String? {
        val calendar = Calendar.getInstance()
        val ruLocale = Locale("ru")
        val date = SimpleDateFormat("dd MMMM yyyy", ruLocale).format(calendar.time)
        val day = SimpleDateFormat("EEEE", ruLocale).format(calendar.time)
        val year = SimpleDateFormat("yyyy", ruLocale).format(calendar.time)

        val template = loadDateTimeTemplate() ?: return getDefaultDateTimeResponse(query, date, day, year)

        return when {
            query.contains(getString(R.string.date_keyword)) -> template.replace("{date}", date)
            query.contains(getString(R.string.day_keyword)) -> template.replace("{day}", day)
            query.contains(getString(R.string.year_keyword)) -> template.replace("{year}", year)
            else -> template.replace("{date}", date)
        }
    }

    private fun loadDateTimeTemplate(): String? {
        return try {
            folderUri?.let { uri ->
                val dir = DocumentFile.fromTreeUri(this, uri) ?: return null
                dir.findFile(getString(R.string.calendar_system_file))?.takeIf { it.exists() }?.let { file ->
                    contentResolver.openInputStream(file.uri)?.bufferedReader()?.use { reader ->
                        reader.readText().split("|").map { it.trim() }.filter { it.isNotEmpty() }.randomOrNull()
                    }
                }
            }
        } catch (e: Exception) {
            showCustomToast(getString(R.string.error_loading_calendar_system, e.message ?: ""))
            null
        }
    }

    private fun getDefaultDateTimeResponse(query: String, date: String, day: String, year: String): String {
        return when {
            query.contains(getString(R.string.date_keyword)) -> getString(R.string.date_response, date)
            query.contains(getString(R.string.day_keyword)) -> getString(R.string.day_response, day)
            query.contains(getString(R.string.year_keyword)) -> getString(R.string.year_response, year)
            else -> getString(R.string.date_day_response, date, day)
        }
    }

    private fun startTimeUpdater() {
        stopTimeUpdater()
        timeUpdaterRunnable = object : Runnable {
            override fun run() {
                val now = Date()
                val format = if (DateFormat.is24HourFormat(this@ChatActivity)) "HH:mm" else "hh:mm a"
                val timeString = SimpleDateFormat(format, Locale.getDefault()).format(now)
                runOnUiThread { (binding.topBar.root.getChildAt(1) as? TextView)?.text = timeString }
                timeHandler.postDelayed(this, 60000L)
            }
        }
        timeHandler.post(timeUpdaterRunnable!!)
    }

    private fun stopTimeUpdater() {
        timeUpdaterRunnable?.let { timeHandler.removeCallbacks(it) }
        timeUpdaterRunnable = null
    }

    private fun createDataSnapshots(): DataSnapshots {
        return DataSnapshots(contextMap, templatesMap, keywordResponses)
    }

    private suspend fun findSubqueryResponses(
        qFiltered: String,
        qTokensFiltered: List<String>,
        snapshots: DataSnapshots
    ): List<String> {
        return emptyList()
    }

    private fun findKeywordResponse(qFiltered: String, snapshots: DataSnapshots): String? {
        return snapshots.keywordResponses[qFiltered]?.random()
    }

    private suspend fun findFuzzyMatchResponse(
        qFiltered: String,
        qTokensFiltered: List<String>,
        snapshots: DataSnapshots
    ): String? {
        val candidates = findCandidates(qTokensFiltered, invertedIndex)
            .ifEmpty { templatesMap.keys.take(MAX_CANDIDATES_FOR_LEV) }

        val bestJaccard = findBestJaccardMatch(qTokensFiltered, candidates, JACCARD_THRESHOLD)
        if (bestJaccard != null) return snapshots.templatesMap[bestJaccard]?.random()

        val bestLev = findBestLevenshteinMatch(qFiltered, candidates)
        if (bestLev != null) return snapshots.templatesMap[bestLev]?.random()

        return null
    }

    private fun findCandidates(qTokens: List<String>, localInverted: Map<String, List<String>>): List<String> {
        val candidates = mutableSetOf<String>()
        for (token in qTokens) {
            localInverted[token]?.let { candidates.addAll(it) }
        }
        return candidates.distinct().take(MAX_CANDIDATES_FOR_LEV)
    }

    private fun findBestJaccardMatch(
        tokens: List<String>,
        candidates: List<String>,
        threshold: Double
    ): String? {
        if (candidates.isEmpty()) return null
        val querySet = tokens.toSet()
        var bestMatch: String? = null
        var bestScore = 0.0
        for (cand in candidates) {
            val candTokens = filterStopwordsAndMapSynonyms(cand).first
            val candSet = candTokens.toSet()
            val intersection = querySet.intersect(candSet).size.toDouble()
            val union = (querySet.size + candSet.size - intersection).toDouble()
            val score = if (union > 0) intersection / union else 0.0
            if (score >= threshold && score > bestScore) {
                bestScore = score
                bestMatch = cand
            }
        }
        return bestMatch
    }

    private fun findBestLevenshteinMatch(qFiltered: String, candidates: List<String>): String? {
        if (candidates.isEmpty()) return null
        val bestCandidate = candidates.minByOrNull { cand -> levenshtein(qFiltered, cand, qFiltered) } ?: return null
        val dist = levenshtein(qFiltered, bestCandidate, qFiltered)
        return if (dist < Int.MAX_VALUE / 2) bestCandidate else null
    }

    private fun updateQuerySpamCount(qFiltered: String) {
        queryCountMap[qFiltered] = queryCountMap.getOrDefault(qFiltered, 0) + 1
    }

    private fun parseTemplatesFromFile(filename: String): Pair<Map<String, List<String>>, Map<String, List<String>>> {
        val localTemplates = HashMap<String, MutableList<String>>()
        val localKeywords = HashMap<String, MutableList<String>>()
        val uri = folderUri ?: return Pair(emptyMap(), emptyMap())
        val dir = DocumentFile.fromTreeUri(this, uri) ?: return Pair(emptyMap(), emptyMap())
        val file = dir.findFile(filename)?.takeIf { it.exists() } ?: return Pair(emptyMap(), emptyMap())
        try {
            contentResolver.openInputStream(file.uri)?.bufferedReader()?.useLines { seq ->
                for (raw in seq) processTemplateLineForLocal(raw, filename, localTemplates, localKeywords)
            }
        } catch (e: Exception) {
            // Ignore for local parse
        }
        return Pair(localTemplates, localKeywords)
    }

    private fun processTemplateLineForLocal(
        rawLine: String,
        filename: String,
        templates: MutableMap<String, MutableList<String>>,
        keywords: MutableMap<String, MutableList<String>>
    ) {
        val line = rawLine.trim()
        if (line.isEmpty()) return

        when {
            filename == getString(R.string.base_context_file) && line.startsWith(":") && line.endsWith(":") -> {
                // Skip context for local
            }
            line.startsWith("-") -> {
                val content = line.substring(1)
                val parts = content.split("=", limit = 2).map { it.trim() }
                if (parts.size == 2) {
                    val keyword = parts[0].lowercase(Locale.ROOT)
                    val responses = parts[1].split("|").mapNotNull { it.trim().takeIf { s -> s.isNotEmpty() } }
                    if (keyword.isNotEmpty() && responses.isNotEmpty()) {
                        keywords[keyword] = responses.toMutableList()
                    }
                }
            }
            line.contains("=") -> {
                val parts = line.split("=", limit = 2).map { it.trim() }
                if (parts.size == 2) {
                    val triggerFiltered = filterStopwordsAndMapSynonyms(normalizeText(parts[0])).second
                    val responses = parts[1].split("|").mapNotNull { it.trim().takeIf { s -> s.isNotEmpty() } }
                    if (triggerFiltered.isNotEmpty() && responses.isNotEmpty()) {
                        templates[triggerFiltered] = responses.toMutableList()
                    }
                }
            }
        }
    }
}
