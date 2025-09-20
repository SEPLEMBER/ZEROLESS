package com.nemesis.droidcrypt

import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import android.util.Log
import androidx.documentfile.provider.DocumentFile
import java.util.ArrayDeque
import java.util.Locale
import java.util.regex.Pattern

private const val TAG = "MemoryManager"

/**
 * Простая реализация менеджера памяти на шаблонах (без сторонних библиотек).
 * - Загружает шаблоны из папки (vospominania.txt, zapominanie.txt, ncorrect.txt)
 * - Хранит recentMessages (дека) и извлечённые MemoryEntry
 * - Сохраняет простые слоты (name, age, petname и т.д.) в SharedPreferences
 * - Обрабатывает входящие сообщения: пытается сопоставить с zapominanie -> сохраняет слот;
 *   иначе сопоставляет с vospominania -> создаёт память (event/state/fact)
 * - Отвечает на запросы типа "о чём мы говорили" через recallRecentConversation()
 *
 * Замечание: это MVP, использует простую замену "я->ты" при воспоминании. Для корректных
 * падежей/рода нужна морфология — это за пределами MVP.
 */

object MemoryManager {

    // SharedPreferences
    private const val PREFS_NAME = "pawscribe_memory"

    // Файлы-шаблоны
    private const val FILE_VOSPOMINANIA = "vospominania.txt"
    private const val FILE_ZAPOMINANIE = "zapominanie.txt"
    private const val FILE_NCORRECT = "ncorrect.txt"

    // Ограничения
    private const val RECENT_MESSAGES_LIMIT = 10
    private const val MEMORIES_LIMIT = 200

    data class UserMessage(val text: String, val normalized: String, val ts: Long = System.currentTimeMillis())

    data class MemoryEntry(
        val type: String, // "state", "event", "fact"
        val predicate: String?, // краткое описание (напр. "грустн", "женился")
        val obj: String?, // дополнение/объект (напр. "брат")
        val rawText: String,
        val mood: String? = null, // "sad"/"neutral"/"happy"
        val ts: Long = System.currentTimeMillis(),
        var confidence: Double = 1.0
    )

    data class Template(
        val raw: String,
        val regex: Regex,
        val placeholders: List<String>,
        val responseTemplate: String? = null, // если шаблон содержит ответ
        val targetSlot: String? = null // для zapominanie: ключ SharedPref
    )

    private lateinit var prefs: SharedPreferences

    // runtime storage
    private val recentMessages: ArrayDeque<UserMessage> = ArrayDeque()
    private val memories: ArrayDeque<MemoryEntry> = ArrayDeque()

    // loaded templates
    private val vospominaniaTemplates: MutableList<Template> = mutableListOf()
    private val zapominanieTemplates: MutableList<Template> = mutableListOf()

    // corrections: неправильное -> правильное (из ncorrect.txt)
    private val corrections: MutableMap<String, String> = mutableMapOf()

    // простые списки чувств для эвристики
    private val sadWords = setOf("грустн", "грустит", "плохо", "тоску", "одинок", "печаль")
    private val happyWords = setOf("счастл", "рад", "весел", "восторг", "счастье", "радост")

    // слот-плейсхолдеры, которые мы поддерживаем по умолчанию
    private val knownSlots = setOf("name", "age", "petname")

