package com.ccagent.plugin.tools.impl;

import com.ccagent.core.plugin.ToolExtensionPoint;
import com.ccagent.kernel.plugin.PluginContext;
import com.ccagent.core.tool.annotation.Tool;
import com.ccagent.api.model.response.ExecutionResult;
import com.ccagent.exception.base.ErrorCode;
import com.ccagent.exception.security.SecurityException;
import com.ccagent.exception.tool.ToolException;
import com.ccagent.infrastructure.util.FileUtil;
import com.ccagent.infrastructure.util.PathUtil;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Map;

@Tool(
        name = "file_operation",
        description = "文件系统操作，支持 copy（复制）、move（移动/重命名）、delete（删除）、mkdir（创建目录）、stat（文件信息）",
        readOnly = false
)
public class FileOperationTool implements ToolExtensionPoint {

    @Override
    public void init(PluginContext context) {}

    private enum Operation {
        COPY, MOVE, DELETE, MKDIR, STAT
    }

    @Override
    public ExecutionResult execute(Map<String, Object> args, String workspaceRoot) {
        String opStr = args != null ? (String) args.get("operation") : "";

        if (opStr == null || opStr.isBlank()) {
            throw new ToolException(ErrorCode.TOOL_EXECUTION_FAILED,
                    "参数 operation 不能为空（copy/move/delete/mkdir/stat）", getName(), null);
        }

        Operation operation;
        try {
            operation = Operation.valueOf(opStr.toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new ToolException(ErrorCode.TOOL_EXECUTION_FAILED,
                    "无效的 operation 值: " + opStr + "，支持 copy/move/delete/mkdir/stat",
                    getName(), null);
        }

        switch (operation) {
            case COPY -> {
                String source = getRequiredArg(args, "source", "copy 模式需要参数 source");
                String target = getRequiredArg(args, "target", "copy 模式需要参数 target");
                return doCopy(source, target, workspaceRoot);
            }
            case MOVE -> {
                String source = getRequiredArg(args, "source", "move 模式需要参数 source");
                String target = getRequiredArg(args, "target", "move 模式需要参数 target");
                return doMove(source, target, workspaceRoot);
            }
            case DELETE -> {
                String path = getRequiredArg(args, "path", "delete 模式需要参数 path");
                return doDelete(path, workspaceRoot);
            }
            case MKDIR -> {
                String path = getRequiredArg(args, "path", "mkdir 模式需要参数 path");
                return doMkdir(path, workspaceRoot);
            }
            case STAT -> {
                String path = getRequiredArg(args, "path", "stat 模式需要参数 path");
                return doStat(path, workspaceRoot);
            }
            default -> throw new ToolException(ErrorCode.TOOL_EXECUTION_FAILED,
                    "未实现的操作: " + operation, getName(), null);
        }
    }

    private String getRequiredArg(Map<String, Object> args, String key, String errorMsg) {
        String value = args != null ? (String) args.get(key) : null;
        if (value == null || value.isBlank()) {
            throw new ToolException(ErrorCode.TOOL_EXECUTION_FAILED, errorMsg, getName(), null);
        }
        return value;
    }

    private void validatePath(String path, String workspaceRoot, String paramName) {
        String resolved = PathUtil.resolve(workspaceRoot, path);
        if (!PathUtil.isAllowedPath(workspaceRoot, resolved)) {
            throw new SecurityException(ErrorCode.PATH_TRAVERSAL_DETECTED,
                    "参数 " + paramName + " 超出工作区及白名单范围: " + path, resolved, workspaceRoot);
        }
    }

    private ExecutionResult doCopy(String source, String target, String workspaceRoot) {
        validatePath(source, workspaceRoot, "source");
        validatePath(target, workspaceRoot, "target");

        String resolvedSource = PathUtil.resolve(workspaceRoot, source);
        String resolvedTarget = PathUtil.resolve(workspaceRoot, target);
        Path src = Paths.get(resolvedSource);
        Path dst = Paths.get(resolvedTarget);

        if (!FileUtil.exists(src)) {
            throw new ToolException(ErrorCode.TOOL_EXECUTION_FAILED,
                    "源文件/目录不存在: " + resolvedSource, getName(), resolvedSource);
        }

        FileUtil.copy(src, dst);
        return new ExecutionResult(true,
                "复制成功: " + resolvedSource + " -> " + resolvedTarget,
                resolvedTarget, 0, 0, null);
    }

    private ExecutionResult doMove(String source, String target, String workspaceRoot) {
        validatePath(source, workspaceRoot, "source");
        validatePath(target, workspaceRoot, "target");

        String resolvedSource = PathUtil.resolve(workspaceRoot, source);
        String resolvedTarget = PathUtil.resolve(workspaceRoot, target);
        Path src = Paths.get(resolvedSource);
        Path dst = Paths.get(resolvedTarget);

        if (!FileUtil.exists(src)) {
            throw new ToolException(ErrorCode.TOOL_EXECUTION_FAILED,
                    "源文件/目录不存在: " + resolvedSource, getName(), resolvedSource);
        }

        try {
            Files.createDirectories(dst.getParent());
            Files.move(src, dst, StandardCopyOption.REPLACE_EXISTING);
        } catch (Exception e) {
            throw new ToolException(ErrorCode.TOOL_EXECUTION_FAILED,
                    "移动失败: " + resolvedSource + " -> " + resolvedTarget + ": " + e.getMessage(),
                    e, getName(), resolvedSource);
        }

        return new ExecutionResult(true,
                "移动成功: " + resolvedSource + " -> " + resolvedTarget,
                resolvedTarget, 0, 0, null);
    }

    private ExecutionResult doDelete(String path, String workspaceRoot) {
        validatePath(path, workspaceRoot, "path");

        String resolvedPath = PathUtil.resolve(workspaceRoot, path);
        Path p = Paths.get(resolvedPath);

        if (!FileUtil.exists(p)) {
            throw new ToolException(ErrorCode.TOOL_EXECUTION_FAILED,
                    "文件/目录不存在: " + resolvedPath, getName(), resolvedPath);
        }

        FileUtil.delete(p);
        return new ExecutionResult(true,
                "删除成功: " + resolvedPath,
                resolvedPath, 0, 0, null);
    }

    private ExecutionResult doMkdir(String path, String workspaceRoot) {
        validatePath(path, workspaceRoot, "path");

        String resolvedPath = PathUtil.resolve(workspaceRoot, path);
        Path p = Paths.get(resolvedPath);

        FileUtil.createDirectories(p);
        return new ExecutionResult(true,
                "目录创建成功: " + resolvedPath,
                resolvedPath, 0, 0, null);
    }

    private ExecutionResult doStat(String path, String workspaceRoot) {
        validatePath(path, workspaceRoot, "path");

        String resolvedPath = PathUtil.resolve(workspaceRoot, path);
        Path p = Paths.get(resolvedPath);

        if (!FileUtil.exists(p)) {
            throw new ToolException(ErrorCode.TOOL_EXECUTION_FAILED,
                    "文件/目录不存在: " + resolvedPath, getName(), resolvedPath);
        }

        try {
            BasicFileAttributes attrs = Files.readAttributes(p, BasicFileAttributes.class);
            String type = attrs.isDirectory() ? "directory"
                    : attrs.isRegularFile() ? "file"
                    : attrs.isSymbolicLink() ? "symlink" : "other";
            long size = attrs.isRegularFile() ? attrs.size() : -1;
            String created = formatTime(attrs.creationTime().toInstant());
            String modified = formatTime(attrs.lastModifiedTime().toInstant());

            StringBuilder sb = new StringBuilder();
            sb.append("路径: ").append(resolvedPath).append("\n");
            sb.append("类型: ").append(type).append("\n");
            if (size >= 0) {
                sb.append("大小: ").append(size).append(" bytes\n");
            }
            sb.append("创建时间: ").append(created).append("\n");
            sb.append("修改时间: ").append(modified);

            return new ExecutionResult(true, sb.toString(), resolvedPath, 0, 0, null);
        } catch (Exception e) {
            throw new ToolException(ErrorCode.TOOL_EXECUTION_FAILED,
                    "获取文件信息失败: " + e.getMessage(), e, getName(), resolvedPath);
        }
    }

    private String formatTime(Instant instant) {
        return LocalDateTime.ofInstant(instant, ZoneId.systemDefault())
                .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
    }

    @Override
    public String getName() {
        return "file_operation";
    }

    @Override
    public String getDescription() {
        return "文件系统操作（copy/move/delete/mkdir/stat）";
    }

    @Override
    public boolean isReadOnly() {
        return false;
    }
}