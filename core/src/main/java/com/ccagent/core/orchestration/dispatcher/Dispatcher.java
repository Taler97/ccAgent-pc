package com.ccagent.core.orchestration.dispatcher;

import com.ccagent.api.context.TaskScope;
import com.ccagent.api.model.plan.Plan;
import com.ccagent.api.model.plan.Step;
import com.ccagent.api.model.response.StreamChunk;
import com.ccagent.api.port.Expert;
import com.ccagent.api.port.ExpertRegistryPort;
import com.ccagent.api.port.StreamObserver;
import com.ccagent.core.context.ArtifactStore;
import com.ccagent.core.context.TaskContextImpl;
import com.ccagent.core.event.StepCompletedEvent;
import com.ccagent.core.event.StepFailedEvent;
import com.ccagent.core.event.StepRolledBackEvent;
import com.ccagent.core.event.StepStartedEvent;
import com.ccagent.core.governance.context.ExecutionContextHolder;
import com.ccagent.core.orchestration.context.ExecutionContext;
import com.ccagent.core.orchestration.repository.ExecutionPlanRepository;
import com.ccagent.kernel.event.EventBus;
import com.ccagent.exception.base.ErrorCode;
import com.ccagent.exception.plan.PlanCycleException;
import com.ccagent.exception.tool.ToolException;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;

/**
 * 调度器
 * <p>将 Plan 步骤落地执行。被编排器调用，不对外暴露端口。</p>
 *
 * <h3>执行策略</h3>
 * <ul>
 *   <li>基于 affectedFiles + reads/writes 构建 DAG，无依赖的步骤通过虚拟线程并行执行</li>
 *   <li>同批次内任一步骤失败 → 取消该批次其余步骤，回滚失败步骤，中止后续批次</li>
 *   <li>每个步骤独立备份，失败时仅回滚该步骤的修改</li>
 *   <li>支持黑板：专家通过 TaskScope 消费/生产中间产物，基于 CompletableFuture 同步</li>
 *   <li>支持断点续存：skipIds 跳过已完成步骤，restoreFromDisk 恢复持久化产物</li>
 * </ul>
 */
public class Dispatcher {

    private final ExpertRegistryPort expertRegistryPort;
    private final ExecutionPlanRepository executionPlanRepository;
    private final ArtifactStore artifactStore;
    private final EventBus eventBus;

    public Dispatcher(ExpertRegistryPort expertRegistryPort,
                      ExecutionPlanRepository executionPlanRepository,
                      EventBus eventBus) {
        this(expertRegistryPort, executionPlanRepository, null, eventBus);
    }

    public Dispatcher(ExpertRegistryPort expertRegistryPort,
                      ExecutionPlanRepository executionPlanRepository,
                      ArtifactStore artifactStore,
                      EventBus eventBus) {
        this.expertRegistryPort = expertRegistryPort;
        this.executionPlanRepository = executionPlanRepository;
        this.artifactStore = artifactStore;
        this.eventBus = eventBus;
    }

    /**
     * 按 DAG 调度执行 Plan 的所有步骤（旧接口，不支持黑板）
     */
    @Deprecated
    public void execute(Plan plan, String seqId, String sessionId, int planVersion,
                        String workspaceRoot, AtomicBoolean cancelled,
                        StreamObserver<StreamChunk> observer) {
        TaskScope task = new TaskContextImpl(seqId, plan, artifactStore, workspaceRoot);
        try {
            execute(plan, seqId, sessionId, planVersion, workspaceRoot,
                    cancelled, observer, task, Set.of());
        } finally {
            task.close();
        }
    }

