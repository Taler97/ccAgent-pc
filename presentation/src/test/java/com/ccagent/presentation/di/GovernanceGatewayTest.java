package com.ccagent.presentation.di;

import com.ccagent.api.model.memory.SessionInfo;
import com.ccagent.api.model.request.ExecuteRequest;
import com.ccagent.api.model.response.StreamChunk;
import com.ccagent.api.port.StreamObserver;
import com.ccagent.core.event.ExecutionCompletedEvent;
import com.ccagent.core.event.ExecutionFailedEvent;
import com.ccagent.core.event.ExecutionStartedEvent;
import com.ccagent.core.governance.audit.AuditEvent;
import com.ccagent.core.governance.context.ExecutionContextHolder;
import com.ccagent.core.governance.policy.PlanSanitizer;
import com.ccagent.core.governance.policy.PolicyEnforcer;
import com.ccagent.core.governance.policy.TokenQuotaService;
import com.ccagent.core.orchestration.orchestrator.SimpleOrchestrator;
import com.ccagent.core.orchestration.progress.ProgressStore;
import com.ccagent.exception.base.AgentException;
import com.ccagent.exception.base.ErrorCode;
import com.ccagent.exception.governance.GovernanceException;
import com.ccagent.exception.workspace.WorkspaceException;
import com.ccagent.kernel.event.EventBus;
import com.ccagent.kernel.event.SimpleEventBus;
import com.ccagent.plugin.governance.audit.Auditor;
import com.ccagent.plugin.governance.audit.TokenStatsService;
import com.ccagent.plugin.governance.rollback.RollbackManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * GovernanceGateway 单元测试。
 * <p>验证：治理检查（PolicyEnforcer/TokenQuota）、事件发布（Started/Completed/Failed）、
 * SafeStreamObserver 双完成保护、RollbackManager 生命周期、ExecutionContext 清理、
 * 委托正确性、热插拔替换。</p>
 * <p>Mock 边界：SimpleOrchestrator/RollbackManager/Auditor/TokenStatsService 用 Mock；
 * PolicyEnforcer/TokenQuotaService/PlanSanitizer/EventBus/ProgressStore 用真实实例。</p>
 */
@ExtendWith(MockitoExtension.class)
class GovernanceGatewayTest {

    @Mock SimpleOrchestrator orchestrator;
    @Mock RollbackManager rollbackManager;
    @Mock Auditor auditor;
    @Mock TokenStatsService tokenStatsService;

    // 真实实例
    private final PolicyEnforcer policyEnforcer = new PolicyEnforcer();
    private final TokenQuotaService tokenQuotaService = new TokenQuotaService(100_000);
    private final PlanSanitizer planSanitizer = new PlanSanitizer(10);
    private final ProgressStore progressStore = new ProgressStore();
    private EventBus eventBus;

    // 事件收集器
    private final List<Object> publishedEvents = new ArrayList<>();

    @BeforeEach
    void setUp() {
        // 禁用持久化，避免文件 IO
        eventBus = new SimpleEventBus(false, 1, 1);
        eventBus.subscribe(ExecutionStartedEvent.class, e -> publishedEvents.add(e));
        eventBus.subscribe(ExecutionCompletedEvent.class, e -> publishedEvents.add(e));
        eventBus.subscribe(ExecutionFailedEvent.class, e -> publishedEvents.add(e));
    }

    @AfterEach
    void tearDown() {
        ExecutionContextHolder.clear();
        eventBus.shutdown();
    }

    private GovernanceGateway newGateway() {
        return new GovernanceGateway(orchestrator, policyEnforcer, tokenQuotaService,
                planSanitizer, rollbackManager, auditor, tokenStatsService,
                eventBus, progressStore);
    }

    private ExecuteRequest req(String workspaceId, String sessionId, String prompt) {
        return new ExecuteRequest(workspaceId, sessionId, prompt, true);
    }

    // ===== executeWithSeqId — 正常路径 =====

    @Test
    @SuppressWarnings("unchecked")
    void executePublishesStartedAndCompletedEvents() {
        GovernanceGateway gateway = newGateway();

        // 捕获传给 orchestrator 的 observer，触发 onComplete
        doAnswer(inv -> {
            StreamObserver<StreamChunk> obs = inv.getArgument(2);
            obs.onComplete();
            return null;
        }).when(orchestrator).execute(any(), anyString(), any());

        StreamObserver<StreamChunk> mockObserver = mock(StreamObserver.class);
        gateway.executeWithSeqId(req("ws-1", "s-1", "hello"), "seq-1", mockObserver);

        // 验证事件发布顺序：Started → Completed
        assertThat(publishedEvents).hasSize(2);
        assertThat(publishedEvents.get(0)).isInstanceOf(ExecutionStartedEvent.class);
        assertThat(publishedEvents.get(1)).isInstanceOf(ExecutionCompletedEvent.class);

        ExecutionStartedEvent started = (ExecutionStartedEvent) publishedEvents.get(0);
        assertThat(started.getSeqId()).isEqualTo("seq-1");
        assertThat(started.getPrompt()).isEqualTo("hello");

        // 验证 rollbackManager 在完成后清理
        verify(rollbackManager).cleanup("seq-1");
        // 验证原始 observer 收到 onComplete
        verify(mockObserver).onComplete();
    }

