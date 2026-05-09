package com.hmdp.ai.dto;

import lombok.Data;

import java.util.List;

/**
 * Agent 响应 DTO
 */
@Data
public class AgentResponse {

    /**
     * 会话ID
     */
    private String sessionId;

    /**
     * AI 回复内容
     */
    private String reply;

    /**
     * 思考过程（Agent 的推理和工具调用步骤）
     */
    private String thinking;

    /**
     * 建议问题
     */
    private List<String> suggestions;

    /**
     * 相关数据（可选）
     */
    private Object relatedData;

    /**
     * 调用的 Tool 名称（可选）
     */
    private String toolCalled;

    /**
     * 是否完成
     */
    private boolean done;
}