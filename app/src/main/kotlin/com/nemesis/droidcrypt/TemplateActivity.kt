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
// CommandsMain (расширённый): множество физических утилит/команд
// Поддерживает: время (старый), brake, drag, collision, projectile-drag, rc/rl/rlc, heat, hydro, pendulum
// + НОВЫЕ: beam, flow, rocket, inertia, sound, power, gas
// --------------------
private object CommandsMain {

    fun handleCommand(cmdRaw: String): List<String> {
        val cmd = cmdRaw.trim()
        val lower = cmd.lowercase(Locale.getDefault())

        // Существующая команда "время"
        if (lower.contains("время") || lower.contains("сколько времени") || lower.contains("time")) {
            return handleTimeForDistance(cmd)
        }

        // Тормозной путь
        if (listOf("тормоз", "тормозной", "brake", "stopping").any { lower.contains(it) }) {
            return handleBrake(cmd)
        }

        // Сопротивление воздуха
        if (listOf("drag", "сопротивлен", "сопротивление воздуха", "сопротивл").any { lower.contains(it) }) {
            return handleDrag(cmd)
        }

        // Столкновение 1D
        if (listOf("столкн", "удар", "collision").any { lower.contains(it) }) {
            return handleCollision(cmd)
        }

        // Projectile with drag (численное)
        if ( (lower.contains("projectile") && lower.contains("drag")) ||
             listOf("projectile-drag","pdrag","пдрег","проекц сопротив").any { lower.contains(it) } ||
             (lower.contains("проект") && lower.contains("сопр") ) ) {
            return handleProjectileWithDrag(cmd)
        }

        // RC / RL / RLC
        if (listOf("rc ", "rl ", "rlc", "переход", "time constant", "tau").any { lower.contains(it) }) {
            return handleCircuitTransient(cmd)
        }

        // Тепловой баланс / нагрев
        if (listOf("heat", "нагрев", "нагревание", "теплов", "тепло").any { lower.contains(it) }) {
            return handleHeat(cmd)
        }

        // Гидростатика / архимед
        if (listOf("hydro", "hydraul", "архимед", "поплав", "гидро", "глубин").any { lower.contains(it) }) {
            return handleHydro(cmd)
        }

        // Маятник
        if (listOf("pendulum", "маятник", "маятни").any { lower.contains(it) }) {
            return handlePendulum(cmd)
        }

        // 9) Beam / балка (стат. нагрузки)
        if (listOf("beam", "балк", "опор", "shear", "moment", "схема нагрузки", "изгиб").any { lower.contains(it) }) {
            return handleBeam(cmd)
        }

        // 10) Flow / Bernoulli / Darcy
        if (listOf("flow", "bernoulli", "darcy", "h_f", "поток", "потери", "скорость потока", "hazen").any { lower.contains(it) }) {
            return handleFlow(cmd)
        }

        // 11) Rocket / Tsiolkovsky
        if (listOf("rocket", "tsiolkovsky", "delta-v", "delta v", "Δv", "dv", "isp").any { lower.contains(it) }) {
            return handleRocket(cmd)
        }

        // 12) Inertia / момент инерции
        if (listOf("inertia", "момент инерции", "моментинерц", "I=", "инерц").any { lower.contains(it) }) {
            return handleInertia(cmd)
        }

        // 13) Sound / дБ / суммирование / падение с расстоянием
        if (listOf("sound", "дб", "db", "spl", "звук", "уровень звука", "дб spl").any { lower.contains(it) }) {
            return handleSound(cmd)
        }

        // 14) Power / torque / rpm / efficiency
        if (listOf("power", "мощн", "torque", "момент", "rpm", "об/мин", "efficiency", "кпд").any { lower.contains(it) }) {
            return handlePower(cmd)
        }

        // 15) Ideal gas / процессы
        if (listOf("gas", "pv", "идеальн", "adiabatic", "isothermal", "адиабат", "изотерм").any { lower.contains(it) }) {
            return handleGas(cmd)
        }

        return emptyList()
    }

    // --------------------
    // 1) Time (existing) — вынесена сюда (не изменяйте логику)
    // --------------------
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

    // --------------------
    // Helper parsing utilities used внутри CommandsMain
    // (независимые от PhysUtils, но используют его когда возможно)
    // --------------------
    private fun parseDoubleByKey(lower: String, keys: List<String>): Double? {
        return PhysUtils.extractNumberIfPresent(lower, keys)
    }

    private fun parseNumberWithUnitGeneric(lower: String, label: String): PhysUtils.NumUnit? {
        val re1 = Regex("""\b${Regex.escape(label)}\s*=\s*(-?\d+(?:[.,]\d+)?)([a-zA-Zµ°/%μ\.\-+]*)\b""", RegexOption.IGNORE_CASE).find(lower)
        if (re1 != null) {
            val v = re1.groupValues[1].replace(',', '.').toDoubleOrNull() ?: return null
            val u = re1.groupValues[2].ifBlank { null }
            return PhysUtils.NumUnit(v, u)
        }
        val re2 = Regex("""\b${Regex.escape(label)}\s+(-?\d+(?:[.,]\d+)?)([a-zA-Zµ°/%μ\.\-+]*)\b""", RegexOption.IGNORE_CASE).find(lower)
        if (re2 != null) {
            val v = re2.groupValues[1].replace(',', '.').toDoubleOrNull() ?: return null
            val u = re2.groupValues[2].ifBlank { null }
            return PhysUtils.NumUnit(v, u)
        }
        val bare = PhysUtils.extractBareNumber(lower, label)
        return bare?.let { PhysUtils.NumUnit(it, null) }
    }

    private fun safeDoubleOrNull(s: String?): Double? = s?.replace(',', '.')?.toDoubleOrNull()

