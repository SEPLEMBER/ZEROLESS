package com.nemesis.droidcrypt

import android.animation.ObjectAnimator
import android.content.Intent
import android.graphics.*
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.ColorDrawable
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
import kotlin.math.min
import kotlin.math.abs

class ChatActivity : AppCompatActivity() {

    companion object {
        private const val PREF_KEY_FOLDER_URI = "pref_folder_uri"
        private const val PREF_KEY_DISABLE_SCREENSHOTS = "pref_disable_screenshots"
        private const val MAX_MESSAGES = 250
        private const val MAX_FUZZY_DISTANCE = 5
        private const val CANDIDATE_TOKEN_THRESHOLD = 1
        private const val MAX_CANDIDATES_FOR_LEV = 40
        private const val JACCARD_THRESHOLD = 0.35
    }

    // UI Elements
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

    // Data Structures
    private val fallback = arrayOf("Привет", "Как дела?", "Расскажи о себе", "Выход")
    private val templatesMap = HashMap<String, MutableList<String>>() // normalized triggers -> responses
    private val contextMap = HashMap<String, String>()
    private val keywordResponses = HashMap<String, MutableList<String>>()
    private val antiSpamResponses = mutableListOf<String>()
    private val mascotList = mutableListOf<Map<String, String>>()
    private val dialogLines = mutableListOf<String>()
    private val dialogs = mutableListOf<Dialog>()

    // Fuzzy Matching Support
    private val invertedIndex = HashMap<String, MutableList<String>>() // token -> list of trigger keys
    private val synonymsMap = HashMap<String, String>() // synonym -> canonical
    private val stopwords = HashSet<String>() // normalized stopwords

    // Temporary UI Elements
    private var typingOrangeView: View? = null
    private val slashTags = "user_slash"

    // State
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

    private data class Dialog(val name: String, val replies: MutableList<Map<String, String>> = mutableListOf())

