package com.hmdp.ai.agent;

import cn.hutool.json.JSONUtil;
import com.alibaba.cloud.ai.graph.RunnableConfig;
import com.alibaba.cloud.ai.graph.agent.ReactAgent;
import com.hmdp.ai.dto.AgentRequest;
import com.hmdp.ai.dto.AgentResponse;
import com.hmdp.ai.memory.ChatMemory;
import com.hmdp.ai.memory.RedisChatMemory;
import com.hmdp.ai.skill.SkillDefinition;
import com.hmdp.ai.skill.SkillsLoader;
import com.hmdp.ai.tool.BlogTools;
import com.hmdp.ai.tool.RecommendTools;
import com.hmdp.ai.tool.ReservationTools;
import com.hmdp.ai.tool.ShopTools;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.content.Media;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.util.MimeTypeUtils;
import org.springframework.web.client.RestTemplate;
import reactor.core.publisher.Flux;

import io.minio.GetObjectArgs;
import io.minio.MinioClient;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.Resource;
import java.io.InputStream;
import java.net.URI;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

/**
 * 智能客服 Agent 核心
 * 使用 Spring AI Alibaba ReactAgent 实现 ReAct 模式
 * 对话记忆持久化到 Redis，每次最多带出 30 条历史消息
 * 支持多模态图片输入
 */
@Slf4j
@Component
public class CustomerServiceAgent {

    private static final int MAX_HISTORY_MESSAGES = 30;

    /**
     * 多模态 ChatModel（用于带图片的对话）
     * 使用 qwen3.5-plus 模型
     */
    @Resource
    @Qualifier("multimodalChatModel")
    private ChatModel multimodalChatModel;

    /**
     * 普通 ChatModel（用于纯文本对话）
     */
    @Resource
    private ChatModel chatModel;

    /**
     * MinIO 客户端（用于下载图片）
     */
    @Resource
    private MinioClient minioClient;

    @Resource
    private SkillsLoader skillsLoader;

    @Resource
    private ShopTools shopTools;

    @Resource
    private RecommendTools recommendTools;

    @Resource
    private ReservationTools reservationTools;

    @Resource
    private BlogTools blogTools;

    @Resource(name = "redisChatMemory")
    private RedisChatMemory chatMemory;  // 改为具体类型以支持带图片的方法

    private ReactAgent agent;

    /**
     * 构建 System Prompt
     */
    private String buildSystemPrompt() {
        StringBuilder sb = new StringBuilder();

        sb.append("你是HM-DianPing平台的智能客服助手。\n\n");

        sb.append("# 可用技能和工具\n\n");

        List<SkillDefinition> definitions = skillsLoader.getSkillDefinitions();
        for (SkillDefinition definition : definitions) {
            sb.append("## ").append(definition.getName()).append(" Skill\n");
            sb.append("描述: ").append(definition.getDescription()).append("\n");
            sb.append("触发关键词: ").append(String.join(", ", definition.getKeywords())).append("\n\n");

            sb.append("工具:\n");
            for (SkillDefinition.ToolDefinition tool : definition.getTools()) {
                sb.append("- ").append(tool.getName()).append(": ").append(tool.getDescription()).append("\n");
            }

            if (definition.getPromptTemplate() != null) {
                sb.append("\n").append(definition.getPromptTemplate()).append("\n");
            }
        }

        sb.append("\n# 回答要求\n");
        sb.append("1. 友好亲切，使用礼貌用语\n");
        sb.append("2. 简洁明了，直接回答问题\n");
        sb.append("3. 主动引导用户操作\n");
        sb.append("4. 根据问题调用对应的工具获取数据\n");
        sb.append("5. 使用 Markdown 格式美化回复：\n");
        sb.append("   - 推荐餐厅用表格展示：| 名称 | 评分 | 人均 | 地址 |\n");
        sb.append("   - 列表信息用 bullet points (- 或 *)\n");
        sb.append("   - 关键信息用 **加粗** 强调\n");
        sb.append("   - 店铺名称用 🏪 等图标增加可读性\n");
        sb.append("   - 评分用 ⭐ 表示，如 ⭐4.5\n");

        // 动态注入当前日期
        sb.append("\n# 当前日期\n");
        sb.append("今天是 ").append(java.time.LocalDate.now().toString()).append("。\n");
        sb.append("当用户提到\"明天\"、\"后天\"等相对日期时，请使用实际日期计算。\n");

        sb.append("\n# 思考过程输出要求\n");
        sb.append("在调用任何工具之前，你需要先输出你的思考过程，格式如下：\n");
        sb.append("```\n");
        sb.append("【分析】用户想要...，需要使用...工具\n");
        sb.append("【工具】准备调用工具：xxx\n");
        sb.append("【参数】工具参数：xxx\n");
        sb.append("```\n");
        sb.append("思考过程输出后，再执行工具调用并生成最终回复。\n");

        return sb.toString();
    }

