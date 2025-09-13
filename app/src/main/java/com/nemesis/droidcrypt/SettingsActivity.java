package com.nemesis.droidcrypt;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import android.preference.PreferenceManager;
import android.content.SharedPreferences;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.Toast;
import androidx.appcompat.app.AppCompatActivity;
import androidx.documentfile.provider.DocumentFile;

import java.io.FileOutputStream;

public class SettingsActivity extends AppCompatActivity {

    private Uri folderUri;
    private Button selectFolderButton;
    private Button clearTemplatesButton;
    private Button backButton;
    private Button saveTemplatesButton;
    private EditText templatesInput;

    private static final int REQUEST_CODE_OPEN_DIRECTORY = 1;
    private static final String PREF_KEY_FOLDER_URI = "pref_folder_uri";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);

        selectFolderButton = findViewById(R.id.selectFolderButton);
        clearTemplatesButton = findViewById(R.id.clearTemplatesButton);
        backButton = findViewById(R.id.backButton);
        saveTemplatesButton = findViewById(R.id.saveTemplatesButton);
        templatesInput = findViewById(R.id.templatesInput);

        // Если ранее выбирали папку — подхватим её из SharedPreferences
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(this);
        String savedUri = prefs.getString(PREF_KEY_FOLDER_URI, null);
        if (savedUri != null) {
            folderUri = Uri.parse(savedUri);
        }

        // Initially clear input
        templatesInput.setText("");

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
            // Если пусто — перезаписать файл пустым содержимым (очистить)
            saveTemplatesToFile("base.txt", all);
        });

        backButton.setOnClickListener(v -> {
            // Вернём folderUri в вызывающую Activity (опционально) и завершение
            Intent resultIntent = new Intent();
            if (folderUri != null) resultIntent.putExtra("folderUri", folderUri);
            setResult(RESULT_OK, resultIntent);
            finish();
        });
    }

    private void openFolderPicker() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        startActivityForResult(intent, REQUEST_CODE_OPEN_DIRECTORY);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST_CODE_OPEN_DIRECTORY && resultCode == RESULT_OK && data != null) {
            folderUri = data.getData();
            if (folderUri != null) {
                try {
                    // Сохраняем права и persist URI
                    getContentResolver().takePersistableUriPermission(
                            folderUri,
                            Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                    );
                    // Сохраняем URI в SharedPreferences, чтобы не выбирать снова
                    SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(this);
                    prefs.edit().putString(PREF_KEY_FOLDER_URI, folderUri.toString()).apply();

                    Toast.makeText(this, "Папка выбрана", Toast.LENGTH_SHORT).show();
                    // Попробуем загрузить base.txt (необязательно)
                    loadTemplatesFromFile("base.txt");
                } catch (SecurityException e) {
                    Toast.makeText(this, "Ошибка прав доступа: " + e.getMessage(), Toast.LENGTH_SHORT).show();
                    folderUri = null;
                }
            } else {
                Toast.makeText(this, "Ошибка: папка не выбрана", Toast.LENGTH_SHORT).show();
            }
        }
    }

    private void saveTemplatesToFile(String filename, String content) {
        if (folderUri == null) {
            Toast.makeText(this, "Папка не выбрана", Toast.LENGTH_SHORT).show();
            return;
        }
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
                fos.flush();
                Toast.makeText(this, "Сохранено успешно", Toast.LENGTH_SHORT).show();
            }
        } catch (Exception e) {
            e.printStackTrace();
            Toast.makeText(this, "Ошибка сохранения: " + e.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }

    // Пробная функция: загрузить base.txt и показать в поле (используется в onActivityResult)
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
                return;
            }
            try (ParcelFileDescriptor pfd = getContentResolver().openFileDescriptor(file.getUri(), "r");
                 FileInputStream fis = new FileInputStream(pfd.getFileDescriptor());
                 BufferedReader reader = new BufferedReader(new InputStreamReader(fis))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (sb.length() > 0) sb.append("\n");
                    sb.append(line);
                }
                templatesInput.setText(sb.toString());
            }
        } catch (Exception e) {
            e.printStackTrace();
            Toast.makeText(this, "Ошибка загрузки: " + e.getMessage(), Toast.LENGTH_SHORT).show();
            templatesInput.setText("");
        }
    }
}
