package com.ccagent.core.orchestration.context;

/**
 * 执行上下文
 * <p>承载当前执行流的阶段标记和元数据，供治理层拦截器读取。
 * 使用 ThreadLocal 存储，确保虚拟线程隔离。</p>
 */
public class ExecutionContext {

    /** 执行阶段枚举 */
    public enum ExecutionStage {
        IDLE,
        PLANNING,
        EXECUTING
    }

    private ExecutionStage stage = ExecutionStage.IDLE;
    private String sessionId;
    private String workspaceRoot;
    private String seqId;
    /** 会话级 skills 合并后的 prompt（模式 A：会话启用的技能注入） */
    private String skillsPrompt;

    public ExecutionContext() {}

    public ExecutionContext(ExecutionStage stage, String sessionId, String workspaceRoot, String seqId) {
        this.stage = stage;
        this.sessionId = sessionId;
        this.workspaceRoot = workspaceRoot;
        this.seqId = seqId;
    }

    public ExecutionStage getStage() { return stage; }
    public void setStage(ExecutionStage stage) { this.stage = stage; }
    public String getSessionId() { return sessionId; }
    public void setSessionId(String sessionId) { this.sessionId = sessionId; }
    public String getWorkspaceRoot() { return workspaceRoot; }
    public void setWorkspaceRoot(String workspaceRoot) { this.workspaceRoot = workspaceRoot; }
    public String getSeqId() { return seqId; }
    public void setSeqId(String seqId) { this.seqId = seqId; }
    public String getSkillsPrompt() { return skillsPrompt; }
    public void setSkillsPrompt(String skillsPrompt) { this.skillsPrompt = skillsPrompt; }
}
