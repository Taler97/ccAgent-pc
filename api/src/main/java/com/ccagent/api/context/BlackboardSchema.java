package com.ccagent.api.context;

import java.util.Set;

/**
 * 黑板 schema 查询接口
 * <p>定义黑板字段类型与合并策略. 实现类在 core 模块 (YamlBlackboardSchema),
 * 从 blackboard-schema.yaml 加载配置.</p>
 *
 * <h3>合并策略</h3>
 * <ul>
 *   <li>replace - 新值覆盖旧值 (默认)</li>
 *   <li>append  - 新值追加到列表 (list 专用)</li>
 *   <li>merge   - 新值合并进 Map (map 专用)</li>
 * </ul>
 */
public interface BlackboardSchema {

    /**
     * 查询某 Key 的 schema
     * @return schema 或 null (Key 未在 schema 中定义)
     */
    FieldSchema getField(String key);

    /**
     * @return schema 中定义的所有 Key
     */
    Set<String> getDefinedKeys();

    /**
     * @return schema 是否为空 (即关闭合并行为)
     */
    boolean isEmpty();

    /**
     * 空 schema 单例 (关闭合并行为, putArtifact 走 fallback 直接替换)
     */
    BlackboardSchema EMPTY = new BlackboardSchema() {
        @Override public FieldSchema getField(String key) { return null; }
        @Override public Set<String> getDefinedKeys() { return Set.of(); }
        @Override public boolean isEmpty() { return true; }
    };
}
