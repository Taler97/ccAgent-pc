package com.ccagent.core.orchestration.dispatcher;

import com.ccagent.api.context.TaskScope;
import com.ccagent.api.model.plan.Plan;
import com.ccagent.api.model.plan.Step;
import com.ccagent.api.model.response.StreamChunk;
import com.ccagent.api.port.Expert;
import com.ccagent.api.port.ExpertRegistryPort;
import com.ccagent.api.port.StreamObserver;
import com.ccagent.core.context.FileArtifactStore;
import com.ccagent.core.context.TaskContextImpl;
import com.ccagent.core.orchestration.repository.ExecutionPlanRepository;
import com.ccagent.kernel.event.EventBus;
import com.ccagent.kernel.event.SimpleEventBus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class DispatcherTest {

    @TempDir Path tempDir;

    @Mock ExpertRegistryPort expertRegistry;
    @Mock Expert codeWriter;
    @Mock StreamObserver<StreamChunk> observer;
    @Mock ExecutionPlanRepository executionPlanRepository;

    @Test
    void shouldExecuteStepsInParallel() {
        Step s1 = new Step(1, "code-writer", "step 1", List.of("a.java"));
        Step s2 = new Step(2, "code-writer", "step 2", List.of("b.java"));
        Plan plan = new Plan("test", List.of(s1, s2), List.of("a.java", "b.java"));

        when(expertRegistry.getExpert("code-writer")).thenReturn(codeWriter);
        when(codeWriter.execute(anyString(), any(), any(), anyString(), any()))
                .thenAnswer(inv -> {
                    StreamObserver<StreamChunk> obs = inv.getArgument(4);
                    obs.onNext(new StreamChunk("seq-1", "STEP_CHUNK", "done", null));
                    return java.util.Map.of("result", "done");
                });

        EventBus eventBus = new SimpleEventBus(false, 100, 10);
        Dispatcher dispatcher = new Dispatcher(expertRegistry, executionPlanRepository,
                new FileArtifactStore(), eventBus);
        TaskContextImpl task = new TaskContextImpl("seq-1", plan, new FileArtifactStore(), tempDir.toString());

        try {
            dispatcher.execute(plan, "seq-1", "session-1", -1, tempDir.toString(),
                    new AtomicBoolean(false), observer, task, Set.of());
        } finally {
            task.close();
        }

        verify(codeWriter, times(2)).execute(anyString(), any(), any(), anyString(), any());
        verify(observer, atLeastOnce()).onNext(any());
    }

    @Test
    void shouldSendStepStatusForEachStep() {
        Plan plan = new Plan("single",
                List.of(new Step(1, "code-writer", "do it", List.of())),
                List.of());

        when(expertRegistry.getExpert("code-writer")).thenReturn(codeWriter);
        when(codeWriter.execute(anyString(), any(), any(), anyString(), any()))
                .thenReturn(java.util.Map.of("result", "ok"));

        EventBus eventBus = new SimpleEventBus(false, 100, 10);
        Dispatcher dispatcher = new Dispatcher(expertRegistry, executionPlanRepository,
                new FileArtifactStore(), eventBus);
        TaskContextImpl task = new TaskContextImpl("seq-1", plan, new FileArtifactStore(), tempDir.toString());

        try {
            dispatcher.execute(plan, "seq-1", "session-1", -1, tempDir.toString(),
                    new AtomicBoolean(false), observer, task, Set.of());
        } finally {
            task.close();
        }

        verify(observer, atLeastOnce()).onNext(any());
    }

    // ============ DAG 构建：文件依赖 ============

    @Test
    void shouldDetectDependencyBetweenSteps() {
        Step s1 = new Step(1, "code-writer", "step 1", List.of("a.java"));
        Step s2 = new Step(2, "code-writer", "step 2", List.of("a.java"));

        var deps = Dispatcher.buildDependencyGraph(List.of(s1, s2));

        assertThat(deps.get(2)).contains(1);
        assertThat(deps.get(1)).isEmpty();
    }

    @Test
    void shouldDetectNoDependencyBetweenIndependentSteps() {
        Step s1 = new Step(1, "code-writer", "step 1", List.of("a.java"));
        Step s2 = new Step(2, "code-writer", "step 2", List.of("b.java"));

        var deps = Dispatcher.buildDependencyGraph(List.of(s1, s2));

        assertThat(deps.get(2)).isEmpty();
        assertThat(deps.get(1)).isEmpty();
    }

    @Test
    void shouldDetectCyclicDependency() {
        Step s1 = new Step(1, "code-writer", "step 1", List.of("a.java", "b.java"));
        Step s2 = new Step(2, "code-writer", "step 2", List.of("b.java", "c.java"));
        Step s3 = new Step(3, "code-writer", "step 3", List.of("a.java", "c.java"));

        boolean hasCyclic = Dispatcher.hasCyclicDependency(List.of(s1, s2, s3));
        assertThat(hasCyclic).isFalse();
    }

    // ============ DAG 构建：黑板 Key 依赖 ============

    @Test
    void shouldBuildDependencyGraphWithBlackboardKeys() {
        Step s1 = new Step(1, "researcher", "研究", List.of());
        s1.setWrites(Set.of("analysis"));
        Step s2 = new Step(2, "writer", "撰写", List.of());
        s2.setReads(Set.of("analysis"));

        var deps = Dispatcher.buildDependencyGraph(List.of(s1, s2));

        assertThat(deps.get(2)).contains(1);
        assertThat(deps.get(1)).isEmpty();
    }

    @Test
    void shouldNotAddKeyDependencyWhenNoReadsWritesOverlap() {
        Step s1 = new Step(1, "researcher", "研究", List.of("a.java"));
        s1.setWrites(Set.of("analysis"));
        Step s2 = new Step(2, "note-taker", "记笔记", List.of("b.java"));
        s2.setReads(Set.of("notes"));

        var deps = Dispatcher.buildDependencyGraph(List.of(s1, s2));

        assertThat(deps.get(2)).isEmpty();
        assertThat(deps.get(1)).isEmpty();
    }

    @Test
    void shouldDetectCycleViaBlackboardKey() {
        Step a = new Step(1, "expert-a", "task a", List.of());
        a.setReads(Set.of("y"));
        a.setWrites(Set.of("x"));
        Step b = new Step(2, "expert-b", "task b", List.of());
        b.setReads(Set.of("x"));
        b.setWrites(Set.of("y"));

        boolean hasCyclic = Dispatcher.hasCyclicDependency(List.of(a, b));

        assertThat(hasCyclic).isTrue();
    }

    @Test
    void shouldFuseFileAndKeyDependencies() {
        Step s1 = new Step(1, "researcher", "研究", List.of("a.java"));
        s1.setWrites(Set.of("analysis"));
        Step s2 = new Step(2, "writer", "撰写", List.of("a.java"));
        s2.setReads(Set.of("analysis"));

        var deps = Dispatcher.buildDependencyGraph(List.of(s1, s2));

        assertThat(deps.get(2)).containsExactly(1);
    }

    // ============ 黑板数据流：专家间传递产物 ============

    @Test
    void shouldPassArtifactsBetweenSteps() {
        Step s1 = new Step(1, "researcher", "研究", List.of());
        s1.setWrites(Set.of("analysis"));
        Step s2 = new Step(2, "summarizer", "摘要", List.of());
        s2.setReads(Set.of("analysis"));
        Plan plan = new Plan("flow", List.of(s1, s2), List.of());

        when(expertRegistry.getExpert("researcher")).thenReturn(codeWriter);
        when(expertRegistry.getExpert("summarizer")).thenReturn(codeWriter);
        when(codeWriter.execute(anyString(), any(), any(), anyString(), any()))
                .thenReturn(java.util.Map.of("analysis", "analysis-result"))
                .thenAnswer(inv -> {
                    Map<String, Object> inputs = inv.getArgument(2);
                    assertThat(inputs.get("analysis")).isEqualTo("analysis-result");
                    return java.util.Map.of("summary", "summary-based-on-analysis");
                });

        EventBus eventBus = new SimpleEventBus(false, 100, 10);
        Dispatcher dispatcher = new Dispatcher(expertRegistry, executionPlanRepository,
                new FileArtifactStore(), eventBus);
        TaskContextImpl task = new TaskContextImpl("seq-1", plan, new FileArtifactStore(), tempDir.toString());

        try {
            dispatcher.execute(plan, "seq-1", "session-1", -1, tempDir.toString(),
                    new AtomicBoolean(false), observer, task, Set.of());
        } finally {
            task.close();
        }

        verify(codeWriter, times(2)).execute(anyString(), any(), any(), anyString(), any());
    }

    @Test
    void shouldSkipStepsInSkipIdsAndResumeFromRemaining() {
        Step s1 = new Step(1, "researcher", "研究", List.of());
        s1.setWrites(Set.of("a"));
        Step s2 = new Step(2, "researcher", "研究2", List.of());
        s2.setWrites(Set.of("b"));
        Step s3 = new Step(3, "researcher", "研究3", List.of());
        s3.setWrites(Set.of("c"));
        Plan plan = new Plan("resume", List.of(s1, s2, s3), List.of());

        when(expertRegistry.getExpert("researcher")).thenReturn(codeWriter);
        when(codeWriter.execute(anyString(), any(), any(), anyString(), any()))
                .thenReturn(java.util.Map.of("c", "step3-result"));

        EventBus eventBus = new SimpleEventBus(false, 100, 10);
        Dispatcher dispatcher = new Dispatcher(expertRegistry, executionPlanRepository,
                new FileArtifactStore(), eventBus);
        TaskContextImpl task = new TaskContextImpl("seq-1", plan, new FileArtifactStore(), tempDir.toString());

        try {
            dispatcher.execute(plan, "seq-1", "session-1", -1, tempDir.toString(),
                    new AtomicBoolean(false), observer, task, Set.of(1, 2));
        } finally {
            task.close();
        }

        verify(codeWriter, times(1)).execute(anyString(), any(), any(), anyString(), any());
    }
}
