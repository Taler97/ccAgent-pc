package com.ccagent.presentation.cli;

import com.ccagent.api.model.response.StreamChunk;
import com.ccagent.api.port.ExpertRegistryPort;
import com.ccagent.api.port.OrchestratorPort;
import com.ccagent.api.port.StreamObserver;
import com.ccagent.core.sync.LocalDataRepository;
import com.ccagent.core.sync.SyncScheduler;
import com.ccagent.infrastructure.config.AppConfig;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.ByteArrayInputStream;
import java.util.Scanner;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * StandaloneAgentRunner 单元测试
 * <p>验证 P0 CLI 命令与后端 OrchestratorPort 的调用链路，不涉及后端逻辑。</p>
 */
@ExtendWith(MockitoExtension.class)
class StandaloneAgentRunnerTest {

    @Mock
    private OrchestratorPort mockOrchestrator;
    @Mock
    private SyncScheduler mockSyncScheduler;
    @Mock
    private LocalDataRepository mockLocalRepo;
    @Mock
    private ExpertRegistryPort mockExpertRegistry;

    private AppConfig config;
    private StandaloneAgentRunner runner;

    @BeforeEach
    void setUp() {
        config = new AppConfig();
        config.getAuth().setWorkspaceDir(".");
    }

    // ==================== /confirm 命令 ====================

    @Test
    void confirmWithoutPendingPlan_shouldNotCallExecutePlan() {
        runner = createRunnerWithInput("/confirm\n/exit\n");
        runner.start();

        verify(mockOrchestrator, never()).executePlan(anyString(), anyBoolean());
    }

    @Test
    void confirmWithPendingPlan_shouldCallExecutePlanTrue() {
        runner = createRunnerWithInput("/confirm\n/exit\n");

        // 模拟 planPending 状态
        runner.pendingSeqId = "seq-test";
        runner.planPending = true;

        runner.start();

        verify(mockOrchestrator).executePlan("seq-test", true);
    }

    // ==================== /reject 命令 ====================

    @Test
    void rejectWithoutPendingPlan_shouldNotCallExecutePlan() {
        runner = createRunnerWithInput("/reject\n/exit\n");
        runner.start();

        verify(mockOrchestrator, never()).executePlan(anyString(), anyBoolean());
    }

    @Test
    void rejectWithPendingPlan_shouldCallExecutePlanFalse() {
        runner = createRunnerWithInput("/reject\n/exit\n");

        runner.pendingSeqId = "seq-test";
        runner.planPending = true;

        runner.start();

        verify(mockOrchestrator).executePlan("seq-test", false);
    }

    // ==================== /cancel 命令 ====================

    @Test
    void cancelWithoutExecution_shouldNotCallCancelExecution() {
        runner = createRunnerWithInput("/cancel\n/exit\n");
        runner.start();

        verify(mockOrchestrator, never()).cancelExecution(anyString());
    }

    @Test
    void cancelDuringExecution_shouldCallCancelExecution() {
        runner = createRunnerWithInput("/cancel\n/exit\n");

        runner.pendingSeqId = "seq-test";
        runner.isExecuting = true;

        runner.start();

        verify(mockOrchestrator).cancelExecution("seq-test");
    }

    // ==================== /rollback 命令 ====================

    @Test
    void rollbackWithoutSeqId_shouldUsePendingSeqId() {
        runner = createRunnerWithInput("/rollback\n/exit\n");
        runner.pendingSeqId = "seq-test";

        runner.start();

        verify(mockOrchestrator).rollbackDialog("seq-test");
    }

    @Test
    void rollbackWithSpecificSeqId_shouldUseProvidedSeqId() {
        runner = createRunnerWithInput("/rollback seq-custom\n/exit\n");

        runner.start();

        verify(mockOrchestrator).rollbackDialog("seq-custom");
    }

    // ==================== 事件处理 ====================

    @Test
    void planReadyEvent_shouldSetPlanPending() {
        runner = createRunnerWithInput("/exit\n");

        StreamChunk planEvent = new StreamChunk("seq-1", "PLAN_READY",
                "{\"summary\":\"重构代码\",\"steps\":3}", null);

        runner.handleStreamChunk(planEvent, "seq-1");

        assert runner.planPending : "PLAN_READY 应设置 planPending=true";
        assert "seq-1".equals(runner.pendingSeqId) : "pendingSeqId 应被设置";
    }

