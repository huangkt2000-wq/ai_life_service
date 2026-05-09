package com.hmdp.ai.dto;

import lombok.Data;

/**
 * 写作板进度响应 DTO
 * 用于实时推送写作板的内容更新
 */
@Data
public class BlogWritingProgressDTO {

    /**
     * 草稿ID
     */
    private String draftId;

    /**
     * 当前阶段
     */
    private String phase;

    /**
     * 当前执行的Agent名称
     */
    private String agentName;

    /**
     * 进度消息（如"正在分析图片..."）
     */
    private String progressMessage;

    /**
     * 图片分析结果（Phase: IMAGE_ANALYZED）
     */
    private Object imageAnalysis;

    /**
     * 生成的标题（实时更新）
     */
    private String title;

    /**
     * 生成的文案（实时更新，打字效果）
     */
    private String content;

    /**
     * 关联的餐厅列表（Phase: SHOP_MATCHING）
     */
    private Object shopOptions;

    /**
     * 选中的餐厅信息
     */
    private Object selectedShop;

    /**
     * 是否完成此阶段
     */
    private boolean phaseComplete;

    /**
     * 是否可以发布
     */
    private boolean readyToPublish;

    /**
     * 笔记发布结果（Phase: PUBLISHED）
     */
    private Long blogId;

    /**
     * 错误信息
     */
    private String error;

    /**
     * 创建进度开始响应
     */
    public static BlogWritingProgressDTO start(String draftId, String agentName, String message) {
        BlogWritingProgressDTO dto = new BlogWritingProgressDTO();
        dto.setDraftId(draftId);
        dto.setAgentName(agentName);
        dto.setProgressMessage(message);
        dto.setPhaseComplete(false);
        return dto;
    }

    /**
     * 创建图片分析结果响应
     */
    public static BlogWritingProgressDTO imageAnalyzed(String draftId, Object analysis) {
        BlogWritingProgressDTO dto = new BlogWritingProgressDTO();
        dto.setDraftId(draftId);
        dto.setPhase("IMAGE_ANALYZED");
        dto.setAgentName("ImageAnalyzer");
        dto.setImageAnalysis(analysis);
        dto.setPhaseComplete(true);
        return dto;
    }

    /**
     * 创建内容生成进度响应（打字效果）
     */
    public static BlogWritingProgressDTO contentProgress(String draftId, String title, String content, boolean complete) {
        BlogWritingProgressDTO dto = new BlogWritingProgressDTO();
        dto.setDraftId(draftId);
        dto.setPhase("CONTENT_GENERATING");
        dto.setAgentName("ContentWriter");
        dto.setTitle(title);
        dto.setContent(content);
        dto.setPhaseComplete(complete);
        return dto;
    }

    /**
     * 创建店铺选项响应
     */
    public static BlogWritingProgressDTO shopOptions(String draftId, Object shops) {
        BlogWritingProgressDTO dto = new BlogWritingProgressDTO();
        dto.setDraftId(draftId);
        dto.setPhase("SHOP_MATCHING");
        dto.setAgentName("ShopMatcher");
        dto.setShopOptions(shops);
        dto.setPhaseComplete(true);
        return dto;
    }

    /**
     * 创建准备发布响应
     */
    public static BlogWritingProgressDTO readyToPublish(String draftId, String title, String content, Object shop) {
        BlogWritingProgressDTO dto = new BlogWritingProgressDTO();
        dto.setDraftId(draftId);
        dto.setPhase("READY_FOR_PUBLISH");
        dto.setTitle(title);
        dto.setContent(content);
        dto.setSelectedShop(shop);
        dto.setReadyToPublish(true);
        dto.setPhaseComplete(true);
        return dto;
    }

    /**
     * 创建发布成功响应
     */
    public static BlogWritingProgressDTO published(String draftId, Long blogId) {
        BlogWritingProgressDTO dto = new BlogWritingProgressDTO();
        dto.setDraftId(draftId);
        dto.setPhase("PUBLISHED");
        dto.setAgentName("BlogPublisher");
        dto.setBlogId(blogId);
        dto.setPhaseComplete(true);
        return dto;
    }
}