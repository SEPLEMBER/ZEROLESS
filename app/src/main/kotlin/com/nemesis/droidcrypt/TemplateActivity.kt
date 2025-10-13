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
import kotlin.math.*

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

        addSystemLine("Шаблон: в модулях есть физические команды. Введите 'help' для краткой справки (внизу).")
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

// --------------------
// CommandsMain: расчёт времени по расстоянию и скорости
//  (команда 1: "время / time / сколько времени ...")
// --------------------
private object CommandsMain {

    fun handleCommand(cmdRaw: String): List<String> {
        val cmd = cmdRaw.trim()
        val lower = cmd.lowercase(Locale.getDefault())
        if (lower.contains("время") || lower.contains("сколько времени") || lower.contains("time")) {
            return handleTimeForDistance(cmd)
        }
        return emptyList()
    }

    private fun handleTimeForDistance(cmd: String): List<String> {
        val distMeters = parseDistanceMeters(cmd) ?: return listOf(
            "Время: не нашёл расстояние. Пример: 'время 150 км при 80 км/ч' или 'time 120km at 60km/h'"
        )
        val speedMetersPerSec = parseSpeedToMPerS(cmd)
        if (speedMetersPerSec == null) {
            return listOf(
                "Время: не нашёл скорость. Пример: 'время 150 км при 80 км/ч' или 'time 10km at 5 m/s'"
            )
        }

        val seconds = distMeters / speedMetersPerSec
        val roundedHalfMin = ( (seconds / 60.0) * 2.0 ).roundToInt() / 2.0 // minutes rounded to 0.5
        val hours = (seconds / 3600.0)
        val hh = floor(hours).toInt()
        val remMinutes = (hours - hh) * 60.0
        val mm = floor(remMinutes).toInt()
        val ss = ((remMinutes - mm) * 60.0).roundToInt()

        val sb = mutableListOf<String>()
        sb.add("Расстояние: ${formatDistanceNice(distMeters)}")
        sb.add("Скорость: ${formatSpeedNice(speedMetersPerSec)}")
        sb.add("Время в секундах: ${"%.2f".format(seconds)} s")
        sb.add("Время: ${hh} ч ${mm} мин ${ss} сек")
        sb.add("Округлённо до половины минуты: ${roundedHalfMin} мин (≈ ${formatMinutesToHMS(roundedHalfMin)})")
        // Доп. детальные разбивки
        val totalMinutesExact = seconds / 60.0
        val minutesFull = floor(totalMinutesExact).toInt()
        val halfMinuteParts = ( (totalMinutesExact - minutesFull) * 60.0 ) // seconds leftover in last minute
        sb.add("Точное время: ${"%.3f".format(totalMinutesExact)} мин (или ${"%.1f".format(totalMinutesExact/60.0)} ч)")
        // полезная нотация: для путешествий
        val per100kmSec = if (distMeters >= 1000.0) {
            val timePer100km = (100000.0 / distMeters) * seconds // not exact; compute time to travel 100km at same speed directly
            null
        } else null
        // добавим скорость в km/h и pace (мин/км)
        val speedKmh = speedMetersPerSec * 3.6
        if (speedMetersPerSec > 0.0) {
            val paceSecPerKm = 1000.0 / speedMetersPerSec
            sb.add("Пэйс: ${formatPace(paceSecPerKm)} (мин/км)")
            sb.add("Скорость: ${"%.2f".format(speedKmh)} км/ч")
        }
        return sb
    }

