package com.ccagent.core.orchestration.orchestrator;

import com.ccagent.api.context.TaskScope;
import com.ccagent.api.model.memory.ConversationTurn;
import com.ccagent.api.model.memory.SessionInfo;
import com.ccagent.api.model.plan.Plan;
import com.ccagent.api.model.request.ExecuteRequest;
import com.ccagent.api.model.response.StreamChunk;
import com.ccagent.api.port.*;
import com.ccagent.core.context.ArtifactStore;
import com.ccagent.core.context.TaskContextImpl;
import com.ccagent.core.orchestration.agent.ChatAgent;
import com.ccagent.core.orchestration.agent.ChatResult;
import com.ccagent.core.orchestration.context.ExecutionContext;
import com.ccagent.core.orchestration.dispatcher.Dispatcher;
import com.ccagent.core.orchestration.repository.ExecutionPlanRepository;
import com.ccagent.core.orchestration.repository.model.PlanRecord;
import com.ccagent.core.sync.LocalDataRepository;
import com.ccagent.exception.base.AgentException;
import com.ccagent.exception.base.ErrorCode;
import com.ccagent.exception.tool.ToolException;
import com.ccagent.exception.workspace.WorkspaceException;
import com.ccagent.infrastructure.util.JsonUtil;

import java.util.List;
import java.util.Set;
import java.util.HashSet;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.locks.ReentrantLock;
import java.util.concurrent.atomic.AtomicBoolean;

public class SimpleOrchestrator {

    private final ChatAgent chatAgent;
    private final Dispatcher dispatcher;
    private final MemoryPort memoryPort;
    private final ExpertRegistryPort expertRegistry;
    private final ExecutionPlanRepository executionPlanRepository;
    private final LocalDataRepository localDataRepository;
    private final ArtifactStore artifactStore;

    private final ConcurrentHashMap<String, ReentrantLock> sessionLocks = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, CompletableFuture<Boolean>> approvalFutures = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, AtomicBoolean> cancelFlags = new ConcurrentHashMap<>();

    public SimpleOrchestrator(ChatAgent chatAgent, Dispatcher dispatcher,
                               MemoryPort memoryPort,
                               ExpertRegistryPort expertRegistry,
                               ExecutionPlanRepository executionPlanRepository) {
        this(chatAgent, dispatcher, memoryPort, expertRegistry, executionPlanRepository, null, null);
    }

    public SimpleOrchestrator(ChatAgent chatAgent, Dispatcher dispatcher,
                               MemoryPort memoryPort,
                               ExpertRegistryPort expertRegistry,
                               ExecutionPlanRepository executionPlanRepository,
                               LocalDataRepository localDataRepository) {
        this(chatAgent, dispatcher, memoryPort, expertRegistry, executionPlanRepository, localDataRepository, null);
    }

    public SimpleOrchestrator(ChatAgent chatAgent, Dispatcher dispatcher,
                               MemoryPort memoryPort,
                               ExpertRegistryPort expertRegistry,
                               ExecutionPlanRepository executionPlanRepository,
                               LocalDataRepository localDataRepository,
                               ArtifactStore artifactStore) {
        this.chatAgent = chatAgent;
        this.dispatcher = dispatcher;
        this.memoryPort = memoryPort;
        this.expertRegistry = expertRegistry;
        this.executionPlanRepository = executionPlanRepository;
        this.localDataRepository = localDataRepository;
        this.artifactStore = artifactStore;
    }

    public void createSession(String sessionId, String workspaceRoot) {
        memoryPort.createSession(sessionId, sessionId, workspaceRoot);
    }

    public String loadWorkspaceRoot(String sessionId) {
        return memoryPort.loadWorkspaceRoot(sessionId);
    }

    public List<SessionInfo> listSessions() {
        return memoryPort.listSessions();
    }

