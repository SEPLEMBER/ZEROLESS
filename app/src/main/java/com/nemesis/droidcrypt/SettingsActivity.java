package com.nemesis.droidcrypt;

import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.preference.PreferenceManager;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.Toast;
import androidx.appcompat.app.AppCompatActivity;

public class SettingsActivity extends AppCompatActivity {

    private SharedPreferences prefs;
    private Button clearCacheButton;
    private Button backButton;
    private Button saveTemplatesButton;
    private EditText templatesInput; // multiline input for bulk templates

    private static final String KEY_TEMPLATES = "templates"; // stored as normalized multiline string (\n separated)

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);

        prefs = PreferenceManager.getDefaultSharedPreferences(this);

        clearCacheButton = findViewById(R.id.clearCacheButton);
        backButton = findViewById(R.id.backButton);
        saveTemplatesButton = findViewById(R.id.saveTemplatesButton);
        templatesInput = findViewById(R.id.templatesInput);

        // Load existing templates into the multiline EditText
        String existing = prefs.getString(KEY_TEMPLATES, "");
        templatesInput.setText(existing);

        clearCacheButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                prefs.edit().clear().apply(); // Clear internal SharedPrefs
                templatesInput.setText("");
                Toast.makeText(SettingsActivity.this, "SharedPrefs очищены", Toast.LENGTH_SHORT).show();
            }
        });

        saveTemplatesButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                String all = templatesInput.getText().toString();
                if (all.trim().isEmpty()) {
                    prefs.edit().remove(KEY_TEMPLATES).apply();
                    Toast.makeText(SettingsActivity.this, "Шаблоны очищены", Toast.LENGTH_SHORT).show();
                    return;
                }

                // Разбиваем по пустым строкам / переводу строки, нормализуем
                String[] lines = all.split("\\r?\\n");
                StringBuilder sb = new StringBuilder();
                int saved = 0;
                int skipped = 0;
                for (String raw : lines) {
                    String line = raw.trim();
                    if (line.isEmpty()) continue; // пропускаем пустые строки

                    // Допустимый формат: триггер=ответ. Если нет, пропускаем.
                    if (!line.contains("=")) {
                        skipped++;
                        continue;
                    }
                    String[] parts = line.split("=", 2);
                    String key = parts[0].trim();
                    String val = parts.length > 1 ? parts[1].trim() : "";
                    if (key.isEmpty()) { skipped++; continue; }

                    if (sb.length() > 0) sb.append('\n');
                    // Сохраняем нормализованную строку key=value
                    sb.append(key).append("=").append(val);
                    saved++;
                }

                prefs.edit().putString(KEY_TEMPLATES, sb.toString()).apply();
                Toast.makeText(SettingsActivity.this, "Сохранено: " + saved + ", пропущено: " + skipped, Toast.LENGTH_SHORT).show();
            }
        });

        backButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Intent intent = new Intent(SettingsActivity.this, MainActivity.class);
                startActivity(intent);
            }
        });
    }
}
