package com.hmdp.ai.dto;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 笔记草稿 DTO
 * 用于多Agent协同写作过程中保存草稿状态
 */
@Data
public class BlogDraftDTO {

    /**
     * 草稿ID
     */
    private String draftId;

    /**
     * 会话ID
     */
    private String sessionId;

    /**
     * 用户ID
     */
    private Long userId;

    /**
     * 关联餐厅ID
     */
    private Long shopId;

    /**
     * 餐厅名称（关联后填充）
     */
    private String shopName;

    /**
     * 标题
     */
    private String title;

    /**
     * 文案内容
     */
    private String content;

    /**
     * 图片URL列表
     */
    private List<String> images = new ArrayList<>();

    /**
     * 图片分析结果（每张图片的分析）
     */
    private List<Map<String, Object>> imageAnalyses = new ArrayList<>();

    /**
     * 文案风格
     */
    private String style = "活泼";

    /**
     * 目标字数
     */
    private Integer wordCount = 150;

    /**
     * 当前阶段
     * INIT -> IMAGE_ANALYZING -> CONTENT_GENERATING -> SHOP_MATCHING -> READY_FOR_PUBLISH -> PUBLISHED
     */
    private DraftPhase phase = DraftPhase.INIT;

    /**
     * 当前执行的Agent
     */
    private String currentAgent;

    /**
     * 创建时间
     */
    private LocalDateTime createTime;

    /**
     * 更新时间
     */
    private LocalDateTime updateTime;

    /**
     * 草稿阶段枚举
     */
    public enum DraftPhase {
        INIT,                   // 初始化，等待图片上传
        IMAGE_ANALYZING,        // 图片分析Agent执行中
        IMAGE_ANALYZED,         // 图片分析完成
        CONTENT_GENERATING,     // 文案生成Agent执行中
        CONTENT_GENERATED,      // 文案生成完成
        SHOP_MATCHING,          // 店铺匹配Agent执行中
        SHOP_ASSOCIATED,        // 店铺已关联
        READY_FOR_PUBLISH,      // 准备发布，等待用户确认
        PUBLISHED               // 已发布
    }

    /**
     * 创建新草稿
     */
    public static BlogDraftDTO createNew(String sessionId, Long userId) {
        BlogDraftDTO draft = new BlogDraftDTO();
        draft.setDraftId("draft_" + System.currentTimeMillis() + "_" + userId);
        draft.setSessionId(sessionId);
        draft.setUserId(userId);
        draft.setCreateTime(LocalDateTime.now());
        draft.setUpdateTime(LocalDateTime.now());
        return draft;
    }

    /**
     * 添加图片
     */
    public void addImage(String imageUrl) {
        this.images.add(imageUrl);
        this.updateTime = LocalDateTime.now();
    }

    /**
     * 添加图片分析结果
     */
    public void addImageAnalysis(Map<String, Object> analysis) {
        this.imageAnalyses.add(analysis);
        this.updateTime = LocalDateTime.now();
    }

    /**
     * 更新阶段
     */
    public void updatePhase(DraftPhase newPhase, String agentName) {
        this.phase = newPhase;
        this.currentAgent = agentName;
        this.updateTime = LocalDateTime.now();
    }

    /**
     * 是否可以发布
     */
    public boolean canPublish() {
        return phase == DraftPhase.READY_FOR_PUBLISH
            && shopId != null
            && userId != null
            && title != null && !title.isEmpty()
            && content != null && !content.isEmpty()
            && images != null && !images.isEmpty();
    }
}
