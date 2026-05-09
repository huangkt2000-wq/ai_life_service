package com.hmdp.ai.tool;

import cn.hutool.json.JSONUtil;
import com.hmdp.ai.agent.BlogAgent;
import com.hmdp.ai.dto.BlogDraftDTO;
import com.hmdp.ai.dto.BlogWritingProgressDTO;
import com.hmdp.dto.Result;
import com.hmdp.entity.Shop;
import com.hmdp.service.IShopService;
import com.hmdp.utils.UserHolder;
import io.minio.GetObjectArgs;
import io.minio.MinioClient;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.content.Media;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.util.MimeTypeUtils;

import jakarta.annotation.Resource;
import java.io.InputStream;
import java.net.URI;
import java.util.*;
import java.util.Base64;
import java.util.concurrent.TimeUnit;

/**
 * 笔记发布工具集
 * 支持多模态图片识别（使用 qwen3.5-plus），多Agent协同自动生成标题和文案，一键发布探店笔记
 */
@Slf4j
@Component
public class BlogTools {

    @Resource
    private IShopService shopService;

    /**
     * 多模态 ChatModel（用于图片分析）
     * 使用 qwen3.5-plus 模型
     */
    @Resource
    @Qualifier("multimodalChatModel")
    private ChatModel multimodalChatModel;

    /**
     * 普通 ChatModel（用于文案生成）
     */
    @Resource
    private ChatModel chatModel;

    /**
     * MinIO 客户端（用于下载图片）
     */
    @Resource
    private MinioClient minioClient;

    @Resource
    private BlogAgent blogAgent;

    /**
     * 启动笔记写作流程，创建草稿会话
     * 返回触发指令让前端显示写作板
     *
     * 注意：Agent只负责触发写作板，后续流程（图片分析、文案生成、店铺选择）由前端写作板组件处理
     */
    @Tool(
            name = "blog/startDraft",
            description = "启动笔记写作流程。返回触发指令[BLOG_WRITING_PANEL:draftId=xxx]让前端显示写作板。" +
                          "参数：wordCount为目标字数（50-500）。" +
                          "注意：Agent只负责触发，不调用其他blog工具，写作板会独立处理图片分析和文案生成。"
    )
    public String startDraft(Integer wordCount) {
        log.info("Tool called: blog/startDraft, wordCount={}", wordCount);

        try {
            // 获取当前用户
            Long userId = getCurrentUserId();
            if (userId == null) {
                return "请先登录后再发布笔记";
            }

            // 设置默认字数
            if (wordCount == null || wordCount < 50 || wordCount > 500) {
                wordCount = 150;
            }

            // 创建草稿
            String sessionId = "session_" + userId + "_" + System.currentTimeMillis();
            BlogDraftDTO draft = blogAgent.createDraft(sessionId, wordCount);
            blogAgent.saveDraft(draft);

            // 返回触发指令，让前端显示写作板
            // 写作板会处理后续的图片上传、分析、文案生成、店铺选择等流程
            String trigger = String.format("[BLOG_WRITING_PANEL:draftId=%s]", draft.getDraftId());
            if (wordCount != 150) {
                trigger += String.format("\n[BLOG_WORD_COUNT:%d]", wordCount);
            }

            return trigger + "\n\n右侧写作板已打开，请上传探店照片开始创作！";

        } catch (Exception e) {
            log.error("创建草稿失败", e);
            return "创建草稿失败：" + e.getMessage();
        }
    }

