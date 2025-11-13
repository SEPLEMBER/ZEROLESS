package app.pawstribe.assistant.locale

import android.graphics.Typeface
import android.os.Bundle
import android.text.Editable
import android.text.InputType
import android.text.method.ScrollingMovementMethod
import android.view.KeyEvent
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.setPadding
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale
import kotlin.math.abs
import app.pawstribe.assistant.R

class RuTimeAsActivity : AppCompatActivity() {

    private lateinit var messagesContainer: LinearLayout
    private lateinit var scrollView: ScrollView
    private lateinit var input: EditText

    private val bgColor = 0xFF0A0A0A.toInt()
    private val neonCyan = 0xFF00F5FF.toInt()
    private val userGray = 0xFFB0B0B0.toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        window.setFlags(
            WindowManager.LayoutParams.FLAG_FULLSCREEN,
            WindowManager.LayoutParams.FLAG_FULLSCREEN
        )

        setContentView(R.layout.activity_ru_dig_as)

        messagesContainer = findViewById(R.id.messagesContainer)
        scrollView = findViewById(R.id.scrollView)
        input = findViewById(R.id.inputEditText)

        window.decorView.setBackgroundColor(bgColor)

        input.imeOptions = EditorInfo.IME_ACTION_DONE
        input.setRawInputType(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS)
        input.setOnEditorActionListener { _, actionId, event ->
            if (actionId == EditorInfo.IME_ACTION_DONE || (event?.keyCode == KeyEvent.KEYCODE_ENTER)) {
                val text = input.text.toString().trim()
                if (text.isNotEmpty()) {
                    submitCommand(text)
                    input.text = Editable.Factory.getInstance().newEditable("")
                } else {
                    addAssistantLine("Введите команду (help — справка).")
                }
                true
            } else {
                false
            }
        }

        addSystemLine("RuTimeAs — команды времени. Введите 'help' для справки.")
    }

    private fun submitCommand(command: String) {
        addUserLine("> $command")
        lifecycleScope.launch(Dispatchers.Default) {
            try {
                val outputs = parseCommand(command)
                withContext(Dispatchers.Main) {
                    outputs.forEach { addAssistantLine("= $it") }
                    scrollView.post { scrollView.fullScroll(ScrollView.FOCUS_DOWN) }
                }
            } catch (t: Throwable) {
                withContext(Dispatchers.Main) {
                    addAssistantLine("= Ошибка при обработке команды: ${t.message ?: t::class.java.simpleName}")
                    scrollView.post { scrollView.fullScroll(ScrollView.FOCUS_DOWN) }
                }
            }
        }
    }

    private suspend fun parseCommand(commandRaw: String): List<String> {
        val cmd = commandRaw.trim()

        try {
            val mainRes = RuTimeAsCommandsMain.handleCommand(cmd)
            if (mainRes.isNotEmpty()) return mainRes
        } catch (_: Exception) { }

        try {
            val v2Res = RuTimeAsCommandsV2.handleCommand(cmd)
            if (v2Res.isNotEmpty()) return v2Res
        } catch (_: Exception) { }

        try {
            val v3Res = RuTimeAsCommandsV3.handleCommand(cmd)
            if (v3Res.isNotEmpty()) return v3Res
        } catch (_: Exception) { }

        return listOf("Неизвестная команда. Введите 'help' для справки.")
    }

    private fun addUserLine(text: String) {
        val tv = TextView(this).apply {
            this.text = text
            setTextColor(userGray)
            setPadding(14)
            textSize = 14f
            typeface = Typeface.MONOSPACE
        }
        messagesContainer.addView(tv)
        scrollView.post { scrollView.fullScroll(ScrollView.FOCUS_DOWN) }
    }

    private fun addAssistantLine(text: String) {
        val tv = TextView(this).apply {
            this.text = text
            setTextColor(neonCyan)
            setPadding(14)
            textSize = 15f
            typeface = Typeface.MONOSPACE
            movementMethod = ScrollingMovementMethod()
        }
        messagesContainer.addView(tv)
        scrollView.post { scrollView.fullScroll(ScrollView.FOCUS_DOWN) }
    }

    private fun addSystemLine(text: String) {
        val tv = TextView(this).apply {
            this.text = text
            setTextColor(neonCyan)
            setPadding(12)
            textSize = 13f
            typeface = Typeface.SANS_SERIF
        }
        messagesContainer.addView(tv)
        scrollView.post { scrollView.fullScroll(ScrollView.FOCUS_DOWN) }
    }
}

