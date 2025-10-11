package com.nemesis.droidcrypt

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.DocumentsContract;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResult;
import androidx.activity.result.ActivityResultCallback;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.documentfile.provider.DocumentFile;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class StreetFoodActivity extends AppCompatActivity {

    private static final String PREFS = "Pawstribe_prefs";
    private static final String KEY_PIN = "pin_code";
    private static final String DEFAULT_PIN = "5387";
    private static final int SAF_REQUEST_CODE = 42;
    private static final long CLICK_INTERVAL = 500; // ms for triple click

    private TextView[] dots;
    private TextView errorText;
    private ProgressBar progressBar;
    private TextView progressText;
    private TextView panicButton;
    private LinearLayout keypad;
    private LinearLayout centerColumn;

    private String currentPin = "";
    private String storedPin;
    private boolean isChangingPin = false;

    private File safeDir;
    private long lastPanicClickTime = 0;
    private int panicClickCount = 0;

    private enum ConflictAction {
        SKIP, SKIP_ALL, MERGE, OVERWRITE, OVERWRITE_ALL
    }

    private ConflictAction globalAction = null;

    private final ActivityResultLauncher<Intent> safLauncher = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(),
            new ActivityResultCallback<ActivityResult>() {
                @Override
                public void onActivityResult(ActivityResult result) {
                    if (result.getResultCode() == RESULT_OK && result.getData() != null) {
                        Uri uri = result.getData().getData();
                        if (uri != null) {
                            getContentResolver().takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
                            importFromSaf(uri);
                        }
                    }
                }
            });

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_street_food);

        // Инициализация UI элементов
        dots = new TextView[]{
                findViewById(R.id.d1),
                findViewById(R.id.d2),
                findViewById(R.id.d3),
                findViewById(R.id.d4)
        };
        errorText = findViewById(R.id.errorText);
        progressBar = findViewById(R.id.progressBar); // Нужно добавить в XML
        progressText = findViewById(R.id.progressText); // Нужно добавить в XML
        panicButton = findViewById(R.id.btnPanic); // Нужно добавить в XML как TextView
        keypad = findViewById(R.id.keypad);
        centerColumn = findViewById(R.id.centerColumn);

        // Убрать центральный текст, если был (centerText в старом коде)
        // Предполагаем, что его нет в обновлённом XML

        // Настройка темы: neon cyan для текста
        int neonCyan = 0xFF00FFFF; // #00FFFF
        for (TextView dot : dots) {
            dot.setTextColor(neonCyan);
        }
        errorText.setTextColor(0xFFFF5252); // Красный для ошибок
        panicButton.setTextColor(neonCyan);
        panicButton.setText("PANIC");
        panicButton.setOnClickListener(this::onPanicClick);

        // Настройка клавиатуры: TextView вместо Button
        setupKeypadListeners();

        // Получить приватную папку на SD
        File[] externalDirs = getExternalFilesDirs(null);
        if (externalDirs.length > 1 && externalDirs[1] != null) {
            safeDir = new File(externalDirs[1], "safe");
            safeDir.mkdirs();
        } else {
            Toast.makeText(this, "SD-карта не найдена", Toast.LENGTH_SHORT).show();
            finish();
            return;
        }

        // Загрузить сохранённый PIN
        SharedPreferences prefs = getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        storedPin = prefs.getString(KEY_PIN, DEFAULT_PIN);

        // Показать клавиатуру для ввода PIN
        showPinEntry();
    }

    private void setupKeypadListeners() {
        int[] btnIds = {R.id.btn1, R.id.btn2, R.id.btn3, R.id.btn4, R.id.btn5, R.id.btn6, R.id.btn7, R.id.btn8, R.id.btn9, R.id.btn0, R.id.btnDelete};
        for (int id : btnIds) {
            TextView btn = findViewById(id);
            btn.setTextColor(0xFF00FFFF); // neon cyan
            btn.setOnClickListener(v -> onKeypadClick(btn.getText().toString()));
        }
    }

    private void onKeypadClick(String key) {
        if (key.equals("⌫")) {
            if (currentPin.length() > 0) {
                currentPin = currentPin.substring(0, currentPin.length() - 1);
            }
        } else {
            if (currentPin.length() < 4) {
                currentPin += key;
            }
        }
        updateDots();

        if (currentPin.length() == 4) {
            if (isChangingPin) {
                // Смена PIN
                SharedPreferences prefs = getSharedPreferences(PREFS, Context.MODE_PRIVATE);
                prefs.edit().putString(KEY_PIN, currentPin).apply();
                storedPin = currentPin;
                Toast.makeText(this, "PIN изменён", Toast.LENGTH_SHORT).show();
                isChangingPin = false;
                showSafeUI();
            } else {
                // Проверка PIN
                if (currentPin.equals(storedPin)) {
                    showSafeUI();
                } else {
                    errorText.setVisibility(View.VISIBLE);
                    currentPin = "";
                    updateDots();
                    Handler handler = new Handler(Looper.getMainLooper());
                    handler.postDelayed(() -> errorText.setVisibility(View.GONE), 2000);
                }
            }
        }
    }

    private void updateDots() {
        for (int i = 0; i < 4; i++) {
            dots[i].setText(i < currentPin.length() ? "°" : "°"); // Всегда °, но можно изменить для заполненных
        }
    }

    private void showPinEntry() {
        centerColumn.setVisibility(View.VISIBLE);
        keypad.setVisibility(View.VISIBLE);
        panicButton.setVisibility(View.GONE); // PANIC после входа
        progressBar.setVisibility(View.GONE);
        progressText.setVisibility(View.GONE);
        currentPin = "";
        updateDots();
        errorText.setVisibility(View.GONE);
    }

    private void showSafeUI() {
        centerColumn.setVisibility(View.GONE);
        keypad.setVisibility(View.GONE);
        panicButton.setVisibility(View.VISIBLE);
        // Здесь добавить UI для сейфа, например, кнопку "Импорт"
        findViewById(R.id.btnImport).setVisibility(View.VISIBLE); // Нужно добавить в XML TextView btnImport
        findViewById(R.id.btnImport).setOnClickListener(v -> startSafImport());
        // Для смены PIN добавить кнопку
        findViewById(R.id.btnChangePin).setVisibility(View.VISIBLE); // Нужно добавить
        findViewById(R.id.btnChangePin).setOnClickListener(v -> {
            isChangingPin = true;
            showPinEntry();
        });
    }

    private void startSafImport() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
        safLauncher.launch(intent);
    }

    private void importFromSaf(Uri treeUri) {
        showProgress(true);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        executor.execute(() -> {
            try {
                DocumentFile sourceDir = DocumentFile.fromTreeUri(this, treeUri);
                if (sourceDir != null) {
                    copyRecursive(sourceDir, safeDir);
                }
                runOnUiThread(() -> Toast.makeText(this, "Импорт завершён", Toast.LENGTH_SHORT).show());
            } catch (Exception e) {
                runOnUiThread(() -> Toast.makeText(this, "Ошибка импорта: " + e.getMessage(), Toast.LENGTH_SHORT).show());
            } finally {
                runOnUiThread(() -> showProgress(false));
            }
        });
    }

    private void copyRecursive(DocumentFile source, File targetDir) throws IOException {
        if (source.isDirectory()) {
            File newDir = new File(targetDir, source.getName());
            if (newDir.exists()) {
                ConflictAction action = getConflictAction(newDir.getName(), true);
                if (action == ConflictAction.SKIP || action == ConflictAction.SKIP_ALL) return;
                if (action == ConflictAction.OVERWRITE || action == ConflictAction.OVERWRITE_ALL) {
                    deleteRecursive(newDir);
                }
                // Для MERGE продолжаем копировать внутрь
            } else {
                newDir.mkdirs();
            }
            for (DocumentFile child : source.listFiles()) {
                copyRecursive(child, newDir);
            }
        } else {
            File targetFile = new File(targetDir, source.getName());
            if (targetFile.exists()) {
                ConflictAction action = getConflictAction(targetFile.getName(), false);
                if (action == ConflictAction.SKIP || action == ConflictAction.SKIP_ALL) return;
                if (action == ConflictAction.MERGE) return; // Для файлов MERGE = SKIP
                if (action == ConflictAction.OVERWRITE || action == ConflictAction.OVERWRITE_ALL) {
                    targetFile.delete();
                }
            }
            try (InputStream in = getContentResolver().openInputStream(source.getUri());
                 OutputStream out = new FileOutputStream(targetFile)) {
                byte[] buffer = new byte[1024];
                int len;
                while ((len = in.read(buffer)) > 0) {
                    out.write(buffer, 0, len);
                }
            }
        }
    }

    private ConflictAction getConflictAction(String name, boolean isDir) {
        if (globalAction != null) {
            switch (globalAction) {
                case SKIP_ALL: return ConflictAction.SKIP_ALL;
                case OVERWRITE_ALL: return ConflictAction.OVERWRITE_ALL;
            }
        }
        final ConflictAction[] result = new ConflictAction[1];
        runOnUiThread(() -> {
            MaterialAlertDialogBuilder builder = new MaterialAlertDialogBuilder(this);
            builder.setTitle("Конфликт: " + name + " существует");
            String[] options = isDir ? new String[]{"Пропустить", "Пропустить все", "Объединить", "Перезаписать", "Перезаписать всё"}
                    : new String[]{"Пропустить", "Пропустить все", "Перезаписать", "Перезаписать всё"};
            builder.setItems(options, (dialog, which) -> {
                if (isDir) {
                    switch (which) {
                        case 0: result[0] = ConflictAction.SKIP; break;
                        case 1: globalAction = ConflictAction.SKIP_ALL; result[0] = ConflictAction.SKIP_ALL; break;
                        case 2: result[0] = ConflictAction.MERGE; break;
                        case 3: result[0] = ConflictAction.OVERWRITE; break;
                        case 4: globalAction = ConflictAction.OVERWRITE_ALL; result[0] = ConflictAction.OVERWRITE_ALL; break;
                    }
                } else {
                    switch (which) {
                        case 0: result[0] = ConflictAction.SKIP; break;
                        case 1: globalAction = ConflictAction.SKIP_ALL; result[0] = ConflictAction.SKIP_ALL; break;
                        case 2: result[0] = ConflictAction.OVERWRITE; break;
                        case 3: globalAction = ConflictAction.OVERWRITE_ALL; result[0] = ConflictAction.OVERWRITE_ALL; break;
                    }
                }
                synchronized (result) {
                    result.notify();
                }
            });
            builder.show();
        });
        synchronized (result) {
            try {
                result.wait();
            } catch (InterruptedException e) {
                // ignore
            }
        }
        return result[0];
    }

    private void deleteRecursive(File file) {
        if (file.isDirectory()) {
            for (File child : file.listFiles()) {
                deleteRecursive(child);
            }
        }
        file.delete();
    }

    private void onPanicClick(View v) {
        long currentTime = System.currentTimeMillis();
        if (currentTime - lastPanicClickTime < CLICK_INTERVAL) {
            panicClickCount++;
            if (panicClickCount >= 3) {
                deleteAll();
                panicClickCount = 0;
            }
        } else {
            panicClickCount = 1;
        }
        lastPanicClickTime = currentTime;
    }

    private void deleteAll() {
        showProgress(true);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        executor.execute(() -> {
            deleteRecursive(safeDir);
            safeDir.mkdirs(); // Пересоздать пустую
            runOnUiThread(() -> {
                Toast.makeText(this, "Все файлы удалены", Toast.LENGTH_SHORT).show();
                showProgress(false);
            });
        });
    }

    private void showProgress(boolean show) {
        progressBar.setVisibility(show ? View.VISIBLE : View.GONE);
        progressText.setVisibility(show ? View.VISIBLE : View.GONE);
        progressText.setText("В процессе...");
        progressText.setTextColor(0xFF00FFFF); // neon cyan
    }
}

        statusText.visibility = View.GONE

        btnSave.setOnClickListener {
            statusText.visibility = View.GONE
            val newPin = editPin.text.toString().trim()
            if (newPin.length == 4 && newPin.all { it.isDigit() }) {
                prefs.edit().putString(KEY_PIN, newPin).apply()
                statusText.setTextColor(resources.getColor(android.R.color.white, theme))
                statusText.text = "PIN сохранён"
                statusText.visibility = View.VISIBLE

                // Обновить hint
                editPin.hint = "Текущий: $newPin"
                editPin.text.clear()

                // скрыть сообщение через 2 секунды
                Handler(Looper.getMainLooper()).postDelayed({
                    statusText.visibility = View.GONE
                }, 2000L)
            } else {
                statusText.setTextColor(resources.getColor(android.R.color.holo_red_light, theme))
                statusText.text = "Введите 4 цифры"
                statusText.visibility = View.VISIBLE
            }
        }
    }
}
