package com.nemesis.droidcrypt

import com.nemesis.droidcrypt.R
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.ProgressBar
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.documentfile.provider.DocumentFile
import java.io.InputStream
import java.io.OutputStream

class SetupActivity : AppCompatActivity() {

    private lateinit var selectFolderButton: Button
    private lateinit var progressBar: ProgressBar
    private var folderUri: Uri? = null

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
                        copyAssetsToSafFolder(uri)
                    } catch (e: SecurityException) {
                        Toast.makeText(this, R.string.error_no_permission, Toast.LENGTH_SHORT).show()
                        progressBar.visibility = View.GONE
                        selectFolderButton.isEnabled = true
                    }
                } ?: run {
                    Toast.makeText(this, R.string.error_no_folder_selected, Toast.LENGTH_SHORT).show()
                    progressBar.visibility = View.GONE
                    selectFolderButton.isEnabled = true
                }
            } else {
                progressBar.visibility = View.GONE
                selectFolderButton.isEnabled = true
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_setup)

        selectFolderButton = findViewById(R.id.selectFolderButton)
        progressBar = findViewById(R.id.progressBar)

        selectFolderButton.setOnClickListener {
            selectFolderButton.isEnabled = false
            progressBar.visibility = View.VISIBLE
            openFolderPicker()
        }
    }

    private fun openFolderPicker() {
        Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            addFlags(Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
            folderPickerLauncher.launch(this)
        }
    }

    private fun copyAssetsToSafFolder(folderUri: Uri) {
        try {
            val documentFile = DocumentFile.fromTreeUri(this, folderUri)
            if (documentFile == null || !documentFile.isDirectory) {
                Toast.makeText(this, R.string.error_folder_unavailable, Toast.LENGTH_SHORT).show()
                progressBar.visibility = View.GONE
                selectFolderButton.isEnabled = true
                return
            }

            // Копируем файлы из assets (.txt, .png, .ogg)
            val assetFiles = assets.list("")?.filter {
                it.endsWith(".txt", true) || it.endsWith(".png", true) || it.endsWith(".ogg", true)
            } ?: emptyList()

            if (assetFiles.isEmpty()) {
                Toast.makeText(this, R.string.no_supported_files_in_assets, Toast.LENGTH_SHORT).show()
                progressBar.visibility = View.GONE
                selectFolderButton.isEnabled = true
                return
            }

            for (fileName in assetFiles) {
                val inputStream: InputStream = assets.open(fileName)
                val mimeType = when {
                    fileName.endsWith(".txt", true) -> "text/plain"
                    fileName.endsWith(".png", true) -> "image/png"
                    fileName.endsWith(".ogg", true) -> "audio/ogg"
                    else -> "application/octet-stream" // Fallback, не должен использоваться
                }
                val newFile = documentFile.createFile(mimeType, fileName)

                if (newFile != null) {
                    contentResolver.openOutputStream(newFile.uri)?.use { outputStream: OutputStream ->
                        inputStream.copyTo(outputStream)
                    }
                }
                inputStream.close()
            }

            // Показываем инструкцию пользователю
            Toast.makeText(
                this,
                R.string.files_copied_successfully,
                Toast.LENGTH_LONG
            ).show()
            finish()
        } catch (e: Exception) {
            e.printStackTrace()
            Toast.makeText(this, getString(R.string.error_copying_files, e.message), Toast.LENGTH_LONG).show()
            progressBar.visibility = View.GONE
            selectFolderButton.isEnabled = true
        }
    }
}
