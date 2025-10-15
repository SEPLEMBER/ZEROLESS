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
import java.security.SecureRandom
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.*
import com.nemesis.droidcrypt.R

class EnPhysAsActivity : AppCompatActivity() {

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

        setContentView(R.layout.activity_ru_phys_as)

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
                    addAssistantLine("Enter a command (help — for quick help).")
                }
                true
            } else {
                false
            }
        }

        addSystemLine("Template: modules contain practical physics commands. Type 'help' for brief help (below).")
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

        try {
            val mainRes = EngPhysCommandsMain.handleCommand(cmd)
            if (mainRes.isNotEmpty()) return mainRes
        } catch (_: Exception) { }

        try {
            val v2Res = EngPhysCommandsV2.handleCommand(cmd)
            if (v2Res.isNotEmpty()) return v2Res
        } catch (_: Exception) { }

        try {
            val v3Res = EngPhysCommandsV3.handleCommand(cmd)
            if (v3Res.isNotEmpty()) return v3Res
        } catch (_: Exception) { }

        return listOf("Unknown command. Type 'help' for details.")
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
// EngPhysCommandsMain: set of practical human-friendly commands in English.
// Supports: time, stop/braking, gap/distance, bike-stop, fall, boil, charge,
// stairs, incline, lift, sound, windchill, heatloss.
// --------------------
private object EngPhysCommandsMain {

    private const val G = 9.80665

    fun handleCommand(cmdRaw: String): List<String> {
        val cmd = cmdRaw.trim()
        val lower = cmd.lowercase(Locale.getDefault())

        // time
        if (lower.contains("time") || lower.contains("how long") || lower.contains("how much time")) {
            return handleTimeForDistance(cmd)
        }

        // stop / braking
        val stopRoots = listOf("stop", "brake", "braking")
        if (stopRoots.any { lower.contains(it) }) {
            return handleStop(cmd)
        }

        // gap / safe distance
        val gapRoots = listOf("gap", "distance", "safe distance", "interval")
        if (gapRoots.any { lower.contains(it) }) {
            return handleGap(cmd)
        }

        // bike stop
        val bikeRoots = listOf("bike", "bicycle", "cycling", "bike-stop")
        if (bikeRoots.any { lower.contains(it) }) {
            return handleBikeStop(cmd)
        }

        // fall
        val fallRoots = listOf("fall", "drop", "falling", "impact speed")
        if (fallRoots.any { lower.contains(it) }) {
            return handleFall(cmd)
        }

        // boil
        val boilRoots = listOf("boil", "boiling", "kettle")
        if (boilRoots.any { lower.contains(it) }) {
            return handleBoil(cmd)
        }

        // charge
        val chargeRoots = listOf("charge", "charging", "battery")
        if (chargeRoots.any { lower.contains(it) }) {
            return handleCharge(cmd)
        }

        // stairs / floors
        val stairsRoots = listOf("stairs", "floor", "story", "staircase")
        if (stairsRoots.any { lower.contains(it) }) {
            return handleStairs(cmd)
        }

        // incline / slope
        val inclineRoots = listOf("incline", "slope", "grade", "uphill", "downhill")
        if (inclineRoots.any { lower.contains(it) }) {
            return handleIncline(cmd)
        }

        // lift / hoist
        val liftRoots = listOf("lift", "hoist", "raise", "lifting")
        if (liftRoots.any { lower.contains(it) }) {
            return handleLift(cmd)
        }

        // sound
        val soundRoots = listOf("sound", "db", "decibel", "loudness")
        if (soundRoots.any { lower.contains(it) }) {
            return handleSound(cmd)
        }

        // windchill
        val windRoots = listOf("windchill", "feels like", "wind")
        if (windRoots.any { lower.contains(it) }) {
            return handleWindchill(cmd)
        }

        // heatloss
        val heatlossRoots = listOf("heatloss", "heat loss", "heating", "insulation")
        if (heatlossRoots.any { lower.contains(it) }) {
            return handleHeatLoss(cmd)
        }

        return emptyList()
    }

    // --------------------
    // Time for distance given speed (now formatted for miles/mph output)
    // --------------------
    private fun handleTimeForDistance(cmd: String): List<String> {
        val distMeters = PhysUtils.parseDistanceMeters(cmd)
            ?: return listOf(
                "Time: couldn't find distance. Example: 'time 100 mi at 60 mph' or 'time 10mi at 5 m/s'"
            )
        val speedMetersPerSec = PhysUtils.parseSpeedToMPerS(cmd)
            ?: return listOf(
                "Time: couldn't find speed. Example: 'time 100 mi at 60 mph' or 'time 10km at 5 m/s'"
            )

        val seconds = distMeters / speedMetersPerSec
        val roundedHalfMin = ( (seconds / 60.0) * 2.0 ).roundToInt() / 2.0 // minutes rounded to 0.5
        val hours = (seconds / 3600.0)
        val hh = floor(hours).toInt()
        val remMinutes = (hours - hh) * 60.0
        val mm = floor(remMinutes).toInt()
        val ss = ((remMinutes - mm) * 60.0).roundToInt()

        val sb = mutableListOf<String>()
        sb.add("Distance: ${PhysUtils.formatDistanceNice(distMeters)}")
        sb.add("Speed: ${PhysUtils.formatSpeedNice(speedMetersPerSec)}")
        sb.add("Exact time: ${"%.2f".format(seconds)} s (${PhysUtils.formatSecondsNice(seconds)})")
        sb.add("Breakdown: ${hh} h ${mm} min ${ss} sec")
        sb.add("Rounded to 0.5 min: ${roundedHalfMin} min (≈ ${PhysUtils.formatMinutesToHMS(roundedHalfMin)})")

        // Pace & per-100mi (adapted: per-100 miles makes little sense — show per-100 miles only when large)
        if (speedMetersPerSec > 0.0) {
            val secPerMile = 1609.344 / speedMetersPerSec
            sb.add("Pace: ${PhysUtils.formatPace(secPerMile)} (min/mi)")
            val time100miSec = 1609.344 * 100.0 / speedMetersPerSec
            sb.add("At this speed — 100 miles would take: ${PhysUtils.formatSecondsNice(time100miSec)}")
            sb.add("Speed: ${"%.2f".format(speedMetersPerSec*2.2369362920544)} mph (${String.format("%.2f", speedMetersPerSec)} m/s)")
        }

        // ETA (local time)
        try {
            val eta = LocalDateTime.now().plusSeconds(seconds.toLong())
            val fmt = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
            sb.add("Estimated arrival (local): ${eta.format(fmt)}")
        } catch (_: Exception) { /*ignore*/ }

        sb.add("Note: simple model — ignores terrain, traffic, or stops.")
        return sb
    }

