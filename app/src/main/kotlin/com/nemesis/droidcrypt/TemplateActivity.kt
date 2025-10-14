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
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
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
        val distMeters = PhysUtils.parseDistanceMeters(cmd)
            ?: return listOf(
                "Время: не нашёл расстояние. Пример: 'время 150 км при 80 км/ч' или 'time 120km at 60km/h'"
            )
        val speedMetersPerSec = PhysUtils.parseSpeedToMPerS(cmd)
            ?: return listOf(
                "Время: не нашёл скорость. Пример: 'время 150 км при 80 км/ч' или 'time 10km at 5 m/s'"
            )

        val seconds = distMeters / speedMetersPerSec
        val roundedHalfMin = ( (seconds / 60.0) * 2.0 ).roundToInt() / 2.0 // minutes rounded to 0.5
        val hours = (seconds / 3600.0)
        val hh = floor(hours).toInt()
        val remMinutes = (hours - hh) * 60.0
        val mm = floor(remMinutes).toInt()
        val ss = ((remMinutes - mm) * 60.0).roundToInt()

        val sb = mutableListOf<String>()
        sb.add("Расстояние: ${PhysUtils.formatDistanceNice(distMeters)}")
        sb.add("Скорость: ${PhysUtils.formatSpeedNice(speedMetersPerSec)}")
        sb.add("Точное время: ${"%.2f".format(seconds)} с (${PhysUtils.formatSecondsNice(seconds)})")
        sb.add("Разбивка: ${hh} ч ${mm} мин ${ss} сек")
        sb.add("Округлённо до 0.5 мин: ${roundedHalfMin} мин (≈ ${PhysUtils.formatMinutesToHMS(roundedHalfMin)})")

        // Pace & per-100km
        if (speedMetersPerSec > 0.0) {
            val paceSecPerKm = 1000.0 / speedMetersPerSec
            sb.add("Пэйс: ${PhysUtils.formatPace(paceSecPerKm)} (мин/км)")
            val time100kSec = 100000.0 / speedMetersPerSec
            sb.add("Если ехать/идти с такой скоростью — 100 км займут: ${PhysUtils.formatSecondsNice(time100kSec)}")
            sb.add("Скорость: ${"%.2f".format(speedMetersPerSec*3.6)} км/ч (${String.format("%.2f", speedMetersPerSec)} м/с)")
        }

        // ETA (локальное время)
        try {
            val eta = LocalDateTime.now().plusSeconds(seconds.toLong())
            val fmt = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
            sb.add("Ожидаемое время прибытия (локально): ${eta.format(fmt)}")
        } catch (_: Exception) { /*ignore*/ }

        sb.add("Заметка: модель проста — без учёта рельефа, пробок или остановок.")
        return sb
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
        if (rootSpeed.any { lower.contains(it) } || (lower.contains("сколько") && (lower.contains("идти") || lower.contains("ехать")))) {
            return handleRequiredSpeed(cmd)
        }

        return emptyList()
    }

    private fun handleRequiredSpeed(cmd: String): List<String> {
        val distMeters = PhysUtils.parseDistanceMeters(cmd)
            ?: return listOf("Speed: не нашёл расстояние. Пример: 'сколько ехать 10 км за 15 минут' или 'сколько идти 5km за 50min'")
        val timeSec = PhysUtils.parseTimeToSeconds(cmd)
            ?: return listOf("Speed: не нашёл целевого времени. Примеры: 'за 15 минут', 'в 1:30', 'за 90 мин', 'за 0.5 часа'")

        val lower = cmd.lowercase(Locale.getDefault())
        val walkingRoots = listOf("идт", "ход", "пеш", "шаг", "пешком")
        val drivingRoots = listOf("ех", "езд", "машин", "авто", "вел", "велосипед")
        val mode = when {
            walkingRoots.any { lower.contains(it) } -> "walk"
            drivingRoots.any { lower.contains(it) } -> "drive"
            else -> "either"
        }

        val neededMps = distMeters / timeSec
        val neededKmh = neededMps * 3.6
        val paceSecPerKm = if (neededMps > 0) 1000.0 / neededMps else Double.POSITIVE_INFINITY

        val lines = mutableListOf<String>()
        lines.add("Расстояние: ${PhysUtils.formatDistanceNice(distMeters)}")
        lines.add("Целевое время: ${PhysUtils.formatSecondsNice(timeSec)}")
        lines.add("Требуемая скорость: ${"%.2f".format(neededKmh)} км/ч (${String.format("%.2f", neededMps)} м/с)")

        if (mode == "walk" || mode == "either") {
            lines.add("Пэйс для пешей: ${PhysUtils.formatPace(paceSecPerKm)} (мин/км)")
            val steps = (distMeters / 0.75).roundToInt() // stride ≈ 0.75 m
            lines.add("Оценка шагов (шага ≈0.75 м): ≈ $steps шагов")
            lines.add("Комфортные скорости ходьбы: ~4.5–5.5 км/ч (разминка/медленно) — быстрый шаг ~6.5 км/ч")
        }
        if (mode == "drive" || mode == "either") {
            val mph = neededKmh / 1.609344
            lines.add("Для езды: ${"%.2f".format(mph)} миль/ч (mph)")
            lines.add("Совет по безопасности: проверьте ограничения скорости и дорожные условия.")
        }

        // реалистичность
        if (neededKmh > 200.0) {
            lines.add("Внимание: требуемая скорость очень велика (${String.format("%.1f", neededKmh)} км/ч). Это малореалистично/опасно.")
        } else if (neededKmh < 1.0 && mode == "drive") {
            lines.add("Примечание: для езды требуется очень низкая скорость — возможно вы имели в виду пешую цель.")
        }

        lines.add("Детали:")
        lines.add(" - Средняя скорость = ${"%.2f".format(neededKmh)} км/ч")
        lines.add(" - Пэйс = ${PhysUtils.formatPace(paceSecPerKm)} (мин/км)")

        return lines
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
        val projRoots = listOf("проек", "парабол", "пуля", "брос", "пуск", "projectile", "range", "trajectory", "дальность")
        if (projRoots.any { lower.contains(it) } || (lower.contains("угол") && (lower.contains("м/с") || lower.contains("v")))) {
            return handleProjectile(cmd)
        }

        // Energy calculations
        val energyRoots = listOf("энерг", "кинет", "потенц", "потенциальн", "kinetic", "potential", "energy")
        if (energyRoots.any { lower.contains(it) }) {
            return handleEnergy(cmd)
        }

        // help (lowest priority)
        if (lower.contains("справк") || lower == "help" || lower.contains("помощ")) {
            return listOf(
                "Справка (CommandsV3): физические команды:",
                "1) Время: 'время 150 км при 80 км/ч' — рассчитывает время в сек/мин/ч и округляет до 0.5 мин.",
                "2) Скорость: 'сколько ехать 10 км за 15 минут' или 'сколько идти 5 км за 50 минут' — вычисляет требуемую скорость и пэйс.",
                "3) Projectile: 'проект v=30 м/с угол 45' — рассчитывает время полёта, макс. высоту, дальность. Если задана дальность и h0=0 — попытается найти углы.",
                "   Пример: 'проект v=50 range=200' или 'projectile v=30 angle=45 h0=1.5'",
                "4) Energy: 'энергия m=80 v=5' или 'кинет m 80 v 5' — кинетическая/потенциальная энергия (J/kJ/kWh/kcal)."
            )
        }

        return emptyList()
    }

    // --------------------
    // Projectile motion (no air resistance)
    // Parses v (m/s) and angle (deg). Optional initial height h0 in meters. Optional range parameter to invert.
    // --------------------
    private fun handleProjectile(cmd: String): List<String> {
        val lower = cmd.lowercase(Locale.getDefault())

        // parse v: accept v=..., число с m/s or km/h
        val vParsed = PhysUtils.extractNumberWithUnit(lower, listOf("m/s", "м/с", "mps", "м/сек", "km/h", "км/ч", "kmh", "км/ч"))
        val v = vParsed?.let { PhysUtils.normalizeSpeedToMPerS(it.value, it.unit) }
            ?: PhysUtils.extractBareNumber(lower, "v") // maybe "v 30"
        if (v == null) return listOf("Projectile: не нашёл скорость (v). Пример: 'проект v=30 угол 45' или 'projectile 20m/s 30°'")

        // parse angle in degrees if present
        var angleDeg: Double? = PhysUtils.extractAngleDegrees(lower)
        // parse range if provided (meters)
        val rangeMeters = PhysUtils.extractNumberIfPresent(lower, listOf("range", "дальность", "r"))
        // parse initial height h0
        val h0 = PhysUtils.extractNumberIfPresent(lower, listOf("h0", "h", "height", "высот", "высота")) ?: 0.0

        val g = 9.80665
        val out = mutableListOf<String>()

        // If range provided and angle missing and h0==0 -> compute possible angles
        if (rangeMeters != null && angleDeg == null && abs(h0) < 1e-9) {
            val sin2theta = (rangeMeters * g) / (v * v)
            if (sin2theta < -1.0 || sin2theta > 1.0) {
                out.add("Projectile: при v=${"%.3f".format(v)} м/с невозможно достичь range=${"%.3f".format(rangeMeters)} м (sin2θ=${"%.6f".format(sin2theta)} вне [-1,1]).")
                out.add("Попробуйте увеличить скорость или уменьшить дальность.")
                return out
            }
            val twoTheta1 = asin(sin2theta)
            val theta1 = Math.toDegrees(twoTheta1 / 2.0)
            val theta2 = Math.toDegrees((PI - twoTheta1) / 2.0)
            out.add("Найдено два возможных угла (h0=0): ${"%.3f".format(theta1)}° и ${"%.3f".format(theta2)}°")
            // compute flights for both
            for (ang in listOf(theta1, theta2)) {
                val (tf, maxH, rng) = computeProjectileFor(v, ang, h0)
                out.add("Угол ${"%.3f".format(ang)}° → время полёта ${"%.3f".format(tf)} с, макс. высота ${"%.3f".format(maxH)} м, дальность ${"%.3f".format(rng)} м")
            }
            out.add("Заметка: модель идеализирована — сопротивление воздуха не учтено.")
            return out
        }

        // if angle present: compute trajectory
        if (angleDeg != null) {
            val (tFlight, maxHeight, range) = computeProjectileFor(v, angleDeg, h0)
            val theta = Math.toRadians(angleDeg)
            val vx = v * cos(theta)
            val vy = v * sin(theta)
            out.add("Projectile (без сопротивления воздуха):")
            out.add("v0 = ${"%.3f".format(v)} m/s, угол = ${"%.3f".format(angleDeg)}°")
            out.add("vx = ${"%.3f".format(vx)} m/s, vy = ${"%.3f".format(vy)} m/s")
            out.add("Время полёта (до земли): ${"%.3f".format(tFlight)} с")
            out.add("Макс. высота: ${"%.3f".format(maxHeight)} м")
            out.add("Дальность (гориз.): ${"%.3f".format(range)} м (~${"%.3f".format(range/1000.0)} км)")
            out.add("Замечание: модель идеализирована (без воздуха).")
            return out
        }

        // if range provided but angle present -> compute and show results
        if (rangeMeters != null && angleDeg != null) {
            val (tFlight, maxHeight, rangeCalc) = computeProjectileFor(v, angleDeg, h0)
            out.add("Расчёт по заданным параметрам:")
            out.add("Угол: ${"%.3f".format(angleDeg)}°, v=${"%.3f".format(v)} м/с, h0=${"%.3f".format(h0)} м")
            out.add("Полученная дальность: ${"%.3f".format(rangeCalc)} м (запрошено ${"%.3f".format(rangeMeters)} м)")
            return out
        }

        return listOf("Projectile: укажите скорость (v) и угол или дальность. Пример: 'проект v=30 угол 45' или 'проект v=50 range=200'")
    }

    // helper to compute tFlight, maxHeight, range for v(m/s), angleDeg (deg), h0 (m)
    private fun computeProjectileFor(v: Double, angleDeg: Double, h0: Double): Triple<Double, Double, Double> {
        val g = 9.80665
        val theta = Math.toRadians(angleDeg)
        val vx = v * cos(theta)
        val vy = v * sin(theta)
        val a = -0.5 * g
        val b = vy
        val c = h0
        val disc = b * b - 4.0 * a * c
        if (disc < 0) return Triple(0.0, h0, 0.0)
        val sqrtD = sqrt(disc)
        val t1 = (-b + sqrtD) / (2.0 * a)
        val t2 = (-b - sqrtD) / (2.0 * a)
        val tFlight = listOf(t1, t2).filter { it > 1e-9 }.maxOrNull() ?: 0.0
        val maxHeight = h0 + (vy * vy) / (2.0 * g)
        val range = vx * tFlight
        return Triple(tFlight, maxHeight, range)
    }

    // --------------------
    // Energy: kinetic and potential + conversions
    //    Examples: "энергия m=80 v=5"  or "кинет m 80 v 5" or "потенц m=2 h=10"
    // --------------------
    private fun handleEnergy(cmd: String): List<String> {
        val lower = cmd.lowercase(Locale.getDefault())

        // parse mass with units (kg, g, lb)
        val massParsed = PhysUtils.extractNumberWithUnit(lower, listOf("kg", "кг", "g", "г", "lb", "фунт", "lbs"))
        val massKg = when {
            massParsed == null -> PhysUtils.extractBareNumber(lower, "m") // maybe "m 80"
            else -> PhysUtils.normalizeMassToKg(massParsed.value, massParsed.unit)
        }

        // parse velocity (m/s or km/h)
        val velParsed = PhysUtils.extractNumberWithUnit(lower, listOf("m/s", "м/с", "mps", "м/сек", "km/h", "км/ч", "kmh"))
        val v = velParsed?.let { PhysUtils.normalizeSpeedToMPerS(it.value, it.unit) }
            ?: PhysUtils.extractBareNumber(lower, "v")

        // parse height
        val hParsed = PhysUtils.extractNumberWithUnit(lower, listOf("m", "м"))
        val h = hParsed?.value ?: PhysUtils.extractNumberIfPresent(lower, listOf("h", "height", "высота")) ?: PhysUtils.extractBareNumber(lower, "h")

        val g = 9.80665
        val out = mutableListOf<String>()

        if (massKg != null && v != null) {
            val ke = 0.5 * massKg * v * v
            out.add("Кинетическая энергия:")
            out.add(" m = ${"%.3f".format(massKg)} кг, v = ${"%.3f".format(v)} м/с")
            out.add(" KE = 0.5*m*v^2 = ${"%.3f".format(ke)} J")
            out.add(" → ${"%.3f".format(ke/1000.0)} kJ, ${"%.6f".format(ke/3_600_000.0)} kWh, ${"%.3f".format(ke/4184.0)} kcal")
        }

        if (massKg != null && h != null) {
            val pe = massKg * g * h
            out.add("Потенциальная энергия:")
            out.add(" m = ${"%.3f".format(massKg)} кг, h = ${"%.3f".format(h)} м")
            out.add(" PE = m*g*h = ${"%.3f".format(pe)} J")
            out.add(" → ${"%.3f".format(pe/1000.0)} kJ, ${"%.6f".format(pe/3_600_000.0)} kWh, ${"%.3f".format(pe/4184.0)} kcal")
        }

        if (out.isEmpty()) {
            return listOf("Energy: укажите параметры. Примеры: 'энергия m=80 v=5' или 'потенц m=2 h=10'")
        }
        out.add("Замечание: результаты в системе СИ, округлены для наглядности.")
        return out
    }
}

