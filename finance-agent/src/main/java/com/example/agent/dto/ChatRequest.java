package com.example.agent.dto;

import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 聊天请求DTO，前端发送用户消息时的请求体。
 */
@Data
@NoArgsConstructor
public class ChatRequest {

    /** 用户发送的聊天消息内容 */
    private String message;

    /** 用户唯一标识 */
    private String userId;
}
