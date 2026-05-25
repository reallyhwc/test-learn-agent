package com.example.agent.memory;

import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;

/**
 * 聊天历史条目，表示对话中的一条消息记录（用于上下文记忆）。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ChatHistoryItem {

    /** 消息角色：user(用户) / assistant(AI助手) / system(系统) */
    private String role;

    /** 消息文本内容 */
    private String text;
}
