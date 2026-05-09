package com.hmdp.ai.memory;

import org.springframework.ai.chat.messages.Message;

import java.util.List;

/**
 * 对话记忆接口
 */
public interface ChatMemory {

    /**
     * 添加消息到记忆
     * @param sessionId 会话ID
     * @param message 消息
     */
    void add(String sessionId, Message message);

    /**
     * 获取最近的对话历史
     * @param sessionId 会话ID
     * @param lastN 最近N条
     * @return 消息列表
     */
    List<Message> get(String sessionId, int lastN);

    /**
     * 获取所有对话历史
     * @param sessionId 会话ID
     * @return 消息列表
     */
    List<Message> getAll(String sessionId);

    /**
     * 获取对话历史的数量
     * @param sessionId 会话ID
     * @return 消息数量
     */
    long size(String sessionId);

    /**
     * 清除会话记忆
     * @param sessionId 会话ID
     */
    void clear(String sessionId);

    /**
     * 创建新会话
     * @return 会话ID
     */
    String createSession();
}