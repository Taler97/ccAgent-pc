package com.ccagent.core.governance.isolation;

import com.ccagent.api.model.plan.Plan;

import java.util.function.Supplier;

/**
 * 隔离执行器接口
 * <p>为未来扩展准备的占位接口，V1.0 不实现具体逻辑。</p>
 */
public interface ExecutionIsolator {

    /**
     * 为当前 seqId 创建隔离执行环境
     */
    IsolationContext prepare(String seqId, Plan plan);

    /**
     * 在隔离环境中执行操作
     */
    <T> T execute(IsolationContext context, Supplier<T> action);

    /**
     * 提交隔离环境的变更（合并到真实工作区）
     */
    void commit(IsolationContext context);

    /**
     * 丢弃隔离环境（回退）
     */
    void discard(IsolationContext context);
}
