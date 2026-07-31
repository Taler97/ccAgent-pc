package com.ccagent.core.context;

import com.ccagent.api.model.plan.Plan;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 任务级黑板核心 API 单元测试
 * <p>使用真实 {@link FileArtifactStore} + {@code @TempDir} 验证：</p>
 * <ul>
 *   <li>CompletableFuture 生产者-消费者同步</li>
 *   <li>computeIfAbsent 单次计算保证</li>
 *   <li>close 取消未完成的 Future</li>
 *   <li>持久化标记隔离</li>
 *   <li>从磁盘恢复产物</li>
 * </ul>
 */
class TaskContextImplTest {

    @TempDir Path tempDir;

    @Test
    void shouldBlockUntilProducerPutsArtifact() throws Exception {
        Plan plan = new Plan("test", java.util.List.of(), java.util.List.of());
        FileArtifactStore store = new FileArtifactStore();
        TaskContextImpl task = new TaskContextImpl("seq-1", plan, store, tempDir.toString());

        AtomicReference<String> consumed = new AtomicReference<>();
        CountDownLatch latch = new CountDownLatch(1);

        // 消费者：先 join 等待
        Thread consumer = Thread.startVirtualThread(() -> {
            String value = task.<String>getArtifact("analysis").join();
            consumed.set(value);
            latch.countDown();
        });

        // 确认消费者已被挂起
        Thread.sleep(50);
        assertThat(consumed.get()).isNull();

        // 生产者：写入产物
        task.putArtifact("analysis", "analysis-result");

        assertThat(latch.await(2, TimeUnit.SECONDS)).isTrue();
        assertThat(consumed.get()).isEqualTo("analysis-result");

        task.close();
    }

    @Test
    void shouldSupportComputeIfAbsentWithSingleExecution() throws Exception {
        Plan plan = new Plan("test", java.util.List.of(), java.util.List.of());
        FileArtifactStore store = new FileArtifactStore();
        TaskContextImpl task = new TaskContextImpl("seq-1", plan, store, tempDir.toString());

        AtomicInteger callCount = new AtomicInteger(0);
        CompletableFuture<String> f1 = task.computeIfAbsent("key", () -> {
            callCount.incrementAndGet();
            return "computed-value";
        });
        // 第二次调用：不应重新计算
        CompletableFuture<String> f2 = task.computeIfAbsent("key", () -> {
            callCount.incrementAndGet();
            return "should-not-happen";
        });

        assertThat(f1.join()).isEqualTo("computed-value");
        assertThat(f2.join()).isEqualTo("computed-value");
        assertThat(callCount.get()).isEqualTo(1);

        task.close();
    }

    @Test
    void shouldCancelIncompleteFuturesOnClose() {
        Plan plan = new Plan("test", java.util.List.of(), java.util.List.of());
        FileArtifactStore store = new FileArtifactStore();
        TaskContextImpl task = new TaskContextImpl("seq-1", plan, store, tempDir.toString());

        // 创建未完成的 Future（无生产者）
        CompletableFuture<String> pending = task.getArtifact("never-produced");

        task.close();

        // close 后未完成的 Future 应被取消
        assertThat(pending.isCancelled()).isTrue();
    }

    @Test
    void shouldMarkPersistentOnlyAffectsSpecifiedKey() {
        Plan plan = new Plan("test", java.util.List.of(), java.util.List.of());
        FileArtifactStore store = new FileArtifactStore();
        TaskContextImpl task = new TaskContextImpl("seq-1", plan, store, tempDir.toString());

        task.markPersistent("analysis");
        // 不标记 "code"

        assertThat(task.isPersistent("analysis")).isTrue();
        assertThat(task.isPersistent("code")).isFalse();

        task.close();
    }

