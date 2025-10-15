package com.nemesis.droidcrypt

import android.content.Intent
import android.graphics.Typeface
import android.os.Bundle
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
import java.util.Locale

class ArrivalActivity : AppCompatActivity() {

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

        setContentView(R.layout.activity_arrival)

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
                    addAssistantLine(getString(R.string.enter_choice))
                }
                true
            } else {
                false
            }
        }

        addSystemLine(getString(R.string.greeting))
        addSystemLine(getString(R.string.question))
        addSystemLine("") // vertical separation
        addSystemLine(getString(R.string.option_digital))
        addSystemLine(getString(R.string.option_finances))
        addSystemLine(getString(R.string.option_physics))
        addSystemLine(getString(R.string.option_other))
        addSystemLine("") // vertical separation
        addSystemLine(getString(R.string.note_experimental))
    }

    private fun submitCommand(command: String) {
        addUserLine("> $command")
        val outputs = parseCommand(command)
        outputs.forEach { addAssistantLine("= $it") }
        scrollView.post { scrollView.fullScroll(ScrollView.FOCUS_DOWN) }
    }

    private fun parseCommand(commandRaw: String): List<String> {
        val cmd = commandRaw.trim().toLowerCase(Locale.ROOT)

        when {
            cmd.contains("finance") -> {
                startActivity(Intent(this, EnFinAsActivity::class.java))
                finish()
                return listOf(getString(R.string.redirect_english_finances))
            }
            cmd.contains("финансы") -> {
                startActivity(Intent(this, RuFinAsActivity::class.java))
                finish()
                return listOf(getString(R.string.redirect_russian_finances))
            }
            cmd.contains("phys") -> {
                startActivity(Intent(this, EnPhysAsActivity::class.java))
                finish()
                return listOf(getString(R.string.redirect_english_physics))
            }
            cmd.contains("физи") -> {
                startActivity(Intent(this, RuPhysAsActivity::class.java))
                finish()
                return listOf(getString(R.string.redirect_russian_physics))
            }
            cmd.contains("other") || cmd.contains("другое") -> {
                startActivity(Intent(this, MainActivity::class.java))
                finish()
                return listOf(getString(R.string.redirect_main))
            }
            cmd.contains("dig") || cmd.contains("циф") -> {
                startActivity(Intent(this, MainActivity::class.java))
                finish()
                return listOf(getString(R.string.redirect_main))
            }
            else -> return listOf(getString(R.string.unknown_choice))
        }
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
}
