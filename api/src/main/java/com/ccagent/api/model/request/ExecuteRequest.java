package com.ccagent.api.model.request;

import java.util.List;
import java.util.Map;

/**
 * 执行请求
 * <p>来自 H5 的用户自然语言指令。</p>
 */
public class ExecuteRequest {

    private String workspaceId;
    private String sessionId;
    private String prompt;
    private boolean autoMode;
    private List<Map<String, String>> history;
    /** 会话级启用的 skill.md 名称列表（模式 A，可空） */
    private List<String> skills;

    public ExecuteRequest() {}

    public ExecuteRequest(String workspaceId, String sessionId, String prompt, boolean autoMode) {
        this.workspaceId = workspaceId;
        this.sessionId = sessionId;
        this.prompt = prompt;
        this.autoMode = autoMode;
    }

    public static ExecuteRequest local(String sessionId, String prompt) {
        return new ExecuteRequest(sessionId, sessionId, prompt, true);
    }

    public String getWorkspaceId() { return workspaceId; }
    public void setWorkspaceId(String workspaceId) { this.workspaceId = workspaceId; }
    public String getSessionId() { return sessionId; }
    public void setSessionId(String sessionId) { this.sessionId = sessionId; }
    public String getPrompt() { return prompt; }
    public void setPrompt(String prompt) { this.prompt = prompt; }
    public boolean isAutoMode() { return autoMode; }
    public void setAutoMode(boolean autoMode) { this.autoMode = autoMode; }
    public List<Map<String, String>> getHistory() { return history; }
    public void setHistory(List<Map<String, String>> history) { this.history = history; }
    public List<String> getSkills() { return skills; }
    public void setSkills(List<String> skills) { this.skills = skills; }
}
