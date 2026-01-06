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
        input.setRawInputType(
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        )

        input.setOnEditorActionListener { _, actionId, event ->
            if (actionId == EditorInfo.IME_ACTION_DONE ||
                event?.keyCode == KeyEvent.KEYCODE_ENTER
            ) {
                val text = input.text.toString().trim()
                if (text.isNotEmpty()) {
                    computeDateSpan(text)
                    input.text = Editable.Factory.getInstance().newEditable("")
                } else {
                    addAssistantLine(
                        "Введите две даты или одну (вторая будет «сейчас»)"
                    )
                }
                true
            } else false
        }

        addSystemLine(
            "DateSpanRaven — расчёт расстояния между датами. " +
                    "Пример: 2020-01-01 - 2023-06-15 14:30"
        )
    }

    // ===================== ОСНОВНАЯ ЛОГИКА =====================

    private fun computeDateSpan(text: String) {
        addUserLine("> $text")

        val parsed = parseTwoDateTimes(text)
            ?: run {
                addAssistantLine("Не удалось распознать даты.")
                return
            }

        val (a, b) = parsed
        val start = minOf(a, b)
        val end = maxOf(a, b)

        val duration = Duration.between(start, end)

        val totalSeconds = duration.seconds.toDouble()
        val totalMinutes = totalSeconds / 60.0
        val totalHours = totalMinutes / 60.0
        val totalDays = totalHours / 24.0
        val totalWeeks = totalDays / 7.0

        addAssistantLine("Между:")
        addAssistantLine(" start: ${fmtDT(start)}")
        addAssistantLine(" end  : ${fmtDT(end)}")
        addAssistantLine("")

        val period = Period.between(start.toLocalDate(), end.toLocalDate())

        val afterPeriod = start
            .plusYears(period.years.toLong())
            .plusMonths(period.months.toLong())
            .plusDays(period.days.toLong())

        val remainder = Duration.between(afterPeriod, end)

        addAssistantLine(
            "Разложение:\n" +
                    " ${period.years} лет, ${period.months} мес, ${period.days} дней\n" +
                    " ${remainder.toHours()} ч, " +
                    "${remainder.toMinutes() % 60} мин, " +
                    "${remainder.seconds % 60} сек"
        )

        addAssistantLine("")
        addAssistantLine("Дробные значения:")
        addAssistantLine(" лет ≈ ${fmtDecimal(totalDays / 365.2425)}")
        addAssistantLine(" месяцев ≈ ${fmtDecimal(totalDays / 30.436875)}")
        addAssistantLine(" недель ≈ ${fmtDecimal(totalWeeks)}")
        addAssistantLine(" дней ≈ ${fmtDecimal(totalDays)}")
        addAssistantLine(" часов ≈ ${fmtDecimal(totalHours)}")

        val businessDays = businessDaysCount(
            start.toLocalDate(),
            end.toLocalDate()
        )
        addAssistantLine("Бизнес-дни (Пн–Пт): $businessDays")

        val feb29 = countFeb29sBetween(
            start.toLocalDate(),
            end.toLocalDate()
        )
        addAssistantLine("29 февраля: $feb29")

        addAssistantLine("")
        addAssistantLine("Всего:")
        addAssistantLine(" дней: ${duration.toDays()}")
        addAssistantLine(" часов: ${duration.toHours()}")
        addAssistantLine(" секунд: ${duration.seconds}")
    }

    // ===================== ПАРСИНГ =====================

    private fun parseTwoDateTimes(
        text: String
    ): Pair<LocalDateTime, LocalDateTime>? {

        val regex = Regex(
            """\d{4}-\d{2}-\d{2}(?:[ T]\d{2}:\d{2}(?::\d{2})?)?|
               \d{1,2}\.\d{1,2}\.\d{4}(?:\s\d{2}:\d{2})?|
               \d{1,2}/\d{1,2}/\d{4}""".trimIndent()
        )

        val found = regex.findAll(text).map { it.value }.toList()
        if (found.isEmpty()) return null

        val dates = found.mapNotNull { parseOneDate(it) }.toMutableList()
        if (dates.size == 1) dates.add(LocalDateTime.now())

        return if (dates.size >= 2) dates[0] to dates[1] else null
    }

    private fun parseOneDate(s: String): LocalDateTime? {
        val formats = listOf(
            "yyyy-MM-dd HH:mm:ss",
            "yyyy-MM-dd HH:mm",
            "yyyy-MM-dd",
            "dd.MM.yyyy HH:mm",
            "dd.MM.yyyy",
            "dd/MM/yyyy"
        )

        for (f in formats) {
            try {
                return LocalDateTime.parse(
                    s,
                    DateTimeFormatter.ofPattern(f)
                )
            } catch (_: Exception) {
            }
            try {
                return LocalDate.parse(
                    s,
                    DateTimeFormatter.ofPattern(f)
                ).atStartOfDay()
            } catch (_: Exception) {
            }
        }
        return null
    }

    // ===================== УТИЛИТЫ =====================

    private fun fmtDT(dt: LocalDateTime): String =
        dt.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))

    private fun fmtDecimal(d: Double): String =
        String.format(Locale.getDefault(), "%.6f", d)

    private fun addUserLine(text: String) = addLine(text, userGray)
    private fun addAssistantLine(text: String) = addLine(text, neonCyan)

    private fun addSystemLine(text: String) = addLine(
        text,
        neonCyan,
        Typeface.SANS_SERIF,
        13f
    )

    private fun addLine(
        text: String,
        color: Int,
        tf: Typeface = Typeface.MONOSPACE,
        size: Float = 15f
    ) {
        val tv = TextView(this).apply {
            this.text = text
            setTextColor(color)
            setPadding(14)
            textSize = size
            typeface = tf
            movementMethod = ScrollingMovementMethod()
        }
        messagesContainer.addView(tv)
        scrollView.post { scrollView.fullScroll(ScrollView.FOCUS_DOWN) }
    }

    // ===================== ВАЖНЫЙ ФИКС =====================

    private fun businessDaysCount(
        startInclusive: LocalDate,
        endExclusive: LocalDate
    ): Long {
        var count = 0L
        var d = startInclusive
        while (!d.isAfter(endExclusive.minusDays(1L))) {
            val dow = d.dayOfWeek
            if (dow != DayOfWeek.SATURDAY && dow != DayOfWeek.SUNDAY) count++
            d = d.plusDays(1L)
        }
        return count
    }

    private fun countFeb29sBetween(
        startInclusive: LocalDate,
        endInclusive: LocalDate
    ): Int {
        var count = 0
        for (y in startInclusive.year..endInclusive.year) {
            if (Year.isLeap(y)) {
                val d = LocalDate.of(y, Month.FEBRUARY, 29)
                if (!d.isBefore(startInclusive) && !d.isAfter(endInclusive)) {
                    count++
                }
            }
        }
        return count
    }
}
