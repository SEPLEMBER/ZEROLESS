package com.nemesis.droidcrypt

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.animation.AlphaAnimation
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import java.io.BufferedReader
import java.io.InputStreamReader
import kotlin.random.Random

class MainActivity : AppCompatActivity() {
    private lateinit var matrixText: TextView
    private lateinit var modelLogo: ImageView
    private lateinit var metaCoreText: TextView
    private val handler = Handler(Looper.getMainLooper())
    private val welcomeMessages = arrayOf(
        R.string.welcome_1,
        R.string.welcome_2,
        R.string.welcome_3
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        matrixText = findViewById(R.id.matrix_text)
        modelLogo = findViewById(R.id.model_logo)
        metaCoreText = findViewById(R.id.meta_core_text)

        val prefs = getSharedPreferences("CyberBeastBot", MODE_PRIVATE)
        val isRegistered = prefs.getBoolean("isRegistered", false)
        val hasExited = prefs.getBoolean("hasExited", false)

        if (!isRegistered) {
            startActivity(Intent(this, SetupActivity::class.java))
            finish()
        } else {
            showConnectionAnimation(hasExited)
        }
    }

    private fun showConnectionAnimation(hasExited: Boolean) {
        if (hasExited) {
            val randomMessage = getString(welcomeMessages[Random.nextInt(welcomeMessages.size)])
            animateText(matrixText, randomMessage) { showConnecting() }
        } else {
            showConnecting()
        }
    }

    private fun showConnecting() {
        animateText(matrixText, "Подключение...") {
            // Load logo and meta_core.txt
            val prefs = getSharedPreferences("CyberBeastBot", MODE_PRIVATE)
            val logoPath = prefs.getString("modelLogoPath", null)
            if (logoPath != null) {
                modelLogo.setImageURI(Uri.parse(logoPath))
                val fadeIn = AlphaAnimation(0f, 1f).apply { duration = 1000 }
                modelLogo.visibility = TextView.VISIBLE
                modelLogo.startAnimation(fadeIn)
            }

            val metaPath = prefs.getString("metaCorePath", null)
            if (metaPath != null) {
                try {
                    contentResolver.openInputStream(Uri.parse(metaPath))?.use { input ->
                        BufferedReader(InputStreamReader(input)).use { reader ->
                            val metaText = reader.readText()
                            metaCoreText.text = metaText
                            metaCoreText.visibility = TextView.VISIBLE
                        }
                    }
                } catch (e: Exception) {
                    metaCoreText.text = "..."
                    metaCoreText.visibility = TextView.VISIBLE
                }
            }

            handler.postDelayed({
                animateText(matrixText, "С подключением.") {
                    startActivity(Intent(this, ChatActivity::class.java))
                    finish()
                }
            }, 2000)
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
