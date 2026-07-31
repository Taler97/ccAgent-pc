package com.ccagent.presentation.ws;

import com.ccagent.presentation.observer.StatusObserver;
import org.junit.jupiter.api.Test;
import java.util.concurrent.atomic.AtomicReference;
import static org.assertj.core.api.Assertions.assertThat;

class StateManagerTest {

    @Test
    void shouldStartDisconnected() {
        StateManager sm = new StateManager();
        assertThat(sm.getCloudState()).isEqualTo(StateManager.CloudState.DISCONNECTED);
    }

    @Test
    void shouldStartIdle() {
        StateManager sm = new StateManager();
        assertThat(sm.getAgentState()).isEqualTo(StatusObserver.AgentState.IDLE);
    }

    @Test
    void shouldTrackCloudState() {
        StateManager sm = new StateManager();
        sm.setCloudState(StateManager.CloudState.CONNECTED);
        assertThat(sm.getCloudState()).isEqualTo(StateManager.CloudState.CONNECTED);
    }

    @Test
    void shouldTrackAgentState() {
        StateManager sm = new StateManager();
        sm.setAgentState(StatusObserver.AgentState.PLANNING, "Generating plan...");
        assertThat(sm.getAgentState()).isEqualTo(StatusObserver.AgentState.PLANNING);
        assertThat(sm.getCurrentOperation()).isEqualTo("Generating plan...");
    }

    @Test
    void shouldNotifyObserverOnStateChange() {
        StateManager sm = new StateManager();
        AtomicReference<StatusObserver.AgentState> captured = new AtomicReference<>();
        sm.setStatusObserver(new StatusObserver() {
            public void onStateChange(AgentState newState, String op) { captured.set(newState); }
            public void onStepProgress(StatusObserver.StepProgress p) {}
            public void onExecutionComplete(com.ccagent.api.model.response.ExecutionResult r) {}
            public void onError(String id, com.ccagent.exception.base.AgentException e) {}
        });
        sm.setAgentState(StatusObserver.AgentState.EXECUTING);
        assertThat(captured.get()).isEqualTo(StatusObserver.AgentState.EXECUTING);
    }
}