    /**
     * 分析用户上传的探店图片（多模态）
     * 此工具仅供 BlogAgent 内部使用，Agent 不应调用此工具
     */
    @Tool(
            name = "blog/analyzeImage",
            description = "[内部API] 图片分析工具，仅供后端BlogAgent内部调用。Agent请不要调用此工具，前端写作板会通过API自动处理图片分析。"
    )
    public String analyzeImage(String imageUrl, String draftId) {
        log.info("Tool called: blog_analyzeImage, imageUrl={}, draftId={}", imageUrl, draftId);

        try {
            // 检查是否是虚构的URL，直接返回默认结果
            if (imageUrl == null || imageUrl.contains("example.com") || imageUrl.contains("placeholder")) {
                log.warn("检测到虚构或空的图片URL，返回默认分析结果");
                return createDefaultAnalysisResult();
            }

            String analysisPrompt = """
                请分析这张探店图片，识别以下信息并以JSON格式返回：
                {
                  "foodType": "菜品类型（如：港式茶餐厅、火锅、烧烤等）",
                  "dishes": ["识别到的菜品列表"],
                  "environment": "环境风格（如：复古港风、现代简约、温馨等）",
                  "vibe": "氛围描述",
                  "estimatedPrice": "预估人均消费（数字）",
                  "suitableFor": ["适合的场景列表，如约会、聚餐等"],
                  "colors": ["主要色调"],
                  "highlights": ["亮点特色"]
                }
                只返回JSON，不要其他解释。
                """;

            // 从 MinIO 下载图片并转换为 Base64
            byte[] imageData = downloadImageAsBytes(imageUrl);
            if (imageData == null) {
                log.warn("图片下载失败或URL无效，返回默认分析结果");
                return createDefaultAnalysisResult();
            }

            String base64Data = Base64.getEncoder().encodeToString(imageData);

            // 使用 data URI 格式：data:image/jpeg;base64,{base64Data}
            String dataUri = "data:image/jpeg;base64," + base64Data;

            ChatClient chatClient = ChatClient.create(multimodalChatModel);
            Media image = Media.builder()
                    .mimeType(MimeTypeUtils.IMAGE_JPEG)
                    .data(dataUri)
                    .build();

            log.debug("图片转换为Base64成功: size={}bytes", imageData.length);

            String response = chatClient.prompt()
                    .user(u -> u.text(analysisPrompt).media(image))
                    .call()
                    .content();

            log.info("图片分析结果: {}", response);

            // 保存分析结果到草稿
            if (draftId != null) {
                BlogDraftDTO draft = blogAgent.getDraft(draftId);
                if (draft != null) {
                    // 提取JSON
                    if (response != null && response.contains("{")) {
                        int start = response.indexOf("{");
                        int end = response.lastIndexOf("}") + 1;
                        String jsonStr = response.substring(start, end);
                        Map<String, Object> analysis = JSONUtil.toBean(jsonStr, Map.class);
                        draft.addImageAnalysis(analysis);
                        blogAgent.saveDraft(draft);
                    }
                }
            }

            // 返回分析结果
            if (response != null && response.contains("{")) {
                return response;
            } else {
                return createDefaultAnalysisResult();
            }

        } catch (Exception e) {
            log.error("图片分析失败", e);
            return createDefaultAnalysisResult();
        }
    }

    /**
     * 根据图片分析结果生成笔记标题和文案
     * 此工具仅供 BlogAgent 内部使用，Agent 不应调用此工具
     */
    @Tool(
            name = "blog/generateContent",
            description = "[内部API] 文案生成工具，仅供后端BlogAgent内部调用。Agent请不要调用此工具，前端写作板会通过API自动生成文案。"
    )
    public String generateContent(String imageAnalysisJson, Long shopId, String style, String draftId) {
        log.info("Tool called: blog/generateContent, shopId={}, style={}, draftId={}", shopId, style, draftId);

        try {
            // 解析图片分析结果
            Map<String, Object> analysis;
            if (imageAnalysisJson != null && imageAnalysisJson.contains("{")) {
                analysis = JSONUtil.toBean(imageAnalysisJson, Map.class);
            } else {
                analysis = createDefaultAnalysisMap();
            }

            // 获取餐厅信息补充
            String shopInfo = "";
            Shop shop = null;
            if (shopId != null) {
                shop = shopService.getById(shopId);
                if (shop != null) {
                    shopInfo = String.format("餐厅：%s，评分：%s分，人均：%d元，地址：%s",
                            shop.getName(), shop.getScore() / 10.0, shop.getAvgPrice(), shop.getAddress());
                }
            }

            // 获取草稿设置
            Integer wordCount = 150;
            if (draftId != null) {
                BlogDraftDTO draft = blogAgent.getDraft(draftId);
                if (draft != null) {
                    wordCount = draft.getWordCount();
                    if (style == null || style.isEmpty()) {
                        style = draft.getStyle();
                    }
                }
            }

            // 默认风格
            if (style == null || style.isEmpty()) {
                style = "活泼";
            }

            // 构建生成Prompt
            String generatePrompt = buildContentGenerationPrompt(analysis, shopInfo, style, wordCount);

            ChatClient chatClient = ChatClient.create(chatModel);
            String response = chatClient.prompt()
                    .user(generatePrompt)
                    .call()
                    .content();

            log.info("生成文案结果: {}", response);

            // 解析并保存到草稿
            if (draftId != null && response != null && response.contains("{")) {
                int start = response.indexOf("{");
                int end = response.lastIndexOf("}") + 1;
                String jsonStr = response.substring(start, end);
                Map<String, String> content = JSONUtil.toBean(jsonStr, Map.class);

                BlogDraftDTO draft = blogAgent.getDraft(draftId);
                if (draft != null) {
                    draft.setTitle(content.get("title"));
                    draft.setContent(content.get("content"));
                    if (shop != null) {
                        draft.setShopId(shopId);
                        draft.setShopName(shop.getName());
                    }
                    draft.updatePhase(BlogDraftDTO.DraftPhase.CONTENT_GENERATED, "ContentWriter");
                    blogAgent.saveDraft(draft);
                }
            }

            // 格式化返回结果
            return formatGeneratedContent(response);

        } catch (Exception e) {
            log.error("文案生成失败", e);
            return "{\"title\":\"探店分享\",\"content\":\"今天去了这家店，味道还不错～\"}";
        }
    }

