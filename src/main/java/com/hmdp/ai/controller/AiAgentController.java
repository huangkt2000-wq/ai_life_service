package com.hmdp.ai.controller;

import com.hmdp.ai.agent.BlogAgent;
import com.hmdp.ai.agent.CustomerServiceAgent;
import com.hmdp.ai.dto.*;
import com.hmdp.ai.memory.RedisChatMemory;
import com.hmdp.entity.Shop;
import com.hmdp.service.IShopService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import jakarta.annotation.Resource;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * AI Agent API Controller
 * 提供流式对话、历史记录、会话管理接口
 * 以及多Agent协同的笔记写作接口
 */
@Slf4j
@RestController
@RequestMapping("/ai/agent")
public class AiAgentController {

    private final CustomerServiceAgent customerServiceAgent;

    @Resource
    private BlogAgent blogAgent;

    @Resource(name = "redisChatMemory")
    private RedisChatMemory chatMemory;

    @Resource
    private IShopService shopService;

    public AiAgentController(CustomerServiceAgent customerServiceAgent) {
        this.customerServiceAgent = customerServiceAgent;
    }

    // ==================== 对话接口 ====================

    /**
     * SSE 流式对话
     * @param request 用户请求
     * @return SSE 流式响应
     */
    @PostMapping(value = "/chat/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<String> chatStream(@RequestBody AgentRequest request) {
        log.info("Received stream chat request: message={}", request.getMessage());
        return customerServiceAgent.chatStream(request);
    }

    /**
     * 普通对话（非流式）
     * @param request 用户请求
     * @return 响应
     */
    @PostMapping("/chat")
    public Mono<AgentResponse> chat(@RequestBody AgentRequest request) {
        log.info("Received chat request: message={}", request.getMessage());
        return Mono.just(customerServiceAgent.chat(request));
    }

    /**
     * 获取会话历史记录
     * @param sessionId 会话ID
     * @return 历史消息列表（包含图片信息）
     */
    @GetMapping("/history/{sessionId}")
    public Mono<ChatHistoryResponse> getHistory(@PathVariable String sessionId) {
        log.info("Getting history for sessionId: {}", sessionId);

        ChatHistoryResponse response = new ChatHistoryResponse();
        response.setSessionId(sessionId);

        // 获取带图片的消息记录
        List<RedisChatMemory.MessageRecord> records = chatMemory.getAllRecords(sessionId);

        List<MessageItem> messageItems = records.stream()
                .map(record -> new MessageItem(
                        record.getType(),
                        record.getContent(),
                        record.getImages()  // 包含图片信息
                ))
                .toList();

        response.setMessages(messageItems);
        response.setTotal(messageItems.size());

        return Mono.just(response);
    }

    /**
     * 清空会话（用于新增会话）
     * @param sessionId 会话ID
     * @return 操作结果
     */
    @DeleteMapping("/session/{sessionId}")
    public Mono<AgentResponse> clearSession(@PathVariable String sessionId) {
        log.info("Clearing session: {}", sessionId);
        customerServiceAgent.clearSession(sessionId);

        AgentResponse response = new AgentResponse();
        response.setSessionId(sessionId);
        response.setReply("会话已清空");
        response.setDone(true);

        return Mono.just(response);
    }

    /**
     * 创建新会话
     * @return 新会话ID
     */
    @PostMapping("/session/new")
    public Mono<String> newSession() {
        String sessionId = UUID.randomUUID().toString().replace("-", "");
        log.info("Created new session: {}", sessionId);
        return Mono.just(sessionId);
    }

    // ==================== 笔记写作接口（多Agent协同） ====================

    /**
     * 创建笔记草稿
     * 启动多Agent协同写作流程
     *
     * @param request 包含wordCount的请求
     * @return 草稿ID和触发指令
     */
    @PostMapping("/blog/draft/create")
    public Mono<BlogDraftDTO> createBlogDraft(@RequestBody AgentRequest request) {
        log.info("Creating blog draft: wordCount={}", request.getWordCount());

        String sessionId = request.getSessionId();
        if (sessionId == null || sessionId.isEmpty()) {
            sessionId = UUID.randomUUID().toString().replace("-", "");
        }

        BlogDraftDTO draft = blogAgent.createDraft(sessionId, request.getWordCount());
        return Mono.just(draft);
    }

