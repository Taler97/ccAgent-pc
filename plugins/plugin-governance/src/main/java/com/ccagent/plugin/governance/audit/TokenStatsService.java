package com.ccagent.plugin.governance.audit;

import com.ccagent.core.event.LlmCallCompletedEvent;
import com.ccagent.kernel.event.EventBus;
import com.ccagent.kernel.event.Subscription;
import com.ccagent.core.plugin.ListenerExtensionPoint;
import com.ccagent.infrastructure.config.AppConfig;
import com.ccagent.kernel.plugin.PluginContext;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Token 统计与缓存命中服务
 * <p>统计 Token 消耗与缓存命中，提供会话级、对话级统计数据。</p>
 */
public class TokenStatsService implements ListenerExtensionPoint {

    private boolean enabled;
    private double inputPricePerMillion;
    private double cachedInputPricePerMillion;
    private double outputPricePerMillion;

    /** 会话级统计：sessionId → stats */
    private final ConcurrentHashMap<String, SessionTokenStats> sessionStats = new ConcurrentHashMap<>();
    /** 对话级统计：seqId → stats */
    private final ConcurrentHashMap<String, DialogTokenStats> dialogStats = new ConcurrentHashMap<>();
    private final List<Subscription> subscriptions = new ArrayList<>();

    public TokenStatsService() {}

    @Override
    public void init(PluginContext context) {
        AppConfig config = context.getService(AppConfig.class);
        AppConfig.GovernanceConfig govConfig = config.getGovernance();
        this.enabled = govConfig.isTokenStatsEnabled();
        AppConfig.PricingConfig pricing = govConfig.getPricing();
        if (pricing != null) {
            this.inputPricePerMillion = pricing.getInputPerMillion();
            this.cachedInputPricePerMillion = pricing.getCachedInputPerMillion();
            this.outputPricePerMillion = pricing.getOutputPerMillion();
        }

        EventBus eventBus = context.getService(EventBus.class);
        if (eventBus != null) {
            registerOnEventBus(eventBus);
        }
    }

    public TokenStatsService(boolean enabled, double inputPricePerMillion,
                              double cachedInputPricePerMillion, double outputPricePerMillion) {
        this.enabled = enabled;
        this.inputPricePerMillion = inputPricePerMillion;
        this.cachedInputPricePerMillion = cachedInputPricePerMillion;
        this.outputPricePerMillion = outputPricePerMillion;
    }

    public void registerOnEventBus(EventBus eventBus) {
        subscriptions.add(eventBus.subscribeAsync(LlmCallCompletedEvent.class, event -> {
            recordLlmCall(event.getSessionId(), event.getSeqId() != null ? event.getSeqId() : "",
                    event.getInputTokens() + event.getOutputTokens(), event.getCachedInputTokens());
        }));
    }

    public void unregisterAll() {
        for (Subscription sub : subscriptions) {
            sub.unsubscribe();
        }
        subscriptions.clear();
    }

    /**
     * 记录一次 LLM 调用的 Token 数据
     */
    public void recordLlmCall(String sessionId, String seqId, long totalTokens, long cachedTokens) {
        if (!enabled) return;

        // 更新会话统计
        sessionStats.computeIfAbsent(sessionId, k -> new SessionTokenStats())
                .addCall(totalTokens, cachedTokens);

        // 更新对话统计
        dialogStats.computeIfAbsent(seqId, k -> new DialogTokenStats())
                .addTokens(totalTokens, cachedTokens);
    }

    /**
     * 获取会话级统计
     */
    public SessionTokenStats getSessionStats(String sessionId) {
        return sessionStats.getOrDefault(sessionId, new SessionTokenStats());
    }

    /**
     * 获取对话级统计
     */
    public DialogTokenStats getDialogStats(String seqId) {
        return dialogStats.getOrDefault(seqId, new DialogTokenStats());
    }

    /**
     * 对话结束时汇总（写入审计日志前的快照）
     */
    public Map<String, Object> recordDialogSummary(String seqId, String sessionId) {
        DialogTokenStats dialog = dialogStats.get(seqId);
        if (dialog == null) return Map.of();

        return Map.of(
                "totalTokens", dialog.totalTokens,
                "cachedTokens", dialog.cachedInputTokens,
                "cacheHitRate", dialog.getCacheHitRate(),
                "estimatedCost", dialog.estimateCost(inputPricePerMillion,
                        cachedInputPricePerMillion, outputPricePerMillion)
        );
    }

    /**
     * 会话级 Token 统计
     */
    public static class SessionTokenStats {
        private long totalInputTokens;
        private long cachedInputTokens;
        private long totalOutputTokens;
        private long totalTokens;
        private int llmCallCount;

        public synchronized void addCall(long totalTokens, long cachedTokens) {
            this.totalTokens += totalTokens;
            this.cachedInputTokens += cachedTokens;
            this.llmCallCount++;
        }

        public double getCacheHitRate() {
            return totalTokens > 0 ? (double) cachedInputTokens / totalTokens : 0.0;
        }

        public long getTotalInputTokens() { return totalInputTokens; }
        public long getCachedInputTokens() { return cachedInputTokens; }
        public long getTotalOutputTokens() { return totalOutputTokens; }
        public long getTotalTokens() { return totalTokens; }
        public int getLlmCallCount() { return llmCallCount; }
    }

    /**
     * 对话级 Token 统计
     */
    public static class DialogTokenStats {
        private long totalTokens;
        private long cachedInputTokens;

        public synchronized void addTokens(long total, long cached) {
            this.totalTokens += total;
            this.cachedInputTokens += cached;
        }

        public double getCacheHitRate() {
            return totalTokens > 0 ? (double) cachedInputTokens / totalTokens : 0.0;
        }

        public double estimateCost(double inputPrice, double cachedPrice, double outputPrice) {
            long uncachedInput = totalTokens - cachedInputTokens;
            double uncachedCost = (uncachedInput / 1_000_000.0) * inputPrice;
            double cachedCost = (cachedInputTokens / 1_000_000.0) * cachedPrice;
            return uncachedCost + cachedCost;
        }

        public long getTotalTokens() { return totalTokens; }
        public long getCachedInputTokens() { return cachedInputTokens; }
    }
}