    /**
     * 搜索关联餐厅
     * 此工具仅供 BlogAgent 内部使用，Agent 不应调用此工具
     */
    @Tool(
            name = "blog/searchShop",
            description = "[内部API] 店铺搜索工具，仅供后端BlogAgent内部调用。Agent请不要调用此工具，前端写作板会通过API搜索店铺供用户选择。"
    )
    public String searchShop(String keyword, String draftId) {
        log.info("Tool called: blog/searchShop, keyword={}, draftId={}", keyword, draftId);

        Result result = shopService.queryShopByName(keyword, 1);

        if (!result.isSuccess()) {
            return "未找到匹配的餐厅，请尝试其他关键词";
        }

        List<Shop> shops = (List<Shop>) result.getData();
        return formatShopSearchResult(shops);
    }

    /**
     * 关联店铺到草稿
     * 此工具仅供 BlogAgent 内部使用，Agent 不应调用此工具
     */
    @Tool(
            name = "blog/associateShop",
            description = "[内部API] 店铺关联工具，仅供后端BlogAgent内部调用。Agent请不要调用此工具，用户会在前端写作板选择店铺。"
    )
    public String associateShop(Long shopId, String draftId) {
        log.info("Tool called: blog/associateShop, shopId={}, draftId={}", shopId, draftId);

        try {
            BlogDraftDTO draft = blogAgent.associateShop(draftId, shopId);
            Shop shop = shopService.getById(shopId);

            if (shop != null) {
                return String.format("已关联餐厅：**%s**（评分%.1f分，人均%d元）\n\n草稿已更新，请确认内容后发布。",
                        shop.getName(), shop.getScore() / 10.0, shop.getAvgPrice());
            }

            return "餐厅已关联到草稿";

        } catch (Exception e) {
            log.error("关联餐厅失败", e);
            return "关联餐厅失败：" + e.getMessage();
        }
    }

    /**
     * 预览草稿内容
     * 此工具仅供 BlogAgent 内部使用，Agent 不应调用此工具
     */
    @Tool(
            name = "blog/previewDraft",
            description = "[内部API] 草稿预览工具，仅供后端BlogAgent内部调用。Agent请不要调用此工具，前端写作板会显示预览内容。"
    )
    public String previewDraft(String draftId) {
        log.info("Tool called: blog/previewDraft, draftId={}", draftId);

        BlogDraftDTO draft = blogAgent.getDraft(draftId);
        if (draft == null) {
            return "草稿不存在";
        }

        StringBuilder sb = new StringBuilder();
        sb.append("**草稿预览**\n\n");
        sb.append("📝 标题：").append(draft.getTitle()).append("\n\n");
        sb.append("📄 内容：\n").append(draft.getContent()).append("\n\n");
        sb.append("📸 图片：").append(draft.getImages().size()).append("张\n\n");

        if (draft.getShopName() != null) {
            sb.append("🏪 关联餐厅：").append(draft.getShopName()).append("\n\n");
        }

        sb.append("---\n\n");
        sb.append("请确认内容无误后，回复「发布」完成发布。");

        draft.updatePhase(BlogDraftDTO.DraftPhase.READY_FOR_PUBLISH, "UserReview");
        blogAgent.saveDraft(draft);

        return sb.toString();
    }

