package com.nemesis.droidcrypt

import java.math.BigDecimal
import java.math.RoundingMode
import kotlin.math.ln
import kotlin.math.pow
import java.security.SecureRandom

/**
 * Чистая логика для VprActivity — парсинг, расчёты, формулы.
 * Не использует Android API — можно покрыть unit-тестами.
 */
object VprCore {

    enum class EntropyStrength { WEAK, ACCEPTABLE, NORMAL, STRONG }

    data class EntropyResult(
        val byteEntropyPerSymbol: Double,
        val totalBitsBytes: Double,
        val byteLength: Int,
        val codepointEntropyPerSymbol: Double,
        val totalBitsCodepoints: Double,
        val codepointCount: Int,
        val strength: EntropyStrength
    )

    data class TrafficRates(
        val bytesPerMonth: Long,
        val bytesPerDay: Double,
        val bytesPerHour: Double,
        val bytesPerMin: Double,
        val bytesPerSec: Double
    )

    data class SimpleInterestResult(
        val principal: BigDecimal,
        val annualRate: BigDecimal,
        val years: Double,
        val interestTotal: BigDecimal,
        val totalAmount: BigDecimal,
        val yearlyProfit: BigDecimal,
        val perMonth: BigDecimal,
        val perDay: BigDecimal,
        val perHour: BigDecimal
    )

    data class CompoundInterestResult(
        val principal: BigDecimal,
        val annualRate: BigDecimal,
        val years: Double,
        val capitalization: String,
        val finalAmount: BigDecimal,
        val totalGain: BigDecimal,
        val avgPerYear: BigDecimal,
        val perMonth: BigDecimal,
        val perDay: BigDecimal,
        val perHour: BigDecimal
    )

    data class MonthlyRatesResult(
        val monthly: BigDecimal,
        val yearly: BigDecimal,
        val perDay: BigDecimal,
        val perHour24: BigDecimal,
        val perWorkHour: BigDecimal,
        val workHours: Int
    )

    // ---- Normalization / simple extractors ----

    fun normalizeInput(raw: String): String =
        raw.trim()
            .replace(Regex("\\s+"), " ")
            .replace(',', '.') // unify decimals
            .trim()

    /**
     * Find first numeric token in text (like "200" or "200.5") — returns null if none.
     */
    fun findFirstNumber(raw: String): Double? {
        val m = Regex("""(\d+(?:\.\d+)?)""").find(raw)
        return m?.groupValues?.get(1)?.toDoubleOrNull()
    }

    /**
     * Find first percent in text (like "7%" or "7 %") — returns 0..1 as BigDecimal or null.
     */
    fun findPercentBigDecimal(raw: String): BigDecimal? {
        val m = Regex("""(\d+(?:[.,]\d+)?)\s*%""").find(raw)
        val s = m?.groupValues?.get(1)?.replace(',', '.')
        return s?.let { try { BigDecimal(it).divide(BigDecimal(100)) } catch (e: Exception) { null } }
    }

    /**
     * Parse time expressions anywhere in the text and return years as Double.
     * Supports years, months, days and single numeric (assumed years).
     */
    fun parseTimeToYears(raw: String): Double {
        val lower = raw.lowercase()
        val y = Regex("""(\d+(?:[.,]\d+)?)\s*(лет|года|год|year|y)""").find(lower)
        if (y != null) return y.groupValues[1].replace(',', '.').toDoubleOrNull() ?: 1.0
        val m = Regex("""(\d+(?:[.,]\d+)?)\s*(месяц|месяцев|мес|month)""").find(lower)
        if (m != null) return (m.groupValues[1].replace(',', '.').toDoubleOrNull() ?: 0.0) / 12.0
        val d = Regex("""(\d+(?:[.,]\d+)?)\s*(дн|дня|дней|day)""").find(lower)
        if (d != null) return (d.groupValues[1].replace(',', '.').toDoubleOrNull() ?: 0.0) / 365.0
        val numOnly = Regex("""^(\d+(?:[.,]\d+)?)$""").find(lower)
        if (numOnly != null) return numOnly.groupValues[1].replace(',', '.').toDoubleOrNull() ?: 1.0
        return 1.0 // default 1 year
    }

    // ---- Entropy ----

    /**
     * Shannon entropy for byte array (bits per byte)
     */
    fun shannonEntropyBytes(bytes: ByteArray): Double {
        if (bytes.isEmpty()) return 0.0
        val freq = IntArray(256)
        for (b in bytes) freq[b.toInt() and 0xFF]++
        val len = bytes.size.toDouble()
        var entropy = 0.0
        for (c in freq) {
            if (c == 0) continue
            val p = c / len
            entropy -= p * (ln(p) / ln(2.0))
        }
        return entropy
    }