    // --------------------
    // 2) Brake: тормозной путь
    // (оставлена без изменений)
    // --------------------
    private fun handleBrake(cmd: String): List<String> {
        val lower = cmd.lowercase(Locale.getDefault())
        val v = PhysUtils.parseSpeedToMPerS(lower) ?: PhysUtils.extractBareNumber(lower, "v")
        val mu = parseDoubleByKey(lower, listOf("μ", "mu", "mu=","mu")) ?: parseDoubleByKey(lower, listOf("mu"))
        val aExplicit = parseNumberWithUnitGeneric(lower, "a")?.value

        if (v == null) {
            return listOf("Brake: не найдено скорость (v). Пример: 'тормоз 80 км/ч μ=0.7' или 'brake v=30 m/s a=-6'")
        }
        val g = 9.80665
        val a = when {
            aExplicit != null -> aExplicit
            mu != null -> -abs(mu) * g
            else -> -0.7 * g
        }

        if (a >= 0.0) {
            return listOf("Brake: неверное значение deceleration a=$a (ожидается отрицательное).")
        }

        val stoppingDistance = (v * v) / (2.0 * abs(a))
        val stoppingTime = v / abs(a)

        val lines = mutableListOf<String>()
        lines.add("Тормозной расчёт:")
        lines.add(" v = ${"%.2f".format(v)} м/с (${String.format("%.2f", v*3.6)} км/ч)")
        if (mu != null) lines.add(" Использован μ = ${"%.3f".format(mu)} -> a = ${"%.3f".format(-abs(mu)*g)} м/с²")
        else if (aExplicit != null) lines.add(" Использовано указанное замедление a = ${"%.3f".format(aExplicit)} м/с²")
        else lines.add(" Использовано стандартное μ≈0.7 -> a≈${"%.3f".format(a)} м/с²")
        lines.add(" Время до остановки: ${"%.2f".format(stoppingTime)} с")
        lines.add(" Тормозной путь: ${"%.3f".format(stoppingDistance)} м (${PhysUtils.formatDistanceNice(stoppingDistance)})")
        lines.add(" Примечание: простая модель (скольжение без ABS, без реакции водителя, без уклона).")
        return lines
    }

    // --------------------
    // 3) Drag: сопротивление воздуха (оставлена без изменений)
    // --------------------
    private fun handleDrag(cmd: String): List<String> {
        val lower = cmd.lowercase(Locale.getDefault())
        val v = PhysUtils.extractNumberWithUnit(lower, listOf("m/s","м/с","mps"))?.value
            ?: PhysUtils.parseSpeedToMPerS(lower)
            ?: PhysUtils.extractBareNumber(lower, "v")
        val massNum = PhysUtils.extractNumberWithUnit(lower, listOf("kg","кг","g","г","lb"," фунт"))?.let {
            PhysUtils.normalizeMassToKg(it.value, it.unit)
        } ?: PhysUtils.extractBareNumber(lower, "m")
        val cdNum = parseDoubleByKey(lower, listOf("cd","Cd","c_d")) ?: parseDoubleByKey(lower, listOf("коэф","коэфд"))
        val area = parseDoubleByKey(lower, listOf("a","area","A")) ?: parseDoubleByKey(lower, listOf("площадь"))
        val rho = PhysUtils.extractNumberWithUnit(lower, listOf("kg/m3","kg/m^3","kg/m³","кг/м3"))?.value ?: parseDoubleByKey(lower, listOf("rho","ρ")) ?: 1.225

        if (v == null) return listOf("Drag: не найдена скорость v. Пример: 'drag v=30 m/s Cd=1.0 A=0.5 m=80'")

        val Cd = cdNum ?: 1.0
        val A = area ?: 0.5

        val Fd = 0.5 * rho * Cd * A * v * v
        val lines = mutableListOf<String>()
        lines.add("Сопротивление воздуха (приближённо):")
        lines.add(" v = ${"%.3f".format(v)} м/с (${String.format("%.1f", v*3.6)} км/ч)")
        lines.add(" Cd = ${"%.3f".format(Cd)}, A = ${"%.3f".format(A)} м², ρ = ${"%.3f".format(rho)} кг/м³")
        lines.add(" Сила сопротивления F_d = 0.5·ρ·Cd·A·v² = ${"%.3f".format(Fd)} Н")
        massNum?.let {
            val weight = it * 9.80665
            lines.add(" Масса = ${"%.3f".format(it)} кг → вес ≈ ${"%.3f".format(weight)} Н")
            lines.add(" F_d / weight = ${"%.3f".format(Fd / weight)} (доля от веса)")
            val vTerm = sqrt((2.0 * it * 9.80665) / (rho * Cd * A))
            lines.add(" Предельная скорость при падении (пример) v_term ≈ ${"%.3f".format(vTerm)} м/с (~${"%.1f".format(vTerm*3.6)} км/ч)")
        }
        lines.add(" Примечание: модель не учитывает форму тела (Cd прибл.), турбулентность и изменение rho с высотой.")
        return lines
    }

    // --------------------
    // 4) Collision 1D: (оставлена без изменений)
    // --------------------
    private fun handleCollision(cmd: String): List<String> {
        val lower = cmd.lowercase(Locale.getDefault())
        val m1 = PhysUtils.extractNumberWithUnit(lower, listOf("m1","m_1","m1="))?.value ?: PhysUtils.extractBareNumber(lower, "m1")
            ?: PhysUtils.extractBareNumber(lower, "m")
        val v1 = PhysUtils.extractNumberWithUnit(lower, listOf("v1","v_1"))?.value ?: PhysUtils.extractBareNumber(lower, "v1")
            ?: PhysUtils.extractBareNumber(lower, "v")
        val m2 = PhysUtils.extractNumberWithUnit(lower, listOf("m2","m_2"))?.value ?: PhysUtils.extractBareNumber(lower, "m2")
        val v2 = PhysUtils.extractNumberWithUnit(lower, listOf("v2","v_2"))?.value ?: PhysUtils.extractBareNumber(lower, "v2")
        val e = parseDoubleByKey(lower, listOf("e","coef","коэф","e="))

        if (m1 == null || v1 == null || m2 == null || v2 == null) {
            return listOf("Collision: укажите m1,v1,m2,v2. Пример: 'collision m1=2 v1=5 m2=3 v2=-1 e=0.8'")
        }
        val lines = mutableListOf<String>()
        lines.add("Столкновение 1D:")
        lines.add(" m1=${"%.3f".format(m1)} кг, v1=${"%.3f".format(v1)} м/с; m2=${"%.3f".format(m2)} кг, v2=${"%.3f".format(v2)} м/с")
        if (lower.contains("inelastic") || lower.contains("неупр") || (e != null && e == 0.0)) {
            val vAfter = (m1*v1 + m2*v2) / (m1 + m2)
            val keBefore = 0.5*m1*v1*v1 + 0.5*m2*v2*v2
            val keAfter = 0.5*(m1+m2)*vAfter*vAfter
            lines.add(" Неразделимое столкновение: v_after = ${"%.3f".format(vAfter)} м/с")
            lines.add(" Кин. энергия до = ${"%.3f".format(keBefore)} J, после = ${"%.3f".format(keAfter)} J, потеря = ${"%.3f".format(keBefore - keAfter)} J")
            return lines
        }

        if (e != null) {
            val v1After = ( (m1 - e*m2)*v1 + (1 + e)*m2*v2 ) / (m1 + m2)
            val v2After = ( (m2 - e*m1)*v2 + (1 + e)*m1*v1 ) / (m1 + m2)
            val keBefore = 0.5*m1*v1*v1 + 0.5*m2*v2*v2
            val keAfter = 0.5*m1*v1After*v1After + 0.5*m2*v2After*v2After
            lines.add(" Коэффициент восстановления e = ${"%.3f".format(e)}")
            lines.add(" v1' = ${"%.3f".format(v1After)} м/с, v2' = ${"%.3f".format(v2After)} м/с")
            lines.add(" KE_before = ${"%.3f".format(keBefore)} J, KE_after = ${"%.3f".format(keAfter)} J, ΔKE = ${"%.3f".format(keBefore - keAfter)} J")
            return lines
        }

        val v1After = ( (m1 - m2) * v1 + 2.0 * m2 * v2 ) / (m1 + m2)
        val v2After = ( 2.0 * m1 * v1 + (m2 - m1) * v2 ) / (m1 + m2)
        val keBefore = 0.5*m1*v1*v1 + 0.5*m2*v2*v2
        val keAfter = 0.5*m1*v1After*v1After + 0.5*m2*v2After*v2After
        lines.add(" Упругое столкновение (e≈1):")
        lines.add(" v1' = ${"%.3f".format(v1After)} м/с, v2' = ${"%.3f".format(v2After)} м/с")
        lines.add(" KE_before = ${"%.3f".format(keBefore)} J, KE_after = ${"%.3f".format(keAfter)} J (сохраняется в идеале)")
        return lines
    }