    /**
     * 获取草稿详情
     *
     * @param draftId 草稿ID
     * @return 草稿内容
     */
    @GetMapping("/blog/draft/{draftId}")
    public Mono<BlogDraftDTO> getBlogDraft(@PathVariable String draftId) {
        log.info("Getting blog draft: {}", draftId);
        BlogDraftDTO draft = blogAgent.getDraft(draftId);
        return Mono.justOrEmpty(draft);
    }

    /**
     * 更新草稿内容
     *
     * @param request 包含draftId、title、content的请求
     * @return 更新后的草稿
     */
    @PostMapping("/blog/draft/update")
    public Mono<BlogDraftDTO> updateBlogDraft(@RequestBody AgentRequest request) {
        log.info("Updating blog draft: draftId={}", request.getDraftId());

        BlogDraftDTO draft = blogAgent.updateDraft(request.getDraftId(), request.getTitle(), request.getContent());
        return Mono.just(draft);
    }

    @PostMapping("/blog/draft/revise")
    public Mono<BlogDraftDTO> reviseBlogDraft(@RequestBody AgentRequest request) {
        log.info("Revising blog draft: draftId={}", request.getDraftId());

        BlogDraftDTO draft = blogAgent.reviseDraft(
                request.getDraftId(),
                request.getMessage(),
                request.getTitle(),
                request.getContent()
        );
        return Mono.just(draft);
    }

    /**
     * SSE 流式执行笔记写作流程
     * 返回写作进度，前端写作板实时展示
     *
     * @param request 包含draftId和images的请求
     * @return 写作进度流
     */
    @PostMapping(value = "/blog/write/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<BlogWritingProgressDTO> writeBlogStream(@RequestBody AgentRequest request) {
        log.info("Starting blog writing stream: draftId={}, images={}", request.getDraftId(), request.getImages());

        return blogAgent.executeWritingStream(request.getDraftId(), request.getImages());
    }

    /**
     * 搜索店铺供用户选择关联
     *
     * @param keyword 搜索关键词
     * @param draftId 草稿ID（可选）
     * @return 店铺列表
     */
    @GetMapping("/blog/shop/search")
    public Mono<Map<String, Object>> searchShop(
            @RequestParam String keyword,
            @RequestParam(required = false) String draftId) {
        log.info("Searching shop for blog: keyword={}, draftId={}", keyword, draftId);

        var result = shopService.queryShopByName(keyword, 1);
        if (!result.isSuccess()) {
            return Mono.just(Map.of("shops", List.of()));
        }

        List<Shop> shops = (List<Shop>) result.getData();
        List<Map<String, Object>> shopOptions = shops.stream()
                .limit(5)
                .map(shop -> {
                    Map<String, Object> option = new HashMap<>();
                    option.put("id", shop.getId());
                    option.put("name", shop.getName());
                    option.put("score", shop.getScore() / 10.0);
                    option.put("avgPrice", shop.getAvgPrice());
                    option.put("address", shop.getAddress());
                    return option;
                })
                .toList();

        return Mono.just(Map.of("shops", shopOptions));
    }

    /**
     * 选择店铺关联到草稿
     *
     * @param draftId 草稿ID
     * @param shopId  店铺ID
     * @return 进度响应
     */
    @PostMapping(value = "/blog/shop/select/{draftId}/{shopId}", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<BlogWritingProgressDTO> selectShop(@PathVariable String draftId, @PathVariable Long shopId) {
        log.info("Selecting shop: draftId={}, shopId={}", draftId, shopId);

        return blogAgent.selectShopAndPrepareStream(draftId, shopId);
    }

    /**
     * SSE 流式发布笔记
     *
     * @param draftId 草稿ID
     * @return 发布进度流
     */
    @PostMapping(value = "/blog/publish/stream/{draftId}", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<BlogWritingProgressDTO> publishBlogStream(@PathVariable String draftId) {
        log.info("Publishing blog: draftId={}", draftId);

        return blogAgent.publishStream(draftId);
    }

    @PostMapping("/blog/publish/{draftId}")
    public Mono<Map<String, Object>> publishBlog(@PathVariable String draftId) {
        log.info("Publishing blog directly: draftId={}", draftId);

        Long blogId = blogAgent.publishDraft(draftId);
        Map<String, Object> response = new HashMap<>();
        response.put("blogId", blogId);
        return Mono.just(response);
    }
}
