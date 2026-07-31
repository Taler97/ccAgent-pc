package com.ccagent.exception.tool;

import com.ccagent.exception.base.AgentException;
import com.ccagent.exception.base.ErrorCode;

/**
 * 工具领域异常
 * <p>承载工具执行失败、工具不存在等错误。</p>
 */
public class ToolException extends AgentException {

    /** 失败的工具名称（如 write_file） */
    private final String toolName;
    /** 操作涉及的文件路径（如有） */
    private final String filePath;

    public ToolException(ErrorCode errorCode, String message) {
        super(errorCode, message);
        this.toolName = null;
        this.filePath = null;
    }

    public ToolException(ErrorCode errorCode, String message, Throwable cause) {
        super(errorCode, message, cause);
        this.toolName = null;
        this.filePath = null;
    }

    public ToolException(ErrorCode errorCode, String message, String toolName, String filePath) {
        super(errorCode, message);
        this.toolName = toolName;
        this.filePath = filePath;
    }

    public ToolException(ErrorCode errorCode, String message, Throwable cause, String toolName, String filePath) {
        super(errorCode, message, cause);
        this.toolName = toolName;
        this.filePath = filePath;
    }

    public String getToolName() {
        return toolName;
    }

    public String getFilePath() {
        return filePath;
    }
}
