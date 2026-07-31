package com.ccagent.presentation.orchestration;

import com.ccagent.adapter.llm.DeepSeekLlmAdapter;
import com.ccagent.api.model.plan.Plan;
import com.ccagent.api.model.plan.Step;
import com.ccagent.api.model.response.StreamChunk;
import com.ccagent.api.port.ExpertRegistryPort;
import com.ccagent.api.port.LlmPort;
import com.ccagent.api.port.StreamObserver;
import com.ccagent.core.context.FileArtifactStore;
import com.ccagent.core.context.TaskContextImpl;
import com.ccagent.core.expert.DefaultExpertRegistry;
import com.ccagent.core.orchestration.dispatcher.Dispatcher;
import com.ccagent.core.orchestration.progress.ProgressStore;
import com.ccagent.core.orchestration.repository.ExecutionPlanRepository;
import com.ccagent.core.tool.DefaultToolRegistry;
import com.ccagent.infrastructure.config.AppConfig;
import com.ccagent.infrastructure.config.ConfigLoader;
import com.ccagent.infrastructure.util.JsonUtil;
import com.ccagent.kernel.event.SimpleEventBus;
import com.ccagent.plugin.experts.impl.ResearcherExpert;
import com.ccagent.plugin.experts.impl.SummarizerExpert;
import com.ccagent.plugin.experts.impl.WriterExpert;
import com.ccagent.plugin.tools.impl.EditFileTool;
import com.ccagent.plugin.tools.impl.FileOperationTool;
import com.ccagent.plugin.tools.impl.ListDirectoryTool;
import com.ccagent.plugin.tools.impl.ReadFileTool;
import com.ccagent.plugin.tools.impl.SearchContentTool;
import com.ccagent.plugin.tools.impl.WriteFileTool;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

/**
 * Dispatcher 进度查询集成测试（真实组件 + 真实 DeepSeek API）
 * <p>全部使用生产类：真实专家（WriterExpert/ResearcherExpert/SummarizerExpert）、
 * 真实工具（WriteFileTool 等，写入 @TempDir 真实磁盘）、真实 DeepSeekLlmAdapter
 * （调用 DeepSeek API，key 来自 ccagent.yaml）、H2 内存库、真实 EventBus。</p>
 *
 * <p>注意：LLM 为真实外部调用，行为不可控（是否调工具、耗时均不确定），
 * 故断言聚焦查询链路本身：状态流转、快照结构、活动不泄漏、完成后销毁；
 * 不对 LLM 生成内容做精确断言。</p>
 */
@Tag("real-llm")
class DispatcherProgressIntegrationTest {

    @TempDir Path tempDir;

    private JdbcDataSource dataSource;
    private FileArtifactStore artifactStore;
    private ExecutionPlanRepository planRepo;
    private ProgressStore progressStore;
    private Dispatcher dispatcher;
    private CollectingObserver observer;

    @BeforeEach
    void setUp() {
        // 真实 LLM：从 pcAgent 根目录 ccagent.yaml 读取 DeepSeek 配置
        AppConfig config = ConfigLoader.load("../ccagent.yaml");
        String apiKey = config.getLlm().getApiKey();
        assertThat(apiKey).as("ccagent.yaml 中未配置 llm.api-key").isNotBlank();

        dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:test-progress;DB_CLOSE_DELAY=-1;MODE=MySQL");
        dataSource.setUser("sa");
        dataSource.setPassword("");
        artifactStore = new FileArtifactStore();
        planRepo = new ExecutionPlanRepository(dataSource);
        progressStore = new ProgressStore();
        observer = new CollectingObserver();

        // 真实工具注册
        DefaultToolRegistry toolRegistry = new DefaultToolRegistry();
        toolRegistry.register(new WriteFileTool());
        toolRegistry.register(new ReadFileTool());
        toolRegistry.register(new EditFileTool());
        toolRegistry.register(new SearchContentTool());
        toolRegistry.register(new ListDirectoryTool());
        toolRegistry.register(new FileOperationTool());

        // 真实 LLM 适配器
        LlmPort llm = new DeepSeekLlmAdapter(
                apiKey, config.getLlm().getBaseUrl(), config.getLlm().getModel());

        // 真实专家注册
        DefaultExpertRegistry expertRegistry = new DefaultExpertRegistry();
        expertRegistry.register(new WriterExpert(toolRegistry, llm));
        expertRegistry.register(new ResearcherExpert(toolRegistry, llm));
        expertRegistry.register(new SummarizerExpert(toolRegistry, llm));

        dispatcher = new Dispatcher(expertRegistry, planRepo, artifactStore,
                new SimpleEventBus(false, 100, 10), progressStore);
    }

