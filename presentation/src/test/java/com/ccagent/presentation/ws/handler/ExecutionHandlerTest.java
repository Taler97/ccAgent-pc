package com.ccagent.presentation.ws.handler;

import com.ccagent.api.model.envelope.Envelope;
import com.ccagent.api.model.request.ExecuteRequest;
import com.ccagent.api.model.response.StreamChunk;
import com.ccagent.api.port.OrchestratorPort;
import com.ccagent.api.port.StreamObserver;
import com.ccagent.presentation.ws.MessageRouter.MessageSender;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * ExecutionHandler 单元测试。
 * <p>验证：EXECUTE 流式回调转发、CANCEL/ROLLBACK/TOOL_APPROVAL/EXECUTE_PLAN/CANCEL_PLAN 委托正确。
 * 不含业务逻辑验证（由 OrchestratorPort 实现端负责）。</p>
 */
@ExtendWith(MockitoExtension.class)
class ExecutionHandlerTest {

    @Mock OrchestratorPort gateway;

    static class CapturingSender implements MessageSender {
        final List<Envelope> sent = new ArrayList<>();
        public void send(Envelope e) { sent.add(e); }
    }

    private Envelope env(String type, String seqId, Object payload) {
        return new Envelope(type, seqId, "2026-01-01T00:00:00Z", payload);
    }

    // ===== EXECUTE =====

    @Test
    @SuppressWarnings("unchecked")
    void executeSendsPlanningStatusThenDelegates() {
        ExecutionHandler handler = new ExecutionHandler(gateway);

        // 捕获传给 gateway 的 observer，不触发回调
        doNothing().when(gateway).executeWithSeqId(any(), anyString(), any());

        CapturingSender sender = new CapturingSender();
        Envelope envelope = env("EXECUTE", "seq-1",
                Map.of("prompt", "hello", "sessionId", "s-1"));
        handler.handle(envelope, sender);

        // 应先发 AGENT_STATUS(PLANNING)
        assertThat(sender.sent).hasSize(1);
        assertThat(sender.sent.get(0).getType()).isEqualTo("AGENT_STATUS");
        String payload = (String) sender.sent.get(0).getPayload();
        assertThat(payload).contains("PLANNING");

        verify(gateway).executeWithSeqId(any(ExecuteRequest.class), eq("seq-1"), any());
    }

    @Test
    @SuppressWarnings("unchecked")
    void executeStreamOnNextForwardsChunk() {
        ExecutionHandler handler = new ExecutionHandler(gateway);

        // 捕获 observer 并触发 onNext
        ArgumentCaptor<StreamObserver<StreamChunk>> captor =
                ArgumentCaptor.forClass(StreamObserver.class);
        doAnswer(inv -> {
            StreamObserver<StreamChunk> obs = inv.getArgument(2);
            obs.onNext(new StreamChunk("seq-1", "STEP_CHUNK", "partial-data", null));
            return null;
        }).when(gateway).executeWithSeqId(any(), anyString(), any());

        CapturingSender sender = new CapturingSender();
        handler.handle(env("EXECUTE", "seq-1", Map.of("prompt", "hi", "sessionId", "s-1")), sender);

        // 第一条是 AGENT_STATUS(PLANNING)，第二条是转发的 STEP_CHUNK
        assertThat(sender.sent).hasSizeGreaterThanOrEqualTo(2);
        Envelope chunkEnv = sender.sent.get(1);
        assertThat(chunkEnv.getType()).isEqualTo("STEP_CHUNK");
        assertThat(chunkEnv.getSeqId()).isEqualTo("seq-1");
        assertThat(chunkEnv.getPayload()).isEqualTo("partial-data");
    }

