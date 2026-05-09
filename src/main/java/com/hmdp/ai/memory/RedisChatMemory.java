package com.hmdp.ai.memory;

import cn.hutool.json.JSONUtil;
import com.hmdp.utils.RedisConstants;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * Redis 实现的对话记忆
 * 使用 Redis List 存储对话历史，支持持久化和多轮对话
 * 支持保存图片信息
 */
@Slf4j
@Component("redisChatMemory")
public class RedisChatMemory implements ChatMemory {

    private final StringRedisTemplate redisTemplate;

    public RedisChatMemory(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    @Override
    public void add(String sessionId, Message message) {
        add(sessionId, message, null);
    }

    /**
     * 添加消息到记忆，支持携带图片
     */
    public void add(String sessionId, Message message, List<String> images) {
        String key = RedisConstants.AI_MEMORY_KEY + sessionId;

        // 序列化消息（包含图片）
        MessageRecord record = new MessageRecord(
            message.getMessageType().getValue(),
            message.getText(),
            images
        );

        // 添加到 Redis List (右侧添加)
        redisTemplate.opsForList().rightPush(key, JSONUtil.toJsonStr(record));

        // 设置过期时间（每次添加时刷新）
        redisTemplate.expire(key, RedisConstants.AI_MEMORY_TTL, TimeUnit.SECONDS);

        log.debug("Added message to memory: sessionId={}, type={}, images={}",
                sessionId, message.getMessageType().getValue(), images != null ? images.size() : 0);
    }

    @Override
    public List<Message> get(String sessionId, int lastN) {
        String key = RedisConstants.AI_MEMORY_KEY + sessionId;

        // 获取最近 N 条消息（使用负数索引从右边获取）
        // Redis List: range(key, start, end)
        // 负数索引: -1 表示最后一个元素，-n 表示倒数第 n 个
        long size = redisTemplate.opsForList().size(key);
        if (size == 0) {
            return new ArrayList<>();
        }

        // 计算实际要获取的起始位置
        long start = Math.max(0, size - lastN);
        List<String> messages = redisTemplate.opsForList().range(key, start, size - 1);

        if (messages == null || messages.isEmpty()) {
            return new ArrayList<>();
        }

        return convertToMessages(messages);
    }

    @Override
    public List<Message> getAll(String sessionId) {
        String key = RedisConstants.AI_MEMORY_KEY + sessionId;

        // 获取所有消息 (range 0 到 -1)
        List<String> messages = redisTemplate.opsForList().range(key, 0, -1);

        if (messages == null || messages.isEmpty()) {
            return new ArrayList<>();
        }

        return convertToMessages(messages);
    }

    /**
     * 获取所有消息记录（包含图片信息）
     * 用于历史记录API返回给前端
     */
    public List<MessageRecord> getAllRecords(String sessionId) {
        String key = RedisConstants.AI_MEMORY_KEY + sessionId;

        List<String> messages = redisTemplate.opsForList().range(key, 0, -1);

        if (messages == null || messages.isEmpty()) {
            return new ArrayList<>();
        }

        List<MessageRecord> result = new ArrayList<>();
        for (String json : messages) {
            MessageRecord record = JSONUtil.toBean(json, MessageRecord.class);
            result.add(record);
        }
        return result;
    }

    @Override
    public long size(String sessionId) {
        String key = RedisConstants.AI_MEMORY_KEY + sessionId;
        Long size = redisTemplate.opsForList().size(key);
        return size != null ? size : 0;
    }

    @Override
    public void clear(String sessionId) {
        String key = RedisConstants.AI_MEMORY_KEY + sessionId;
        redisTemplate.delete(key);
        log.debug("Cleared memory for sessionId: {}", sessionId);
    }

    @Override
    public String createSession() {
        return UUID.randomUUID().toString().replace("-", "");
    }

    /**
     * 将 JSON 字符串列表转换为 Message 列表
     */
    private List<Message> convertToMessages(List<String> jsonMessages) {
        List<Message> result = new ArrayList<>();
        for (String json : jsonMessages) {
            MessageRecord record = JSONUtil.toBean(json, MessageRecord.class);
            Message message = convertToMessage(record);
            if (message != null) {
                result.add(message);
            }
        }
        return result;
    }

    /**
     * 将 MessageRecord 转换为 Spring AI Message
     */
    private Message convertToMessage(MessageRecord record) {
        switch (record.getType()) {
            case "user":
                return new UserMessage(record.getContent());
            case "assistant":
                return new AssistantMessage(record.getContent());
            default:
                log.warn("Unknown message type: {}", record.getType());
                return null;
        }
    }

    /**
     * 消息记录（用于序列化）
     * 支持图片信息
     */
    public static class MessageRecord {
        private String type;
        private String content;
        private List<String> images;  // 图片URL列表

        public MessageRecord(String type, String content) {
            this.type = type;
            this.content = content;
            this.images = null;
        }

        public MessageRecord(String type, String content, List<String> images) {
            this.type = type;
            this.content = content;
            this.images = images;
        }

        public String getType() {
            return type;
        }

        public void setType(String type) {
            this.type = type;
        }

        public String getContent() {
            return content;
        }

        public void setContent(String content) {
            this.content = content;
        }

        public List<String> getImages() {
            return images;
        }

        public void setImages(List<String> images) {
            this.images = images;
        }
    }
}