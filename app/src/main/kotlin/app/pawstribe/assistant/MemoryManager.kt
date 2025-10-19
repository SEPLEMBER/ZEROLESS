package app.pawstribe.assistant

import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import android.util.Log
import androidx.documentfile.provider.DocumentFile
import java.io.InputStreamReader
import java.util.ArrayDeque
import java.util.Locale
import java.util.regex.Pattern

private const val TAG = "MemoryManager"

object MemoryManager {

    private const val PREFS_NAME = "pawscribe_memory"

    private const val FILE_VOSPOMINANIA = "vospominania.txt"
    private const val FILE_ZAPOMINANIE = "zapominanie.txt"
    private const val FILE_NCORRECT = "ncorrect.txt"

    private const val RECENT_MESSAGES_LIMIT = 10
    private const val MEMORIES_LIMIT = 200

    data class UserMessage(val text: String, val normalized: String, val ts: Long = System.currentTimeMillis())

    data class MemoryEntry(
        val type: String,
        val predicate: String?,
        val obj: String?,
        val rawText: String,
        val ts: Long = System.currentTimeMillis(),
        var confidence: Double = 1.0
    )

    data class Template(
        val raw: String,
        val regex: Regex,
        val placeholders: List<String>,
        val responseTemplate: String? = null,
        val targetSlot: String? = null
    )

    private lateinit var prefs: SharedPreferences

    private val recentMessages: ArrayDeque<UserMessage> = ArrayDeque()
    private val memories: ArrayDeque<MemoryEntry> = ArrayDeque()

    private val vospominaniaTemplates: MutableList<Template> = mutableListOf()
    private val zapominanieTemplates: MutableList<Template> = mutableListOf()

    private val corrections: MutableMap<String, String> = mutableMapOf()

    private var synonymsGlobal: Map<String, String> = emptyMap()

    private val knownSlots = setOf("name", "age", "petname")

