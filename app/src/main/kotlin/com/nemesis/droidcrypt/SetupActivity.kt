package com.nemesis.droidcrypt

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

    companion object {
        private const val PREF_KEY_FOLDER_URI = "pref_folder_uri"
    }

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
                        getSharedPreferences("my_prefs", MODE_PRIVATE)
                            .edit()
                            .putString(PREF_KEY_FOLDER_URI, uri.toString())
                            .apply()
                        copyAssetsToSafFolder(uri)
                    } catch (e: SecurityException) {
                        Toast.makeText(this, "Ошибка: не удалось получить права доступа", Toast.LENGTH_SHORT).show()
                        progressBar.visibility = View.GONE
                        selectFolderButton.isEnabled = true
                    }
                } ?: run {
                    Toast.makeText(this, "Ошибка: папка не выбрана", Toast.LENGTH_SHORT).show()
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

        // Проверяем, есть ли сохранённый URI
        getSharedPreferences("my_prefs", MODE_PRIVATE)
            .getString(PREF_KEY_FOLDER_URI, null)?.let { saved ->
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
                Toast.makeText(this, "Ошибка: Папка недоступна", Toast.LENGTH_SHORT).show()
                progressBar.visibility = View.GONE
                selectFolderButton.isEnabled = true
                return
            }

            // Копируем все файлы из assets (фильтруем по .txt, можно изменить)
            val assetFiles = assets.list("")?.filter { it.endsWith(".txt") } ?: emptyList()

            if (assetFiles.isEmpty()) {
                Toast.makeText(this, "В assets нет текстовых файлов", Toast.LENGTH_SHORT).show()
                progressBar.visibility = View.GONE
                selectFolderButton.isEnabled = true
                return
            }

                for (fileName in assetFiles) {
                val inputStream: InputStream = assets.open(fileName)
                val newFile = documentFile.createFile("text/plain", fileName)

                if (newFile != null) {
                    contentResolver.openOutputStream(newFile.uri)?.use { outputStream: OutputStream ->
                        inputStream.copyTo(outputStream)
                    }
                }
                inputStream.close()
            }

            Toast.makeText(this, "Файлы успешно скопированы!", Toast.LENGTH_LONG).show()
            setResult(RESULT_OK, Intent().apply {
                putExtra("folderUri", folderUri)
            })
            finish()
        } catch (e: Exception) {
            e.printStackTrace()
            Toast.makeText(this, "Ошибка при копировании: ${e.message}", Toast.LENGTH_LONG).show()
            progressBar.visibility = View.GONE
            selectFolderButton.isEnabled = true
        }
    }
}
