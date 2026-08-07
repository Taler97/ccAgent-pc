package com.ccagent.presentation.integration;

import com.ccagent.api.model.envelope.Envelope;
import com.ccagent.api.model.memory.SessionInfo;
import com.ccagent.api.model.request.ExecuteRequest;
import com.ccagent.api.model.response.StreamChunk;
import com.ccagent.api.port.StreamObserver;
import com.ccagent.core.event.ExecutionCompletedEvent;
import com.ccagent.core.event.ExecutionFailedEvent;
import com.ccagent.core.event.ExecutionStartedEvent;
import com.ccagent.core.governance.policy.PlanSanitizer;
import com.ccagent.core.governance.policy.PolicyEnforcer;
import com.ccagent.core.governance.policy.TokenQuotaService;
import com.ccagent.core.orchestration.orchestrator.SimpleOrchestrator;
import com.ccagent.core.orchestration.progress.ProgressStore;
import com.ccagent.infrastructure.util.JsonUtil;
import com.ccagent.kernel.event.EventBus;
import com.ccagent.kernel.event.SimpleEventBus;
import com.ccagent.presentation.di.GovernanceGateway;
import com.ccagent.presentation.ws.MessageRouter;
import com.ccagent.presentation.ws.MessageRouter.MessageSender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * Handler + GovernanceGateway 全链路集成测试。
 * <p>真实装配：MessageRouter → 5 个 Handler → GovernanceGateway → PolicyEnforcer/TokenQuotaService/EventBus/ProgressStore
 * Mock 边界：SimpleOrchestrator、RollbackManager、Auditor、TokenStatsService（重依赖，不在此层验证）。
 * 验证：JSON 消息从路由到治理到委托的端到端正确性。</p>
 */
@ExtendWith(MockitoExtension.class)
class HandlerGatewayIntegrationTest {

    @Mock SimpleOrchestrator orchestrator;
    @Mock com.ccagent.plugin.governance.rollback.RollbackManager rollbackManager;
    @Mock com.ccagent.plugin.governance.audit.Auditor auditor;
    @Mock com.ccagent.plugin.governance.audit.TokenStatsService tokenStatsService;

    @TempDir Path tempDir;

    // 真实实例
    private final PolicyEnforcer policyEnforcer = new PolicyEnforcer();
    private final TokenQuotaService tokenQuotaService = new TokenQuotaService(100_000);
    private final PlanSanitizer planSanitizer = new PlanSanitizer(10);
    private final ProgressStore progressStore = new ProgressStore();
    private EventBus eventBus;

    // 事件收集
    private final List<Object> publishedEvents = new ArrayList<>();

    // 管线
    private MessageRouter router;
    private CapturingSender sender;

    @BeforeEach
    void setUp() {
        eventBus = new SimpleEventBus(false, 1, 1);
        eventBus.subscribe(ExecutionStartedEvent.class, publishedEvents::add);
        eventBus.subscribe(ExecutionCompletedEvent.class, publishedEvents::add);
        eventBus.subscribe(ExecutionFailedEvent.class, publishedEvents::add);

        GovernanceGateway gateway = new GovernanceGateway(
                orchestrator, policyEnforcer, tokenQuotaService, planSanitizer,
                rollbackManager, auditor, tokenStatsService, eventBus, progressStore);

        router = new MessageRouter(gateway);
        sender = new CapturingSender();
    }

    @AfterEach
    void tearDown() {
        com.ccagent.core.governance.context.ExecutionContextHolder.clear();
        eventBus.shutdown();
    }

    // ===== 辅助 =====

    static class CapturingSender implements MessageSender {
        final List<Envelope> sent = new ArrayList<>();
        public void send(Envelope e) { sent.add(e); }
    }

    private String json(String type, String seqId, Object payload) {
        return JsonUtil.toJson(new Envelope(type, seqId, "2026-01-01T00:00:00Z", payload));
    }

    private String json(String type, String seqId) {
        return json(type, seqId, Map.of());
    }

    // ===== PING =====

    @Test
    void ping_fullChain_returnsPong() {
        router.route(json("PING", "seq-1"), sender);

        assertThat(sender.sent).hasSize(1);
        assertThat(sender.sent.get(0).getType()).isEqualTo("PONG");
        assertThat(sender.sent.get(0).getSeqId()).isEqualTo("seq-1");
        assertThat((String) sender.sent.get(0).getPayload()).contains("pong");
    }

    // ===== CREATE_SESSION =====

    @Test
    void createSession_fullChain_delegatesToOrchestrator() {
        router.route(json("CREATE_SESSION", "seq-2",
                Map.of("sessionId", "s-1", "rootPath", tempDir.toString())), sender);

        verify(orchestrator).createSession("s-1", tempDir.toAbsolutePath().normalize().toString());
    }