    init {
        antiSpamResponses.addAll(
            listOf(
                "Ты надоел, давай что-то новенького!",
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

        // Initialize UI References
        scrollView = findViewById(R.id.scrollView)
        queryInput = findViewById(R.id.queryInput)
        envelopeInputButton = findViewById(R.id.envelope_button)
        mascotTopImage = findViewById(R.id.mascot_top_image)
        btnLock = findViewById(R.id.btn_lock)
        btnTrash = findViewById(R.id.btn_trash)
        btnEnvelopeTop = findViewById(R.id.btn_envelope_top)
        btnSettings = findViewById(R.id.btn_settings)
        messagesContainer = findViewById(R.id.chatMessagesContainer)

        // Load Folder URI
        folderUri = intent?.getParcelableExtra("folderUri")
        if (folderUri == null) {
            contentResolver.persistedUriPermissions.firstOrNull { it.isReadPermission }?.let {
                folderUri = it.uri
            }
        }
        try {
            val prefs = PreferenceManager.getDefaultSharedPreferences(this)
            folderUri ?: prefs.getString(PREF_KEY_FOLDER_URI, null)?.let { saved ->
                folderUri = Uri.parse(saved)
            }
        } catch (_: Exception) {}

        // Load Synonyms and Stopwords
        loadSynonymsAndStopwords()

        // Apply Screenshot Lock
        try {
            val prefs = PreferenceManager.getDefaultSharedPreferences(this)
            if (prefs.getBoolean(PREF_KEY_DISABLE_SCREENSHOTS, false)) {
                window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
            } else {
                window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
            }
        } catch (_: Exception) {}

        // Load Toolbar Icons and Setup Effects
        loadToolbarIcons()
        listOf(btnLock, btnTrash, btnEnvelopeTop, btnSettings, envelopeInputButton).forEach {
            setupIconTouchEffect(it)
        }

        // Setup Button Listeners
        btnLock?.setOnClickListener { finish() }
        btnTrash?.setOnClickListener { clearChat() }
        btnSettings?.setOnClickListener { startActivity(Intent(this, SettingsActivity::class.java)) }
        // btnEnvelopeTop?.setOnClickListener { startActivity(Intent(this, PostActivity::class.java)) } // Commented out due to unresolved reference
        envelopeInputButton?.setOnClickListener {
            val input = queryInput.text.toString().trim()
            if (input.isNotEmpty()) {
                processUserQuery(input)
                queryInput.setText("")
            }
        }

        // Initial Load
        if (folderUri == null) {
            showCustomToast("Папка не выбрана! Открой настройки и выбери папку.")
            loadFallbackTemplates()
        } else {
            loadTemplatesFromFile(currentContext)
        }
        rebuildInvertedIndex()
        updateAutoComplete()
        addChatMessage(currentMascotName, "Добро пожаловать!")

        // AutoComplete Click Listener
        queryInput.setOnItemClickListener { parent, _, position, _ ->
            val selected = parent.getItemAtPosition(position) as String
            queryInput.setText(selected)
            processUserQuery(selected)
        }

        // Setup Idle Checker
        idleCheckRunnable = object : Runnable {
            override fun run() {
                val idleTime = System.currentTimeMillis() - lastUserInputTime
                if (idleTime >= 25000L) {
                    if (dialogs.isNotEmpty()) {
                        startRandomDialog()
                    } else if (dialogLines.isNotEmpty()) {
                        triggerRandomDialog()
                    }
                }
                dialogHandler.postDelayed(this, 5000L)
            }
        }
        dialogHandler.postDelayed(idleCheckRunnable!!, 5000L)

        // Hide Top Mascot Image
        mascotTopImage?.visibility = View.GONE
    }

    override fun onResume() {
        super.onResume()
        folderUri?.let { loadTemplatesFromFile(currentContext) }
        rebuildInvertedIndex()
        updateAutoComplete()
        dialogHandler.removeCallbacks(idleCheckRunnable!!)
        dialogHandler.postDelayed(idleCheckRunnable!!, 5000L)
        loadToolbarIcons()
    }

    override fun onPause() {
        super.onPause()
        stopDialog()
        dialogHandler.removeCallbacksAndMessages(null)
    }

    // Toolbar Helpers
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
            val iconNames = mapOf(
                "lock.png" to btnLock,
                "trash.png" to btnTrash,
                "envelope.png" to btnEnvelopeTop,
                "settings.png" to btnSettings,
                "send.png" to envelopeInputButton
            )
            iconNames.forEach { (name, target) ->
                dir.findFile(name)?.takeIf { it.exists() }?.let { file ->
                    contentResolver.openInputStream(file.uri)?.use { input ->
                        val bitmap = BitmapFactory.decodeStream(input)
                        target?.setImageBitmap(bitmap)
                    }
                }
            }
        } catch (_: Exception) {}
    }

    // Core Chat Logic
    private fun processUserQuery(userInput: String) {
        val qOrigRaw = userInput.trim()
        val qOrig = normalizeText(qOrigRaw)
        val (qTokensFiltered, qFiltered) = filterStopwordsAndMapSynonyms(qOrig)

        if (qFiltered.isBlank()) return

        lastUserInputTime = System.currentTimeMillis()
        stopDialog()

        val qKeyForCount = qFiltered
        val repeats = queryCountMap.getOrDefault(qKeyForCount, 0) + 1
        queryCountMap[qKeyForCount] = repeats
        if (qKeyForCount != lastQuery) {
            queryCountMap.clear()
            queryCountMap[qKeyForCount] = 1
            lastQuery = qKeyForCount
        }

        addChatMessage("Ты", qOrigRaw)

        if (repeats >= 5) {
            addChatMessage(currentMascotName, antiSpamResponses.random())
            startIdleTimer()
            return
        }

        var answered = false
        var responseToSend: String? = null

        // Exact Match
        templatesMap[qFiltered]?.let { responses ->
            if (responses.isNotEmpty()) {
                responseToSend = responses.random()
                answered = true
            }
        }

        // Keyword Match
        if (!answered) {
            keywordResponses.forEach { (keyword, responses) ->
                if (qFiltered.contains(keyword) && responses.isNotEmpty()) {
                    responseToSend = responses.random()
                    answered = true
                    return@forEach
                }
            }
        }

        // Fuzzy Matching
        if (!answered) {
            answered = performFuzzyMatching(qFiltered, qTokensFiltered, qOrig)
        }

        // Context Switch
        if (!answered) {
            detectContext(qFiltered)?.let { newContext ->
                if (newContext != currentContext) {
                    currentContext = newContext
                    loadTemplatesFromFile(currentContext)
                    rebuildInvertedIndex()
                    updateAutoComplete()
                    templatesMap[qFiltered]?.let { responses ->
                        if (responses.isNotEmpty()) {
                            responseToSend = responses.random()
                            answered = true
                        }
                    }
                }
            }
        }

        // Fallback
        if (responseToSend == null) {
            responseToSend = getDummyResponse(qOrig)
        }

        scheduleBotReply(responseToSend!!)
        triggerRandomDialog()
        startIdleTimer()
    }

