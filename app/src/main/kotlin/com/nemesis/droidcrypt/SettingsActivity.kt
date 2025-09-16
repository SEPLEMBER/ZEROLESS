package com.nemesis.droidcrypt

import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.os.Bundle
import android.preference.PreferenceManager
import android.util.Log
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.documentfile.provider.DocumentFile
import java.io.*

class SettingsActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "SettingsActivity"
        private const val REQUEST_CODE_OPEN_DIRECTORY = 1
        private const val PREF_KEY_FOLDER_URI = "pref_folder_uri"
        private const val PREF_KEY_DISABLE_SCREENSHOTS = "pref_disable_screenshots"
        private const val BASE_TEMPLATES_FILE = "base.txt"
    }

    // UI элементы
    private lateinit var selectFolderButton: Button
    private lateinit var clearTemplatesButton: Button
    private lateinit var backButton: Button
    private lateinit var saveTemplatesButton: Button
    private lateinit var templatesInput: EditText
    private lateinit var disableScreenshotsSwitch: Switch
    private lateinit var folderStatusText: TextView

    // Данные
    private var folderUri: Uri? = null
    private lateinit var prefs: SharedPreferences

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        try {
            setContentView(R.layout.activity_settings)
            initializeComponents()
            setupEventListeners()
            loadSavedSettings()
        } catch (e: Exception) {
            Log.e(TAG, "Error in onCreate", e)
            showError("Ошибка инициализации настроек")
            finish()
        }
    }

    private fun initializeComponents() {
        prefs = PreferenceManager.getDefaultSharedPreferences(this)
        
        // Инициализация UI элементов
        selectFolderButton = findViewById(R.id.selectFolderButton)
        clearTemplatesButton = findViewById(R.id.clearTemplatesButton)
        backButton = findViewById(R.id.backButton)
        saveTemplatesButton = findViewById(R.id.saveTemplatesButton)
        templatesInput = findViewById(R.id.templatesInput)
        disableScreenshotsSwitch = findViewById(R.id.disableScreenshotsSwitch)
        
        // Пытаемся найти элемент для отображения статуса папки (если есть в layout)
        folderStatusText = findViewById<TextView?>(R.id.folderStatusText) ?: run {
            // Создаем TextView программно, если его нет в layout
            TextView(this).apply {
                text = "Папка не выбрана"
                textSize = 12f
            }
        }
        
        // Очищаем поле ввода
        templatesInput.setText("")
    }

    private fun setupEventListeners() {
        selectFolderButton.setOnClickListener { 
            openFolderPicker() 
        }

        clearTemplatesButton.setOnClickListener {
            clearTemplates()
        }

        saveTemplatesButton.setOnClickListener {
            saveTemplates()
        }

        disableScreenshotsSwitch.setOnCheckedChangeListener { _, isChecked ->
            handleScreenshotToggle(isChecked)
        }

        backButton.setOnClickListener {
            finishWithResult()
        }
    }

    private fun loadSavedSettings() {
        // Загружаем настройку скриншотов
        disableScreenshotsSwitch.isChecked = prefs.getBoolean(PREF_KEY_DISABLE_SCREENSHOTS, false)

        // Восстанавливаем URI папки
        val savedUri = prefs.getString(PREF_KEY_FOLDER_URI, null)
        if (!savedUri.isNullOrEmpty()) {
            restoreFolderUri(savedUri)
        } else {
            updateFolderStatus("Папка не выбрана")
        }
    }

    private fun restoreFolderUri(savedUri: String) {
        try {
            folderUri = Uri.parse(savedUri)
            folderUri?.let { uri ->
                if (validateAndRequestPermissions(uri)) {
                    updateFolderStatus("Папка: ${uri.lastPathSegment ?: "Выбрана"}")
                    loadTemplatesFromFile(BASE_TEMPLATES_FILE)
                } else {
                    folderUri = null
                    updateFolderStatus("Папка недоступна")
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error restoring folder URI", e)
            folderUri = null
            updateFolderStatus("Ошибка восстановления папки")
        }
    }

    private fun validateAndRequestPermissions(uri: Uri): Boolean {
        return try {
            contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
            true
        } catch (e: SecurityException) {
            Log.w(TAG, "Cannot take persistable URI permission", e)
            false
        }
    }

    private fun openFolderPicker() {
        try {
            val intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
                addFlags(Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
            }
            startActivityForResult(intent, REQUEST_CODE_OPEN_DIRECTORY)
        } catch (e: Exception) {
            Log.e(TAG, "Error opening folder picker", e)
            showError("Не удалось открыть выбор папки")
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        
        if (requestCode == REQUEST_CODE_OPEN_DIRECTORY && resultCode == RESULT_OK) {
            handleFolderSelection(data)
        }
    }

    private fun handleFolderSelection(data: Intent?) {
        val uri = data?.data
        
        if (uri == null) {
            showError("Ошибка: папка не выбрана")
            return
        }

        try {
            folderUri = uri
            
            if (validateAndRequestPermissions(uri)) {
                // Сохраняем URI в настройках
                prefs.edit()
                    .putString(PREF_KEY_FOLDER_URI, folderUri.toString())
                    .apply()
                
                updateFolderStatus("Папка: ${uri.lastPathSegment ?: "Выбрана"}")
                showSuccess("Папка выбрана")
                loadTemplatesFromFile(BASE_TEMPLATES_FILE)
            } else {
                folderUri = null
                updateFolderStatus("Нет прав доступа к папке")
                showError("Нет прав доступа к выбранной папке")
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "Error handling folder selection", e)
            folderUri = null
            updateFolderStatus("Ошибка выбора папки")
            showError("Ошибка при выборе папки")
        }
    }

    private fun clearTemplates() {
        templatesInput.setText("")
        showSuccess("Поле очищено")
    }

    private fun saveTemplates() {
        if (folderUri == null) {
            showError("Сначала выберите папку")
            return
        }

        val content = templatesInput.text.toString()
        
        if (content.trim().isEmpty()) {
            saveTemplatesToFile(BASE_TEMPLATES_FILE, "")
            showSuccess("Шаблоны очищены")
            return
        }

        val processedContent = processTemplatesContent(content)
        val stats = getProcessingStats(content, processedContent)
        
        saveTemplatesToFile(BASE_TEMPLATES_FILE, processedContent)
        showSuccess("Сохранено: ${stats.first}, пропущено: ${stats.second}")
    }

    private fun processTemplatesContent(content: String): String {
        val lines = content.split("\r?\n".toRegex())
        val processedLines = mutableListOf<String>()
        
        for (rawLine in lines) {
            val line = rawLine.trim()
            
            if (line.isEmpty() || !line.contains("=")) {
                continue
            }
            
            val parts = line.split("=", limit = 2)
            val key = parts[0].trim().lowercase()
            val value = if (parts.size > 1) parts[1].trim() else ""
            
            if (key.isNotEmpty()) {
                processedLines.add("$key=$value")
            }
        }
        
        return processedLines.joinToString("\n")
    }

    private fun getProcessingStats(original: String, processed: String): Pair<Int, Int> {
        val originalLines = original.split("\r?\n".toRegex()).count { it.trim().isNotEmpty() }
        val processedLines = processed.split("\n").size
        val skipped = originalLines - processedLines
        
        return Pair(processedLines, maxOf(0, skipped))
    }

    private fun handleScreenshotToggle(isChecked: Boolean) {
        prefs.edit()
            .putBoolean(PREF_KEY_DISABLE_SCREENSHOTS, isChecked)
            .apply()
        
        val message = if (isChecked) "Скриншоты запрещены" else "Скриншоты разрешены"
        showSuccess(message)
    }

    private fun finishWithResult() {
        try {
            // Сохраняем текущие настройки
            prefs.edit()
                .putBoolean(PREF_KEY_DISABLE_SCREENSHOTS, disableScreenshotsSwitch.isChecked)
                .apply()

            // Возвращаем результат для ChatActivity
            val resultIntent = Intent().apply {
                putExtra("folderUri", folderUri?.toString())
                putExtra("disableScreenshots", disableScreenshotsSwitch.isChecked)
            }
            
            setResult(RESULT_OK, resultIntent)
            finish()
            
        } catch (e: Exception) {
            Log.e(TAG, "Error finishing with result", e)
            finish()
        }
    }

    private fun saveTemplatesToFile(filename: String, content: String) {
        if (folderUri == null) {
            showError("Папка не выбрана")
            return
        }

        try {
            val dir = DocumentFile.fromTreeUri(this, folderUri!!)
            
            if (dir == null || !dir.exists() || !dir.isDirectory) {
                showError("Ошибка: папка недоступна")
                return
            }

            var file = dir.findFile(filename)
            if (file == null) {
                file = dir.createFile("text/plain", filename)
            }
            
            if (file == null) {
                showError("Ошибка создания файла")
                return
            }

            contentResolver.openFileDescriptor(file.uri, "w")?.use { pfd ->
                FileOutputStream(pfd.fileDescriptor).use { fos ->
                    OutputStreamWriter(fos, Charsets.UTF_8).use { writer ->
                        writer.write(content)
                    }
                }
            }

        } catch (e: Exception) {
            Log.e(TAG, "Error saving templates", e)
            showError("Ошибка сохранения: ${e.message}")
        }
    }

    private fun loadTemplatesFromFile(filename: String) {
        if (folderUri == null) {
            templatesInput.setText("")
            return
        }

        try {
            val dir = DocumentFile.fromTreeUri(this, folderUri!!)
            
            if (dir == null || !dir.exists() || !dir.isDirectory) {
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
                    InputStreamReader(fis, Charsets.UTF_8).use { reader ->
                        BufferedReader(reader).use { bufferedReader ->
                            val content = bufferedReader.readText()
                            templatesInput.setText(content)
                        }
                    }
                }
            }

        } catch (e: Exception) {
            Log.e(TAG, "Error loading templates", e)
            showError("Ошибка загрузки: ${e.message}")
            templatesInput.setText("")
        }
    }

    private fun updateFolderStatus(status: String) {
        folderStatusText.text = status
    }

    private fun showSuccess(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    private fun showError(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }
}
