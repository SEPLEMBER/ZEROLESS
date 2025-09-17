package com.nemesis.droidcrypt

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.nemesis.droidcrypt.utils.security.SecCoreUtils

class MainActivity : AppCompatActivity() {

    companion object {
        private const val REQUEST_CODE_SETTINGS = 1001
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val startChat = findViewById<Button>(R.id.startChatButton)
        val openSettings = findViewById<Button>(R.id.openSettingsButton)

        startChat.setOnClickListener {
            // NEW: перед запуском ChatActivity — запрос пароля
            showPasswordDialog()
        }

        openSettings.setOnClickListener {
            val i = Intent(this@MainActivity, SettingsActivity::class.java)
            startActivityForResult(i, REQUEST_CODE_SETTINGS)
        }
    }

    @Deprecated("onActivityResult is deprecated but kept for parity with original behavior")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_CODE_SETTINGS && resultCode == RESULT_OK && data != null) {
            val folderUri = data.getParcelableExtra<Uri>("folderUri")
            val i = Intent(this@MainActivity, ChatActivity::class.java)
            if (folderUri != null) {
                i.putExtra("folderUri", folderUri)
            }
            startActivity(i)
        }
    }

    // NEW: функция запроса пароля и расшифровки masterKey
    private fun showPasswordDialog() {
        val editText = EditText(this)
        editText.hint = "Введите пароль"

        val container = LinearLayout(this).apply {
            setPadding(50, 40, 50, 10)
            orientation = LinearLayout.VERTICAL
            addView(editText)
        }

        AlertDialog.Builder(this)
            .setTitle("Авторизация")
            .setView(container)
            .setPositiveButton("OK") { dialog, _ ->
                val password = editText.text.toString().toCharArray()
                val prefs = getSharedPreferences("my_prefs", MODE_PRIVATE) // Заменено PreferenceManager

                try {
                    val masterKey = SecCoreUtils.loadMasterFromPrefs(prefs, "wrapped_master_key", password)

                    SecCoreUtils.wipe(password) // сразу очищаем пароль из памяти

                    if (masterKey != null) {
                        // Сохраняем ключ в оперативной памяти (SessionKeys — простой object)
                        SessionKeys.masterKey = masterKey

                        // Успех — запускаем чат
                        val i = Intent(this@MainActivity, ChatActivity::class.java)
                        startActivity(i)
                    } else {
                        Toast.makeText(this, "Не найден сохранённый ключ", Toast.LENGTH_SHORT).show()
                    }
                } catch (e: Exception) {
                    Toast.makeText(this, "Неверный пароль", Toast.LENGTH_SHORT).show()
                    SecCoreUtils.wipe(password)
                }

                dialog.dismiss()
            }
            .setNegativeButton("Отмена") { dialog, _ ->
                dialog.dismiss()
            }
            .show()
    }
}

// NEW: простое хранилище ключа в ОЗУ
object SessionKeys {
    @Volatile
    var masterKey: ByteArray? = null
}
