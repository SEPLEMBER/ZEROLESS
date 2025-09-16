package com.nemesis.droidcrypt

import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import android.view.animation.AlphaAnimation
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import java.io.BufferedReader
import java.io.InputStreamReader
import kotlin.random.Random

class MainActivity : AppCompatActivity() {
    
    companion object {
        private const val TAG = "MainActivity"
        private const val PREFS_NAME = "CyberBeastBot"
        private const val KEY_IS_REGISTERED = "isRegistered"
        private const val KEY_HAS_EXITED = "hasExited"
        private const val KEY_MODEL_LOGO_PATH = "modelLogoPath"
        private const val KEY_META_CORE_PATH = "metaCorePath"
        
        private const val TYPING_DELAY = 50L
        private const val CONNECTION_DELAY = 2000L
        private const val FADE_DURATION = 1000L
    }
    
    private lateinit var matrixText: TextView
    private lateinit var modelLogo: ImageView
    private lateinit var metaCoreText: TextView
    
    private val handler = Handler(Looper.getMainLooper())
    private val prefs: SharedPreferences by lazy {
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
    }
    
    private val welcomeMessages = arrayOf(
        R.string.welcome_1,
        R.string.welcome_2,
        R.string.welcome_3
    )
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        try {
            setContentView(R.layout.activity_main)
            initViews()
            checkRegistrationAndProceed()
        } catch (e: Exception) {
            Log.e(TAG, "Error in onCreate", e)
            finishWithError()
        }
    }
    
    override fun onDestroy() {
        super.onDestroy()
        // Очищаем все отложенные задачи
        handler.removeCallbacksAndMessages(null)
    }
    
    private fun initViews() {
        matrixText = findViewById(R.id.matrix_text)
        modelLogo = findViewById(R.id.model_logo)
        metaCoreText = findViewById(R.id.meta_core_text)
        
        // Инициализируем начальное состояние views
        modelLogo.visibility = View.INVISIBLE
        metaCoreText.visibility = View.INVISIBLE
    }
    
    private fun checkRegistrationAndProceed() {
        val isRegistered = prefs.getBoolean(KEY_IS_REGISTERED, false)
        val hasExited = prefs.getBoolean(KEY_HAS_EXITED, false)
        
        if (!isRegistered) {
            navigateToSetup()
        } else {
            showConnectionAnimation(hasExited)
        }
    }
    
    private fun navigateToSetup() {
        try {
            val intent = Intent(this, SetupActivity::class.java)
            startActivity(intent)
            finish()
        } catch (e: Exception) {
            Log.e(TAG, "Error navigating to SetupActivity", e)
            finishWithError()
        }
    }
    
    private fun showConnectionAnimation(hasExited: Boolean) {
        if (hasExited && welcomeMessages.isNotEmpty()) {
            val randomMessage = getRandomWelcomeMessage()
            animateText(matrixText, randomMessage) { 
                showConnecting() 
            }
        } else {
            showConnecting()
        }
    }
    
    private fun getRandomWelcomeMessage(): String {
        return try {
            val randomIndex = Random.nextInt(welcomeMessages.size)
            getString(welcomeMessages[randomIndex])
        } catch (e: Exception) {
            Log.w(TAG, "Error getting welcome message", e)
            "Добро пожаловать!"
        }
    }
    
    private fun showConnecting() {
        val connectingText = getString(R.string.connecting) ?: "Подключение..."
        
        animateText(matrixText, connectingText) {
            loadUserAssets()
            scheduleNavigation()
        }
    }
    
    private fun loadUserAssets() {
        loadModelLogo()
        loadMetaCoreText()
    }
    
    private fun loadModelLogo() {
        val logoPath = prefs.getString(KEY_MODEL_LOGO_PATH, null)
        
        if (!logoPath.isNullOrEmpty()) {
            try {
                val uri = Uri.parse(logoPath)
                modelLogo.setImageURI(uri)
                showViewWithFadeIn(modelLogo)
            } catch (e: Exception) {
                Log.w(TAG, "Error loading model logo", e)
                // Можно установить дефолтное изображение
                setDefaultLogo()
            }
        } else {
            setDefaultLogo()
        }
    }
    
    private fun setDefaultLogo() {
        try {
            modelLogo.setImageDrawable(
                ContextCompat.getDrawable(this, R.drawable.ic_default_logo)
            )
            showViewWithFadeIn(modelLogo)
        } catch (e: Exception) {
            Log.w(TAG, "Error setting default logo", e)
        }
    }
    
    private fun loadMetaCoreText() {
        val metaPath = prefs.getString(KEY_META_CORE_PATH, null)
        
        if (!metaPath.isNullOrEmpty()) {
            try {
                val uri = Uri.parse(metaPath)
                val metaText = readTextFromUri(uri)
                
                if (metaText.isNotEmpty()) {
                    metaCoreText.text = metaText
                } else {
                    metaCoreText.text = "..."
                }
                
                metaCoreText.visibility = View.VISIBLE
                
            } catch (e: Exception) {
                Log.w(TAG, "Error loading meta core text", e)
                metaCoreText.text = "..."
                metaCoreText.visibility = View.VISIBLE
            }
        }
    }
    
    private fun readTextFromUri(uri: Uri): String {
        return contentResolver.openInputStream(uri)?.use { inputStream ->
            BufferedReader(InputStreamReader(inputStream, Charsets.UTF_8)).use { reader ->
                reader.readText()
            }
        } ?: ""
    }
    
    private fun showViewWithFadeIn(view: View) {
        val fadeIn = AlphaAnimation(0f, 1f).apply { 
            duration = FADE_DURATION 
        }
        
        view.visibility = View.VISIBLE
        view.startAnimation(fadeIn)
    }
    
    private fun scheduleNavigation() {
        handler.postDelayed({
            if (!isDestroyed && !isFinishing) {
                val connectedText = getString(R.string.connected) ?: "Подключено."
                
                animateText(matrixText, connectedText) {
                    navigateToChat()
                }
            }
        }, CONNECTION_DELAY)
    }
    
    private fun navigateToChat() {
        try {
            val intent = Intent(this, ChatActivity::class.java)
            startActivity(intent)
            finish()
        } catch (e: Exception) {
            Log.e(TAG, "Error navigating to ChatActivity", e)
            finishWithError()
        }
    }
    
    private fun animateText(textView: TextView, text: String, onComplete: () -> Unit) {
        if (text.isEmpty()) {
            onComplete()
            return
        }
        
        textView.text = ""
        
        text.forEachIndexed { index, char ->
            handler.postDelayed({
                if (!isDestroyed && !isFinishing) {
                    textView.append(char.toString())
                    
                    if (index == text.length - 1) {
                        onComplete()
                    }
                }
            }, TYPING_DELAY * index)
        }
    }
    
    private fun finishWithError() {
        // Можно показать toast с ошибкой или диалог
        Log.e(TAG, "Critical error occurred, finishing activity")
        finish()
    }
}
