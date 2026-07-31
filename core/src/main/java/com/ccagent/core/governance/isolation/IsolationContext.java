package com.ccagent.core.governance.isolation;

/**
 * 隔离执行上下文
 */
public class IsolationContext {

    private final String seqId;

    public IsolationContext(String seqId) {
        this.seqId = seqId;
    }

    public String getSeqId() {
        return seqId;
    }
}
