package com.example.agent.dto;

public class ChatRequest {
    private String message;
    private String userId;

    public String getMessage() { return message; }
    public void setMessage(String message) { this.message = message; }
    public String getUserId() { return userId; }
    public void setUserId(String userId) { this.userId = userId; }
}
