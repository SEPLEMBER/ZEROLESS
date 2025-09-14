package com.nemesis.droidcrypt;

import android.animation.ObjectAnimator;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.preference.PreferenceManager;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
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
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

/**
 * Исправленный ChatActivity
 * - детерминированный поиск ответа по цепочке контекстов (без видимых "Переключаюсь...")
 * - исправлен UI контейнер для сообщений (messages container внутри ScrollView)
 * - исправлен idle/randomreply поведение
 * - восстановлено копирование текста (textIsSelectable для созданных TextView)
 * - скролл вниз теперь через smoothScrollTo
 * - блокировка скриншотов читается из двух возможных prefs и применяется через addFlags/clearFlags
 *
 * minSdk: 26 (Android 8) — использованы API, доступные на 26+
 */
public class ChatActivity extends AppCompatActivity {

    private Uri folderUri;
    private ScrollView scrollView;
    private AutoCompleteTextView queryInput;
    private Button sendButton;
    private Button clearButton;
    private ImageView mascotImage;
    private LinearLayout messagesContainer; // <-- контейнер сообщений внутри ScrollView
    private ArrayAdapter<String> adapter;

    // Fallback suggestions
    private String[] fallback = {"Привет", "Как дела?", "Расскажи о себе", "Выход"};

    // Data structures
    private Map<String, List<String>> templatesMap = new HashMap<>();
    private Map<String, String> contextMap = new HashMap<>();
    private Map<String, List<String>> keywordResponses = new HashMap<>();
    private final List<String> antiSpamResponses = new ArrayList<>();

    private final List<Map<String, String>> mascotList = new ArrayList<>();
    private final List<String> dialogLines = new ArrayList<>();
    private final List<Dialog> dialogs = new ArrayList<>();

    private String currentMascotName = "Racky";
    private String currentMascotIcon = "raccoon_icon.png";
    private String currentThemeColor = "#00FF00";
    private String currentThemeBackground = "#000000";
    private String currentContext = "base.txt";
    private String lastQuery = "";

    // dialogs handling
    private Dialog currentDialog = null;
    private int currentDialogIndex = 0;
    private final Handler dialogHandler = new Handler(Looper.getMainLooper());
    private Runnable dialogRunnable;

    // idle check
    private Runnable idleCheckRunnable;
    private long lastUserInputTime = System.currentTimeMillis();

    private final Random random = new Random();
    private final Map<String, Integer> queryCountMap = new HashMap<>();

    private static final int MAX_CONTEXT_SWITCH = 6;
    private static final int MAX_MESSAGES = 400;

    private static class Dialog {
        String name;
        List<Map<String, String>> replies;
        Dialog(String name) { this.name = name; this.replies = new ArrayList<>(); }
    }

    public ChatActivity() {
        antiSpamResponses.addAll(Arrays.asList(
                "Ты надоел, давай что-то новенькое!",
                "Спамить нехорошо, попробуй другой запрос.",
                "Я устал от твоих повторений!",
                "Хватит спамить, придумай что-то интересное.",
                "Эй, не зацикливайся, попробуй другой вопрос!",
                "Повторяешь одно и то же? Давай разнообразие!",
                "Слишком много повторов, я же не робот... ну, почти.",
                "Не спамь, пожалуйста, задай новый вопрос!",
                "Пять раз одно и то же? Попробуй что-то другое.",
                "Я уже ответил, давай новый запрос!"
        ));
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_chat);

        // UI refs
        scrollView = findViewById(R.id.scrollView);
        queryInput = findViewById(R.id.queryInput);
        sendButton = findViewById(R.id.sendButton);
        clearButton = findViewById(R.id.clearButton);
        mascotImage = findViewById(R.id.mascot_image);
        messagesContainer = findViewById(R.id.chatMessagesContainer); // <-- важно: этот id присутствует в XML

        // Получаем folderUri из Intent или persisted permissions
        Intent intent = getIntent();
        folderUri = intent != null ? intent.getParcelableExtra("folderUri") : null;
        if (folderUri == null) {
            for (android.content.UriPermission p : getContentResolver().getPersistedUriPermissions()) {
                if (p.isReadPermission()) { folderUri = p.getUri(); break; }
            }
        }

