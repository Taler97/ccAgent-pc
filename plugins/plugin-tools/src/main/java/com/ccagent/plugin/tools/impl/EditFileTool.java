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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Tool(
        name = "edit_file",
        description = "编辑文件内容，支持三种模式：replace（搜索替换）、insert（插入行）、delete（删除行）",
        readOnly = false
)
public class EditFileTool implements ToolExtensionPoint {

    @Override
    public void init(PluginContext context) {}

    private enum Mode {
        REPLACE, INSERT, DELETE
    }

    @Override
    public ExecutionResult execute(Map<String, Object> args, String workspaceRoot) {
        String filePath = args != null ? (String) args.get("path") : "";
        String modeStr = args != null ? (String) args.get("mode") : "";

        if (filePath == null || filePath.isBlank()) {
            throw new ToolException(ErrorCode.TOOL_EXECUTION_FAILED,
                    "参数 path 不能为空", getName(), null);
        }
        if (modeStr == null || modeStr.isBlank()) {
            throw new ToolException(ErrorCode.TOOL_EXECUTION_FAILED,
                    "参数 mode 不能为空（replace/insert/delete）", getName(), null);
        }

        Mode mode;
        try {
            mode = Mode.valueOf(modeStr.toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new ToolException(ErrorCode.TOOL_EXECUTION_FAILED,
                    "无效的 mode 值: " + modeStr + "，支持 replace/insert/delete", getName(), null);
        }

        String resolvedPath = PathUtil.resolve(workspaceRoot, filePath);
        Path path = Paths.get(resolvedPath);

        if (!FileUtil.exists(path)) {
            throw new ToolException(ErrorCode.TOOL_EXECUTION_FAILED,
                    "文件不存在: " + resolvedPath, getName(), resolvedPath);
        }
        if (FileUtil.isDirectory(path)) {
            throw new ToolException(ErrorCode.TOOL_EXECUTION_FAILED,
                    "路径是目录，无法编辑: " + resolvedPath, getName(), resolvedPath);
        }

        String originalContent = FileUtil.readString(path);
        List<String> originalLines = originalContent.isEmpty()
                ? new ArrayList<>()
                : new ArrayList<>(List.of(originalContent.split("\n", -1)));

        String resultContent;
        int linesChanged;

        switch (mode) {
            case REPLACE -> {
                String target = args != null ? (String) args.get("target") : "";
                String replacement = args != null ? (String) args.get("replacement") : "";
                if (target == null || target.isEmpty()) {
                    throw new ToolException(ErrorCode.TOOL_EXECUTION_FAILED,
                            "replace 模式需要参数 target", getName(), resolvedPath);
                }
                if (replacement == null) replacement = "";
                resultContent = doReplace(originalContent, target, replacement, resolvedPath);
                linesChanged = Math.abs(countLines(replacement) - countLines(target)) + 1;
            }
            case INSERT -> {
                String insertAfter = args != null ? (String) args.get("insert_after") : "";
                String content = args != null ? (String) args.get("content") : "";
                Integer lineNum = args != null && args.get("line") != null
                        ? toInt(args.get("line")) : null;
                if (content == null) content = "";
                resultContent = doInsert(originalLines, insertAfter, lineNum, content, resolvedPath);
                linesChanged = countLines(content);
            }
            case DELETE -> {
                Integer startLine = args != null && args.get("start_line") != null
                        ? toInt(args.get("start_line")) : null;
                Integer endLine = args != null && args.get("end_line") != null
                        ? toInt(args.get("end_line")) : null;
                String target = args != null ? (String) args.get("target") : "";
                resultContent = doDelete(originalLines, startLine, endLine, target, resolvedPath);
                int start = startLine != null ? startLine : 1;
                int end = endLine != null ? endLine : start;
                linesChanged = end - start + 1;
            }
            default -> throw new ToolException(ErrorCode.TOOL_EXECUTION_FAILED,
                    "未实现的模式: " + mode, getName(), resolvedPath);
        }

        FileUtil.writeString(path, resultContent);

        return new ExecutionResult(true,
                "编辑成功（模式: " + modeStr.toLowerCase() + "）",
                resolvedPath, linesChanged, 0, null);
    }

    private String doReplace(String content, String target, String replacement, String resolvedPath) {
        int count = 0;
        int idx = 0;
        while ((idx = content.indexOf(target, idx)) != -1) {
            count++;
            idx += target.length();
        }

        if (count == 0) {
            throw new ToolException(ErrorCode.TOOL_EXECUTION_FAILED,
                    "未找到匹配的 target 文本，请确认内容准确", getName(), resolvedPath);
        }
        if (count > 1) {
            throw new ToolException(ErrorCode.TOOL_EXECUTION_FAILED,
                    "找到 " + count + " 处匹配，target 必须唯一匹配。请提供更长的上下文以缩小范围",
                    getName(), resolvedPath);
        }

        return content.replace(target, replacement);
    }

    private String doInsert(List<String> lines, String insertAfter, Integer lineNum,
                            String content, String resolvedPath) {
        int insertPos;

        if (lineNum != null) {
            if (lineNum < 1 || lineNum > lines.size() + 1) {
                throw new ToolException(ErrorCode.TOOL_EXECUTION_FAILED,
                        "行号超出范围: " + lineNum + "（文件共 " + lines.size() + " 行）",
                        getName(), resolvedPath);
            }
            insertPos = lineNum - 1;
        } else if (insertAfter != null && !insertAfter.isEmpty()) {
            int matchCount = 0;
            int lastMatch = -1;
            for (int i = 0; i < lines.size(); i++) {
                if (lines.get(i).contains(insertAfter)) {
                    matchCount++;
                    lastMatch = i;
                }
            }
            if (matchCount == 0) {
                throw new ToolException(ErrorCode.TOOL_EXECUTION_FAILED,
                        "未找到匹配 insert_after 的行", getName(), resolvedPath);
            }
            if (matchCount > 1) {
                throw new ToolException(ErrorCode.TOOL_EXECUTION_FAILED,
                        "找到 " + matchCount + " 处匹配 insert_after 的行，必须唯一匹配",
                        getName(), resolvedPath);
            }
            insertPos = lastMatch + 1;
        } else {
            throw new ToolException(ErrorCode.TOOL_EXECUTION_FAILED,
                    "insert 模式需要参数 line 或 insert_after", getName(), resolvedPath);
        }

        List<String> insertLines = content.isEmpty() ? List.of() : List.of(content.split("\n", -1));
        List<String> result = new ArrayList<>(lines);
        result.addAll(insertPos, insertLines);
        return String.join("\n", result);
    }

    private String doDelete(List<String> lines, Integer startLine, Integer endLine,
                            String target, String resolvedPath) {
        if (target != null && !target.isEmpty()) {
            int count = 0;
            int firstMatch = -1;
            for (int i = 0; i < lines.size(); i++) {
                if (lines.get(i).contains(target)) {
                    count++;
                    if (firstMatch == -1) firstMatch = i;
                }
            }
            if (count == 0) {
                throw new ToolException(ErrorCode.TOOL_EXECUTION_FAILED,
                        "未找到匹配 target 的行", getName(), resolvedPath);
            }
            if (count > 1) {
                throw new ToolException(ErrorCode.TOOL_EXECUTION_FAILED,
                        "找到 " + count + " 处匹配 target 的行，必须唯一匹配",
                        getName(), resolvedPath);
            }
            List<String> result = new ArrayList<>(lines);
            result.remove(firstMatch);
            return String.join("\n", result);
        }

        if (startLine == null) {
            throw new ToolException(ErrorCode.TOOL_EXECUTION_FAILED,
                    "delete 模式需要参数 start_line 或 target", getName(), resolvedPath);
        }
        int start = startLine - 1;
        int end = (endLine != null ? endLine : startLine) - 1;

        if (start < 0 || end >= lines.size() || start > end) {
            throw new ToolException(ErrorCode.TOOL_EXECUTION_FAILED,
                    "行号范围无效: start_line=" + startLine + ", end_line=" + endLine
                            + "（文件共 " + lines.size() + " 行）",
                    getName(), resolvedPath);
        }

        List<String> result = new ArrayList<>(lines);
        for (int i = end; i >= start; i--) {
            result.remove(i);
        }
        return String.join("\n", result);
    }

    private int countLines(String s) {
        if (s == null || s.isEmpty()) return 0;
        return (int) s.chars().filter(c -> c == '\n').count() + 1;
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
        return "edit_file";
    }

    @Override
    public String getDescription() {
        return "编辑文件内容（replace/insert/delete 三种模式）";
    }

    @Override
    public boolean isReadOnly() {
        return false;
    }
}