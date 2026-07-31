package com.ccagent.api.port;

import com.ccagent.api.model.request.ChatRequest;
import com.ccagent.api.model.response.ChatResponse;
import com.ccagent.api.model.response.StreamChunk;
import com.ccagent.exception.llm.LlmException;

/**
 * 大模型调用端口
 * <p>封装对大语言模型（DeepSeek）的调用，提供同步和流式两种能力。
 * 由 adapter/llm/ 实现。</p>
 */
public interface LlmPort {

    /**
     * 同步调用，等待完整响应
     *
     * @param request LLM 请求
     * @return LLM 响应
     * @throws LlmException 超时/服务不可用/认证失败
     */
    ChatResponse chat(ChatRequest request) throws LlmException;

    /**
     * 流式调用，逐块推送
     *
     * @param request  LLM 请求
     * @param observer 逐块回调
     * @throws LlmException 超时/服务不可用/认证失败
     */
    void chatStream(ChatRequest request, StreamObserver<StreamChunk> observer) throws LlmException;

    /**
     * 取消正在进行的流式请求
     *
     * @param requestId 请求标识
     * @return true 若取消成功
     */
    boolean cancel(String requestId);
}
