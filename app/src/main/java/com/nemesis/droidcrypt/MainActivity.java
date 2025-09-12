package com.nemesis.droidcrypt;

import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.preference.PreferenceManager;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;
import androidx.appcompat.app.AppCompatActivity;

public class MainActivity extends AppCompatActivity {

    private SharedPreferences prefs; // For prototype storage
    private TextView titleText;
    private Button sendButton; // Placeholder for navigation

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        prefs = PreferenceManager.getDefaultSharedPreferences(this); // Internal storage

        titleText = findViewById(R.id.titleText);
        sendButton = findViewById(R.id.sendButton);

        // Green text already in layout

        sendButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                // Navigate to ChatActivity for input/response
                Intent intent = new Intent(MainActivity.this, ChatActivity.class);
                startActivity(intent);
            }
        });

        // TODO: Later add SAF folder selection if needed, store Uri in prefs
    }
}
