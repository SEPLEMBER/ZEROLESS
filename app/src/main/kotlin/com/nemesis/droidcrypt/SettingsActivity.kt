package com.nemesis.droidcrypt

import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.preference.PreferenceManager
import android.widget.Button
import android.widget.EditText
import android.widget.Switch
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.documentfile.provider.DocumentFile
import java.io.BufferedReader
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStreamReader
import java.util.Locale

class SettingsActivity : AppCompatActivity() {

    private var folderUri: Uri? = null
    private lateinit var selectFolderButton: Button
    private lateinit var clearTemplatesButton: Button
    private lateinit var backButton: Button
    private lateinit var saveTemplatesButton: Button
    private lateinit var templatesInput: EditText
    private lateinit var disableScreenshotsSwitch: Switch

    companion object {
        private const val REQUEST_CODE_OPEN_DIRECTORY = 1
        private const val PREF_KEY_FOLDER_URI = "pref_folder_uri"
        private const val PREF_KEY_DISABLE_SCREENSHOTS = "pref_disable_screenshots"
    }

    private lateinit var prefs: SharedPreferences

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

        // Load screenshot disable setting (same key used in ChatActivity)
        disableScreenshotsSwitch.isChecked = prefs.getBoolean(PREF_KEY_DISABLE_SCREENSHOTS, false)

        // Try to restore previously persisted folder URI from SharedPreferences
        val saved = prefs.getString(PREF_KEY_FOLDER_URI, null)
        if (saved != null) {
            try {
                folderUri = Uri.parse(saved)
                // use safe let to avoid passing nullable Uri
                folderUri?.let { uri ->
                    try {
                        contentResolver.takePersistableUriPermission(
                            uri,
                            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                        )
                    } catch (_: SecurityException) { }
                }
                loadTemplatesFromFile("base.txt")
            } catch (e: Exception) {
                folderUri = null
            }
        }

        selectFolderButton.setOnClickListener { openFolderPicker() }

        clearTemplatesButton.setOnClickListener {
            templatesInput.setText("")
            Toast.makeText(this@SettingsActivity, "Поле очищено", Toast.LENGTH_SHORT).show()
        }

        saveTemplatesButton.setOnClickListener {
            if (folderUri == null) {
                Toast.makeText(this@SettingsActivity, "Сначала выберите папку", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val all = templatesInput.text.toString()
            if (all.trim().isEmpty()) {
                saveTemplatesToFile("base.txt", "")
                Toast.makeText(this@SettingsActivity, "Шаблоны очищены", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val lines = all.split("\\r?\\n".toRegex())
            val sb = StringBuilder()
            var savedCount = 0
            var skipped = 0
            for (raw in lines) {
                val line = raw.trim()
                if (line.isEmpty()) continue
                if (!line.contains("=")) {
                    skipped++
                    continue
                }
                val parts = line.split("=", limit = 2)
                val key = parts[0].trim().lowercase(Locale.ROOT)
                val value = if (parts.size > 1) parts[1].trim() else ""
                if (key.isEmpty()) {
                    skipped++
                    continue
                }
                if (sb.isNotEmpty()) sb.append('\n')
                sb.append(key).append("=").append(value)
                savedCount++
            }
            val content = sb.toString()
            saveTemplatesToFile("base.txt", content)
            Toast.makeText(this@SettingsActivity, "Сохранено: $savedCount, пропущено: $skipped", Toast.LENGTH_SHORT).show()
        }

        disableScreenshotsSwitch.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean(PREF_KEY_DISABLE_SCREENSHOTS, isChecked).apply()
            Toast.makeText(this, if (isChecked) "Скриншоты запрещены" else "Скриншоты разрешены", Toast.LENGTH_SHORT).show()
        }

        backButton.setOnClickListener {
            prefs.edit().putBoolean(PREF_KEY_DISABLE_SCREENSHOTS, disableScreenshotsSwitch.isChecked).apply()

            val resultIntent = Intent().apply {
                putExtra("folderUri", folderUri)
                putExtra("disableScreenshots", disableScreenshotsSwitch.isChecked)
            }
            setResult(RESULT_OK, resultIntent)
            finish()
        }
    }

    private fun openFolderPicker() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            addFlags(Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
        }
        startActivityForResult(intent, REQUEST_CODE_OPEN_DIRECTORY)
    }

    @Deprecated("onActivityResult is deprecated but kept for parity with original behavior")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_CODE_OPEN_DIRECTORY && resultCode == RESULT_OK && data != null) {
            val uri = data.data
            if (uri != null) {
                folderUri = uri
                // safe non-null pass
                try {
                    folderUri?.let { u ->
                        contentResolver.takePersistableUriPermission(
                            u,
                            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                        )
                    }
                } catch (_: SecurityException) { }
                // Save chosen folder URI to SharedPreferences (same key as ChatActivity reads)
                prefs.edit().putString(PREF_KEY_FOLDER_URI, folderUri.toString()).apply()

                Toast.makeText(this, "Папка выбрана", Toast.LENGTH_SHORT).show()
                loadTemplatesFromFile("base.txt")
            } else {
                Toast.makeText(this, "Ошибка: папка не выбрана", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun saveTemplatesToFile(filename: String, content: String) {
        try {
            val dir = DocumentFile.fromTreeUri(this, folderUri)
            if (dir == null || !dir.exists() || !dir.isDirectory) {
                Toast.makeText(this, "Ошибка: папка недоступна", Toast.LENGTH_SHORT).show()
                return
            }
            var file = dir.findFile(filename)
            if (file == null) file = dir.createFile("text/plain", filename)
            if (file == null) {
                Toast.makeText(this, "Ошибка создания файла", Toast.LENGTH_SHORT).show()
                return
            }
            contentResolver.openFileDescriptor(file.uri, "w")?.use { pfd: ParcelFileDescriptor ->
                FileOutputStream(pfd.fileDescriptor).use { fos ->
                    fos.write(content.toByteArray())
                }
            }
            Toast.makeText(this, "Сохранено успешно", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(this, "Ошибка сохранения: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun loadTemplatesFromFile(filename: String) {
        val sb = StringBuilder()
        try {
            val dir = DocumentFile.fromTreeUri(this, folderUri)
            if (dir == null || !dir.exists() || !dir.isDirectory) {
                Toast.makeText(this, "Ошибка: папка недоступна", Toast.LENGTH_SHORT).show()
                templatesInput.setText("")
                return
            }
            val file = dir.findFile(filename)
            if (file == null || !file.exists()) {
                templatesInput.setText("")
                return // файл может отсутствовать
            }
            contentResolver.openFileDescriptor(file.uri, "r")?.use { pfd: ParcelFileDescriptor ->
                FileInputStream(pfd.fileDescriptor).use { fis ->
                    BufferedReader(InputStreamReader(fis)).use { reader ->
                        var line: String?
                        while (true) {
                            line = reader.readLine() ?: break
                            if (sb.isNotEmpty()) sb.append("\n")
                            sb.append(line)
                        }
                        val content = sb.toString()
                        templatesInput.setText(content)
                    }
                }
            }
        } catch (e: Exception) {
            Toast.makeText(this, "Ошибка загрузки: ${e.message}", Toast.LENGTH_SHORT).show()
            templatesInput.setText("")
        }
    }
}
