package com.nemesis.droidcrypt;

import java.util.Arrays;

public class Task {
    private char[] project;
    private char[] task;
    private String priority;
    private String status;
    private String emoji;

    public Task(String project, String task, String priority, String status, String emoji) {
        if (project == null || project.trim().isEmpty()) {
            throw new IllegalArgumentException("Project cannot be null or empty");
        }
        if (task == null || task.trim().isEmpty()) {
            throw new IllegalArgumentException("Task cannot be null or empty");
        }
        this.project = project.trim().toCharArray();
        this.task = task.trim().toCharArray();
        this.priority = priority != null ? priority.trim() : "Medium";
        this.status = status != null ? status.trim() : "To Do";
        this.emoji = emoji != null ? emoji.trim() : "😊";
    }

    public String getProject() {
        return new String(project);
    }

    public String getTask() {
        return new String(task);
    }

    public String getPriority() {
        return priority;
    }

    public String getStatus() {
        return status;
    }

    public String getEmoji() {
        return emoji;
    }

    public void setTask(String task) {
        if (task == null || task.trim().isEmpty()) {
            throw new IllegalArgumentException("Task cannot be null or empty");
        }
        Arrays.fill(this.task, '\0');
        this.task = task.trim().toCharArray();
    }

    public void setPriority(String priority) {
        this.priority = priority != null ? priority.trim() : "Medium";
    }

    public void setStatus(String status) {
        this.status = status != null ? status.trim() : "To Do";
    }

    public void setEmoji(String emoji) {
        this.emoji = emoji != null ? emoji.trim() : "😊";
    }

    public void clear() {
        Arrays.fill(project, '\0');
        Arrays.fill(task, '\0');
    }
}
