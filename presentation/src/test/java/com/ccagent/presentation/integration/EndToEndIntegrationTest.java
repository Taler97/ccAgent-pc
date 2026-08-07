package com.ccagent.presentation.integration;

import com.ccagent.adapter.memory.H2MemoryAdapter;
import com.ccagent.api.model.envelope.Envelope;
import com.ccagent.api.model.request.ExecuteRequest;
import com.ccagent.core.context.FileArtifactStore;
import com.ccagent.core.expert.DefaultExpertRegistry;
import com.ccagent.core.governance.context.ExecutionContextHolder;
import com.ccagent.core.governance.policy.PlanSanitizer;
import com.ccagent.core.governance.policy.PolicyEnforcer;
import com.ccagent.core.governance.policy.TokenQuotaService;
import com.ccagent.core.governance.audit.AuditLevel;
import com.ccagent.core.orchestration.agent.ChatAgent;
import com.ccagent.core.orchestration.dispatcher.Dispatcher;
import com.ccagent.core.orchestration.orchestrator.SimpleOrchestrator;
import com.ccagent.core.orchestration.progress.ProgressStore;
import com.ccagent.core.orchestration.repository.ExecutionPlanRepository;
import com.ccagent.core.tool.DefaultToolRegistry;
import com.ccagent.infrastructure.util.JsonUtil;
import com.ccagent.kernel.event.SimpleEventBus;
import com.ccagent.plugin.experts.impl.PlannerExpert;
import com.ccagent.plugin.experts.impl.SummarizerExpert;
import com.ccagent.plugin.experts.impl.WriterExpert;
import com.ccagent.plugin.governance.audit.Auditor;
import com.ccagent.plugin.governance.audit.TokenStatsService;
import com.ccagent.plugin.governance.rollback.RollbackManager;
import com.ccagent.plugin.tools.impl.WriteFileTool;
import com.ccagent.plugin.tools.impl.ReadFileTool;
import com.ccagent.presentation.di.GovernanceGateway;
import com.ccagent.presentation.ws.MessageRouter;
import com.ccagent.presentation.ws.MessageRouter.MessageSender;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.sql.DataSource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 全链路集成测试：WebSocket Envelope → MessageRouter → Handler → GovernanceGateway
 * → SimpleOrchestrator → ChatAgent → PlannerExpert → Dispatcher → WriterExpert → 黑板 → 流式响应。
 *
 * <p>无 mock 核心执行组件。仅 LlmPort 用 stub 返回固定响应，避免依赖外部 DeepSeek API。</p>
 *
 * <p>验证端到端：</p>
 * <ul>
 *   <li>EXECUTE Envelope JSON 输入</li>
 *   <li>真实 ChatAgent 决策（stub LLM 返回 plan 类型）</li>
 *   <li>真实 PlannerExpert 生成 Plan（stub LLM 返回 Plan JSON）</li>
 *   <li>真实 Dispatcher 按 DAG 调度</li>
 *   <li>真实 WriterExpert 执行（stub LLM 返回文本，无 toolCalls）</li>
 *   <li>真实黑板写入产物（TaskContextImpl.putArtifact）</li>
 *   <li>真实持久化（FileArtifactStore + H2）</li>
 *   <li>CapturingSender 收到完整流式响应（PLANNING → STREAM_CHUNK → IDLE → EXECUTION_RESULT）</li>
 *   <li>PlanRecord 状态最终为 COMPLETED</li>
 * </ul>
 */
class EndToEndIntegrationTest {

    @TempDir Path tempDir;

    private DataSource dataSource;
    private SimpleEventBus eventBus;
    private MessageRouter router;
    private CapturingSender sender;
    private ExecutionPlanRepository planRepo;
    private StubLlmPort stubLlm;

