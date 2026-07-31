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
import java.util.Map;

/**
 * 写入/覆盖文件工具
 */
@Tool(
        name = "write_file",
        description = "写入或覆盖指定文件的内容（修改操作，需用户确认）",
        readOnly = false
)
public class WriteFileTool implements ToolExtensionPoint {

    @Override
    public void init(PluginContext context) {}

    @Override
    public ExecutionResult execute(Map<String, Object> args, String workspaceRoot) {
        String filePath = args != null ? (String) args.get("path") : "";
        String content = args != null ? (String) args.get("content") : "";

        if (filePath == null || filePath.isBlank()) {
            throw new ToolException(ErrorCode.TOOL_EXECUTION_FAILED,
                    "参数 path 不能为空", getName(), null);
        }
        if (content == null) {
            content = "";
        }

        String resolvedPath = PathUtil.resolve(workspaceRoot, filePath);
        Path path = Paths.get(resolvedPath);

        // 确保父目录存在
        Path parent = path.getParent();
        if (parent != null) {
            FileUtil.createDirectories(parent);
        }

        // 写入文件
        FileUtil.writeString(path, content);

        // 计算行数变化
        int linesChanged = content.isEmpty() ? 0 : content.split("\n").length;

        return new ExecutionResult(true, "文件写入成功", resolvedPath, linesChanged, 0, null);
    }

    @Override
    public String getName() {
        return "write_file";
    }

    @Override
    public String getDescription() {
        return "写入/覆盖文件内容";
    }

    @Override
    public boolean isReadOnly() {
        return false;
    }
}