    @Test
    @SuppressWarnings("unchecked")
    void executeForwardsOnNextChunks() {
        GovernanceGateway gateway = newGateway();

        StreamChunk chunk = new StreamChunk("seq-1", "STEP_CHUNK", "partial-data", null);
        doAnswer(inv -> {
            StreamObserver<StreamChunk> obs = inv.getArgument(2);
            obs.onNext(chunk);
            obs.onComplete();
            return null;
        }).when(orchestrator).execute(any(), anyString(), any());

        StreamObserver<StreamChunk> mockObserver = mock(StreamObserver.class);
        gateway.executeWithSeqId(req("ws-1", "s-1", "test"), "seq-1", mockObserver);

        verify(mockObserver).onNext(chunk);
        verify(mockObserver).onComplete();
    }

    // ===== executeWithSeqId — 异常路径 =====

    @Test
    @SuppressWarnings("unchecked")
    void executeOnErrorAgentExceptionCleansUpAndPublishesFailedEvent() {
        GovernanceGateway gateway = newGateway();

        AgentException agentEx = new GovernanceException(ErrorCode.QUOTA_EXCEEDED, "配额超限");
        doAnswer(inv -> {
            StreamObserver<StreamChunk> obs = inv.getArgument(2);
            obs.onError(agentEx);
            return null;
        }).when(orchestrator).execute(any(), anyString(), any());

        StreamObserver<StreamChunk> mockObserver = mock(StreamObserver.class);
        gateway.executeWithSeqId(req("ws-1", "s-1", "test"), "seq-1", mockObserver);

        // AgentException → rollbackManager.cleanup 被调
        verify(rollbackManager).cleanup("seq-1");
        // ExecutionFailedEvent 发布，errorCode = QUOTA_EXCEEDED
        assertThat(publishedEvents).hasSize(2);
        assertThat(publishedEvents.get(0)).isInstanceOf(ExecutionStartedEvent.class);
        ExecutionFailedEvent failed = (ExecutionFailedEvent) publishedEvents.get(1);
        assertThat(failed.getErrorCode()).isEqualTo("QUOTA_EXCEEDED");
        assertThat(failed.getErrorMessage()).isEqualTo("配额超限");
        // 原始 observer 收到 onError
        verify(mockObserver).onError(agentEx);
    }

    @Test
    @SuppressWarnings("unchecked")
    void executeOnErrorNonAgentExceptionPublishesUnknownErrorCode() {
        GovernanceGateway gateway = newGateway();

        RuntimeException runtimeEx = new RuntimeException("LLM 连接断开");
        doAnswer(inv -> {
            StreamObserver<StreamChunk> obs = inv.getArgument(2);
            obs.onError(runtimeEx);
            return null;
        }).when(orchestrator).execute(any(), anyString(), any());

        StreamObserver<StreamChunk> mockObserver = mock(StreamObserver.class);
        gateway.executeWithSeqId(req("ws-1", "s-1", "test"), "seq-1", mockObserver);

        // 非 AgentException → rollbackManager.cleanup 不被调
        verify(rollbackManager, never()).cleanup(anyString());
        // errorCode = UNKNOWN_ERROR
        ExecutionFailedEvent failed = (ExecutionFailedEvent) publishedEvents.get(1);
        assertThat(failed.getErrorCode()).isEqualTo("UNKNOWN_ERROR");
        verify(mockObserver).onError(runtimeEx);
    }

    @Test
    @SuppressWarnings("unchecked")
    void executePolicyCheckThrowsSendsErrorChunk() {
        GovernanceGateway gateway = newGateway();

        // workspaceId 为空 → PolicyEnforcer 抛 WorkspaceException
        ExecuteRequest badReq = req("", "s-1", "test");

        StreamObserver<StreamChunk> mockObserver = mock(StreamObserver.class);
        gateway.executeWithSeqId(badReq, "seq-1", mockObserver);

        // orchestrator.execute 不应被调
        verify(orchestrator, never()).execute(any(), anyString(), any());
        // 应发送 ERROR chunk + onError
        ArgumentCaptor<StreamChunk> chunkCaptor = ArgumentCaptor.forClass(StreamChunk.class);
        verify(mockObserver).onNext(chunkCaptor.capture());
        assertThat(chunkCaptor.getValue().getType()).isEqualTo("ERROR");
        assertThat(chunkCaptor.getValue().getContent()).contains("WORKSPACE_NOT_FOUND");
        verify(mockObserver).onError(any(WorkspaceException.class));
    }