// --------------------
// Общие утилиты для парсинга/форматирования (переиспользуются во всех модулях)
// --------------------
private object PhysUtils {

    data class NumUnit(val value: Double, val unit: String?)

    // parse patterns like "123 km", "123km", "123 км", "123.5", supports comma
    fun parseDistanceMeters(s: String): Double? {
        val lower = s.lowercase(Locale.getDefault())
        // Try to find explicit unit matches first
        val pattern = Regex("""(-?\d+(?:[.,]\d+)?)\s*(km|км|km/h|км/ч|m\b|м\b|meters|метров|метр)""", RegexOption.IGNORE_CASE)
        val matches = pattern.findAll(lower).toList()
        if (matches.isNotEmpty()) {
            // choose first that is distance (km or m)
            for (m in matches) {
                val num = m.groupValues[1].replace(',', '.').toDoubleOrNull() ?: continue
                val unit = m.groupValues[2].lowercase(Locale.getDefault())
                return when {
                    unit.contains("km") || unit == "км" -> num * 1000.0
                    unit == "m" || unit == "м" || unit.contains("метр") -> num
                    // sometimes we accidentally matched km/h; ignore
                    unit.contains("km/h") || unit.contains("км/ч") -> continue
                    else -> num
                }
            }
        }
        // fallback: look for any number and assume meters if small or km if big? We'll assume km if number >= 1000? To be safe, assume kilometers if number > 3 else meters ambiguous.
        val anyNum = Regex("""(-?\d+(?:[.,]\d+)?)""").find(lower)?.groupValues?.get(1)?.replace(',', '.')
        return anyNum?.toDoubleOrNull()
    }

