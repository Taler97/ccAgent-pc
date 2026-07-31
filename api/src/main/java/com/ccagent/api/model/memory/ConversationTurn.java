package com.ccagent.api.model.memory;

/**
 * 对话轮次
 * <p>一条完整的用户-助手交互记录。</p>
 */
public class ConversationTurn {

    private String role;
    private String content;
    private int tokenCount;

    public ConversationTurn() {}

    public ConversationTurn(String role, String content, int tokenCount) {
        this.role = role;
        this.content = content;
        this.tokenCount = tokenCount;
    }

    public String getRole() { return role; }
    public void setRole(String role) { this.role = role; }
    public String getContent() { return content; }
    public void setContent(String content) { this.content = content; }
    public int getTokenCount() { return tokenCount; }
    public void setTokenCount(int tokenCount) { this.tokenCount = tokenCount; }
}
