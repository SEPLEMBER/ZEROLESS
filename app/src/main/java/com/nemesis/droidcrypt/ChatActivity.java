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
import android.widget.Toast;
import androidx.appcompat.app.AppCompatActivity;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class ChatActivity extends AppCompatActivity {

private SharedPreferences prefs;
private TextView responseArea;
private AutoCompleteTextView chatInput;
private Button chatSend;
private ArrayAdapter<String> adapter; // For autocomplete suggestions

// Fallback dummy suggestions
private String[] fallback = {"Привет", "Как дела?", "Расскажи о себе", "Выход"};


// Templates from SharedPreferences: trigger -> answer
}
