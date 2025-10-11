package com.nemesis.droidcrypt

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

class EggActivity : AppCompatActivity() {

    private val PREFS = "app_prefs"
    private val KEY_PIN = "pin_code"
    private val DEFAULT_PIN = "5387"

    private lateinit var digitViews: List<TextView>
    private lateinit var errorText: TextView
    private lateinit var welcomeText: TextView
    private lateinit var btnDelete: Button
    private lateinit var keypadButtons: List<Button>

    private val current = StringBuilder()
    private var correctPin: String = DEFAULT_PIN

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_reg)

        digitViews = listOf(
            findViewById(R.id.d1),
            findViewById(R.id.d2),
            findViewById(R.id.d3),
            findViewById(R.id.d4),
        )

        errorText = findViewById(R.id.errorText)
        welcomeText = findViewById(R.id.welcomeText)
        btnDelete = findViewById(R.id.btnDelete)

        keypadButtons = listOf(
            findViewById(R.id.btn1),
            findViewById(R.id.btn2),
            findViewById(R.id.btn3),
            findViewById(R.id.btn4),
            findViewById(R.id.btn5),
            findViewById(R.id.btn6),
            findViewById(R.id.btn7),
            findViewById(R.id.btn8),
            findViewById(R.id.btn9),
            findViewById(R.id.btn0)
        )

        // Определяем корректный PIN:
        val prefs = getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        correctPin = if (prefs.contains(KEY_PIN)) {
            prefs.getString(KEY_PIN, "") ?: ""
        } else {
            DEFAULT_PIN
        }

        setupKeypad()
        updateDots()
    }

    private fun setupKeypad() {
        keypadButtons.forEach { btn ->
            btn.setOnClickListener {
                errorText.visibility = View.GONE
                if (current.length < 4) {
                    current.append(btn.text.toString())
                    updateDots()
                    if (current.length == 4) checkPin()
                }
            }
            // отключаем длинное нажатие, чтобы не показывать всплывающие меню
            btn.isLongClickable = false
        }

        btnDelete.setOnClickListener {
            errorText.visibility = View.GONE
            if (current.isNotEmpty()) {
                current.deleteCharAt(current.length - 1)
                updateDots()
            }
        }
        btnDelete.isLongClickable = false
    }

    private fun updateDots() {
        for (i in 0 until 4) {
            digitViews[i].text = if (i < current.length) "●" else "○"
        }
    }

    private fun checkPin() {
        val entered = current.toString()
        // проверка: если в prefs была метка, используется она; иначе DEFAULT_PIN
        if (entered == correctPin) {
            onSuccess()
        } else {
            onWrong()
        }
    }

    private fun onWrong() {
        // показать текстовую ошибку (не тост)
        errorText.text = "Неверный код"
        errorText.visibility = View.VISIBLE

        // очищаем поле для нового ввода
        current.clear()
        updateDots()
    }

    private fun onSuccess() {
        // блокируем клавиатуру
        keypadButtons.forEach { it.isEnabled = false }
        btnDelete.isEnabled = false

        // Скрываем ошибку (если была)
        errorText.visibility = View.GONE

        // Показываем мягко загорающуюся надпись "Welcome!"
        welcomeText.alpha = 0f
        welcomeText.text = "Welcome!"
        welcomeText.visibility = View.VISIBLE
        welcomeText.animate()
            .alpha(1f)
            .setDuration(700)
            .start()

        // Через 2 секунды переходим в StreetFoodActivity
        Handler(Looper.getMainLooper()).postDelayed({
            startActivity(Intent(this@EggActivity, StreetFoodActivity::class.java))
            finish()
        }, 2000L)
    }
}
