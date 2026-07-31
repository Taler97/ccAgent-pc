package com.ccagent.plugin.experts.impl;

import com.ccagent.core.expert.annotation.ExpertAnnotation;
import com.ccagent.api.context.TaskScope;
import com.ccagent.api.model.response.StreamChunk;
import com.ccagent.api.port.LlmPort;
import com.ccagent.api.port.StreamObserver;
import com.ccagent.api.port.ToolRegistryPort;

import java.util.Map;

@ExpertAnnotation(
    name = "note-taker",
    description = "笔记记录、知识管理和内容整理",
    category = "knowledge",
    tools = {"read_file", "write_file"}
)
public class NoteTakerExpert extends BaseExpert {

    private static final String SYSTEM_PROMPT = "你是一个知识管理助手。"
            + "根据用户提供的内容整理成结构化的笔记。"
            + "需要时主动读取已有笔记进行合并更新，使用 write_file 保存笔记。"
            + "如果未指定路径，默认保存到 notes/ 目录下。"
            + "使用 Markdown 格式，包含标题、要点和标签。以中文输出。";

    public NoteTakerExpert() {}

    public NoteTakerExpert(ToolRegistryPort toolRegistry, LlmPort llmPort) {
        super(toolRegistry, llmPort);
    }

    @Override
    public String execute(String task, TaskScope taskScope, Map<String, Object> inputs,
                          String workspaceRoot, StreamObserver<StreamChunk> observer) {
        pushStatus(observer, "RUNNING", "开始记录笔记: " + truncate(task, 50));

        String result = runReActLoop(SYSTEM_PROMPT, task, workspaceRoot, observer);

        pushStatus(observer, "SUCCESS", "笔记已完成");

        return result;
    }

    @Override
    public String getName() { return "note-taker"; }

    @Override
    public String getDescription() { return "笔记记录、知识管理和内容整理"; }
}