    // --------------------
    // 5) Projectile with drag (численное) — ОСТАВЛЕНО как есть
    // --------------------
    private fun handleProjectileWithDrag(cmd: String): List<String> {
        val lower = cmd.lowercase(Locale.getDefault())
        val vRaw = PhysUtils.extractNumberWithUnit(lower, listOf("m/s","м/с","mps"))?.value
            ?: PhysUtils.extractBareNumber(lower, "v")
            ?: PhysUtils.extractBareNumber(lower, "speed")
        val angleDeg = PhysUtils.extractAngleDegrees(lower) ?: PhysUtils.extractBareNumber(lower, "angle") ?: PhysUtils.extractBareNumber(lower, "угол")
        if (vRaw == null) return listOf("Projectile-drag: не найдена скорость v. Пример: 'projectile-drag v=200 angle=30 Cd=0.3 A=0.02 m=0.05'")
        if (angleDeg == null) return listOf("Projectile-drag: не найден угол. Пример: 'projectile-drag v=200 angle=30'")

        val Cd = parseDoubleByKey(lower, listOf("cd","Cd")) ?: 0.47 // default: sphere ~0.47
        val A = parseDoubleByKey(lower, listOf("a","area","A")) ?: 0.03
        val m = PhysUtils.extractNumberWithUnit(lower, listOf("kg","кг"))?.let { PhysUtils.normalizeMassToKg(it.value, it.unit) } ?: PhysUtils.extractBareNumber(lower, "m") ?: 0.1
        val rho = PhysUtils.extractNumberWithUnit(lower, listOf("kg/m3","kg/m^3","kg/m³"))?.value ?: parseDoubleByKey(lower, listOf("rho","ρ")) ?: 1.225
        val dt = parseDoubleByKey(lower, listOf("dt")) ?: 0.01
        val maxSteps = 200000

        val g = 9.80665
        var vx = vRaw * cos(Math.toRadians(angleDeg))
        var vy = vRaw * sin(Math.toRadians(angleDeg))
        var x = 0.0
        var y = parseDoubleByKey(lower, listOf("h0","h","height")) ?: 0.0

        var t = 0.0
        var step = 0
        var maxH = y
        var hitGround = false

        val dtSafe = dt.coerceIn(1e-4, 0.1)

        while (step < maxSteps) {
            val v = sqrt(vx*vx + vy*vy)
            if (v < 1e-12) {
                vy -= g * dtSafe
                y += vy * dtSafe
                x += vx * dtSafe
            } else {
                val Fd = 0.5 * rho * Cd * A * v * v
                val ax = - (Fd / m) * (vx / v)
                val ay = -g - (Fd / m) * (vy / v)
                val vx_mid = vx + ax * (dtSafe * 0.5)
                val vy_mid = vy + ay * (dtSafe * 0.5)
                val v_mid = sqrt(vx_mid*vx_mid + vy_mid*vy_mid)
                val Fd_mid = 0.5 * rho * Cd * A * v_mid * v_mid
                val ax_mid = if (v_mid < 1e-12) 0.0 else - (Fd_mid / m) * (vx_mid / v_mid)
                val ay_mid = -g - (Fd_mid / m) * (vy_mid / v_mid)
                vx += ax_mid * dtSafe
                vy += ay_mid * dtSafe
                x += vx_mid * dtSafe
                y += vy_mid * dtSafe
            }
            t += dtSafe
            step += 1
            if (y > maxH) maxH = y
            if (y <= 0.0 && t > 1e-6) {
                hitGround = true
                break
            }
        }

        val range = x
        val lines = mutableListOf<String>()
        lines.add("Projectile с сопротивлением (численно):")
        lines.add(" v0=${"%.3f".format(vRaw)} м/с, угол=${"%.3f".format(angleDeg)}°, m=${"%.3f".format(m)} кг")
        lines.add(" Cd=${"%.3f".format(Cd)}, A=${"%.3f".format(A)} м², ρ=${"%.3f".format(rho)} кг/м³")
        lines.add(" Время полёта (численно, dt=${"%.4f".format(dtSafe)}): ${"%.3f".format(t)} с")
        lines.add(" Максимальная высота ≈ ${"%.3f".format(maxH)} м")
        lines.add(" Дальность ≈ ${"%.3f".format(range)} м (${String.format("%.3f", range/1000.0)} км)")
        if (!hitGround) lines.add(" ВНИМАНИЕ: интеграция остановлена по шагам (возможно dt слишком мал или траектория долгая).")
        lines.add(" Примечание: модель приближённая (квадратичный тормоз), не учитывает подветренность/смену rho/обтекаемость при высоких M).")
        return lines
    }

