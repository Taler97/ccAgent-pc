package com.ccagent.core.orchestration.agent;

/**
 * ChatAgent 的决策结果
 * <p>封装主 Agent 的决策：直接回复聊天，还是需要规划执行。</p>
 */
public class ChatResult {

    private final String type;    // "chat" 或 "plan"
    private final String content; // 回复内容（chat）或任务描述（plan）

    private ChatResult(String type, String content) {
        this.type = type;
        this.content = content;
    }

    /** 创建聊天回复结果 */
    public static ChatResult reply(String content) {
        return new ChatResult("chat", content);
    }

    /** 创建规划任务结果 */
    public static ChatResult planTask(String taskDescription) {
        return new ChatResult("plan", taskDescription);
    }

    public boolean isReply() { return "chat".equals(type); }
    public boolean isPlan()  { return "plan".equals(type); }

    public String getType() { return type; }

    /**
     * 获取回复内容（type=chat 时）
     * @return 回复文本
     */
    public String getReplyContent() { return content; }

    /**
     * 获取任务描述（type=plan 时）
     * @return 任务描述
     */
    public String getTaskDescription() { return content; }
}
