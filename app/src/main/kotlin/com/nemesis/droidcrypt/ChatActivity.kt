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
import java.util.*
import kotlin.math.roundToInt
import kotlin.math.min // <-- ADDED
import kotlin.math.abs // <-- ADDED
import android.graphics.drawable.ColorDrawable

class ChatActivity : AppCompatActivity() {

    companion object {
        private const val PREF_KEY_FOLDER_URI = "pref_folder_uri"
        private const val PREF_KEY_DISABLE_SCREENSHOTS = "pref_disable_screenshots"
        private const val MAX_CONTEXT_SWITCH = 6 // Note: This is now unused due to logic simplification
        private const val MAX_MESSAGES = 250
        private const val CANDIDATE_TOKEN_THRESHOLD = 2 // <-- ADDED: минимальное число общих токенов для кандидата
        private const val MAX_SUBQUERY_RESPONSES = 3 // Ограничение на количество подответов 
        private const val SUBQUERY_RESPONSE_DELAY = 1500L // Задержка для индикатора "печатает..." }

        private const val MAX_CANDIDATES_FOR_LEV = 25 // <-- ADDED: ограничение числа кандидатов для Levenshtein
        private const val JACCARD_THRESHOLD = 0.50
    }

    private fun getFuzzyDistance(word: String): Int {
    return when {
        word.length <= 4 -> 1
        word.length <= 8 -> 2
        else -> 3
    }
}
    
    /// SECTION: UI и Data — Объявление переменных (UI-элементы, карты шаблонов, состояния маскотов/контекста, idle-данные)
    // UI
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

    // Data structures
    private val fallback = arrayOf("Привет", "Как дела?", "Расскажи о себе", "Выход")
    private val templatesMap = HashMap<String, MutableList<String>>() // keys are normalized triggers now
    private val contextMap = HashMap<String, String>()
    private val keywordResponses = HashMap<String, MutableList<String>>()
    private val antiSpamResponses = mutableListOf<String>()
    private val mascotList = mutableListOf<Map<String, String>>()
    private val dialogLines = mutableListOf<String>()
    private val dialogs = mutableListOf<Dialog>()

    // <-- ADDED: inverted index token -> list of triggers (normalized)
    private val invertedIndex = HashMap<String, MutableList<String>>() // token -> list of trigger keys

    // <-- ADDED: synonyms and stopwords storage
    private val synonymsMap = HashMap<String, String>() // synonym -> canonical
    private val stopwords = HashSet<String>() // normalized stopwords set

    private var currentMascotName = "Racky"
    private var currentMascotIcon = "raccoon_icon.png"
    private var currentThemeColor = "#00FF00"
    private var currentThemeBackground = "#000000"
    private var currentContext = "base.txt"
    private var lastQuery = ""

    // Dialogs / idle
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

    /// SECTION: Lifecycle — Инициализация Activity (onCreate, onResume, onPause) — Настройка UI, prefs, иконок, listeners, начальная загрузка
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

        // SAF Uri: Intent -> persisted -> prefs
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

        // <-- ADDED: load synonyms & stopwords early (if folderUri available this will read files)
        loadSynonymsAndStopwords()

        // screenshots lock from prefs
        try {
            val prefs = PreferenceManager.getDefaultSharedPreferences(this)
            val disable = prefs.getBoolean(PREF_KEY_DISABLE_SCREENSHOTS, false)
            if (disable) window.addFlags(WindowManager.LayoutParams.FLAG_SECURE) else window.clearFlags(
                WindowManager.LayoutParams.FLAG_SECURE
            )
        } catch (_: Exception) {
        }

        // load icons
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
        btnEnvelopeTop?.setOnClickListener { startActivity(Intent(this, PostsActivity::class.java)) }

        // envelope near input — отправка
        envelopeInputButton?.setOnClickListener {
            val input = queryInput.text.toString().trim()
            if (input.isNotEmpty()) {
                processUserQuery(input)
                queryInput.setText("")
            }
        }