    // Parse speed: supports "80 км/ч", "5 m/s", "80 kmh", "80 км/час"
    fun parseSpeedToMPerS(s: String): Double? {
        val lower = s.lowercase(Locale.getDefault())
        val re = Regex("""(-?\d+(?:[.,]\d+)?)\s*(km/h|км/ч|км/час|kmh|kmh\b|m/s|м/с|м/сек|mps)\b""", RegexOption.IGNORE_CASE)
        val m = re.find(lower)
        if (m != null) {
            val num = m.groupValues[1].replace(',', '.').toDoubleOrNull() ?: return null
            val unit = m.groupValues[2].lowercase(Locale.getDefault())
            return when {
                unit.contains("km") && !unit.contains("h") -> num / 3.6 // if "km" alone treat as km/h
                unit.contains("km") -> num / 3.6
                unit.contains("м/с") || unit.contains("m/s") || unit.contains("mps") -> num
                else -> num
            }
        }
        // support "при 80" assume km/h
        val pri = Regex("""при\s+(-?\d+(?:[.,]\d+)?)\s*(?:км/ч|km/h|км|km)?""", RegexOption.IGNORE_CASE).find(lower)
        if (pri != null) {
            val num = pri.groupValues[1].replace(',', '.').toDoubleOrNull() ?: return null
            return num / 3.6
        }
        // support "at 80" assume km/h
        val at = Regex("""\bat\s+(-?\d+(?:[.,]\d+)?)\b""", RegexOption.IGNORE_CASE).find(lower)
        if (at != null) {
            val num = at.groupValues[1].replace(',', '.').toDoubleOrNull() ?: return null
            return num / 3.6
        }
        return null
    }

