package com.nemesis.droidcrypt;

import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.documentfile.provider.DocumentFile;
import android.preference.PreferenceManager;

import java.io.BufferedReader;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;

public class SettingsActivity extends AppCompatActivity {

    private Uri folderUri;
    private Button selectFolderButton;
    private Button clearTemplatesButton;
    private Button backButton;
    private Button saveTemplatesButton;
    private EditText templatesInput;

    private static final int REQUEST_CODE_OPEN_DIRECTORY = 1;
    private static final String PREF_KEY_FOLDER_URI = "pref_folder_uri";

    private SharedPreferences prefs;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);

        prefs = PreferenceManager.getDefaultSharedPreferences(this);

        selectFolderButton = findViewById(R.id.selectFolderButton);
        clearTemplatesButton = findViewById(R.id.clearTemplatesButton);
        backButton = findViewById(R.id.backButton);
        saveTemplatesButton = findViewById(R.id.saveTemplatesButton);
        templatesInput = findViewById(R.id.templatesInput);

        // Initially clear input
        templatesInput.setText("");

        // Try to restore previously persisted folder URI from SharedPreferences
        String saved = prefs.getString(PREF_KEY_FOLDER_URI, null);
        if (saved != null) {
            try {
                folderUri = Uri.parse(saved);
                // Try to take persisted permission again if possible (may throw if not granted)
                try {
                    getContentResolver().takePersistableUriPermission(
                            folderUri,
                            Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                    );
                } catch (SecurityException ignored) {
                    // ignore - permission might already be granted or cannot be re-taken
                }
                // Try to load existing base.txt into the EditText if available
                loadTemplatesFromFile("base.txt");
            } catch (Exception e) {
                folderUri = null;
            }
        }

        selectFolderButton.setOnClickListener(v -> openFolderPicker());

        clearTemplatesButton.setOnClickListener(v -> {
            templatesInput.setText("");
            Toast.makeText(SettingsActivity.this, "Поле очищено", Toast.LENGTH_SHORT).show();
        });

        saveTemplatesButton.setOnClickListener(v -> {
            if (folderUri == null) {
                Toast.makeText(SettingsActivity.this, "Сначала выберите папку", Toast.LENGTH_SHORT).show();
                return;
            }
            String all = templatesInput.getText().toString();
            if (all.trim().isEmpty()) {
                saveTemplatesToFile("base.txt", "");
                Toast.makeText(SettingsActivity.this, "Шаблоны очищены", Toast.LENGTH_SHORT).show();
                return;
            }
            String[] lines = all.split("\\r?\\n");
            StringBuilder sb = new StringBuilder();
            int savedCount = 0;
            int skipped = 0;
            for (String raw : lines) {
                String line = raw.trim();
                if (line.isEmpty()) continue;
                if (!line.contains("=")) {
                    skipped++;
                    continue;
                }
                String[] parts = line.split("=", 2);
                String key = parts[0].trim().toLowerCase();
                String val = parts.length > 1 ? parts[1].trim() : "";
                if (key.isEmpty()) {
                    skipped++;
                    continue;
                }
                if (sb.length() > 0) sb.append('\n');
                sb.append(key).append("=").append(val);
                savedCount++;
            }
            String content = sb.toString();
            saveTemplatesToFile("base.txt", content);
            Toast.makeText(SettingsActivity.this, "Сохранено: " + savedCount + ", пропущено: " + skipped, Toast.LENGTH_SHORT).show();
        });

        backButton.setOnClickListener(v -> {
            Intent resultIntent = new Intent();
            resultIntent.putExtra("folderUri", folderUri);
            setResult(RESULT_OK, resultIntent);
            finish();
        });
    }

    private void openFolderPicker() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
        // Allow persisted permissions
        intent.addFlags(Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        startActivityForResult(intent, REQUEST_CODE_OPEN_DIRECTORY);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST_CODE_OPEN_DIRECTORY && resultCode == RESULT_OK && data != null) {
            Uri uri = data.getData();
            if (uri != null) {
                folderUri = uri;
                try {
                    // Persist permissions so we can access later without re-prompt
                    getContentResolver().takePersistableUriPermission(
                            folderUri,
                            Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                    );
                } catch (SecurityException e) {
                    // may happen on some devices - still continue
                }

                // Save chosen folder URI string to SharedPreferences
                prefs.edit().putString(PREF_KEY_FOLDER_URI, folderUri.toString()).apply();

                Toast.makeText(this, "Папка выбрана", Toast.LENGTH_SHORT).show();
                // Попробуем загрузить файл base.txt чтобы показать содержимое сразу
                loadTemplatesFromFile("base.txt");
            } else {
                Toast.makeText(this, "Ошибка: папка не выбрана", Toast.LENGTH_SHORT).show();
            }
        }
    }

    private void saveTemplatesToFile(String filename, String content) {
        try {
            DocumentFile dir = DocumentFile.fromTreeUri(this, folderUri);
            if (dir == null || !dir.exists() || !dir.isDirectory()) {
                Toast.makeText(this, "Ошибка: папка недоступна", Toast.LENGTH_SHORT).show();
                return;
            }
            DocumentFile file = dir.findFile(filename);
            if (file == null) {
                file = dir.createFile("text/plain", filename);
            }
            if (file == null) {
                Toast.makeText(this, "Ошибка создания файла", Toast.LENGTH_SHORT).show();
                return;
            }
            try (ParcelFileDescriptor pfd = getContentResolver().openFileDescriptor(file.getUri(), "w");
                 FileOutputStream fos = new FileOutputStream(pfd.getFileDescriptor())) {
                fos.write(content.getBytes());
                Toast.makeText(this, "Сохранено успешно", Toast.LENGTH_SHORT).show();
            }
        } catch (Exception e) {
            Toast.makeText(this, "Ошибка сохранения: " + e.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }

    private void loadTemplatesFromFile(String filename) {
        StringBuilder sb = new StringBuilder();
        try {
            DocumentFile dir = DocumentFile.fromTreeUri(this, folderUri);
            if (dir == null || !dir.exists() || !dir.isDirectory()) {
                Toast.makeText(this, "Ошибка: папка недоступна", Toast.LENGTH_SHORT).show();
                templatesInput.setText("");
                return;
            }
            DocumentFile file = dir.findFile(filename);
            if (file == null || !file.exists()) {
                templatesInput.setText("");
                return; // Не показываем Toast, файл может отсутствовать по умолчанию
            }
            try (ParcelFileDescriptor pfd = getContentResolver().openFileDescriptor(file.getUri(), "r");
                 FileInputStream fis = new FileInputStream(pfd.getFileDescriptor());
                 BufferedReader reader = new BufferedReader(new InputStreamReader(fis))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (sb.length() > 0) sb.append("\n");
                    sb.append(line);
                }
                String content = sb.toString();
                templatesInput.setText(content);
            }
        } catch (Exception e) {
            Toast.makeText(this, "Ошибка загрузки: " + e.getMessage(), Toast.LENGTH_SHORT).show();
            templatesInput.setText("");
        }
    }
}
