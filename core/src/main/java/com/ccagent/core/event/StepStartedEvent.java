package com.ccagent.core.event;

import java.util.List;

public class StepStartedEvent extends StepEvent {

    public StepStartedEvent(String seqId, String sessionId, int stepId, int totalSteps,
                             String expertName, List<String> affectedFiles) {
        super(seqId, sessionId, stepId, totalSteps, expertName, affectedFiles);
    }
}
