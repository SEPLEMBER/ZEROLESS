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
import android.view.View;
import android.view.animation.AlphaAnimation;
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
    private ImageView mascotImage;
    private LinearLayout chatLayout;
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

    // Для маскотов
    private List<Map<String, String>> mascotList = new ArrayList<>();
    private List<String> dialogLines = new ArrayList<>();
    private String currentMascotName = "Racky";
    private String currentMascotIcon = "raccoon_icon.png";
    private String currentThemeColor = "#00FF00";
    private String currentThemeBackground = "#000000";

    // Для диалогов
    private List<Dialog> dialogs = new ArrayList<>();
    private Dialog currentDialog = null;
    private int currentDialogIndex = 0;
    private Handler dialogHandler = new Handler(Looper.getMainLooper());
    private Runnable dialogRunnable;
    private long lastUserInputTime = System.currentTimeMillis();
    private Random random = new Random();

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
        responseArea.setTextIsSelectable(true); // Поддержка копирования текста
        responseScrollView = findViewById(R.id.responseScrollView);
        chatInput = findViewById(R.id.chatInput);
        chatSend = findViewById(R.id.chatSend);
        clearChatButton = findViewById(R.id.clearChatButton);
        mascotImage = findViewById(R.id.mascot_image);
        chatLayout = findViewById(R.id.chat_layout);

        // Применение запрета скриншотов из настроек
        SharedPreferences prefs = getSharedPreferences("ChatTribePrefs", MODE_PRIVATE);
        if (prefs.getBoolean("disableScreenshots", false)) {
            getWindow().setFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE,
                    android.view.WindowManager.LayoutParams.FLAG_SECURE);
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
        startIdleTimer();
    }

    @Override
    protected void onPause() {
        super.onPause();
        stopDialog();
        dialogHandler.removeCallbacksAndMessages(null);
    }

    private void loadTemplatesFromFile(String filename) {
        templatesMap.clear();
        keywordResponses.clear();
        mascotList.clear();
        dialogLines.clear();
        dialogs.clear();
        currentMascotName = "Racky";
        currentMascotIcon = "raccoon_icon.png";
        currentThemeColor = "#00FF00";
        currentThemeBackground = "#000000";

        if (folderUri == null) {
            showCustomToast("Папка не выбрана, используем базовые ответы.");
            loadFallbackTemplates();
            updateUI(currentMascotName, currentMascotIcon, currentThemeColor, currentThemeBackground);
            return;
        }

        try {
            DocumentFile dir = DocumentFile.fromTreeUri(this, folderUri);
            if (dir == null || !dir.exists() || !dir.isDirectory()) {
                showCustomToast("Папка недоступна. Проверьте права.");
                loadFallbackTemplates();
                updateUI(currentMascotName, currentMascotIcon, currentThemeColor, currentThemeBackground);
                return;
            }

            DocumentFile file = dir.findFile(filename);
            if (file == null || !file.exists()) {
                showCustomToast("Файл " + filename + " не найден! Используем базовые ответы.");
                loadFallbackTemplates();
                updateUI(currentMascotName, currentMascotIcon, currentThemeColor, currentThemeBackground);
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
                                if (!keyword.isEmpty() && !responseList.isEmpty()) {
                                    keywordResponses.put(keyword, responseList);
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

            // Загрузка метаданных
            String metadataFilename = filename.replace(".txt", "_metadata.txt");
            DocumentFile metadataFile = dir.findFile(metadataFilename);
            if (metadataFile != null && metadataFile.exists()) {
                try (InputStream is = getContentResolver().openInputStream(metadataFile.getUri());
                     BufferedReader reader = new BufferedReader(new InputStreamReader(is))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        if (line.startsWith("mascot_list=")) {
                            String[] mascots = line.substring("mascot_list=".length()).split("\\|");
                            for (String mascot : mascots) {
                                String[] parts = mascot.split(":");
                                if (parts.length == 4) {
                                    Map<String, String> mascotData = new HashMap<>();
                                    mascotData.put("name", parts[0].trim());
                                    mascotData.put("icon", parts[1].trim());
                                    mascotData.put("color", parts[2].trim());
                                    mascotData.put("background", parts[3].trim());
                                    mascotList.add(mascotData);
                                }
                            }
                        } else if (line.startsWith("mascot_name=")) {
                            currentMascotName = line.substring("mascot_name=".length()).trim();
                        } else if (line.startsWith("mascot_icon=")) {
                            currentMascotIcon = line.substring("mascot_icon=".length()).trim();
                        } else if (line.startsWith("theme_color=")) {
                            currentThemeColor = line.substring("theme_color=".length()).trim();
                        } else if (line.startsWith("theme_background=")) {
                            currentThemeBackground = line.substring("theme_background=".length()).trim();
                        } else if (line.startsWith("dialog_lines=")) {
                            String[] lines = line.substring("dialog_lines=".length()).split("\\|");
                            for (String dialog : lines) {
                                String d = dialog.trim();
                                if (!d.isEmpty()) dialogLines.add(d);
                            }
                        }
                    }
                }
            }

            // Загрузка randomreply.txt
            DocumentFile dialogFile = dir.findFile("randomreply.txt");
            if (dialogFile != null && dialogFile.exists()) {
                try (InputStream is = getContentResolver().openInputStream(dialogFile.getUri());
                     BufferedReader reader = new BufferedReader(new InputStreamReader(is))) {
                    String line;
                    Dialog current = null;
                    while ((line = reader.readLine()) != null) {
                        if (line.startsWith(";")) {
                            if (current != null) dialogs.add(current);
                            current = new Dialog(line.substring(1).trim());
                        } else if (current != null && line.contains(">")) {
                            String[] parts = line.split(">", 2);
                            if (parts.length == 2) {
                                Map<String, String> reply = new HashMap<>();
                                reply.put("mascot", parts[0].trim());
                                reply.put("text", parts[1].trim());
                                current.replies.add(reply);
                            }
                        }
                    }
                    if (current != null) dialogs.add(current);
                } catch (Exception e) {
                    showCustomToast("Ошибка чтения randomreply.txt: " + e.getMessage());
                }
            }

            // Выбор случайного маскота для base.txt
            if ("base.txt".equals(filename) && !mascotList.isEmpty()) {
                Map<String, String> selectedMascot = mascotList.get(random.nextInt(mascotList.size()));
                currentMascotName = selectedMascot.get("name");
                currentMascotIcon = selectedMascot.get("icon");
                currentThemeColor = selectedMascot.get("color");
                currentThemeBackground = selectedMascot.get("background");
            }

            updateUI(currentMascotName, currentMascotIcon, currentThemeColor, currentThemeBackground);

            // Запуск таймера для диалогов только для base.txt
            if ("base.txt".equals(filename)) {
                startIdleTimer();
            }
        } catch (Exception e) {
            e.printStackTrace();
            showCustomToast("Ошибка чтения файла: " + e.getMessage());
            loadFallbackTemplates();
            updateUI(currentMascotName, currentMascotIcon, currentThemeColor, currentThemeBackground);
        }
    }

    private void loadFallbackTemplates() {
        templatesMap.clear();
        contextMap.clear();
        keywordResponses.clear();
        dialogs.clear();
        dialogLines.clear();
        mascotList.clear();
        templatesMap.put("привет", new ArrayList<>(Arrays.asList("Привет! Чем могу помочь?", "Здравствуй!")));
        templatesMap.put("как дела", new ArrayList<>(Arrays.asList("Всё отлично, а у тебя?", "Нормально, как дела?")));
        keywordResponses.put("спасибо", new ArrayList<>(Arrays.asList("Рад, что помог!", "Всегда пожалуйста!")));
        updateUI(currentMascotName, currentMascotIcon, currentThemeColor, currentThemeBackground);
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
        String q = query.trim().toLowerCase();
        if (q.isEmpty()) return;
        lastUserInputTime = System.currentTimeMillis(); // Обновить время ввода
        stopDialog(); // Остановить диалог

        // Отслеживание повторений
        if (q.equals(lastQuery)) {
            queryCountMap.put(q, queryCountMap.getOrDefault(q, 0) + 1);
        } else {
            queryCountMap.clear();
            queryCountMap.put(q, 1);
            lastQuery = q;
        }

        // Антиспам: если запрос повторён 5 раз подряд
        if (queryCountMap.get(q) >= 5) {
            String response = antiSpamResponses.get(random.nextInt(antiSpamResponses.size()));
            responseArea.append("Ты: " + query + "\n" + currentMascotName + ": " + response + "\n\n");
            responseScrollView.post(() -> responseScrollView.fullScroll(View.FOCUS_DOWN));
            startIdleTimer();
            return;
        }

        // Проверка на смену контекста
        String newContext = detectContext(q);
        if (newContext != null && !newContext.equals(currentContext)) {
            currentContext = newContext;
            loadTemplatesFromFile(currentContext);
            updateAutoComplete();
            responseArea.append("Ты: " + query + "\n" + currentMascotName + ": Переключаюсь на " + currentContext + "...\n\n");
            responseScrollView.post(() -> responseScrollView.fullScroll(View.FOCUS_DOWN));
            processQuery(query); // Повторить запрос
            return;
        }

        // Проверка ключевых слов (-спасибо=...)
        for (Map.Entry<String, List<String>> entry : keywordResponses.entrySet()) {
            String keyword = entry.getKey();
            if (q.contains(keyword)) {
                List<String> responses = entry.getValue();
                String response = responses.get(random.nextInt(responses.size()));
                responseArea.append("Ты: " + query + "\n" + currentMascotName + ": " + response + "\n\n");
                responseScrollView.post(() -> responseScrollView.fullScroll(View.FOCUS_DOWN));
                triggerRandomDialog();
                startIdleTimer();
                return;
            }
        }

        // Поиск точного триггера в текущем контексте
        List<String> possibleResponses = templatesMap.get(q);
        if (possibleResponses != null && !possibleResponses.isEmpty()) {
            String response = possibleResponses.get(random.nextInt(possibleResponses.size()));
            responseArea.append("Ты: " + query + "\n" + currentMascotName + ": " + response + "\n\n");
            responseScrollView.post(() -> responseScrollView.fullScroll(View.FOCUS_DOWN));
            triggerRandomDialog();
            startIdleTimer();
            return;
        }

        // Если не в base.txt и нет ответа, возвращаемся к base.txt
        if (!"base.txt".equals(currentContext)) {
            currentContext = "base.txt";
            loadTemplatesFromFile(currentContext);
            updateAutoComplete();
            responseArea.append("Ты: " + query + "\n" + currentMascotName + ": Возвращаюсь к общей теме...\n\n");
            responseScrollView.post(() -> responseScrollView.fullScroll(View.FOCUS_DOWN));
            processQuery(query);
            return;
        }

        // Fallback в base.txt
        String response = getDummyResponse(q);
        responseArea.append("Ты: " + query + "\n" + currentMascotName + ": " + response + "\n\n");
        responseScrollView.post(() -> responseScrollView.fullScroll(View.FOCUS_DOWN));
        triggerRandomDialog();
        startIdleTimer();
    }

    private void triggerRandomDialog() {
        // 30% шанс на случайную реплику из dialog_lines
        if (!dialogLines.isEmpty() && random.nextDouble() < 0.3) {
            dialogHandler.postDelayed(() -> {
                String dialog = dialogLines.get(random.nextInt(dialogLines.size()));
                String randomMascot = mascotList.get(random.nextInt(mascotList.size())).get("name");
                loadMascotMetadata(randomMascot);
                responseArea.append(randomMascot + ": " + dialog + "\n\n");
                responseScrollView.post(() -> responseScrollView.fullScroll(View.FOCUS_DOWN));
            }, 2000);
        }

        // 10% шанс на пасхалку "Эй, мы не закончили!"
        if (random.nextDouble() < 0.1) {
            dialogHandler.postDelayed(() -> {
                String randomMascot = mascotList.get(random.nextInt(mascotList.size())).get("name");
                loadMascotMetadata(randomMascot);
                responseArea.append(randomMascot + ": Эй, мы не закончили!\n\n");
                responseScrollView.post(() -> responseScrollView.fullScroll(View.FOCUS_DOWN));
            }, 3000);
        }
    }

    private void loadMascotMetadata(String mascotName) {
        String metadataFilename = mascotName.toLowerCase() + "_metadata.txt";
        DocumentFile dir = DocumentFile.fromTreeUri(this, folderUri);
        DocumentFile metadataFile = dir.findFile(metadataFilename);
        if (metadataFile != null && metadataFile.exists()) {
            try (InputStream is = getContentResolver().openInputStream(metadataFile.getUri());
                 BufferedReader reader = new BufferedReader(new InputStreamReader(is))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (line.startsWith("mascot_name=")) {
                        currentMascotName = line.substring("mascot_name=".length()).trim();
                    } else if (line.startsWith("mascot_icon=")) {
                        currentMascotIcon = line.substring("mascot_icon=".length()).trim();
                    } else if (line.startsWith("theme_color=")) {
                        currentThemeColor = line.substring("theme_color=".length()).trim();
                    } else if (line.startsWith("theme_background=")) {
                        currentThemeBackground = line.substring("theme_background=".length()).trim();
                    }
                }
                updateUI(currentMascotName, currentMascotIcon, currentThemeColor, currentThemeBackground);
            } catch (Exception e) {
                showCustomToast("Ошибка загрузки метаданных маскота: " + e.getMessage());
            }
        }
    }

    private void startIdleTimer() {
        dialogHandler.postDelayed(() -> {
            if (System.currentTimeMillis() - lastUserInputTime >= 25000 && !dialogs.isEmpty()) {
                startRandomDialog();
            }
        }, 25000);
    }

    private void startRandomDialog() {
        if (dialogs.isEmpty()) return;
        stopDialog();
        currentDialog = dialogs.get(random.nextInt(dialogs.size()));
        currentDialogIndex = 0;
        dialogRunnable = new Runnable() {
            @Override
            public void run() {
                if (currentDialogIndex < currentDialog.replies.size()) {
                    Map<String, String> reply = currentDialog.replies.get(currentDialogIndex);
                    String mascot = reply.get("mascot");
                    String text = reply.get("text");
                    loadMascotMetadata(mascot);
                    responseArea.append(mascot + ": " + text + "\n\n");
                    responseScrollView.post(() -> responseScrollView.fullScroll(View.FOCUS_DOWN));
                    currentDialogIndex++;
                    dialogHandler.postDelayed(this, random.nextInt(15000) + 10000); // 10-25 сек
                } else {
                    startRandomDialog(); // Новый диалог
                }
            }
        };
        dialogHandler.postDelayed(dialogRunnable, random.nextInt(15000) + 10000);
    }

    private void stopDialog() {
        if (dialogRunnable != null) {
            dialogHandler.removeCallbacks(dialogRunnable);
            dialogRunnable = null;
        }
    }

    private void updateUI(String mascotName, String mascotIcon, String themeColor, String themeBackground) {
        setTitle("ChatTribe - " + mascotName);
        if (mascotImage != null) {
            try {
                DocumentFile dir = DocumentFile.fromTreeUri(this, folderUri);
                DocumentFile iconFile = dir.findFile(mascotIcon);
                if (iconFile != null && iconFile.exists()) {
                    try (InputStream is = getContentResolver().openInputStream(iconFile.getUri())) {
                        Bitmap bitmap = BitmapFactory.decodeStream(is);
                        mascotImage.setImageBitmap(bitmap);
                        // Анимация fade-in
                        AlphaAnimation fadeIn = new AlphaAnimation(0.0f, 1.0f);
                        fadeIn.setDuration(500);
                        mascotImage.startAnimation(fadeIn);
                    }
                } else {
                    showCustomToast("Иконка " + mascotIcon + " не найдена!");
                }
            } catch (Exception e) {
                showCustomToast("Ошибка загрузки иконки: " + e.getMessage());
            }
        }

        try {
            if (chatLayout != null) {
                chatLayout.setBackgroundColor(Color.parseColor(themeBackground));
            }
            if (responseArea != null) {
                responseArea.setTextColor(Color.parseColor(themeColor));
            }
            if (chatInput != null) {
                chatInput.setTextColor(Color.parseColor(themeColor));
                chatInput.setHintTextColor(Color.parseColor(themeColor));
            }
        } catch (Exception e) {
            showCustomToast("Ошибка применения темы: " + e.getMessage());
        }
    }

    private String detectContext(String input) {
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
