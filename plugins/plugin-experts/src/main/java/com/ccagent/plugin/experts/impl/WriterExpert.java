package com.ccagent.plugin.experts.impl;

import com.ccagent.core.expert.annotation.ExpertAnnotation;
import com.ccagent.api.context.TaskScope;
import com.ccagent.api.model.response.StreamChunk;
import com.ccagent.api.port.LlmPort;
import com.ccagent.api.port.StreamObserver;
import com.ccagent.api.port.ToolRegistryPort;

import java.util.Map;

@ExpertAnnotation(
    name = "writer",
    description = "撰写、润色和改写各类文字内容，可读写和编辑文件",
    category = "write",
    tools = {"read_file", "write_file", "edit_file", "search_content"}
)
public class WriterExpert extends BaseExpert {

    private static final String SYSTEM_PROMPT = "你是一个专业的文字工作者。"
            + "根据用户的任务撰写、润色或改写内容。"
            + "需要时主动调用工具读取文件、搜索内容、写入或编辑文件。"
            + "输出格式清晰，语言得体。如果是生成文件，使用 write_file 保存。";

    public WriterExpert() {}

    public WriterExpert(ToolRegistryPort toolRegistry, LlmPort llmPort) {
        super(toolRegistry, llmPort);
    }

    @Override
    public String execute(String task, TaskScope taskScope, Map<String, Object> inputs,
                          String workspaceRoot, StreamObserver<StreamChunk> observer) {
        pushStatus(observer, "RUNNING", "开始撰写: " + truncate(task, 50));

        String analysis = inputs.containsKey("analysis")
                ? String.valueOf(inputs.get("analysis")) : "";
        String prompt = analysis.isEmpty()
                ? task
                : "参考分析结果：\n" + truncate(analysis, 5000) + "\n\n任务：\n" + task;

        String result = runReActLoop(SYSTEM_PROMPT, prompt, workspaceRoot, observer);
        pushStatus(observer, "SUCCESS", "撰写完成");

        return result;
    }

    @Override
    public String getName() { return "writer"; }

    @Override
    public String getDescription() { return "撰写、润色和改写各类文字内容，可读写和编辑文件"; }
}