    // --------------------
    // 6) RC/RL/RLC переходные процессы (простая оценка) — ОСТАВЛЕНО
    // --------------------
    private fun handleCircuitTransient(cmd: String): List<String> {
        val lower = cmd.lowercase(Locale.getDefault())
        if (lower.contains("rc")) {
            val Rnu = PhysUtils.extractNumberWithUnit(lower, listOf("ohm","Ω","ohms"))?.value ?: parseDoubleByKey(lower, listOf("r","R")) ?: 1000.0
            val Cnu = PhysUtils.extractNumberWithUnit(lower, listOf("f","farad","ф"))?.value ?: parseDoubleByKey(lower, listOf("c","C")) ?: 1e-6
            val stepV = parseDoubleByKey(lower, listOf("step","V","v")) ?: 1.0
            val tau = Rnu * Cnu
            val lines = mutableListOf<String>()
            lines.add("RC-circuit:")
            lines.add(" R = ${"%.6g".format(Rnu)} Ω, C = ${"%.6g".format(Cnu)} F")
            lines.add(" Постоянная времени τ = R·C = ${"%.6g".format(tau)} с")
            lines.add(" Отношение при t=τ: vC(τ) = V*(1 - e^{-1}) ≈ ${"%.3f".format(stepV * (1 - exp(-1.0)))} V")
            lines.add(" Примеры: vC(0)=0, vC(τ)≈${"%.3f".format(stepV*(1-exp(-1.0)))} V, vC(5τ)≈${"%.6g".format(stepV*(1-exp(-5.0)))} V (~почти V)")
            lines.add(" Примечание: модель идеальна — без утечек, начальных зарядов и ESR/индуктивностей.")
            return lines
        }

        if (lower.contains("rl") && !lower.contains("rlc")) {
            val Rnu = PhysUtils.extractNumberWithUnit(lower, listOf("ohm","Ω"))?.value ?: parseDoubleByKey(lower, listOf("r","R")) ?: 1.0
            val Lnu = PhysUtils.extractNumberWithUnit(lower, listOf("h","H","henry"))?.value ?: parseDoubleByKey(lower, listOf("l","L")) ?: 0.01
            val tau = Lnu / Rnu
            val lines = mutableListOf<String>()
            lines.add("RL-circuit:")
            lines.add(" R = ${"%.6g".format(Rnu)} Ω, L = ${"%.6g".format(Lnu)} H")
            lines.add(" Постоянная времени τ = L / R = ${"%.6g".format(tau)} с")
            lines.add(" Токи при ступеньке: i(t) = (V/R)*(1 - e^{-t/τ})")
            lines.add(" Примечание: для точных форм учитывайте индуктивность пиков и насыщение.")
            return lines
        }

        if (lower.contains("rlc")) {
            val Rnu = PhysUtils.extractNumberWithUnit(lower, listOf("ohm","Ω"))?.value ?: parseDoubleByKey(lower, listOf("r","R")) ?: 1.0
            val Lnu = PhysUtils.extractNumberWithUnit(lower, listOf("h","H","henry"))?.value ?: parseDoubleByKey(lower, listOf("l","L")) ?: 0.01
            val Cnu = PhysUtils.extractNumberWithUnit(lower, listOf("f","farad"))?.value ?: parseDoubleByKey(lower, listOf("c","C")) ?: 1e-6
            val omega0 = 1.0 / sqrt(Lnu * Cnu)
            val zeta = Rnu / (2.0 * sqrt(Lnu / Cnu))
            val lines = mutableListOf<String>()
            lines.add("RLC (приближённый анализ):")
            lines.add(" R = ${"%.6g".format(Rnu)} Ω, L = ${"%.6g".format(Lnu)} H, C = ${"%.6g".format(Cnu)} F")
            lines.add(" Собственная частота ω0 = ${"%.6g".format(omega0)} рад/с (f0=${"%.3f".format(omega0/ (2*Math.PI))} Гц)")
            lines.add(" Приблизительный коэффициент демпфирования ζ ≈ ${"%.6f".format(zeta)}")
            lines.add( when {
                zeta < 1.0 -> " Режим: недемпфированный (колебания)."
                zeta == 1.0 -> " Режим: критическое затухание."
                else -> " Режим: апериодическое (пере- или сильное затухание)."
            } )
            lines.add(" Для точного времени/амплитуды — решите ОДУ или используйте численный симулятор.")
            return lines
        }

        return listOf("Circuit: не распознана команда. Примеры: 'rc R=10k C=10u step=5V', 'rl R=10 L=50m', 'rlc R=10 L=0.1 C=1e-6'")
    }

    // --------------------
    // 7) Heat: тепловой баланс и время нагрева — ОСТАВЛЕНО
    // --------------------
    private fun handleHeat(cmd: String): List<String> {
        val lower = cmd.lowercase(Locale.getDefault())
        val mass = PhysUtils.extractNumberWithUnit(lower, listOf("kg","кг","g","г"))?.let { PhysUtils.normalizeMassToKg(it.value, it.unit) } ?: PhysUtils.extractBareNumber(lower, "m")
        val c = parseDoubleByKey(lower, listOf("c","cp","heatcapacity","specificheat")) ?: 4184.0
        val T0 = parseDoubleByKey(lower, listOf("t0","T0","T_start","start")) ?: parseDoubleByKey(lower, listOf("t","T")) ?: 20.0
        val T1 = parseDoubleByKey(lower, listOf("t1","T1","target","end")) ?: parseDoubleByKey(lower, listOf("to")) ?: return listOf("Heat: укажите целевую температуру T1")
        val P = parseDoubleByKey(lower, listOf("p","P","power")) ?: parseDoubleByKey(lower, listOf("w","W")) ?: 1000.0
        val kLoss = parseDoubleByKey(lower, listOf("k_loss","k","loss")) ?: 0.0

        if (mass == null) return listOf("Heat: не указана масса. Пример: 'heat m=2 c=900 T0=20 T1=80 P=2000'")

        val deltaT = T1 - T0
        val Q = mass * c * deltaT
        val effectiveP = max(1e-12, P - kLoss)
        val timeSec = Q / effectiveP
        val lines = mutableListOf<String>()
        lines.add("Тепловой расчёт:")
        lines.add(" m=${"%.3f".format(mass)} кг, c=${"%.3f".format(c)} J/(kg·K)")
        lines.add(" ΔT = ${"%.3f".format(deltaT)} K, Q = m·c·ΔT = ${"%.3f".format(Q)} J (~${"%.6f".format(Q/3_600_000.0)} kWh)")
        lines.add(" Мощность нагрева P = ${"%.3f".format(P)} W, потери ≈ ${"%.3f".format(kLoss)} W -> эффективная P = ${"%.3f".format(effectiveP)} W")
        lines.add(" Оценочное время нагрева: ${"%.1f".format(timeSec)} с (≈ ${"%.2f".format(timeSec/60.0)} мин)")
        if (T1 >= 100.0 && c == 4184.0) lines.add(" ВНИМАНИЕ: T1 ≥ 100°C — возможен фазовый переход (закипание воды).")
        lines.add(" Примечание: модель простая — нет продвинутой теплоотдачи/конвекции/излучения.")
        return lines
    }

