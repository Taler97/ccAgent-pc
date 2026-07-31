package com.ccagent.exception.approval;

import com.ccagent.exception.base.AgentException;
import com.ccagent.exception.base.ErrorCode;

/**
 * 审批领域异常
 * <p>承载审批超时、审批拒绝等错误。</p>
 */
public class ApprovalException extends AgentException {

    /** 仅 APPROVAL_TIMEOUT 时有值 */
    private final Integer timeoutSeconds;
    /** 审批对应的消息序列号 */
    private final String seqId;

    public ApprovalException(ErrorCode errorCode, String message) {
        super(errorCode, message);
        this.timeoutSeconds = null;
        this.seqId = null;
    }

    public ApprovalException(ErrorCode errorCode, String message, int timeoutSeconds, String seqId) {
        super(errorCode, message);
        this.timeoutSeconds = timeoutSeconds;
        this.seqId = seqId;
    }

    public ApprovalException(ErrorCode errorCode, String message, Throwable cause) {
        super(errorCode, message, cause);
        this.timeoutSeconds = null;
        this.seqId = null;
    }

    public Integer getTimeoutSeconds() {
        return timeoutSeconds;
    }

    public String getSeqId() {
        return seqId;
    }
}