    // Парсит расстояние: число + (km|км|m|м)
    private fun parseDistanceMeters(s: String): Double? {
        val lower = s.lowercase(Locale.getDefault())
        // ищем "число + unit" ближайшую пару
        val pattern = Regex("""(-?\d+(?:[.,]\d+)?)\s*(km|км|m\b|м\b|meters|метр|метров)?""", RegexOption.IGNORE_CASE)
        val matches = pattern.findAll(lower).toList()
        if (matches.isEmpty()) return null
        // prefer match where unit present and is km or m
        for (m in matches) {
            val num = m.groupValues[1].replace(',', '.').toDoubleOrNull() ?: continue
            val unit = m.groupValues.getOrNull(2) ?: ""
            if (unit.isNotBlank()) {
                return when (unit) {
                    "km", "км" -> num * 1000.0
                    "m", "м", "метр", "метров", "meters" -> num
                    else -> num
                }
            }
        }
        // fallback: first numeric as meters if no explicit unit
        val first = matches.first()
        val valNum = first.groupValues[1].replace(',', '.').toDoubleOrNull()
        return valNum
    }

    // Парсит скорость в m/s поддерживая km/h и m/s
    private fun parseSpeedToMPerS(s: String): Double? {
        val lower = s.lowercase(Locale.getDefault())
        // patterns: "80 км/ч", "80 km/h", "5 m/s", "5 м/с"
        val re = Regex("""(-?\d+(?:[.,]\d+)?)\s*(km/h|км/ч|км/час|kmh|km/h|m/s|м/с|м/сек|mps)\b""", RegexOption.IGNORE_CASE)
        val m = re.find(lower)
        if (m != null) {
            val num = m.groupValues[1].replace(',', '.').toDoubleOrNull() ?: return null
            val unit = m.groupValues[2].lowercase(Locale.getDefault())
            return when {
                unit.contains("km") || unit.contains("км") -> num / 3.6
                unit.contains("m/s") || unit.contains("м/с") || unit.contains("mps") -> num
                else -> num
            }
        }
        // support "at 80" style (assume km/h if integer likely)
        val simple = Regex("""\bat\s+(-?\d+(?:[.,]\d+)?)\b""", RegexOption.IGNORE_CASE).find(lower)
        if (simple != null) {
            val num = simple.groupValues[1].replace(',', '.').toDoubleOrNull() ?: return null
            // assume km/h
            return num / 3.6
        }
        // support "при 80" (assume km/h)
        val pri = Regex("""при\s+(-?\d+(?:[.,]\d+)?)\s*(?:км/ч|km/h|км|km)?""", RegexOption.IGNORE_CASE).find(lower)
        if (pri != null) {
            val num = pri.groupValues[1].replace(',', '.').toDoubleOrNull() ?: return null
            return num / 3.6
        }
        // last resort: any standalone number followed by "км" nearby means maybe total distance only -> no speed
        return null
    }

    private fun formatDistanceNice(meters: Double): String {
        return if (meters >= 1000.0) {
            "${"%.3f".format(meters/1000.0)} km"
        } else {
            "${"%.1f".format(meters)} m"
        }
    }

    private fun formatSpeedNice(mps: Double): String {
        val kmh = mps * 3.6
        return "${"%.2f".format(kmh)} km/h (${String.format("%.2f", mps)} m/s)"
    }

    private fun formatMinutesToHMS(minutes: Double): String {
        val totalSec = (minutes * 60.0).roundToInt()
        val h = totalSec / 3600
        val m = (totalSec % 3600) / 60
        val s = totalSec % 60
        return "${h}ч ${m}мин ${s}сек"
    }

    private fun formatPace(secPerKm: Double): String {
        val m = floor(secPerKm / 60.0).toInt()
        val s = (secPerKm - m * 60).roundToInt()
        return "${m}:${if (s<10) "0$s" else "$s"} мин/км"
    }
}

// --------------------
// CommandsV2: скорость, необходимая чтобы пройти/проехать расстояние за заданное время.
//   Также различение "идти" и "ехать" (walk vs drive).
//   (команда 2: "сколько ехать / сколько идти / скорость ...")
// --------------------
private object CommandsV2 {

    fun handleCommand(cmdRaw: String): List<String> {
        val cmd = cmdRaw.trim()
        val lower = cmd.lowercase(Locale.getDefault())

        val rootSpeed = listOf("скорост", "скорость", "сколько ех", "сколько ид", "сколько идти", "сколько ехать")
        if (rootSpeed.any { lower.contains(it) } || lower.contains("сколько") && (lower.contains("идти") || lower.contains("ехать"))) {
            return handleRequiredSpeed(cmd)
        }

        return emptyList()
    }

