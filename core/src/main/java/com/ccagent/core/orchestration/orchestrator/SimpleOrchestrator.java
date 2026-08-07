package com.ccagent.core.orchestration.orchestrator;

import com.ccagent.api.context.TaskScope;
import com.ccagent.api.context.BlackboardSchema;
import com.ccagent.api.model.memory.ConversationTurn;
import com.ccagent.api.model.memory.SessionInfo;
import com.ccagent.api.model.plan.Plan;
import com.ccagent.api.model.plan.Step;
import com.ccagent.api.model.request.ExecuteRequest;
import com.ccagent.api.model.response.StreamChunk;
import com.ccagent.api.port.*;
import com.ccagent.core.context.ArtifactStore;
import com.ccagent.core.context.TaskContextImpl;
import com.ccagent.core.context.YamlBlackboardSchema;
import com.ccagent.core.event.LlmCallCompletedEvent;
import com.ccagent.core.event.PlanExecutionStartedEvent;
import com.ccagent.core.governance.context.ExecutionContextHolder;
import com.ccagent.core.orchestration.agent.ChatAgent;
import com.ccagent.core.orchestration.agent.ChatResult;
import com.ccagent.core.orchestration.context.ExecutionContext;
import com.ccagent.core.orchestration.dispatcher.Dispatcher;
import com.ccagent.core.orchestration.repository.ExecutionPlanRepository;
import com.ccagent.core.orchestration.repository.model.PlanRecord;
import com.ccagent.core.orchestration.state.SessionStateMachine;
import com.ccagent.core.skill.SkillRegistry;
import com.ccagent.core.sync.LocalDataRepository;
import com.ccagent.exception.base.AgentException;
import com.ccagent.kernel.event.EventBus;
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
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Logger;

public class SimpleOrchestrator {

    private static final Logger LOG = Logger.getLogger(SimpleOrchestrator.class.getName());

    private final ChatAgent chatAgent;
    private final Dispatcher dispatcher;
    private final MemoryPort memoryPort;
    private final ExpertRegistryPort expertRegistry;
    private final ExecutionPlanRepository executionPlanRepository;
    private final LocalDataRepository localDataRepository;
    private final ArtifactStore artifactStore;
    private final EventBus eventBus;
    /** 技能注册表（可空：不启用 skill.md 注入） */
    private final SkillRegistry skillRegistry;
    /** 黑板 schema (懒加载, 从 classpath 读取 blackboard-schema.yaml) */
    private volatile BlackboardSchema blackboardSchema;

    /** 会话状态机：锁只护状态转移（≤1ms），串行性由状态机裁决（D1/D2） */
    private final SessionStateMachine stateMachine;
    /** seqId → [totalTokens, cachedTokens]，由 LlmCallCompletedEvent 同步累积 */
    private final ConcurrentHashMap<String, long[]> seqTokens = new ConcurrentHashMap<>();

    public SimpleOrchestrator(ChatAgent chatAgent, Dispatcher dispatcher,
                               MemoryPort memoryPort,
                               ExpertRegistryPort expertRegistry,
                               ExecutionPlanRepository executionPlanRepository) {
        this(chatAgent, dispatcher, memoryPort, expertRegistry, executionPlanRepository, null, null, null);
    }

    public SimpleOrchestrator(ChatAgent chatAgent, Dispatcher dispatcher,
                               MemoryPort memoryPort,
                               ExpertRegistryPort expertRegistry,
                               ExecutionPlanRepository executionPlanRepository,
                               LocalDataRepository localDataRepository) {
        this(chatAgent, dispatcher, memoryPort, expertRegistry, executionPlanRepository, localDataRepository, null, null);
    }

    public SimpleOrchestrator(ChatAgent chatAgent, Dispatcher dispatcher,
                               MemoryPort memoryPort,
                               ExpertRegistryPort expertRegistry,
                               ExecutionPlanRepository executionPlanRepository,
                               LocalDataRepository localDataRepository,
                               ArtifactStore artifactStore) {
        this(chatAgent, dispatcher, memoryPort, expertRegistry, executionPlanRepository, localDataRepository, artifactStore, null);
    }

