package com.ccagent.core.event;

import com.ccagent.kernel.event.Event;

import java.util.List;

public abstract class StepEvent extends Event {

    protected final String seqId;
    protected final String sessionId;
    protected final int stepId;
    protected final int totalSteps;
    protected final String expertName;
    protected final List<String> affectedFiles;

    protected StepEvent(String seqId, String sessionId, int stepId, int totalSteps,
                         String expertName, List<String> affectedFiles) {
        this.seqId = seqId;
        this.sessionId = sessionId;
        this.stepId = stepId;
        this.totalSteps = totalSteps;
        this.expertName = expertName;
        this.affectedFiles = affectedFiles;
        putMetadata("seqId", seqId);
        putMetadata("sessionId", sessionId);
        putMetadata("stepId", stepId);
        putMetadata("totalSteps", totalSteps);
        putMetadata("expertName", expertName != null ? expertName : "");
    }

    public String getSeqId() { return seqId; }
    public String getSessionId() { return sessionId; }
    public int getStepId() { return stepId; }
    public int getTotalSteps() { return totalSteps; }
    public String getExpertName() { return expertName; }
    public List<String> getAffectedFiles() { return affectedFiles; }
}
