package com.ccagent.core.orchestration.repository.model;

import java.time.Instant;

public class StepRecord {
    private int stepId;
    private String expertName;
    private String task;
    private String status;
    private String errorMessage;
    private Instant startedAt;
    private Instant completedAt;

    public int getStepId() { return stepId; }
    public void setStepId(int stepId) { this.stepId = stepId; }
    public String getExpertName() { return expertName; }
    public void setExpertName(String expertName) { this.expertName = expertName; }
    public String getTask() { return task; }
    public void setTask(String task) { this.task = task; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getErrorMessage() { return errorMessage; }
    public void setErrorMessage(String errorMessage) { this.errorMessage = errorMessage; }
    public Instant getStartedAt() { return startedAt; }
    public void setStartedAt(Instant startedAt) { this.startedAt = startedAt; }
    public Instant getCompletedAt() { return completedAt; }
    public void setCompletedAt(Instant completedAt) { this.completedAt = completedAt; }
}