    private fun handleRequiredSpeed(cmd: String): List<String> {
        val distMeters = parseDistanceMeters(cmd) ?: return listOf("Speed: не нашёл расстояние. Пример: 'сколько ехать 10 км за 15 минут' или 'сколько идти 5km за 50min'")
        val timeSec = parseTimeToSeconds(cmd) ?: return listOf("Speed: не нашёл целевого времени. Примеры: 'за 15 минут', 'в 1:30', 'за 90 мин', 'за 0.5 часа'")
        val lower = cmd.lowercase(Locale.getDefault())
        val walking = listOf("идт", "ход", "пеш", "шаг")
        val driving = listOf("ех", "езд", "машин", "авто", "вел")
        val mode = when {
            walking.any { lower.contains(it) } -> "walk"
            driving.any { lower.contains(it) } -> "drive"
            else -> "either"
        }

        val neededMps = distMeters / timeSec
        val neededKmh = neededMps * 3.6
        val paceSecPerKm = if (neededMps > 0) 1000.0 / neededMps else Double.POSITIVE_INFINITY

        val lines = mutableListOf<String>()
        lines.add("Расстояние: ${formatDistanceNice(distMeters)}")
        lines.add("Целевое время: ${formatSecondsNice(timeSec)}")
        lines.add("Требуемая скорость: ${"%.2f".format(neededKmh)} км/ч (${String.format("%.2f", neededMps)} м/с)")

        if (mode == "walk" || mode == "either") {
            // walking-specific: pace (мин/км) and шаги (приблизительно)
            lines.add("Пэйс для пешей: ${formatPace(paceSecPerKm)} мин/км")
            // estimate steps: assume stride ~0.75m for walking average
            val steps = (distMeters / 0.75).roundToInt()
            lines.add("Оценка шагов (при шаге ≈0.75м): ≈ $steps шагов")
            // comfortable walking speeds:
            lines.add("Примечание по скоростям пешком: комфортная ~5 км/ч, быстрая ~6.5 км/ч")
        }
        if (mode == "drive" || mode == "either") {
            // driving: provide mph too (if useful) and compare to limits
            val mph = neededKmh / 1.609344
            lines.add("Для езды: ${"%.2f".format(mph)} миль/ч (mph)")
            lines.add("Примечание: соблюдайте дорожные ограничения и безопасность.")
        }

        // additional human-friendly tiers
        lines.add("Детали:")
        lines.add(" - Средняя скорость = ${"%.2f".format(neededKmh)} км/ч")
        lines.add(" - Пэйс = ${formatPace(paceSecPerKm)} (мин/км)")
        return lines
    }

    // parse distance reuse (supports km/m)
    private fun parseDistanceMeters(s: String): Double? {
        val lower = s.lowercase(Locale.getDefault())
        val pattern = Regex("""(\d+(?:[.,]\d+)?)\s*(km|км|m\b|м\b|meters|метр|метров)""", RegexOption.IGNORE_CASE)
        val m = pattern.find(lower)
        if (m != null) {
            val num = m.groupValues[1].replace(',', '.').toDoubleOrNull() ?: return null
            val unit = m.groupValues[2]
            return when (unit.lowercase(Locale.getDefault())) {
                "km", "км" -> num * 1000.0
                else -> num
            }
        }
        // fallback: first number as km
        val anyNum = Regex("""(\d+(?:[.,]\d+)?)""").find(lower)?.groupValues?.get(1)?.replace(',', '.')
        return anyNum?.toDoubleOrNull()
    }

