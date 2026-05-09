package com.hmdp.ai.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 聊天消息项
 * 用于历史记录中的单条消息
 * 支持图片信息
 */
@Data
@AllArgsConstructor
@NoArgsConstructor
public class MessageItem {

    /**
     * 消息角色：user / assistant
     */
    private String role;

    /**
     * 消息内容
     */
    private String content;

    /**
     * 图片URL列表（用户上传的图片）
     */
    private List<String> images;
}