    // --------------------
    // 8) Hydro: давление по глубине и архимедова сила — ОСТАВЛЕНО
    // --------------------
    private fun handleHydro(cmd: String): List<String> {
        val lower = cmd.lowercase(Locale.getDefault())
        val depth = PhysUtils.extractNumberWithUnit(lower, listOf("m","м"))?.value ?: parseDoubleByKey(lower, listOf("depth","h","depth="))
        val rho = PhysUtils.extractNumberWithUnit(lower, listOf("kg/m3","kg/m^3","kg/m³"))?.value ?: parseDoubleByKey(lower, listOf("rho")) ?: 1000.0
        val g = 9.80665
        val lines = mutableListOf<String>()

        if (depth != null) {
            val p0 = 101325.0
            val p = p0 + rho * g * depth
            lines.add("Гидростатика:")
            lines.add(" Глубина h = ${"%.3f".format(depth)} м, ρ=${"%.3f".format(rho)} кг/м³")
            lines.add(" Давление P = P0 + ρ·g·h = ${"%.0f".format(p)} Па (~${"%.3f".format(p/101325.0)} атм)")
            lines.add(" Абсолютное давление: ${"%.0f".format(p)} Па; избыточное (гидростатическое) ≈ ${"%.0f".format(rho*g*depth)} Па")
        }

        val mass = PhysUtils.extractNumberWithUnit(lower, listOf("kg","кг","m","mass"))?.let { PhysUtils.normalizeMassToKg(it.value, it.unit) } ?: parseDoubleByKey(lower, listOf("m","mass"))
        val volume = PhysUtils.extractNumberWithUnit(lower, listOf("m3","м3","m^3"))?.value ?: parseDoubleByKey(lower, listOf("V","volume","vol"))
        if (mass != null) {
            val Vreq = mass / rho
            val Fb = rho * g * Vreq
            lines.add(" Для удержания массы m=${"%.3f".format(mass)} кг в жидкости (плотность ${"%.3f".format(rho)}):")
            lines.add(" Требуемый вытесненный объём V = m/ρ = ${"%.6f".format(Vreq)} м³")
            lines.add(" Архимедова сила при таком объёме F_b = ρ·g·V = ${"%.3f".format(Fb)} Н (сравн. вес = ${"%.3f".format(mass*g)} Н)")
        } else if (volume != null) {
            val Fb = rho * g * volume
            lines.add(" Вытесненный объём V = ${"%.6f".format(volume)} м³ -> Архимедова сила F_b = ${"%.3f".format(Fb)} Н")
        }

        if (depth == null && mass == null && volume == null) {
            return listOf("Hydro: укажите depth (глубина) или mass/volume. Примеры: 'hydro depth=30', 'hydro mass=2000 rho=1000', 'hydro V=0.5'")
        }

        lines.add(" Примечание: модель статична, не учитывает поверхностные эффекты, сжимаемость, движение жидкости.")
        return lines
    }

    // --------------------
    // 9) Pendulum: период простого и поправка при большой амплитуде
    // Примеры: "pendulum L=2", "pendulum L=1.5 theta0=30deg"
    // --------------------
    private fun handlePendulum(cmd: String): List<String> {
        val lower = cmd.lowercase(Locale.getDefault())
        val L = PhysUtils.extractNumberWithUnit(lower, listOf("m","м"))?.value ?: parseDoubleByKey(lower, listOf("L","length")) ?: return listOf("Pendulum: укажите длину L (пример: 'pendulum L=1.5')")
        val theta0deg = PhysUtils.extractAngleDegrees(lower) ?: parseDoubleByKey(lower, listOf("theta0","theta","theta0=")) ?: 5.0
        val g = 9.80665
        val T0 = 2.0 * Math.PI * sqrt(L / g)
        val theta0rad = Math.toRadians(theta0deg)
        val correction = 1.0 + (theta0rad*theta0rad)/16.0
        val Tcorr = T0 * correction

        val lines = mutableListOf<String>()
        lines.add("Маятник:")
        lines.add(" L = ${"%.3f".format(L)} м, θ0 = ${"%.3f".format(theta0deg)}°")
        lines.add(" Период малых колебаний T0 = 2π√(L/g) = ${"%.4f".format(T0)} с")
        if (theta0deg > 10.0) {
            lines.add(" Поправка на большую амплитуду (прибл.): T ≈ ${"%.4f".format(Tcorr)} с (коэф. ≈ ${"%.6f".format(correction)})")
        } else {
            lines.add(" Малые колебания применимы; поправка мала.")
        }
        lines.add(" Частота f = ${"%.4f".format(1.0 / T0)} Гц")
        lines.add(" Примечание: это идеализированная модель (без трения/воздушного сопротивления).")
        return lines
    }

    // --------------------
    // 10) Beam: реакции опор и максимальный изгибающий момент (простая статическая модель)
    // --------------------
    private fun handleBeam(cmd: String): List<String> {
        val lower = cmd.lowercase(Locale.getDefault())
        val L = PhysUtils.extractNumberWithUnit(lower, listOf("m","м"))?.value ?: parseDoubleByKey(lower, listOf("L","length"))
        if (L == null) return listOf("Beam: укажите длину балки L (например 'beam L=5').")

        val lines = mutableListOf<String>()
        lines.add("Балка (простой расчёт): L = ${"%.3f".format(L)} м")

        val P = PhysUtils.extractNumberWithUnit(lower, listOf("N","н"))?.value ?: parseDoubleByKey(lower, listOf("P","p","load"))
        val at = parseDoubleByKey(lower, listOf("at","a","pos","x"))
        val w = parseDoubleByKey(lower, listOf("w","q","w="))

        if (P != null && at != null) {
            val a = at.coerceIn(0.0, L)
            val RA = P * (L - a) / L
            val RB = P * a / L
            val M_max = RA * a
            lines.add(" Точечная нагрузка P = ${"%.3f".format(P)} N в x=${"%.3f".format(a)} м")
            lines.add(" Реакции: R_left = ${"%.3f".format(RA)} N, R_right = ${"%.3f".format(RB)} N")
            lines.add(" Максимальный изгибающий момент около точки: M_max ≈ ${"%.3f".format(M_max)} N·m")
            lines.add(" Момент в середине: M_mid ≈ ${"%.3f".format(P * a * (L - a) / L)} N·m")
            return lines
        }

        if (w != null) {
            val W = w * L
            val RA = W / 2.0
            val RB = W / 2.0
            val M_max = w * L * L / 8.0
            lines.add(" Равномерная нагрузка w = ${"%.3f".format(w)} N/m → суммарно W=${"%.3f".format(W)} N")
            lines.add(" Реакции: R_left = ${"%.3f".format(RA)} N, R_right = ${"%.3f".format(RB)} N")
            lines.add(" Максимальный изгибающий момент в середине: M_max = w·L²/8 = ${"%.3f".format(M_max)} N·m")
            lines.add(" Для расчёта прогиба — нужен модуль упругости (E) и момент инерции сечения (I).")
            return lines
        }

        return listOf("Beam: не найдены нагрузки. Примеры: 'beam L=5 P=1000 at=2' или 'beam L=6 w=200'")
    }