    // parse time to seconds: supports "15 минут", "1:30", "1.5 часа", "90 мин", "за 1 час 20 мин"
    private fun parseTimeToSeconds(s: String): Double? {
        val lower = s.lowercase(Locale.getDefault())

        // hh:mm pattern
        val hhmm = Regex("""\b(\d{1,2}):(\d{1,2})(?::(\d{1,2}))?\b""").find(lower)
        if (hhmm != null) {
            val h = hhmm.groupValues[1].toIntOrNull() ?: 0
            val m = hhmm.groupValues[2].toIntOrNull() ?: 0
            val sec = hhmm.groupValues.getOrNull(3)?.toIntOrNull() ?: 0
            return (h * 3600 + m * 60 + sec).toDouble()
        }

        // "X час(а/ов) Y мин" etc.
        val hoursMatch = Regex("""(\d+(?:[.,]\d+)?)\s*(час|часа|часов|h)\b""").find(lower)
        val minsMatch = Regex("""(\d+(?:[.,]\d+)?)\s*(мин|минут|m\b)\b""").find(lower)

        if (hoursMatch != null || minsMatch != null) {
            val hours = hoursMatch?.groupValues?.get(1)?.replace(',', '.')?.toDoubleOrNull() ?: 0.0
            val mins = minsMatch?.groupValues?.get(1)?.replace(',', '.')?.toDoubleOrNull() ?: 0.0
            return hours * 3600.0 + mins * 60.0
        }

        // decimal hours "1.5 часа"
        val decHour = Regex("""(\d+(?:[.,]\d+)?)\s*ч(?:аса|ас)?\b""").find(lower)
        if (decHour != null) {
            val h = decHour.groupValues[1].replace(',', '.').toDoubleOrNull() ?: return null
            return h * 3600.0
        }

        // minutes only
        val onlyMin = Regex("""\b(\d+(?:[.,]\d+)?)\s*(мин|минут)\b""").find(lower)
        if (onlyMin != null) {
            val m = onlyMin.groupValues[1].replace(',', '.').toDoubleOrNull() ?: return null
            return m * 60.0
        }

        // bare number with "за" -> treat as minutes
        val za = Regex("""за\s*(\d+(?:[.,]\d+)?)\b""").find(lower)
        if (za != null) {
            val v = za.groupValues[1].replace(',', '.').toDoubleOrNull() ?: return null
            // heuristic: if <=6 -> hours? no, assume minutes
            return v * 60.0
        }

        return null
    }

    private fun formatDistanceNice(meters: Double): String {
        return if (meters >= 1000.0) "${"%.3f".format(meters/1000.0)} km" else "${"%.1f".format(meters)} m"
    }

    private fun formatSecondsNice(secDouble: Double): String {
        val s = secDouble.roundToInt()
        val h = s / 3600
        val m = (s % 3600) / 60
        val sRem = s % 60
        return "${h}ч ${m}м ${sRem}с"
    }

    private fun formatPace(secPerKm: Double): String {
        if (!secPerKm.isFinite() || secPerKm.isNaN()) return "—"
        val m = floor(secPerKm / 60.0).toInt()
        val s = (secPerKm - m * 60).roundToInt()
        return "${m}:${if (s<10) "0$s" else "$s"}"
    }
}

// --------------------
// CommandsV3: пара сложных физических формул + help (нижний приоритет)
//   (команда 3: projectile + energies)
// --------------------
private object CommandsV3 {

