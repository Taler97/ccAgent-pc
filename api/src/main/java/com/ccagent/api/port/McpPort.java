package com.ccagent.api.port;

import com.ccagent.api.model.mcp.McpResource;
import com.ccagent.api.model.mcp.McpTool;
import com.ccagent.api.model.response.ExecutionResult;
import com.ccagent.exception.tool.ToolException;

import java.util.List;
import java.util.Map;

/**
 * MCP 标准协议暴露端口
 * <p>将内部工具和能力以 MCP（Model Context Protocol）标准格式对外暴露。
 * 由 adapter/mcp/ 实现。</p>
 */
public interface McpPort {

    /**
     * 列出所有资源（V1.0 返回空列表）
     *
     * @return 资源列表
     */
    List<McpResource> listResources();

    /**
     * 列出所有工具（MCP 标准格式）
     *
     * @return 工具列表
     */
    List<McpTool> listTools();

    /**
     * 调用工具（MCP 标准接口）
     *
     * @param toolName 工具名称
     * @param args     参数映射
     * @return 执行结果
     * @throws ToolException 执行失败
     */
    ExecutionResult callTool(String toolName, Map<String, Object> args) throws ToolException;
}
