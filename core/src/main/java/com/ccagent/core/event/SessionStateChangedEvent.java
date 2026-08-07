package com.ccagent.core.event;

import com.ccagent.kernel.event.Event;

/**
 * 会话状态变更事件（事件驱动状态机）
 * <p>由 {@code SessionStateMachine} 在每次状态转移时发布，
 * 供审计/观测/测试使用。关键路径同步投递，审计类消费方可按需订阅。</p>
 */
public class SessionStateChangedEvent extends Event {

    private final String sessionId;
    private final String fromState;
    private final String toState;
    private final String seqId;

    public SessionStateChangedEvent(String sessionId, String fromState, String toState, String seqId) {
        this.sessionId = sessionId;
        this.fromState = fromState;
        this.toState = toState;
        this.seqId = seqId != null ? seqId : "";
        putMetadata("sessionId", sessionId);
        putMetadata("from", fromState);
        putMetadata("to", toState);
        putMetadata("seqId", this.seqId);
    }

    public String getSessionId() { return sessionId; }
    public String getFromState() { return fromState; }
    public String getToState() { return toState; }
    public String getSeqId() { return seqId; }
}
