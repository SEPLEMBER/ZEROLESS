package app.pawstribe.assistant

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
import java.time.*
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.time.temporal.ChronoUnit
import java.util.*
import kotlin.math.floor
import kotlin.math.roundToLong

/**
 * Новый Activity: подробный расчёт расстояния между датами.
 * Random имя: DateSpanRavenActivity
 */
class DateSpanRavenActivity : AppCompatActivity() {

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

        val prefs = getSharedPreferences("PawsTribePrefs", MODE_PRIVATE)
        if (prefs.getBoolean("disableScreenshots", false)) {
            window.setFlags(
                WindowManager.LayoutParams.FLAG_SECURE,
                WindowManager.LayoutParams.FLAG_SECURE
            )
        }

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
                    computeDateSpan(text)
                    input.text = Editable.Factory.getInstance().newEditable("")
                } else {
                    addAssistantLine("Введите две даты (или одну — тогда вторая = сейчас). Пример: '2020-01-01 - 2023-06-15 14:30'")
                }
                true
            } else {
                false
            }
        }

        addSystemLine("DateSpanRaven: введите две даты (разделитель '-' или 'до'/'и'/'между'). Поддерживаются форматы: yyyy-MM-dd, yyyy-MM-dd HH:mm, dd.MM.yyyy, dd/MM/yyyy, итд.")
    }

    // -------------------------
    // Основная логика
    // -------------------------
    private fun computeDateSpan(text: String) {
        addUserLine("> $text")
        val parseResult = parseTwoDateTimes(text)
        if (parseResult == null) {
            addAssistantLine("Не удалось распознать даты. Примеры: '2020-01-01 - 2023-06-15 14:30' или '01.01.2020 до 15.06.2023'.")
            return
        }
        val (a, b) = parseResult
        val start = if (a <= b) a else b
        val end = if (a <= b) b else a

        // full duration
        val duration = Duration.between(start, end)
        val totalSeconds = duration.seconds.toDouble()
        val totalMinutes = totalSeconds / 60.0
        val totalHours = totalMinutes / 60.0
        val totalDays = totalHours / 24.0
        val totalWeeks = totalDays / 7.0

        addAssistantLine("Вычисление между:")
        addAssistantLine(" - start: ${fmtDT(start)}")
        addAssistantLine(" - end  : ${fmtDT(end)}")
        addAssistantLine("")

        // Period-based breakdown (yrs / months / days) — аккуратно с временем
        val p = Period.between(start.toLocalDate(), end.toLocalDate())
        val years = p.years
        val months = p.months
        val days = p.days

        // остаток времени внутри дня: посчитаем так:
        val afterPeriod = start.plusYears(years.toLong()).plusMonths(months.toLong()).plusDays(days.toLong())
        val timeRem = Duration.between(afterPeriod, end)
        val hours = timeRem.toHours()
        val minutes = timeRem.minusHours(hours).toMinutes()
        val seconds = timeRem.minusHours(hours).minusMinutes(minutes).seconds

        addAssistantLine("Разложение (целое):")
        addAssistantLine(" - ${years} лет, ${months} месяцев, ${days} дней, ${hours} ч, ${minutes} мин, ${seconds} с")
        addAssistantLine("")

        // дробные/фракционные значения
        val fractionalYears_byDays = totalDays / 365.2425 // средний год
        val fractionalMonths_byDays = totalDays / 30.436875 // средний месяц (365.2425/12)
        val fractionalWeeks = totalWeeks
        val fractionalDays = totalDays
        val fractionalHours = totalHours

        addAssistantLine("Фракционные значения (десятичные):")
        addAssistantLine(" - лет (по среднему году): ${fmtDecimal(fractionalYears_byDays)}")
        addAssistantLine(" - месяцев (средний): ${fmtDecimal(fractionalMonths_byDays)}")
        addAssistantLine(" - недель: ${fmtDecimal(fractionalWeeks)}")
        addAssistantLine(" - дней: ${fmtDecimal(fractionalDays)}")
        addAssistantLine(" - часов: ${fmtDecimal(fractionalHours)}")
        addAssistantLine(" - минут: ${fmtDecimal(totalMinutes)}")
        addAssistantLine(" - секунд: ${fmtDecimal(totalSeconds)}")
        addAssistantLine("")

        // business days (исключая субботу/воскресенье)
        val business = businessDaysCount(start.toLocalDate(), end.toLocalDate())
        addAssistantLine("Бизнес-дни (Mon-Fri): $business")

        // кол-во високосных 29 февраля между датами
        val feb29Count = countFeb29sBetween(start.toLocalDate(), end.toLocalDate())
        addAssistantLine("Количество 29 февраля (включительно): $feb29Count")

        // ISO weeks difference (целые и дробные)
        val weeksWhole = ChronoUnit.WEEKS.between(start, end)
        addAssistantLine("Целых недель между датами: $weeksWhole")

        // удобная сводка
        addAssistantLine("")
        addAssistantLine("Краткая сводка:")
        addAssistantLine(" - Полных лет: $years")
        addAssistantLine(" - Полных месяцев (включая годы): ${years * 12 + months}")
        addAssistantLine(" - Суммарных дней: ${duration.toDays()} (целых)")
        addAssistantLine(" - Всего часов (целых): ${duration.toHours()}")
        addAssistantLine(" - Всего секунд (целых): ${duration.seconds}")
        addAssistantLine("")
        addAssistantLine("Примечание: месяцы/годы считаются календарно (Period), дробные значения — по среднему числу дней в году/месяце.")
    }

    // -------------------------
    // Парсинг: ищем до двух дат/дата-время в тексте
    // Поддерживаем форматы: yyyy-MM-dd[ HH:mm[:ss]], dd.MM.yyyy[ HH:mm[:ss]], dd/MM/yyyy[ HH:mm], ISO (T), и др.
    // Если найдена одна дата — вторая = сейчас.
    // -------------------------
    private fun parseTwoDateTimes(text: String): Pair<LocalDateTime, LocalDateTime>? {
        val candidates = mutableListOf<String>()

        // регулярки для извлечения дат/датавремён (цифровые шаблоны)
        val patterns = listOf(
            // ISO with optional time: 2023-06-15T14:30:00 or 2023-06-15 14:30
            Regex("""\b\d{4}-\d{2}-\d{2}(?:[T\s]\d{2}:\d{2}(?::\d{2})?)?\b"""),
            // dd.MM.yyyy or dd.MM.yyyy HH:mm
            Regex("""\b\d{1,2}\.\d{1,2}\.\d{4}(?:\s+\d{1,2}:\d{2}(?::\d{2})?)?\b"""),
            // dd/MM/yyyy or dd/MM/yyyy HH:mm
            Regex("""\b\d{1,2}/\d{1,2}/\d{4}(?:\s+\d{1,2}:\d{2})?\b"""),
            // yyyy/MM/dd
            Regex("""\b\d{4}/\d{1,2}/\d{1,2}(?:\s+\d{1,2}:\d{2})?\b""")
        )

        for (p in patterns) {
            for (m in p.findAll(text)) {
                candidates.add(m.value)
            }
            if (candidates.size >= 2) break
        }

        // If still none, try splitting by common separators " - " or " до " / " и "
        if (candidates.isEmpty()) {
            // try to split textual forms: "между X и Y", "X до Y", "X - Y"
            val sepRegex = Regex("""\bмежду\b|\bи\b|\bдо\b| - |–|—|/до/""", RegexOption.IGNORE_CASE)
            // but better to split by '-' if contains
            if (text.contains("-") && text.count { it == '-' } >= 1) {
                val parts = text.split("-")
                if (parts.size >= 2) {
                    candidates.add(parts[0].trim())
                    candidates.add(parts[1].trim())
                }
            } else {
                // try words 'до' or 'и'
                val words = Regex("""(.+?)\s+(?:до|и|между)\s+(.+)""", RegexOption.IGNORE_CASE).find(text)
                if (words != null && words.groupValues.size >= 3) {
                    candidates.add(words.groupValues[1].trim())
                    candidates.add(words.groupValues[2].trim())
                }
            }
        }

        // remove duplicates / empty
        val trimmed = candidates.map { it.trim() }.filter { it.isNotEmpty() }.toMutableList()

        // Keep only up to 2 items
        val items = if (trimmed.size >= 2) trimmed.subList(0, 2) else trimmed

        // if still empty, nothing to parse
        if (items.isEmpty()) return null

        // try parse each item into LocalDateTime
        fun parseOne(s: String): LocalDateTime? {
            val variants = listOf(
                DateTimeFormatter.ISO_LOCAL_DATE_TIME,
                DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"),
                DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"),
                DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss"),
                DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm"),
                DateTimeFormatter.ofPattern("yyyy-MM-dd"),
                DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm:ss"),
                DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm"),
                DateTimeFormatter.ofPattern("dd.MM.yyyy"),
                DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm"),
                DateTimeFormatter.ofPattern("dd/MM/yyyy"),
                DateTimeFormatter.ofPattern("yyyy/MM/dd HH:mm"),
                DateTimeFormatter.ofPattern("yyyy/MM/dd")
            )
            val sClean = s.trim()
            // try direct parse with formatters
            for (f in variants) {
                try {
                    // some patterns parse LocalDate or LocalDateTime; handle both
                    return try {
                        LocalDateTime.parse(sClean, f)
                    } catch (e: DateTimeParseException) {
                        // try LocalDate
                        val ld = LocalDate.parse(sClean, f)
                        ld.atStartOfDay()
                    }
                } catch (_: Exception) {
                }
            }
            // last resort: try to parse just a number-ish as yyyy-MM-dd
            try {
                val ld = LocalDate.parse(sClean)
                return ld.atStartOfDay()
            } catch (_: Exception) {
            }
            return null
        }

        val parsed = mutableListOf<LocalDateTime>()
        for (it in items) {
            val p = parseOne(it)
            if (p != null) parsed.add(p)
        }

        // If we failed to parse, but we had two textual parts like "01.01.2020 до 15.06.2023" earlier, try to re-run a more permissive search
        if (parsed.isEmpty()) {
            // try to find any date-like token again (looser)
            val loose = Regex("""\d{1,4}[-./]\d{1,2}[-./]\d{1,4}(?:[ T]\d{1,2}:\d{2}(?::\d{2})?)?""")
            val found = loose.findAll(text).map { it.value }.toList()
            for (f in found) {
                parseOne(f)?.let { parsed.add(it) }
                if (parsed.size >= 2) break
            }
        }

        // If got only one parsed, second = now
        if (parsed.size == 1) {
            parsed.add(LocalDateTime.now())
        }

        // If we have >=2, return pair(first, second)
        if (parsed.size >= 2) {
            return Pair(parsed[0], parsed[1])
        }

        return null
    }

    // -------------------------
    // Утилиты
    // -------------------------
    private fun fmtDT(dt: LocalDateTime): String {
        val fmt = DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm:ss")
        return dt.format(fmt)
    }

    private fun fmtDecimal(d: Double): String {
        return String.format(Locale.getDefault(), "%.6f", d)
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

    // count business days (Mon-Fri) inclusive of start, exclusive of end
    private fun businessDaysCount(startInclusive: LocalDate, endExclusive: LocalDate): Long {
        var count = 0L
        var d = startInclusive
        while (!d.isAfter(endExclusive.minusDays(1))) {
            val dow = d.dayOfWeek
            if (dow != DayOfWeek.SATURDAY && dow != DayOfWeek.SUNDAY) count++
            d = d.plusDays(1)
        }
        return count
    }

    // count occurrences of Feb 29 between dates inclusive start..end
    private fun countFeb29sBetween(startInclusive: LocalDate, endInclusive: LocalDate): Int {
        var count = 0
        var y = startInclusive.year
        val endYear = endInclusive.year
        while (y <= endYear) {
            if (Year.isLeap(y)) {
                val feb29 = LocalDate.of(y, Month.FEBRUARY, 29)
                if (( !feb29.isBefore(startInclusive) ) && ( !feb29.isAfter(endInclusive) )) {
                    count++
                }
            }
            y++
        }
        return count
    }
}
