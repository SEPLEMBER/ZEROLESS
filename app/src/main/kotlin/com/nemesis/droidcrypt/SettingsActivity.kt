package com.nemesis.droidcrypt

import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.Switch
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.documentfile.provider.DocumentFile
import androidx.preference.PreferenceManager
import java.io.BufferedReader
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStreamReader

class SettingsActivity : AppCompatActivity() {

    companion object {
        private const val REQUEST_CODE_OPEN_DIRECTORY = 1
        private const val PREF_KEY_FOLDER_URI = "pref_folder_uri"
        private const val PREF_KEY_DISABLE_SCREENSHOTS = "pref_disable_screenshots"
    }

    private var folderUri: Uri? = null
    private lateinit var selectFolderButton: Button
    private lateinit var clearTemplatesButton: Button
    private lateinit var backButton: Button
    private lateinit var saveTemplatesButton: Button
    private lateinit var templatesInput: EditText
    private lateinit var disableScreenshotsSwitch: Switch
    private lateinit var prefs: SharedPreferences

    private val folderPickerLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == RESULT_OK) {
            result.data?.data?.let { uri ->
                folderUri = uri
                try {
                    contentResolver.takePersistableUriPermission(
                        uri,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                    )
                } catch (e: SecurityException) {
                    // Игнорируем, если не удалось получить разрешение
                }
                prefs.edit().putString(PREF_KEY_FOLDER_URI, uri.toString()).apply()
                Toast.makeText(this, "Папка выбрана", Toast.LENGTH_SHORT).show()
                loadTemplatesFromFile("base.txt")
            } ?: Toast.makeText(this, "Ошибка: папка не выбрана", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        prefs = PreferenceManager.getDefaultSharedPreferences(this)

        selectFolderButton = findViewById(R.id.selectFolderButton)
        clearTemplatesButton = findViewById(R.id.clearTemplatesButton)
        backButton = findViewById(R.id.backButton)
        saveTemplatesButton = findViewById(R.id.saveTemplatesButton)
        templatesInput = findViewById(R.id.templatesInput)
        disableScreenshotsSwitch = findViewById(R.id.disableScreenshotsSwitch)

        templatesInput.setText("")

        // Загрузка настройки запрета скриншотов
        disableScreenshotsSwitch.isChecked = prefs.getBoolean(PREF_KEY_DISABLE_SCREENSHOTS, false)

        // Попытка восстановить ранее сохраненный URI папки
        prefs.getString(PREF_KEY_FOLDER_URI, null)?.let { saved ->
            try {
                folderUri = Uri.parse(saved)
                contentResolver.takePersistableUriPermission(
                    folderUri!!,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                )
                loadTemplatesFromFile("base.txt")
            } catch (e: Exception) {
                folderUri = null
            }
        }

        selectFolderButton.setOnClickListener { openFolderPicker() }

        clearTemplatesButton.setOnClickListener {
            templatesInput.setText("")
            Toast.makeText(this, "Поле очищено", Toast.LENGTH_SHORT).show()
        }

        saveTemplatesButton.setOnClickListener {
            if (folderUri == null) {
                Toast.makeText(this, "Сначала выберите папку", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val all = templatesInput.text.toString()
            if (all.trim().isEmpty()) {
                saveTemplatesToFile("base.txt", "")
                Toast.makeText(this, "Шаблоны очищены", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val lines = all.lines()
            var savedCount = 0
            var skipped = 0
            val content = buildString {
                lines.forEach { raw ->
                    val line = raw.trim()
                    if (line.isEmpty()) return@forEach
                    if ("=" !in line) {
                        skipped++
                        return@forEach
                    }
                    val (key, value) = line.split("=", limit = 2).map { it.trim() }
                    if (key.isEmpty()) {
                        skipped++
                        return@forEach
                    }
                    if (isNotEmpty()) append('\n')
                    append(key.toLowerCase()).append('=').append(value)
                    savedCount++
                }
            }
            saveTemplatesToFile("base.txt", content)
            Toast.makeText(this, "Сохранено: $savedCount, пропущено: $skipped", Toast.LENGTH_SHORT).show()
        }

        disableScreenshotsSwitch.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean(PREF_KEY_DISABLE_SCREENSHOTS, isChecked).apply()
            Toast.makeText(this, if (isChecked) "Скриншоты запрещены" else "Скриншоты разрешены", Toast.LENGTH_SHORT).show()
        }

        backButton.setOnClickListener {
            prefs.edit().putBoolean(PREF_KEY_DISABLE_SCREENSHOTS, disableScreenshotsSwitch.isChecked).apply()
            setResult(RESULT_OK, Intent().apply {
                putExtra("folderUri", folderUri)
                putExtra("disableScreenshots", disableScreenshotsSwitch.isChecked)
            })
            finish()
        }
    }

    private fun openFolderPicker() {
        Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            addFlags(Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
            folderPickerLauncher.launch(this)
        }
    }

    private fun saveTemplatesToFile(filename: String, content: String) {
        try {
            val dir = DocumentFile.fromTreeUri(this, folderUri!!)
            if (dir == null || !dir.exists() || !dir.isDirectory) {
                Toast.makeText(this, "Ошибка: папка недоступна", Toast.LENGTH_SHORT).show()
                return
            }
            val file = dir.findFile(filename) ?: dir.createFile("text/plain", filename)
            if (file == null) {
                Toast.makeText(this, "Ошибка создания файла", Toast.LENGTH_SHORT).show()
                return
            }
            contentResolver.openFileDescriptor(file.uri, "w")?.use { pfd ->
                FileOutputStream(pfd.fileDescriptor).use { fos ->
                    fos.write(content.toByteArray())
                    Toast.makeText(this, "Сохранено успешно", Toast.LENGTH_SHORT).show()
                }
            }
        } catch (e: Exception) {
            Toast.makeText(this, "Ошибка сохранения: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun loadTemplatesFromFile(filename: String) {
        try {
            val dir = DocumentFile.fromTreeUri(this, folderUri!!)
            if (dir == null || !dir.exists() || !dir.isDirectory) {
                Toast.makeText(this, "Ошибка: папка недоступна", Toast.LENGTH_SHORT).show()
                templatesInput.setText("")
                return
            }
            val file = dir.findFile(filename)
            if (file == null || !file.exists()) {
                templatesInput.setText("")
                return
            }
            contentResolver.openFileDescriptor(file.uri, "r")?.use { pfd ->
                FileInputStream(pfd.fileDescriptor).use { fis ->
                    BufferedReader(InputStreamReader(fis)).use { reader ->
                        templatesInput.setText(reader.readText())
                    }
                }
            }
        } catch (e: Exception) {
            Toast.makeText(this, "Ошибка загрузки: ${e.message}", Toast.LENGTH_SHORT).show()
            templatesInput.setText("")
        }
    }
}