    private fun performFuzzyMatching(qFiltered: String, qTokensFiltered: List<String>, qOrig: String): Boolean {
        var answered = false
        var responseToSend: String? = null

        val qTokens = if (qTokensFiltered.isNotEmpty()) qTokensFiltered else tokenize(qFiltered)
        val candidateCounts = HashMap<String, Int>()
        qTokens.forEach { tok ->
            invertedIndex[tok]?.forEach { trig ->
                candidateCounts[trig] = candidateCounts.getOrDefault(trig, 0) + 1
            }
        }

        val candidates = if (candidateCounts.isNotEmpty()) {
            candidateCounts.entries
                .filter { it.value >= CANDIDATE_TOKEN_THRESHOLD }
                .sortedByDescending { it.value }
                .map { it.key }
                .take(MAX_CANDIDATES_FOR_LEV)
        } else {
            templatesMap.keys
                .filter { abs(it.length - qFiltered.length) <= MAX_FUZZY_DISTANCE }
                .take(MAX_CANDIDATES_FOR_LEV)
        }

        // Jaccard Similarity
        var bestJaccard = 0.0
        var bestByJaccard: String? = null
        val qSet = qTokens.toSet()
        candidates.forEach { key ->
            val keyTokens = filterStopwordsAndMapSynonyms(key).first.toSet()
            if (keyTokens.isEmpty()) return@forEach
            val inter = qSet.intersect(keyTokens).size.toDouble()
            val union = (qSet.union(keyTokens)).size.toDouble()
            val jaccard = if (union > 0) inter / union else 0.0
            if (jaccard > bestJaccard) {
                bestJaccard = jaccard
                bestByJaccard = key
            }
        }
        if (bestByJaccard != null && bestJaccard >= JACCARD_THRESHOLD) {
            templatesMap[bestByJaccard]?.let { responses ->
                if (responses.isNotEmpty()) {
                    responseToSend = responses.random()
                    answered = true
                }
            }
        }

        // Levenshtein Fallback
        if (!answered) {
            var bestDist = Int.MAX_VALUE
            var bestKey: String? = null
            candidates.forEach { key ->
                if (abs(key.length - qFiltered.length) > MAX_FUZZY_DISTANCE) return@forEach
                val dist = levenshtein(qFiltered, key)
                if (dist < bestDist) {
                    bestDist = dist
                    bestKey = key
                }
                if (bestDist == 0) return@forEach
            }
            if (bestKey != null && bestDist <= MAX_FUZZY_DISTANCE) {
                templatesMap[bestKey]?.let { responses ->
                    if (responses.isNotEmpty()) {
                        responseToSend = responses.random()
                        answered = true
                    }
                }
            }
        }

        if (answered) {
            scheduleBotReply(responseToSend!!)
        }

        return answered
    }

    private fun scheduleBotReply(response: String) {
        typingOrangeView?.let { view ->
            (view.parent as? ViewGroup)?.removeView(view)
            typingOrangeView = null
        }

        dialogHandler.postDelayed({
            if (typingOrangeView == null) {
                typingOrangeView = showTypingIndicatorOrange()
            }
        }, 5000L)

        val delay = 5000L + (4000..7000).random().toLong()
        dialogHandler.postDelayed({
            typingOrangeView?.let { view ->
                (view.parent as? ViewGroup)?.removeView(view)
                typingOrangeView = null
            }
            removeAllUserSlashIcons()
            playNotifySound()
            addChatMessage(currentMascotName, response)
        }, delay)
    }

