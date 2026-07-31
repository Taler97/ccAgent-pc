package com.ccagent.core.event;

public class ExecutionCompletedEvent extends ExecutionEvent {

    private final String summary;

    public ExecutionCompletedEvent(String seqId, String sessionId, String summary) {
        super(seqId, sessionId);
        this.summary = summary;
        putMetadata("summary", summary != null ? summary : "");
    }

    public String getSummary() {
        return summary;
    }
}
