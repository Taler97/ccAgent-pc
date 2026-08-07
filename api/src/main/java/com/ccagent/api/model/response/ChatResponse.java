package com.ccagent.api.model.response;

import com.ccagent.api.model.request.ToolCall;

import java.util.List;

/**
 * LLM 响应
 * <p>大语言模型的同步调用响应。</p>
 */
public class ChatResponse {

    private String content;
    private List<ToolCall> toolCalls;
    private int totalTokens;
    private int cachedTokens;
    private String finishReason;
    // DeepSeek 思考模式返回的思维链内容（与 content 同级）
    private String reasoningContent;

    public ChatResponse() {}

    public ChatResponse(String content, List<ToolCall> toolCalls, int totalTokens,
                        int cachedTokens, String finishReason) {
        this.content = content;
        this.toolCalls = toolCalls;
        this.totalTokens = totalTokens;
        this.cachedTokens = cachedTokens;
        this.finishReason = finishReason;
    }

    public String getContent() { return content; }
    public void setContent(String content) { this.content = content; }
    public List<ToolCall> getToolCalls() { return toolCalls; }
    public void setToolCalls(List<ToolCall> toolCalls) { this.toolCalls = toolCalls; }
    public int getTotalTokens() { return totalTokens; }
    public void setTotalTokens(int totalTokens) { this.totalTokens = totalTokens; }
    public int getCachedTokens() { return cachedTokens; }
    public void setCachedTokens(int cachedTokens) { this.cachedTokens = cachedTokens; }
    public String getFinishReason() { return finishReason; }
    public void setFinishReason(String finishReason) { this.finishReason = finishReason; }
    public String getReasoningContent() { return reasoningContent; }
    public void setReasoningContent(String reasoningContent) { this.reasoningContent = reasoningContent; }
}
