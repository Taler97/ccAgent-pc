package com.ccagent.core.governance.audit;

/**
 * 审计事件类型枚举
 */
public enum AuditEvent {
    PLAN_READY,
    PLAN_COMPLETE,
    STEP_START,
    STEP_COMPLETE,
    TOOL_CALL,
    EXECUTION_COMPLETE,
    DIALOG_ROLLBACK,
    BACKUP_CREATED,
    BACKUP_RESTORED,
    ERROR
}
