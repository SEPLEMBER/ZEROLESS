package com.nemesis.droidcrypt

import android.app.AlertDialog
import android.content.ContentResolver
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.os.Bundle
import android.provider.DocumentsContract
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.appcompat.app.AppCompatActivity
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.view.setPadding
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.InputStreamReader
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.round

class FinActivity : AppCompatActivity() {

    companion object {
        private const val PREF_NAME = "PawsTribePrefs"
        private const val PREF_KEY_FOLDER_URI = "folderUri"
        private const val PREF_KEY_DISABLE_SCREENSHOTS = "disableScreenshots"

        // wallet-specific keys
        private const val PREF_KEY_WALLET_PASSWORD_HASH = "walletPasswordHash"
        private const val PREF_KEY_WALLET_INFLATION_ENABLED = "walletInflationEnabled"
        private const val PREF_KEY_WALLET_INFLATION_PERCENT = "walletInflationPercent"
        private const val PREF_KEY_WALLET_INITIALIZED = "walletInitialized"

        private const val WALLET_DIR_NAME = "wallet"
        private const val FILE_FINMAN = "finman.txt"
        private const val FILE_FINHYST = "finhyst.txt"
    }

    private lateinit var prefs: SharedPreferences
    private var folderUri: Uri? = null

    // UI
    private lateinit var rootLayout: ConstraintLayout
    private lateinit var totalTextView: TextView
    private lateinit var inflationTextView: TextView
    private lateinit var accountsContainer: LinearLayout
    private lateinit var historyButton: TextView
    private lateinit var settingsButton: TextView
    private lateinit var addTxButton: TextView
    private lateinit var messageTextView: TextView

    // state
    private var currentPassword: CharArray? = null // plaintext in memory while activity alive (cleared on destroy)
    private var accountsList: MutableList<Pair<String, Double>> = mutableListOf()
    private var totalAmount: Double = 0.0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        prefs = getSharedPreferences(PREF_NAME, MODE_PRIVATE)

        // Apply FLAG_SECURE if needed
        if (prefs.getBoolean(PREF_KEY_DISABLE_SCREENSHOTS, false)) {
            window.setFlags(
                WindowManager.LayoutParams.FLAG_SECURE,
                WindowManager.LayoutParams.FLAG_SECURE
            )
        }

        setContentView(R.layout.activity_fin)

        // Bind views
        totalTextView = findViewById(R.id.totalTextView)
        inflationTextView = findViewById(R.id.inflationTextView)
        accountsContainer = findViewById(R.id.accountsContainer)
        historyButton = findViewById(R.id.historyButton)
        settingsButton = findViewById(R.id.settingsButton)
        addTxButton = findViewById(R.id.addTxButton)
        messageTextView = findViewById(R.id.messageTextView)

        // restore persisted folderUri and try to take permission again (safe)
        prefs.getString(PREF_KEY_FOLDER_URI, null)?.let { uriString ->
            try {
                folderUri = Uri.parse(uriString)
                contentResolver.takePersistableUriPermission(
                    folderUri!!,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                )
            } catch (e: Exception) {
                folderUri = null
            }
        }

        historyButton.setOnClickListener {
            startActivity(Intent(this, FinHistoryActivity::class.java))
        }

        settingsButton.setOnClickListener {
            startActivity(Intent(this, FinSettingsActivity::class.java))
        }

        addTxButton.setOnClickListener {
            showAddTransactionDialog()
        }

        // if wallet password not set -> inform user, else prompt for password to decrypt
        val hashed = prefs.getString(PREF_KEY_WALLET_PASSWORD_HASH, null)
        if (hashed.isNullOrEmpty()) {
            showMessage("Пароль не установлен. Данные недоступны.")
            renderEmptyState()
        } else {
            // prompt for password
            showPasswordDialogAndLoadData()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        // zero out password copy if present
        currentPassword?.let {
            for (i in it.indices) it[i] = '\u0000'
            currentPassword = null
        }
    }

