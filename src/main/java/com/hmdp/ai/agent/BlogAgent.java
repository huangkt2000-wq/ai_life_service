package com.hmdp.ai.agent;

import cn.hutool.json.JSONUtil;
import com.hmdp.ai.dto.BlogDraftDTO;
import com.hmdp.ai.dto.BlogWritingProgressDTO;
import com.hmdp.dto.Result;
import com.hmdp.entity.Blog;
import com.hmdp.entity.Shop;
import com.hmdp.service.IBlogService;
import com.hmdp.service.IShopService;
import com.hmdp.utils.RedisConstants;
import com.hmdp.utils.UserHolder;
import io.minio.GetObjectArgs;
import io.minio.MinioClient;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.content.Media;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.util.MimeTypeUtils;
import reactor.core.publisher.Flux;

import jakarta.annotation.Resource;
import java.io.InputStream;
import java.net.URI;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.*;
import java.util.Base64;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * BlogAgent - 多Agent协同笔记写作处理器
 *
 * 协调以下Agent执行笔记写作任务：
 * - ImageAnalyzer: 多模态图片分析（使用 qwen3.5-plus）
 * - ContentWriter: 文案生成
 * - BlogPublisher: 发布执行
 */
@Slf4j
@Component
public class BlogAgent {

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
    private IBlogService blogService;

    @Resource
    private IShopService shopService;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    /**
     * 并行执行线程池
     */
    private final ExecutorService agentExecutor = Executors.newFixedThreadPool(2);

    // ==================== 草稿管理 ====================

    /**
     * 创建新草稿
     */
    public BlogDraftDTO createDraft(String sessionId, Integer wordCount) {
        Long userId = getCurrentUserId();
        if (userId == null) {
            throw new RuntimeException("用户未登录");
        }

        BlogDraftDTO draft = BlogDraftDTO.createNew(sessionId, userId);
        if (wordCount != null && wordCount >= 50 && wordCount <= 500) {
            draft.setWordCount(wordCount);
        }

        saveDraft(draft);
        log.info("创建草稿: draftId={}, userId={}, wordCount={}", draft.getDraftId(), userId, wordCount);
        return draft;
    }

    /**
     * 获取草稿
     */
    public BlogDraftDTO getDraft(String draftId) {
        String key = RedisConstants.BLOG_DRAFT_KEY + draftId;
        String json = stringRedisTemplate.opsForValue().get(key);
        if (json == null) {
            return null;
        }
        return JSONUtil.toBean(json, BlogDraftDTO.class);
    }

    /**
     * 保存草稿
     */
    public void saveDraft(BlogDraftDTO draft) {
        String key = RedisConstants.BLOG_DRAFT_KEY + draft.getDraftId();
        String json = JSONUtil.toJsonStr(draft);
        stringRedisTemplate.opsForValue().set(key, json, RedisConstants.BLOG_DRAFT_TTL, TimeUnit.SECONDS);
    }

    /**
     * 更新草稿内容
     */
    public BlogDraftDTO updateDraft(String draftId, String title, String content) {
        BlogDraftDTO draft = getDraft(draftId);
        if (draft == null) {
            throw new RuntimeException("草稿不存在");
        }

        if (title != null && !title.isEmpty()) {
            draft.setTitle(title);
        }
        if (content != null && !content.isEmpty()) {
            draft.setContent(content);
        }
        draft.setUpdateTime(LocalDateTime.now());

        saveDraft(draft);
        return draft;
    }

