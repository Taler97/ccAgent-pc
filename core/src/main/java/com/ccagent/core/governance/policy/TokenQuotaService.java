package com.ccagent.core.governance.policy;

import com.ccagent.exception.base.ErrorCode;
import com.ccagent.exception.governance.GovernanceException;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Token 配额计数器
 * <p>维护会话级 Token 配额计数，调用前检查是否超限。
 * 有状态（按 sessionId 维护累计值），无 IO（纯内存计数）。</p>
 */
public class TokenQuotaService {

    private final long maxTokensPerSession;
    private final Map<String, Long> sessionUsage = new ConcurrentHashMap<>();

    public TokenQuotaService(long maxTokensPerSession) {
        this.maxTokensPerSession = maxTokensPerSession;
    }

    /**
     * 检查配额是否超限
     *
     * @param sessionId       会话标识
     * @param estimatedTokens 预估本次调用将消耗的 Token 数
     * @throws GovernanceException 配额超限
     */
    public void checkQuota(String sessionId, long estimatedTokens) {
        Long currentUsage = sessionUsage.get(sessionId);
        long usage = currentUsage != null ? currentUsage : 0L;
        if (usage + estimatedTokens > maxTokensPerSession) {
            throw new GovernanceException(ErrorCode.QUOTA_EXCEEDED,
                    "Token 配额超限", usage, maxTokensPerSession);
        }
    }

    /**
     * 原子地检查和记录 Token 消耗
     * <p>使用 compute 确保检查和记录为原子操作，避免并行下的竞态条件。</p>
     *
     * @param sessionId 会话标识
     * @param tokens    本次消耗的 Token 数
     * @return true 若配额充足并已记录，false 若配额超限
     */
    public boolean tryRecord(String sessionId, long tokens) {
        Long newUsage = sessionUsage.compute(sessionId, (k, v) -> {
            long current = v != null ? v : 0L;
            if (current + tokens > maxTokensPerSession) {
                return v; // 配额超限，不修改
            }
            return current + tokens;
        });
        return newUsage != null && newUsage <= maxTokensPerSession;
    }

    /**
     * 记录 Token 消耗（非原子，由调用方保证线程安全）
     *
     * @param sessionId 会话标识
     * @param tokens    本次消耗的 Token 数
     */
    public void recordUsage(String sessionId, long tokens) {
        sessionUsage.merge(sessionId, tokens, Long::sum);
    }

    /**
     * 获取当前会话已消耗的 Token 数
     */
    public long getCurrentUsage(String sessionId) {
        return sessionUsage.getOrDefault(sessionId, 0L);
    }

    /**
     * 获取会话配额上限
     */
    public long getMaxTokensPerSession() {
        return maxTokensPerSession;
    }

    /**
     * 重置会话计数（创建新会话时调用）
     */
    public void resetSession(String sessionId) {
        sessionUsage.remove(sessionId);
    }
}
