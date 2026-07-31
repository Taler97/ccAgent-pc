package com.ccagent.plugin.tools.impl;

import com.ccagent.core.plugin.ToolExtensionPoint;
import com.ccagent.kernel.plugin.PluginContext;
import com.ccagent.core.tool.annotation.Tool;
import com.ccagent.api.model.response.ExecutionResult;
import com.ccagent.exception.base.ErrorCode;
import com.ccagent.exception.tool.ToolException;
import com.ccagent.infrastructure.util.PathUtil;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

@Tool(
        name = "list_directory",
        description = "列出目录内容（文件/文件夹/大小），支持递归、文件过滤和深度限制",
        readOnly = true
)
public class ListDirectoryTool implements ToolExtensionPoint {

    @Override
    public void init(PluginContext context) {}

    private static final int DEFAULT_MAX_DEPTH = 3;
    private static final int MAX_RESULT_LIMIT = 500;

    @Override
    public ExecutionResult execute(Map<String, Object> args, String workspaceRoot) {
        String dirPath = args != null && args.containsKey("path")
                ? (String) args.get("path")
                : "";
        boolean recursive = args != null && args.containsKey("recursive")
                && Boolean.TRUE.equals(args.get("recursive"));
        String include = args != null ? (String) args.get("include") : null;
        String exclude = args != null ? (String) args.get("exclude") : null;
        int maxDepth = args != null && args.containsKey("max_depth")
                ? toInt(args.get("max_depth"), DEFAULT_MAX_DEPTH) : DEFAULT_MAX_DEPTH;

        String resolvedPath = PathUtil.resolve(workspaceRoot, dirPath);
        Path dir = Paths.get(resolvedPath);

        if (!Files.exists(dir)) {
            throw new ToolException(ErrorCode.TOOL_EXECUTION_FAILED,
                    "目录不存在: " + resolvedPath, getName(), resolvedPath);
        }
        if (!Files.isDirectory(dir)) {
            throw new ToolException(ErrorCode.TOOL_EXECUTION_FAILED,
                    "路径不是目录: " + resolvedPath, getName(), resolvedPath);
        }

        List<String> entries;
        try {
            if (recursive) {
                try (Stream<Path> walk = Files.walk(dir, maxDepth)) {
                    entries = walk
                            .filter(p -> !p.equals(dir))
                            .filter(p -> matchesFilter(p, dir, include, exclude))
                            .limit(MAX_RESULT_LIMIT)
                            .map(p -> formatEntry(p, dir))
                            .toList();
                }
            } else {
                try (Stream<Path> stream = Files.list(dir)) {
                    entries = stream
                            .filter(p -> matchesFilter(p, dir, include, exclude))
                            .limit(MAX_RESULT_LIMIT)
                            .map(p -> formatEntry(p, dir))
                            .toList();
                }
            }
        } catch (Exception e) {
            throw new ToolException(ErrorCode.TOOL_EXECUTION_FAILED,
                    "列出目录失败: " + resolvedPath, e, getName(), resolvedPath);
        }

        String result = entries.isEmpty()
                ? "(空目录)"
                : String.join("\n", entries);
        if (entries.size() >= MAX_RESULT_LIMIT) {
            result += "\n... (结果超过 " + MAX_RESULT_LIMIT + " 条，已截断)";
        }

        return new ExecutionResult(true, result, resolvedPath, 0, 0, null);
    }

    private boolean matchesFilter(Path p, Path base, String include, String exclude) {
        String relativeName = p.getFileName().toString();
        if (include != null && !include.isBlank()) {
            if (!matchesGlob(relativeName, include)) {
                return false;
            }
        }
        if (exclude != null && !exclude.isBlank()) {
            if (matchesGlob(relativeName, exclude)) {
                return false;
            }
        }
        return true;
    }

    private boolean matchesGlob(String name, String pattern) {
        if (pattern == null || pattern.isBlank()) return true;
        String regex = globToRegex(pattern);
        return name.matches(regex);
    }

    private String globToRegex(String glob) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < glob.length(); i++) {
            char c = glob.charAt(i);
            switch (c) {
                case '*' -> sb.append(".*");
                case '?' -> sb.append('.');
                case '.' -> sb.append("\\.");
                case '\\' -> sb.append("\\\\");
                default -> sb.append(c);
            }
        }
        return sb.toString();
    }

    private String formatEntry(Path p, Path base) {
        String type = Files.isDirectory(p) ? "[DIR]" : "[FILE]";
        String relative = base.relativize(p).toString().replace("\\", "/");
        long size = 0;
        try { size = Files.size(p); } catch (Exception ignored) {}
        return type + " " + relative + " (" + size + " bytes)";
    }

    private int toInt(Object obj, int defaultValue) {
        if (obj instanceof Integer i) return i;
        if (obj instanceof Number n) return n.intValue();
        if (obj instanceof String s) {
            try { return Integer.parseInt(s.trim()); }
            catch (NumberFormatException e) { return defaultValue; }
        }
        return defaultValue;
    }

    @Override
    public String getName() {
        return "list_directory";
    }

    @Override
    public String getDescription() {
        return "列出目录内容（文件/文件夹/大小），支持递归和过滤";
    }

    @Override
    public boolean isReadOnly() {
        return true;
    }
}