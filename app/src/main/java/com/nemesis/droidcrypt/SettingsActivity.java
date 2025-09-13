package com.nemesis.droidcrypt;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.Toast;
import androidx.appcompat.app.AppCompatActivity;
import androidx.documentfile.provider.DocumentFile;

import java.io.BufferedReader;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;

public class SettingsActivity extends AppCompatActivity {

    private Uri folderUri;
    private Button selectFolderButton;
    private Button clearTemplatesButton;
    private Button backButton;
    private Button saveTemplatesButton;
    private EditText templatesInput; // multiline input for bulk templates

    private static final int REQUEST_CODE_OPEN_DIRECTORY = 1;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);

        selectFolderButton = findViewById(R.id.selectFolderButton);
        clearTemplatesButton = findViewById(R.id.clearTemplatesButton);
        backButton = findViewById(R.id.backButton);
        saveTemplatesButton = findViewById(R.id.saveTemplatesButton);
        templatesInput = findViewById(R.id.templatesInput);

        // Initially clear input
        templatesInput.setText("");

        selectFolderButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                openFolderPicker();
            }
        });

        clearTemplatesButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                templatesInput.setText("");
                Toast.makeText(SettingsActivity.this, "Поле очищено", Toast.LENGTH_SHORT).show();
            }
        });

        saveTemplatesButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
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
                    String key = parts[0].trim().toLowerCase();
                    String val = parts.length > 1 ? parts[1].trim() : "";
                    if (key.isEmpty()) { skipped++; continue; }

                    if (sb.length() > 0) sb.append('\n');
                    // Сохраняем нормализованную строку key=value (val может содержать | для нескольких ответов)
                    sb.append(key).append("=").append(val);
                    saved++;
                }

                String content = sb.toString();
                saveTemplatesToFile("base.txt", content);
                Toast.makeText(SettingsActivity.this, "Сохранено: " + saved + ", пропущено: " + skipped, Toast.LENGTH_SHORT).show();
            }
        });

        backButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Intent resultIntent = new Intent();
                resultIntent.putExtra("folderUri", folderUri);
                setResult(RESULT_OK, resultIntent);
                finish();
            }
        });
    }

    private void openFolderPicker() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
        startActivityForResult(intent, REQUEST_CODE_OPEN_DIRECTORY);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST_CODE_OPEN_DIRECTORY && resultCode == RESULT_OK) {
            folderUri = data.getData();
            if (folderUri != null) {
                getContentResolver().takePersistableUriPermission(
                    folderUri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                );
                loadTemplatesFromFile("base.txt");
                Toast.makeText(this, "Папка выбрана", Toast.LENGTH_SHORT).show();
            }
        }
    }

    private void saveTemplatesToFile(String filename, String content) {
        try {
            DocumentFile dir = DocumentFile.fromTreeUri(this, folderUri);
            if (dir == null) {
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

            Uri fileUri = file.getUri();
            ParcelFileDescriptor pfd = getContentResolver().openFileDescriptor(fileUri, "w");
            if (pfd == null) {
                Toast.makeText(this, "Ошибка доступа к файлу", Toast.LENGTH_SHORT).show();
                return;
            }

            FileOutputStream fos = new FileOutputStream(pfd.getFileDescriptor());
            fos.write(content.getBytes());
            fos.close();
            pfd.close();
            Toast.makeText(this, "Сохранено успешно", Toast.LENGTH_SHORT).show();
        } catch (Exception e) {
            Toast.makeText(this, "Ошибка сохранения: " + e.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }

    private void loadTemplatesFromFile(String filename) {
        StringBuilder sb = new StringBuilder();
        try {
            Uri fileUri = Uri.withAppendedPath(folderUri, filename);
            ParcelFileDescriptor pfd = getContentResolver().openFileDescriptor(fileUri, "r");
            if (pfd == null) {
                // Файл не существует, очищаем поле
                templatesInput.setText("");
                return;
            }

            FileInputStream fis = new FileInputStream(pfd.getFileDescriptor());
            BufferedReader reader = new BufferedReader(new InputStreamReader(fis));
            String line;
            while ((line = reader.readLine()) != null) {
                if (sb.length() > 0) sb.append("\n");
                sb.append(line);
            }
            reader.close();
            fis.close();
            pfd.close();

            String content = sb.toString();
            templatesInput.setText(content);
        } catch (Exception e) {
            Toast.makeText(this, "Ошибка загрузки: " + e.getMessage(), Toast.LENGTH_SHORT).show();
            templatesInput.setText("");
        }
    }
}
