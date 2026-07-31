package com.ccagent.plugin.tools.mcp;

import com.ccagent.api.model.mcp.McpResource;
import com.ccagent.api.model.mcp.McpTool;
import com.ccagent.api.model.response.ExecutionResult;
import com.ccagent.api.model.tool.ToolDefinition;
import com.ccagent.api.port.McpPort;
import com.ccagent.api.port.ToolRegistryPort;
import com.ccagent.exception.base.ErrorCode;
import com.ccagent.exception.tool.ToolException;
import com.ccagent.infrastructure.util.JsonUtil;

import java.util.*;
import java.util.stream.Collectors;

/**
 * 默认 MCP 适配器
 * <p>实现 McpPort，将内部工具以 MCP 标准格式对外暴露。</p>
 */
public class DefaultMcpAdapter implements McpPort {

    private final ToolRegistryPort toolRegistryPort;

    public DefaultMcpAdapter(ToolRegistryPort toolRegistryPort) {
        this.toolRegistryPort = toolRegistryPort;
    }

    @Override
    public List<McpResource> listResources() {
        // V1.0 返回空列表
        return Collections.emptyList();
    }

    @Override
    public List<McpTool> listTools() {
        return toolRegistryPort.getToolDefinitions().stream()
                .map(this::toMcpTool)
                .collect(Collectors.toList());
    }

    @Override
    public ExecutionResult callTool(String toolName, Map<String, Object> args) {
        return toolRegistryPort.executeTool(toolName, args, null);
    }

    /**
     * 将 ToolDefinition 转换为 MCP 标准格式
     */
    private McpTool toMcpTool(ToolDefinition def) {
        Map<String, Object> inputSchema = new HashMap<>();
        inputSchema.put("type", "object");
        inputSchema.put("properties", def.getParameters() != null ? def.getParameters() : Map.of());

        return new McpTool(
                def.getName(),
                def.getDescription(),
                inputSchema
        );
    }
}