    fun handleCommand(cmdRaw: String): List<String> {
        val cmd = cmdRaw.trim()
        val lower = cmd.lowercase(Locale.getDefault())

        // Projectile (параболическая траектория)
        val projRoots = listOf("проек", "парабол", "пуля", "брос", "пуск", "projectile", "range", "trajectory")
        if (projRoots.any { lower.contains(it) } || (lower.contains("угол") && lower.contains("м/с"))) {
            return handleProjectile(cmd)
        }

        // Energy calculations
        val energyRoots = listOf("энерг", "кинет", "потенц", "потенциальн", "kinetic", "potential")
        if (energyRoots.any { lower.contains(it) }) {
            return handleEnergy(cmd)
        }

        // help (lowest priority)
        if (lower.contains("справк") || lower == "help" || lower.contains("помощ")) {
            return listOf(
                "Справка (CommandsV3): физические команды (заглушки):",
                "1) Время: 'время 150 км при 80 км/ч' — рассчитывает время в сек/мин/ч и округляет до 0.5 мин.",
                "2) Скорость: 'сколько ехать 10 км за 15 минут' или 'сколько идти 5 км за 50 минут' — вычисляет требуемую скорость и пэйс.",
                "3) Projectile: 'проект v=30 м/с угол 45' — рассчитывает время полёта, максимальную высоту, дальность.",
                "4) Energy: 'энергия m=80 v=5' или 'кинет m 80 v 5' — кинетическая/потенциальная энергия."
            )
        }

        return emptyList()
    }

    // --------------------
    // Projectile motion (no air resistance)
    // Parses v (m/s) and angle (deg). Optional initial height h0 in meters.
    // --------------------
    private fun handleProjectile(cmd: String): List<String> {
        val lower = cmd.lowercase(Locale.getDefault())

        // parse v
        val vMatch = Regex("""(?:v=|v\s+|скорост(?:ь)?\s+|скорость=)?(-?\d+(?:[.,]\d+)?)\s*(m/s|м/с|mps|м/сек|м/с)?""").find(lower)
        // Better: explicit patterns for v= and angle
        val vExplicit = Regex("""v\s*=?\s*(-?\d+(?:[.,]\d+)?)""", RegexOption.IGNORE_CASE).find(lower)
        val angleExplicit = Regex("""(?:угол|angle|a)\s*=?\s*(-?\d+(?:[.,]\d+)?)""", RegexOption.IGNORE_CASE).find(lower)
        val genericV = vExplicit?.groupValues?.get(1) ?: vMatch?.groupValues?.get(1)
        if (genericV == null) {
            return listOf("Projectile: не нашёл скорость (v). Пример: 'проект v=30 угол 45' или 'projectile 20m/s 30°'")
        }
        val v = genericV.replace(',', '.').toDoubleOrNull() ?: return listOf("Projectile: не смог распознать число скорости.")
        // angle: look for degrees: "45°" or "45 deg" or angleExplicit
        var angleDeg: Double? = null
        val degSym = Regex("""(-?\d+(?:[.,]\d+)?)\s*°""").find(lower)
        if (degSym != null) angleDeg = degSym.groupValues[1].replace(',', '.').toDoubleOrNull()
        if (angleDeg == null && angleExplicit != null) angleDeg = angleExplicit.groupValues[1].replace(',', '.').toDoubleOrNull()
        // fallback: maybe two numbers present: first v, second angle
        if (angleDeg == null) {
            val nums = Regex("""(-?\d+(?:[.,]\d+)?)""").findAll(lower).map { it.groupValues[1].replace(',', '.') }.toList()
            if (nums.size >= 2) {
                // try second as angle if reasonable (<=90)
                val cand = nums[1].toDoubleOrNull()
                if (cand != null && cand.absoluteValue <= 89.0) angleDeg = cand
            }
        }
        if (angleDeg == null) return listOf("Projectile: не нашёл угол (в градусах). Укажите, например, 'угол 45' или '45°'.")

        // optionally parse initial height h0
        var h0 = 0.0
        val hMatch = Regex("""h0\s*=?\s*(-?\d+(?:[.,]\d+)?)""").find(lower)
        if (hMatch != null) h0 = hMatch.groupValues[1].replace(',', '.').toDoubleOrNull() ?: 0.0
        val g = 9.80665

        val theta = Math.toRadians(angleDeg)
        val vx = v * cos(theta)
        val vy = v * sin(theta)
        // time of flight solving y(t) = h0 + vy * t - 0.5 g t^2 = 0 -> quadratic: -0.5 g t^2 + vy t + h0 = 0
        val a = -0.5 * g
        val b = vy
        val c = h0
        val disc = b * b - 4.0 * a * c
        if (disc < 0) {
            return listOf("Projectile: дискриминант < 0 — нет реального пересечения с землёй при данных параметрах.")
        }
        val sqrtD = sqrt(disc)
        // two roots, take positive largest
        val t1 = (-b + sqrtD) / (2.0 * a)
        val t2 = (-b - sqrtD) / (2.0 * a)
        val tFlight = listOf(t1, t2).filter { it > 1e-9 }.maxOrNull() ?: 0.0
        val maxHeight = h0 + (vy * vy) / (2.0 * g)
        val range = vx * tFlight
        val apexTime = vy / g

        val out = mutableListOf<String>()
        out.add("Projectile (без сопротивления воздуха):")
        out.add("v0 = ${"%.3f".format(v)} m/s, угол = ${"%.3f".format(angleDeg)}°")
        out.add("Горизонтальная скорость vx = ${"%.3f".format(vx)} m/s, вертикальная vy = ${"%.3f".format(vy)} m/s")
        out.add("Время полёта (до земли): ${"%.3f".format(tFlight)} с")
        out.add("Макс. высота: ${"%.3f".format(maxHeight)} м (время подъёма ≈ ${"%.3f".format(apexTime)} с)")
        out.add("Дальность (гориз.): ${"%.3f".format(range)} м (~${"%.3f".format(range/1000.0)} км)")
        out.add("Замечание: модель идеализирована (без воздуха).")
        return out
    }

