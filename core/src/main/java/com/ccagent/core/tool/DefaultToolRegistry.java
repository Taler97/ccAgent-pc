package com.ccagent.core.tool;

import com.ccagent.core.tool.annotation.Tool;
import com.ccagent.api.model.response.ExecutionResult;
import com.ccagent.api.model.tool.ToolDefinition;
import com.ccagent.api.port.ToolRegistryPort;
import com.ccagent.exception.base.ErrorCode;
import com.ccagent.exception.security.SecurityException;
import com.ccagent.exception.tool.ToolException;
import com.ccagent.infrastructure.util.PathUtil;

import java.util.*;
import java.util.stream.Collectors;

public class DefaultToolRegistry implements ToolRegistryPort {

    private final Map<String, ToolExecutor> tools = new TreeMap<>();
    private final ToolCallRepairer repairer = new ToolCallRepairer();

    public DefaultToolRegistry() {
    }

    public void register(ToolExecutor executor) {
        tools.put(executor.getName(), executor);
    }

    @Override
    public List<ToolDefinition> getToolDefinitions() {
        return tools.values().stream()
                .map(this::toToolDefinition)
                .collect(Collectors.toList());
    }

    @Override
    public ExecutionResult executeTool(String toolName, Map<String, Object> args, String workspaceRoot) {
        ToolExecutor executor = tools.get(toolName);
        if (executor == null) {
            throw new ToolException(ErrorCode.TOOL_NOT_FOUND,
                    "工具不存在: " + toolName, toolName, null);
        }
        if (workspaceRoot != null && args != null) {
            for (var entry : args.entrySet()) {
                if ("path".equals(entry.getKey()) && entry.getValue() instanceof String pathStr && !pathStr.isBlank()) {
                    String resolved = PathUtil.resolve(workspaceRoot, pathStr);
                    if (!PathUtil.isSubPath(workspaceRoot, resolved)) {
                        throw new SecurityException(ErrorCode.PATH_TRAVERSAL_DETECTED,
                                "路径超出工作区范围: " + pathStr, resolved, workspaceRoot);
                    }
                }
            }
        }
        return executor.execute(args, workspaceRoot);
    }

    public ExecutionResult executeToolWithRepair(String toolName, String argsJson, String workspaceRoot) {
        String matchedName = matchToolName(toolName);
        if (matchedName == null) {
            throw new ToolException(ErrorCode.TOOL_NOT_FOUND,
                    "工具不存在: " + toolName, toolName, null);
        }
        Map<String, Object> repairedArgs;
        try {
            repairedArgs = repairer.repair(matchedName, argsJson, tools.keySet());
        } catch (IllegalArgumentException e) {
            throw new ToolException(ErrorCode.TOOL_EXECUTION_FAILED,
                    "工具参数修复失败: " + e.getMessage(), matchedName, null);
        }
        return executeTool(matchedName, repairedArgs, workspaceRoot);
    }

    private ToolDefinition toToolDefinition(ToolExecutor executor) {
        Map<String, Object> parameters = new HashMap<>();
        parameters.put("type", "object");

        Tool annotation = executor.getClass().getAnnotation(Tool.class);
        String description = annotation != null ? annotation.description() : executor.getDescription();

        return new ToolDefinition(
                executor.getName(),
                description,
                parameters,
                executor.isReadOnly()
        );
    }

    private String matchToolName(String name) {
        if (name == null) return null;
        if (tools.containsKey(name)) return name;
        String lowerName = name.toLowerCase(Locale.ROOT);
        for (String registered : tools.keySet()) {
            if (registered.toLowerCase(Locale.ROOT).equals(lowerName)) {
                return registered;
            }
        }
        return null;
    }

    public Set<String> getRegisteredToolNames() {
        return tools.keySet();
    }
}