// --------------------
// RuTimeAsCommandsMain:
// 1) Конверсия / деление между временными единицами
// --------------------
private object RuTimeAsCommandsMain {

    fun handleCommand(cmdRaw: String): List<String> {
        val lower = cmdRaw.lowercase(Locale.getDefault())

        // help
        if (lower == "help" || lower.contains("справк") || lower.contains("помощ")) {
            return listOf(
                "Доступные команды (по модулям):",
                "Main: конверсия / деление единиц (дни/недели/месяцы ↔ часы/дни/недели).",
                "  Примеры:",
                "   - '10 дней в часы' → конверсия",
                "   - '3 недели в дни'",
                "   - '14 дней / 7 часов' или '14 дней поделить на 7 часов' → число (отношение)",
                "V2: разница между двумя датами/временами.",
                "  Пример: 'разница 13.11.2025 08:00 и 23.11.2025 12:30'",
                "V3: дата + / - N дней.",
                "  Примеры: '13 ноября 2025 + 10 дней', '+5 дней от 2025-11-13', 'сегодня + 3 дня'"
            )
        }

        // division "поделить на" or slash "/"
        val divRe = Regex("""(-?\d+(?:[.,]\d+)?)\s*(\p{L}+)\s*(?:/|поделить\s+на)\s*(-?\d+(?:[.,]\d+)?)\s*(\p{L}+)\b""", RegexOption.IGNORE_CASE)
        val divMatch = divRe.find(cmdRaw)
        if (divMatch != null) {
            val a = divMatch.groupValues[1].replace(',', '.').toDoubleOrNull() ?: return listOf("Неверное число.")
            val unitA = RuTimeUtils.normalizeUnit(divMatch.groupValues[2])
            val b = divMatch.groupValues[3].replace(',', '.').toDoubleOrNull() ?: return listOf("Неверное число.")
            val unitB = RuTimeUtils.normalizeUnit(divMatch.groupValues[4])
            if (unitA == null || unitB == null) return listOf("Не распознал единицы. Используй 'дней, недель, месяцев, часов'.")
            val aSeconds = a * RuTimeUtils.unitToSeconds(unitA)
            val bSeconds = b * RuTimeUtils.unitToSeconds(unitB)
            if (bSeconds == 0.0) return listOf("Деление на ноль невозможно.")
            val ratio = aSeconds / bSeconds
            return listOf(
                "${a} ${unitA} = ${"%.6f".format(aSeconds)} секунд",
                "${b} ${unitB} = ${"%.6f".format(bSeconds)} секунд",
                "Отношение: ${"%.6f".format(ratio)} (т.е. ${a}${unitA} / ${b}${unitB} = ${"%.6f".format(ratio)})"
            )
        }

        // conversion: "X unit1 в unit2" or "X unit1 to unit2"
        val convRe = Regex("""(-?\d+(?:[.,]\d+)?)\s*(\p{L}+)\s*(?:в|to|->)\s*(\p{L}+)\b""", RegexOption.IGNORE_CASE)
        val convMatch = convRe.find(cmdRaw)
        if (convMatch != null) {
            val valNum = convMatch.groupValues[1].replace(',', '.').toDoubleOrNull() ?: return listOf("Неверное число.")
            val fromUnit = RuTimeUtils.normalizeUnit(convMatch.groupValues[2])
            val toUnit = RuTimeUtils.normalizeUnit(convMatch.groupValues[3])
            if (fromUnit == null || toUnit == null) return listOf("Не распознал единицы. Используй 'часы/дни/недели/месяцы'.")
            val fromSec = valNum * RuTimeUtils.unitToSeconds(fromUnit)
            val toValue = fromSec / RuTimeUtils.unitToSeconds(toUnit)
            return listOf("${valNum} ${fromUnit} = ${"%.6f".format(toValue)} ${toUnit}")
        }

        // also allow simple "X unit" queries that ask how many smaller units in larger common sizes
        // e.g. "сколько часов в 3 днях" -> match "сколько .* в X unit"
        val howRe = Regex("""сколько.*в\s+(-?\d+(?:[.,]\d+)?)\s*(\p{L}+)\b""", RegexOption.IGNORE_CASE)
        val howMatch = howRe.find(cmdRaw)
        if (howMatch != null) {
            val num = howMatch.groupValues[1].replace(',', '.').toDoubleOrNull() ?: return listOf("Неверное число.")
            val unit = RuTimeUtils.normalizeUnit(howMatch.groupValues[2])
            if (unit == null) return listOf("Не распознал единицу.")
            // default convert to hours and minutes
            val hours = num * RuTimeUtils.unitToSeconds(unit) / RuTimeUtils.unitToSeconds("hours")
            return listOf("${num} ${unit} = ${"%.3f".format(hours)} часов")
        }

        return emptyList()
    }
}