    @Test
    void createSession_fullChain_nullRootPath_doesNotDelegate() {
        router.route(json("CREATE_SESSION", "seq-3",
                Map.of("sessionId", "s-1")), sender);

        verify(orchestrator, never()).createSession(anyString(), anyString());
    }

    // ===== LIST_SESSIONS =====

    @Test
    @SuppressWarnings("unchecked")
    void listSessions_fullChain_returnsSessionListRes() {
        when(orchestrator.listSessions()).thenReturn(List.of(
                new SessionInfo("s-1", "ws-1", "/root", 5, "summary",
                        Instant.parse("2026-01-01T00:00:00Z"),
                        Instant.parse("2026-01-02T00:00:00Z"))
        ));

        router.route(json("LIST_SESSIONS", "seq-4"), sender);

        assertThat(sender.sent).hasSize(1);
        Envelope resp = sender.sent.get(0);
        assertThat(resp.getType()).isEqualTo("SESSION_LIST_RES");
        Map<String, Object> payload = JsonUtil.fromJson((String) resp.getPayload(), Map.class);
        List<Map<String, Object>> sessions = (List<Map<String, Object>>) payload.get("sessions");
        assertThat(sessions).hasSize(1);
        assertThat(sessions.get(0).get("sessionId")).isEqualTo("s-1");
    }

    // ===== EXECUTE 正常路径 =====

    @Test
    @SuppressWarnings("unchecked")
    void execute_fullChain_onComplete_sendsPlanningIdleResultAndEvents() {
        doAnswer(inv -> {
            StreamObserver<StreamChunk> obs = inv.getArgument(2);
            obs.onComplete();
            return null;
        }).when(orchestrator).execute(any(), anyString(), any());

        router.route(json("EXECUTE", "seq-5",
                Map.of("workspaceId", "ws-1", "sessionId", "s-1", "prompt", "hello")), sender);

        // 2 条响应：AGENT_STATUS(PLANNING) → AGENT_STATUS(IDLE)
        // EXECUTION_RESULT 由 SimpleOrchestrator 通过 onNext 发送（携带 token），onComplete 不再重复发送
        assertThat(sender.sent).hasSize(2);
        assertThat((String) sender.sent.get(0).getPayload()).contains("PLANNING");
        assertThat((String) sender.sent.get(1).getPayload()).contains("IDLE");

        // 事件：Started → Completed
        assertThat(publishedEvents).hasSize(2);
        assertThat(publishedEvents.get(0)).isInstanceOf(ExecutionStartedEvent.class);
        assertThat(publishedEvents.get(1)).isInstanceOf(ExecutionCompletedEvent.class);

        verify(rollbackManager).cleanup("seq-5");
    }

    @Test
    @SuppressWarnings("unchecked")
    void execute_fullChain_onNext_forwardsStreamChunk() {
        doAnswer(inv -> {
            StreamObserver<StreamChunk> obs = inv.getArgument(2);
            obs.onNext(new StreamChunk("seq-6", "STEP_CHUNK", "partial-data", null));
            obs.onComplete();
            return null;
        }).when(orchestrator).execute(any(), anyString(), any());

        router.route(json("EXECUTE", "seq-6",
                Map.of("workspaceId", "ws-1", "sessionId", "s-1", "prompt", "test")), sender);

        // PLANNING → STEP_CHUNK → IDLE
        assertThat(sender.sent).hasSize(3);
        assertThat(sender.sent.get(1).getType()).isEqualTo("STEP_CHUNK");
        assertThat((String) sender.sent.get(1).getPayload()).isEqualTo("partial-data");
    }

    @Test
    @SuppressWarnings("unchecked")
    void execute_fullChain_onError_sendsErrorAndFailedEvent() {
        doAnswer(inv -> {
            StreamObserver<StreamChunk> obs = inv.getArgument(2);
            obs.onError(new RuntimeException("LLM 调用超时"));
            return null;
        }).when(orchestrator).execute(any(), anyString(), any());

        router.route(json("EXECUTE", "seq-7",
                Map.of("workspaceId", "ws-1", "sessionId", "s-1", "prompt", "test")), sender);

        // PLANNING → AGENT_STATUS(ERROR) → ERROR
        assertThat(sender.sent).hasSizeGreaterThanOrEqualTo(2);
        assertThat((String) sender.sent.get(1).getPayload()).contains("ERROR");

        // 事件：Started → Failed
        assertThat(publishedEvents).hasSize(2);
        assertThat(publishedEvents.get(1)).isInstanceOf(ExecutionFailedEvent.class);
        ExecutionFailedEvent failed = (ExecutionFailedEvent) publishedEvents.get(1);
        assertThat(failed.getErrorCode()).isEqualTo("UNKNOWN_ERROR");
        assertThat(failed.getErrorMessage()).contains("LLM 调用超时");

        // 非 AgentException → rollback 不清理
        verify(rollbackManager, never()).cleanup(anyString());
    }

