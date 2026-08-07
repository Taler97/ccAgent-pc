package com.ccagent.presentation.ws;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;

/**
 * seqId 粒度的锁管理器。
 * <p>从 MessageRouter 抽出，职责单一：按 seqId 加锁执行，执行后自动清理。
 * 将来可替换为读写锁或分布式锁，MessageRouter 无需改动。</p>
 */
public class SeqLockManager {

    private final ConcurrentHashMap<String, ReentrantLock> seqLocks = new ConcurrentHashMap<>();

    /**
     * 加锁执行（有返回值），执行后锁在空闲时自动清理。
     * <p>不立即删除锁对象：避免 use-and-delete 竞态——
     * 线程 A 持锁执行时线程 B computeIfAbsent 拿到同一把锁阻塞等待，
     * A unlock 后立即 remove 会导致线程 C 创建新锁并并发执行，锁失效。
     * 仅当锁空闲且无等待者时才用条件删除清理。</p>
     */
    public <T> T withLock(String seqId, Supplier<T> task) {
        ReentrantLock lock = seqLocks.computeIfAbsent(seqId, k -> new ReentrantLock());
        lock.lock();
        try {
            return task.get();
        } finally {
            lock.unlock();
            // tryLock 成功 = 此刻无线程持有；hasQueuedThreads=false = 无线程排队等待
            // 双重检查后才用条件删除 remove(key, value)，避免删错或删掉正在被等待的锁
            if (lock.tryLock()) {
                try {
                    if (!lock.hasQueuedThreads()) {
                        seqLocks.remove(seqId, lock);
                    }
                } finally {
                    lock.unlock();
                }
            }
        }
    }

    /**
     * 加锁执行（无返回值），执行后锁自动清理。
     */
    public void withLock(String seqId, Runnable task) {
        withLock(seqId, () -> {
            task.run();
            return null;
        });
    }
}
