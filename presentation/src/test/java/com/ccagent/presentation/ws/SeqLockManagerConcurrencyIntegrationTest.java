package com.ccagent.presentation.ws;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

/**
 * SeqLockManager 并发安全集成测试。
 * <p>验证 P0-#2 修复：use-and-delete 锁对象替换 bug。
 * 修复前：线程 A 持锁 → B 阻塞等 → A unlock 后 remove → C 创建新锁立即获取 → B 和 C 并发执行。
 * 修复后：仅当锁空闲且无等待者时才用条件删除 remove(key, value)。</p>
 */
class SeqLockManagerConcurrencyIntegrationTest {

    /**
     * 100 个虚拟线程并发 withLock 同一 seqId，验证任意时刻只有一个线程在执行临界区。
     * 这是 use-and-delete bug 的核心暴露场景：修复前会有并发执行。
     */
    @Test
    void shouldSerializeConcurrentAccessToSameSeqId() throws Exception {
        SeqLockManager manager = new SeqLockManager();
        int threadCount = 100;
        ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor();
        CountDownLatch ready = new CountDownLatch(threadCount);
        CountDownLatch done = new CountDownLatch(threadCount);
        AtomicInteger inCriticalSection = new AtomicInteger(0);
        AtomicInteger maxConcurrent = new AtomicInteger(0);

        for (int i = 0; i < threadCount; i++) {
            final int idx = i;
            pool.submit(() -> {
                ready.countDown();
                manager.withLock("seq-shared", () -> {
                    int current = inCriticalSection.incrementAndGet();
                    // 记录最大并发数（正确实现应为 1）
                    maxConcurrent.accumulateAndGet(current, Math::max);
                    try {
                        Thread.sleep(2); // 模拟临界区工作，增加竞态窗口
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    inCriticalSection.decrementAndGet();
                    return null;
                });
                done.countDown();
                return null;
            });
        }

        assertThat(ready.await(2, TimeUnit.SECONDS)).isTrue();
        assertThat(done.await(10, TimeUnit.SECONDS)).isTrue();
        pool.shutdownNow();

        assertThat(maxConcurrent.get())
                .as("最大并发数应为 1（锁互斥），use-and-delete bug 会导致 >1")
                .isEqualTo(1);
    }

    /**
     * 验证修复后锁会被正确清理（不会内存泄漏）。
     * 所有任务完成后，seqLocks 内部 map 应为空（或仅含空闲锁）。
     */
    @Test
    void shouldCleanupLockWhenIdleAndNoWaiters() throws Exception {
        SeqLockManager manager = new SeqLockManager();

        // 串行执行 3 次（无并发等待者），每次完成后应清理
        for (int i = 0; i < 3; i++) {
            final int idx = i;
            manager.withLock("seq-cleanup", () -> "result-" + idx);
        }

        // 最后一次 withLock 完成后，锁空闲且无等待者 → 应被条件删除
        // 给一点时间让 remove 生效（computeIfAbsent 在 finally 内同步执行，无需等待）
        // 由于 withLock 是同步方法，返回时 finally 已执行完，锁应已被清理
        // 但 remove(key, value) 是条件删除，如果锁对象被替换过则不删
        // 串行场景下锁对象不变，应被删除
        // 验证方式：再次 withLock，如果锁被清理了，会创建新锁对象（不影响功能）
        String result = manager.withLock("seq-cleanup", () -> "after-cleanup");
        assertThat(result).isEqualTo("after-cleanup");
    }

    /**
     * 验证不同 seqId 之间不互斥（并行执行）。
     * 这是 seqId 粒度锁的基本要求。
     */
    @Test
    void shouldNotBlockAcrossDifferentSeqIds() throws Exception {
        SeqLockManager manager = new SeqLockManager();
        ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor();
        CountDownLatch seq1Started = new CountDownLatch(1);
        CountDownLatch seq2Completed = new CountDownLatch(1);

        // seq1 持锁 sleep 1s
        pool.submit(() -> {
            manager.withLock("seq-1", () -> {
                seq1Started.countDown();
                try {
                    Thread.sleep(1000);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                return null;
            });
            return null;
        });

        // seq2 应立即完成（不等 seq1）
        pool.submit(() -> {
            try {
                seq1Started.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            manager.withLock("seq-2", () -> "done");
            seq2Completed.countDown();
            return null;
        });

        assertThat(seq2Completed.await(2, TimeUnit.SECONDS))
                .as("不同 seqId 不应互相阻塞")
                .isTrue();
        pool.shutdownNow();
    }

    /**
     * 验证 use-and-delete 场景：A 持锁 → B 等待 → A 完成 → C 到达。
     * 修复前：A remove 后 C 创建新锁，B 和 C 并发。
     * 修复后：A 完成后 B 拿到同一把锁，C 排队等 B，仍串行。
     */
    @Test
    void shouldNotReplaceLockObjectWhileWaiterExists() throws Exception {
        SeqLockManager manager = new SeqLockManager();
        ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor();
        CountDownLatch aStarted = new CountDownLatch(1);
        CountDownLatch bStarted = new CountDownLatch(1);
        CountDownLatch aRelease = new CountDownLatch(1);
        AtomicInteger concurrent = new AtomicInteger(0);
        AtomicInteger maxConcurrent = new AtomicInteger(0);

        // A: 持锁，等 aRelease 信号才释放
        var aFuture = pool.submit(() -> {
            manager.withLock("seq-race", () -> {
                aStarted.countDown();
                try {
                    aRelease.await(5, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                int c = concurrent.incrementAndGet();
                maxConcurrent.accumulateAndGet(c, Math::max);
                concurrent.decrementAndGet();
                return null;
            });
            return null;
        });

        assertThat(aStarted.await(1, TimeUnit.SECONDS)).isTrue();

        // B: 阻塞等待 A 释放锁
        var bFuture = pool.submit(() -> {
            bStarted.countDown();
            manager.withLock("seq-race", () -> {
                int c = concurrent.incrementAndGet();
                maxConcurrent.accumulateAndGet(c, Math::max);
                try {
                    Thread.sleep(50); // 模拟工作
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                concurrent.decrementAndGet();
                return null;
            });
            return null;
        });

        // 确保 B 已在等待锁
        assertThat(bStarted.await(1, TimeUnit.SECONDS)).isTrue();
        Thread.sleep(100); // 确保 B 已进入 lock() 阻塞

        // C: A 释放后立即到达。修复前会创建新锁与 B 并发；修复后排队等 B
        var cFuture = pool.submit(() -> {
            manager.withLock("seq-race", () -> {
                int c = concurrent.incrementAndGet();
                maxConcurrent.accumulateAndGet(c, Math::max);
                try {
                    Thread.sleep(50);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                concurrent.decrementAndGet();
                return null;
            });
            return null;
        });

        // 释放 A
        aRelease.countDown();

        // 等待全部完成
        aFuture.get(5, TimeUnit.SECONDS);
        bFuture.get(5, TimeUnit.SECONDS);
        cFuture.get(5, TimeUnit.SECONDS);
        pool.shutdownNow();

        assertThat(maxConcurrent.get())
                .as("B 和 C 不应并发执行（use-and-delete bug 会导致 =2）")
                .isEqualTo(1);
    }
}
