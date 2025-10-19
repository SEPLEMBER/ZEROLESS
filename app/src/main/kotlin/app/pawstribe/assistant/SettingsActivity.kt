package app.pawstribe.assistant

import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.os.Bundle
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.preference.PreferenceManager
import com.google.android.material.button.MaterialButton
import com.google.android.material.switchmaterial.SwitchMaterial
import com.google.android.material.textfield.TextInputEditText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class SettingsActivity : AppCompatActivity() {
    companion object {
        private const val PREF_KEY_FOLDER_URI = "folderUri"
        private const val PREF_KEY_DISABLE_SCREENSHOTS = "disableScreenshots"
        private const val PREF_ENCRYPTION_PASSWORD = "pref_encryption_password"
    }

    private var folderUri: Uri? = null
    private lateinit var selectFolderButton: MaterialButton
    private lateinit var setupButton: MaterialButton
    private lateinit var backButton: MaterialButton
    private lateinit var disableScreenshotsSwitch: SwitchMaterial
    private lateinit var prefs: SharedPreferences

    // New UI elements
    private lateinit var passwordEditText: TextInputEditText
    private lateinit var savePassButton: MaterialButton

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

        // hide statusbar
        window.setFlags(
            WindowManager.LayoutParams.FLAG_FULLSCREEN,
            WindowManager.LayoutParams.FLAG_FULLSCREEN
        )

        prefs = getSharedPreferences("PawsTribePrefs", MODE_PRIVATE)

        // find views
        disableScreenshotsSwitch = findViewById(R.id.disableScreenshotsSwitch)

        // NEW: password field and save button
        passwordEditText = findViewById(R.id.passwordEditText)
        savePassButton = findViewById(R.id.savePassButton)

        selectFolderButton = findViewById(R.id.selectFolderButton)
        setupButton = findViewById(R.id.setupButton)
        backButton = findViewById(R.id.backButton)

        // Load default shared prefs password (if any) into field
        val defaultPrefs = PreferenceManager.getDefaultSharedPreferences(this)
        val savedPass = defaultPrefs.getString(PREF_ENCRYPTION_PASSWORD, "")
        passwordEditText.setText(savedPass)

        if (prefs.getBoolean(PREF_KEY_DISABLE_SCREENSHOTS, false)) {
            window.setFlags(
                WindowManager.LayoutParams.FLAG_SECURE,
                WindowManager.LayoutParams.FLAG_SECURE
            )
        }

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

        // Save password to default SharedPreferences when the save button is clicked
        savePassButton.setOnClickListener {
            val entered = passwordEditText.text?.toString() ?: ""
            PreferenceManager.getDefaultSharedPreferences(this)
                .edit()
                .putString(PREF_ENCRYPTION_PASSWORD, entered)
                .apply()

            // hide keyboard if open
            val imm = getSystemService(INPUT_METHOD_MANAGER) as? InputMethodManager
            imm?.hideSoftInputFromWindow(passwordEditText.windowToken, 0)

            Toast.makeText(this, R.string.password_saved, Toast.LENGTH_SHORT).show()
        }

        // launch SetupActivity
        setupButton.setOnClickListener {
            val intent = Intent(this, SetupActivity::class.java)
            startActivity(intent)
        }

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