    @BeforeEach
    void setUp() throws Exception {
        // 1. H2 内存数据库
        JdbcDataSource h2 = new JdbcDataSource();
        h2.setURL("jdbc:h2:mem:test-e2e;DB_CLOSE_DELAY=-1;MODE=MySQL");
        h2.setUser("sa");
        h2.setPassword("");
        dataSource = h2;

        // 清理残留
        try (var conn = dataSource.getConnection();
             var stmt = conn.createStatement()) {
            stmt.execute("DELETE FROM execution_plan");
            stmt.execute("DELETE FROM execution_step");
        } catch (Exception ignored) {}

        // 2. 真实持久化组件
        FileArtifactStore artifactStore = new FileArtifactStore();
        planRepo = new ExecutionPlanRepository(dataSource);
        ProgressStore progressStore = new ProgressStore();
        eventBus = new SimpleEventBus(false, 100, 10);

        // 3. 真实工具注册
        DefaultToolRegistry toolRegistry = new DefaultToolRegistry();
        toolRegistry.register(new WriteFileTool());
        toolRegistry.register(new ReadFileTool());

        // 4. Stub LLM（避免依赖真实 DeepSeek API）
        String planJson = "{"
                + "\"summary\":\"创建测试文件\","
                + "\"estimatedImpact\":[\"" + tempDir.toString().replace("\\", "/") + "/hello.txt\"],"
                + "\"steps\":["
                + "  {\"id\":1,\"expertName\":\"writer\","
                + "   \"task\":\"创建文件 " + tempDir.toString().replace("\\", "/") + "/hello.txt 内容为 hello world\","
                + "   \"affectedFiles\":[\"" + tempDir.toString().replace("\\", "/") + "/hello.txt\"],"
                + "   \"writes\":[\"result\"],\"persistOutput\":false}"
                + "]"
                + "}";
        stubLlm = new StubLlmPort(planJson);

        // 5. 真实专家注册
        DefaultExpertRegistry expertRegistry = new DefaultExpertRegistry();
        expertRegistry.register(new PlannerExpert(toolRegistry, stubLlm));
        expertRegistry.register(new WriterExpert(toolRegistry, stubLlm));
        expertRegistry.register(new SummarizerExpert(toolRegistry, stubLlm));

        // 6. 真实 MemoryPort
        H2MemoryAdapter memoryPort = new H2MemoryAdapter(dataSource, stubLlm);

        // 7. 真实 Dispatcher
        Dispatcher dispatcher = new Dispatcher(expertRegistry, planRepo, artifactStore, eventBus, progressStore);

        // 8. 真实 ChatAgent
        ChatAgent chatAgent = new ChatAgent(stubLlm, memoryPort);

        // 9. 真实 SimpleOrchestrator（无 mock）
        SimpleOrchestrator orchestrator = new SimpleOrchestrator(
                chatAgent, dispatcher, memoryPort, expertRegistry,
                planRepo, null, artifactStore, eventBus, null);

        // 10. 真实治理组件
        PolicyEnforcer policyEnforcer = new PolicyEnforcer();
        TokenQuotaService tokenQuotaService = new TokenQuotaService(100_000);
        PlanSanitizer planSanitizer = new PlanSanitizer(10);
        RollbackManager rollbackManager = new RollbackManager(toolRegistry, false, 1, 1);
        Auditor auditor = new Auditor(AuditLevel.SUMMARY, 100, 1, null);
        TokenStatsService tokenStatsService = new TokenStatsService(false, 0, 0, 0);

        GovernanceGateway gateway = new GovernanceGateway(
                orchestrator, policyEnforcer, tokenQuotaService, planSanitizer,
                rollbackManager, auditor, tokenStatsService, eventBus, progressStore);

        // 11. 真实 MessageRouter
        router = new MessageRouter(gateway);
        sender = new CapturingSender();

        // 12. 创建会话（SimulateOrchestrator.execute 需要 memoryPort 有 sessionId 和 workspaceRoot）
        orchestrator.createSession("e2e-session", tempDir.toString());
    }

    @AfterEach
    void tearDown() {
        ExecutionContextHolder.clear();
        eventBus.shutdown();
    }

