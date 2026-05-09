package com.hmdp.ai.dto;

import lombok.Data;

import java.util.List;

/**
 * 获取聊天历史记录的响应
 */
@Data
public class ChatHistoryResponse {

    /**
     * 会话ID
     */
    private String sessionId;

    /**
     * 消息列表
     */
    private List<MessageItem> messages;

    /**
     * 消息总数
     */
    private Integer total;
}