    // Formatters
    fun formatDistanceNice(meters: Double): String {
        return if (meters >= 1000.0) {
            "${"%.3f".format(meters / 1000.0)} km"
        } else {
            "${"%.1f".format(meters)} m"
        }
    }

    fun formatSpeedNice(mps: Double): String {
        val kmh = mps * 3.6
        return "${"%.2f".format(kmh)} km/h (${String.format(Locale.getDefault(), "%.2f", mps)} m/s)"
    }

    fun formatSecondsNice(secDouble: Double): String {
        val s = secDouble.roundToInt().coerceAtLeast(0)
        val h = s / 3600
        val m = (s % 3600) / 60
        val sRem = s % 60
        return "${h}ч ${m}м ${sRem}с"
    }

    fun formatMinutesToHMS(minutes: Double): String {
        val totalSec = (minutes * 60.0).roundToInt()
        val h = totalSec / 3600
        val m = (totalSec % 3600) / 60
        val s = totalSec % 60
        return "${h}ч ${m}мин ${s}сек"
    }

    fun formatPace(secPerKm: Double): String {
        if (!secPerKm.isFinite() || secPerKm.isNaN()) return "—"
        val m = floor(secPerKm / 60.0).toInt()
        val s = (secPerKm - m * 60).roundToInt()
        return "${m}:${if (s < 10) "0$s" else "$s"} мин/км"
    }