    // --------------------
    // 11) Flow: Bernoulli + Darcy-Weisbach (приближённый, Swamee–Jain)
    // --------------------
    private fun handleFlow(cmd: String): List<String> {
        val lower = cmd.lowercase(Locale.getDefault())
        val D = PhysUtils.extractNumberWithUnit(lower, listOf("m","м"))?.value ?: parseDoubleByKey(lower, listOf("D","diam","diameter"))
        if (D == null) return listOf("Flow: укажите диаметр трубы D (например 'flow D=0.05 v=2 L=10').")

        val rho = PhysUtils.extractNumberWithUnit(lower, listOf("kg/m3","kg/m^3","kg/m³"))?.value ?: parseDoubleByKey(lower, listOf("rho")) ?: 1000.0
        val nu = parseDoubleByKey(lower, listOf("nu","visc","vkin")) ?: 1.0e-6
        val L = parseDoubleByKey(lower, listOf("L","length")) ?: parseDoubleByKey(lower, listOf("len")) ?: 1.0
        val eps = parseDoubleByKey(lower, listOf("eps","epsilon","rough","ε")) ?: 0.00005

        val Q = PhysUtils.extractNumberWithUnit(lower, listOf("m3/s","m^3/s","м3/с"))?.value ?: parseDoubleByKey(lower, listOf("Q","q"))
        val v = PhysUtils.extractNumberWithUnit(lower, listOf("m/s","м/с"))?.value ?: parseDoubleByKey(lower, listOf("v","vel"))

        fun swameeJain(Re: Double, Dloc: Double, epsLoc: Double): Double {
            if (Re <= 0.0) return Double.NaN
            if (Re < 2000.0) return 64.0 / Re
            val A = epsLoc / (3.7 * Dloc)
            val B = 5.74 / Math.pow(Re, 0.9)
            return 0.25 / Math.pow(Math.log10(A + B), 2.0)
        }

        val lines = mutableListOf<String>()
        lines.add("Flow / Bernoulli / Darcy (приближённо):")
        lines.add(" D=${"%.4g".format(D)} m, L=${"%.4g".format(L)} m, ρ=${"%.4g".format(rho)} kg/m³, ν=${"%.4g".format(nu)} m²/s, ε=${"%.6f".format(eps)} m")

        if (Q != null) {
            val area = Math.PI * D * D / 4.0
            val vcalc = Q / area
            val Re = vcalc * D / nu
            val f = swameeJain(Re, D, eps)
            val hf = if (f.isFinite()) f * (L / D) * (vcalc * vcalc) / (2.0 * 9.80665) else Double.NaN
            lines.add(" Q = ${"%.6g".format(Q)} m³/s → v = ${"%.6g".format(vcalc)} m/s")
            lines.add(" Re = ${"%.3g".format(Re)}, прибл. f = ${if (f.isFinite()) "%.6g".format(f) else "—"}")
            lines.add(" Потери напора по длине h_f ≈ ${"%.6g".format(hf)} м (Darcy-Weisbach)")
            return lines
        } else if (v != null) {
            val area = Math.PI * D * D / 4.0
            val Qcalc = area * v
            val Re = v * D / nu
            val f = swameeJain(Re, D, eps)
            val hf = if (f.isFinite()) f * (L / D) * (v * v) / (2.0 * 9.80665) else Double.NaN
            lines.add(" v = ${"%.6g".format(v)} m/s → Q = ${"%.6g".format(Qcalc)} m³/s")
            lines.add(" Re = ${"%.3g".format(Re)}, прибл. f = ${if (f.isFinite()) "%.6g".format(f) else "—"}")
            lines.add(" Потери напора по длине h_f ≈ ${"%.6g".format(hf)} м (Darcy-Weisbach)")
            return lines
        } else {
            return listOf("Flow: укажите Q (m3/s) или v (m/s) вместе с D. Пример: 'flow Q=0.02 D=0.05 L=10' или 'flow v=2 D=0.1 L=50'.")
        }
    }

    // --------------------
    // 11) Rocket: Tsiolkovsky (Δv и требуемая масса топлива)
    // --------------------
    private fun handleRocket(cmd: String): List<String> {
        val lower = cmd.lowercase(Locale.getDefault())
        val g0 = 9.80665
        val dvInput = parseDoubleByKey(lower, listOf("dv","Δv","delta-v","dV"))
        var dv: Double? = dvInput
        if (dv == null) {
            val dvNu = PhysUtils.extractNumberWithUnit(lower, listOf("m/s","km/s","км/с","mps"))?.let {
                val unit = it.unit ?: ""
                val v = it.value
                if (unit.contains("km")) v * 1000.0 else v
            }
            dv = dvNu ?: null
        }

        val isp = parseDoubleByKey(lower, listOf("isp")) ?: parseDoubleByKey(lower, listOf("Isp")) ?: 300.0
        val m0 = PhysUtils.extractNumberWithUnit(lower, listOf("kg","кг"))?.let { PhysUtils.normalizeMassToKg(it.value, it.unit) } ?: parseDoubleByKey(lower, listOf("m0","m0="))
        val mfuel = parseDoubleByKey(lower, listOf("mfuel","mf","fuel","m_f"))
        val dry = parseDoubleByKey(lower, listOf("ms","m_dry","m_d"))

        val lines = mutableListOf<String>()
        lines.add("Rocket / Tsiolkovsky:")
        lines.add(" Isp = ${"%.3f".format(isp)} s (g0=${"%.5g".format(g0)} m/s²)")

        if (dv != null && m0 != null && mfuel == null && dry == null) {
            val expTerm = Math.exp(dv / (isp * g0))
            val mf = m0 * (1.0 - 1.0 / expTerm)
            lines.add(" Δv = ${"%.3f".format(dv)} m/s, начальная масса m0 = ${"%.3f".format(m0)} кг")
            lines.add(" Требуемая масса топлива ≈ ${"%.3f".format(mf)} кг (m_dry ≈ ${"%.3f".format(m0-mf)})")
            return lines
        }

        if (dv != null && dry != null && mfuel == null && m0 == null) {
            val expTerm = Math.exp(dv / (isp * g0))
            val mf = dry * (expTerm - 1.0)
            lines.add(" Δv = ${"%.3f".format(dv)} m/s, сухая масса m_dry = ${"%.3f".format(dry)} кг")
            lines.add(" Требуемая масса топлива ≈ ${"%.3f".format(mf)} кг (m0 ≈ ${"%.3f".format(dry+mf)} кг)")
            return lines
        }

        if (dv != null && m0 != null && mfuel != null) {
            val mDry = m0 - mfuel
            val dvCalc = isp * g0 * Math.log(m0 / mDry)
            lines.add(" Заданы m0=${"%.3f".format(m0)} кг, mfuel=${"%.3f".format(mfuel)} кг -> вычисленное Δv = ${"%.3f".format(dvCalc)} m/s (запрошено ${"%.3f".format(dv)} м/с)")
            return lines
        }

        if (m0 != null && mfuel != null && dv == null) {
            val mDry = m0 - mfuel
            val dvCalc = isp * g0 * Math.log(m0 / mDry)
            lines.add(" m0=${"%.3f".format(m0)} кг, mfuel=${"%.3f".format(mfuel)} кг -> Δv = ${"%.3f".format(dvCalc)} m/s")
            return lines
        }

        lines.add(" Примеры использования:")
        lines.add("  - 'rocket dv=9500 isp=350 m0=1000' — найти mfuel.")
        lines.add("  - 'rocket m_dry=500 dv=9500 isp=320' — найти mfuel.")
        lines.add("  - 'rocket m0=2000 mfuel=1200 isp=300' — узнать Δv.")
        return lines
    }

