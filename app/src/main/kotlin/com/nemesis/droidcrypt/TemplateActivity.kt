package com.nemesis.droidcrypt

import android.content.Intent
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
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
import java.security.SecureRandom
import java.time.LocalDate
import java.time.Month
import java.time.temporal.ChronoUnit
import java.util.Locale
import kotlin.math.ln
import kotlin.math.pow
import java.math.BigDecimal
import java.math.MathContext
import java.math.RoundingMode

class TemplateActivity : AppCompatActivity() {

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

        setContentView(R.layout.activity_vpr)

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

        addSystemLine("Шаблон: в модулей есть простые команды. Help обрабатывается в самом низком приоритете.")
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
            val mainRes = CommandsMain.handleCommand(cmd)
            if (mainRes.isNotEmpty()) return mainRes
        } catch (_: Exception) { }

        try {
            val v2Res = CommandsV2.handleCommand(cmd)
            if (v2Res.isNotEmpty()) return v2Res
        } catch (_: Exception) { }

        try {
            val v3Res = CommandsV3.handleCommand(cmd)
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

    private fun randomBytes(size: Int): ByteArray {
        val rnd = SecureRandom()
        val arr = ByteArray(size)
        rnd.nextBytes(arr)
        return arr
    }
}

private object CommandsMain {

    fun handleCommand(cmdRaw: String): List<String> {
        val cmd = cmdRaw.trim()
        val lower = cmd.lowercase(Locale.getDefault())

        if (lower.contains("налог") || lower.contains("ндфл") || lower.contains("tax")) {
            return handleTax(cmd)
        }
        if (lower.contains("накоп") || lower.contains("накопить") || lower.contains("savings")) {
            return handleSavings(cmd)
        }
        if (lower.contains("pmt") || lower.contains("плт") || lower.contains("платеж") || lower.contains("платёж")) {
            return handlePmt(cmd)
        }

        return emptyList()
    }

    private fun handleTax(cmd: String): List<String> {
        val nums = Regex("""(\d+(?:[.,]\d+)?)""").findAll(cmd).map { it.groupValues[1].replace(',', '.') }.toList()
        val amount = nums.firstOrNull()?.toDoubleOrNull() ?: return listOf("Налог: укажите сумму. Пример: 'налог 100000 13%'")
        val perc = Regex("""(\d+(?:[.,]\d+)?)\s*%""").find(cmd)?.groupValues?.get(1)?.replace(',', '.')?.toDoubleOrNull() ?: 13.0
        val rate = perc / 100.0
        val tax = amount * rate
        val net = amount - tax
        return listOf("Сумма: ${formatMoney(amount)}", "Ставка: ${"%.2f".format(perc)}%", "Налог: ${formatMoney(tax)}", "После налога: ${formatMoney(net)}")
    }

    private fun handleSavings(cmd: String): List<String> {
        val nums = Regex("""(\d+(?:[.,]\d+)?)""").findAll(cmd).map { it.groupValues[1].replace(',', '.') }.toList()
        val amount = nums.firstOrNull()?.toDoubleOrNull() ?: return listOf("Накопить: укажите сумму. Пример: 'накопить 300000 на 180 дней под 7%'")
        val days = parseDaysFromText(cmd) ?: (parseTimeYearsFromText(cmd) * 365.0).toInt().coerceAtLeast(30)
        val perc = Regex("""(\d+(?:[.,]\d+)?)\s*%""").find(cmd)?.groupValues?.get(1)?.replace(',', '.')?.toDoubleOrNull() ?: 0.0

        val months = kotlin.math.max(1, Math.round(days / 30.0).toInt())

        if (perc == 0.0) {
            val perDay = amount / days
            val perWeek = perDay * 7.0
            val perMonth = amount / months.toDouble()
            return listOf(
                "Цель: ${formatMoney(amount)}",
                "Период: $days дней (~${"%.2f".format(months / 12.0)} лет)",
                "Без процентов: в день ${formatMoney(perDay)} ₽, в неделю ${formatMoney(perWeek)} ₽, в месяц ≈ ${formatMoney(perMonth)} ₽"
            )
        } else {
            val pm = pmtForFutureValue(amount, perc / 100.0, months)
            val perDay = pm / 30.0
            val perWeek = perDay * 7.0
            return listOf(
                "Цель: ${formatMoney(amount)}",
                "Период: $days дней (~$months мес)",
                "Учитывая ${"%.4f".format((perc))}% годовых (помесячная кап.):",
                "Нужно ежемесячно: ${formatMoney(pm)} ₽ (≈ в день ${formatMoney(perDay)} ₽, в неделю ${formatMoney(perWeek)} ₽)"
            )
        }
    }