    /**
     * 初始化 ReactAgent（不使用内置 MemorySaver）
     */
    @PostConstruct
    public void init() {
        String systemPrompt = buildSystemPrompt();

        this.agent = ReactAgent.builder()
                .name("hmdp_customer_service_agent")
                .model(chatModel)
                .systemPrompt(systemPrompt)
                .methodTools(
                        shopTools,
                        recommendTools,
                        reservationTools,
                        blogTools
                )
                .build();

        log.info("ReactAgent 已创建: skills数量={}, tools=ShopTools,RecommendTools,ReservationTools,BlogTools",
                skillsLoader.getSkillDefinitions().size());
    }

    /**
     * 流式对话
     */
    public Flux<String> chatStream(AgentRequest request) {
        String sessionId = request.getSessionId();
        if (sessionId == null || sessionId.isEmpty()) {
            sessionId = chatMemory.createSession();
        }

        log.info("开始对话: sessionId={}, message={}", sessionId, request.getMessage());

        String finalSessionId = sessionId;
        return Flux.defer(() -> Flux.just(toSseEvent(chatWithSession(request, finalSessionId))))
                .doOnComplete(() -> log.info("对话完成: sessionId={}", finalSessionId));
    }

    /**
     * 普通对话
     */
    public AgentResponse chat(AgentRequest request) {
        String sessionId = request.getSessionId();
        if (sessionId == null || sessionId.isEmpty()) {
            sessionId = chatMemory.createSession();
        }

        log.info("开始对话: sessionId={}, message={}", sessionId, request.getMessage());

        AgentResponse response = chatWithSession(request, sessionId);
        log.info("对话完成: sessionId={}", sessionId);
        return response;
    }

    /**
     * 执行对话，使用 Redis 存储记忆
     * 支持多模态：当请求包含图片时，先用 qwen3.5-plus 识别图片，再将结果传递给 glm-5 的 ReactAgent
     */
    private AgentResponse chatWithSession(AgentRequest request, String sessionId) {
        AgentResponse response = new AgentResponse();
        response.setSessionId(sessionId);
        response.setDone(true);

        StringBuilder thinkingBuilder = new StringBuilder();
        boolean hasImages = request.getImages() != null && !request.getImages().isEmpty();

        try {
            // 1. 从 Redis 获取历史消息（最多 30 条）
            List<Message> historyMessages = chatMemory.get(sessionId, MAX_HISTORY_MESSAGES);
            log.debug("获取历史消息: sessionId={}, count={}", sessionId, historyMessages.size());

            // 2. 构建用户消息
            String userMessage = request.getMessage();

            // 3. 如果有图片，先用多模态模型识别图片内容
            String imageAnalysisResult = null;
            if (hasImages) {
                thinkingBuilder.append("【分析】用户上传了 ").append(request.getImages().size()).append(" 张图片\n");
                thinkingBuilder.append("【推理】使用 qwen3.5-plus 多模态模型进行图片识别...\n");

                // 使用 qwen3.5-plus 识别图片
                imageAnalysisResult = analyzeImages(request.getImages());
                thinkingBuilder.append("【识别】图片分析完成，已提取关键信息\n");

                // 将图片识别结果整合到用户消息中（包含实际图片URL）
                userMessage = buildMessageWithImageAnalysis(request.getMessage(), imageAnalysisResult, request.getImages());
                log.info("整合后的用户消息: {}", userMessage);
            }

            // 4. 构建带历史消息的 prompt（使用整合后的消息）
            String promptWithHistory = buildPromptWithHistory(userMessage, historyMessages);

            // 5. 保存用户消息到 Redis（原始消息 + 图片信息）
            String messageToSave = buildUserMessageWithImages(request);
            List<String> imagesToSave = hasImages ? request.getImages() : null;
            chatMemory.add(sessionId, new UserMessage(messageToSave), imagesToSave);

            // 6. 调用 ReactAgent（glm-5）处理
            RunnableConfig runnableConfig = RunnableConfig.builder()
                    .threadId(sessionId)
                    .build();

            thinkingBuilder.append("【推理】正在分析用户意图，匹配相关工具...\n");
            thinkingBuilder.append("【记忆】已加载 ").append(historyMessages.size()).append(" 条历史对话\n");

            AssistantMessage assistantMessage = agent.call(promptWithHistory, runnableConfig);
            String replyText = assistantMessage.getText();

            // 7. 保存 AI 回复到 Redis
            chatMemory.add(sessionId, new AssistantMessage(replyText));

            // 8. 检查并清理超过限制的消息
            trimMessagesIfNeeded(sessionId);

            // 9. 提取思考过程和回复内容
            String thinking = extractThinkingFromReply(replyText);
            String reply = extractActualReply(replyText);

            if (thinking == null || thinking.isEmpty()) {
                thinkingBuilder.append("【决策】已生成回复内容\n");
                thinking = thinkingBuilder.toString();
            } else {
                // 将图片识别的思考过程添加到前面
                thinking = thinkingBuilder.toString() + thinking;
            }

            response.setThinking(thinking);
            response.setReply(reply);

        } catch (Exception e) {
            log.error("Agent 调用失败: sessionId={}", sessionId, e);
            thinkingBuilder.append("【异常】Agent 执行过程中遇到错误：").append(e.getMessage()).append("\n");
            response.setThinking(thinkingBuilder.toString());
            response.setReply("抱歉，智能客服暂时不可用，请稍后再试。");
        }

        return response;
    }