    // --------------------
    // STOP — braking distance with reaction and road condition
    // --------------------
    private fun handleStop(cmd: String): List<String> {
        val lower = cmd.lowercase(Locale.getDefault())

        // parse speed
        var v = PhysUtils.parseSpeedToMPerS(lower)
        if (v == null) {
            val possible = PhysUtils.extractBareNumber(lower, "v") ?: PhysUtils.extractBareNumber(lower, "")
            if (possible != null) {
                // if number > 18 assume mph (18 mph ~ 29 km/h) else assume m/s? keep heuristic: > 10 assume mph? better: assume mph if user uses english context and number < 200
                v = if (possible > 10) possible / 2.2369362920544 else possible
            }
        }
        if (v == null) return listOf("Braking: couldn't recognize speed. Example: 'stop 60 mph' or 'brake 50 mph'")

        // reaction time
        val reactionDefault = 1.0 // s
        val reactionMatch = Regex("""(reaction|react|react time)\s*[:=]?\s*(-?\d+(?:[.,]\d+)?)""").find(lower)
        val reaction = reactionMatch?.groupValues?.get(2)?.replace(',', '.')?.toDoubleOrNull() ?: reactionDefault

        // road condition
        val mu = when {
            lower.contains("wet") -> 0.4
            lower.contains("ice") -> 0.12
            else -> 0.8 // dry by default
        }

        val a = mu * G
        val brakingDistance = if (a > 0) v * v / (2.0 * a) else Double.POSITIVE_INFINITY
        val reactionDist = v * reaction
        val timeBraking = if (a > 0) v / a else Double.POSITIVE_INFINITY
        val totalDist = reactionDist + brakingDistance
        val totalTime = reaction + timeBraking

        val sb = mutableListOf<String>()
        sb.add("Braking from ${"%.0f".format(v*2.2369362920544)} mph:")
        sb.add(" - Reaction time: ${"%.2f".format(reaction)} s → distance ≈ ${"%.1f".format(reactionDist)} m")
        sb.add(" - Estimated grip: μ ≈ ${"%.2f".format(mu)} (${if (mu==0.8) "dry" else if (mu==0.4) "wet" else "slippery"})")
        sb.add(" - Braking distance (after reaction): ≈ ${"%.1f".format(brakingDistance)} m")
        sb.add("Total to full stop: ≈ ${"%.1f".format(totalDist)} m (≈ ${"%.2f".format(totalTime)} s)")
        sb.add("Note: approximation — doesn't account for ABS, slope, tire condition.")
        return sb
    }

    // --------------------
    // GAP — safe following distance
    // --------------------
    private fun handleGap(cmd: String): List<String> {
        val lower = cmd.lowercase(Locale.getDefault())
        var v = PhysUtils.parseSpeedToMPerS(lower)
        if (v == null) {
            val num = PhysUtils.extractBareNumber(lower, "")
            if (num != null) v = if (num > 10) num / 2.2369362920544 else num
        }
        if (v == null) return listOf("Gap: couldn't find speed. Example: 'distance 60 mph'")

        val secondsNormal = 2.5
        val secondsWet = 4.0
        val seconds = if (lower.contains("wet")) secondsWet else secondsNormal
        val dist = v * seconds

        val sb = mutableListOf<String>()
        sb.add("Recommended safe gap at ${"%.0f".format(v*2.2369362920544)} mph:")
        sb.add(" - Time gap: ≈ ${"%.1f".format(seconds)} s")
        sb.add(" - Which is ≈ ${"%.1f".format(dist)} m (≈ ${"%.3f".format(dist/1609.344)} mi)")
        sb.add("Tip: increase gap in low visibility or slippery roads.")
        return sb
    }

    // --------------------
    // BIKE-STOP — bike braking distance
    // --------------------
    private fun handleBikeStop(cmd: String): List<String> {
        val lower = cmd.lowercase(Locale.getDefault())
        // speed
        var v = PhysUtils.parseSpeedToMPerS(lower)
        if (v == null) {
            val num = PhysUtils.extractBareNumber(lower, "")
            if (num != null) v = if (num > 10) num / 2.2369362920544 else num
        }
        if (v == null) return listOf("Bike-stop: specify speed. Example: 'bike-stop 20 mph disc dry'")

        // brake type
        val muBrake = when {
            lower.contains("disc") -> 0.7
            lower.contains("drum") -> 0.5
            else -> 0.6
        }

        // road surface
        val muRoad = when {
            lower.contains("wet") -> 0.4
            lower.contains("ice") -> 0.12
            else -> 0.8
        }

        // slope e.g. "down 5%"
        val slopeMatch = Regex("""(-?\d+(?:[.,]\d+)?)\s*%""").find(lower)
        val slopePercent = slopeMatch?.groupValues?.get(1)?.replace(',', '.')?.toDoubleOrNull() ?: 0.0
        val slopeAccel = (slopePercent / 100.0) * G // positive on downhill increases stopping distance

        val muEffective = min(muBrake, muRoad)
        val a = muEffective * G - slopeAccel
        val brakingDistance = if (a > 0) v * v / (2.0 * a) else Double.POSITIVE_INFINITY
        val reaction = 1.0
        val reactionDist = v * reaction
        val total = reactionDist + brakingDistance

        val sb = mutableListOf<String>()
        sb.add("Bicycle ${"%.0f".format(v*2.2369362920544)} mph, brakes: ${if (muBrake>=0.7) "disc" else "drum/regular"}")
        sb.add(" - Slope: ${"%.2f".format(slopePercent)}% → affects braking")
        sb.add(" - Braking distance ≈ ${if (brakingDistance.isFinite()) "%.1f".format(brakingDistance) + " m" else "infinite (too little grip)"}")
        sb.add(" - Add ≈ ${"%.1f".format(reactionDist)} m for reaction (~1 s). Total ≈ ${"%.1f".format(total)} m")
        sb.add("Tip: on descents reduce speed and use both brakes.")
        return sb
    }

    // --------------------
    // FALL — free-fall time and impact speed
    // --------------------
    private fun handleFall(cmd: String): List<String> {
        val lower = cmd.lowercase(Locale.getDefault())
        // parse height in meters or feet or miles
        val h = PhysUtils.extractNumberIfPresent(lower, listOf("m", "meter", "meters", "ft", "feet", "height"))
            ?: PhysUtils.parseDistanceMeters(lower) ?: PhysUtils.extractBareNumber(lower, "") ?: return listOf("Fall: specify height. Example: 'fall 10 m' or 'fall 30 ft'")

        val hMeters = h
        if (hMeters < 0.0) return listOf("Fall: height must be positive.")
        val t = sqrt(2.0 * hMeters / G)
        val v = G * t
        val sb = mutableListOf<String>()
        sb.add("Drop from ${"%.2f".format(hMeters)} m (${String.format("%.3f", hMeters/1609.344)} mi):")
        sb.add(" - Time of fall ≈ ${"%.2f".format(t)} s")
        sb.add(" - Impact speed ≈ ${"%.2f".format(v)} m/s (≈ ${"%.1f".format(v*2.2369362920544)} mph)")
        sb.add("Warning: impacts at this speed may be dangerous — approximations (no air resistance).")
        return sb
    }

