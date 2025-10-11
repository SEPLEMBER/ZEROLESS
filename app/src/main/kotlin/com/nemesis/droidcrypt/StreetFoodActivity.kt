package com.nemesis.droidcrypt

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.DocumentsContract
import android.view.View
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.ActivityResult
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

class StreetFoodActivity : AppCompatActivity() {

    companion object {
        private const val PREFS = "Pawstribe_prefs"
        private const val KEY_PIN = "pin_code"
        private const val DEFAULT_PIN = "5387"
        private const val CLICK_INTERVAL = 500L // ms for triple click
    }

    private lateinit var dots: Array<TextView>
    private lateinit var errorText: TextView
    private lateinit var progressBar: ProgressBar
    private lateinit var progressText: TextView
    private lateinit var panicButton: TextView
    private lateinit var keypad: LinearLayout
    private lateinit var centerColumn: LinearLayout

    private var currentPin = ""
    private lateinit var storedPin: String
    private var isChangingPin = false

    private lateinit var safeDir: File
    private var lastPanicClickTime = 0L
    private var panicClickCount = 0

    private enum class ConflictAction {
        SKIP, SKIP_ALL, MERGE, OVERWRITE, OVERWRITE_ALL
    }

    private var globalAction: ConflictAction? = null

    private val safLauncher: ActivityResultLauncher<Intent> = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result: ActivityResult ->
        if (result.resultCode == RESULT_OK && result.data != null) {
            val uri = result.data?.data
            if (uri != null) {
                contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
                importFromSaf(uri)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_street_food)

        // Инициализация UI элементов
        dots = arrayOf(
            findViewById(R.id.d1),
            findViewById(R.id.d2),
            findViewById(R.id.d3),
            findViewById(R.id.d4)
        )
        errorText = findViewById(R.id.errorText)
        progressBar = findViewById(R.id.progressBar) // Нужно добавить в XML
        progressText = findViewById(R.id.progressText) // Нужно добавить в XML
        panicButton = findViewById(R.id.btnPanic) // Нужно добавить в XML как TextView
        keypad = findViewById(R.id.keypad)
        centerColumn = findViewById(R.id.centerColumn)

        // Убрать центральный текст, если был (centerText в старом коде)
        // Предполагаем, что его нет в обновлённом XML

        // Настройка темы: neon cyan для текста
        val neonCyan = 0xFF00FFFF // #00FFFF
        dots.forEach { it.setTextColor(neonCyan) }
        errorText.setTextColor(0xFFFF5252) // Красный для ошибок
        panicButton.setTextColor(neonCyan)
        panicButton.text = "PANIC"
        panicButton.setOnClickListener { onPanicClick(it) }

        // Настройка клавиатуры: TextView вместо Button
        setupKeypadListeners()

        // Получить приватную папку на SD
        val externalDirs = getExternalFilesDirs(null)
        if (externalDirs.size > 1 && externalDirs[1] != null) {
            safeDir = File(externalDirs[1], "safe")
            safeDir.mkdirs()
        } else {
            Toast.makeText(this, "SD-карта не найдена", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        // Загрузить сохранённый PIN
        val prefs = getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        storedPin = prefs.getString(KEY_PIN, DEFAULT_PIN)!!

        // Показать клавиатуру для ввода PIN
        showPinEntry()
    }

    private fun setupKeypadListeners() {
        val btnIds = intArrayOf(R.id.btn1, R.id.btn2, R.id.btn3, R.id.btn4, R.id.btn5, R.id.btn6, R.id.btn7, R.id.btn8, R.id.btn9, R.id.btn0, R.id.btnDelete)
        for (id in btnIds) {
            val btn = findViewById<TextView>(id)
            btn.setTextColor(0xFF00FFFF) // neon cyan
            btn.setOnClickListener { v -> onKeypadClick(btn.text.toString()) }
        }
    }

    private fun onKeypadClick(key: String) {
        if (key == "⌫") {
            if (currentPin.isNotEmpty()) {
                currentPin = currentPin.substring(0, currentPin.length - 1)
            }
        } else {
            if (currentPin.length < 4) {
                currentPin += key
            }
        }
        updateDots()

        if (currentPin.length == 4) {
            if (isChangingPin) {
                // Смена PIN
                val prefs = getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                prefs.edit().putString(KEY_PIN, currentPin).apply()
                storedPin = currentPin
                Toast.makeText(this, "PIN изменён", Toast.LENGTH_SHORT).show()
                isChangingPin = false
                showSafeUI()
            } else {
                // Проверка PIN
                if (currentPin == storedPin) {
                    showSafeUI()
                } else {
                    errorText.visibility = View.VISIBLE
                    currentPin = ""
                    updateDots()
                    Handler(Looper.getMainLooper()).postDelayed({
                        errorText.visibility = View.GONE
                    }, 2000)
                }
            }
        }
    }

    private fun updateDots() {
        for (i in 0 until 4) {
            dots[i].text = "°" // Всегда °, но можно изменить для заполненных
        }
    }

    private fun showPinEntry() {
        centerColumn.visibility = View.VISIBLE
        keypad.visibility = View.VISIBLE
        panicButton.visibility = View.GONE // PANIC после входа
        progressBar.visibility = View.GONE
        progressText.visibility = View.GONE
        currentPin = ""
        updateDots()
        errorText.visibility = View.GONE
    }

    private fun showSafeUI() {
        centerColumn.visibility = View.GONE
        keypad.visibility = View.GONE
        panicButton.visibility = View.VISIBLE
        // Здесь добавить UI для сейфа, например, кнопку "Импорт"
        findViewById<View>(R.id.btnImport).visibility = View.VISIBLE // Нужно добавить в XML TextView btnImport
        findViewById<View>(R.id.btnImport).setOnClickListener { startSafImport() }
        // Для смены PIN добавить кнопку
        findViewById<View>(R.id.btnChangePin).visibility = View.VISIBLE // Нужно добавить
        findViewById<View>(R.id.btnChangePin).setOnClickListener {
            isChangingPin = true
            showPinEntry()
        }
    }

    private fun startSafImport() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE)
        safLauncher.launch(intent)
    }

