package com.nemesis.pawstribe

import android.graphics.Typeface
import android.os.Bundle
import android.text.Editable
import android.text.InputType
import android.text.method.ScrollingMovementMethod
import android.view.KeyEvent
import android.view.View
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
import java.math.BigDecimal
import java.math.RoundingMode
import java.security.SecureRandom
import kotlin.math.ln
import kotlin.math.pow

/**
 * VprActivity — минималистичный текстовый ассистент в одном Activity.
 *
 * Package: com.nemesis.pawstribe
 *
 * UI: activity_vpr.xml (ScrollView -> LinearLayout messagesContainer + EditText input)
 *
 * Цвета: фон #0A0A0A, текст неон-циан #00F5FF (строки создаются программно)
 *
 * Команды (примеры):
 *  - "энтропия привет как дела"
 *  - "трафик 5gb"
 *  - "простые проценты 100000 7% 3 года"
 *  - "сложные проценты 100000 7% 3 года помесячно"
 *  - "месячный доход 120000 рабочие 8"
 *
 * Никаких сторонних библиотек, никакого RecyclerView.
 */
class VprActivity : AppCompatActivity() {

    // UI references
    private lateinit var messagesContainer: LinearLayout
    private lateinit var scrollView: ScrollView
    private lateinit var input: EditText

