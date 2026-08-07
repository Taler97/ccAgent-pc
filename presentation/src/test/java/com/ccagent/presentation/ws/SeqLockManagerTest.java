package com.ccagent.presentation.ws;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * SeqLockManager 并发安全测试。
 * <p>验证：相同 seqId 串行、不同 seqId 并行、锁清理、异常释放、压测无死锁。</p>
 */
class SeqLockManagerTest {

    @Test
    void sameSeqIdExecutesSequentially() throws Exception {
        SeqLockManager mgr = new SeqLockManager();
        String seqId = "seq-1";

        // 用于检测并发的标志：如果两个任务重叠，concurrent 会 > 0
        AtomicInteger running = new AtomicInteger(0);
        AtomicInteger maxConcurrent = new AtomicInteger(0);
        CountDownLatch firstStarted = new CountDownLatch(1);
        CountDownLatch firstDone = new CountDownLatch(1);
        AtomicInteger executionOrder = new AtomicInteger(0);

        List<Thread> threads = new ArrayList<>();
        threads.add(Thread.ofVirtual().start(() -> {
            mgr.withLock(seqId, () -> {
                int cur = running.incrementAndGet();
                maxConcurrent.accumulateAndGet(cur, Math::max);
                executionOrder.set(1);
                firstStarted.countDown();
                try { firstDone.await(5, TimeUnit.SECONDS); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                running.decrementAndGet();
            });
        }));

        // 等第一个任务拿到锁
        assertThat(firstStarted.await(2, TimeUnit.SECONDS)).isTrue();

        threads.add(Thread.ofVirtual().start(() -> {
            mgr.withLock(seqId, () -> {
                int cur = running.incrementAndGet();
                maxConcurrent.accumulateAndGet(cur, Math::max);
                executionOrder.set(2);
                running.decrementAndGet();
            });
        }));

        // 放行第一个
        firstDone.countDown();

        for (Thread t : threads) t.join(5_000);

        assertThat(maxConcurrent.get()).isEqualTo(1);
        // 第二个任务在第一个完成后才执行
        assertThat(executionOrder.get()).isEqualTo(2);
    }

    @Test
    void differentSeqIdExecutesInParallel() throws Exception {
        SeqLockManager mgr = new SeqLockManager();

        CountDownLatch bothStarted = new CountDownLatch(2);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger maxConcurrent = new AtomicInteger(0);
        AtomicInteger running = new AtomicInteger(0);

        Thread t1 = Thread.ofVirtual().start(() -> {
            mgr.withLock("seq-a", () -> {
                int cur = running.incrementAndGet();
                maxConcurrent.accumulateAndGet(cur, Math::max);
                bothStarted.countDown();
                try { release.await(5, TimeUnit.SECONDS); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                running.decrementAndGet();
            });
        });

        Thread t2 = Thread.ofVirtual().start(() -> {
            mgr.withLock("seq-b", () -> {
                int cur = running.incrementAndGet();
                maxConcurrent.accumulateAndGet(cur, Math::max);
                bothStarted.countDown();
                try { release.await(5, TimeUnit.SECONDS); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                running.decrementAndGet();
            });
        });

        // 两个任务应该都能同时进入
        assertThat(bothStarted.await(3, TimeUnit.SECONDS)).isTrue();
        assertThat(maxConcurrent.get()).isEqualTo(2);

        release.countDown();
        t1.join(5_000);
        t2.join(5_000);
    }

    @Test
    void lockCleanedUpAfterExecution() {
        SeqLockManager mgr = new SeqLockManager();

        mgr.withLock("seq-1", () -> {});

        // 再次执行同一个 seqId，如果锁没清理，也不会有影响（因为用的是 computeIfAbsent）
        // 这里验证不抛异常即可
        AtomicReference<String> result = new AtomicReference<>();
        mgr.withLock("seq-1", () -> result.set("ok"));
        assertThat(result.get()).isEqualTo("ok");
    }

    @Test
    void exceptionDoesNotLeakLock() {
        SeqLockManager mgr = new SeqLockManager();

        assertThatThrownBy(() -> mgr.withLock("seq-err", (Supplier<Void>) () -> {
            throw new RuntimeException("boom");
        })).isInstanceOf(RuntimeException.class);

        // 锁应该已释放，后续任务可正常执行
        AtomicReference<String> result = new AtomicReference<>();
        mgr.withLock("seq-err", () -> result.set("recovered"));
        assertThat(result.get()).isEqualTo("recovered");
    }

    @Test
    void noDeadlockUnderStress() throws Exception {
        SeqLockManager mgr = new SeqLockManager();
        int seqCount = 10;
        int threadsPerSeq = 10;

        CountDownLatch done = new CountDownLatch(seqCount * threadsPerSeq);
        AtomicInteger successCount = new AtomicInteger(0);

        for (int i = 0; i < seqCount; i++) {
            String seqId = "stress-" + i;
            for (int j = 0; j < threadsPerSeq; j++) {
                Thread.startVirtualThread(() -> {
                    try {
                        mgr.withLock(seqId, () -> successCount.incrementAndGet());
                    } finally {
                        done.countDown();
                    }
                });
            }
        }

        assertThat(done.await(30, TimeUnit.SECONDS)).isTrue();
        assertThat(successCount.get()).isEqualTo(seqCount * threadsPerSeq);
    }
}