    private fun importFromSaf(treeUri: Uri) {
        showProgress(true)
        lifecycleScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    val sourceDir = DocumentFile.fromTreeUri(this@StreetFoodActivity, treeUri)
                    if (sourceDir != null) {
                        copyRecursive(sourceDir, safeDir)
                    }
                }
                Toast.makeText(this@StreetFoodActivity, "Импорт завершён", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Toast.makeText(this@StreetFoodActivity, "Ошибка импорта: ${e.message}", Toast.LENGTH_SHORT).show()
            } finally {
                showProgress(false)
            }
        }
    }

    @Throws(IOException::class)
    private suspend fun copyRecursive(source: DocumentFile, targetDir: File) {
        if (source.isDirectory) {
            val newDir = File(targetDir, source.name ?: "")
            if (newDir.exists()) {
                val action = getConflictAction(newDir.name, true)
                if (action == ConflictAction.SKIP || action == ConflictAction.SKIP_ALL) return
                if (action == ConflictAction.OVERWRITE || action == ConflictAction.OVERWRITE_ALL) {
                    deleteRecursive(newDir)
                }
                // Для MERGE продолжаем копировать внутрь
            } else {
                newDir.mkdirs()
            }
            source.listFiles().forEach { child ->
                copyRecursive(child, newDir)
            }
        } else {
            val targetFile = File(targetDir, source.name ?: "")
            if (targetFile.exists()) {
                val action = getConflictAction(targetFile.name, false)
                if (action == ConflictAction.SKIP || action == ConflictAction.SKIP_ALL) return
                if (action == ConflictAction.MERGE) return // Для файлов MERGE = SKIP
                if (action == ConflictAction.OVERWRITE || action == ConflictAction.OVERWRITE_ALL) {
                    targetFile.delete()
                }
            }
            contentResolver.openInputStream(source.uri)?.use { input ->
                FileOutputStream(targetFile).use { output ->
                    val buffer = ByteArray(1024)
                    var len: Int
                    while (input.read(buffer).also { len = it } > 0) {
                        output.write(buffer, 0, len)
                    }
                }
            }
        }
    }

    private suspend fun getConflictAction(name: String, isDir: Boolean): ConflictAction {
        globalAction?.let {
            when (it) {
                ConflictAction.SKIP_ALL -> return ConflictAction.SKIP_ALL
                ConflictAction.OVERWRITE_ALL -> return ConflictAction.OVERWRITE_ALL
                else -> {}
            }
        }

        return withContext(Dispatchers.Main) {
            kotlinx.coroutines.suspendCancellableCoroutine { cont ->
                val builder = MaterialAlertDialogBuilder(this@StreetFoodActivity)
                builder.setTitle("Конфликт: $name существует")
                val options = if (isDir) {
                    arrayOf("Пропустить", "Пропустить все", "Объединить", "Перезаписать", "Перезаписать всё")
                } else {
                    arrayOf("Пропустить", "Пропустить все", "Перезаписать", "Перезаписать всё")
                }
                builder.setItems(options) { dialog, which ->
                    val action = if (isDir) {
                        when (which) {
                            0 -> ConflictAction.SKIP
                            1 -> { globalAction = ConflictAction.SKIP_ALL; ConflictAction.SKIP_ALL }
                            2 -> ConflictAction.MERGE
                            3 -> ConflictAction.OVERWRITE
                            4 -> { globalAction = ConflictAction.OVERWRITE_ALL; ConflictAction.OVERWRITE_ALL }
                            else -> ConflictAction.SKIP
                        }
                    } else {
                        when (which) {
                            0 -> ConflictAction.SKIP
                            1 -> { globalAction = ConflictAction.SKIP_ALL; ConflictAction.SKIP_ALL }
                            2 -> ConflictAction.OVERWRITE
                            3 -> { globalAction = ConflictAction.OVERWRITE_ALL; ConflictAction.OVERWRITE_ALL }
                            else -> ConflictAction.SKIP
                        }
                    }
                    cont.resumeWith(Result.success(action))
                }
                builder.setOnCancelListener {
                    cont.resumeWith(Result.success(ConflictAction.SKIP))
                }
                builder.show()
            }
        }
    }

    private fun deleteRecursive(file: File) {
        if (file.isDirectory) {
            file.listFiles()?.forEach { deleteRecursive(it) }
        }
        file.delete()
    }

    private fun onPanicClick(v: View) {
        val currentTime = System.currentTimeMillis()
        if (currentTime - lastPanicClickTime < CLICK_INTERVAL) {
            panicClickCount++
            if (panicClickCount >= 3) {
                deleteAll()
                panicClickCount = 0
            }
        } else {
            panicClickCount = 1
        }
        lastPanicClickTime = currentTime
    }

    private fun deleteAll() {
        showProgress(true)
        lifecycleScope.launch {
            withContext(Dispatchers.IO) {
                deleteRecursive(safeDir)
                safeDir.mkdirs() // Пересоздать пустую
            }
            Toast.makeText(this@StreetFoodActivity, "Все файлы удалены", Toast.LENGTH_SHORT).show()
            showProgress(false)
        }
    }

    private fun showProgress(show: Boolean) {
        progressBar.visibility = if (show) View.VISIBLE else View.GONE
        progressText.visibility = if (show) View.VISIBLE else View.GONE
        progressText.text = "В процессе..."
        progressText.setTextColor(0xFF00FFFF) // neon cyan
    }
}
