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
import java.time.*
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
// --------------------
private object RuTimeAsCommandsMain {

    fun handleCommand(cmdRaw: String): List<String> {
        val lower = cmdRaw.lowercase(Locale.getDefault())

        if (lower == "help" || lower.contains("справк") || lower.contains("помощ")) {
            return listOf(
                "Доступные команды (Main / V2 / V3):",
                "Main: конверсия единиц, timezone, прогресс, humanize, округление, 'через N ...'.",
                "  Примеры:",
                "   - '10 дней в часы'",
                "   - '3 недели / 5 дней'",
                "   - '2025-11-13 15:00 Europe/Moscow в America/New_York'",
                "   - 'прогресс 01.11.2025 01.12.2025 на 13.11.2025'",
                "   - 'humanize 90061s' -> '1 день 1 час 1 мин'",
                "   - 'округлить 13.11.2025 10:07 до 15мин up'",
                "V2: разница, overlap, рабочие дни, shifts, next occurrence.",
                "V3: add/sub business days, next event, countdown, split, cron next."
            )
        }

        // division "поделить на" or slash "/"
        val divRe = Regex("(-?\\d+(?:[.,]\\d+)?)\\s*(\\p{L}+)\\s*(?:/|поделить\\s+на)\\s*(-?\\d+(?:[.,]\\d+)?)\\s*(\\p{L}+)\\b", RegexOption.IGNORE_CASE)
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
                "Отношение: ${"%.6f".format(ratio)}"
            )
        }

        // conversion
        val convRe = Regex("(-?\\d+(?:[.,]\\d+)?)\\s*(\\p{L}+)\\s*(?:в|to|->)\\s*(\\p{L}+)\\b", RegexOption.IGNORE_CASE)
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

        // timezone conversion: "<datetime> <zone> в <zone>"
        val tzRe = Regex("(.+?)\\s+([A-Za-z0-9_\\\\/:+-]+)\\s+(?:в|to)\\s+([A-Za-z0-9_\\\\/:+-]+)\\b", RegexOption.IGNORE_CASE)
        val tzMatch = tzRe.find(cmdRaw)
        if (tzMatch != null) {
            val dtRaw = tzMatch.groupValues[1].trim()
            val fromZone = tzMatch.groupValues[2].trim()
            val toZone = tzMatch.groupValues[3].trim()
            val dt = RuTimeUtils.parseDateTimeFlexible(dtRaw) ?: return listOf("Не удалось распознать дату/время: '$dtRaw'")
            return try {
                val zFrom = ZoneId.of(fromZone)
                val zTo = ZoneId.of(toZone)
                val zdt = ZonedDateTime.of(dt, zFrom)
                val converted = zdt.withZoneSameInstant(zTo)
                listOf("В $fromZone: ${RuTimeUtils.formatDateTimeNice(dt)}", "В $toZone: ${converted.format(DateTimeFormatter.ofPattern(\"d MMMM uuuu HH:mm\", Locale(\"ru\")))}")
            } catch (e: Exception) {
                listOf("Ошибка зоны: ${e.message}")
            }
        }

        // progress: "прогресс <start> <end> на <now|date>"
        if (lower.startsWith("прогресс")) {
            val body = cmdRaw.substringAfter("прогресс").trim()
            val parts = body.split(Regex("\\s+на\\s+"), 2)
            val periodPart = parts.getOrNull(0)?.trim() ?: ""
            val atRaw = parts.getOrNull(1)?.trim()
            val dateCandidates = RuTimeUtils.extractTwoDateTimes(periodPart).toMutableList()
            if (dateCandidates.size < 2) {
                val sepCandidates = periodPart.split(Regex("\\s+(?:-|–|—|и|and|to)\\s+"))
                if (sepCandidates.size >= 2) {
                    dateCandidates.clear()
                    dateCandidates.add(sepCandidates[0].trim())
                    dateCandidates.add(sepCandidates[1].trim())
                }
            }
            if (dateCandidates.size < 2) return listOf("Не удалось распознать период (start и end). Пример: 'прогресс 01.11.2025 00:00 - 01.12.2025 00:00 на 13.11.2025'")
            val start = RuTimeUtils.parseDateTimeFlexible(dateCandidates[0]) ?: return listOf("Не распознать start")
            val end = RuTimeUtils.parseDateTimeFlexible(dateCandidates[1]) ?: return listOf("Не распознать end")
            val at = if (atRaw.isNullOrBlank()) LocalDateTime.now() else RuTimeUtils.parseDateTimeFlexible(atRaw) ?: return listOf("Не распознать момент 'на'")
            val totalSec = ChronoUnit.SECONDS.between(start, end).toDouble()
            val passedSec = ChronoUnit.SECONDS.between(start, at).toDouble()
            if (totalSec == 0.0) return listOf("Start и end совпадают.")
            val pct = (passedSec / totalSec * 100.0).coerceIn(0.0, 100.0)
            val remain = totalSec - passedSec
            return listOf("Прогресс: ${\"%.2f\".format(pct)}% (прошло: ${RuTimeUtils.humanizeDurationSeconds(passedSec.toLong())}, осталось: ${RuTimeUtils.humanizeDurationSeconds(remain.toLong())})")
        }

        // humanize
        val humRe = Regex("humanize\\s+(.+)$", RegexOption.IGNORE_CASE)
        val humMatch = humRe.find(cmdRaw)
        if (humMatch != null) {
            val token = humMatch.groupValues[1].trim()
            val seconds = RuTimeUtils.parseDurationToSeconds(token) ?: return listOf("Не удалось распознать длительность.")
            return listOf(RuTimeUtils.humanizeDurationSeconds(seconds.toLong()))
        }

        // round time
        val roundRe = Regex("округлить\\s+(.+?)\\s+до\\s+(\\d+)\\s*мин\\s*(up|down|nearest)?", RegexOption.IGNORE_CASE)
        val roundMatch = roundRe.find(cmdRaw)
        if (roundMatch != null) {
            val dtRaw = roundMatch.groupValues[1].trim()
            val step = roundMatch.groupValues[2].toIntOrNull() ?: 1
            val mode = roundMatch.groupValues.getOrNull(3) ?: "nearest"
            val dt = RuTimeUtils.parseDateTimeFlexible(dtRaw) ?: return listOf("Не распознал дату/время.")
            val res = RuTimeUtils.roundDateTime(dt, step, mode)
            return listOf("Оригинал: ${RuTimeUtils.formatDateTimeNice(dt)}", "Результат: ${RuTimeUtils.formatDateTimeNice(res)}")
        }

        // NL "через N ..."
        val inRe = Regex("(?:через|in)\\s+(\\d+)\\s*(дней|дня|дн|д|hours|часов|час|h|weeks|нед|недели)?(?:\\s+в\\s+(\\d{1,2}:\\d{2}))?", RegexOption.IGNORE_CASE)
        val inMatch = inRe.find(cmdRaw)
        if (inMatch != null) {
            val num = inMatch.groupValues[1].toLongOrNull() ?: return listOf("Неверное число.")
            val unitRaw = inMatch.groupValues[2]
            val timePart = inMatch.groupValues.getOrNull(3)
            val base = LocalDateTime.now()
            val unit = RuTimeUtils.normalizeUnit(if (unitRaw.isNullOrBlank()) "days" else unitRaw)
            val res = when (unit) {
                "hours" -> base.plusHours(num)
                "weeks" -> base.plusWeeks(num)
                "months" -> base.plusMonths(num)
                else -> base.plusDays(num)
            }
            val final = if (!timePart.isNullOrBlank()) {
                val hhmm = timePart.split(":")
                try {
                    res.withHour(hhmm[0].toInt()).withMinute(hhmm[1].toInt())
                } catch (e: Exception) { res }
            } else res
            return listOf("Результат: ${RuTimeUtils.formatDateTimeNice(final)}")
        }

        return emptyList()
    }
}

