package com.nemesis.droidcrypt;

import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.preference.PreferenceManager;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.Toast;
import androidx.appcompat.app.AppCompatActivity;

public class SettingsActivity extends AppCompatActivity {

private SharedPreferences prefs;
private Button clearCacheButton;
private Button backButton;
private Button saveTemplatesButton;
private EditText templatesInput; // multiline input for bulk templates

private static final String KEY_TEMPLATES = "templates"; // stored as normalized multiline string (\n separated)

@Override
protected void onCreate(Bundle savedInstanceState) {
super.onCreate(savedInstanceState);
setContentView(R.layout.activity_settings);

prefs = PreferenceManager.getDefaultSharedPreferences(this);

clearCacheButton = findViewById(R.id.clearCacheButton);
backButton = findViewById(R.id.backButton);
saveTemplatesButton = findViewById(R.id.saveTemplatesButton);
templatesInput = findViewById(R.id.templatesInput);

// Load existing templates into the multiline EditText
String existing = prefs.getString(KEY_TEMPLATES, "");
templatesInput.setText(existing);

clearCacheButton.setOnClickListener(new View.OnClickListener() {
}
