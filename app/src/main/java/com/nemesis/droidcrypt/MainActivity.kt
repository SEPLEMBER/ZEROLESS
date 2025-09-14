package com.nemesis.droidcrypt

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Button
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {

    companion object {
        private const val REQUEST_CODE_SETTINGS = 1001
    }

    private val settingsResultLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == RESULT_OK) {
            result.data?.getParcelableExtra<Uri>("folderUri")?.let { folderUri ->
                startActivity(Intent(this, ChatActivity::class.java).apply {
                    putExtra("folderUri", folderUri)
                })
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        findViewById<Button>(R.id.startChatButton).setOnClickListener {
            startActivity(Intent(this, ChatActivity::class.java))
        }

        findViewById<Button>(R.id.openSettingsButton).setOnClickListener {
            settingsResultLauncher.launch(Intent(this, SettingsActivity::class.java))
        }
    }
}
