package com.nemesis.droidcrypt;

import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.view.WindowManager;
import android.widget.ProgressBar;
import android.widget.Toast;
import androidx.appcompat.app.AppCompatActivity;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;
import java.io.File;
import java.io.FileInputStream;
import java.util.Arrays;

public class LockActivity extends AppCompatActivity {
    private TextInputEditText passwordInput;
    private MaterialButton unlockButton;
    private ProgressBar progressBar;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE);
        setContentView(R.layout.activity_lock);

        passwordInput = findViewById(R.id.password_input);
        unlockButton = findViewById(R.id.unlock_button);
        progressBar = findViewById(R.id.progress_bar);

        unlockButton.setOnClickListener(v -> {
            String input = passwordInput.getText().toString().trim();
            char[] password = input.toCharArray();
            if (password.length < 6) {
                Toast.makeText(this, R.string.password_too_short, Toast.LENGTH_SHORT).show();
                Arrays.fill(password, '\0');
                return;
            }
            progressBar.setVisibility(View.VISIBLE);
            unlockButton.setEnabled(false);
            new Thread(() -> {
                try {
                    File file = new File(getExternalFilesDir(null), "tasks/project.txt");
                    if (!file.exists()) {
                        runOnUiThread(() -> startMainActivity(password));
                        return;
                    }
                    byte[] inputBytes = new byte[(int) file.length()];
                    try (FileInputStream fis = new FileInputStream(file)) {
                        fis.read(inputBytes);
                    }
                    byte[] decryptedData = CryptUtils.decrypt(inputBytes, password);
                    runOnUiThread(() -> startMainActivity(password));
                } catch (Exception e) {
                    runOnUiThread(() -> {
                        Toast.makeText(this, "Invalid password or corrupted data", Toast.LENGTH_LONG).show();
                        progressBar.setVisibility(View.GONE);
                        unlockButton.setEnabled(true);
                    });
                } finally {
                    Arrays.fill(password, '\0');
                }
            }).start();
        });
    }

    private void startMainActivity(char[] password) {
        Intent intent = new Intent(this, MainActivity.class);
        intent.putExtra("password", password);
        startActivity(intent);
        finish();
        progressBar.setVisibility(View.GONE);
        unlockButton.setEnabled(true);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (passwordInput != null) {
            passwordInput.setText("");
        }
    }
}