    private fun showTypingIndicatorOrange(): View {
        return TextView(this).apply {
            text = "печатает..."
            textSize = 14f
            setTextColor(Color.parseColor("#FFA500"))
            alpha = 0.85f
            setPadding(dpToPx(16), dpToPx(8), dpToPx(16), dpToPx(8))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                setMargins(0, dpToPx(16), 0, 0)
            }
            messagesContainer.addView(this, 0)
        }
    }

    private fun playNotifySound() {
        val uri = folderUri ?: return
        try {
            val dir = DocumentFile.fromTreeUri(this, uri) ?: return
            val file = dir.findFile("Notify.ogg") ?: dir.findFile("notify.ogg")
            if (file?.exists() == true) {
                val pfd = contentResolver.openFileDescriptor(file.uri, "r") ?: return
                val mediaPlayer = MediaPlayer().apply {
                    setDataSource(pfd.fileDescriptor)
                    pfd.close()
                    setOnCompletionListener { it.release() }
                    prepare()
                    start()
                }
            }
        } catch (_: Exception) {}
    }

    private fun removeAllUserSlashIcons() {
        val toRemove = mutableListOf<View>()
        for (i in 0 until messagesContainer.childCount) {
            findTaggedViews(messagesContainer.getChildAt(i), toRemove)
        }
        toRemove.forEach { view ->
            (view.parent as? ViewGroup)?.removeView(view)
        }
    }

    private fun findTaggedViews(view: View, outList: MutableList<View>) {
        if (view.tag == slashTags) {
            outList.add(view)
            return
        }
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) {
                findTaggedViews(view.getChildAt(i), outList)
            }
        }
    }

    private fun showTypingIndicator() {
        val typingView = TextView(this).apply {
            text = "печатает..."
            textSize = 14f
            setTextColor(getColor(android.R.color.white))
            setBackgroundColor(0x80000000)
            alpha = 0.7f
            setPadding(dpToPx(16), dpToPx(8), dpToPx(16), dpToPx(8))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                setMargins(0, dpToPx(16), 0, 0)
            }
        }
        messagesContainer.addView(typingView, 0)
        val delay = (1000..3000).random().toLong()
        dialogHandler.postDelayed({ messagesContainer.removeView(typingView) }, delay)
    }

    private fun clearChat() {
        messagesContainer.removeAllViews()
        queryCountMap.clear()
        lastQuery = ""
        currentContext = "base.txt"
        loadTemplatesFromFile(currentContext)
        rebuildInvertedIndex()
        updateAutoComplete()
        addChatMessage(currentMascotName, "Чат очищен. Возвращаюсь к началу.")
    }

    private fun detectContext(input: String): String? {
        contextMap.forEach { (keyword, context) ->
            if (input.contains(keyword)) return context
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

    // Text Processing Helpers
    private fun normalizeText(s: String): String {
        val lower = s.lowercase(Locale.getDefault())
        val cleaned = lower.replace(Regex("[^\\p{L}\\p{Nd}\\s]"), " ")
        return cleaned.replace(Regex("\\s+"), " ").trim()
    }

    private fun tokenize(s: String): List<String> {
        if (s.isBlank()) return emptyList()
        return s.split(Regex("\\s+")).map { it.trim() }.filter { it.isNotEmpty() }
    }

    private fun loadSynonymsAndStopwords() {
        synonymsMap.clear()
        stopwords.clear()
        val uri = folderUri ?: return
        try {
            val dir = DocumentFile.fromTreeUri(this, uri) ?: return

            // Load Synonyms
            dir.findFile("synonims.txt")?.takeIf { it.exists() }?.let { file ->
                contentResolver.openInputStream(file.uri)?.bufferedReader()?.use { reader ->
                    reader.forEachLine { line ->
                        var trimmed = line.trim()
                        if (trimmed.isEmpty()) return@forEachLine
                        if (trimmed.startsWith("*") && trimmed.endsWith("*")) {
                            trimmed = trimmed.substring(1, trimmed.length - 1)
                        }
                        val parts = trimmed.split(";").map { normalizeText(it.trim()) }.filter { it.isNotEmpty() }
                        if (parts.isNotEmpty()) {
                            val canonical = parts.last()
                            parts.forEach { synonymsMap[it] = canonical }
                        }
                    }
                }
            }

            // Load Stopwords
            dir.findFile("stopwords.txt")?.takeIf { it.exists() }?.let { file ->
                contentResolver.openInputStream(file.uri)?.bufferedReader()?.use { reader ->
                    val content = reader.readText()
                    if (content.isNotEmpty()) {
                        content.split("^").map { normalizeText(it.trim()) }.filter { it.isNotEmpty() }
                            .forEach { stopwords.add(it) }
                    }
                }
            }
        } catch (_: Exception) {}
    }

    private fun filterStopwordsAndMapSynonyms(input: String): Pair<List<String>, String> {
        val tokens = tokenize(input)
        val filtered = tokens.map { tok ->
            val normalized = normalizeText(tok)
            synonymsMap[normalized] ?: normalized
        }.filter { it.isNotEmpty() && !stopwords.contains(it) }
        val joined = filtered.joinToString(" ")
        return Pair(filtered, joined)
    }

    private fun rebuildInvertedIndex() {
        invertedIndex.clear()
        templatesMap.forEach { (key, _) ->
            val tokens = filterStopwordsAndMapSynonyms(key).first
            tokens.forEach { tok ->
                val list = invertedIndex.getOrPut(tok) { mutableListOf() }
                if (!list.contains(key)) list.add(key)
            }
        }
    }

    private fun levenshtein(s: String, t: String): Int {
        if (s == t) return 0
        val n = s.length
        val m = t.length
        if (n == 0) return m
        if (m == 0) return n
        if (abs(n - m) > MAX_FUZZY_DISTANCE + 2) return Int.MAX_VALUE / 2

        val prev = IntArray(m + 1) { it }
        val curr = IntArray(m + 1)

        for (i in 1..n) {
            curr[0] = i
            var minInRow = i
            val si = s[i - 1]
            for (j in 1..m) {
                val cost = if (si == t[j - 1]) 0 else 1
                val del = prev[j] + 1
                val ins = curr[j - 1] + 1
                val sub = prev[j - 1] + cost
                curr[j] = minOf(del, min(ins, sub))
                minInRow = minOf(minInRow, curr[j])
            }
            if (minInRow > MAX_FUZZY_DISTANCE + 2) return Int.MAX_VALUE / 2
            System.arraycopy(curr, 0, prev, 0, m + 1)
        }
        return prev[m]
    }

    // UI Messages
    private fun addChatMessage(sender: String, text: String) {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            val pad = dpToPx(6)
            setPadding(pad, pad / 2, pad, pad / 2)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }

        val isUser = sender.equals("Ты", ignoreCase = true)

        if (isUser) {
            val bubble = createMessageBubble(sender, text, true)
            val lp = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                gravity = Gravity.END
                marginStart = dpToPx(48)
            }
            row.addView(spaceView(), LinearLayout.LayoutParams(0, 0, 1f))
            row.addView(bubble, lp)

            // Add Slash Icon
            val slashView = TextView(this).apply {
                text = "/"
                textSize = 14f
                setPadding(dpToPx(6), dpToPx(4), dpToPx(6), dpToPx(4))
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply {
                    gravity = Gravity.CENTER_VERTICAL
                    marginStart = dpToPx(6)
                }
                tag = slashTags
            }
            row.addView(slashView)
            dialogHandler.postDelayed({
                try {
                    slashView.text = "//"
                } catch (_: Exception) {}
            }, 2000L)
        } else {
            val avatarView = ImageView(this).apply {
                val size = dpToPx(64)
                layoutParams = LinearLayout.LayoutParams(size, size)
                scaleType = ImageView.ScaleType.CENTER_CROP
                adjustViewBounds = true
                loadAvatarInto(this, sender)
            }
            val bubble = createMessageBubble(sender, text, false)
            val bubbleLp = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                marginStart = dpToPx(8)
            }
            row.addView(avatarView)
            row.addView(bubble, bubbleLp)
        }

        messagesContainer.addView(row)
        trimMessagesIfNeeded()
        scrollView.post { scrollView.smoothScrollTo(0, messagesContainer.bottom) }
    }

    private fun spaceView(): View = View(this).apply {
        layoutParams = LinearLayout.LayoutParams(0, 0, 1f)
    }

    private fun createMessageBubble(sender: String, text: String, isUser: Boolean): LinearLayout {
        val container = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val tvSender = TextView(this).apply {
            text = "$sender:"
            textSize = 12f
            setTextColor(Color.parseColor("#AAAAAA"))
        }
        val tv = TextView(this).apply {
            text = text
            textSize = 16f
            setTextIsSelectable(true)
            val pad = dpToPx(10)
            setPadding(pad, pad, pad, pad)
            val accent = safeParseColorOrDefault(currentThemeColor, Color.parseColor("#00FF00"))
            background = createBubbleDrawable(accent)
            setTextColor(accent)
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
        val uri = folderUri ?: run {
            target.setImageResource(android.R.color.transparent)
            return
        }
        try {
            val dir = DocumentFile.fromTreeUri(this, uri) ?: return
            val s = sender.lowercase(Locale.getDefault())
            val candidates = listOf("${s}_icon.png", "${s}_avatar.png", "${s}.png", currentMascotIcon)
            candidates.forEach { name ->
                dir.findFile(name)?.takeIf { it.exists() }?.let { file ->
                    contentResolver.openInputStream(file.uri)?.use { input ->
                        val bitmap = BitmapFactory.decodeStream(input)
                        target.setImageBitmap(bitmap)
                        return
                    }
                }
            }
        } catch (_: Exception) {}
        target.setImageResource(android.R.color.transparent)
    }

    private fun blendColors(base: Int, accent: Int, ratio: Float): Int {
        val r = (Color.red(base) * (1 - ratio) + Color.red(accent) * ratio).roundToInt()
        val g = (Color.green(base) * (1 - ratio) + Color.green(accent) * ratio).roundToInt()
        val b = (Color.blue(base) * (1 - ratio) + Color.blue(accent) * ratio).roundToInt()
        return Color.rgb(r, g, b)
    }

    private fun safeParseColorOrDefault(spec: String?, fallback: Int): Int = try {
        Color.parseColor(spec ?: throw IllegalArgumentException("Invalid color"))
    } catch (_: Exception) {
        fallback
    }

    private fun dpToPx(dp: Int): Int = (dp * resources.displayMetrics.density).roundToInt()

    private fun trimMessagesIfNeeded() {
        while (messagesContainer.childCount > MAX_MESSAGES) {
            messagesContainer.removeViewAt(0)
        }
    }

    // Template Loading
    private fun loadTemplatesFromFile(filename: String) {
        templatesMap.clear()
        keywordResponses.clear()
        mascotList.clear()
        dialogLines.clear()
        dialogs.clear()

        if (filename == "base.txt") {
            contextMap.clear()
        }

        currentMascotName = "Racky"
        currentMascotIcon = "raccoon_icon.png"
        currentThemeColor = "#00FF00"
        currentThemeBackground = "#000000"

        loadSynonymsAndStopwords()

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

            val file = dir.findFile(filename) ?: run {
                loadFallbackTemplates()
                updateUI(currentMascotName, currentMascotIcon, currentThemeColor, currentThemeBackground)
                return
            }
            if (!file.exists()) {
                loadFallbackTemplates()
                updateUI(currentMascotName, currentMascotIcon, currentThemeColor, currentThemeBackground)
                return
            }

            // Parse Main File
            contentResolver.openInputStream(file.uri)?.bufferedReader()?.use { reader ->
                reader.forEachLine { line ->
                    val trimmed = line.trim()
                    if (trimmed.isEmpty()) return@forEachLine

                    if (filename == "base.txt" && trimmed.startsWith(":") && trimmed.endsWith(":")) {
                        val contextLine = trimmed.substring(1, trimmed.length - 1)
                        contextLine.split("=", limit = 2).takeIf { it.size == 2 }?.let { parts ->
                            val keyword = parts[0].trim().lowercase(Locale.ROOT)
                            val contextFile = parts[1].trim()
                            if (keyword.isNotEmpty() && contextFile.isNotEmpty()) {
                                contextMap[keyword] = contextFile
                            }
                        }
                        return@forEachLine
                    }

                    if (trimmed.startsWith("-")) {
                        val keywordLine = trimmed.substring(1)
                        keywordLine.split("=", limit = 2).takeIf { it.size == 2 }?.let { parts ->
                            val keyword = parts[0].trim().lowercase(Locale.ROOT)
                            val responses = parts[1].split("|").mapNotNull { it.trim().takeIf { it.isNotEmpty() } }
                            if (keyword.isNotEmpty() && responses.isNotEmpty()) {
                                keywordResponses[keyword] = responses.toMutableList()
                            }
                        }
                        return@forEachLine
                    }

                    if (!trimmed.contains("=")) return@forEachLine
                    trimmed.split("=", limit = 2).takeIf { it.size == 2 }?.let { parts ->
                        val triggerRaw = parts[0].trim()
                        val trigger = normalizeText(triggerRaw)
                        val triggerFiltered = filterStopwordsAndMapSynonyms(trigger).second
                        val responses = parts[1].split("|").mapNotNull { it.trim().takeIf { it.isNotEmpty() } }
                        if (triggerFiltered.isNotEmpty() && responses.isNotEmpty()) {
                            templatesMap[triggerFiltered] = responses.toMutableList()
                        }
                    }
                }
            }

            // Parse Metadata
            val metadataFile = dir.findFile(filename.replace(".txt", "_metadata.txt"))
            if (metadataFile?.exists() == true) {
                contentResolver.openInputStream(metadataFile.uri)?.bufferedReader()?.use { reader ->
                    reader.forEachLine { line ->
                        val trimmed = line.trim()
                        when {
                            trimmed.startsWith("mascot_list=") -> {
                                trimmed.substring("mascot_list=".length).split("|").forEach { mascotStr ->
                                    mascotStr.split(":").takeIf { it.size == 4 }?.let { parts ->
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
                            trimmed.startsWith("mascot_name=") -> currentMascotName = trimmed.substring("mascot_name=".length).trim()
                            trimmed.startsWith("mascot_icon=") -> currentMascotIcon = trimmed.substring("mascot_icon=".length).trim()
                            trimmed.startsWith("theme_color=") -> currentThemeColor = trimmed.substring("theme_color=".length).trim()
                            trimmed.startsWith("theme_background=") -> currentThemeBackground = trimmed.substring("theme_background=".length).trim()
                            trimmed.startsWith("dialog_lines=") -> {
                                trimmed.substring("dialog_lines=".length).split("|").map { it.trim() }.filter { it.isNotEmpty() }
                                    .forEach { dialogLines.add(it) }
                            }
                        }
                    }
                }
            }

            // Select Random Mascot for Base
            if (filename == "base.txt" && mascotList.isNotEmpty()) {
                mascotList.random().let { selected ->
                    selected["name"]?.let { currentMascotName = it }
                    selected["icon"]?.let { currentMascotIcon = it }
                    selected["color"]?.let { currentThemeColor = it }
                    selected["background"]?.let { currentThemeBackground = it }
                }
            }

            rebuildInvertedIndex()
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

        val t1 = filterStopwordsAndMapSynonyms(normalizeText("привет")).second
        templatesMap[t1] = mutableListOf("Привет! Чем могу помочь?", "Здравствуй!")
        val t2 = filterStopwordsAndMapSynonyms(normalizeText("как дела")).second
        templatesMap[t2] = mutableListOf("Всё отлично, а у тебя?", "Нормально, как дела?")
        keywordResponses["спасибо"] = mutableListOf("Рад, что помог!", "Всегда пожалуйста!")
    }

    private fun updateAutoComplete() {
        val suggestions = templatesMap.keys.toMutableList()
        fallback.map { it.lowercase(Locale.ROOT) }.filter { !suggestions.contains(it) }.forEach { suggestions.add(it) }

        if (adapter == null) {
            adapter = object : ArrayAdapter<String>(this, android.R.layout.simple_dropdown_item_1line, suggestions) {
                override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                    val view = super.getView(position, convertView, parent) as TextView
                    view.setTextColor(Color.WHITE)
                    return view
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

    // Idle & Dialogs
    private fun triggerRandomDialog() {
        if (dialogLines.isNotEmpty() && random.nextDouble() < 0.3) {
            dialogHandler.postDelayed({
                if (dialogLines.isEmpty()) return@postDelayed
                val line = dialogLines.random()
                if (mascotList.isNotEmpty()) {
                    mascotList.random().let { rnd ->
                        val name = rnd["name"] ?: currentMascotName
                        loadMascotMetadata(name)
                        addChatMessage(name, line)
                    }
                } else {
                    addChatMessage(currentMascotName, line)
                }
            }, 1500L)
        }
        if (mascotList.isNotEmpty() && random.nextDouble() < 0.1) {
            dialogHandler.postDelayed({
                mascotList.random().let { rnd ->
                    val name = rnd["name"] ?: currentMascotName
                    loadMascotMetadata(name)
                    addChatMessage(name, "Эй, мы не закончили!")
                }
            }, 2500L)
        }
    }

    private fun startRandomDialog() {
        if (dialogs.isEmpty()) return
        stopDialog()
        currentDialog = dialogs.random()
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
                    dialogHandler.postDelayed(this, (10000..25000).random().toLong())
                } else {
                    dialogHandler.postDelayed({ startRandomDialog() }, (5000..25000).random().toLong())
                }
            }
        }
        dialogHandler.postDelayed(dialogRunnable!!, (10000..25000).random().toLong())
    }

    private fun stopDialog() {
        dialogRunnable?.let { dialogHandler.removeCallbacks(it) }
        dialogRunnable = null
    }

    private fun loadMascotMetadata(mascotName: String) {
        val uri = folderUri ?: return
        val metadataFileName = "${mascotName.lowercase(Locale.ROOT)}_metadata.txt"
        val dir = DocumentFile.fromTreeUri(this, uri) ?: return
        dir.findFile(metadataFileName)?.takeIf { it.exists() }?.let { file ->
            try {
                contentResolver.openInputStream(file.uri)?.bufferedReader()?.use { reader ->
                    reader.forEachLine { line ->
                        val trimmed = line.trim()
                        when {
                            trimmed.startsWith("mascot_name=") -> currentMascotName = trimmed.substring("mascot_name=".length).trim()
                            trimmed.startsWith("mascot_icon=") -> currentMascotIcon = trimmed.substring("mascot_icon=".length).trim()
                            trimmed.startsWith("theme_color=") -> currentThemeColor = trimmed.substring("theme_color=".length).trim()
                            trimmed.startsWith("theme_background=") -> currentThemeBackground = trimmed.substring("theme_background=".length).trim()
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
        mascotTopImage?.visibility = View.GONE
        try {
            messagesContainer.setBackgroundColor(Color.parseColor(themeBackground))
        } catch (_: Exception) {}
    }

    // Utils
    private fun showCustomToast(message: String) {
        try {
            val layout = layoutInflater.inflate(R.layout.custom_toast, null)
            layout.findViewById<TextView>(R.id.customToastText)?.text = message
            val toast = Toast(applicationContext).apply {
                duration = Toast.LENGTH_SHORT
                view = layout
            }
            toast.show()
        } catch (_: Exception) {
            Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
        }
    }

    private fun startIdleTimer() {
        lastUserInputTime = System.currentTimeMillis()
        dialogHandler.removeCallbacks(idleCheckRunnable!!)
        dialogHandler.postDelayed(idleCheckRunnable!!, 5000L)
    }
}