// --------------------
// RuTimeAsCommandsV2:
// 2) Разница между двумя датами/временами
// --------------------
private object RuTimeAsCommandsV2 {

    fun handleCommand(cmdRaw: String): List<String> {
        val lower = cmdRaw.lowercase(Locale.getDefault())

        if (lower.startsWith("разница") || lower.contains("между") || Regex("""\d{1,2}[\./-]\d{1,2}[\./-]\d{2,4}""").containsMatchIn(lower) || RuTimeUtils.containsMonthName(lower)) {
            return handleDifference(cmdRaw)
        }

        return emptyList()
    }

    private fun handleDifference(cmdRaw: String): List<String> {
        // try to find two date/time tokens separated by "и" or "and" or "to"
        // patterns: "разница <date1> и <date2>" or "<date1> - <date2>"
        val andRe = Regex("""(разница\s+)?(.+?)\s+(?:и|and|-|—|to)\s+(.+)""", RegexOption.IGNORE_CASE)
        val m = andRe.find(cmdRaw)
        val candidates = if (m != null) {
            listOf(m.groupValues[2].trim(), m.groupValues[3].trim())
        } else {
            // fallback: try to extract two date-like substrings via date regexes
            RuTimeUtils.extractTwoDateTimes(cmdRaw)
        }

        if (candidates.size < 2) return listOf("Не удалось распознать две даты/времени. Примеры: 'разница 13.11.2025 08:00 и 23.11.2025 12:30'")

        val dt1 = RuTimeUtils.parseDateTimeFlexible(candidates[0])
            ?: return listOf("Не удалось распознать первую дату/время: '${candidates[0]}'")
        val dt2 = RuTimeUtils.parseDateTimeFlexible(candidates[1])
            ?: return listOf("Не удалось распознать вторую дату/время: '${candidates[1]}'")

        val from = if (dt1.isBefore(dt2)) dt1 else dt2
        val to = if (dt1.isBefore(dt2)) dt2 else dt1

        val days = ChronoUnit.DAYS.between(from, to)
        val hoursTotal = ChronoUnit.HOURS.between(from, to)
        val minutesTotal = ChronoUnit.MINUTES.between(from, to)

        val remHours = hoursTotal % 24
        val remMinutes = minutesTotal % 60

        val lines = mutableListOf<String>()
        lines.add("Первая: ${RuTimeUtils.formatDateTimeNice(dt1)}")
        lines.add("Вторая: ${RuTimeUtils.formatDateTimeNice(dt2)}")
        lines.add("Разница: ${days} дн, ${remHours} ч, ${remMinutes} мин")
        lines.add("Итого: ${hoursTotal} часов (${minutesTotal} минут)")
        return lines
    }
}

