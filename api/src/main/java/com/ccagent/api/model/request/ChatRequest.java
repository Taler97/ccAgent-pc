package com.ccagent.api.model.request;

import com.ccagent.api.model.tool.ToolDefinition;

import java.util.List;
import java.util.Map;

/**
 * LLM 请求
 * <p>封装对大语言模型的完整请求参数。</p>
 */
public class ChatRequest {

    private String systemPrompt;
    private String userPrompt;
    private List<ToolDefinition> tools;
    private boolean allowTools;
    private int maxTokens;
    private Double temperature;
    private int timeoutSeconds;
    private List<Map<String, String>> historyMessages;

    public ChatRequest() {}

    public ChatRequest(String systemPrompt, String userPrompt, List<ToolDefinition> tools,
                       boolean allowTools, int maxTokens, Double temperature, int timeoutSeconds) {
        this(systemPrompt, userPrompt, tools, allowTools, maxTokens, temperature, timeoutSeconds, null);
    }

    public ChatRequest(String systemPrompt, String userPrompt, List<ToolDefinition> tools,
                       boolean allowTools, int maxTokens, Double temperature, int timeoutSeconds,
                       List<Map<String, String>> historyMessages) {
        this.systemPrompt = systemPrompt;
        this.userPrompt = userPrompt;
        this.tools = tools;
        this.allowTools = allowTools;
        this.maxTokens = maxTokens;
        this.temperature = temperature;
        this.timeoutSeconds = timeoutSeconds;
        this.historyMessages = historyMessages;
    }

    public String getSystemPrompt() { return systemPrompt; }
    public void setSystemPrompt(String systemPrompt) { this.systemPrompt = systemPrompt; }
    public String getUserPrompt() { return userPrompt; }
    public void setUserPrompt(String userPrompt) { this.userPrompt = userPrompt; }
    public List<ToolDefinition> getTools() { return tools; }
    public void setTools(List<ToolDefinition> tools) { this.tools = tools; }
    public boolean isAllowTools() { return allowTools; }
    public void setAllowTools(boolean allowTools) { this.allowTools = allowTools; }
    public int getMaxTokens() { return maxTokens; }
    public void setMaxTokens(int maxTokens) { this.maxTokens = maxTokens; }
    public Double getTemperature() { return temperature; }
    public void setTemperature(Double temperature) { this.temperature = temperature; }
    public int getTimeoutSeconds() { return timeoutSeconds; }
    public void setTimeoutSeconds(int timeoutSeconds) { this.timeoutSeconds = timeoutSeconds; }
    public List<Map<String, String>> getHistoryMessages() { return historyMessages; }
    public void setHistoryMessages(List<Map<String, String>> historyMessages) { this.historyMessages = historyMessages; }
}