        // Считываем настройку блокировки скриншота из разных источников на всякий случай
        boolean disableScreenshots = false;
        try {
            SharedPreferences prefs1 = PreferenceManager.getDefaultSharedPreferences(this);
            SharedPreferences prefs2 = getSharedPreferences("ChatTribePrefs", MODE_PRIVATE);
            disableScreenshots = prefs1.getBoolean("disableScreenshots", false) || prefs2.getBoolean("disableScreenshots", false);
        } catch (Exception ignored) {}
        if (disableScreenshots) {
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
        } else {
            getWindow().clearFlags(WindowManager.LayoutParams.FLAG_SECURE);
        }

        // Load initial templates
        if (folderUri == null) {
            showCustomToast("Папка не выбрана! Откройте настройки и выберите папку.");
            loadFallbackTemplates();
            updateAutoComplete();
            addChatMessage(currentMascotName, "Добро пожаловать!");
        } else {
            loadTemplatesFromFile(currentContext);
            updateAutoComplete();
            addChatMessage(currentMascotName, "Добро пожаловать!");
        }

        // Autocomplete click
        queryInput.setOnItemClickListener((parent, view, position, id) -> {
            String selected = (String) parent.getItemAtPosition(position);
            queryInput.setText(selected);
            processUserQuery(selected);
        });

        // Send button
        sendButton.setOnClickListener(v -> {
            String input = queryInput.getText().toString().trim();
            if (!input.isEmpty()) {
                processUserQuery(input);
                queryInput.setText("");
            }
        });

        // Clear button
        if (clearButton != null) {
            clearButton.setOnClickListener(v -> clearChat());
        }

