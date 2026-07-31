package com.ccagent.api.model.plan;

import java.util.List;

/**
 * 执行计划
 * <p>Planner 将用户意图转化为的结构化执行计划。</p>
 */
public class Plan {

    private String summary;
    private List<Step> steps;
    private List<String> estimatedImpact;

    public Plan() {}

    public Plan(String summary, List<Step> steps, List<String> estimatedImpact) {
        this.summary = summary;
        this.steps = steps;
        this.estimatedImpact = estimatedImpact;
    }

    public String getSummary() { return summary; }
    public void setSummary(String summary) { this.summary = summary; }
    public List<Step> getSteps() { return steps; }
    public void setSteps(List<Step> steps) { this.steps = steps; }
    public List<String> getEstimatedImpact() { return estimatedImpact; }
    public void setEstimatedImpact(List<String> estimatedImpact) { this.estimatedImpact = estimatedImpact; }
}
