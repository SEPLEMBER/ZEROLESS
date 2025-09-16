package com.nemesis.droidcrypt

import android.content.SharedPreferences
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.documentfile.provider.DocumentFile
import androidx.preference.PreferenceManager
import java.io.BufferedReader
import java.io.InputStreamReader

class MainActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "MainActivity"
        private const val TYPING_DELAY = 50L
    }

    private lateinit var matrixText: TextView
    private lateinit var prefs: SharedPreferences
    private val handler = Handler(Looper.getMainLooper())
    private var folderUri: Uri? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        matrixText = findViewById(R.id.matrix_text)
        matrixText.setTextColor(0xFF00FF00.toInt()) // зеленый
        matrixText.text = ""

        prefs = PreferenceManager.getDefaultSharedPreferences(this)
        folderUri = prefs.getString("pref_folder_uri", null)?.let { Uri.parse(it) }

        if (folderUri != null) {
            loadTextFilesFromFolder(folderUri!!)
        } else {
            matrixText.text = "Папка не выбрана"
        }
    }

    private fun loadTextFilesFromFolder(uri: Uri) {
        try {
            val dir = DocumentFile.fromTreeUri(this, uri)
            if (dir == null || !dir.exists() || !dir.isDirectory) {
                matrixText.text = "Папка недоступна"
                return
            }

            val txtFiles = dir.listFiles().filter { it.name?.endsWith(".txt") == true }
            if (txtFiles.isEmpty()) {
                matrixText.text = "Файлов нет"
                return
            }

            var fullText = ""
            for (file in txtFiles) {
                contentResolver.openInputStream(file.uri)?.use { input ->
                    BufferedReader(InputStreamReader(input, Charsets.UTF_8)).use { reader ->
                        fullText += reader.readText() + "\n"
                    }
                }
            }
            animateText(matrixText, fullText.trim())
        } catch (e: Exception) {
            Log.e(TAG, "Error loading files", e)
            matrixText.text = "Ошибка загрузки"
        }
    }

    private fun animateText(textView: TextView, text: String) {
        textView.text = ""
        text.forEachIndexed { index, char ->
            handler.postDelayed({
                textView.append(char.toString())
            }, TYPING_DELAY * index)
        }
    }
}
