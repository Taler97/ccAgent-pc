package com.ccagent.core.orchestration.progress;

import com.ccagent.api.model.plan.Step;
import com.ccagent.infrastructure.util.JsonUtil;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ProgressStore 单元测试
 * <p>验证状态流转、活动记录、快照组装与销毁，不依赖 Dispatcher。</p>
 */
class ProgressStoreTest {

    private final ProgressStore store = new ProgressStore();

    private List<Step> steps3() {
        return List.of(
                new Step(1, "writer", "编辑", List.of()),
                new Step(2, "researcher", "研究", List.of()),
                new Step(3, "summarizer", "摘要", List.of()));
    }

    @SuppressWarnings("unchecked")
    @Test
    void shouldStartWithAllPending() {
        store.register("seq-1", steps3());

        Map<String, Object> snap = JsonUtil.fromJson(store.snapshot("seq-1"), Map.class);
        assertThat(snap.get("seqId")).isEqualTo("seq-1");
        assertThat(snap.get("totalSteps")).isEqualTo(3);
        assertThat(snap.get("completedSteps")).isEqualTo(0);
        assertThat(snap.get("overallProgress")).isEqualTo(0);

        List<Map<String, Object>> steps = (List<Map<String, Object>>) snap.get("steps");
        assertThat(steps).hasSize(3);
        assertThat(steps.get(0).get("status")).isEqualTo("PENDING");
        assertThat(steps.get(0).get("expertName")).isEqualTo("writer");
        assertThat(steps.get(0).get("activity")).isEqualTo("");
    }

    @SuppressWarnings("unchecked")
    @Test
    void shouldTrackStatusTransitions() {
        store.register("seq-1", steps3());
        store.startStep("seq-1", 1);
        store.completeStep("seq-1", 1);
        store.startStep("seq-1", 2);
        store.failStep("seq-1", 2);
        store.cancelStep("seq-1", 3);

        Map<String, Object> s1 = JsonUtil.fromJson(store.snapshotStep("seq-1", 1), Map.class);
        assertThat(s1.get("status")).isEqualTo("SUCCESS");
        assertThat(s1.get("expertName")).isEqualTo("writer");
        assertThat(((Number) s1.get("totalSteps")).intValue()).isEqualTo(3);

        Map<String, Object> s2 = JsonUtil.fromJson(store.snapshotStep("seq-1", 2), Map.class);
        assertThat(s2.get("status")).isEqualTo("FAILED");

        Map<String, Object> s3 = JsonUtil.fromJson(store.snapshotStep("seq-1", 3), Map.class);
        assertThat(s3.get("status")).isEqualTo("CANCELLED");

        Map<String, Object> snap = JsonUtil.fromJson(store.snapshot("seq-1"), Map.class);
        assertThat(snap.get("completedSteps")).isEqualTo(1);
        assertThat(snap.get("overallProgress")).isEqualTo(33);
    }

    @Test
    void shouldRecordActivityAndElapsed() throws Exception {
        store.register("seq-1", steps3());
        store.startStep("seq-1", 1);
        store.updateActivity("seq-1", 1, "正在编辑文件: A.java");
        Thread.sleep(30);

        Map<String, Object> s1 = JsonUtil.fromJson(store.snapshotStep("seq-1", 1), Map.class);
        assertThat(s1.get("status")).isEqualTo("RUNNING");
        assertThat(s1.get("activity")).isEqualTo("正在编辑文件: A.java");
        long elapsed = ((Number) s1.get("elapsedMs")).longValue();
        assertThat(elapsed).isGreaterThanOrEqualTo(30);
    }

    @Test
    void shouldReturnNullAfterClear() {
        store.register("seq-1", steps3());
        assertThat(store.snapshot("seq-1")).isNotNull();
        assertThat(store.snapshotStep("seq-1", 1)).isNotNull();

        store.clear("seq-1");
        assertThat(store.snapshot("seq-1")).isNull();
        assertThat(store.snapshotStep("seq-1", 1)).isNull();
    }

    @Test
    void shouldReturnNullForUnknownStep() {
        store.register("seq-1", steps3());
        assertThat(store.snapshotStep("seq-1", 99)).isNull();
        assertThat(store.snapshotStep("seq-unknown", 1)).isNull();
        assertThat(store.snapshot("seq-unknown")).isNull();
    }

    @Test
    void shouldIgnoreUpdateForUnknownStep() {
        store.register("seq-1", steps3());
        store.startStep("seq-1", 99);
        store.updateActivity("seq-1", 99, "x");
        assertThat(store.snapshotStep("seq-1", 99)).isNull();
    }
}
