package com.ccagent.api.model.request;

import java.util.Map;

/**
 * 工具调用请求
 * <p>LLM 返回中携带的工具调用信息。</p>
 */
public class ToolCall {

    private String id;
    private String name;
    private Map<String, Object> arguments;

    public ToolCall() {}

    public ToolCall(String name, Map<String, Object> arguments) {
        this.name = name;
        this.arguments = arguments;
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public Map<String, Object> getArguments() { return arguments; }
    public void setArguments(Map<String, Object> arguments) { this.arguments = arguments; }
}
