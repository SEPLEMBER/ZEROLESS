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
import java.math.BigDecimal
import java.math.RoundingMode
import java.util.Locale
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.roundToLong

/**
 * Новый Activity — подробно делит одно временное количество на другое.
 * Примеры ввода:
 *  - "2 часа на 3 дня"
 *  - "2h / 3d"
 *  - "1.5 hours divided by 90 minutes"
 *  - "45 мин на 1.5 ч"
 */
class ChronoSplitterQActivity : AppCompatActivity() {

    private lateinit var messagesContainer: LinearLayout
    private lateinit var scrollView: ScrollView
    private lateinit var input: EditText

    private val bgColor = 0xFF0A0A0A.toInt()
    private val neonCyan = 0xFF00F5FF.toInt()
    private val userGray = 0xFFB0B0B0.toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // fullscreen (как в оригинале)
        window.setFlags(
            WindowManager.LayoutParams.FLAG_FULLSCREEN,
            WindowManager.LayoutParams.FLAG_FULLSCREEN
        )

        // сохраняем поведение блокировки скриншотов, если у вас есть prefs
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
                    handleInput(text)
                    input.text = Editable.Factory.getInstance().newEditable("")
                } else {
                    addAssistantLine("Введите выражение, например: '2 часа на 3 дня' или '2h / 3d'.")
                }
                true
            } else {
                false
            }
        }

        addSystemLine(
            "ChronoSplitterQ — делит одно время на другое и показывает подробные шаги.\n" +
                    "Примеры: '2 часа на 3 дня', '90 мин / 1.5 ч', '1.5h divided by 45min'."
        )
    }

    // -------------------------
    // Core logic: парсинг двух временных величин и подробный вывод
    // -------------------------
    private fun handleInput(text: String) {
        addUserLine("> $text")

        val input = text.replace(',', '.')
        val found = parseTwoTimeQuantities(input)
        if (found == null) {
            addAssistantLine(
                "Не удалось распознать два временных количества.\n" +
                        "Убедитесь, что указали два выражения с единицами (сек, мин, час, день, нед, мес, год).\n" +
                        "Примеры: '2 часа на 3 дня', '90 min / 1.5 h', '2h divided by 3d'."
            )
            return
        }

        val (rawA, aSeconds) = found.first
        val (rawB, bSeconds) = found.second

        // Детальные вычисления
        try {
            // show conversions
            addAssistantLine("Шаг 1 — конвертация в секунды:")
            addAssistantLine(" • Левое: $rawA = ${formatSecondsFull(aSeconds)} (${formatBytesLikeSeconds(aSeconds)})")
            addAssistantLine(" • Правое: $rawB = ${formatSecondsFull(bSeconds)} (${formatBytesLikeSeconds(bSeconds)})")

            // compute quotient = a / b
            val quotient = if (bSeconds == 0.0) Double.NaN else aSeconds / bSeconds
            // Use BigDecimal for stable rounding/representation
            val quotientBd = if (quotient.isFinite()) BigDecimal(quotient).setScale(9, RoundingMode.HALF_UP).stripTrailingZeros() else null

            addAssistantLine("Шаг 2 — деление (левое ÷ правое):")
            if (bSeconds == 0.0) {
                addAssistantLine(" • Делитель равен нулю — деление невозможно.")
            } else {
                // show fraction (aSeconds / bSeconds) as simplified fraction when integers
                val fraction = buildFractionString(aSeconds, bSeconds)
                addAssistantLine(" • Дробь: $fraction")
                addAssistantLine(" • Десятичный результат: ${quotientBd?.toPlainString() ?: quotient.toString()}")
                addAssistantLine(" • Процент: ${if (quotient.isFinite()) formatPercent(quotient) else "—"}")
            }

            // Reciprocal interpretations
            addAssistantLine("Шаг 3 — интерпретации результата:")
            if (quotient.isFinite()) {
                // how many left-units fit into right-unit? and vice versa
                val leftPerRight = quotient // left in terms of right
                val rightPerLeft = if (quotient != 0.0) 1.0 / quotient else Double.NaN
                addAssistantLine(" • Левое / Правое = ${prettyNumber(leftPerRight)} (то есть левое = ${prettyNumber(leftPerRight)} × правое)")
                if (rightPerLeft.isFinite()) {
                    addAssistantLine(" • Правое / Левое = ${prettyNumber(rightPerLeft)} (то есть правое = ${prettyNumber(rightPerLeft)} × левое)")
                } else {
                    addAssistantLine(" • Правое / Левое = — (деление на ноль или бесконечность)")
                }

                // show "на единицу" (например сколько часов в одном правом единичном)
                val leftExpressedInCommon = expressPairInFriendlyUnits(aSeconds, bSeconds)
                leftExpressedInCommon.forEach { addAssistantLine(" • $it") }
            }

            addAssistantLine("Готово.")
        } catch (t: Throwable) {
            addAssistantLine("Ошибка при вычислениях: ${t.message ?: t::class.java.simpleName}")
        }
    }

    // -------------------------
    // Parsing: находит первые два вхождения 'число + единица'
    // возвращает Pair( rawString, seconds )
    // -------------------------
    private fun parseTwoTimeQuantities(s: String): Pair<Pair<String, Double>, Pair<String, Double>>? {
        // regex: число (с точкой) и единица (рус/англ/сокращения)
        val unitPat = listOf(
            "секунд(?:а|ы)?", "сек", "s",
            "минут(?:а|ы)?", "мин", "m", "min",
            "час(?:а|ов)?", "ч", "h", "hr", "hour(?:s)?",
            "дн(?:я|ей|ь)?", "д", "day(?:s)?",
            "недел(?:я|и|ь)?", "нед", "w", "week(?:s)?",
            "месяц(?:а|ев)?", "мес", "mon(?:th)?", "months?",
            "год(?:а|ов)?", "г", "y", "yr", "year(?:s)?"
        ).joinToString("|")

        val regex = Regex("""(-?\d+(?:[.,]\d+)?)\s*(?:$unitPat)\b""", RegexOption.IGNORE_CASE)
        val matches = regex.findAll(s).toList()
        if (matches.size < 2) {
            // additionally support compact like "2h/3d" or "2h / 3d"
            val compact = Regex("""(-?\d+(?:[.,]\d+)?)(h|hr|ч|час|m|min|мин|s|sec|сек|d|day|д|дн|w|week|нед|y|yr|год|г)\s*[\/÷]\s*(-?\d+(?:[.,]\d+)?)(h|hr|ч|час|m|min|мин|s|sec|сек|d|day|д|дн|w|week|нед|y|yr|год|г)""", RegexOption.IGNORE_CASE)
            val c = compact.find(s)
            if (c != null) {
                val aNum = c.groupValues[1].replace(',', '.').toDoubleOrNull() ?: return null
                val aUnit = c.groupValues[2]
                val bNum = c.groupValues[3].replace(',', '.').toDoubleOrNull() ?: return null
                val bUnit = c.groupValues[4]
                val aSec = timeToSeconds(aNum, aUnit)
                val bSec = timeToSeconds(bNum, bUnit)
                return Pair("${aNum.toPlainString()} ${aUnit}" to aSec, "${bNum.toPlainString()} ${bUnit}" to bSec)
            }
            return null
        }

        val first = matches[0]
        val second = matches[1]

        val aNum = first.groupValues[1].replace(',', '.').toDoubleOrNull() ?: return null
        val aUnitRaw = extractUnitFromMatch(first.value)
        val bNum = second.groupValues[1].replace(',', '.').toDoubleOrNull() ?: return null
        val bUnitRaw = extractUnitFromMatch(second.value)

        val aSec = timeToSeconds(aNum, aUnitRaw)
        val bSec = timeToSeconds(bNum, bUnitRaw)

        val rawA = "${first.groupValues[1]} ${aUnitRaw}"
        val rawB = "${second.groupValues[1]} ${bUnitRaw}"
        return Pair(rawA to aSec, rawB to bSec)
    }

    // helper: extract unit token from matched substring like "2 часа" -> "часа" (last word)
    private fun extractUnitFromMatch(matched: String): String {
        val tokens = matched.trim().split("\\s+".toRegex())
        return if (tokens.size >= 2) tokens.last() else ""
    }

    // core: convert (value, unitString) -> seconds (Double)
    private fun timeToSeconds(value: Double, unitRaw: String): Double {
        val u = unitRaw.lowercase(Locale.getDefault())
        return when {
            u.matches(Regex(".*(сек|секунд|s|sec).*")) -> value * 1.0
            u.matches(Regex(".*(мин|минут|m|min).*")) -> value * 60.0
            u.matches(Regex(".*(час|ч|h|hr|hour).*")) -> value * 3600.0
            u.matches(Regex(".*(дн|день|дня|day|д).*")) -> value * 86400.0
            u.matches(Regex(".*(нед|недел|week|w).*")) -> value * 604800.0
            u.matches(Regex(".*(месяц|мес|mon|month).*")) -> value * 2592000.0 // 30*24*3600
            u.matches(Regex(".*(год|г|yr|year).*")) -> value * 31536000.0 // 365*24*3600
            else -> value * 1.0 // fallback assume seconds
        }
    }

    // -------------------------
    // Formatting helpers
    // -------------------------
    private fun formatSecondsFull(seconds: Double): String {
        // produce friendly breakdown like "1h 30m 0s"
        if (!seconds.isFinite()) return seconds.toString()
        var s = seconds.roundToLong()
        val sign = if (s < 0) { s = abs(s); "-" } else ""
        val days = s / 86400
        s %= 86400
        val hours = s / 3600
        s %= 3600
        val mins = s / 60
        val secs = s % 60
        val parts = mutableListOf<String>()
        if (days > 0) parts.add("${days}d")
        if (hours > 0) parts.add("${hours}h")
        if (mins > 0) parts.add("${mins}m")
        parts.add("${secs}s")
        return sign + parts.joinToString(" ")
    }

    // secondary friendly representation (e.g. "7200 s")
    private fun formatBytesLikeSeconds(seconds: Double): String {
        val bd = BigDecimal(seconds).setScale(3, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString()
        return "$bd s"
    }

    private fun formatPercent(ratio: Double): String {
        val bd = BigDecimal(ratio * 100.0).setScale(6, RoundingMode.HALF_UP).stripTrailingZeros()
        return bd.toPlainString() + " %"
    }

    private fun prettyNumber(d: Double): String {
        if (!d.isFinite()) return d.toString()
        val bd = BigDecimal(d).setScale(6, RoundingMode.HALF_UP).stripTrailingZeros()
        return bd.toPlainString()
    }

    // build a simple fraction string like "7200 / 259200 = 1 / 36" when possible
    private fun buildFractionString(aSec: Double, bSec: Double): String {
        if (!aSec.isFinite() || !bSec.isFinite() || bSec == 0.0) return "${aSec.toString()} / ${bSec.toString()}"
        // try to use integer seconds if both are close to integers
        val aInt = aSec.roundToLong()
        val bInt = bSec.roundToLong()
        val useInts = (abs(aSec - aInt) < 1e-6) && (abs(bSec - bInt) < 1e-6)
        return if (useInts && bInt != 0L) {
            val g = gcd(aInt, bInt)
            "${aInt} / ${bInt} = ${aInt / g} / ${bInt / g}"
        } else {
            // use rational approximation
            val bdA = BigDecimal(aSec).setScale(9, RoundingMode.HALF_UP)
            val bdB = BigDecimal(bSec).setScale(9, RoundingMode.HALF_UP)
            "${bdA.stripTrailingZeros().toPlainString()} / ${bdB.stripTrailingZeros().toPlainString()}"
        }
    }

    private fun gcd(a: Long, b: Long): Long {
        var x = abs(a)
        var y = abs(b)
        if (x == 0L) return y
        if (y == 0L) return x
        while (y != 0L) {
            val t = x % y
            x = y
            y = t
        }
        return x
    }

    // express pair in friendly sentences: e.g. "2h is 0.0277 of 3d", "that's X hours per day"
    private fun expressPairInFriendlyUnits(aSec: Double, bSec: Double): List<String> {
        val lines = mutableListOf<String>()
        if (!aSec.isFinite() || !bSec.isFinite() || bSec == 0.0) return lines
        val ratio = aSec / bSec
        // if right is a multiple of day, show left in per-day units relative to right
        // show left in hours:
        val aHours = aSec / 3600.0
        val bHours = bSec / 3600.0
        lines.add("Левое в часах: ${prettyNumber(aHours)} h, Правое в часах: ${prettyNumber(bHours)} h")
        // show how many left per 1 right (ratio) and how many left per 24h etc
        lines.add("Левое = ${prettyNumber(ratio)} × Правое")
        // show left as percentage of right
        lines.add("Левое составляет ${formatPercent(ratio)} от Правого")
        // show per-day rate: (aSec / bSec) * 1 правого в часах? also compute 'левое в единице времени' like hours per day: (aSec / bSec) * (rightUnitLengthInDays?) — simpler:
        // compute "если правое = 1 день, сколько левое в часах/мин/сек"
        val leftPerOneRightSec = aSec / bSec // unitless
        val leftIfRightWasOneDaySeconds = leftPerOneRightSec * 86400.0
        lines.add("Если правое = 1 day, то левое = ${formatSecondsFull(leftIfRightWasOneDaySeconds)} (то есть ${prettyNumber(leftIfRightWasOneDaySeconds / 3600.0)} h)")
        return lines
    }

    // -------------------------
    // UI helpers (как в шаблоне)
    // -------------------------
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

    // small helper to format Double -> plain string without scientific notation when not huge
    private fun Double.toPlainString(): String {
        val bd = BigDecimal(this).setScale(9, RoundingMode.HALF_UP).stripTrailingZeros()
        return bd.toPlainString()
    }
}