    @Test
    @SuppressWarnings("unchecked")
    void executeQuotaExceededSendsErrorChunk() {
        // 配额设为 1，预估 1000 → 超限
        TokenQuotaService lowQuota = new TokenQuotaService(1);
        GovernanceGateway gateway = new GovernanceGateway(orchestrator, policyEnforcer, lowQuota,
                planSanitizer, rollbackManager, auditor, tokenStatsService,
                eventBus, progressStore);

        StreamObserver<StreamChunk> mockObserver = mock(StreamObserver.class);
        gateway.executeWithSeqId(req("ws-1", "s-1", "test"), "seq-1", mockObserver);

        verify(orchestrator, never()).execute(any(), anyString(), any());
        ArgumentCaptor<StreamChunk> chunkCaptor = ArgumentCaptor.forClass(StreamChunk.class);
        verify(mockObserver).onNext(chunkCaptor.capture());
        assertThat(chunkCaptor.getValue().getType()).isEqualTo("ERROR");
        assertThat(chunkCaptor.getValue().getContent()).contains("QUOTA_EXCEEDED");
    }

    // ===== ExecutionContext 生命周期 =====

    @Test
    @SuppressWarnings("unchecked")
    void executionContextClearedAfterComplete() {
        GovernanceGateway gateway = newGateway();

        doAnswer(inv -> {
            // 执行期间 context 应存在
            assertThat(ExecutionContextHolder.getContext()).isNotNull();
            assertThat(ExecutionContextHolder.getStage())
                    .isEqualTo(com.ccagent.core.orchestration.context.ExecutionContext.ExecutionStage.PLANNING);
            StreamObserver<StreamChunk> obs = inv.getArgument(2);
            obs.onComplete();
            return null;
        }).when(orchestrator).execute(any(), anyString(), any());

        gateway.executeWithSeqId(req("ws-1", "s-1", "test"), "seq-1", mock(StreamObserver.class));

        // 执行后 context 应已清理
        assertThat(ExecutionContextHolder.getContext()).isNull();
    }

    @Test
    @SuppressWarnings("unchecked")
    void executionContextClearedAfterError() {
        GovernanceGateway gateway = newGateway();

        doAnswer(inv -> {
            assertThat(ExecutionContextHolder.getContext()).isNotNull();
            StreamObserver<StreamChunk> obs = inv.getArgument(2);
            obs.onError(new RuntimeException("boom"));
            return null;
        }).when(orchestrator).execute(any(), anyString(), any());

        gateway.executeWithSeqId(req("ws-1", "s-1", "test"), "seq-1", mock(StreamObserver.class));

        assertThat(ExecutionContextHolder.getContext()).isNull();
    }

    @Test
    @SuppressWarnings("unchecked")
    void executionContextClearedAfterPolicyException() {
        GovernanceGateway gateway = newGateway();

        // policy 检查会抛异常，但 context 应在 finally 中清理
        gateway.executeWithSeqId(req("", "s-1", "test"), "seq-1", mock(StreamObserver.class));

        assertThat(ExecutionContextHolder.getContext()).isNull();
    }

    // ===== SafeStreamObserver 双完成保护 =====

    @Test
    @SuppressWarnings("unchecked")
    void safeObserverPreventsDoubleComplete() {
        GovernanceGateway gateway = newGateway();

        doAnswer(inv -> {
            StreamObserver<StreamChunk> obs = inv.getArgument(2);
            obs.onComplete();
            obs.onComplete(); // 第二次应被忽略
            return null;
        }).when(orchestrator).execute(any(), anyString(), any());

        StreamObserver<StreamChunk> mockObserver = mock(StreamObserver.class);
        gateway.executeWithSeqId(req("ws-1", "s-1", "test"), "seq-1", mockObserver);

        // 原始 observer 只应收到一次 onComplete
        verify(mockObserver, times(1)).onComplete();
    }

