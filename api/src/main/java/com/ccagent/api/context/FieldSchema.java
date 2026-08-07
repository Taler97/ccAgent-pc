package com.ccagent.api.context;

/**
 * 单个黑板字段的 schema 定义
 * <p>对应 blackboard-schema.yaml 中 fields.{key} 的配置.</p>
 */
public class FieldSchema {

    /** 字段类型: list / string / map */
    private String type;

    /** 合并策略: replace / append / merge */
    private String mergeStrategy;

    /** 字段语义说明 */
    private String description;

    public FieldSchema() {}

    public FieldSchema(String type, String mergeStrategy, String description) {
        this.type = type;
        this.mergeStrategy = mergeStrategy;
        this.description = description;
    }

    public String getType() { return type; }
    public void setType(String type) { this.type = type; }

    public String getMergeStrategy() { return mergeStrategy; }
    public void setMergeStrategy(String mergeStrategy) { this.mergeStrategy = mergeStrategy; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }

    public boolean isAppend() { return "append".equalsIgnoreCase(mergeStrategy); }
    public boolean isMerge() { return "merge".equalsIgnoreCase(mergeStrategy); }
    public boolean isReplace() { return mergeStrategy == null || "replace".equalsIgnoreCase(mergeStrategy); }
}
