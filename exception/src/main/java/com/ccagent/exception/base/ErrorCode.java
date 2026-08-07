package com.ccagent.exception.base;

/**
 * 统一错误码枚举（粗粒度设计）
 * 每个 ErrorCode 对应一个 AgentException 子类，按领域分组。
 * 枚举值携带默认消息模板（含占位符），供上层在无额外上下文时使用。
 */
public enum ErrorCode {

    // ========== LLM 领域 ==========
    /** LLM 调用超时 */
    LLM_TIMEOUT("LLM 调用超时（%d 秒）"),
    /** DeepSeek 服务不可用 */
    LLM_SERVICE_UNAVAILABLE("LLM 服务不可用"),
    /** API Key 无效 */
    LLM_AUTH_FAILED("API Key 无效"),

    // ========== 工具领域 ==========
    /** 工具执行失败（IO/权限/文件不存在） */
    TOOL_EXECUTION_FAILED("执行 %s 工具失败"),
    /** 调用不存在的工具 */
    TOOL_NOT_FOUND("工具 %s 不存在"),
    /** 调用不存在的专家 */
    EXPERT_NOT_FOUND("专家 %s 不存在"),

    // ========== 工作区领域 ==========
    /** 工作区不存在 */
    WORKSPACE_NOT_FOUND("工作区 %s 不存在"),
    /** 会话不存在 */
    SESSION_NOT_FOUND("会话 %s 不存在"),
    /** 会话忙：同一会话已有任务在执行/等待审批 */
    SESSION_BUSY("会话忙，已有任务在执行"),

    // ========== 审批领域 ==========
    /** 审批超时 */
    APPROVAL_TIMEOUT("审批超时（%d 秒）"),
    /** 用户拒绝审批 */
    APPROVAL_REJECTED("审批被拒绝"),

    // ========== 安全领域 ==========
    /** 路径穿越攻击 */
    PATH_TRAVERSAL_DETECTED("路径 %s 超出工作区范围"),

    // ========== 治理层领域 ==========
    /** Token 配额超限 */
    QUOTA_EXCEEDED("Token 配额超限（当前 %d，上限 %d）"),
    /** Plan 校验失败（超步骤/循环依赖） */
    PLAN_INVALID("Plan 校验失败: %s"),
    /** 备份或恢复失败 */
    BACKUP_FAILED("备份或恢复失败: %s"),

    // ========== 解析错误 ==========
    /** JSON/YAML 解析失败 */
    PARSE_ERROR("解析失败: %s");

    private final String defaultTemplate;

    ErrorCode(String defaultTemplate) {
        this.defaultTemplate = defaultTemplate;
    }

    /**
     * 获取默认消息模板（含占位符，如 %d、%s）
     */
    public String getDefaultTemplate() {
        return defaultTemplate;
    }

    /**
     * 使用模板参数格式化默认消息
     *
     * @param args 模板参数，顺序与模板中的占位符对应
     * @return 格式化后的消息
     */
    public String formatMessage(Object... args) {
        return String.format(defaultTemplate, args);
    }
}
