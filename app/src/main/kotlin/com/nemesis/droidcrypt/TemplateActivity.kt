package com.nemesis.droidcrypt

import android.content.Intent
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.text.Editable
import android.text.InputType
import android.text.method.ScrollingMovementMethod
import android.util.Log
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
import kotlin.math.ln
import kotlin.math.pow
import java.math.BigDecimal
import java.math.MathContext
import java.math.RoundingMode

/**
 * TemplateActivity — копия UI/структуры VprActivity, но без реальных команд.
 * Служит как шаблон для новых activity с другими наборами команд.
 *
 * Правила работы парсера в шаблоне:
 *  - Поддерживается только help/справка (возвращает простую заглушку).
 *  - Если help не сработал, пытаем основную логику модулей по приоритету:
 *      1) CommandsMain (основной)
 *      2) CommandsV2 (низкий приоритет)
 *      3) CommandsV3 (ещё ниже)
 *  - Каждый объект/модуль — полностью автономен (в нём свои helpers).
 *  - При расширении — добавляйте новые функции только в нужный модуль.
 */
class TemplateActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "TemplateActivity"
    }

    // UI references
    private lateinit var messagesContainer: LinearLayout
    private lateinit var scrollView: ScrollView
    private lateinit var input: EditText

    // Styling constants (копия из вашего VprActivity)
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

        // NOTE: реальный layout я оставил тот же — activity_vpr (вы можете сделать отдельный layout)
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
                    addAssistantLine("Введите команду (help — справка).")
                }
                true
            } else {
                false
            }
        }

        // seed welcome
        addSystemLine("Шаблон: пока команд нет. Введите 'help' для заглушки.")
    }

    // --------------------
    // Command routing (только help + делегирование в модули)
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
                    addAssistantLine("= Ошибка при обработке команды: ${t.message ?: t::class.java.simpleName}")
                    scrollView.post { scrollView.fullScroll(ScrollView.FOCUS_DOWN) }
                }
                Log.e(TAG, "Error while processing command", t)
            }
        }
    }

    /**
     * В этом шаблоне: help возвращает заглушку. Иначе пробуем модули по приоритету:
     * CommandsMain -> CommandsV2 -> CommandsV3
     */
    private suspend fun parseCommand(commandRaw: String): List<String> {
        val cmd = commandRaw.trim()
        val lower = cmd.lowercase(Locale.getDefault())

        // help / справка — возврат простой заглушки
        if (lower.contains("справк") || lower == "help" || lower.contains("помощ")) {
            return listOf(
                "Справка (заглушка):",
                "Здесь пока нет реализованных команд.",
                "Когда будете готовы — добавьте команды в соответствующий модуль: CommandsMain / CommandsV2 / CommandsV3."
            )
        }

        // Попробуем основной модуль (CommandsMain) — он сейчас пустой и вернёт emptyList
        try {
            val mainRes = CommandsMain.handleCommand(cmd)
            if (mainRes.isNotEmpty()) return mainRes
        } catch (e: Exception) {
            Log.w(TAG, "CommandsMain failed", e)
            // не фейлим — идём дальше
        }

        // Затем V2 (низкий приоритет)
        try {
            val v2Res = CommandsV2.handleCommand(cmd)
            if (v2Res.isNotEmpty()) return v2Res
        } catch (e: Exception) {
            Log.w(TAG, "CommandsV2 failed", e)
        }

        // Затем V3 (ещё ниже)
        try {
            val v3Res = CommandsV3.handleCommand(cmd)
            if (v3Res.isNotEmpty()) return v3Res
        } catch (e: Exception) {
            Log.w(TAG, "CommandsV3 failed", e)
        }

        // fallback
        return listOf("Неизвестная команда. Введите 'help' для справки (заглушка).")
    }

    // --------------------
    // UI helpers (копия стиля)
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

    // Utility: generate random bytes (unused but left) — оставлено для совместимости с оригиналом
    private fun randomBytes(size: Int): ByteArray {
        val rnd = SecureRandom()
        val arr = ByteArray(size)
        rnd.nextBytes(arr)
        return arr
    }
}

// --------------------
// Модули команд: заглушки / шаблоны
// --------------------

/**
 * CommandsMain — основной модуль для этого activity.
 * Сейчас: только help-заглушка. Расширяйте здесь реальные команды в будущем.
 *
 * Пример: fun handleCommand(cmdRaw: String): List<String> { ... }
 */
private object CommandsMain {

    fun handleCommand(cmdRaw: String): List<String> {
        val lower = cmdRaw.trim().lowercase(Locale.getDefault())
        // Заглушка: main не реализован, кроме help (который обрабатывается в Activity)
        // Возвращаем пустой список — чтобы парсер продолжил в V2/V3.
        return emptyList()
    }

    // Пример helper — добавляйте здесь реальные парсеры/модули.
    // private fun parseSomething(payload: String): Something { ... }
}

/**
 * CommandsV2 — второй модуль (низкий приоритет).
 * Пока заглушка: возвращает пустой список.
 * Предназначен для размещения дополнительных команд (в дальнейшем можно перенести сюда FinanceV2 и т.п.).
 */
private object CommandsV2 {

    fun handleCommand(cmdRaw: String): List<String> {
        val lower = cmdRaw.trim().lowercase(Locale.getDefault())
        // Заглушка: ничего не делает (ниже по приоритету).
        // Если захотите, можно реализовать help здесь или отдельные тестовые команды.
        return emptyList()
    }

    // Место для helpers V2
    // private fun helperV2(...) { ... }
}

/**
 * CommandsV3 — третий модуль (ещё ниже приоритет).
 * Заглушка — используется как резервный модуль.
 */
private object CommandsV3 {

    fun handleCommand(cmdRaw: String): List<String> {
        // Простейшая заглушка
        return emptyList()
    }

    // Место для helpers V3
}

/*
  Инструкции по использованию/расширению:
  - Чтобы перенести команду из вашего VprActivity в этот шаблон, поместите парсер/хелперы в один из объектов:
      CommandsMain (первый), CommandsV2 (второй), CommandsV3 (третий).
  - Каждый объект должен возвращать List<String> (пустой список = не распознано).
  - Парсер Activity вызывает модули в порядке приоритета — main -> v2 -> v3.
  - Важно: не кидать unchecked исключения из модулей — Activity ловит и логирует их (чтобы модуль низкого приоритета не ломал обработку).
  - Команды, зависящие от Android (Intent и т.п.), нужно выполнять через withContext(Dispatchers.Main) в Activity
    или прокидывать контекст/интерфейс в модуль (если делаете тесную интеграцию).
*/

