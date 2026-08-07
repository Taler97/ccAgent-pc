package com.ccagent.core.context;

import com.ccagent.api.context.BlackboardSchema;
import com.ccagent.api.context.FieldSchema;
import com.ccagent.api.context.TaskScope;
import com.ccagent.api.model.plan.Plan;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
 * <h3>缩略型黑板 (LangGraph 风格)</h3>
 * <p>putArtifact 时按 {@link BlackboardSchema} 的 mergeStrategy 合并旧值与新值:
 * <ul>
 *   <li>replace - 直接用新值 complete Future (幂等, 多次 complete 静默忽略)</li>
 *   <li>append  - 旧 List + 新值合并成新 List, 重新 complete 新 Future</li>
 *   <li>merge   - 旧 Map + 新 Map 合并, 重新 complete 新 Future</li>
 * </ul>
 * append/merge 的语义是: 同一个 Key 在 Plan 执行期间被多个 Step 写入时累积,
 * 而不是最后一次覆盖前面所有.</p>
 *
 * <h3>线程安全</h3>
 * <ul>
 *   <li>futures: ConcurrentHashMap, computeIfAbsent 保证原子创建</li>
 *   <li>persistFlags: ConcurrentHashMap</li>
 *   <li>cancelled: AtomicBoolean</li>
 *   <li>append/merge 通过 futures.compute 同步原子合并</li>
 *   <li>虚拟线程通过 join() 挂起, 不阻塞 OS 线程</li>
 * </ul>
 */
public class TaskContextImpl implements TaskScope {

    private final String seqId;
    private final Plan plan;
    private final ArtifactStore artifactStore;
    private final String workspaceRoot;
    private final BlackboardSchema schema;

    private final ConcurrentHashMap<String, CompletableFuture<Object>> futures = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Boolean> persistFlags = new ConcurrentHashMap<>();
    private final Set<String> restoredKeys = ConcurrentHashMap.newKeySet();
    private final Set<String> missingKeys = ConcurrentHashMap.newKeySet();
    private final AtomicBoolean cancelled = new AtomicBoolean(false);

    public TaskContextImpl(String seqId, Plan plan, ArtifactStore artifactStore, String workspaceRoot) {
        this(seqId, plan, artifactStore, workspaceRoot, BlackboardSchema.EMPTY);
    }

    public TaskContextImpl(String seqId, Plan plan, ArtifactStore artifactStore,
                           String workspaceRoot, BlackboardSchema schema) {
        this.seqId = seqId;
        this.plan = plan;
        this.artifactStore = artifactStore;
        this.workspaceRoot = workspaceRoot;
        this.schema = schema != null ? schema : BlackboardSchema.EMPTY;
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

    @Override
    public BlackboardSchema getSchema() {
        return schema;
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
        FieldSchema fs = schema.getField(key);
        final T newValue = value;

        // compute 原子操作: 按 schema mergeStrategy 合并旧值与新值
        // 关键: 若旧 Future 未完成(消费者在等待), 直接 complete 旧 Future 唤醒消费者;
        //       若旧 Future 已完成(前一次 put), 只能创建新 Future (complete 幂等, 无法更新值)
        CompletableFuture<Object> resultFuture = futures.compute(key, (k, oldFuture) -> {
            Object oldValue = (oldFuture != null && oldFuture.isDone())
                    ? oldFuture.getNow(null) : null;
            Object finalValue;
            if (fs == null || fs.isReplace()) {
                finalValue = newValue;
            } else {
                finalValue = mergeValues(oldValue, newValue, fs);
            }
            // 旧 Future 未完成: 直接 complete 它, 唤醒所有等待的消费者
            if (oldFuture != null && !oldFuture.isDone()) {
                oldFuture.complete(finalValue);
                return oldFuture;
            }
            // 旧 Future 已完成或不存在: 创建新 Future
            CompletableFuture<Object> nf = new CompletableFuture<>();
            nf.complete(finalValue);
            return nf;
        });

        Object finalValue = resultFuture.getNow(newValue);

        if (persistFlags.getOrDefault(key, false) && artifactStore != null && workspaceRoot != null) {
            final Object toSave = finalValue;
            Thread.startVirtualThread(() -> {
                try {
                    artifactStore.save(workspaceRoot, seqId, key, toSave);
                } catch (Exception ignored) {}
            });
        }
    }

    /**
     * 按合并策略合并旧值与新值
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private Object mergeValues(Object oldValue, Object newValue, FieldSchema fs) {
        if (fs.isAppend()) {
            List<Object> merged = new ArrayList<>();
            if (oldValue instanceof List) {
                merged.addAll((Collection<?>) oldValue);
            } else if (oldValue != null) {
                merged.add(oldValue);
            }
            if (newValue instanceof Collection) {
                merged.addAll((Collection<?>) newValue);
            } else {
                merged.add(newValue);
            }
            return merged;
        }
        if (fs.isMerge()) {
            Map<String, Object> merged = new LinkedHashMap<>();
            if (oldValue instanceof Map) {
                for (Object k : ((Map<?, ?>) oldValue).keySet()) {
                    merged.put(String.valueOf(k), ((Map<?, ?>) oldValue).get(k));
                }
            }
            if (newValue instanceof Map) {
                for (Object k : ((Map<?, ?>) newValue).keySet()) {
                    merged.put(String.valueOf(k), ((Map<?, ?>) newValue).get(k));
                }
            } else if (newValue != null) {
                return newValue;
            }
            return merged;
        }
        return newValue;
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
            } else {
                // 清单/磁盘记录了该 Key 但文件缺失或损坏（如被用户手动删除），记录供上层决策
                missingKeys.add(key);
            }
        }
    }

    @Override
    public Set<String> getRestoredKeys() {
        return Set.copyOf(restoredKeys);
    }

    /**
     * 返回恢复时检测到的缺失 Key（清单或磁盘有记录，但产物文件缺失/损坏）。
     */
    public Set<String> getMissingKeys() {
        return Set.copyOf(missingKeys);
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