    fun init(context: Context) {
        prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    fun loadTemplatesFromFolder(context: Context, folderUri: Uri?, synonyms: Map<String, String> = emptyMap()) {
        vospominaniaTemplates.clear()
        zapominanieTemplates.clear()
        corrections.clear()
        synonymsGlobal = synonyms
        val uri = folderUri ?: return
        try {
            val dir = DocumentFile.fromTreeUri(context, uri) ?: return

            dir.findFile(FILE_NCORRECT)?.takeIf { it.exists() }?.let { file ->
                context.contentResolver.openInputStream(file.uri)?.use { ins ->
                    InputStreamReader(ins, Charsets.UTF_8).buffered().useLines { lines ->
                        lines.forEach { raw ->
                            val l = raw.trim()
                            if (l.isEmpty()) return@forEach
                            val parts = l.split("=", limit = 2).map { it.trim() }
                            if (parts.size == 2) {
                                var left = parts[0]
                                val right = parts[1]
                                if (left.startsWith("recall:")) {
                                    left = left.substringAfter("recall:")
                                }
                                corrections[Engine.normalizeText(left)] = right
                            }
                        }
                    }
                }
            }

            dir.findFile(FILE_ZAPOMINANIE)?.takeIf { it.exists() }?.let { file ->
                context.contentResolver.openInputStream(file.uri)?.use { ins ->
                    InputStreamReader(ins, Charsets.UTF_8).buffered().useLines { lines ->
                        lines.forEach { raw ->
                            val l = raw.trim()
                            if (l.isEmpty()) return@forEach
                            try {
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
            }

            dir.findFile(FILE_VOSPOMINANIA)?.takeIf { it.exists() }?.let { file ->
                context.contentResolver.openInputStream(file.uri)?.use { ins ->
                    InputStreamReader(ins, Charsets.UTF_8).buffered().useLines { lines ->
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
            }

            Log.d(
                TAG,
                "Loaded templates: vosp=${vospominaniaTemplates.size}, zapom=${zapominanieTemplates.size}, corrections=${corrections.size}"
            )

        } catch (e: Exception) {
            Log.e(TAG, "Error loading templates from folder", e)
        }
    }

    private val placeholderRegex = Regex("<([\\p{L}\\p{N}_]+)>")

    private fun buildTemplateFromPattern(pattern: String, response: String?, isSlot: Boolean): Template {
        val placeholders = placeholderRegex.findAll(pattern).map { it.groupValues[1] }.toList()

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
            sb.append("([^\\n]+?)")
            last = end + 1
        }
        if (last < pattern.length) {
            val tail = pattern.substring(last)
            val mappedTail = mapAndNormalizeSegment(tail)
            if (mappedTail.isNotEmpty()) sb.append(Pattern.quote(mappedTail))
        }

        val finalRegex = if (onlyPlaceholder) {
            "^\\s*([\\s\\S]+)\\s*\$"
        } else {
            "^\\s*" + sb.toString() + "\\s*\$"
        }

        val kotlinRegex = Regex(finalRegex, setOf(RegexOption.IGNORE_CASE))

        val targetSlot = if (isSlot && placeholders.size == 1) placeholders[0] else null

        return Template(raw = pattern, regex = kotlinRegex, placeholders = placeholders, responseTemplate = response, targetSlot = targetSlot)
    }

    private fun mapAndNormalizeSegment(seg: String): String {
        val norm = Engine.normalizeText(seg)
        if (norm.isBlank()) return ""
        val toks = norm.split(Regex("\\s+")).map { t ->
            val k = Engine.normalizeText(t)
            synonymsGlobal[k] ?: k
        }
        return toks.joinToString(" ")
    }

    private fun applyCorrections(s: String): String {
        if (corrections.isEmpty()) return s
        try {
            val toks = Engine.tokenizeStatic(s)
            if (toks.isNotEmpty()) {
                val mapped = toks.map { t ->
                    val key = Engine.normalizeText(t)
                    corrections[key] ?: t
                }
                return mapped.joinToString(" ")
            }
        } catch (_: Exception) {
        }

        var res = s
        for ((bad, good) in corrections) {
            try {
                res = res.replace(Regex("\\b" + Regex.escape(bad) + "\\b", RegexOption.IGNORE_CASE), good)
            } catch (_: Exception) {
            }
        }
        return res
    }

    private fun getCapturedValue(match: MatchResult, placeholders: List<String>, name: String): String? {
        val idx = placeholders.indexOf(name)
        if (idx >= 0) {
            return match.groupValues.getOrNull(idx + 1)?.trim()?.takeIf { it.isNotBlank() }
        }
        return match.groupValues.getOrNull(1)?.trim()?.takeIf { it.isNotBlank() }
    }

    private fun renderResponseWithPlaceholders(template: String, match: MatchResult, placeholders: List<String>): String {
        val chosen = if (template.contains("|")) {
            val parts = template.split("|").map { it.trim() }.filter { it.isNotEmpty() }
            if (parts.isEmpty()) template else parts.random()
        } else template

        var out = chosen
        placeholderRegex.findAll(chosen).forEach { m ->
            val name = m.groupValues[1]
            val valFromMatch = getCapturedValue(match, placeholders, name)
            val replacement = when {
                !valFromMatch.isNullOrBlank() -> valFromMatch
                else -> readSlot(name) ?: ""
            }
            out = out.replace("<${'$'}{name}>", replacement)
        }
        return out
    }

    fun processIncoming(context: Context, text: String): String? {
        val normalized = Engine.normalizeText(text)
        val corrected = applyCorrections(normalized)
        val correctedMapped = corrected.split(Regex("\\s+")).map { t ->
            val k = Engine.normalizeText(t)
            synonymsGlobal[k] ?: k
        }.joinToString(" ")
        addRecentMessage(text, corrected)

        for (tpl in zapominanieTemplates) {
            val m = tpl.regex.find(correctedMapped)
            if (m != null) {
                if (tpl.targetSlot != null) {
                    val slotValue = getCapturedValue(m, tpl.placeholders, tpl.targetSlot)
                    if (!slotValue.isNullOrBlank()) {
                        saveSlot(tpl.targetSlot, slotValue)
                        val resp = tpl.responseTemplate?.let { renderResponseWithPlaceholders(it, m, tpl.placeholders) }
                            ?: "Запомнил."
                        return resp
                    }
                } else {
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

        for (tpl in vospominaniaTemplates) {
            val m = tpl.regex.find(correctedMapped)
            if (m != null) {
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

    // --- helpers for name-slot casing preservation ---

    private fun isNameSlot(slot: String): Boolean {
        return slot.contains("name", ignoreCase = true)
    }

    // Попытка восстановить оригинальную регистровую форму value, взяв подстроку из originalText при совпадении нормализованных токенов.
    private fun recoverOriginalCasing(value: String, originalText: String): String {
        if (originalText.isBlank()) return capitalizeWords(value)

        val normTarget = Engine.normalizeText(value).trim()
        if (normTarget.isEmpty()) return capitalizeWords(value)

        // Найдём «словесные» фрагменты в оригинальном тексте (с позициями)
        val wordRegex = Regex("\\p{L}[\\p{L}\\p{N}'-]*")
        val matches = wordRegex.findAll(originalText).toList()
        if (matches.isEmpty()) return capitalizeWords(value)

        // Собираем нормализованные токены для сравнения
        val normTokens = matches.map { Engine.normalizeText(it.value) }

        // Попробуем все подпоследовательности
        for (start in normTokens.indices) {
            val sb = StringBuilder()
            for (end in start until normTokens.size) {
                if (sb.isNotEmpty()) sb.append(" ")
                sb.append(normTokens[end])
                if (sb.toString() == normTarget) {
                    // нашли соответствие — вернём подстроку с оригинальным регистром
                    val startIdx = matches[start].range.first
                    val endIdx = matches[end].range.last + 1
                    return originalText.substring(startIdx, endIdx).trim()
                }
                // если длина превышает цель — можно прервать внутренний цикл
                if (sb.length > normTarget.length + 10) break
            }
        }

        // fallback: если явного вхождения нет — попробуем простую капитализацию
        return capitalizeWords(value)
    }

    private fun capitalizeWords(s: String): String {
        return s.split(Regex("\\s+")).filter { it.isNotEmpty() }.joinToString(" ") { part ->
            part.replaceFirstChar {
                if (it.isLowerCase()) it.titlecase(Locale.getDefault()) else it.toString()
            }
        }
    }

    // Изменённая saveSlot: сохраняем оригинальную капитализацию для слотов, содержащих "name"
    private fun saveSlot(slot: String, value: String) {
        if (!this::prefs.isInitialized) return
        val key = slot.trim()
        var v = value.trim()
        if (key.isEmpty() || v.isEmpty()) return

        if (isNameSlot(key)) {
            // Берём последнее пользовательское сообщение (оригинал) — addRecentMessage вызывается ранее в processIncoming
            val recentOriginal = recentMessages.firstOrNull()?.text ?: ""
            val recovered = try {
                recoverOriginalCasing(v, recentOriginal)
            } catch (_: Exception) {
                capitalizeWords(v)
            }
            v = recovered
        } else {
            // Для остальных слотов оставляем текущее поведение.
            // при желании можно нормализовать: v = Engine.normalizeText(v)
        }

        prefs.edit().putString(key, v).apply()
        Log.d(TAG, "Saved slot: $key = $v")
    }

    fun readSlot(slot: String): String? {
        if (!this::prefs.isInitialized) return null
        return prefs.getString(slot, null)
    }

    fun dumpMemories(): List<MemoryEntry> = memories.toList()
    fun dumpRecents(): List<UserMessage> = recentMessages.toList()

    fun clearAll() {
        recentMessages.clear()
        memories.clear()
        if (this::prefs.isInitialized) prefs.edit().clear().apply()
    }
}
