package com.ccagent.presentation.di;

import com.ccagent.plugin.tools.mcp.DefaultMcpAdapter;
import com.ccagent.core.plugin.ToolExtensionPoint;
import com.ccagent.core.tool.DefaultToolRegistry;
import com.ccagent.api.port.McpPort;
import com.ccagent.api.port.ToolRegistryPort;
import com.ccagent.kernel.plugin.PluginContext;
import com.ccagent.kernel.plugin.PluginManager;
import com.ccagent.kernel.plugin.PluginType;

import java.util.List;

public class ToolModule {

    private DefaultToolRegistry toolRegistry;
    private DefaultMcpAdapter mcpAdapter;

    public void init() {
        toolRegistry = new DefaultToolRegistry();
        mcpAdapter = new DefaultMcpAdapter(toolRegistry);
    }

    public void init(PluginContext ctx, PluginManager pm) {
        toolRegistry = new DefaultToolRegistry();
        List<ToolExtensionPoint> tools = pm.loadExtensions(PluginType.TOOL, ctx)
                .stream()
                .map(ext -> (ToolExtensionPoint) ext)
                .toList();
        for (ToolExtensionPoint tool : tools) {
            toolRegistry.register(tool);
        }
        mcpAdapter = new DefaultMcpAdapter(toolRegistry);
        if (ctx instanceof com.ccagent.kernel.plugin.SimplePluginContext spc) {
            spc.registerService(ToolRegistryPort.class, toolRegistry);
        }
    }

    public ToolRegistryPort getToolRegistry() {
        return toolRegistry;
    }

    public McpPort getMcpAdapter() {
        return mcpAdapter;
    }
}