    /**
     * 更新草稿内容
     * 此工具仅供 BlogAgent 内部使用，Agent 不应调用此工具
     */
    @Tool(
            name = "blog/updateDraft",
            description = "[内部API] 草稿更新工具，仅供后端BlogAgent内部调用。Agent请不要调用此工具，用户会在前端写作板直接编辑内容。"
    )
    public String updateDraft(String draftId, String title, String content) {
        log.info("Tool called: blog/updateDraft, draftId={}, title={}", draftId, title);

        try {
            BlogDraftDTO draft = blogAgent.updateDraft(draftId, title, content);
            return "草稿已更新\n\n" + previewDraft(draftId);
        } catch (Exception e) {
            log.error("更新草稿失败", e);
            return "更新草稿失败：" + e.getMessage();
        }
    }

    /**
     * 一键发布笔记
     * 此工具仅供 BlogAgent 内部使用，Agent 不应调用此工具
     */
    @Tool(
            name = "blog/publish",
            description = "[内部API] 笔记发布工具，仅供后端BlogAgent内部调用。Agent请不要调用此工具，用户会在前端写作板点击发布按钮。"
    )
    public String publish(String draftId) {
        log.info("Tool called: blog/publish, draftId={}", draftId);

        try {
            BlogDraftDTO draft = blogAgent.getDraft(draftId);
            if (draft == null) {
                return "草稿不存在";
            }

            if (!draft.canPublish()) {
                return "草稿内容不完整，无法发布。请确保已上传图片、填写标题和内容、关联餐厅。";
            }

            // 通过 BlogAgent 发布
            draft.updatePhase(BlogDraftDTO.DraftPhase.READY_FOR_PUBLISH, "BlogPublisher");
            blogAgent.saveDraft(draft);

            return formatPublishReady(draft);

        } catch (Exception e) {
            log.error("发布失败", e);
            return "发布失败：" + e.getMessage();
        }
    }

    // ==================== 辅助方法 ====================

    private Long getCurrentUserId() {
        try {
            var user = UserHolder.getUser();
            return user != null ? user.getId() : null;
        } catch (Exception e) {
            log.warn("获取用户ID失败", e);
            return null;
        }
    }

    /**
     * 从 MinIO 下载图片并返回字节数据
     * 支持 MinIO URL 格式: http://host:port/bucket/objectPath
     * 也支持纯路径格式: bucket/objectPath 或 /bucket/objectPath
     */
    private byte[] downloadImageAsBytes(String imageUrl) {
        try {
            if (imageUrl == null || imageUrl.isEmpty()) {
                throw new RuntimeException("图片URL为空");
            }

            // 检查是否是假的URL（Agent虚构的示例URL）
            if (imageUrl.contains("example.com") || imageUrl.contains("placeholder") || imageUrl.contains("sample")) {
                log.warn("检测到虚构的图片URL: {}, 返回默认分析结果", imageUrl);
                return null;  // 返回null表示无法下载，让调用方使用默认结果
            }

            String bucket;
            String objectName;

            // 判断是完整URL还是纯路径
            if (imageUrl.startsWith("http://") || imageUrl.startsWith("https://")) {
                // 完整URL格式: http://host:port/bucket/objectPath
                URI uri = URI.create(imageUrl);
                String path = uri.getPath();

                // 移除开头的 /
                if (path.startsWith("/")) {
                    path = path.substring(1);
                }

                // 解析 bucket 和 objectName
                String[] parts = path.split("/", 2);
                if (parts.length < 2) {
                    log.warn("无法解析完整URL的路径: {}", imageUrl);
                    return null;
                }

                bucket = parts[0];
                objectName = parts[1];
            } else {
                // 纯路径格式: bucket/objectPath 或 /bucket/objectPath
                String path = imageUrl;
                if (path.startsWith("/")) {
                    path = path.substring(1);
                }

                String[] parts = path.split("/", 2);
                if (parts.length < 2) {
                    log.warn("无法解析路径格式: {}", imageUrl);
                    return null;
                }

                bucket = parts[0];
                objectName = parts[1];
            }

            log.debug("从MinIO下载图片: bucket={}, object={}", bucket, objectName);

            InputStream inputStream = minioClient.getObject(
                    GetObjectArgs.builder()
                            .bucket(bucket)
                            .object(objectName)
                            .build()
            );

            byte[] imageData = inputStream.readAllBytes();
            inputStream.close();

            return imageData;

        } catch (Exception e) {
            log.error("下载图片失败: {}", imageUrl, e);
            throw new RuntimeException("下载图片失败: " + e.getMessage());
        }
    }