    private fun showMessage(text: String) {
        messageTextView.visibility = View.VISIBLE
        messageTextView.text = text
    }

    private fun hideMessage() {
        messageTextView.visibility = View.GONE
    }

    private fun showPasswordDialogAndLoadData() {
        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            hint = "Введите пароль"
            setPadding(24)
        }
        AlertDialog.Builder(this)
            .setTitle("Введите пароль кошелька")
            .setView(input)
            .setCancelable(false)
            .setPositiveButton("OK") { dialog, _ ->
                val pwd = input.text?.toString() ?: ""
                if (pwd.isEmpty()) {
                    Toast.makeText(this, "Пароль пустой", Toast.LENGTH_SHORT).show()
                    renderEmptyState()
                    dialog.dismiss()
                    return@setPositiveButton
                }
                val pwdChars = pwd.toCharArray()
                val hashedStored = prefs.getString(PREF_KEY_WALLET_PASSWORD_HASH, "") ?: ""
                val hashedInput = sha256Hex(pwdChars)
                if (hashedInput.equals(hashedStored, ignoreCase = true)) {
                    // accepted
                    currentPassword = pwdChars
                    hideMessage()
                    loadFinMan()
                } else {
                    // clear pwdChars
                    for (i in pwdChars.indices) pwdChars[i] = '\u0000'
                    Toast.makeText(this, "Неверный пароль", Toast.LENGTH_SHORT).show()
                    renderEmptyState()
                }
                dialog.dismiss()
            }
            .setNegativeButton("Отмена") { dialog, _ ->
                dialog.dismiss()
                renderEmptyState()
            }
            .show()
    }

    private fun renderEmptyState() {
        // show TOTAL = 0 and "У вас нет счетов."
        totalTextView.text = "0"
        inflationTextView.visibility = View.GONE
        accountsContainer.removeAllViews()
        val tv = TextView(this).apply {
            text = "У вас нет счетов."
            setTextColor(0xFF00FFFF.toInt()) // neon cyan
            textSize = 14f
            gravity = Gravity.CENTER
            setPadding(24)
        }
        accountsContainer.addView(tv)
    }

    private fun loadFinMan() {
        lifecycleScope.launch(Dispatchers.IO) {
            val uri = folderUri
            if (uri == null) {
                withContext(Dispatchers.Main) {
                    showMessage("Папка SAF не выбрана в настройках.")
                    renderEmptyState()
                }
                return@launch
            }

            try {
                val tree = DocumentFile.fromTreeUri(this@FinActivity, uri)
                if (tree == null || !tree.exists() || !tree.isDirectory) {
                    withContext(Dispatchers.Main) {
                        showMessage("Невалидная SAF папка.")
                        renderEmptyState()
                    }
                    return@launch
                }

                // find or create wallet dir
                var walletDir = tree.findFile(WALLET_DIR_NAME)
                if (walletDir == null || !walletDir.isDirectory) {
                    // may not exist
                    walletDir = tree.createDirectory(WALLET_DIR_NAME)
                }

                val finmanFile = walletDir.findFile(FILE_FINMAN)

                if (finmanFile == null || !finmanFile.exists()) {
                    // nothing to read
                    withContext(Dispatchers.Main) {
                        renderEmptyState()
                    }
                    return@launch
                }

                // read file content
                val finmanUri = finmanFile.uri
                val rawEncrypted = readTextFromUri(finmanUri)
                val pwd = currentPassword ?: run {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(this@FinActivity, "Пароль отсутствует в памяти", Toast.LENGTH_SHORT).show()
                        renderEmptyState()
                    }
                    return@launch
                }

                val decrypted = try {
                    // pass a copy to Secure.decrypt to avoid it zeroing our kept password
                    Secure.decrypt(pwd.copyOf(), rawEncrypted)
                } catch (e: Exception) {
                    withContext(Dispatchers.Main) {
                        showMessage("Ошибка дешифровки: проверьте пароль.")
                        renderEmptyState()
                    }
                    return@launch
                }

                // parse decrypted
                parseFinManAndRender(decrypted)

            } catch (e: Exception) {
                e.printStackTrace()
                withContext(Dispatchers.Main) {
                    showMessage("Ошибка при доступе к файлам: ${e.message}")
                    renderEmptyState()
                }
            }
        }
    }

    private fun parseFinManAndRender(plain: String) {
        // parse lines
        val lines = plain.lines().map { it.trim() }.filter { it.isNotEmpty() }
        var total = 0.0
        val accounts = mutableListOf<Pair<String, Double>>()

        var i = 0
        while (i < lines.size) {
            val line = lines[i]
            if (line.startsWith("TOTAL:", ignoreCase = true)) {
                val v = line.substringAfter("TOTAL:", "").trim()
                total = v.toDoubleOrNull() ?: 0.0
                i++
                continue
            }
            if (line.startsWith("WLT", ignoreCase = true)) {
                val name = line.substringAfter(":", "").trim()
                // next line may be WNT...
                val next = if (i + 1 < lines.size) lines[i + 1] else null
                if (next != null && next.startsWith("WNT", ignoreCase = true)) {
                    val amt = next.substringAfter(":", "").trim().toDoubleOrNull() ?: 0.0
                    accounts.add(Pair(name, amt))
                    i += 2
                    continue
                } else {
                    // malformed, skip
                    accounts.add(Pair(name, 0.0))
                    i++
                    continue
                }
            }
            i++
        }

        // update state & UI on main
        lifecycleScope.launch(Dispatchers.Main) {
            totalAmount = total
            accountsList = accounts
            renderFinScreen()
        }
    }

    private fun renderFinScreen() {
        // total - neon green
        totalTextView.setTextColor(0xFF00FF00.toInt())
        totalTextView.text = formatMoney(totalAmount)

        // inflation
        val inflationEnabled = prefs.getBoolean(PREF_KEY_WALLET_INFLATION_ENABLED, false)
        if (inflationEnabled) {
            val pct = prefs.getFloat(PREF_KEY_WALLET_INFLATION_PERCENT, 0.0f)
            val inflated = totalAmount * (1.0 + pct / 100.0)
            inflationTextView.visibility = View.VISIBLE
            inflationTextView.setTextColor(0xFF00FFFF.toInt())
            inflationTextView.text = "с учетом инфляции: ${formatMoney(inflated)}"
        } else {
            inflationTextView.visibility = View.GONE
        }

        // accounts
        accountsContainer.removeAllViews()
        if (accountsList.isEmpty()) {
            val tv = TextView(this).apply {
                text = "У вас нет счетов."
                setTextColor(0xFF00FFFF.toInt())
                textSize = 14f
                gravity = Gravity.CENTER
                setPadding(24)
            }
            accountsContainer.addView(tv)
        } else {
            for ((name, amt) in accountsList) {
                val row = TextView(this).apply {
                    text = "$name — ${formatMoney(amt)}"
                    setTextColor(0xFF00FFFF.toInt()) // neon cyan
                    textSize = 16f
                    setPadding(16)
                }
                accountsContainer.addView(row)
                // separator
                val sep = View(this).apply {
                    layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 1).also {
                        it.setMargins(8, 6, 8, 6)
                    }
                    setBackgroundColor(0xFF222222.toInt())
                }
                accountsContainer.addView(sep)
            }
        }
    }

    private fun formatMoney(value: Double): String {
        // simple formatting without localization complexity
        val rounded = round(value * 100) / 100.0
        return String.format(Locale.getDefault(), "%,.2f", rounded)
    }

    // --- Utilities for SAF read/write --------------------------------------

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
        // delete and recreate to ensure truncation
        contentResolver.openOutputStream(uri, "w")?.use { os ->
            os.write(text.toByteArray(StandardCharsets.UTF_8))
            os.flush()
        }
    }

    // helper: find or create wallet dir and files
    private fun getOrCreateWalletFiles(): Pair<DocumentFile?, DocumentFile?> {
        val tree = DocumentFile.fromTreeUri(this, folderUri!!)
        var walletDir = tree?.findFile(WALLET_DIR_NAME)
        if (walletDir == null || !walletDir.isDirectory) {
            walletDir = tree?.createDirectory(WALLET_DIR_NAME)
        }
        walletDir ?: return Pair(null, null)

        var finman = walletDir.findFile(FILE_FINMAN)
        if (finman == null) {
            finman = walletDir.createFile("text/plain", FILE_FINMAN)
        }
        var finhyst = walletDir.findFile(FILE_FINHYST)
        if (finhyst == null) {
            finhyst = walletDir.createFile("text/plain", FILE_FINHYST)
        }
        return Pair(finman, finhyst)
    }

    // --- Adding a transaction ------------------------------------------------

    private fun showAddTransactionDialog() {
        if (currentPassword == null) {
            Toast.makeText(this, "Нельзя добавить транзакцию без ввода пароля.", Toast.LENGTH_SHORT).show()
            return
        }

        // simple dialog: amount (with sign), account name (optional)
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24)
        }
        val amountInput = EditText(this).apply {
            hint = "Сумма (например: +500 или -120)"
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_SIGNED or InputType.TYPE_NUMBER_FLAG_DECIMAL
        }
        val accountInput = EditText(this).apply {
            hint = "Название счета (опционально)"
            inputType = InputType.TYPE_CLASS_TEXT
        }
        layout.addView(amountInput)
        layout.addView(accountInput)

        AlertDialog.Builder(this)
            .setTitle("Добавить транзакцию")
            .setView(layout)
            .setPositiveButton("Сохранить") { dialog, _ ->
                val amtStr = amountInput.text?.toString()?.trim() ?: ""
                val acc = accountInput.text?.toString()?.trim() ?: ""
                if (amtStr.isEmpty()) {
                    Toast.makeText(this, "Сумма не указана", Toast.LENGTH_SHORT).show()
                    dialog.dismiss()
                    return@setPositiveButton
                }
                val amt = amtStr.replace(",", ".").toDoubleOrNull()
                if (amt == null) {
                    Toast.makeText(this, "Неверный формат суммы", Toast.LENGTH_SHORT).show()
                    dialog.dismiss()
                    return@setPositiveButton
                }
                // write transaction
                lifecycleScope.launch(Dispatchers.IO) {
                    addTransactionAndUpdateFiles(amt, acc)
                }
                dialog.dismiss()
            }
            .setNegativeButton("Отмена") { d, _ -> d.dismiss() }
            .show()
    }

    private suspend fun addTransactionAndUpdateFiles(amount: Double, accountName: String) {
        withContext(Dispatchers.IO) {
            val uri = folderUri
            if (uri == null) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@FinActivity, "Папка SAF не выбрана", Toast.LENGTH_SHORT).show()
                }
                return@withContext
            }

            val (finmanDoc, finhystDoc) = getOrCreateWalletFiles()
            if (finmanDoc == null || finhystDoc == null) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@FinActivity, "Не удалось получить файлы кошелька", Toast.LENGTH_SHORT).show()
                }
                return@withContext
            }

            // read and decrypt finman
            val rawEncrypted = readTextFromUri(finmanDoc.uri)
            val plainFinman = try {
                Secure.decrypt(currentPassword!!.copyOf(), rawEncrypted)
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@FinActivity, "Ошибка дешифровки finman: ${e.message}", Toast.LENGTH_SHORT).show()
                }
                return@withContext
            }

            // update model
            val lines = plainFinman.lines().toMutableList()
            var total = 0.0
            val accounts = mutableListOf<Pair<String, Double>>()
            var i = 0
            while (i < lines.size) {
                val line = lines[i].trim()
                if (line.startsWith("TOTAL:", ignoreCase = true)) {
                    total = line.substringAfter("TOTAL:", "0").trim().toDoubleOrNull() ?: 0.0
                    i++
                    continue
                }
                if (line.startsWith("WLT", ignoreCase = true)) {
                    val name = line.substringAfter(":", "").trim()
                    val next = if (i + 1 < lines.size) lines[i + 1].trim() else null
                    if (next != null && next.startsWith("WNT", ignoreCase = true)) {
                        val amt = next.substringAfter(":", "0").trim().toDoubleOrNull() ?: 0.0
                        accounts.add(Pair(name, amt))
                        i += 2
                        continue
                    } else {
                        accounts.add(Pair(name, 0.0))
                        i++
                        continue
                    }
                }
                i++
            }

            // apply transaction: update total and account if provided
            total += amount
            if (accountName.isNotEmpty()) {
                var updated = false
                for (idx in accounts.indices) {
                    if (accounts[idx].first.equals(accountName, ignoreCase = true)) {
                        accounts[idx] = Pair(accounts[idx].first, accounts[idx].second + amount)
                        updated = true
                        break
                    }
                }
                // if account not found -> do not create new account here (user requested to add accounts via settings)
                // alternative: you could create new account; but spec said adding accounts via FinSettingsActivity
            }

            // rebuild finman plaintext
            val sb = StringBuilder()
            sb.append("TOTAL:${String.format(Locale.getDefault(), "%.2f", total)}\n")
            for ((idx, p) in accounts.withIndex()) {
                val id = idx + 1
                sb.append("WLT${id}:${p.first}\n")
                sb.append("WNT${id}:${String.format(Locale.getDefault(), "%.2f", p.second)}\n")
            }
            val newFinmanPlain = sb.toString().trim()

            // encrypt and write finman
            try {
                val encryptedFinman = Secure.encrypt(currentPassword!!.copyOf(), newFinmanPlain)
                writeTextToDocument(finmanDoc, encryptedFinman)
            } catch (e: Exception) {
                e.printStackTrace()
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@FinActivity, "Ошибка при записи finman: ${e.message}", Toast.LENGTH_SHORT).show()
                }
                return@withContext
            }

            // append to history (read existing hist, append new line)
            val timestamp = SimpleDateFormat("yyyy-MM-dd_HH:mm", Locale.getDefault()).format(Date())
            val histLine = "hy${timestamp}:${if (amount >= 0) "+" else ""}${String.format(Locale.getDefault(), "%.2f", amount)}${if (accountName.isNotEmpty()) ":$accountName" else ""}\n"

            // read existing hist decrypted, append plain, encrypt back
            val rawHistEncrypted = readTextFromUri(finhystDoc.uri)
            val plainHist = try {
                if (rawHistEncrypted.isBlank()) "" else Secure.decrypt(currentPassword!!.copyOf(), rawHistEncrypted)
            } catch (e: Exception) {
                ""
            }
            val newPlainHist = (plainHist + histLine).trim()

            try {
                val encryptedHist = Secure.encrypt(currentPassword!!.copyOf(), newPlainHist)
                writeTextToDocument(finhystDoc, encryptedHist)
            } catch (e: Exception) {
                e.printStackTrace()
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@FinActivity, "Ошибка при записи истории: ${e.message}", Toast.LENGTH_SHORT).show()
                }
                return@withContext
            }

            // success -> update UI
            withContext(Dispatchers.Main) {
                Toast.makeText(this@FinActivity, "Транзакция сохранена", Toast.LENGTH_SHORT).show()
                // update in-memory and render
                totalAmount = total
                accountsList = accounts
                renderFinScreen()
            }
        }
    }

    // --- small helpers -----------------------------------------------------

    private fun sha256Hex(chars: CharArray): String {
        val md = MessageDigest.getInstance("SHA-256")
        val bytes = String(chars).toByteArray(StandardCharsets.UTF_8)
        val digest = md.digest(bytes)
        // hex
        val sb = StringBuilder()
        for (b in digest) {
            sb.append(String.format("%02x", b))
        }
        return sb.toString()
    }
}