    // --------------------
    // Energy: kinetic and potential + conversions
    //    Examples: "энергия m=80 v=5"  or "кинет m 80 v 5" or "потенц m=2 h=10"
    // --------------------
    private fun handleEnergy(cmd: String): List<String> {
        val lower = cmd.lowercase(Locale.getDefault())
        // parse mass
        val mMatch = Regex("""(?:m=|m\s+|масса\s*=?\s*)(\d+(?:[.,]\d+)?)""", RegexOption.IGNORE_CASE).find(lower)
        val mass = mMatch?.groupValues?.get(1)?.replace(',', '.')?.toDoubleOrNull()

        // parse velocity
        val vMatch = Regex("""(?:v=|v\s+|скорост(?:ь)?\s*=?\s*)(-?\d+(?:[.,]\d+)?)""", RegexOption.IGNORE_CASE).find(lower)
        val velocity = vMatch?.groupValues?.get(1)?.replace(',', '.')?.toDoubleOrNull()

        // parse height
        val hMatch = Regex("""(?:h=|h\s+|высот(?:а|ы)?\s*=?\s*)(-?\d+(?:[.,]\d+)?)""", RegexOption.IGNORE_CASE).find(lower)
        val height = hMatch?.groupValues?.get(1)?.replace(',', '.')?.toDoubleOrNull()

        val g = 9.80665
        val out = mutableListOf<String>()

        if (mass != null && velocity != null) {
            val ke = 0.5 * mass * velocity * velocity
            out.add("Кинетическая энергия: m=${"%.3f".format(mass)} кг, v=${"%.3f".format(velocity)} м/с")
            out.add("KE = 0.5*m*v^2 = ${"%.3f".format(ke)} J")
            out.add("Эквивалент: ${(ke/4184.0).formatWith(6)} kcal (≈ хлебных кусков и т.п.)")
        }

        if (mass != null && height != null) {
            val pe = mass * g * height
            out.add("Потенциальная энергия (потенц.): m=${"%.3f".format(mass)} кг, h=${"%.3f".format(height)} м")
            out.add("PE = m*g*h = ${"%.3f".format(pe)} J")
        }

        if (out.isEmpty()) {
            return listOf("Energy: укажите параметры. Примеры: 'энергия m=80 v=5' или 'потенц m=2 h=10'")
        }
        return out
    }

    // helper extension
    private fun Double.formatWith(frac: Int): String = try { String.format(Locale.getDefault(), "%.${frac}f", this) } catch (_: Exception) { this.toString() }
}

// --------------------
// Конец файла
// --------------------