    /**
     * 根据用户在主对话窗口里的要求改写当前草稿。
     */
    public BlogDraftDTO reviseDraft(String draftId, String instruction, String currentTitle, String currentContent) {
        BlogDraftDTO draft = getDraft(draftId);
        if (draft == null) {
            throw new RuntimeException("草稿不存在");
        }
        if (instruction == null || instruction.trim().isEmpty()) {
            return draft;
        }
        if (currentTitle != null && !currentTitle.trim().isEmpty()) {
            draft.setTitle(currentTitle.trim());
        }
        if (currentContent != null && !currentContent.trim().isEmpty()) {
            draft.setContent(currentContent.trim());
        }

        String prompt = """
                你是探店笔记改稿助手。请根据用户的修改要求，改写当前笔记标题和正文。
                要求：
                - 保留用户已经生成的主要信息，不要编造店铺、价格、地址。
                - 如果用户只要求改标题，只调整 title，content 保持原文。
                - 如果用户只要求改正文，只调整 content，title 保持原文。
                - 正文不超过500字。
                - 只返回JSON，不要解释。

                当前标题：
                %s

                当前正文：
                %s

                用户修改要求：
                %s

                返回格式：
                {"title":"...","content":"..."}
                """.formatted(
                Optional.ofNullable(draft.getTitle()).orElse(""),
                Optional.ofNullable(draft.getContent()).orElse(""),
                instruction
        );

        try {
            String response = ChatClient.create(chatModel)
                    .prompt()
                    .user(prompt)
                    .call()
                    .content();

            Map<String, Object> revised = parseJsonObject(response);
            Object title = revised.get("title");
            Object content = revised.get("content");

            if (title != null && !title.toString().trim().isEmpty()) {
                draft.setTitle(title.toString().trim());
            }
            if (content != null && !content.toString().trim().isEmpty()) {
                draft.setContent(content.toString().trim());
            }

            if (hasPublishContent(draft)) {
                draft.updatePhase(BlogDraftDTO.DraftPhase.READY_FOR_PUBLISH, "ChatRevision");
            } else {
                draft.setUpdateTime(LocalDateTime.now());
            }
            saveDraft(draft);
            return draft;
        } catch (Exception e) {
            log.error("草稿改写失败: draftId={}, instruction={}", draftId, instruction, e);
            throw new RuntimeException("草稿改写失败");
        }
    }

    /**
     * 关联店铺
     */
    public BlogDraftDTO associateShop(String draftId, Long shopId) {
        BlogDraftDTO draft = getDraft(draftId);
        if (draft == null) {
            throw new RuntimeException("草稿不存在");
        }

        // 获取店铺信息
        Shop shop = shopService.getById(shopId);
        if (shop == null) {
            throw new RuntimeException("店铺不存在");
        }

        draft.setShopId(shopId);
        draft.setShopName(shop.getName());
        if (hasPublishContent(draft)) {
            draft.updatePhase(BlogDraftDTO.DraftPhase.READY_FOR_PUBLISH, "UserSelection");
        } else {
            draft.updatePhase(BlogDraftDTO.DraftPhase.SHOP_ASSOCIATED, "UserSelection");
        }
        saveDraft(draft);

        log.info("关联店铺: draftId={}, shopId={}, shopName={}", draftId, shopId, shop.getName());
        return draft;
    }

    // ==================== 多Agent执行 ====================