    /**
     * 使用多模态模型识别图片内容
     * 将图片下载并转换为 Base64 格式（带 data:image/jpeg;base64, 前缀）
     */
    private String analyzeImages(List<String> imageUrls) {
        log.info("开始分析图片: {}", imageUrls);

        try {
            ChatClient chatClient = ChatClient.create(multimodalChatModel);

            String analysisPrompt = """
                请详细分析这些图片，识别以下信息：
                1. 图片中的主要内容（菜品、环境、物品等）
                2. 图片风格和氛围
                3. 可能的场景或用途
                4. 任何其他值得注意的细节

                请用简洁的文字描述图片内容，以便后续对话使用。
                """;

            // 构建多模态请求，使用 Base64 格式
            List<Media> mediaList = new ArrayList<>();
            for (String imageUrl : imageUrls) {
                try {
                    // 从 MinIO 下载图片并转换为 Base64
                    byte[] imageData = downloadImageAsBytes(imageUrl);
                    String base64Data = Base64.getEncoder().encodeToString(imageData);

                    // 使用 data URI 格式：data:image/jpeg;base64,{base64Data}
                    String dataUri = "data:image/jpeg;base64," + base64Data;

                    Media image = Media.builder()
                            .mimeType(MimeTypeUtils.IMAGE_JPEG)
                            .data(dataUri)
                            .build();
                    mediaList.add(image);

                    log.debug("图片转换为Base64成功: size={}bytes", imageData.length);

                } catch (Exception e) {
                    log.warn("图片下载失败: {}, 将跳过此图片", imageUrl, e);
                }
            }

            if (mediaList.isEmpty()) {
                return "图片下载失败，无法进行识别分析。";
            }

            String response = chatClient.prompt()
                    .user(u -> u.text(analysisPrompt).media(mediaList.toArray(new Media[0])))
                    .call()
                    .content();

            log.info("图片分析结果: {}", response);
            return response != null ? response : "图片识别完成，但未获取到详细内容。";

        } catch (Exception e) {
            log.error("图片分析失败", e);
            return "图片识别失败：" + e.getMessage();
        }
    }

    /**
     * 将图片识别结果整合到用户消息中
     * 包含实际的图片URL，避免 Agent 虚构假URL
     */
    private String buildMessageWithImageAnalysis(String originalMessage, String imageAnalysis, List<String> imageUrls) {
        StringBuilder sb = new StringBuilder();

        // 添加图片URL信息（让 Agent 知道真实的图片地址）
        if (imageUrls != null && !imageUrls.isEmpty()) {
            sb.append("【用户上传的图片】\n");
            for (int i = 0; i < imageUrls.size(); i++) {
                sb.append("图片").append(i + 1).append(": ").append(imageUrls.get(i)).append("\n");
            }
            sb.append("\n");
        }

        // 如果用户有文字消息，先添加
        if (originalMessage != null && !originalMessage.isEmpty()) {
            sb.append(originalMessage).append("\n\n");
        }

        // 添加图片识别结果作为上下文
        sb.append("【图片识别结果】\n");
        sb.append(imageAnalysis);
        sb.append("\n\n");
        sb.append("注意：图片已经被自动分析，以上是识别结果。\n");
        sb.append("如果需要进一步分析图片，请使用上面提供的真实图片URL，不要虚构假URL。\n");
        sb.append("请根据以上图片内容和我的问题进行回复。");

        return sb.toString();
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
                throw new RuntimeException("虚构的图片URL: " + imageUrl);
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
                    throw new RuntimeException("无法解析图片URL路径: " + imageUrl);
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
                    throw new RuntimeException("无法解析图片路径: " + imageUrl);
                }

