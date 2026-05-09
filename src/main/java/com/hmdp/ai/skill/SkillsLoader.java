package com.hmdp.ai.skill;

import cn.hutool.core.util.StrUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Skills 加载器
 * 启动时从 resources/skills/*.skill.md 加载 Skills 定义
 * 用于构建 Agent 的 System Prompt，实现技能边界规约
 */
@Slf4j
@Component
public class SkillsLoader {

    private List<SkillDefinition> skillDefinitions = new ArrayList<>();

    @PostConstruct
    public void loadSkills() {
        log.info("开始加载 Skills 定义...");

        try {
            // 扫描 resources/skills 目录下的 .skill.md 文件
            PathMatchingResourcePatternResolver resolver = new PathMatchingResourcePatternResolver();
            Resource[] resources = resolver.getResources("classpath*:skills/*.skill.md");

            log.info("发现 {} 个 Skill 文件", resources.length);

            for (Resource resource : resources) {
                try {
                    String filename = resource.getFilename();
                    log.info("加载 Skill 文件: {}", filename);

                    // 读取文件内容
                    String content = readResourceContent(resource);

                    // 解析 Skill 定义
                    SkillDefinition definition = parseSkillDefinition(content, filename);

                    if (definition != null) {
                        skillDefinitions.add(definition);
                        log.info("成功解析 Skill: {} (优先级={}, 工具数={})",
                                definition.getName(),
                                definition.getPriority(),
                                definition.getTools().size());
                    }
                } catch (Exception e) {
                    log.error("解析 Skill 文件失败: {}", resource.getFilename(), e);
                }
            }

            // 按优先级排序
            skillDefinitions.sort(Comparator.comparingInt(SkillDefinition::getPriority));

            log.info("Skills 加载完成，共加载 {} 个 Skills", skillDefinitions.size());

        } catch (Exception e) {
            log.error("加载 Skills 失败", e);
        }
    }

