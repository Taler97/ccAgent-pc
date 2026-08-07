package com.ccagent.core.context;

import com.ccagent.api.model.plan.Plan;
import com.ccagent.api.model.plan.Step;
import com.ccagent.api.model.response.StreamChunk;
import com.ccagent.api.port.Expert;
import com.ccagent.api.port.ExpertRegistryPort;
import com.ccagent.api.port.MemoryPort;
import com.ccagent.api.port.StreamObserver;
import com.ccagent.api.port.ToolRegistryPort;
import com.ccagent.core.orchestration.agent.ChatAgent;
import com.ccagent.core.orchestration.dispatcher.Dispatcher;
import com.ccagent.core.orchestration.orchestrator.SimpleOrchestrator;
import com.ccagent.core.orchestration.repository.ExecutionPlanRepository;
import com.ccagent.core.orchestration.repository.model.PlanRecord;
import com.ccagent.kernel.event.EventBus;
import com.ccagent.kernel.event.SimpleEventBus;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.sql.DataSource;

import org.h2.jdbcx.JdbcDataSource;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * 黑板断点恢复集成测试
 * <p>端到端验证：</p>
 * <ul>
 *   <li>真实 H2 + 真实磁盘 + 真实 FileArtifactStore</li>
 *   <li>产物持久化 → 崩溃 → 重启恢复 → 续跑未完成步骤</li>
 *   <li>SimpleOrchestrator.recoverInterruptedPlans 端到端流程</li>
 *   <li>并发写同一 Key 的原子性</li>
 * </ul>
 */
class BlackboardRecoveryIntegrationTest {

    @TempDir Path tempDir;

    private JdbcDataSource dataSource;
    private FileArtifactStore artifactStore;
    private ExecutionPlanRepository planRepo;

    @BeforeEach
    void setUp() {
        dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:test-blackboard;DB_CLOSE_DELAY=-1;MODE=MySQL");
        dataSource.setUser("sa");
        dataSource.setPassword("");

        artifactStore = new FileArtifactStore();
        planRepo = new ExecutionPlanRepository(dataSource);

        // 清理上一测试残留的 RUNNING 状态 plan，避免 recoverInterruptedPlans 查到跨测试数据
        try (var conn = dataSource.getConnection();
             var stmt = conn.createStatement()) {
            stmt.execute("DELETE FROM execution_plan");
            stmt.execute("DELETE FROM execution_step");
        } catch (Exception ignored) {}
    }

    @AfterEach
    void tearDown() {
        // H2 内存库随 JVM 退出自动清理
    }

