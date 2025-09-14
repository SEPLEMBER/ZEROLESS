package com.nemesis.droidcrypt;

import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.animation.AlphaAnimation;
import android.view.animation.Animation;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.AutoCompleteTextView;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.documentfile.provider.DocumentFile;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

public class ChatActivity extends AppCompatActivity {

    private Uri folderUri;
    private TextView responseArea;
    private ScrollView responseScrollView;
    private AutoCompleteTextView chatInput;
    private Button chatSend;
    private Button clearChatButton;
    private ArrayAdapter<String> adapter;

    // Fallback dummy suggestions
    private String[] fallback = {"Привет", "Как дела?", "Расскажи о себе", "Выход"};

    // Templates: trigger -> list of answers
    private Map<String, List<String>> templatesMap = new HashMap<>();
    // Context keyword -> file (e.g., "сон" -> "health.txt")
    private Map<String, String> contextMap = new HashMap<>();
    // Keyword responses: keyword -> list of responses (e.g., "спасибо" -> ["Рад, что помог!", ...])
    private Map<String, List<String>> keywordResponses = new HashMap<>();
    // Query count: query -> count of consecutive repetitions
    private Map<String, Integer> queryCountMap = new HashMap<>();
    // Anti-spam responses
    private List<String> antiSpamResponses = new ArrayList<>();
    {
        antiSpamResponses.add("Ты надоел, давай что-то новенькое!");
        antiSpamResponses.add("Спамить нехорошо, попробуй другой запрос.");
        antiSpamResponses.add("Я устал от твоих повторений!");
        antiSpamResponses.add("Хватит спамить, придумай что-то интересное.");
        antiSpamResponses.add("Эй, не зацикливайся, попробуй другой вопрос!");
        antiSpamResponses.add("Повторяешь одно и то же? Давай разнообразие!");
        antiSpamResponses.add("Слишком много повторов, я же не робот... ну, почти.");
        antiSpamResponses.add("Не спамь, пожалуйста, задай новый вопрос!");
        antiSpamResponses.add("Пять раз одно и то же? Попробуй что-то другое.");
        antiSpamResponses.add("Я уже ответил, давай новый запрос!");
    }

    private String currentContext = "base.txt";
    private String lastQuery = "";
    private Random random = new Random();

    // Для маскотов
    private List<Map<String, String>> mascotList = new ArrayList<>();
    private List<String> dialogLines = new ArrayList<>();
    private String currentMascotName = "Racky";
    private String currentMascotIcon = "default_raccoon";
    private String currentThemeColor = "#00FF00";
    private String currentThemeBackground = "#000000";

    // Для диалогов
    private List<Dialog> dialogs = new ArrayList<>();
    private Dialog currentDialog = null;
    private int currentDialogIndex = 0;
    private Handler dialogHandler = new Handler(Looper.getMainLooper());
    private Runnable dialogRunnable;
    private long lastUserInputTime = System.currentTimeMillis();

    // Класс Dialog для хранения диалогов
    private static class Dialog {
        String name;
        List<Map<String, String>> replies;
        Dialog(String name) {
            this.name = name;
            this.replies = new ArrayList<>();
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_chat);

        // Получение folderUri из Intent
        Intent intent = getIntent();
        folderUri = intent != null ? intent.getParcelableExtra("folderUri") : null;

        responseArea = findViewById(R.id.responseArea);
        responseArea.setTextIsSelectable(true); // Добавлено для копирования текста
        responseScrollView = findViewById(R.id.responseScrollView);
        chatInput = findViewById(R.id.chatInput);
        chatSend = findViewById(R.id.chatSend);
        clearChatButton = findViewById(R.id.clearChatButton);

        // Применение запрета скриншотов из настроек
        SharedPreferences prefs = getSharedPreferences("ChatTribePrefs", MODE_PRIVATE);
        if (prefs.getBoolean("disableScreenshots", false)) {
            getWindow().setFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE, android.view.WindowManager.LayoutParams.FLAG_SECURE);
        }

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
            clearChatButton.setOnClickListener(v -> {
                responseArea.setText("");
                queryCountMap.clear();
            });
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (folderUri != null) {
            loadTemplatesFromFile(currentContext);
        }
        updateAutoComplete();
        startIdleTimer(); // Запуск таймера для диалогов
    }

    @Override
    protected void onPause() {
        super.onPause();
        stopDialog(); // Остановка диалогов
        dialogHandler.removeCallbacksAndMessages(null);
    }

    private void loadTemplatesFromFile(String filename) {
        templatesMap.clear();
        keywordResponses.clear();
        if ("base.txt".equals(filename)) {
            contextMap.clear();
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

                    // Парсинг ключевых слов: -ключ=ответ1|ответ2|...
                    if (l.startsWith("-")) {
                        String keywordLine = l.substring(1);
                        if (keywordLine.contains("=")) {
                            String[] parts = keywordLine.split("=", 2);
                            if (parts.length == 2) {
                                String keyword = parts[0].trim().toLowerCase();
                                String[] responses = parts[1].split("\\|");
                                List<String> responseList = new ArrayList<>();
                                for (String r : responses) {
                                    String rr = r.trim();
                                    if (!rr.isEmpty()) responseList.add(rr);
                                }
                                if (!keyword.isEmpty() && !responseList