    /**
     * 流式执行笔记写作流程（多Agent并行）
     *
     * Agent协作架构：
     * - ImageAnalyzer: 分析图片
     * - TitleAgent: 生成标题（并行）
     * - ContentAgent: 生成文案（并行）
     */
    public Flux<BlogWritingProgressDTO> executeWritingStream(String draftId, List<String> images) {
        return Flux.create(emitter -> {
            try {
                BlogDraftDTO draft = getDraft(draftId);
                if (draft == null) {
                    emitter.error(new RuntimeException("草稿不存在"));
                    return;
                }
                if (draft.getShopId() == null) {
                    emitter.error(new RuntimeException("请先选择店铺，再开始创作"));
                    return;
                }

                // 添加图片到草稿
                for (String imageUrl : images) {
                    draft.addImage(imageUrl);
                }
                saveDraft(draft);

                // Phase 1: 图片分析 (ImageAnalyzer Agent)
                emitter.next(BlogWritingProgressDTO.start(draftId, "ImageAnalyzer", "正在分析图片..."));
                draft.updatePhase(BlogDraftDTO.DraftPhase.IMAGE_ANALYZING, "ImageAnalyzer");
                saveDraft(draft);

                List<Map<String, Object>> allAnalyses = new ArrayList<>();
                for (String imageUrl : images) {
                    Map<String, Object> analysis = analyzeImage(imageUrl);
                    allAnalyses.add(analysis);
                    draft.addImageAnalysis(analysis);
                    emitter.next(BlogWritingProgressDTO.imageAnalyzed(draftId, analysis));
                }

                draft.updatePhase(BlogDraftDTO.DraftPhase.IMAGE_ANALYZED, "ImageAnalyzer");
                saveDraft(draft);

                // Phase 2: 并行生成标题和文案
                emitter.next(BlogWritingProgressDTO.start(draftId, "MultiAgent", "正在并行创作标题和文案..."));
                draft.updatePhase(BlogDraftDTO.DraftPhase.CONTENT_GENERATING, "MultiAgent");
                saveDraft(draft);

                // 综合分析结果
                Map<String, Object> combinedAnalysis = combineAnalyses(allAnalyses);

                // 并行执行 TitleAgent 和 ContentAgent
                CompletableFuture<String> titleFuture = CompletableFuture.supplyAsync(
                    () -> generateTitle(combinedAnalysis, draft), agentExecutor
                );
                CompletableFuture<String> contentFuture = CompletableFuture.supplyAsync(
                    () -> generateContentBody(combinedAnalysis, draft), agentExecutor
                );

                // 等待两个Agent完成
                CompletableFuture.allOf(titleFuture, contentFuture).join();

                String title = titleFuture.get();
                String content = contentFuture.get();

                log.info("多Agent并行完成: title={}, contentLength={}", title, content.length());

                // 流式输出内容（打字效果）
                int contentLength = content.length();
                int chunkSize = 10;
                for (int i = 0; i <= contentLength; i += chunkSize) {
                    int end = Math.min(i + chunkSize, contentLength);
                    String partialContent = content.substring(0, end);
                    boolean complete = end >= contentLength;
                    emitter.next(BlogWritingProgressDTO.contentProgress(draftId, title, partialContent, complete));
                    Thread.sleep(50);
                }

                draft.setTitle(title);
                draft.setContent(content);
                if (hasPublishContent(draft)) {
                    draft.updatePhase(BlogDraftDTO.DraftPhase.READY_FOR_PUBLISH, "MultiAgent");
                } else {
                    draft.updatePhase(BlogDraftDTO.DraftPhase.CONTENT_GENERATED, "MultiAgent");
                }
                saveDraft(draft);

                if (draft.getPhase() == BlogDraftDTO.DraftPhase.READY_FOR_PUBLISH) {
                    Shop shop = shopService.getById(draft.getShopId());
                    emitter.next(BlogWritingProgressDTO.readyToPublish(
                            draftId,
                            draft.getTitle(),
                            draft.getContent(),
                            formatShopInfo(shop)
                    ));
                }

                emitter.complete();
                log.info("写作流程完成: draftId={}, phase={}", draftId, draft.getPhase());

            } catch (Exception e) {
                log.error("写作流程执行失败: draftId={}", draftId, e);
                emitter.error(e);
            }
        });
    }

    /**
     * 发布笔记
     */
    public Flux<BlogWritingProgressDTO> publishStream(String draftId) {
        return Flux.create(emitter -> {
            try {
                Long blogId = publishDraft(draftId);
                emitter.next(BlogWritingProgressDTO.published(draftId, blogId));
                emitter.complete();

                log.info("笔记发布成功: draftId={}, blogId={}", draftId, blogId);

            } catch (Exception e) {
                log.error("笔记发布失败: draftId={}", draftId, e);
                emitter.error(e);
            }
        });
    }

    /**
     * 同步发布笔记并返回数据库ID。
     */
    public Long publishDraft(String draftId) {
        BlogDraftDTO draft = getDraft(draftId);
        if (draft == null) {
            throw new RuntimeException("草稿不存在");
        }

        if (draft.getPhase() == BlogDraftDTO.DraftPhase.SHOP_ASSOCIATED && hasPublishContent(draft)) {
            draft.updatePhase(BlogDraftDTO.DraftPhase.READY_FOR_PUBLISH, "BlogPublisher");
            saveDraft(draft);
        }

        if (!draft.canPublish()) {
            throw new RuntimeException("草稿内容不完整，无法发布，请先生成内容并选择店铺");
        }

        Blog blog = new Blog();
        blog.setUserId(draft.getUserId());
        blog.setShopId(draft.getShopId());
        blog.setTitle(draft.getTitle());
        blog.setContent(draft.getContent());
        blog.setImages(String.join(",", draft.getImages()));
        blog.setLiked(0);
        blog.setComments(0);
        blog.setCreateTime(LocalDateTime.now());
        blog.setUpdateTime(LocalDateTime.now());

        Result result = blogService.saveBlog(blog);
        if (!result.isSuccess() || result.getData() == null) {
            throw new RuntimeException("发布失败: 数据库保存失败");
        }
        Long blogId = ((Number) result.getData()).longValue();

        draft.updatePhase(BlogDraftDTO.DraftPhase.PUBLISHED, "BlogPublisher");
        saveDraft(draft);

        log.info("笔记发布成功: draftId={}, blogId={}", draftId, blogId);
        return blogId;
    }