        // initial parse / fallback
        if (folderUri == null) {
            showCustomToast("Папка не выбрана! Открой настройки и выбери папку.")
            loadFallbackTemplates()
            rebuildInvertedIndex() // <-- ADDED: build index for fallback
            updateAutoComplete()
            addChatMessage(currentMascotName, "Добро пожаловать!")
        } else {
            loadTemplatesFromFile(currentContext)
            rebuildInvertedIndex() // <-- ADDED: build index after load
            updateAutoComplete()
            addChatMessage(currentMascotName, "Добро пожаловать!")
        }

        queryInput.setOnItemClickListener { parent, _, position, _ ->
            val selected = parent.getItemAtPosition(position) as String
            queryInput.setText(selected)
            processUserQuery(selected)
        }

        // idle runnable
        idleCheckRunnable = object : Runnable {
            override fun run() {
                val idle = System.currentTimeMillis() - lastUserInputTime
                if (idle >= 25000) {
                    if (dialogs.isNotEmpty()) startRandomDialog() else if (dialogLines.isNotEmpty()) triggerRandomDialog()
                }
                dialogHandler.postDelayed(this, 5000)
            }
        }
        idleCheckRunnable?.let { dialogHandler.postDelayed(it, 5000) }

    // <-- CHANGED: hide actionbar big avatar (we keep side avatars)
        mascotTopImage?.visibility = View.GONE
    }

    override fun onResume() {
        super.onResume()
        folderUri?.let { loadTemplatesFromFile(currentContext) }
        rebuildInvertedIndex() // <-- ADDED: ensure index fresh on resume
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

    /// SECTION: Toolbar Helpers — Вспомогательные функции для тулбара (touch-эффекты, загрузка иконок) — Работают с UI-элементами и файлами из папки
    // --- toolbar helpers ---
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
            // top icons expected names
            tryLoad("lock.png", btnLock)
            tryLoad("trash.png", btnTrash)
            tryLoad("envelope.png", btnEnvelopeTop)
            tryLoad("settings.png", btnSettings)
            
        } catch (e: Exception) {
            e.printStackTrace()
            // Optionally show a toast on error
            // showCustomToast("Ошибка загрузки иконок")
        }
    }

