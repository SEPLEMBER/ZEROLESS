package com.nemesis.droidcrypt;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.Toast;
import androidx.appcompat.app.AppCompatActivity;

public class MainActivity extends AppCompatActivity {

    private Uri savedFolderUri;
    private static final int REQUEST_CODE_SETTINGS = 1;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        Button startChat = findViewById(R.id.startChatButton);
        Button openSettings = findViewById(R.id.openSettingsButton);

        startChat.setOnClickListener(v -> {
            if (savedFolderUri != null) {
                Intent intent = new Intent(MainActivity.this, ChatActivity.class);
                intent.putExtra("folderUri", savedFolderUri);
                startActivity(intent);
            } else {
                Toast.makeText(this, "Сначала выберите папку в настройках", Toast.LENGTH_SHORT).show();
            }
        });

        openSettings.setOnClickListener(v -> {
            Intent intent = new Intent(MainActivity.this, SettingsActivity.class);
            startActivityForResult(intent, REQUEST_CODE_SETTINGS);
        });
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST_CODE_SETTINGS && resultCode == RESULT_OK && data != null) {
            savedFolderUri = data.getParcelableExtra("folderUri");
        }
    }
}
