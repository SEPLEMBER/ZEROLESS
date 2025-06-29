package com.nemesis.droidcrypt;

public class Task {
    private String project;
    private String task;
    private String priority;
    private String status;
    private String emoji;

    public Task(String project, String task, String priority, String status, String emoji) {
        this.project = project;
        this.task = task;
        this.priority = priority;
        this.status = status;
        this.emoji = emoji;
    }

    public String getProject() {
        return project;
    }

    public String getTask() {
        return task;
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
        this.task = task;
    }

    public void setPriority(String priority) {
        this.priority = priority;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public void setEmoji(String emoji) {
        this.emoji = emoji;
    }
}
