package com.ccagent.exception.governance;

import com.ccagent.exception.base.AgentException;
import com.ccagent.exception.base.ErrorCode;

/**
 * 治理层领域异常
 * <p>承载配额超限、Plan 校验失败、备份恢复失败等错误。</p>
 */
public class GovernanceException extends AgentException {

    /** 仅 QUOTA_EXCEEDED 时有值 */
    private final Long currentUsage;
    /** 仅 QUOTA_EXCEEDED 时有值 */
    private final Long maxLimit;
    /** 仅 PLAN_INVALID 时有值（如"步骤数 12 超过上限 10"） */
    private final String validationDetails;
    /** 仅 BACKUP_FAILED 时有值 */
    private final String backupPath;

    public GovernanceException(ErrorCode errorCode, String message) {
        super(errorCode, message);
        this.currentUsage = null;
        this.maxLimit = null;
        this.validationDetails = null;
        this.backupPath = null;
    }

    public GovernanceException(ErrorCode errorCode, String message, Throwable cause) {
        super(errorCode, message, cause);
        this.currentUsage = null;
        this.maxLimit = null;
        this.validationDetails = null;
        this.backupPath = null;
    }

    /** 配额超限构造器 */
    public GovernanceException(ErrorCode errorCode, String message, long currentUsage, long maxLimit) {
        super(errorCode, message);
        this.currentUsage = currentUsage;
        this.maxLimit = maxLimit;
        this.validationDetails = null;
        this.backupPath = null;
    }

    /** Plan 校验失败构造器 */
    public GovernanceException(ErrorCode errorCode, String message, String validationDetails) {
        super(errorCode, message);
        this.currentUsage = null;
        this.maxLimit = null;
        this.validationDetails = validationDetails;
        this.backupPath = null;
    }

    /** 备份恢复失败构造器 */
    public GovernanceException(ErrorCode errorCode, String message, Throwable cause, String backupPath) {
        super(errorCode, message, cause);
        this.currentUsage = null;
        this.maxLimit = null;
        this.validationDetails = null;
        this.backupPath = backupPath;
    }

    public Long getCurrentUsage() {
        return currentUsage;
    }

    public Long getMaxLimit() {
        return maxLimit;
    }

    public String getValidationDetails() {
        return validationDetails;
    }

    public String getBackupPath() {
        return backupPath;
    }
}
