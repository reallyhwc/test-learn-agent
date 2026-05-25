package com.example.agent.dto;

import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;

/**
 * 聊天响应DTO，AI Agent返回给前端的回复消息。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ChatResponse {

    /** AI回复的文本内容 */
    private String reply;
}
