package com.ccagent.core.orchestration.repository.model;

import com.ccagent.api.model.plan.Plan;

import java.time.Instant;
import java.util.List;

public class PlanRecord {
    private String sessionId;
    private int version;
    private Plan plan;
    private String overallStatus;
    private String completedStepIds;
    private Instant createdAt;
    private Instant updatedAt;
    private List<StepRecord> steps;

    public String getSessionId() { return sessionId; }
    public void setSessionId(String sessionId) { this.sessionId = sessionId; }
    public int getVersion() { return version; }
    public void setVersion(int version) { this.version = version; }
    public Plan getPlan() { return plan; }
    public void setPlan(Plan plan) { this.plan = plan; }
    public String getOverallStatus() { return overallStatus; }
    public void setOverallStatus(String overallStatus) { this.overallStatus = overallStatus; }
    public String getCompletedStepIds() { return completedStepIds; }
    public void setCompletedStepIds(String completedStepIds) { this.completedStepIds = completedStepIds; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
    public List<StepRecord> getSteps() { return steps; }
    public void setSteps(List<StepRecord> steps) { this.steps = steps; }
}
