package com.nemesis.droidcrypt;

import android.Manifest;
import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.OpenableColumns;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.View;
import android.view.WindowManager;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.ProgressBar;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import org.bouncycastle.crypto.generators.SCrypt;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.security.GeneralSecurityException;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Locale;
import android.util.Base64;

import javax.crypto.AEADBadTagException;
import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.UnsupportedEncodingException;

public class MainActivity extends AppCompatActivity {

    private EditText inputEditText, passwordEditText;
    private TextView outputTextView, passwordDisplay;
    private ProgressBar progress;
    private static final int FILE_PICKER_REQUEST_CODE = 123;
    private static final int PERMISSION_REQUEST_CODE = 124;
    private Uri selectedFileUri;
    private byte[] salt, iv, derivedKey;
    private SecretKey secretKey;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        loadSavedLanguage();
        super.onCreate(savedInstanceState);
        getWindow().setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE);
        setContentView(R.layout.activity_main);

        inputEditText = findViewById(R.id.inputEditText);
        passwordEditText = findViewById(R.id.passwordEditText);
        outputTextView = findViewById(R.id.outputTextView);
        passwordDisplay = findViewById(R.id.passwordDisplay);
        progress = findViewById(R.id.textEncryptProgress);

        passwordEditText.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {}
            @Override
            public void afterTextChanged(Editable s) {
                passwordDisplay.setText(s.toString());
            }
        });

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M &&
                ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.READ_EXTERNAL_STORAGE}, PERMISSION_REQUEST_CODE);
        }
    }

    private void loadSavedLanguage() {
        SharedPreferences prefs = getSharedPreferences("app_prefs", MODE_PRIVATE);
        String languageCode = prefs.getString("language", "en");
        setLocale(languageCode);
    }

    private void setLocale(String languageCode) {
        Locale locale = new Locale(languageCode);
        Locale.setDefault(locale);
        Configuration config = new Configuration();
        config.setLocale(locale);
        getResources().updateConfiguration(config, getResources().getDisplayMetrics());
    }

    @Override
    protected void onResume() {
        super.onResume();
        ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (clipboard.hasPrimaryClip() && clipboard.getPrimaryClip().getItemCount() > 0) {
            CharSequence pasteData = clipboard.getPrimaryClip().getItemAt(0).getText();
            if (pasteData != null && inputEditText.getText().toString().isEmpty()) {
                inputEditText.setText(pasteData);
                showToast(getString(R.string.success_text_pasted));
            }
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == PERMISSION_REQUEST_CODE && (grantResults.length == 0 || grantResults[0] != PackageManager.PERMISSION_GRANTED)) {
            showToast(getString(R.string.error_permission_denied));
        }
    }

    public void pickFile(View view) {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");
        startActivityForResult(intent, FILE_PICKER_REQUEST_CODE);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == FILE_PICKER_REQUEST_CODE && resultCode == Activity.RESULT_OK && data != null) {
            selectedFileUri = data.getData();
            if (selectedFileUri != null) {
                getContentResolver().takePersistableUriPermission(selectedFileUri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
                showToast(getString(R.string.success_file_selected, getFileName(selectedFileUri)));
            }
        }
    }

    public void performTextEncryption(View view) {
        process(true, true);
    }

    public void performTextDecryption(View view) {
        process(false, true);
    }

    public void performFileEncryption(View view) {
        process(true, false);
    }

    public void performFileDecryption(View view) {
        process(false, false);
    }

    private void process(boolean isEncryption, boolean isText) {
        String input = inputEditText.getText().toString();
        String password = passwordEditText.getText().toString();
        if (password.length() < 6) {
            showToast(getString(R.string.error_password_short));
            return;
        }
        if (isText && input.isEmpty()) {
            showToast(String.format(getString(R.string.error_empty_input), isEncryption ? getString(R.string.encrypt_button).toLowerCase() : getString(R.string.decrypt_button).toLowerCase()));
            return;
        }
        if (!isText && selectedFileUri == null) {
            showToast(String.format(getString(R.string.error_file_not_selected), isEncryption ? getString(R.string.encrypt_file_button).toLowerCase() : getString(R.string.decrypt_file_button).toLowerCase()));
            return;
        }
        progress.setVisibility(View.VISIBLE);
        new Thread(() -> {
            try {
                String result = null;
                if (isText) {
                    result = isEncryption ? encryptText(input, password) : decryptText(input, password);
                } else {
                    byte[] processedBytes = isEncryption ? encryptFile(selectedFileUri, password) : decryptFile(selectedFileUri, password);
                    String fileName = isEncryption ? getFileName(selectedFileUri) + ".enc" : getFileNameWithoutExtension(selectedFileUri);
                    saveFile(processedBytes, fileName);
                }
                final String finalResult = result;
                runOnUiThread(() -> {
                    progress.setVisibility(View.GONE);
                    if (isText && finalResult != null) {
                        showOutputText(finalResult);
                    } else if (!isText) {
                        showToast(getString(R.string.success_file_processed, isEncryption ? getString(R.string.encrypt_file_button).toLowerCase() : getString(R.string.decrypt_file_button).toLowerCase()));
                    }
                    clearSensitiveData();
                });
            } catch (AEADBadTagException e) {
                showError(getString(R.string.error_tampering_detected));
            } catch (Exception e) {
                showError(getString(R.string.error_process_failed, isEncryption ? (isText ? getString(R.string.encrypt_button) : getString(R.string.encrypt_file_button)).toLowerCase() : (isText ? getString(R.string.decrypt_button) : getString(R.string.decrypt_file_button)).toLowerCase(), e.getMessage()));
            } finally {
                runOnUiThread(() -> progress.setVisibility(View.GONE));
            }
        }).start();
    }

    private void saveFile(byte[] bytes, String fileName) {
        File directory = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
        File file = new File(directory, fileName);
        try (FileOutputStream fos = new FileOutputStream(file)) {
            fos.write(bytes);
            showToast(getString(R.string.success_file_saved, file.getAbsolutePath()));
        } catch (IOException e) {
            showError(getString(R.string.error_save_failed, e.getMessage()));
        } finally {
            Arrays.fill(bytes, (byte) 0);
        }
    }

    private byte[] encryptFile(Uri fileUri, String password) throws IOException, GeneralSecurityException {
        try (InputStream inputStream = getContentResolver().openInputStream(fileUri);
             BufferedInputStream bis = new BufferedInputStream(inputStream)) {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            byte[] buffer = new byte[4096];
            int bytesRead;
            while ((bytesRead = bis.read(buffer)) != -1) {
                baos.write(buffer, 0, bytesRead);
            }
            byte[] inputBytes = baos.toByteArray();

            salt = generateSalt();
            secretKey = generateSecretKey(password, salt);
            Cipher cipher = getCipher(Cipher.ENCRYPT_MODE, secretKey, null);
            iv = cipher.getIV();
            byte[] cipherText = cipher.doFinal(inputBytes);

            byte[] result = concatenateArrays(salt, iv, cipherText);
            Arrays.fill(inputBytes, (byte) 0);
            Arrays.fill(cipherText, (byte) 0);
            return result;
        }
    }

    private byte[] decryptFile(Uri fileUri, String password) throws IOException, GeneralSecurityException {
        try (InputStream inputStream = getContentResolver().openInputStream(fileUri)) {
            byte[] inputBytes = readBytes(inputStream);
            salt = Arrays.copyOfRange(inputBytes, 0, 16);
            iv = Arrays.copyOfRange(inputBytes, 16, 28);
            byte[] cipherText = Arrays.copyOfRange(inputBytes, 28, inputBytes.length);

            secretKey = generateSecretKey(password, salt);
            Cipher cipher = getCipher(Cipher.DECRYPT_MODE, secretKey, iv);
            byte[] result = cipher.doFinal(cipherText);

            Arrays.fill(inputBytes, (byte) 0);
            Arrays.fill(cipherText, (byte) 0);
            return result;
        }
    }

    private String encryptText(String inputText, String password) throws GeneralSecurityException, UnsupportedEncodingException {
        salt = generateSalt();
        secretKey = generateSecretKey(password, salt);
        Cipher cipher = getCipher(Cipher.ENCRYPT_MODE, secretKey, null);
        iv = cipher.getIV();
        byte[] cipherText = cipher.doFinal(inputText.getBytes("UTF-8"));
        byte[] resultBytes = concatenateArrays(salt, iv, cipherText);
        showToast("Salt: " + salt.length + ", IV: " + iv.length + ", CipherText: " + cipherText.length + ", Result: " + resultBytes.length);
        String result = Base64.encodeToString(resultBytes, Base64.DEFAULT);
        Arrays.fill(cipherText, (byte) 0);
        return result;
    }

    private String decryptText(String inputText, String password) throws GeneralSecurityException, UnsupportedEncodingException {
        byte[] inputBytes = Base64.decode(inputText, Base64.DEFAULT);
        salt = Arrays.copyOfRange(inputBytes, 0, 16);
        iv = Arrays.copyOfRange(inputBytes, 16, 28);
        byte[] cipherText = Arrays.copyOfRange(inputBytes, 28, inputBytes.length);
        secretKey = generateSecretKey(password, salt);
        Cipher cipher = getCipher(Cipher.DECRYPT_MODE, secretKey, iv);
        byte[] decryptedBytes = cipher.doFinal(cipherText);
        String result = new String(decryptedBytes, "UTF-8");
        Arrays.fill(inputBytes, (byte) 0);
        Arrays.fill(cipherText, (byte) 0);
        Arrays.fill(decryptedBytes, (byte) 0);
        return result;
    }

    private Cipher getCipher(int mode, SecretKey secretKey, byte[] iv) throws GeneralSecurityException {
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        if (iv == null) {
            cipher.init(mode, secretKey);
        } else {
            cipher.init(mode, secretKey, new GCMParameterSpec(128, iv));
        }
        return cipher;
    }

    private SecretKey generateSecretKey(String password, byte[] salt) throws NoSuchAlgorithmException {
        derivedKey = SCrypt.generate(password.getBytes(), salt, 16384, 8, 1, 32);
        return new SecretKeySpec(derivedKey, "AES");
    }

    private byte[] generateSalt() {
        byte[] salt = new byte[16];
        new SecureRandom().nextBytes(salt);
        return salt;
    }

    private byte[] concatenateArrays(byte[]... arrays) {
        int totalLength = 0;
        for (byte[] array : arrays) {
            totalLength += array.length;
        }
        byte[] result = new byte[totalLength];
        int currentIndex = 0;
        for (byte[] array : arrays) {
            System.arraycopy(array, 0, result, currentIndex, array.length);
            currentIndex += array.length;
        }
        return result;
    }

    private String getFileName(Uri uri) {
        String result = null;
        if (uri != null) {
            try (Cursor cursor = getContentResolver().query(uri, null, null, null, null)) {
                if (cursor != null && cursor.moveToFirst()) {
                    result = cursor.getString(cursor.getColumnIndexOrThrow(OpenableColumns.DISPLAY_NAME));
                }
            }
        }
        return result;
    }

    private String getFileNameWithoutExtension(Uri uri) {
        String fileName = getFileName(uri);
        if (fileName != null && fileName.endsWith(".enc")) {
            fileName = fileName.substring(0, fileName.length() - 4);
        }
        return fileName;
    }

    private byte[] readBytes(InputStream inputStream) throws IOException {
        ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream();
        byte[] buffer = new byte[4096];
        int bytesRead;
        while ((bytesRead = inputStream.read(buffer)) != -1) {
            byteArrayOutputStream.write(buffer, 0, bytesRead);
        }
        return byteArrayOutputStream.toByteArray();
    }

    private void clearSensitiveData() {
        if (salt != null) {
            Arrays.fill(salt, (byte) 0);
            salt = null;
        }
        if (iv != null) {
            Arrays.fill(iv, (byte) 0);
            iv = null;
        }
        if (derivedKey != null) {
            Arrays.fill(derivedKey, (byte) 0);
            derivedKey = null;
        }
        secretKey = null;
        System.gc();
    }

    private void clearAllData() {
        inputEditText.setText("");
        passwordEditText.setText("");
        outputTextView.setText("");
        passwordDisplay.setText("");
        selectedFileUri = null;
        ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (clipboard != null) {
            clipboard.setPrimaryClip(ClipData.newPlainText("clear", ""));
        }
        clearSensitiveData();
        showToast(getString(R.string.success_data_cleared));
    }

    public void copyToClipboard(View view) {
        String outputText = outputTextView.getText().toString();
        if (!outputText.isEmpty()) {
            ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            ClipData clip = ClipData.newPlainText("Output Text", outputText);
            if (clipboard != null) {
                clipboard.setPrimaryClip(clip);
                showToast(getString(R.string.success_text_copied));
            } else {
                showError(getString(R.string.error_clipboard_unavailable));
            }
        } else {
            showToast(getString(R.string.error_output_empty));
        }
    }

    public void clearInput(View view) {
        inputEditText.setText("");
        showToast(getString(R.string.success_input_cleared));
    }

    public void pasteInput(View view) {
        ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (clipboard != null && clipboard.hasPrimaryClip()) {
            ClipData.Item item = clipboard.getPrimaryClip().getItemAt(0);
            CharSequence pasteData = item.getText();
            if (pasteData != null) {
                inputEditText.setText(pasteData.toString());
                showToast(getString(R.string.success_text_pasted));
            } else {
                showToast(getString(R.string.error_clipboard_empty));
            }
        } else {
            showToast(getString(R.string.error_clipboard_empty));
        }
    }

    public void exitWithObfuscation(View view) {
        ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        Handler handler = new Handler(Looper.getMainLooper());
        for (int i = 1; i <= 45; i++) {
            final int num = i;
            handler.postDelayed(() -> {
                ClipData clip = ClipData.newPlainText("obfuscation", String.valueOf(num));
                clipboard.setPrimaryClip(clip);
                if (num == 45) {
                    clearAllData();
                    finish();
                }
            }, i * 50);
        }
        showToast(getString(R.string.success_obfuscating));
    }

    public void showInfo(View view) {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle(R.string.info_title);
        builder.setMessage(R.string.info_message);

        // Add language selection
        Spinner languageSpinner = new Spinner(this);
        ArrayAdapter<CharSequence> adapter = ArrayAdapter.createFromResource(this,
                R.array.languages, android.R.layout.simple_spinner_item);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        languageSpinner.setAdapter(adapter);

        SharedPreferences prefs = getSharedPreferences("app_prefs", MODE_PRIVATE);
        String[] languageCodes = getResources().getStringArray(R.array.language_codes);
        languageSpinner.setSelection(getCurrentLanguageIndex(prefs, languageCodes));

        builder.setView(languageSpinner);
        builder.setPositiveButton(android.R.string.ok, (dialog, which) -> {
            int selectedPosition = languageSpinner.getSelectedItemPosition();
            String selectedCode = languageCodes[selectedPosition];
            if (!prefs.getString("language", "en").equals(selectedCode)) {
                prefs.edit().putString("language", selectedCode).apply();
                setLocale(selectedCode);
                recreate();
            }
        });
        builder.setNegativeButton(android.R.string.cancel, null);
        builder.show();
    }

    private int getCurrentLanguageIndex(SharedPreferences prefs, String[] languageCodes) {
        String savedLanguage = prefs.getString("language", "en");
        for (int i = 0; i < languageCodes.length; i++) {
            if (languageCodes[i].equals(savedLanguage)) {
                return i;
            }
        }
        return 0;
    }

    public void showOutputText(String text) {
        outputTextView.setVisibility(View.VISIBLE);
        outputTextView.setText(text);
        ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        ClipData clip = ClipData.newPlainText("Encrypted Text", text);
        clipboard.setPrimaryClip(clip);
        showToast(getString(R.string.success_text_copied));
    }

    public void hideOutputText() {
        outputTextView.setVisibility(View.INVISIBLE);
        outputTextView.setText("");
    }

    private void showToast(String message) {
        runOnUiThread(() -> Toast.makeText(this, message, Toast.LENGTH_SHORT).show());
    }

    private void showError(String message) {
        runOnUiThread(() -> Toast.makeText(this, message, Toast.LENGTH_LONG).show());
    }
}
