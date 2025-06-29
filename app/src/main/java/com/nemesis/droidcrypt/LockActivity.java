package com.nemesis.droidcrypt;

import android.content.Intent;
import android.os.Bundle;
import android.widget.Toast;
import androidx.appcompat.app.AppCompatActivity;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;
import java.io.File;
import java.io.FileInputStream;

public class LockActivity extends AppCompatActivity {
    private TextInputEditText passwordInput;
    private MaterialButton unlockButton;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_lock);

        passwordInput = findViewById(R.id.password_input);
        unlockButton = findViewById(R.id.unlock_button);

        unlockButton.setOnClickListener(v -> {
            String password = passwordInput.getText().toString();
            if (password.length() < 6) {
                Toast.makeText(this, R.string.password_too_short, Toast.LENGTH_SHORT).show();
                return;
            }
            new Thread(() -> {
                try {
                    File file = new File(getExternalFilesDir(null), "tasks/project.txt");
                    if (!file.exists()) {
                        startMainActivity(password);
                        return;
                    }
                    byte[] inputBytes = new byte[(  int) file.length()];
                    try (FileInputStream fis = new FileInputStream(file)) {
                        fis.read(inputBytes);
                    }
                    byte[] decryptedData = CryptUtils.decrypt(inputBytes, password);
                    runOnUiThread(() -> startMainActivity(password));
                } catch (Exception e) {
                    runOnUiThread(() -> Toast.makeText(this, "Invalid password or corrupted data", Toast.LENGTH_LONG).show());
                }
            }).start();
        });
    }

    private void startMainActivity(String password) {
        Intent intent = new Intent(this, MainActivity.class);
        intent.putExtra("password", password);
        startActivity(intent);
        finish();
    }
}