    public SimpleOrchestrator(ChatAgent chatAgent, Dispatcher dispatcher,
                               MemoryPort memoryPort,
                               ExpertRegistryPort expertRegistry,
                               ExecutionPlanRepository executionPlanRepository,
                               LocalDataRepository localDataRepository,
                               ArtifactStore artifactStore,
                               EventBus eventBus) {
        this(chatAgent, dispatcher, memoryPort, expertRegistry, executionPlanRepository,
                localDataRepository, artifactStore, eventBus, null);
    }

    public SimpleOrchestrator(ChatAgent chatAgent, Dispatcher dispatcher,
                               MemoryPort memoryPort,
                               ExpertRegistryPort expertRegistry,
                               ExecutionPlanRepository executionPlanRepository,
                               LocalDataRepository localDataRepository,
                               ArtifactStore artifactStore,
                               EventBus eventBus,
                               SkillRegistry skillRegistry) {
        this.chatAgent = chatAgent;
        this.dispatcher = dispatcher;
        this.memoryPort = memoryPort;
        this.expertRegistry = expertRegistry;
        this.executionPlanRepository = executionPlanRepository;
        this.localDataRepository = localDataRepository;
        this.artifactStore = artifactStore;
        this.eventBus = eventBus;
        this.skillRegistry = skillRegistry;
        this.stateMachine = new SessionStateMachine(eventBus);
        // 订阅 LLM 调用完成事件，按 seqId 累积 token（同步订阅，保证保存对话时数据已就绪）
        if (eventBus != null) {
            eventBus.subscribe(LlmCallCompletedEvent.class, event -> {
                String sid = event.getSeqId();
                if (sid != null && !sid.isEmpty()) {
                    seqTokens.computeIfAbsent(sid, k -> new long[2]);
                    seqTokens.get(sid)[0] += (long) event.getInputTokens() + event.getOutputTokens();
                    seqTokens.get(sid)[1] += event.getCachedInputTokens();
                }
            });
        }
    }