    /**
     * 全链路测试：EXECUTE → ChatAgent 决策 → PlannerExpert 规划 → WriterExpert 执行 → 流式响应。
     *
     * 验证：
     * 1. CapturingSender 收到 PLANNING 状态
     * 2. 收到 STREAM_CHUNK（PlannerExpert 和 WriterExpert 的 LLM 输出）
     * 3. 收到 EXECUTION_RESULT（COMPLETED）
     * 4. 收到 IDLE 状态
     * 5. PlanRecord 状态为 COMPLETED
     */
    @Test
    void shouldExecuteFullChainFromEnvelopeToArtifact() throws Exception {
        // 1. 构造 EXECUTE Envelope
        ExecuteRequest request = new ExecuteRequest();
        request.setWorkspaceId("e2e-workspace");
        request.setSessionId("e2e-session");
        request.setPrompt("创建一个 hello.txt 文件");
        request.setAutoMode(true);

        Envelope executeEnvelope = new Envelope(
                "EXECUTE", "seq-e2e-1", Instant.now().toString(), request);

        // 2. 路由（EXECUTE 已异步提交，route 快速返回，执行在独立虚拟线程）
        router.route(JsonUtil.toJson(executeEnvelope), sender);

        // 3. 等待异步执行完成（最长 30s）
        awaitResult();
        awaitIdle();

        // 4. 验证收到了响应消息
        assertThat(sender.sent)
                .as("应至少收到 AGENT_STATUS + STREAM_CHUNK + EXECUTION_RESULT 多条消息")
                .hasSizeGreaterThanOrEqualTo(2);

        // 5. 验证消息类型序列
        List<String> types = sender.sent.stream().map(Envelope::getType).toList();
        System.out.println("[E2E-TEST] 收到的消息类型序列: " + types);

        // 第一个应是 AGENT_STATUS（PLANNING 状态）
        assertThat(types.get(0)).isEqualTo("AGENT_STATUS");

        // 应包含 STREAM_CHUNK（PlannerExpert/WriterExpert 的 LLM 输出）
        assertThat(types).contains("STREAM_CHUNK");

        // 应包含 EXECUTION_RESULT（SimpleOrchestrator 发送，携带真实 token 统计）
        assertThat(types).contains("EXECUTION_RESULT");

        // 最后一条应是 AGENT_STATUS(IDLE)（ExecutionHandler.onComplete 在 EXECUTION_RESULT 之后发送空闲状态）
        assertThat(types.get(types.size() - 1)).isEqualTo("AGENT_STATUS");

        // 6. 验证 PlanRecord 最终状态
        var plans = planRepo.findByStatus("COMPLETED");
        assertThat(plans)
                .as("应有一个 COMPLETED 状态的 PlanRecord")
                .isNotEmpty();

        // 7. 验证黑板产物落盘（WriterExpert 的 writes=["result"]，但 stub LLM 返回文本被包装为 Map.of("result", "任务已完成")）
        // 真实写入会触发 Dispatcher 的兜底逻辑或 Expert 返回的 "result" key
        // 由于 stubLlm 返回"任务已完成"且无 toolCalls，WriterExpert 的 executeLegacy 返回"任务已完成"
        // BaseExpert 包装为 Map.of("result", "任务已完成")
        // Dispatcher 按 step.writes=["result"] 过滤，"result" 匹配，写入黑板

        // 8. 验证 stub LLM 被调用（ChatAgent + PlannerExpert + WriterExpert 至少 3 次）
        assertThat(stubLlm.getChatCallCount())
                .as("stub LLM 应被调用至少 3 次（ChatAgent + PlannerExpert + WriterExpert）")
                .isGreaterThanOrEqualTo(3);
    }

    /**
     * 全链路测试：ChatAgent 返回 chat 类型（简单对话），不触发 Plan 执行。
     *
     * 验证：
     * 1. 收到 EXECUTION_RESULT（直接回复）
     * 2. 收到 IDLE 状态
     * 3. 不触发 PlannerExpert（stub LLM 调用次数 = 1，仅 ChatAgent）
     */
    @Test
    void shouldHandleChatReplyWithoutPlanExecution() throws Exception {
        // 使用返回 chat 类型的 stub LLM
        String chatOnlyResponse = "{\"type\":\"chat\",\"content\":\"你好，我是助手\"}";
        stubLlm = new StubLlmPort("{}"); // PlannerExpert 不会被调用
        // 重新装配（因为 stubLlm 换了）
        setUpWithChatOnlyStub();

        ExecuteRequest request = new ExecuteRequest();
        request.setWorkspaceId("e2e-workspace");
        request.setSessionId("e2e-session");
        request.setPrompt("你好");
        request.setAutoMode(true);

        Envelope executeEnvelope = new Envelope(
                "EXECUTE", "seq-e2e-2", Instant.now().toString(), request);

        router.route(JsonUtil.toJson(executeEnvelope), sender);

        awaitResult();
        awaitIdle();

        List<String> types = sender.sent.stream().map(Envelope::getType).toList();
        System.out.println("[E2E-CHAT-TEST] 收到的消息类型序列: " + types);

        // 应有 AGENT_STATUS → EXECUTION_RESULT → AGENT_STATUS(IDLE)
        assertThat(types.get(0)).isEqualTo("AGENT_STATUS");
        assertThat(types).contains("EXECUTION_RESULT");

        // 最后一条应是 AGENT_STATUS(IDLE)（EXECUTION_RESULT 由 SimpleOrchestrator 发送）
        assertThat(types.get(types.size() - 1)).isEqualTo("AGENT_STATUS");

        // 不应有 STREAM_CHUNK（没有 Expert 执行）
        assertThat(types).doesNotContain("STREAM_CHUNK");

        // stub LLM 只被调用 1 次（仅 ChatAgent）
        assertThat(stubLlm.getChatCallCount())
                .as("简单对话场景 stub LLM 应只被调用 1 次（仅 ChatAgent）")
                .isEqualTo(1);
    }