    // --------------------
    // 12) Inertia: моменты инерции для типовых тел + параллельная ось
    // --------------------
    private fun handleInertia(cmd: String): List<String> {
        val lower = cmd.lowercase(Locale.getDefault())
        val shape = when {
            lower.contains("rod") || lower.contains("палка") || lower.contains("стерж") -> "rod"
            lower.contains("cylinder") || lower.contains("цилиндр") -> "cylinder"
            lower.contains("sphere") || lower.contains("шар") -> "sphere"
            lower.contains("plate") || lower.contains("плита") || lower.contains("rect") -> "plate"
            else -> null
        }
        val m = PhysUtils.extractNumberWithUnit(lower, listOf("kg","кг"))?.let { PhysUtils.normalizeMassToKg(it.value, it.unit) } ?: parseDoubleByKey(lower, listOf("m","mass"))
        val r = parseDoubleByKey(lower, listOf("r","radius"))
        val L = parseDoubleByKey(lower, listOf("L","length"))
        val a = parseDoubleByKey(lower, listOf("a","d"))
        val axisStr = Regex("""axis\s*[:=]?\s*([a-zA-Z_]+)""").find(lower)?.groupValues?.get(1)

        val lines = mutableListOf<String>()
        if (shape == null || m == null) {
            return listOf("Inertia: укажите форму (rod/cylinder/sphere/plate) и массу m. Пример: 'inertia rod L=1 m=0.5 axis=end'")
        }

        when (shape) {
            "rod" -> {
                if (L == null) return listOf("Inertia(rod): укажите длину L. Пример: 'inertia rod L=1 m=0.5 axis=center'")
                val Icenter = (1.0/12.0) * m * L * L
                val Iend = (1.0/3.0) * m * L * L
                lines.add("Rod (тонкий стержень): m=${"%.4g".format(m)} кг, L=${"%.4g".format(L)} м")
                lines.add(" I_center = 1/12 mL² = ${"%.6g".format(Icenter)} kg·m²")
                lines.add(" I_end = 1/3 mL² = ${"%.6g".format(Iend)} kg·m²")
                if (a != null) {
                    val I_par = Icenter + m * a * a
                    lines.add(" Параллельная ось (смещение a=${"%.4g".format(a)} м): I = I_center + m a² = ${"%.6g".format(I_par)}")
                }
            }
            "cylinder" -> {
                if (r == null) return listOf("Inertia(cylinder): укажите радиус r. Пример: 'inertia cylinder m=2 r=0.1 axis=central'")
                val Isolid = 0.5 * m * r * r
                lines.add("Solid cylinder: m=${"%.4g".format(m)} кг, r=${"%.4g".format(r)} м")
                lines.add(" I_about_axis = 1/2 m r² = ${"%.6g".format(Isolid)} kg·m²")
                if (a != null) {
                    val I_par = Isolid + m * a * a
                    lines.add(" Параллельная ось (смещение a=${"%.4g".format(a)} м): I = ${"%.6g".format(I_par)}")
                }
            }
            "sphere" -> {
                if (r == null) return listOf("Inertia(sphere): укажите радиус r. Пример: 'inertia sphere m=1 r=0.1'")
                val Isolid = 0.4 * m * r * r
                lines.add("Solid sphere: m=${"%.4g".format(m)} кг, r=${"%.4g".format(r)} м")
                lines.add(" I = 2/5 m r² = ${"%.6g".format(Isolid)} kg·m²")
                if (a != null) {
                    val I_par = Isolid + m * a * a
                    lines.add(" Параллельная ось: I = ${"%.6g".format(I_par)}")
                }
            }
            "plate" -> {
                val aPlate = parseDoubleByKey(lower, listOf("a","width")) ?: 1.0
                val bPlate = parseDoubleByKey(lower, listOf("b","height","h")) ?: 1.0
                val Iplate = (1.0/12.0) * m * (aPlate*aPlate + bPlate*bPlate)
                lines.add("Rectangular plate (center): m=${"%.4g".format(m)} кг, a=${"%.4g".format(aPlate)} м, b=${"%.4g".format(bPlate)} м")
                lines.add(" I_center(perp) = 1/12 m (a² + b²) = ${"%.6g".format(Iplate)} kg·m²")
                if (a != null) {
                    val I_par = Iplate + m * a * a
                    lines.add(" Параллельная ось (смещение a=${"%.4g".format(a)} м): I = ${"%.6g".format(I_par)}")
                }
            }
        }

        return lines
    }

    // --------------------
    // 13) Sound: dB SPL, давление↔дБ, суммирование уровней, затухание с расстоянием
    // --------------------
    private fun handleSound(cmd: String): List<String> {
        val lower = cmd.lowercase(Locale.getDefault())
        val lines = mutableListOf<String>()
        val p = PhysUtils.extractNumberWithUnit(lower, listOf("pa","Pa","Па"))?.value ?: parseDoubleByKey(lower, listOf("p","pressure"))
        val p0 = 20e-6

        if (p != null) {
            val db = 20.0 * log10(p / p0)
            lines.add("Звуковое давление:")
            lines.add(" p = ${"%.6g".format(p)} Pa → L = ${"%.3f".format(db)} dB SPL (p0=20 µPa)")
            return lines
        }

        val level = Regex("""(-?\d+(?:[.,]\d+)?)\s*dB""").find(lower)?.groupValues?.get(1)?.replace(',', '.')?.toDoubleOrNull()
        val atMatch = Regex("""at\s*=?\s*(-?\d+(?:[.,]\d+)?)\s*m""").find(lower)
        val toMatch = Regex("""to\s*=?\s*(-?\d+(?:[.,]\d+)?)\s*m""").find(lower)
        if (level != null && atMatch != null && toMatch != null) {
            val L1 = level
            val r1 = atMatch.groupValues[1].replace(',', '.').toDoubleOrNull() ?: 1.0
            val r2 = toMatch.groupValues[1].replace(',', '.').toDoubleOrNull() ?: r1
            if (r1 <= 0.0 || r2 <= 0.0) return listOf("Sound: расстояния должны быть > 0.")
            val L2 = L1 - 20.0 * log10(r2 / r1)
            lines.add("Уровень звука:")
            lines.add(" L1 = ${"%.3f".format(L1)} dB at r1=${"%.3f".format(r1)} m → L2 at r2=${"%.3f".format(r2)} m = ${"%.3f".format(L2)} dB")
            return lines
        }

        if (lower.contains("sum") || lower.contains("слож")) {
            val nums = Regex("""(-?\d+(?:[.,]\d+)?)""").findAll(lower).map { it.groupValues[1].replace(',', '.').toDoubleOrNull() }.filterNotNull().toList()
            if (nums.isEmpty()) return listOf("Sound sum: укажите уровни в dB. Пример: 'sound sum 85 90 92'")
            val total = 10.0 * log10(nums.map { Math.pow(10.0, it / 10.0) }.sum())
            lines.add("Сумма уровней: входы = ${nums.joinToString(", ") { "%.2f".format(it) }} dB")
            lines.add(" Общий уровень = ${"%.3f".format(total)} dB")
            return lines
        }

        return listOf("Sound: не распознана команда. Примеры: 'sound p=0.02', 'sound level 85dB at 1m to 10m', 'sound sum 85 90 92'")
    }