// --------------------
// RuTimeAsCommandsV3:
// 3) Дата + / - N дней (поддержка базовой даты или 'сегодня')
// --------------------
private object RuTimeAsCommandsV3 {

    fun handleCommand(cmdRaw: String): List<String> {
        val lower = cmdRaw.lowercase(Locale.getDefault())

        // look for patterns like "<date> + 10 дней", "+10 дней от <date>", "сегодня + 3 дня"
        val plusMinusRe = Regex("""(.+?)?([+-]\s*\d+)\s*(дн|дней|дня|days)?""", RegexOption.IGNORE_CASE)
        val explicitRe = Regex("""(.+?)\s*([+-])\s*(\d+)\s*(дн|дней|дня|days)\b""", RegexOption.IGNORE_CASE)

        // first try explicit pattern with base and sign
        val m1 = explicitRe.find(cmdRaw)
        if (m1 != null) {
            val baseRaw = m1.groupValues[1].trim()
            val sign = m1.groupValues[2]
            val num = m1.groupValues[3].toLongOrNull() ?: return listOf("Неверное число дней.")
            val base = if (baseRaw.isBlank()) LocalDate.now().atStartOfDay() else RuTimeUtils.parseDateTimeFlexible(baseRaw) ?: return listOf("Не удалось распознать базовую дату: '$baseRaw'")
            val res = if (sign == "+") base.plusDays(num) else base.minusDays(num)
            return listOf("Результат: ${RuTimeUtils.formatDateTimeNice(res)}")
        }

        // try forms like "13 ноября 2025 + 10 дней"
        val altRe = Regex("""(.*?)(?:\s+|\b)([+-]?\d+)\s*(дн|дней|дня)\b""", RegexOption.IGNORE_CASE)
        val m2 = altRe.find(cmdRaw)
        if (m2 != null) {
            val baseRaw = m2.groupValues[1].trim()
            val numSignedStr = m2.groupValues[2]
            val num = numSignedStr.toLongOrNull() ?: return listOf("Неверное число дней.")
            val base = if (baseRaw.isBlank()) LocalDate.now().atStartOfDay() else RuTimeUtils.parseDateTimeFlexible(baseRaw) ?: return listOf("Не удалось распознать базовую дату: '$baseRaw'")
            val res = base.plusDays(num)
            return listOf("База: ${RuTimeUtils.formatDateTimeNice(base)}", "Результат: ${RuTimeUtils.formatDateTimeNice(res)}")
        }

        // try commands like "сегодня + 3 дня" or "сегодня -2 дней"
        val todayRe = Regex("""(сегодня|today)\s*([+-])\s*(\d+)\s*дн""", RegexOption.IGNORE_CASE)
        val m3 = todayRe.find(cmdRaw)
        if (m3 != null) {
            val sign = m3.groupValues[2]
            val num = m3.groupValues[3].toLongOrNull() ?: return listOf("Неверное число дней.")
            val base = LocalDate.now().atStartOfDay()
            val res = if (sign == "+") base.plusDays(num) else base.minusDays(num)
            return listOf("Сегодня: ${RuTimeUtils.formatDateTimeNice(base)}", "Результат: ${RuTimeUtils.formatDateTimeNice(res)}")
        }

        return emptyList()
    }
}

// --------------------
// Вспомогательные утилиты для времени/даты и парсинга
// --------------------
private object RuTimeUtils {

    private val ruMonthsGenitive = mapOf(
        "января" to 1, "февраля" to 2, "марта" to 3, "апреля" to 4,
        "мая" to 5, "июня" to 6, "июля" to 7, "августа" to 8,
        "сентября" to 9, "октября" to 10, "ноября" to 11, "декабря" to 12
    )

    private val ruMonthsNominative = mapOf(
        "январь" to 1, "февраль" to 2, "март" to 3, "апрель" to 4,
        "май" to 5, "июнь" to 6, "июль" to 7, "август" to 8,
        "сентябрь" to 9, "октябрь" to 10, "ноябрь" to 11, "декабрь" to 12
    )

