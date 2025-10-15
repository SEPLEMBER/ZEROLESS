package com.nemesis.droidcrypt.locale

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
import java.math.MathContext
import java.math.RoundingMode
import java.security.SecureRandom
import java.time.LocalDate
import java.time.Month
import java.time.temporal.ChronoUnit
import java.util.Locale
import kotlin.math.pow
import com.nemesis.droidcrypt.R

class EnFinAsActivity : AppCompatActivity() {

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

        // NOTE: rename your layout resource accordingly or change this back.
        setContentView(R.layout.activity_en_fin_as)

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
                    addAssistantLine("Error: empty input. Please type a command.")
                }
                true
            } else {
                false
            }
        }

        addSystemLine("Welcome to FinAssistant — type 'help' for commands.")
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
                    addAssistantLine("= Error processing command: ${t.message ?: t::class.java.simpleName}")
                    scrollView.post { scrollView.fullScroll(ScrollView.FOCUS_DOWN) }
                }
            }
        }
    }

    private suspend fun parseCommand(commandRaw: String): List<String> {
        val cmd = commandRaw.trim()
        val lower = cmd.lowercase(Locale.getDefault())
        val colonIndex = cmd.indexOf(':')

        if (lower.contains("help") || lower.contains("h") || lower.contains("support")) {
            return listOf(
                "Supported commands (examples):",
                " - compare prices: 'compare prices: apples 1.20, oranges 1.40'",
                " - save goal: 'save 100000 by 2025-11-07 at 5%'",
                " - mortgage/loan: 'mortgage 1000000 7% 15 years first 10'",
                " - inflation: 'inflation: 100000 8% 3 years'",
                " - tax: 'tax 100000 13%'",
                " - roi/invest: 'roi 50000 65000' or 'invest 50000 profit 15000'",
                " - amortization: 'amortization 100000 5 years'",
                " - payback: 'payback 50000 2000 per month'",
                " - simple interest: 'simple interest 100000 7% 3 years'",
                " - compound interest: 'compound interest 100000 7% 3 years monthly'",
                " - monthly income: 'monthly income 3000 for 7 days' or 'monthly income 120000 work 8'",
                " - xirr: 'xirr -10000@2023-01-01 3000@2023-12-01'",
                " - npv: 'npv 10% -100000 50000'",
                " - budget: 'budget 100 for 7 days'",
                " - pmt: 'pmt 150000 9% 15 years annuity'",
                " - debtplan: 'debtplan a:100000@12% b:50000@8% payment 15000'"
            )
        }

        // --- compare prices ---
        if (lower.contains("compare") && (lower.contains("price") || lower.contains("prices"))) {
            val numberRegex = Regex("""(\d+(?:[.,]\d+)?)""")
            val matches = numberRegex.findAll(cmd).toList()
            if (matches.size < 2) return listOf("Not enough prices to compare. Example: 'compare prices apples 1.20 oranges 1.40'")
            val rootIndex = lower.indexOf("compare").coerceAtLeast(0)
            val rootEnd = rootIndex + "compare".length
            val items = mutableListOf<Pair<String, BigDecimal>>()
            val limit = matches.size.coerceAtMost(10)
            for (i in 0 until limit) {
                val m = matches[i]
                val start = m.range.first
                val prevEnd = if (i == 0) rootEnd else matches[i - 1].range.last + 1
                val effectivePrevEnd = if (colonIndex >= 0 && colonIndex < start && colonIndex >= prevEnd) colonIndex + 1 else prevEnd
                val labelRaw = try { cmd.substring(effectivePrevEnd, start).trim() } catch (e: Exception) { "" }
                val stopWords = setOf("compare", "compareprices", "price", "prices", "please", "the", "and", "vs", "vs.")
                val tokens = labelRaw.split(Regex("\\s+"))
                    .map { it.trim().trim(',', ';', ':', '.', '\"', '\'') }
                    .filter { it.isNotEmpty() && it.lowercase(Locale.getDefault()) !in stopWords && !it.matches(Regex("""^\d+$""")) }
                val name = if (tokens.isEmpty()) "item${i + 1}" else tokens.takeLast(3).joinToString(" ")
                val priceStr = m.groupValues[1].replace(',', '.')
                val price = try { BigDecimal(priceStr) } catch (e: Exception) { BigDecimal.ZERO }
                items.add(name to price)
            }
            if (items.size < 2) return listOf("Not enough name+price pairs for comparison.")
            val sorted = items.sortedBy { it.second }
            val cheapest = sorted.first().second
            val outputs = mutableListOf<String>()
            outputs.add("Items by price (ascending):")
            for ((idx, pair) in sorted.withIndex()) {
                val name = pair.first
                val price = pair.second.setScale(2, RoundingMode.HALF_UP)
                val diffPercent = if (cheapest.compareTo(BigDecimal.ZERO) == 0) BigDecimal.ZERO
                else pair.second.subtract(cheapest).divide(cheapest, 6, RoundingMode.HALF_UP).multiply(BigDecimal(100))
                val diffPercentStr = diffPercent.setScale(2, RoundingMode.HALF_UP).toPlainString()
                val extra = if (idx == 0) "" else " (+$diffPercentStr%)"
                outputs.add("${idx + 1}) $name — ${price.toPlainString()} $extra")
            }
            return outputs
        }

        if (lower.contains("save") || lower.contains("savings") || lower.contains("savegoal") || lower.contains("saving")) {
            val payload = if (colonIndex >= 0) cmd.substring(colonIndex + 1) else cmd
            val nums = Regex("""(\d+(?:[.,]\d+)?)""").findAll(payload).map { it.groupValues[1].replace(',', '.') }.toList()
            if (nums.isEmpty()) return listOf("Usage: save <amount> by <date> | save <amount> for <N days> [at X%]")

            val amount = try { BigDecimal(nums[0]) } catch (e: Exception) { BigDecimal.ZERO }

            val daysFromText = parseDaysFromText(payload)
            val daysDouble = daysFromText?.toDouble()
            val yearsFallback = parseTimeToYears(payload)
            val daysFromYears = (yearsFallback * 365.0)

            val daysToUse = when {
                daysDouble != null -> daysDouble
                daysFromYears > 0.0 -> daysFromYears
                else -> 30.0
            }.coerceAtLeast(1.0)

            val percentRegex = Regex("""(\d+(?:[.,]\d+)?)\s*%""")
            val percMatch = percentRegex.find(payload)
            val annualRate = percMatch?.groupValues?.getOrNull(1)?.replace(',', '.')?.let {
                try { BigDecimal(it).divide(BigDecimal(100)) } catch (_: Exception) { BigDecimal.ZERO }
            } ?: BigDecimal.ZERO

            val perDay = amount.divide(BigDecimal.valueOf(daysToUse), 10, RoundingMode.HALF_UP)
            val perWeek = perDay.multiply(BigDecimal(7))
            val perMonth = perDay.multiply(BigDecimal(30))

            val outputs = mutableListOf<String>()
            outputs.add("Goal: ${amount.setScale(2, RoundingMode.HALF_UP)}")
            outputs.add("Period: ${"%.1f".format(Locale.getDefault(), daysToUse)} days (~${"%.2f".format(Locale.getDefault(), daysToUse / 30.0)} months)")
            if (annualRate == BigDecimal.ZERO) {
                outputs.add("You need to save: per day ${perDay.setScale(2, RoundingMode.HALF_UP)} $, per week ${perWeek.setScale(2, RoundingMode.HALF_UP)} $, per month ≈ ${perMonth.setScale(2, RoundingMode.HALF_UP)} $")
            } else {
                val months = Math.max(1, Math.round(daysToUse / 30.0).toInt())
                val rMonth = annualRate.divide(BigDecimal(12), 20, RoundingMode.HALF_UP)
                val n = months
                val pm: BigDecimal = if (rMonth.compareTo(BigDecimal.ZERO) == 0) {
                    amount.divide(BigDecimal.valueOf(n.toLong()), 10, RoundingMode.HALF_UP)
                } else {
                    try {
                        val mc = MathContext(34)
                        val onePlus = BigDecimal.ONE.add(rMonth, mc)
                        val factor = onePlus.pow(n, mc)
                        val numerator = amount.multiply(rMonth, mc).multiply(factor, mc)
                        val denominator = factor.subtract(BigDecimal.ONE, mc)
                        if (denominator.compareTo(BigDecimal.ZERO) == 0) {
                            amount.divide(BigDecimal.valueOf(n.toLong()), 10, RoundingMode.HALF_UP)
                        } else {
                            numerator.divide(denominator, 10, RoundingMode.HALF_UP)
                        }
                    } catch (e: Exception) {
                        val rDouble = rMonth.toDouble()
                        val factor = (1.0 + rDouble).pow(n.toDouble())
                        val numerator = amount.toDouble() * rDouble
                        val denom = factor - 1.0
                        val pmt = if (denom == 0.0) amount.toDouble() / n.toDouble() else numerator / denom
                        BigDecimal.valueOf(pmt).setScale(10, RoundingMode.HALF_UP)
                    }
                }
                val perMonthWithInterest = pm.setScale(2, RoundingMode.HALF_UP)
                val perDayApprox = pm.divide(BigDecimal(30), 10, RoundingMode.HALF_UP)
                val perWeekApprox = perDayApprox.multiply(BigDecimal(7))
                outputs.add("Assuming ${(annualRate.multiply(BigDecimal(100))).setScale(2, RoundingMode.HALF_UP)}% annual (monthly compounding):")
                outputs.add("Monthly required: ${perMonthWithInterest} $ (≈ per day ${perDayApprox.setScale(2, RoundingMode.HALF_UP)} $, per week ${perWeekApprox.setScale(2, RoundingMode.HALF_UP)} $)")
            }
            return outputs
        }

        val mortgagePattern = Regex("""(?i)\b\w*(mortgag|loan)\w*\b[ :]*([\s\S]+)""")
        val mortMatch = mortgagePattern.find(cmd)
        if (mortMatch != null) {
            var payload = mortMatch.groupValues.getOrNull(2)?.trim() ?: ""
            if (payload.isEmpty() && colonIndex >= 0) payload = cmd.substring(colonIndex + 1).trim()

            val percentRegex = Regex("""(\d+(?:[.,]\d+)?)\s*%""")
            val percMatch = percentRegex.find(payload)
            val percVal = percMatch?.groupValues?.getOrNull(1)?.replace(',', '.')?.let { try { BigDecimal(it).divide(BigDecimal(100)) } catch (_: Exception) { BigDecimal.ZERO } }

            val allNums = Regex("""(\d+(?:[.,]\d+)?)""").findAll(payload).map { it.groupValues[1].replace(',', '.') }.toList()

            val principal = when {
                percVal != null && allNums.isNotEmpty() -> {
                    val candidate = allNums.firstOrNull { it != percMatch?.groupValues?.getOrNull(1)?.replace(',', '.') } ?: allNums.first()
                    try { BigDecimal(candidate) } catch (e: Exception) { BigDecimal.ZERO }
                }
                allNums.isNotEmpty() -> try { BigDecimal(allNums[0]) } catch (e: Exception) { BigDecimal.ZERO }
                else -> null
            }

            if (principal == null) return listOf("Usage: mortgage <amount> <rate%> <term (years|months)> [first N]")

            val annualRate = percVal ?: BigDecimal.ZERO
            val years = parseTimeToYears(payload)
            val months = (years * 12.0).toInt().coerceAtLeast(1)

            val monthlyPaymentBD = calculateMonthlyAnnuity(principal, annualRate, months)
            val totalPayment = monthlyPaymentBD.multiply(BigDecimal.valueOf(months.toLong()))
            val totalInterest = totalPayment.subtract(principal)

            val paymentPerYear = if (years > 0.0) totalInterest.divide(BigDecimal.valueOf(years), 10, RoundingMode.HALF_UP) else BigDecimal.ZERO
            val perMonthAvg = paymentPerYear.divide(BigDecimal(12), 10, RoundingMode.HALF_UP)
            val perDayAvg = paymentPerYear.divide(BigDecimal(365), 10, RoundingMode.HALF_UP)
            val perHourAvg = perDayAvg.divide(BigDecimal(24), 10, RoundingMode.HALF_UP)
            val ratePercentStr = (annualRate.multiply(BigDecimal(100))).setScale(4, RoundingMode.HALF_UP).toPlainString()

            val firstN = findRequestedFirstN(payload)
            val outputs = mutableListOf<String>()
            outputs.add("Loan/Mortgage: principal ${principal.setScale(2, RoundingMode.HALF_UP)} $")
            outputs.add("Annual rate: $ratePercentStr%")
            outputs.add("Term: ${"%.4f".format(Locale.getDefault(), years)} years (${months} months)")
            outputs.add("Monthly payment (annuity): ${monthlyPaymentBD.setScale(2, RoundingMode.HALF_UP)} $")
            outputs.add("Total payback: ${totalPayment.setScale(2, RoundingMode.HALF_UP)} $ (interest ${totalInterest.setScale(2, RoundingMode.HALF_UP)} $)")
            outputs.add("Average interest: per year ${paymentPerYear.setScale(2, RoundingMode.HALF_UP)} $, per month ${perMonthAvg.setScale(2, RoundingMode.HALF_UP)} $, per day ${perDayAvg.setScale(4, RoundingMode.HALF_UP)} $, per hour ${perHourAvg.setScale(6, RoundingMode.HALF_UP)} $")

            if (firstN != null && firstN > 0) {
                val cap = firstN.coerceAtMost(240).coerceAtMost(months)
                outputs.add("First $cap months of amortization (payment, interest, principal, remaining):")
                outputs.addAll(generateAmortizationRows(principal, annualRate, monthlyPaymentBD, months, cap))
                if (cap < months) outputs.add("(Showing first $cap of $months months)")
            }

            return outputs
        }

        // --- inflation ---
        if (lower.contains("inflation") || lower.contains("infl")) {
            var payload = ""
            if (colonIndex >= 0) {
                payload = cmd.substring(colonIndex + 1).trim()
            } else {
                val idx = lower.indexOf("infl").let { if (it >= 0) it else -1 }
                payload = if (idx >= 0) {
                    try { cmd.substring(idx).substringAfter(" ").trim() } catch (_: Exception) { cmd }
                } else {
                    cmd
                }
            }

            val amountMatch = Regex("""(\d+(?:[.,]\d+)?)""").find(payload)
            val amount = if (amountMatch != null) {
                try {
                    BigDecimal(amountMatch.groupValues[1].replace(',', '.'))
                } catch (e: Exception) { BigDecimal.ZERO }
            } else BigDecimal.ZERO

            val rateMatch = Regex("""(\d+(?:[.,]\d+)?)\s*%""").find(payload)
            val annualRate = if (rateMatch != null) parsePercent(rateMatch.groupValues[0]) else BigDecimal.ZERO
            val years = parseTimeToYears(payload)

            val factor = (1.0 + annualRate.toDouble()).pow(years)
            val futureValue = BigDecimal.valueOf(amount.toDouble() * factor).setScale(2, RoundingMode.HALF_UP)
            val presentEquivalent = if (factor != 0.0) BigDecimal.valueOf(amount.toDouble() / factor).setScale(2, RoundingMode.HALF_UP) else BigDecimal.ZERO

            val annualDouble = annualRate.toDouble()
            val perDayRate = (1.0 + annualDouble).pow(1.0 / 365.0) - 1.0
            val perHourRate = (1.0 + annualDouble).pow(1.0 / (365.0 * 24.0)) - 1.0

            val ratePercentStr = (annualRate.multiply(BigDecimal(100))).setScale(4, RoundingMode.HALF_UP).toPlainString()
            val perDayPercent = String.format(Locale.getDefault(), "%.6f", perDayRate * 100.0)
            val perHourPercent = String.format(Locale.getDefault(), "%.8f", perHourRate * 100.0)
            val amountStr = if (amount > BigDecimal.ZERO) amount.setScale(2, RoundingMode.HALF_UP).toPlainString() else "—"

            val results = mutableListOf<String>()
            results.add("Inflation — rate: $ratePercentStr% annually, period: ${"%.4f".format(Locale.getDefault(), years)} years")
            if (amount > BigDecimal.ZERO) {
                results.add("Now: $amountStr $ → In period (nominal): ${futureValue.toPlainString()} $")
                results.add("Equivalent in today's money: ${presentEquivalent.toPlainString()} $")
            } else {
                results.add("Amount not specified — example: 'inflation 100000 8% 3 years'")
            }
            results.add("Derived rates: per day ≈ $perDayPercent%, per hour ≈ $perHourPercent%")
            return results
        }

        if (lower.contains("tax")) {
            val percentRegex = Regex("""(\d+(?:[.,]\d+)?)\s*%""")
            val percMatch = percentRegex.find(cmd)
            val percVal = percMatch?.groupValues?.getOrNull(1)?.replace(',', '.')?.let {
                try { BigDecimal(it).divide(BigDecimal(100)) } catch (e: Exception) { BigDecimal.ZERO }
            }
            val allNums = Regex("""(\d+(?:[.,]\d+)?)""").findAll(cmd).map { it.groupValues[1].replace(',', '.') }.toList()
            val amount = when {
                percVal != null && allNums.isNotEmpty() -> {
                    val candidateStr = allNums.firstOrNull { it != percMatch.groupValues.getOrNull(1)?.replace(',', '.') } ?: allNums.first()
                    try { BigDecimal(candidateStr) } catch (e: Exception) { BigDecimal.ZERO }
                }
                allNums.isNotEmpty() -> try { BigDecimal(allNums[0]) } catch (e: Exception) { BigDecimal.ZERO }
                else -> null
            }
            if (amount == null) return listOf("Usage: tax <amount> [rate%] — example: 'tax 100000 13%' or 'tax 13% of 100'")
            val rate = percVal ?: BigDecimal("0.13") // default 13%
            val tax = amount.multiply(rate).setScale(2, RoundingMode.HALF_UP)
            val net = amount.subtract(tax).setScale(2, RoundingMode.HALF_UP)
            return listOf("Amount: ${amount.setScale(2, RoundingMode.HALF_UP)} $", "Tax: ${tax.toPlainString()} $", "After tax: ${net.toPlainString()} $")
        }

        if (lower.contains("roi") || lower.contains("payback") || lower.contains("invest")) {
            val nums = Regex("""(\d+(?:[.,]\d+)?)""").findAll(cmd).map { it.groupValues[1].replace(',', '.') }.toList()
            if (nums.isEmpty()) return listOf("Usage: roi <invested> <final> — or 'invest 50000 profit 15000'")
            if (nums.size >= 2 && (lower.contains("profit") || lower.contains("gain"))) {
                val invested = try { BigDecimal(nums[0]) } catch (e: Exception) { BigDecimal.ZERO }
                val profit = try { BigDecimal(nums[1]) } catch (e: Exception) { BigDecimal.ZERO }
                val roi = if (invested > BigDecimal.ZERO) profit.divide(invested, 6, RoundingMode.HALF_UP) else BigDecimal.ZERO
                return listOf("Invested: ${invested.setScale(2, RoundingMode.HALF_UP)} $", "Profit: ${profit.setScale(2, RoundingMode.HALF_UP)} $", "ROI: ${(roi.multiply(BigDecimal(100))).setScale(4, RoundingMode.HALF_UP)}%")
            } else if (nums.size >= 2) {
                val invested = try { BigDecimal(nums[0]) } catch (e: Exception) { BigDecimal.ZERO }
                val final = try { BigDecimal(nums[1]) } catch (e: Exception) { BigDecimal.ZERO }
                val profit = final.subtract(invested)
                val roi = if (invested > BigDecimal.ZERO) profit.divide(invested, 6, RoundingMode.HALF_UP) else BigDecimal.ZERO
                return listOf("Invested: ${invested.setScale(2, RoundingMode.HALF_UP)} $", "Final: ${final.setScale(2, RoundingMode.HALF_UP)} $", "Profit: ${profit.setScale(2, RoundingMode.HALF_UP)} $", "ROI: ${(roi.multiply(BigDecimal(100))).setScale(4, RoundingMode.HALF_UP)}%")
            } else {
                return listOf("Not enough data. Example: 'roi 50000 65000' or 'invest 50000 profit 15000'")
            }
        }

        if (lower.contains("amort") || lower.contains("amortization")) {
            val nums = Regex("""(\d+(?:[.,]\d+)?)""").findAll(cmd).map { it.groupValues[1].replace(',', '.') }.toList()
            if (nums.isEmpty()) return listOf("Usage: amortization <cost> <years> [salvage value]")
            val cost = try { BigDecimal(nums[0]) } catch (e: Exception) { BigDecimal.ZERO }
            val years = if (nums.size >= 2) try { nums[1].toDouble() } catch (e: Exception) { 1.0 } else 1.0
            val salvage = if (nums.size >= 3) try { BigDecimal(nums[2]) } catch (e: Exception) { BigDecimal.ZERO } else BigDecimal.ZERO
            val usefulYears = if (years <= 0.0) 1.0 else years
            val annual = (cost.subtract(salvage)).divide(BigDecimal.valueOf(usefulYears.toLong()), 10, RoundingMode.HALF_UP)
            val monthly = annual.divide(BigDecimal(12), 10, RoundingMode.HALF_UP)
            return listOf("Amortization (straight-line): cost ${cost.setScale(2, RoundingMode.HALF_UP)} $", "Term (years): ${"%.4f".format(Locale.getDefault(), usefulYears)}", "Per year: ${annual.setScale(2, RoundingMode.HALF_UP)} $", "Per month: ${monthly.setScale(2, RoundingMode.HALF_UP)} $")
        }

        if (lower.contains("payback") || lower.contains("payback period") || lower.contains("break-even")) {
            val nums = Regex("""(\d+(?:[.,]\d+)?)""").findAll(cmd).map { it.groupValues[1].replace(',', '.') }.toList()
            if (nums.size < 2) return listOf("Usage: payback <invested> <profit per month/year>")
            val invested = try { BigDecimal(nums[0]) } catch (e: Exception) { BigDecimal.ZERO }
            val profitNum = try { BigDecimal(nums[1]) } catch (e: Exception) { BigDecimal.ZERO }
            val profitIsAnnual = lower.contains("year") || lower.contains("per year") || lower.contains("annually")
            val monthlyProfit = if (profitIsAnnual) profitNum.divide(BigDecimal(12), 10, RoundingMode.HALF_UP) else profitNum
            if (monthlyProfit <= BigDecimal.ZERO) return listOf("Invalid profit for payback calculation")
            val months = invested.divide(monthlyProfit, 4, RoundingMode.HALF_UP)
            val years = months.divide(BigDecimal(12), 4, RoundingMode.HALF_UP)
            return listOf("Invested: ${invested.setScale(2, RoundingMode.HALF_UP)} $", "Monthly profit: ${monthlyProfit.setScale(2, RoundingMode.HALF_UP)} $", "Payback: ${months.setScale(2, RoundingMode.HALF_UP)} months (~${years.setScale(2, RoundingMode.HALF_UP)} years)")
        }

        if (lower.contains("simple") && lower.contains("interest")) {
            val percentRegex = Regex("""(\d+(?:[.,]\d+)?)\s*%""")
            val percMatch = percentRegex.find(cmd)
            val percVal = percMatch?.groupValues?.getOrNull(1)?.replace(',', '.')?.let { try { BigDecimal(it).divide(BigDecimal(100)) } catch (_: Exception) { BigDecimal.ZERO } }

            val allNums = Regex("""(\d+(?:[.,]\d+)?)""").findAll(cmd).map { it.groupValues[1].replace(',', '.') }.toList()
            val principal = when {
                percVal != null && allNums.isNotEmpty() -> {
                    val candidate = allNums.firstOrNull { it != percMatch.groupValues.getOrNull(1)?.replace(',', '.') } ?: allNums.first()
                    try { BigDecimal(candidate) } catch (e: Exception) { BigDecimal.ZERO }
                }
                allNums.isNotEmpty() -> try { BigDecimal(allNums[0]) } catch (e: Exception) { BigDecimal.ZERO }
                else -> return listOf("Usage: simple interest <principal> <rate%> <time>")
            }
            val rate = percVal ?: BigDecimal.ZERO
            val timeYears = parseTimeToYears(cmd)
            return listOfNotNull(simpleInterestReport(principal, rate, timeYears))
        }

        if (lower.contains("compound") && lower.contains("interest")) {
            val percentRegex = Regex("""(\d+(?:[.,]\d+)?)\s*%""")
            val percMatch = percentRegex.find(cmd)
            val percVal = percMatch?.groupValues?.getOrNull(1)?.replace(',', '.')?.let { try { BigDecimal(it).divide(BigDecimal(100)) } catch (_: Exception) { BigDecimal.ZERO } }

            val allNums = Regex("""(\d+(?:[.,]\d+)?)""").findAll(cmd).map { it.groupValues[1].replace(',', '.') }.toList()
            val principal = when {
                percVal != null && allNums.isNotEmpty() -> {
                    val candidate = allNums.firstOrNull { it != percMatch.groupValues?.getOrNull(1)?.replace(',', '.') } ?: allNums.first()
                    try { BigDecimal(candidate) } catch (e: Exception) { BigDecimal.ZERO }
                }
                allNums.isNotEmpty() -> try { BigDecimal(allNums[0]) } catch (e: Exception) { BigDecimal.ZERO }
                else -> return listOf("Usage: compound interest <principal> <rate%> <time> [monthly/daily/yearly]")
            }
            val rate = percVal ?: BigDecimal.ZERO
            val timeYears = parseTimeToYears(cmd)
            val capitalization = when {
                cmd.contains("monthly", ignoreCase = true) || cmd.contains("pomm", ignoreCase = true) -> "monthly"
                cmd.contains("daily", ignoreCase = true) -> "daily"
                cmd.contains("year", ignoreCase = true) || cmd.contains("yearly", ignoreCase = true) -> "yearly"
                else -> "monthly"
            }
            return listOfNotNull(compoundInterestReport(principal, rate, timeYears, capitalization))
        }

        if (lower.contains("budget") || (lower.contains("monthly") && (lower.contains("income") || lower.contains("salary") || lower.contains("expense")))) {
            val payload = if (colonIndex >= 0) cmd.substring(colonIndex + 1) else cmd
            val amountMatch = Regex("""(\d+(?:[.,]\d+)?)""").find(payload)
            if (amountMatch == null) return listOf("Usage: monthly income <amount>")
            val amount = try { BigDecimal(amountMatch.groupValues[1].replace(',', '.')) } catch (e: Exception) { return listOf("Invalid amount: ${amountMatch.groupValues[1]}") }

            val workHoursMatch = Regex("""work\w*\s+(\d{1,2})""", RegexOption.IGNORE_CASE).find(payload)
            val workHours = workHoursMatch?.groupValues?.get(1)?.toIntOrNull() ?: 8

            val days = parseDaysFromText(payload)
            if (days != null) {
                return listOfNotNull(moneyPerDaysReport(amount, days, workHours))
            }

            if (lower.contains("monthly") && (lower.contains("income") || lower.contains("salary") || lower.contains("expense"))) {
                return listOfNotNull(monthlyIncomeReport(amount, workHours))
            }

            return listOfNotNull(monthlyIncomeReport(amount, workHours))
        }

        try {
            val v2Outputs = EnFinanceV2.handleCommand(cmd)
            if (!v2Outputs.isNullOrEmpty()) {
                return v2Outputs
            }
        } catch (e: Exception) {
        }

        return listOf("Unknown command. Type 'help' for a list of commands.")
    }

    private fun calculateMonthlyAnnuity(principal: BigDecimal, annualRate: BigDecimal, months: Int): BigDecimal {
        if (months <= 0) return principal.setScale(10, RoundingMode.HALF_UP)
        val r = annualRate
        if (r.compareTo(BigDecimal.ZERO) == 0) {
            return principal.divide(BigDecimal.valueOf(months.toLong()), 10, RoundingMode.HALF_UP)
        }

        return try {
            val mc = MathContext(34)
            val rMonth = r.divide(BigDecimal(12), 34, RoundingMode.HALF_UP)
            val onePlus = BigDecimal.ONE.add(rMonth, mc)
            val factor = onePlus.pow(months, mc) // (1 + rMonth)^n
            val numerator = principal.multiply(rMonth, mc).multiply(factor, mc)
            val denominator = factor.subtract(BigDecimal.ONE, mc)
            if (denominator.compareTo(BigDecimal.ZERO) == 0) {
                principal.divide(BigDecimal.valueOf(months.toLong()), 10, RoundingMode.HALF_UP)
            } else {
                numerator.divide(denominator, 10, RoundingMode.HALF_UP)
            }
        } catch (e: Exception) {
            val rMonthDouble = r.divide(BigDecimal(12), 20, RoundingMode.HALF_UP).toDouble()
            val factor = (1.0 + rMonthDouble).pow(months.toDouble())
            val numerator = principal.toDouble() * (rMonthDouble * factor)
            val denominator = (factor - 1.0)
            val payment = if (denominator == 0.0) principal.toDouble() / months.toDouble() else numerator / denominator
            BigDecimal.valueOf(payment).setScale(10, RoundingMode.HALF_UP)
        }
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
            rows.add(String.format(Locale.getDefault(), "Month %d: payment %s, interest %s, principal %s, remaining %s",
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

    private fun findRequestedFirstN(payload: String): Int? {
        val patterns = listOf(
            Regex("""first\s*(\d{1,3})""", RegexOption.IGNORE_CASE),
            Regex("""table(?:\.)?\s*(\d{1,3})""", RegexOption.IGNORE_CASE),
            Regex("""show\s*(\d{1,3})\s*month""", RegexOption.IGNORE_CASE),
            Regex("""show\s*(\d{1,3})\s*first\s*months""", RegexOption.IGNORE_CASE),
            Regex("""(\d{1,3})\s*first\s*months""", RegexOption.IGNORE_CASE)
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

    private fun moneyPerDaysReport(amount: BigDecimal, days: Int, workingHours: Int = 8): String {
        val d = days.coerceAtLeast(1)
        val daily = amount.divide(BigDecimal.valueOf(d.toLong()), 10, RoundingMode.HALF_UP)
        val weekly = daily.multiply(BigDecimal(7))
        val monthlyApprox = daily.multiply(BigDecimal(30))
        val hourly = daily.divide(BigDecimal(24), 10, RoundingMode.HALF_UP)
        val perWorkHour = if (workingHours > 0) daily.divide(BigDecimal(workingHours), 10, RoundingMode.HALF_UP) else BigDecimal.ZERO

        return buildString {
            append("Budget: ${amount.setScale(2, RoundingMode.HALF_UP)} (limit for $d days)").append("\n")
            append("→ Limits — how much you can spend per period:").append("\n")
            append("Per day: ${daily.setScale(2, RoundingMode.HALF_UP)} $").append("\n")
            append("Per week (7d): ${weekly.setScale(2, RoundingMode.HALF_UP)} $").append("\n")
            append("Per month (≈30d): ${monthlyApprox.setScale(2, RoundingMode.HALF_UP)} $").append("\n")
            append("Per hour (24h): ${hourly.setScale(4, RoundingMode.HALF_UP)} $").append("\n")
            append("Per working hour (work $workingHours h/day): ${perWorkHour.setScale(2, RoundingMode.HALF_UP)} $")
        }
    }

    private fun monthlyIncomeReport(monthly: BigDecimal, workingHours: Int): String {
        val perMonth = monthly.setScale(2, RoundingMode.HALF_UP)
        val perYear = monthly.multiply(BigDecimal(12)).setScale(2, RoundingMode.HALF_UP)
        val perDay = monthly.divide(BigDecimal(30), 10, RoundingMode.HALF_UP).setScale(2, RoundingMode.HALF_UP)
        val perWeek = monthly.multiply(BigDecimal(7)).divide(BigDecimal(30), 10, RoundingMode.HALF_UP).setScale(2, RoundingMode.HALF_UP)
        val perHour24 = monthly.divide(BigDecimal(30), 10, RoundingMode.HALF_UP).divide(BigDecimal(24), 10, RoundingMode.HALF_UP).setScale(4, RoundingMode.HALF_UP)
        val perWorkHour = if (workingHours > 0) monthly.divide(BigDecimal(30), 10, RoundingMode.HALF_UP).divide(BigDecimal(workingHours), 10, RoundingMode.HALF_UP).setScale(2, RoundingMode.HALF_UP) else BigDecimal.ZERO.setScale(2)

        return buildString {
            append("Monthly income: ${perMonth} $").append("\n")
            append("Per year: ${perYear} $").append("\n")
            append("Per week (≈): ${perWeek} $").append("\n")
            append("Per day (30d/month): ${perDay} $").append("\n")
            append("Per hour (24h): ${perHour24} $").append("\n")
            append("Per working hour (work $workingHours): ${perWorkHour} $")
        }
    }

    private fun monthlyConversionReport(monthly: BigDecimal, workingHours: Int): String {
        val yearly = monthly.multiply(BigDecimal(12))
        val perDay = monthly.divide(BigDecimal(30), 10, RoundingMode.HALF_UP)
        val per24Hour = perDay.divide(BigDecimal(24), 10, RoundingMode.HALF_UP)
        val perWorkHour = if (workingHours > 0) perDay.divide(BigDecimal(workingHours), 10, RoundingMode.HALF_UP) else BigDecimal.ZERO
        val perWeek = monthly.multiply(BigDecimal(7)).divide(BigDecimal(30), 10, RoundingMode.HALF_UP)
        return buildString {
            append("Monthly summary").append("\n")
            append("Amount: ${monthly.setScale(2, RoundingMode.HALF_UP)} $").append("\n")
            append("Year: ${yearly.setScale(2, RoundingMode.HALF_UP)} $").append("\n")
            append("Per day: ${perDay.setScale(2, RoundingMode.HALF_UP)} $").append("\n")
            append("Per hour (24h): ${per24Hour.setScale(2, RoundingMode.HALF_UP)} $").append("\n")
            append("Per work hour ($workingHours h): ${perWorkHour.setScale(2, RoundingMode.HALF_UP)} $").append("\n")
            append("Per week (≈): ${perWeek.setScale(2, RoundingMode.HALF_UP)} $")
        }
    }

    private fun parsePercent(s: String): BigDecimal {
        val cleaned = s.replace("%", "").replace(',', '.').trim()
        return try {
            BigDecimal(cleaned).divide(BigDecimal(100))
        } catch (e: Exception) {
            BigDecimal.ZERO
        }
    }

    private fun parseDaysFromText(textRaw: String): Int? {
        val text = textRaw.lowercase(Locale.getDefault())

        val explicitDays = Regex("""\b(?:for|in)?\s*(\d{1,4})(?:-(\d{1,4}))?\s*(d(?:ays?)|day)\b""", RegexOption.IGNORE_CASE).find(text)
        if (explicitDays != null) {
            val days = explicitDays.groupValues[1].toIntOrNull() ?: return null
            return days.coerceAtLeast(1)
        }

        val weeks = Regex("""\b(?:for|in)?\s*(\d{1,3})?\s*(week|weeks)\b""", RegexOption.IGNORE_CASE).find(text)
        if (weeks != null) {
            val maybeNum = weeks.groupValues[1]
            val num = if (maybeNum.isBlank()) 1 else maybeNum.toIntOrNull() ?: 1
            return (num * 7).coerceAtLeast(1)
        }

        if (Regex("""\b(?:for|in)?\s*week\b""", RegexOption.IGNORE_CASE).containsMatchIn(text)) return 7

        val dayOnly = Regex("""\b(\d{1,2})\s*(th|st|nd|rd)?\b""").find(text)
        if (dayOnly != null) {
            val daysToDate = parseDaysUntilTargetDate(text)
            if (daysToDate != null) return daysToDate.toInt().coerceAtLeast(1)
        }

        val daysToDate = parseDaysUntilTargetDate(text)
        if (daysToDate != null) return daysToDate.toInt().coerceAtLeast(1)

        return null
    }

    private fun parseTimeToYears(tail: String): Double {
        if (tail.isBlank()) return 1.0
        val lower = tail.lowercase(Locale.getDefault())

        if (Regex("""\byear\b""").containsMatchIn(lower) && !Regex("""\b\d+\s*year\b""").find(lower).isNullOrEmpty()) {
            // handled below
        }

        val y = Regex("""(\d+(?:[.,]\d+)?)\s*(years|year|y)\b""").find(lower)
        if (y != null) return y.groupValues[1].replace(',', '.').toDoubleOrNull() ?: 1.0

        val m = Regex("""(\d+(?:[.,]\d+)?)\s*(months|month|mo)\b""").find(lower)
        if (m != null) return (m.groupValues[1].replace(',', '.').toDoubleOrNull() ?: 0.0) / 12.0

        val days = parseDaysFromText(lower)
        if (days != null) return days.toDouble() / 365.0

        val d = Regex("""(\d+(?:[.,]\d+)?)\s*(days|day)\b""").find(lower)
        if (d != null) return (d.groupValues[1].replace(',', '.').toDoubleOrNull() ?: 0.0) / 365.0

        val w = Regex("""(\d+(?:[.,]\d+)?)\s*(week|weeks)\b""").find(lower)
        if (w != null) {
            val weeks = w.groupValues[1].replace(',', '.').toDoubleOrNull() ?: 0.0
            return (weeks * 7.0) / 365.0
        }

        val daysToDate = parseDaysUntilTargetDate(lower)
        if (daysToDate != null) return daysToDate / 365.0

        val numOnly = Regex("""^(\d+(?:[.,]\d+)?)$""").find(lower)
        if (numOnly != null) return numOnly.groupValues[1].replace(',', '.').toDoubleOrNull() ?: 1.0

        return 1.0
    }

    private fun parseDaysUntilTargetDate(lowerInput: String): Double? {
        val today = LocalDate.now()
        val input = lowerInput.lowercase(Locale.getDefault())

        val iso = Regex("""\b(\d{4})-(\d{2})-(\d{2})\b""").find(input)
        if (iso != null) {
            try {
                val y = iso.groupValues[1].toInt()
                val m = iso.groupValues[2].toInt()
                val d = iso.groupValues[3].toInt()
                val target = LocalDate.of(y, m, d)
                val delta = ChronoUnit.DAYS.between(today, target).toDouble()
                return if (delta >= 0) delta else null
            } catch (e: Exception) {
            }
        }

        val dotPattern = Regex("""\b(\d{1,2})\.(\d{1,2})(?:\.(\d{2,4}))?\b""").find(input)
        if (dotPattern != null) {
            try {
                val day = dotPattern.groupValues[1].toInt()
                val month = dotPattern.groupValues[2].toInt()
                val yearStr = dotPattern.groupValues.getOrNull(3)
                val year = if (!yearStr.isNullOrBlank()) {
                    val y = yearStr.toInt()
                    if (yearStr.length == 2) 2000 + y else y
                } else today.year
                var target = LocalDate.of(year, month.coerceIn(1, 12), day.coerceIn(1, Month.of(month.coerceIn(1,12)).length(today.isLeapYear)))
                if (!target.isAfter(today)) {
                    if (dotPattern.groupValues.getOrNull(3).isNullOrBlank()) {
                        target = target.plusYears(1)
                    }
                }
                val delta = ChronoUnit.DAYS.between(today, target).toDouble()
                return if (delta >= 0) delta else null
            } catch (e: Exception) {
            }
        }

        // English month names and abbreviations
        val dayMonth = Regex("""\b(?:by\s*)?(\d{1,2})\s*(jan|feb|mar|apr|may|jun|jul|aug|sep|oct|nov|dec|january|february|march|april|may|june|july|august|september|october|november|december)\b""", RegexOption.IGNORE_CASE).find(input)
        if (dayMonth != null) {
            val day = dayMonth.groupValues[1].toIntOrNull() ?: return null
            val monthRaw = dayMonth.groupValues[2].lowercase(Locale.getDefault())
            val monthMap = mapOf(
                "jan" to Month.JANUARY, "january" to Month.JANUARY,
                "feb" to Month.FEBRUARY, "february" to Month.FEBRUARY,
                "mar" to Month.MARCH, "march" to Month.MARCH,
                "apr" to Month.APRIL, "april" to Month.APRIL,
                "may" to Month.MAY,
                "jun" to Month.JUNE, "june" to Month.JUNE,
                "jul" to Month.JULY, "july" to Month.JULY,
                "aug" to Month.AUGUST, "august" to Month.AUGUST,
                "sep" to Month.SEPTEMBER, "sept" to Month.SEPTEMBER, "september" to Month.SEPTEMBER,
                "oct" to Month.OCTOBER, "october" to Month.OCTOBER,
                "nov" to Month.NOVEMBER, "november" to Month.NOVEMBER,
                "dec" to Month.DECEMBER, "december" to Month.DECEMBER
            )
            val month = monthMap[monthRaw] ?: return null
            var target = LocalDate.of(today.year, month, day.coerceIn(1, month.length(today.isLeapYear)))
            if (!target.isAfter(today)) {
                target = target.plusYears(1)
            }
            val delta = ChronoUnit.DAYS.between(today, target).toDouble()
            return delta
        }

        val dayOnly = Regex("""\b(\d{1,2})\b""", RegexOption.IGNORE_CASE).find(input)
        if (dayOnly != null) {
            val day = dayOnly.groupValues[1].toIntOrNull() ?: return null
            var target = LocalDate.of(today.year, today.month, day.coerceIn(1, today.month.length(today.isLeapYear)))
            if (!target.isAfter(today)) target = target.plusMonths(1)
            val delta = ChronoUnit.DAYS.between(today, target).toDouble()
            return delta
        }

        return null
    }

    private fun simpleInterestReport(principal: BigDecimal, annualRate: BigDecimal, years: Double): String {
        val principalMC = principal
        val rateBD = BigDecimal(annualRate.toPlainString())
        val yearsBD = BigDecimal.valueOf(years)
        val interest = principalMC.multiply(rateBD).multiply(yearsBD)
        val total = principalMC.add(interest)
        val yearlyProfit = principalMC.multiply(rateBD)
        val perMonth = yearlyProfit.divide(BigDecimal(12), 10, RoundingMode.HALF_UP)
        val perDay = yearlyProfit.divide(BigDecimal(365), 10, RoundingMode.HALF_UP)
        val perHour = perDay.divide(BigDecimal(24), 10, RoundingMode.HALF_UP)
        return buildString {
            append("Simple interest calculation").append("\n")
            append("Principal: ${principalMC.setScale(2, RoundingMode.HALF_UP)} $").append("\n")
            append("Rate: ${(annualRate.multiply(BigDecimal(100))).setScale(4, RoundingMode.HALF_UP)}%").append("\n")
            append("Period: ${"%.4f".format(Locale.getDefault(), years)} years").append("\n")
            append("Total (principal + interest): ${total.setScale(2, RoundingMode.HALF_UP)} $ (interest ${interest.setScale(2, RoundingMode.HALF_UP)} $)").append("\n")
            append("Yearly interest: ${yearlyProfit.setScale(2, RoundingMode.HALF_UP)} $").append("\n")
            append("Per month: ${perMonth.setScale(2, RoundingMode.HALF_UP)} $, per day: ${perDay.setScale(4, RoundingMode.HALF_UP)} $, per hour: ${perHour.setScale(6, RoundingMode.HALF_UP)} $")
        }
    }

    private fun compoundInterestReport(principal: BigDecimal, annualRate: BigDecimal, years: Double, capitalization: String): String {
        val n = when (capitalization) {
            "monthly" -> 12
            "daily" -> 365
            "yearly" -> 1
            else -> 12
        }
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
            append("Compound interest (" + capitalization + ")").append("\n")
            append("Principal: ${principal.setScale(2, RoundingMode.HALF_UP)} $").append("\n")
            append("Rate: ${(annualRate.multiply(BigDecimal(100))).setScale(4, RoundingMode.HALF_UP)}%").append("\n")
            append("Period: ${"%.4f".format(Locale.getDefault(), years)} years").append("\n")
            append("Final amount: ${amount.setScale(2, RoundingMode.HALF_UP)} $ (gain ${totalGain.setScale(2, RoundingMode.HALF_UP)} $)").append("\n")
            append("Avg per year: ${avgPerYear.setScale(2, RoundingMode.HALF_UP)} $").append("\n")
            append("Per month: ${perMonth.setScale(2, RoundingMode.HALF_UP)} $, per day: ${perDay.setScale(4, RoundingMode.HALF_UP)} $, per hour: ${perHour.setScale(6, RoundingMode.HALF_UP)} $")
        }
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

private object EnFinanceV2 {

    fun handleCommand(cmdRaw: String): List<String> {
        val cmd = cmdRaw.trim()
        if (cmd.isEmpty()) return emptyList()
        val lower = cmd.lowercase()

        return when {
            (lower.contains("xirr") || (lower.contains("irr") && lower.contains("x"))) -> handleXirr(cmd)
            lower.contains("xirr") -> handleXirr(cmd)
            lower.contains("npv") -> handleNpv(cmd)
            rootIn(lower, "pmt", "payment", "paymentplan", "installment") -> handlePmt(cmd)
            rootIn(lower, "save", "savings", "savegoal", "savingsgoal") -> handleSavingsGoal(cmd)
            rootIn(lower, "debtplan", "debt", "payoff", "repay", "loanplan") -> handleDebtPlan(cmd)
            else -> emptyList()
        }
    }

    private fun handleXirr(cmd: String): List<String> {
        val cf = parseCashflowsWithDates(cmd)
        if (cf == null) {
            return listOf("XIRR: couldn't find amount@date pairs. Example: 'xirr -10000@2023-01-01 3000@2023-12-01 8000@2024-06-01'")
        }
        if (cf.size < 2) return listOf("XIRR: need at least 2 cashflows (investment and return).")

        val totalIn = cf.filter { it.second < 0.0 }.sumOf { it.second }
        val totalOut = cf.filter { it.second > 0.0 }.sumOf { it.second }
        val spanDays = kotlin.math.max(1.0, ChronoUnit.DAYS.between(cf.first().first, cf.last().first).toDouble())
        val spanYears = spanDays / 365.0

        val result = try {
            xirr(cf)
        } catch (e: Exception) {
            null
        }

        if (result == null) {
            return listOf("XIRR: no convergence (couldn't find solution). Try different inputs or explicit dates.")
        } else {
            val annualPct = result * 100.0
            val monthlyRate = Math.pow(1.0 + result, 1.0 / 12.0) - 1.0
            val dailyRate = Math.pow(1.0 + result, 1.0 / 365.0) - 1.0
            val hourlyRate = Math.pow(1.0 + result, 1.0 / (365.0 * 24.0)) - 1.0

            val npvAt = npvWithDates(result, cf)

            return listOf(
                "XIRR (annual effective): ${formatPercent(annualPct)}%",
                "Equivalent: month ${formatPercent(monthlyRate * 100)}% — day ${formatPercent(dailyRate * 100)}% — hour ${formatPercent(hourlyRate * 100)}%",
                "Period: ${"%.1f".format(spanDays)} days (~${"%.3f".format(spanYears)} years)",
                "Sum of inflows (negative = invested): ${formatMoney(totalIn)}",
                "Sum of outflows (returns): ${formatMoney(totalOut)}",
                "NPV at found rate (sanity): ${formatMoney(npvAt)} (≈0 expected)",
                "Note: XIRR uses exact dates; multiple solutions possible for complex patterns."
            )
        }
    }

    private fun handleNpv(cmd: String): List<String> {
        val rate = extractPercentFromCommand(cmd)
        val flowsWithDates = parseCashflowsWithDates(cmd) // if dates present
        val amounts = parseAmountsSimple(cmd)

        if (rate == null && flowsWithDates == null && amounts.isEmpty()) {
            return listOf("NPV: specify rate and/or cashflows. Example: 'npv 10% -100000 50000 60000' or 'npv 10% -100000@2024-01-01 60000@2025-01-01'")
        }

        val out = mutableListOf<String>()

        if (flowsWithDates != null && rate != null) {
            val npvVal = npvWithDates(rate, flowsWithDates)
            val totalCash = flowsWithDates.sumOf { it.second }
            val durationYears = ChronoUnit.DAYS.between(flowsWithDates.first().first, flowsWithDates.last().first).toDouble() / 365.0
            out.add("NPV (with dates): rate ${formatPercent(rate * 100)}% → ${formatMoney(npvVal)}")
            out.add("Total of flows: ${formatMoney(totalCash)}; period ~ ${"%.3f".format(durationYears)} years")
            val irr = try { xirr(flowsWithDates) } catch (_: Exception) { null }
            if (irr != null) {
                out.add("IRR for same flows: ${formatPercent(irr * 100)}% (annual).")
            }
            out.add("Discounting performed using exact days since first flow.")
            return out
        }

        if (rate != null && amounts.isNotEmpty()) {
            val npvVal = npvEqualPeriods(rate, amounts)
            val total = amounts.sum()
            out.add("NPV (equal periods): rate ${formatPercent(rate * 100)}% → ${formatMoney(npvVal)}")
            out.add("Flows: ${amounts.size} items, total ${formatMoney(total)}")
            val pvPos = amounts.mapIndexed { i, a -> if (a > 0) a / Math.pow(1.0 + rate, i.toDouble()) else 0.0 }.sum()
            val pvNeg = amounts.mapIndexed { i, a -> if (a < 0) a / Math.pow(1.0 + rate, i.toDouble()) else 0.0 }.sum()
            out.add("PV positives: ${formatMoney(pvPos)}, PV negatives: ${formatMoney(pvNeg)}")
            return out
        }

        return listOf("NPV: insufficient data. Example: 'npv 10% -100000 50000 60000' or with dates 'amount@YYYY-MM-DD'.")
    }

    private fun handlePmt(cmd: String): List<String> {
        val nums = Regex("""(-?\d+(?:[.,]\d+)?)""").findAll(cmd).map { it.groupValues[1].replace(',', '.') }.toList()
        if (nums.isEmpty()) return listOf("PMT: specify principal. Example: 'pmt 150000 9% 15 years annuity'")
        val principal = nums[0].toDoubleOrNull() ?: return listOf("PMT: couldn't parse principal.")
        val percent = extractPercentFromCommand(cmd) ?: 0.0
        val years = parseTimeYearsFromText(cmd)
        val months = Math.max(1, (years * 12.0).toInt())
        val lower = cmd.lowercase()
        val mode = if (lower.contains("diff") || lower.contains("differential")) "diff" else "annuity"

        if (mode == "annuity") {
            val pmt = pmtAnnuity(principal, percent, months)
            val total = pmt * months
            val overpay = total - principal
            val effectiveMonthly = if (percent != 0.0) percent / 12.0 else 0.0
            val ear = Math.pow(1.0 + effectiveMonthly, 12.0) - 1.0
            val perDay = pmt / 30.0
            val perHour = perDay / 24.0
            val lines = mutableListOf<String>()
            lines.add("Annuity payment: ${formatMoney(pmt)} $/month (total $months months)")
            lines.add("Effective annual (EAR): ${formatPercent(ear * 100)}% (nominal ${formatPercent(percent * 100)}%)")
            lines.add("Per day: ${formatMoney(perDay)} $; per hour: ${formatMoney(perHour)} $")
            lines.add("Total payback: ${formatMoney(total)} $ (overpay ${formatMoney(overpay)} $)")
            lines.add("First 12 months amortization (payment, interest, principal, remaining):")
            val amort = amortizationAnnuity(principal, percent, months, 12)
            lines.addAll(amort)
            val yearsToShow = Math.min(10, months / 12)
            if (yearsToShow >= 1) {
                lines.add("Annual summaries (approx):")
                var rem = principal
                val rMonth = percent / 12.0
                for (y in 1..yearsToShow) {
                    var paidYear = 0.0
                    var interestYear = 0.0
                    for (m in 1..12) {
                        val interest = rem * rMonth
                        val principalPaid = (pmt - interest).coerceAtMost(rem)
                        rem -= principalPaid
                        interestYear += interest
                        paidYear += (principalPaid + interest)
                        if (rem <= 0.0) break
                    }
                    lines.add("Year $y: paid ${formatMoney(paidYear)}, interest ${formatMoney(interestYear)}, remaining ${formatMoney(rem)}")
                    if (rem <= 0.0) break
                }
            }
            return lines
        } else {
            val (firstPayment, avgPayment, total) = pmtDifferentialSummary(principal, percent, months)
            val overpay = total - principal
            val perDayAvg = avgPayment / 30.0
            val perHourAvg = perDayAvg / 24.0
            val out = mutableListOf<String>()
            out.add("Differential payment (straight-line):")
            out.add("First payment: ${formatMoney(firstPayment)} $, average payment: ${formatMoney(avgPayment)} $")
            out.add("Per day ≈ ${formatMoney(perDayAvg)} $, per hour ≈ ${formatMoney(perHourAvg)} $")
            out.add("Total payback: ${formatMoney(total)} $ (overpay ${formatMoney(overpay)} $)")
            out.add("First 12 months (payment, interest, principal, remaining):")
            out.addAll(amortizationDifferential(principal, percent, months, 12))
            return out
        }
    }

    private fun handleSavingsGoal(cmd: String): List<String> {
        val nums = Regex("""(\d+(?:[.,]\d+)?)""").findAll(cmd).map { it.groupValues[1].replace(',', '.') }.toList()
        if (nums.isEmpty()) return listOf("Save: specify amount. Example: 'save 300000 by 2026-06-01 at 7%'")
        val amount = nums[0].toDoubleOrNull() ?: return listOf("Save: couldn't parse amount.")
        val targetDays = parseDaysFromText(cmd)
        val annualRate = extractPercentFromCommand(cmd) ?: 0.0

        val daysToUse = targetDays ?: (parseTimeYearsFromText(cmd) * 365.0).toInt().coerceAtLeast(30)
        if (daysToUse < 1) return listOf("Save: invalid period.")
        val months = Math.max(1, Math.round(daysToUse / 30.0).toInt())

        if (annualRate == 0.0) {
            val perDay = amount / daysToUse
            val perWeek = perDay * 7.0
            val perMonth = amount / months.toDouble()
            return listOf(
                "Goal: ${formatMoney(amount)} $",
                "Period: $daysToUse days (~${"%.2f".format(months / 12.0)} years)",
                "If no interest — save: per day ${formatMoney(perDay)} $, per week ${formatMoney(perWeek)} $, per month ≈ ${formatMoney(perMonth)} $",
                "Tip: account for fees and round up payments."
            )
        } else {
            val pm = pmtForFutureValue(amount, annualRate, months)
            val totalPaid = pm * months
            val interestEarned = totalPaid - amount
            val perDay = pm / 30.0
            val perWeek = perDay * 7.0
            val effMonthly = annualRate / 12.0
            val ear = Math.pow(1.0 + effMonthly, 12.0) - 1.0
            return listOf(
                "Goal: ${formatMoney(amount)} $",
                "Period: $daysToUse days (~$months months)",
                "Rate: ${formatPercent(annualRate * 100)}% annually (monthly compounding). EAR: ${formatPercent(ear * 100)}%",
                "You need monthly: ${formatMoney(pm)} $ (≈ per day ${formatMoney(perDay)} $, per week ${formatMoney(perWeek)} $)",
                "Total paid: ${formatMoney(totalPaid)} $ (interest ${formatMoney(interestEarned)} $)"
            )
        }
    }

    private fun handleDebtPlan(cmd: String): List<String> {
        val debtTokens = Regex("""([A-Za-z0-9_+-]+)[:=]?\s*([0-9]+(?:[.,][0-9]+)?)\s*@\s*([0-9]+(?:[.,][0-9]+)?)%""").findAll(cmd)
            .mapNotNull {
                val name = it.groupValues[1]
                val amt = it.groupValues[2].replace(',', '.').toDoubleOrNull()
                val pct = it.groupValues[3].replace(',', '.').toDoubleOrNull()
                if (amt != null && pct != null) Debt(name, amt, pct / 100.0) else null
            }.toList()

        val paymentNum = Regex("""payment\s+([0-9]+(?:[.,][0-9]+)?)""", RegexOption.IGNORE_CASE).find(cmd)?.groupValues?.get(1)
            ?: Regex("""monthly\s+([0-9]+(?:[.,][0-9]+)?)""", RegexOption.IGNORE_CASE).find(cmd)?.groupValues?.get(1)

        if (debtTokens.isEmpty() || paymentNum == null) {
            return listOf("DebtPlan: specify debts like name:amount@rate% and total monthly payment. Example: 'debtplan a:100000@12% b:50000@8% payment 15000'")
        }
        val monthlyPayment = paymentNum.replace(',', '.').toDoubleOrNull() ?: return listOf("DebtPlan: couldn't parse payment.")
        val strategy = if (cmd.lowercase().contains("avalanche") || cmd.lowercase().contains("rate")) Strategy.AVALANCHE else Strategy.SNOWBALL

        val plan = simulateDebtPlan(debtTokens, monthlyPayment, strategy)

        val initialTotal = debtTokens.sumOf { it.balance }
        val out = mutableListOf<String>()
        out.add("Debt plan (${if (strategy == Strategy.AVALANCHE) "avalanche (by rate)" else "snowball (by balance)"}):")
        out.add("Initial debt total: ${formatMoney(initialTotal)} $; monthly payment: ${formatMoney(monthlyPayment)} $")
        out.add("Estimated payoff time: ${plan.months} months (~${"%.2f".format(plan.months / 12.0)} years)")
        out.add("Total interest paid: ${formatMoney(plan.totalInterest)} $")
        out.add("Average monthly paid: ${formatMoney(plan.steps.map { it.paid }.average())} $ (over simulated period)")
        out.add("First months (max 20 shown):")
        plan.steps.take(20).forEachIndexed { idx, s ->
            out.add("Month ${idx + 1}: paid ${formatMoney(s.paid)}, interest ${formatMoney(s.interest)}, remaining ${formatMoney(s.remaining)}")
        }
        if (plan.months > plan.steps.size) {
            out.add("(Showing first ${plan.steps.size} months)")
        }
        out.add("Note: model is simple — assumes all monthly payment is allocated by priority; minimal payments not enforced.")
        return out
    }

    private data class Debt(val name: String, var balance: Double, val annualRate: Double)
    private enum class Strategy { SNOWBALL, AVALANCHE }
    private data class DebtPlanStep(val paid: Double, val interest: Double, val remaining: Double)
    private data class DebtPlanResult(val months: Int, val totalInterest: Double, val steps: List<DebtPlanStep>)

    private fun rootIn(textLower: String, vararg roots: String): Boolean {
        for (r in roots) {
            if (textLower.contains(r)) return true
        }
        return false
    }

    private fun parseCashflowsWithDates(cmd: String): List<Pair<LocalDate, Double>>? {
        val list = mutableListOf<Pair<LocalDate, Double>>()
        val pattern = Regex("""(-?\d+(?:[.,]\d+)?)\s*@\s*([0-9]{4}-[0-9]{2}-[0-9]{2}|[0-9]{1,2}\.[0-9]{1,2}\.[0-9]{4}|[0-9]{1,2}-[0-9]{1,2}-[0-9]{4})""")
        val matches = pattern.findAll(cmd).toList()
        if (matches.isEmpty()) return null
        for (m in matches) {
            val amt = m.groupValues[1].replace(',', '.').toDoubleOrNull() ?: continue
            val dateStr = m.groupValues[2]
            val date = tryParseDate(dateStr) ?: continue
            list.add(date to amt)
        }
        return if (list.isEmpty()) null else list.sortedBy { it.first }
    }

    private fun tryParseDate(s: String): LocalDate? {
        return try {
            when {
                s.contains('-') && s.length >= 10 && s[4] == '-' -> LocalDate.parse(s) // yyyy-MM-dd
                s.contains('.') -> {
                    val parts = s.split('.')
                    if (parts.size == 3) {
                        val day = parts[0].toInt()
                        val mon = parts[1].toInt()
                        val year = parts[2].toInt()
                        LocalDate.of(year, mon, day)
                    } else null
                }
                s.contains('-') -> {
                    val parts = s.split('-')
                    if (parts.size == 3 && parts[0].length == 2) {
                        val day = parts[0].toInt()
                        val mon = parts[1].toInt()
                        val year = parts[2].toInt()
                        LocalDate.of(year, mon, day)
                    } else null
                }
                else -> null
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun parseAmountsSimple(cmd: String): List<Double> {
        return Regex("""(-?\d+(?:[.,]\d+)?)""").findAll(cmd)
            .map { it.groupValues[1].replace(',', '.').toDoubleOrNull() }
            .filterNotNull().toList()
    }

    private fun extractPercentFromCommand(cmd: String): Double? {
        val m = Regex("""(\d+(?:[.,]\d+)?)\s*%""").find(cmd)
        return m?.groupValues?.get(1)?.replace(',', '.')?.toDoubleOrNull()?.div(100.0)
    }

    private fun parseTimeYearsFromText(cmd: String): Double {
        val yearMatch = Regex("""(\d+(?:[.,]\d+)?)\s*(years|year|y)""", RegexOption.IGNORE_CASE).find(cmd)
        if (yearMatch != null) return yearMatch.groupValues[1].replace(',', '.').toDoubleOrNull() ?: 1.0
        val monthMatch = Regex("""(\d+(?:[.,]\d+)?)\s*(months|month|mo)""", RegexOption.IGNORE_CASE).find(cmd)
        if (monthMatch != null) return (monthMatch.groupValues[1].replace(',', '.').toDoubleOrNull() ?: 1.0) / 12.0
        val numOnly = Regex("""\b(\d+(?:[.,]\d+)?)\b""").find(cmd)
        if (numOnly != null) {
            val v = numOnly.groupValues[1].replace(',', '.').toDoubleOrNull() ?: return 1.0
            return v
        }
        return 1.0
    }

    private fun xirr(cashflows: List<Pair<LocalDate, Double>>): Double? {
        if (cashflows.size < 2) return null
        val baseDate = cashflows.first().first
        val days = cashflows.map { ChronoUnit.DAYS.between(baseDate, it.first).toDouble() }
        val amounts = cashflows.map { it.second }

        fun f(r: Double): Double {
            return amounts.indices.sumOf { i ->
                val denom = Math.pow(1.0 + r, days[i] / 365.0)
                amounts[i] / denom
            }
        }

        fun fprime(r: Double): Double {
            return amounts.indices.sumOf { i ->
                val t = days[i] / 365.0
                val denom = Math.pow(1.0 + r, t + 1.0)
                -t * amounts[i] / denom
            }
        }

        val allPos = amounts.all { it >= 0.0 }
        val allNeg = amounts.all { it <= 0.0 }
        if (allPos || allNeg) return null

        var r = 0.1
        val maxIter = 200
        val tol = 1e-9
        for (i in 0 until maxIter) {
            val y = f(r)
            val dy = fprime(r)
            if (Math.abs(y) < tol) return r
            if (dy == 0.0) break
            val rNext = r - y / dy
            if (!rNext.isFinite() || rNext <= -0.999999999) break
            if (Math.abs(rNext - r) < 1e-12) return rNext
            r = rNext
        }

        var a = -0.9999999
        var b = 10.0
        var fa = f(a)
        var fb = f(b)
        if (fa.isNaN() || fb.isNaN()) return null
        if (fa * fb > 0) {
            var bb = b
            var fbb = fb
            var attempts = 0
            while (fa * fbb > 0 && attempts < 50) {
                bb *= 2.0
                fbb = f(bb)
                attempts++
            }
            if (fa * fbb > 0) return null
            b = bb
            fb = fbb
        }

        var left = a
        var right = b
        var fleft = fa
        var fright = fb
        for (i in 0 until 200) {
            val mid = (left + right) / 2.0
            val fmid = f(mid)
            if (!fmid.isFinite()) break
            if (Math.abs(fmid) < 1e-9) return mid
            if (fleft * fmid <= 0) {
                right = mid
                fright = fmid
            } else {
                left = mid
                fleft = fmid
            }
            if (Math.abs(right - left) < 1e-12) return (left + right) / 2.0
        }
        return null
    }

    private fun npvWithDates(rate: Double, flows: List<Pair<LocalDate, Double>>): Double {
        val base = flows.first().first
        return flows.fold(0.0) { acc, p ->
            val t = ChronoUnit.DAYS.between(base, p.first).toDouble() / 365.0
            acc + p.second / Math.pow(1.0 + rate, t)
        }
    }

    private fun npvEqualPeriods(rate: Double, amounts: List<Double>): Double {
        return amounts.foldIndexed(0.0) { i, acc, amt ->
            acc + amt / Math.pow(1.0 + rate, i.toDouble())
        }
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
            out.add(String.format("Month %d: payment %s, interest %s, principal %s, remaining %s",
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

    private fun pmtDifferentialSummary(principal: Double, annualRate: Double, months: Int): Triple<Double, Double, Double> {
        if (months <= 0) return Triple(principal, principal, principal)
        val bodyPay = principal / months
        var remaining = principal
        val rMonth = annualRate / 12.0
        var totalPaid = 0.0
        var firstPayment = 0.0
        for (i in 1..months) {
            val interest = remaining * rMonth
            val payment = bodyPay + interest
            if (i == 1) firstPayment = payment
            totalPaid += payment
            remaining -= bodyPay
        }
        val avg = totalPaid / months
        return Triple(firstPayment, avg, totalPaid)
    }

    private fun amortizationDifferential(principal: Double, annualRate: Double, months: Int, showFirst: Int): List<String> {
        val out = mutableListOf<String>()
        val bodyPay = principal / months
        var remaining = principal
        val rMonth = annualRate / 12.0
        val maxShow = showFirst.coerceAtMost(months)
        for (i in 1..maxShow) {
            val interest = remaining * rMonth
            val payment = bodyPay + interest
            remaining -= bodyPay
            if (remaining < 0) remaining = 0.0
            out.add(String.format("Month %d: payment %s, interest %s, principal %s, remaining %s",
                i,
                formatMoney(payment),
                formatMoney(interest),
                formatMoney(bodyPay),
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

    private fun simulateDebtPlan(debtsInput: List<Debt>, monthlyPayment: Double, strategy: Strategy): DebtPlanResult {
        val debts = debtsInput.map { Debt(it.name, it.balance, it.annualRate) }.toMutableList()
        val steps = mutableListOf<DebtPlanStep>()
        var months = 0
        var totalInterest = 0.0
        val maxIter = 60 * 12
        while (debts.any { it.balance > 0.01 } && months < maxIter) {
            months++
            var remainingPayment = monthlyPayment
            val ordered = when (strategy) {
                Strategy.AVALANCHE -> debts.filter { it.balance > 0.01 }.sortedByDescending { it.annualRate }
                Strategy.SNOWBALL -> debts.filter { it.balance > 0.01 }.sortedBy { it.balance }
            }
            var monthInterest = 0.0
            var paidThisMonth = 0.0
            for (d in ordered) {
                if (remainingPayment <= 0.0) break
                val monthlyRate = d.annualRate / 12.0
                val interest = d.balance * monthlyRate
                monthInterest += interest
                val needed = d.balance + interest
                val pay = Math.min(remainingPayment, needed)
                val towardsPrincipal = (pay - interest).coerceAtLeast(0.0)
                d.balance = (d.balance - towardsPrincipal).coerceAtLeast(0.0)
                remainingPayment -= pay
                paidThisMonth += pay
            }
            totalInterest += monthInterest
            val remainingTotal = debts.sumOf { it.balance }
            steps.add(DebtPlanStep(paidThisMonth, monthInterest, remainingTotal))
            if (paidThisMonth <= 0.0) break
        }
        return DebtPlanResult(months, totalInterest, steps)
    }

    private fun formatMoney(v: Double): String {
        return try {
            String.format(Locale.getDefault(), "%.2f", v)
        } catch (_: Exception) {
            v.toString()
        }
    }

    private fun formatPercent(v: Double): String {
        return try {
            String.format(Locale.getDefault(), "%.4f", v)
        } catch (_: Exception) {
            v.toString()
        }
    }

    private fun parseDaysFromText(textRaw: String): Int? {
        val text = textRaw.lowercase()
        val explicitDays = Regex("""\b(?:for|in)?\s*(\d{1,4})(?:-(\d{1,4}))?\s*(d(?:ays?)|day)\b""", RegexOption.IGNORE_CASE).find(text)
        if (explicitDays != null) return explicitDays.groupValues[1].toIntOrNull()?.coerceAtLeast(1)
        val weeks = Regex("""\b(?:for|in)?\s*(\d{1,3})?\s*(week|weeks)\b""", RegexOption.IGNORE_CASE).find(text)
        if (weeks != null) {
            val maybeNum = weeks.groupValues[1]
            val num = if (maybeNum.isBlank()) 1 else maybeNum.toIntOrNull() ?: 1
            return (num * 7).coerceAtLeast(1)
        }
        return null
    }
}