    // --------------------
    // BOIL — time to boil water
    // --------------------
    private fun handleBoil(cmd: String): List<String> {
        val lower = cmd.lowercase(Locale.getDefault())

        // parse volume (liters)
        val volParsed = PhysUtils.extractNumberWithUnit(lower, listOf("l", "liter", "litre"))
        val volumeL = volParsed?.value ?: PhysUtils.extractNumberIfPresent(lower, listOf("v", "volume")) ?: PhysUtils.extractBareNumber(lower, "") ?: return listOf("Boiling: specify water volume. Example: 'boil 1.5 l from 20° power 2000 W'")

        val volumeM3 = volumeL / 1000.0
        val massKg = 1000.0 * volumeM3 // density ~1000 kg/m3

        // temperatures
        val tFromMatch = Regex("""from\s*(-?\d+(?:[.,]\d+)?)\s*°?c""").find(lower)
        val tFrom = tFromMatch?.groupValues?.get(1)?.replace(',', '.')?.toDoubleOrNull()
            ?: PhysUtils.extractNumberIfPresent(lower, listOf("t0", "start", "from")) ?: 20.0
        val tTo = 100.0

        // power (W or kW)
        val power = parsePowerWatts(lower) ?: return listOf("Boiling: couldn't find power. Specify 'power 2000 W' or 'power 1.5 kW'")

        // efficiency: stove / kettle
        val eff = if (lower.contains("stove")) 0.6 else 0.9

        val deltaT = tTo - tFrom
        if (deltaT <= 0.0) return listOf("Boiling: final temperature must be higher than initial.")

        val Q = massKg * 4184.0 * deltaT // J
        val tSec = Q / (power * eff)
        val sb = mutableListOf<String>()
        sb.add("Heat ${"%.2f".format(volumeL)} L water from ${"%.1f".format(tFrom)}°C to 100°C at P=${formatWatts(power)} (eff ≈ ${"%.0f".format(eff*100)}%):")
        sb.add(" - Energy required: ≈ ${"%.0f".format(Q)} J (${String.format("%.3f", Q/3_600_000.0)} kWh)")
        sb.add(" - Approx. time: ${PhysUtils.formatSecondsNice(tSec)} (≈ ${"%.1f".format(tSec/60.0)} min)")
        sb.add("Note: actual time depends on pot shape and heat losses.")
        return sb
    }