    public void execute(ExecuteRequest request, String seqId, StreamObserver<StreamChunk> observer) {
        String sessionId = request.getSessionId();
        ReentrantLock lock = sessionLocks.computeIfAbsent(sessionId, k -> new ReentrantLock());

        try {
            if (!lock.tryLock(10, TimeUnit.SECONDS)) {
                throw new ToolException(ErrorCode.TOOL_EXECUTION_FAILED, "获取会话锁超时: " + sessionId);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ToolException(ErrorCode.TOOL_EXECUTION_FAILED, "获取会话锁被中断: " + sessionId, e);
        }

        try {
            AtomicBoolean cancelled = new AtomicBoolean(false);
            cancelFlags.put(seqId, cancelled);

            if (request.getWorkspaceId() == null || request.getWorkspaceId().isBlank()) {
                throw new WorkspaceException(ErrorCode.WORKSPACE_NOT_FOUND, "workspaceId 不能为空");
            }
            if (sessionId == null || sessionId.isBlank()) {
                throw new WorkspaceException(ErrorCode.SESSION_NOT_FOUND, "sessionId 不能为空");
            }

            memoryPort.loadStructuredContext(sessionId);

            String workspaceRoot = memoryPort.loadWorkspaceRoot(sessionId);

            ExecutionContext ctx = new ExecutionContext(
                    ExecutionContext.ExecutionStage.PLANNING, sessionId,
                    workspaceRoot, seqId);

            if (cancelled.get()) { observer.onComplete(); return; }

            // 第一步：ChatAgent 决策
            ChatResult result = chatAgent.chat(sessionId, request.getWorkspaceId(),
                    request.getPrompt(), request.getHistory());

            // 第二步：根据决策结果分支
            if (result.isReply()) {
                // 简单对话，直接返回
                String reply = result.getReplyContent();
                String msg = JsonUtil.toJson(java.util.Map.of("success", true, "message", reply));
                observer.onNext(new StreamChunk(seqId, "EXECUTION_RESULT", msg, null));
                saveConversationTurns(sessionId, request.getPrompt(), reply);
                observer.onComplete();
                return;
            }

            // 第三步：复杂任务，找 PlannerExpert 生成 Plan
            String taskDescription = result.getTaskDescription();
            Expert plannerExpert;
            try {
                plannerExpert = expertRegistry.getExpert("planner");
            } catch (Exception e) {
                throw new ToolException(ErrorCode.TOOL_EXECUTION_FAILED, "找不到规划专家（planner）", e);
            }

            String planJson = plannerExpert.execute(taskDescription, null, java.util.Map.of(),
                    workspaceRoot, observer);

            Plan plan;
            try {
                plan = JsonUtil.fromJson(planJson, Plan.class);
                if (plan == null) {
                    throw new ToolException(ErrorCode.TOOL_EXECUTION_FAILED, "规划专家返回了空的 Plan");
                }
            } catch (Exception e) {
                throw new ToolException(ErrorCode.TOOL_EXECUTION_FAILED, "解析 Plan JSON 失败: " + e.getMessage());
            }

            if (plan.getSteps() == null || plan.getSteps().isEmpty()) {
                String summary = plan.getSummary() != null ? plan.getSummary() : "";
                String msg = JsonUtil.toJson(java.util.Map.of("success", true, "message", summary));
                observer.onNext(new StreamChunk(seqId, "EXECUTION_RESULT", msg, null));
                saveConversationTurns(sessionId, request.getPrompt(), summary);
                observer.onComplete();
                return;
            }

            // 第四步：执行 Plan
            String userPrompt = request.getPrompt();

            if (request.isAutoMode()) {
                executePlanInternal(plan, seqId, ctx, cancelled, observer, userPrompt);
            } else {
                StreamChunk planReady = new StreamChunk(seqId, "PLAN_READY",
                        "{\"summary\":\"" + plan.getSummary().replace("\"", "'")
                                + "\",\"steps\":" + plan.getSteps().size() + "}", null);
                observer.onNext(planReady);

                CompletableFuture<Boolean> future = new CompletableFuture<>();
                approvalFutures.put(seqId, future);

                try {
                    boolean confirmed = future.get(120, TimeUnit.SECONDS);
                    if (confirmed && !cancelled.get()) {
                        ctx.setStage(ExecutionContext.ExecutionStage.EXECUTING);
                        executePlanInternal(plan, seqId, ctx, cancelled, observer, userPrompt);
                    } else {
                        observer.onNext(new StreamChunk(seqId, "PLAN_CANCELLED",
                                "{\"message\":\"Plan 已被取消\"}", null));
                        observer.onComplete();
                    }
                } catch (TimeoutException e) {
                    throw new com.ccagent.exception.approval.ApprovalException(
                            ErrorCode.APPROVAL_TIMEOUT, "审批超时", 120, seqId);
                } catch (ExecutionException e) {
                    throw new ToolException(ErrorCode.TOOL_EXECUTION_FAILED, "审批等待异常", e.getCause());
                } finally {
                    approvalFutures.remove(seqId);
                }
            }

        } catch (AgentException e) {
            observer.onNext(new StreamChunk(seqId, "ERROR",
                    "{\"errorCode\":\"" + e.getErrorCode().name()
                            + "\",\"message\":\"" + e.getMessage().replace("\"", "'") + "\"}", null));
            observer.onError(e);
        } catch (Exception e) {
            observer.onError(e);
        } finally {
            cancelFlags.remove(seqId);
            lock.unlock();
        }
    }

    private void executePlanInternal(Plan plan, String seqId, ExecutionContext ctx,
                                      AtomicBoolean cancelled, StreamObserver<StreamChunk> observer,
                                      String userPrompt) {
        if (cancelled.get()) { observer.onComplete(); return; }

        int planVersion = executionPlanRepository.createPlan(ctx.getSessionId(), plan);
        executionPlanRepository.updatePlanStatus(ctx.getSessionId(), planVersion, "EXECUTING");

        String recoveryKey = ctx.getSessionId() + "_" + planVersion;
        TaskContextImpl task = new TaskContextImpl(
                recoveryKey, plan, artifactStore, ctx.getWorkspaceRoot());
        try {
            dispatcher.execute(plan, seqId, ctx.getSessionId(), planVersion,
                    ctx.getWorkspaceRoot(), cancelled, observer, task, Set.of());
        } finally {
            task.close();
        }
        saveConversationTurns(ctx.getSessionId(), userPrompt, plan.getSummary());

        if (localDataRepository != null) {
            try {
                localDataRepository.saveConversation(
                        seqId, ctx.getSessionId(), ctx.getSessionId(),
                        userPrompt, plan.getSummary() != null ? plan.getSummary() : "",
                        JsonUtil.toJson(plan.getSteps()), null, null,
                        "SUCCESS", 0, 0, 0.0, 0.0
                );
            } catch (Exception ignored) {}
        }
    }

    private void saveConversationTurns(String sessionId, String userPrompt, String summary) {
        try {
            memoryPort.saveTurn(sessionId, new ConversationTurn("user", userPrompt, 0));
            if (summary != null && !summary.isEmpty()) {
                memoryPort.saveTurn(sessionId, new ConversationTurn("assistant", summary, 0));
            }
        } catch (Exception ignored) {}
    }

    /**
     * 启动时恢复被中断的执行计划
     * <p>查找所有状态为 RUNNING 的 Plan，从磁盘恢复已持久化的黑板产物，
     * 提取已完成的步骤 ID 作为 skipIds，重新调度未完成的步骤。</p>
     *
     * @return 恢复的 Plan 数量
     */
    public int recoverInterruptedPlans() {
        List<PlanRecord> runningPlans;
        try {
            runningPlans = executionPlanRepository.findByStatus("RUNNING");
        } catch (Exception e) {
            return 0;
        }
        if (runningPlans == null || runningPlans.isEmpty()) return 0;

        int recovered = 0;
        for (PlanRecord record : runningPlans) {
            if (recoverSinglePlan(record)) {
                recovered++;
            }
        }
        return recovered;
    }

    private boolean recoverSinglePlan(PlanRecord record) {
        try {
            Plan plan = record.getPlan();
            if (plan == null || plan.getSteps() == null || plan.getSteps().isEmpty()) {
                executionPlanRepository.updatePlanStatus(
                        record.getSessionId(), record.getVersion(), "FAILED");
                return false;
            }

            String sessionId = record.getSessionId();
            int planVersion = record.getVersion();
            String workspaceRoot = memoryPort.loadWorkspaceRoot(sessionId);

            Set<Integer> skipIds = parseCompletedStepIds(record.getCompletedStepIds());

            String recoveryKey = sessionId + "_" + planVersion;
            TaskContextImpl task = new TaskContextImpl(
                    recoveryKey, plan, artifactStore, workspaceRoot);
            task.restoreFromDisk(recoveryKey);

            if (skipIds.size() >= plan.getSteps().size()) {
                executionPlanRepository.updatePlanStatus(sessionId, planVersion, "COMPLETED");
                task.close();
                return true;
            }

            AtomicBoolean cancelled = new AtomicBoolean(false);
            StreamObserver<StreamChunk> noopObserver = new StreamObserver<>() {
                @Override public void onNext(StreamChunk chunk) {}
                @Override public void onError(Throwable error) {}
                @Override public void onComplete() {}
            };

            try {
                dispatcher.execute(plan, recoveryKey, sessionId, planVersion,
                        workspaceRoot, cancelled, noopObserver, task, skipIds);
            } finally {
                task.close();
            }
            return true;
        } catch (Exception e) {
            try {
                executionPlanRepository.updatePlanStatus(
                        record.getSessionId(), record.getVersion(), "FAILED");
            } catch (Exception ignored) {}
            return false;
        }
    }

    private Set<Integer> parseCompletedStepIds(String json) {
        if (json == null || json.isBlank()) return new HashSet<>();
        try {
            Integer[] ids = JsonUtil.fromJson(json, Integer[].class);
            return new HashSet<>(List.of(ids));
        } catch (Exception e) {
            return new HashSet<>();
        }
    }

    public void confirmPlan(String seqId, boolean confirmed) {
        CompletableFuture<Boolean> future = approvalFutures.get(seqId);
        if (future != null) future.complete(confirmed);
    }

    public boolean cancelExecution(String seqId) {
        AtomicBoolean flag = cancelFlags.get(seqId);
        if (flag != null) { flag.set(true); return true; }
        CompletableFuture<Boolean> future = approvalFutures.get(seqId);
        if (future != null) { future.complete(false); return true; }
        return false;
    }
}
