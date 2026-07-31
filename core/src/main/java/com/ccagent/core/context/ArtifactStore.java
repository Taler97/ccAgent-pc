package com.ccagent.core.context;

import java.util.Optional;
import java.util.Set;

/**
 * 黑板产物持久化仓储
 * <p>负责将黑板 Artifact 序列化到磁盘，用于断点续存。
 * 存储路径：{workspaceRoot}/.codepal/cache/artifacts/{seqId}/{key}.json</p>
 */
public interface ArtifactStore {

    /**
     * 保存产物到磁盘（原子写入：先 .tmp 再 rename）
     *
     * @param workspaceRoot 工作区根路径
     * @param seqId         任务序列号
     * @param key           黑板 Key
     * @param value         产物值（需可 JSON 序列化）
     */
    void save(String workspaceRoot, String seqId, String key, Object value);

    /**
     * 从磁盘加载产物
     *
     * @param workspaceRoot 工作区根路径
     * @param seqId         任务序列号
     * @param key           黑板 Key
     * @param type          期望的类型
     * @return 产物值（若不存在则 empty）
     */
    <T> Optional<T> load(String workspaceRoot, String seqId, String key, Class<T> type);

    /**
     * 列出指定任务的所有持久化 Key
     *
     * @param workspaceRoot 工作区根路径
     * @param seqId         任务序列号
     * @return Key 集合
     */
    Set<String> listKeys(String workspaceRoot, String seqId);

    /**
     * 删除指定任务的所有产物（Task 销毁时清理）
     *
     * @param workspaceRoot 工作区根路径
     * @param seqId         任务序列号
     */
    void deleteAll(String workspaceRoot, String seqId);
}
