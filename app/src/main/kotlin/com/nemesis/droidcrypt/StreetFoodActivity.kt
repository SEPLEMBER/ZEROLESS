package com.nemesis.droidcrypt

import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

class StreetFoodActivity : AppCompatActivity() {

    private val PREFS = "Pawstribe_prefs"
    private val KEY_PIN = "pin_code"
    private val DEFAULT_PIN = "5387"

    private lateinit var editPin: EditText
    private lateinit var btnSave: Button
    private lateinit var statusText: TextView
    private lateinit var centerText: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_street_food)

        editPin = findViewById(R.id.editPin)
        btnSave = findViewById(R.id.btnSavePin)
        statusText = findViewById(R.id.statusText)
        centerText = findViewById(R.id.centerText)

        // Показать текущий пин (если есть) как hint; иначе показать hint с дефолтом
        val prefs = getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val stored = if (prefs.contains(KEY_PIN)) prefs.getString(KEY_PIN, "") else null
        editPin.hint = if (stored.isNullOrEmpty()) "Текущий: $DEFAULT_PIN" else "Текущий: $stored"

        statusText.visibility = View.GONE

        btnSave.setOnClickListener {
            statusText.visibility = View.GONE
            val newPin = editPin.text.toString().trim()
            if (newPin.length == 4 && newPin.all { it.isDigit() }) {
                prefs.edit().putString(KEY_PIN, newPin).apply()
                statusText.setTextColor(resources.getColor(android.R.color.white, theme))
                statusText.text = "PIN сохранён"
                statusText.visibility = View.VISIBLE

                // Обновить hint
                editPin.hint = "Текущий: $newPin"
                editPin.text.clear()

                // скрыть сообщение через 2 секунды
                Handler(Looper.getMainLooper()).postDelayed({
                    statusText.visibility = View.GONE
                }, 2000L)
            } else {
                statusText.setTextColor(resources.getColor(android.R.color.holo_red_light, theme))
                statusText.text = "Введите 4 цифры"
                statusText.visibility = View.VISIBLE
            }
        }
    }
}