    /**
     * Shannon entropy for codepoints (characters) — bits per symbol (codepoint)
     */
    fun shannonEntropyCodepoints(s: String): Double {
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

    fun entropyStrength(totalBits: Double): EntropyStrength {
        return when {
            totalBits < 28.0 -> EntropyStrength.WEAK
            totalBits < 46.0 -> EntropyStrength.ACCEPTABLE
            totalBits < 71.0 -> EntropyStrength.NORMAL
            else -> EntropyStrength.STRONG
        }
    }

    fun calculateEntropy(s: String): EntropyResult {
        val bytes = s.toByteArray(Charsets.UTF_8)
        val byteEntropyPerSymbol = shannonEntropyBytes(bytes)
        val totalBitsBytes = byteEntropyPerSymbol * bytes.size
        val codepointEntropyPerSymbol = shannonEntropyCodepoints(s)
        val codepointCount = s.codePointCount(0, s.length)
        val totalBitsCodepoints = codepointEntropyPerSymbol * codepointCount
        val strength = entropyStrength(totalBitsBytes)
        return EntropyResult(
            byteEntropyPerSymbol = byteEntropyPerSymbol,
            totalBitsBytes = totalBitsBytes,
            byteLength = bytes.size,
            codepointEntropyPerSymbol = codepointEntropyPerSymbol,
            totalBitsCodepoints = totalBitsCodepoints,
            codepointCount = codepointCount,
            strength = strength
        )
    }

    // ---- Traffic ----

    /**
     * Parse amount + unit (unit may be "gb","mb","kb","b" or localized variants).
     * Uses decimal units: 1 kB = 1000 B, 1 MB = 1000^2 B, etc.
     */
    fun parseBytes(amount: Double, unitRaw: String?): Long {
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

    fun bytesPerMonthToRates(bytesPerMonth: Long, daysInMonth: Int = 30): TrafficRates {
        val bMonth = bytesPerMonth.toDouble()
        val bDay = bMonth / daysInMonth
        val bHour = bDay / 24.0
        val bMin = bHour / 60.0
        val bSec = bMin / 60.0
        return TrafficRates(
            bytesPerMonth = bytesPerMonth,
            bytesPerDay = bDay,
            bytesPerHour = bHour,
            bytesPerMin = bMin,
            bytesPerSec = bSec
        )
    }

    // ---- Percent calculations ----

    /**
     * Simple interest: P * r * t
     */
    fun simpleInterest(principal: BigDecimal, annualRate: BigDecimal, years: Double): SimpleInterestResult {
        val yearsBD = BigDecimal.valueOf(years)
        val interest = principal.multiply(annualRate).multiply(yearsBD)
        val total = principal.add(interest)
        val yearlyProfit = principal.multiply(annualRate)
        val perMonth = yearlyProfit.divide(BigDecimal(12), 10, RoundingMode.HALF_UP)
        val perDay = yearlyProfit.divide(BigDecimal(365), 10, RoundingMode.HALF_UP)
        val perHour = perDay.divide(BigDecimal(24), 10, RoundingMode.HALF_UP)
        return SimpleInterestResult(
            principal = principal,
            annualRate = annualRate,
            years = years,
            interestTotal = interest,
            totalAmount = total,
            yearlyProfit = yearlyProfit,
            perMonth = perMonth,
            perDay = perDay,
            perHour = perHour
        )
    }

    /**
     * Compound interest with capitalization periods per year n (1, 12, 365)
     * We calculate using Double pow for fractional years then convert back to BigDecimal.
     */
    fun compoundInterest(principal: BigDecimal, annualRate: BigDecimal, years: Double, capitalization: String): CompoundInterestResult {
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
        val finalAmount = BigDecimal.valueOf(amountDouble).setScale(10, RoundingMode.HALF_UP)
        val totalGain = finalAmount.subtract(principal)
        val avgPerYear = if (years > 0.0) totalGain.divide(BigDecimal.valueOf(years), 10, RoundingMode.HALF_UP) else BigDecimal.ZERO
        val perMonth = avgPerYear.divide(BigDecimal(12), 10, RoundingMode.HALF_UP)
        val perDay = avgPerYear.divide(BigDecimal(365), 10, RoundingMode.HALF_UP)
        val perHour = perDay.divide(BigDecimal(24), 10, RoundingMode.HALF_UP)
        return CompoundInterestResult(
            principal = principal,
            annualRate = annualRate,
            years = years,
            capitalization = capitalization,
            finalAmount = finalAmount,
            totalGain = totalGain,
            avgPerYear = avgPerYear,
            perMonth = perMonth,
            perDay = perDay,
            perHour = perHour
        )
    }

    // ---- Monthly -> hourly/day conversions ----

    fun monthlyToRates(monthly: BigDecimal, workingHoursPerDay: Int = 8): MonthlyRatesResult {
        val yearly = monthly.multiply(BigDecimal(12))
        val perDay = monthly.divide(BigDecimal(30), 10, RoundingMode.HALF_UP)
        val per24Hour = perDay.divide(BigDecimal(24), 10, RoundingMode.HALF_UP)
        val perWorkHour = if (workingHoursPerDay > 0) perDay.divide(BigDecimal(workingHoursPerDay), 10, RoundingMode.HALF_UP) else BigDecimal.ZERO
        return MonthlyRatesResult(
            monthly = monthly,
            yearly = yearly,
            perDay = perDay,
            perHour24 = per24Hour,
            perWorkHour = perWorkHour,
            workHours = workingHoursPerDay
        )
    }

    // ---- Utilities for testing/etc ----

    fun randomBytes(size: Int): ByteArray {
        val rnd = SecureRandom()
        val arr = ByteArray(size)
        rnd.nextBytes(arr)
        return arr
    }
}