/// SECTION: Core Chat Logic — Основная логика чата (processUserQuery, clearChat) — Обработка ввода, антиспам, смена контекста, dummy-ответы; использует templatesMap, keywordResponses
    // === core: process user query ===
    private fun processUserQuery(userInput: String) {
    // <-- ADDED: normalize input early (remove punctuation, collapse spaces)
    val qOrigRaw = userInput.trim()
    val qOrig = normalizeText(qOrigRaw)
    // <-- ADDED: filter stopwords and map synonyms producing tokens and joined string
    val (qTokensFiltered, qFiltered) = filterStopwordsAndMapSynonyms(qOrig)

    // store/use normalized keys in queryCountMap to count repeats robustly
    val qKeyForCount = qFiltered

    if (qFiltered.isEmpty()) return
    lastUserInputTime = System.currentTimeMillis()
    stopDialog()
    if (qKeyForCount == lastQuery) {
        val cnt = queryCountMap.getOrDefault(qKeyForCount, 0)
        queryCountMap[qKeyForCount] = cnt + 1
    } else {
        queryCountMap.clear()
        queryCountMap[qKeyForCount] = 1
        lastQuery = qKeyForCount
    }
    addChatMessage("Ты", userInput)
    
    // Показать уведомление "Печатает..." с рандомной задержкой
    showTypingIndicator()
    
    val repeats = queryCountMap.getOrDefault(qKeyForCount, 0)
    if (repeats >= 5) {
        val spamResp = antiSpamResponses.random()
        addChatMessage(currentMascotName, spamResp)
        startIdleTimer()
        return
    }
    var answered = false

    // ADDED: List to collect subquery responses for combining
    val subqueryResponses = mutableListOf<String>()
    val processedSubqueries = mutableSetOf<String>()

    // 1. Check for an exact match in the current context (templatesMap keys are normalized)
    templatesMap[qFiltered]?.let { possible ->
        if (possible.isNotEmpty()) {
            subqueryResponses.add(possible.random())
            answered = true
            processedSubqueries.add(qFiltered)
        }
    }

    // ADDED: 2. Try subqueries (individual tokens and two-token combinations)
    if (subqueryResponses.size < MAX_SUBQUERY_RESPONSES) {
        val tokens = if (qTokensFiltered.isNotEmpty()) qTokensFiltered else tokenize(qFiltered)
        // Try individual tokens
        for (token in tokens) {
            if (subqueryResponses.size >= MAX_SUBQUERY_RESPONSES) break
            if (token in processedSubqueries || token.length < 2) continue // Ignore short tokens
            templatesMap[token]?.let { possible ->
                if (possible.isNotEmpty()) {
                    subqueryResponses.add(possible.random())
                    processedSubqueries.add(token)
                }
            }
            if (subqueryResponses.size < MAX_SUBQUERY_RESPONSES) {
                keywordResponses[token]?.let { possible ->
                    if (possible.isNotEmpty()) {
                        subqueryResponses.add(possible.random())
                        processedSubqueries.add(token)
                    }
                }
            }
        }

        // Try two-token combinations
        if (subqueryResponses.size < MAX_SUBQUERY_RESPONSES && tokens.size > 1) {
            for (i in 0 until tokens.size - 1) {
                if (subqueryResponses.size >= MAX_SUBQUERY_RESPONSES) break
                val twoTokens = "${tokens[i]} ${tokens[i + 1]}"
                if (twoTokens in processedSubqueries) continue
                templatesMap[twoTokens]?.let { possible ->
                    if (possible.isNotEmpty()) {
                        subqueryResponses.add(possible.random())
                        processedSubqueries.add(twoTokens)
                    }
                }
            }
        }
    }

    // ADDED: 3. Combine subquery responses into a single message
    if (subqueryResponses.isNotEmpty()) {
        val combinedResponse = subqueryResponses.joinToString(". ")
        dialogHandler.postDelayed({
            addChatMessage(currentMascotName, combinedResponse)
        }, SUBQUERY_RESPONSE_DELAY)
        answered = true
    }

    // MODIFIED: 4. If no subquery responses, check for keywords in the current context
    if (!answered) {
        for ((keyword, responses) in keywordResponses) {
            // normalize keyword check
            if (qFiltered.contains(keyword) && responses.isNotEmpty()) {
                dialogHandler.postDelayed({
                    addChatMessage(currentMascotName, responses.random())
                }, SUBQUERY_RESPONSE_DELAY)
                answered = true
                break
            }
        }
    }
}

        // 2.5 FUZZY: Use inverted index to collect candidates and run Levenshtein only on them
        if (!answered) {
            // tokenize normalized query (we already have filtered tokens)
            val qTokens = if (qTokensFiltered.isNotEmpty()) qTokensFiltered else tokenize(qFiltered)
            val candidateCounts = HashMap<String, Int>()
            for (tok in qTokens) {
                invertedIndex[tok]?.forEach { trig ->
                    candidateCounts[trig] = candidateCounts.getOrDefault(trig, 0) + 1
                }
            }
            // Prefer candidates with more shared tokens
            val candidates = if (candidateCounts.isNotEmpty()) {
                candidateCounts.entries
                    .filter { it.value >= CANDIDATE_TOKEN_THRESHOLD }
                    .sortedByDescending { it.value }
                    .map { it.key }
                    .take(MAX_CANDIDATES_FOR_LEV)
            } else {
                val maxDist = getFuzzyDistance(qFiltered)
templatesMap.keys.filter { abs(it.length - qFiltered.length) <= maxDist }
    .take(MAX_CANDIDATES_FOR_LEV)

            }

            // <-- ADDED: Try Jaccard first on token-sets (stopwords removed / synonyms mapped)
            var bestByJaccard: String? = null
            var bestJaccard = 0.0
            val qSet = qTokens.toSet()
            for (key in candidates) {
                val keyTokens = filterStopwordsAndMapSynonyms(key).first.toSet()
                if (keyTokens.isEmpty()) continue
                val inter = qSet.intersect(keyTokens).size.toDouble()
                val union = qSet.union(keyTokens).size.toDouble()
                val j = if (union == 0.0) 0.0 else inter / union
                if (j > bestJaccard) {
                    bestJaccard = j
                    bestByJaccard = key
                }
            }
            if (bestByJaccard != null && bestJaccard >= JACCARD_THRESHOLD) {
                val possible = templatesMap[bestByJaccard]
                if (!possible.isNullOrEmpty()) {
                    addChatMessage(currentMascotName, possible.random())
                    answered = true
                }
            }

            // fallback to Levenshtein if Jaccard didn't decide
            if (!answered) {
                var bestKey: String? = null
                var bestDist = Int.MAX_VALUE
                for (key in candidates) {
                    val maxDist = getFuzzyDistance(qFiltered)
if (abs(key.length - qFiltered.length) > maxDist + 1) continue
val d = levenshtein(qFiltered, key, qFiltered)
if (d < bestDist) {
    bestDist = d
    bestKey = key
}

                    if (bestDist == 0) break
                }
                val maxDist = getFuzzyDistance(qFiltered)
if (bestKey != null && bestDist <= maxDist) {
    val possible = templatesMap[bestKey]
    if (!possible.isNullOrEmpty()) {
        addChatMessage(currentMascotName, possible.random())
        answered = true
    }
}

            }
        } // <-- FUZZY BLOCK ADDED

        // 3. If still no answer, try to switch context
        if (!answered) {
            detectContext(qFiltered)?.let { newContext ->
                if (newContext != currentContext) {
                    currentContext = newContext
                    loadTemplatesFromFile(currentContext)
                    rebuildInvertedIndex() // <-- ADDED: rebuild index after switching context
                    updateAutoComplete()
                    // Re-check for an answer in the new context after switching
                    templatesMap[qFiltered]?.let { possible ->
                        if (possible.isNotEmpty()) {
                            addChatMessage(currentMascotName, possible.random())
                            answered = true
                        }
                    }
                }
            }
        }
        // 4. If nothing worked, use a fallback response
        if (!answered) {
            val fallbackResp = getDummyResponse(qOrig)
            addChatMessage(currentMascotName, fallbackResp)
        }
        // Trigger idle events after processing the query
        triggerRandomDialog()
        startIdleTimer()
    }
    
    // Новый метод для показа полупрозрачного уведомления "Печатает..."
    private fun showTypingIndicator() {
        val typingView = TextView(this).apply {
            text = "печатает..."
            textSize = 14f
            setTextColor(getColor(android.R.color.white))
            setBackgroundColor(0x80000000.toInt()) // Полупрозрачный чёрный фон
            alpha = 0.7f // Дополнительная полупрозрачность
            setPadding(16, 8, 16, 8)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                setMargins(0, 16, 0, 0) // Отступ сверху для размещения вверху чата
            }
        }
        messagesContainer.addView(typingView, 0) // Добавляем в начало контейнера (вверху)
        
        // Рандомная задержка 1–3 секунды перед удалением
        val randomDelay = (1000..3000).random().toLong()
        Handler(Looper.getMainLooper()).postDelayed({
            messagesContainer.removeView(typingView)
        }, randomDelay)
    }
    
    private fun clearChat() {
        messagesContainer.removeAllViews()
        queryCountMap.clear()
        lastQuery = ""
        currentContext = "base.txt"
        loadTemplatesFromFile(currentContext)
        rebuildInvertedIndex() // <-- ADDED
        updateAutoComplete()
        addChatMessage(currentMascotName, "Чат очищен. Возвращаюсь к началу.")
    }
    private fun detectContext(input: String): String? {
        val lower = normalizeText(input) // <-- ADDED: normalize when detecting context
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

    // <-- ADDED: normalize text (remove punctuation, collapse spaces)
    private fun normalizeText(s: String): String {
        // keep letters, digits and spaces only
        val lower = s.lowercase(Locale.getDefault())
        val cleaned = lower.replace(Regex("[^\\p{L}\\p{Nd}\\s]"), " ")
        val collapsed = cleaned.replace(Regex("\\s+"), " ").trim()
        return collapsed
    }

    // <-- ADDED: tokenize (split normalized text to tokens)
    private fun tokenize(s: String): List<String> {
        if (s.isBlank()) return emptyList()
        return s.split(Regex("\\s+")).map { it.trim() }.filter { it.isNotEmpty() }
    }

    // <-- ADDED: load synonyms and stopwords from folder via SAF
    private fun loadSynonymsAndStopwords() {
        synonymsMap.clear()
        stopwords.clear()
        val uri = folderUri ?: return
        try {
            val dir = DocumentFile.fromTreeUri(this, uri) ?: return

            // synonims.txt (note: filename spelled as in your request)
            val synFile = dir.findFile("synonims.txt")
            if (synFile != null && synFile.exists()) {
                contentResolver.openInputStream(synFile.uri)?.bufferedReader()?.use { reader ->
                    reader.forEachLine { raw ->
                        var l = raw.trim()
                        if (l.isEmpty()) return@forEachLine
                        // allow lines wrapped in * or not
                        if (l.startsWith("*") && l.endsWith("*") && l.length > 1) {
                            l = l.substring(1, l.length - 1)
                        }
                        val parts = l.split(";").map { normalizeText(it).trim() }.filter { it.isNotEmpty() }
                        if (parts.isEmpty()) return@forEachLine
                        // choose last element as canonical
                        val canonical = parts.last()
                        for (p in parts) {
                            synonymsMap[p] = canonical
                        }
                    }
                }
            }

            // stopwords.txt with ^ separators: ^я^бы^хотел^узнать^ ...
            val stopFile = dir.findFile("stopwords.txt")
            if (stopFile != null && stopFile.exists()) {
                contentResolver.openInputStream(stopFile.uri)?.bufferedReader()?.use { reader ->
                    val all = reader.readText()
                    if (all.isNotEmpty()) {
                        val parts = all.split("^").map { normalizeText(it).trim() }.filter { it.isNotEmpty() }
                        for (p in parts) stopwords.add(p)
                    }
                }
            }
        } catch (e: Exception) {
            // ignore silently — allow app to continue without synonyms/stopwords
        }
    }

    // <-- ADDED: filter stopwords and map synonyms for an input string
    // returns Pair(listOfTokens, joinedNormalizedString)
    private fun filterStopwordsAndMapSynonyms(input: String): Pair<List<String>, String> {
        val toks = tokenize(input)
        val mapped = toks.map { tok ->
            val n = normalizeText(tok)
            val s = synonymsMap[n] ?: n
            s
        }.filter { it.isNotEmpty() && !stopwords.contains(it) }
        val joined = mapped.joinToString(" ")
        return Pair(mapped, joined)
    }

    // <-- ADDED: rebuild inverted index from current templatesMap (respect stopwords & synonyms)
    private fun rebuildInvertedIndex() {
        invertedIndex.clear()
        for (key in templatesMap.keys) {
            // key already normalized, further filter and map synonyms
            val toks = filterStopwordsAndMapSynonyms(key).first
            for (t in toks) {
                val list = invertedIndex.getOrPut(t) { mutableListOf() }
                // avoid duplicates
                if (!list.contains(key)) list.add(key)
            }
        }
    }

    // <-- ADDED: Levenshtein implementation (optimized with rolling rows and early exit)
private fun levenshtein(s: String, t: String, qFiltered: String): Int {
    // quick shortcuts
    if (s == t) return 0
    val n = s.length
    val m = t.length
    if (n == 0) return m
    if (m == 0) return n

    val maxDist = getFuzzyDistance(qFiltered)
    if (abs(n - m) > maxDist + 2) return Int.MAX_VALUE / 2

    // use two rolling rows to save memory
    val prev = IntArray(m + 1) { it }
    val curr = IntArray(m + 1)

    for (i in 1..n) {
        curr[0] = i
        var minInRow = curr[0]
        val si = s[i - 1]
        for (j in 1..m) {
            val cost = if (si == t[j - 1]) 0 else 1
            val deletion = prev[j] + 1
            val insertion = curr[j - 1] + 1
            val substitution = prev[j - 1] + cost
            curr[j] = min(min(deletion, insertion), substitution)
            if (curr[j] < minInRow) minInRow = curr[j]
        }

        val maxDistRow = getFuzzyDistance(qFiltered)
        if (minInRow > maxDistRow + 2) return Int.MAX_VALUE / 2

        for (k in 0..m) prev[k] = curr[k]
    }

    return prev[m]
}
// <-- END ADDED


    /// SECTION: UI Messages — Создание и добавление сообщений в чат (addChatMessage, createMessageBubble и утилиты) — UI-логика пузырей, аватаров, скролла; использует currentThemeColor
    // === UI: add message with avatar left for mascots, right-aligned for user ===
    private fun addChatMessage(sender: String, text: String) {
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
                listOf("${s}_icon.png", "${s}_avatar.png", "${s}.png", currentMascotIcon)
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

    /// SECTION: Template Loading — Загрузка шаблонов и метаданных (loadTemplatesFromFile, loadFallbackTemplates, updateAutoComplete) — Парсинг файлов из папки, карты шаблонов/контекстов, fallback; использует folderUri, contextMap
    // ========================== Загрузка шаблонов ==========================
    private fun loadTemplatesFromFile(filename: String) {
        templatesMap.clear()
        keywordResponses.clear()
        mascotList.clear()
        dialogLines.clear()
        dialogs.clear()

        // Reset to default before parsing, especially contextMap for base.txt
        if (filename == "base.txt") {
            contextMap.clear()
        }

        currentMascotName = "Racky"
        currentMascotIcon = "raccoon_icon.png"
        currentThemeColor = "#00FF00"
        currentThemeBackground = "#000000"

        // <-- ADDED: ensure synonyms/stopwords are loaded (in case folderUri was set later)
        loadSynonymsAndStopwords()

        if (folderUri == null) {
            loadFallbackTemplates()
            rebuildInvertedIndex() // <-- ADDED
            updateUI(
                currentMascotName,
                currentMascotIcon,
                currentThemeColor,
                currentThemeBackground
            )
            return
        }

        try {
            val dir = DocumentFile.fromTreeUri(this, folderUri!!) ?: run {
                loadFallbackTemplates()
                rebuildInvertedIndex() // <-- ADDED
                updateUI(
                    currentMascotName,
                    currentMascotIcon,
                    currentThemeColor,
                    currentThemeBackground
                )
                return
            }

            val file = dir.findFile(filename)
            if (file == null || !file.exists()) {
                loadFallbackTemplates()
                rebuildInvertedIndex() // <-- ADDED
                updateUI(
                    currentMascotName,
                    currentMascotIcon,
                    currentThemeColor,
                    currentThemeBackground
                )
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
                                if (keyword.isNotEmpty() && contextFile.isNotEmpty()) contextMap[keyword] =
                                    contextFile
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
                                val responses = parts[1].split("|")
                                val responseList =
                                    responses.mapNotNull { it.trim().takeIf { s -> s.isNotEmpty() } }
                                        .toMutableList()
                                if (keyword.isNotEmpty() && responseList.isNotEmpty()) keywordResponses[keyword] =
                                    responseList
                            }
                        }
                        return@forEachLine
                    }

                    if (!l.contains("=")) return@forEachLine
                    val parts = l.split("=", limit = 2)
                    if (parts.size == 2) {
                        // <-- ADDED: normalize trigger key (remove punctuation etc)
                        val triggerRaw = parts[0].trim()
                        val trigger = normalizeText(triggerRaw)
                        // <-- ADDED: additionally filter stopwords and map synonyms for trigger key when storing
                        val triggerFiltered = filterStopwordsAndMapSynonyms(trigger).second
                        val responses = parts[1].split("|")
                        val responseList =
                            responses.mapNotNull { it.trim().takeIf { s -> s.isNotEmpty() } }
                                .toMutableList()
                        if (triggerFiltered.isNotEmpty() && responseList.isNotEmpty()) templatesMap[triggerFiltered] =
                            responseList
                    }
                }
            }

            // metadata file
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
                            line.startsWith("mascot_name=") -> currentMascotName =
                                line.substring("mascot_name=".length).trim()
                            line.startsWith("mascot_icon=") -> currentMascotIcon =
                                line.substring("mascot_icon=".length).trim()
                            line.startsWith("theme_color=") -> currentThemeColor =
                                line.substring("theme_color=".length).trim()
                            line.startsWith("theme_background=") -> currentThemeBackground =
                                line.substring("theme_background=".length).trim()
                            line.startsWith("dialog_lines=") -> {
                                val lines =
                                    line.substring("dialog_lines=".length).split("|")
                                for (d in lines) {
                                    val t = d.trim(); if (t.isNotEmpty()) dialogLines.add(t)
                                }
                            }
                        }
                    }
                }
            }

            // ---- randomreply.txt parsing (robust)
            val dialogFile = dir.findFile("randomreply.txt")
            if (dialogFile != null && dialogFile.exists()) {
                try {
                    contentResolver.openInputStream(dialogFile.uri)?.bufferedReader()
                        ?.use { reader ->
                            var currentDialogParser: Dialog? = null
                            reader.forEachLine { raw ->
                                val l = raw.trim()
                                if (l.isEmpty()) return@forEachLine

                                if (l.startsWith(";")) {
                                    currentDialogParser?.takeIf { it.replies.isNotEmpty() }
                                        ?.let { dialogs.add(it) }
                                    currentDialogParser = Dialog(l.substring(1).trim())
                                    return@forEachLine
                                }

                                if (l.contains(">")) {
                                    val parts = l.split(">", limit = 2)
                                    if (parts.size == 2) {
                                        val mascot = parts[0].trim()
                                        val text = parts[1].trim()
                                        if (mascot.isNotEmpty() && text.isNotEmpty()) {
                                            val cur = currentDialogParser
                                                ?: Dialog("default").also { currentDialogParser = it }
                                            cur.replies.add(mapOf("mascot" to mascot, "text" to text))
                                        }
                                    }
                                    return@forEachLine
                                }

                                // fallback short dialog line
                                dialogLines.add(l)
                            }
                            currentDialogParser?.takeIf { it.replies.isNotEmpty() }
                                ?.let { dialogs.add(it) }
                        }
                } catch (e: Exception) {
                    showCustomToast("Ошибка чтения randomreply.txt: ${e.message}")
                }
            }

            // choose random mascot for base
            if (filename == "base.txt" && mascotList.isNotEmpty()) {
                val selected = mascotList.random()
                selected["name"]?.let { currentMascotName = it }
                selected["icon"]?.let { currentMascotIcon = it }
                selected["color"]?.let { currentThemeColor = it }
                selected["background"]?.let { currentThemeBackground = it }
            }

            // <-- ADDED: build inverted index once templatesMap filled
            rebuildInvertedIndex()

            updateUI(
                currentMascotName,
                currentMascotIcon,
                currentThemeColor,
                currentThemeBackground
            )

        } catch (e: Exception) {
            e.printStackTrace()
            showCustomToast("Ошибка чтения файла: ${e.message}")
            loadFallbackTemplates()
            rebuildInvertedIndex() // <-- ADDED
            updateUI(
                currentMascotName,
                currentMascotIcon,
                currentThemeColor,
                currentThemeBackground
            )
        }
    }

    private fun loadFallbackTemplates() {
        templatesMap.clear()
        contextMap.clear()
        keywordResponses.clear()
        dialogs.clear()
        dialogLines.clear()
        mascotList.clear()
        // <-- ADDED: store normalized keys in fallback as well (respect stopwords/synonyms)
        val t1 = filterStopwordsAndMapSynonyms(normalizeText("привет")).second
        templatesMap[t1] = mutableListOf("Привет! Чем могу помочь?", "Здравствуй!")
        val t2 = filterStopwordsAndMapSynonyms(normalizeText("как дела")).second
        templatesMap[t2] = mutableListOf("Всё отлично, а у тебя?", "Нормально, как дела?")
        keywordResponses["спасибо"] = mutableListOf("Рад, что помог!", "Всегда пожалуйста!")
    }

    private fun updateAutoComplete() {
        val suggestions = mutableListOf<String>()
        suggestions.addAll(templatesMap.keys) // keys are normalized triggers
        for (s in fallback) {
            val low = s.lowercase(Locale.ROOT)
            if (!suggestions.contains(low)) suggestions.add(low)
        }
        if (adapter == null) {
            // <-- CHANGED: make adapter produce white text for dropdown items
            adapter = object : ArrayAdapter<String>(this, android.R.layout.simple_dropdown_item_1line, suggestions) {
                override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                    val v = super.getView(position, convertView, parent) as TextView
                    v.setTextColor(Color.WHITE)
                    return v
                }
            }
            queryInput.setAdapter(adapter)
            queryInput.threshold = 1
            // set dropdown background to semi-transparent dark so white text is readable
            queryInput.setDropDownBackgroundDrawable(ColorDrawable(Color.parseColor("#80000000")))
        } else {
            adapter?.clear()
            adapter?.addAll(suggestions)
            adapter?.notifyDataSetChanged()
        }
    }

    /// SECTION: Idle & Dialogs — Автономные диалоги и idle-логика (triggerRandomDialog, startRandomDialog, stopDialog, loadMascotMetadata, updateUI) — Рандомные реплики, таймеры, смена маскота; использует dialogHandler, mascotList
    // ======= Idle & random dialogs =======
    private fun triggerRandomDialog() {
        if (dialogLines.isNotEmpty() && random.nextDouble() < 0.3) {
            dialogHandler.postDelayed({
                if (dialogLines.isEmpty()) return@postDelayed
                val dialog = dialogLines.random()
                if (mascotList.isNotEmpty()) {
                    val rnd = mascotList.random()
                    val rndName = rnd["name"] ?: currentMascotName
                    loadMascotMetadata(rndName)
                    addChatMessage(rndName, dialog)
                } else addChatMessage(currentMascotName, dialog)
            }, 1500)
        }
        if (mascotList.isNotEmpty() && random.nextDouble() < 0.1) {
            dialogHandler.postDelayed({
                val rnd = mascotList.random()
                val rndName = rnd["name"] ?: currentMascotName
                loadMascotMetadata(rndName)
                addChatMessage(rndName, "Эй, мы не закончили!")
            }, 2500)
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
                    dialogHandler.postDelayed(this, (random.nextInt(15000) + 10000).toLong())
                } else {
                    dialogHandler.postDelayed(
                        { startRandomDialog() },
                        (random.nextInt(20000) + 5000).toLong()
                    )
                }
            }
        }
        dialogRunnable?.let {
            dialogHandler.postDelayed(
                it,
                (random.nextInt(15000) + 10000).toLong()
            )
        }
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
                        val line = raw.trim()
                        when {
                            line.startsWith("mascot_name=") -> currentMascotName =
                                line.substring("mascot_name=".length).trim()
                            line.startsWith("mascot_icon=") -> currentMascotIcon =
                                line.substring("mascot_icon=".length).trim()
                            line.startsWith("theme_color=") -> currentThemeColor =
                                line.substring("theme_color=".length).trim()
                            line.startsWith("theme_background=") -> currentThemeBackground =
                                line.substring("theme_background=".length).trim()
                        }
                    }
                    updateUI(
                        currentMascotName,
                        currentMascotIcon,
                        currentThemeColor,
                        currentThemeBackground
                    )
                }
            } catch (e: Exception) {
                showCustomToast("Ошибка загрузки метаданных маскота: ${e.message}")
            }
        }
    }

    private fun updateUI(
        mascotName: String,
        mascotIcon: String,
        themeColor: String,
        themeBackground: String
    ) {
        title = "Pawstribe - $mascotName"
        // <-- CHANGED: we hide actionbar mascotTopImage (no big avatar)
        mascotTopImage?.visibility = View.GONE

        try {
            messagesContainer.setBackgroundColor(Color.parseColor(themeBackground))
        } catch (_: Exception) {
        }
    }

    /// SECTION: Utils — Утилиты (showCustomToast, startIdleTimer) — Toast, таймеры idle; простые хелперы для UI и состояний
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
            dialogHandler.removeCallbacks(it); dialogHandler.postDelayed(
                it,
                5000
            )
        }
    }
}
