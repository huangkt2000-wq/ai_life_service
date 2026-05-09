# Skills Markdown 格式规范

## 文件命名

Skills 文件放置于 `src/main/resources/skills/` 目录，命名格式为 `{skill-name}.skill.md`。

例如：
- `shop.skill.md`
- `recommend.skill.md`
- `reservation.skill.md`
- `blog.skill.md`

## 文件结构

```markdown
---
name: skill-name
description: 技能描述
priority: 1  # 优先级，数字越小优先级越高
---

# 触发条件

keywords:
- 关键词1
- 关键词2
- 关键词3

# 工具定义

## tool_name_1

描述: 工具描述

参数:
| 参数名 | 类型 | 必填 | 描述 |
|--------|------|------|------|
| id | long | 是 | 商铺ID |

返回: 返回值描述

## tool_name_2

...

# Prompt 模板

prompt: |
  你是xxx助手...
  可用操作...
  回复要求...

# 示例对话（可选）

示例对话不参与加载，仅作为文档参考。
```

## 解析规则

加载器按以下规则解析 Markdown：

1. **Front Matter（YAML 头）**: 解析 `name`、`description`、`priority`
2. **触发条件**: 读取 `keywords:` 下的列表
3. **工具定义**: 解析 `## tool_name` 下的参数表格
4. **Prompt 模板**: 读取 `prompt:` 下的多行文本

## 参数类型

支持以下参数类型：

| 类型 | 说明 | 示例 |
|------|------|------|
| `long` | 长整数 | 商铺ID |
| `integer` | 整数 | 就餐人数 |
| `string` | 字符串 | 餐厅名称 |
| `double` | 浮点数 | 经纬度坐标 |
| `datetime` | 日期时间 | 预约时间 |
| `date` | 日期 | 查询日期 |
| `boolean` | 布尔值 | 是否需要包间 |
| `array` | 数组 | 图片列表 |
| `object` | 对象 | 图片分析结果 |

## 优先级说明

优先级用于意图识别时的匹配顺序：
- 数字越小，优先级越高
- 同一关键词可能触发多个 Skill，按优先级选择
- 默认优先级为 10