    /** 手写流观察者：收集所有 chunk，等待完成信号 */
    static class CollectingObserver implements StreamObserver<StreamChunk> {
        final List<StreamChunk> chunks = Collections.synchronizedList(new ArrayList<>());
        final CountDownLatch done = new CountDownLatch(1);
        volatile Throwable error;

        @Override
        public void onNext(StreamChunk chunk) {
            chunks.add(chunk);
        }

        @Override
        public void onError(Throwable e) {
            error = e;
            System.out.println("[诊断] onError: " + e);
            e.printStackTrace(System.out);
            for (StreamChunk c : chunks) {
                if (c.getContent() != null && c.getContent().contains("FAILED")) {
                    System.out.println("[诊断] chunk " + c.getType() + ": " + c.getContent());
                }
            }
            done.countDown();
        }

        @Override
        public void onComplete() {
            done.countDown();
        }

        boolean hasActivity() {
            return chunks.stream().anyMatch(c -> "ACTIVITY".equals(c.getType()));
        }
    }

    /**
     * 场景：5 步黑板依赖 DAG，批次1（writer、researcher）并发执行，执行中查询进度
     * <p>验证：并发步骤进入 RUNNING、快照结构完整、活动不泄漏到下游、完成后销毁。</p>
     */
    @SuppressWarnings("unchecked")
    @Test
    void shouldQueryConcurrentStepProgressDuringExecution() throws Exception {
        Step s1 = new Step(1, "writer", "使用 write_file 工具写入文件 a.java，内容为 class A {}", List.of());
        s1.setWrites(Set.of("k1"));
        Step s2 = new Step(2, "researcher", "使用 search_content 工具搜索关键词 ccagent 并总结", List.of());
        s2.setWrites(Set.of("k2"));
        Step s3 = new Step(3, "writer", "根据前面的产物用 write_file 写入 b.java", List.of());
        s3.setReads(Set.of("k1", "k2"));
        s3.setWrites(Set.of("k3"));
        Step s4 = new Step(4, "researcher", "研究 b.java 的内容并总结", List.of());
        s4.setReads(Set.of("k3"));
        s4.setWrites(Set.of("k4"));
        Step s5 = new Step(5, "summarizer", "总结整个执行过程", List.of());
        s5.setReads(Set.of("k4"));
        Plan plan = new Plan("progress-test", List.of(s1, s2, s3, s4, s5), List.of());

        String seqId = "seq-progress";
        String sessionId = "session-progress";
        int planVersion = planRepo.createPlan(sessionId, plan);
        planRepo.updatePlanStatus(sessionId, planVersion, "EXECUTING");

        TaskContextImpl task = new TaskContextImpl(sessionId + "_" + planVersion, plan, artifactStore, tempDir.toString());
        AtomicBoolean cancelled = new AtomicBoolean(false);

        Thread execThread = Thread.startVirtualThread(() -> {
            dispatcher.execute(plan, seqId, sessionId, planVersion, tempDir.toString(),
                    cancelled, observer, task, Set.of());
        });

        try {
            // 批次1（步骤1、2 并发）执行中：等待出现 RUNNING 步骤
            awaitAnyRunning(seqId, List.of(1, 2), 90_000);

            // 1. 全量快照：步骤1/2 至少一个 RUNNING，结构完整
            String snapJson = progressStore.snapshot(seqId);
            assertThat(snapJson).isNotNull();
            Map<String, Object> snap = JsonUtil.fromJson(snapJson, Map.class);
            assertThat(snap.get("totalSteps")).isEqualTo(5);
            Map<Integer, Map<String, Object>> byId = indexSteps((List<Map<String, Object>>) snap.get("steps"));
            assertThat(byId).containsKeys(1, 2, 3, 4, 5);
            boolean s1Running = "RUNNING".equals(byId.get(1).get("status"));
            boolean s2Running = "RUNNING".equals(byId.get(2).get("status"));
            assertThat(s1Running || s2Running)
                    .as("批次1 中至少一个步骤应处于 RUNNING").isTrue();

            // 2. 单步骤查询：查询正在运行的步骤，字段完整
            Map<String, Object> runningStep = byId.get(1);
            int queryStepId = "RUNNING".equals(runningStep.get("status")) ? 1 : 2;
            Map<String, Object> single = JsonUtil.fromJson(
                    progressStore.snapshotStep(seqId, queryStepId), Map.class);
            assertThat(single.get("status")).isEqualTo("RUNNING");
            assertThat(single.get("stepId")).isEqualTo(queryStepId);
            assertThat(single).containsKeys("expertName", "task", "activity", "elapsedMs", "totalSteps");

            // 3. 等待全部完成
            assertThat(observer.done.await(120, TimeUnit.SECONDS)).isTrue();
            assertThat(observer.error).isNull();
        } finally {
            execThread.join(120_000);
            task.close();
        }

        // 4. ACTIVITY 事件不泄漏到下游（只写 store，不推送）
        assertThat(observer.hasActivity()).isFalse();

        // 5. 任务结束销毁：查询返回 null
        assertThat(progressStore.snapshot(seqId)).isNull();
        assertThat(progressStore.snapshotStep(seqId, 1)).isNull();
    }

