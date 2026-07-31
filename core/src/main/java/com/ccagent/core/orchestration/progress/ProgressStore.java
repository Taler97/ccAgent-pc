package com.ccagent.core.orchestration.progress;

import com.ccagent.api.model.plan.Step;
import com.ccagent.infrastructure.util.JsonUtil;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 执行进度存储（内存态）
 * <p>记录每个 seqId 执行过程中各步骤的状态与活动描述，供进度查询接口读取快照。</p>
 *
 * <p>数据两类：</p>
 * <ul>
 *   <li>状态层：步骤生命周期（PENDING / RUNNING / SUCCESS / FAILED / CANCELLED），由调度器写入</li>
 *   <li>活动层：步骤内部实时动作（"正在执行命令 xxx"），由专家通过 ACTIVITY 事件上报</li>
 * </ul>
 *
 * <p>活动层数据不持久化，任务结束时由调度器调用 {@link #clear(String)} 整体销毁。</p>
 */
public class ProgressStore {

    public static final String PENDING = "PENDING";
    public static final String RUNNING = "RUNNING";
    public static final String SUCCESS = "SUCCESS";
    public static final String FAILED = "FAILED";
    public static final String CANCELLED = "CANCELLED";

    /** 单个步骤的进度信息（volatile 保证并发可见性） */
    public static class StepInfo {
        public final int stepId;
        public final String expertName;
        public final String task;
        public volatile String status = PENDING;
        public volatile String activity = "";
        public volatile long startTime = 0L;
        public volatile long endTime = 0L;

        StepInfo(int stepId, String expertName, String task) {
            this.stepId = stepId;
            this.expertName = expertName;
            this.task = task;
        }

        /** 运行时长毫秒（未开始返回 0，运行中按当前时刻推算） */
        public long elapsedMs() {
            if (startTime == 0L) return 0L;
            return (endTime > 0L ? endTime : System.currentTimeMillis()) - startTime;
        }
    }

    /** 一次执行的整体进度快照 */
    public static class ExecutionProgress {
        public final String seqId;
        public final long createdAt;
        public final List<StepInfo> steps;

        ExecutionProgress(String seqId, List<StepInfo> steps) {
            this.seqId = seqId;
            this.createdAt = System.currentTimeMillis();
            this.steps = steps;
        }

        public int totalSteps() {
            return steps.size();
        }

        public int completedSteps() {
            return (int) steps.stream().filter(s -> SUCCESS.equals(s.status)).count();
        }
    }

    private final Map<String, ExecutionProgress> store = new ConcurrentHashMap<>();

    /**
     * 注册一次执行计划（初始化全部步骤为 PENDING）
     */
    public void register(String seqId, List<Step> steps) {
        List<StepInfo> infos = new ArrayList<>();
        for (Step s : steps) {
            infos.add(new StepInfo(s.getId(), s.getExpertName(), s.getTask()));
        }
        store.put(seqId, new ExecutionProgress(seqId, infos));
    }

    /** 步骤开始（RUNNING + 记录开始时间） */
    public void startStep(String seqId, int stepId) {
        StepInfo info = find(seqId, stepId);
        if (info == null) return;
        info.status = RUNNING;
        info.startTime = System.currentTimeMillis();
    }

    /** 步骤成功 */
    public void completeStep(String seqId, int stepId) {
        StepInfo info = find(seqId, stepId);
        if (info == null) return;
        info.status = SUCCESS;
        info.endTime = System.currentTimeMillis();
    }

    /** 步骤失败 */
    public void failStep(String seqId, int stepId) {
        StepInfo info = find(seqId, stepId);
        if (info == null) return;
        info.status = FAILED;
        info.endTime = System.currentTimeMillis();
    }

    /** 步骤取消 */
    public void cancelStep(String seqId, int stepId) {
        StepInfo info = find(seqId, stepId);
        if (info == null) return;
        info.status = CANCELLED;
        info.endTime = System.currentTimeMillis();
    }

    /** 更新步骤活动描述（活动层，仅内存） */
    public void updateActivity(String seqId, int stepId, String activity) {
        StepInfo info = find(seqId, stepId);
        if (info == null || activity == null) return;
        info.activity = activity;
    }

    /**
     * 组装进度快照 JSON
     *
     * @return 快照 JSON 字符串；seqId 不存在（未注册或已销毁）时返回 null
     */
    public String snapshot(String seqId) {
        ExecutionProgress progress = store.get(seqId);
        if (progress == null) return null;

        int total = progress.totalSteps();
        int completed = progress.completedSteps();
        int overall = total == 0 ? 0 : Math.round(completed * 100f / total);

        List<Map<String, Object>> steps = new ArrayList<>();
        for (StepInfo s : progress.steps) {
            Map<String, Object> node = new LinkedHashMap<>();
            node.put("stepId", s.stepId);
            node.put("expertName", s.expertName == null ? "" : s.expertName);
            node.put("task", s.task == null ? "" : s.task);
            node.put("status", s.status);
            node.put("activity", s.activity);
            node.put("elapsedMs", s.elapsedMs());
            steps.add(node);
        }

        Map<String, Object> root = new LinkedHashMap<>();
        root.put("seqId", progress.seqId);
        root.put("totalSteps", total);
        root.put("completedSteps", completed);
        root.put("overallProgress", overall);
        root.put("steps", steps);
        return JsonUtil.toJson(root);
    }

    /**
     * 组装单个步骤的快照 JSON
     *
     * @return 单步骤快照 JSON；seqId 或 stepId 不存在时返回 null
     */
    public String snapshotStep(String seqId, int stepId) {
        ExecutionProgress progress = store.get(seqId);
        if (progress == null) return null;
        StepInfo info = null;
        for (StepInfo s : progress.steps) {
            if (s.stepId == stepId) {
                info = s;
                break;
            }
        }
        if (info == null) return null;

        Map<String, Object> node = new LinkedHashMap<>();
        node.put("seqId", progress.seqId);
        node.put("stepId", info.stepId);
        node.put("totalSteps", progress.totalSteps());
        node.put("expertName", info.expertName == null ? "" : info.expertName);
        node.put("task", info.task == null ? "" : info.task);
        node.put("status", info.status);
        node.put("activity", info.activity);
        node.put("elapsedMs", info.elapsedMs());
        return JsonUtil.toJson(node);
    }

    /**
     * 销毁指定执行的进度数据（任务结束时调用，活动数据不持久化）
     */
    public void clear(String seqId) {
        store.remove(seqId);
    }

    private StepInfo find(String seqId, int stepId) {
        ExecutionProgress progress = store.get(seqId);
        if (progress == null) return null;
        for (StepInfo info : progress.steps) {
            if (info.stepId == stepId) return info;
        }
        return null;
    }
}
