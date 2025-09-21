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
 * Поддерживает произвольные плейсхолдеры <xxx>, сохранение слотов в SharedPreferences,
 * использование ncorrect.txt и случайные варианты ответа через |.
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

    // дополнительные глобальные маппинги (передаются вызовом loadTemplatesFromFolder)
    private var synonymsGlobal: Map<String, String> = emptyMap()

    // слот-плейсхолдеры, поддерживаемые по умолчанию (можно дополнять)
    private val knownSlots = setOf("name", "age", "petname")

    // Инициализация менеджера памяти
    fun init(context: Context) {
        prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    // Загрузить шаблоны из директории (DocumentFile tree)
    // `synonyms` — опциональная мапа canonical форм (передавать engine.synonymsMap для совместимости)
    fun loadTemplatesFromFolder(context: Context, folderUri: Uri?, synonyms: Map<String, String> = emptyMap()) {
        vospominaniaTemplates.clear()
        zapominanieTemplates.clear()
        corrections.clear()
        synonymsGlobal = synonyms
        val uri = folderUri ?: return
        try {
            val dir = DocumentFile.fromTreeUri(context, uri) ?: return

            dir.findFile(FILE_NCORRECT)?.takeIf { it.exists() }?.let { file ->
                context.contentResolver.openInputStream(file.uri)?.bufferedReader()?.useLines { lines ->
                    lines.forEach { raw ->
                        val l = raw.trim()
                        if (l.isEmpty()) return@forEach
                        // формат: incorrect=correct
                        // Если строка начинается с recall:, теперь просто убираем префикс и сохраняем как обычную корректировку.
                        val parts = l.split("=", limit = 2).map { it.trim() }
                        if (parts.size == 2) {
                            var left = parts[0]
                            val right = parts[1]
                            if (left.startsWith("recall:")) {
                                left = left.substringAfter("recall:")
                            }
                            corrections[left.lowercase(Locale.getDefault())] = right
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

            Log.d(
                TAG,
                "Loaded templates: vosp=${vospominaniaTemplates.size}, zapom=${zapominanieTemplates.size}, corrections=${corrections.size}"
            )

        } catch (e: Exception) {
            Log.e(TAG, "Error loading templates from folder", e)
        }
    }

    // Создаёт Template из строкового шаблона: поддерживает любые <placeholder>
    // Строим regex по нормализованной/mapped форме (synonymsGlobal применяется к литеральным сегментам)
    private fun buildTemplateFromPattern(pattern: String, response: String?, isSlot: Boolean): Template {
        val placeholderRegex = Regex("<([a-zA-Z0-9_]+)>")
        val placeholders = placeholderRegex.findAll(pattern).map { it.groupValues[1] }.toList()

        // Если шаблон — ровно один плейсхолдер (например "<name>"), захватываем всю строку (включая переводы строк)
        val onlyPlaceholder = pattern.trim().matches(Regex("^<[^>]+>\$"))

        val sb = StringBuilder()
        var last = 0
        for (m in placeholderRegex.findAll(pattern)) {
            val start = m.range.first
            val end = m.range.last
            if (start > last) {
                val segment = pattern.substring(last, start)
                val mapped = mapAndNormalizeSegment(segment)
                if (mapped.isNotEmpty()) sb.append(Pattern.quote(mapped))
            }
            // заменяем на ленивую захватывающую группу, но предотвращаем захват перевода строки
            // чтобы плейсхолдер не поглотил несколько абзацев: захватываем всё кроме '\n'
            sb.append("([^\\n]+?)")
            last = end + 1
        }
        if (last < pattern.length) {
            val tail = pattern.substring(last)
            val mappedTail = mapAndNormalizeSegment(tail)
            if (mappedTail.isNotEmpty()) sb.append(Pattern.quote(mappedTail))
        }

        val finalRegex = if (onlyPlaceholder) {
            // захватываем ВСЁ (включая переводы строк) — т.к. шаблон состоит только из плейсхолдера
            "^\\s*([\\s\\S]+)\\s*\$"
        } else {
            "^\\s*" + sb.toString() + "\\s*\$"
        }

        val kotlinRegex = Regex(finalRegex, setOf(RegexOption.IGNORE_CASE))

        val targetSlot = if (isSlot && placeholders.size == 1) placeholders[0] else null

        return Template(raw = pattern, regex = kotlinRegex, placeholders = placeholders, responseTemplate = response, targetSlot = targetSlot)
    }

    // Нормализует и заменяет токены на canonical (synonymsGlobal) для кусочка текста шаблона
    private fun mapAndNormalizeSegment(seg: String): String {
        val norm = Engine.normalizeText(seg)
        if (norm.isBlank()) return ""
        val toks = norm.split(Regex("\\s+")).map { t ->
            val k = t.lowercase(Locale.getDefault())
            synonymsGlobal[k] ?: k
        }
        return toks.joinToString(" ")
    }

    // Применить исправления из ncorrect.txt на нормализованную строку (только corrections)
    // Работает через токенизацию (более корректно для разных языков), fallback - regex замены
    private fun applyCorrections(s: String): String {
        if (corrections.isEmpty()) return s
        try {
            val toks = Engine.tokenizeStatic(s)
            if (toks.isNotEmpty()) {
                val mapped = toks.map { t ->
                    val key = t.lowercase(Locale.getDefault())
                    corrections[key] ?: t
                }
                return mapped.joinToString(" ")
            }
        } catch (_: Exception) {
            // fallthrough to regex-based approach
        }

        var res = s
        // Legacy fallback: простая замена по границам слов (для языков с пробелами)
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
            return match.groupValues.getOrNull(idx + 1)?.trim()?.takeIf { it.isNotBlank() }
        }
        // fallback: если нет такого плейсхолдера — вернём первую группу
        return match.groupValues.getOrNull(1)?.trim()?.takeIf { it.isNotBlank() }
    }

    // Рендер ответа, подставляя захваченные группы (по имени плейсхолдера), поддержка | (варианты)
    private fun renderResponseWithPlaceholders(template: String, match: MatchResult, placeholders: List<String>): String {
        // если есть альтернативы через | — выберем одну случайно
        val chosen = if (template.contains("|")) {
            val parts = template.split("|").map { it.trim() }.filter { it.isNotEmpty() }
            if (parts.isEmpty()) template else parts.random()
        } else template

        var out = chosen
        val placeholderRegex = Regex("<([a-zA-Z0-9_]+)>")
        placeholderRegex.findAll(chosen).forEach { m ->
            val name = m.groupValues[1]
            val valFromMatch = getCapturedValue(match, placeholders, name)
            val replacement = when {
                !valFromMatch.isNullOrBlank() -> valFromMatch
                else -> readSlot(name) ?: ""
            }
            out = out.replace("<${name}>", replacement)
        }
        return out
    }

    // Добавить входящее сообщение и попытаться извлечь память / слоты
    fun processIncoming(context: Context, text: String): String? {
        val normalized = Engine.normalizeText(text)
        val corrected = applyCorrections(normalized)
        // также подготовим mapped версию для поиска (synonyms)
        val correctedMapped = corrected.split(Regex("\\s+")).map { t ->
            val k = t.lowercase(Locale.getDefault())
            synonymsGlobal[k] ?: k
        }.joinToString(" ")
        addRecentMessage(text, corrected)

        // 1) попытка сопоставления с zapominanie (сохранение слотов)
        for (tpl in zapominanieTemplates) {
            val m = tpl.regex.find(correctedMapped)
            if (m != null) {
                // если шаблон предназначен для слота
                if (tpl.targetSlot != null) {
                    val slotValue = getCapturedValue(m, tpl.placeholders, tpl.targetSlot)
                    if (!slotValue.isNullOrBlank()) {
                        saveSlot(tpl.targetSlot, slotValue)
                        val resp = tpl.responseTemplate?.let { renderResponseWithPlaceholders(it, m, tpl.placeholders) }
                            ?: "Запомнил."
                        return resp
                    }
                } else {
                    // общий zapominanie: берем первую группу и, если шаблон содержит имя — сохраним
                    val val0 = m.groupValues.getOrNull(1)?.trim()
                    if (!val0.isNullOrBlank()) {
                        if (tpl.placeholders.contains("name")) {
                            saveSlot("name", val0)
                            val resp = tpl.responseTemplate?.let { renderResponseWithPlaceholders(it, m, tpl.placeholders) } ?: "Запомню это."
                            return resp
                        } else {
                            val resp = tpl.responseTemplate?.let { renderResponseWithPlaceholders(it, m, tpl.placeholders) }
                            if (!resp.isNullOrBlank()) return resp
                        }
                    }
                }
            }
        }

        // 2) попытка сопоставления с vospominania (создание памяти)
        for (tpl in vospominaniaTemplates) {
            val m = tpl.regex.find(correctedMapped)
            if (m != null) {
                // Сохраняем все захваченные плейсхолдеры в слоты (если есть)
                for (ph in tpl.placeholders) {
                    val captured = getCapturedValue(m, tpl.placeholders, ph)
                    if (!captured.isNullOrBlank()) {
                        saveSlot(ph, captured)
                    }
                }

                val placeholders = tpl.placeholders
                val mainCaptured = if (placeholders.isNotEmpty()) getCapturedValue(m, placeholders, placeholders[0]) else m.groupValues.getOrNull(1)
                val obj = if (placeholders.size > 1) getCapturedValue(m, placeholders, placeholders[1]) else null

                val entry = MemoryEntry(
                    type = "event",
                    predicate = mainCaptured?.takeIf { it.isNotBlank() } ?: tpl.raw,
                    obj = obj,
                    rawText = text
                )
                pushMemory(entry)
                val resp = tpl.responseTemplate?.let { renderResponseWithPlaceholders(it, m, tpl.placeholders) } ?: "Понял, запомню."
                return resp
            }
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
        val key = slot.trim()
        val v = value.trim()
        if (key.isEmpty() || v.isEmpty()) return
        prefs.edit().putString(key, v).apply()
        Log.d(TAG, "Saved slot: $key = $v")
    }

    fun readSlot(slot: String): String? {
        if (!this::prefs.isInitialized) return null
        return prefs.getString(slot, null)
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