    // --------------------
    // CHARGE — battery charging time
    // --------------------
    private fun handleCharge(cmd: String): List<String> {
        val lower = cmd.lowercase(Locale.getDefault())

        // try Wh first
        val whParsed = PhysUtils.extractNumberWithUnit(lower, listOf("wh", "w h", "watt-hour", "watthours"))
        var energyWh: Double? = null
        if (whParsed != null) energyWh = whParsed.value

        // try mAh and voltage
        val mahMatch = Regex("""(\d+(?:[.,]\d+)?)\s*m?a?h\b""").find(lower)
        val vMatch = Regex("""(\d+(?:[.,]\d+)?)\s*v\b""").find(lower)
        if (energyWh == null && mahMatch != null) {
            val mah = mahMatch.groupValues[1].replace(',', '.').toDoubleOrNull()
            val volt = vMatch?.groupValues?.get(1)?.replace(',', '.')?.toDoubleOrNull() ?: 3.7
            if (mah != null) energyWh = mah / 1000.0 * volt
        }

        // fallback: capacity without unit
        if (energyWh == null) {
            val bare = PhysUtils.extractBareNumber(lower, "")
            if (bare != null && bare > 1000) {
                // assume mAh
                val volt = vMatch?.groupValues?.get(1)?.replace(',', '.')?.toDoubleOrNull() ?: 3.7
                energyWh = bare / 1000.0 * volt
            } else if (bare != null && bare <= 100) {
                // maybe Wh
                energyWh = bare
            }
        }

        if (energyWh == null) return listOf("Charge: couldn't parse capacity (mAh or Wh). Example: 'charge 5000 mAh 3.7V power 5 W'")

        val power = parsePowerWatts(lower) ?: return listOf("Charge: couldn't find charging power. Specify 'power 5 W' or 'power 20 W'")

        // percent range (optional)
        val percMatch = Regex("""(\d{1,3})\s*%\s*->\s*(\d{1,3})\s*%""").find(lower)
        val (fromP, toP) = if (percMatch != null) {
            val a = percMatch.groupValues[1].toIntOrNull() ?: 0
            val b = percMatch.groupValues[2].toIntOrNull() ?: 100
            Pair(a.coerceIn(0,100), b.coerceIn(0,100))
        } else {
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
        sb.add("Charging: capacity ≈ ${"%.2f".format(energyWh)} Wh, from ${fromP}% to ${toP}% at P=${formatWatts(power)} (eff ≈ ${"%.0f".format(eff*100)}%):")
        sb.add(" - Required energy: ≈ ${"%.2f".format(needWh)} Wh")
        sb.add(" - Approx. time: ${PhysUtils.formatSecondsNice(timeSec)} (≈ ${"%.1f".format(timeSec/3600.0)} h)")
        sb.add("Note: fast charge behavior and battery specifics may affect real time.")
        return sb
    }

    // --------------------
    // STAIRS — energy and calories to climb
    // --------------------
    private fun handleStairs(cmd: String): List<String> {
        val lower = cmd.lowercase(Locale.getDefault())
        // floors or height
        val floorsMatch = Regex("""(\d+)\s*floor|(\d+)\s*story""").find(lower)
        val heightProvided = PhysUtils.extractNumberIfPresent(lower, listOf("h", "height"))
        val floors = floorsMatch?.groupValues?.get(1)?.toIntOrNull() ?: floorsMatch?.groupValues?.get(2)?.toIntOrNull()
        val floorHeight = 3.0
        val height = when {
            floors != null -> floors * floorHeight
            heightProvided != null -> heightProvided
            else -> PhysUtils.extractBareNumber(lower, "") ?: return listOf("Stairs: specify floors or height. Example: 'stairs 3 floors' or 'stairs height 9'")
        }

        val mass = (PhysUtils.extractNumberWithUnit(lower, listOf("kg", "lb", "lbs"))?.let { PhysUtils.normalizeMassToKg(it.value, it.unit) }
            ?: PhysUtils.extractBareNumber(lower, "weight") ?: PhysUtils.extractBareNumber(lower, "") ?: 70.0)

        val deltaPE = mass * G * height // J
        val kcalPure = deltaPE / 4184.0
        val muscleEff = 0.25 // muscle efficiency ~25%
        val kcalEstimated = kcalPure / muscleEff

        val sb = mutableListOf<String>()
        sb.add("Climb ${"%.2f".format(height)} m (mass ${"%.1f".format(mass)} kg):")
        sb.add(" - Pure potential energy: ≈ ${"%.0f".format(deltaPE)} J (${String.format("%.2f", kcalPure)} kcal)")
        sb.add(" - With muscle efficiency (~25%): ≈ ${String.format("%.1f", kcalEstimated)} kcal (estimate)")
        sb.add("Note: real expenditure higher due to steps, cadence, pace.")
        return sb
    }

    // --------------------
    // INCLINE — walking on incline (time and calories)
    // --------------------
    private fun handleIncline(cmd: String): List<String> {
        val lower = cmd.lowercase(Locale.getDefault())

        val distMeters = PhysUtils.parseDistanceMeters(lower) ?: run {
            val bare = PhysUtils.extractBareNumber(lower, "") ?: return listOf("Incline: specify distance (e.g. 2 mi).")
            // assume miles if >3 (user likely meant miles); by default convert to meters assuming miles
            if (bare > 3) bare * 1609.344 else bare * 1000.0
        }

        val slopePercent = PhysUtils.extractNumberIfPresent(lower, listOf("slope", "grade", "%")) ?: 0.0
        val speedMps = PhysUtils.parseSpeedToMPerS(lower) ?: ( (PhysUtils.extractBareNumber(lower, "") ?: 5.0) / 3.6 )
        val mass = (PhysUtils.extractNumberWithUnit(lower, listOf("kg", "lb", "lbs"))?.let { PhysUtils.normalizeMassToKg(it.value, it.unit) }
            ?: 75.0)

        val height = distMeters * (slopePercent / 100.0)
        val workJ = mass * G * height
        val timeSec = if (speedMps > 0) distMeters / speedMps else Double.POSITIVE_INFINITY

        // base walking ≈0.9 kcal/kg/km + climb work
        val baseKcal = 0.9 * mass * (distMeters / 1000.0)
        val climbKcal = workJ / 4184.0
        val totalKcal = baseKcal + (climbKcal / 0.25) // muscle efficiency

        val sb = mutableListOf<String>()
        sb.add("Route ${"%.2f".format(distMeters/1609.344)} mi at ${"%.2f".format(slopePercent)}% grade, speed ${"%.1f".format(speedMps*2.2369362920544)} mph:")
        sb.add(" - Elevation gain: ≈ ${"%.2f".format(height)} m")
        sb.add(" - Time: ≈ ${PhysUtils.formatSecondsNice(timeSec)}")
        sb.add(" - Estimated calories: ≈ ${"%.0f".format(totalKcal)} kcal (including base & climb)")
        sb.add("Note: estimates are approximate and depend on pace and terrain.")
        return sb
    }

    // --------------------
    // LIFT — force and power to lift a load
    // --------------------
    private fun handleLift(cmd: String): List<String> {
        val lower = cmd.lowercase(Locale.getDefault())

        val mass = PhysUtils.extractNumberWithUnit(lower, listOf("kg", "lb", "lbs"))?.let { PhysUtils.normalizeMassToKg(it.value, it.unit) }
            ?: PhysUtils.extractBareNumber(lower, "m") ?: PhysUtils.extractBareNumber(lower, "") ?: return listOf("Lift: specify mass. Example: 'lift 200 kg 2 m 5 s'")

        val height = PhysUtils.extractNumberIfPresent(lower, listOf("h", "height", "m", "meter")) ?: PhysUtils.extractBareNumber(lower, "") ?: 1.0
        val time = PhysUtils.extractNumberIfPresent(lower, listOf("t", "time", "s")) ?: PhysUtils.extractBareNumber(lower, "") ?: 1.0

        val force = mass * G
        val power = if (time > 0) mass * G * height / time else Double.POSITIVE_INFINITY

        val sb = mutableListOf<String>()
        sb.add("Lift ${"%.1f".format(mass)} kg by ${"%.2f".format(height)} m in ${"%.1f".format(time)} s:")
        sb.add(" - Average vertical force: ≈ ${"%.0f".format(force)} N")
        sb.add(" - Power: ≈ ${"%.0f".format(power)} W (${String.format("%.2f", power/1000.0)} kW)")
        sb.add("Tip: sustained human power ≈100–200 W; consider winch/pulley for large loads.")
        return sb
    }

    // --------------------
    // SOUND — attenuation with distance and safety guidance
    // --------------------
    private fun handleSound(cmd: String): List<String> {
        val lower = cmd.lowercase(Locale.getDefault())
        // parse dB
        val dbMatch = Regex("""(-?\d+(?:[.,]\d+)?)\s*(d\s?b|db)\b""").find(lower)
        val L1 = dbMatch?.groupValues?.get(1)?.replace(',', '.')?.toDoubleOrNull() ?: PhysUtils.extractBareNumber(lower, "")?.let { if (it > 10) it else null }
        if (L1 == null) return listOf("Sound: specify level in dB. Example: 'sound 95 dB at 1 m to 10 m'")

        // r1 and r2
        val atMatch = Regex("""\bat\s*(\d+(?:[.,]\d+)?)\s*(m|ft)?""").find(lower)
        val toMatch = Regex("""\bto\s*(\d+(?:[.,]\d+)?)\s*(m|ft)?""").find(lower)
        val r1 = atMatch?.groupValues?.get(1)?.replace(',', '.')?.toDoubleOrNull() ?: 1.0
        val r2 = toMatch?.groupValues?.get(1)?.replace(',', '.')?.toDoubleOrNull() ?: null

        val sb = mutableListOf<String>()
        if (r2 != null) {
            val L2 = L1 - 20.0 * log10(r2 / r1)
            sb.add("${"%.1f".format(L1)} dB at ${"%.1f".format(r1)} m → at ${"%.1f".format(r2)} m ≈ ${"%.1f".format(L2)} dB")
            sb.add("Guidance: prolonged exposure >85 dB is harmful. 100 dB shortens allowable exposure.")
        } else {
            sb.add("Level: ${"%.1f".format(L1)} dB (specify 'at <r1> to <r2>' to calculate at another distance).")
            sb.add("Example: 'sound 95 dB at 1 m to 10 m' will show attenuation over distance.")
        }
        return sb
    }

    // --------------------
    // WINDCHILL — feels-like temperature (uses Celsius input)
    // --------------------
    private fun handleWindchill(cmd: String): List<String> {
        val lower = cmd.lowercase(Locale.getDefault())
        val tMatch = Regex("""(-?\d+(?:[.,]\d+)?)\s*°?c\b""").find(lower)
        val T = tMatch?.groupValues?.get(1)?.replace(',', '.')?.toDoubleOrNull()
            ?: PhysUtils.extractNumberIfPresent(lower, listOf("t", "temp", "temperature")) ?: return listOf("Windchill: specify temperature in °C. Example: 'windchill -5° wind 20 mph'")

        // wind speed in mph or m/s
        var v = PhysUtils.parseSpeedToMPerS(lower)
        if (v == null) {
            val num = PhysUtils.extractBareNumber(lower, "wind") ?: PhysUtils.extractBareNumber(lower, "")
            if (num != null) {
                v = if (num <= 60) num / 2.2369362920544 else num // if <=60 assume mph
            }
        }
        if (v == null) return listOf("Windchill: specify wind speed. Example: 'windchill -5° wind 20 mph'")

        val vKmh = v * 3.6
        // NOAA formula: applicable for T <= 10°C and v_kmh >= 4.8 km/h
        val wc = if (T <= 10.0 && vKmh >= 4.8) {
            13.12 + 0.6215 * T - 11.37 * vKmh.pow(0.16) + 0.3965 * T * vKmh.pow(0.16)
        } else {
            T
        }
        val sb = mutableListOf<String>()
        sb.add("Air temp: ${"%.1f".format(T)}°C, wind ${"%.1f".format(v*2.2369362920544)} mph → feels like ${"%.1f".format(wc)}°C")
        if (wc <= -27) sb.add("Warning: risk of frostbite with prolonged exposure.")
        return sb
    }

    // --------------------
    // HEAT LOSS — rough room heat loss estimate (area in m^2 or ft^2)
    // --------------------
    private fun handleHeatLoss(cmd: String): List<String> {
        val lower = cmd.lowercase(Locale.getDefault())
        val area = PhysUtils.extractNumberIfPresent(lower, listOf("area", "m2", "ft2", "sqft")) ?: PhysUtils.extractBareNumber(lower, "") ?: return listOf("Heat loss: specify room area in m². Example: 'heatloss 200 ft2 height 8 good ΔT 20'")

        // If user provided ft2, convert to m2 in extractNumberWithUnit; otherwise treat as m2
        val height = PhysUtils.extractNumberIfPresent(lower, listOf("height", "h")) ?: 2.5
        val deltaT = PhysUtils.extractNumberIfPresent(lower, listOf("dT", "dt", "delta")) ?: 20.0
        val insulation = when {
            lower.contains("poor") || lower.contains("bad") -> "poor"
            lower.contains("good") || lower.contains("well") -> "good"
            else -> "average"
        }

        val coef = when (insulation) {
            "poor" -> 1.0 // approx W/(m2·K)
            "good" -> 0.3
            else -> 0.6
        }

        val Q = area * coef * deltaT // W
        val sb = mutableListOf<String>()
        sb.add("Room: area ${"%.1f".format(area)} m², height ${"%.1f".format(height)} m, ΔT=${"%.1f".format(deltaT)}°C, insulation: $insulation")
        sb.add(" - Approx. heat loss power: ≈ ${"%.0f".format(Q)} W")
        sb.add(" - Recommendation: heating roughly ~${"%.0f".format(Q)} W to maintain temperature (rough)")
        sb.add("Note: coarse estimate; for precise calc use U-values and construction details.")
        return sb
    }

    // --------------------
    // Helper parsers/formatters (shared)
    // --------------------
    private fun parsePowerWatts(lower: String): Double? {
        // accept kW, kw, W, watt, kW variants
        val reKw = Regex("""(-?\d+(?:[.,]\d+)?)\s*(kw|kw\b|kW|kW\b|kW|kW)\b""", RegexOption.IGNORE_CASE)
        val mkw = reKw.find(lower)
        if (mkw != null) {
            val num = mkw.groupValues[1].replace(',', '.').toDoubleOrNull() ?: return null
            return num * 1000.0
        }
        val re = Regex("""(-?\d+(?:[.,]\d+)?)\s*(w|W|watt|watts)\b""", RegexOption.IGNORE_CASE)
        val mw = re.find(lower)
        if (mw != null) {
            return mw.groupValues[1].replace(',', '.').toDoubleOrNull()
        }
        val near = Regex("""(power|power[:=]|power\s*)\s*(-?\d+(?:[.,]\d+)?)""", RegexOption.IGNORE_CASE).find(lower)
        if (near != null) return near.groupValues[2].replace(',', '.').toDoubleOrNull()
        return null
    }

    private fun formatWatts(w: Double): String {
        return if (w >= 1000.0) "${"%.2f".format(w/1000.0)} kW" else "${"%.0f".format(w)} W"
    }
}

// --------------------
// EngPhysCommandsV2: required speed to cover a distance in given time (walk vs drive).
// --------------------
private object EngPhysCommandsV2 {

