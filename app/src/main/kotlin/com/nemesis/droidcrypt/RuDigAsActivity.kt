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

class RuDigAsActivity : AppCompatActivity() {

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

        setContentView(R.layout.activity_ru_dig_as)

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

        addSystemLine("Шаблон: в модулях есть цифровые команды. Введите 'help' для краткой справки (внизу).")
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
            val mainRes = RuDigAsCommandsMain.handleCommand(cmd)
            if (mainRes.isNotEmpty()) return mainRes
        } catch (_: Exception) { }

        try {
            val v2Res = RuDigAsCommandsV2.handleCommand(cmd)
            if (v2Res.isNotEmpty()) return v2Res
        } catch (_: Exception) { }

        try {
            val v3Res = RuDigAsCommandsV3.handleCommand(cmd)
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
// RuDigAsCommandsMain: цифровые полезные вычисления (2 команды)
// 1) Storage: сколько места займёт N файлов среднего размера (включая опции копий/дисков).
// 2) Download time: время загрузки/скачивания при заданной скорости.
// --------------------
private object RuDigAsCommandsMain {

    fun handleCommand(cmdRaw: String): List<String> {
        val cmd = cmdRaw.trim()
        val lower = cmd.lowercase(Locale.getDefault())

        // Storage command triggers
        if (lower.contains("сколько места") ||
            lower.contains("сколько займ") ||
            lower.contains("сколько займёт") ||
            lower.contains("хранилище") ||
            lower.contains("файлов") && (lower.contains("средн") || Regex("""\d+\s*(gb|гб|mb|мб|kb|кб|b|байт)""").containsMatchIn(lower))
        ) {
            return handleStorageEstimate(cmd)
        }

        // Download time triggers
        if (lower.contains("скачать") || lower.contains("за сколько скачать") || lower.contains("время скач") || lower.contains("download") || Regex("""\d+\s*(gb|гб|mb|мб|kb|кб|b|байт)""").containsMatchIn(lower) && Regex("""\d+\s*(mbit/s|mbps|мбит/с|мб/с|kbps|кбит/с|гбит/с|gbps|mb/s|мбит)""").containsMatchIn(lower)) {
            return handleDownloadTime(cmd)
        }

        return emptyList()
    }

    // --------------------
    // Storage estimation: parse count and avg size. Supports optional "копий N", "copies N", "на N дисках".
    // Examples:
    //  - "сколько займёт 2500 файлов по 3.5 MB"
    //  - "2500 files avg 3.5MB copies 2 на 4 дисках"
    // --------------------
    private fun handleStorageEstimate(cmdRaw: String): List<String> {
        val lower = cmdRaw.lowercase(Locale.getDefault())

        // find count
        val countRe = Regex("""(\d+(?:[.,]\d+)?)\s*(шт|штук|файлов|файл|files|items)\b""", RegexOption.IGNORE_CASE)
        val countMatch = countRe.find(lower)
        val count = countMatch?.groupValues?.get(1)?.replace(',', '.')?.toDoubleOrNull()

        // alternative pattern: "N x files" or "N files"
        val countAlt = if (count == null) {
            Regex("""\b(\d{1,9})\b(?=\s*(файлов|файл|files|шт|items))""").find(lower)?.groupValues?.get(1)?.toDoubleOrNull()
        } else null

        val finalCount = count ?: countAlt

        // avg size: look for first occurrence of bytes unit
        val sizeRe = Regex("""(-?\d+(?:[.,]\d+)?)\s*(tb|тб|gb|гб|mb|мб|kb|кб|b|байт)\b""", RegexOption.IGNORE_CASE)
        val sizeMatch = sizeRe.find(lower)
        val avgBytes = sizeMatch?.let {
            val num = it.groupValues[1].replace(',', '.').toDoubleOrNull() ?: 0.0
            val unit = it.groupValues[2].lowercase(Locale.getDefault())
            PhysUtils.parseBytes(num, unit)
        }

        if (finalCount == null || avgBytes == null) {
            return listOf("Storage: не нашёл количество файлов или средний размер. Пример: 'сколько займёт 2500 файлов по 3.5 MB' или '2500 files avg 3.5MB'")
        }

        // copies / replicas
        val copies = Regex("""(копий|copies|replicas?)\s*[:=]?\s*(\d+)""", RegexOption.IGNORE_CASE).find(lower)?.groupValues?.get(2)?.toIntOrNull() ?: 1

        // disks count
        val disks = Regex("""(дисках|дисков|disks|на\s+(\d+)\s*дисках)""", RegexOption.IGNORE_CASE).find(lower)
        val disksNum = Regex("""\bна\s*(\d+)\s*(диск|дисках|дисков|disks)\b""", RegexOption.IGNORE_CASE).find(lower)?.groupValues?.get(1)?.toIntOrNull()

        val totalBytes = finalCount * avgBytes * copies
        val perDisk = if (disksNum != null && disksNum > 0) totalBytes / disksNum else null

        val lines = mutableListOf<String>()
        lines.add("Файлов: ${"%.0f".format(finalCount)} (копий: $copies)")
        lines.add("Средний размер файла: ${PhysUtils.formatBytesDecimalDouble(avgBytes)}")
        lines.add("Итого (с учётом копий): ${PhysUtils.formatBytesDecimal(totalBytes)} (${totalBytes.toLong()} bytes)")

        if (perDisk != null) {
            lines.add("Распределение на $disksNum дисках: ≈ ${PhysUtils.formatBytesDecimal(perDisk)} на диск")
        }

        // helpful extras: how many files per common storage sizes
        val common = listOf(1_000_000_000.0 /*1GB*/, 10_000_000_000.0 /*10GB*/, 100_000_000_000.0 /*100GB*/, 1_000_000_000_000.0 /*1TB*/)
        val fits = common.map { cap ->
            val n = floor(cap / avgBytes).toLong()
            "${PhysUtils.formatBytesDecimal(cap)} → ≈ $n файлов"
        }
        lines.add("Примеры вместимости:")
        fits.forEach { lines.add(" - $it") }

        lines.add("Замечание: расчёт простой — не учитывает файловую систему, метаданные, slack space или сжатие.")
        return lines
    }

    // --------------------
    // Download time: parse file size and network speed and compute time estimates.
    // Examples:
    //  - "скачать 5 GB при 20 Mbps"
    //  - "download 500MB at 10 MB/s"
    // --------------------
    private fun handleDownloadTime(cmdRaw: String): List<String> {
        val lower = cmdRaw.lowercase(Locale.getDefault())

        // parse size (first match)
        val sizeRe = Regex("""(-?\d+(?:[.,]\d+)?)\s*(tb|тб|gb|гб|mb|мб|kb|кб|b|байт)\b""", RegexOption.IGNORE_CASE)
        val sizeMatch = sizeRe.find(lower)
        val bytes = sizeMatch?.let {
            val num = it.groupValues[1].replace(',', '.').toDoubleOrNull() ?: return@let null
            val unit = it.groupValues[2].lowercase(Locale.getDefault())
            PhysUtils.parseBytes(num, unit)
        }

        // parse speed: support Mbps, Kbps, MB/s, KB/s, Gbps, мбит/с, мб/с
        val speedRe = Regex("""(-?\d+(?:[.,]\d+)?)\s*(gbit/s|gbps|гбит/с|mbit/s|mbps|мбит/с|mb/s|мб/с|kbps|kbit/s|кбит/с|kb/s|кб/с)\b""", RegexOption.IGNORE_CASE)
        val speedMatch = speedRe.find(lower)
        val bitsPerSec = speedMatch?.let {
            val num = it.groupValues[1].replace(',', '.').toDoubleOrNull() ?: return@let null
            val unit = it.groupValues[2].lowercase(Locale.getDefault())
            when {
                unit.contains("gbit") || unit.contains("gbps") || unit.contains("гбит") -> num * 1_000_000_000.0
                unit.contains("mbit") || unit.contains("mbps") || unit.contains("мбит") -> num * 1_000_000.0
                unit.contains("kbit") || unit.contains("kbps") || unit.contains("кбит") -> num * 1_000.0
                unit.contains("/s") && (unit.contains("mb/") || unit.contains("мб/")) -> num * 8.0 * 1_000_000.0 // MB/s -> bits/s
                unit.contains("/s") && (unit.contains("kb/") || unit.contains("кб/")) -> num * 8.0 * 1_000.0
                else -> num // fallback
            }
        }

        if (bytes == null) {
            return listOf("Download: не нашёл размер файла. Пример: 'скачать 5 GB при 20 Mbps' или 'download 500MB at 10 MB/s'")
        }

        val lines = mutableListOf<String>()
        lines.add("Размер: ${PhysUtils.formatBytesDecimalDouble(bytes)} (${bytes.toLong()} bytes)")

        if (bitsPerSec == null) {
            // if no speed provided, show typical times for common links
            val sampleSpeeds = mapOf(
                "10 Mbps" to 10.0 * 1_000_000.0,
                "50 Mbps" to 50.0 * 1_000_000.0,
                "100 Mbps" to 100.0 * 1_000_000.0,
                "1 Gbps" to 1.0 * 1_000_000_000.0
            )
            lines.add("Скорость не указана — примерные времена при типичных скоростях:")
            for ((name, bps) in sampleSpeeds) {
                val sec = (bytes * 8.0) / bps
                lines.add(" - $name: ${PhysUtils.formatSecondsNice(sec)} (~${PhysUtils.prettyDurationHMS(sec)})")
            }
            lines.add("Укажите скорость: 'при 20 Mbps' или 'at 10 MB/s'")
            return lines
        } else {
            val seconds = (bytes * 8.0) / bitsPerSec
            lines.add("Скорость канала: ${PhysUtils.formatBitsPerSecond(bitsPerSec)}")
            lines.add("Ожидаемое время: ${PhysUtils.formatSecondsNice(seconds)}")
            lines.add("Разбивка: ${PhysUtils.prettyDurationHMS(seconds)}")
            // show effective throughput in bytes/s
            val bytesPerSec = bitsPerSec / 8.0
            lines.add("Эффективная скорость: ${PhysUtils.formatBytesDecimalDouble(bytesPerSec)}/s")
            return lines
        }
    }
}

// --------------------
// RuDigAsCommandsV2: ещё 2 полезные цифровые команды
// 3) Image memory estimate: сколько оперативки займёт bitmap при разрешении и глубине цвета.
// 4) CSV / table size estimator: по числу строк, столбцов и средней длине ячейки.
// --------------------
private object RuDigAsCommandsV2 {

    fun handleCommand(cmdRaw: String): List<String> {
        val cmd = cmdRaw.trim()
        val lower = cmd.lowercase(Locale.getDefault())

        // Image memory
        if (lower.contains("изображение") || lower.contains("bitmap") || lower.contains("память изображения") || lower.contains("image memory") || Regex("""\d+\s*[xх×]\s*\d+""").containsMatchIn(lower)) {
            return handleImageMemory(cmd)
        }

        // CSV / table estimator
        if (lower.contains("csv") || lower.contains("таблица") || lower.contains("строк") || lower.contains("rows") && lower.contains("cols") || lower.contains("столбцов")) {
            return handleCsvEstimate(cmd)
        }

        return emptyList()
    }

    // --------------------
    // Image memory: parse WxH and bpp or format (RGB, RGBA, gray)
    // Examples:
    //  - "image 1920x1080 24bit"
    //  - "изображение 3840x2160 rgba 8bit"
    //  - "сколько памяти для 1024 x 1024 с 16 бит?"
    // Optional: "в 8 GB" to show how many fit into RAM.
    // --------------------
    private fun handleImageMemory(cmdRaw: String): List<String> {
        val lower = cmdRaw.lowercase(Locale.getDefault())

        val whRe = Regex("""\b(\d{2,5})\s*[xх×]\s*(\d{2,5})\b""")
        val whMatch = whRe.find(lower)
        if (whMatch == null) {
            return listOf("Image: не найдено разрешение. Пример: '1920x1080 24bit' или 'изображение 1024 x 768 rgba'")
        }
        val w = whMatch.groupValues[1].toIntOrNull() ?: return listOf("Image: неверное ширина")
        val h = whMatch.groupValues[2].toIntOrNull() ?: return listOf("Image: неверное высота")

        // bits per pixel detection
        var bpp: Int? = null
        val bppRe = Regex("""(\d{1,3})\s*-\s*?bit|(\d{1,3})\s*bit|(\d{1,3})\s*бит""", RegexOption.IGNORE_CASE)
        val bppMatch = bppRe.find(lower)
        if (bppMatch != null) {
            val g = bppMatch.groupValues.firstOrNull { it.isNotBlank() }
            bpp = g?.replace("bit", "")?.replace("бит", "")?.trim()?.toIntOrNull()
        }
        // detect common formats if bpp missing
        if (bpp == null) {
            when {
                lower.contains("rgba") -> bpp = 32
                lower.contains("rgb") -> bpp = 24
                lower.contains("grayscale") || lower.contains("gray") || lower.contains("grey") -> bpp = 8
                lower.contains("pal") -> bpp = 8
            }
        }
        if (bpp == null) bpp = 24 // default

        val bytes = w.toLong() * h.toLong() * (bpp.toLong() / 8L).toDouble()
        val lines = mutableListOf<String>()
        lines.add("Разрешение: ${w}×${h}")
        lines.add("Глубина цвета: ${bpp} бит/пикс")
        lines.add("Не сжатый объём: ${PhysUtils.formatBytesDecimal(bytes)} (${bytes.toLong()} bytes)")

        // if user specified a RAM size, compute how many such images fit
        val ramRe = Regex("""в\s*(-?\d+(?:[.,]\d+)?)\s*(tb|тб|gb|гб|mb|мб|kb|кб|b|байт)\b""", RegexOption.IGNORE_CASE)
        val ramMatch = ramRe.find(lower)
        if (ramMatch != null) {
            val num = ramMatch.groupValues[1].replace(',', '.').toDoubleOrNull()
            val unit = ramMatch.groupValues[2].lowercase(Locale.getDefault())
            if (num != null) {
                val ramBytes = PhysUtils.parseBytes(num, unit)
                val fit = floor(ramBytes / bytes).toLong()
                lines.add("В указанной памяти (${PhysUtils.formatBytesDecimal(ramBytes)}) помещается ≈ $fit таких изображений")
            }
        }

        lines.add("Замечание: расчёт для несжатого битмапа. Сжатие и форматы (JPEG/WEBP/HEIF) снижают объём.")
        return lines
    }

    // --------------------
    // CSV / table estimator
    // Examples:
    //  - "csv 100000 rows 12 cols avg 20"
    //  - "таблица 1_000_000 строк 10 столбцов средний 50"
    // --------------------
    private fun handleCsvEstimate(cmdRaw: String): List<String> {
        val lower = cmdRaw.lowercase(Locale.getDefault())

        val rows = Regex("""(\d{1,3}(?:[_\d]{0,})?)\s*(строк|rows|row)""", RegexOption.IGNORE_CASE).find(lower)?.groupValues?.get(1)?.replace("_","")?.toLongOrNull()
            ?: Regex("""rows?\s*(\d{1,9})""", RegexOption.IGNORE_CASE).find(lower)?.groupValues?.get(1)?.toLongOrNull()

        val cols = Regex("""(\d{1,3})\s*(столбц|столбцов|cols|columns|col)""", RegexOption.IGNORE_CASE).find(lower)?.groupValues?.get(1)?.toIntOrNull()
            ?: Regex("""cols?\s*(\d{1,3})""", RegexOption.IGNORE_CASE).find(lower)?.groupValues?.get(1)?.toIntOrNull()

        val avgChars = Regex("""avg\s*(\d+(?:[.,]\d+)?)\b""", RegexOption.IGNORE_CASE).find(lower)?.groupValues?.get(1)?.replace(',', '.')?.toDoubleOrNull()
            ?: Regex("""средн(?:ая|ий|ое)?\s*(\d+(?:[.,]\d+)?)\s*(симв|chars|символ)""", RegexOption.IGNORE_CASE).find(lower)?.groupValues?.get(1)?.replace(',', '.')?.toDoubleOrNull()
            ?: Regex("""avgchars\s*(\d+(?:[.,]\d+)?)\b""", RegexOption.IGNORE_CASE).find(lower)?.groupValues?.get(1)?.replace(',', '.')?.toDoubleOrNull()

        if (rows == null || cols == null || avgChars == null) {
            return listOf("CSV: укажите количество строк, столбцов и среднюю длину поля. Пример: 'csv 100000 rows 12 cols avg 20'")
        }

        // average size per field include separator (assume 1 char) and quotes maybe
        val bytesPerField = avgChars + 1.0 // separator
        val bytesPerRow = cols * bytesPerField + 1.0 // newline
        val totalBytes = rows.toDouble() * bytesPerRow

        val lines = mutableListOf<String>()
        lines.add("Оценка CSV / таблицы:")
        lines.add("Строк: $rows, Столбцов: $cols, Средняя длина поля: ${"%.1f".format(avgChars)} символов")
        lines.add("Примерный размер строки: ${"%.1f".format(bytesPerRow)} байт")
        lines.add("Оценочный общий размер: ${PhysUtils.formatBytesDecimal(totalBytes)} (${totalBytes.toLong()} bytes)")
        lines.add("Примечание: реальный размер зависит от разделителя, кавычек и кодировки (UTF-8/UTF-16).")
        return lines
    }
}

// --------------------
// RuDigAsCommandsV3: дополнительные цифровые утилиты (2 команды)
// 5) Traffic command (поддерживает дни) — конвертация объёма в скорости/потоки (взято и расширено из примера).
// 6) Streams/bandwidth planner — сколько Mbps нужно для N потоков (1080p/720p/4k) и суммарные требования.
// --------------------
private object RuDigAsCommandsV3 {

    fun handleCommand(cmdRaw: String): List<String> {
        val cmd = cmdRaw.trim()
        val lower = cmd.lowercase(Locale.getDefault())

        // Traffic command (detect word "трафик" or a bytes-like expression)
        if (lower.contains("трафик") || Regex("""\d+\s*(gb|гб|mb|мб|kb|кб|b|байт)""").containsMatchIn(lower)) {
            return handleTraffic(cmd)
        }

        // Streams / bandwidth planner
        val streamRoots = listOf("потоков", "поток", "стрим", "streams", "stream", "требуется канал", "сколько мбпс")
        if (streamRoots.any { lower.contains(it) } || Regex("""\b(\d+)\s*(потоков|streams)\b""").containsMatchIn(lower) && (lower.contains("720p") || lower.contains("1080p") || lower.contains("4k") || lower.contains("4k") || lower.contains("kbps") || lower.contains("mbps") || lower.contains("мбит"))) {
            return handleStreamsPlanner(cmd)
        }

        // help
        if (lower.contains("справк") || lower == "help" || lower.contains("помощ")) {
            return listOf(
                "Справка (RuDigAsCommandsV3 — цифровые утилиты):",
                "1) Трафик: 'трафик 50 GB' или 'трафик 300GB за 30 дней' — выведет B/день/час/мин/сек и биты/с.",
                "2) Планирование потоков: '10 потоков 1080p' или '3 streams 4k' — оценит требуемую пропускную способность.",
                "Примеры:",
                " - 'сколько места 2500 файлов по 3.5 MB' (RuDigAsCommandsMain)",
                " - 'скачать 5 GB при 20 Mbps' (RuDigAsCommandsMain)",
                " - 'image 1920x1080 32bit in 8 GB' (RuDigAsCommandsV2)"
            )
        }

        return emptyList()
    }

    // --------------------
    // Traffic command implementation (supports days parameter)
    // --------------------
    private fun handleTraffic(cmdRaw: String): List<String> {
        val lower = cmdRaw.lowercase(Locale.getDefault())
        val m = Regex("""(\d+(?:[.,]\d+)?)\s*(tb|тб|gb|гб|mb|мб|kb|кб|b|байт)?""", RegexOption.IGNORE_CASE).find(lower)
        if (m == null) return listOf("Использование трафика: укажите объём, например 'трафик 50 GB' или 'трафик 300GB за 30 дней'")
        val numStr = m.groupValues[1].replace(',', '.')
        val unit = m.groupValues.getOrNull(2) ?: ""
        val bytes = PhysUtils.parseBytes(numStr.toDouble(), unit)

        val days = PhysUtils.parseDaysFromText(cmdRaw)
        if (days != null) {
            val bPerDay = bytes / days.toDouble()
            val bPerHour = bPerDay / 24.0
            val bPerMin = bPerHour / 60.0
            val bPerSec = bPerMin / 60.0
            return listOf(
                "Ввод: ${m.value.trim()} → ${PhysUtils.formatBytesDecimal(bytes)}",
                "Период: $days дней",
                "В день: ${PhysUtils.formatBytesDecimalDouble(bPerDay)}",
                "В час: ${PhysUtils.formatBytesDecimalDouble(bPerHour)}",
                "В мин: ${PhysUtils.formatBytesDecimalDouble(bPerMin)}",
                "В сек (бит/с): ${PhysUtils.formatBitsPerSecond(bPerSec)}"
            )
        } else {
            val rates = PhysUtils.bytesPerMonthToRates(bytes)
            return listOf(
                "Ввод: ${m.value.trim()} → ${PhysUtils.formatBytesDecimal(bytes)}",
                "В день: ${PhysUtils.formatBytesDecimalDouble(rates["B/day"] ?: 0.0)}",
                "В час: ${PhysUtils.formatBytesDecimalDouble(rates["B/hour"] ?: 0.0)}",
                "В мин: ${PhysUtils.formatBytesDecimalDouble(rates["B/min"] ?: 0.0)}",
                "В сек (бит/с): ${PhysUtils.formatBitsPerSecond(rates["B/s"] ?: 0.0)}"
            )
        }
    }

    // --------------------
    // Streams / bandwidth planner
    // Supports quality tokens: 720p, 1080p, 4k, or explicit bitrate e.g. "5 Mbps".
    // Examples:
    //  - "10 потоков 1080p"
    //  - "3 streams 4k"
    //  - "5 потоков по 3.5 Mbps"
    // --------------------
    private fun handleStreamsPlanner(cmdRaw: String): List<String> {
        val lower = cmdRaw.lowercase(Locale.getDefault())

        val count = Regex("""\b(\d+)\s*(потоков|поток|streams|stream)\b""", RegexOption.IGNORE_CASE).find(lower)?.groupValues?.get(1)?.toIntOrNull()
            ?: Regex("""^(\d+)\b""", RegexOption.IGNORE_CASE).find(lower)?.groupValues?.get(1)?.toIntOrNull()

        val quality = when {
            lower.contains("4k") || lower.contains("2160p") -> "4k"
            lower.contains("1080p") || lower.contains("fullhd") -> "1080p"
            lower.contains("720p") -> "720p"
            lower.contains("480p") -> "480p"
            else -> null
        }

        // explicit per-stream bitrate
        val brRe = Regex("""(-?\d+(?:[.,]\d+)?)\s*(mbit/s|mbps|мбит/с|мб/с|kbps|кбит/с|kbit/s|kb/s)\b""", RegexOption.IGNORE_CASE)
        val brMatch = brRe.find(lower)
        val perStreamBps = brMatch?.let {
            val num = it.groupValues[1].replace(',', '.').toDoubleOrNull() ?: 0.0
            val unit = it.groupValues[2].lowercase(Locale.getDefault())
            when {
                unit.contains("gbit") || unit.contains("gbps") || unit.contains("гбит") -> num * 1_000_000_000.0
                unit.contains("mbit") || unit.contains("mbps") || unit.contains("мбит") -> num * 1_000_000.0
                unit.contains("kbit") || unit.contains("kbps") || unit.contains("кбит") -> num * 1_000.0
                unit.contains("/s") && (unit.contains("mb/") || unit.contains("мб/")) -> num * 8.0 * 1_000_000.0
                else -> num
            }
        }

        val typicalMap = mapOf(
            "480p" to 1_000_000.0,   // 1 Mbps
            "720p" to 3_000_000.0,   // 3 Mbps
            "1080p" to 5_000_000.0,  // 5 Mbps
            "4k" to 25_000_000.0     // 25 Mbps (approx)
        )

        val lines = mutableListOf<String>()

        if (count == null) {
            return listOf("Streams: не указан количество потоков. Пример: '10 потоков 1080p' или '3 streams 4k'")
        }

        val perBps = perStreamBps ?: (quality?.let { typicalMap[it] } ?: 5_000_000.0)
        val totalBps = perBps * count
        lines.add("Потоков: $count")
        lines.add("Оценка на поток: ${PhysUtils.formatBitsPerSecond(perBps)}")
        lines.add("Итого нужно: ${PhysUtils.formatBitsPerSecond(totalBps)}")
        lines.add("Рекомендуется добавить запас 20%: ${PhysUtils.formatBitsPerSecond(totalBps * 1.2)}")
        // monthly transfer if stream runs 24/7
        val monthBytes = totalBps / 8.0 * 3600.0 * 24.0 * 30.0
        lines.add("Если стримы идут 24/7 — ≈ ${PhysUtils.formatBytesDecimal(monthBytes)} в месяц")
        return lines
    }
}

// --------------------
// Общие утилиты для парсинга/форматирования (переиспользуются во всех модулях)
// Добавлены функции для работы с байтами, форматированием и сетевыми единицами
// --------------------
private object DigitUtils {

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

    // --------------------
    // New helpers for bytes/traffic formatting and parsing
    // --------------------

    // parseBytes: parse numeric value and unit string into bytes (Double)
    // units: tb/tб -> 1e12, gb/gб -> 1e9, mb -> 1e6, kb -> 1e3, b/байт -> 1
    fun parseBytes(value: Double, unitRaw: String?): Double {
        if (unitRaw == null || unitRaw.isBlank()) return value
        val unit = unitRaw.lowercase(Locale.getDefault())
        return when {
            unit.contains("tb") || unit.contains("тб") -> value * 1_000_000_000_000.0
            unit.contains("gb") || unit.contains("гб") -> value * 1_000_000_000.0
            unit.contains("mb") || unit.contains("мб") -> value * 1_000_000.0
            unit.contains("kb") || unit.contains("кб") -> value * 1_000.0
            unit.contains("b") || unit.contains("байт") -> value
            else -> value
        }
    }

    // formatBytesDecimal: human readable (decimal SI)
    fun formatBytesDecimal(bytes: Double): String {
        if (!bytes.isFinite()) return "0 B"
        val abs = abs(bytes)
        return when {
            abs >= 1_000_000_000_000.0 -> "${"%.2f".format(bytes / 1_000_000_000_000.0)} TB"
            abs >= 1_000_000_000.0 -> "${"%.2f".format(bytes / 1_000_000_000.0)} GB"
            abs >= 1_000_000.0 -> "${"%.2f".format(bytes / 1_000_000.0)} MB"
            abs >= 1_000.0 -> "${"%.2f".format(bytes / 1_000.0)} KB"
            else -> "${bytes.toLong()} B"
        }
    }

    // formatBytesDecimalDouble: slightly different presentation, keep decimals for smaller values
    fun formatBytesDecimalDouble(bytes: Double): String {
        if (!bytes.isFinite()) return "0 B"
        val abs = abs(bytes)
        return when {
            abs >= 1_000_000_000_000.0 -> "${"%.3f".format(bytes / 1_000_000_000_000.0)} TB"
            abs >= 1_000_000_000.0 -> "${"%.3f".format(bytes / 1_000_000_000.0)} GB"
            abs >= 1_000_000.0 -> "${"%.3f".format(bytes / 1_000_000.0)} MB"
            abs >= 1_000.0 -> "${"%.2f".format(bytes / 1_000.0)} KB"
            else -> "${"%.0f".format(bytes)} B"
        }
    }

    // formatBitsPerSecond: present bits/s and bytes/s
    fun formatBitsPerSecond(bps: Double): String {
        if (!bps.isFinite()) return "0 b/s"
        val abs = abs(bps)
        val bitsPart = when {
            abs >= 1_000_000_000.0 -> "${"%.2f".format(bps / 1_000_000_000.0)} Gbit/s"
            abs >= 1_000_000.0 -> "${"%.2f".format(bps / 1_000_000.0)} Mbit/s"
            abs >= 1_000.0 -> "${"%.2f".format(bps / 1_000.0)} Kbit/s"
            else -> "${"%.0f".format(bps)} bit/s"
        }
        val bytesPerSec = bps / 8.0
        val bytesPart = when {
            bytesPerSec >= 1_000_000_000.0 -> "${"%.2f".format(bytesPerSec / 1_000_000_000.0)} GB/s"
            bytesPerSec >= 1_000_000.0 -> "${"%.2f".format(bytesPerSec / 1_000_000.0)} MB/s"
            bytesPerSec >= 1_000.0 -> "${"%.2f".format(bytesPerSec / 1_000.0)} KB/s"
            else -> "${"%.2f".format(bytesPerSec)} B/s"
        }
        return "$bitsPart (~$bytesPart)"
    }

    // parseDaysFromText: find "за N дней" or "N дней"
    fun parseDaysFromText(s: String): Int? {
        val lower = s.lowercase(Locale.getDefault())
        val re1 = Regex("""за\s+(\d+)\s*дн""", RegexOption.IGNORE_CASE).find(lower)
        if (re1 != null) return re1.groupValues[1].toIntOrNull()
        val re2 = Regex("""(\d+)\s*дн(?:ей|я)?\b""", RegexOption.IGNORE_CASE).find(lower)
        if (re2 != null) return re2.groupValues[1].toIntOrNull()
        return null
    }

    // bytesPerMonthToRates: assume 'bytes' per month, derive per-day/hour/min/sec and bits/sec
    fun bytesPerMonthToRates(bytes: Double): Map<String, Double> {
        val days = 30.0
        val bPerDay = bytes / days
        val bPerHour = bPerDay / 24.0
        val bPerMin = bPerHour / 60.0
        val bPerSec = bPerMin / 60.0
        val map = mutableMapOf<String, Double>()
        map["B/month"] = bytes
        map["B/day"] = bPerDay
        map["B/hour"] = bPerHour
        map["B/min"] = bPerMin
        map["B/s"] = bPerSec
        map["bits/s"] = bPerSec * 8.0
        return map
    }

    // prettyDurationHMS: human-friendly H:M:S with two digits
    fun prettyDurationHMS(secondsRaw: Double): String {
        if (!secondsRaw.isFinite()) return "0s"
        var s = secondsRaw.roundToLong()
        val h = s / 3600
        s %= 3600
        val m = s / 60
        val sec = s % 60
        return String.format("%02d:%02d:%02d", h, m, sec)
    }
}
