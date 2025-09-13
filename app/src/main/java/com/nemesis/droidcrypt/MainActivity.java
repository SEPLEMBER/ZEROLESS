package com.nemesis.droidcrypt;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

public class MainActivity extends AppCompatActivity {

    private static final int REQUEST_CODE_SETTINGS = 1001;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        Button startChat = findViewById(R.id.startChatButton);
        Button openSettings = findViewById(R.id.openSettingsButton);

        startChat.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                // Просто стартуем ChatActivity — он сам попытается взять folderUri из SharedPreferences / persisted permissions
                Intent i = new Intent(MainActivity.this, ChatActivity.class);
                startActivity(i);
            }
        });

        openSettings.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                // Запускаем SettingsActivity для выбора папки и редактирования шаблонов.
                // Ожидаем результат — при возвращении можем сразу открыть чат с переданным folderUri.
                Intent i = new Intent(MainActivity.this, SettingsActivity.class);
                startActivityForResult(i, REQUEST_CODE_SETTINGS);
            }
        });
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST_CODE_SETTINGS && resultCode == RESULT_OK && data != null) {
            // Если Settings вернул folderUri — передаём его в ChatActivity и открываем чат
            Uri folderUri = data.getParcelableExtra("folderUri");
            Intent i = new Intent(MainActivity.this, ChatActivity.class);
            if (folderUri != null) {
                i.putExtra("folderUri", folderUri);
            }
            startActivity(i);
        }
    }
}