    // Time parser: supports hh:mm, X час/часа/ч, X минут/мин, decimal hours "1.5 часа", "за 90 мин"
    fun parseTimeToSeconds(s: String): Double? {
        val lower = s.lowercase(Locale.getDefault())

        val hhmm = Regex("""\b(\d{1,2}):(\d{1,2})(?::(\d{1,2}))?\b""").find(lower)
        if (hhmm != null) {
            val h = hhmm.groupValues[1].toIntOrNull() ?: 0
            val m = hhmm.groupValues[2].toIntOrNull() ?: 0
            val sec = hhmm.groupValues.getOrNull(3)?.toIntOrNull() ?: 0
            return (h * 3600 + m * 60 + sec).toDouble()
        }

        val hoursMatch = Regex("""(\d+(?:[.,]\d+)?)\s*(час|часа|часов|h|ч)\b""", RegexOption.IGNORE_CASE).find(lower)
        val minsMatch = Regex("""(\d+(?:[.,]\d+)?)\s*(минут|мин|минуты|m|min)\b""", RegexOption.IGNORE_CASE).find(lower)
        if (hoursMatch != null || minsMatch != null) {
            val hours = hoursMatch?.groupValues?.get(1)?.replace(',', '.')?.toDoubleOrNull() ?: 0.0
            val mins = minsMatch?.groupValues?.get(1)?.replace(',', '.')?.toDoubleOrNull() ?: 0.0
            return hours * 3600.0 + mins * 60.0
        }

        val decHour = Regex("""(\d+(?:[.,]\d+)?)\s*ч\b""", RegexOption.IGNORE_CASE).find(lower)
        if (decHour != null) {
            val h = decHour.groupValues[1].replace(',', '.').toDoubleOrNull() ?: return null
            return h * 3600.0
        }

        val onlyMin = Regex("""\b(\d+(?:[.,]\d+)?)\s*(мин|минут)\b""", RegexOption.IGNORE_CASE).find(lower)
        if (onlyMin != null) {
            val m = onlyMin.groupValues[1].replace(',', '.').toDoubleOrNull() ?: return null
            return m * 60.0
        }

        val za = Regex("""за\s*(\d+(?:[.,]\d+)?)\b""").find(lower)
        if (za != null) {
            val v = za.groupValues[1].replace(',', '.').toDoubleOrNull() ?: return null
            return v * 60.0 // treat as minutes
        }

        // bare number: ambiguous, treat as minutes by default
        val bare = Regex("""\b(\d+(?:[.,]\d+)?)\b""").find(lower)?.groupValues?.get(1)?.replace(',', '.')
        return bare?.toDoubleOrNull()?.let { it * 60.0 }
    }

