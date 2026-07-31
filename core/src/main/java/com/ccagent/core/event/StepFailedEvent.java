package com.ccagent.core.event;

import java.util.List;

public class StepFailedEvent extends StepEvent {

    private final String errorMessage;

    public StepFailedEvent(String seqId, String sessionId, int stepId, int totalSteps,
                           String expertName, List<String> affectedFiles, String errorMessage) {
        super(seqId, sessionId, stepId, totalSteps, expertName, affectedFiles);
        this.errorMessage = errorMessage;
        putMetadata("errorMessage", errorMessage != null ? errorMessage : "");
    }

    public String getErrorMessage() {
        return errorMessage;
    }
}
