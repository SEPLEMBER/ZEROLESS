package com.nemesis.droidcrypt;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.AutoCompleteTextView;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;
import androidx.appcompat.app.AppCompatActivity;

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
    private Button clearChatButton; // Добавлена для очистки чата
    private ArrayAdapter<String> adapter; // For autocomplete suggestions

    // Fallback dummy suggestions
    private String[] fallback = {"Привет", "Как дела?", "Расскажи о себе", "Выход"};

    // Templates: trigger -> list of answers
    private Map<String, List<String>> templatesMap = new HashMap<>();
    private String currentContext = "base.txt";
    private Random random = new Random();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_chat);

        // Получение folderUri из Intent
        folderUri = getIntent().getParcelableExtra("folderUri");
        if (folderUri == null) {
            showCustomToast("Папка не выбрана!");
            finish(); // Закрыть активити, если папка не выбрана
            return;
        }

        responseArea = findViewById(R.id.responseArea);
        chatInput = findViewById(R.id.chatInput);
        chatSend = findViewById(R.id.chatSend);
        clearChatButton = findViewById(R.id.clearChatButton); // Предполагается наличие в layout

        // Загрузка base.txt по умолчанию
        loadTemplatesFromFile(currentContext);

        // Build suggestions list: keys from templates + fallback
        updateAutoComplete();

        chatInput.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
                String selected = (String) parent.getItemAtPosition(position);
                chatInput.setText(selected);
                processQuery(selected);
                // Убрано showCustomToast, чтобы не засорять
            }
        });

        chatSend.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                String input = chatInput.getText().toString().trim();
                if (!input.isEmpty()) {
                    processQuery(input);
                    chatInput.setText(""); // Clear input, no history save
                }
            }
        });

        // Обработчик кнопки очистки чата
        if (clearChatButton != null) {
            clearChatButton.setOnClickListener(v -> responseArea.setText(""));
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        // Reload templates from current context
        loadTemplatesFromFile(currentContext);
        // Update adapter suggestions dynamically
        updateAutoComplete();
    }

    private void loadTemplatesFromFile(String filename) {
        templatesMap.clear();
        try {
            Uri fileUri = Uri.withAppendedPath(folderUri, filename);
            ParcelFileDescriptor pfd = getContentResolver().openFileDescriptor(fileUri, "r");
            if (pfd == null) {
                showCustomToast("Файл " + filename + " не найден! Используем базовые ответы.");
                loadFallbackTemplates(); // Загрузить fallback, если файл отсутствует
                return;
            }
            FileInputStream fileInputStream = new FileInputStream(pfd.getFileDescriptor());
            BufferedReader reader = new BufferedReader(new InputStreamReader(fileInputStream));
            String line;
            while ((line = reader.readLine()) != null) {
                String l = line.trim();
                if (l.isEmpty() || !l.contains("=")) continue;
                String[] parts = l.split("=", 2);
                if (parts.length == 2) {
                    String trigger = parts[0].trim().toLowerCase();
                    String[] responses = parts[1].split("\\|");
                    List<String> responseList = new ArrayList<>();
                    for (String response : responses) {
                        String trimmed = response.trim();
                        if (!trimmed.isEmpty()) {
                            responseList.add(trimmed);
                        }
                    }
                    if (!trigger.isEmpty() && !responseList.isEmpty()) {
                        templatesMap.put(trigger, responseList);
                    }
                }
            }
            reader.close();
            fileInputStream.close();
            pfd.close();
            updateAutoComplete();
        } catch (Exception e) {
            showCustomToast("Ошибка при загрузке файла: " + e.getMessage());
            loadFallbackTemplates();
        }
    }

    private void loadFallbackTemplates() {
        // Простые fallback шаблоны в памяти
        templatesMap.put("привет", new ArrayList<>(List.of("Привет! Чем могу помочь?")));
        templatesMap.put("как дела", new ArrayList<>(List.of("Всё отлично, а у тебя?")));
    }

    private void updateAutoComplete() {
        List<String> suggestionsList = new ArrayList<>();
        suggestionsList.addAll(templatesMap.keySet());
        for (String s : fallback) {
            if (!suggestionsList.contains(s.toLowerCase())) {
                suggestionsList.add(s.toLowerCase());
            }
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

        // Проверка контекста (ключевые слова)
        String newContext = detectContext(q);
        if (newContext != null && !newContext.equals(currentContext)) {
            currentContext = newContext;
            loadTemplatesFromFile(currentContext);
            responseArea.append("Bot: Переключено на контекст: " + currentContext + "\n\n");
            return;
        }

        // Поиск ответа в templatesMap
        List<String> possibleResponses = templatesMap.get(q);
        if (possibleResponses != null && !possibleResponses.isEmpty()) {
            String response = possibleResponses.get(random.nextInt(possibleResponses.size()));
            responseArea.append("Ты: " + query + "\nBot: " + response + "\n\n");
            return;
        }

        // Fallback rule-based
        String response = getDummyResponse(q);
        responseArea.append("Ты: " + query + "\nBot: " + response + "\n\n");
    }

    private String detectContext(String input) {
        // Пример логики для определения контекста
        if (input.contains("сон") || input.contains("сны") || input.contains("про сон")) {
            return "health.txt";
        }
        // Другие контексты можно добавить здесь
        return null;
    }

    private String getDummyResponse(String query) {
        if (query.contains("привет")) return "Привет! Чем могу помочь?";
        if (query.contains("как дела")) return "Всё отлично, а у тебя?";
        return "Не понял запрос. Попробуй другой вариант.";
    }

    private void showCustomToast(String message) {
        LayoutInflater inflater = getLayoutInflater();
        View layout = inflater.inflate(R.layout.custom_toast, null);

        TextView text = layout.findViewById(R.id.customToastText);
        text.setText(message);

        Toast toast = new Toast(getApplicationContext());
        toast.setDuration(Toast.LENGTH_SHORT);
        toast.setView(layout);
        toast.show();
    }
}