    /**
     * 懒加载黑板 schema (从 classpath 读取 blackboard-schema.yaml)
     */
    private BlackboardSchema getBlackboardSchema() {
        if (blackboardSchema == null) {
            synchronized (this) {
                if (blackboardSchema == null) {
                    blackboardSchema = YamlBlackboardSchema.loadFromClasspath("blackboard-schema.yaml");
                }
            }
        }
        return blackboardSchema;
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
        if (sessionId == null || sessionId.isBlank()) {
            throw new WorkspaceException(ErrorCode.SESSION_NOT_FOUND, "sessionId 不能为空");
        }

        // 会话准入：瞬时状态转移（锁内极短，≤1ms），忙则拒绝（D1 → SESSION_BUSY）
        if (!stateMachine.tryBegin(sessionId, seqId)) {
            throw new ToolException(ErrorCode.SESSION_BUSY, "会话忙，已有任务在执行: " + sessionId);
        }
        AtomicBoolean cancelled = stateMachine.getCancelFlag(sessionId);

        try {
            if (request.getWorkspaceId() == null || request.getWorkspaceId().isBlank()) {
                throw new WorkspaceException(ErrorCode.WORKSPACE_NOT_FOUND, "workspaceId 不能为空");
            }

            memoryPort.loadStructuredContext(sessionId);

            String workspaceRoot = memoryPort.loadWorkspaceRoot(sessionId);

            ExecutionContext ctx = new ExecutionContext(
                    ExecutionContext.ExecutionStage.PLANNING, sessionId,
                    workspaceRoot, seqId);

            // 会话级 skills：解析为 prompt 注入执行上下文（模式 A），专家经 BaseExpert 合并进 system prompt
            if (skillRegistry != null) {
                ctx.setSkillsPrompt(skillRegistry.resolvePrompt(request.getSkills()));
            }
            ExecutionContextHolder.setContext(ctx);

            if (cancelled.get()) { observer.onComplete(); return; }

            // 第一步：ChatAgent 决策
            LOG.info("[ORCH] 第一步 ChatAgent 决策开始: seqId=" + seqId + ", prompt=" + request.getPrompt());
            ChatResult result = chatAgent.chat(sessionId, request.getWorkspaceId(),
                    request.getPrompt(), request.getHistory());
            LOG.info("[ORCH] 第一步 ChatAgent 决策完成: seqId=" + seqId + ", isReply=" + result.isReply());

            // 第二步：根据决策结果分支
            if (result.isReply()) {
                // 简单对话，直接返回
                String reply = result.getReplyContent();
                String msg = buildResultMsg(seqId, sessionId, java.util.Map.of("success", true, "message", reply));
                observer.onNext(new StreamChunk(seqId, "EXECUTION_RESULT", msg, null));
                saveConversationTurns(sessionId, request.getPrompt(), reply);
                observer.onComplete();
                return;
            }

            // 第三步：复杂任务，找 PlannerExpert 生成 Plan
            String taskDescription = result.getTaskDescription();
            LOG.info("[ORCH] 第三步 PlannerExpert 生成 Plan: seqId=" + seqId + ", taskDesc=" + taskDescription);
            Expert plannerExpert;
            try {
                plannerExpert = expertRegistry.getExpert("planner");
            } catch (Exception e) {
                throw new ToolException(ErrorCode.TOOL_EXECUTION_FAILED, "找不到规划专家（planner）", e);
            }

            java.util.Map<String, Object> plannerOutput = plannerExpert.execute(taskDescription, null, java.util.Map.of(),
                    workspaceRoot, observer);
            String planJson = plannerOutput != null && plannerOutput.containsKey("result")
                    ? String.valueOf(plannerOutput.get("result")) : null;
            LOG.info("[ORCH] PlannerExpert 返回 planJson: seqId=" + seqId + ", planJson=" + planJson);

            Plan plan;
            try {
                plan = JsonUtil.fromJson(planJson, Plan.class);
                if (plan == null) {
                    throw new ToolException(ErrorCode.TOOL_EXECUTION_FAILED, "规划专家返回了空的 Plan");
                }
            } catch (Exception e) {
                throw new ToolException(ErrorCode.TOOL_EXECUTION_FAILED, "解析 Plan JSON 失败: " + e.getMessage());
            }
            LOG.info("[ORCH] Plan 解析成功: seqId=" + seqId + ", steps=" + (plan.getSteps() != null ? plan.getSteps().size() : 0));

            if (plan.getSteps() == null || plan.getSteps().isEmpty()) {
                String summary = plan.getSummary() != null ? plan.getSummary() : "";
                String msg = buildResultMsg(seqId, sessionId, java.util.Map.of("success", true, "message", summary));
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
                // Plan 就绪，发送 PLAN_READY 给前端，并附带详细 steps 数组
                String stepsJson = JsonUtil.toJson(plan.getSteps());
                StreamChunk planReady = new StreamChunk(seqId, "PLAN_READY",
                        "{\"summary\":\"" + plan.getSummary().replace("\"", "'")
                                + "\",\"steps\":" + stepsJson
                                + ",\"stepCount\":" + plan.getSteps().size() + "}", null);
                observer.onNext(planReady);
                LOG.info("[ORCH] PLAN_READY 已发送，等待用户确认: seqId=" + seqId);

                CompletableFuture<Boolean> future = new CompletableFuture<>();
                stateMachine.markApproval(sessionId, seqId, future);

                try {
                    // 锁外等待：虚拟线程挂起，不占任何会话锁（四象限 ③ 人工等待）
                    boolean confirmed = future.get(120, TimeUnit.SECONDS);
                    LOG.info("[ORCH] 用户确认结果: seqId=" + seqId + ", confirmed=" + confirmed);
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
                    stateMachine.clearApproval(sessionId, seqId);
                }
            }

        } catch (AgentException e) {
            LOG.warning("[ORCH] AgentException: seqId=" + seqId + ", code=" + e.getErrorCode() + ", msg=" + e.getMessage());
            stateMachine.markError(sessionId, seqId);
            observer.onNext(new StreamChunk(seqId, "ERROR",
                    "{\"errorCode\":\"" + e.getErrorCode().name()
                            + "\",\"message\":\"" + e.getMessage().replace("\"", "'") + "\"}", null));
            observer.onError(e);
        } catch (Exception e) {
            LOG.warning("[ORCH] 未预期异常: seqId=" + seqId + ", msg=" + e.getMessage() + ", cause=" + e.getCause());
            stateMachine.markError(sessionId, seqId);
            observer.onError(e);
        } finally {
            stateMachine.finish(sessionId, seqId);
        }
    }

