package com.ccagent.plugin.experts.impl;

import com.ccagent.core.expert.annotation.ExpertAnnotation;
import com.ccagent.api.context.TaskScope;
import com.ccagent.api.model.response.StreamChunk;
import com.ccagent.api.port.LlmPort;
import com.ccagent.api.port.StreamObserver;
import com.ccagent.api.port.ToolRegistryPort;

import java.util.Map;

@ExpertAnnotation(
    name = "organizer",
    description = "文件整理、分类归档和目录管理",
    category = "file",
    tools = {"list_directory", "read_file", "file_operation"}
)
public class OrganizerExpert extends BaseExpert {

    private static final String SYSTEM_PROMPT = "你是一个文件管理助手。"
            + "根据用户的整理任务，主动调用工具浏览目录、读取文件、复制移动文件等。"
            + "分析文件和目录情况后，给出整理建议或直接执行整理操作。"
            + "以中文回复。";

    public OrganizerExpert() {}

    public OrganizerExpert(ToolRegistryPort toolRegistry, LlmPort llmPort) {
        super(toolRegistry, llmPort);
    }

    @Override
    public String execute(String task, TaskScope taskScope, Map<String, Object> inputs,
                          String workspaceRoot, StreamObserver<StreamChunk> observer) {
        pushStatus(observer, "RUNNING", "开始整理: " + truncate(task, 50));

        String result = runReActLoop(SYSTEM_PROMPT, task, workspaceRoot, observer);
        pushStatus(observer, "SUCCESS", "整理完成");

        return result;
    }

    @Override
    public String getName() { return "organizer"; }

    @Override
    public String getDescription() { return "文件整理、分类归档和目录管理"; }
}