// --------------------
// RuTimeAsCommandsV2:
// --------------------
private object RuTimeAsCommandsV2 {

    fun handleCommand(cmdRaw: String): List<String> {
        val lower = cmdRaw.lowercase(Locale.getDefault())

        if (lower.startsWith("разница") || (lower.contains("между") && (RuTimeUtils.containsMonthName(lower) || Regex("\\d{1,2}[\\.\\/\\-]\\d{1,2}[\\.\\/\\-]\\d{2,4}").containsMatchIn(lower)))) {
            return handleDifference(cmdRaw)
        }

        if (lower.contains("рабочих") && lower.contains("дн")) {
            return handleBusinessDaysBetween(cmdRaw)
        }

        if (lower.contains("перекрытие") || lower.contains("overlap")) {
            return handleOverlap(cmdRaw)
        }

        if (lower.contains("смены") || lower.contains("shifts")) {
            return handleShifts(cmdRaw)
        }

        if (lower.startsWith("next") || lower.startsWith("следующ") || lower.startsWith("следующий") || lower.contains("every")) {
            return handleNextOccurrence(cmdRaw)
        }

        return emptyList()
    }

    private fun handleDifference(cmdRaw: String): List<String> {
        val andRe = Regex("(разница\\s+)?(.+?)\\s+(?:и|and|-|—|to)\\s+(.+)", RegexOption.IGNORE_CASE)
        val m = andRe.find(cmdRaw)
        val candidates = if (m != null) {
            listOf(m.groupValues[2].trim(), m.groupValues[3].trim())
        } else {
            RuTimeUtils.extractTwoDateTimes(cmdRaw)
        }
        if (candidates.size < 2) return listOf("Не удалось распознать две даты/времени. Пример: 'разница 13.11.2025 08:00 и 23.11.2025 12:30'")

        val dt1 = RuTimeUtils.parseDateTimeFlexible(candidates[0]) ?: return listOf("Не распознать первую дату.")
        val dt2 = RuTimeUtils.parseDateTimeFlexible(candidates[1]) ?: return listOf("Не распознать вторую дату.")

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

    private fun handleBusinessDaysBetween(cmdRaw: String): List<String> {
        val two = RuTimeUtils.extractTwoDateTimes(cmdRaw)
        if (two.size < 2) return listOf("Укажите две даты. Пример: 'рабочие дни 13.11.2025 и 30.11.2025'")
        val dt1 = RuTimeUtils.parseDateTimeFlexible(two[0]) ?: return listOf("Не распознать первую дату.")
        val dt2 = RuTimeUtils.parseDateTimeFlexible(two[1]) ?: return listOf("Не распознать вторую дату.")
        val start = dt1.toLocalDate()
        val end = dt2.toLocalDate()
        val count = RuTimeUtils.businessDaysBetween(start, end)
        return listOf("Рабочих дней между ${start} и ${end}: $count (без праздников, только Sat/Sun пропускаются)")
    }

    private fun handleOverlap(cmdRaw: String): List<String> {
        val re = Regex("(\\d{1,2}[\\.\\/\\-]\\d{1,2}[\\.\\/\\-]\\d{2,4}.*?)\\s+(\\d{1,2}:\\d{2})-(\\d{1,2}:\\d{2}).*?(?:и|and)\\s+(\\d{1,2}[\\.\\/\\-]\\d{1,2}[\\.\\/\\-]\\d{2,4}.*?)\\s+(\\d{1,2}:\\d{2})-(\\d{1,2}:\\d{2})", RegexOption.IGNORE_CASE)
        val m = re.find(cmdRaw)
        if (m != null) {
            val d1 = m.groupValues[1].trim()
            val s1 = m.groupValues[2]
            val e1 = m.groupValues[3]
            val d2 = m.groupValues[4].trim()
            val s2 = m.groupValues[5]
            val e2 = m.groupValues[6]
            val dtStart1 = RuTimeUtils.parseDateTimeFlexible("$d1 $s1") ?: return listOf("Не распознать первый интервал")
            val dtEnd1 = RuTimeUtils.parseDateTimeFlexible("$d1 $e1") ?: return listOf("Не распознать первый интервал")
            val dtStart2 = RuTimeUtils.parseDateTimeFlexible("$d2 $s2") ?: return listOf("Не распознать второй интервал")
            val dtEnd2 = RuTimeUtils.parseDateTimeFlexible("$d2 $e2") ?: return listOf("Не распознать второй интервал")
            val overlap = RuTimeUtils.intervalOverlapSeconds(dtStart1, dtEnd1, dtStart2, dtEnd2)
            return if (overlap <= 0) listOf("Интервалы не пересекаются") else listOf("Перекрытие: ${RuTimeUtils.humanizeDurationSeconds(overlap)}")
        }
        return listOf("Не удалось распознать интервалы. Пример: 'перекрытие 13.11.2025 10:00-12:00 и 13.11.2025 11:00-13:00'")
    }

    private fun handleShifts(cmdRaw: String): List<String> {
        val re = Regex("смены\\s+(.+)$", RegexOption.IGNORE_CASE)
        val m = re.find(cmdRaw)
        val body = m?.groupValues?.get(1) ?: cmdRaw
        val ranges = body.split(Regex("[,;]")).map { it.trim() }.filter { it.isNotBlank() }
        val pairs = mutableListOf<Pair<LocalTime, LocalTime>>()
        for (r in ranges) {
            val mm = Regex("(\\d{1,2}:\\d{2})-(\\d{1,2}:\\d{2})").find(r)
            if (mm != null) {
                val s = LocalTime.parse(mm.groupValues[1])
                val e = LocalTime.parse(mm.groupValues[2])
                pairs.add(Pair(s, e))
            }
        }
        if (pairs.isEmpty()) return listOf("Не распознать смены. Пример: 'смены 09:00-17:00,18:00-22:00'")
        var totalMin = 0L
        pairs.forEach { (s, e) ->
            val dur = ChronoUnit.MINUTES.between(s, e)
            totalMin += if (dur >= 0) dur else 0
        }
        val hours = totalMin / 60
        val mins = totalMin % 60
        return listOf("Смен: ${pairs.size}", "Итого: ${hours} ч ${mins} мин")
    }

    private fun handleNextOccurrence(cmdRaw: String): List<String> {
        val re = Regex("(?:every|кажд(?:ый|ые|ая)?)\\s+([\\p{L},\\s]+)\\s+(?:at|в)\\s+(\\d{1,2}:\\d{2})", RegexOption.IGNORE_CASE)
        val m = re.find(cmdRaw)
        if (m != null) {
            val daysRaw = m.groupValues[1]
            val timeRaw = m.groupValues[2]
            val weekdays = RuTimeUtils.parseWeekdayList(daysRaw)
            if (weekdays.isEmpty()) return listOf("Не распознать дни недели.")
            val now = LocalDateTime.now()
            val timeParts = timeRaw.split(":")
            val hour = timeParts[0].toInt()
            val minute = timeParts[1].toInt()
            val next = RuTimeUtils.nextOccurrenceFromWeekdays(now, weekdays, hour, minute)
            return listOf("Следующее: ${RuTimeUtils.formatDateTimeNice(next)}")
        }
        return listOf("Не распознан шаблон next occurrence. Пример: 'every Mon,Wed at 09:00' или 'каждый пн,ср в 09:00'")
    }
}

// --------------------
// RuTimeAsCommandsV3:
// --------------------
private object RuTimeAsCommandsV3 {

