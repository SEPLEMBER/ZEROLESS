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
        if (prefs.getBoolean("disable_screenshots", false)) {
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

        // --------------------
        // help / справка
        // --------------------
        if (lower.contains("справк") || lower == "help" || lower.contains("помощ")) {
            return listOf(
                "Поддерживаемые команды (примеры):",
                " - энтроп/энтропия <текст>        — посчитать энтропию",
                " - трафик 5gb                     — разделить трафик по дням/часам/сек",
                " - простые проценты 100000 7% 3 года",
                " - сложные проценты 100000 7% 3 года помесячно",
                " - месячный доход 120000 рабочие 8",
                " - ипотек/кредит 3000000 9% 15 лет [первые 12] — платёж и первые N мес амортизации",
                " - инфляц 100000 8% 3 года        — инфляция / эквивалент",
                " - налог 100000 13%               — налог и чистая сумма",
                " - roi 50000 80000                — ROI (инвестиция, итоговая сумма)",
                " - амортиз 200000 5 лет           — линейная амортизация (год/мес)",
                " - окупаемость 500000 20000/мес   — месяцы до окупаемости",
                " - терминал                       — попытка открыть com.example.app",
                " - сравн цен <название цена ...>  — сравнить до 10 позиций",
                " - настройки                      — подсказка: введите 114 или 411"
            )
        }

        // --------------------
        // TERMINAL — open package com.example.app (runs on main thread)
        // --------------------
        if (lower.contains("терминал") || lower.contains("термин")) {
            // Try to launch package com.example.app
            val pkg = "com.example.app"
            return try {
                withContext(Dispatchers.Main) {
                    val intent = packageManager.getLaunchIntentForPackage(pkg)
                    if (intent != null) {
                        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        startActivity(intent)
                        // Inform user
                    } else {
                        // can't start
                    }
                }
                listOf("Пытаюсь открыть терминал (пакет $pkg). Если приложение установлено — оно запустится.")
            } catch (t: Throwable) {
                listOf("Не удалось запустить $pkg: ${t.message ?: t::class.java.simpleName}")
            }
        }

        // --------------------
        // SETTINGS prompt / 114 / 411
        // --------------------
        if (lower.contains("настрой")) {
            return listOf(
                "Введите 114, если хотите попасть в системные настройки.",
                "Введите 411, если хотите попасть в настройки приложения."
            )
        }
        // open system settings if user typed exactly "114"
        if (cmd == "114") {
            return try {
                withContext(Dispatchers.Main) {
                    val intent = Intent(Settings.ACTION_SETTINGS)
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    startActivity(intent)
                }
                listOf("Открываю системные настройки...")
            } catch (t: Throwable) {
                listOf("Не удалось открыть системные настройки: ${t.message ?: t::class.java.simpleName}")
            }
        }
        // open app-specific settings if user typed exactly "411"
        if (cmd == "411") {
            return try {
                withContext(Dispatchers.Main) {
                    val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                    intent.data = Uri.fromParts("package", packageName, null)
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    startActivity(intent)
                }
                listOf("Открываю настройки приложения...")
            } catch (t: Throwable) {
                listOf("Не удалось открыть настройки приложения: ${t.message ?: t::class.java.simpleName}")
            }
        }

        // --------------------
        // Compare prices: "сравн цен ..." up to 10 items
        // --------------------
        val compareRoot = Regex("""(?i)\bсравн\w*\b""").find(lower)
        if (compareRoot != null && (lower.contains("цен") || lower.contains("цена") || lower.contains("цены"))) {
            // find numbers and take name as the substring immediately preceding each number (up to previous number or root)
            val numberRegex = Regex("""(\d+(?:[.,]\d+)?)""")
            val matches = numberRegex.findAll(cmd).toList()
            if (matches.size < 2) return listOf("Недостаточно цен для сравнения. Пример: 'сравн цен яблоки 120 апельсины 140'")
            val rootEnd = compareRoot.range.last + 1
            val stopWords = setOf("сравн", "сравнить", "сравнц", "цен", "цена", "цены", "цену")
            val items = mutableListOf<Pair<String, BigDecimal>>()
            val limit = matches.size.coerceAtMost(10)
            for (i in 0 until limit) {
                val m = matches[i]
                val start = m.range.first
                val prevEnd = if (i == 0) rootEnd else matches[i - 1].range.last + 1
                val labelRaw = try {
                    cmd.substring(prevEnd, start).trim()
                } catch (e: Exception) {
                    ""
                }
                // sanitize label: remove punctuation, split, drop stop words, take last up to 3 tokens
                val tokens = labelRaw.split(Regex("\\s+")).map { it.trim().trim(',', ';', ':', '.', '\"', '\'') }
                    .filter { it.isNotEmpty() && it.lowercase() !in stopWords }
                val name = if (tokens.isEmpty()) "item${i + 1}" else tokens.takeLast(3).joinToString(" ")
                val priceStr = m.groupValues[1].replace(',', '.')
                val price = try { BigDecimal(priceStr) } catch (e: Exception) { BigDecimal.ZERO }
                items.add(name to price)
            }
            if (items.size < 2) return listOf("Недостаточно пар название+цена для сравнения.")
            // sort by price ascending
            val sorted = items.sortedBy { it.second }
            val cheapest = sorted.first().second
            val outputs = mutableListOf<String>()
            outputs.add("Товары по цене (по возрастанию):")
            for ((idx, pair) in sorted.withIndex()) {
                val name = pair.first
                val price = pair.second.setScale(2, RoundingMode.HALF_UP)
                val diffPercent = if (cheapest.compareTo(BigDecimal.ZERO) == 0) BigDecimal.ZERO
                else pair.second.subtract(cheapest).divide(cheapest, 6, RoundingMode.HALF_UP).multiply(BigDecimal(100))
                val diffPercentStr = diffPercent.setScale(2, RoundingMode.HALF_UP).toPlainString()
                val extra = if (idx == 0) "" else " (+$diffPercentStr%)"
                outputs.add("${idx + 1}) $name — ${price.toPlainString()} ₽$extra")
            }
            return outputs
        }

        // --------------------
        // Entropy command
        // --------------------
        val prefix = getString(R.string.cmd_entropy_prefix)
        val entropyPattern = Regex("""(?i)\b\w*${Regex.escape(prefix)}\w*\b[ :]*([\s\S]+)""")
        val entMatch = entropyPattern.find(cmd)
        if (entMatch != null) {
            val payload = entMatch.groupValues.getOrNull(1)?.trim() ?: ""
            if (payload.isEmpty()) return listOf(getString(R.string.usage_entropy))
            val bytes = payload.toByteArray(Charsets.UTF_8)
            val byteEntropyPerSymbol = shannonEntropyBytes(bytes)
            val totalBitsBytes = byteEntropyPerSymbol * bytes.size
            val codepoints = payload.codePointCount(0, payload.length)
            val codepointEntropyPerSymbol = shannonEntropyCodepoints(payload)
            val totalBitsCodepoints = codepointEntropyPerSymbol * codepoints
            val strengthLabel = entropyStrengthLabel(totalBitsBytes)

            return listOf(
                getString(R.string.entropy_per_symbol_bytes, "%.4f".format(byteEntropyPerSymbol)),
                getString(R.string.entropy_total_bits_bytes, "%.2f".format(totalBitsBytes), bytes.size),
                getString(R.string.entropy_per_symbol_codepoints, "%.4f".format(codepointEntropyPerSymbol)),
                getString(R.string.entropy_total_bits_codepoints, "%.2f".format(totalBitsCodepoints), codepoints),
                getString(R.string.entropy_strength, strengthLabel)
            )
        }

        // --------------------
        // Mortgage / Loan (ипотека / кредит) with optional amortization table
        // --------------------
        val mortgagePattern = Regex("""(?i)\b\w*(ипотек|кредит)\w*\b[ :]*([\s\S]+)""")
        val mortMatch = mortgagePattern.find(cmd)
        if (mortMatch != null) {
            val payload = mortMatch.groupValues.getOrNull(2)?.trim() ?: ""
            // parse principal
            val amountMatch = Regex("""(\d+(?:[.,]\d+)?)""").find(payload)
            if (amountMatch == null) return listOf("Использование: ипотека <сумма> <ставка%> <срок (лет|месяцев)> [первые N]")
            val principal = try {
                BigDecimal(amountMatch.groupValues[1].replace(',', '.'))
            } catch (e: Exception) {
                return listOf("Невалидная сумма: ${amountMatch.groupValues[1]}")
            }

            // rate
            val rateMatch = Regex("""(\d+(?:[.,]\d+)?)\s*%""").find(payload)
            val annualRate = if (rateMatch != null) parsePercent(rateMatch.groupValues[0]) else BigDecimal.ZERO

            // time -> years
            val years = parseTimeToYears(payload)
            val months = (years * 12.0).toInt().coerceAtLeast(1)

            // monthly payment (use BigDecimal where possible)
            val monthlyPaymentBD = calculateMonthlyAnnuity(principal, annualRate, months)
            val totalPayment = monthlyPaymentBD.multiply(BigDecimal.valueOf(months.toLong()))
            val totalInterest = totalPayment.subtract(principal)

            // averages
            val paymentPerYear = if (years > 0.0) totalInterest.divide(BigDecimal.valueOf(years), 10, RoundingMode.HALF_UP) else BigDecimal.ZERO
            val perMonthAvg = paymentPerYear.divide(BigDecimal(12), 10, RoundingMode.HALF_UP)
            val perDayAvg = paymentPerYear.divide(BigDecimal(365), 10, RoundingMode.HALF_UP)
            val perHourAvg = perDayAvg.divide(BigDecimal(24), 10, RoundingMode.HALF_UP)
            val ratePercentStr = (annualRate.multiply(BigDecimal(100))).setScale(4, RoundingMode.HALF_UP).toPlainString()

            // check if user requested "первые N" or "табл N" or "первыеN"
            val firstN = findRequestedFirstN(payload) // returns Int? or null
            val outputs = mutableListOf<String>()
            outputs.add("Кредит/Ипотека: сумма ${principal.setScale(2, RoundingMode.HALF_UP)}")
            outputs.add("Ставка (годовая): $ratePercentStr%")
            outputs.add("Срок: ${"%.4f".format(years)} лет (${months} мес)")
            outputs.add("Ежемесячный платёж (аннуитет): ${monthlyPaymentBD.setScale(2, RoundingMode.HALF_UP)}")
            outputs.add("Общая выплата: ${totalPayment.setScale(2, RoundingMode.HALF_UP)} (переплата ${totalInterest.setScale(2, RoundingMode.HALF_UP)})")
            outputs.add("Средняя переплата: в год ${paymentPerYear.setScale(2, RoundingMode.HALF_UP)}, в месяц ${perMonthAvg.setScale(2, RoundingMode.HALF_UP)}, в день ${perDayAvg.setScale(4, RoundingMode.HALF_UP)}, в час ${perHourAvg.setScale(6, RoundingMode.HALF_UP)}")

            if (firstN != null && firstN > 0) {
                // produce amortization rows (first N months), cap to reasonable max
                val cap = firstN.coerceAtMost(240).coerceAtMost(months)
                outputs.add("Первые $cap месяцев амортизации (платёж, проценты, погашение тела, остаток):")
                outputs.addAll(generateAmortizationRows(principal, annualRate, monthlyPaymentBD, months, cap))
                if (cap < months) outputs.add("(Показаны первые $cap из $months месяцев)")
            }

            return outputs
        }

        // --------------------
        // Inflation (инфляц)
        // --------------------
        val inflationPattern = Regex("""(?i)\b\w*(инфл|инфляц)\w*\b[ :]*([\s\S]+)""")
        val inflMatch = inflationPattern.find(cmd)
        if (inflMatch != null) {
            val payload = inflMatch.groupValues.getOrNull(2)?.trim() ?: ""
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
            val perDayPercent = String.format("%.6f", perDayRate * 100.0)
            val perHourPercent = String.format("%.8f", perHourRate * 100.0)
            val amountStr = if (amount > BigDecimal.ZERO) amount.setScale(2, RoundingMode.HALF_UP).toPlainString() else "—"

            val results = mutableListOf<String>()
            results.add("Инфляция — ставка: $ratePercentStr% годовых, период: ${"%.4f".format(years)} лет")
            if (amount > BigDecimal.ZERO) {
                results.add("Сейчас: $amountStr → Через период (номинал): ${futureValue.toPlainString()}")
                results.add("Эквивалент в сегодняшних деньгах: ${presentEquivalent.toPlainString()}")
            } else {
                results.add("Сумма не указана — пример: 'инфляция 100000 8% 3 года'")
            }
            results.add("Приведённые ставки: в день ≈ $perDayPercent%, в час ≈ $perHourPercent%")
            return results
        }

        // --------------------
        // Tax (налог, НДФЛ)
        // --------------------
        if (lower.contains("налог") || lower.contains("ндфл")) {
            val numMatch = Regex("""(\d+(?:[.,]\d+)?)""").find(cmd)
            if (numMatch == null) return listOf("Использование: налог <сумма> [ставка%]")
            val amount = try { BigDecimal(numMatch.groupValues[1].replace(',', '.')) } catch (e: Exception) { return listOf("Невалидная сумма") }
            val rateMatch = Regex("""(\d+(?:[.,]\d+)?)\s*%""").find(cmd)
            val rate = if (rateMatch != null) parsePercent(rateMatch.groupValues[0]) else BigDecimal("0.13") // default 13%
            val tax = amount.multiply(rate).setScale(2, RoundingMode.HALF_UP)
            val net = amount.subtract(tax).setScale(2, RoundingMode.HALF_UP)
            return listOf("Сумма: ${amount.setScale(2, RoundingMode.HALF_UP)}", "Налог: ${tax.toPlainString()}", "После налога: ${net.toPlainString()}")
        }

        // --------------------
        // ROI / инвестиции / окупаемость
        // --------------------
        if (lower.contains("roi") || lower.contains("окупаем") || lower.contains("инвест")) {
            val nums = Regex("""(\d+(?:[.,]\d+)?)""").findAll(cmd).map { it.groupValues[1].replace(',', '.') }.toList()
            if (nums.isEmpty()) return listOf("Использование: roi <вложено> <итоговая сумма>  — или 'инвест 50000 прибыль 15000'")
            // if contains 'прибыл' treat second as profit
            if (nums.size >= 2 && (lower.contains("прибыл") || lower.contains("прибыль"))) {
                val invested = try { BigDecimal(nums[0]) } catch (e: Exception) { BigDecimal.ZERO }
                val profit = try { BigDecimal(nums[1]) } catch (e: Exception) { BigDecimal.ZERO }
                val roi = if (invested > BigDecimal.ZERO) profit.divide(invested, 6, RoundingMode.HALF_UP) else BigDecimal.ZERO
                return listOf("Вложено: ${invested.setScale(2, RoundingMode.HALF_UP)}", "Прибыль: ${profit.setScale(2, RoundingMode.HALF_UP)}", "ROI: ${(roi.multiply(BigDecimal(100))).setScale(4, RoundingMode.HALF_UP)}%")
            } else if (nums.size >= 2) {
                // treat as invested and final amount
                val invested = try { BigDecimal(nums[0]) } catch (e: Exception) { BigDecimal.ZERO }
                val final = try { BigDecimal(nums[1]) } catch (e: Exception) { BigDecimal.ZERO }
                val profit = final.subtract(invested)
                val roi = if (invested > BigDecimal.ZERO) profit.divide(invested, 6, RoundingMode.HALF_UP) else BigDecimal.ZERO
                return listOf("Вложено: ${invested.setScale(2, RoundingMode.HALF_UP)}", "Итог: ${final.setScale(2, RoundingMode.HALF_UP)}", "Прибыль: ${profit.setScale(2, RoundingMode.HALF_UP)}", "ROI: ${(roi.multiply(BigDecimal(100))).setScale(4, RoundingMode.HALF_UP)}%")
            } else {
                return listOf("Недостаточно данных. Пример: 'roi 50000 65000' или 'инвест 50000 прибыль 15000'")
            }
        }

        // --------------------
        // Linear amortization (амортизация)
        // --------------------
        if (lower.contains("амортиз") || lower.contains("амортизац")) {
            val nums = Regex("""(\d+(?:[.,]\d+)?)""").findAll(cmd).map { it.groupValues[1].replace(',', '.') }.toList()
            if (nums.isEmpty()) return listOf("Использование: амортиз <стоимость> <срок в годах> [ликв. стоимость]")
            val cost = try { BigDecimal(nums[0]) } catch (e: Exception) { BigDecimal.ZERO }
            val years = if (nums.size >= 2) try { nums[1].toDouble() } catch (e: Exception) { 1.0 } else 1.0
            val salvage = if (nums.size >= 3) try { BigDecimal(nums[2]) } catch (e: Exception) { BigDecimal.ZERO } else BigDecimal.ZERO
            val usefulYears = if (years <= 0.0) 1.0 else years
            val annual = (cost.subtract(salvage)).divide(BigDecimal.valueOf(usefulYears.toLong()), 10, RoundingMode.HALF_UP)
            val monthly = annual.divide(BigDecimal(12), 10, RoundingMode.HALF_UP)
            return listOf("Амортизация (линейная): стоимость ${cost.setScale(2, RoundingMode.HALF_UP)}", "Срок (лет): ${"%.4f".format(usefulYears)}", "Ежегодно: ${annual.setScale(2, RoundingMode.HALF_UP)}", "Ежемесячно: ${monthly.setScale(2, RoundingMode.HALF_UP)}")
        }

        // --------------------
        // Payback / окупаемость (в мес/годах)
        // --------------------
        if (lower.contains("окупа") || lower.contains("окупаемость")) {
            val nums = Regex("""(\d+(?:[.,]\d+)?)""").findAll(cmd).map { it.groupValues[1].replace(',', '.') }.toList()
            if (nums.size < 2) return listOf("Использование: окупаемость <вложено> <прибыль в мес/год>")
            val invested = try { BigDecimal(nums[0]) } catch (e: Exception) { BigDecimal.ZERO }
            val profitNum = try { BigDecimal(nums[1]) } catch (e: Exception) { BigDecimal.ZERO }
            // decide whether profit is monthly or yearly: check phrase
            val profitIsAnnual = lower.contains("год") || lower.contains("в год") || lower.contains("годовых")
            val monthlyProfit = if (profitIsAnnual) profitNum.divide(BigDecimal(12), 10, RoundingMode.HALF_UP) else profitNum
            if (monthlyProfit <= BigDecimal.ZERO) return listOf("Невалидная прибыль для расчёта окупаемости")
            val months = invested.divide(monthlyProfit, 4, RoundingMode.HALF_UP)
            val years = months.divide(BigDecimal(12), 4, RoundingMode.HALF_UP)
            return listOf("Вложено: ${invested.setScale(2, RoundingMode.HALF_UP)}", "Ежемесячная прибыль: ${monthlyProfit.setScale(2, RoundingMode.HALF_UP)}", "Окупаемость: ${months.setScale(2, RoundingMode.HALF_UP)} мес (~${years.setScale(2, RoundingMode.HALF_UP)} лет)")
        }

        // --------------------
        // Traffic command (existing)
        // --------------------
        if (lower.contains("трафик") || Regex("""\d+\s*(gb|гб|mb|мб|kb|кб|b|байт)""").containsMatchIn(lower)) {
            val m = Regex("""(\d+(?:[.,]\d+)?)\s*(gb|гб|mb|мб|kb|кб|b|байт)?""", RegexOption.IGNORE_CASE).find(lower)
            if (m == null) return listOf(getString(R.string.usage_traffic))
            val numStr = m.groupValues[1].replace(',', '.')
            val unit = m.groupValues.getOrNull(2) ?: ""
            val bytes = parseBytes(numStr.toDouble(), unit)
            val rates = bytesPerMonthToRates(bytes)

            return listOf(
                getString(R.string.traffic_input_approx, m.value.trim(), formatBytesDecimal(bytes)),
                getString(R.string.traffic_per_day, formatBytesDecimalDouble(rates["B/day"] ?: 0.0)),
                getString(R.string.traffic_per_hour, formatBytesDecimalDouble(rates["B/hour"] ?: 0.0)),
                getString(R.string.traffic_per_min, formatBytesDecimalDouble(rates["B/min"] ?: 0.0)),
                getString(R.string.traffic_per_sec, formatBitsPerSecond(rates["B/s"] ?: 0.0))
            )
        }

        // --------------------
        // Simple interest
        // --------------------
        if (lower.contains("прост") && lower.contains("процент")) {
            val numMatch = Regex("""(\d+(?:[.,]\d+)?)""").find(cmd)
            if (numMatch == null) return listOf(getString(R.string.usage_simple_percent))
            val principal = try {
                BigDecimal(numMatch.groupValues[1].replace(',', '.'))
            } catch (e: Exception) {
                return listOf(getString(R.string.err_invalid_amount, numMatch.groupValues[1]))
            }
            val rateMatch = Regex("""(\d+(?:[.,]\d+)?)\s*%""").find(cmd)
            val rate = if (rateMatch != null) parsePercent(rateMatch.groupValues[0])
            else {
                val rest = cmd.substring(numMatch.range.last + 1)
                val nextNum = Regex("""(\d+(?:[.,]\d+)?)""").find(rest)
                if (nextNum != null) parsePercent(nextNum.groupValues[1]) else BigDecimal.ZERO
            }
            val timeYears = parseTimeToYears(cmd)
            return listOfNotNull(simpleInterestReport(principal, rate, timeYears))
        }

        // --------------------
        // Compound interest
        // --------------------
        if (lower.contains("слож") && lower.contains("процент")) {
            val numMatch = Regex("""(\d+(?:[.,]\d+)?)""").find(cmd)
            if (numMatch == null) return listOf(getString(R.string.usage_compound_percent))
            val principal = try {
                BigDecimal(numMatch.groupValues[1].replace(',', '.'))
            } catch (e: Exception) {
                return listOf(getString(R.string.err_invalid_amount, numMatch.groupValues[1]))
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

        // --------------------
        // Monthly conversion
        // --------------------
        if (lower.contains("месяч") && (lower.contains("доход") || lower.contains("зарп") || lower.contains("расход"))) {
            val numMatch = Regex("""(\d+(?:[.,]\d+)?)""").find(cmd)
            if (numMatch == null) return listOf(getString(R.string.usage_monthly))
            val amount = try {
                BigDecimal(numMatch.groupValues[1].replace(',', '.'))
            } catch (e: Exception) {
                return listOf(getString(R.string.err_invalid_amount, numMatch.groupValues[1]))
            }
            val workHoursMatch = Regex("""рабоч\w*\s+(\d{1,2})""", RegexOption.IGNORE_CASE).find(cmd)
            val workHours = workHoursMatch?.groupValues?.get(1)?.toIntOrNull() ?: 8
            return listOfNotNull(monthlyToRatesReport(amount, workHours))
        }

        // fallback
        return listOf(getString(R.string.fallback_unknown_command))
    }

    // --------------------
    // Helpers: mortgage amortization generator
    // --------------------
    private fun calculateMonthlyAnnuity(principal: BigDecimal, annualRate: BigDecimal, months: Int): BigDecimal {
        if (months <= 0) return principal
        val r = annualRate // BigDecimal fractional (e.g., 0.07)
        if (r == BigDecimal.ZERO) {
            return principal.divide(BigDecimal.valueOf(months.toLong()), 10, RoundingMode.HALF_UP)
        }
        // monthly rate as BigDecimal
        val rMonth = r.divide(BigDecimal(12), 20, RoundingMode.HALF_UP)
        // formula P * (r(1+r)^n) / ((1+r)^n - 1)
        val one = BigDecimal.ONE
        // compute (1+r)^n using Double (safe enough) -> convert to BigDecimal
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
            // in last payment adjust rounding issues
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
        // look for "первые N", "первыеN", "табл N", "таблица N"
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
    // --- Entropy helpers
    // --------------------
    private fun shannonEntropyBytes(bytes: ByteArray): Double {
        if (bytes.isEmpty()) return 0.0
        val freq = IntArray(256)
        bytes.forEach { b -> freq[b.toInt() and 0xFF]++ }
        val len = bytes.size.toDouble()
        var entropy = 0.0
        for (count in freq) {
            if (count == 0) continue
            val p = count / len
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
            val p = count / len
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
    // --- Percent helpers
    // --------------------
    private fun parsePercent(s: String): BigDecimal {
        val cleaned = s.replace("%", "").replace(',', '.').trim()
        return try {
            BigDecimal(cleaned).divide(BigDecimal(100))
        } catch (e: Exception) {
            BigDecimal.ZERO
        }
    }

    private fun parseTimeToYears(tail: String): Double {
        if (tail.isBlank()) return 1.0
        val lower = tail.lowercase()
        val y = Regex("""(\d+(?:[.,]\d+)?)\s*(лет|года|год|year|y)""").find(lower)
        if (y != null) return y.groupValues[1].replace(',', '.').toDoubleOrNull() ?: 1.0
        val m = Regex("""(\d+(?:[.,]\d+)?)\s*(месяц|месяцев|мес|month)""").find(lower)
        if (m != null) return (m.groupValues[1].replace(',', '.').toDoubleOrNull() ?: 0.0) / 12.0
        val d = Regex("""(\d+(?:[.,]\d+)?)\s*(дн|дня|дней|day)""").find(lower)
        if (d != null) return (d.groupValues[1].replace(',', '.').toDoubleOrNull() ?: 0.0) / 365.0
        val numOnly = Regex("""^(\d+(?:[.,]\d+)?)$""").find(lower)
        if (numOnly != null) return numOnly.groupValues[1].replace(',', '.').toDoubleOrNull() ?: 1.0
        return 1.0
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
            append(getString(R.string.simple_percent_header)).append("\n")
            append(getString(R.string.simple_percent_principal, principalMC.setScale(2, RoundingMode.HALF_UP))).append("\n")
            append(getString(R.string.simple_percent_rate, (annualRate.multiply(BigDecimal(100))).setScale(4, RoundingMode.HALF_UP))).append("\n")
            append(getString(R.string.simple_percent_period, "%.4f".format(years))).append("\n")
            append(getString(R.string.simple_percent_total, total.setScale(2, RoundingMode.HALF_UP), interest.setScale(2, RoundingMode.HALF_UP))).append("\n")
            append(getString(R.string.simple_percent_yearly, yearlyProfit.setScale(2, RoundingMode.HALF_UP))).append("\n")
            append(getString(R.string.simple_percent_month_day_hour,
                perMonth.setScale(2, RoundingMode.HALF_UP),
                perDay.setScale(4, RoundingMode.HALF_UP),
                perHour.setScale(6, RoundingMode.HALF_UP)))
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
            append(getString(R.string.compound_percent_header, capitalization)).append("\n")
            append(getString(R.string.compound_percent_principal, principal.setScale(2, RoundingMode.HALF_UP))).append("\n")
            append(getString(R.string.compound_percent_rate, (annualRate.multiply(BigDecimal(100))).setScale(4, RoundingMode.HALF_UP))).append("\n")
            append(getString(R.string.compound_percent_period, "%.4f".format(years))).append("\n")
            append(getString(R.string.compound_percent_total, amount.setScale(2, RoundingMode.HALF_UP), totalGain.setScale(2, RoundingMode.HALF_UP))).append("\n")
            append(getString(R.string.compound_percent_avg_year, avgPerYear.setScale(2, RoundingMode.HALF_UP))).append("\n")
            append(getString(R.string.compound_percent_month_day_hour,
                perMonth.setScale(2, RoundingMode.HALF_UP),
                perDay.setScale(4, RoundingMode.HALF_UP),
                perHour.setScale(6, RoundingMode.HALF_UP)))
        }
    }

    private fun monthlyToRatesReport(monthly: BigDecimal, workingHours: Int): String {
        val yearly = monthly.multiply(BigDecimal(12))
        val perDay = monthly.divide(BigDecimal(30), 10, RoundingMode.HALF_UP)
        val per24Hour = perDay.divide(BigDecimal(24), 10, RoundingMode.HALF_UP)
        val perWorkHour = if (workingHours > 0) perDay.divide(BigDecimal(workingHours), 10, RoundingMode.HALF_UP) else BigDecimal.ZERO
        return buildString {
            append(getString(R.string.monthly_header)).append("\n")
            append(getString(R.string.monthly_amount, monthly.setScale(2, RoundingMode.HALF_UP))).append("\n")
            append(getString(R.string.monthly_year, yearly.setScale(2, RoundingMode.HALF_UP))).append("\n")
            append(getString(R.string.monthly_day, perDay.setScale(2, RoundingMode.HALF_UP))).append("\n")
            append(getString(R.string.monthly_hour_24, per24Hour.setScale(2, RoundingMode.HALF_UP))).append("\n")
            append(getString(R.string.monthly_hour_work, workingHours, perWorkHour.setScale(2, RoundingMode.HALF_UP)))
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
