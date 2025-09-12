package com.nemesis.droidcrypt;

import android.content.SharedPreferences;
import android.os.Bundle;
import android.preference.PreferenceManager;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.AutoCompleteTextView;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast; // For popup notifications
import androidx.appcompat.app.AppCompatActivity;

public class ChatActivity extends AppCompatActivity {

    private SharedPreferences prefs;
    private TextView responseArea;
    private AutoCompleteTextView chatInput;
    private Button chatSend;
    private ArrayAdapter<String> adapter; // For autocomplete suggestions

    // Dummy suggestions for prototype (from index.txt later)
    private String[] suggestions = {"Привет", "Как дела?", "Расскажи о себе", "Выход"};

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_chat);

        prefs = PreferenceManager.getDefaultSharedPreferences(this);

        responseArea = findViewById(R.id.responseArea);
        chatInput = findViewById(R.id.chatInput);
        chatSend = findViewById(R.id.chatSend);

        // Setup autocomplete with popup suggestions
        adapter = new ArrayAdapter<>(this, android.R.layout.simple_dropdown_item_1line, suggestions);
        chatInput.setAdapter(adapter);
        chatInput.setThreshold(1);

        // On item click: Send selected suggestion as query
        chatInput.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
                String selected = (String) parent.getItemAtPosition(position);
                chatInput.setText(selected);
                processQuery(selected); // Index and respond
                Toast.makeText(ChatActivity.this, "Selected: " + selected, Toast.LENGTH_SHORT).show(); // Popup notification
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

    private void processQuery(String query) {
        // Prototype logic: Dummy response (later parse txt via SharedPrefs or load)
        String response = getDummyResponse(query.toLowerCase());
        responseArea.setText("Bot: " + response);
        // No save to prefs or external; all in memory
    }

    private String getDummyResponse(String query) {
        if (query.contains("привет")) return "Привет! Чем могу помочь?";
        if (query.contains("дела")) return "Всё отлично, а у тебя?";
        return "Не понял запрос. Попробуй другой вариант.";
    }
}