    // ===== EXECUTE 治理拦截 =====

    @Test
    @SuppressWarnings("unchecked")
    void execute_fullChain_emptyWorkspace_governanceBlocks() {
        router.route(json("EXECUTE", "seq-8",
                Map.of("workspaceId", "", "sessionId", "s-1", "prompt", "test")), sender);

        // governance 拦截，orchestrator 不执行
        verify(orchestrator, never()).execute(any(), anyString(), any());

        // 完整流程：PLANNING → ERROR chunk(gateway catch onNext) → AGENT_STATUS(ERROR) → ERROR(onError)
        assertThat(sender.sent).hasSize(4);
        assertThat((String) sender.sent.get(0).getPayload()).contains("PLANNING");
        assertThat(sender.sent.get(1).getType()).isEqualTo("ERROR");
        assertThat((String) sender.sent.get(1).getPayload()).contains("WORKSPACE_NOT_FOUND");
        assertThat((String) sender.sent.get(2).getPayload()).contains("ERROR");
        assertThat(sender.sent.get(3).getType()).isEqualTo("ERROR");
    }

    @Test
    @SuppressWarnings("unchecked")
    void execute_fullChain_quotaExceeded_governanceBlocks() {
        // 用低配额 gateway 替换
        TokenQuotaService lowQuota = new TokenQuotaService(1);
        GovernanceGateway gateway = new GovernanceGateway(
                orchestrator, policyEnforcer, lowQuota, planSanitizer,
                rollbackManager, auditor, tokenStatsService, eventBus, progressStore);
        router = new MessageRouter(gateway);
        sender = new CapturingSender();

        router.route(json("EXECUTE", "seq-9",
                Map.of("workspaceId", "ws-1", "sessionId", "s-1", "prompt", "test")), sender);

        verify(orchestrator, never()).execute(any(), anyString(), any());
        // PLANNING → ERROR chunk → AGENT_STATUS(ERROR) → ERROR
        assertThat(sender.sent).hasSize(4);
        assertThat((String) sender.sent.get(0).getPayload()).contains("PLANNING");
        assertThat(sender.sent.get(1).getType()).isEqualTo("ERROR");
        assertThat((String) sender.sent.get(1).getPayload()).contains("QUOTA_EXCEEDED");
    }

    // ===== CANCEL_EXECUTION =====

    @Test
    void cancelExecution_fullChain_delegatesAndSendsIdle() {
        when(orchestrator.cancelExecution("seq-10")).thenReturn(true);

        router.route(json("CANCEL_EXECUTION", "seq-10"), sender);

        verify(orchestrator).cancelExecution("seq-10");
        verify(auditor).record(eq(com.ccagent.core.governance.audit.AuditEvent.ERROR), any());
        assertThat(sender.sent).hasSize(1);
        assertThat((String) sender.sent.get(0).getPayload()).contains("IDLE");
    }

    // ===== ROLLBACK_DIALOG =====

    @Test
    void rollbackDialog_fullChain_delegatesAndSendsResult() {
        when(rollbackManager.restoreAll("seq-11")).thenReturn(true);

        router.route(json("ROLLBACK_DIALOG", "seq-11"), sender);

        verify(rollbackManager).restoreAll("seq-11");
        verify(auditor).record(eq(com.ccagent.core.governance.audit.AuditEvent.ERROR), any());
        // ROLLBACK → IDLE → ROLLBACK_RESULT
        assertThat(sender.sent).hasSize(3);
        Envelope result = sender.sent.get(2);
        assertThat(result.getType()).isEqualTo("ROLLBACK_RESULT");
        assertThat((String) result.getPayload()).contains("SUCCESS");
    }

    // ===== EXECUTE_PLAN / CANCEL_PLAN =====

    @Test
    void executePlan_fullChain_delegatesConfirmPlan() {
        router.route(json("EXECUTE_PLAN", "seq-12", Map.of("confirmed", true)), sender);

        verify(orchestrator).confirmPlan("seq-12", true);
        assertThat(sender.sent).hasSize(1);
        assertThat((String) sender.sent.get(0).getPayload()).contains("EXECUTING");
    }

    @Test
    void cancelPlan_fullChain_delegatesCancelExecution() {
        when(orchestrator.cancelExecution("seq-13")).thenReturn(true);

        router.route(json("CANCEL_PLAN", "seq-13"), sender);

        verify(orchestrator).cancelExecution("seq-13");
        assertThat(sender.sent).hasSize(1);
        assertThat((String) sender.sent.get(0).getPayload()).contains("IDLE");
    }

