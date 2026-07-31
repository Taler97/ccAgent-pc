package com.ccagent.api.model.memory;

import java.time.Instant;

public class SessionInfo {

    private String sessionId;
    private String workspaceId;
    private String rootPath;
    private int totalTurns;
    private String summary;
    private Instant createdAt;
    private Instant updatedAt;

    public SessionInfo() {}

    public SessionInfo(String sessionId, String workspaceId, String rootPath,
                       int totalTurns, String summary,
                       Instant createdAt, Instant updatedAt) {
        this.sessionId = sessionId;
        this.workspaceId = workspaceId;
        this.rootPath = rootPath;
        this.totalTurns = totalTurns;
        this.summary = summary;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
    }

    public String getSessionId() { return sessionId; }
    public void setSessionId(String sessionId) { this.sessionId = sessionId; }
    public String getWorkspaceId() { return workspaceId; }
    public void setWorkspaceId(String workspaceId) { this.workspaceId = workspaceId; }
    public String getRootPath() { return rootPath; }
    public void setRootPath(String rootPath) { this.rootPath = rootPath; }
    public int getTotalTurns() { return totalTurns; }
    public void setTotalTurns(int totalTurns) { this.totalTurns = totalTurns; }
    public String getSummary() { return summary; }
    public void setSummary(String summary) { this.summary = summary; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
}