    fun handleCommand(cmdRaw: String): List<String> {
        val cmd = cmdRaw.trim()
        val lower = cmd.lowercase(Locale.getDefault())

        val rootSpeed = listOf("speed", "how fast", "how long to go", "how long to drive", "how long to walk")
        if (rootSpeed.any { lower.contains(it) } || (lower.contains("how") && (lower.contains("walk") || lower.contains("drive")))) {
            return handleRequiredSpeed(cmd)
        }

        return emptyList()
    }

    private fun handleRequiredSpeed(cmd: String): List<String> {
        val distMeters = PhysUtils.parseDistanceMeters(cmd)
            ?: return listOf("Speed: couldn't find distance. Example: 'how fast 10 mi in 15 minutes' or 'how fast walk 5 mi in 50 minutes'")
        val timeSec = PhysUtils.parseTimeToSeconds(cmd)
            ?: return listOf("Speed: couldn't find target time. Examples: 'in 15 minutes', '1:30', '90 min', '0.5 hours'")

        val lower = cmd.lowercase(Locale.getDefault())
        val walkingRoots = listOf("walk", "walking", "hike", "on foot")
        val drivingRoots = listOf("drive", "driving", "car", "bike", "bicycle")
        val mode = when {
            walkingRoots.any { lower.contains(it) } -> "walk"
            drivingRoots.any { lower.contains(it) } -> "drive"
            else -> "either"
        }

        val neededMps = distMeters / timeSec
        val neededKmh = neededMps * 3.6
        val neededMph = neededMps * 2.2369362920544
        val paceSecPerMile = if (neededMps > 0) 1609.344 / neededMps else Double.POSITIVE_INFINITY

        val lines = mutableListOf<String>()
        lines.add("Distance: ${PhysUtils.formatDistanceNice(distMeters)}")
        lines.add("Target time: ${PhysUtils.formatSecondsNice(timeSec)}")
        lines.add("Required speed: ${"%.2f".format(neededMph)} mph (${String.format("%.2f", neededMps)} m/s)")

        if (mode == "walk" || mode == "either") {
            lines.add("Pace for walking: ${PhysUtils.formatPace(paceSecPerMile)} (min/mi)")
            val steps = (distMeters / 0.75).roundToInt() // step ≈0.75 m
            lines.add("Estimated steps (step ≈0.75 m): ≈ $steps steps")
            lines.add("Comfortable walking speeds: ~3–3.5 mph — brisk ~4 mph")
        }
        if (mode == "drive" || mode == "either") {
            lines.add("For driving: ${"%.2f".format(neededMph)} mph")
            lines.add("Safety: check posted limits and road conditions.")
        }

        if (neededMph > 200.0) {
            lines.add("Warning: required speed is very large (${String.format("%.1f", neededMph)} mph). Unrealistic/dangerous.")
        } else if (neededMph < 1.0 && mode == "drive") {
            lines.add("Note: extremely low driving speed — maybe you meant walking.")
        }

        lines.add("Details:")
        lines.add(" - Average speed = ${"%.2f".format(neededMph)} mph")
        lines.add(" - Pace = ${PhysUtils.formatPace(paceSecPerMile)} (min/mi)")

        return lines
    }
}

// --------------------
// EngPhysCommandsV3: projectile + energy + help (lower priority)
// --------------------
private object EngPhysCommandsV3 {

