package com.nemesis.droidcrypt

import android.content.Intent
import android.content.SharedPreferences
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.KeyEvent
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

class SetupActivity : AppCompatActivity() {
    
    companion object {
        private const val TAG = "SetupActivity"
        private const val PREFS_NAME = "CyberBeastBot"
        private const val KEY_HAS_EXITED = "hasExited"
        private const val KEY_IS_REGISTERED = "isRegistered"
        
        private const val TYPING_DELAY = 50L
        
        // Команды
        private const val CMD_SETTINGS = "/settings"
        private const val CMD_EXIT = "/exit"
        private const val CMD_HELP = "/help"
        private const val CMD_START = "/start"
    }
    
    private lateinit var rackyText: TextView
    private lateinit var commandInput: EditText
    
    private val handler = Handler(Looper.getMainLooper())
    private val prefs: SharedPreferences by lazy {
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
    }
    
    private var isAnimating = false
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        try {
            setContentView(R.layout.activity_setup)
            initViews()
            startSetupSequence()
        } catch (e: Exception) {
            Log.e(TAG, "Error in onCreate", e)
            showErrorAndFinish()
        }
    }
    
    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacksAndMessages(null)
    }
    
    private fun initViews() {
        rackyText = findViewById(R.id.racky_text)
        commandInput = findViewById(R.id.command_input)
        
        // Настройка input'а
        commandInput.apply {
            imeOptions = EditorInfo.IME_ACTION_SEND
            setOnEditorActionListener { _, actionId, event ->
                when (actionId) {
                    EditorInfo.IME_ACTION_SEND -> {
                        handleCommand()
                        true
                    }
                    else -> {
                        // Обработка Enter на клавиатуре
                        if (event?.keyCode == KeyEvent.KEYCODE_ENTER && 
                            event.action == KeyEvent.ACTION_DOWN) {
                            handleCommand()
                            true
                        } else {
                            false
                        }
                    }
                }
            }
        }
    }
    
    private fun startSetupSequence() {
        val introText = getString(R.string.racky_intro) ?: "Добро пожаловать в настройку!"
        
        animateText(rackyText, introText) {
            enableInput()
            showAvailableCommands()
        }
    }
    
    private fun enableInput() {
        commandInput.isEnabled = true
        commandInput.requestFocus()
    }
    
    private fun showAvailableCommands() {
        handler.postDelayed({
            if (!isDestroyed && !isFinishing) {
                val helpText = buildString {
                    appendLine()
                    appendLine("Доступные команды:")
                    appendLine("$CMD_SETTINGS - открыть настройки")
                    appendLine("$CMD_START - начать использование")
                    appendLine("$CMD_HELP - показать помощь")
                    appendLine("$CMD_EXIT - выйти")
                }
                
                animateText(rackyText, rackyText.text.toString() + helpText) { }
            }
        }, 1000)
    }
    
    private fun handleCommand() {
        if (isAnimating) {
            return
        }
        
        val command = commandInput.text.toString().trim().lowercase()
        commandInput.text.clear()
        
        if (command.isEmpty()) {
            showMessage("Введите команду")
            return
        }
        
        when (command) {
            CMD_SETTINGS -> handleSettingsCommand()
            CMD_START -> handleStartCommand()
            CMD_HELP -> handleHelpCommand()
            CMD_EXIT -> handleExitCommand()
            else -> handleUnknownCommand(command)
        }
    }
    
    private fun handleSettingsCommand() {
        try {
            showMessage("Переход к настройкам...")
            
            handler.postDelayed({
                val intent = Intent(this, SettingsActivity::class.java)
                startActivity(intent)
                finish()
            }, 500)
            
        } catch (e: Exception) {
            Log.e(TAG, "Error opening settings", e)
            showMessage("Ошибка при переходе к настройкам")
        }
    }
    
    private fun handleStartCommand() {
        try {
            // Помечаем как зарегистрированного пользователя
            prefs.edit()
                .putBoolean(KEY_IS_REGISTERED, true)
                .apply()
            
            showMessage("Запуск приложения...")
            
            handler.postDelayed({
                val intent = Intent(this, MainActivity::class.java)
                startActivity(intent)
                finish()
            }, 1000)
            
        } catch (e: Exception) {
            Log.e(TAG, "Error starting main activity", e)
            showMessage("Ошибка при запуске")
        }
    }
    
    private fun handleHelpCommand() {
        val helpText = buildString {
            appendLine()
            appendLine("=== СПРАВКА ===")
            appendLine("$CMD_SETTINGS - Открыть меню настроек")
            appendLine("$CMD_START - Начать работу с приложением")
            appendLine("$CMD_HELP - Показать эту справку")
            appendLine("$CMD_EXIT - Выйти из приложения")
            appendLine()
            appendLine("Для выполнения команды введите её и нажмите Enter")
        }
        
        animateText(rackyText, helpText) { }
    }
    
    private fun handleExitCommand() {
        try {
            // Сохраняем флаг выхода
            prefs.edit()
                .putBoolean(KEY_HAS_EXITED, true)
                .apply()
            
            showMessage("До свидания!")
            
            handler.postDelayed({
                finishAffinity() // Закрываем все активности
            }, 1000)
            
        } catch (e: Exception) {
            Log.e(TAG, "Error on exit", e)
            finish()
        }
    }
    
    private fun handleUnknownCommand(command: String) {
        val errorMessage = "Неизвестная команда: '$command'\nВведите $CMD_HELP для справки"
        
        animateText(rackyText, errorMessage) {
            // Через некоторое время показываем доступные команды
            handler.postDelayed({
                showAvailableCommands()
            }, 2000)
        }
    }
    
    private fun showMessage(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }
    
    private fun animateText(textView: TextView, text: String, onComplete: () -> Unit) {
        if (text.isEmpty()) {
            onComplete()
            return
        }
        
        isAnimating = true
        textView.text = ""
        
        text.forEachIndexed { index, char ->
            handler.postDelayed({
                if (!isDestroyed && !isFinishing) {
                    textView.append(char.toString())
                    
                    if (index == text.length - 1) {
                        isAnimating = false
                        onComplete()
                    }
                }
            }, TYPING_DELAY * index)
        }
    }
    
    private fun showErrorAndFinish() {
        Toast.makeText(this, "Произошла ошибка при загрузке", Toast.LENGTH_LONG).show()
        finish()
    }
}