    private boolean hasPublishContent(BlogDraftDTO draft) {
        return draft.getShopId() != null
                && draft.getUserId() != null
                && draft.getTitle() != null && !draft.getTitle().isEmpty()
                && draft.getContent() != null && !draft.getContent().isEmpty()
                && draft.getImages() != null && !draft.getImages().isEmpty();
    }

    private Map<String, Object> parseJsonObject(String response) {
        if (response == null || !response.contains("{")) {
            return Map.of();
        }
        int start = response.indexOf("{");
        int end = response.lastIndexOf("}") + 1;
        if (end <= start) {
            return Map.of();
        }
        return JSONUtil.toBean(response.substring(start, end), Map.class);
    }

    /**
     * 用户选择店铺后继续流程
     */
    public Flux<BlogWritingProgressDTO> selectShopAndPrepareStream(String draftId, Long shopId) {
        return Flux.create(emitter -> {
            try {
                BlogDraftDTO draft = associateShop(draftId, shopId);

                // 获取店铺详情
                Shop shop = shopService.getById(shopId);
                if (draft.canPublish()) {
                    emitter.next(BlogWritingProgressDTO.readyToPublish(
                        draftId,
                        draft.getTitle(),
                        draft.getContent(),
                        formatShopInfo(shop)
                    ));
                } else {
                    BlogWritingProgressDTO progress = new BlogWritingProgressDTO();
                    progress.setDraftId(draftId);
                    progress.setPhase(draft.getPhase().name());
                    progress.setAgentName("UserSelection");
                    progress.setSelectedShop(formatShopInfo(shop));
                    progress.setPhaseComplete(true);
                    progress.setReadyToPublish(false);
                    emitter.next(progress);
                }

                emitter.complete();
            } catch (Exception e) {
                log.error("选择店铺失败: draftId={}, shopId={}", draftId, shopId, e);
                emitter.error(e);
            }
        });
    }

    // ==================== Agent内部方法 ====================

