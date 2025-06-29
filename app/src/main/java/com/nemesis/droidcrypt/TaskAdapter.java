package com.nemesis.droidcrypt;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;
import com.google.android.material.button.MaterialButton;
import java.util.List;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

public class TaskAdapter extends RecyclerView.Adapter<TaskAdapter.TaskViewHolder> {
    private List<Task> tasks;
    private Consumer<Integer> onDelete;
    private BiConsumer<Integer, Task> onEdit;

    public TaskAdapter(List<Task> tasks, Consumer<Integer> onDelete, BiConsumer<Integer, Task> onEdit) {
        this.tasks = tasks;
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
        holder.projectText.setText(task.getProject());
        holder.taskText.setText(task.getTask());
        holder.priorityText.setText(task.getPriority());
        holder.statusText.setText(task.getStatus());
        holder.emojiText.setText(task.getEmoji());

        holder.deleteButton.setOnClickListener(v -> onDelete.accept(position));
        holder.editButton.setOnClickListener(v -> {
            // Для редактирования можно открыть диалог или новое Activity
            Task updatedTask = new Task(task.getProject(), task.getTask() + " (Edited)", task.getPriority(), task.getStatus(), task.getEmoji());
            onEdit.accept(position, updatedTask);
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
