package com.ccagent.core.context;

import com.ccagent.api.context.TaskScope;
import com.ccagent.api.model.plan.Plan;

import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

/**
 * 任务级黑板实现
 * <p>基于 ConcurrentHashMap + CompletableFuture 实现生产者-消费者同步。
 * 每个 Plan 执行期间创建一个实例，Dispatcher 通过参数传递给专家。</p>
 *
 * <h3>线程安全</h3>
 * <ul>
 *   <li>futures: ConcurrentHashMap，computeIfAbsent 保证原子创建</li>
 *   <li>persistFlags: ConcurrentHashMap</li>
 *   <li>cancelled: AtomicBoolean</li>
 *   <li>虚拟线程通过 join() 挂起，不阻塞 OS 线程</li>
 * </ul>
 */
public class TaskContextImpl implements TaskScope {

    private final String seqId;
    private final Plan plan;
    private final ArtifactStore artifactStore;
    private final String workspaceRoot;

    private final ConcurrentHashMap<String, CompletableFuture<Object>> futures = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Boolean> persistFlags = new ConcurrentHashMap<>();
    private final Set<String> restoredKeys = ConcurrentHashMap.newKeySet();
    private final AtomicBoolean cancelled = new AtomicBoolean(false);

    public TaskContextImpl(String seqId, Plan plan, ArtifactStore artifactStore, String workspaceRoot) {
        this.seqId = seqId;
        this.plan = plan;
        this.artifactStore = artifactStore;
        this.workspaceRoot = workspaceRoot;
    }

    @Override
    public String getSeqId() {
        return seqId;
    }

    @Override
    public Plan getPlan() {
        return plan;
    }

    @Override
    public boolean isCancelled() {
        return cancelled.get();
    }

    @Override
    public void cancel() {
        cancelled.set(true);
    }

    // --- 黑板核心 API ---

    @Override
    @SuppressWarnings("unchecked")
    public <T> CompletableFuture<T> getArtifact(String key) {
        CompletableFuture<Object> future = futures.computeIfAbsent(key, k -> new CompletableFuture<>());
        return (CompletableFuture<T>) future;
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T> void putArtifact(String key, T value) {
        CompletableFuture<Object> future = futures.computeIfAbsent(key, k -> new CompletableFuture<>());
        future.complete(value);

        if (persistFlags.getOrDefault(key, false) && artifactStore != null && workspaceRoot != null) {
            Thread.startVirtualThread(() -> {
                try {
                    artifactStore.save(workspaceRoot, seqId, key, value);
                } catch (Exception ignored) {}
            });
        }
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T> CompletableFuture<T> computeIfAbsent(String key, Supplier<T> supplier) {
        CompletableFuture<Object> future = futures.computeIfAbsent(key, k -> {
            T value = supplier.get();
            if (persistFlags.getOrDefault(key, false) && artifactStore != null && workspaceRoot != null) {
                Thread.startVirtualThread(() -> {
                    try {
                        artifactStore.save(workspaceRoot, seqId, key, value);
                    } catch (Exception ignored) {}
                });
            }
            return CompletableFuture.completedFuture(value);
        });
        return (CompletableFuture<T>) future;
    }

    // --- 持久化标记 ---

    @Override
    public void markPersistent(String key) {
        persistFlags.put(key, true);
    }

    @Override
    public boolean isPersistent(String key) {
        return persistFlags.getOrDefault(key, false);
    }

    // --- 恢复 API ---

    @Override
    public void restoreFromDisk(String seqId) {
        if (artifactStore == null || workspaceRoot == null) return;

        Set<String> keys = artifactStore.listKeys(workspaceRoot, seqId);
        for (String key : keys) {
            Optional<String> value = artifactStore.load(workspaceRoot, seqId, key, String.class);
            if (value.isPresent()) {
                futures.computeIfAbsent(key, k -> CompletableFuture.completedFuture(value.get()));
                restoredKeys.add(key);
            }
        }
    }

    @Override
    public Set<String> getRestoredKeys() {
        return Set.copyOf(restoredKeys);
    }

    // --- 生命周期 ---

    @Override
    public void close() {
        cancelled.set(true);
        for (CompletableFuture<Object> future : futures.values()) {
            if (!future.isDone()) {
                future.cancel(true);
            }
        }
        futures.clear();
        persistFlags.clear();
    }
}