        // idle check runnable — каждые 5 с проверяем простое
        idleCheckRunnable = new Runnable() {
            @Override
            public void run() {
                long idle = System.currentTimeMillis() - lastUserInputTime;
                if (idle >= 25000) {
                    if (!dialogs.isEmpty()) {
                        startRandomDialog();
                    } else if (!dialogLines.isEmpty()) {
                        triggerRandomDialog();
                    }
                }
                dialogHandler.postDelayed(this, 5000);
            }
        };
        dialogHandler.postDelayed(idleCheckRunnable, 5000);
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (folderUri != null) loadTemplatesFromFile(currentContext);
        updateAutoComplete();
        dialogHandler.removeCallbacks(idleCheckRunnable);
        dialogHandler.postDelayed(idleCheckRunnable, 5000);
    }

    @Override
    protected void onPause() {
        super.onPause();
        stopDialog();
        dialogHandler.removeCallbacksAndMessages(null);
    }

    // ========== основной алгоритм поиска ответа ==========
    private void processUserQuery(String userInput) {
        String qOrig = userInput.trim().toLowerCase();
        if (qOrig.isEmpty()) return;

        lastUserInputTime = System.currentTimeMillis();
        stopDialog();

        // обновляем счетчики повторов
        if (qOrig.equals(lastQuery)) {
            int cnt = queryCountMap.containsKey(qOrig) ? queryCountMap.get(qOrig) : 0;
            queryCountMap.put(qOrig, cnt + 1);
        } else {
            queryCountMap.clear();
            queryCountMap.put(qOrig, 1);
            lastQuery = qOrig;
        }

        addChatMessage("Ты", userInput);

        // антиспам
        int repeats = queryCountMap.getOrDefault(qOrig, 0);
        if (repeats >= 5) {
            String spamResp = antiSpamResponses.get(random.nextInt(antiSpamResponses.size()));
            addChatMessage(currentMascotName, spamResp);
            startIdleTimer();
            return;
        }

        // deterministic search: последовательность контекстов, чтобы избежать циклов
        Set<String> visited = new HashSet<>();
        String startContext = currentContext != null ? currentContext : "base.txt";
        String context = startContext;
        boolean answered = false;
        int switches = 0;

        while (switches <= MAX_CONTEXT_SWITCH && !answered) {
            visited.add(context);

            // загрузим контент для текущего контекста (уже в памяти, но на всякий случай)
            if (!context.equals(currentContext)) {
                currentContext = context;
                loadTemplatesFromFile(currentContext);
                updateAutoComplete();
            }

            // 1) попытка точного триггера (в текущем контексте)
            List<String> possible = templatesMap.get(qOrig);
            if (possible != null && !possible.isEmpty()) {
                String resp = possible.get(random.nextInt(possible.size()));
                addChatMessage(currentMascotName, resp);
                triggerRandomDialog();
                startIdleTimer();
                answered = true;
                break;
            }

            // 2) попытка ключевых слов (-ключ=...), ищем в текущем контексте
            boolean handledByKeyword = false;
            for (Map.Entry<String, List<String>> e : keywordResponses.entrySet()) {
                String keyword = e.getKey();
                if (qOrig.contains(keyword)) {
                    List<String> responses = e.getValue();
                    if (!responses.isEmpty()) {
                        String resp = responses.get(random.nextInt(responses.size()));
                        addChatMessage(currentMascotName, resp);
                        triggerRandomDialog();
                        startIdleTimer();
                        handledByKeyword = true;
                        answered = true;
                        break;
                    }
                }
            }
            if (handledByKeyword) break;

            // 3) если мы не в base.txt, то попробуем найти маршрут в base.txt: (не показываем "переключаюсь")
            if (!"base.txt".equals(context)) {
                // загрузим base.txt (если не в visited)
                if (!visited.contains("base.txt")) {
                    context = "base.txt";
                    switches++;
                    continue; // следующая итерация будет в base.txt
                } else {
                    // base уже посещён — нет смысла продолжать
                    break;
                }
            }

            // 4) Если мы в base.txt и не нашли точный триггер — проверим, какие ключи в base соответствуют словосочетанию.
            boolean foundRoute = false;
            for (Map.Entry<String, String> e : contextMap.entrySet()) {
                String keyword = e.getKey();
                String mappedFile = e.getValue();
                if (qOrig.contains(keyword) && mappedFile != null && !visited.contains(mappedFile)) {
                    context = mappedFile;
                    switches++;
                    foundRoute = true;
                    break; // пройдём в mappedFile на следующей итерации
                }
            }
            if (foundRoute) continue;

            // 5) Не нашли ничего — fallback
            String fallbackResp = getDummyResponse(qOrig);
            addChatMessage(currentMascotName, fallbackResp);
            triggerRandomDialog();
            startIdleTimer();
            answered = true;
            break;
        }

        if (!answered) {
            addChatMessage(currentMascotName, "Не могу найти ответ, попробуй переформулировать.");
        }
    }

    // ========== UI сообщений (динамическое добавление View) ==========
    private void addChatMessage(String sender, String text) {
        if (messagesContainer == null) return;

        LinearLayout item = new LinearLayout(this);
        item.setOrientation(LinearLayout.VERTICAL);
        int pad = dpToPx(8);
        item.setPadding(pad, pad / 2, pad, pad / 2);

        TextView tvSender = new TextView(this);
        tvSender.setText(sender + ":");
        tvSender.setTextSize(12);
        tvSender.setTextColor(Color.parseColor("#AAAAAA"));
        item.addView(tvSender, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextSize(16);
        tv.setTextIsSelectable(true); // <-- копирование восстановлено
        try { tv.setTextColor(Color.parseColor(currentThemeColor)); } catch (Exception e) { tv.setTextColor(Color.WHITE); }
        item.addView(tv, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        messagesContainer.addView(item);
        // trim old messages
        if (messagesContainer.getChildCount() > MAX_MESSAGES) {
            int removeCount = messagesContainer.getChildCount() - MAX_MESSAGES;
            for (int i = 0; i < removeCount; i++) messagesContainer.removeViewAt(0);
        }

        // скроллим вниз (smoothScrollTo по bottom контейнера)
        scrollView.post(() -> scrollView.smoothScrollTo(0, messagesContainer.getBottom()));
    }

    private int dpToPx(int dp) {
        float density = getResources().getDisplayMetrics().density;
        return Math.round(dp * density);
    }

    private void clearChat() {
        if (messagesContainer != null) messagesContainer.removeAllViews();
        queryCountMap.clear();
        lastQuery = "";
        currentContext = "base.txt";
        loadTemplatesFromFile(currentContext);
        updateAutoComplete();
        addChatMessage(currentMascotName, "Чат очищен. Возвращаюсь к началу.");
    }

    // ========== загрузка / парсинг ==========
    private void loadTemplatesFromFile(String filename) {
        templatesMap.clear();
        keywordResponses.clear();
        mascotList.clear();
        dialogLines.clear();
        dialogs.clear();

        // defaults
        currentMascotName = "Racky";
        currentMascotIcon = "raccoon_icon.png";
        currentThemeColor = "#00FF00";
        currentThemeBackground = "#000000";

        if (folderUri == null) {
            loadFallbackTemplates();
            updateUI(currentMascotName, currentMascotIcon, currentThemeColor, currentThemeBackground);
            return;
        }

        try {
            DocumentFile dir = DocumentFile.fromTreeUri(this, folderUri);
            if (dir == null || !dir.exists() || !dir.isDirectory()) {
                loadFallbackTemplates();
                updateUI(currentMascotName, currentMascotIcon, currentThemeColor, currentThemeBackground);
                return;
            }

            DocumentFile file = dir.findFile(filename);
            if (file == null || !file.exists()) {
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

                    if ("base.txt".equals(filename) && l.startsWith(":") && l.endsWith(":")) {
                        String contextLine = l.substring(1, l.length() - 1);
                        if (contextLine.contains("=")) {
                            String[] parts = contextLine.split("=", 2);
                            if (parts.length == 2) {
                                String keyword = parts[0].trim().toLowerCase();
                                String contextFile = parts[1].trim();
                                if (!keyword.isEmpty() && !contextFile.isEmpty()) contextMap.put(keyword, contextFile);
                            }
                        }
                        continue;
                    }

                    if (l.startsWith("-")) {
                        String keywordLine = l.substring(1);
                        if (keywordLine.contains("=")) {
                            String[] parts = keywordLine.split("=", 2);
                            if (parts.length == 2) {
                                String keyword = parts[0].trim().toLowerCase();
                                String[] responses = parts[1].split("\\|");
                                List<String> responseList = new ArrayList<>();
                                for (String r : responses) { String rr = r.trim(); if (!rr.isEmpty()) responseList.add(rr); }
                                if (!keyword.isEmpty() && !responseList.isEmpty()) keywordResponses.put(keyword, responseList);
                            }
                        }
                        continue;
                    }

                    if (!l.contains("=")) continue;
                    String[] parts = l.split("=", 2);
                    if (parts.length == 2) {
                        String trigger = parts[0].trim().toLowerCase();
                        String[] responses = parts[1].split("\\|");
                        List<String> responseList = new ArrayList<>();
                        for (String r : responses) { String rr = r.trim(); if (!rr.isEmpty()) responseList.add(rr); }
                        if (!trigger.isEmpty() && !responseList.isEmpty()) templatesMap.put(trigger, responseList);
                    }
                }
            }

            // metadata
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
                            for (String dialog : lines) { String d = dialog.trim(); if (!d.isEmpty()) dialogLines.add(d); }
                        }
                    }
                }
            }

            // randomreply.txt -> dialogs
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

            // pick random mascot for base
            if ("base.txt".equals(filename) && !mascotList.isEmpty()) {
                Map<String, String> selectedMascot = mascotList.get(random.nextInt(mascotList.size()));
                currentMascotName = selectedMascot.get("name");
                currentMascotIcon = selectedMascot.get("icon");
                currentThemeColor = selectedMascot.get("color");
                currentThemeBackground = selectedMascot.get("background");
            }

            updateUI(currentMascotName, currentMascotIcon, currentThemeColor, currentThemeBackground);

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
    }

    private void updateAutoComplete() {
        List<String> suggestionsList = new ArrayList<>();
        suggestionsList.addAll(templatesMap.keySet());
        for (String s : fallback) {
            if (!suggestionsList.contains(s.toLowerCase())) suggestionsList.add(s.toLowerCase());
        }

        if (adapter == null) {
            adapter = new ArrayAdapter<>(this, android.R.layout.simple_dropdown_item_1line, suggestionsList);
            queryInput.setAdapter(adapter);
            queryInput.setThreshold(1);
        } else {
            adapter.clear();
            adapter.addAll(suggestionsList);
            adapter.notifyDataSetChanged();
        }
    }

    // idle / random dialogs
    private void triggerRandomDialog() {
        if (!dialogLines.isEmpty() && random.nextDouble() < 0.3) {
            dialogHandler.postDelayed(() -> {
                if (dialogLines.isEmpty()) return;
                String dialog = dialogLines.get(random.nextInt(dialogLines.size()));
                if (!mascotList.isEmpty()) {
                    Map<String, String> rnd = mascotList.get(random.nextInt(mascotList.size()));
                    String rndName = rnd.getOrDefault("name", currentMascotName);
                    loadMascotMetadata(rndName);
                    addChatMessage(rndName, dialog);
                } else {
                    addChatMessage(currentMascotName, dialog);
                }
            }, 1500);
        }
        if (!mascotList.isEmpty() && random.nextDouble() < 0.1) {
            dialogHandler.postDelayed(() -> {
                Map<String, String> rnd = mascotList.get(random.nextInt(mascotList.size()));
                String rndName = rnd.getOrDefault("name", currentMascotName);
                loadMascotMetadata(rndName);
                addChatMessage(rndName, "Эй, мы не закончили!");
            }, 2500);
        }
    }

    private void startRandomDialog() {
        if (dialogs.isEmpty()) return;
        stopDialog();
        currentDialog = dialogs.get(random.nextInt(dialogs.size()));
        currentDialogIndex = 0;
        dialogRunnable = new Runnable() {
            @Override
            public void run() {
                if (currentDialog == null) return;
                if (currentDialogIndex < currentDialog.replies.size()) {
                    Map<String, String> reply = currentDialog.replies.get(currentDialogIndex);
                    String mascot = reply.get("mascot");
                    String text = reply.get("text");
                    loadMascotMetadata(mascot);
                    addChatMessage(mascot, text);
                    currentDialogIndex++;
                    dialogHandler.postDelayed(this, random.nextInt(15000) + 10000);
                } else {
                    dialogHandler.postDelayed(() -> startRandomDialog(), random.nextInt(20000) + 5000);
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

    // Mascot metadata & UI
    private void loadMascotMetadata(String mascotName) {
        if (folderUri == null) return;
        String metadataFilename = mascotName.toLowerCase() + "_metadata.txt";
        DocumentFile dir = DocumentFile.fromTreeUri(this, folderUri);
        if (dir == null) return;
        DocumentFile metadataFile = dir.findFile(metadataFilename);
        if (metadataFile != null && metadataFile.exists()) {
            try (InputStream is = getContentResolver().openInputStream(metadataFile.getUri());
                 BufferedReader reader = new BufferedReader(new InputStreamReader(is))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (line.startsWith("mascot_name=")) currentMascotName = line.substring("mascot_name=".length()).trim();
                    else if (line.startsWith("mascot_icon=")) currentMascotIcon = line.substring("mascot_icon=".length()).trim();
                    else if (line.startsWith("theme_color=")) currentThemeColor = line.substring("theme_color=".length()).trim();
                    else if (line.startsWith("theme_background=")) currentThemeBackground = line.substring("theme_background=".length()).trim();
                }
                updateUI(currentMascotName, currentMascotIcon, currentThemeColor, currentThemeBackground);
            } catch (Exception e) {
                showCustomToast("Ошибка загрузки метаданных маскота: " + e.getMessage());
            }
        }
    }

    private void updateUI(String mascotName, String mascotIcon, String themeColor, String themeBackground) {
        setTitle("ChatTribe - " + mascotName);
        if (mascotImage != null) {
            if (folderUri != null) {
                try {
                    DocumentFile dir = DocumentFile.fromTreeUri(this, folderUri);
                    DocumentFile iconFile = dir.findFile(mascotIcon);
                    if (iconFile != null && iconFile.exists()) {
                        try (InputStream is = getContentResolver().openInputStream(iconFile.getUri())) {
                            Bitmap bitmap = BitmapFactory.decodeStream(is);
                            mascotImage.setImageBitmap(bitmap);
                            mascotImage.setAlpha(0f);
                            ObjectAnimator.ofFloat(mascotImage, "alpha", 0f, 1f).setDuration(450).start();
                        }
                    }
                } catch (Exception ignored) {}
            }
        }
        try {
            if (messagesContainer != null) messagesContainer.setBackgroundColor(Color.parseColor(themeBackground));
            // цвета текста применяются при создании сообщений
        } catch (Exception e) { /* ignore */ }
    }

    private String detectContext(String input) {
        String lower = input.toLowerCase();
        for (Map.Entry<String, String> e : contextMap.entrySet()) {
            String keyword = e.getKey().toLowerCase();
            if (lower.contains(keyword)) return e.getValue();
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

    // startIdleTimer (расширитель)
    private void startIdleTimer() {
        lastUserInputTime = System.currentTimeMillis();
        dialogHandler.removeCallbacks(idleCheckRunnable);
        dialogHandler.postDelayed(idleCheckRunnable, 5000);
    }
}
