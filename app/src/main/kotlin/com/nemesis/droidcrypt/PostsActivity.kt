package com.nemesis.droidcrypt

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.Button
import androidx.appcompat.app.AppCompatActivity

class PostsActivity : AppCompatActivity() {

    companion object {
        private const val REQUEST_CODE_SETTINGS = 1001
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_posts)

        val startChat = findViewById<Button>(R.id.startChatButton)
        val openSettings = findViewById<Button>(R.id.openSettingsButton)

        startChat.setOnClickListener {
            // Просто стартуем ChatActivity — он сам попытается взять folderUri из SharedPreferences / persisted permissions
            val i = Intent(this@PostsActivity, ChatActivity::class.java)
            startActivity(i)
        }

        openSettings.setOnClickListener {
            // Запускаем SettingsActivity для выбора папки и редактирования шаблонов.
            // Ожидаем результат — при возвращении можем сразу открыть чат с переданным folderUri.
            val i = Intent(this@PostsActivity, SettingsActivity::class.java)
            startActivityForResult(i, REQUEST_CODE_SETTINGS)
        }
    }

    @Deprecated("onActivityResult is deprecated but kept for parity with original behavior")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_CODE_SETTINGS && resultCode == RESULT_OK && data != null) {
            // Если Settings вернул folderUri — передаём его в ChatActivity и открываем чат
            val folderUri = data.getParcelableExtra<Uri>("folderUri")
            val i = Intent(this@PostsActivity, ChatActivity::class.java)
            if (folderUri != null) {
                i.putExtra("folderUri", folderUri)
            }
            startActivity(i)
        }
    }
}
