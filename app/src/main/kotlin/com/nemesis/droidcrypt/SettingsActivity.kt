package com.nemesis.droidcrypt

import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.os.Bundle
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import com.google.android.material.switchmaterial.SwitchMaterial
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class SettingsActivity : AppCompatActivity() {
    companion object {
        private const val PREF_KEY_FOLDER_URI = "folderUri"
        private const val PREF_KEY_DISABLE_SCREENSHOTS = "disableScreenshots"
    }

    private var folderUri: Uri? = null
    private lateinit var selectFolderButton: MaterialButton
    private lateinit var backButton: MaterialButton
    private lateinit var disableScreenshotsSwitch: SwitchMaterial
    private lateinit var prefs: SharedPreferences

    private val folderPickerLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            lifecycleScope.launch {
                if (result.resultCode == RESULT_OK) {
                    result.data?.data?.let { uri ->
                        folderUri = uri
                        try {
                            contentResolver.takePersistableUriPermission(
                                uri,
                                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                            )
                            prefs.edit().putString(PREF_KEY_FOLDER_URI, uri.toString()).apply()
                            withContext(Dispatchers.Main) {
                                Toast.makeText(this@SettingsActivity, R.string.folder_selected, Toast.LENGTH_SHORT).show()
                            }
                        } catch (e: SecurityException) {
                            withContext(Dispatchers.Main) {
                                Toast.makeText(this@SettingsActivity, R.string.error_no_permission, Toast.LENGTH_SHORT).show()
                            }
                        }
                    } ?: withContext(Dispatchers.Main) {
                        Toast.makeText(this@SettingsActivity, R.string.error_no_folder_selected, Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        // Скрытие статус-бара
        window.setFlags(
            WindowManager.LayoutParams.FLAG_FULLSCREEN,
            WindowManager.LayoutParams.FLAG_FULLSCREEN
        )

        prefs = getSharedPreferences("PawsTribePrefs", MODE_PRIVATE)
        selectFolderButton = findViewById(R.id.selectFolderButton)
        backButton = findViewById(R.id.backButton)
        disableScreenshotsSwitch = findViewById(R.id.disableScreenshotsSwitch)

        // Применяем FLAG_SECURE, если скриншоты запрещены
        if (prefs.getBoolean(PREF_KEY_DISABLE_SCREENSHOTS, false)) {
            window.setFlags(
                WindowManager.LayoutParams.FLAG_SECURE,
                WindowManager.LayoutParams.FLAG_SECURE
            )
        }

        // Восстанавливаем настройки
        disableScreenshotsSwitch.isChecked = prefs.getBoolean(PREF_KEY_DISABLE_SCREENSHOTS, false)
        prefs.getString(PREF_KEY_FOLDER_URI, null)?.let { saved: String ->
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

        selectFolderButton.setOnClickListener { openFolderPicker() }

        disableScreenshotsSwitch.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean(PREF_KEY_DISABLE_SCREENSHOTS, isChecked).apply()
            if (isChecked) {
                window.setFlags(
                    WindowManager.LayoutParams.FLAG_SECURE,
                    WindowManager.LayoutParams.FLAG_SECURE
                )
                Toast.makeText(this, R.string.screenshots_disabled, Toast.LENGTH_SHORT).show()
            } else {
                window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
                Toast.makeText(this, R.string.screenshots_enabled, Toast.LENGTH_SHORT).show()
            }
        }

        backButton.setOnClickListener {
            val intent = Intent(this, MainActivity::class.java)
            intent.putExtra("folderUri", folderUri)
            intent.putExtra("disableScreenshots", disableScreenshotsSwitch.isChecked)
            startActivity(intent)
            finish()
        }
    }

    private fun openFolderPicker() {
        lifecycleScope.launch {
            Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
                addFlags(Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
                folderPickerLauncher.launch(this)
            }
        }
    }
}