    @Test
    @SuppressWarnings("unchecked")
    void executeStreamOnCompleteSendsIdleOnly() {
        ExecutionHandler handler = new ExecutionHandler(gateway);

        doAnswer(inv -> {
            StreamObserver<StreamChunk> obs = inv.getArgument(2);
            obs.onComplete();
            return null;
        }).when(gateway).executeWithSeqId(any(), anyString(), any());

        CapturingSender sender = new CapturingSender();
        handler.handle(env("EXECUTE", "seq-1", Map.of("prompt", "hi", "sessionId", "s-1")), sender);

        // AGENT_STATUS(PLANNING) → AGENT_STATUS(IDLE)
        // EXECUTION_RESULT 由 SimpleOrchestrator 通过 onNext 发送（携带 token 统计），onComplete 不再重复发送
        assertThat(sender.sent).hasSize(2);
        assertThat((String) sender.sent.get(0).getPayload()).contains("PLANNING");
        assertThat(sender.sent.get(1).getType()).isEqualTo("AGENT_STATUS");
        assertThat((String) sender.sent.get(1).getPayload()).contains("IDLE");
    }

    @Test
    @SuppressWarnings("unchecked")
    void executeStreamOnNextPlanReadySendsWaitApproval() {
        ExecutionHandler handler = new ExecutionHandler(gateway);

        // 捕获 observer 并触发 PLAN_READY（模拟 SimpleOrchestrator 非 autoMode 下发计划）
        ArgumentCaptor<StreamObserver<StreamChunk>> captor =
                ArgumentCaptor.forClass(StreamObserver.class);
        doAnswer(inv -> {
            StreamObserver<StreamChunk> obs = inv.getArgument(2);
            obs.onNext(new StreamChunk("seq-1", "PLAN_READY",
                    "{\"summary\":\"plan\",\"steps\":[],\"stepCount\":0}", null));
            return null;
        }).when(gateway).executeWithSeqId(any(), anyString(), any());

        CapturingSender sender = new CapturingSender();
        handler.handle(env("EXECUTE", "seq-1", Map.of("prompt", "hi", "sessionId", "s-1")), sender);

        // AGENT_STATUS(PLANNING) → AGENT_STATUS(WAIT_APPROVAL) → PLAN_READY
        assertThat(sender.sent).hasSize(3);
        assertThat(sender.sent.get(0).getType()).isEqualTo("AGENT_STATUS");
        assertThat((String) sender.sent.get(0).getPayload()).contains("PLANNING");
        assertThat(sender.sent.get(1).getType()).isEqualTo("AGENT_STATUS");
        assertThat((String) sender.sent.get(1).getPayload()).contains("WAIT_APPROVAL");
        assertThat(sender.sent.get(2).getType()).isEqualTo("PLAN_READY");
    }

    @Test
    @SuppressWarnings("unchecked")
    void executeStreamOnErrorSendsError() {
        ExecutionHandler handler = new ExecutionHandler(gateway);

        doAnswer(inv -> {
            StreamObserver<StreamChunk> obs = inv.getArgument(2);
            obs.onError(new RuntimeException("LLM 调用超时"));
            return null;
        }).when(gateway).executeWithSeqId(any(), anyString(), any());

        CapturingSender sender = new CapturingSender();
        handler.handle(env("EXECUTE", "seq-1", Map.of("prompt", "hi", "sessionId", "s-1")), sender);

        // AGENT_STATUS(PLANNING) → AGENT_STATUS(ERROR) → ERROR
        assertThat(sender.sent).hasSizeGreaterThanOrEqualTo(2);
        assertThat((String) sender.sent.get(1).getPayload()).contains("ERROR");
        // 最后一条应该是 ERROR envelope
        Envelope last = sender.sent.get(sender.sent.size() - 1);
        assertThat(last.getType()).isEqualTo("ERROR");
        assertThat((String) last.getPayload()).contains("LLM 调用超时");
    }

    // ===== CANCEL_EXECUTION =====