    /**
     * ImageAnalyzer Agent: 分析图片
     * 将图片下载并转换为 Base64 格式（带 data:image/jpeg;base64, 前缀）
     */
    private Map<String, Object> analyzeImage(String imageUrl) {
        log.info("ImageAnalyzer: 分析图片 {}", imageUrl);

        try {
            // 检查是否是虚构的URL
            if (imageUrl == null || imageUrl.contains("example.com") || imageUrl.contains("placeholder")) {
                log.warn("检测到虚构或空的图片URL，返回默认分析结果");
                return createDefaultAnalysis();
            }

            String analysisPrompt = """
                请分析这张探店图片，识别以下信息并以JSON格式返回：
                {
                  "foodType": "菜品类型（如：港式茶餐厅、火锅、烧烤等）",
                  "dishes": ["识别到的菜品列表"],
                  "environment": "环境风格（如：复古港风、现代简约、温馨等）",
                  "vibe": "氛围描述",
                  "estimatedPrice": "预估人均消费（数字）",
                  "suitableFor": ["适合的场景列表"],
                  "highlights": ["亮点特色"]
                }
                只返回JSON，不要其他解释。
                """;

            // 从 MinIO 下载图片并转换为 Base64
            byte[] imageData = downloadImageAsBytes(imageUrl);
            if (imageData == null) {
                log.warn("图片下载失败，返回默认分析结果");
                return createDefaultAnalysis();
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

            // 提取JSON部分
            if (response != null && response.contains("{")) {
                int start = response.indexOf("{");
                int end = response.lastIndexOf("}") + 1;
                String jsonStr = response.substring(start, end);
                return JSONUtil.toBean(jsonStr, Map.class);
            }

        } catch (Exception e) {
            log.warn("图片分析失败: {}", imageUrl, e);
        }

        // 返回默认分析结果
        return createDefaultAnalysis();
    }

    /**
     * TitleAgent: 生成吸引人的标题
     */
    private String generateTitle(Map<String, Object> analysis, BlogDraftDTO draft) {
        log.info("TitleAgent: 开始生成标题");

        try {
            StringBuilder promptBuilder = new StringBuilder();
            promptBuilder.append("""
                你是探店笔记标题专家。请根据以下信息，生成一个吸引眼球的标题。

                ## 标题风格选择（选一种）
                1. 情绪共鸣型：终于找到这家XXX了！/ X刷的宝藏店铺，必须安利！
                2. 好奇心型：人均XX的XXX，真的值得吗？/ 这家XXX凭什么这么火？
                3. 价值型：XX元吃饱！XXX人均攻略 / 这家XXX，性价比天花板！
                4. 场景型：约会必去！这家XXX太浪漫了 / 聚餐首选！XXX超适合朋友聚会

                ## 要求
                - 不超过30字
                - 有吸引力，能引起用户点击欲望
                - 只返回标题文本，不要其他解释

                """);

            promptBuilder.append("店铺类型：").append(analysis.get("foodType")).append("\n");
            promptBuilder.append("亮点：").append(analysis.get("highlights")).append("\n");

            if (draft.getShopId() != null) {
                Shop shop = shopService.getById(draft.getShopId());
                if (shop != null) {
                    promptBuilder.append("店铺名称：").append(shop.getName()).append("\n");
                    promptBuilder.append("人均价格：").append(shop.getAvgPrice()).append("元\n");
                }
            }

            promptBuilder.append("\n请直接生成标题：");

            ChatClient chatClient = ChatClient.create(chatModel);
            String title = chatClient.prompt()
                    .user(promptBuilder.toString())
                    .call()
                    .content();

            // 清理标题（去掉可能的引号或多余内容）
            title = title.trim().replaceAll("^[\"']|[\"']$", "");
            if (title.length() > 30) {
                title = title.substring(0, 30);
            }

            log.info("TitleAgent完成: {}", title);
            return title.isEmpty() ? "探店分享" : title;

        } catch (Exception e) {
            log.error("TitleAgent生成失败", e);
            return "探店分享";
        }
    }

    /**
     * ContentAgent: 生成爆文正文
     */
    private String generateContentBody(Map<String, Object> analysis, BlogDraftDTO draft) {
        log.info("ContentAgent: 开始生成文案");

        try {
            StringBuilder promptBuilder = new StringBuilder();

            promptBuilder.append("""
                你是探店笔记爆文创作专家。请根据图片分析和店铺信息，生成一篇层次分明、吸引眼球的探店正文。

                ## 爆文结构要求（必须严格遵守）

                【开场引入】（1-2句）
                - 用一句话引起共鸣或好奇心

                【环境/氛围】（2-3句）
                - 描述店铺环境特色

                【核心推荐】（每道菜单独一段）
                - 推荐具体菜品，描述口感味道
                - 每道菜前加emoji符号

                【实用信息】（1-2句）
                - 人均价格、适合场景

                【结尾互动】（1句）
                - 引导互动评论

                ## 格式要求
                - 每个段落之间必须换行
                - 短句为主，每句不超过20字
                - 使用emoji增加视觉吸引力

                """);

            promptBuilder.append("## 图片分析结果\n");
            promptBuilder.append("- 菜品类型：").append(analysis.get("foodType")).append("\n");
            promptBuilder.append("- 菜品：").append(analysis.get("dishes")).append("\n");
            promptBuilder.append("- 环境：").append(analysis.get("environment")).append("\n");
            promptBuilder.append("- 氛围：").append(analysis.get("vibe")).append("\n");
            promptBuilder.append("- 亮点：").append(analysis.get("highlights")).append("\n\n");

            if (draft.getShopId() != null) {
                Shop shop = shopService.getById(draft.getShopId());
                if (shop != null) {
                    promptBuilder.append("## 店铺详情\n");
                    promptBuilder.append("- 店铺名称：").append(shop.getName()).append("\n");
                    promptBuilder.append("- 地址：").append(shop.getAddress()).append("\n");
                    promptBuilder.append("- 人均：").append(shop.getAvgPrice()).append("元\n");
                    promptBuilder.append("- 评分：").append(shop.getScore() / 10.0).append("⭐\n\n");
                }
            }

            promptBuilder.append("## 文案风格：").append(draft.getStyle()).append("\n");
            promptBuilder.append("## 目标字数：约").append(draft.getWordCount()).append("字\n\n");

            promptBuilder.append("""
                请直接生成正文内容（不要返回标题，只返回正文）：
                """);

            ChatClient chatClient = ChatClient.create(chatModel);
            String content = chatClient.prompt()
                    .user(promptBuilder.toString())
                    .call()
                    .content();

            // 清理内容
            content = content.trim();
            if (content.startsWith("\"") && content.endsWith("\"")) {
                content = content.substring(1, content.length() - 1);
            }

            log.info("ContentAgent完成: length={}", content.length());
            return content.isEmpty() ? "今天去了这家店，体验不错～" : content;

        } catch (Exception e) {
            log.error("ContentAgent生成失败", e);
            return "今天去了这家店，体验不错～";
        }
    }

    // ==================== 辅助方法 ====================

    /**
     * 综合多张图片的分析结果
     */
    private Map<String, Object> combineAnalyses(List<Map<String, Object>> analyses) {
        if (analyses.isEmpty()) {
            return createDefaultAnalysis();
        }

        if (analyses.size() == 1) {
            return analyses.get(0);
        }

        // 多张图片时，合并信息
        Map<String, Object> combined = new HashMap<>();
        combined.put("foodType", analyses.get(0).get("foodType"));

        Set<String> allDishes = new HashSet<>();
        Set<String> allHighlights = new HashSet<>();
        int maxPrice = 0;
        String environment = "";
        String vibe = "";

        for (Map<String, Object> analysis : analyses) {
            if (analysis.get("dishes") != null) {
                allDishes.addAll((List<String>) analysis.get("dishes"));
            }
            if (analysis.get("highlights") != null) {
                allHighlights.addAll((List<String>) analysis.get("highlights"));
            }
            Object priceObj = analysis.get("estimatedPrice");
            if (priceObj instanceof Number) {
                maxPrice = Math.max(maxPrice, ((Number) priceObj).intValue());
            }
            if (analysis.get("environment") != null && environment.isEmpty()) {
                environment = (String) analysis.get("environment");
            }
            if (analysis.get("vibe") != null && vibe.isEmpty()) {
                vibe = (String) analysis.get("vibe");
            }
        }

        combined.put("dishes", new ArrayList<>(allDishes));
        combined.put("highlights", new ArrayList<>(allHighlights));
        combined.put("estimatedPrice", maxPrice);
        combined.put("environment", environment);
        combined.put("vibe", vibe);
        combined.put("suitableFor", List.of("朋友聚餐", "探店打卡"));

        return combined;
    }

    /**
     * 格式化店铺信息
     */
    private Map<String, Object> formatShopInfo(Shop shop) {
        Map<String, Object> info = new HashMap<>();
        info.put("id", shop.getId());
        info.put("name", shop.getName());
        info.put("score", shop.getScore() / 10.0);
        info.put("avgPrice", shop.getAvgPrice());
        info.put("address", shop.getAddress());
        return info;
    }

    /**
     * 创建默认分析结果
     */
    private Map<String, Object> createDefaultAnalysis() {
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

    /**
     * 从 MinIO 下载图片并返回字节数据
     * 支持 MinIO URL 格式: http://host:port/bucket/objectPath
     * 也支持纯路径格式: bucket/objectPath 或 /bucket/objectPath
     */
    private byte[] downloadImageAsBytes(String imageUrl) {
        try {
            if (imageUrl == null || imageUrl.isEmpty()) {
                log.warn("图片URL为空");
                return null;
            }

            // 检查是否是假的URL（Agent虚构的示例URL）
            if (imageUrl.contains("example.com") || imageUrl.contains("placeholder") || imageUrl.contains("sample")) {
                log.warn("检测到虚构的图片URL: {}, 返回null", imageUrl);
                return null;
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
            return null;
        }
    }

    /**
     * 获取当前用户ID
     */
    private Long getCurrentUserId() {
        try {
            var user = UserHolder.getUser();
            return user != null ? user.getId() : null;
        } catch (Exception e) {
            log.warn("获取用户ID失败", e);
            return null;
        }
    }
}
