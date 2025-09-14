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
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * Исправленный ChatActivity:
 * - устранено зацикливание контекстов
 * - антиспам считается только один раз при входе запроса
 * - таймер idle исправлен и работает
 * - плавная анимация маскота через ObjectAnimator
 * - добавление сообщений как отдельные View в LinearLayout (чтобы избежать больших append в TextView)
 * - проверка persisted-uri permissions и корректная обработка отсутствия прав
 *
 * Совместимость: minSdk >= 19
 */
public class ChatActivity extends AppCompatActivity {

    private Uri folderUri;
    private ScrollView scrollView;
    private AutoCompleteTextView queryInput;
    private Button sendButton;
    private Button clearButton;
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
    // Anti-spam responses
    private final List<String> antiSpamResponses = new ArrayList<>();
    // Для маскотов и тем
    private final List<Map<String, String>> mascotList = new ArrayList<>();
    private final List<String> dialogLines = new ArrayList<>();
    private String currentMascotName = "Racky";
    private String currentMascotIcon = "raccoon_icon.png";
    private String currentThemeColor = "#00FF00";
    private String currentThemeBackground = "#000000";
    private String currentContext = "base.txt";
    private String lastQuery = "";

    // Для диалогов
    private final List<Dialog> dialogs = new ArrayList<>();
    private Dialog currentDialog = null;
    private int currentDialogIndex = 0;
    private final Handler dialogHandler = new Handler(Looper.getMainLooper());
    private Runnable dialogRunnable;

    // idle check runnable
    private Runnable idleCheckRunnable;
    private long lastUserInputTime = System.currentTimeMillis();

    private final Random random = new Random();

    // Для отслеживания повторов одного запроса (считаем только один раз при входе запроса)
    private final Map<String, Integer> queryCountMap = new HashMap<>();

    // Ограничения
    private static final int MAX_CONTEXT_SWITCH = 3;
    private static final int MAX_MESSAGES = 300; // чтобы не удерживать бесконечно UI-вью

    // Класс Dialog для хранения диалогов
    private static class Dialog {
        String name;
        List<Map<String, String>> replies;
        Dialog(String name) {
            this.name = name;
            this.replies = new ArrayList<>();
        }
    }

    public ChatActivity() {
        // init anti-spam
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

        // UI
        scrollView = findViewById(R.id.scrollView);
        queryInput = findViewById(R.id.queryInput);
        sendButton = findViewById(R.id.sendButton);
        clearButton = findViewById(R.id.clearButton);
        mascotImage = findViewById(R.id.mascot_image);
        chatLayout = findViewById(R.id.chat_layout);

        // Получение folderUri из Intent или persisted permissions
        Intent intent = getIntent();
        folderUri = intent != null ? intent.getParcelableExtra("folderUri") : null;
        if (folderUri == null) {
            for (android.content.UriPermission p : getContentResolver().getPersistedUriPermissions()) {
                if (p.isReadPermission()) {
                    folderUri = p.getUri();
                    break;
                }
            }
        }

        // Блокировка скриншотов — используем default SharedPreferences (так, как SettingsActivity сохраняет)
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(this);
        boolean disableScreenshots = prefs.getBoolean("disableScreenshots", false);
        if (disableScreenshots) {
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
        } else {
            getWindow().clearFlags(WindowManager.LayoutParams.FLAG_SECURE);
        }

        // Если folderUri нет — warn и fallback
        if (folderUri == null) {
            showCustomToast("Папка не выбрана! Откройте настройки и выберите папку.");
            loadFallbackTemplates();
            updateAutoComplete();
        } else {
            loadTemplatesFromFile(currentContext);
            updateAutoComplete();
        }

        queryInput.setOnItemClickListener((parent, view, position, id) -> {
            String selected = (String) parent.getItemAtPosition(position);
            queryInput.setText(selected);
            processUserQuery(selected);
        });

        sendButton.setOnClickListener(v -> {
            String input = queryInput.getText().toString().trim();
            if (!input.isEmpty()) {
                processUserQuery(input);
                queryInput.setText("");
            }
        });

        if (clearButton != null) {
            clearButton.setOnClickListener(v -> {
                clearChat();
            });
        }

        // idle runnable: проверять каждые 5 секунд
        idleCheckRunnable = new Runnable() {
            @Override
            public void run() {
                long idle = System.currentTimeMillis() - lastUserInputTime;
                if (idle >= 25000 && !dialogs.isEmpty()) {
                    startRandomDialog();
                }
                // ресchedule
                dialogHandler.postDelayed(this, 5000);
            }
        };
        // стартуем
        dialogHandler.postDelayed(idleCheckRunnable, 5000);
    }