    fun handleCommand(cmdRaw: String): List<String> {
        val cmd = cmdRaw.trim()
        val lower = cmd.lowercase(Locale.getDefault())

        // projectile / range / trajectory
        val projRoots = listOf("projectile", "range", "trajectory", "launch", "throw", "angle")
        if (projRoots.any { lower.contains(it) }) {
            return handleProjectile(cmd)
        }

        // energy
        val energyRoots = listOf("energy", "kinetic", "potential")
        if (energyRoots.any { lower.contains(it) }) {
            return handleEnergy(cmd)
        }

        // help
        if (lower.contains("help") || lower.contains("info") || lower.contains("assist") || lower.contains("manual")) {
            return listOf(
                "Help (EngPhysCommandsV3 + extensions): list of available practical physics commands.",
                "",
                "Available commands:",
                "1) time — 'time 100 mi at 60 mph' — exact time in s/min/h, breakdown and rounded to 0.5 min; shows ETA.",
                "2) speed — 'how fast 10 mi in 15 minutes' or 'how fast walk 5 mi in 50 minutes' — average speed and pace (min/mi).",
                "",
                "Practical commands:",
                " • stop <speed> [dry/wet/ice] — braking calc: reaction, braking distance, total stopping distance & time.",
                "   Example: 'stop 60 mph' → 'reaction ≈1.0 s (≈27 m), braking ≈45 m, total ≈72 m (≈3.2 s)'.",
                "",
                " • distance <speed> — recommended following distance at given speed (m and s).",
                "   Example: 'distance 60 mph' → '2.5 s ≈ X m; when wet — increase to ~Y m'.",
                "",
                " • bike-stop <speed> [disc/drum] [dry/wet] [slope <%>] — bike stopping distance.",
                "   Example: 'bike-stop 20 mph disc dry down 5%' → shows braking + reaction.",
                "",
                " • fall <height> — free-fall time and impact speed.",
                "   Example: 'fall 10 m' → '≈1.43 s, ≈50 km/h'.",
                "",
                " • boil <volume L> from <temp> power <W> [kettle/stove] — heating time & energy.",
                "   Example: 'boil 1.5 l from 20° power 2000 W'.",
                "",
                " • charge <capacity mAh/Wh> [V] power <W> [from X% to Y%] — approximate charging time.",
                "   Example: 'charge 5000 mAh 3.7V power 5 W' or 'charge 50 Wh power 20 W from 20% to 100%'.",
                "",
                " • stairs <floors/height> [weight <kg/lb>] — energy & calories to climb.",
                "   Example: 'stairs 3 floors weight 70 kg'.",
                "",
                " • incline <distance> grade <%> speed <mph/kmh> weight <kg/lb> — time & energy for uphill walk.",
                "   Example: 'incline 2 mi grade 6% speed 3 mph weight 75 kg'.",
                "",
                " • lift <mass kg/lb> height <m/ft> time <s> — average force & power required.",
                "   Example: 'lift 200 kg height 2 m time 5 s' → power ≈800 W.",
                "",
                " • sound <level dB> at <r1> to <r2> — how loud at another distance.",
                "   Example: 'sound 95 dB at 1 m to 10 m'.",
                "",
                " • windchill <temp °C> wind <speed mph> — feels-like temperature (wind chill).",
                "   Example: 'windchill -5° wind 20 mph'.",
                "",
                " • heatloss <area m2/ft2> height <m> insulation <good/average/poor> ΔT <°C> — rough heat loss (W).",
                "   Example: 'heatloss 200 ft2 height 2.5 average ΔT 20'.",
                "",
                "Notes:",
                " • Pace — minutes per mile (min/mi).",
                " • If you need metric output instead, include units (km, km/h, m/s, m).",
                "",
                "How to type commands:",
                " • Natural input partially supported; more explicit parameter style yields best results.",
                " • Defaults: speeds will be interpreted contextually (mph vs km/h) and distances may be assumed miles in English examples.",
                "",
                "Test examples:",
                " • stop 50 mph",
                " • distance 55 mph wet",
                " • bike-stop 25 mph disc down 7%",
                " • fall 5 m",
                " • boil 0.5 l from 20° power 1500 W",
                " • charge 3000 mAh 3.7V power 10 W from 20% to 100%",
                " • stairs 4 floors weight 75 kg",
                " • incline 3 mi grade 5% speed 3 mph weight 70 kg",
                " • lift 80 kg height 1.5 m time 2 s",
                " • sound 100 dB at 1 m to 10 m",
                " • windchill -10° wind 30 mph",
                " • heatloss 25 m² height 2.5 average ΔT 20",
                "",
                "If unrecognized — an example will be shown."
            )
        }

        return emptyList()
    }

    // --------------------
    // Projectile motion (no air resistance)
    // --------------------
    private fun handleProjectile(cmd: String): List<String> {
        val lower = cmd.lowercase(Locale.getDefault())

        // parse v: supports m/s, km/h, mph
        val vParsed = PhysUtils.extractNumberWithUnit(lower, listOf("m/s", "mps", "m/s", "km/h", "kmh", "mph", "mi/h"))
        val v = vParsed?.let { PhysUtils.normalizeSpeedToMPerS(it.value, it.unit) }
            ?: PhysUtils.extractBareNumber(lower, "v") // maybe "v 30"
        if (v == null) return listOf("Projectile: velocity (v) not found. Example: 'projectile v=30 angle 45'")

        // angle degrees
        var angleDeg: Double? = PhysUtils.extractAngleDegrees(lower)
        // range (m)
        val rangeMeters = PhysUtils.extractNumberIfPresent(lower, listOf("range", "distance", "r"))
        // initial height h0
        val h0 = PhysUtils.extractNumberIfPresent(lower, listOf("h0", "h", "height")) ?: 0.0

        val g = 9.80665
        val out = mutableListOf<String>()

        // if given range and no angle and h0==0 — find angles
        if (rangeMeters != null && angleDeg == null && abs(h0) < 1e-9) {
            val sin2theta = (rangeMeters * g) / (v * v)
            if (sin2theta < -1.0 || sin2theta > 1.0) {
                out.add("Projectile: with v=${"%.3f".format(v)} m/s cannot reach range ${"%.3f".format(rangeMeters)} m (sin2θ=${"%.6f".format(sin2theta)} out of [-1,1]).")
                out.add("Try increasing speed or decreasing range.")
                return out
            }
            val twoTheta1 = asin(sin2theta)
            val theta1 = Math.toDegrees(twoTheta1 / 2.0)
            val theta2 = Math.toDegrees((PI - twoTheta1) / 2.0)
            out.add("Two possible angles (h0=0): ${"%.3f".format(theta1)}° and ${"%.3f".format(theta2)}°")
            for (ang in listOf(theta1, theta2)) {
                val (tf, maxH, rng) = computeProjectileFor(v, ang, h0)
                out.add("Angle ${"%.3f".format(ang)}° → time ${"%.3f".format(tf)} s, max height ${"%.3f".format(maxH)} m, range ${"%.3f".format(rng)} m")
            }
            out.add("Note: idealized model — no air resistance.")
            return out
        }

        // if angle present — normal calc
        if (angleDeg != null) {
            val (tFlight, maxHeight, range) = computeProjectileFor(v, angleDeg, h0)
            val theta = Math.toRadians(angleDeg)
            val vx = v * cos(theta)
            val vy = v * sin(theta)
            out.add("Projectile (no air):")
            out.add("v0 = ${"%.3f".format(v)} m/s, angle = ${"%.3f".format(angleDeg)}°")
            out.add("vx = ${"%.3f".format(vx)} m/s, vy = ${"%.3f".format(vy)} m/s")
            out.add("Flight time: ${"%.3f".format(tFlight)} s")
            out.add("Max height: ${"%.3f".format(maxHeight)} m")
            out.add("Range (horiz.): ${"%.3f".format(range)} m (~${"%.3f".format(range/1609.344)} mi)")
            out.add("Note: idealized model (no air).")
            return out
        }

        if (rangeMeters != null && angleDeg != null) {
            val (tFlight, maxHeight, rangeCalc) = computeProjectileFor(v, angleDeg, h0)
            out.add("Calculation with given params:")
            out.add("Angle: ${"%.3f".format(angleDeg)}°, v=${"%.3f".format(v)} m/s, h0=${"%.3f".format(h0)} m")
            out.add("Resulting range: ${"%.3f".format(rangeCalc)} m (requested ${"%.3f".format(rangeMeters)} m)")
            return out
        }

        return listOf("Projectile: provide speed (v) and angle or range. Example: 'projectile v=30 angle 45' or 'projectile v=50 range=200'")
    }

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
    // ENERGY — kinetic & potential, conversions
    // --------------------
    private fun handleEnergy(cmd: String): List<String> {
        val lower = cmd.lowercase(Locale.getDefault())

        // parse mass (kg, g, lb)
        val massParsed = PhysUtils.extractNumberWithUnit(lower, listOf("kg", "g", "lb", "lbs"))
        val massKg = when {
            massParsed == null -> PhysUtils.extractBareNumber(lower, "m") // maybe "m 80"
            else -> PhysUtils.normalizeMassToKg(massParsed.value, massParsed.unit)
        }

        // parse velocity (m/s, km/h, mph)
        val velParsed = PhysUtils.extractNumberWithUnit(lower, listOf("m/s", "km/h", "mph", "mi/h"))
        val v = velParsed?.let { PhysUtils.normalizeSpeedToMPerS(it.value, it.unit) }
            ?: PhysUtils.extractBareNumber(lower, "v")

        // parse height
        val hParsed = PhysUtils.extractNumberWithUnit(lower, listOf("m", "meter", "ft", "feet"))
        val h = hParsed?.value ?: PhysUtils.extractNumberIfPresent(lower, listOf("h", "height")) ?: PhysUtils.extractBareNumber(lower, "h")

        val g = 9.80665
        val out = mutableListOf<String>()

        if (massKg != null && v != null) {
            val ke = 0.5 * massKg * v * v
            out.add("Kinetic energy:")
            out.add(" m = ${"%.3f".format(massKg)} kg, v = ${"%.3f".format(v)} m/s")
            out.add(" KE = 0.5·m·v² = ${"%.3f".format(ke)} J")
            out.add(" → ${"%.3f".format(ke/1000.0)} kJ, ${"%.6f".format(ke/3_600_000.0)} kWh, ${"%.3f".format(ke/4184.0)} kcal")
        }

        if (massKg != null && h != null) {
            val pe = massKg * g * h
            out.add("Potential energy:")
            out.add(" m = ${"%.3f".format(massKg)} kg, h = ${"%.3f".format(h)} m")
            out.add(" PE = m·g·h = ${"%.3f".format(pe)} J")
            out.add(" → ${"%.3f".format(pe/1000.0)} kJ, ${"%.6f".format(pe/3_600_000.0)} kWh, ${"%.3f".format(pe/4184.0)} kcal")
        }

        if (out.isEmpty()) {
            return listOf("Energy: specify parameters. Examples: 'energy m=80 v=5' or 'potential m=2 h=10'")
        }
        out.add("Note: results in SI units, rounded for readability.")
        return out
    }
}

// --------------------
// PhysUtils: parsing & formatting utilities (adapted to accept imperial units and format outputs in imperial)
// --------------------
private object PhysUtils {

