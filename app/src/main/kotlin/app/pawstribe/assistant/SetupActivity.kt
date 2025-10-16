package app.pawstribe.assistant

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import com.google.android.material.progressindicator.CircularProgressIndicator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.InputStream
import java.io.OutputStream

class SetupActivity : AppCompatActivity() {
    private lateinit var russianButton: MaterialButton
    private lateinit var englishButton: MaterialButton
    private lateinit var progressBar: CircularProgressIndicator
    private var folderUri: Uri? = null
    private var selectedLanguage: String? = null

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
                        getSharedPreferences("PawsTribePrefs", MODE_PRIVATE)
                            .edit()
                            .putString("folderUri", uri.toString())
                            .apply()
                        copyAssetsToSafFolder(uri, selectedLanguage ?: "ru")
                    } catch (e: SecurityException) {
                        Toast.makeText(this, R.string.error_no_permission, Toast.LENGTH_SHORT).show()
                        progressBar.visibility = View.GONE
                        enableButtons(true)
                    }
                } ?: run {
                    Toast.makeText(this, R.string.error_no_folder_selected, Toast.LENGTH_SHORT).show()
                    progressBar.visibility = View.GONE
                    enableButtons(true)
                }
            } else {
                progressBar.visibility = View.GONE
                enableButtons(true)
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_setup)

        window.setFlags(
            android.view.WindowManager.LayoutParams.FLAG_FULLSCREEN,
            android.view.WindowManager.LayoutParams.FLAG_FULLSCREEN
        )

        russianButton = findViewById(R.id.russianButton)
        englishButton = findViewById(R.id.englishButton)
        progressBar = findViewById(R.id.progressBar)

        russianButton.setOnClickListener {
            selectedLanguage = "ru"
            enableButtons(false)
            progressBar.visibility = View.VISIBLE
            Toast.makeText(this, R.string.select_folder_instruction, Toast.LENGTH_SHORT).show()
            openFolderPicker()
        }

        englishButton.setOnClickListener {
            selectedLanguage = "en"
            enableButtons(false)
            progressBar.visibility = View.VISIBLE
            Toast.makeText(this, R.string.select_folder_instruction, Toast.LENGTH_SHORT).show()
            openFolderPicker()
        }
    }

    private fun enableButtons(enabled: Boolean) {
        russianButton.isEnabled = enabled
        englishButton.isEnabled = enabled
    }

    private fun openFolderPicker() {
        Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            addFlags(Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
            folderPickerLauncher.launch(this)
        }
    }

    private fun copyAssetsToSafFolder(folderUri: Uri, language: String) {
        lifecycleScope.launch {
            try {
                val documentFile = DocumentFile.fromTreeUri(this@SetupActivity, folderUri)
                if (documentFile == null || !documentFile.isDirectory) {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(this@SetupActivity, R.string.error_folder_unavailable, Toast.LENGTH_SHORT).show()
                        progressBar.visibility = View.GONE
                        enableButtons(true)
                    }
                    return@launch
                }

                val assetFiles = assets.list(language) ?: emptyArray()
                if (assetFiles.isEmpty()) {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(this@SetupActivity, R.string.no_supported_files_in_assets, Toast.LENGTH_SHORT).show()
                        progressBar.visibility = View.GONE
                        enableButtons(true)
                    }
                    return@launch
                }

                for (fileName in assetFiles) {
                    val inputStream: InputStream = assets.open("$language/$fileName")
                    val newFile = documentFile.createFile("application/octet-stream", fileName)

                    if (newFile != null) {
                        contentResolver.openOutputStream(newFile.uri)?.use { outputStream: OutputStream ->
                            inputStream.copyTo(outputStream)
                        }
                    }
                    inputStream.close()
                }

                withContext(Dispatchers.Main) {
                    Toast.makeText(
                        this@SetupActivity,
                        R.string.files_copied_successfully,
                        Toast.LENGTH_LONG
                    ).show()
                    finish()
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@SetupActivity, getString(R.string.error_copying_files, e.message), Toast.LENGTH_LONG).show()
                    progressBar.visibility = View.GONE
                    enableButtons(true)
                }
            }
        }
    }
}
