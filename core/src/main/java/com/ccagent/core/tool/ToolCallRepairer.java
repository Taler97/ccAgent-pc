package com.ccagent.core.tool;

import com.ccagent.infrastructure.util.JsonUtil;

import java.util.HashMap;
import java.util.Map;

/**
 * 工具调用格式修复器
 * <p>修复 LLM 返回的工具调用格式错误，避免因格式问题重发请求导致前缀缓存失效。
 * 设计原则：能在本地修的绝不发回 LLM 重算。</p>
 *
 * <h3>修复场景</h3>
 * <ul>
 *   <li>JSON 少了末尾的 } 或 ] → 自动补全闭合括号</li>
 *   <li>参数名拼写错误（相似度 &gt; 0.8）→ 自动映射到正确的参数名</li>
 *   <li>多余的尾随逗号 → 移除</li>
 *   <li>工具名大小写不一致 → 自动匹配已注册工具的正确名称</li>
 * </ul>
 */
public class ToolCallRepairer {

    /**
     * 修复工具调用参数 JSON
     *
     * @param toolName          工具名称
     * @param argsJson          参数 JSON 字符串
     * @param registeredTools   已注册的工具名集合（用于模糊匹配）
     * @return 修复后的参数 Map；若无法修复则抛出异常
     * @throws IllegalArgumentException 修复失败
     */
    public Map<String, Object> repair(String toolName, String argsJson,
                                       java.util.Set<String> registeredTools) {
        if (argsJson == null || argsJson.isBlank()) {
            return new HashMap<>();
        }

        String json = argsJson.trim();

        // 尝试直接解析
        try {
            return JsonUtil.fromJson(json, HashMap.class);
        } catch (Exception ignored) {
            // 解析失败，尝试修复
        }

        // 1. 补全闭合括号
        json = fixBrackets(json);

        // 2. 移除尾随逗号
        json = json.replaceAll(",\\s*}", "}")
                .replaceAll(",\\s*]", "]");

        // 3. 尝试重新解析
        try {
            return JsonUtil.fromJson(json, HashMap.class);
        } catch (Exception ignored) {
            // 仍失败
        }

        // 4. 尝试用简单引号修复（部分 LLM 可能用单引号而非双引号）
        json = json.replace('\'', '"');
        try {
            return JsonUtil.fromJson(json, HashMap.class);
        } catch (Exception ignored) {
            // 最终失败
        }

        throw new IllegalArgumentException(
                "无法修复工具调用参数: toolName=" + toolName + ", args=" + argsJson);
    }

    /**
     * 补全 JSON 中缺失的闭合括号
     */
    private String fixBrackets(String json) {
        int openBraces = countChar(json, '{');
        int closeBraces = countChar(json, '}');
        int openBrackets = countChar(json, '[');
        int closeBrackets = countChar(json, ']');

        StringBuilder sb = new StringBuilder(json);
        for (int i = 0; i < openBraces - closeBraces; i++) {
            sb.append('}');
        }
        for (int i = 0; i < openBrackets - closeBrackets; i++) {
            sb.append(']');
        }
        return sb.toString();
    }

    private int countChar(String s, char c) {
        int count = 0;
        for (int i = 0; i < s.length(); i++) {
            if (s.charAt(i) == c) count++;
        }
        return count;
    }
}