    // Styling constants
    private val bgColor = 0xFF0A0A0A.toInt()
    private val neonCyan = 0xFF00F5FF.toInt()
    private val userGray = 0xFFB0B0B0.toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_vpr)

        messagesContainer = findViewById(R.id.messagesContainer)
        scrollView = findViewById(R.id.scrollView)
        input = findViewById(R.id.inputEditText)

        window.decorView.setBackgroundColor(bgColor)

        // Input behaviour: IME_ACTION_DONE submits command
        input.imeOptions = EditorInfo.IME_ACTION_DONE
        input.setRawInputType(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS)
        input.setOnEditorActionListener { _, actionId, event ->
            if (actionId == EditorInfo.IME_ACTION_DONE || (event?.keyCode == KeyEvent.KEYCODE_ENTER)) {
                val text = input.text.toString().trim()
                if (text.isNotEmpty()) {
                    submitCommand(text)
                    input.text = Editable.Factory.getInstance().newEditable("")
                }
                true
            } else {
                false
            }
        }

        // seed welcome
        addSystemLine("Offline Assistant — режим оффлайн. Введите команду (напр.: \"простые проценты 100000 7% 3 года\")")
    }

    // Add user command view and process (with try/catch to prevent crashes)
    private fun submitCommand(command: String) {
        addUserLine("> $command")
        lifecycleScope.launch(Dispatchers.Default) {
            try {
                val outputs = parseCommand(command)
                withContext(Dispatchers.Main) {
                    outputs.forEach { addAssistantLine("= $it") }
                    // auto scroll to bottom
                    scrollView.post { scrollView.fullScroll(View.FOCUS_DOWN) }
                }
            } catch (t: Throwable) {
                // Показываем ошибку как сообщение ассистента — приложение не падает
                withContext(Dispatchers.Main) {
                    addAssistantLine("= Ошибка при обработке команды: ${t.message ?: t::class.java.simpleName}")
                    scrollView.post { scrollView.fullScroll(View.FOCUS_DOWN) }
                }
            }
        }
    }

    // Append a user text line
    private fun addUserLine(text: String) {
        val tv = TextView(this).apply {
            this.text = text
            setTextColor(userGray)
            setPadding(14)
            textSize = 14f
            typeface = Typeface.MONOSPACE
        }
        messagesContainer.addView(tv)
        scrollView.post { scrollView.fullScroll(View.FOCUS_DOWN) }
    }

    // Append an assistant/result line
    private fun addAssistantLine(text: String) {
        val tv = TextView(this).apply {
            this.text = text
            setTextColor(neonCyan)
            setPadding(14)
            textSize = 15f
            typeface = Typeface.MONOSPACE
            // enable multi-line scrolling in case of big result
            movementMethod = ScrollingMovementMethod()
        }
        messagesContainer.addView(tv)
        scrollView.post { scrollView.fullScroll(View.FOCUS_DOWN) }
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
        scrollView.post { scrollView.fullScroll(View.FOCUS_DOWN) }
    }

    /**
     * Простая маршрутизация команд -> возвращает список строк-ответов
     */
    private suspend fun parseCommand(commandRaw: String): List<String> {
        val cmd = commandRaw.trim()
        val lower = cmd.lowercase()

        // Энтропия: "энтропия <...>"
        if (lower.startsWith("энтроп") || lower.startsWith("эн")) {
            val payload = cmd.substringAfter(' ').trim()
            if (payload.isEmpty()) return listOf("Использование: энтропия <текст>")
            val entropy = shannonEntropyString(payload)
            return listOf("Энтропия (Shannon) строки: ${"%.4f".format(entropy)} бит/символ")
        }

        // Трафик: "трафик 5gb" или просто "5gb трафик"
        if (lower.contains("трафик") || Regex("""\d+\s*(gb|гб|mb|мб|kb|кб|b|байт)""").containsMatchIn(lower)) {
            // try to find number + optional unit
            val m = Regex("""(\d+(?:[.,]\d+)?)\s*(gb|гб|mb|мб|kb|кб|b|байт)?""", RegexOption.IGNORE_CASE).find(lower)
            if (m == null) return listOf("Не могу распознать объём. Пример: 'трафик 5gb'")
            val numStr = m.groupValues[1].replace(',', '.')
            val unit = m.groupValues.getOrNull(2) ?: ""
            val bytes = parseBytes(numStr.toDouble(), unit)
            val rates = bytesPerMonthToRates(bytes)
            return listOf(
                "${m.value.trim()} ≈ ${formatBytes(bytes)} / мес",
                "B/day: ${"%.2f".format(rates["B/day"])}",
                "B/hour: ${"%.2f".format(rates["B/hour"])}",
                "B/min: ${"%.2f".format(rates["B/min"])}",
                "B/sec: ${"%.4f".format(rates["B/s"])}",
                "bit/s: ${"%.4f".format(rates["bit/s"])}"
            )
        }

        // Простые проценты: "простые проценты 100000 7% 3 года"
        if (lower.contains("прост") && lower.contains("процент")) {
            // ищем первую сумму
            val numMatch = Regex("""(\d+(?:[.,]\d+)?)""").find(cmd)
            if (numMatch == null) return listOf("Использование: простые проценты <сумма> <ставка%> [время]")
            val principal = try {
                BigDecimal(numMatch.groupValues[1].replace(',', '.'))
            } catch (e: Exception) {
                return listOf("Невалидная сумма: ${numMatch.groupValues[1]}")
            }

            // ищем процент (число со знаком %)
            val rateMatch = Regex("""(\d+(?:[.,]\d+)?)\s*%""").find(cmd)
            val rate = if (rateMatch != null) {
                parsePercent(rateMatch.groupValues[0])
            } else {
                // fallback: следующая числовая токен после суммы
                val rest = cmd.substring(numMatch.range.last + 1)
                val nextNum = Regex("""(\d+(?:[.,]\d+)?)""").find(rest)
                if (nextNum != null) parsePercent(nextNum.groupValues[1]) else BigDecimal.ZERO
            }

            val timeYears = parseTimeToYears(cmd)
            return listOfNotNull(simpleInterestReport(principal, rate, timeYears))
        }

        // Сложные проценты: "сложные проценты 100000 7% 3 года помесячно"
        if (lower.contains("слож") && lower.contains("процент")) {
            val numMatch = Regex("""(\d+(?:[.,]\d+)?)""").find(cmd)
            if (numMatch == null) return listOf("Использование: сложные проценты <сумма> <ставка%> [время] [помесячно/посуточно/годовая]")
            val principal = try {
                BigDecimal(numMatch.groupValues[1].replace(',', '.'))
            } catch (e: Exception) {
                return listOf("Невалидная сумма: ${numMatch.groupValues[1]}")
            }

            val rateMatch = Regex("""(\d+(?:[.,]\d+)?)\s*%""").find(cmd)
            val rate = if (rateMatch != null) parsePercent(rateMatch.groupValues[0]) else BigDecimal.ZERO

            val timeYears = parseTimeToYears(cmd)
            val capitalization = when {
                cmd.contains("помесяч", ignoreCase = true) || cmd.contains("monthly", ignoreCase = true) -> "monthly"
                cmd.contains("посуточ", ignoreCase = true) || cmd.contains("daily", ignoreCase = true) -> "daily"
                cmd.contains("год", ignoreCase = true) || cmd.contains("year", ignoreCase = true) -> "yearly"
                else -> "monthly"
            }
            return listOfNotNull(compoundInterestReport(principal, rate, timeYears, capitalization))
        }

        // Месячный доход/расход: "месячный доход 120000 рабочие 8"
        if (lower.contains("месяч") && (lower.contains("доход") || lower.contains("зарп") || lower.contains("расход"))) {
            // ищем сумму в тексте
            val numMatch = Regex("""(\d+(?:[.,]\d+)?)""").find(cmd)
            if (numMatch == null) return listOf("Использование: месячный доход <сумма> [рабочие <hours>]")
            val amount = try {
                BigDecimal(numMatch.groupValues[1].replace(',', '.'))
            } catch (e: Exception) {
                return listOf("Невалидная сумма: ${numMatch.groupValues[1]}")
            }
            // find working hours in the command (pattern "рабочие X")
            val workHoursMatch = Regex("""рабоч\w*\s+(\d{1,2})""", RegexOption.IGNORE_CASE).find(cmd)
            val workHours = workHoursMatch?.groupValues?.get(1)?.toIntOrNull() ?: 8
            return listOfNotNull(monthlyToRatesReport(amount, workHours))
        }

        // fallback
        return listOf("Не распознанная команда. Поддерживаемые: энтропия, трафик, простые проценты, сложные проценты, месячный доход.")
    }

    // --------------------
    // Helpers: parsing + calculations
    // --------------------

    private fun parseBytes(amount: Double, unitRaw: String?): Long {
        val unit = unitRaw?.lowercase() ?: ""
        return when (unit) {
            "gb", "гб" -> (amount * 1024.0 * 1024.0 * 1024.0).toLong()
            "mb", "мб" -> (amount * 1024.0 * 1024.0).toLong()
            "kb", "кб" -> (amount * 1024.0).toLong()
            "b", "байт" -> amount.toLong()
            "" -> (amount).toLong() // assume bytes if no unit
            else -> (amount).toLong()
        }
    }

    private fun formatBytes(bytes: Long): String {
        val kb = 1024.0
        val mb = kb * 1024.0
        val gb = mb * 1024.0
        return when {
            bytes >= gb -> String.format("%.2f GB", bytes / gb)
            bytes >= mb -> String.format("%.2f MB", bytes / mb)
            bytes >= kb -> String.format("%.2f KB", bytes / kb)
            else -> "$bytes B"
        }
    }

    private fun bytesPerMonthToRates(bytesPerMonth: Long, daysInMonth: Int = 30): Map<String, Double> {
        val bMonth = bytesPerMonth.toDouble()
        val bDay = bMonth / daysInMonth
        val bHour = bDay / 24.0
        val bMin = bHour / 60.0
        val bSec = bMin / 60.0
        val bitsPerSec = bSec * 8.0
        return mapOf("B/day" to bDay, "B/hour" to bHour, "B/min" to bMin, "B/s" to bSec, "bit/s" to bitsPerSec)
    }

    // tokenize numbers and keep words: returns list of tokens that look like numbers or words
    private fun tokenizeNumbersAndUnits(cmd: String): List<String> {
        // crude: split by spaces, but keep punctuation removed
        return cmd.split(Regex("\\s+")).map { it.trim().trim(',', ';') }.filter { it.isNotEmpty() }
    }

    private fun parsePercent(s: String): BigDecimal {
        val cleaned = s.replace("%", "").replace(',', '.').trim()
        return try {
            BigDecimal(cleaned).divide(BigDecimal(100))
        } catch (e: Exception) {
            BigDecimal.ZERO
        }
    }

    // parse time expressions like "3 года", "18 месяцев", "2.5 года", "90 дней"
    // returns time in years (Double)
    private fun parseTimeToYears(tail: String): Double {
        if (tail.isBlank()) return 1.0 // default 1 year when no time specified
        val lower = tail.lowercase()
        // years
        val y = Regex("""(\d+(?:[.,]\d+)?)\s*(лет|года|год|year|y)""").find(lower)
        if (y != null) {
            val num = y.groupValues[1].replace(',', '.').toDoubleOrNull() ?: 1.0
            return num
        }
        val m = Regex("""(\d+(?:[.,]\d+)?)\s*(месяц|месяцев|мес|month)""").find(lower)
        if (m != null) {
            val num = m.groupValues[1].replace(',', '.').toDoubleOrNull() ?: 0.0
            return num / 12.0
        }
        val d = Regex("""(\d+(?:[.,]\d+)?)\s*(дн|дня|дней|day)""").find(lower)
        if (d != null) {
            val num = d.groupValues[1].replace(',', '.').toDoubleOrNull() ?: 0.0
            return num / 365.0
        }
        // if tail contains single number — assume years
        val numOnly = Regex("""^(\d+(?:[.,]\d+)?)$""").find(lower)
        if (numOnly != null) {
            return numOnly.groupValues[1].replace(',', '.').toDoubleOrNull() ?: 1.0
        }
        // fallback default 1 year
        return 1.0
    }

    // Simple interest report: returns formatted string
    private fun simpleInterestReport(principal: BigDecimal, annualRate: BigDecimal, years: Double): String {
        // Interest = P * r * t
        val mcScale = 10
        val principalMC = principal
        val rateBD = BigDecimal(annualRate.toPlainString())
        val yearsBD = BigDecimal.valueOf(years)
        val interest = principalMC.multiply(rateBD).multiply(yearsBD)
        val total = principalMC.add(interest)
        // yearly profit
        val yearlyProfit = principalMC.multiply(rateBD)
        val perMonth = yearlyProfit.divide(BigDecimal(12), 10, RoundingMode.HALF_UP)
        val perDay = yearlyProfit.divide(BigDecimal(365), 10, RoundingMode.HALF_UP)
        val perHour = perDay.divide(BigDecimal(24), 10, RoundingMode.HALF_UP)
        return buildString {
            append("Простой процент:\n")
            append("Вложено: ${principalMC.setScale(2, RoundingMode.HALF_UP)}\n")
            append("Годовая ставка: ${(annualRate.multiply(BigDecimal(100))).setScale(4, RoundingMode.HALF_UP)}%\n")
            append("Период: ${"%.4f".format(years)} лет\n")
            append("Итого через период: ${total.setScale(2, RoundingMode.HALF_UP)} (прибыль ${interest.setScale(2, RoundingMode.HALF_UP)})\n")
            append("Прибыль в год (средн.): ${yearlyProfit.setScale(2, RoundingMode.HALF_UP)}\n")
            append("в месяц: ${perMonth.setScale(2, RoundingMode.HALF_UP)}, в день: ${perDay.setScale(4, RoundingMode.HALF_UP)}, в час: ${perHour.setScale(6, RoundingMode.HALF_UP)}")
        }
    }

    // Compound interest report. capitalization: "monthly" / "daily" / "yearly"
    private fun compoundInterestReport(principal: BigDecimal, annualRate: BigDecimal, years: Double, capitalization: String): String {
        val n = when (capitalization) {
            "monthly" -> 12
            "daily" -> 365
            "yearly" -> 1
            else -> 12
        }
        // formula: A = P * (1 + r/n)^(n*t)
        // Use Double pow for fractional years, then convert to BigDecimal
        val p = principal.toDouble()
        val r = annualRate.toDouble()
        val factor = (1.0 + r / n.toDouble()).pow(n.toDouble() * years)
        val amountDouble = p * factor
        val amount = BigDecimal.valueOf(amountDouble).setScale(10, RoundingMode.HALF_UP)
        val totalGain = amount.subtract(principal)
        val avgPerYear = if (years > 0.0) totalGain.divide(BigDecimal.valueOf(years), 10, RoundingMode.HALF_UP) else BigDecimal.ZERO
        val perMonth = avgPerYear.divide(BigDecimal(12), 10, RoundingMode.HALF_UP)
        val perDay = avgPerYear.divide(BigDecimal(365), 10, RoundingMode.HALF_UP)
        val perHour = perDay.divide(BigDecimal(24), 10, RoundingMode.HALF_UP)

        return buildString {
            append("Сложные проценты (${capitalization}):\n")
            append("Вложено: ${principal.setScale(2, RoundingMode.HALF_UP)}\n")
            append("Годовая ставка: ${(annualRate.multiply(BigDecimal(100))).setScale(4, RoundingMode.HALF_UP)}%\n")
            append("Период: ${"%.4f".format(years)} лет\n")
            append("Итоговая сумма: ${amount.setScale(2, RoundingMode.HALF_UP)} (прибыль ${totalGain.setScale(2, RoundingMode.HALF_UP)})\n")
            append("Средняя прибыль в год: ${avgPerYear.setScale(2, RoundingMode.HALF_UP)}\n")
            append("в месяц: ${perMonth.setScale(2, RoundingMode.HALF_UP)}, в день: ${perDay.setScale(4, RoundingMode.HALF_UP)}, в час: ${perHour.setScale(6, RoundingMode.HALF_UP)}")
        }
    }

    private fun monthlyToRatesReport(monthly: BigDecimal, workingHours: Int): String {
        val mc = 10
        val yearly = monthly.multiply(BigDecimal(12))
        val perDay = monthly.divide(BigDecimal(30), mc, RoundingMode.HALF_UP)
        val per24Hour = perDay.divide(BigDecimal(24), mc, RoundingMode.HALF_UP)
        val perWorkHour = if (workingHours > 0) perDay.divide(BigDecimal(workingHours), mc, RoundingMode.HALF_UP) else BigDecimal.ZERO
        return buildString {
            append("Месячная сумма: ${monthly.setScale(2, RoundingMode.HALF_UP)}\n")
            append("Год: ${yearly.setScale(2, RoundingMode.HALF_UP)}\n")
            append("День (30d): ${perDay.setScale(2, RoundingMode.HALF_UP)}\n")
            append("В час (24/сут): ${per24Hour.setScale(2, RoundingMode.HALF_UP)}\n")
            append("В час (рабочие $workingHours): ${perWorkHour.setScale(2, RoundingMode.HALF_UP)}")
        }
    }

    // Shannon entropy in bits per symbol for a UTF-8 string
    private fun shannonEntropyString(s: String): Double {
        val bytes = s.toByteArray(Charsets.UTF_8)
        if (bytes.isEmpty()) return 0.0
        val freq = IntArray(256)
        for (b in bytes) {
            freq[b.toInt() and 0xFF]++
        }
        val len = bytes.size.toDouble()
        var entropy = 0.0
        for (c in freq) {
            if (c == 0) continue
            val p = c / len
            entropy -= p * (ln(p) / ln(2.0))
        }
        return entropy
    }

    // Utility: generate random bytes (for quick entropy test) - not used by default but kept
    private fun randomBytes(size: Int): ByteArray {
        val rnd = SecureRandom()
        val arr = ByteArray(size)
        rnd.nextBytes(arr)
        return arr
    }
}