    private void executePlanInternal(Plan plan, String seqId, ExecutionContext ctx,
                                      AtomicBoolean cancelled, StreamObserver<StreamChunk> observer,
                                      String userPrompt) {
        if (cancelled.get()) { observer.onComplete(); return; }

        int planVersion = executionPlanRepository.createPlan(ctx.getSessionId(), plan);
        executionPlanRepository.updatePlanStatus(ctx.getSessionId(), planVersion, "RUNNING");

        String recoveryKey = ctx.getSessionId() + "_" + planVersion;
        TaskContextImpl task = new TaskContextImpl(
                recoveryKey, plan, artifactStore, ctx.getWorkspaceRoot(), getBlackboardSchema());

        // 包装 observer：在 dispatcher 触发 onComplete 前，先发送带 token 统计的 EXECUTION_RESULT
        // （Dispatcher 内部会调用 observer.onComplete()，其后 onNext 不再转发，因此必须在此包装）
        StreamObserver<StreamChunk> resultObserver = new StreamObserver<>() {
            private boolean finished = false;

            @Override
            public void onNext(StreamChunk chunk) {
                if (!finished) observer.onNext(chunk);
            }

            @Override
            public void onError(Throwable error) {
                if (!finished) {
                    finished = true;
                    observer.onError(error);
                }
            }

            @Override
            public void onComplete() {
                if (finished) return;
                finished = true;
                String summary = plan.getSummary() != null ? plan.getSummary() : "";
                observer.onNext(new StreamChunk(seqId, "EXECUTION_RESULT",
                        buildResultMsg(seqId, ctx.getSessionId(),
                                java.util.Map.of("success", true, "message", summary)), null));
                observer.onComplete();
            }
        };

        try {
            // 执行前一次性发布：治理层（回滚备份等）在此完成整批准备工作，避免每个步骤事件重复 IO
            if (eventBus != null) {
                eventBus.publish(new PlanExecutionStartedEvent(seqId, ctx.getSessionId(), plan, ctx.getWorkspaceRoot()));
            }
            dispatcher.execute(plan, seqId, ctx.getSessionId(), planVersion,
                    ctx.getWorkspaceRoot(), cancelled, resultObserver, task, Set.of());
        } finally {
            task.close();
        }
        saveConversationTurns(ctx.getSessionId(), userPrompt, plan.getSummary());

        if (localDataRepository != null) {
            try {
                long[] tokens = seqTokens.remove(seqId);
                long totalTokens = tokens != null ? tokens[0] : 0;
                long cachedTokens = tokens != null ? tokens[1] : 0;
                double cacheHitRate = totalTokens > 0 ? (double) cachedTokens / totalTokens : 0.0;
                localDataRepository.saveConversation(
                        seqId, ctx.getSessionId(), ctx.getSessionId(),
                        userPrompt, plan.getSummary() != null ? plan.getSummary() : "",
                        JsonUtil.toJson(plan.getSteps()), null, null,
                        "SUCCESS", (int) totalTokens, (int) cachedTokens, cacheHitRate, 0.0
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
     * 构造 EXECUTION_RESULT 结果消息，附带本次 seqId 的真实 token 统计
     * （tokensUsed/cachedTokens/cacheHitRate/estimatedCost，供云端 parseFromEnvelope 落库）
     */
    private String buildResultMsg(String seqId, String sessionId, java.util.Map<String, Object> base) {
        long[] tokens = seqTokens.get(seqId);
        long totalTokens = tokens != null ? tokens[0] : 0;
        long cachedTokens = tokens != null ? tokens[1] : 0;
        double cacheHitRate = totalTokens > 0 ? (double) cachedTokens / totalTokens : 0.0;
        java.util.Map<String, Object> msg = new java.util.HashMap<>(base);
        msg.put("sessionId", sessionId != null ? sessionId : "");
        msg.put("tokensUsed", totalTokens);
        msg.put("cachedTokens", cachedTokens);
        msg.put("cacheHitRate", cacheHitRate);
        msg.put("estimatedCost", 0.0);
        return JsonUtil.toJson(msg);
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
                    recoveryKey, plan, artifactStore, workspaceRoot, getBlackboardSchema());
            task.restoreFromDisk(recoveryKey);

            // 缺失产物处理：用户可能手动删除了黑板产物文件。
            // 对"已跳过但产物丢失"的生产者步骤，强制从 skipIds 移除以重跑重新生成，
            // 避免下游步骤 getArtifact().join() 永久挂起。
            handleMissingArtifacts(plan, skipIds, task);

            if (skipIds.size() >= plan.getSteps().size()) {
                executionPlanRepository.updatePlanStatus(sessionId, planVersion, "COMPLETED");
                task.close();
                return true;
            }

            // 恢复期间置会话忙，避免并发 EXECUTE 干扰恢复（会话状态机兜底）
            if (!stateMachine.tryBegin(sessionId, recoveryKey)) {
                LOG.warning("恢复失败：会话忙，跳过恢复 sessionId=" + sessionId);
                task.close();
                return false;
            }
            AtomicBoolean cancelled = stateMachine.getCancelFlag(sessionId);
            StreamObserver<StreamChunk> noopObserver = new StreamObserver<>() {
                @Override public void onNext(StreamChunk chunk) {}
                @Override public void onError(Throwable error) {}
                @Override public void onComplete() {}
            };

            try {
                // 恢复执行同样先触发整批备份，保证未完成步骤的回滚能力
                if (eventBus != null) {
                    eventBus.publish(new PlanExecutionStartedEvent(recoveryKey, sessionId, plan, workspaceRoot));
                }
                dispatcher.execute(plan, recoveryKey, sessionId, planVersion,
                        workspaceRoot, cancelled, noopObserver, task, skipIds);
            } finally {
                task.close();
                stateMachine.finish(sessionId, recoveryKey);
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

    /**
     * 处理恢复时检测到的缺失黑板产物。
     * <p>对缺失 Key，若其生产者步骤已被跳过（H2 标记完成）则强制移出 skipIds 重跑，
     * 使产物重新生成，避免下游 join 永久挂起；若生产者未完成会自然重跑，仅记录日志。</p>
     */
    private void handleMissingArtifacts(Plan plan, Set<Integer> skipIds, TaskContextImpl task) {
        Set<String> missingKeys = task.getMissingKeys();
        if (missingKeys.isEmpty()) return;

        for (String key : missingKeys) {
            Step producer = findProducerStep(plan, key);
            if (producer == null) {
                LOG.warning("恢复发现缺失产物但找不到生产者步骤: seqId=" + task.getSeqId() + ", key=" + key);
                continue;
            }
            if (!skipIds.contains(producer.getId())) {
                LOG.warning("恢复发现缺失产物，生产者步骤将重跑: key=" + key + ", step=" + producer.getId());
                continue;
            }
            LOG.warning("恢复发现缺失产物，强制重跑已完成的生产者步骤: key=" + key + ", step=" + producer.getId());
            skipIds.remove(producer.getId());
        }
    }

    /**
     * 在计划步骤中查找产出指定黑板 Key 的步骤（writes 包含该 Key）。
     */
    private Step findProducerStep(Plan plan, String key) {
        if (plan.getSteps() == null) return null;
        for (Step step : plan.getSteps()) {
            Set<String> writes = step.getWrites();
            if (writes != null && writes.contains(key)) return step;
        }
        return null;
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
        stateMachine.confirmApproval(seqId, confirmed);
    }

    public boolean cancelExecution(String seqId) {
        return stateMachine.requestCancel(seqId);
    }
}