    fun handleCommand(cmdRaw: String): List<String> {
        val lower = cmdRaw.lowercase(Locale.getDefault())

        if (lower.contains("рабочих") && (lower.contains("+") || lower.contains("add") || lower.contains("прибав"))) {
            return handleAddBusinessDays(cmdRaw)
        }

        if (lower.contains("add business") || lower.contains("add business-days") || lower.contains("add business days")) {
            return handleAddBusinessDays(cmdRaw)
        }

        if (lower.startsWith("next ") || lower.startsWith("следующее ") || lower.startsWith("ближайшее ")) {
            return handleNextEvent(cmdRaw)
        }

        if (lower.startsWith("countdown") || lower.contains("обратн")) {
            return handleCountdown(cmdRaw)
        }

        if (lower.startsWith("split ") || lower.contains("разбить")) {
            return handleSplit(cmdRaw)
        }

        if (lower.startsWith("cron ")) {
            return handleCronNext(cmdRaw)
        }

        return emptyList()
    }

    private fun handleAddBusinessDays(cmdRaw: String): List<String> {
        val explicit = Regex("(.+?)\\s*([+-])\\s*(\\d+)\\s*(рабочих|рабочих\\s+дней|рабочихдней|business)\\b", RegexOption.IGNORE_CASE).find(cmdRaw)
        if (explicit != null) {
            val baseRaw = explicit.groupValues[1].trim()
            val sign = explicit.groupValues[2]
            val num = explicit.groupValues[3].toIntOrNull() ?: return listOf("Неверное число.")
            val base = if (baseRaw.isBlank()) LocalDate.now() else RuTimeUtils.parseDateTimeFlexible(baseRaw)?.toLocalDate() ?: return listOf("Не распознать базовую дату.")
            val result = if (sign == "+") RuTimeUtils.addBusinessDays(base, num) else RuTimeUtils.addBusinessDays(base, -num)
            return listOf("База: $base", "Результат: $result")
        }
        val addToRe = Regex("add\\s+(\\d+)\\s+business(?:-|\\s)?days\\s+to\\s+(.+)", RegexOption.IGNORE_CASE).find(cmdRaw)
        if (addToRe != null) {
            val num = addToRe.groupValues[1].toIntOrNull() ?: return listOf("Неверное число.")
            val baseRaw = addToRe.groupValues[2].trim()
            val base = RuTimeUtils.parseDateTimeFlexible(baseRaw)?.toLocalDate() ?: return listOf("Не распознать базовую дату.")
            val result = RuTimeUtils.addBusinessDays(base, num)
            return listOf("База: $base", "Результат: $result")
        }

        return listOf("Не распознан add business days. Примеры: '13.11.2025 + 10 рабочих дней' или 'add 10 business days to 2025-11-13'")
    }

