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
        // TERMINAL — open package (supports specifying package or short name)
        // --------------------
        if (lower.contains("терминал") || lower.contains("термин")) {
            // try to extract token after the word terminal (if present)
            val tkn = Regex("""(?i)\bтерминал\b[ :]*([^\s]+)""").find(cmd)?.groupValues?.getOrNull(1)
                ?: Regex("""(?i)\bтермин\b[ :]*([^\s]+)""").find(cmd)?.groupValues?.getOrNull(1)

            val candidates = mutableListOf<String>()
            if (!tkn.isNullOrBlank()) {
                val token = tkn.trim()
                if (token.contains('.')) {
                    candidates.add(token)
                } else {
                    candidates.add(token)
                    candidates.add("com.$token")
                    candidates.add("org.$token")
                    candidates.add("net.$token")
                    candidates.add("com.${token.replace('-', '.')}")
                }
            }
            candidates.addAll(listOf("org.syndes.terminal", "com.termux", "com.termux.boot", "com.android.terminal"))

            return try {
                var launchedPkg: String? = null
                withContext(Dispatchers.Main) {
                    for (pkg in candidates) {
                        try {
                            var intent = packageManager.getLaunchIntentForPackage(pkg)
                            if (intent != null) {
                                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                startActivity(intent)
                                launchedPkg = pkg
                                break
                            }
                            intent = Intent(Intent.ACTION_MAIN).apply {
                                addCategory(Intent.CATEGORY_LAUNCHER)
                                setPackage(pkg)
                            }
                            val resolveInfos = packageManager.queryIntentActivities(intent, 0)
                            if (resolveInfos.isNotEmpty()) {
                                val ri = resolveInfos[0]
                                val explicit = Intent(Intent.ACTION_MAIN).apply {
                                    addCategory(Intent.CATEGORY_LAUNCHER)
                                    setClassName(ri.activityInfo.packageName, ri.activityInfo.name)
                                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                }
                                startActivity(explicit)
                                launchedPkg = ri.activityInfo.packageName
                                break
                            }
                        } catch (_: Exception) {
                        }
                    }
                }
                if (launchedPkg != null) {
                    listOf("Пытаюсь открыть терминал (пакет $launchedPkg). Если приложение установлено — оно запустится.")
                } else {
                    val candidatesStr = candidates.joinToString(", ")
                    listOf("Не нашёл установленного терминала среди кандидатов ($candidatesStr). Укажите корректный пакет, например: 'терминал com.termux', или откройте страницу приложения в настройках.")
                }
            } catch (t: Throwable) {
                listOf("Не удалось запустить терминал: ${t.message ?: t::class.java.simpleName}")
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
        // Compare prices
        // --------------------
        val compareRoot = Regex("""(?i)\bсравн\w*\b""").find(lower)
        if (compareRoot != null && (lower.contains("цен") || lower.contains("цена") || lower.contains("цены"))) {
            val numberRegex = Regex("""(\d+(?:[.,]\d+)?)""")
            val matches = numberRegex.findAll(cmd).toList()
            if (matches.size < 2) return listOf("Недостаточно цен для сравнения. Пример: 'сравн цен яблоки 120 апельсины 140'")
            val rootEnd = compareRoot.range.last + 1
            val items = mutableListOf<Pair<String, BigDecimal>>()
            val limit = matches.size.coerceAtMost(10)
            for (i in 0 until limit) {
                val m = matches[i]
                val start = m.range.first
                val prevEnd = if (i == 0) rootEnd else matches[i - 1].range.last + 1
                val effectivePrevEnd = if (colonIndex >= 0 && colonIndex < start && colonIndex >= prevEnd) colonIndex + 1 else prevEnd
                val labelRaw = try { cmd.substring(effectivePrevEnd, start).trim() } catch (e: Exception) { "" }
                val stopWords = setOf("сравн", "сравнить", "сравнц", "цен", "цена", "цены", "цену", "эти", "пожалуйста", "пожалуйстa")
                val tokens = labelRaw.split(Regex("\\s+")).map { it.trim().trim(',', ';', ':', '.', '\"', '\'') }
                    .filter { it.isNotEmpty() && it.lowercase() !in stopWords }
                val name = if (tokens.isEmpty()) "item${i + 1}" else tokens.takeLast(3).joinToString(" ")
                val priceStr = m.groupValues[1].replace(',', '.')
                val price = try { BigDecimal(priceStr) } catch (e: Exception) { BigDecimal.ZERO }
                items.add(name to price)
            }
            if (items.size < 2) return listOf("Недостаточно пар название+цена для сравнения.")
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
        // Entropy command (improved)
        // --------------------
        val prefix = getString(R.string.cmd_entropy_prefix)
        val entropyPattern = Regex("""(?i)\b\w*${Regex.escape(prefix)}\w*\b[ :]*([\s\S]+)""")
        val entMatch = entropyPattern.find(cmd)
        if (entMatch != null) {
            var payload = entMatch.groupValues.getOrNull(1)?.trim() ?: ""
            if (payload.isEmpty() && colonIndex >= 0) payload = cmd.substring(colonIndex + 1).trim()
            if (payload.isEmpty()) return listOf(getString(R.string.usage_entropy))

            // bytes-level entropy
            val bytes = payload.toByteArray(Charsets.UTF_8)
            val byteEntropyPerSymbol = shannonEntropyBytes(bytes)
            val totalBitsBytes = byteEntropyPerSymbol * bytes.size

            // codepoint-level entropy (Unicode symbols)
            val codepoints = payload.codePointCount(0, payload.length)
            val codepointEntropyPerSymbol = shannonEntropyCodepoints(payload)
            val totalBitsCodepoints = codepointEntropyPerSymbol * codepoints

            // background rarity metric — marks characters like '&' as rarer
            val bgSurprisal = backgroundAverageSurprisalBits(payload)

            val strengthLabel = entropyStrengthLabel(totalBitsBytes)

            return listOf(
                getString(R.string.entropy_per_symbol_bytes, "%.4f".format(byteEntropyPerSymbol)),
                getString(R.string.entropy_total_bits_bytes, "%.2f".format(totalBitsBytes), bytes.size),
                getString(R.string.entropy_per_symbol_codepoints, "%.4f".format(codepointEntropyPerSymbol)),
                getString(R.string.entropy_total_bits_codepoints, "%.2f".format(totalBitsCodepoints), codepoints),
                "Средняя фон. непредсказуемость (по таблице частот): ${"%.4f".format(bgSurprisal)} бит/симв",
                getString(R.string.entropy_strength, strengthLabel)
            )
        }

        // --------------------
        // НАКОПИТЬ — now supports % and fixed days/weeks/dates
        // usage: "накопить <сумма> к <дате>" or "накопить <сумма> за <N дней>" or "накопить <сумма> за 30 дней под 5%"
        // --------------------
        if (lower.contains("накоп") || lower.contains("накопить") || lower.contains("накопление") || lower.contains("отлож")) {
            val payload = if (colonIndex >= 0) cmd.substring(colonIndex + 1) else cmd
            val nums = Regex("""(\d+(?:[.,]\d+)?)""").findAll(payload).map { it.groupValues[1].replace(',', '.') }.toList()
            if (nums.isEmpty()) return listOf("Использование: накопить <сумма> к <дате> | накопить <сумма> за <N дней> [под X%]")

            val amount = try { BigDecimal(nums[0]) } catch (e: Exception) { BigDecimal.ZERO }

            // days from text if present
            val daysFromText = parseDaysFromText(payload)
            val daysDouble = daysFromText?.toDouble()
            val yearsFallback = parseTimeToYears(payload)
            val daysFromYears = (yearsFallback * 365.0)

            val daysToUse = when {
                daysDouble != null -> daysDouble
                daysFromYears > 0.0 -> daysFromYears
                else -> 30.0
            }.coerceAtLeast(1.0)

            // check percent (optional)
            val percentRegex = Regex("""(\d+(?:[.,]\d+)?)\s*%""")
            val percMatch = percentRegex.find(payload)
            val annualRate = percMatch?.groupValues?.getOrNull(1)?.replace(',', '.')?.let {
                try { BigDecimal(it).divide(BigDecimal(100)) } catch (_: Exception) { BigDecimal.ZERO }
            } ?: BigDecimal.ZERO

            // basic per-day/week/month if no interest
            val perDay = amount.divide(BigDecimal.valueOf(daysToUse), 10, RoundingMode.HALF_UP)
            val perWeek = perDay.multiply(BigDecimal(7))
            val perMonth = perDay.multiply(BigDecimal(30))

            val outputs = mutableListOf<String>()
            outputs.add("Цель: ${amount.setScale(2, RoundingMode.HALF_UP)}")
            outputs.add("Период: ${"%.1f".format(daysToUse)} дней (~${"%.2f".format(daysToUse / 30.0)} мес)")
            if (annualRate == BigDecimal.ZERO) {
                outputs.add("Нужно откладывать: в день ${perDay.setScale(2, RoundingMode.HALF_UP)} ₽, в неделю ${perWeek.setScale(2, RoundingMode.HALF_UP)} ₽, в месяц ≈ ${perMonth.setScale(2, RoundingMode.HALF_UP)} ₽")
            } else {
                // compute monthly payment (annuity) required with monthly compounding as approximation
                val months = Math.max(1, Math.round(daysToUse / 30.0).toInt())
                val rMonth = annualRate.divide(BigDecimal(12), 20, RoundingMode.HALF_UP)
                val n = months
                val pm: BigDecimal = if (rMonth.compareTo(BigDecimal.ZERO) == 0) {
                    amount.divide(BigDecimal.valueOf(n.toLong()), 10, RoundingMode.HALF_UP)
                } else {
                    // PMT = FV * (r) / ((1+r)^n - 1)
                    val rDouble = rMonth.toDouble()
                    val factor = (1.0 + rDouble).pow(n.toDouble())
                    val numerator = amount.toDouble() * rDouble
                    val denom = factor - 1.0
                    val pmt = if (denom == 0.0) amount.toDouble() / n.toDouble() else numerator / denom
                    BigDecimal.valueOf(pmt).setScale(10, RoundingMode.HALF_UP)
                }
                val perMonthWithInterest = pm.setScale(2, RoundingMode.HALF_UP)
                val perDayApprox = pm.divide(BigDecimal(30), 10, RoundingMode.HALF_UP)
                val perWeekApprox = perDayApprox.multiply(BigDecimal(7))
                outputs.add("Учитывая ${ (annualRate.multiply(BigDecimal(100))).setScale(2, RoundingMode.HALF_UP) }% годовых (капитализация помесячно):")
                outputs.add("Нужно ежемесячно: ${perMonthWithInterest} ₽ (приблизительно в день ${perDayApprox.setScale(2, RoundingMode.HALF_UP)} ₽, в неделю ${perWeekApprox.setScale(2, RoundingMode.HALF_UP)} ₽)")
            }
            return outputs
        }

        // --------------------
        // Mortgage / Loan
        // --------------------
        val mortgagePattern = Regex("""(?i)\b\w*(ипотек|кредит)\w*\b[ :]*([\s\S]+)""")
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

            if (principal == null) return listOf("Использование: ипотека <сумма> <ставка%> <срок (лет|месяцев)> [первые N]")

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
            outputs.add("Кредит/Ипотека: сумма ${principal.setScale(2, RoundingMode.HALF_UP)}")
            outputs.add("Ставка (годовая): $ratePercentStr%")
            outputs.add("Срок: ${"%.4f".format(years)} лет (${months} мес)")
            outputs.add("Ежемесячный платёж (аннуитет): ${monthlyPaymentBD.setScale(2, RoundingMode.HALF_UP)}")
            outputs.add("Общая выплата: ${totalPayment.setScale(2, RoundingMode.HALF_UP)} (переплата ${totalInterest.setScale(2, RoundingMode.HALF_UP)})")
            outputs.add("Средняя переплата: в год ${paymentPerYear.setScale(2, RoundingMode.HALF_UP)}, в месяц ${perMonthAvg.setScale(2, RoundingMode.HALF_UP)}, в день ${perDayAvg.setScale(4, RoundingMode.HALF_UP)}, в час ${perHourAvg.setScale(6, RoundingMode.HALF_UP)}")

            if (firstN != null && firstN > 0) {
                val cap = firstN.coerceAtMost(240).coerceAtMost(months)
                outputs.add("Первые $cap месяцев амортизации (платёж, проценты, погашение тела, остаток):")
                outputs.addAll(generateAmortizationRows(principal, annualRate, monthlyPaymentBD, months, cap))
                if (cap < months) outputs.add("(Показаны первые $cap из $months месяцев)")
            }

            return outputs
        }

        // --------------------
        // Inflation
        // --------------------
        val inflationPattern = Regex("""(?i)\b\w*(инфл|инфляц)\w*\b[ :]*([\s\S]+)""")
        val inflMatch = inflationPattern.find(cmd)
        if (inflMatch != null) {
            var payload = inflMatch.groupValues.getOrNull(2)?.trim() ?: ""
            if (payload.isEmpty() && colonIndex >= 0) payload = cmd.substring(colonIndex + 1).trim()
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
        // Tax
        // --------------------
        if (lower.contains("налог") || lower.contains("ндфл")) {
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
            if (amount == null) return listOf("Использование: налог <сумма> [ставка%] — пример: 'налог 100000 13%' или 'налог на 13% от 100'")

            val rate = percVal ?: BigDecimal("0.13") // default 13%
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
            if (nums.size >= 2 && (lower.contains("прибыл") || lower.contains("прибыль"))) {
                val invested = try { BigDecimal(nums[0]) } catch (e: Exception) { BigDecimal.ZERO }
                val profit = try { BigDecimal(nums[1]) } catch (e: Exception) { BigDecimal.ZERO }
                val roi = if (invested > BigDecimal.ZERO) profit.divide(invested, 6, RoundingMode.HALF_UP) else BigDecimal.ZERO
                return listOf("Вложено: ${invested.setScale(2, RoundingMode.HALF_UP)}", "Прибыль: ${profit.setScale(2, RoundingMode.HALF_UP)}", "ROI: ${(roi.multiply(BigDecimal(100))).setScale(4, RoundingMode.HALF_UP)}%")
            } else if (nums.size >= 2) {
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
        // Linear amortization
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
        // Payback / окупаемость
        // --------------------
        if (lower.contains("окупа") || lower.contains("окупаемость")) {
            val nums = Regex("""(\d+(?:[.,]\d+)?)""").findAll(cmd).map { it.groupValues[1].replace(',', '.') }.toList()
            if (nums.size < 2) return listOf("Использование: окупаемость <вложено> <прибыль в мес/год>")
            val invested = try { BigDecimal(nums[0]) } catch (e: Exception) { BigDecimal.ZERO }
            val profitNum = try { BigDecimal(nums[1]) } catch (e: Exception) { BigDecimal.ZERO }
            val profitIsAnnual = lower.contains("год") || lower.contains("в год") || lower.contains("годовых")
            val monthlyProfit = if (profitIsAnnual) profitNum.divide(BigDecimal(12), 10, RoundingMode.HALF_UP) else profitNum
            if (monthlyProfit <= BigDecimal.ZERO) return listOf("Невалидная прибыль для расчёта окупаемости")
            val months = invested.divide(monthlyProfit, 4, RoundingMode.HALF_UP)
            val years = months.divide(BigDecimal(12), 4, RoundingMode.HALF_UP)
            return listOf("Вложено: ${invested.setScale(2, RoundingMode.HALF_UP)}", "Ежемесячная прибыль: ${monthlyProfit.setScale(2, RoundingMode.HALF_UP)}", "Окупаемость: ${months.setScale(2, RoundingMode.HALF_UP)} мес (~${years.setScale(2, RoundingMode.HALF_UP)} лет)")
        }

        // --------------------
        // Traffic command (supports days)
        // --------------------
        if (lower.contains("трафик") || Regex("""\d+\s*(gb|гб|mb|мб|kb|кб|b|байт)""").containsMatchIn(lower)) {
            val m = Regex("""(\d+(?:[.,]\d+)?)\s*(gb|гб|mb|мб|kb|кб|b|байт)?""", RegexOption.IGNORE_CASE).find(lower)
            if (m == null) return listOf(getString(R.string.usage_traffic))
            val numStr = m.groupValues[1].replace(',', '.')
            val unit = m.groupValues.getOrNull(2) ?: ""
            val bytes = parseBytes(numStr.toDouble(), unit)

            val days = parseDaysFromText(cmd)
            if (days != null) {
                val bPerDay = bytes.toDouble() / days.toDouble()
                val bPerHour = bPerDay / 24.0
                val bPerMin = bPerHour / 60.0
                val bPerSec = bPerMin / 60.0
                return listOf(
                    getString(R.string.traffic_input_approx, m.value.trim(), formatBytesDecimal(bytes)),
                    "Период: $days дней",
                    "В день: ${formatBytesDecimalDouble(bPerDay)}",
                    "В час: ${formatBytesDecimalDouble(bPerHour)}",
                    "В мин: ${formatBytesDecimalDouble(bPerMin)}",
                    "В сек: ${formatBitsPerSecond(bPerSec)}"
                )
            } else {
                val rates = bytesPerMonthToRates(bytes)
                return listOf(
                    getString(R.string.traffic_input_approx, m.value.trim(), formatBytesDecimal(bytes)),
                    getString(R.string.traffic_per_day, formatBytesDecimalDouble(rates["B/day"] ?: 0.0)),
                    getString(R.string.traffic_per_hour, formatBytesDecimalDouble(rates["B/hour"] ?: 0.0)),
                    getString(R.string.traffic_per_min, formatBytesDecimalDouble(rates["B/min"] ?: 0.0)),
                    getString(R.string.traffic_per_sec, formatBitsPerSecond(rates["B/s"] ?: 0.0))
                )
            }
        }

        // --------------------
        // Simple interest
        // --------------------
        if (lower.contains("прост") && lower.contains("процент")) {
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
                else -> return listOf(getString(R.string.usage_simple_percent))
            }
            val rate = percVal ?: BigDecimal.ZERO
            val timeYears = parseTimeToYears(cmd)
            return listOfNotNull(simpleInterestReport(principal, rate, timeYears))
        }

        // --------------------
        // Compound interest
        // --------------------
        if (lower.contains("слож") && lower.contains("процент")) {
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
                else -> return listOf(getString(R.string.usage_compound_percent))
            }
            val rate = percVal ?: BigDecimal.ZERO
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
        // Monthly conversion & BUDGET: supports "за N дней" and "на N дней" and "на неделю"
        // Now budget = limits (сколько можно тратить)
        // Examples: "бюджет 3000 на 7 дней"
        // --------------------
        if (lower.contains("бюджет") || (lower.contains("месяч") && (lower.contains("доход") || lower.contains("зарп") || lower.contains("расход")))) {
            val payload = if (colonIndex >= 0) cmd.substring(colonIndex + 1) else cmd
            val amountMatch = Regex("""(\d+(?:[.,]\d+)?)""").find(payload)
            if (amountMatch == null) return listOf(getString(R.string.usage_monthly))
            val amount = try { BigDecimal(amountMatch.groupValues[1].replace(',', '.')) } catch (e: Exception) { return listOf(getString(R.string.err_invalid_amount, amountMatch.groupValues[1])) }

            val workHoursMatch = Regex("""рабоч\w*\s+(\d{1,2})""", RegexOption.IGNORE_CASE).find(payload)
            val workHours = workHoursMatch?.groupValues?.get(1)?.toIntOrNull() ?: 8

            val days = parseDaysFromText(payload)
            if (days != null) {
                // compute limits: per day/week/month/hour/work-hour
                return listOfNotNull(moneyPerDaysReport(amount, days, workHours))
            }

            // if it's a monthly income (not budget), use monthlyToRatesReport
            if (lower.contains("месяч") && (lower.contains("доход") || lower.contains("зарп") || lower.contains("расход"))) {
                return listOfNotNull(monthlyToRatesReport(amount, workHours))
            }

            // fallback: monthly conversion semantics assume this is income if "месяч" phrase present, else present budgets as monthly->rates
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

    /**
     * backgroundAverageSurprisalBits:
     * Оценивает среднюю "фоную" непредсказуемость символов по заранее заданной таблице частот.
     * Это НЕ заменяет эмпирическую энтропию, но показывает насколько символы в строке редки относительно обычного текста.
     */
    private fun backgroundAverageSurprisalBits(s: String): Double {
        if (s.isEmpty()) return 0.0
        // простая таблица частот (примерные значения) для латиницы/кириллицы/пробела/цифр/пунктуации
        val freqMap = mutableMapOf<Int, Double>().apply {
            // space
            put(' '.code, 0.13)
            // common Russian letters (approx)
            val commonRu = mapOf(
                'о' to 0.09, 'е' to 0.07, 'а' to 0.062, 'и' to 0.06, 'н' to 0.055,
                'т' to 0.052, 'с' to 0.045, 'р' to 0.039, 'в' to 0.037, 'л' to 0.035
            )
            for ((ch, v) in commonRu) put(ch.code, v)
            // digits
            for (d in '0'..'9') put(d.code, 0.01)
            // Latin letters
            for (c in 'a'..'z') put(c.code, 0.01)
            for (c in 'A'..'Z') put(c.code, 0.01)
            // punctuation common
            put('.'.code, 0.03); put(','.code, 0.03); put('-'.code, 0.005); put('_'.code, 0.002)
            // fallback: others considered rare
        }
        val defaultProb = 0.0005 // rare by default
        val cps = s.codePoints().toArray()
        var sum = 0.0
        for (cp in cps) {
            val p = freqMap[cp] ?: defaultProb
            // surprisal in bits: -log2(p)
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
     * Используется для команды "бюджет X на N дней" — возвращает лимиты (сколько можно тратить),
     * а не доход. Чётко показывает лимит в день/нед/мес/час/рабочий час.
     */
    private fun moneyPerDaysReport(amount: BigDecimal, days: Int, workingHours: Int = 8): String {
        val d = days.coerceAtLeast(1)
        val daily = amount.divide(BigDecimal.valueOf(d.toLong()), 10, RoundingMode.HALF_UP)
        val weekly = daily.multiply(BigDecimal(7))
        val monthlyApprox = daily.multiply(BigDecimal(30))
        val hourly = daily.divide(BigDecimal(24), 10, RoundingMode.HALF_UP)
        val perWorkHour = if (workingHours > 0) daily.divide(BigDecimal(workingHours), 10, RoundingMode.HALF_UP) else BigDecimal.ZERO

        return buildString {
            append("Бюджет: ${amount.setScale(2, RoundingMode.HALF_UP)} (лимит на $d дней)").append("\n")
            append("→ Это лимиты — сколько можно тратить, чтобы запас хватил на указанный период:").append("\n")
            append("В день: ${daily.setScale(2, RoundingMode.HALF_UP)} ₽").append("\n")
            append("В неделю (7д): ${weekly.setScale(2, RoundingMode.HALF_UP)} ₽").append("\n")
            append("В месяц (≈30д): ${monthlyApprox.setScale(2, RoundingMode.HALF_UP)} ₽").append("\n")
            append("В час (24ч): ${hourly.setScale(4, RoundingMode.HALF_UP)} ₽").append("\n")
            append("За рабочий час (рабочие $workingHours): ${perWorkHour.setScale(2, RoundingMode.HALF_UP)} ₽")
        }
    }

    /**
     * monthlyToRatesReport:
     * Используется для команды "месячный доход X" — превращает месячный доход в:
     * год, неделя (условно 7/30 части месяца), день (30-дн), час, рабочий час.
     */
    private fun monthlyToRatesReport(monthly: BigDecimal, workingHours: Int): String {
        // assume month ~ 30 days for straightforward conversion
        val perMonth = monthly.setScale(2, RoundingMode.HALF_UP)
        val perYear = monthly.multiply(BigDecimal(12)).setScale(2, RoundingMode.HALF_UP)
        val perDay = monthly.divide(BigDecimal(30), 10, RoundingMode.HALF_UP).setScale(2, RoundingMode.HALF_UP)
        // weeks per month ≈ 30/7, so week = month * 7 / 30
        val perWeek = monthly.multiply(BigDecimal(7)).divide(BigDecimal(30), 10, RoundingMode.HALF_UP).setScale(2, RoundingMode.HALF_UP)
        val perHour24 = monthly.divide(BigDecimal(30), 10, RoundingMode.HALF_UP).divide(BigDecimal(24), 10, RoundingMode.HALF_UP).setScale(4, RoundingMode.HALF_UP)
        val perWorkHour = if (workingHours > 0) monthly.divide(BigDecimal(30), 10, RoundingMode.HALF_UP).divide(BigDecimal(workingHours), 10, RoundingMode.HALF_UP).setScale(2, RoundingMode.HALF_UP) else BigDecimal.ZERO.setScale(2)

        return buildString {
            append("Месячный доход: ${perMonth}").append("\n")
            append("В год: ${perYear}").append("\n")
            append("В неделю (≈): ${perWeek}").append("\n")
            append("В день (30д/мес): ${perDay}").append("\n")
            append("В час (24ч): ${perHour24}").append("\n")
            append("За рабочий час (рабочие $workingHours): ${perWorkHour}")
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

    /**
     * parseDaysFromText:
     * - распознаёт "на N дней", "за N дней", "на неделю", "на 2 недели", "на 7 дней"
     * - возвращает количество дней (Int) либо null
     * - учитывает "к 7 ноября", "2 числа", "7 ноября" через parseDaysUntilTargetDate
     */
    private fun parseDaysFromText(textRaw: String): Int? {
        val text = textRaw.lowercase()

        // explicit "на 7 дней" / "за 7 дней" / "7 дней"
        val explicitDays = Regex("""\b(?:на|за)?\s*(\d{1,4})(?:-(\d{1,4}))?\s*(дн|дня|дней|дн\.)\b""", RegexOption.IGNORE_CASE).find(text)
        if (explicitDays != null) {
            val days = explicitDays.groupValues[1].toIntOrNull() ?: return null
            return days.coerceAtLeast(1)
        }

        // "на неделю", "на 2 недели", "2 недели", "за неделю"
        val weeks = Regex("""\b(?:на|за)?\s*(\d{1,3})?\s*(недел|нед|недели|неделя)\b""", RegexOption.IGNORE_CASE).find(text)
        if (weeks != null) {
            val maybeNum = weeks.groupValues[1]
            val num = if (maybeNum.isBlank()) 1 else maybeNum.toIntOrNull() ?: 1
            return (num * 7).coerceAtLeast(1)
        }

        if (Regex("""\b(?:на|за)?\s*недел(?:я|и|ь)\b""", RegexOption.IGNORE_CASE).containsMatchIn(text)) return 7

        // day-only like "2 числа" or "числа 2" (supports optional whitespace)
        val dayOnly = Regex("""\b(\d{1,2})\s*(числ|числа|число)\b""", RegexOption.IGNORE_CASE).find(text)
        if (dayOnly != null) {
            val daysToDate = parseDaysUntilTargetDate(text)
            if (daysToDate != null) return daysToDate.toInt().coerceAtLeast(1)
        }

        val daysToDate = parseDaysUntilTargetDate(text)
        if (daysToDate != null) return daysToDate.toInt().coerceAtLeast(1)

        return null
    }

    /**
     * parseTimeToYears: (keeps previous behavior but uses parseDaysFromText)
     */
    private fun parseTimeToYears(tail: String): Double {
        if (tail.isBlank()) return 1.0
        val lower = tail.lowercase()

        if (Regex("""\b(через\s+)?год\b""").containsMatchIn(lower)) return 1.0

        val y = Regex("""(\d+(?:[.,]\d+)?)\s*(лет|года|год|year|y)""").find(lower)
        if (y != null) return y.groupValues[1].replace(',', '.').toDoubleOrNull() ?: 1.0

        val m = Regex("""(\d+(?:[.,]\d+)?)\s*(месяц|месяцев|мес|month)""").find(lower)
        if (m != null) return (m.groupValues[1].replace(',', '.').toDoubleOrNull() ?: 0.0) / 12.0

        val days = parseDaysFromText(lower)
        if (days != null) return days.toDouble() / 365.0

        val d = Regex("""(\d+(?:[.,]\d+)?)\s*(дн|дня|дней|day)""").find(lower)
        if (d != null) return (d.groupValues[1].replace(',', '.').toDoubleOrNull() ?: 0.0) / 365.0

        val w = Regex("""(\d+(?:[.,]\d+)?)\s*(недел|нед|недели|неделя)""").find(lower)
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

    /**
     * Parse expressions like:
     * - "к 7 ноября", "7 ноября" -> days from today until that date (next occurrence)
     * - "2 числа" -> nearest 2nd of month (next if passed)
     *
     * IMPORTANT: allow absence of whitespace between day and month (e.g. "7ноября").
     */
    private fun parseDaysUntilTargetDate(lowerInput: String): Double? {
        val today = LocalDate.now()

        // pattern "к 7 ноября" or "7 ноября" or "7ноября" (allow optional whitespace)
        val dayMonth = Regex("""\b(?:к\s*)?(\d{1,2})\s*(янв|фев|мар|апр|май|мая|июн|июл|авг|сен|окт|ноя|дек|января|февраля|марта|апреля|мая|июня|июля|августа|сентября|октября|ноября|декабря)\b""", RegexOption.IGNORE_CASE).find(lowerInput)
        if (dayMonth != null) {
            val day = dayMonth.groupValues[1].toIntOrNull() ?: return null
            val monthRaw = dayMonth.groupValues[2].lowercase()
            val monthMap = mapOf(
                "янв" to Month.JANUARY, "января" to Month.JANUARY,
                "фев" to Month.FEBRUARY, "февраля" to Month.FEBRUARY,
                "мар" to Month.MARCH, "марта" to Month.MARCH,
                "апр" to Month.APRIL, "апреля" to Month.APRIL,
                "май" to Month.MAY, "мая" to Month.MAY,
                "июн" to Month.JUNE, "июня" to Month.JUNE,
                "июл" to Month.JULY, "июля" to Month.JULY,
                "авг" to Month.AUGUST, "августа" to Month.AUGUST,
                "сен" to Month.SEPTEMBER, "сентября" to Month.SEPTEMBER,
                "окт" to Month.OCTOBER, "октября" to Month.OCTOBER,
                "ноя" to Month.NOVEMBER, "ноября" to Month.NOVEMBER,
                "дек" to Month.DECEMBER, "декабря" to Month.DECEMBER
            )
            val month = monthMap[monthRaw] ?: return null
            var target = LocalDate.of(today.year, month, day.coerceIn(1, month.length(today.isLeapYear)))
            if (!target.isAfter(today)) {
                target = target.plusYears(1)
            }
            val delta = ChronoUnit.DAYS.between(today, target).toDouble()
            return delta
        }

        // pattern "2 числа" or "числа 2" (supports optional whitespace)
        val dayOnly = Regex("""\b(\d{1,2})\s*(числ|числа|число)\b""", RegexOption.IGNORE_CASE).find(lowerInput)
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
        val perWeek = monthly.multiply(BigDecimal(7)).divide(BigDecimal(30), 10, RoundingMode.HALF_UP)
        return buildString {
            append(getString(R.string.monthly_header)).append("\n")
            append(getString(R.string.monthly_amount, monthly.setScale(2, RoundingMode.HALF_UP))).append("\n")
            append(getString(R.string.monthly_year, yearly.setScale(2, RoundingMode.HALF_UP))).append("\n")
            append(getString(R.string.monthly_day, perDay.setScale(2, RoundingMode.HALF_UP))).append("\n")
            append(getString(R.string.monthly_hour_24, per24Hour.setScale(2, RoundingMode.HALF_UP))).append("\n")
            append(getString(R.string.monthly_hour_work, workingHours, perWorkHour.setScale(2, RoundingMode.HALF_UP))).append("\n")
            append("В неделю (≈): ${perWeek.setScale(2, RoundingMode.HALF_UP)}")
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
