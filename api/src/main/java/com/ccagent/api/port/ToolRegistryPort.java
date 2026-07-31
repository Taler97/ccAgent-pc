package com.ccagent.api.port;

import com.ccagent.api.model.response.ExecutionResult;
import com.ccagent.api.model.tool.ToolDefinition;
import com.ccagent.exception.tool.ToolException;

import java.util.List;
import java.util.Map;

/**
 * 工具注册与执行端口
 * <p>注册所有可用工具，执行具体工具调用。
 * 由 adapter/tool/ 实现。</p>
 */
public interface ToolRegistryPort {

    /**
     * 获取所有工具定义（供 LLM 理解）
     *
     * @return 工具定义列表（按名称字母序排列）
     */
    List<ToolDefinition> getToolDefinitions();

    /**
     * 执行指定工具
     *
     * @param toolName      工具名称
     * @param args          参数映射
     * @param workspaceRoot 工作区根路径
     * @return 执行结果
     * @throws ToolException 执行失败或工具不存在
     */
    ExecutionResult executeTool(String toolName, Map<String, Object> args, String workspaceRoot) throws ToolException;
}