    private fun handlePmt(cmd: String): List<String> {
        val nums = Regex("""(-?\d+(?:[.,]\d+)?)""").findAll(cmd).map { it.groupValues[1].replace(',', '.') }.toList()
        if (nums.isEmpty()) return listOf("PMT: укажите сумму кредита. Пример: 'pmt 150000 9% 15 лет'")
        val principal = nums[0].toDoubleOrNull() ?: return listOf("PMT: не удалось распознать сумму.")
        val perc = Regex("""(\d+(?:[.,]\d+)?)\s*%""").find(cmd)?.groupValues?.get(1)?.replace(',', '.')?.toDoubleOrNull() ?: 0.0
        val years = parseTimeYearsFromText(cmd)
        val months = kotlin.math.max(1, (years * 12.0).toInt())
        val pmt = pmtAnnuity(principal, perc / 100.0, months)
        val total = pmt * months
        val overpay = total - principal
        val perYear = (total - principal) / years
        val perMonthAvg = perYear / 12.0
        val perDayAvg = perYear / 365.0
        val perHourAvg = perDayAvg / 24.0

        val out = mutableListOf<String>()
        out.add("Аннуитетный платёж: ${formatMoney(pmt)} ₽/мес")
        out.add("Срок: $months мес (~${"%.2f".format(years)} лет)")
        out.add("Общая выплата: ${formatMoney(total)} ₽ (переплата ${formatMoney(overpay)} ₽)")
        out.add("Переплата в среднем: в год ${formatMoney(perYear)} ₽, в месяц ${formatMoney(perMonthAvg)} ₽, в день ${String.format("%.4f", perDayAvg)} ₽, в час ${String.format("%.6f", perHourAvg)} ₽")
        out.add("Первые 6 месяцев амортизации:")
        out.addAll(amortizationAnnuity(principal, perc / 100.0, months, 6))
        return out
    }

    private fun parseDaysFromText(textRaw: String): Int? {
        val text = textRaw.lowercase()
        val explicitDays = Regex("""\b(?:на|за)?\s*(\d{1,4})\s*(дн|дня|дней)\b""").find(text)
        if (explicitDays != null) return explicitDays.groupValues[1].toIntOrNull()?.coerceAtLeast(1)
        val weeks = Regex("""\b(?:на|за)?\s*(\d{1,3})?\s*(недел|нед)\b""").find(text)
        if (weeks != null) {
            val maybeNum = weeks.groupValues[1]
            val num = if (maybeNum.isBlank()) 1 else maybeNum.toIntOrNull() ?: 1
            return (num * 7).coerceAtLeast(1)
        }
        return null
    }

    private fun parseTimeYearsFromText(cmd: String): Double {
        val yearMatch = Regex("""(\d+(?:[.,]\d+)?)\s*(лет|год|года)""", RegexOption.IGNORE_CASE).find(cmd)
        if (yearMatch != null) return yearMatch.groupValues[1].replace(',', '.').toDoubleOrNull() ?: 1.0
        val monthMatch = Regex("""(\d+(?:[.,]\d+)?)\s*(мес|месяц|месяцев)""", RegexOption.IGNORE_CASE).find(cmd)
        if (monthMatch != null) return (monthMatch.groupValues[1].replace(',', '.').toDoubleOrNull() ?: 1.0) / 12.0
        val numOnly = Regex("""\b(\d+(?:[.,]\d+)?)\b""").find(cmd)
        if (numOnly != null) return numOnly.groupValues[1].replace(',', '.').toDoubleOrNull() ?: 1.0
        return 1.0
    }

