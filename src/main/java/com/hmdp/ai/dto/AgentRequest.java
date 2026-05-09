package com.hmdp.ai.dto;

import lombok.Data;

import java.util.List;

/**
 * Agent 请求 DTO
 */
@Data
public class AgentRequest {

    /**
     * 会话ID（可选，首次对话不传）
     */
    private String sessionId;

    /**
     * 用户消息
     */
    private String message;

    /**
     * 上下文类型（可选）
     * shop / voucher / blog
     */
    private String contextType;

    /**
     * 上下文关联ID（可选）
     */
    private Long contextId;

    /**
     * 图片URL列表（多模态请求）
     */
    private List<String> images;

    /**
     * 草稿ID（笔记写作流程）
     */
    private String draftId;

    /**
     * 目标字数（笔记写作）
     */
    private Integer wordCount;

    /**
     * 文案风格（笔记写作）
     */
    private String style;

    /**
     * 笔记标题（草稿编辑）
     */
    private String title;

    /**
     * 笔记正文（草稿编辑）
     */
    private String content;
}
