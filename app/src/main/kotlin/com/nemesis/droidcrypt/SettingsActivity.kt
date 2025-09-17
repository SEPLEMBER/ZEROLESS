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
import com.nemesis.droidcrypt.utils.security.SecCoreUtils
import java.io.BufferedReader
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStreamReader

class SettingsActivity : AppCompatActivity() {

    companion object {
        private const val PREF_KEY_FOLDER_URI = "pref_folder_uri"
        private const val PREF_KEY_DISABLE_SCREENSHOTS = "pref_disable_screenshots"
        private const val PREF_KEY_MASTER_KEY = "pref_master_key"
    }

    private var folderUri: Uri? = null
    private lateinit var selectFolderButton: Button
    private lateinit var clearTemplatesButton: Button
    private lateinit var backButton: Button
    private lateinit var saveTemplatesButton: Button
    private lateinit var protectButton: Button
    private lateinit var unprotectButton: Button
    private lateinit var templatesInput: EditText
    private lateinit var masterKeyInput: EditText
    private lateinit var passwordInput: EditText
    private lateinit var disableScreenshotsSwitch: Switch
    private lateinit var prefs: SharedPreferences

    private val folderPickerLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == RESULT_OK) {
                result.data?.data?.let { uri ->
                    folderUri = uri
                    try {
                        contentResolver.takePersistableUriPermission(
                            uri,
                            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                        )
                        prefs.edit().putString(PREF_KEY_FOLDER_URI, uri.toString()).apply()
                        Toast.makeText(this, "Папка выбрана", Toast.LENGTH_SHORT).show()
                        loadTemplatesFromFile("base.txt")
                    } catch (e: SecurityException) {
                        Toast.makeText(this, "Ошибка доступа к папке", Toast.LENGTH_SHORT).show()
                    }
                } ?: Toast.makeText(this, "Ошибка: папка не выбрана", Toast.LENGTH_SHORT).show()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        prefs = getSharedPreferences("my_prefs", MODE_PRIVATE)

        selectFolderButton = findViewById(R.id.selectFolderButton)
        clearTemplatesButton = findViewById(R.id.clearTemplatesButton)
        backButton = findViewById(R.id.backButton)
        saveTemplatesButton = findViewById(R.id.saveTemplatesButton)
        protectButton = findViewById(R.id.protectButton)
        unprotectButton = findViewById(R.id.unprotectButton)
        templatesInput = findViewById(R.id.templatesInput)
        masterKeyInput = findViewById(R.id.masterKeyInput)
        passwordInput = findViewById(R.id.passwordInput)
        disableScreenshotsSwitch = findViewById(R.id.disableScreenshotsSwitch)

        templatesInput.setText("")
        disableScreenshotsSwitch.isChecked = prefs.getBoolean(PREF_KEY_DISABLE_SCREENSHOTS, false)
        masterKeyInput.setText(prefs.getString(PREF_KEY_MASTER_KEY, ""))

        // Восстанавливаем сохранённый URI папки
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
                Toast.makeText(this, "Ошибка загрузки папки", Toast.LENGTH_SHORT).show()
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
            if (all.isBlank()) {
                saveTemplatesToFile("base.txt", "")
                Toast.makeText(this, "Шаблоны очищены", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            var savedCount = 0
            var skipped = 0
            val content = buildString {
                all.lines().forEach { raw ->
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
                    append(key.lowercase()).append('=').append(value)
                    savedCount++
                }
            }

            saveTemplatesToFile("base.txt", content)
            Toast.makeText(this, "Сохранено: $savedCount, пропущено: $skipped", Toast.LENGTH_SHORT).show()
        }

        protectButton.setOnClickListener {
            val masterKey = masterKeyInput.text.toString().trim()
            val password = passwordInput.text.toString().trim()

            if (masterKey.isEmpty() || password.isEmpty()) {
                Toast.makeText(this, "Введите ключ и пароль", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            prefs.edit().putString(PREF_KEY_MASTER_KEY, masterKey).apply()
            encryptTextFiles(masterKey, password)
            passwordInput.setText("") // Очищаем поле пароля
            Toast.makeText(this, "Файлы зашифрованы", Toast.LENGTH_SHORT).show()
        }

        unprotectButton.setOnClickListener {
            val password = passwordInput.text.toString().trim()
            if (password.isEmpty()) {
                Toast.makeText(this, "Введите пароль", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            val masterKey = prefs.getString(PREF_KEY_MASTER_KEY, null)
            if (masterKey == null) {
                Toast.makeText(this, "Ключ не найден", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            if (decryptTextFiles(masterKey, password)) {
                SessionKeys.masterKey = masterKey.toByteArray()
                passwordInput.setText("") // Очищаем поле пароля
                Toast.makeText(this, "Файлы расшифрованы", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, "Ошибка расшифровки: неверный пароль", Toast.LENGTH_SHORT).show()
            }
        }

        disableScreenshotsSwitch.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean(PREF_KEY_DISABLE_SCREENSHOTS, isChecked).apply()
            Toast.makeText(
                this,
                if (isChecked) "Скриншоты запрещены" else "Скриншоты разрешены",
                Toast.LENGTH_SHORT
            ).show()
        }

        backButton.setOnClickListener {
            prefs.edit().putBoolean(PREF_KEY_DISABLE_SCREENSHOTS, disableScreenshotsSwitch.isChecked).apply()
            val masterKey = masterKeyInput.text.toString().trim()
            if (masterKey.isNotEmpty()) {
                prefs.edit().putString(PREF_KEY_MASTER_KEY, masterKey).apply()
            }

            val password = passwordInput.text.toString().trim()
            if (password.isNotEmpty()) {
                val storedMasterKey = prefs.getString(PREF_KEY_MASTER_KEY, null)
                if (storedMasterKey != null) {
                    try {
                        val derivedKey = SecCoreUtils.deriveDbKey(storedMasterKey.toByteArray(), password.toByteArray())
                        SessionKeys.masterKey = derivedKey
                        passwordInput.setText("") // Очищаем поле пароля
                        val intent = Intent(this, ChatActivity::class.java).apply {
                            putExtra("folderUri", folderUri)
                            putExtra("disableScreenshots", disableScreenshotsSwitch.isChecked)
                        }
                        startActivity(intent)
                        finish()
                    } catch (e: Exception) {
                        Toast.makeText(this, "Ошибка расшифровки: неверный пароль", Toast.LENGTH_SHORT).show()
                        startMainActivity()
                    }
                } else {
                    Toast.makeText(this, "Ключ не задан", Toast.LENGTH_SHORT).show()
                    startMainActivity()
                }
            } else {
                startMainActivity()
            }
        }
    }

    private fun startMainActivity() {
        val intent = Intent(this, MainActivity::class.java).apply {
            putExtra("folderUri", folderUri)
            putExtra("disableScreenshots", disableScreenshotsSwitch.isChecked)
        }
        startActivity(intent)
        finish()
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

    private fun encryptTextFiles(masterKey: String, password: String) {
        try {
            val dir = DocumentFile.fromTreeUri(this, folderUri!!)
            if (dir == null || !dir.exists() || !dir.isDirectory) {
                Toast.makeText(this, "Ошибка: папка недоступна", Toast.LENGTH_SHORT).show()
                return
            }
            dir.listFiles().filter { it.name?.endsWith(".txt") == true }.forEach { file ->
                try {
                    contentResolver.openInputStream(file.uri)?.use { input ->
                        val content = input.readBytes()
                        val salt = file.name!!.toByteArray()
                        val derivedKey = SecCoreUtils.deriveDbKey(masterKey.toByteArray(), salt)
                        val encryptedContent = SecCoreUtils.encryptAesGcm(content, derivedKey)
                        SecCoreUtils.wipe(derivedKey)
                        contentResolver.openFileDescriptor(file.uri, "w")?.use { pfd ->
                            FileOutputStream(pfd.fileDescriptor).use { fos ->
                                fos.write(encryptedContent)
                            }
                        }
                    }
                } catch (e: Exception) {
                    Toast.makeText(this, "Ошибка шифрования файла ${file.name}", Toast.LENGTH_SHORT).show()
                }
            }
        } catch (e: Exception) {
            Toast.makeText(this, "Ошибка шифрования: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun decryptTextFiles(masterKey: String, password: String): Boolean {
        try {
            val dir = DocumentFile.fromTreeUri(this, folderUri!!)
            if (dir == null || !dir.exists() || !dir.isDirectory) {
                Toast.makeText(this, "Ошибка: папка недоступна", Toast.LENGTH_SHORT).show()
                return false
            }
            dir.listFiles().filter { it.name?.endsWith(".txt") == true }.forEach { file ->
                try {
                    contentResolver.openInputStream(file.uri)?.use { input ->
                        val encryptedContent = input.readBytes()
                        val salt = file.name!!.toByteArray()
                        val derivedKey = SecCoreUtils.deriveDbKey(masterKey.toByteArray(), salt)
                        val decryptedContent = SecCoreUtils.decryptAesGcm(encryptedContent, derivedKey)
                        SecCoreUtils.wipe(derivedKey)
                        contentResolver.openFileDescriptor(file.uri, "w")?.use { pfd ->
                            FileOutputStream(pfd.fileDescriptor).use { fos ->
                                fos.write(decryptedContent)
                            }
                        }
                    }
                } catch (e: Exception) {
                    Toast.makeText(this, "Ошибка расшифровки файла ${file.name}", Toast.LENGTH_SHORT).show()
                    return false
                }
            }
            return true
        } catch (e: Exception) {
            Toast.makeText(this, "Ошибка расшифровки: ${e.message}", Toast.LENGTH_SHORT).show()
            return false
        }
    }
}
