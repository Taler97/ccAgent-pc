package com.ccagent.exception.llm;

import com.ccagent.exception.base.AgentException;
import com.ccagent.exception.base.ErrorCode;

/**
 * LLM 领域异常
 * <p>承载超时/服务不可用/认证失败等 LLM 相关错误。</p>
 */
public class LlmException extends AgentException {

    /** 仅当 errorCode == LLM_TIMEOUT 时有值 */
    private final Integer timeoutSeconds;
    /** 调用的 API 端点（如 /v1/chat/completions） */
    private final String endpoint;

    public LlmException(ErrorCode errorCode, String message) {
        super(errorCode, message);
        this.timeoutSeconds = null;
        this.endpoint = null;
    }

    public LlmException(ErrorCode errorCode, String message, Throwable cause) {
        super(errorCode, message, cause);
        this.timeoutSeconds = null;
        this.endpoint = null;
    }

    public LlmException(ErrorCode errorCode, String message, int timeoutSeconds) {
        super(errorCode, message);
        this.timeoutSeconds = timeoutSeconds;
        this.endpoint = null;
    }

    public LlmException(ErrorCode errorCode, String message, Throwable cause, int timeoutSeconds, String endpoint) {
        super(errorCode, message, cause);
        this.timeoutSeconds = timeoutSeconds;
        this.endpoint = endpoint;
    }

    public Integer getTimeoutSeconds() {
        return timeoutSeconds;
    }

    public String getEndpoint() {
        return endpoint;
    }
}
