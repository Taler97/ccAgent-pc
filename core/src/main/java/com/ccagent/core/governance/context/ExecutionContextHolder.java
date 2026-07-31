package com.ccagent.core.governance.context;

import com.ccagent.core.orchestration.context.ExecutionContext;

/**
 * 执行上下文持有者（ThreadLocal）
 * <p>管理当前虚拟线程的执行阶段标记（PLANNING / EXECUTING / IDLE）。
 * 供审计、状态展示等使用。</p>
 */
public class ExecutionContextHolder {

    private static final ThreadLocal<ExecutionContext> CONTEXT = new ThreadLocal<>();

    private ExecutionContextHolder() {}

    /**
     * 设置当前执行上下文
     */
    public static void setContext(ExecutionContext ctx) {
        CONTEXT.set(ctx);
    }

    /**
     * 获取当前执行上下文
     */
    public static ExecutionContext getContext() {
        return CONTEXT.get();
    }

    /**
     * 设置执行阶段
     */
    public static void setStage(ExecutionContext.ExecutionStage stage) {
        ExecutionContext ctx = CONTEXT.get();
        if (ctx != null) {
            ctx.setStage(stage);
        }
    }

    /**
     * 获取当前执行阶段
     */
    public static ExecutionContext.ExecutionStage getStage() {
        ExecutionContext ctx = CONTEXT.get();
        return ctx != null ? ctx.getStage() : ExecutionContext.ExecutionStage.IDLE;
    }

    /**
     * 判断当前是否为规划阶段
     */
    public static boolean isPlanning() {
        return getStage() == ExecutionContext.ExecutionStage.PLANNING;
    }

    /**
     * 判断当前是否为执行阶段
     */
    public static boolean isExecuting() {
        return getStage() == ExecutionContext.ExecutionStage.EXECUTING;
    }

    /**
     * 创建当前上下文的副本（供虚拟线程使用）
     */
    public static ExecutionContext copyContext() {
        ExecutionContext ctx = CONTEXT.get();
        if (ctx == null) return null;
        return new ExecutionContext(ctx.getStage(), ctx.getSessionId(),
                ctx.getWorkspaceRoot(), ctx.getSeqId());
    }

    /**
     * 将上下文副本设置到当前线程（供虚拟线程子线程使用）
     */
    public static void propagate(ExecutionContext ctx) {
        if (ctx != null) {
            CONTEXT.set(ctx);
        }
    }

    /**
     * 清理上下文（务必在 finally 中调用）
     */
    public static void clear() {
        CONTEXT.remove();
    }
}
