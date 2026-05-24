package com.example.agent.dto;

public class FeedbackRequest {
    private String userId;
    private String messageId;
    private String rating;

    public String getUserId() { return userId; }
    public void setUserId(String userId) { this.userId = userId; }
    public String getMessageId() { return messageId; }
    public void setMessageId(String messageId) { this.messageId = messageId; }
    public String getRating() { return rating; }
    public void setRating(String rating) { this.rating = rating; }
}
