package com.ccagent.core.event;

public class ExecutionFailedEvent extends ExecutionEvent {

    private final String errorMessage;
    private final String errorCode;

    public ExecutionFailedEvent(String seqId, String sessionId, String errorCode, String errorMessage) {
        super(seqId, sessionId);
        this.errorCode = errorCode;
        this.errorMessage = errorMessage;
        putMetadata("errorCode", errorCode != null ? errorCode : "");
        putMetadata("errorMessage", errorMessage != null ? errorMessage : "");
    }

    public String getErrorCode() {
        return errorCode;
    }

    public String getErrorMessage() {
        return errorMessage;
    }
}