    private fun handleNextEvent(cmdRaw: String): List<String> {
        val re = Regex("(?:next|следующее|ближайшее)\\s+(.+)$", RegexOption.IGNORE_CASE)
        val body = re.find(cmdRaw)?.groupValues?.get(1) ?: cmdRaw.substringAfter("next", "").trim()
        val tokens = body.split(Regex("[,;\\\\s]+")).map { it.trim() }.filter { it.isNotBlank() }
        val parsed = tokens.mapNotNull { t -> RuTimeUtils.parseDateTimeFlexible(t) }.filter { it.isAfter(LocalDateTime.now()) }
        if (parsed.isEmpty()) return listOf("Нет будущих событий в списке или не распознано.")
        val next = parsed.minByOrNull { it }!!
        return listOf("Ближайшее событие: ${RuTimeUtils.formatDateTimeNice(next)}")
    }

    private fun handleCountdown(cmdRaw: String): List<String> {
        val re = Regex("countdown\\s+(.+)$", RegexOption.IGNORE_CASE)
        val body = re.find(cmdRaw)?.groupValues?.get(1) ?: cmdRaw.substringAfter("countdown", "").trim()
        val dt = RuTimeUtils.parseDateTimeFlexible(body) ?: return listOf("Не распознать дату.")
        val now = LocalDateTime.now()
        val seconds = ChronoUnit.SECONDS.between(now, dt)
        if (seconds <= 0) return listOf("Уже прошло или сейчас.")
        return listOf("До события: ${RuTimeUtils.humanizeDurationSeconds(seconds)}")
    }

