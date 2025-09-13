package com.nemesis.droidcrypt;

import android.content.SharedPreferences;
import android.os.Bundle;
import android.preference.PreferenceManager;
import android.view\.LayoutInflater;
import android.view\.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.AutoCompleteTextView;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;
import androidx.appcompat.app.AppCompatActivity;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class ChatActivity extends AppCompatActivity {

```
private SharedPreferences prefs;
private TextView responseArea;
private AutoCompleteTextView chatInput;
private Button chatSend;
private ArrayAdapter<String> adapter; // For autocomplete suggestions

// Fallback dummy suggestions
private String[] fallback = {"Привет", "Как дела?", "Расскажи о себе", "Выход"};

// Templates from SharedPreferences: trigger -> answer
private Map<String, String> templatesMap = new HashMap<>();

private static final String KEY_TEMPLATES = "templates";

@Override
protected void onCreate(Bundle savedInstanceState) {
    super.onCreate(savedInstanceState);
    setContentView(R.layout.activity_chat);

    prefs = PreferenceManager.getDefaultSharedPreferences(this);

    responseArea = findViewById(R.id.responseArea);
    chatInput = findViewById(R.id.chatInput);
    chatSend = findViewById(R.id.chatSend);

    loadTemplatesFromPrefs();

    // Build suggestions list: keys from templates + fallback
    List<String> suggestionsList = new ArrayList<>();
    suggestionsList.addAll(templatesMap.keySet());
    for (String s : fallback) {
        if (!suggestionsList.contains(s)) suggestionsList.add(s);
    }

    adapter = new ArrayAdapter<>(this, android.R.layout.simple_dropdown_item_1line, suggestionsList);
    chatInput.setAdapter(adapter);
    chatInput.setThreshold(1);

    chatInput.setOnItemClickListener(new AdapterView.OnItemClickListener() {
        @Override
        public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
            String selected = (String) parent.getItemAtPosition(position);
            chatInput.setText(selected);
            processQuery(selected);
            showCustomToast("Selected: " + selected);
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
}

@Override
protected void onResume() {
    super.onResume();
    // Reload templates in case they were changed in Settings
    loadTemplatesFromPrefs();
    // Update adapter suggestions dynamically
    List<String> suggestionsList = new ArrayList<>();
    suggestionsList.addAll(templatesMap.keySet());
    for (String s : fallback) {
        if (!suggestionsList.contains(s)) suggestionsList.add(s);
    }
    adapter.clear();
    adapter.addAll(suggestionsList);
    adapter.notifyDataSetChanged();
}

private void loadTemplatesFromPrefs() {
    templatesMap.clear();
    String raw = prefs.getString(KEY_TEMPLATES, "");
    if (raw == null || raw.trim().isEmpty()) return;
    String[] lines = raw.split("\\r?\\n");
    for (String line : lines) {
        String l = line.trim();
        if (l.isEmpty()) continue;
        if (!l.contains("=")) continue;
        String[] parts = l.split("=", 2);
        String key = parts[0].trim().toLowerCase();
        String val = parts.length > 1 ? parts[1].trim() : "";
        if (!key.isEmpty()) templatesMap.put(key, val);
    }
}

private void processQuery(String query) {
    String q = query.toLowerCase().trim();

    // 1) Check templates for exact or contained trigger
    for (Map.Entry<String, String> e : templatesMap.entrySet()) {
        String trigger = e.getKey();
        String answer = e.getValue();
        if (q.equals(trigger) || q.contains(trigger)) {
            responseArea.setText("Bot: " + (answer.isEmpty() ? "" : answer));
            return;
        }
    }

    // 2) Fallback rule-based
    String response = getDummyResponse(q);
    responseArea.setText("Bot: " + response);
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
