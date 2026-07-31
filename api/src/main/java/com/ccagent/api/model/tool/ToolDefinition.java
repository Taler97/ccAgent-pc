package com.ccagent.api.model.tool;

import java.util.Map;

/**
 * 工具定义
 * <p>工具注册时的元数据，供 LLM 理解工具的能力和参数要求。</p>
 */
public class ToolDefinition {

    private String name;
    private String description;
    private Map<String, Object> parameters;
    private boolean readOnly;

    public ToolDefinition() {}

    public ToolDefinition(String name, String description, Map<String, Object> parameters, boolean readOnly) {
        this.name = name;
        this.description = description;
        this.parameters = parameters;
        this.readOnly = readOnly;
    }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public Map<String, Object> getParameters() { return parameters; }
    public void setParameters(Map<String, Object> parameters) { this.parameters = parameters; }
    public boolean isReadOnly() { return readOnly; }
    public void setReadOnly(boolean readOnly) { this.readOnly = readOnly; }
}