    // Helper: extract number for named key e.g. range=200 or "range 200"
    fun extractNumberIfPresent(lower: String, keys: List<String>): Double? {
        for (k in keys) {
            val re1 = Regex("""\b${Regex.escape(k)}\s*=\s*(-?\d+(?:[.,]\d+)?)\b""", RegexOption.IGNORE_CASE).find(lower)
            if (re1 != null) return re1.groupValues[1].replace(',', '.').toDoubleOrNull()
            val re2 = Regex("""\b${Regex.escape(k)}\s+(-?\d+(?:[.,]\d+)?)\b""", RegexOption.IGNORE_CASE).find(lower)
            if (re2 != null) return re2.groupValues[1].replace(',', '.').toDoubleOrNull()
        }
        return null
    }

    // Extract angle specified as "45°" or "угол 45" or "angle=45"
    fun extractAngleDegrees(lower: String): Double? {
        val degSym = Regex("""(-?\d+(?:[.,]\d+)?)\s*°""").find(lower)
        if (degSym != null) return degSym.groupValues[1].replace(',', '.').toDoubleOrNull()
        val angleExplicit = Regex("""(?:угол|angle|a)\s*=?\s*(-?\d+(?:[.,]\d+)?)""", RegexOption.IGNORE_CASE).find(lower)
        if (angleExplicit != null) return angleExplicit.groupValues[1].replace(',', '.').toDoubleOrNull()
        // fallback: no angle
        return null
    }

