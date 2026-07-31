package com.ccagent.plugin.governance.llmproxy;

import com.ccagent.api.model.request.ChatRequest;
import com.ccagent.api.model.response.ChatResponse;
import com.ccagent.api.model.response.StreamChunk;
import com.ccagent.api.port.LlmPort;
import com.ccagent.api.port.StreamObserver;
import com.ccagent.core.event.LlmCallCompletedEvent;
import com.ccagent.core.governance.context.ExecutionContextHolder;
import com.ccagent.core.governance.policy.TokenQuotaService;
import com.ccagent.core.orchestration.context.ExecutionContext;
import com.ccagent.core.sync.LocalDataRepository;
import com.ccagent.exception.llm.LlmException;
import com.ccagent.kernel.event.EventBus;

import java.util.logging.Logger;

/**
 * Token 追踪 LLM 代理
 * <p>实现 LlmPort，包裹真正的 LLM 适配器，作为透明代理：</p>
 * <ul>
 *   <li>调用前：检查 Token 配额</li>
 *   <li>调用后：提取 totalTokens 和 cachedTokens，更新配额 + 统计缓存</li>
 *   <li>上层完全无感知</li>
 * </ul>
 */
public class TokenTrackingLlmProxy implements LlmPort {

    private static final Logger LOG = Logger.getLogger(TokenTrackingLlmProxy.class.getName());
    private static final String UNKNOWN_SESSION = "session-unknown";

    private final LlmPort realLlmPort;
    private final TokenQuotaService quotaService;
    private final LocalDataRepository localDataRepository;
    private final EventBus eventBus;

    public TokenTrackingLlmProxy(LlmPort realLlmPort, TokenQuotaService quotaService,
                                  EventBus eventBus) {
        this(realLlmPort, quotaService, null, eventBus);
    }

    public TokenTrackingLlmProxy(LlmPort realLlmPort, TokenQuotaService quotaService,
                                  LocalDataRepository localDataRepository,
                                  EventBus eventBus) {
        this.realLlmPort = realLlmPort;
        this.quotaService = quotaService;
        this.localDataRepository = localDataRepository;
        this.eventBus = eventBus;
    }

    @Override
    public ChatResponse chat(ChatRequest request) {
        String sessionId = extractSessionId(request);

        // 预处理：预估 Token 并检查配额
        long estimatedTokens = estimateTokens(request);
        quotaService.checkQuota(sessionId, estimatedTokens);

        // 调用真实 LLM
        long startTime = System.currentTimeMillis();
        ChatResponse response = realLlmPort.chat(request);
        long durationMs = System.currentTimeMillis() - startTime;

        // 后处理：更新配额
        int totalTokens = response.getTotalTokens();
        int cachedTokens = response.getCachedTokens();
        int outputTokens = totalTokens - cachedTokens;
        quotaService.recordUsage(sessionId, totalTokens);

        String seqId = extractSeqId();
        // 统计统一走事件总线（TokenStatsService 监听 LlmCallCompletedEvent），保证单实例单次计数
        eventBus.publish(new LlmCallCompletedEvent(
                sessionId, seqId, "",
                totalTokens - cachedTokens, cachedTokens, outputTokens,
                0.0, durationMs));

        // 同步写入本地 Token 统计（供离线模式和云同步使用）
        if (localDataRepository != null) {
            try {
                localDataRepository.saveTokenStats(sessionId, totalTokens, cachedTokens, 0.0);
            } catch (Exception ignored) {}
        }

        return response;
    }

    @Override
    public void chatStream(ChatRequest request, StreamObserver<StreamChunk> observer) {
        String sessionId = extractSessionId(request);
        String seqId = extractSeqId();

        long estimatedTokens = estimateTokens(request);
        quotaService.checkQuota(sessionId, estimatedTokens);

        int[] accumulated = new int[]{0, 0};
        long startTime = System.currentTimeMillis();

        StreamObserver<StreamChunk> wrappedObserver = new StreamObserver<>() {
            @Override
            public void onNext(StreamChunk chunk) {
                if (chunk.getFinishReason() != null && !chunk.getFinishReason().isEmpty()) {
                    accumulated[0] = chunk.getTotalTokens();
                    accumulated[1] = chunk.getCachedTokens();
                }
                observer.onNext(chunk);
            }

            @Override
            public void onError(Throwable error) {
                observer.onError(error);
            }

            @Override
            public void onComplete() {
                int totalTokens = accumulated[0];
                int cachedTokens = accumulated[1];
                if (totalTokens > 0) {
                    long durationMs = System.currentTimeMillis() - startTime;
                    int outputTokens = totalTokens - cachedTokens;
                    quotaService.recordUsage(sessionId, totalTokens);
                    eventBus.publish(new LlmCallCompletedEvent(
                            sessionId, seqId, "",
                            totalTokens - cachedTokens, cachedTokens, outputTokens,
                            0.0, durationMs));
                    if (localDataRepository != null) {
                        try {
                            localDataRepository.saveTokenStats(sessionId, totalTokens, cachedTokens, 0.0);
                        } catch (Exception ignored) {}
                    }
                }
                observer.onComplete();
            }
        };

        realLlmPort.chatStream(request, wrappedObserver);
    }

    @Override
    public boolean cancel(String requestId) {
        return realLlmPort.cancel(requestId);
    }

    /**
     * 从执行上下文中提取 sessionId
     * <p>优先从 ThreadLocal 获取（与 GovernanceGateway 入口设置的 sessionId 一致），
     * 获取不到时降级为 session-unknown 并记录告警，避免配额统计张冠李戴。</p>
     */
    private String extractSessionId(ChatRequest request) {
        ExecutionContext ctx = ExecutionContextHolder.getContext();
        if (ctx != null && ctx.getSessionId() != null) {
            return ctx.getSessionId();
        }
        LOG.warning("执行上下文中无 sessionId，配额将记录到 session-unknown");
        return UNKNOWN_SESSION;
    }

    private String extractSeqId() {
        ExecutionContext ctx = ExecutionContextHolder.getContext();
        if (ctx != null && ctx.getSeqId() != null) {
            return ctx.getSeqId();
        }
        return "";
    }

    /**
     * 粗略预估 Token 数（按字符数 / 4 估算）
     */
    private long estimateTokens(ChatRequest request) {
        long chars = 0;
        if (request.getSystemPrompt() != null) chars += request.getSystemPrompt().length();
        if (request.getUserPrompt() != null) chars += request.getUserPrompt().length();
        return Math.max(1, chars / 4);
    }
}