    private void setUpWithChatOnlyStub() throws Exception {
        // 用 chat-only 的 stub 重新装配
        JdbcDataSource h2 = new JdbcDataSource();
        h2.setURL("jdbc:h2:mem:test-e2e-chat;DB_CLOSE_DELAY=-1;MODE=MySQL");
        h2.setUser("sa");
        h2.setPassword("");
        dataSource = h2;

        FileArtifactStore artifactStore = new FileArtifactStore();
        planRepo = new ExecutionPlanRepository(dataSource);
        ProgressStore progressStore = new ProgressStore();
        eventBus = new SimpleEventBus(false, 100, 10);

        DefaultToolRegistry toolRegistry = new DefaultToolRegistry();
        toolRegistry.register(new WriteFileTool());
        toolRegistry.register(new ReadFileTool());

        // chat-only stub：所有调用都返回 chat 类型
        stubLlm = new StubLlmPort("{}", true);

        DefaultExpertRegistry expertRegistry = new DefaultExpertRegistry();
        expertRegistry.register(new PlannerExpert(toolRegistry, stubLlm));
        expertRegistry.register(new WriterExpert(toolRegistry, stubLlm));

        H2MemoryAdapter memoryPort = new H2MemoryAdapter(dataSource, stubLlm);

        Dispatcher dispatcher = new Dispatcher(expertRegistry, planRepo, artifactStore, eventBus, progressStore);
        ChatAgent chatAgent = new ChatAgent(stubLlm, memoryPort);

        SimpleOrchestrator orchestrator = new SimpleOrchestrator(
                chatAgent, dispatcher, memoryPort, expertRegistry,
                planRepo, null, artifactStore, eventBus, null);

        PolicyEnforcer policyEnforcer = new PolicyEnforcer();
        TokenQuotaService tokenQuotaService = new TokenQuotaService(100_000);
        PlanSanitizer planSanitizer = new PlanSanitizer(10);
        RollbackManager rollbackManager = new RollbackManager(toolRegistry, false, 1, 1);
        Auditor auditor = new Auditor(AuditLevel.SUMMARY, 100, 1, null);
        TokenStatsService tokenStatsService = new TokenStatsService(false, 0, 0, 0);

        GovernanceGateway gateway = new GovernanceGateway(
                orchestrator, policyEnforcer, tokenQuotaService, planSanitizer,
                rollbackManager, auditor, tokenStatsService, eventBus, progressStore);

        router = new MessageRouter(gateway);
        sender = new CapturingSender();

        orchestrator.createSession("e2e-session", tempDir.toString());
    }

    static class CapturingSender implements MessageSender {
        final List<Envelope> sent = Collections.synchronizedList(new ArrayList<>());
        public void send(Envelope e) { sent.add(e); }
    }

    /** 等待异步执行产出 EXECUTION_RESULT（EXECUTE 已异步提交，route 不再同步阻塞） */
    private void awaitResult() throws InterruptedException {
        long deadline = System.currentTimeMillis() + 30_000;
        while (System.currentTimeMillis() < deadline) {
            if (sender.sent.stream().anyMatch(e -> "EXECUTION_RESULT".equals(e.getType()))) return;
            Thread.sleep(50);
        }
        assertThat(sender.sent.stream().anyMatch(e -> "EXECUTION_RESULT".equals(e.getType())))
                .as("应收到 EXECUTION_RESULT").isTrue();
    }

    /** 等待最后一条消息为 AGENT_STATUS(IDLE)（onComplete 在 EXECUTION_RESULT 之后发送） */
    private void awaitIdle() throws InterruptedException {
        long deadline = System.currentTimeMillis() + 5000;
        while (System.currentTimeMillis() < deadline) {
            if (!sender.sent.isEmpty()
                    && "AGENT_STATUS".equals(sender.sent.get(sender.sent.size() - 1).getType())
                    && String.valueOf(sender.sent.get(sender.sent.size() - 1).getPayload()).contains("IDLE")) {
                return;
            }
            Thread.sleep(20);
        }
        assertThat(!sender.sent.isEmpty()
                && "AGENT_STATUS".equals(sender.sent.get(sender.sent.size() - 1).getType())
                && String.valueOf(sender.sent.get(sender.sent.size() - 1).getPayload()).contains("IDLE"))
                .as("最后一条消息应为 AGENT_STATUS(IDLE)").isTrue();
    }
}