    // ===== QUERY_PROGRESS =====

    @Test
    @SuppressWarnings("unchecked")
    void queryProgress_fullChain_returnsProgressResp() {
        // progressStore 是真实的，未注册返回 null → handler 兜底空结构
        router.route(json("QUERY_PROGRESS", "seq-14"), sender);

        assertThat(sender.sent).hasSize(1);
        Envelope resp = sender.sent.get(0);
        assertThat(resp.getType()).isEqualTo("PROGRESS_RESP");
        Map<String, Object> payload = JsonUtil.fromJson((String) resp.getPayload(), Map.class);
        assertThat(payload.get("totalSteps")).isEqualTo(0);
        assertThat(payload.get("completedSteps")).isEqualTo(0);
    }

    // ===== FILE_LIST_REQ / FILE_READ_REQ =====

    @Test
    @SuppressWarnings("unchecked")
    void fileList_fullChain_returnsDirectoryContents() throws Exception {
        Files.writeString(tempDir.resolve("a.txt"), "hello");
        Files.createDirectory(tempDir.resolve("subdir"));
        when(orchestrator.loadWorkspaceRoot("s-1")).thenReturn(tempDir.toString());

        router.route(json("FILE_LIST_REQ", "seq-15",
                Map.of("sessionId", "s-1", "path", "/")), sender);

        assertThat(sender.sent).hasSize(1);
        Envelope resp = sender.sent.get(0);
        assertThat(resp.getType()).isEqualTo("FILE_LIST_RES");
        Map<String, Object> payload = JsonUtil.fromJson((String) resp.getPayload(), Map.class);
        List<Map<String, Object>> files = (List<Map<String, Object>>) payload.get("files");
        assertThat(files).hasSize(2);
        assertThat(files).anyMatch(f -> "a.txt".equals(f.get("name")));
    }

    @Test
    @SuppressWarnings("unchecked")
    void fileRead_fullChain_returnsFileContent() throws Exception {
        Files.writeString(tempDir.resolve("test.txt"), "line1\nline2");
        when(orchestrator.loadWorkspaceRoot("s-1")).thenReturn(tempDir.toString());

        router.route(json("FILE_READ_REQ", "seq-16",
                Map.of("sessionId", "s-1", "path", "/test.txt")), sender);

        assertThat(sender.sent).hasSize(1);
        Envelope resp = sender.sent.get(0);
        assertThat(resp.getType()).isEqualTo("FILE_READ_RES");
        Map<String, Object> payload = JsonUtil.fromJson((String) resp.getPayload(), Map.class);
        assertThat(payload.get("content")).isEqualTo("line1\nline2");
        assertThat(payload.get("size")).isEqualTo(11);
    }

    // ===== 并发 seqId 隔离 =====

    @Test
    void concurrentSameSeqId_messagesAreSerialized() throws Exception {
        AtomicInteger running = new AtomicInteger(0);
        AtomicInteger maxConcurrent = new AtomicInteger(0);
        CountDownLatch release = new CountDownLatch(1);

        doAnswer(inv -> {
            int cur = running.incrementAndGet();
            maxConcurrent.accumulateAndGet(cur, Math::max);
            release.await(3, TimeUnit.SECONDS);
            running.decrementAndGet();
            return null;
        }).when(orchestrator).execute(any(), anyString(), any());

        // 两条 EXECUTE 同 seqId，应在 SeqLockManager 中串行
        Thread t1 = Thread.startVirtualThread(() ->
                router.route(json("EXECUTE", "seq-concurrent",
                        Map.of("workspaceId", "ws-1", "sessionId", "s-1", "prompt", "a")), sender));

        // 等第一个进入 orchestrator
        Thread.sleep(200);

        Thread t2 = Thread.startVirtualThread(() ->
                router.route(json("EXECUTE", "seq-concurrent",
                        Map.of("workspaceId", "ws-1", "sessionId", "s-1", "prompt", "b")), sender));

        Thread.sleep(200);
        // 只有一个在执行
        assertThat(maxConcurrent.get()).isEqualTo(1);

        release.countDown();
        t1.join(5_000);
        t2.join(5_000);

        // 第二个也应执行完
        assertThat(maxConcurrent.get()).isEqualTo(1);
    }

    // ===== 未知 type 静默返回 =====

    @Test
    void unknownType_fullChain_silentlyIgnored() {
        router.route(json("UNKNOWN_TYPE", "seq-x"), sender);
        assertThat(sender.sent).isEmpty();
    }

    @Test
    void invalidJson_fullChain_silentlyIgnored() {
        router.route("not valid json {{{", sender);
        assertThat(sender.sent).isEmpty();
    }
}