    /**
     * 场景1：3 步计划，执行第 1 步后"崩溃"，
     *        重启时从磁盘恢复 analysis 产物，跳过已完成步骤，续跑剩余
     */
    @Test
    void shouldResumeFromCrashWithRestoredArtifacts() throws Exception {
        // S1 writes "analysis"（持久化），S2 reads "analysis" writes "code"，S3 reads "code" writes "summary"
        Step s1 = new Step(1, "researcher", "研究", List.of());
        s1.setWrites(Set.of("analysis"));
        s1.setPersistOutput(true);
        Step s2 = new Step(2, "writer", "撰写", List.of());
        s2.setReads(Set.of("analysis"));
        s2.setWrites(Set.of("code"));
        s2.setPersistOutput(true);
        Step s3 = new Step(3, "summarizer", "摘要", List.of());
        s3.setReads(Set.of("code"));
        s3.setWrites(Set.of("summary"));
        Plan plan = new Plan("crash-test", List.of(s1, s2, s3), List.of());

        String sessionId = "session-crash";
        String workspaceRoot = tempDir.toString();

        // === Phase 1：只执行第 1 步，然后"崩溃" ===
        // mock expert：S1 执行后抛异常模拟崩溃
        ExpertRegistryPort expertRegistry = mock(ExpertRegistryPort.class);
        Expert mockExpert = mock(Expert.class);
        when(expertRegistry.getExpert(anyString())).thenReturn(mockExpert);

        // S1 成功执行，S2 时模拟崩溃
        AtomicInteger execCount = new AtomicInteger(0);
        when(mockExpert.execute(anyString(), any(), any(), anyString(), any()))
                .thenAnswer(inv -> {
                    int n = execCount.incrementAndGet();
                    if (n == 1) return java.util.Map.of("analysis", "analysis-data"); // S1 成功
                    throw new RuntimeException("模拟崩溃"); // S2 失败
                });

        EventBus eventBus = new SimpleEventBus(false, 100, 10);
        Dispatcher dispatcher = new Dispatcher(expertRegistry, planRepo, artifactStore, eventBus);

        // 持久化 Plan
        int planVersion = planRepo.createPlan(sessionId, plan);
        planRepo.updatePlanStatus(sessionId, planVersion, "RUNNING");

        // 执行（会因 S2 崩溃而失败）
        String recoveryKey = sessionId + "_" + planVersion;
        TaskContextImpl task1 = new TaskContextImpl(recoveryKey, plan, artifactStore, workspaceRoot);
        AtomicBoolean cancelled = new AtomicBoolean(false);
        StreamObserver<StreamChunk> observer = mock(StreamObserver.class);

        try {
            dispatcher.execute(plan, "seq-1", sessionId, planVersion, workspaceRoot,
                    cancelled, observer, task1, Set.of());
        } catch (Exception ignored) {
            // 预期会抛异常
        } finally {
            task1.close();
        }

        // 验证 S1 已完成并写入 completed_step_ids
        Set<Integer> completedIds = planRepo.getCompletedStepIds(sessionId, planVersion);
        assertThat(completedIds).contains(1);

        // 验证 analysis 产物已持久化到磁盘
        waitForAsyncSave(tempDir, recoveryKey, "analysis");
        Optional<String> persisted = artifactStore.load(workspaceRoot, recoveryKey, "analysis", String.class);
        assertThat(persisted).isPresent().contains("analysis-data");

        // === Phase 2：重启，从磁盘恢复，续跑 ===
        TaskContextImpl task2 = new TaskContextImpl(recoveryKey, plan, artifactStore, workspaceRoot);
        task2.restoreFromDisk(recoveryKey);

        // 恢复后的 analysis 应立即可用
        assertThat(task2.getRestoredKeys()).contains("analysis");
        assertThat(task2.<String>getArtifact("analysis").join()).isEqualTo("analysis-data");

        // 重新 mock expert：这次 S2、S3 都能成功
        Expert freshExpert = mock(Expert.class);
        AtomicInteger freshCount = new AtomicInteger(0);
        when(freshExpert.execute(anyString(), any(), any(), anyString(), any()))
                .thenAnswer(inv -> {
                    int n = freshCount.incrementAndGet();
                    if (n == 1) return java.util.Map.of("code", "code-data"); // S2
                    return java.util.Map.of("summary", "summary-data"); // S3
                });
        when(expertRegistry.getExpert(anyString())).thenReturn(freshExpert);

        // 用 skipIds 跳过已完成的 S1
        StreamObserver<StreamChunk> observer2 = mock(StreamObserver.class);
        try {
            dispatcher.execute(plan, "seq-2", sessionId, planVersion, workspaceRoot,
                    new AtomicBoolean(false), observer2, task2, completedIds);
        } finally {
            task2.close();
        }

        // 验证 S2、S3 都执行了（freshExpert 被调用 2 次）
        verify(freshExpert, times(2)).execute(anyString(), any(), any(), anyString(), any());

        // 验证全部步骤都标记为已完成
        Set<Integer> allCompleted = planRepo.getCompletedStepIds(sessionId, planVersion);
        assertThat(allCompleted).containsExactlyInAnyOrder(1, 2, 3);

        // Plan 状态应为 COMPLETED
        PlanRecord finalRecord = planRepo.getPlan(sessionId, planVersion);
        assertThat(finalRecord.getOverallStatus()).isEqualTo("COMPLETED");
    }

    /**
     * 场景2：全部步骤都已完成，重启时直接标记 COMPLETED
     */
    @Test
    void shouldMarkCompletedWhenAllStepsAlreadyDone() {
        Step s1 = new Step(1, "researcher", "研究", List.of());
        s1.setWrites(Set.of("a"));
        Plan plan = new Plan("all-done", List.of(s1), List.of());

        String sessionId = "session-done";
        String workspaceRoot = tempDir.toString();

        // 手动创建一条 RUNNING 状态的 Plan，标记所有步骤已完成
        int planVersion = planRepo.createPlan(sessionId, plan);
        planRepo.updatePlanStatus(sessionId, planVersion, "RUNNING");
        planRepo.addCompletedStep(sessionId, planVersion, 1);

        // 用 SimpleOrchestrator 跑恢复
        ChatAgent chatAgent = mock(ChatAgent.class);
        Dispatcher dispatcher = mock(Dispatcher.class);
        MemoryPort memoryPort = mock(MemoryPort.class);
        when(memoryPort.loadWorkspaceRoot(sessionId)).thenReturn(workspaceRoot);
        ExpertRegistryPort expertRegistry = mock(ExpertRegistryPort.class);

        SimpleOrchestrator orchestrator = new SimpleOrchestrator(
                chatAgent, dispatcher, memoryPort, expertRegistry,
                planRepo, null, artifactStore);

        int recovered = orchestrator.recoverInterruptedPlans();

        assertThat(recovered).isEqualTo(1);
        // Dispatcher 不应被调用（因为全部完成）
        verify(dispatcher, never()).execute(any(), anyString(), anyString(), anyInt(),
                anyString(), any(), any(), any(), any());

        // 状态应已标记为 COMPLETED
        PlanRecord record = planRepo.getPlan(sessionId, planVersion);
        assertThat(record.getOverallStatus()).isEqualTo("COMPLETED");
    }

