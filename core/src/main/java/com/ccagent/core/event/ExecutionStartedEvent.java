package com.ccagent.core.event;

public class ExecutionStartedEvent extends ExecutionEvent {

    private final String prompt;

    public ExecutionStartedEvent(String seqId, String sessionId, String prompt) {
        super(seqId, sessionId);
        this.prompt = prompt;
        putMetadata("prompt", prompt);
    }

    public String getPrompt() {
        return prompt;
    }
}
