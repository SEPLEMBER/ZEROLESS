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

class RuPhysAsActivity : AppCompatActivity() {

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
            val mainRes = RusPhysCommandsMain.handleCommand(cmd)
            if (mainRes.isNotEmpty()) return mainRes
        } catch (_: Exception) { }

        try {
            val v2Res = RusPhysCommandsV2.handleCommand(cmd)
            if (v2Res.isNotEmpty()) return v2Res
        } catch (_: Exception) { }

        try {
            val v3Res = RusPhysCommandsV3.handleCommand(cmd)
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
// RusPhysCommandsMain: расширенный набор практичных "человеческих" команд
//  Поддерживает: время (существующий), стоп, дистанция, вело-стоп, падение, кипяток, заряд,
//  лестница, уклон, подъём/подъёмник, звук, ощущ (ветер), теплопотери
// --------------------
private object RusPhysCommandsMain {

    private const val G = 9.80665

    fun handleCommand(cmdRaw: String): List<String> {
        val cmd = cmdRaw.trim()
        val lower = cmd.lowercase(Locale.getDefault())
        
        // время (существующий обработчик)
        if (lower.contains("время") || lower.contains("сколько времени") || lower.contains("time")) {
            return handleTimeForDistance(cmd)
        }

        // стоп / тормозной путь
        val stopRoots = listOf("стоп", "тормоз", "тормозн", "stop")
        if (stopRoots.any { lower.contains(it) }) {
            return handleStop(cmd)
        }

        // дистанция / интервал
        val gapRoots = listOf("дистанц", "интервал", "безопасн", "gap")
        if (gapRoots.any { lower.contains(it) }) {
            return handleGap(cmd)
        }

        // вело-стоп
        val bikeRoots = listOf("вело", "велосип", "байк", "вел", "bike")
        if (bikeRoots.any { lower.contains(it) }) {
            return handleBikeStop(cmd)
        }

        // падение
        val fallRoots = listOf("пад", "упад", "паден", "скорость при ударе", "fall")
        if (fallRoots.any { lower.contains(it) }) {
            return handleFall(cmd)
        }

        // кипяток
        val boilRoots = listOf("кип", "чайник", "вскип", "кипят", "boil")
        if (boilRoots.any { lower.contains(it) }) {
            return handleBoil(cmd)
        }

        // заряд
        val chargeRoots = listOf("заряд", "зарядк", "charge")
        if (chargeRoots.any { lower.contains(it) }) {
            return handleCharge(cmd)
        }

        // лестница / этажи
        val stairsRoots = listOf("лестн", "этаж", "поднят", "stairs")
        if (stairsRoots.any { lower.contains(it) }) {
            return handleStairs(cmd)
        }

        // уклон / подъём
        val inclineRoots = listOf("уклон", "подъём", "подъем", "вверх", "slope", "incline")
        if (inclineRoots.any { lower.contains(it) }) {
            return handleIncline(cmd)
        }

        // подъём / подъёмник
        val liftRoots = listOf("поднят", "подъём", "поднять", "lift")
        if (liftRoots.any { lower.contains(it) }) {
            return handleLift(cmd)
        }

        // звук
        val soundRoots = listOf("звук", "дб", "дбс", "уровень", "sound")
        if (soundRoots.any { lower.contains(it) }) {
            return handleSound(cmd)
        }

        // ощущение холода / ветер
        val windRoots = listOf("ощущ", "ветр", "ветер", "wind", "windchill")
        if (windRoots.any { lower.contains(it) }) {
            return handleWindchill(cmd)
        }

        // теплопотери
        val heatlossRoots = listOf("теплопот", "тепло", "утечк", "теплопотери", "heatloss")
        if (heatlossRoots.any { lower.contains(it) }) {
            return handleHeatLoss(cmd)
        }

        return emptyList()
    }

    // --------------------
    // Существующий: время по расстоянию и скорости (оставлен без изменений логики)
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
    // СТОП — тормозной путь с учётом реакции и состояния дороги
    // --------------------
    private fun handleStop(cmd: String): List<String> {
        val lower = cmd.lowercase(Locale.getDefault())

        // парсинг скорости: сначала обычный парсер, затем bare number (предполагаем км/ч)
        var v = PhysUtils.parseSpeedToMPerS(lower)
        if (v == null) {
            val possible = PhysUtils.extractBareNumber(lower, "v") ?: PhysUtils.extractBareNumber(lower, "")
            if (possible != null) {
                // если число > 10 — предполагаем км/ч, иначе м/с
                v = if (possible > 10) possible / 3.6 else possible
            }
        }
        if (v == null) return listOf("Торможение: не удалось распознать скорость. Пример: 'стоп 80 км/ч' или 'тормоз 50 км/ч'")

        // время реакции
        val reactionDefault = 1.0 // с
        val reactionMatch = Regex("""(реакц|reaction)\s*[:=]?\s*(-?\d+(?:[.,]\d+)?)""").find(lower)
        val reaction = reactionMatch?.groupValues?.get(2)?.replace(',', '.')?.toDoubleOrNull() ?: reactionDefault

        // состояние дороги
        val mu = when {
            lower.contains("мокр") || lower.contains("wet") -> 0.4
            lower.contains("лед") || lower.contains("ice") -> 0.12
            else -> 0.8 // сухо по умолчанию
        }

        val a = mu * G
        val brakingDistance = if (a > 0) v * v / (2.0 * a) else Double.POSITIVE_INFINITY
        val reactionDist = v * reaction
        val timeBraking = if (a > 0) v / a else Double.POSITIVE_INFINITY
        val totalDist = reactionDist + brakingDistance
        val totalTime = reaction + timeBraking

        val sb = mutableListOf<String>()
        sb.add("Торможение от ${"%.0f".format(v*3.6)} км/ч:")
        sb.add(" - Время реакции: ${"%.2f".format(reaction)} с → пройдено ≈ ${"%.1f".format(reactionDist)} м")
        sb.add(" - Оценка сцепления: μ ≈ ${"%.2f".format(mu)} (${if (mu==0.8) "сухо" else if (mu==0.4) "мокро" else "скользко"})")
        sb.add(" - Тормозной путь (после реакции): ≈ ${"%.1f".format(brakingDistance)} м")
        sb.add("Итого до полной остановки: ≈ ${"%.1f".format(totalDist)} м (≈ ${"%.2f".format(totalTime)} с)")
        sb.add("Примечание: приближение — не учитывает ABS, рельеф, уклон и состояние шин.")
        return sb
    }

    // --------------------
    // ДИСТАНЦИЯ — безопасный интервал
    // --------------------
    private fun handleGap(cmd: String): List<String> {
        val lower = cmd.lowercase(Locale.getDefault())
        var v = PhysUtils.parseSpeedToMPerS(lower)
        if (v == null) {
            val num = PhysUtils.extractBareNumber(lower, "")
            if (num != null) v = if (num > 10) num / 3.6 else num
        }
        if (v == null) return listOf("Дистанция: не найдена скорость. Пример: 'дистанция 100 км/ч'")

        val secondsNormal = 2.5
        val secondsWet = 4.0
        val seconds = if (lower.contains("мокр") || lower.contains("wet")) secondsWet else secondsNormal
        val dist = v * seconds

        val sb = mutableListOf<String>()
        sb.add("Рекомендованный безопасный интервал при ${"%.0f".format(v*3.6)} км/ч:")
        sb.add(" - Временной интервал: ≈ ${"%.1f".format(seconds)} с")
        sb.add(" - Это соответствует ≈ ${"%.1f".format(dist)} м")
        sb.add("Совет: увеличивайте интервал при плохой видимости или скользкой дороге.")
        return sb
    }

    // --------------------
    // ВЕЛО-СТОП — тормозной путь для велосипеда
    // --------------------
    private fun handleBikeStop(cmd: String): List<String> {
        val lower = cmd.lowercase(Locale.getDefault())
        // скорость
        var v = PhysUtils.parseSpeedToMPerS(lower)
        if (v == null) {
            val num = PhysUtils.extractBareNumber(lower, "")
            if (num != null) v = if (num > 10) num / 3.6 else num
        }
        if (v == null) return listOf("Вело-стоп: укажите скорость. Пример: 'вело-стоп 30 км/ч диск сухо'")

        // тип тормозов
        val muBrake = when {
            lower.contains("disc") || lower.contains("диск") -> 0.7
            lower.contains("drum") || lower.contains("бараб") -> 0.5
            else -> 0.6
        }

        // покрытие дороги
        val muRoad = when {
            lower.contains("мокр") || lower.contains("wet") -> 0.4
            lower.contains("лед") || lower.contains("ice") -> 0.12
            else -> 0.8
        }

        // уклон, например "спуск 5%" или "down 5%"
        val slopeMatch = Regex("""(-?\d+(?:[.,]\d+)?)\s*%""").find(lower)
        val slopePercent = slopeMatch?.groupValues?.get(1)?.replace(',', '.')?.toDoubleOrNull() ?: 0.0
        val slopeAccel = (slopePercent / 100.0) * G // положительное при спуске увеличивает тормозной путь

        val muEffective = min(muBrake, muRoad)
        val a = muEffective * G - slopeAccel
        val brakingDistance = if (a > 0) v * v / (2.0 * a) else Double.POSITIVE_INFINITY
        val reaction = 1.0
        val reactionDist = v * reaction
        val total = reactionDist + brakingDistance

        val sb = mutableListOf<String>()
        sb.add("Велосипед ${"%.0f".format(v*3.6)} км/ч, тормоза: ${if (muBrake>=0.7) "дисковые" else "барабан/обычные"}")
        sb.add(" - Уклон: ${"%.2f".format(slopePercent)}% → дополнительное влияние на торможение")
        sb.add(" - Тормозной путь ≈ ${if (brakingDistance.isFinite()) "%.1f".format(brakingDistance) + " м" else "бесконечность (слишком маленькое сцепление)"}")
        sb.add(" - Добавьте ≈ ${"%.1f".format(reactionDist)} м на реакцию (≈1 с). Итого ≈ ${"%.1f".format(total)} м")
        sb.add("Совет: на спусках снижайте скорость заранее и используйте оба тормоза.")
        return sb
    }

    // --------------------
    // ПАДЕНИЕ — время падения и скорость при ударе
    // --------------------
    private fun handleFall(cmd: String): List<String> {
        val lower = cmd.lowercase(Locale.getDefault())
        // парсинг высоты в метрах
        val h = PhysUtils.extractNumberIfPresent(lower, listOf("m", "м", "height", "высота"))
            ?: PhysUtils.parseDistanceMeters(lower) ?: PhysUtils.extractBareNumber(lower, "") ?: return listOf("Падение: укажите высоту в метрах. Пример: 'падение 10 м'")

        val hMeters = h
        if (hMeters < 0.0) return listOf("Падение: высота должна быть положительной.")
        val t = sqrt(2.0 * hMeters / G)
        val v = G * t
        val sb = mutableListOf<String>()
        sb.add("Падение с ${"%.2f".format(hMeters)} м:")
        sb.add(" - Время падения ≈ ${"%.2f".format(t)} с")
        sb.add(" - Скорость при ударе ≈ ${"%.2f".format(v)} м/с (≈ ${"%.1f".format(v*3.6)} км/ч)")
        sb.add("Внимание: удар на такой скорости может быть опасен — это ориентировочные значения (без сопротивления воздуха).")
        return sb
    }

    // --------------------
    // КИПЯТОК — время вскипания воды
    // --------------------
    private fun handleBoil(cmd: String): List<String> {
        val lower = cmd.lowercase(Locale.getDefault())

        // парсинг объёма (л)
        val volParsed = PhysUtils.extractNumberWithUnit(lower, listOf("l", "л", "liter", "litre"))
        val volumeL = volParsed?.value ?: PhysUtils.extractNumberIfPresent(lower, listOf("v", "объем", "volume")) ?: PhysUtils.extractBareNumber(lower, "") ?: return listOf("Кипячение: укажите объём воды. Пример: 'кипяток 1.5 л из 20° мощность 2000 Вт'")

        val volumeM3 = volumeL / 1000.0
        val massKg = 1000.0 * volumeM3 // плотность ~1000 кг/м3

        // температуры
        val tFromMatch = Regex("""from\s*(-?\d+(?:[.,]\d+)?)\s*°?c""").find(lower)
        val tFrom = tFromMatch?.groupValues?.get(1)?.replace(',', '.')?.toDoubleOrNull()
            ?: PhysUtils.extractNumberIfPresent(lower, listOf("t0", "start", "from")) ?: 20.0
        val tTo = 100.0

        // парсинг мощности (Вт или кВт)
        val power = parsePowerWatts(lower) ?: return listOf("Кипячение: не найдена мощность. Укажите 'мощность 2000 Вт' или 'мощность 1.5 кВт'")

        // эффективность: плита/чайник
        val eff = if (lower.contains("stove") || lower.contains("плита")) 0.6 else 0.9

        val deltaT = tTo - tFrom
        if (deltaT <= 0.0) return listOf("Кипячение: конечная температура должна быть выше начальной.")

        val Q = massKg * 4184.0 * deltaT // Дж
        val tSec = Q / (power * eff)
        val sb = mutableListOf<String>()
        sb.add("Нагреть ${"%.2f".format(volumeL)} л воды с ${"%.1f".format(tFrom)}°C до 100°C при P=${formatWatts(power)} (эффективность ≈ ${"%.0f".format(eff*100)}%):")
        sb.add(" - Необходимая энергия: ≈ ${"%.0f".format(Q)} Дж (${String.format("%.3f", Q/3_600_000.0)} кВт·ч)")
        sb.add(" - Приблизительное время: ${PhysUtils.formatSecondsNice(tSec)} (≈ ${"%.1f".format(tSec/60.0)} мин)")
        sb.add("Примечание: реальное время зависит от формы ёмкости и потерь на нагрев воздуха/посуды.")
        return sb
    }

    // --------------------
    // ЗАРЯД — время зарядки аккумулятора
    // --------------------
    private fun handleCharge(cmd: String): List<String> {
        val lower = cmd.lowercase(Locale.getDefault())

        // пробуем Wh сначала
        val whParsed = PhysUtils.extractNumberWithUnit(lower, listOf("wh", "w h", "ваттчас", "втч"))
        var energyWh: Double? = null
        if (whParsed != null) energyWh = whParsed.value

        // пробуем mAh и напряжение
        val mahMatch = Regex("""(\d+(?:[.,]\d+)?)\s*m?a?h\b""").find(lower)
        val vMatch = Regex("""(\d+(?:[.,]\d+)?)\s*v\b""").find(lower)
        if (energyWh == null && mahMatch != null) {
            val mah = mahMatch.groupValues[1].replace(',', '.').toDoubleOrNull()
            val volt = vMatch?.groupValues?.get(1)?.replace(',', '.')?.toDoubleOrNull() ?: 3.7
            if (mah != null) energyWh = mah / 1000.0 * volt
        }

        // fallback: ёмкость без единиц (угадываем mAh)
        if (energyWh == null) {
            val bare = PhysUtils.extractBareNumber(lower, "")
            if (bare != null && bare > 1000) {
                // предполагаем mAh
                val volt = vMatch?.groupValues?.get(1)?.replace(',', '.')?.toDoubleOrNull() ?: 3.7
                energyWh = bare / 1000.0 * volt
            } else if (bare != null && bare <= 100) {
                // возможно Wh
                energyWh = bare
            }
        }

        if (energyWh == null) return listOf("Заряд: не удалось распознать ёмкость (mAh или Wh). Пример: 'заряд 5000 mAh 3.7V мощность 5 Вт'")

        val power = parsePowerWatts(lower) ?: return listOf("Заряд: не найдена мощность зарядки. Укажите 'мощность 5 Вт' или 'мощность 20 Вт'")

        // диапазон процентов (опционально)
        val percMatch = Regex("""(\d{1,3})\s*%\s*->\s*(\d{1,3})\s*%""").find(lower)
        val (fromP, toP) = if (percMatch != null) {
            val a = percMatch.groupValues[1].toIntOrNull() ?: 0
            val b = percMatch.groupValues[2].toIntOrNull() ?: 100
            Pair(a.coerceIn(0,100), b.coerceIn(0,100))
        } else {
            // найдём токены типа from 50% to 100%
            val fromMatch = Regex("""from\s*(\d{1,3})\s*%""").find(lower)
            val toMatch = Regex("""to\s*(\d{1,3})\s*%""").find(lower)
            val a = fromMatch?.groupValues?.get(1)?.toIntOrNull() ?: 0
            val b = toMatch?.groupValues?.get(1)?.toIntOrNull() ?: 100
            Pair(a.coerceIn(0,100), b.coerceIn(0,100))
        }

        val eff = 0.9
        val needWh = energyWh * (toP - fromP) / 100.0
        val timeSec = needWh / (power * eff) * 3600.0
        val sb = mutableListOf<String>()
        sb.add("Зарядка: ёмкость ≈ ${"%.2f".format(energyWh)} Wh, зарядка от ${fromP}% до ${toP}% при P=${formatWatts(power)} (эффективность ≈ ${"%.0f".format(eff*100)}%):")
        sb.add(" - Необходимая энергия: ≈ ${"%.2f".format(needWh)} Wh")
        sb.add(" - Примерное время: ${PhysUtils.formatSecondsNice(timeSec)} (≈ ${"%.1f".format(timeSec/3600.0)} ч)")
        sb.add("Примечание: быстрые циклы, специфика батареи и зарядного могут влиять на реальное время.")
        return sb
    }

    // --------------------
    // ЛЕСТНИЦА — энергия и калории подъёма
    // --------------------
    private fun handleStairs(cmd: String): List<String> {
        val lower = cmd.lowercase(Locale.getDefault())
        // этажи или высота
        val floorsMatch = Regex("""(\d+)\s*этаж""").find(lower)
        val heightProvided = PhysUtils.extractNumberIfPresent(lower, listOf("h", "height", "высота"))
        val floors = floorsMatch?.groupValues?.get(1)?.toIntOrNull()
        val floorHeight = 3.0
        val height = when {
            floors != null -> floors * floorHeight
            heightProvided != null -> heightProvided
            else -> PhysUtils.extractBareNumber(lower, "") ?: return listOf("Лестница: укажите этажи или высоту. Пример: 'лестница 3 этажа' или 'лестница высота 9'")
        }

        val mass = (PhysUtils.extractNumberWithUnit(lower, listOf("kg", "кг"))?.let { PhysUtils.normalizeMassToKg(it.value, it.unit) }
            ?: PhysUtils.extractBareNumber(lower, "weight") ?: PhysUtils.extractBareNumber(lower, "") ?: 70.0)

        val deltaPE = mass * G * height // Дж
        val kcalPure = deltaPE / 4184.0
        val muscleEff = 0.25 // эффективность мышц ~25%
        val kcalEstimated = kcalPure / muscleEff

        val sb = mutableListOf<String>()
        sb.add("Подняться на ${"%.2f".format(height)} м (масса ${"%.1f".format(mass)} кг):")
        sb.add(" - Чистая потенциальная энергия: ≈ ${"%.0f".format(deltaPE)} Дж (${String.format("%.2f", kcalPure)} ккал)")
        sb.add(" - С учётом эффективности мышц (~25%): ≈ ${String.format("%.1f", kcalEstimated)} ккал (ориентир)")
        sb.add("Примечание: реальная тратта энергии выше из-за шагов, ритма и темпа.")
        return sb
    }

    // --------------------
    // УКЛОН — ходьба по уклону (время и калории)
    // --------------------
    private fun handleIncline(cmd: String): List<String> {
        val lower = cmd.lowercase(Locale.getDefault())

        val distMeters = PhysUtils.parseDistanceMeters(lower) ?: run {
            val bare = PhysUtils.extractBareNumber(lower, "") ?: return listOf("Уклон: укажите расстояние (например, 2 км).")
            // предполагаем км если > 3
            if (bare > 3) bare * 1000.0 else bare
        }

        val slopePercent = PhysUtils.extractNumberIfPresent(lower, listOf("slope", "уклон", "%")) ?: 0.0
        val speedMps = PhysUtils.parseSpeedToMPerS(lower) ?: ( (PhysUtils.extractBareNumber(lower, "") ?: 5.0) / 3.6 )
        val mass = (PhysUtils.extractNumberWithUnit(lower, listOf("kg", "кг"))?.let { PhysUtils.normalizeMassToKg(it.value, it.unit) }
            ?: 75.0)

        val height = distMeters * (slopePercent / 100.0)
        val workJ = mass * G * height
        val timeSec = if (speedMps > 0) distMeters / speedMps else Double.POSITIVE_INFINITY

        // базовая ходьба ≈0.9 ккал/кг/км + работа подъёма
        val baseKcal = 0.9 * mass * (distMeters / 1000.0)
        val climbKcal = workJ / 4184.0
        val totalKcal = baseKcal + (climbKcal / 0.25) // поправка на эффективность

        val sb = mutableListOf<String>()
        sb.add("Маршрут ${"%.2f".format(distMeters/1000.0)} км при уклоне ${"%.2f".format(slopePercent)}%, скорость ${"%.1f".format(speedMps*3.6)} км/ч:")
        sb.add(" - Высота подъёма: ≈ ${"%.2f".format(height)} м")
        sb.add(" - Время: ≈ ${PhysUtils.formatSecondsNice(timeSec)}")
        sb.add(" - Ориентировочные калории: ≈ ${"%.0f".format(totalKcal)} ккал (включая базовую трату и подъём)")
        sb.add("Примечание: оценки приблизительны и зависят от темпа и рельефа.")
        return sb
    }

    // --------------------
    // ПОДЪЁМ — усилие и мощность для подъёма груза
    // --------------------
    private fun handleLift(cmd: String): List<String> {
        val lower = cmd.lowercase(Locale.getDefault())

        val mass = PhysUtils.extractNumberWithUnit(lower, listOf("kg", "кг"))?.let { PhysUtils.normalizeMassToKg(it.value, it.unit) }
            ?: PhysUtils.extractBareNumber(lower, "m") ?: PhysUtils.extractBareNumber(lower, "") ?: return listOf("Подъём: укажите массу. Пример: 'подъём 200 кг 2 м 5 с'")

        val height = PhysUtils.extractNumberIfPresent(lower, listOf("h", "height", "m", "метр")) ?: PhysUtils.extractBareNumber(lower, "") ?: 1.0
        val time = PhysUtils.extractNumberIfPresent(lower, listOf("t", "time", "s")) ?: PhysUtils.extractBareNumber(lower, "") ?: 1.0

        val force = mass * G
        val power = if (time > 0) mass * G * height / time else Double.POSITIVE_INFINITY

        val sb = mutableListOf<String>()
        sb.add("Поднять ${"%.1f".format(mass)} кг на ${"%.2f".format(height)} м за ${"%.1f".format(time)} с:")
        sb.add(" - Средняя сила (вертикальная): ≈ ${"%.0f".format(force)} Н")
        sb.add(" - Мощность: ≈ ${"%.0f".format(power)} Вт (${String.format("%.2f", power/1000.0)} кВт)")
        sb.add("Совет: для ручного подъёма оценивайте нагрузку на человека ≈100–200 Вт устойчиво; рассмотрите лебёдку/блоки при больших массах.")
        return sb
    }

    // --------------------
    // ЗВУК — ослабление звука с расстоянием и ориентиры по безопасности
    // --------------------
    private fun handleSound(cmd: String): List<String> {
        val lower = cmd.lowercase(Locale.getDefault())
        // парсинг уровня dB или дБ
        val dbMatch = Regex("""(-?\d+(?:[.,]\d+)?)\s*(d\s?b|дб)\b""").find(lower)
        val L1 = dbMatch?.groupValues?.get(1)?.replace(',', '.')?.toDoubleOrNull() ?: PhysUtils.extractBareNumber(lower, "")?.let { if (it > 10) it else null }
        if (L1 == null) return listOf("Звук: укажите уровень в дБ. Пример: 'звук 95 дБ на 1 м до 10 м'")

        // r1 и r2
        val atMatch = Regex("""(на|at)\s*(\d+(?:[.,]\d+)?)\s*(m|м)?""").find(lower)
        val toMatch = Regex("""(до|to)\s*(\d+(?:[.,]\d+)?)\s*(m|м)?""").find(lower)
        val r1 = atMatch?.groupValues?.get(2)?.replace(',', '.')?.toDoubleOrNull() ?: 1.0
        val r2 = toMatch?.groupValues?.get(2)?.replace(',', '.')?.toDoubleOrNull() ?: null

        val sb = mutableListOf<String>()
        if (r2 != null) {
            val L2 = L1 - 20.0 * log10(r2 / r1)
            sb.add("${"%.1f".format(L1)} дБ на ${"%.1f".format(r1)} м → на ${"%.1f".format(r2)} м ≈ ${"%.1f".format(L2)} дБ")
            sb.add("Ориентир по безопасности: длительное воздействие >85 дБ вредно. 100 дБ сокращает допустимое время воздействия.")
        } else {
            sb.add("Уровень: ${"%.1f".format(L1)} дБ (укажите 'на <r1> до <r2>' для расчёта на другом расстоянии).")
            sb.add("Пример: 'звук 95 дБ на 1 м до 10 м' даст ослабление на расстоянии.")
        }
        return sb
    }

    // --------------------
    // ОЩУЩЕНИЕ ХОЛОДА (wind chill) — ощущаемая температура
    // --------------------
    private fun handleWindchill(cmd: String): List<String> {
        val lower = cmd.lowercase(Locale.getDefault())
        val tMatch = Regex("""(-?\d+(?:[.,]\d+)?)\s*°?c\b""").find(lower)
        val T = tMatch?.groupValues?.get(1)?.replace(',', '.')?.toDoubleOrNull()
            ?: PhysUtils.extractNumberIfPresent(lower, listOf("t", "temp", "темп", "температура")) ?: return listOf("Ощущение: укажите температуру в °C. Пример: 'ощущ -5° ветер 20 км/ч'")

        // скорость ветра в км/ч или м/с
        var v = PhysUtils.parseSpeedToMPerS(lower)
        if (v == null) {
            val num = PhysUtils.extractBareNumber(lower, "wind") ?: PhysUtils.extractBareNumber(lower, "")
            if (num != null) {
                v = if (num <= 60) num / 3.6 else num // если <=60 предполагаем км/ч
            }
        }
        if (v == null) return listOf("Ощущение: укажите скорость ветра. Пример: 'ощущ -5° ветер 20 км/ч'")

        val vKmh = v * 3.6
        // формула NOAA: применима для T <= 10°C и v_kmh >= 4.8 км/ч
        val wc = if (T <= 10.0 && vKmh >= 4.8) {
            13.12 + 0.6215 * T - 11.37 * vKmh.pow(0.16) + 0.3965 * T * vKmh.pow(0.16)
        } else {
            T
        }
        val sb = mutableListOf<String>()
        sb.add("Темп воздуха: ${"%.1f".format(T)}°C, ветер ${"%.1f".format(vKmh)} км/ч → ощущается как ${"%.1f".format(wc)}°C")
        if (wc <= -27) sb.add("Внимание: риск обморожения при длительном воздействии.")
        return sb
    }

    // --------------------
    // ТЕПЛОПОТЕРИ — приближённая оценка теплопотерь комнаты
    // --------------------
    private fun handleHeatLoss(cmd: String): List<String> {
        val lower = cmd.lowercase(Locale.getDefault())
        val area = PhysUtils.extractNumberIfPresent(lower, listOf("area", "площадь")) ?: PhysUtils.extractNumberIfPresent(lower, listOf("m2", "м2")) ?: PhysUtils.extractBareNumber(lower, "") ?: return listOf("Теплопотери: укажите площадь комнаты в м². Пример: 'теплопотери 20 м2 высота 2.5 хорошая ΔT 20'")

        val height = PhysUtils.extractNumberIfPresent(lower, listOf("height", "h", "высота")) ?: 2.5
        val deltaT = PhysUtils.extractNumberIfPresent(lower, listOf("dT", "dt", "delta")) ?: PhysUtils.extractNumberIfPresent(lower, listOf("dt", "разница")) ?: 20.0
        val insulation = when {
            lower.contains("плохо") || lower.contains("poor") || lower.contains("bad") -> "плохая"
            lower.contains("хорош") || lower.contains("good") || lower.contains("good-insulation") -> "хорошая"
            else -> "средняя"
        }

        val coef = when (insulation) {
            "плохая" -> 1.0 // прибл. W/(м2·K)
            "хорошая" -> 0.3
            else -> 0.6
        }

        val Q = area * coef * deltaT // Вт
        val sb = mutableListOf<String>()
        sb.add("Комната: площадь ${"%.1f".format(area)} м², высота ${"%.1f".format(height)} м, ΔT=${"%.1f".format(deltaT)}°C, изоляция: $insulation")
        sb.add(" - Приблизительная мощность потерь: ≈ ${"%.0f".format(Q)} Вт")
        sb.add(" - Рекомендация: для поддержания температуры потребуется отопление ~${"%.0f".format(Q)} Вт (ориентир)")
        sb.add("Примечание: это грубая оценка; для точных расчётов используйте U-value (коэфф. теплопередачи) и конструктивные данные.")
        return sb
    }

    // --------------------
    // Вспомогательные парсеры и форматтеры
    // --------------------
    private fun parsePowerWatts(lower: String): Double? {
        // принимаем кВт, kW, кВт, Вт
        val reKw = Regex("""(-?\d+(?:[.,]\d+)?)\s*(kw|kW|кВт|квт)\b""", RegexOption.IGNORE_CASE)
        val mkw = reKw.find(lower)
        if (mkw != null) {
            val num = mkw.groupValues[1].replace(',', '.').toDoubleOrNull() ?: return null
            return num * 1000.0
        }
        val re = Regex("""(-?\d+(?:[.,]\d+)?)\s*(w|W|вт)\b""", RegexOption.IGNORE_CASE)
        val mw = re.find(lower)
        if (mw != null) {
            return mw.groupValues[1].replace(',', '.').toDoubleOrNull()
        }
        // попробовать plain number рядом с "power" или "мощность"
        val near = Regex("""(power|мощност)\s*[:=]?\s*(-?\d+(?:[.,]\d+)?)""", RegexOption.IGNORE_CASE).find(lower)
        if (near != null) return near.groupValues[2].replace(',', '.').toDoubleOrNull()
        return null
    }

    private fun formatWatts(w: Double): String {
        return if (w >= 1000.0) "${"%.2f".format(w/1000.0)} кВт" else "${"%.0f".format(w)} Вт"
    }
}

// --------------------
// RusPhysCommandsV2: скорость, необходимая чтобы пройти/проехать расстояние за заданное время.
//   Также различение "идти" и "ехать" (walk vs drive).
// --------------------
private object RusPhysCommandsV2 {

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
            ?: return listOf("Скорость: не нашёл расстояние. Пример: 'сколько ехать 10 км за 15 минут' или 'сколько идти 5 км за 50 минут'")
        val timeSec = PhysUtils.parseTimeToSeconds(cmd)
            ?: return listOf("Скорость: не нашёл целевого времени. Примеры: 'за 15 минут', 'в 1:30', 'за 90 мин', 'за 0.5 часа'")

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
            val steps = (distMeters / 0.75).roundToInt() // шаг ≈ 0.75 м
            lines.add("Оценка шагов (шаг ≈0.75 м): ≈ $steps шагов")
            lines.add("Комфортные скорости ходьбы: ~4.5–5.5 км/ч — быстрый шаг ~6.5 км/ч")
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
// RusPhysCommandsV3: projectile + energy + help (нижний приоритет)
// --------------------
private object RusPhysCommandsV3 {

    fun handleCommand(cmdRaw: String): List<String> {
        val cmd = cmdRaw.trim()
        val lower = cmd.lowercase(Locale.getDefault())

        // снаряд / проекция траектории
        val projRoots = listOf("проек", "парабол", "пуля", "брос", "пуск", "projectile", "range", "trajectory", "дальность")
        if (projRoots.any { lower.contains(it) } || (lower.contains("угол") && (lower.contains("м/с") || lower.contains("v")))) {
            return handleProjectile(cmd)
        }

        // энергия
        val energyRoots = listOf("энерг", "кинет", "потенц", "потенциальн", "kinetic", "potential", "energy")
        if (energyRoots.any { lower.contains(it) }) {
            return handleEnergy(cmd)
        }

        // help (низкий приоритет — справка)
        if (lower.contains("справк") || lower == "help" || lower.contains("помощь")) {
            return listOf(
                "Справка (RusPhysCommandsV3 + расширение): список доступных физических и прикладных команд.",
                "",
                "Существующие команды:",
                "1) время — 'время 150 км при 80 км/ч' — точное время в сек/мин/ч, разбивка и округление до 0.5 мин; показывает ETA.",
                "2) скорость — 'сколько ехать 10 км за 15 минут' или 'сколько идти 5 км за 50 минут' — средняя скорость и пэйс (мин/км).",
                "",
                "Новые практичные команды:",
                " • стоп <скорость> [сухо/мокро/лед] — расчёт торможения: реакция, тормозной путь, общее расстояние и время остановки.",
                "   Пример: 'торможение при 80 км/ч' → 'время реакции ≈1.0 с (≈22 м), тормозной путь ≈45 м, всего ≈67 м (≈2.8 с)'.",
                "",
                " • дистанция <скорость> — рекомендуемая безопасная дистанция между машинами при данной скорости (м и с).",
                "   Пример: 'дистанция 100 км/ч' → '2.5–3.0 с ≈70–84 м; на мокрой дороге — до ~110 м'.",
                "",
                " • вело-стоп <скорость> [диск/барабан] [сухо/мокро] [спуск/подъём <%>] — расчёт тормозного пути для велосипеда.",
                "   Пример: 'вело-стоп 30 км/ч диск сухо спуск 5%' → покажет тормозной путь и итог с реакцией.",
                "",
                " • падение <высота> — расчёт времени свободного падения и скорости удара.",
                "   Пример: 'падение 10 м' → '≈1.43 с, ≈50 км/ч'.",
                "",
                " • кипяток <объём л> из <темп> мощность <Вт> [чайник/плита] — время и энергия для нагрева воды до кипения.",
                "   Пример: 'кипяток 1.5 л из 20° мощность 2000 Вт'.",
                "",
                " • заряд <ёмкость mAh/Wh> [V] мощность <Вт> [от X% до Y%] — примерное время зарядки.",
                "   Пример: 'заряд 5000 mAh 3.7V мощность 5 Вт' или 'заряд 50 Wh мощность 20 Вт от 20% до 100%'.",
                "",
                " • лестница <этажи/высота> [вес <кг>] — сколько энергии и калорий нужно, чтобы подняться.",
                "   Пример: 'лестница 3 этажа вес 70 кг' → оценка затраченной энергии.",
                "",
                " • подъём <расстояние> уклон <%> скорость <км/ч> вес <кг> — время и энергозатраты при ходьбе в гору.",
                "   Пример: 'подъём 2 км уклон 6% скорость 5 км/ч вес 75 кг'.",
                "",
                " • подъёмник <масса кг> высота <м> время <с> — средняя сила и мощность для подъёма груза.",
                "   Пример: 'подъёмник 200 кг высота 2 м время 5 с' → мощность ≈800 Вт.",
                "",
                " • звук <уровень дБ> на <r1> до <r2> — как изменится громкость звука на другом расстоянии.",
                "   Пример: 'звук 95 дБ на 1 м до 10 м' → покажет ослабление.",
                "",
                " • ощущ/холод <темп °C> ветер <скорость> — ощущаемая температура (эффект ветра).",
                "   Пример: 'ощущ -5° ветер 20 км/ч' → покажет, как будет ощущаться.",
                "",
                " • тепло/теплопотери <площадь м²> высота <м> изоляция <хорошая/средняя/плохая> ΔT <°C> — прибл. теплопотери и требуемая мощность.",
                "   Пример: 'теплопотери 20 м² высота 2.5 хорошая ΔT 20' → оценка в Вт.",
                "",
                "Пояснения терминов:",
                " • Пэйс — время на 1 км (мин/км).",
                " • U-value — коэффициент теплопередачи (Вт/(м²·К)). Меньше U — лучше утепление. Если нужен точный расчёт теплопотерь, указывайте U-value и площади поверхности.",
                "",
                "Как вводить команды:",
                " • Поддерживается частично естественный ввод; эффективнее — конкретный ввод с параметрами.",
                " • По умолчанию: скорость — км/ч, расстояние — км (если >3), высота — м.",
                " • Доп. параметры: 'мокро', 'сухо', 'диск', 'спуск 5%' и т.д.",
                "",
                "Примеры для теста:",
                " • стоп 50 км/ч",
                " • дистанция 90 км/ч мокро",
                " • вело-стоп 25 км/ч диск спуск 7%",
                " • падение 5 м",
                " • кипяток 0.5 л из 20° мощность 1500 Вт",
                " • заряд 3000 mAh 3.7V мощность 10 Вт от 20% до 100%",
                " • лестница 4 этажа вес 75 кг",
                " • подъём 3 км уклон 5% скорость 6 км/ч вес 70 кг",
                " • подъёмник 80 кг высота 1.5 м время 2 с",
                " • звук 100 дБ на 1 м до 10 м",
                " • ощущ -10° ветер 30 км/ч",
                " • теплопотери 25 м² высота 2.5 средняя ΔT 20",
                "",
                "Если команда не распознана — появится пример правильного ввода."
            )
        }

        return emptyList()
    }

    // --------------------
    // Движение снаряда (без сопротивления воздуха)
    // --------------------
    private fun handleProjectile(cmd: String): List<String> {
        val lower = cmd.lowercase(Locale.getDefault())

        // парсинг v: поддерживаем m/s, км/ч и bare v
        val vParsed = PhysUtils.extractNumberWithUnit(lower, listOf("m/s", "м/с", "mps", "м/сек", "km/h", "км/ч", "kmh", "км/ч"))
        val v = vParsed?.let { PhysUtils.normalizeSpeedToMPerS(it.value, it.unit) }
            ?: PhysUtils.extractBareNumber(lower, "v") // возможно "v 30"
        if (v == null) return listOf("Снаряд: не найдена скорость (v). Пример: 'проект v=30 угол 45'")

        // угол в градусах
        var angleDeg: Double? = PhysUtils.extractAngleDegrees(lower)
        // дальность (м)
        val rangeMeters = PhysUtils.extractNumberIfPresent(lower, listOf("range", "дальность", "r"))
        // начальная высота h0
        val h0 = PhysUtils.extractNumberIfPresent(lower, listOf("h0", "h", "height", "высот", "высота")) ?: 0.0

        val g = 9.80665
        val out = mutableListOf<String>()

        // если указана дальность и угол не задан и h0==0 — ищем возможные углы
        if (rangeMeters != null && angleDeg == null && abs(h0) < 1e-9) {
            val sin2theta = (rangeMeters * g) / (v * v)
            if (sin2theta < -1.0 || sin2theta > 1.0) {
                out.add("Снаряд: при v=${"%.3f".format(v)} м/с невозможно достичь дальности ${"%.3f".format(rangeMeters)} м (sin2θ=${"%.6f".format(sin2theta)} вне [-1,1]).")
                out.add("Попробуйте увеличить скорость или уменьшить дальность.")
                return out
            }
            val twoTheta1 = asin(sin2theta)
            val theta1 = Math.toDegrees(twoTheta1 / 2.0)
            val theta2 = Math.toDegrees((PI - twoTheta1) / 2.0)
            out.add("Найдено два возможных угла (h0=0): ${"%.3f".format(theta1)}° и ${"%.3f".format(theta2)}°")
            // расчет для обоих углов
            for (ang in listOf(theta1, theta2)) {
                val (tf, maxH, rng) = computeProjectileFor(v, ang, h0)
                out.add("Угол ${"%.3f".format(ang)}° → время полёта ${"%.3f".format(tf)} с, макс. высота ${"%.3f".format(maxH)} м, дальность ${"%.3f".format(rng)} м")
            }
            out.add("Заметка: модель идеализирована — сопротивление воздуха не учтено.")
            return out
        }

        // если задан угол — обычный расчёт
        if (angleDeg != null) {
            val (tFlight, maxHeight, range) = computeProjectileFor(v, angleDeg, h0)
            val theta = Math.toRadians(angleDeg)
            val vx = v * cos(theta)
            val vy = v * sin(theta)
            out.add("Снаряд (без сопротивления воздуха):")
            out.add("v0 = ${"%.3f".format(v)} м/с, угол = ${"%.3f".format(angleDeg)}°")
            out.add("vx = ${"%.3f".format(vx)} м/с, vy = ${"%.3f".format(vy)} м/с")
            out.add("Время полёта (до земли): ${"%.3f".format(tFlight)} с")
            out.add("Макс. высота: ${"%.3f".format(maxHeight)} м")
            out.add("Дальность (гориз.): ${"%.3f".format(range)} м (~${"%.3f".format(range/1000.0)} км)")
            out.add("Замечание: модель идеализирована (без воздуха).")
            return out
        }

        // если заданы дальность и угол — сравнить
        if (rangeMeters != null && angleDeg != null) {
            val (tFlight, maxHeight, rangeCalc) = computeProjectileFor(v, angleDeg, h0)
            out.add("Расчёт по заданным параметрам:")
            out.add("Угол: ${"%.3f".format(angleDeg)}°, v=${"%.3f".format(v)} м/с, h0=${"%.3f".format(h0)} м")
            out.add("Полученная дальность: ${"%.3f".format(rangeCalc)} м (запрошено ${"%.3f".format(rangeMeters)} м)")
            return out
        }

        return listOf("Снаряд: укажите скорость (v) и угол или дальность. Пример: 'проект v=30 угол 45' или 'проект v=50 range=200'")
    }

    // helper для расчёта времени полёта, макс. высоты и дальности
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
    // ЭНЕРГИЯ — кинетическая и потенциальная + преобразования
    // --------------------
    private fun handleEnergy(cmd: String): List<String> {
        val lower = cmd.lowercase(Locale.getDefault())

        // парсинг массы (kg, г, lb)
        val massParsed = PhysUtils.extractNumberWithUnit(lower, listOf("kg", "кг", "g", "г", "lb", "фунт", "lbs"))
        val massKg = when {
            massParsed == null -> PhysUtils.extractBareNumber(lower, "m") // возможно "m 80"
            else -> PhysUtils.normalizeMassToKg(massParsed.value, massParsed.unit)
        }

        // парсинг скорости (м/с или км/ч)
        val velParsed = PhysUtils.extractNumberWithUnit(lower, listOf("m/s", "м/с", "mps", "м/сек", "km/h", "км/ч", "kmh"))
        val v = velParsed?.let { PhysUtils.normalizeSpeedToMPerS(it.value, it.unit) }
            ?: PhysUtils.extractBareNumber(lower, "v")

        // парсинг высоты
        val hParsed = PhysUtils.extractNumberWithUnit(lower, listOf("m", "м"))
        val h = hParsed?.value ?: PhysUtils.extractNumberIfPresent(lower, listOf("h", "height", "высота")) ?: PhysUtils.extractBareNumber(lower, "h")

        val g = 9.80665
        val out = mutableListOf<String>()

        if (massKg != null && v != null) {
            val ke = 0.5 * massKg * v * v
            out.add("Кинетическая энергия:")
            out.add(" m = ${"%.3f".format(massKg)} кг, v = ${"%.3f".format(v)} м/с")
            out.add(" KE = 0.5·m·v² = ${"%.3f".format(ke)} Дж")
            out.add(" → ${"%.3f".format(ke/1000.0)} кДж, ${"%.6f".format(ke/3_600_000.0)} кВт·ч, ${"%.3f".format(ke/4184.0)} ккал")
        }

        if (massKg != null && h != null) {
            val pe = massKg * g * h
            out.add("Потенциальная энергия:")
            out.add(" m = ${"%.3f".format(massKg)} кг, h = ${"%.3f".format(h)} м")
            out.add(" PE = m·g·h = ${"%.3f".format(pe)} Дж")
            out.add(" → ${"%.3f".format(pe/1000.0)} кДж, ${"%.6f".format(pe/3_600_000.0)} кВт·ч, ${"%.3f".format(pe/4184.0)} ккал")
        }

        if (out.isEmpty()) {
            return listOf("Энергия: укажите параметры. Примеры: 'энергия m=80 v=5' или 'потенц m=2 h=10'")
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