    @Test
    void shouldPersistToDiskOnlyWhenMarkedPersistent() {
        Plan plan = new Plan("test", java.util.List.of(), java.util.List.of());
        FileArtifactStore store = new FileArtifactStore();
        TaskContextImpl task = new TaskContextImpl("seq-1", plan, store, tempDir.toString());

        task.markPersistent("analysis");
        task.putArtifact("analysis", "persisted-value");

        // 未标记的 key 不应持久化
        task.putArtifact("code", "transient-value");

        // 等待异步保存完成
        waitForAsyncSave(tempDir, "seq-1", "analysis");

        Optional<String> loaded = store.load(tempDir.toString(), "seq-1", "analysis", String.class);
        assertThat(loaded).isPresent().contains("persisted-value");

        // code 未标记持久化 → 磁盘上不应存在
        Optional<String> codeLoaded = store.load(tempDir.toString(), "seq-1", "code", String.class);
        assertThat(codeLoaded).isEmpty();

        task.close();
    }

    @Test
    void shouldRestoreFromDiskAndExposeRestoredKeys() {
        Plan plan = new Plan("test", java.util.List.of(), java.util.List.of());
        FileArtifactStore store = new FileArtifactStore();

        // 1. 模拟第一次执行：写入并持久化
        TaskContextImpl task1 = new TaskContextImpl("recovery-key", plan, store, tempDir.toString());
        task1.markPersistent("analysis");
        task1.putArtifact("analysis", "recovered-data");
        task1.markPersistent("summary");
        task1.putArtifact("summary", "recovered-summary");
        waitForAsyncSave(tempDir, "recovery-key", "analysis");
        waitForAsyncSave(tempDir, "recovery-key", "summary");
        task1.close();

        // 2. 模拟重启：新建 TaskContext，从磁盘恢复
        TaskContextImpl task2 = new TaskContextImpl("recovery-key", plan, store, tempDir.toString());
        task2.restoreFromDisk("recovery-key");

        Set<String> restored = task2.getRestoredKeys();
        assertThat(restored).containsExactlyInAnyOrder("analysis", "summary");

        // 恢复后的值应该立即可用（不需 join 等待）
        assertThat(task2.<String>getArtifact("analysis").join()).isEqualTo("recovered-data");
        assertThat(task2.<String>getArtifact("summary").join()).isEqualTo("recovered-summary");

        task2.close();
    }

    @Test
    void shouldNotRestoreFromDiskWhenStoreIsNull() {
        Plan plan = new Plan("test", java.util.List.of(), java.util.List.of());
        // artifactStore = null
        TaskContextImpl task = new TaskContextImpl("seq-1", plan, null, tempDir.toString());

        // 不应抛异常，restoredKeys 应为空
        task.restoreFromDisk("seq-1");
        assertThat(task.getRestoredKeys()).isEmpty();

        task.close();
    }

    @Test
    void shouldNotRestoreFromDiskWhenWorkspaceRootIsNull() {
        Plan plan = new Plan("test", java.util.List.of(), java.util.List.of());
        FileArtifactStore store = new FileArtifactStore();
        // workspaceRoot = null
        TaskContextImpl task = new TaskContextImpl("seq-1", plan, store, null);

        task.restoreFromDisk("seq-1");
        assertThat(task.getRestoredKeys()).isEmpty();

        task.close();
    }

    @Test
    void shouldExposeSeqIdAndPlan() {
        Plan plan = new Plan("test", java.util.List.of(), java.util.List.of());
        FileArtifactStore store = new FileArtifactStore();
        TaskContextImpl task = new TaskContextImpl("seq-xyz", plan, store, tempDir.toString());

        assertThat(task.getSeqId()).isEqualTo("seq-xyz");
        assertThat(task.getPlan()).isSameAs(plan);

        task.close();
    }

    @Test
    void shouldReportCancelledState() {
        Plan plan = new Plan("test", java.util.List.of(), java.util.List.of());
        FileArtifactStore store = new FileArtifactStore();
        TaskContextImpl task = new TaskContextImpl("seq-1", plan, store, tempDir.toString());

        assertThat(task.isCancelled()).isFalse();
        task.cancel();
        assertThat(task.isCancelled()).isTrue();

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
