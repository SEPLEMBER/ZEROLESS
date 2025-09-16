package com.nemesis.droidcrypt

import android.app.Activity
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.os.Bundle
import android.preference.PreferenceManager
import android.util.Log
import android.widget.*
import androidx.appcompat.app.AppCompatActivity

class SettingsActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "SettingsActivity"
        private const val REQUEST_CODE_OPEN_DIRECTORY = 1
        private const val PREF_KEY_FOLDER_URI = "pref_folder_uri"
        private const val PREF_KEY_DISABLE_SCREENSHOTS = "pref_disable_screenshots"
    }

    private lateinit var selectFolderButton: Button
    private lateinit var backButton: Button
    private lateinit var disableScreenshotsSwitch: Switch
    private lateinit var folderStatusText: TextView
    private lateinit var prefs: SharedPreferences

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        prefs = PreferenceManager.getDefaultSharedPreferences(this)

        selectFolderButton = findViewById(R.id.selectFolderButton)
        backButton = findViewById(R.id.backButton)
        disableScreenshotsSwitch = findViewById(R.id.disableScreenshotsSwitch)
        folderStatusText = findViewById(R.id.folderStatusText)

        // Загружаем настройки
        disableScreenshotsSwitch.isChecked = prefs.getBoolean(PREF_KEY_DISABLE_SCREENSHOTS, false)
        val savedUri = prefs.getString(PREF_KEY_FOLDER_URI, null)
        folderStatusText.text = if (!savedUri.isNullOrEmpty()) "Папка: ${Uri.parse(savedUri).lastPathSegment}" else "Папка не выбрана"

        selectFolderButton.setOnClickListener { openFolderPicker() }
        backButton.setOnClickListener { finishWithResult() }

        disableScreenshotsSwitch.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean(PREF_KEY_DISABLE_SCREENSHOTS, isChecked).apply()
            val message = if (isChecked) "Скриншоты запрещены" else "Скриншоты разрешены"
            Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
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
            Toast.makeText(this, "Не удалось открыть выбор папки", Toast.LENGTH_LONG).show()
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_CODE_OPEN_DIRECTORY && resultCode == Activity.RESULT_OK) {
            val uri = data?.data
            if (uri != null) {
                try {
                    contentResolver.takePersistableUriPermission(
                        uri,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                    )
                    prefs.edit().putString(PREF_KEY_FOLDER_URI, uri.toString()).apply()
                    folderStatusText.text = "Папка: ${uri.lastPathSegment ?: "Выбрана"}"
                    Toast.makeText(this, "Папка выбрана", Toast.LENGTH_SHORT).show()
                } catch (e: Exception) {
                    Log.e(TAG, "Cannot take persistable URI permission", e)
                    Toast.makeText(this, "Нет доступа к папке", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun finishWithResult() {
        val resultIntent = Intent().apply {
            putExtra("folderUri", prefs.getString(PREF_KEY_FOLDER_URI, null))
            putExtra("disableScreenshots", disableScreenshotsSwitch.isChecked)
        }
        setResult(Activity.RESULT_OK, resultIntent)
        finish()
    }
}
