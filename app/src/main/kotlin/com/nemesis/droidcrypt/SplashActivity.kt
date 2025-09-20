package com.nemesis.droidcrypt

import android.content.Intent
import android.content.SharedPreferences
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.documentfile.provider.DocumentFile
import java.io.BufferedReader
import java.io.FileInputStream
import java.io.InputStreamReader

class SplashActivity : AppCompatActivity() {
    private lateinit var prefs: SharedPreferences
    private var folderUri: Uri? = null
    private lateinit var splashImage: ImageView
    private lateinit var statusText: TextView
    private lateinit var metadataText: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_splash)

        splashImage = findViewById(R.id.splashImage)
        statusText = findViewById(R.id.statusText)
        metadataText = findViewById(R.id.metadataText)

        prefs = getSharedPreferences("my_prefs", MODE_PRIVATE)
        prefs.getString("pref_folder_uri", null)?.let { saved ->
            try {
                folderUri = Uri.parse(saved)
                contentResolver.takePersistableUriPermission(
                    folderUri!!,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                )
            } catch (e: Exception) {
                folderUri = null
                // Опционально: Покажите Toast или лог об ошибке
            }
        }

        // Устанавливаем статус "подключение"
        statusText.text = "Подключение..."

        // Загружаем только сплэш и метаданные
        loadImageFromSAF("splash_engine.png", splashImage)
        val engineMeta = loadTextFromSAF("engine_metadata.txt")
        val uiMeta = loadTextFromSAF("UI_metadata.txt")
        metadataText.text = (engineMeta + "\n" + uiMeta).trim()

        // Сразу переходим в чат после загрузки
        Handler(Looper.getMainLooper()).postDelayed({
            val intent = Intent(this, ChatActivity::class.java)
            if (folderUri != null) {
                intent.putExtra("folderUri", folderUri)
            }
            startActivity(intent)
            finish()
        }, 50)
    }

    private fun loadImageFromSAF(filename: String, target: ImageView) {
        try {
            folderUri?.let { uri ->
                val dir = DocumentFile.fromTreeUri(this, uri)
                val file = dir?.findFile(filename)
                if (file != null && file.exists()) {
                    contentResolver.openFileDescriptor(file.uri, "r")?.use { pfd ->
                        FileInputStream(pfd.fileDescriptor).use { fis ->
                            val bmp = BitmapFactory.decodeStream(fis)
                            runOnUiThread { target.setImageBitmap(bmp) }
                        }
                    }
                }
            }
        } catch (_: Exception) {
        }
    }

    private fun loadTextFromSAF(filename: String): String {
        return try {
            folderUri?.let { uri ->
                val dir = DocumentFile.fromTreeUri(this, uri)
                val file = dir?.findFile(filename)
                if (file != null && file.exists()) {
                    contentResolver.openFileDescriptor(file.uri, "r")?.use { pfd ->
                        FileInputStream(pfd.fileDescriptor).use { fis ->
                            BufferedReader(InputStreamReader(fis)).use { reader ->
                                return reader.readText()
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
