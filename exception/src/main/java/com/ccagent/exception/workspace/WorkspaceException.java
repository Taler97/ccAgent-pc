package com.ccagent.exception.workspace;

import com.ccagent.exception.base.AgentException;
import com.ccagent.exception.base.ErrorCode;

/**
 * 工作区领域异常
 * <p>承载工作区不存在、会话不存在等错误。</p>
 */
public class WorkspaceException extends AgentException {

    /** 工作区标识（仅 WORKSPACE_NOT_FOUND 时有值） */
    private final String workspaceId;
    /** 会话标识（仅 SESSION_NOT_FOUND 时有值） */
    private final String sessionId;

    public WorkspaceException(ErrorCode errorCode, String message) {
        super(errorCode, message);
        this.workspaceId = null;
        this.sessionId = null;
    }

    public WorkspaceException(ErrorCode errorCode, String message, String workspaceId, String sessionId) {
        super(errorCode, message);
        this.workspaceId = workspaceId;
        this.sessionId = sessionId;
    }

    public WorkspaceException(ErrorCode errorCode, String message, Throwable cause) {
        super(errorCode, message, cause);
        this.workspaceId = null;
        this.sessionId = null;
    }

    public String getWorkspaceId() {
        return workspaceId;
    }

    public String getSessionId() {
        return sessionId;
    }
}