    @Test
    void cancelExecutionDelegatesAndSendsIdle() {
        ExecutionHandler handler = new ExecutionHandler(gateway);
        CapturingSender sender = new CapturingSender();

        handler.handle(env("CANCEL_EXECUTION", "seq-2", Map.of()), sender);

        verify(gateway).cancelExecution("seq-2");
        assertThat(sender.sent).hasSize(1);
        assertThat((String) sender.sent.get(0).getPayload()).contains("IDLE");
        assertThat((String) sender.sent.get(0).getPayload()).contains("已取消执行");
    }

    // ===== ROLLBACK_DIALOG =====

    @Test
    void rollbackDialogSendsSuccessResult() {
        when(gateway.rollbackDialog("seq-3")).thenReturn(true);
        ExecutionHandler handler = new ExecutionHandler(gateway);
        CapturingSender sender = new CapturingSender();

        handler.handle(env("ROLLBACK_DIALOG", "seq-3", Map.of()), sender);

        // AGENT_STATUS(ROLLBACK) → AGENT_STATUS(IDLE) → ROLLBACK_RESULT
        assertThat(sender.sent).hasSize(3);
        Envelope result = sender.sent.get(2);
        assertThat(result.getType()).isEqualTo("ROLLBACK_RESULT");
        assertThat((String) result.getPayload()).contains("SUCCESS");
    }

    @Test
    void rollbackDialogSendsFailedResult() {
        when(gateway.rollbackDialog("seq-3b")).thenReturn(false);
        ExecutionHandler handler = new ExecutionHandler(gateway);
        CapturingSender sender = new CapturingSender();

        handler.handle(env("ROLLBACK_DIALOG", "seq-3b", Map.of()), sender);

        Envelope result = sender.sent.get(2);
        assertThat(result.getType()).isEqualTo("ROLLBACK_RESULT");
        assertThat((String) result.getPayload()).contains("FAILED");
    }

    // ===== TOOL_APPROVAL =====

    @Test
    void toolApprovalDelegatesApprovedTrue() {
        ExecutionHandler handler = new ExecutionHandler(gateway);

        handler.handle(env("TOOL_APPROVAL", "seq-4", Map.of("approved", true)),
                new CapturingSender());

        verify(gateway).onToolApproval("seq-4", true);
    }

    @Test
    void toolApprovalDelegatesApprovedFalse() {
        ExecutionHandler handler = new ExecutionHandler(gateway);

        handler.handle(env("TOOL_APPROVAL", "seq-4b", Map.of("approved", false)),
                new CapturingSender());

        verify(gateway).onToolApproval("seq-4b", false);
    }

    // ===== EXECUTE_PLAN =====

    @Test
    void executePlanConfirmedSendsExecuting() {
        ExecutionHandler handler = new ExecutionHandler(gateway);
        CapturingSender sender = new CapturingSender();

        handler.handle(env("EXECUTE_PLAN", "seq-5", Map.of("confirmed", true)), sender);

        verify(gateway).executePlan("seq-5", true);
        assertThat(sender.sent).hasSize(1);
        assertThat((String) sender.sent.get(0).getPayload()).contains("EXECUTING");
    }

    @Test
    void executePlanRejectedSendsIdle() {
        ExecutionHandler handler = new ExecutionHandler(gateway);
        CapturingSender sender = new CapturingSender();

        handler.handle(env("EXECUTE_PLAN", "seq-5b", Map.of("confirmed", false)), sender);

        verify(gateway).executePlan("seq-5b", false);
        assertThat(sender.sent).hasSize(1);
        assertThat((String) sender.sent.get(0).getPayload()).contains("IDLE");
    }

    // ===== CANCEL_PLAN =====

    @Test
    void cancelPlanDelegatesAndSendsIdle() {
        ExecutionHandler handler = new ExecutionHandler(gateway);
        CapturingSender sender = new CapturingSender();

        handler.handle(env("CANCEL_PLAN", "seq-6", Map.of()), sender);

        verify(gateway).cancelPlan("seq-6");
        assertThat(sender.sent).hasSize(1);
        assertThat((String) sender.sent.get(0).getPayload()).contains("IDLE");
    }
}
