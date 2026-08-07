package com.ccagent.core.context;

import com.ccagent.api.model.plan.Plan;
import com.ccagent.api.model.plan.Step;
import com.ccagent.api.port.Expert;
import com.ccagent.api.port.ExpertRegistryPort;
import com.ccagent.api.port.StreamObserver;
import com.ccagent.api.model.response.StreamChunk;
import com.ccagent.core.orchestration.dispatcher.Dispatcher;
import com.ccagent.kernel.event.EventBus;
import com.ccagent.kernel.event.SimpleEventBus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * 黑板 join() 超时集成测试。
 * <p>验证 P0-#4 修复：生产者异常未 complete Future 时，消费者 orTimeout 触发 TimeoutException，
 * 而非永久挂起。</p>
 *
 * <p>覆盖三处 join 加超时：</p>
 * <ul>
 *   <li>Dispatcher 批次等待 allOf().join() → orTimeout(10min)</li>
 *   <li>Dispatcher 黑板 reads 等待 allOf().join() → orTimeout(5min)</li>
 *   <li>Dispatcher 单个 artifact 等待 getArtifact().join() → orTimeout(5min)</li>
 * </ul>
 *
 * <p>注意：由于超时阈值设为 5/10 分钟，测试中无法等待真实超时。
 * 采用短超时 orTimeout 直接在 TaskContextImpl 层验证超时机制本身可工作，
 * 并通过"生产者永远不 complete"的场景验证 Dispatcher 不会立即返回（会阻塞直到超时）。</p>
 */
class BlackboardTimeoutIntegrationTest {

    @TempDir Path tempDir;

    /**
     * 直接验证 CompletableFuture.orTimeout 机制：未 complete 的 Future 在超时后抛 TimeoutException。
     * 这验证了 Dispatcher 中 join() 加 orTimeout 的基础机制是有效的。
     */
    @Test
    void orTimeoutShouldTriggerWhenFutureNeverCompletes() throws Exception {
        CompletableFuture<String> neverCompleted = new CompletableFuture<>();

        // 100ms 超时
        CompletableFuture<String> timed = neverCompleted.orTimeout(100, TimeUnit.MILLISECONDS);

        assertThatThrownBy(timed::join)
                .isInstanceOf(CompletionException.class)
                .hasCauseInstanceOf(TimeoutException.class);
    }

    /**
     * 已 complete 的 Future 加 orTimeout 不影响结果。
     * 验证正常路径下 orTimeout 不会干扰。
     */
    @Test
    void orTimeoutShouldNotAffectCompletedFuture() throws Exception {
        CompletableFuture<String> completed = CompletableFuture.completedFuture("result");

        String result = completed.orTimeout(5, TimeUnit.SECONDS).join();

        assertThat(result).isEqualTo("result");
    }

    /**
     * 验证 TaskContextImpl.getArtifact() 返回的 Future 可以被 orTimeout。
     * 模拟 Dispatcher 中的用法：task.getArtifact(key).orTimeout(5, MINUTES).join()
     * 当生产者永不 putArtifact 时，应抛 TimeoutException 而非永久阻塞。
     */
    @Test
    void taskContextGetArtifactShouldBeTimeoutable() throws Exception {
        Plan plan = new Plan("test", List.of(), List.of());
        FileArtifactStore store = new FileArtifactStore();
        TaskContextImpl task = new TaskContextImpl("seq-1", plan, store, tempDir.toString());

        try {
            // 获取一个永远不会被 complete 的 Future
            CompletableFuture<String> future = task.getArtifact("never-produced");

            // 加短超时验证
            assertThatThrownBy(() -> future.orTimeout(100, TimeUnit.MILLISECONDS).join())
                    .isInstanceOf(CompletionException.class)
                    .hasCauseInstanceOf(TimeoutException.class);
        } finally {
            task.close();
        }
    }

    /**
     * 验证 Dispatcher 中黑板 reads 等待场景：生产者步骤异常未写入产物时，
     * 消费者步骤的 allOf(deps).join() 会因 orTimeout 超时而非永久挂起。
     *
     * <p>这里用短超时模拟：mock Expert 让 S1 抛异常不写黑板，
     * S2 依赖 S1 的产物 → S2 等待 → 超时。
     * 但由于 Dispatcher 的超时是 5 分钟，测试中无法等待真实超时。
     * 改为验证：S1 异常后 Dispatcher 会走 catch 路径发错误事件，不会让 S2 永久阻塞。</p>
     */
    @Test
    void dispatcherShouldNotHangWhenProducerFailsWithoutWritingArtifact() throws Exception {
        Step s1 = new Step(1, "producer", "生产", List.of());
        s1.setWrites(Set.of("data"));
        Step s2 = new Step(2, "consumer", "消费", List.of());
        s2.setReads(Set.of("data"));
        Plan plan = new Plan("flow", List.of(s1, s2), List.of());

        Expert producer = mock(Expert.class);
        // S1 抛异常，不写黑板
        when(producer.execute(anyString(), any(), any(), anyString(), any()))
                .thenThrow(new RuntimeException("生产者崩溃"));

        ExpertRegistryPort registry = mock(ExpertRegistryPort.class);
        when(registry.getExpert("producer")).thenReturn(producer);
        when(registry.getExpert("consumer")).thenReturn(mock(Expert.class));

        StreamObserver<StreamChunk> observer = mock(StreamObserver.class);
        CountDownLatch errorReceived = new CountDownLatch(1);
        doAnswer(inv -> {
            errorReceived.countDown();
            return null;
        }).when(observer).onError(any());

        EventBus eventBus = new SimpleEventBus(false, 100, 10);
        Dispatcher dispatcher = new Dispatcher(registry, mock(com.ccagent.core.orchestration.repository.ExecutionPlanRepository.class),
                new FileArtifactStore(), eventBus);
        TaskContextImpl task = new TaskContextImpl("seq-1", plan, new FileArtifactStore(), tempDir.toString());

        try {
            // 在虚拟线程中执行，设置较短的总超时
            CompletableFuture<Void> execFuture = CompletableFuture.runAsync(() -> {
                dispatcher.execute(plan, "seq-1", "session-1", -1, tempDir.toString(),
                        new AtomicBoolean(false), observer, task, Set.of());
            });

            // 验证不会永久挂起：10s 内应完成（S1 异常 → Dispatcher 发 onError → 返回）
            execFuture.get(10, TimeUnit.SECONDS);
            assertThat(errorReceived.await(2, TimeUnit.SECONDS))
                    .as("生产者异常后应触发 observer.onError")
                    .isTrue();
        } finally {
            task.close();
        }
    }
}
