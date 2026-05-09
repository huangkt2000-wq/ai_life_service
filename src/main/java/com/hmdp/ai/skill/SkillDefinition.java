package com.hmdp.ai.skill;

import lombok.Data;

import java.util.List;
import java.util.Map;

/**
 * Skill 定义类
 * 从 Markdown 文件解析后的 Skill 定义
 */
@Data
public class SkillDefinition {

    /**
     * 技能名称
     */
    private String name;

    /**
     * 技能描述
     */
    private String description;

    /**
     * 优先级（数字越小优先级越高）
     */
    private int priority;

    /**
     * 触发关键词列表
     */
    private List<String> keywords;

    /**
     * 工具定义列表
     */
    private List<ToolDefinition> tools;

    /**
     * Prompt 模板
     */
    private String promptTemplate;

    /**
     * 工具定义
     */
    @Data
    public static class ToolDefinition {
        /**
         * 工具名称
         */
        private String name;

        /**
         * 工具描述
         */
        private String description;

        /**
         * 参数列表
         */
        private List<ToolParameter> parameters;

        /**
         * 返回值描述
         */
        private String returnType;

        /**
         * 特殊模型（如多模态）
         */
        private String model;
    }

    /**
     * 工具参数
     */
    @Data
    public static class ToolParameter {
        /**
         * 参数名
         */
        private String name;

        /**
         * 参数类型
         */
        private String type;

        /**
         * 是否必填
         */
        private boolean required;

        /**
         * 参数描述
         */
        private String description;
    }
}