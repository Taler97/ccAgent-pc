package com.ccagent.plugin.tools.impl;

import com.ccagent.core.plugin.ToolExtensionPoint;
import com.ccagent.kernel.plugin.PluginContext;
import com.ccagent.core.tool.annotation.Tool;
import com.ccagent.api.model.response.ExecutionResult;
import com.ccagent.exception.base.ErrorCode;
import com.ccagent.exception.tool.ToolException;
import com.ccagent.infrastructure.util.FileUtil;
import com.ccagent.infrastructure.util.PathUtil;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

@Tool(
        name = "read_file",
        description = "读取指定文件的内容，支持按行号范围分段读取",
        readOnly = true
)
public class ReadFileTool implements ToolExtensionPoint {

    @Override
    public void init(PluginContext context) {}

    private static final int MAX_LINES = 2000;

    @Override
    public ExecutionResult execute(Map<String, Object> args, String workspaceRoot) {
        String filePath = args != null ? (String) args.get("path") : "";
        if (filePath == null || filePath.isBlank()) {
            throw new ToolException(ErrorCode.TOOL_EXECUTION_FAILED,
                    "参数 path 不能为空", getName(), null);
        }

        String resolvedPath = PathUtil.resolve(workspaceRoot, filePath);
        Path path = Paths.get(resolvedPath);

        if (!FileUtil.exists(path)) {
            throw new ToolException(ErrorCode.TOOL_EXECUTION_FAILED,
                    "文件不存在: " + resolvedPath, getName(), resolvedPath);
        }
        if (FileUtil.isDirectory(path)) {
            throw new ToolException(ErrorCode.TOOL_EXECUTION_FAILED,
                    "路径是目录，无法读取: " + resolvedPath, getName(), resolvedPath);
        }

        Integer startLine = args != null && args.get("start_line") != null
                ? toInt(args.get("start_line")) : null;
        Integer endLine = args != null && args.get("end_line") != null
                ? toInt(args.get("end_line")) : null;

        String content = FileUtil.readString(path);
        String[] lineArray = content.isEmpty() ? new String[0] : content.split("\n", -1);
        int totalLines = lineArray.length;

        if (startLine == null && endLine == null) {
            if (totalLines > MAX_LINES) {
                List<String> head = Arrays.asList(Arrays.copyOfRange(lineArray, 0, MAX_LINES));
                String truncated = String.join("\n", head)
                        + "\n... (文件共 " + totalLines + " 行，已截断前 " + MAX_LINES + " 行，"
                        + "请使用 start_line/end_line 分段读取)";
                return new ExecutionResult(true, truncated, resolvedPath, 0, 0, null);
            }
            return new ExecutionResult(true, content, resolvedPath, 0, 0, null);
        }

        int start = startLine != null ? Math.max(1, startLine) : 1;
        int end = endLine != null ? Math.min(totalLines, endLine) : Math.min(totalLines, start + MAX_LINES - 1);

        if (start > totalLines) {
            return new ExecutionResult(true,
                    "(文件共 " + totalLines + " 行，start_line=" + start + " 超出范围)",
                    resolvedPath, 0, 0, null);
        }
        if (end < start) {
            throw new ToolException(ErrorCode.TOOL_EXECUTION_FAILED,
                    "行号范围无效: start_line=" + startLine + ", end_line=" + endLine,
                    getName(), resolvedPath);
        }

        if (end - start + 1 > MAX_LINES) {
            end = start + MAX_LINES - 1;
        }

        List<String> slice = Arrays.asList(Arrays.copyOfRange(lineArray, start - 1, end));
        String result = "--- 第 " + start + "-" + end + " 行 (共 " + totalLines + " 行) ---\n"
                + String.join("\n", slice);

        return new ExecutionResult(true, result, resolvedPath, 0, 0, null);
    }

    private Integer toInt(Object obj) {
        if (obj instanceof Integer i) return i;
        if (obj instanceof Number n) return n.intValue();
        if (obj instanceof String s) {
            try { return Integer.parseInt(s.trim()); }
            catch (NumberFormatException e) { return null; }
        }
        return null;
    }

    @Override
    public String getName() {
        return "read_file";
    }

    @Override
    public String getDescription() {
        return "读取文件内容，支持按行号范围分段读取";
    }

    @Override
    public boolean isReadOnly() {
        return true;
    }
}