    private String createDefaultAnalysisResult() {
        return JSONUtil.toJsonStr(Map.of(
                "foodType", "美食餐厅",
                "dishes", List.of(),
                "environment", "温馨舒适",
                "vibe", "轻松惬意",
                "estimatedPrice", 50,
                "suitableFor", List.of("聚餐", "探店"),
                "colors", List.of("暖色调"),
                "highlights", List.of("美食", "环境")
        ));
    }

    private Map<String, Object> createDefaultAnalysisMap() {
        return Map.of(
                "foodType", "美食餐厅",
                "dishes", List.of(),
                "environment", "温馨舒适",
                "vibe", "轻松惬意",
                "estimatedPrice", 50,
                "suitableFor", List.of("聚餐", "探店"),
                "highlights", List.of("美食", "环境")
        );
    }

    private String buildContentGenerationPrompt(Map<String, Object> analysis, String shopInfo, String style, Integer wordCount) {
        StringBuilder prompt = new StringBuilder();

        prompt.append("请根据以下图片分析结果，生成一篇探店笔记。\n\n");

        prompt.append("图片分析结果：\n");
        prompt.append("- 菜品类型：").append(analysis.get("foodType")).append("\n");
        prompt.append("- 菜品：").append(analysis.get("dishes")).append("\n");
        prompt.append("- 环境：").append(analysis.get("environment")).append("\n");
        prompt.append("- 氛围：").append(analysis.get("vibe")).append("\n");
        prompt.append("- 预估人均：").append(analysis.get("estimatedPrice")).append("元\n");
        prompt.append("- 亮点：").append(analysis.get("highlights")).append("\n");

        if (!shopInfo.isEmpty()) {
            prompt.append("\n餐厅信息：").append(shopInfo).append("\n");
        }

        prompt.append("\n文案风格：").append(style).append("\n");
        prompt.append("字数要求：约").append(wordCount).append("字\n");

        prompt.append("""

            请生成：
            1. 一个吸引人的标题（不超过30字）
            2. 一段生动的探店文案

            要求：
            - 使用emoji增加活泼感
            - 描述菜品、环境、体验
            - 有情感共鸣

            以JSON格式返回：
            {"title": "标题", "content": "文案"}
            """);

        return prompt.toString();
    }

    private String formatGeneratedContent(String response) {
        if (response != null && response.contains("{") && response.contains("title")) {
            return response;
        }
        return JSONUtil.toJsonStr(Map.of(
                "title", "探店分享",
                "content", response != null ? response : "今天去了这家店，体验不错～"
        ));
    }

    private String formatShopSearchResult(List<Shop> shops) {
        if (shops == null || shops.isEmpty()) {
            return "未找到匹配的餐厅，请尝试其他关键词";
        }

        StringBuilder sb = new StringBuilder("找到以下餐厅，请选择关联餐厅：\n\n");
        for (int i = 0; i < Math.min(shops.size(), 5); i++) {
            Shop shop = shops.get(i);
            sb.append(i + 1).append(". 【").append(shop.getName()).append("】\n");
            sb.append("   评分：").append(shop.getScore() / 10.0).append("分 ⭐\n");
            sb.append("   人均：").append(shop.getAvgPrice()).append("元\n");
            sb.append("   ID：").append(shop.getId()).append("\n\n");
        }
        sb.append("回复「选择第X个」关联餐厅。");
        return sb.toString();
    }

    private String formatPublishReady(BlogDraftDTO draft) {
        StringBuilder sb = new StringBuilder();
        sb.append("笔记准备发布！\n\n");
        sb.append("📝 标题：").append(draft.getTitle()).append("\n");
        sb.append("📄 内容：").append(draft.getContent()).append("\n");
        sb.append("🏪 餐厅：").append(draft.getShopName()).append("\n");
        sb.append("📸 图片：").append(draft.getImages().size()).append("张\n\n");
        sb.append("[BLOG_READY_TO_PUBLISH:draftId=").append(draft.getDraftId()).append("]\n");
        sb.append("确认后点击写作板的发布按钮完成发布。");
        return sb.toString();
    }
}