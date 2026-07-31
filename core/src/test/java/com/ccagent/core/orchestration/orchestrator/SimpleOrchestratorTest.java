package com.ccagent.core.orchestration.orchestrator;

import com.ccagent.api.port.ExpertRegistryPort;
import com.ccagent.api.port.MemoryPort;
import com.ccagent.core.orchestration.agent.ChatAgent;
import com.ccagent.core.orchestration.dispatcher.Dispatcher;
import com.ccagent.core.orchestration.repository.ExecutionPlanRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class SimpleOrchestratorTest {

    @Mock ChatAgent chatAgent;
    @Mock Dispatcher dispatcher;
    @Mock MemoryPort memoryPort;
    @Mock ExpertRegistryPort expertRegistry;
    @Mock ExecutionPlanRepository executionPlanRepository;

    @Test
    void shouldCancelNonExistentExecution() {
        SimpleOrchestrator orchestrator = new SimpleOrchestrator(
                chatAgent, dispatcher, memoryPort, expertRegistry,
                executionPlanRepository);
        boolean result = orchestrator.cancelExecution("nonexistent-seq");
        assert !result : "取消不存在的执行应返回 false";
    }

    @Test
    void shouldConfirmNonExistentPlan() {
        SimpleOrchestrator orchestrator = new SimpleOrchestrator(
                chatAgent, dispatcher, memoryPort, expertRegistry,
                executionPlanRepository);
        orchestrator.confirmPlan("seq-1", true);
        orchestrator.confirmPlan("seq-1", false);
    }
}
