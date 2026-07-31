package com.ccagent.core.governance.isolation;

import com.ccagent.api.model.plan.Plan;

import java.util.function.Supplier;

/**
 * V1.0 空实现：直接透传，不做隔离
 */
public class NoOpIsolator implements ExecutionIsolator {

    @Override
    public IsolationContext prepare(String seqId, Plan plan) {
        return new IsolationContext(seqId);
    }

    @Override
    public <T> T execute(IsolationContext context, Supplier<T> action) {
        return action.get();
    }

    @Override
    public void commit(IsolationContext context) {
        // no-op
    }

    @Override
    public void discard(IsolationContext context) {
        // no-op
    }
}
