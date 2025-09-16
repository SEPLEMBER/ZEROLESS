package com.nemesis.droidcrypt

import android.os.Handler
import android.os.Looper
import java.lang.ref.WeakReference
import java.util.*
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.abs

/**
 * Логика вынесена в отдельный файл.
 * - Не держит сильных ссылок на Activity/UI (WeakReference).
 * - Выполняет тяжёлую работу в bgExecutor (single thread).
 * - Постит UI-операции через mainHandler.
 */
class Logic(
    private val mainHandler: Handler = Handler(Looper.getMainLooper())
) {

    interface UIBridge {
        fun addChatMessage(sender: String, text: String)
        fun showTypingIndicator(durationMs: Long, colorHex: String)
        fun playNotifySound()
        fun updateAutoCompleteSuggestions(suggestions: List<String>)
        fun loadTemplatesFromFileRequest(filename: String)
    }

    // weak ref to UI to avoid leaks
    private var uiBridgeRef: WeakReference<UIBridge>? = null
    fun setUIBridge(bridge: UIBridge?) {
        uiBridgeRef = if (bridge == null) null else WeakReference(bridge)
    }

    private fun uiBridge(): UIBridge? = uiBridgeRef?.get()

    // single-threaded executor for background work (search/indexing)
    private val bgExecutor: ExecutorService = Executors.newSingleThreadExecutor()

    // ---- Data (synchronized access) ----
    private val templatesMap = HashMap<String, MutableList<String>>()
    private val keywordResponses = HashMap<String, MutableList<String>>()
    private val contextMap = HashMap<String, String>()
    private val dialogLines = mutableListOf<String>()
    private val dialogs = mutableListOf<Dialog>()
    private val mascotList = mutableListOf<Map<String, String>>()
    private val invertedIndex = HashMap<String, MutableList<String>>()
    private val synonymsMap = HashMap<String, String>()
    private val stopwords = HashSet<String>()
    private val antiSpamResponses = mutableListOf<String>()

    // state
    @Volatile var currentMascotName = "Racky"; private set
    @Volatile var currentMascotIcon = "raccoon_icon.png"; private set
    @Volatile var currentThemeColor = "#00FF00"; private set
    @Volatile var currentThemeBackground = "#000000"; private set
    @Volatile var currentContext = "base.txt"; private set

    // idle/dialogs
    private val handler = mainHandler
    private var dialogRunnable: Runnable? = null
    private var idleChecker: Runnable? = null
    private var lastUserInputTime = System.currentTimeMillis()
    private val queryCountMap = HashMap<String, Int>()
    private var lastQuery = ""
    private var currentDialog: Dialog? = null
    private var currentDialogIndex = 0

    private data class Dialog(val name: String, val replies: MutableList<Map<String, String>> = mutableListOf())

    private val random = Random()

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
                "Не спамь, пожалуйста, задай новый запрос!",
                "Пять раз одно и то же? Попробуй что-то другое.",
                "Я уже ответил, давай новый запрос!"
            )
        )
    }

    // ---- Thread-safe getters for UI ----
    @Synchronized fun getSynonyms(): Map<String, String> = HashMap(synonymsMap)
    @Synchronized fun getStopwords(): Set<String> = HashSet(stopwords)

    // ---- Methods for UI to populate data (called from UI thread) ----
    @Synchronized fun clearTemplatesAndState() {
        templatesMap.clear()
        keywordResponses.clear()
        contextMap.clear()
        dialogs.clear()
        dialogLines.clear()
        mascotList.clear()
        invertedIndex.clear()
        synonymsMap.clear()
        stopwords.clear()
    }

    @Synchronized fun addTemplate(trigger: String, responses: List<String>) {
        val t = trigger.trim().lowercase(Locale.getDefault())
        if (t.isEmpty() || responses.isEmpty()) return
        templatesMap.getOrPut(t) { mutableListOf() }.addAll(responses)
    }

    @Synchronized fun addKeywordResponse(keyword: String, responses: List<String>) {
        val k = keyword.trim().lowercase(Locale.getDefault())
        if (k.isEmpty() || responses.isEmpty()) return
        keywordResponses.getOrPut(k) { mutableListOf() }.addAll(responses)
    }

    @Synchronized fun addContextMapping(key: String, value: String) {
        if (key.isBlank()) return
        contextMap[key.trim().lowercase(Locale.getDefault())] = value.trim()
    }

    @Synchronized fun addDialogLine(line: String) {
        if (line.isNotBlank()) dialogLines.add(line.trim())
    }

    @Synchronized fun addDialog(name: String, replies: List<Pair<String, String>>) {
        val d = Dialog(name)
        replies.forEach { (mascot, text) -> d.replies.add(mapOf("mascot" to mascot, "text" to text)) }
        if (d.replies.isNotEmpty()) dialogs.add(d)
    }

    @Synchronized fun addMascotEntry(name: String, icon: String, color: String, background: String) {
        if (name.isBlank()) return
        mascotList.add(mapOf("name" to name, "icon" to icon, "color" to color, "background" to background))
    }

    @Synchronized fun setMascotFromMetadata(name: String?, icon: String?, color: String?, background: String?) {
        name?.let { if (it.isNotBlank()) currentMascotName = it }
        icon?.let { if (it.isNotBlank()) currentMascotIcon = it }
        color?.let { if (it.isNotBlank()) currentThemeColor = it }
        background?.let { if (it.isNotBlank()) currentThemeBackground = it }
    }

    @Synchronized fun setSynonyms(map: Map<String, String>) {
        synonymsMap.clear()
        synonymsMap.putAll(map)
    }

    @Synchronized fun setStopwords(set: Set<String>) {
        stopwords.clear()
        stopwords.addAll(set)
    }

    /**
     * Rebuild inverted index in bg thread and notify UI when ready.
     */
    fun rebuildInvertedIndexAsync() {
        bgExecutor.submit {
            synchronized(this) {
                invertedIndex.clear()
                for (key in templatesMap.keys) {
                    val toks = filterStopwordsAndMapSynonymsLocked(key).first
                    for (t in toks) {
                        invertedIndex.getOrPut(t) { mutableListOf() }.apply { if (!contains(key)) add(key) }
                    }
                }
            }
            // prepare suggestions snapshot
            val suggestions: List<String> = synchronized(this) {
                (templatesMap.keys + listOf("Привет", "Как дела?", "Расскажи о себе", "Выход")).toList()
            }
            mainHandler.post { uiBridge()?.updateAutoCompleteSuggestions(suggestions) }
        }
    }

    /**
     * Вход: UI вызывает processUserQuery (обычно из main thread).
     * Мы делаем: постим user message немедленно, запускаем bg compute, показываем индикатор печати и по завершении постим ответ.
     */
    fun processUserQuery(userInput: String) {
        val qOrigRaw = userInput.trim()
        val qOrig = normalizeText(qOrigRaw)
        val (qTokensFiltered, qFiltered) = synchronized(this) { filterStopwordsAndMapSynonymsLocked(qOrig) }

        if (qFiltered.isEmpty()) return

        lastUserInputTime = System.currentTimeMillis()
        stopDialog()

        synchronized(this) {
            if (qFiltered == lastQuery) {
                val cnt = queryCountMap.getOrDefault(qFiltered, 0) + 1
                queryCountMap[qFiltered] = cnt
            } else {
                queryCountMap.clear()
                queryCountMap[qFiltered] = 1
                lastQuery = qFiltered
            }
        }

        // add user message immediately on UI
        mainHandler.post { uiBridge()?.addChatMessage("Ты", userInput) }

        val repeats = synchronized(this) { queryCountMap.getOrDefault(qFiltered, 0) }
        if (repeats >= 5) {
            val spamResp = antiSpamResponses.random()
            mainHandler.postDelayed({
                uiBridge()?.addChatMessage(currentMascotName, spamResp)
                uiBridge()?.playNotifySound()
            }, 1200)
            startIdleTimer()
            return
        }

        // show typing indicator quickly (UI on main)
        val afterTypingDelay = (1500L + random.nextInt(3500))
        mainHandler.post { uiBridge()?.showTypingIndicator(afterTypingDelay + 600L, "#FFA500") }

        // compute response in bg
        bgExecutor.submit {
            val response = computeResponseLocked(qFiltered, qOrig, qTokensFiltered)
            // post result to UI
            mainHandler.postDelayed({
                uiBridge()?.addChatMessage(currentMascotName, response)
                uiBridge()?.playNotifySound()
            }, afterTypingDelay + 700L)
        }

        triggerRandomDialog()
        startIdleTimer()
    }

    // computeResponse must not touch UI directly; return String. Access shared data inside synchronized blocks.
    private fun computeResponseLocked(qFiltered: String, qOrig: String, qTokensFiltered: List<String>): String {
        try {
            synchronized(this) {
                templatesMap[qFiltered]?.randomOrNull()?.let { return it }
                for ((keyword, responses) in keywordResponses) {
                    if (qFiltered.contains(keyword)) {
                        responses.randomOrNull()?.let { return it }
                    }
                }
            }

            val qTokens = if (qTokensFiltered.isNotEmpty()) qTokensFiltered else tokenize(qFiltered)
            val candidates = findBestCandidatesLocked(qTokens, qFiltered)

            val qSet = qTokens.toSet()
            var bestByJaccard: String? = null
            var bestJaccard = 0.0
            for (key in candidates) {
                val keyTokens = synchronized(this) { filterStopwordsAndMapSynonymsLocked(key).first.toSet() }
                if (keyTokens.isEmpty()) continue
                val inter = qSet.intersect(keyTokens).size.toDouble()
                val union = qSet.union(keyTokens).size.toDouble()
                val j = if (union > 0) inter / union else 0.0
                if (j > bestJaccard) {
                    bestJaccard = j
                    bestByJaccard = key
                }
            }
            if (bestByJaccard != null && bestJaccard >= JACCARD_THRESHOLD) {
                synchronized(this) { templatesMap[bestByJaccard]?.randomOrNull()?.let { return it } }
            }

            var bestKey: String? = null
            var bestDist = MAX_FUZZY_DISTANCE + 1
            for (key in candidates) {
                if (abs(key.length - qFiltered.length) > MAX_FUZZY_DISTANCE) continue
                val d = levenshtein(qFiltered, key)
                if (d < bestDist) {
                    bestDist = d
                    bestKey = key
                }
                if (bestDist == 0) break
            }
            if (bestKey != null && bestDist <= MAX_FUZZY_DISTANCE) {
                synchronized(this) { templatesMap[bestKey]?.randomOrNull()?.let { return it } }
            }

            val ctx = synchronized(this) { detectContextLocked(qFiltered) }
            ctx?.let { newContext ->
                if (newContext != currentContext) {
                    currentContext = newContext
                    mainHandler.post { uiBridge()?.loadTemplatesFromFileRequest(newContext) }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        return getDummyResponse(qOrig)
    }

    // ---- helper locked versions ----
    private fun findBestCandidatesLocked(qTokens: List<String>, qFiltered: String): List<String> {
        val candidateCounts = HashMap<String, Int>()
        synchronized(this) {
            qTokens.forEach { tok ->
                invertedIndex[tok]?.forEach { trig ->
                    candidateCounts[trig] = candidateCounts.getOrDefault(trig, 0) + 1
                }
            }
            return if (candidateCounts.isNotEmpty()) {
                candidateCounts.entries
                    .filter { it.value >= CANDIDATE_TOKEN_THRESHOLD }
                    .sortedByDescending { it.value }
                    .map { it.key }
                    .take(MAX_CANDIDATES_FOR_LEV)
            } else {