    data class NumUnit(val value: Double, val unit: String?)

    // parse distance like "123 km", "123km", "123 mi", "123 mi", "123 m", "200 ft"
    // returns meters
    fun parseDistanceMeters(s: String): Double? {
        val lower = s.lowercase(Locale.getDefault())
        // explicit matches
        val pattern = Regex("""(-?\d+(?:[.,]\d+)?)\s*(km|k?m\b|км|km/h|км/ч|m\b|meters|meter|метр|mi|mile|miles|ft|feet|feet\b)""", RegexOption.IGNORE_CASE)
        val matches = pattern.findAll(lower).toList()
        if (matches.isNotEmpty()) {
            for (m in matches) {
                val num = m.groupValues[1].replace(',', '.').toDoubleOrNull() ?: continue
                val unit = m.groupValues[2].lowercase(Locale.getDefault())
                return when {
                    unit.contains("mi") || unit.contains("mile") -> num * 1609.344
                    unit.contains("km") || unit == "км" -> num * 1000.0
                    unit == "m" || unit.contains("meter") || unit.contains("метр") -> num
                    unit.contains("ft") || unit.contains("feet") -> num * 0.3048
                    // avoid matching km/h as distance
                    unit.contains("km/h") || unit.contains("км/ч") -> continue
                    else -> num
                }
            }
        }
        // fallback: look for any number — ambiguous: assume miles if in English context and number > 3, else meters? here assume meters if <= 1000 else meters
        val anyNum = Regex("""(-?\d+(?:[.,]\d+)?)""").find(lower)?.groupValues?.get(1)?.replace(',', '.')
        return anyNum?.toDoubleOrNull()
    }

    // Parse speed: supports "80 mph", "80 mph", "80 km/h", "5 m/s"
    // returns m/s
    fun parseSpeedToMPerS(s: String): Double? {
        val lower = s.lowercase(Locale.getDefault())
        val re = Regex("""(-?\d+(?:[.,]\d+)?)\s*(mph|mi/h|km/h|км/ч|kmh|m/s|mps|m/s|м/с|м/сек)\b""", RegexOption.IGNORE_CASE)
        val m = re.find(lower)
        if (m != null) {
            val num = m.groupValues[1].replace(',', '.').toDoubleOrNull() ?: return null
            val unit = m.groupValues[2].lowercase(Locale.getDefault())
            return when {
                unit.contains("mph") || unit.contains("mi/") || unit == "mi" -> num * 0.44704 // 1 mph = 0.44704 m/s
                unit.contains("km") -> num / 3.6
                unit.contains("m/s") || unit.contains("м/с") || unit.contains("mps") -> num
                else -> num
            }
        }
        // support "at 60" assume mph in English context; but if user used km/h explicitly earlier they'd include unit.
        val pri = Regex("""(?:at|@)\s+(-?\d+(?:[.,]\d+)?)\s*(mph|mi/h|km/h|km|m/s)?""", RegexOption.IGNORE_CASE).find(lower)
        if (pri != null) {
            val num = pri.groupValues[1].replace(',', '.').toDoubleOrNull() ?: return null
            val unit = pri.groupValues.getOrNull(2)
            return when {
                unit == null || unit.isBlank() -> num * 0.44704 // assume mph
                unit.contains("mph") || unit.contains("mi/") -> num * 0.44704
                unit.contains("km") -> num / 3.6
                unit.contains("m/s") -> num
                else -> num * 0.44704
            }
        }
        // support "при 80" (if Russian) assume km/h behavior — keep some backward compatibility
        val rusPri = Regex("""при\s+(-?\d+(?:[.,]\d+)?)\s*(?:км/ч|km/h|км|km)?""", RegexOption.IGNORE_CASE).find(lower)
        if (rusPri != null) {
            val num = rusPri.groupValues[1].replace(',', '.').toDoubleOrNull() ?: return null
            return num / 3.6
        }
        return null
    }

    // Format distance: show miles when >= 1 mile, else show feet
    fun formatDistanceNice(meters: Double): String {
        val miles = meters / 1609.344
        return if (miles >= 1.0) {
            "${"%.3f".format(miles)} mi"
        } else {
            val feet = meters * 3.28084
            "${"%.1f".format(feet)} ft"
        }
    }

    // Format speed: show mph and m/s
    fun formatSpeedNice(mps: Double): String {
        val mph = mps * 2.2369362920544
        return "${"%.2f".format(mph)} mph (${String.format(Locale.getDefault(), "%.2f", mps)} m/s)"
    }

    fun formatSecondsNice(secDouble: Double): String {
        val s = secDouble.roundToInt().coerceAtLeast(0)
        val h = s / 3600
        val m = (s % 3600) / 60
        val sRem = s % 60
        return "${h}h ${m}m ${sRem}s"
    }

    fun formatMinutesToHMS(minutes: Double): String {
        val totalSec = (minutes * 60.0).roundToInt()
        val h = totalSec / 3600
        val m = (totalSec % 3600) / 60
        val s = totalSec % 60
        return "${h}h ${m}min ${s}sec"
    }

