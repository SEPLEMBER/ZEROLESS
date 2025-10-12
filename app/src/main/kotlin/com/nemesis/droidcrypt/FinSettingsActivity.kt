package com.nemesis.droidcrypt

import android.content.ContentResolver
import android.content.SharedPreferences
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.EditText
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
import java.util.Locale

class FinSettingsActivity : AppCompatActivity() {

    companion object {
        private const val PREF_NAME = "PawsTribePrefs"
        private const val PREF_KEY_FOLDER_URI = "folderUri"
        private const val PREF_KEY_DISABLE_SCREENSHOTS = "disableScreenshots"

        private const val PREF_KEY_WALLET_PASSWORD_HASH = "walletPasswordHash"
        private const val PREF_KEY_WALLET_PASSWORD_PLAIN = "walletPasswordPlain" // <-- plaintext для автозагрузки
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
    private lateinit var addAccountNameInput: EditText
    private lateinit var addAccountBalanceInput: EditText
    private lateinit var addAccountButton: TextView
    private lateinit var accountsContainer: LinearLayout
    private lateinit var inflationSwitch: SwitchMaterial
    private lateinit var inflationPercentInput: EditText
    private lateinit var passwordNewInput: EditText
    private lateinit var passwordConfirmInput: EditText
    private lateinit var saveButton: TextView
    private lateinit var messageText: TextView

    // model
    private var accountsList: MutableList<Pair<String, Double>> = mutableListOf()
    private var walletInitialized: Boolean = false
    private var currentPasswordChars: CharArray? = null // plaintext из prefs

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = getSharedPreferences(PREF_NAME, MODE_PRIVATE)

        if (prefs.getBoolean(PREF_KEY_DISABLE_SCREENSHOTS, false)) {
            window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
        }

        setContentView(R.layout.activity_fin_settings)

        // Bind UI
        addAccountNameInput = findViewById(R.id.addAccountNameInput)
        addAccountBalanceInput = findViewById(R.id.addAccountBalanceInput)
        addAccountButton = findViewById(R.id.addAccountButton)
        accountsContainer = findViewById(R.id.accountsContainer)
        inflationSwitch = findViewById(R.id.inflationSwitch)
        inflationPercentInput = findViewById(R.id.inflationPercentInput)
        passwordNewInput = findViewById(R.id.passwordNewInput)
        passwordConfirmInput = findViewById(R.id.passwordConfirmInput)
        saveButton = findViewById(R.id.saveButton)
        messageText = findViewById(R.id.messageText)

        // restore SAF uri
        prefs.getString(PREF_KEY_FOLDER_URI, null)?.let { s ->
            try {
                folderUri = Uri.parse(s)
                contentResolver.takePersistableUriPermission(
                    folderUri!!,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                )
            } catch (_: Exception) {
                folderUri = null
            }
        }

        // restore inflation prefs
        inflationSwitch.isChecked = prefs.getBoolean(PREF_KEY_WALLET_INFLATION_ENABLED, false)
        inflationPercentInput.setText(String.format(Locale.getDefault(), "%.2f", prefs.getFloat(PREF_KEY_WALLET_INFLATION_PERCENT, 0.0f)))

        walletInitialized = prefs.getBoolean(PREF_KEY_WALLET_INITIALIZED, false)

        // load password from prefs (plaintext)
        prefs.getString(PREF_KEY_WALLET_PASSWORD_PLAIN, null)?.let { savedPlain ->
            if (savedPlain.isNotEmpty()) {
                currentPasswordChars = savedPlain.toCharArray()
                loadFinManForSettings() // загружаем счета
            }
        } ?: run {
            // кошелек не инициализирован
            messageText.text = "Кошелёк не инициализирован. Для создания файлов установите пароль и нажмите Сохранить."
            messageText.visibility = View.VISIBLE
        }

        addAccountButton.setOnClickListener {
            val name = addAccountNameInput.text?.toString()?.trim() ?: ""
            val balStr = addAccountBalanceInput.text?.toString()?.trim() ?: ""
            if (name.isEmpty()) {
                Toast.makeText(this, "Название счёта пустое", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val bal = balStr.replace(",", ".").toDoubleOrNull() ?: 0.0
            accountsList.add(Pair(name, bal))
            addAccountNameInput.setText("")
            addAccountBalanceInput.setText("")
            renderAccountsList()
        }

        saveButton.setOnClickListener {
            onSaveClicked()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        currentPasswordChars?.let {
            for (i in it.indices) it[i] = '\u0000'
            currentPasswordChars = null
        }
    }

    private fun loadFinManForSettings() {
        lifecycleScope.launch(Dispatchers.IO) {
            val uri = folderUri ?: return@launch
            try {
                val tree = DocumentFile.fromTreeUri(this@FinSettingsActivity, uri) ?: return@launch
                var walletDir = tree.findFile(WALLET_DIR_NAME)
                if (walletDir == null || !walletDir.isDirectory) {
                    walletDir = tree.createDirectory(WALLET_DIR_NAME)
                }
                val wallet = walletDir ?: return@launch
                val finmanFile = wallet.findFile(FILE_FINMAN)
                if (finmanFile == null || !finmanFile.exists()) return@launch
                val raw = readTextFromUri(finmanFile.uri)
                val pwd = currentPasswordChars ?: return@launch
                val plain = try {
                    Secure.decrypt(pwd.copyOf(), raw)
                } catch (_: Exception) {
                    return@launch
                }
                parseFinManPlain(plain)
                withContext(Dispatchers.Main) {
                    renderAccountsList()
                    messageText.visibility = View.GONE
                }
            } catch (_: Exception) {}
        }
    }

    private fun parseFinManPlain(plain: String) {
        val lines = plain.lines().map { it.trim() }.filter { it.isNotEmpty() }
        val accs = mutableListOf<Pair<String, Double>>()
        var i = 0
        while (i < lines.size) {
            val line = lines[i]
            if (line.startsWith("WLT", ignoreCase = true)) {
                val name = line.substringAfter(":", "").trim()
                val next = if (i + 1 < lines.size) lines[i + 1] else null
                val amt = next?.substringAfter(":", "0")?.trim()?.toDoubleOrNull() ?: 0.0
                accs.add(Pair(name, amt))
                i += 2
                continue
            }
            i++
        }
        accountsList = accs
    }

    private fun renderAccountsList() {
        runOnUiThread {
            accountsContainer.removeAllViews()
            if (accountsList.isEmpty()) {
                val tv = TextView(this).apply {
                    text = "Счёта отсутствуют."
                    setTextColor(0xFF00FFFF.toInt())
                    textSize = 14f
                    gravity = Gravity.CENTER
                    setPadding(24, 24, 24, 24)
                }
                accountsContainer.addView(tv)
            } else {
                for ((idx, p) in accountsList.withIndex()) {
                    val row = LinearLayout(this).apply {
                        orientation = LinearLayout.HORIZONTAL
                        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                    }
                    val nameTv = TextView(this).apply {
                        text = p.first
                        setTextColor(0xFF00FFFF.toInt())
                        textSize = 15f
                        layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                        setPadding(8, 8, 8, 8)
                    }
                    val balTv = TextView(this).apply {
                        text = String.format(Locale.getDefault(), "%.2f", p.second)
                        setTextColor(0xFF00FFFF.toInt())
                        textSize = 15f
                        setPadding(8, 8, 8, 8)
                    }
                    val del = TextView(this).apply {
                        text = "Удалить"
                        setTextColor(0xFF00FFFF.toInt())
                        setPadding(8, 8, 8, 8)
                        setOnClickListener {
                            accountsList.removeAt(idx)
                            renderAccountsList()
                        }
                    }
                    row.addView(nameTv)
                    row.addView(balTv)
                    row.addView(del)
                    accountsContainer.addView(row)
                    val sep = View(this).apply {
                        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 1).also {
                            it.setMargins(6,6,6,6)
                        }
                        setBackgroundColor(0xFF222222.toInt())
                    }
                    accountsContainer.addView(sep)
                }
            }
        }
    }

    private fun onSaveClicked() {
        val newPwd = passwordNewInput.text?.toString()?.trim() ?: ""
        val confirm = passwordConfirmInput.text?.toString()?.trim() ?: ""
        if (newPwd.isNotEmpty() && newPwd != confirm) {
            Toast.makeText(this, "Пароли не совпадают", Toast.LENGTH_SHORT).show()
            return
        }

        // Save inflation prefs
        prefs.edit().putBoolean(PREF_KEY_WALLET_INFLATION_ENABLED, inflationSwitch.isChecked).apply()
        val pct = inflationPercentInput.text?.toString()?.replace(",", ".")?.toFloatOrNull() ?: 0.0f
        prefs.edit().putFloat(PREF_KEY_WALLET_INFLATION_PERCENT, pct).apply()

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                if (newPwd.isNotEmpty()) {
                    val fUri = folderUri ?: run {
                        withContext(Dispatchers.Main) {
                            Toast.makeText(this@FinSettingsActivity, "SAF-папка не выбрана", Toast.LENGTH_LONG).show()
                        }
                        return@launch
                    }
                    val newChars = newPwd.toCharArray()
                    val newHash = sha256Hex(newChars)
                    prefs.edit().putString(PREF_KEY_WALLET_PASSWORD_HASH, newHash).apply()
                    prefs.edit().putString(PREF_KEY_WALLET_PASSWORD_PLAIN, newPwd).apply()
                    currentPasswordChars?.let { for (i in it.indices) it[i] = '\u0000' }
                    currentPasswordChars = newChars

                    val (finmanDoc, finhystDoc) = getOrCreateWalletFiles()
                    if (finmanDoc == null || finhystDoc == null) {
                        withContext(Dispatchers.Main) {
                            Toast.makeText(this@FinSettingsActivity, "Не удалось создать файлы в SAF.", Toast.LENGTH_SHORT).show()
                        }
                        return@launch
                    }

                    val total = accountsList.sumOf { it.second }
                    val sb = StringBuilder()
                    sb.append("TOTAL:${String.format(Locale.getDefault(), "%.2f", total)}\n")
                    accountsList.forEachIndexed { idx, p ->
                        val id = idx + 1
                        sb.append("WLT${id}:${p.first}\n")
                        sb.append("WNT${id}:${String.format(Locale.getDefault(), "%.2f", p.second)}\n")
                    }
                    val encryptedFinman = Secure.encrypt(currentPasswordChars!!.copyOf(), sb.toString().trim())
                    writeTextToDocument(finmanDoc, encryptedFinman)

                    val encryptedHist = Secure.encrypt(currentPasswordChars!!.copyOf(), " ")
                    writeTextToDocument(finhystDoc, encryptedHist)

                    prefs.edit().putBoolean(PREF_KEY_WALLET_INITIALIZED, true).apply()
                    withContext(Dispatchers.Main) {
                        Toast.makeText(this@FinSettingsActivity, "Кошелёк создан и сохранён.", Toast.LENGTH_SHORT).show()
                        finish()
                    }
                    return@launch
                } else if (walletInitialized && currentPasswordChars != null) {
                    // update only accounts
                    val (finmanDoc, _) = getOrCreateWalletFiles()
                    if (finmanDoc != null) {
                        val total = accountsList.sumOf { it.second }
                        val sb = StringBuilder()
                        sb.append("TOTAL:${String.format(Locale.getDefault(), "%.2f", total)}\n")
                        accountsList.forEachIndexed { idx, p ->
                            val id = idx + 1
                            sb.append("WLT${id}:${p.first}\n")
                            sb.append("WNT${id}:${String.format(Locale.getDefault(), "%.2f", p.second)}\n")
                        }
                        val encryptedFinman = Secure.encrypt(currentPasswordChars!!.copyOf(), sb.toString().trim())
                        writeTextToDocument(finmanDoc, encryptedFinman)
                        withContext(Dispatchers.Main) {
                            Toast.makeText(this@FinSettingsActivity, "Настройки кошелька сохранены.", Toast.LENGTH_SHORT).show()
                            finish()
                        }
                    }
                } else {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(this@FinSettingsActivity, "Укажите новый пароль для инициализации кошелька.", Toast.LENGTH_LONG).show()
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@FinSettingsActivity, "Ошибка сохранения: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun getOrCreateWalletFiles(): Pair<DocumentFile?, DocumentFile?> {
        val fUri = folderUri ?: return Pair(null, null)
        val tree = DocumentFile.fromTreeUri(this, fUri) ?: return Pair(null, null)
        var walletDir = tree.findFile(WALLET_DIR_NAME) ?: tree.createDirectory(WALLET_DIR_NAME)
        val wallet = walletDir ?: return Pair(null, null)
        var finman = wallet.findFile(FILE_FINMAN) ?: wallet.createFile("text/plain", FILE_FINMAN)
        var finhyst = wallet.findFile(FILE_FINHYST) ?: wallet.createFile("text/plain", FILE_FINHYST)
        return Pair(finman, finhyst)
    }

    private fun readTextFromUri(uri: Uri): String {
        val sb = StringBuilder()
        contentResolver.openInputStream(uri)?.use { input ->
            BufferedReader(InputStreamReader(input, StandardCharsets.UTF_8)).use { br ->
                var line = br.readLine()
                while (line != null) {
                    sb.append(line).append("\n")
                    line = br.readLine()
                }
            }
        }
        return sb.toString().trim()
    }

    private fun writeTextToDocument(document: DocumentFile, text: String) {
        contentResolver.openOutputStream(document.uri)?.use { os ->
            os.write(text.toByteArray(StandardCharsets.UTF_8))
            os.flush()
        }
    }

    private fun sha256Hex(chars: CharArray): String {
        val md = java.security.MessageDigest.getInstance("SHA-256")
        val digest = md.digest(String(chars).toByteArray(StandardCharsets.UTF_8))
        val sb = StringBuilder()
        digest.forEach { b -> sb.append(String.format("%02x", b)) }
        return sb.toString()
    }
}
