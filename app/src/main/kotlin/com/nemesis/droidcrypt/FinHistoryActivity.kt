package com.nemesis.droidcrypt

import android.content.ContentResolver
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.lifecycleScope
import com.google.android.material.switchmaterial.SwitchMaterial
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.InputStreamReader
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Locale

class FinHistoryActivity : AppCompatActivity() {

    companion object {
        private const val PREF_NAME = "PawsTribePrefs"
        private const val PREF_KEY_FOLDER_URI = "folderUri"
        private const val PREF_KEY_DISABLE_SCREENSHOTS = "disableScreenshots"

        private const val PREF_KEY_WALLET_PASSWORD_HASH = "walletPasswordHash"
        private const val PREF_KEY_WALLET_PASSWORD_PLAIN = "walletPasswordPlain" // используем plain если есть
        private const val PREF_KEY_WALLET_INFLATION_ENABLED = "walletInflationEnabled"
        private const val PREF_KEY_WALLET_INFLATION_PERCENT = "walletInflationPercent"
        private const val PREF_KEY_WALLET_INITIALIZED = "walletInitialized"

        private const val WALLET_DIR_NAME = "wallet"
        private const val FILE_FINMAN = "finman.txt"
        private const val FILE_FINHYST = "finhyst.txt"
    }

    private lateinit var prefs: SharedPreferences
    private var folderUri: Uri? = null
    private var currentPasswordChars: CharArray? = null

    // UI
    private lateinit var totalText: TextView
    private lateinit var inflationText: TextView
    private lateinit var historyContainer: LinearLayout
    private lateinit var clearHistoryButton: TextView
    private lateinit var messageText: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        prefs = getSharedPreferences(PREF_NAME, MODE_PRIVATE)

        if (prefs.getBoolean(PREF_KEY_DISABLE_SCREENSHOTS, false)) {
            window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
        }

        setContentView(R.layout.activity_fin_history)

        totalText = findViewById(R.id.totalText)
        inflationText = findViewById(R.id.inflationText)
        historyContainer = findViewById(R.id.historyContainer)
        clearHistoryButton = findViewById(R.id.clearHistoryButton)
        messageText = findViewById(R.id.messageText)

        prefs.getString(PREF_KEY_FOLDER_URI, null)?.let { s ->
            try {
                folderUri = Uri.parse(s)
                contentResolver.takePersistableUriPermission(
                    folderUri!!,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                )
            } catch (e: Exception) {
                folderUri = null
            }
        }

        // If no password hash set -> show message and stop
        val storedHash = prefs.getString(PREF_KEY_WALLET_PASSWORD_HASH, null)
        if (storedHash.isNullOrEmpty()) {
            messageText.text = "Пароль не установлен. Данные недоступны."
            messageText.visibility = View.VISIBLE
            return
        }

        // Auto-load plain password from prefs if present. If not present — показываем сообщение и не запрашиваем диалог.
        val savedPlain = prefs.getString(PREF_KEY_WALLET_PASSWORD_PLAIN, null)
        if (!savedPlain.isNullOrEmpty()) {
            currentPasswordChars = savedPlain.toCharArray()
            loadHistoryAndRender()
        } else {
            messageText.text = "Пароль не установлен. Данные недоступны."
            messageText.visibility = View.VISIBLE
        }

        clearHistoryButton.setOnClickListener {
            confirmAndClearHistory()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        currentPasswordChars?.let {
            for (i in it.indices) it[i] = '\u0000'
            currentPasswordChars = null
        }
    }