    private val dtPatterns = listOf(
        DateTimeFormatter.ofPattern("yyyy-MM-dd['T'HH:mm[:ss]]", Locale.ENGLISH),
        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm", Locale.ENGLISH),
        DateTimeFormatter.ofPattern("yyyy-MM-dd", Locale.ENGLISH),
        DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm", Locale.ENGLISH),
        DateTimeFormatter.ofPattern("dd.MM.yyyy", Locale.ENGLISH),
        DateTimeFormatter.ofPattern("d MMM uuuu HH:mm", Locale.ENGLISH),
        DateTimeFormatter.ofPattern("d MMM uuuu", Locale.ENGLISH),
        DateTimeFormatter.ISO_LOCAL_DATE_TIME,
        DateTimeFormatter.ISO_LOCAL_DATE
    )

    // Normalize common unit tokens to canonical English keys: hours, days, weeks, months
    fun normalizeUnit(raw: String?): String? {
        if (raw == null) return null
        val r = raw.lowercase(Locale.getDefault()).trim()
        return when {
            r.startsWith("ч") || r.startsWith("hour") || r.startsWith("h") -> "hours"
            r.startsWith("д") || r.startsWith("day") || r == "дн" || r.startsWith("день") -> "days"
            r.startsWith("н") || r.startsWith("week") || r.startsWith("нед") -> "weeks"
            r.startsWith("месяц") || r.startsWith("month") || r.startsWith("мес") -> "months"
            else -> null
        }
    }

    // seconds in canonical unit (approx for months = 30 days)
    fun unitToSeconds(unit: String): Double {
        return when (unit) {
            "hours" -> 3600.0
            "days" -> 3600.0 * 24.0
            "weeks" -> 3600.0 * 24.0 * 7.0
            "months" -> 3600.0 * 24.0 * 30.0 // approximate
            else -> 1.0
        }
    }

    // Try to parse many flexible date/time formats, including russian "13 ноября 2025 14:30"
    fun parseDateTimeFlexible(raw: String): LocalDateTime? {
        val s = raw.trim()
        if (s.isBlank()) return null

        // 1) try to parse russian "d MMMM yyyy [HH:mm]" with genitive month names
        val ruGenRe = Regex("""\b(\d{1,2})\s+(января|февраля|марта|апреля|мая|июня|июля|августа|сентября|октября|ноября|декабря)\s+(\d{4})(?:\s+(\d{1,2}):(\d{2}))?""", RegexOption.IGNORE_CASE)
        val g = ruGenRe.find(s)
        if (g != null) {
            val day = g.groupValues[1].toIntOrNull() ?: return null
            val monthName = g.groupValues[2].lowercase(Locale.getDefault())
            val month = ruMonthsGenitive[monthName] ?: return null
            val year = g.groupValues[3].toIntOrNull() ?: return null
            val hour = g.groupValues.getOrNull(4)?.toIntOrNull() ?: 0
            val minute = g.groupValues.getOrNull(5)?.toIntOrNull() ?: 0
            return LocalDateTime.of(year, month, day, hour, minute)
        }

        // 2) try "d MMM yyyy" in english (e.g., 13 Nov 2025)
        for (fmt in dtPatterns) {
            try {
                val parsed = try {
                    // some formatters return LocalDate, some LocalDateTime
                    val tmp = fmt.parseBest(s, LocalDateTime::from, LocalDate::from)
                    when (tmp) {
                        is LocalDateTime -> tmp
                        is LocalDate -> tmp.atStartOfDay()
                        else -> null
                    }
                } catch (e: Exception) {
                    null
                }
                if (parsed != null) return parsed
            } catch (_: Exception) { }
        }

        // 3) try dd/MM/yyyy or dd-MM-yyyy with optional time
        val altRe = Regex("""\b(\d{1,2})[\.\/-](\d{1,2})[\.\/-](\d{2,4})(?:\s+(\d{1,2}):(\d{2}))?\b""")
        val a = altRe.find(s)
        if (a != null) {
            val d = a.groupValues[1].toIntOrNull() ?: return null
            val m = a.groupValues[2].toIntOrNull() ?: return null
            val y = a.groupValues[3].toIntOrNull() ?: return null
            val year = if (y < 100) 2000 + y else y
            val hour = a.groupValues.getOrNull(4)?.toIntOrNull() ?: 0
            val min = a.groupValues.getOrNull(5)?.toIntOrNull() ?: 0
            return try {
                LocalDateTime.of(year, m, d, hour, min)
            } catch (_: Exception) {
                null
            }
        }

        // 4) try "yyyy-MM-dd" or "yyyy-MM-dd HH:mm"
        val isoRe = Regex("""\b(\d{4})-(\d{1,2})-(\d{1,2})(?:[T\s](\d{1,2}):(\d{2}))?\b""")
        val iso = isoRe.find(s)
        if (iso != null) {
            val y = iso.groupValues[1].toIntOrNull() ?: return null
            val mo = iso.groupValues[2].toIntOrNull() ?: return null
            val d = iso.groupValues[3].toIntOrNull() ?: return null
            val hour = iso.groupValues.getOrNull(4)?.toIntOrNull() ?: 0
            val min = iso.groupValues.getOrNull(5)?.toIntOrNull() ?: 0
            return try {
                LocalDateTime.of(y, mo, d, hour, min)
            } catch (_: Exception) {
                null
            }
        }

        // 5) keywords: "сегодня", "завтра", "вчера"
        val lower = s.lowercase(Locale.getDefault())
        if (lower == "сегодня" || lower == "today") return LocalDate.now().atStartOfDay()
        if (lower == "завтра" || lower == "tomorrow") return LocalDate.now().plusDays(1).atStartOfDay()
        if (lower == "вчера" || lower == "yesterday") return LocalDate.now().minusDays(1).atStartOfDay()

        return null
    }