                bucket = parts[0];
                objectName = parts[1];
            }

            log.debug("从MinIO下载图片: bucket={}, object={}", bucket, objectName);

            // 使用 MinioClient 下载图片
            InputStream inputStream = minioClient.getObject(
                    GetObjectArgs.builder()
                            .bucket(bucket)
                            .object(objectName)
                            .build()
            );

            // 读取所有字节
            byte[] imageData = inputStream.readAllBytes();
            inputStream.close();

            return imageData;

        } catch (Exception e) {
            log.error("下载图片失败: {}", imageUrl, e);
            throw new RuntimeException("下载图片失败: " + e.getMessage());
        }
    }

    /**
     * 构建包含图片信息的用户消息（用于保存到历史记录）
     */
    private String buildUserMessageWithImages(AgentRequest request) {
        StringBuilder sb = new StringBuilder();
        if (request.getMessage() != null && !request.getMessage().isEmpty()) {
            sb.append(request.getMessage());
        }
        sb.append("\n[上传了 ").append(request.getImages().size()).append(" 张图片]");
        return sb.toString();
    }

    /**
     * 构建带历史消息的 prompt
     * 将历史对话注入到当前问题之前
     */
    private String buildPromptWithHistory(String currentMessage, List<Message> historyMessages) {
        if (historyMessages.isEmpty()) {
            return currentMessage;
        }

        StringBuilder promptBuilder = new StringBuilder();
        promptBuilder.append("# 以下是对话历史（供参考上下文）\n\n");

        for (Message message : historyMessages) {
            String role = message.getMessageType().getValue();
            String content = message.getText();
            promptBuilder.append("**").append(role).append("**: ").append(content).append("\n\n");
        }

        promptBuilder.append("# 当前问题\n\n");
        promptBuilder.append(currentMessage);

        return promptBuilder.toString();
    }

    /**
     * 检查消息数量，超过限制时清理旧消息
     */
    private void trimMessagesIfNeeded(String sessionId) {
        long totalSize = chatMemory.size(sessionId);
        int maxAllowed = MAX_HISTORY_MESSAGES * 2; // 用户+AI 各30条

        if (totalSize > maxAllowed) {
            // 获取所有消息，保留最近 MAX_HISTORY_MESSAGES * 2 条
            List<Message> allMessages = chatMemory.getAll(sessionId);
            int retainCount = MAX_HISTORY_MESSAGES * 2;
            int startIndex = Math.max(0, allMessages.size() - retainCount);

            // 清除所有，然后重新添加保留的消息
            chatMemory.clear(sessionId);
            List<Message> recentMessages = allMessages.subList(startIndex, allMessages.size());
            for (Message message : recentMessages) {
                chatMemory.add(sessionId, message);
            }
            log.info("清理旧消息: sessionId={}, total={}, retained={}", sessionId, totalSize, retainCount);
        }
    }

    private String extractThinkingFromReply(String replyText) {
        if (replyText == null || replyText.isEmpty()) {
            return null;
        }

        StringBuilder thinking = new StringBuilder();
        String[] lines = replyText.split("\n");

        for (String line : lines) {
            if (line.contains("【分析】") || line.contains("【推理】") ||
                line.contains("【决策】") || line.contains("【工具】") ||
                line.contains("【参数】") || line.contains("【记忆】") ||
                line.startsWith("Thinking:") || line.contains("Thinking")) {
                thinking.append(line).append("\n");
            }
        }

        return thinking.length() > 0 ? thinking.toString().trim() : null;
    }

    private String extractActualReply(String replyText) {
        if (replyText == null || replyText.isEmpty()) {
            return "我已经收到你的问题，但这次没有生成有效回复。";
        }

        StringBuilder reply = new StringBuilder();
        String[] lines = replyText.split("\n");
        boolean inThinkingBlock = false;

        for (String line : lines) {
            if (line.contains("【分析】") || line.contains("【推理】") ||
                line.contains("【决策】") || line.contains("【工具】") ||
                line.contains("【参数】") || line.contains("【记忆】") ||
                line.startsWith("Thinking:") || line.contains("Thinking")) {
                inThinkingBlock = true;
                continue;
            }

            if (inThinkingBlock && !line.trim().isEmpty()) {
                if (!line.contains("【") && !line.contains("Thinking")) {
                    inThinkingBlock = false;
                    reply.append(line).append("\n");
                }
            } else if (!inThinkingBlock) {
                reply.append(line).append("\n");
            }
        }

        String result = reply.toString().trim();
        return result.isEmpty() ? replyText : result;
    }

    private String toSseEvent(AgentResponse response) {
        return "data: " + JSONUtil.toJsonStr(response) + "\n\n";
    }

    public void clearSession(String sessionId) {
        chatMemory.clear(sessionId);
        log.info("会话已清除: sessionId={}", sessionId);
    }
}