    // format pace (seconds per mile) as min:sec per mile
    fun formatPace(secPerMile: Double): String {
        if (!secPerMile.isFinite() || secPerMile.isNaN()) return "—"
        val m = floor(secPerMile / 60.0).toInt()
        val s = (secPerMile - m * 60).roundToInt()
        return "${m}:${if (s < 10) "0$s" else "$s"} min/mi"
    }

    // Time parser: supports hh:mm, hours, minutes, "in 15 minutes", decimal hours
    fun parseTimeToSeconds(s: String): Double? {
        val lower = s.lowercase(Locale.getDefault())

        val hhmm = Regex("""\b(\d{1,2}):(\d{1,2})(?::(\d{1,2}))?\b""").find(lower)
        if (hhmm != null) {
            val h = hhmm.groupValues[1].toIntOrNull() ?: 0
            val m = hhmm.groupValues[2].toIntOrNull() ?: 0
            val sec = hhmm.groupValues.getOrNull(3)?.toIntOrNull() ?: 0
            return (h * 3600 + m * 60 + sec).toDouble()
        }

        val hoursMatch = Regex("""(\d+(?:[.,]\d+)?)\s*(hour|hours|hr|h)\b""", RegexOption.IGNORE_CASE).find(lower)
        val minsMatch = Regex("""(\d+(?:[.,]\d+)?)\s*(minute|minutes|min|m)\b""", RegexOption.IGNORE_CASE).find(lower)
        if (hoursMatch != null || minsMatch != null) {
            val hours = hoursMatch?.groupValues?.get(1)?.replace(',', '.')?.toDoubleOrNull() ?: 0.0
            val mins = minsMatch?.groupValues?.get(1)?.replace(',', '.')?.toDoubleOrNull() ?: 0.0
            return hours * 3600.0 + mins * 60.0
        }

        val decHour = Regex("""(\d+(?:[.,]\d+)?)\s*h\b""", RegexOption.IGNORE_CASE).find(lower)
        if (decHour != null) {
            val h = decHour.groupValues[1].replace(',', '.').toDoubleOrNull() ?: return null
            return h * 3600.0
        }

        val onlyMin = Regex("""\b(\d+(?:[.,]\d+)?)\s*(min|minute|minutes)\b""", RegexOption.IGNORE_CASE).find(lower)
        if (onlyMin != null) {
            val m = onlyMin.groupValues[1].replace(',', '.').toDoubleOrNull() ?: return null
            return m * 60.0
        }

        val za = Regex("""(?:in|for)\s*(\d+(?:[.,]\d+)?)\b""", RegexOption.IGNORE_CASE).find(lower)
        if (za != null) {
            val v = za.groupValues[1].replace(',', '.').toDoubleOrNull() ?: return null
            return v * 60.0 // treat as minutes
        }

        val bare = Regex("""\b(\d+(?:[.,]\d+)?)\b""").find(lower)?.groupValues?.get(1)?.replace(',', '.')
        return bare?.toDoubleOrNull()?.let { it * 60.0 }
    }

    // Extract number for keys (range=200 etc.)
    fun extractNumberIfPresent(lower: String, keys: List<String>): Double? {
        for (k in keys) {
            val re1 = Regex("""\b${Regex.escape(k)}\s*=\s*(-?\d+(?:[.,]\d+)?)\b""", RegexOption.IGNORE_CASE).find(lower)
            if (re1 != null) return re1.groupValues[1].replace(',', '.').toDoubleOrNull()
            val re2 = Regex("""\b${Regex.escape(k)}\s+(-?\d+(?:[.,]\d+)?)\b""", RegexOption.IGNORE_CASE).find(lower)
            if (re2 != null) return re2.groupValues[1].replace(',', '.').toDoubleOrNull()
        }
        return null
    }

    fun extractAngleDegrees(lower: String): Double? {
        val degSym = Regex("""(-?\d+(?:[.,]\d+)?)\s*°""").find(lower)
        if (degSym != null) return degSym.groupValues[1].replace(',', '.').toDoubleOrNull()
        val angleExplicit = Regex("""(?:angle|ang|угол)\s*=?\s*(-?\d+(?:[.,]\d+)?)""", RegexOption.IGNORE_CASE).find(lower)
        if (angleExplicit != null) return angleExplicit.groupValues[1].replace(',', '.').toDoubleOrNull()
        return null
    }

    // Extract number with unit (supports many units incl. ft, mi, mph)
    fun extractNumberWithUnit(lower: String, units: List<String>): NumUnit? {
        for (u in units) {
            val re = Regex("""(-?\d+(?:[.,]\d+)?)\s*${Regex.escape(u)}\b""", RegexOption.IGNORE_CASE).find(lower)
            if (re != null) return NumUnit(re.groupValues[1].replace(',', '.').toDoubleOrNull() ?: return null, u)
        }
        // generic patterns like "m=80" or "v=30mph"
        val generic = Regex("""([a-z]{1,3})\s*=\s*(-?\d+(?:[.,]\d+)?)([a-z/%°]*)""", RegexOption.IGNORE_CASE).find(lower)
        if (generic != null) {
            val unit = generic.groupValues[3].ifBlank { null }
            return NumUnit(generic.groupValues[2].replace(',', '.').toDoubleOrNull() ?: return null, unit)
        }
        // support ft2 / sqft detection
        val areaFt = Regex("""(-?\d+(?:[.,]\d+)?)\s*(ft2|sqft|ft²)""", RegexOption.IGNORE_CASE).find(lower)
        if (areaFt != null) {
            val v = areaFt.groupValues[1].replace(',', '.').toDoubleOrNull() ?: return null
            // convert sq ft to m2
            return NumUnit(v * 0.09290304, "m2")
        }
        return null
    }

    fun extractBareNumber(lower: String, label: String): Double? {
        if (label.isNotBlank()) {
            val re = Regex("""\b${Regex.escape(label)}\s*[:=]?\s*(-?\d+(?:[.,]\d+)?)\b""", RegexOption.IGNORE_CASE).find(lower)
            if (re != null) return re.groupValues[1].replace(',', '.').toDoubleOrNull()
        }
        val any = Regex("""(-?\d+(?:[.,]\d+)?)""").findAll(lower).map { it.groupValues[1].replace(',', '.') }.toList()
        if (any.isNotEmpty()) {
            return any[0].toDoubleOrNull()
        }
        return null
    }

    // normalize mass to kg (kg, g, lb)
    fun normalizeMassToKg(value: Double, unit: String?): Double {
        if (unit == null) return value // assume kg
        val u = unit.lowercase(Locale.getDefault())
        return when {
            u.contains("kg") -> value
            u == "g" -> value / 1000.0
            u.contains("lb") || u.contains("lbs") -> value * 0.45359237
            else -> value
        }
    }

    // normalize speed to m/s (supports mph, km/h, m/s)
    fun normalizeSpeedToMPerS(value: Double, unit: String?): Double {
        if (unit == null) return value // assume m/s
        val u = unit.lowercase(Locale.getDefault())
        return when {
            u.contains("mi") || u.contains("mph") -> value * 0.44704
            u.contains("km") -> value / 3.6
            u.contains("m/s") || u.contains("mps") -> value
            else -> value
        }
    }
}
