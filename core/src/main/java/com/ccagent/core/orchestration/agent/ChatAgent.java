package com.ccagent.core.orchestration.agent;

import com.ccagent.api.model.memory.ConversationTurn;
import com.ccagent.api.model.memory.StructuredContext;
import com.ccagent.api.model.request.ChatRequest;
import com.ccagent.api.model.response.ChatResponse;
import com.ccagent.api.port.LlmPort;
import com.ccagent.api.port.MemoryPort;
import com.ccagent.infrastructure.util.JsonUtil;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 主 Agent（对话 / 决策）
 * <p>作为用户交互的入口，负责：</p>
 * <ul>
 *   <li>处理简单对话，直接回复</li>
 *   <li>判断是否需要进行任务执行，返回任务描述</li>
 * </ul>
 * <p>决策结果通过 {@link ChatResult} 封装：</p>
 * <ul>
 *   <li>{@code type: "chat"} → 直接回复</li>
 *   <li>{@code type: "plan"} → 需要规划执行</li>
 * </ul>
 */
public class ChatAgent {

    private final LlmPort llmPort;
    private final MemoryPort memoryPort;

    public ChatAgent(LlmPort llmPort, MemoryPort memoryPort) {
        this.llmPort = llmPort;
        this.memoryPort = memoryPort;
    }

    /**
     * 处理用户输入，返回决策结果
     *
     * @param sessionId      会话 ID
     * @param workspaceId    工作区 ID
     * @param userPrompt     用户输入
     * @param externalHistory 外部传入的历史消息（可选）
     * @return 决策结果
     */
    public ChatResult chat(String sessionId, String workspaceId,
                           String userPrompt,
                           List<Map<String, String>> externalHistory) {
        String memorySummary = "";
        List<ConversationTurn> recentTurns = List.of();
        try {
            StructuredContext ctx = memoryPort.loadStructuredContext(sessionId);
            memorySummary = ctx.getSummary();
            recentTurns = ctx.getRecentTurns();
        } catch (Exception ignored) {
        }

        List<Map<String, Object>> historyMessages = null;
        if (externalHistory != null && !externalHistory.isEmpty()) {
            historyMessages = new ArrayList<>();
            for (Map<String, String> m : externalHistory) {
                historyMessages.add(new LinkedHashMap<>(m));
            }
        } else if (!recentTurns.isEmpty()) {
            historyMessages = new ArrayList<>();
            for (ConversationTurn turn : recentTurns) {
                historyMessages.add(Map.<String, Object>of("role", turn.getRole(), "content", turn.getContent()));
            }
        }

        String prompt = buildPrompt(memorySummary, userPrompt);

        ChatRequest request = new ChatRequest(
                SYSTEM_PROMPT,
                prompt,
                List.of(),
                false,
                1024,
                0.7,
                30,
                historyMessages
        );

        ChatResponse response = llmPort.chat(request);
        return parseResult(response);
    }

    private ChatResult parseResult(ChatResponse response) {
        String content = response.getContent();
        if (content == null || content.isBlank()) {
            return ChatResult.reply("");
        }

        // 尝试解析 JSON 结构
        String json = content;
        if (content.contains("```json")) {
            int start = content.indexOf("```json") + 7;
            int end = content.indexOf("```", start);
            if (end > start) {
                json = content.substring(start, end).trim();
            }
        } else if (content.contains("```")) {
            int start = content.indexOf("```") + 3;
            int end = content.indexOf("```", start);
            if (end > start) {
                json = content.substring(start, end).trim();
            }
        }
        json = json.trim();

        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> result = JsonUtil.fromJson(json, Map.class);
            if (result != null) {
                String type = (String) result.get("type");
                String text = (String) result.get("content");
                if (text == null) text = (String) result.get("task");
                if (text == null) text = "";

                if ("plan".equals(type)) {
                    return ChatResult.planTask(text);
                }
                return ChatResult.reply(text);
            }
        } catch (Exception ignored) {
        }

        // 无法解析 JSON，当作普通聊天回复
        return ChatResult.reply(content);
    }

    private String buildPrompt(String memorySummary, String userPrompt) {
        StringBuilder sb = new StringBuilder();
        if (!memorySummary.isEmpty()) {
            sb.append("## 对话记忆摘要\n").append(memorySummary).append("\n\n");
        }
        sb.append("## 用户输入\n").append(userPrompt);
        return sb.toString();
    }

    private static final String SYSTEM_PROMPT = ""
            + "你是一个智能助手，负责处理用户的请求。请严格按以下规则判断并输出固定格式的 JSON。\n\n"
            + "**需要规划执行的情况**（输出 type 为 plan）：\n"
            + "- 需要修改代码、创建文件、编辑文件\n"
            + "- 需要多步骤协作的任务（如重构、新增功能）\n"
            + "- 需要读取文件或搜索项目内容的操作\n"
            + "- 涉及代码分析、Bug 修复等需要工具支持的任务\n\n"
            + "**直接回复的情况**（输出 type 为 chat）：\n"
            + "- 简单的问候、闲聊\n"
            + "- 一般性的知识问答、咨询\n"
            + "- 用户仅需解释说明、不需要实际操作\n"
            + "- 用户询问功能或概念\n\n"
            + "## 输出格式（必须严格遵循）\n"
            + "```json\n"
            + "// 需要执行任务时：\n"
            + "{\"type\": \"plan\", \"task\": \"用户任务的详细描述\"}\n\n"
            + "// 直接回复时：\n"
            + "{\"type\": \"chat\", \"content\": \"你的回复内容\"}\n"
            + "```";
}
