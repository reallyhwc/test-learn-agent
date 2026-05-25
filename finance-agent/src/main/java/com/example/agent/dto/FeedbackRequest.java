package com.example.agent.dto;

import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 用户反馈请求DTO，用于提交对AI回复的评价。
 */
@Data
@NoArgsConstructor
public class FeedbackRequest {

    /** 用户唯一标识 */
    private String userId;

    /** 被评价的消息ID */
    private String messageId;

    /** 评价等级，如"good"、"bad" */
    private String rating;
}