    // Extract number with unit from text for several known units: returns value and found unit
    fun extractNumberWithUnit(lower: String, units: List<String>): NumUnit? {
        for (u in units) {
            // try value + optional space + unit
            val re = Regex("""(-?\d+(?:[.,]\d+)?)\s*${Regex.escape(u)}\b""", RegexOption.IGNORE_CASE).find(lower)
            if (re != null) return NumUnit(re.groupValues[1].replace(',', '.').toDoubleOrNull() ?: return null, u)
        }
        // try patterns like "m=80" or "v=30km/h"
        val generic = Regex("""([a-z]{1,3})\s*=\s*(-?\d+(?:[.,]\d+)?)([a-z/%°]*)""", RegexOption.IGNORE_CASE).find(lower)
        if (generic != null) {
            val unit = generic.groupValues[3].ifBlank { null }
            return NumUnit(generic.groupValues[2].replace(',', '.').toDoubleOrNull() ?: return null, unit)
        }
        return null
    }

    // Extract bare numeric by label "v 30" or "m 80"
    fun extractBareNumber(lower: String, label: String): Double? {
        val re = Regex("""\b${Regex.escape(label)}\s*[:=]?\s*(-?\d+(?:[.,]\d+)?)\b""", RegexOption.IGNORE_CASE).find(lower)
        if (re != null) return re.groupValues[1].replace(',', '.').toDoubleOrNull()
        // fallback: first numeric
        val any = Regex("""(-?\d+(?:[.,]\d+)?)""").findAll(lower).map { it.groupValues[1].replace(',', '.') }.toList()
        if (any.isNotEmpty()) {
            return any[0].toDoubleOrNull()
        }
        return null
    }

    // normalize mass unit to kg (supports kg, g, lb)
    fun normalizeMassToKg(value: Double, unit: String?): Double {
        if (unit == null) return value // assume kg if unspecified
        val u = unit.lowercase(Locale.getDefault())
        return when {
            u.contains("kg") || u.contains("кг") -> value
            u == "g" || u == "г" -> value / 1000.0
            u.contains("lb") || u.contains("фунт") -> value * 0.45359237
            else -> value
        }
    }

    // normalize speed unit to m/s
    fun normalizeSpeedToMPerS(value: Double, unit: String?): Double {
        if (unit == null) return value // assume m/s if unspecified
        val u = unit.lowercase(Locale.getDefault())
        return when {
            u.contains("km") || u.contains("км") -> value / 3.6
            u.contains("m/s") || u.contains("м/с") || u.contains("mps") -> value
            else -> value
        }
    }
}
