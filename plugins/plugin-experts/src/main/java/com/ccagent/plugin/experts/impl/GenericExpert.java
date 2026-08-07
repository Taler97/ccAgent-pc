package com.ccagent.plugin.experts.impl;

import com.ccagent.api.context.TaskScope;
import com.ccagent.api.model.response.StreamChunk;
import com.ccagent.api.model.tool.ToolDefinition;
import com.ccagent.api.port.StreamObserver;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 配置式通用专家
 * <p>由自定义专家配置（<code>experts/&lt;name&gt;.yaml</code>）驱动：prompt 作为 system prompt，
 * tools 限定可用工具，skills 绑定 skill.md，执行统一走 ReAct 循环。
 * 无需编写 Java 类即可新增专家。</p>
 */
public class GenericExpert extends BaseExpert {

    private String expertName = "generic";
    private String description = "";
    private String systemPrompt = "";
    private List<String> tools = List.of();
    private List<String> skills = List.of();

    public GenericExpert() {}

    @Override
    protected String executeLegacy(String task, TaskScope taskScope, Map<String, Object> inputs,
                                   String workspaceRoot, StreamObserver<StreamChunk> observer) {
        pushStatus(observer, "executing", "正在执行任务...");
        return runReActLoop(systemPrompt, task, workspaceRoot, observer);
    }

    @Override
    public String getName() {
        return expertName;
    }

    @Override
    public String getDescription() {
        return description;
    }

    /**
     * 覆盖工具过滤：配置声明 tools 时只暴露声明的子集；未声明则使用全部工具。
     */
    @Override
    protected List<ToolDefinition> getAvailableTools() {
        List<ToolDefinition> all = super.getAvailableTools();
        if (tools == null || tools.isEmpty()) return all;
        return all.stream()
                .filter(t -> tools.contains(t.getName()))
                .collect(Collectors.toList());
    }

    // ==================== 配置注入（由 CustomExpertLoader 调用） ====================

    public void setExpertName(String expertName) {
        this.expertName = expertName;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public void setSystemPrompt(String systemPrompt) {
        this.systemPrompt = systemPrompt;
    }

    public void setTools(List<String> tools) {
        this.tools = tools == null ? List.of() : tools;
    }

    public void setSkills(List<String> skills) {
        this.skills = skills == null ? List.of() : skills;
        // BaseExpert 的专家绑定注入直接使用此列表
        this.boundSkills = this.skills;
    }

    public List<String> getSkills() {
        return skills;
    }
}
