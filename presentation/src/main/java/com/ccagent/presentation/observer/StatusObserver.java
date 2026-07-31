package com.ccagent.presentation.observer;

import com.ccagent.api.model.response.ExecutionResult;
import com.ccagent.api.model.response.StreamChunk;
import com.ccagent.api.port.StreamObserver;
import com.ccagent.exception.base.AgentException;
import com.ccagent.presentation.ws.StateManager;

/**
 * 状态回调接口
 * <p>治理层通过此接口向通信层推送状态变化。</p>
 */
public interface StatusObserver {

    /**
     * 状态变化通知
     * @param newState         新的 Agent 状态
     * @param currentOperation 当前操作描述
     */
    void onStateChange(AgentState newState, String currentOperation);

    /**
     * 步骤进度通知（由编排层回调）
     */
    void onStepProgress(StepProgress progress);

    /**
     * 执行完成通知
     */
    void onExecutionComplete(ExecutionResult result);

    /**
     * 错误通知
     */
    void onError(String seqId, AgentException error);

    /** Agent 运行状态枚举 */
    enum AgentState {
        IDLE, PLANNING, EXECUTING, ROLLBACK, ERROR, SHUTTING_DOWN
    }

    /** 步骤进度结构 */
    class StepProgress {
        public String seqId;
        public int stepId;
        public int totalSteps;
        public String expertName;
        public String task;
        public String status; // RUNNING / SUCCESS / FAILED / CANCELLED / ROLLED_BACK
        public java.util.List<String> affectedFiles;
        public String timestamp;

        public StepProgress() {}
    }
}
