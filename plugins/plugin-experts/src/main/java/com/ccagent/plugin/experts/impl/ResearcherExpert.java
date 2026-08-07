package com.ccagent.plugin.experts.impl;

import com.ccagent.core.expert.annotation.ExpertAnnotation;
import com.ccagent.api.context.TaskScope;
import com.ccagent.api.model.response.StreamChunk;
import com.ccagent.api.port.LlmPort;
import com.ccagent.api.port.StreamObserver;
import com.ccagent.api.port.ToolRegistryPort;

import java.util.Map;

@ExpertAnnotation(
    name = "researcher",
    description = "信息检索、资料分析和调研报告生成",
    category = "research",
    tools = {"list_directory", "read_file", "search_content"}
)
public class ResearcherExpert extends BaseExpert {

    private static final String SYSTEM_PROMPT = "你是一个专业的研究分析助手。"
            + "根据用户提供的任务，主动调用工具搜索和读取相关文件、收集信息。"
            + "然后进行分析、归纳和总结。"
            + "输出结构化的研究报告，包含要点分析和结论。以中文回复。";

    public ResearcherExpert() {}

    public ResearcherExpert(ToolRegistryPort toolRegistry, LlmPort llmPort) {
        super(toolRegistry, llmPort);
    }

    @Override
    protected String executeLegacy(String task, TaskScope taskScope, Map<String, Object> inputs,
                                   String workspaceRoot, StreamObserver<StreamChunk> observer) {
        pushStatus(observer, "RUNNING", "开始研究分析: " + truncate(task, 50));

        String result = runReActLoop(SYSTEM_PROMPT, task, workspaceRoot, observer);
        pushStatus(observer, "SUCCESS", "研究分析完成");

        return result;
    }

    @Override
    public String getName() { return "researcher"; }

    @Override
    public String getDescription() { return "信息检索、资料分析和调研报告生成"; }
}