    /**
     * 按 DAG 调度执行 Plan 的所有步骤（黑板模式）
     *
     * @param plan            执行计划
     * @param seqId           消息序列号
     * @param sessionId       会话标识
     * @param planVersion     Plan 版本号（持久化用，-1 表示不持久化）
     * @param workspaceRoot   工作区根路径
     * @param cancelled       取消信号
     * @param observer        结果回调
     * @param task            任务级黑板上下文
     * @param skipIds         需跳过的已完成步骤 ID（断点续存场景）
     */
    public void execute(Plan plan, String seqId, String sessionId, int planVersion,
                        String workspaceRoot, AtomicBoolean cancelled,
                        StreamObserver<StreamChunk> observer,
                        TaskScope task, Set<Integer> skipIds) {
        List<Step> steps = plan.getSteps();
        int totalSteps = steps.size();

        // 循环依赖检测
        if (hasCyclicDependency(steps)) {
            throw new PlanCycleException("检测到循环依赖（文件或黑板 Key 级别）");
        }

        // 构建 DAG：stepId → 依赖的 stepId 集合
        Map<Integer, Set<Integer>> dependencyGraph = buildDependencyGraph(steps);

        // 初始化完成/待执行集合
        Set<Integer> completed = new HashSet<>();
        Set<Integer> remaining = steps.stream().map(Step::getId).collect(Collectors.toSet());

        // 断点续存：跳过已完成步骤
        if (skipIds != null && !skipIds.isEmpty()) {
            completed.addAll(skipIds);
            remaining.removeAll(skipIds);
        }

        // 执行上下文快照（供虚拟线程继承）
        ExecutionContext parentCtx = ExecutionContextHolder.copyContext();

        try {
            while (!remaining.isEmpty()) {
                // 找出无未完成依赖的步骤
                Set<Integer> ready = remaining.stream()
                        .filter(id -> {
                            Set<Integer> deps = dependencyGraph.getOrDefault(id, Set.of());
                            return completed.containsAll(deps);
                        })
                        .collect(Collectors.toSet());

                if (ready.isEmpty()) {
                    throw new IllegalStateException("DAG 调度死锁: 剩余步骤依赖无法满足");
                }

                // 该批次执行结果：stepId → true(成功) / false(失败)
                Map<Integer, Boolean> batchResult = new ConcurrentHashMap<>();

                try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
                    for (int stepId : ready) {
                        Step step = steps.stream().filter(s -> s.getId() == stepId).findFirst().orElseThrow();
                        executor.submit(() -> {
                            ExecutionContextHolder.propagate(parentCtx);
                            try {
                                executeSingleStep(step, totalSteps, seqId, sessionId,
                                        planVersion, workspaceRoot, cancelled, observer, task);
                                batchResult.put(stepId, true);
                            } catch (Exception e) {
                                batchResult.put(stepId, false);
                            } finally {
                                ExecutionContextHolder.clear();
                            }
                        });
                    }
                }

                // 检查是否需要清理当前批次
                boolean hasFailure = batchResult.values().stream().anyMatch(r -> !r);

                if (hasFailure) {
                    // 回滚由 RollbackManager 监听 StepFailedEvent 触发
                    // 这里只需要发 ROLLED_BACK SSE 状态（同步等待回滚完成）
                    for (int stepId : ready) {
                        if (!batchResult.get(stepId)) {
                            Step failedStep = steps.stream().filter(s -> s.getId() == stepId).findFirst().orElse(null);
                            StreamChunk stepRolledBack = new StreamChunk(
                                    seqId, "STEP_STATUS",
                                    "{\"stepId\":" + stepId
                                            + ",\"totalSteps\":" + totalSteps
                                            + ",\"status\":\"ROLLED_BACK\"}", null);
                            observer.onNext(stepRolledBack);
                        } else {
                            completed.add(stepId);
                        }
                    }
                    // 取消剩余所有未执行的步骤
                    for (int remainingId : remaining) {
                        if (!completed.contains(remainingId)) {
                            setStepCancelStatus(remainingId, totalSteps, seqId, sessionId, planVersion, observer);
                        }
                    }
                    observer.onError(new ToolException(
                            ErrorCode.TOOL_EXECUTION_FAILED,
                            "步骤执行失败"));
                    return;
                }

                completed.addAll(ready);
                remaining.removeAll(ready);
            }

            // 全部完成
            executionPlanRepository.updatePlanStatus(sessionId, planVersion, "COMPLETED");
            observer.onComplete();

        } catch (Exception e) {
            observer.onError(e);
        }
    }

    private void executeSingleStep(Step step, int totalSteps, String seqId,
                                   String sessionId, int planVersion,
                                   String workspaceRoot, AtomicBoolean cancelled,
                                   StreamObserver<StreamChunk> observer, TaskScope task) {
        if (cancelled.get() || task.isCancelled()) {
            setStepCancelStatus(step.getId(), totalSteps, seqId, sessionId, planVersion, observer);
            return;
        }

        try {
            if (planVersion > 0) {
                executionPlanRepository.updateStepStatus(sessionId, planVersion, step.getId(), "RUNNING");
            }

            StreamChunk stepStart = new StreamChunk(
                    seqId, "STEP_STATUS",
                    "{\"stepId\":" + step.getId() + ",\"totalSteps\":" + totalSteps
                            + ",\"expertName\":\"" + step.getExpertName()
                            + "\",\"status\":\"RUNNING\"}", null);
            observer.onNext(stepStart);

            eventBus.publish(new StepStartedEvent(seqId, sessionId, step.getId(), totalSteps,
                    step.getExpertName(), step.getAffectedFiles()));

            // 等待黑板 reads 就绪（非阻塞挂起虚拟线程）
            Set<String> reads = step.getReads();
            Map<String, Object> inputs = new HashMap<>();
            if (reads != null && !reads.isEmpty()) {
                List<CompletableFuture<?>> deps = reads.stream()
                        .map(task::getArtifact)
                        .collect(Collectors.toList());
                CompletableFuture.allOf(deps.toArray(new CompletableFuture[0])).join();
                for (String key : reads) {
                    inputs.put(key, task.getArtifact(key).join());
                }
            }

            // 调用专家（黑板模式）
            Expert expert = expertRegistryPort.getExpert(step.getExpertName());
            String result = expert.execute(step.getTask(), task, inputs, workspaceRoot, observer);

            // 写入黑板 writes
            Set<String> writes = step.getWrites();
            if (writes != null && !writes.isEmpty() && result != null) {
                for (String key : writes) {
                    if (step.isPersistOutput()) task.markPersistent(key);
                    task.putArtifact(key, result);
                }
            }

            // 状态更新：成功
            if (planVersion > 0) {
                executionPlanRepository.updateStepStatus(
                        sessionId, planVersion, step.getId(), "SUCCESS");
                executionPlanRepository.addCompletedStep(sessionId, planVersion, step.getId());
            }
            StreamChunk stepSuccess = new StreamChunk(
                    seqId, "STEP_STATUS",
                    "{\"stepId\":" + step.getId()
                            + ",\"totalSteps\":" + totalSteps
                            + ",\"expertName\":\"" + step.getExpertName()
                            + "\",\"status\":\"SUCCESS\"}", null);
            observer.onNext(stepSuccess);

            eventBus.publish(new StepCompletedEvent(seqId, sessionId, step.getId(), totalSteps,
                    step.getExpertName(), step.getAffectedFiles()));

        } catch (ToolException e) {
            // 专家不存在
            if (planVersion > 0) {
                executionPlanRepository.updateStepStatus(
                        sessionId, planVersion, step.getId(), "FAILED",
                        "专家不存在: " + step.getExpertName());
            }
            eventBus.publish(new StepFailedEvent(seqId, sessionId, step.getId(), totalSteps,
                    step.getExpertName(), step.getAffectedFiles(), "专家不存在: " + step.getExpertName()));
            StreamChunk stepFailed = new StreamChunk(
                    seqId, "STEP_STATUS",
                    "{\"stepId\":" + step.getId()
                            + ",\"totalSteps\":" + totalSteps
                            + ",\"expertName\":\"" + step.getExpertName()
                            + "\",\"status\":\"FAILED\""
                            + ",\"error\":\"专家不存在: " + step.getExpertName() + "\"}", null);
            observer.onNext(stepFailed);
            throw e;
        } catch (Exception e) {
            // 专家执行失败
            String errMsg = e.getMessage() != null ? e.getMessage() : "未知错误";
            if (planVersion > 0) {
                executionPlanRepository.updateStepStatus(
                        sessionId, planVersion, step.getId(), "FAILED", errMsg);
            }
            eventBus.publish(new StepFailedEvent(seqId, sessionId, step.getId(), totalSteps,
                    step.getExpertName(), step.getAffectedFiles(), errMsg));
            String safeErrMsg = errMsg.replace("\"", "'");
            StreamChunk stepFailed = new StreamChunk(
                    seqId, "STEP_STATUS",
                    "{\"stepId\":" + step.getId()
                            + ",\"totalSteps\":" + totalSteps
                            + ",\"expertName\":\"" + step.getExpertName()
                            + "\",\"status\":\"FAILED\""
                            + ",\"error\":\"" + safeErrMsg + "\"}", null);
            observer.onNext(stepFailed);
            throw new RuntimeException(e);
        }
    }

    private void setStepCancelStatus(int stepId, int totalSteps, String seqId,
                                      String sessionId, int planVersion,
                                      StreamObserver<StreamChunk> observer) {
        if (planVersion > 0) {
            executionPlanRepository.updateStepStatus(sessionId, planVersion, stepId, "CANCELLED");
        }
        StreamChunk status = new StreamChunk(
                seqId, "STEP_STATUS",
                "{\"stepId\":" + stepId
                        + ",\"totalSteps\":" + totalSteps
                        + ",\"status\":\"CANCELLED\"}", null);
        observer.onNext(status);
    }

    /**
     * 构建依赖图（文件依赖 + 黑板 Key 依赖）
     */
    static Map<Integer, Set<Integer>> buildDependencyGraph(List<Step> steps) {
        // 1. 文件依赖
        Map<Integer, Set<Integer>> graph = buildFileDependencyGraph(steps);

        // 2. 黑板 Key 依赖：若 stepB.reads 包含 key，且 stepA.writes 包含 key，则 B 依赖 A
        for (Step b : steps) {
            Set<String> bReads = b.getReads();
            if (bReads == null || bReads.isEmpty()) continue;

            for (String key : bReads) {
                for (Step a : steps) {
                    if (a.getId() == b.getId()) continue;
                    Set<String> aWrites = a.getWrites();
                    if (aWrites != null && aWrites.contains(key)) {
                        graph.computeIfAbsent(b.getId(), k -> new HashSet<>()).add(a.getId());
                    }
                }
            }
        }

        return graph;
    }

    /**
     * 构建文件依赖图（原 buildDependencyGraph 逻辑）
     */
    static Map<Integer, Set<Integer>> buildFileDependencyGraph(List<Step> steps) {
        Map<Integer, Set<String>> stepFiles = new LinkedHashMap<>();
        for (Step step : steps) {
            Set<String> files = step.getAffectedFiles() != null
                    ? new HashSet<>(step.getAffectedFiles())
                    : Set.of();
            stepFiles.put(step.getId(), files);
        }

        Map<Integer, Set<Integer>> deps = new LinkedHashMap<>();
        List<Integer> stepIds = steps.stream().map(Step::getId).toList();

        for (int i = 0; i < stepIds.size(); i++) {
            int currentId = stepIds.get(i);
            Set<String> currentFiles = stepFiles.getOrDefault(currentId, Set.of());
            Set<Integer> dependencies = new HashSet<>();

            for (int j = 0; j < i; j++) {
                int earlierId = stepIds.get(j);
                Set<String> earlierFiles = stepFiles.getOrDefault(earlierId, Set.of());
                for (String file : currentFiles) {
                    if (earlierFiles.contains(file)) {
                        dependencies.add(earlierId);
                        break;
                    }
                }
            }
            if (currentFiles.isEmpty() || currentFiles.stream().allMatch(String::isBlank)) {
                for (int j = 0; j < i; j++) {
                    Set<String> earlierFiles = stepFiles.getOrDefault(stepIds.get(j), Set.of());
                    if (earlierFiles.isEmpty() || earlierFiles.stream().allMatch(String::isBlank)) {
                        dependencies.add(stepIds.get(j));
                    }
                }
            }

            deps.put(currentId, dependencies);
        }

        return deps;
    }

    public static boolean hasCyclicDependency(List<Step> steps) {
        Map<Integer, Set<Integer>> graph = buildDependencyGraph(steps);
        Set<Integer> visited = new HashSet<>();
        Set<Integer> recursionStack = new HashSet<>();

        for (int node : graph.keySet()) {
            if (hasCycle(node, graph, visited, recursionStack)) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasCycle(int node, Map<Integer, Set<Integer>> graph,
                                     Set<Integer> visited, Set<Integer> recursionStack) {
        if (recursionStack.contains(node)) return true;
        if (visited.contains(node)) return false;

        visited.add(node);
        recursionStack.add(node);

        for (int dep : graph.getOrDefault(node, Set.of())) {
            if (hasCycle(dep, graph, visited, recursionStack)) {
                return true;
            }
        }

        recursionStack.remove(node);
        return false;
    }
}
