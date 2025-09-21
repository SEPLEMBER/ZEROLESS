package com.nemesis.droidcrypt

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.FileInputStream
import java.io.InputStreamReader

class SplashActivity : AppCompatActivity() {
    private lateinit var prefs: SharedPreferences
    private var folderUri: Uri? = null
    private lateinit var statusText: TextView
    private lateinit var metadataText: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_splash)

        // Скрытие статус-бара
        window.setFlags(
            android.view.WindowManager.LayoutParams.FLAG_FULLSCREEN,
            android.view.WindowManager.LayoutParams.FLAG_FULLSCREEN
        )

        statusText = findViewById(R.id.statusText)
        metadataText = findViewById(R.id.metadataText)

        prefs = getSharedPreferences("PawsTribePrefs", MODE_PRIVATE)
        prefs.getString("folderUri", null)?.let { saved ->
            try {
                folderUri = Uri.parse(saved)
                contentResolver.takePersistableUriPermission(
                    folderUri!!,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                )
            } catch (e: Exception) {
                folderUri = null
            }
        }

        // Устанавливаем статус "Подключение..."
        statusText.text = getString(R.string.connecting)

        // Асинхронно загружаем метаданные
        lifecycleScope.launch {
            val engineMeta = loadTextFromSAF("engine_metadata.txt")
            val uiMeta = loadTextFromSAF("UI_metadata.txt")
            metadataText.text = (engineMeta + "\n" + uiMeta).trim()
        }

        // Переход в ChatActivity через 50 мс
        lifecycleScope.launch {
            delay(50)
            val intent = Intent(this@SplashActivity, ChatActivity::class.java)
            folderUri?.let { intent.putExtra("folderUri", it) }
            startActivity(intent)
            finish()
        }
    }

    private suspend fun loadTextFromSAF(filename: String): String = withContext(Dispatchers.IO) {
        try {
            folderUri?.let { uri ->
                val dir = DocumentFile.fromTreeUri(this@SplashActivity, uri)
                val file = dir?.findFile(filename)
                if (file != null && file.exists()) {
                    contentResolver.openFileDescriptor(file.uri, "r")?.use { pfd ->
                        FileInputStream(pfd.fileDescriptor).use { fis ->
                            BufferedReader(InputStreamReader(fis)).use { reader ->
                                return@withContext reader.readText()
                            }
                        }
                    }
                }
            }
            ""
        } catch (e: Exception) {
            ""
        }
    }
}