    /**
     * 场景：断点续存，skipIds 传入的已完成步骤在快照中标记 SUCCESS
     */
    @SuppressWarnings("unchecked")
    @Test
    void shouldMarkSkippedStepsAsCompletedInSnapshot() throws Exception {
        Step s1 = new Step(1, "writer", "使用 write_file 写入 a.java", List.of());
        s1.setWrites(Set.of("k1"));
        Step s2 = new Step(2, "researcher", "总结执行结果", List.of());
        Plan plan = new Plan("resume-test", List.of(s1, s2), List.of());

        String seqId = "seq-resume";
        String sessionId = "session-resume";
        int planVersion = planRepo.createPlan(sessionId, plan);
        planRepo.updatePlanStatus(sessionId, planVersion, "EXECUTING");

        TaskContextImpl task = new TaskContextImpl(sessionId + "_" + planVersion, plan, artifactStore, tempDir.toString());
        AtomicBoolean cancelled = new AtomicBoolean(false);

        // 断点续存：步骤1 已完成，仅执行步骤2
        Thread execThread = Thread.startVirtualThread(() -> {
            dispatcher.execute(plan, seqId, sessionId, planVersion, tempDir.toString(),
                    cancelled, observer, task, Set.of(1));
        });

        try {
            // 步骤2 执行中查询：步骤1 已标记 SUCCESS（skipIds），步骤2 RUNNING
            awaitAnyRunning(seqId, List.of(2), 90_000);
            String snapJson = progressStore.snapshot(seqId);
            assertThat(snapJson).isNotNull();
            Map<String, Object> snap = JsonUtil.fromJson(snapJson, Map.class);
            Map<Integer, Map<String, Object>> byId = indexSteps((List<Map<String, Object>>) snap.get("steps"));
            assertThat(byId.get(1).get("status")).isEqualTo("SUCCESS");
            assertThat(byId.get(2).get("status")).isEqualTo("RUNNING");
        } finally {
            execThread.join(120_000);
            task.close();
        }

        assertThat(observer.done.await(120, TimeUnit.SECONDS)).isTrue();
        assertThat(observer.error).isNull();
    }

    // ==================== 辅助方法 ====================

    private void awaitAnyRunning(String seqId, List<Integer> stepIds, long timeoutMs) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            for (int stepId : stepIds) {
                String json = progressStore.snapshotStep(seqId, stepId);
                if (json != null) {
                    Map<String, Object> s = JsonUtil.fromJson(json, Map.class);
                    if ("RUNNING".equals(s.get("status"))) return;
                }
            }
            Thread.sleep(50);
        }
        fail("步骤 " + stepIds + " 未在 " + timeoutMs + "ms 内进入 RUNNING（真实 LLM 调用可能较慢）");
    }

    private Map<Integer, Map<String, Object>> indexSteps(List<Map<String, Object>> steps) {
        Map<Integer, Map<String, Object>> byId = new HashMap<>();
        for (Map<String, Object> s : steps) {
            byId.put(((Number) s.get("stepId")).intValue(), s);
        }
        return byId;
    }
}