    // NOTE: no interactive password prompt here — password comes from sharedprefs (plain)
    private fun loadHistoryAndRender() {
        lifecycleScope.launch(Dispatchers.IO) {
            val uri = folderUri
            if (uri == null) {
                withContext(Dispatchers.Main) {
                    messageText.text = "SAF папка не выбрана."
                    messageText.visibility = View.VISIBLE
                }
                return@launch
            }

            try {
                val tree = DocumentFile.fromTreeUri(this@FinHistoryActivity, uri)
                if (tree == null) {
                    withContext(Dispatchers.Main) {
                        messageText.text = "Невалидная SAF папка."
                        messageText.visibility = View.VISIBLE
                    }
                    return@launch
                }
                val treeNonNull = tree

                val walletDir = treeNonNull.findFile(WALLET_DIR_NAME)
                if (walletDir == null || !walletDir.exists()) {
                    withContext(Dispatchers.Main) {
                        messageText.text = "Каталог wallet не найден."
                        messageText.visibility = View.VISIBLE
                    }
                    return@launch
                }
                val wallet = walletDir

                val finmanFile = wallet.findFile(FILE_FINMAN)
                val finhystFile = wallet.findFile(FILE_FINHYST)
                if (finmanFile == null || finhystFile == null) {
                    withContext(Dispatchers.Main) {
                        messageText.text = "Файлы кошелька не найдены."
                        messageText.visibility = View.VISIBLE
                    }
                    return@launch
                }

                val finmanRaw = readTextFromUri(finmanFile.uri)
                val finhystRaw = readTextFromUri(finhystFile.uri)

                val pwd = currentPasswordChars ?: run {
                    withContext(Dispatchers.Main) {
                        messageText.text = "Пароль не загружен."
                        messageText.visibility = View.VISIBLE
                    }
                    return@launch
                }

                // decrypt finman only when content non-blank; otherwise treat as empty
                val finmanPlain = try {
                    if (finmanRaw.isBlank()) "" else Secure.decrypt(pwd.copyOf(), finmanRaw)
                } catch (e: Exception) {
                    withContext(Dispatchers.Main) {
                        messageText.text = "Ошибка дешифровки finman. Проверьте пароль в настройках."
                        messageText.visibility = View.VISIBLE
                    }
                    return@launch
                }

                val finhystPlain = try {
                    if (finhystRaw.isBlank()) "" else {
                        val dec = Secure.decrypt(pwd.copyOf(), finhystRaw)
                        if (dec.trim().isEmpty()) "" else dec
                    }
                } catch (e: Exception) {
                    // если история не декодируется — используем пустую
                    ""
                }

                // parse finman total
                var total = 0.0
                val lines = finmanPlain.lines().map { it.trim() }.filter { it.isNotEmpty() }
                for (ln in lines) {
                    if (ln.startsWith("TOTAL:", ignoreCase = true)) {
                        total = ln.substringAfter("TOTAL:", "0").trim().toDoubleOrNull() ?: 0.0
                        break
                    }
                }

                // parse history lines
                val historyLines = finhystPlain.lines().map { it.trim() }.filter { it.isNotEmpty() && it.startsWith("hy") }

                withContext(Dispatchers.Main) {
                    renderHistoryScreen(total, historyLines)
                }

            } catch (e: Exception) {
                e.printStackTrace()
                withContext(Dispatchers.Main) {
                    messageText.text = "Ошибка при загрузке: ${e.message}"
                    messageText.visibility = View.VISIBLE
                }
            }
        }
    }