    /**
     * 场景3：并发写同一 Key，最终值一致且只保留一份
     */
    @Test
    void shouldHandleConcurrentWritesToSameKey() throws Exception {
        Plan plan = new Plan("concurrent", List.of(), List.of());
        TaskContextImpl task = new TaskContextImpl("seq-concurrent", plan, artifactStore, tempDir.toString());

        int threadCount = 10;
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(threadCount);

        // 10 个虚拟线程并发写同一 Key（不标记持久化，避免磁盘竞态干扰测试目的）
        for (int i = 0; i < threadCount; i++) {
            final String value = "value-" + i;
            Thread.startVirtualThread(() -> {
                try {
                    startLatch.await();
                    task.putArtifact("shared-key", value);
                } catch (Exception ignored) {
                } finally {
                    doneLatch.countDown();
                }
            });
        }

        startLatch.countDown();
        assertThat(doneLatch.await(5, TimeUnit.SECONDS)).isTrue();

        // 最终值应该是 10 个中的一个（CompletableFuture.complete 只有第一个成功）
        String finalValue = task.<String>getArtifact("shared-key").join();
        assertThat(finalValue).startsWith("value-");
        assertThat(Integer.parseInt(finalValue.substring("value-".length())))
                .isBetween(0, threadCount - 1);

        task.close();
    }

    /**
     * 场景4：恢复时磁盘无产物，黑板仍可用（空恢复）
     */
    @Test
    void shouldHandleEmptyDiskRecoveryGracefully() {
        Plan plan = new Plan("empty", List.of(), List.of());
        TaskContextImpl task = new TaskContextImpl("nonexistent-key", plan, artifactStore, tempDir.toString());

        // 磁盘上无产物，恢复应无异常
        task.restoreFromDisk("nonexistent-key");
        assertThat(task.getRestoredKeys()).isEmpty();

        // 恢复后黑板仍可正常使用
        task.putArtifact("new-key", "new-value");
        assertThat(task.<String>getArtifact("new-key").join()).isEqualTo("new-value");

        task.close();
    }

    /**
     * 场景5：用户手动删除黑板产物文件后，恢复时能检测到缺失 Key（供上层决策重跑）
     */
    @Test
    void shouldDetectManuallyDeletedArtifactAsMissing() {
        Plan plan = new Plan("deleted", List.of(), List.of());
        String seqId = "seq-deleted";
        String workspaceRoot = tempDir.toString();

        // 先持久化产物
        TaskContextImpl writer = new TaskContextImpl(seqId, plan, artifactStore, workspaceRoot);
        writer.markPersistent("analysis");
        writer.putArtifact("analysis", "analysis-data");
        writer.close();
        waitForAsyncSave(tempDir, seqId, "analysis");

        // 模拟用户手动删除产物文件（清单仍在）
        Path artifact = tempDir.resolve(".codepal/cache/artifacts/" + seqId + "/analysis.json");
        assertThat(artifact).exists();
        try {
            java.nio.file.Files.delete(artifact);
        } catch (Exception e) {
            throw new AssertionError("删除产物文件失败", e);
        }

        // 重启恢复：缺失 Key 被检测出来，未进入 restoredKeys
        TaskContextImpl task = new TaskContextImpl(seqId, plan, artifactStore, workspaceRoot);
        task.restoreFromDisk(seqId);
        assertThat(task.getMissingKeys()).contains("analysis");
        assertThat(task.getRestoredKeys()).doesNotContain("analysis");

        // 缺失产物不可用（futures 未完成，挂起等待生产者重跑）
        assertThat(task.getArtifact("analysis").isDone()).isFalse();
        task.close();
    }

    /**
     * 等待异步保存完成：轮询检查文件是否出现
     */
    private static void waitForAsyncSave(Path workspaceRoot, String seqId, String key) {
        Path file = workspaceRoot.resolve(".codepal/cache/artifacts/" + seqId + "/" + key + ".json");
        long deadline = System.currentTimeMillis() + 2000;
        while (!java.nio.file.Files.exists(file) && System.currentTimeMillis() < deadline) {
            try {
                Thread.sleep(20);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
    }
}