    @Override
    protected void onResume() {
        super.onResume();
        // Перезагрузим контекст (если папка есть)
        if (folderUri != null) {
            loadTemplatesFromFile(currentContext);
        }
        updateAutoComplete();
        // Reschedule idle check to make sure it runs
        dialogHandler.removeCallbacks(idleCheckRunnable);
        dialogHandler.postDelayed(idleCheckRunnable, 5000);
    }

    @Override
    protected void onPause() {
        super.onPause();
        stopDialog();
        dialogHandler.removeCallbacksAndMessages(null);
    }

    /* ========== core: обработка запроса (без рекурсии) ========== */

    /**
     * Вход для пользовательского запроса: обновляем счетчики и вызываем обработчик.
     * Здесь гарантируем, что счетчик повторов увеличится только 1 раз.
     */
    private void processUserQuery(String query) {
        String q = query.trim().toLowerCase();
        if (q.isEmpty()) return;

        // обновляем время и останавливаем диалог
        lastUserInputTime = System.currentTimeMillis();
        stopDialog();

        // обновление счетчика повторов (только один раз)
        if (q.equals(lastQuery)) {
            Integer cnt = queryCountMap.get(q);
            queryCountMap.put(q, (cnt != null ? cnt : 0) + 1);
        } else {
            queryCountMap.clear();
            queryCountMap.put(q, 1);
            lastQuery = q;
        }

        addChatMessage("Ты", query);

        // теперь основной обработчик (итеративно) с лимитом переключения контекстов
        String qOrig = q;
        int switches = 0;
        boolean responded = false;
        while (switches <= MAX_CONTEXT_SWITCH && !responded) {

            // анти-спам: если запрос повторён 5 раз подряд
            int repeats = queryCountMap.getOrDefault(qOrig, 0);
            if (repeats >= 5) {
                String spamResp = antiSpamResponses.get(random.nextInt(antiSpamResponses.size()));
                addChatMessage(currentMascotName, spamResp);
                startIdleTimer(); // перезапустить таймер проверки
                responded = true;
                break;
            }

            // Проверка смены контекста (динамически из contextMap)
            String newContext = detectContext(q);
            if (newContext != null && !newContext.equals(currentContext)) {
                // переключаем контекст и пробуем ответить в новом контексте
                currentContext = newContext;
                loadTemplatesFromFile(currentContext);
                updateAutoComplete();
                addChatMessage(currentMascotName, "Переключаюсь на " + currentContext + "...");
                // не делаем рекурсивных вызовов — увеличим счетчик переключений и продолжим loop
                switches++;
                // continue -> повторная попытка обработки того же q в новом контексте
                continue;
            }

            // Проверка ключевых слов (-ключ=...)
            boolean handledByKeyword = false;
            for (Map.Entry<String, List<String>> entry : keywordResponses.entrySet()) {
                String keyword = entry.getKey();
                if (q.contains(keyword)) {
                    List<String> responses = entry.getValue();
                    if (!responses.isEmpty()) {
                        String response = responses.get(random.nextInt(responses.size()));
                        addChatMessage(currentMascotName, response);
                        triggerRandomDialog();
                        startIdleTimer();
                        responded = true;
                        handledByKeyword = true;
                        break;
                    }
                }
            }
            if (handledByKeyword) break;

            // Поиск точного триггера
            List<String> possibleResponses = templatesMap.get(q);
            if (possibleResponses != null && !possibleResponses.isEmpty()) {
                String response = possibleResponses.get(random.nextInt(possibleResponses.size()));
                addChatMessage(currentMascotName, response);
                triggerRandomDialog();
                startIdleTimer();
                responded = true;
                break;
            }

            // Если мы НЕ в base.txt и не нашли ответ — вернёмся к base.txt и попробуем ещё раз
            if (!"base.txt".equals(currentContext)) {
                currentContext = "base.txt";
                loadTemplatesFromFile(currentContext);
                updateAutoComplete();
                addChatMessage(currentMascotName, "Возвращаюсь к общей теме...");
                switches++;
                continue; // ещё одна попытка
            }

            // Если мы в base.txt и ничего не нашли — fallback
            String fallbackResp = getDummyResponse(q);
            addChatMessage(currentMascotName, fallbackResp);
            triggerRandomDialog();
            startIdleTimer();
            responded = true;
            break;
        } // while

        // если после MAX_CONTEXT_SWITCH так и не ответили — выдаём ошибку-фаилбек
        if (! (templatesMap.containsKey(q) || responded) ) {
            addChatMessage(currentMascotName, "Не могу найти ответ, попробуй переформулировать.");
        }
    }

