import android.content.Intent
import android.content.SharedPreferences
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.documentfile.provider.DocumentFile
import java.io.BufferedReader
import java.io.FileInputStream
import java.io.InputStreamReader

class SplashActivity : AppCompatActivity() {

    private lateinit var prefs: SharedPreferences
    private var folderUri: Uri? = null

    private lateinit var splashImage: ImageView
    private lateinit var logoMini: ImageView
    private lateinit var statusText: TextView
    private lateinit var metadataText: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_splash)

        splashImage = findViewById(R.id.splashImage)
        logoMini = findViewById(R.id.logoMini)
        statusText = findViewById(R.id.statusText)
        metadataText = findViewById(R.id.metadataText)

        prefs = getSharedPreferences("my_prefs", MODE_PRIVATE)
        prefs.getString("pref_folder_uri", null)?.let { saved ->
            folderUri = Uri.parse(saved)
        }

        // Загружаем сплэш и метаданные
        loadImageFromSAF("splash_engine.png", splashImage)
        loadImageFromSAF("logo_mini_.png", logoMini)

        val engineMeta = loadTextFromSAF("engine_metadata.txt")
        val uiMeta = loadTextFromSAF("UI_metadata.txt")
        metadataText.text = (engineMeta + "\n" + uiMeta).trim()

        // Через 5 секунд меняем статус и переходим в чат
        Handler(Looper.getMainLooper()).postDelayed({
            statusText.text = "с подключением."
            startActivity(Intent(this, ChatActivity::class.java))
            finish()
        }, 5000)
    }

    private fun loadImageFromSAF(filename: String, target: ImageView) {
        try {
            folderUri?.let { uri ->
                val dir = DocumentFile.fromTreeUri(this, uri)
                val file = dir?.findFile(filename)
                if (file != null && file.exists()) {
                    contentResolver.openFileDescriptor(file.uri, "r")?.use { pfd ->
                        FileInputStream(pfd.fileDescriptor).use { fis ->
                            val bmp = BitmapFactory.decodeStream(fis)
                            runOnUiThread { target.setImageBitmap(bmp) }
                        }
                    }
                }
            }
        } catch (_: Exception) {
        }
    }

    private fun loadTextFromSAF(filename: String): String {
        return try {
            folderUri?.let { uri ->
                val dir = DocumentFile.fromTreeUri(this, uri)
                val file = dir?.findFile(filename)
                if (file != null && file.exists()) {
                    contentResolver.openFileDescriptor(file.uri, "r")?.use { pfd ->
                        FileInputStream(pfd.fileDescriptor).use { fis ->
                            BufferedReader(InputStreamReader(fis)).use { reader ->
                                return reader.readText()
                            }
                        }
                    }
                }
            }
            ""
        } catch (e: Exception) {
            ""
        }
    }
}
