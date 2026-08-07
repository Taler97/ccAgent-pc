package com.ccagent.api.context;

import com.ccagent.api.model.plan.Plan;

import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;

/**
 * 任务级黑板作用域
 * <p>每个 Plan 执行期间创建，专家通过此接口消费/生产中间产物（Artifact）。
 * 基于 CompletableFuture 实现生产者-消费者同步：getArtifact().join() 阻塞直到 putArtifact() 完成。</p>
 *
 * <h3>缩略型黑板 (LangGraph 风格)</h3>
 * <p>黑板不存完整 LLM 返回，只存结构化摘要。每个 Key 在 blackboard-schema.yaml 中
 * 定义类型 (list/string/map) 和合并策略 (append/replace/merge)。
 * putArtifact 时按 mergeStrategy 与旧值合并，避免下游 context 爆炸。</p>
 */
public interface TaskScope extends AutoCloseable {

    /**
     * @return 当前任务的序列号（唯一标识）
     */
    String getSeqId();

    /**
     * @return 当前执行的计划
     */
    Plan getPlan();

    /**
     * @return 是否已被取消
     */
    boolean isCancelled();

    /**
     * 取消当前任务
     */
    void cancel();

    /**
     * @return 黑板 schema (字段定义与合并策略), 可能为空 schema (关闭合并)
     */
    BlackboardSchema getSchema();

    // --- 黑板核心 API ---

    /**
     * 获取黑板产物（异步）
     * <p>若 Key 不存在则创建未完成的 Future（延迟初始化），调用方 join() 等待生产者完成。</p>
     *
     * @param key 黑板 Key
     * @return 产物对应的 CompletableFuture
     */
    <T> CompletableFuture<T> getArtifact(String key);

    /**
     * 写入黑板产物
     * <p>完成对应的 CompletableFuture（若不存在则创建并立即完成）。
     * 若标记为持久化，异步写入磁盘。</p>
     *
     * @param key   黑板 Key
     * @param value 产物值
     */
    <T> void putArtifact(String key, T value);

    /**
     * 若 Key 不存在则计算并写入（单次计算保证）
     *
     * @param key      黑板 Key
     * @param supplier 计算函数（仅执行一次）
     * @return 产物对应的 CompletableFuture
     */
    <T> CompletableFuture<T> computeIfAbsent(String key, Supplier<T> supplier);

    // --- 持久化标记 ---

    /**
     * 标记某个 Key 为需持久化
     */
    void markPersistent(String key);

    /**
     * 检查某个 Key 是否已标记持久化
     */
    boolean isPersistent(String key);

    // --- 恢复 API ---

    /**
     * 从磁盘恢复已持久化的产物（仅 Dispatcher 调用）
     *
     * @param seqId 任务序列号
     */
    void restoreFromDisk(String seqId);

    /**
     * @return 本次恢复加载的 Key 集合
     */
    Set<String> getRestoredKeys();

    /**
     * 关闭黑板，取消所有未完成的 Future 并清理资源
     */
    @Override
    void close();
}
