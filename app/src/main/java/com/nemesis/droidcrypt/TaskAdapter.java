package com.nemesis.droidcrypt;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;
import com.google.android.material.button.MaterialButton;
import java.util.ArrayList;
import java.util.List;

public class TaskAdapter extends RecyclerView.Adapter<TaskAdapter.TaskViewHolder> {
    private List<Task> tasks;
    private OnDeleteListener onDelete;
    private OnEditListener onEdit;

    public interface OnDeleteListener {
        void onDelete(int position);
    }

    public interface OnEditListener {
        void onEdit(int position, Task updatedTask);
    }

    public TaskAdapter(List<Task> tasks, OnDeleteListener onDelete, OnEditListener onEdit) {
        if (tasks == null) throw new IllegalArgumentException("Tasks list cannot be null");
        if (onDelete == null || onEdit == null) throw new IllegalArgumentException("Listeners cannot be null");
        this.tasks = new ArrayList<>();
        for (Task task : tasks) {
            this.tasks.add(new Task(task.getProject(), task.getTask(), task.getPriority(), task.getStatus(), task.getEmoji()));
        }
        this.onDelete = onDelete;
        this.onEdit = onEdit;
    }

    @NonNull
    @Override
    public TaskViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_task, parent, false);
        return new TaskViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull TaskViewHolder holder, int position) {
        Task task = tasks.get(position);
        // Mask project and task to show first 5 chars
        String project = task.getProject();
        String taskText = task.getTask();
        holder.projectText.setText(project.length() > 5 ? project.substring(0, 5) + "..." : project);
        holder.taskText.setText(taskText.length() > 5 ? taskText.substring(0, 5) + "..." : taskText);
        holder.priorityText.setText(task.getPriority());
        holder.statusText.setText(task.getStatus());
        holder.emojiText.setText(task.getEmoji());

        holder.deleteButton.setOnClickListener(v -> onDelete.onDelete(position));
        holder.editButton.setOnClickListener(v -> {
            Task updatedTask = new Task(task.getProject(), task.getTask() + " (Edited)", task.getPriority(), task.getStatus(), task.getEmoji());
            onEdit.onEdit(position, updatedTask);
        });
    }

    @Override
    public int getItemCount() {
        return tasks.size();
    }

    static class TaskViewHolder extends RecyclerView.ViewHolder {
        TextView projectText, taskText, priorityText, statusText, emojiText;
        MaterialButton editButton, deleteButton;

        TaskViewHolder(@NonNull View itemView) {
            super(itemView);
            projectText = itemView.findViewById(R.id.project_text);
            taskText = itemView.findViewById(R.id.task_text);
            priorityText = itemView.findViewById(R.id.priority_text);
            statusText = itemView.findViewById(R.id.status_text);
            emojiText = itemView.findViewById(R.id.emoji_text);
            editButton = itemView.findViewById(R.id.edit_button);
            deleteButton = itemView.findViewById(R.id.delete_button);
        }
    }
}