    fun formatDateTimeNice(dt: LocalDateTime): String {
        val fmt = DateTimeFormatter.ofPattern("d MMMM uuuu HH:mm", Locale("ru"))
        // ensure month in genitive for Russian formatting: DateTimeFormatter with Locale("ru") outputs nominative by default for standalone,
        // but this simple pattern is acceptable for display purposes.
        return dt.format(fmt)
    }

    // Try to extract two date-like substrings by simple heuristics (numbers + month names or numeric dates)
    fun extractTwoDateTimes(s: String): List<String> {
        // find all numeric date patterns and russian month patterns
        val parts = mutableListOf<String>()
        val ruGenRe = Regex("""\b\d{1,2}\s+(?:января|февраля|марта|апреля|мая|июня|июля|августа|сентября|октября|ноября|декабря)\s+\d{4}(?:\s+\d{1,2}:\d{2})?""", RegexOption.IGNORE_CASE)
        ruGenRe.findAll(s).forEach { parts.add(it.value) }
        val altRe = Regex("""\b\d{1,2}[\.\/-]\d{1,2}[\.\/-]\d{2,4}(?:\s+\d{1,2}:\d{2})?""")
        altRe.findAll(s).forEach { parts.add(it.value) }
        val isoRe = Regex("""\b\d{4}-\d{1,2}-\d{1,2}(?:[T\s]\d{1,2}:\d{2})?""")
        isoRe.findAll(s).forEach { parts.add(it.value) }

        // if we found at least two, return first two
        if (parts.size >= 2) return listOf(parts[0], parts[1])

        // fallback: try to split on " и " or " - " and take tokens that parse
        val tokens = s.split(Regex("""\s+(?:и|and|to|—|-)\s+"""))
        val parsed = tokens.mapNotNull { t -> if (parseDateTimeFlexible(t) != null) t.trim() else null }
        if (parsed.size >= 2) return parsed.take(2)

        return emptyList()
    }

    fun containsMonthName(s: String): Boolean {
        val lower = s.lowercase(Locale.getDefault())
        return ruMonthsGenitive.keys.any { lower.contains(it) } || ruMonthsNominative.keys.any { lower.contains(it) }
    }
}
