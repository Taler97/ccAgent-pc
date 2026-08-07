package com.ccagent.plugin.experts.impl;

import com.ccagent.core.expert.annotation.ExpertAnnotation;
import com.ccagent.api.context.TaskScope;
import com.ccagent.api.model.response.StreamChunk;
import com.ccagent.api.port.LlmPort;
import com.ccagent.api.port.StreamObserver;
import com.ccagent.api.port.ToolRegistryPort;

import java.util.Map;

@ExpertAnnotation(
    name = "summarizer",
    description = "内容摘要、要点提炼和信息浓缩",
    category = "summary",
    tools = {"read_file", "search_content"}
)
public class SummarizerExpert extends BaseExpert {

    private static final String SYSTEM_PROMPT = "你是一个信息提炼助手。"
            + "根据用户提供的任务，主动调用工具读取文件或搜索内容。"
            + "然后生成简洁的摘要或提取关键要点。"
            + "保持客观准确，不添加原文没有的信息。"
            + "以中文回复，使用结构化格式（分点、表格等）。";

    public SummarizerExpert() {}

    public SummarizerExpert(ToolRegistryPort toolRegistry, LlmPort llmPort) {
        super(toolRegistry, llmPort);
    }

    @Override
    protected String executeLegacy(String task, TaskScope taskScope, Map<String, Object> inputs,
                                   String workspaceRoot, StreamObserver<StreamChunk> observer) {
        pushStatus(observer, "RUNNING", "开始提炼摘要: " + truncate(task, 50));

        String prompt = task;
        if (inputs.containsKey("analysis")) {
            prompt = "以下分析结果需要摘要：\n```\n"
                    + truncate(String.valueOf(inputs.get("analysis")), 8000)
                    + "\n```\n\n摘要要求：\n" + task;
        }

        String result = runReActLoop(SYSTEM_PROMPT, prompt, workspaceRoot, observer);
        pushStatus(observer, "SUCCESS", "摘要完成");

        return result;
    }

    @Override
    public String getName() { return "summarizer"; }

    @Override
    public String getDescription() { return "内容摘要、要点提炼和信息浓缩"; }
}