    /* ========== messages UI (очень простая, но эффективная реализация без RecyclerView) ========== */

    private void addChatMessage(String sender, String text) {
        if (chatLayout == null) return;

        // контейнер сообщения
        LinearLayout item = new LinearLayout(this);
        item.setOrientation(LinearLayout.VERTICAL);
        int pad = dpToPx(8);
        item.setPadding(pad, pad/2, pad, pad/2);

        TextView tvSender = new TextView(this);
        tvSender.setText(sender + ":");
        tvSender.setTextSize(12);
        tvSender.setTextColor(Color.parseColor("#AAAAAA"));
        item.addView(tvSender, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextSize(16);
        try { tv.setTextColor(Color.parseColor(currentThemeColor)); } catch (Exception e) { tv.setTextColor(Color.WHITE); }
        item.addView(tv, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        chatLayout.addView(item);
        // ограничиваем число сообщений в логе
        if (chatLayout.getChildCount() > MAX_MESSAGES) {
            // удаляем первые (старые) элементы
            int removeCount = chatLayout.getChildCount() - MAX_MESSAGES;
            for (int i = 0; i < removeCount; i++) {
                chatLayout.removeViewAt(0);
            }
        }

        // скроллим вниз
        scrollView.post(() -> scrollView.fullScroll(View.FOCUS_DOWN));
    }

    private int dpToPx(int dp) {
        float density = getResources().getDisplayMetrics().density;
        return Math.round(dp * density);
    }

    private void clearChat() {
        if (chatLayout != null) {
            chatLayout.removeAllViews();
        }
        queryCountMap.clear();
        lastQuery = "";
    }

    /* ========== загрузка шаблонов и парсинг ========== */

    private void loadTemplatesFromFile(String filename) {
        templatesMap.clear();
        keywordResponses.clear();
        mascotList.clear();
        dialogLines.clear();
        dialogs.clear();
        // reset theme to defaults (we may override later)
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
                            for (String dialog : lines) {
                                String d = dialog.trim();
                                if (!d.isEmpty()) dialogLines.add(d);
                            }
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

            // Выбор случайного маскота для base.txt
            if ("base.txt".equals(filename) && !mascotList.isEmpty()) {
                Map<String, String> selectedMascot = mascotList.get(random.nextInt(mascotList.size()));
                currentMascotName = selectedMascot.get("name");
                currentMascotIcon = selectedMascot.get("icon");
                currentThemeColor = selectedMascot.get("color");
                currentThemeBackground = selectedMascot.get("background");
            }

            updateUI(currentMascotName, currentMascotIcon, currentThemeColor, currentThemeBackground);

            // Запуск idle timer: handled separately (idleCheckRunnable)
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
        // Используем ключи как есть (они в lower-case)
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

    /* ========== Диалоги / idle / random ========== */

    private void startIdleTimer() {
        // Обновляем lastUserInputTime и убедимся, что idleCheckRunnable будет выполнен
        lastUserInputTime = System.currentTimeMillis();
        dialogHandler.removeCallbacks(idleCheckRunnable);
        dialogHandler.postDelayed(idleCheckRunnable, 5000);
    }

    private void triggerRandomDialog() {
        // 30% шанс на случайную реплику из dialog_lines
        if (!dialogLines.isEmpty() && random.nextDouble() < 0.3) {
            dialogHandler.postDelayed(() -> {
                if (dialogLines.isEmpty() || mascotList.isEmpty()) return;
                String dialog = dialogLines.get(random.nextInt(dialogLines.size()));
                Map<String, String> randomMascotMap = mascotList.get(random.nextInt(mascotList.size()));
                String randomMascot = randomMascotMap.getOrDefault("name", currentMascotName);
                loadMascotMetadata(randomMascot);
                addChatMessage(randomMascot, dialog);
            }, 2000);
        }

        // 10% пасхалка
        if (!mascotList.isEmpty() && random.nextDouble() < 0.1) {
            dialogHandler.postDelayed(() -> {
                Map<String, String> randomMascotMap = mascotList.get(random.nextInt(mascotList.size()));
                String randomMascot = randomMascotMap.getOrDefault("name", currentMascotName);
                loadMascotMetadata(randomMascot);
                addChatMessage(randomMascot, "Эй, мы не закончили!");
            }, 3000);
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
                    // schedule next line
                    dialogHandler.postDelayed(this, random.nextInt(15000) + 10000); // 10-25 сек
                } else {
                    // После завершения — запустим новый диалог с задержкой
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

    /* ========== Mascot metadata & UI ========== */

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

    private void updateUI(String mascotName, String mascotIcon, String themeColor, String themeBackground) {
        setTitle("ChatTribe - " + mascotName);
        // Загрузка иконки (с плавным появлением)
        if (mascotImage != null) {
            if (folderUri != null) {
                try {
                    DocumentFile dir = DocumentFile.fromTreeUri(this, folderUri);
                    DocumentFile iconFile = dir.findFile(mascotIcon);
                    if (iconFile != null && iconFile.exists()) {
                        try (InputStream is = getContentResolver().openInputStream(iconFile.getUri())) {
                            Bitmap bitmap = BitmapFactory.decodeStream(is);
                            mascotImage.setImageBitmap(bitmap);
                            // ObjectAnimator для alpha
                            mascotImage.setAlpha(0f);
                            ObjectAnimator.ofFloat(mascotImage, "alpha", 0f, 1f)
                                    .setDuration(500)
                                    .start();
                        }
                    } else {
                        // Иконки может не быть — не крешимся
                    }
                } catch (Exception e) {
                    // игнорируем, показываем тост, если нужно
                }
            }
        }

        try {
            if (chatLayout != null) {
                chatLayout.setBackgroundColor(Color.parseColor(themeBackground));
            }
            // остальные цвета применяются при создании сообщений
        } catch (Exception e) {
            showCustomToast("Ошибка применения темы: " + e.getMessage());
        }
    }

    /* ========== вспомогательные методы ========== */

    private String detectContext(String input) {
        String lower = input.toLowerCase();
        for (Map.Entry<String, String> entry : contextMap.entrySet()) {
            String keyword = entry.getKey().toLowerCase();
            if (lower.contains(keyword)) {
                return entry.getValue();
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
