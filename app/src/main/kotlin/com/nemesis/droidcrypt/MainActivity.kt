package com.nemesis.droidcrypt

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton
import com.google.android.material.snackbar.Snackbar
import kotlin.random.Random
import com.nemesis.droidcrypt.SplashActivity

class MainActivity : AppCompatActivity() {
    private val settingsResultLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == RESULT_OK) {
            val data = result.data
            val folderUri = data?.getParcelableExtra<Uri>("folderUri")
            val i = Intent(this@MainActivity, ChatActivity::class.java)
            folderUri?.let { i.putExtra("folderUri", it) }
            startActivity(i)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // Скрытие статус-бара
        window.setFlags(
            android.view.WindowManager.LayoutParams.FLAG_FULLSCREEN,
            android.view.WindowManager.LayoutParams.FLAG_FULLSCREEN
        )

        // Отображение процента батареи
        updateBatteryLevel()

        // Случайное приветствие с эффектом печатания
        val greetings = resources.getStringArray(R.array.greetings)
        val randomGreeting = greetings[Random.nextInt(greetings.size)]
        animateGreeting(randomGreeting)

        val startChat = findViewById<MaterialButton>(R.id.startChatButton)
        val setupText = findViewById<TextView>(R.id.setupText)
        val settingsText = findViewById<TextView>(R.id.settingsText)

        startChat.setOnClickListener {
            val sharedPrefs = getSharedPreferences("PawsTribePrefs", MODE_PRIVATE)
            val folderUri = sharedPrefs.getString("folderUri", null)
            if (folderUri != null) {
                val i = Intent(this@MainActivity, SplashActivity::class.java)
                startActivity(i)
                finish() // Закрываем MainActivity для освобождения памяти
            } else {
                Snackbar.make(
                    startChat,
                    R.string.toast_folder_not_selected,
                    Snackbar.LENGTH_SHORT
                ).show()
            }
        }

        setupText.setOnClickListener {
            val i = Intent(this@MainActivity, EggActivity::class.java)
            startActivity(i)
        }

        settingsText.setOnClickListener {
            val i = Intent(this@MainActivity, SettingsActivity::class.java)
            settingsResultLauncher.launch(i)
        }
    }

    private fun updateBatteryLevel() {
        val batteryStatus: Intent? = registerReceiver(null, android.content.IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level: Int = batteryStatus?.getIntExtra(android.os.BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale: Int = batteryStatus?.getIntExtra(android.os.BatteryManager.EXTRA_SCALE, -1) ?: -1
        val batteryPct = if (level != -1 && scale != -1) (level * 100 / scale.toFloat()).toInt() else 0

        val batteryText = findViewById<TextView>(R.id.batteryText)
        batteryText.text = "$batteryPct%"
        batteryText.setTextColor(
            when {
                batteryPct <= 20 -> getColor(R.color.battery_low)
                batteryPct <= 40 -> getColor(R.color.battery_ok)
                else -> getColor(R.color.battery_full)
            }
        )
    }

    private fun animateGreeting(greeting: String) {
        val greetingText = findViewById<TextView>(R.id.greetingText)
        greetingText.text = ""
        var index = 0
        val handler = Handler(Looper.getMainLooper())
        handler.postDelayed(object : Runnable {
            override fun run() {
                if (index < greeting.length) {
                    greetingText.append(greeting[index].toString())
                    index++
                    handler.postDelayed(this, 50) // Задержка 50 мс на символ
                }
            }
        }, 500) // Начальная задержка на 500 мс
    }
}
