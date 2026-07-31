package com.ccagent.exception.base;

/**
 * 异常层根异常基类（抽象类，不允许直接实例化）
 * <p>
 * 所有业务异常必须继承此类，且必须位于 {@code exception/} 包内。
 * 子类必须提供 {@link #getErrorCode()} 方法（由本类实现）。
 * 子类可增加专属 getter 方法携带结构化参数。
 * </p>
 */
public abstract class AgentException extends RuntimeException {

    private final ErrorCode errorCode;

    /**
     * @param errorCode 错误码，不可为 null
     * @param message   错误描述
     */
    protected AgentException(ErrorCode errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }

    /**
     * @param errorCode 错误码，不可为 null
     * @param message   错误描述
     * @param cause     原始异常（保留 cause 链，不吞没底层异常）
     */
    protected AgentException(ErrorCode errorCode, String message, Throwable cause) {
        super(message, cause);
        this.errorCode = errorCode;
    }

    /**
     * 获取错误码
     */
    public ErrorCode getErrorCode() {
        return errorCode;
    }
}