    private fun pmtAnnuity(principal: Double, annualRate: Double, months: Int): Double {
        if (months <= 0) return principal
        val rMonth = annualRate / 12.0
        if (rMonth == 0.0) return principal / months
        val factor = Math.pow(1.0 + rMonth, months.toDouble())
        return principal * rMonth * factor / (factor - 1.0)
    }

    private fun amortizationAnnuity(principal: Double, annualRate: Double, months: Int, showFirst: Int): List<String> {
        val out = mutableListOf<String>()
        var remaining = principal
        val pmt = pmtAnnuity(principal, annualRate, months)
        val rMonth = annualRate / 12.0
        val maxShow = showFirst.coerceAtMost(months)
        for (i in 1..maxShow) {
            val interest = remaining * rMonth
            var principalPaid = pmt - interest
            if (i == months) principalPaid = remaining
            remaining -= principalPaid
            if (remaining < 0) remaining = 0.0
            out.add(String.format(Locale.getDefault(), "Мес %d: платёж %s, проценты %s, погашение %s, остаток %s",
                i,
                formatMoney(pmt),
                formatMoney(interest),
                formatMoney(principalPaid),
                formatMoney(remaining)
            ))
            if (remaining <= 0.0) break
        }
        return out
    }

    private fun pmtForFutureValue(futureValue: Double, annualRate: Double, months: Int): Double {
        if (months <= 0) return futureValue
        val r = annualRate / 12.0
        if (r == 0.0) return futureValue / months
        val factor = Math.pow(1.0 + r, months.toDouble()) - 1.0
        return futureValue * r / factor
    }

    private fun formatMoney(v: Double): String {
        return String.format(Locale.getDefault(), "%.2f", v)
    }
}

private object CommandsV2 {

    fun handleCommand(cmdRaw: String): List<String> {
        val cmd = cmdRaw.trim()
        val lower = cmd.lowercase(Locale.getDefault())

        if (lower.contains("налог") || lower.contains("ндфл") || lower.contains("tax")) {
            val out = CommandsMain.handleCommand(cmdRaw)
            if (out.isNotEmpty()) return out.map { "[V2] $it" }
        }
        if (lower.contains("накоп") || lower.contains("накопить") || lower.contains("savings")) {
            val out = CommandsMain.handleCommand(cmdRaw)
            if (out.isNotEmpty()) return out.map { "[V2] $it" }
        }
        if (lower.contains("pmt") || lower.contains("плт") || lower.contains("платеж") || lower.contains("платёж")) {
            val out = CommandsMain.handleCommand(cmdRaw)
            if (out.isNotEmpty()) return out.map { "[V2] $it" }
        }

        return emptyList()
    }
}

private object CommandsV3 {

    fun handleCommand(cmdRaw: String): List<String> {
        val cmd = cmdRaw.trim()
        val lower = cmd.lowercase(Locale.getDefault())

        if (lower.contains("налог") || lower.contains("ндфл") || lower.contains("tax")) {
            val out = CommandsMain.handleCommand(cmdRaw)
            if (out.isNotEmpty()) return out.map { "[V3] $it" }
        }
        if (lower.contains("накоп") || lower.contains("накопить") || lower.contains("savings")) {
            val out = CommandsMain.handleCommand(cmdRaw)
            if (out.isNotEmpty()) return out.map { "[V3] $it" }
        }
        if (lower.contains("pmt") || lower.contains("плт") || lower.contains("платеж") || lower.contains("платёж")) {
            val out = CommandsMain.handleCommand(cmdRaw)
            if (out.isNotEmpty()) return out.map { "[V3] $it" }
        }

        if (lower.contains("справк") || lower == "help" || lower.contains("помощ")) {
            return listOf(
                "Справка-заглушка (CommandsV3):",
                "- налог <сумма> [ставка%]  — расчёт налога (по умолчанию 13%)",
                "- накопить <сумма> на N дней / к ДД.ММ.ГГГГ [под X%] — сколько откладывать",
                "- pmt <сумма> <ставка%> <срок (лет)> — ежемесячный аннуитетный платёж"
            )
        }

        return emptyList()
    }
}
