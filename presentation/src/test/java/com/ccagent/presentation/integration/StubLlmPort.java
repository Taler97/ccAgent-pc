package com.ccagent.presentation.integration;

import com.ccagent.api.model.request.ChatRequest;
import com.ccagent.api.model.response.ChatResponse;
import com.ccagent.api.model.response.StreamChunk;
import com.ccagent.api.port.LlmPort;
import com.ccagent.api.port.StreamObserver;

/**
 * Stub LLM 端口：根据 systemPrompt 内容返回固定响应，避免依赖真实 DeepSeek API。
 *
 * <p>区分三种调用场景：</p>
 * <ul>
 *   <li>ChatAgent（systemPrompt 含"你是一个智能助手"）→ 返回 {"type":"plan","task":"..."} 触发规划</li>
 *   <li>PlannerExpert（systemPrompt 含"任务规划师"）→ 返回 Plan JSON</li>
 *   <li>其他 Expert（writer/researcher/summarizer）→ 返回简单文本，无 toolCalls，ReAct 循环立即退出</li>
 * </ul>
 */
public class StubLlmPort implements LlmPort {

    private final String planJson;
    private final boolean chatOnly;
    private int chatCallCount = 0;

    /**
     * @param planJson PlannerExpert 应返回的 Plan JSON 字符串
     */
    public StubLlmPort(String planJson) {
        this(planJson, false);
    }

    /**
     * @param planJson PlannerExpert 应返回的 Plan JSON 字符串
     * @param chatOnly true 时所有调用都返回 chat 类型（简单对话，不触发 Plan 执行）
     */
    public StubLlmPort(String planJson, boolean chatOnly) {
        this.planJson = planJson;
        this.chatOnly = chatOnly;
    }

    @Override
    public ChatResponse chat(ChatRequest request) {
        chatCallCount++;
        String systemPrompt = request.getSystemPrompt();
        String content;

        if (chatOnly) {
            // chat-only 模式：所有调用都返回 chat 类型
            content = "{\"type\":\"chat\",\"content\":\"你好，我是助手\"}";
        } else if (systemPrompt == null) {
            content = "已完成";
        } else if (systemPrompt.contains("智能助手")) {
            // ChatAgent 决策：返回 plan 类型，触发规划流程
            content = "{\"type\":\"plan\",\"task\":\"创建测试文件\"}";
        } else if (systemPrompt.contains("任务规划师")) {
            // PlannerExpert：返回 Plan JSON（包在 ```json 代码块中）
            content = "```json\n" + planJson + "\n```";
        } else {
            // 其他 Expert（writer/researcher/summarizer）：返回简单文本，无 toolCalls
            content = "任务已完成";
        }

        return new ChatResponse(content, null, 10, 0, "stop");
    }

    @Override
    public void chatStream(ChatRequest request, StreamObserver<StreamChunk> observer) {
        ChatResponse resp = chat(request);
        String content = resp.getContent();
        if (content != null && !content.isEmpty()) {
            observer.onNext(new StreamChunk(null, "STREAM_CHUNK", content, null));
        }
        observer.onComplete();
    }

    @Override
    public boolean cancel(String requestId) {
        return false;
    }

    public int getChatCallCount() {
        return chatCallCount;
    }
}
