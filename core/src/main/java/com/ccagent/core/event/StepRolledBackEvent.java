package com.ccagent.core.event;

import java.util.List;

public class StepRolledBackEvent extends StepEvent {

    public StepRolledBackEvent(String seqId, String sessionId, int stepId, int totalSteps,
                                String expertName, List<String> affectedFiles) {
        super(seqId, sessionId, stepId, totalSteps, expertName, affectedFiles);
    }
}
