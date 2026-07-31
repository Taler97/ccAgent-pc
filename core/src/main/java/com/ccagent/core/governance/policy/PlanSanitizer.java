package com.ccagent.core.governance.policy;

import com.ccagent.api.model.plan.Plan;
import com.ccagent.api.model.plan.Step;
import com.ccagent.core.orchestration.dispatcher.Dispatcher;
import com.ccagent.exception.base.ErrorCode;
import com.ccagent.exception.governance.GovernanceException;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 计划校验器
 * <p>验证 Planner 产出的 Plan 结构合法性。</p>
 *
 * <h3>校验项</h3>
 * <ul>
 *   <li>步骤数 ≤ maxPlanSteps（默认 10）</li>
 *   <li>所有 affectedFiles 均在工作区内（路径校验由外部 PolicyEnforcer 完成）</li>
 *   <li>步骤间无循环依赖</li>
 *   <li>所有 step.id 唯一</li>
 *   <li>所有 step.task 非空且长度 ≥ 3</li>
 * </ul>
 */
public class PlanSanitizer {

    private final int maxPlanSteps;

    public PlanSanitizer(int maxPlanSteps) {
        this.maxPlanSteps = maxPlanSteps;
    }

    /**
     * 校验 Plan 合法性
     *
     * @param plan 执行计划
     * @throws GovernanceException 校验失败时抛出
     */
    public void validate(Plan plan) {
        if (plan == null) {
            throw new GovernanceException(ErrorCode.PLAN_INVALID, "Plan 不能为空");
        }

        // 步骤数检查
        if (plan.getSteps() == null || plan.getSteps().isEmpty()) {
            throw new GovernanceException(ErrorCode.PLAN_INVALID, "Plan 必须包含至少一个步骤");
        }
        if (plan.getSteps().size() > maxPlanSteps) {
            throw new GovernanceException(ErrorCode.PLAN_INVALID,
                    "步骤数 " + plan.getSteps().size() + " 超过上限 " + maxPlanSteps);
        }

        // 步骤 ID 唯一性检查
        Set<Integer> stepIds = new HashSet<>();
        for (Step step : plan.getSteps()) {
            if (!stepIds.add(step.getId())) {
                throw new GovernanceException(ErrorCode.PLAN_INVALID,
                        "步骤 ID 重复: " + step.getId());
            }
            if (step.getTask() == null || step.getTask().length() < 3) {
                throw new GovernanceException(ErrorCode.PLAN_INVALID,
                        "步骤 " + step.getId() + " 描述无效");
            }
        }

        // 基于 affectedFiles 的 DAG 循环依赖检测
        List<Step> steps = plan.getSteps();
        if (steps.size() > 1 && Dispatcher.hasCyclicDependency(steps)) {
            throw new GovernanceException(ErrorCode.PLAN_INVALID,
                    "Plan 步骤间存在循环依赖，无法执行");
        }

        // affectedFiles 非空校验
        for (Step step : steps) {
            if (step.getAffectedFiles() != null) {
                for (String file : step.getAffectedFiles()) {
                    if (file == null || file.isBlank()) {
                        throw new GovernanceException(ErrorCode.PLAN_INVALID,
                                "步骤 " + step.getId() + " 包含无效文件路径");
                    }
                }
            }
        }
    }
}
