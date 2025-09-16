package com.nemesis.droidcrypt

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.EditText
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

class SetupActivity : AppCompatActivity() {
    private lateinit var rackyText: TextView
    private lateinit var commandInput: EditText
    private val handler = Handler(Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_setup)

        rackyText = findViewById(R.id.racky_text)
        commandInput = findViewById(R.id.command_input)

        animateText(rackyText, getString(R.string.racky_intro)) {
            commandInput.setOnEditorActionListener { _, _, _ ->
                val command = commandInput.text.toString().trim()
                when (command) {
                    "/settings" -> {
                        startActivity(Intent(this, SettingsActivity::class.java))
                        finish()
                        true
                    }
                    "/exit" -> {
                        getSharedPreferences("CyberBeastBot", MODE_PRIVATE)
                            .edit()
                            .putBoolean("hasExited", true)
                            .apply()
                        finish()
                        true
                    }
                    else -> false
                }
            }
        }
    }

    private fun animateText(textView: TextView, text: String, onComplete: () -> Unit) {
        textView.text = ""
        text.forEachIndexed { index, char ->
            handler.postDelayed({
                textView.append(char.toString())
                if (index == text.length - 1) onComplete()
            }, 50L * index)
        }
    }
}
