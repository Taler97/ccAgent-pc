package com.ccagent.plugin.tools.mcp;

import com.ccagent.api.model.response.ExecutionResult;
import com.ccagent.core.tool.ToolExecutor;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.spec.McpSchema;

import java.util.Map;

/**
 * MCP 外部工具执行器
 * <p>包装远程 MCP server 的一个工具，注册进 ToolRegistry 后由专家 ReAct 循环调用。
 * 注册名为 <code>mcp_&lt;prefix&gt;_&lt;tool&gt;</code>，调用时经 client 转发到远端。</p>
 */
public class McpToolExecutor implements ToolExecutor {

    private final McpSyncClient client;
    private final String registeredName;
    private final String remoteName;
    private final String description;
    private final Map<String, Object> properties;

    public McpToolExecutor(McpSyncClient client, String registeredName, McpSchema.Tool tool) {
        this.client = client;
        this.registeredName = registeredName;
        this.remoteName = tool.name();
        this.description = tool.description();
        this.properties = tool.inputSchema() != null ? tool.inputSchema().properties() : Map.of();
    }

    @Override
    public ExecutionResult execute(Map<String, Object> args, String workspaceRoot) {
        try {
            McpSchema.CallToolRequest request = McpSchema.CallToolRequest.builder()
                    .name(remoteName)
                    .arguments(args == null ? Map.of() : args)
                    .build();
            McpSchema.CallToolResult result = client.callTool(request);
            StringBuilder sb = new StringBuilder();
            for (McpSchema.Content content : result.content()) {
                if (content instanceof McpSchema.TextContent text) {
                    if (sb.length() > 0) sb.append("\n");
                    sb.append(text.text());
                }
            }
            String text = sb.toString();
            return new ExecutionResult(!result.isError(), text, null, 0, 0, null);
        } catch (Exception e) {
            return new ExecutionResult(false, "MCP 工具调用失败: " + e.getMessage(), null, 0, 0, null);
        }
    }

    @Override
    public String getName() {
        return registeredName;
    }

    @Override
    public String getDescription() {
        return description != null ? description : "";
    }

    @Override
    public boolean isReadOnly() {
        return false;
    }

    @Override
    public Map<String, Object> getParameters() {
        return properties;
    }
}
