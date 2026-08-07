package com.ccagent.core.memory;

import com.ccagent.api.model.memory.ConversationTurn;
import com.ccagent.api.model.request.ChatRequest;
import com.ccagent.api.model.response.ChatResponse;
import com.ccagent.api.port.LlmPort;
import com.ccagent.infrastructure.constant.Constants;
import com.ccagent.infrastructure.util.FileUtil;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 记忆压缩器（纯计算部分）
 * <p>调用 LLM 将最早的部分轮次压缩成摘要。</p>
 * <p>本类不负责数据库持久化，持久化由调用方（如 H2MemoryAdapter）负责。</p>
 */
public class Compressor {

    private final LlmPort llmPort;

    public Compressor(LlmPort llmPort) {
        this.llmPort = llmPort;
    }

    /**
     * 压缩最早 1/3 的轮次到摘要中（纯计算）
     *
     * @param recentTurns    当前最近轮次列表
     * @param currentSummary 当前摘要
     * @return 压缩结果（新摘要 + 剩余轮次 + 总轮次数）
     */
    public CompressResult compress(List<ConversationTurn> recentTurns, String currentSummary) {
        // 无轮次可压缩时返回 null，由调用方决定处理（与 LLM 失败语义一致）
        if (recentTurns == null || recentTurns.isEmpty()) {
            return null;
        }
        int compressCount = Math.max(1, recentTurns.size() / 3);
        List<ConversationTurn> toCompress = recentTurns.subList(0, compressCount);
        List<ConversationTurn> remaining = recentTurns.subList(compressCount, recentTurns.size());

        // 构建压缩 Prompt
        String systemPrompt = loadCompressorPrompt();
        String turnsText = toCompress.stream()
                .map(t -> t.getRole() + ": " + t.getContent())
                .collect(Collectors.joining("\n---\n"));

        ChatRequest request = new ChatRequest(
                systemPrompt,
                "请压缩以下对话轮次:\n" + turnsText,
                null,
                false,
                2000,
                0.3,
                30
        );

        try {
            ChatResponse response = llmPort.chat(request);
            String newSummary = response.getContent();

            // 合并摘要
            String mergedSummary;
            if (currentSummary != null && !currentSummary.isEmpty()) {
                mergedSummary = currentSummary + "\n" + newSummary;
            } else {
                mergedSummary = newSummary;
            }

            // 截断摘要（超长从开头截断）
            int maxTokens = 2000;
            if (estimateTokens(mergedSummary) > maxTokens) {
                mergedSummary = truncateFromStart(mergedSummary, maxTokens);
            }

            return new CompressResult(mergedSummary, remaining, remaining.size());

        } catch (Exception e) {
            // 压缩失败：返回空结果，由调用方决定如何处理
            return null;
        }
    }

    private String loadCompressorPrompt() {
        Path promptPath = Paths.get(Constants.COMPRESSOR_PROMPT);
        if (FileUtil.exists(promptPath)) {
            return FileUtil.readString(promptPath);
        }
        return "你是一个对话记忆压缩助手。请将以下对话轮次压缩为简洁的摘要，"
                + "保留关键决策、代码变更和用户偏好。";
    }

    private int estimateTokens(String text) {
        if (text == null) return 0;
        int tokens = 0;
        for (char c : text.toCharArray()) {
            tokens += (c > 0x7F) ? 2 : 1; // 中文 ≈ 2token，英文 ≈ 1token
        }
        return tokens / 4; // 粗略估算
    }

    private String truncateFromStart(String text, int maxTokens) {
        // 从开头截断：保留最后的部分
        int targetLen = maxTokens * 4;
        if (text.length() <= targetLen) return text;
        return "..." + text.substring(text.length() - targetLen);
    }

    /**
     * 压缩结果
     */
    public record CompressResult(String summary, List<ConversationTurn> remaining, int totalTurns) {
    }
}