    // --------------------
    // 14) Power / torque / rpm / efficiency
    // --------------------
    private fun handlePower(cmd: String): List<String> {
        val lower = cmd.lowercase(Locale.getDefault())
        val torque = parseNumberWithUnitGeneric(lower, "torque")?.value ?: parseDoubleByKey(lower, listOf("T","torque","mom","moment"))
        val rpm = parseDoubleByKey(lower, listOf("rpm","об/мин","revpm"))
        val Pin = parseDoubleByKey(lower, listOf("pin","p_in","p in","power_in"))
        val Pout = parseDoubleByKey(lower, listOf("pout","p_out","p out","power_out"))
        val eff = parseDoubleByKey(lower, listOf("eff","eta","кпд"))

        val lines = mutableListOf<String>()
        if (torque != null && rpm != null) {
            val omega = 2.0 * Math.PI * rpm / 60.0
            val P = torque * omega
            lines.add("Мощность/момент:")
            lines.add(" Torque = ${"%.3f".format(torque)} N·m, rpm = ${"%.3f".format(rpm)} → P = T·ω = ${"%.3f".format(P)} W")
            return lines
        }

        if (Pin != null && Pout != null) {
            val efficiency = if (Pin != 0.0) Pout / Pin else Double.NaN
            lines.add("КПД:")
            lines.add(" P_in = ${"%.3f".format(Pin)} W, P_out = ${"%.3f".format(Pout)} W")
            lines.add(" η = P_out / P_in = ${if (efficiency.isFinite()) "%.4f".format(efficiency) else "—"}")
            return lines
        }

        if (eff != null && Pin != null && Pout == null) {
            val poutCalc = Pin * eff
            lines.add(" P_out (по КПД): P_out = P_in * η = ${"%.3f".format(poutCalc)} W")
            return lines
        }

        return listOf("Power: не распознана команда. Примеры: 'power torque=200 rpm=1500', 'power P_in=1500 P_out=1200', 'power P_in=1500 eff=0.8'")
    }

    // --------------------
    // 15) Gas: идеальный газ + работа при изотерме/адиабате
    // --------------------
    private fun handleGas(cmd: String): List<String> {
        val lower = cmd.lowercase(Locale.getDefault())
        val R_univ = 8.31446261815324

        val n = parseDoubleByKey(lower, listOf("n","mol"))
        val T = parseDoubleByKey(lower, listOf("T","temp","temperature")) ?: parseDoubleByKey(lower, listOf("t"))
        val V = parseDoubleByKey(lower, listOf("V","volume","vol"))
        val P = parseDoubleByKey(lower, listOf("P","p","pressure"))

        val lines = mutableListOf<String>()

        if (n != null && T != null && V != null) {
            val pCalc = n * R_univ * T / V
            lines.add("Идеальный газ: n=${"%.6g".format(n)} mol, T=${"%.3f".format(T)} K, V=${"%.6g".format(V)} m³")
            lines.add(" P = n R T / V = ${"%.3f".format(pCalc)} Pa (~${"%.6g".format(pCalc/101325.0)} atm)")
            return lines
        }

        if (lower.contains("isothermal")) {
            val nIso = parseDoubleByKey(lower, listOf("n","mol")) ?: return listOf("Gas isothermal: укажите n (моль).")
            val Tiso = parseDoubleByKey(lower, listOf("T","temp")) ?: return listOf("Gas isothermal: укажите T (К).")
            val V1 = parseDoubleByKey(lower, listOf("v1","V1"))
            val V2 = parseDoubleByKey(lower, listOf("v2","V2"))
            if (V1 == null || V2 == null) return listOf("Isothermal: укажите V1 и V2. Пример: 'gas isothermal n=1 T=300 V1=0.01 V2=0.02'")
            val W = nIso * R_univ * Tiso * Math.log(V2 / V1)
            lines.add("Изотермическая работа: W = nRT ln(V2/V1) = ${"%.6g".format(W)} J (положительная — если расширение).")
            return lines
        }

        if (lower.contains("adiabatic") || lower.contains("adiabat")) {
            val nAd = parseDoubleByKey(lower, listOf("n","mol")) ?: return listOf("Adiabatic: укажите n (моль).")
            val gamma = parseDoubleByKey(lower, listOf("gamma","γ","cp/cv")) ?: 1.4
            val V1 = parseDoubleByKey(lower, listOf("v1","V1"))
            val V2 = parseDoubleByKey(lower, listOf("v2","V2"))
            if (V1 == null || V2 == null) return listOf("Adiabatic: укажите V1 и V2. Пример: 'gas adiabatic n=1 gamma=1.4 V1=0.01 V2=0.02'")
            val T1 = parseDoubleByKey(lower, listOf("T1","t1","T")) ?: return listOf("Adiabatic: укажите начальную температуру T1 (К) для расчёта.")
            val T2 = T1 * Math.pow(V1 / V2, gamma - 1.0)
            val W = (nAd * R_univ / (gamma - 1.0)) * (T1 - T2)
            lines.add("Адиабатический процесс: γ=${"%.3f".format(gamma)}")
            lines.add(" T1=${"%.3f".format(T1)} K, T2=${"%.3f".format(T2)} K")
            lines.add(" Работа W = n R (T1 - T2)/(γ - 1) = ${"%.6g".format(W)} J")
            return lines
        }

        return listOf("Gas: не распознана команда. Примеры: 'gas n=1 T=300 V=0.01', 'gas isothermal n=1 T=300 V1=0.01 V2=0.02', 'gas adiabatic n=1 gamma=1.4 T1=300 V1=0.01 V2=0.02'")
    }

    // helper: math log10 wrapper (safe)
    private fun log10(x: Double): Double = kotlin.math.log10(x)
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