    @Test
    @SuppressWarnings("unchecked")
    void safeObserverPreventsOnNextAfterComplete() {
        GovernanceGateway gateway = newGateway();

        StreamChunk chunk = new StreamChunk("seq-1", "STEP_CHUNK", "data", null);
        doAnswer(inv -> {
            StreamObserver<StreamChunk> obs = inv.getArgument(2);
            obs.onComplete();
            obs.onNext(chunk); // onComplete 后的 onNext 应被忽略
            return null;
        }).when(orchestrator).execute(any(), anyString(), any());

        StreamObserver<StreamChunk> mockObserver = mock(StreamObserver.class);
        gateway.executeWithSeqId(req("ws-1", "s-1", "test"), "seq-1", mockObserver);

        verify(mockObserver, never()).onNext(any());
    }

    // ===== 委托测试 =====

    @Test
    void cancelExecutionRecordsAuditAndDelegates() {
        GovernanceGateway gateway = newGateway();
        when(orchestrator.cancelExecution("seq-1")).thenReturn(true);

        boolean result = gateway.cancelExecution("seq-1");

        assertThat(result).isTrue();
        verify(auditor).record(eq(AuditEvent.ERROR), any());
        verify(orchestrator).cancelExecution("seq-1");
    }

    @Test
    void executePlanDelegatesConfirmPlan() {
        GovernanceGateway gateway = newGateway();

        gateway.executePlan("seq-1", true);

        verify(orchestrator).confirmPlan("seq-1", true);
    }

    @Test
    void cancelPlanDelegatesCancelExecution() {
        // 注意：cancelPlan 委托给 orchestrator.cancelExecution（非 cancelPlan）
        GovernanceGateway gateway = newGateway();
        when(orchestrator.cancelExecution("seq-1")).thenReturn(true);

        boolean result = gateway.cancelPlan("seq-1");

        assertThat(result).isTrue();
        verify(orchestrator).cancelExecution("seq-1");
    }

    @Test
    void rollbackDialogRecordsAuditAndRestores() {
        GovernanceGateway gateway = newGateway();
        when(rollbackManager.restoreAll("seq-1")).thenReturn(true);

        boolean result = gateway.rollbackDialog("seq-1");

        assertThat(result).isTrue();
        verify(auditor).record(eq(AuditEvent.ERROR), any());
        verify(rollbackManager).restoreAll("seq-1");
    }

    @Test
    void createSessionDelegates() {
        GovernanceGateway gateway = newGateway();

        gateway.createSession("s-1", "/workspace");

        verify(orchestrator).createSession("s-1", "/workspace");
    }

    @Test
    void listSessionsDelegates() {
        GovernanceGateway gateway = newGateway();
        List<SessionInfo> mockList = List.of(
                new SessionInfo("s-1", "ws-1", "/root", 3, "summary",
                        Instant.parse("2026-01-01T00:00:00Z"),
                        Instant.parse("2026-01-02T00:00:00Z"))
        );
        when(orchestrator.listSessions()).thenReturn(mockList);

        List<SessionInfo> result = gateway.listSessions();

        assertThat(result).isSameAs(mockList);
    }

    @Test
    void getExecutionProgressDelegatesToProgressStore() {
        GovernanceGateway gateway = newGateway();

        // progressStore 是真实的，未注册的 seqId 返回 null
        assertThat(gateway.getExecutionProgress("unknown")).isNull();
    }

    @Test
    void getStepExecutionStatusDelegatesToProgressStore() {
        GovernanceGateway gateway = newGateway();

        assertThat(gateway.getStepExecutionStatus("unknown", 0)).isNull();
    }

    // ===== 热插拔 =====

    @Test
    void replaceListenersSwapsInstances() {
        GovernanceGateway gateway = newGateway();

        RollbackManager newRollback = mock(RollbackManager.class);
        Auditor newAuditor = mock(Auditor.class);
        TokenStatsService newTokenStats = mock(TokenStatsService.class);

        when(newRollback.restoreAll("seq-1")).thenReturn(true);

        gateway.replaceListeners(newRollback, newAuditor, newTokenStats);

        // 替换后调用应使用新实例
        gateway.rollbackDialog("seq-1");

        verify(newRollback).restoreAll("seq-1");
        verify(newAuditor).record(eq(AuditEvent.ERROR), any());
        verify(rollbackManager, never()).restoreAll(anyString());
    }

    @Test
    void replaceListenersIgnoresNullArguments() {
        GovernanceGateway gateway = newGateway();
        when(rollbackManager.restoreAll("seq-1")).thenReturn(true);

        // 只替换 auditor，其余传 null
        Auditor newAuditor = mock(Auditor.class);
        gateway.replaceListeners(null, newAuditor, null);

        gateway.rollbackDialog("seq-1");

        // rollbackManager 未被替换
        verify(rollbackManager).restoreAll("seq-1");
        verify(newAuditor).record(eq(AuditEvent.ERROR), any());
    }

}
