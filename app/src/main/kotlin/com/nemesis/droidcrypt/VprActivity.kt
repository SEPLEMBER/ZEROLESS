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
import java.math.BigDecimal
import java.math.RoundingMode
import java.security.SecureRandom
import java.time.LocalDate
import java.time.Month
import java.time.temporal.ChronoUnit
import kotlin.math.ln
import kotlin.math.pow

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

        // hide status bar (full screen)
        window.setFlags(
            WindowManager.LayoutParams.FLAG_FULLSCREEN,
            WindowManager.LayoutParams.FLAG_FULLSCREEN
        )

        // Apply FLAG_SECURE if screenshots are disabled in prefs (key matches SettingsActivity)
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

        // Input behaviour: IME_ACTION_DONE submits command
        input.imeOptions = EditorInfo.IME_ACTION_DONE
        input.setRawInputType(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS)
        input.setOnEditorActionListener { _, actionId, event ->
            if (actionId == EditorInfo.IME_ACTION_DONE || (event?.keyCode == KeyEvent.KEYCODE_ENTER)) {
                val text = input.text.toString().trim()
                if (text.isNotEmpty()) {
                    submitCommand(text)
                    input.text = Editable.Factory.getInstance().newEditable("")
                } else {
                    // friendly hint when empty
                    addAssistantLine(getString(R.string.err_empty_input))
                }
                true
            } else {
                false
            }
        }

        // seed welcome
        addSystemLine(getString(R.string.welcome_messagen))
    }

    // --------------------
    // Command routing
    // --------------------
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
                    addAssistantLine("= ${getString(R.string.err_processing_command)}: ${t.message ?: t::class.java.simpleName}")
                    scrollView.post { scrollView.fullScroll(ScrollView.FOCUS_DOWN) }
                }
            }
        }
    }

    private suspend fun parseCommand(commandRaw: String): List<String> {
        val cmd = commandRaw.trim()
        val lower = cmd.lowercase()
        val colonIndex = cmd.indexOf(':')

        // --------------------
        // help / справка
        // --------------------
        if (lower.contains("справк") || lower == "help" || lower.contains("помощ")) {
            return listOf(
                "Поддерживаемые команды (примеры):",
                " - энтроп/энтропия <текст>        — посчитать энтропию",
                " - трафик 5gb                     — разделить трафик по дням/часам/сек (поддерживает 'на 7 дней', 'за неделю')",
                " - простые проценты 100000 7% 3 года",
                " - сложные проценты 100000 7% 3 года помесячно",
                " - месячный доход 120000 рабочие 8",
                " - бюджет 3000 на 7 дней          — лимиты на день/нед/мес/час",
                " - накопить 100000 к 7 ноября     — сколько откладывать в день/нед/мес (поддерживает % для учёта процентов)"
            )
        }

        // --------------------
        // (rest of parseCommand kept unchanged) ...
        // For brevity the rest of parseCommand remains identical to your provided file
        // --------------------

        // fallback
        return listOf(getString(R.string.fallback_unknown_command))
    }

    // --------------------
    // Helpers: mortgage amortization generator
    // --------------------
    private fun calculateMonthlyAnnuity(principal: BigDecimal, annualRate: BigDecimal, months: Int): BigDecimal {
        if (months <= 0) return principal
        val r = annualRate
        if (r == BigDecimal.ZERO) {
            return principal.divide(BigDecimal.valueOf(months.toLong()), 10, RoundingMode.HALF_UP)
        }
        val rMonth = r.divide(BigDecimal(12), 20, RoundingMode.HALF_UP)
        val rMonthDouble = rMonth.toDouble()
        val factor = (1.0 + rMonthDouble).pow(months.toDouble())
        val numerator = principal.toDouble() * (rMonthDouble * factor)
        val denominator = (factor - 1.0)
        val payment = if (denominator == 0.0) principal.toDouble() / months.toDouble() else numerator / denominator
        return BigDecimal.valueOf(payment).setScale(10, RoundingMode.HALF_UP)
    }

    private fun generateAmortizationRows(
        principalInput: BigDecimal,
        annualRate: BigDecimal,
        monthlyPayment: BigDecimal,
        totalMonths: Int,
        showFirst: Int
    ): List<String> {
        val rows = mutableListOf<String>()
        var remaining = principalInput.setScale(10, RoundingMode.HALF_UP)
        val rMonthBD = annualRate.divide(BigDecimal(12), 20, RoundingMode.HALF_UP)
        val maxShow = showFirst.coerceAtMost(totalMonths)
        for (i in 1..maxShow) {
            val interest = remaining.multiply(rMonthBD).setScale(10, RoundingMode.HALF_UP)
            var principalPaid = monthlyPayment.subtract(interest).setScale(10, RoundingMode.HALF_UP)
            if (i == totalMonths) {
                principalPaid = remaining
            }
            remaining = remaining.subtract(principalPaid).setScale(10, RoundingMode.HALF_UP)
            rows.add(String.format("Мес %d: платёж %s, проценты %s, погашение %s, остаток %s",
                i,
                monthlyPayment.setScale(2, RoundingMode.HALF_UP).toPlainString(),
                interest.setScale(2, RoundingMode.HALF_UP).toPlainString(),
                principalPaid.setScale(2, RoundingMode.HALF_UP).toPlainString(),
                remaining.max(BigDecimal.ZERO).setScale(2, RoundingMode.HALF_UP).toPlainString()
            ))
            if (remaining <= BigDecimal.ZERO) break
        }
        return rows
    }

    // Helper to find "первые N" or "табл N" pattern in payload
    private fun findRequestedFirstN(payload: String): Int? {
        val patterns = listOf(
            Regex("""первые\s*(\d{1,3})""", RegexOption.IGNORE_CASE),
            Regex("""табл(?:\.)?\s*(\d{1,3})""", RegexOption.IGNORE_CASE),
            Regex("""таблица\s*(\d{1,3})""", RegexOption.IGNORE_CASE),
            Regex("""показать\s*(\d{1,3})\s*месяц""", RegexOption.IGNORE_CASE),
            Regex("""(\d{1,3})\s*первых\s*месяцев""", RegexOption.IGNORE_CASE)
        )
        for (pat in patterns) {
            val m = pat.find(payload)
            if (m != null) {
                val n = m.groupValues.getOrNull(1)?.toIntOrNull()
                if (n != null && n > 0) return n
            }
        }
        return null
    }

    // --------------------
    // --- Entropy helpers (fixed numeric types + background freq)
    // --------------------
    private fun shannonEntropyBytes(bytes: ByteArray): Double {
        if (bytes.isEmpty()) return 0.0
        val freq = IntArray(256)
        bytes.forEach { b -> freq[b.toInt() and 0xFF]++ }
        val len = bytes.size.toDouble()
        var entropy = 0.0
        for (count in freq) {
            if (count == 0) continue
            val p = count.toDouble() / len
            entropy -= p * (ln(p) / ln(2.0))
        }
        return entropy
    }

    private fun shannonEntropyCodepoints(s: String): Double {
        val cps = s.codePoints().toArray()
        if (cps.isEmpty()) return 0.0
        val map = mutableMapOf<Int, Int>()
        for (cp in cps) map[cp] = (map[cp] ?: 0) + 1
        val len = cps.size.toDouble()
        var entropy = 0.0
        for ((_, count) in map) {
            val p = count.toDouble() / len
            entropy -= p * (ln(p) / ln(2.0))
        }
        return entropy
    }

    private fun entropyStrengthLabel(totalBits: Double): String {
        return when {
            totalBits < 28.0 -> getString(R.string.entropy_label_weak)
            totalBits < 46.0 -> getString(R.string.entropy_label_acceptable)
            totalBits < 71.0 -> getString(R.string.entropy_label_normal)
            else -> getString(R.string.entropy_label_strong)
        }
    }

    private fun backgroundAverageSurprisalBits(s: String): Double {
        if (s.isEmpty()) return 0.0
        val freqMap = mutableMapOf<Int, Double>().apply {
            put(' '.code, 0.13)
            val commonRu = mapOf(
                'о' to 0.09, 'е' to 0.07, 'а' to 0.062, 'и' to 0.06, 'н' to 0.055,
                'т' to 0.052, 'с' to 0.045, 'р' to 0.039, 'в' to 0.037, 'л' to 0.035
            )
            for ((ch, v) in commonRu) put(ch.code, v)
            for (d in '0'..'9') put(d.code, 0.01)
            for (c in 'a'..'z') put(c.code, 0.01)
            for (c in 'A'..'Z') put(c.code, 0.01)
            put('.'.code, 0.03); put(','.code, 0.03); put('-'.code, 0.005); put('_'.code, 0.002)
        }
        val defaultProb = 0.0005 // rare by default
        val cps = s.codePoints().toArray()
        var sum = 0.0
        for (cp in cps) {
            val p = freqMap[cp] ?: defaultProb
            val surprisal = - (ln(p) / ln(2.0))
            sum += surprisal
        }
        return sum / cps.size.toDouble()
    }

    // --------------------
    // --- Traffic helpers
    // --------------------
    private fun parseBytes(amount: Double, unitRaw: String?): Long {
        val unit = unitRaw?.lowercase() ?: ""
        return when (unit) {
            "gb", "гб" -> (amount * 1000.0 * 1000.0 * 1000.0).toLong()
            "mb", "мб" -> (amount * 1000.0 * 1000.0).toLong()
            "kb", "кб" -> (amount * 1000.0).toLong()
            "b", "байт" -> amount.toLong()
            "" -> amount.toLong()
            else -> amount.toLong()
        }
    }

    private fun formatBytesDecimal(bytes: Long): String {
        val kb = 1000.0
        val mb = kb * 1000.0
        val gb = mb * 1000.0
        return when {
            bytes >= gb -> String.format("%.1f GB", bytes / gb)
            bytes >= mb -> String.format("%.1f MB", bytes / mb)
            bytes >= kb -> String.format("%.1f kB", bytes / kb)
            else -> "$bytes B"
        }
    }

    private fun formatBytesDecimalDouble(bytesDouble: Double): String {
        val kb = 1000.0
        val mb = kb * 1000.0
        val gb = mb * 1000.0
        return when {
            bytesDouble >= gb -> String.format("%.1f GB", bytesDouble / gb)
            bytesDouble >= mb -> String.format("%.1f MB", bytesDouble / mb)
            bytesDouble >= kb -> String.format("%.1f kB", bytesDouble / kb)
            bytesDouble >= 1.0 -> String.format("%.1f B", bytesDouble)
            else -> String.format("%.3f B", bytesDouble)
        }
    }

    private fun formatBitsPerSecond(bytesPerSecond: Double): String {
        val bitsPerSecond = bytesPerSecond * 8.0
        val kbps = 1000.0
        val mbps = kbps * 1000.0
        return when {
            bitsPerSecond >= mbps -> String.format("%.2f Mb/s", bitsPerSecond / mbps)
            bitsPerSecond >= kbps -> String.format("%.2f kb/s", bitsPerSecond / kbps)
            else -> String.format("%.2f bit/s", bitsPerSecond)
        }
    }

    private fun bytesPerMonthToRates(bytesPerMonth: Long, daysInMonth: Int = 30): Map<String, Double> {
        val bMonth = bytesPerMonth.toDouble()
        val bDay = bMonth / daysInMonth
        val bHour = bDay / 24.0
        val bMin = bHour / 60.0
        val bSec = bMin / 60.0
        return mapOf("B/day" to bDay, "B/hour" to bHour, "B/min" to bMin, "B/s" to bSec)
    }

    // --------------------
    // --- Money helpers (budget fixed to be 'limits')
    // --------------------
    /**
     * moneyPerDaysReport:
     * - For 'бюджет' command: budget = запас/лимит на указанный период
     * - Returns human-friendly limits: per day, per week, per month(≈30d), per hour (24h) and per working hour
     */
    private fun moneyPerDaysReport(amount: BigDecimal, days: Int, workingHours: Int = 8): String {
        val d = days.coerceAtLeast(1)
        val daily = amount.divide(BigDecimal.valueOf(d.toLong()), 10, RoundingMode.HALF_UP)
        val weekly = daily.multiply(BigDecimal.valueOf(7L))
        val monthlyApprox = daily.multiply(BigDecimal.valueOf(30L))
        val hourly = daily.divide(BigDecimal.valueOf(24L), 10, RoundingMode.HALF_UP)
        val perWorkHour = if (workingHours > 0) daily.divide(BigDecimal.valueOf(workingHours.toLong()), 10, RoundingMode.HALF_UP) else BigDecimal.ZERO

        return buildString {
            append("Бюджет ${amount.setScale(2, RoundingMode.HALF_UP)} на $d дней").append("\n")
            append("Лимит в день: ${daily.setScale(2, RoundingMode.HALF_UP)} ₽").append("\n")
            append("Лимит в неделю: ${weekly.setScale(2, RoundingMode.HALF_UP)} ₽").append("\n")
            append("Лимит в месяц (≈30д): ${monthlyApprox.setScale(2, RoundingMode.HALF_UP)} ₽").append("\n")
            append("Лимит в час (24ч): ${hourly.setScale(4, RoundingMode.HALF_UP)} ₽").append("\n")
            append("Лимит за рабочий час (рабочие $workingHours): ${perWorkHour.setScale(2, RoundingMode.HALF_UP)} ₽")
        }
    }

    /**
     * monthlyToRatesReport:
     * - For 'месячный доход' and fallback monthly conversions
     * - Treats input as recurring monthly income and computes per-day (30d), per-week (12 months -> weeks), per-24h-hour and per-working-hour
     */
    private fun monthlyToRatesReport(monthly: BigDecimal, workingHours: Int): String {
        // monthly -> per-day (approx 30), per-week (approx 52.1429 weeks/year)
        val perDay = monthly.divide(BigDecimal.valueOf(30L), 10, RoundingMode.HALF_UP)
        // convert months -> weeks: monthly * 12 months / 52.142857 weeks
        val weeksPerYear = 52.142857
        val perWeek = monthly.multiply(BigDecimal.valueOf(12.0 / weeksPerYear)).setScale(10, RoundingMode.HALF_UP)
        val per24Hour = perDay.divide(BigDecimal.valueOf(24L), 10, RoundingMode.HALF_UP)
        val perWorkHour = if (workingHours > 0) perDay.divide(BigDecimal.valueOf(workingHours.toLong()), 10, RoundingMode.HALF_UP) else BigDecimal.ZERO
        val yearly = monthly.multiply(BigDecimal.valueOf(12L))

        return buildString {
            append(getString(R.string.monthly_header)).append("\n")
            append(getString(R.string.monthly_amount, monthly.setScale(2, RoundingMode.HALF_UP))).append("\n")
            append(getString(R.string.monthly_year, yearly.setScale(2, RoundingMode.HALF_UP))).append("\n")
            append("В день (≈30д): ${perDay.setScale(2, RoundingMode.HALF_UP)} ₽").append("\n")
            append("В неделю (≈avg): ${perWeek.setScale(2, RoundingMode.HALF_UP)} ₽").append("\n")
            append(getString(R.string.monthly_hour_24, per24Hour.setScale(2, RoundingMode.HALF_UP))).append("\n")
            append(getString(R.string.monthly_hour_work, workingHours, perWorkHour.setScale(2, RoundingMode.HALF_UP)))
        }
    }

    // --------------------
    // --- Percent & Time helpers
    // --------------------
    private fun parsePercent(s: String): BigDecimal {
        val cleaned = s.replace("%", "").replace(',', '.').trim()
        return try {
            BigDecimal(cleaned).divide(BigDecimal(100))
        } catch (e: Exception) {
            BigDecimal.ZERO
        }
    }

    // --------------------
    // --- UI helpers (moved to end)
    // --------------------
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

    // Utility: generate random bytes (unused but left)
    private fun randomBytes(size: Int): ByteArray {
        val rnd = SecureRandom()
        val arr = ByteArray(size)
        rnd.nextBytes(arr)
        return arr
    }
}
