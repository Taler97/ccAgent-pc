package com.ccagent.exception.plan;

import com.ccagent.exception.base.AgentException;
import com.ccagent.exception.base.ErrorCode;

/**
 * Plan 循环依赖异常
 * <p>当 DAG 中检测到文件或黑板 Key 级别的循环依赖时抛出。</p>
 */
public class PlanCycleException extends AgentException {

    public PlanCycleException(String message) {
        super(ErrorCode.PLAN_INVALID, message);
    }

    public PlanCycleException(String message, Throwable cause) {
        super(ErrorCode.PLAN_INVALID, message, cause);
    }
}
