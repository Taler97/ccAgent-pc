package com.ccagent.api.model.memory;

import java.util.List;

/**
 * 结构化记忆
 * <p>一次性返回会话的压缩摘要 + 最近轮次。</p>
 */
public class StructuredContext {

    private String summary;
    private List<ConversationTurn> recentTurns;
    private int totalTurns;

    public StructuredContext() {}

    public StructuredContext(String summary, List<ConversationTurn> recentTurns, int totalTurns) {
        this.summary = summary;
        this.recentTurns = recentTurns;
        this.totalTurns = totalTurns;
    }

    public String getSummary() { return summary; }
    public void setSummary(String summary) { this.summary = summary; }
    public List<ConversationTurn> getRecentTurns() { return recentTurns; }
    public void setRecentTurns(List<ConversationTurn> recentTurns) { this.recentTurns = recentTurns; }
    public int getTotalTurns() { return totalTurns; }
    public void setTotalTurns(int totalTurns) { this.totalTurns = totalTurns; }
}