    // Инициализация менеджера памяти
    fun init(context: Context) {
        prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    // Загрузить шаблоны из директории (DocumentFile tree)
    fun loadTemplatesFromFolder(context: Context, folderUri: Uri?) {
        vospominaniaTemplates.clear()
        zapominanieTemplates.clear()
        corrections.clear()
        val uri = folderUri ?: return
        try {
            val dir = DocumentFile.fromTreeUri(context, uri) ?: return

            dir.findFile(FILE_NCORRECT)?.takeIf { it.exists() }?.let { file ->
                context.contentResolver.openInputStream(file.uri)?.bufferedReader()?.useLines { lines ->
                    lines.forEach { raw ->
                        val l = raw.trim()
                        if (l.isEmpty()) return@forEach
                        // формат: incorrect=correct
                        val parts = l.split("=", limit = 2).map { it.trim() }
                        if (parts.size == 2) {
                            corrections[parts[0].lowercase(Locale.getDefault())] = parts[1]
                        }
                    }
                }
            }

            dir.findFile(FILE_ZAPOMINANIE)?.takeIf { it.exists() }?.let { file ->
                context.contentResolver.openInputStream(file.uri)?.bufferedReader()?.useLines { lines ->
                    lines.forEach { raw ->
                        val l = raw.trim()
                        if (l.isEmpty()) return@forEach
                        try {
                            // формат: шаблон=ответ (для запоминания слота) или просто шаблон
                            val parts = l.split("=", limit = 2).map { it.trim() }
                            val pattern = parts[0]
                            val response = parts.getOrNull(1)
                            val template = buildTemplateFromPattern(pattern, response, isSlot = true)
                            zapominanieTemplates.add(template)
                        } catch (e: Exception) {
                            Log.e(TAG, "Failed parse zapominanie line: $l", e)
                        }
                    }
                }
            }

            dir.findFile(FILE_VOSPOMINANIA)?.takeIf { it.exists() }?.let { file ->
                context.contentResolver.openInputStream(file.uri)?.bufferedReader()?.useLines { lines ->
                    lines.forEach { raw ->
                        val l = raw.trim()
                        if (l.isEmpty()) return@forEach
                        try {
                            val parts = l.split("=", limit = 2).map { it.trim() }
                            val pattern = parts[0]
                            val response = parts.getOrNull(1)
                            val template = buildTemplateFromPattern(pattern, response, isSlot = false)
                            vospominaniaTemplates.add(template)
                        } catch (e: Exception) {
                            Log.e(TAG, "Failed parse vospominania line: $l", e)
                        }
                    }
                }
            }

            Log.d(TAG, "Loaded templates: vosp=${vospominaniaTemplates.size}, zapom=${zapominanieTemplates.size}, corrections=${corrections.size}")

        } catch (e: Exception) {
            Log.e(TAG, "Error loading templates from folder", e)
        }
    }

    // Создаёт Template из строкового шаблона: поддерживает <name>, <age>, <petname>, <any>
    // ВАЖНО: использует обычные численные группы (без именованных), чтобы работать корректно с Kotlin Regex
    private fun buildTemplateFromPattern(pattern: String, response: String?, isSlot: Boolean): Template {
        // Найдём плейсхолдеры
        val placeholderRegex = Regex("<([a-zA-Z0-9_]+)>")
        val placeholders = placeholderRegex.findAll(pattern).map { it.groupValues[1] }.toList()

        // Построим regex, где каждый плейсхолдер заменяется на захватывающую группу (.*?) — ленивую
        val sb = StringBuilder()
        var last = 0
        for (m in placeholderRegex.findAll(pattern)) {
            val start = m.range.first
            val end = m.range.last
            // добавить предыдущее текстовое содержимое (экранированное)
            sb.append(Pattern.quote(pattern.substring(last, start)))
            // заменяем на ленивую захватывающую группу (негребущую)
            sb.append("(.+?)")
            last = end + 1
        }
        if (last < pattern.length) sb.append(Pattern.quote(pattern.substring(last)))

        // Разрешаем возможные пробелы и пунктуацию в начале/конце
val finalRegex = ".*" + sb.toString() + ".*"
val kotlinRegex = Regex(finalRegex, RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)

        // Если это слот, попробуем извлечь ключ из шаблона (например "моего питомца зовут <name>") -> targetSlot=name
        val targetSlot = if (isSlot && placeholders.size == 1 && placeholders[0] in knownSlots) placeholders[0] else null

        return Template(raw = pattern, regex = kotlinRegex, placeholders = placeholders, responseTemplate = response, targetSlot = targetSlot)
    }

    // Применить исправления из ncorrect.txt на нормализованную строку
    private fun applyCorrections(s: String): String {
        if (corrections.isEmpty()) return s
        var res = s
        // Простая замена по границам слов
        for ((bad, good) in corrections) {
            try {
                res = res.replace(Regex("\\b" + Regex.escape(bad) + "\\b", RegexOption.IGNORE_CASE), good)
            } catch (_: Exception) {
            }
        }
        return res
    }

    // Вспомогательная функция: получить значение захваченной группы по имени плейсхолдера (placeholders - порядок плейсхолдеров)
    private fun getCapturedValue(match: MatchResult, placeholders: List<String>, name: String): String? {
        val idx = placeholders.indexOf(name)
        if (idx >= 0) {
            // группы нумеруются с 1
            return match.groupValues.getOrNull(idx + 1)?.takeIf { it.isNotBlank() }
        }
        // fallback: если нет такого плейсхолдера — вернём первую группу
        return match.groupValues.getOrNull(1)?.takeIf { it.isNotBlank() }
    }

    // Рендер ответа, подставляя захваченные группы (по имени плейсхолдера) и при необходимости
    // выполняя минимальные преобразования (например, <name> -> значение слота)
    private fun renderResponseWithPlaceholders(template: String, match: MatchResult, placeholders: List<String>): String {
        var out = template
        val placeholderRegex = Regex("<([a-zA-Z0-9_]+)>")
        // Найдём все плейсхолдеры в шаблоне и подставим значение
        placeholderRegex.findAll(template).forEach { m ->
            val name = m.groupValues[1]
            val valFromMatch = getCapturedValue(match, placeholders, name)
            val replacement = when {
                !valFromMatch.isNullOrBlank() -> valFromMatch
                knownSlots.contains(name) -> readSlot(name) ?: ""
                else -> ""
            }
            out = out.replace("<${name}>", replacement)
        }
        return out
    }

    // Добавить входящее сообщение и попытаться извлечь память / слоты
    fun processIncoming(context: Context, text: String): String? {
        val normalized = Engine.normalizeText(text)
        val corrected = applyCorrections(normalized)
        addRecentMessage(text, corrected)

        // 1) попытка сопоставления с zapominanie (сохранение слотов)
        for (tpl in zapominanieTemplates) {
            val m = tpl.regex.find(corrected)
            if (m != null) {
                // если шаблон предназначен для слота
                if (tpl.targetSlot != null) {
                    val slotValue = getCapturedValue(m, tpl.placeholders, tpl.targetSlot)
                    if (!slotValue.isNullOrBlank()) {
                        saveSlot(tpl.targetSlot, slotValue)
                        // сформируем ответ: если в шаблоне указан responseTemplate -- используем
                        val resp = tpl.responseTemplate?.let { renderResponseWithPlaceholders(it, m, tpl.placeholders) }
                            ?: "Запомнил."
                        return resp
                    }
                } else {
                    // общий zapominanie: берем первую группу и, если шаблон содержит имя — сохраним
                    val val0 = m.groupValues.getOrNull(1)
                    if (!val0.isNullOrBlank()) {
                        if (tpl.placeholders.contains("name")) {
                            saveSlot("name", val0)
                            val resp = tpl.responseTemplate?.let { renderResponseWithPlaceholders(it, m, tpl.placeholders) } ?: "Запомню это."
                            return resp
                        } else {
                            // Если шаблон не явно слот, но есть ответ — отдадим ответ
                            val resp = tpl.responseTemplate?.let { renderResponseWithPlaceholders(it, m, tpl.placeholders) }
                            if (!resp.isNullOrBlank()) return resp
                        }
                    }
                }
            }
        }

        // 2) попытка сопоставления с vospominania (создание памяти)
        for (tpl in vospominaniaTemplates) {
            val m = tpl.regex.find(corrected)
            if (m != null) {
                // постараемся классифицировать как state/event/fact
                val placeholders = tpl.placeholders
                val mainCaptured = if (placeholders.isNotEmpty()) getCapturedValue(m, placeholders, placeholders[0]) else m.groupValues.getOrNull(1)
                val obj = if (placeholders.size > 1) getCapturedValue(m, placeholders, placeholders[1]) else null

                val entry = MemoryEntry(
                    type = "event",
                    predicate = mainCaptured?.takeIf { it.isNotBlank() } ?: tpl.raw,
                    obj = obj,
                    rawText = text,
                    mood = detectMood(corrected)
                )
                pushMemory(entry)
                val resp = tpl.responseTemplate?.let { renderResponseWithPlaceholders(it, m, tpl.placeholders) } ?: "Понял, запомню."
                return resp
            }
        }

        // 3) эвристическое извлечение эмоций/событий (если шаблоны не сработали)
        val detected = heuristicExtract(corrected)
        if (detected != null) {
            pushMemory(detected)
            return "Понял."
        }

        return null
    }

    private fun addRecentMessage(original: String, normalized: String) {
        recentMessages.addFirst(UserMessage(original, normalized))
        while (recentMessages.size > RECENT_MESSAGES_LIMIT) recentMessages.removeLast()
    }

    private fun pushMemory(entry: MemoryEntry) {
        memories.addFirst(entry)
        while (memories.size > MEMORIES_LIMIT) memories.removeLast()
    }

    private fun saveSlot(slot: String, value: String) {
        if (!this::prefs.isInitialized) return
        prefs.edit().putString(slot, value).apply()
        Log.d(TAG, "Saved slot: $slot = $value")
    }

    fun readSlot(slot: String): String? {
        if (!this::prefs.isInitialized) return null
        return prefs.getString(slot, null)
    }

    // Очень простая эвристика для извлечения эмоций/событий
    private fun heuristicExtract(corrected: String): MemoryEntry? {
        val toks = Engine.tokenizeStatic(corrected)
        // ищем маркеры первого лица
        val firstPerson = toks.any { it == "я" || it == "мне" || it == "мой" || it == "моя" }
        if (!firstPerson) return null

        // найдем позитив/негатив
        val hasSad = toks.any { t -> sadWords.any { t.contains(it) } }
        val hasHappy = toks.any { t -> happyWords.any { t.contains(it) } }

        if (hasSad || hasHappy) {
            val mood = if (hasHappy) "happy" else "sad"
            return MemoryEntry(type = "state", predicate = if (hasHappy) "счастлив" else "грустн", obj = null, rawText = corrected, mood = mood)
        }

        // событие: ищем глаголы-перемены (простая эвристика: слова с "ся"/"лся"/"ил" итд)
        val event = toks.find { it.endsWith("ся") || it.endsWith("лся") || it.endsWith("илась") || it.endsWith("ил") || it.endsWith("ился") }
        if (event != null) {
            return MemoryEntry(type = "event", predicate = event, obj = null, rawText = corrected, mood = null)
        }

        return null
    }

    private fun detectMood(corrected: String): String? {
        val toks = Engine.tokenizeStatic(corrected)
        return when {
            toks.any { t -> happyWords.any { t.contains(it) } } -> "happy"
            toks.any { t -> sadWords.any { t.contains(it) } } -> "sad"
            else -> null
        }
    }

    // Определение намерения "вспомнить"
    fun isRecallIntent(input: String): Boolean {
        val norm = Engine.normalizeText(input)
        return listOf("о чем мы говорили", "о чем говорили", "что мы обсуждали", "что мы говорили", "что мы обсуждали")
            .any { norm.contains(Engine.normalizeText(it)) }
    }

    // Вернуть наиболее релевантную память для ответа на "о чём мы говорили"
    fun recallRecentConversation(): String? {
        // ищем первую память типа state/event/fact
        val mem = memories.firstOrNull { it.type in setOf("state", "event", "fact") }
        if (mem != null) return renderMemoryAsReply(mem)
        // fallback: посмотрим последние сообщения и попытаемся вернуть последнее первое лицо
        val fromRecent = recentMessages.firstOrNull { it.normalized.contains("я ") || it.normalized.startsWith("я") }
        if (fromRecent != null) {
            return transformFirstPersonToYou(fromRecent.text)
        }
        return null
    }

    // Простейшая трансформация "я ..." -> "тебе ..." / "ты ..."
    private fun transformFirstPersonToYou(s: String): String {
        var res = s
        // Набор базовых замен (используем корректные регулярные границы слова "\\b")
        val subs = listOf(
            Pair("\\bя\\b", "ты"),
            Pair("\\bмне\\b", "тебе"),
            Pair("\\bменя\\b", "тебя"),
            Pair("\\bмой\\b", "твой"),
            Pair("\\bмоя\\b", "твоя"),
            Pair("\\bмоё\\b", "твоё"),
            Pair("\\bмои\\b", "твои")
        )
        for ((from, to) in subs) {
            try {
                res = res.replace(Regex(from, RegexOption.IGNORE_CASE), to)
            } catch (_: Exception) {
            }
        }
        // Мелкие коррекции: если предложение начинается с "ты " -> лучше: "Тебе было..."
        return res
    }

    // Рендер памяти в ответ, бот обращается на "ты"
    private fun renderMemoryAsReply(mem: MemoryEntry): String {
        // Если у нас есть структурированное поле predicate/obj — попытаемся сформировать понятную фразу
        mem.predicate?.let { pred ->
            when (mem.type) {
                "state" -> {
                    // "Тебе было грустно" — нейтрально
                    val humanPred = pred
                    return "Тебе было $humanPred."
                }
                "event" -> {
                    val objPart = if (!mem.obj.isNullOrBlank()) " ${mem.obj}" else ""
                    return "Произошло: ${mem.predicate}$objPart."
                }
                "fact" -> {
                    if (!mem.rawText.isNullOrBlank()) return transformFirstPersonToYou(mem.rawText)
                }
            }
        }
        // fallback: raw
        return transformFirstPersonToYou(mem.rawText)
    }

    // Утилиты для дебага
    fun dumpMemories(): List<MemoryEntry> = memories.toList()
    fun dumpRecents(): List<UserMessage> = recentMessages.toList()

    // Очищение памяти (для тестов)
    fun clearAll() {
        recentMessages.clear()
        memories.clear()
        if (this::prefs.isInitialized) prefs.edit().clear().apply()
    }
}
