package com.ccagent.core.event;

import com.ccagent.api.model.plan.Plan;

/**
 * Plan 执行开始事件
 * <p>Plan 确认后、Dispatcher 执行前发布一次，用于触发整批备份等"执行前一次性"的治理动作，
 * 避免每个步骤的 StepStartedEvent 各自重复做文件 IO。</p>
 */
public class PlanExecutionStartedEvent extends ExecutionEvent {

    private final Plan plan;
    private final String workspaceRoot;

    public PlanExecutionStartedEvent(String seqId, String sessionId, Plan plan, String workspaceRoot) {
        super(seqId, sessionId);
        this.plan = plan;
        this.workspaceRoot = workspaceRoot;
        putMetadata("stepCount", plan != null && plan.getSteps() != null ? plan.getSteps().size() : 0);
    }

    public Plan getPlan() {
        return plan;
    }

    public String getWorkspaceRoot() {
        return workspaceRoot;
    }
}
