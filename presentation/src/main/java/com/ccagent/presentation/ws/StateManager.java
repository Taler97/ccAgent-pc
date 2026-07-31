package com.ccagent.presentation.ws;

import com.ccagent.presentation.observer.StatusObserver;

/**
 * 双端状态管理器
 * <p>维护云端连接状态 + Agent 运行状态。</p>
 */
public class StateManager {

    public enum CloudState {
        CONNECTED, DISCONNECTED, RECONNECTING
    }

    private CloudState cloudState = CloudState.DISCONNECTED;
    private StatusObserver.AgentState agentState = StatusObserver.AgentState.IDLE;
    private String currentOperation = "";
    private String currentSessionId;
    private String currentSeqId;

    private StatusObserver statusObserver;

    public void setStatusObserver(StatusObserver observer) {
        this.statusObserver = observer;
    }

    // ====== 云端连接状态 ======

    public synchronized CloudState getCloudState() { return cloudState; }

    public synchronized void setCloudState(CloudState state) {
        this.cloudState = state;
        notifyStatusChange();
    }

    // ====== Agent 运行状态 ======

    public synchronized StatusObserver.AgentState getAgentState() { return agentState; }

    public synchronized void setAgentState(StatusObserver.AgentState state) {
        this.agentState = state;
        notifyStatusChange();
    }

    public synchronized void setAgentState(StatusObserver.AgentState state, String operation) {
        this.agentState = state;
        this.currentOperation = operation;
        notifyStatusChange();
    }

    public synchronized String getCurrentOperation() { return currentOperation; }

    public synchronized void setCurrentOperation(String operation) {
        this.currentOperation = operation;
        notifyStatusChange();
    }

    public synchronized void setCurrentSession(String sessionId, String seqId) {
        this.currentSessionId = sessionId;
        this.currentSeqId = seqId;
    }

    private void notifyStatusChange() {
        if (statusObserver != null) {
            statusObserver.onStateChange(agentState, currentOperation);
        }
    }
}