    /**
     * 读取资源文件内容
     */
    private String readResourceContent(Resource resource) {
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(resource.getInputStream(), StandardCharsets.UTF_8))) {
            return reader.lines().collect(Collectors.joining("\n"));
        } catch (Exception e) {
            throw new RuntimeException("读取文件失败", e);
        }
    }

    /**
     * 解析 Skill 定义
     */
    private SkillDefinition parseSkillDefinition(String content, String filename) {
        SkillDefinition definition = new SkillDefinition();

        // 1. 解析 Front Matter (YAML 头)
        parseFrontMatter(content, definition);

        // 2. 解析触发关键词
        parseKeywords(content, definition);

        // 3. 解析工具定义
        parseTools(content, definition);

        // 4. 解析 Prompt 模板
        parsePromptTemplate(content, definition);

        // 验证必要字段
        if (StrUtil.isBlank(definition.getName())) {
            log.warn("Skill 文件缺少 name 定义: {}", filename);
            return null;
        }

        return definition;
    }

    /**
     * 解析 Front Matter (YAML 头)
     */
    private void parseFrontMatter(String content, SkillDefinition definition) {
        // Front Matter 格式: --- 之间的内容
        Pattern pattern = Pattern.compile("\\A---\\R(.*?)\\R---", Pattern.DOTALL);
        Matcher matcher = pattern.matcher(content);

        if (matcher.find()) {
            String yamlContent = matcher.group(1);

            // name: xxx
            Pattern namePattern = Pattern.compile("name:\\s*(.+)");
            Matcher nameMatcher = namePattern.matcher(yamlContent);
            if (nameMatcher.find()) {
                definition.setName(nameMatcher.group(1).trim());
            }

            // description: xxx
            Pattern descPattern = Pattern.compile("description:\\s*(.+)");
            Matcher descMatcher = descPattern.matcher(yamlContent);
            if (descMatcher.find()) {
                definition.setDescription(descMatcher.group(1).trim());
            }

            // priority: xxx
            Pattern priorityPattern = Pattern.compile("priority:\\s*(\\d+)");
            Matcher priorityMatcher = priorityPattern.matcher(yamlContent);
            if (priorityMatcher.find()) {
                definition.setPriority(Integer.parseInt(priorityMatcher.group(1).trim()));
            } else {
                definition.setPriority(10); // 默认优先级
            }
        }
    }

    /**
     * 解析触发关键词
     */
    private void parseKeywords(String content, SkillDefinition definition) {
        Pattern pattern = Pattern.compile("keywords:\\s*\\n((?:-\\s*.+\\n?)+)");
        Matcher matcher = pattern.matcher(content);

        if (matcher.find()) {
            String keywordsContent = matcher.group(1);
            List<String> keywords = Arrays.stream(keywordsContent.split("\\n"))
                    .filter(line -> line.trim().startsWith("-"))
                    .map(line -> line.replaceFirst("-\\s*", "").trim())
                    .filter(StrUtil::isNotBlank)
                    .collect(Collectors.toList());
            definition.setKeywords(keywords);
        } else {
            definition.setKeywords(new ArrayList<>());
        }
    }

    /**
     * 解析工具定义
     */
    private void parseTools(String content, SkillDefinition definition) {
        List<SkillDefinition.ToolDefinition> tools = new ArrayList<>();

        Pattern toolPattern = Pattern.compile("##\\s+(\\w+)\\s*\\n((?:[^#]+))");
        Matcher toolMatcher = toolPattern.matcher(content);

        while (toolMatcher.find()) {
            String toolName = toolMatcher.group(1);
            String toolContent = toolMatcher.group(2);

            SkillDefinition.ToolDefinition tool = new SkillDefinition.ToolDefinition();
            tool.setName(toolName);

            Pattern descPattern = Pattern.compile("描述:\\s*(.+)");
            Matcher descMatcher = descPattern.matcher(toolContent);
            if (descMatcher.find()) {
                tool.setDescription(descMatcher.group(1).trim());
            }

            tool.setParameters(parseToolParameters(toolContent));

            Pattern returnPattern = Pattern.compile("返回:\\s*(.+)");
            Matcher returnMatcher = returnPattern.matcher(toolContent);
            if (returnMatcher.find()) {
                tool.setReturnType(returnMatcher.group(1).trim());
            }

            Pattern modelPattern = Pattern.compile("模型:\\s*(.+)");
            Matcher modelMatcher = modelPattern.matcher(toolContent);
            if (modelMatcher.find()) {
                tool.setModel(modelMatcher.group(1).trim());
            }

            tools.add(tool);
        }

        definition.setTools(tools);
    }

    /**
     * 解析工具参数表格
     */
    private List<SkillDefinition.ToolParameter> parseToolParameters(String toolContent) {
        List<SkillDefinition.ToolParameter> parameters = new ArrayList<>();

        Pattern tablePattern = Pattern.compile("参数:\\s*\\n\\|[^\\n]+\\n\\|[^\\n]+\\n((?:\\|[^\\n]+\\n?)+)");
        Matcher tableMatcher = tablePattern.matcher(toolContent);

        if (tableMatcher.find()) {
            String tableRows = tableMatcher.group(1);

            Pattern rowPattern = Pattern.compile("\\|\\s*(\\S+)\\s*\\|\\s*(\\S+)\\s*\\|\\s*(是|否)\\s*\\|\\s*([^|]+)\\s*\\|");
            Matcher rowMatcher = rowPattern.matcher(tableRows);

            while (rowMatcher.find()) {
                SkillDefinition.ToolParameter param = new SkillDefinition.ToolParameter();
                param.setName(rowMatcher.group(1));
                param.setType(rowMatcher.group(2));
                param.setRequired(rowMatcher.group(3).equals("是"));
                param.setDescription(rowMatcher.group(4).trim());

                if (!"无".equals(param.getName())) {
                    parameters.add(param);
                }
            }
        }

        return parameters;
    }

    /**
     * 解析 Prompt 模板
     */
    private void parsePromptTemplate(String content, SkillDefinition definition) {
        Pattern pattern = Pattern.compile("prompt:\\s*\\|\\s*\\n((?:.+\\n?)+?)(?=\\n#|$)");
        Matcher matcher = pattern.matcher(content);

        if (matcher.find()) {
            String promptContent = matcher.group(1);
            String prompt = Arrays.stream(promptContent.split("\\n"))
                    .map(line -> line.replaceAll("^\\s+", ""))
                    .collect(Collectors.joining("\\n"))
                    .trim();
            definition.setPromptTemplate(prompt);
        }
    }

    /**
     * 获取所有加载的 Skill 定义
     */
    public List<SkillDefinition> getSkillDefinitions() {
        return skillDefinitions;
    }

    /**
     * 根据名称获取 Skill 定义
     */
    public SkillDefinition getSkillDefinition(String name) {
        return skillDefinitions.stream()
                .filter(s -> s.getName().equals(name))
                .findFirst()
                .orElse(null);
    }
}
