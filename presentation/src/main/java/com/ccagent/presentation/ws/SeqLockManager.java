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
     * 加锁执行（有返回值），执行后锁自动清理。
     */
    public <T> T withLock(String seqId, Supplier<T> task) {
        ReentrantLock lock = seqLocks.computeIfAbsent(seqId, k -> new ReentrantLock());
        lock.lock();
        try {
            return task.get();
        } finally {
            lock.unlock();
            seqLocks.remove(seqId);
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
