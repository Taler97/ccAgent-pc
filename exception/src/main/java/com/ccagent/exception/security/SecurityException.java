package com.ccagent.exception.security;

import com.ccagent.exception.base.AgentException;
import com.ccagent.exception.base.ErrorCode;

/**
 * 安全领域异常
 * <p>承载路径穿越等安全相关错误。</p>
 */
public class SecurityException extends AgentException {

    /** 试图访问的路径 */
    private final String requestedPath;
    /** 当前工作区根路径 */
    private final String workspaceRoot;

    public SecurityException(ErrorCode errorCode, String message) {
        super(errorCode, message);
        this.requestedPath = null;
        this.workspaceRoot = null;
    }

    public SecurityException(ErrorCode errorCode, String message, String requestedPath, String workspaceRoot) {
        super(errorCode, message);
        this.requestedPath = requestedPath;
        this.workspaceRoot = workspaceRoot;
    }

    public SecurityException(ErrorCode errorCode, String message, Throwable cause) {
        super(errorCode, message, cause);
        this.requestedPath = null;
        this.workspaceRoot = null;
    }

    public String getRequestedPath() {
        return requestedPath;
    }

    public String getWorkspaceRoot() {
        return workspaceRoot;
    }
}