    private fun renderHistoryScreen(total: Double, historyLines: List<String>) {
        messageText.visibility = View.GONE
        totalText.setTextColor(0xFF00FF00.toInt()) // neon green
        totalText.text = String.format(Locale.getDefault(), "%,.2f", total)

        val inflationEnabled = prefs.getBoolean(PREF_KEY_WALLET_INFLATION_ENABLED, false)
        if (inflationEnabled) {
            val pct = prefs.getFloat(PREF_KEY_WALLET_INFLATION_PERCENT, 0.0f)
            val inflated = total * (1.0 + pct / 100.0)
            inflationText.visibility = View.VISIBLE
            inflationText.setTextColor(0xFF00FFFF.toInt())
            inflationText.text = "с учетом инфляции: ${String.format(Locale.getDefault(), "%,.2f", inflated)}"
        } else {
            inflationText.visibility = View.GONE
        }

        historyContainer.removeAllViews()
        if (historyLines.isEmpty()) {
            val tv = TextView(this).apply {
                text = "История пустая."
                setTextColor(0xFF00FF00.toInt())
                textSize = 14f
                gravity = Gravity.CENTER
                setPadding(24, 24, 24, 24)
            }
            historyContainer.addView(tv)
        } else {
            for (ln in historyLines) {
                // ln example: hy2025-10-12_12:00:+500:AccountName
                val afterHy = ln.removePrefix("hy")
                val parts = afterHy.split(":")
                val datePart = if (parts.isNotEmpty()) parts[0] else ""
                val amountPart = if (parts.size >= 2) parts[1] else ""
                val accPart = if (parts.size >= 3) parts.subList(2, parts.size).joinToString(":") else ""
                val tv = TextView(this).apply {
                    val accDisplay = if (accPart.isNotEmpty()) " — $accPart" else ""
                    text = "$datePart — $amountPart$accDisplay"
                    setTextColor(0xFF00FF00.toInt())
                    textSize = 14f
                    setPadding(8, 8, 8, 8)
                }
                historyContainer.addView(tv)
                val sep = View(this).apply {
                    layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 1).also {
                        it.setMargins(6,6,6,6)
                    }
                    setBackgroundColor(0xFF222222.toInt())
                }
                historyContainer.addView(sep)
            }
        }
    }

    private fun confirmAndClearHistory() {
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Очистить историю?")
            .setMessage("Будут удалены все записи истории (hy...). Это действие необратимо.")
            .setPositiveButton("Очистить") { dialog, _ ->
                dialog.dismiss()
                lifecycleScope.launch(Dispatchers.IO) {
                    clearHistoryFile()
                }
            }
            .setNegativeButton("Отмена") { d, _ -> d.dismiss() }
            .show()
    }

    private suspend fun clearHistoryFile() {
        withContext(Dispatchers.IO) {
            try {
                val fUri = folderUri ?: run {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(this@FinHistoryActivity, "SAF папка не выбрана.", Toast.LENGTH_SHORT).show()
                    }
                    return@withContext
                }
                val tree = DocumentFile.fromTreeUri(this@FinHistoryActivity, fUri) ?: run {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(this@FinHistoryActivity, "Невалидная SAF папка.", Toast.LENGTH_SHORT).show()
                    }
                    return@withContext
                }
                val walletDir = tree.findFile(WALLET_DIR_NAME)
                if (walletDir == null) {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(this@FinHistoryActivity, "Каталог wallet не найден.", Toast.LENGTH_SHORT).show()
                    }
                    return@withContext
                }
                val finhystFile = walletDir.findFile(FILE_FINHYST)
                if (finhystFile == null) {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(this@FinHistoryActivity, "Файл истории не найден.", Toast.LENGTH_SHORT).show()
                    }
                    return@withContext
                }
                // overwrite with non-empty encrypted marker (single space)
                val pwd = currentPasswordChars ?: run {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(this@FinHistoryActivity, "Пароль не загружен.", Toast.LENGTH_SHORT).show()
                    }
                    return@withContext
                }
                val encrypted = Secure.encrypt(pwd.copyOf(), " ")
                writeTextToDocument(finhystFile, encrypted)
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@FinHistoryActivity, "История очищена.", Toast.LENGTH_SHORT).show()
                    // refresh view
                    loadHistoryAndRender()
                }
            } catch (e: Exception) {
                e.printStackTrace()
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@FinHistoryActivity, "Ошибка при очистке: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun readTextFromUri(uri: Uri): String {
        val cr: ContentResolver = contentResolver
        val sb = StringBuilder()
        cr.openInputStream(uri)?.use { input ->
            BufferedReader(InputStreamReader(input, StandardCharsets.UTF_8)).use { br ->
                var line = br.readLine()
                while (line != null) {
                    sb.append(line)
                    sb.append("\n")
                    line = br.readLine()
                }
            }
        }
        return sb.toString().trim()
    }

    private fun writeTextToDocument(document: DocumentFile, text: String) {
        val uri = document.uri
        contentResolver.openOutputStream(uri)?.use { os ->
            os.write(text.toByteArray(StandardCharsets.UTF_8))
            os.flush()
        }
    }

    private fun sha256Hex(chars: CharArray): String {
        val md = MessageDigest.getInstance("SHA-256")
        val bytes = String(chars).toByteArray(StandardCharsets.UTF_8)
        val digest = md.digest(bytes)
        val sb = StringBuilder()
        for (b in digest) {
            sb.append(String.format("%02x", b))
        }
        return sb.toString()
    }
}
