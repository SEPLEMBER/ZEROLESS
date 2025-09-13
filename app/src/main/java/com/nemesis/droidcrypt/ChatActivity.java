package com.nemesis.droidcrypt;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.AutoCompleteTextView;
import android.widget.Button;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.documentfile.provider.DocumentFile;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

public class ChatActivity extends AppCompatActivity {

    private Uri folderUri;
    private TextView responseArea;
    private ScrollView responseScrollView; // Добавлено для управления скроллом
    private AutoCompleteTextView chatInput;
    private Button chatSend;
    private Button clearChatButton;
    private ArrayAdapter<String> adapter;

    // Fallback dummy suggestions
    private String[] fallback = {"Привет", "Как дела?", "Расскажи о себе", "Выход"};

    // Templates: trigger -> list of answers
    private Map<String, List<String>> templatesMap = new HashMap<>();

    // context keyword -> file (e.g., "сон" -> "health.txt")
    private Map<String, String> contextMap = new HashMap<>();

    private String currentContext = "base.txt";
    private Random random = new Random();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_chat);

        // Получение folderUri из Intent
        Intent intent = getIntent();
        folderUri = intent != null ? intent.getParcelableExtra("folderUri") : null;

        responseArea = findViewById(R.id.responseArea);
        responseScrollView = findViewById(R.id.responseScrollView); // Найти ScrollView
        chatInput = findViewById(R.id.chatInput);
        chatSend = findViewById(R.id.chatSend);
        clearChatButton = findViewById(R.id.clearChatButton);

        // Если folderUri == null — попробуем найти сохранённый persisted Uri
        if (folderUri == null) {
            List<android.content.UriPermission> perms = getContentResolver().getPersistedUriPermissions();
            for (android.content.UriPermission p : perms) {
                if (p.isReadPermission()) {
                    folderUri = p.getUri();
                    break;
                }
            }
        }

        if (folderUri == null) {
            showCustomToast("Папка не выбрана! Откройте настройки и выберите папку.");
            loadFallbackTemplates();
            updateAutoComplete();
        } else {
            loadTemplatesFromFile(currentContext);
            updateAutoComplete();
        }

        chatInput.setOnItemClickListener((parent, view, position, id) -> {
            String selected = (String) parent.getItemAtPosition(position);
            chatInput.setText(selected);
            processQuery(selected);
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
        if (folderUri != null) {
            loadTemplatesFromFile(currentContext);
        }
        updateAutoComplete();
    }

    private void loadTemplatesFromFile(String filename) {
        templatesMap.clear();
        if ("base.txt".equals(filename)) {
            contextMap.clear(); // Очищаем только для base.txt
        }

        if (folderUri == null) {
            showCustomToast("Папка не выбрана, используем базовые ответы.");
            loadFallbackTemplates();
            return;
        }

        try {
            DocumentFile dir = DocumentFile.fromTreeUri(this, folderUri);
            if (dir == null || !dir.exists() || !dir.isDirectory()) {
                showCustomToast("Папка недоступна. Проверьте права.");
                loadFallbackTemplates();
                return;
            }

            DocumentFile file = dir.findFile(filename);
            if (file == null || !file.exists()) {
                showCustomToast("Файл " + filename + " не найден! Используем базовые ответы.");
                loadFallbackTemplates();
                return;
            }

            try (InputStream is = getContentResolver().openInputStream(file.getUri());
                 BufferedReader reader = new BufferedReader(new InputStreamReader(is))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    String l = line.trim();
                    if (l.isEmpty()) continue;

                    // Парсинг контекстных ссылок (только в base.txt): :ключ=файл.txt:
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
                        continue;
                    }

                    // Обычные шаблоны: trigger=ответ1|ответ2|...
                    if (!l.contains("=")) continue;
                    String[] parts = l.split("=", 2);
                    if (parts.length == 2) {
                        String trigger = parts[0].trim().toLowerCase();
                        String[] responses = parts[1].split("\\|");
                        List<String> responseList = new ArrayList<>();
                        for (String r : responses) {
                            String rr = r.trim();
                            if (!rr.isEmpty()) responseList.add(rr);
                        }
                        if (!trigger.isEmpty() && !responseList.isEmpty()) {
                            templatesMap.put(trigger, responseList);
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
        templatesMap.put("привет", new ArrayList<>(List.of("Привет! Чем могу помочь?")));
        templatesMap.put("как дела", new ArrayList<>(List.of("Всё отлично, а у тебя?")));
    }

    private void updateAutoComplete() {
        List<String> suggestionsList = new ArrayList<>();
        suggestionsList.addAll(templatesMap.keySet());
        for (String s : fallback) {
            if (!suggestionsList.contains(s.toLowerCase())) suggestionsList.add(s.toLowerCase());
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

        // Проверка на смену контекста (приоритет над триггерами)
        String newContext = detectContext(q);
        if (newContext != null && !newContext.equals(currentContext)) {
            currentContext = newContext;
            loadTemplatesFromFile(currentContext);
            updateAutoComplete();
            responseArea.append("Ты: " + query + "\nBot: Переключено на контекст: " + currentContext + "\n\n");
            // Прокрутка к последнему сообщению
            responseScrollView.post(() -> responseScrollView.fullScroll(View.FOCUS_DOWN));
            return;
        }

        // Поиск ответа в templatesMap
        List<String> possibleResponses = templatesMap.get(q);
        if (possibleResponses != null && !possibleResponses.isEmpty()) {
            String response = possibleResponses.get(random.nextInt(possibleResponses.size()));
            responseArea.append("Ты: " + query + "\nBot: " + response + "\n\n");
            responseScrollView.post(() -> responseScrollView.fullScroll(View.FOCUS_DOWN));
            return;
        }

        // Если нет ответа и не в base.txt — возвращаемся к base.txt
        if (!"base.txt".equals(currentContext)) {
            currentContext = "base.txt";
            loadTemplatesFromFile(currentContext);
            updateAutoComplete();
            responseArea.append("Ты: " + query + "\nBot: Не понял по теме. Вернулся к основному меню. Попробуй другой запрос.\n\n");
            responseScrollView.post(() -> responseScrollView.fullScroll(View.FOCUS_DOWN));
            return;
        }

        // Fallback в base.txt
        String response = getDummyResponse(q);
        responseArea.append("Ты: " + query + "\nBot: " + response + "\n\n");
        responseScrollView.post(() -> responseScrollView.fullScroll(View.FOCUS_DOWN));
    }

    private String detectContext(String input) {
        // Проверяем все ключевые слова из contextMap
        for (Map.Entry<String, String> entry : contextMap.entrySet()) {
            String keyword = entry.getKey().toLowerCase();
            String contextFile = entry.getValue();
            if (input.toLowerCase().contains(keyword)) {
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
