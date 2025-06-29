package com.nemesis.droidcrypt;

import android.os.Bundle;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.ProgressBar;
import android.widget.Spinner;
import android.widget.Toast;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class MainActivity extends AppCompatActivity {
    private TextInputEditText taskInput, projectInput;
    private Spinner prioritySpinner, statusSpinner, emojiSpinner;
    private MaterialButton addButton;
    private RecyclerView taskRecyclerView;
    private ProgressBar progressBar;
    private TaskAdapter taskAdapter;
    private List<Task> tasks = new ArrayList<>();
    private char[] password;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        password = getIntent().getCharArrayExtra("password");
        projectInput = findViewById(R.id.project_input);
        taskInput = findViewById(R.id.task_input);
        prioritySpinner = findViewById(R.id.priority_spinner);
        statusSpinner = findViewById(R.id.status_spinner);
        emojiSpinner = findViewById(R.id.emoji_spinner);
        addButton = findViewById(R.id.add_button);
        taskRecyclerView = findViewById(R.id.task_recycler_view);
        progressBar = findViewById(R.id.progress_bar);

        taskAdapter = new TaskAdapter(tasks, this::deleteTask, this::editTask);
        taskRecyclerView.setLayoutManager(new LinearLayoutManager(this));
        taskRecyclerView.setAdapter(taskAdapter);

        setupSpinners();
        loadTasks();

        addButton.setOnClickListener(v -> addTask());
    }

    private void setupSpinners() {
        ArrayAdapter<CharSequence> priorityAdapter = ArrayAdapter.createFromResource(this,
                R.array.priority_array, android.R.layout.simple_spinner_item);
        priorityAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        prioritySpinner.setAdapter(priorityAdapter);

        ArrayAdapter<CharSequence> statusAdapter = ArrayAdapter.createFromResource(this,
                R.array.status_array, android.R.layout.simple_spinner_item);
        statusAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        statusSpinner.setAdapter(statusAdapter);

        ArrayAdapter<CharSequence> emojiAdapter = ArrayAdapter.createFromResource(this,
                R.array.emoji_array, android.R.layout.simple_spinner_item);
        emojiAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        emojiSpinner.setAdapter(emojiAdapter);
    }

    private void addTask() {
        String project = projectInput.getText().toString().trim();
        String task = taskInput.getText().toString().trim();
        String priority = prioritySpinner.getSelectedItem().toString();
        String status = statusSpinner.getSelectedItem().toString();
        String emoji = emojiSpinner.getSelectedItem().toString();

        if (project.isEmpty() || task.isEmpty()) {
            showToast(R.string.empty_input);
            return;
        }

        tasks.add(new Task(project, task, priority, status, emoji));
        taskAdapter.notifyDataSetChanged();
        projectInput.setText("");
        taskInput.setText("");
        saveTasks();
    }

    private void deleteTask(int position) {
        tasks.remove(position);
        taskAdapter.notifyDataSetChanged();
        saveTasks();
    }

    private void editTask(int position, Task updatedTask) {
        tasks.set(position, updatedTask);
        taskAdapter.notifyDataSetChanged();
        saveTasks();
    }

    private void loadTasks() {
        progressBar.setVisibility(View.VISIBLE);
        new Thread(() -> {
            try {
                File file = new File(getExternalFilesDir(null), "tasks/project.txt");
                if (!file.exists()) {
                    runOnUiThread(() -> progressBar.setVisibility(View.GONE));
                    return;
                }

                byte[] inputBytes = new byte[(int) file.length()];
                try (FileInputStream fis = new FileInputStream(file)) {
                    fis.read(inputBytes);
                }
                byte[] decryptedData = CryptUtils.decrypt(inputBytes, password);
                String[] lines = new String(decryptedData, "UTF-8").split("\n");
                List<Task> loadedTasks = new ArrayList<>();
                for (String line : lines) {
                    if (line.startsWith("## ") && line.contains("|")) {
                        String[] parts = line.substring(3).split("\\|");
                        if (parts.length == 5) {
                            loadedTasks.add(new Task(parts[0].trim(), parts[1].trim(), parts[2].trim(), parts[3].trim(), parts[4].trim()));
                        }
                    }
                }
                runOnUiThread(() -> {
                    tasks.clear();
                    tasks.addAll(loadedTasks);
                    taskAdapter.notifyDataSetChanged();
                    progressBar.setVisibility(View.GONE);
                });
            } catch (Exception e) {
                runOnUiThread(() -> {
                    showToast(R.string.error_loading_tasks);
                    progressBar.setVisibility(View.GONE);
                });
            }
        }).start();
    }

    private void saveTasks() {
        progressBar.setVisibility(View.VISIBLE);
        new Thread(() -> {
            try {
                StringBuilder data = new StringBuilder();
                for (Task task : tasks) {
                    data.append(String.format("## %s | %s | %s | %s | %s\n",
                            task.getProject(), task.getTask(), task.getPriority(), task.getStatus(), task.getEmoji()));
                }
                byte[] encryptedData = CryptUtils.encrypt(data.toString().getBytes("UTF-8"), password);
                File file = new File(getExternalFilesDir(null), "tasks/project.txt");
                file.getParentFile().mkdirs();
                try (FileOutputStream fos = new FileOutputStream(file)) {
                    fos.write(encryptedData);
                }
                runOnUiThread(() -> {
                    showToast(R.string.tasks_saved);
                    progressBar.setVisibility(View.GONE);
                });
            } catch (Exception e) {
                runOnUiThread(() -> {
                    showToast(R.string.error_saving_tasks);
                    progressBar.setVisibility(View.GONE);
                });
            }
        }).start();
    }

    private void showToast(int resId) {
        runOnUiThread(() -> Toast.makeText(this, resId, Toast.LENGTH_SHORT).show());
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (password != null) {
            Arrays.fill(password, '\0'); // Clear password
            password = null;
        }
    }
}