    private fun handleSplit(cmdRaw: String): List<String> {
        val re = Regex("split\\s+(.+?)\\s+(.+?)\\s+into\\s+(\\d+)", RegexOption.IGNORE_CASE)
        val m = re.find(cmdRaw)
        if (m != null) {
            val sraw = m.groupValues[1].trim()
            val eraw = m.groupValues[2].trim()
            val n = m.groupValues[3].toIntOrNull() ?: return listOf("Неверное число частей.")
            val s = RuTimeUtils.parseDateTimeFlexible(sraw) ?: return listOf("Не распознать start")
            val e = RuTimeUtils.parseDateTimeFlexible(eraw) ?: return listOf("Не распознать end")
            if (!e.isAfter(s)) return listOf("end должен быть позже start")
            val parts = RuTimeUtils.splitPeriod(s, e, n)
            val lines = mutableListOf<String>("Разбиение на $n частей:")
            parts.forEachIndexed { idx, pair ->
                lines.add(" ${idx + 1}: ${RuTimeUtils.formatDateTimeNice(pair.first)} — ${RuTimeUtils.formatDateTimeNice(pair.second)}")
            }
            return lines
        }
        return listOf("Пример: 'split 01.11.2025 00:00 01.12.2025 00:00 into 4'")
    }

    private fun handleCronNext(cmdRaw: String): List<String> {
        val re = Regex("cron\\s+(\\S+)\\s+(\\S+)\\s+\\*\\s+\\*\\s+(\\S+)\\s+next\\s+(\\d+)", RegexOption.IGNORE_CASE)
        val m = re.find(cmdRaw)
        if (m == null) return listOf("Не распознать cron. Пример: 'cron 0 9 * * 1-5 next 5'")
        val minuteTok = m.groupValues[1]
        val hourTok = m.groupValues[2]
        val dowTok = m.groupValues[3]
        val n = m.groupValues[4].toIntOrNull() ?: 5
        val minute = if (minuteTok == "*") null else minuteTok.toIntOrNull()
        val hour = if (hourTok == "*") null else hourTok.toIntOrNull()
        val dowSet = RuTimeUtils.expandDowRange(dowTok)
        if (dowSet.isEmpty()) return listOf("Не распознать дни недели в cron.")
        val now = LocalDateTime.now()
        val results = mutableListOf<LocalDateTime>()
        var cursor = now.plusMinutes(1)
        while (results.size < n && results.size < 5000) {
            val dayOk = dowSet.contains(cursor.dayOfWeek)
            val hourOk = hour == null || cursor.hour == hour
            val minOk = minute == null || cursor.minute == minute
            if (dayOk && hourOk && minOk) {
                results.add(cursor.withSecond(0).withNano(0))
            }
            cursor = cursor.plusMinutes(1)
        }
        if (results.isEmpty()) return listOf("Не удалось найти ближайшие cron-выполнения.")
        return results.map { "→ ${RuTimeUtils.formatDateTimeNice(it)}" }
    }
}

// --------------------
// RuTimeUtils:
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
        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm", Locale.ENGLISH),
        DateTimeFormatter.ofPattern("yyyy-MM-dd", Locale.ENGLISH),
        DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm", Locale.ENGLISH),
        DateTimeFormatter.ofPattern("dd.MM.yyyy", Locale.ENGLISH),
        DateTimeFormatter.ofPattern("d MMMM uuuu HH:mm", Locale("ru")),
        DateTimeFormatter.ofPattern("d MMMM uuuu", Locale("ru")),
        DateTimeFormatter.ISO_LOCAL_DATE_TIME,
        DateTimeFormatter.ISO_LOCAL_DATE
    )

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

    fun unitToSeconds(unit: String): Double {
        return when (unit) {
            "hours" -> 3600.0
            "days" -> 3600.0 * 24.0
            "weeks" -> 3600.0 * 24.0 * 7.0
            "months" -> 3600.0 * 24.0 * 30.0
            else -> 1.0
        }
    }

    fun parseDateTimeFlexible(raw: String): LocalDateTime? {
        val s = raw.trim()
        if (s.isBlank()) return null

        // genitive months
        val ruGenRe = Regex("\\b(\\d{1,2})\\s+(января|февраля|марта|апреля|мая|июня|июля|августа|сентября|октября|ноября|декабря)\\s+(\\d{4})(?:\\s+(\\d{1,2}):(\\d{2}))?", RegexOption.IGNORE_CASE)
        val g = ruGenRe.find(s)
        if (g != null) {
            val day = g.groupValues[1].toIntOrNull() ?: return null
            val monthName = g.groupValues[2].lowercase(Locale.getDefault())
            val month = ruMonthsGenitive[monthName] ?: return null
            val year = g.groupValues[3].toIntOrNull() ?: return null
            val hour = g.groupValues.getOrNull(4)?.toIntOrNull() ?: 0
            val minute = g.groupValues.getOrNull(5)?.toIntOrNull() ?: 0
            return try { LocalDateTime.of(year, month, day, hour, minute) } catch (_: Exception) { null }
        }

        // nominative months like "13 январь 2025"
        val ruNomRe = Regex("\\b(\\d{1,2})\\s+(январь|февраль|март|апрель|май|июнь|июль|август|сентябрь|октябрь|ноябрь|декабрь)\\s+(\\d{4})(?:\\s+(\\d{1,2}):(\\d{2}))?", RegexOption.IGNORE_CASE)
        val n = ruNomRe.find(s)
        if (n != null) {
            val day = n.groupValues[1].toIntOrNull() ?: return null
            val monthName = n.groupValues[2].lowercase(Locale.getDefault())
            val month = ruMonthsNominative[monthName] ?: return null
            val year = n.groupValues[3].toIntOrNull() ?: return null
            val hour = n.groupValues.getOrNull(4)?.toIntOrNull() ?: 0
            val minute = n.groupValues.getOrNull(5)?.toIntOrNull() ?: 0
            return try { LocalDateTime.of(year, month, day, hour, minute) } catch (_: Exception) { null }
        }

        for (fmt in dtPatterns) {
            try {
                val tmp = fmt.parseBest(s, LocalDateTime::from, java.time.LocalDate::from)
                when (tmp) {
                    is LocalDateTime -> return tmp
                    is java.time.LocalDate -> return tmp.atStartOfDay()
                }
            } catch (_: Exception) { }
        }

        val altRe = Regex("\\b(\\d{1,2})[\\.\\/\\-](\\d{1,2})[\\.\\/\\-](\\d{2,4})(?:\\s+(\\d{1,2}):(\\d{2}))?\\b")
        val a = altRe.find(s)
        if (a != null) {
            val d = a.groupValues[1].toIntOrNull() ?: return null
            val m = a.groupValues[2].toIntOrNull() ?: return null
            val y = a.groupValues[3].toIntOrNull() ?: return null
            val year = if (y < 100) 2000 + y else y
            val hour = a.groupValues.getOrNull(4)?.toIntOrNull() ?: 0
            val min = a.groupValues.getOrNull(5)?.toIntOrNull() ?: 0
            return try { LocalDateTime.of(year, m, d, hour, min) } catch (_: Exception) { null }
        }

        val isoRe = Regex("\\b(\\d{4})-(\\d{1,2})-(\\d{1,2})(?:[T\\s](\\d{1,2}):(\\d{2}))?\\b")
        val iso = isoRe.find(s)
        if (iso != null) {
            val y = iso.groupValues[1].toIntOrNull() ?: return null
            val mo = iso.groupValues[2].toIntOrNull() ?: return null
            val d = iso.groupValues[3].toIntOrNull() ?: return null
            val hour = iso.groupValues.getOrNull(4)?.toIntOrNull() ?: 0
            val min = iso.groupValues.getOrNull(5)?.toIntOrNull() ?: 0
            return try { LocalDateTime.of(y, mo, d, hour, min) } catch (_: Exception) { null }
        }

        val lower = s.lowercase(Locale.getDefault())
        if (lower == "сегодня" || lower == "today") return LocalDate.now().atStartOfDay()
        if (lower == "завтра" || lower == "tomorrow") return LocalDate.now().plusDays(1).atStartOfDay()
        if (lower == "вчера" || lower == "yesterday") return LocalDate.now().minusDays(1).atStartOfDay()

        return null
    }

    fun formatDateTimeNice(dt: LocalDateTime): String {
        val fmt = DateTimeFormatter.ofPattern("d MMMM uuuu HH:mm", Locale("ru"))
        return dt.format(fmt)
    }

    fun businessDaysBetween(start: LocalDate, end: LocalDate): Int {
        var s = if (start.isBefore(end)) start else end
        val e = if (start.isBefore(end)) end else start
        var count = 0
        var cur = s
        while (!cur.isAfter(e)) {
            val dow = cur.dayOfWeek
            if (dow != DayOfWeek.SATURDAY && dow != DayOfWeek.SUNDAY) count++
            cur = cur.plusDays(1)
        }
        return count
    }

    fun addBusinessDays(start: LocalDate, delta: Int): LocalDate {
        var days = delta
        var cur = start
        val step = if (days >= 0) 1 else -1
        while (days != 0) {
            cur = cur.plusDays(step.toLong())
            val dow = cur.dayOfWeek
            if (dow != DayOfWeek.SATURDAY && dow != DayOfWeek.SUNDAY) {
                days -= step
            }
        }
        return cur
    }

    fun intervalOverlapSeconds(a1: LocalDateTime, a2: LocalDateTime, b1: LocalDateTime, b2: LocalDateTime): Long {
        val start = if (a1.isAfter(b1)) a1 else b1
        val end = if (a2.isBefore(b2)) a2 else b2
        return if (end.isAfter(start)) ChronoUnit.SECONDS.between(start, end) else 0L
    }

    fun humanizeDurationSeconds(totalSeconds: Long): String {
        var s = totalSeconds
        val days = s / 86400
        s %= 86400
        val hours = s / 3600
        s %= 3600
        val mins = s / 60
        val secs = s % 60
        val parts = mutableListOf<String>()
        if (days > 0) parts.add("$days ${plural(days, "день", "дня", "дней")}")
        if (hours > 0) parts.add("$hours ${plural(hours, "час", "часа", "часов")}")
        if (mins > 0) parts.add("$mins ${plural(mins, "минута", "минуты", "минут")}")
        if (secs > 0 && parts.isEmpty()) parts.add("$secs ${plural(secs, "секунда", "секунды", "секунд")}")
        return if (parts.isEmpty()) "0 сек" else parts.joinToString(" ")
    }

    private fun plural(n: Long, one: String, two: String, five: String): String {
        val nAbs = abs(n % 100)
        return if (nAbs in 11..19) five else when (nAbs % 10) {
            1L -> one
            2L, 3L, 4L -> two
            else -> five
        }
    }

    fun parseDurationToSeconds(token: String): Long? {
        val s = token.trim()
        if (s.matches(Regex("^\\d+$"))) return s.toLong()
        var total = 0L
        val re = Regex("(\\d+)\\s*(d|д|days|day|h|час|часов|m|min|мин|s|sec|сек)", RegexOption.IGNORE_CASE)
        var found = false
        re.findAll(s).forEach {
            found = true
            val num = it.groupValues[1].toLong()
            val unit = it.groupValues[2].lowercase(Locale.getDefault())
            total += when (unit) {
                "d", "д", "day", "days" -> num * 86400
                "h", "час", "часов" -> num * 3600
                "m", "min", "мин" -> num * 60
                else -> num
            }
        }
        return if (found) total else null
    }

    fun roundDateTime(dt: LocalDateTime, stepMinutes: Int, mode: String = "nearest"): LocalDateTime {
        val minute = dt.minute
        val remainder = minute % stepMinutes
        val lower = dt.minusMinutes(remainder.toLong()).withSecond(0).withNano(0)
        val upper = lower.plusMinutes(stepMinutes.toLong())
        return when (mode.lowercase(Locale.getDefault())) {
            "up" -> upper
            "down" -> lower
            else -> {
                val downDiff = ChronoUnit.MINUTES.between(lower, dt)
                val upDiff = ChronoUnit.MINUTES.between(dt, upper)
                if (upDiff < downDiff) upper else lower
            }
        }
    }

    fun parseWeekdayList(raw: String): List<DayOfWeek> {
        val tokens = raw.split(Regex("[,\\s]+")).map { it.trim().lowercase(Locale.getDefault()) }.filter { it.isNotBlank() }
        val out = mutableListOf<DayOfWeek>()
        for (t in tokens) {
            when {
                t.startsWith("mon") || t.startsWith("пн") || t.startsWith("пон") -> out.add(DayOfWeek.MONDAY)
                t.startsWith("tue") || t.startsWith("вт") || t.startsWith("втор") -> out.add(DayOfWeek.TUESDAY)
                t.startsWith("wed") || t.startsWith("ср") || t.startsWith("сред") -> out.add(DayOfWeek.WEDNESDAY)
                t.startsWith("thu") || t.startsWith("чт") || t.startsWith("чет") -> out.add(DayOfWeek.THURSDAY)
                t.startsWith("fri") || t.startsWith("пт") || t.startsWith("пят") -> out.add(DayOfWeek.FRIDAY)
                t.startsWith("sat") || t.startsWith("сб") -> out.add(DayOfWeek.SATURDAY)
                t.startsWith("sun") || t.startsWith("вс") -> out.add(DayOfWeek.SUNDAY)
                else -> {
                    if (t.startsWith("пон")) out.add(DayOfWeek.MONDAY)
                    if (t.startsWith("втор")) out.add(DayOfWeek.TUESDAY)
                    if (t.startsWith("сред")) out.add(DayOfWeek.WEDNESDAY)
                    if (t.startsWith("чет")) out.add(DayOfWeek.THURSDAY)
                    if (t.startsWith("пят")) out.add(DayOfWeek.FRIDAY)
                    if (t.startsWith("суб")) out.add(DayOfWeek.SATURDAY)
                    if (t.startsWith("вос")) out.add(DayOfWeek.SUNDAY)
                }
            }
        }
        return out.distinct()
    }

    fun nextOccurrenceFromWeekdays(now: LocalDateTime, weekdays: List<DayOfWeek>, hour: Int, minute: Int): LocalDateTime {
        var candidate = now.withHour(hour).withMinute(minute).withSecond(0).withNano(0)
        if (!candidate.isAfter(now)) candidate = candidate.plusDays(1)
        var iterations = 0
        while (iterations < 14) {
            if (weekdays.contains(candidate.dayOfWeek)) return candidate
            candidate = candidate.plusDays(1)
            iterations++
        }
        return candidate
    }

    fun splitPeriod(start: LocalDateTime, end: LocalDateTime, n: Int): List<Pair<LocalDateTime, LocalDateTime>> {
        val totalSeconds = ChronoUnit.SECONDS.between(start, end)
        val partSec = totalSeconds / n
        val res = mutableListOf<Pair<LocalDateTime, LocalDateTime>>()
        for (i in 0 until n) {
            val s = start.plusSeconds(partSec * i)
            val e = if (i == n - 1) end else start.plusSeconds(partSec * (i + 1))
            res.add(Pair(s, e))
        }
        return res
    }

    fun containsMonthName(s: String): Boolean {
        val lower = s.lowercase(Locale.getDefault())
        return ruMonthsGenitive.keys.any { lower.contains(it) } || ruMonthsNominative.keys.any { lower.contains(it) }
    }

    fun extractTwoDateTimes(s: String): List<String> {
        val parts = mutableListOf<String>()

        val ruGenRe = Regex("\\b\\d{1,2}\\s+(?:января|февраля|марта|апреля|мая|июня|июля|августа|сентября|октября|ноября|декабря)\\s+\\d{4}(?:\\s+\\d{1,2}:\\d{2})?", RegexOption.IGNORE_CASE)
        ruGenRe.findAll(s).forEach { parts.add(it.value) }

        val altRe = Regex("\\b\\d{1,2}[\\.\\/\\-]\\d{1,2}[\\.\\/\\-]\\d{2,4}(?:\\s+\\d{1,2}:\\d{2})?")
        altRe.findAll(s).forEach { parts.add(it.value) }

        val isoRe = Regex("\\b\\d{4}-\\d{1,2}-\\d{1,2}(?:[T\\s]\\d{1,2}:\\d{2})?")
        isoRe.findAll(s).forEach { parts.add(it.value) }

        if (parts.size >= 2) return listOf(parts[0], parts[1])

        val tokens = s.split(Regex("\\s+(?:и|and|to|—|-)\\s+"))
        val parsed = tokens.mapNotNull { t -> if (parseDateTimeFlexible(t) != null) t.trim() else null }
        if (parsed.size >= 2) return parsed.take(2)

        return emptyList()
    }

    fun expandDowRange(token: String): Set<DayOfWeek> {
        val t = token.trim().lowercase(Locale.getDefault())
        val set = mutableSetOf<DayOfWeek>()
        val numRange = Regex("(\\d+)-(\\d+)").find(t)
        if (numRange != null) {
            val a = numRange.groupValues[1].toIntOrNull() ?: return emptySet()
            val b = numRange.groupValues[2].toIntOrNull() ?: return emptySet()
            var from = a
            while (true) {
                val dow = when (from) {
                    0 -> DayOfWeek.SUNDAY
                    7 -> DayOfWeek.SUNDAY
                    else -> DayOfWeek.of(((from - 1) % 7) + 1)
                }
                set.add(dow)
                if (from == b) break
                from++
            }
            return set
        }

        val txtRange = Regex("([^\\s\\-]+)-([^\\s\\-]+)").find(t)
        if (txtRange != null) {
            val aRaw = txtRange.groupValues[1]
            val bRaw = txtRange.groupValues[2]
            val start = weekdayFromToken(aRaw) ?: return emptySet()
            val end = weekdayFromToken(bRaw) ?: return emptySet()
            var cur = start.value
            while (true) {
                set.add(DayOfWeek.of(cur))
                if (cur == end.value) break
                cur = if (cur == 7) 1 else cur + 1
            }
            return set
        }

        val items = t.split(Regex("[,\\s]+")).map { it.trim() }.filter { it.isNotBlank() }
        for (it in items) {
            val dow = weekdayFromToken(it)
            if (dow != null) set.add(dow)
        }
        return set
    }

    private fun weekdayFromToken(tok: String): DayOfWeek? {
        val t = tok.lowercase(Locale.getDefault())
        return when {
            t.matches(Regex("^\\d+$")) -> {
                val num = t.toInt()
                when (num) {
                    0 -> DayOfWeek.SUNDAY
                    7 -> DayOfWeek.SUNDAY
                    else -> DayOfWeek.of(((num - 1) % 7) + 1)
                }
            }
            t.startsWith("пн") || t.startsWith("pon") || t.startsWith("mon") -> DayOfWeek.MONDAY
            t.startsWith("вт") || t.startsWith("vto") || t.startsWith("tue") -> DayOfWeek.TUESDAY
            t.startsWith("ср") || t.startsWith("sre") || t.startsWith("wed") -> DayOfWeek.WEDNESDAY
            t.startsWith("чт") || t.startsWith("chet") || t.startsWith("thu") -> DayOfWeek.THURSDAY
            t.startsWith("пт") || t.startsWith("pt") || t.startsWith("fri") -> DayOfWeek.FRIDAY
            t.startsWith("сб") || t.startsWith("sat") -> DayOfWeek.SATURDAY
            t.startsWith("вс") || t.startsWith("vos") || t.startsWith("sun") -> DayOfWeek.SUNDAY
            else -> null
        }
    }
}
