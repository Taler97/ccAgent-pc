package com.ccagent.kernel.plugin;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * 插件描述符（从 plugin.yaml 反序列化）
 * <p>YAML 格式示例：
 * <pre>
 * name: writer-expert
 * version: 1.0.0
 * type: EXPERT
 * description: 代码编写专家
 * class: com.ccagent.adapter.expert.impl.WriterExpert
 * dependencies: []
 * </pre>
 * </p>
 */
public class PluginDescriptor {

    private String name;
    private String version;
    private PluginType type;
    private String description;

    /** YAML 中写 class，Java 中用 className（class 是关键字） */
    @JsonProperty("class")
    private String className;

    private List<String> dependencies;

    public PluginDescriptor() {}

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getVersion() { return version; }
    public void setVersion(String version) { this.version = version; }

    public PluginType getType() { return type; }
    public void setType(PluginType type) { this.type = type; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }

    public String getClassName() { return className; }
    public void setClassName(String className) { this.className = className; }

    public List<String> getDependencies() { return dependencies; }
    public void setDependencies(List<String> dependencies) { this.dependencies = dependencies; }

    @Override
    public String toString() {
        return "PluginDescriptor{name='" + name + "', type=" + type
                + ", class='" + className + "'}";
    }
}
