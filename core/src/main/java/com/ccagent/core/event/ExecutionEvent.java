package com.ccagent.core.event;

import com.ccagent.kernel.event.Event;

public abstract class ExecutionEvent extends Event {

    protected final String seqId;
    protected final String sessionId;

    protected ExecutionEvent(String seqId, String sessionId) {
        this.seqId = seqId;
        this.sessionId = sessionId;
        putMetadata("seqId", seqId);
        putMetadata("sessionId", sessionId);
    }

    public String getSeqId() {
        return seqId;
    }

    public String getSessionId() {
        return sessionId;
    }
}
