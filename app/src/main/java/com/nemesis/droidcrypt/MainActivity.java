package com.nemesis.droidcrypt;

import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import android.preference.PreferenceManager;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.AutoCompleteTextView;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.documentfile.provider.DocumentFile;

import java.io.BufferedReader;
import java.io.FileInputStream;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

public class ChatActivity extends AppCompatActivity {

    private Uri folderUri;
    private TextView responseArea;
    private AutoCompleteTextView chatInput;
    private Button chatSend;
    private Button clearChatButton;
    private ArrayAdapter<String> adapter;

    // Fallback dummy suggestions
    private String[] fallback = {"Привет", "Как дела?", "Расскажи о себе", "Выход"};

    // Templates: trigger -> list of answers
    private Map<String, List<String>> templatesMap = new HashMap<>();
    // context file mapping: keyword -> fileName (например "сон" -> "health.txt")
    private Map<String, String> contextMap = new HashMap<>();

    private String currentContext = "base.txt";
    private Random random = new Random();

    private static final String PREF_KEY_FOLDER_URI = "pref_folder_uri";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_chat);

        // Получение folderUri: сначала из Intent, если нет — из SharedPreferences
        Intent intent = getIntent();
        if (intent != null && intent.hasExtra("folderUri")) {
            folderUri = intent.getParcelableExtra("folderUri");
        }
        if (folderUri == null) {
            SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(this);
            String saved = prefs.getString(PREF_KEY_FOLDER_URI, null);
            if (saved != null) {
                folderUri = Uri.parse(saved);
            }
        }

        responseArea = findViewById(R.id.responseArea);
        chatInput = findViewById(R.id.chatInput);
        chatSend = findViewById(R.id.chatSend);
        clearChatButton = findViewById(R.id.clearChatButton);

        // Если нет папки — предупредить, но не закрывать (можно дать возможность перейти в настройки)
        if (folderUri == null) {
            showCustomToast("Папка не выбрана! Зайдите в настройки и выберите папку.");
            // Не finish(), т.к. можно работать с fallback
            loadFallbackTemplates();
        } else {
            loadTemplatesFromFile(currentContext);
        }

        updateAutoComplete();

        chatInput.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
                String selected = (String) parent.getItemAtPosition(position);
                chatInput.setText(selected);
                processQuery(selected);
            }
        });

        chatSend.setOnClickListener(v -> {
            String input = chatInput.getText().toString().trim();
            if (!input.isEmpty()) {
                processQuery(input);
                chatInput.setText("");
            }
        });

        if (clearChatButton != null) {
            clearChatButton.setOnClickListener(v -> responseArea.setText(""));
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        // Если папку выбрали в настройках и передали результат — обновим
        Intent intent = getIntent();
        if (intent != null && intent.hasExtra("folderUri")) {
            Uri newUri = intent.getParcelableExtra("folderUri");
            if (newUri != null) {
                folderUri = newUri;
                // Сохраним в prefs на будущее
                SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(this);
                prefs.edit().putString(PREF_KEY_FOLDER_URI, folderUri.toString()).apply();
                // Перезагрузим текущий контекст
                loadTemplatesFromFile(currentContext);
            }
        }
        updateAutoComplete();
    }

    private void loadTemplatesFromFile(String filename) {
        templatesMap.clear();
        // Если мы загружаем base.txt — мы также парсим контекстные привязки
        if ("base.txt".equals(filename)) {
            contextMap.clear();
        }
        if (folderUri == null) {
            // Нет папки — используем fallback
            loadFallbackTemplates();
            return;
        }
        try {
            DocumentFile dir = DocumentFile.fromTreeUri(this, folderUri);
            if (dir == null || !dir.exists() || !dir.isDirectory()) {
                showCustomToast("Папка недоступна");
                loadFallbackTemplates();
                return;
            }
            DocumentFile file = dir.findFile(filename);
            if (file == null || !file.exists()) {
                showCustomToast("Файл " + filename + " не найден! Используем базовые ответы.");
                loadFallbackTemplates();
                return;
            }

            try (ParcelFileDescriptor pfd = getContentResolver().openFileDescriptor(file.getUri(), "r");
                 FileInputStream fis = new FileInputStream(pfd.getFileDescriptor());
                 BufferedReader reader = new BufferedReader(new InputStreamReader(fis))) {

                String line;
                while ((line = reader.readLine()) != null) {
                    String l = line.trim();
                    if (l.isEmpty()) continue;

                    // Формат контекстной ссылки в base.txt: :ключ=файл.txt:
                    if ("base.txt".equals(filename) && l.startsWith(":") && l.endsWith(":")) {
                        String contextLine = l.substring(1, l.length() - 1);
                        if (contextLine.contains("=")) {
                            String[] parts = contextLine.split("=", 2);
                            if (parts.length == 2) {
                                String keyword = parts[0].trim().toLowerCase();
                                String contextFile = parts[1].trim();
                                if (!keyword.isEmpty() && !contextFile.isEmpty()) {
                                    contextMap.put(keyword, contextFile);
                                }
                            }
                        }
                        continue; // не добавляем это в templatesMap
                    }

                    // Обычные шаблоны: trigger=ответ1|ответ2|...
                    if (!l.contains("=")) continue;
                    String[] parts = l.split("=", 2);
                    if (parts.length == 2) {
                        String trigger = parts[0].trim().toLowerCase();
                        String[] responses = parts[1].split("\\|");
                        List<String> list = new ArrayList<>();
                        for (String r : responses) {
                            String rr = r.trim();
                            if (!rr.isEmpty()) list.add(rr);
                        }
                        if (!trigger.isEmpty() && !list.isEmpty()) {
                            templatesMap.put(trigger, list);
                        }
                    }
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
            showCustomToast("Ошибка чтения файла: " + e.getMessage());
            loadFallbackTemplates();
        }
    }

    private void loadFallbackTemplates() {
        templatesMap.clear();
        contextMap.clear();
        List<String> list = new ArrayList<>();
        list.add("Привет! Чем могу помочь?");
        templatesMap.put("привет", list);
        // можно добавить ещё fallback-ответы
    }

    private void updateAutoComplete() {
        List<String> suggestionsList = new ArrayList<>();
        suggestionsList.addAll(templatesMap.keySet());
        for (String s : fallback) {
            if (!suggestionsList.contains(s.toLowerCase())) suggestionsList.add(s);
        }

        if (adapter == null) {
            adapter = new ArrayAdapter<>(this, android.R.layout.simple_dropdown_item_1line, suggestionsList);
            chatInput.setAdapter(adapter);
            chatInput.setThreshold(1);
        } else {
            adapter.clear();
            adapter.addAll(suggestionsList);
            adapter.notifyDataSetChanged();
        }
    }

    private void processQuery(String query) {
        String q = query.toLowerCase().trim();

        // Проверка на смену контекста (динамически из contextMap)
        String newContext = detectContext(q);
        if (newContext != null && !newContext.equals(currentContext)) {
            currentContext = newContext;
            loadTemplatesFromFile(currentContext);
            updateAutoComplete();
            responseArea.append("Ты: " + query + "\nBot: Переключено на контекст: " + currentContext + "\n\n");
            return;
        }

        // Поиск ответа в templatesMap (вариативный через random)
        List<String> possibleResponses = templatesMap.get(q);
        if (possibleResponses != null && !possibleResponses.isEmpty()) {
            String response = possibleResponses.get(random.nextInt(possibleResponses.size()));
            responseArea.append("Ты: " + query + "\nBot: " + response + "\n\n");
            return;
        }

        // Если нет ответа и не в base.txt — возвращаемся к base.txt
        if (!"base.txt".equals(currentContext)) {
            currentContext = "base.txt";
            loadTemplatesFromFile(currentContext);
            updateAutoComplete();
            responseArea.append("Ты: " + query + "\nBot: Не понял по теме. Вернулся к основному меню. Попробуй другой запрос.\n\n");
            return;
        }

        // Fallback в base.txt
        String response = getDummyResponse(q);
        responseArea.append("Ты: " + query + "\nBot: " + response + "\n\n");
    }

    private String detectContext(String input) {
        for (Map.Entry<String, String> entry : contextMap.entrySet()) {
            String keyword = entry.getKey();
            String contextFile = entry.getValue();
            if (input.contains(keyword)) {
                return contextFile;
            }
        }
        return null;
    }

    private String getDummyResponse(String query) {
        if (query.contains("привет")) return "Привет! Чем могу помочь?";
        if (query.contains("как дела")) return "Всё отлично, а у тебя?";
        return "Не понял запрос. Попробуй другой вариант.";
    }

    private void showCustomToast(String message) {
        try {
            LayoutInflater inflater = getLayoutInflater();
            View layout = inflater.inflate(R.layout.custom_toast, null);
            TextView text = layout.findViewById(R.id.customToastText);
            text.setText(message);
            Toast toast = new Toast(getApplicationContext());
            toast.setDuration(Toast.LENGTH_SHORT);
            toast.setView(layout);
            toast.show();
        } catch (Exception e) {
            Toast.makeText(this, message, Toast.LENGTH_SHORT).show();
        }
    }
}