    @Test
    void stepStatusRunning_shouldUpdateStepState() {
        runner = createRunnerWithInput("/exit\n");

        StreamChunk stepEvent = new StreamChunk("seq-1", "STEP_STATUS",
                "{\"stepId\":1,\"totalSteps\":3,\"expertName\":\"planner\",\"status\":\"RUNNING\"}", null);

        runner.handleStreamChunk(stepEvent, "seq-1");

        assert runner.stepStates.containsKey(1) : "步骤 1 应在状态表中";
        assert "RUNNING".equals(runner.stepStates.get(1).status) : "状态应为 RUNNING";
        assert "planner".equals(runner.stepStates.get(1).expertName) : "专家名应为 planner";
    }

    @Test
    void stepStatusSuccess_shouldMarkStepDone() {
        runner = createRunnerWithInput("/exit\n");

        // 先收到 RUNNING
        runner.handleStreamChunk(new StreamChunk("seq-1", "STEP_STATUS",
                "{\"stepId\":1,\"totalSteps\":3,\"expertName\":\"writer\",\"status\":\"RUNNING\"}", null), "seq-1");
        // 再收到 SUCCESS
        runner.handleStreamChunk(new StreamChunk("seq-1", "STEP_STATUS",
                "{\"stepId\":1,\"totalSteps\":3,\"expertName\":\"writer\",\"status\":\"SUCCESS\"}", null), "seq-1");

        assert "SUCCESS".equals(runner.stepStates.get(1).status) : "状态应为 SUCCESS";
    }

    @Test
    void stepStatusFailed_shouldUpdateStepState() {
        runner = createRunnerWithInput("/exit\n");

        StreamChunk stepEvent = new StreamChunk("seq-1", "STEP_STATUS",
                "{\"stepId\":2,\"totalSteps\":2,\"expertName\":\"writer\",\"status\":\"FAILED\",\"error\":\"写入失败\"}", null);

        runner.handleStreamChunk(stepEvent, "seq-1");

        assert "FAILED".equals(runner.stepStates.get(2).status) : "状态应为 FAILED";
    }

    @Test
    void executionResult_shouldPrintMessage() {
        runner = createRunnerWithInput("/exit\n");

        StreamChunk result = new StreamChunk("seq-1", "EXECUTION_RESULT",
                "{\"success\":true,\"message\":\"重构完成\"}", null);

        // 不抛异常即可
        runner.handleStreamChunk(result, "seq-1");
    }

    @Test
    void errorEvent_shouldNotThrow() {
        runner = createRunnerWithInput("/exit\n");

        StreamChunk error = new StreamChunk("seq-1", "ERROR",
                "{\"error\":\"LLM 调用超时\"}", null);

        runner.handleStreamChunk(error, "seq-1");
    }

    // ==================== 命令 + 事件组合验证 ====================

    @Test
    void confirmTriggersExecutePlan_afterPlanReady() {
        // 模拟完整链路：handleStreamChunk 收到 PLAN_READY → /confirm
        runner = createRunnerWithInput("/exit\n");

        StreamChunk planEvent = new StreamChunk("seq-flow", "PLAN_READY",
                "{\"summary\":\"flow test\",\"steps\":2}", null);
        runner.handleStreamChunk(planEvent, "seq-flow");

        assert runner.planPending;
        assert "seq-flow".equals(runner.pendingSeqId);

        runner.handleCommand("/confirm");

        verify(mockOrchestrator).executePlan("seq-flow", true);
    }

    @Test
    void rejectAfterPlanReady_shouldCallExecutePlanFalse() {
        runner = createRunnerWithInput("/exit\n");

        runner.handleStreamChunk(new StreamChunk("seq-r", "PLAN_READY",
                "{\"summary\":\"reject\",\"steps\":1}", null), "seq-r");

        runner.handleCommand("/reject");

        verify(mockOrchestrator).executePlan("seq-r", false);
    }

    @Test
    void cancelAfterStepRunning_shouldCallCancelExecution() {
        runner = createRunnerWithInput("/exit\n");
        runner.isExecuting = true;
        runner.pendingSeqId = "seq-cancel";

        runner.handleCommand("/cancel");

        verify(mockOrchestrator).cancelExecution("seq-cancel");
    }

    @Test
    void rollbackAfterExecution_shouldCallRollbackDialog() {
        runner = createRunnerWithInput("/exit\n");
        runner.pendingSeqId = "seq-roll";

        runner.handleCommand("/rollback");

        verify(mockOrchestrator).rollbackDialog("seq-roll");
    }

    // ==================== 辅助方法 ====================

    private StandaloneAgentRunner createRunnerWithInput(String input) {
        Scanner scanner = new Scanner(new ByteArrayInputStream(input.getBytes()));
        return new StandaloneAgentRunner(mockOrchestrator, config, mockSyncScheduler,
                mockLocalRepo, mockExpertRegistry, scanner);
    }
}
