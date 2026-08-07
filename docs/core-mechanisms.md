# PC Agent 核心机制详解

> 面向需要理解执行引擎内部机理的开发者。涉及 DAG 调度、黑板（Blackboard）、产物持久化与断点续存四个核心机制。
> 相关代码：`core/orchestration/dispatcher/Dispatcher.java`、`core/context/TaskContextImpl.java`、`core/context/FileArtifactStore.java`、`core/orchestration/orchestrator/SimpleOrchestrator.java`。

## 1. DAG 调度（Dispatcher）

`Dispatcher.execute()` 将 Plan 的步骤按依赖图分批次调度，**批次内并行、批次间串行**。

### 依赖图构建

`buildDependencyGraph(steps)` 同时考虑两类依赖：

- **文件依赖**：步骤的 `affectedFiles` 出现相同文件 → 后者依赖前者（按步骤顺序，前序步骤写过该文件）。
- **黑板 Key 依赖**：步骤 B 的 `reads` 中出现步骤 A 的 `writes` 中的 Key → B 依赖 A。

执行前先 `hasCyclicDependency()` 检测，存在环抛 `PlanCycleException`。

### 批次循环

```
while (!remaining.isEmpty()) {
    ready = 剩余步骤中"依赖全部已完成"的步骤
    批次内并行：每个 ready 步骤 CompletableFuture.runAsync(...)
    allOf(futures).orTimeout(10min).join()   // 批次屏障
    有失败 → 取消其余步骤 + 回滚 + observer.onError()
    completed ∪= ready; remaining -= ready
}
updatePlanStatus(COMPLETED) → observer.onComplete()
```

- **线程模型分流**：IO 密集步骤（LLM 调用、文件读写、等黑板产物）走按批次创建的虚拟线程执行器（try-with-resources 自动关闭）；`cpuIntensive: true` 的步骤走固定大小平台线程池（大小 = CPU 核数），避免抢占 carrier 线程。
- **批次屏障**：`CompletableFuture.allOf(...).orTimeout(10min).join()`，防止步骤永久挂起。
- **失败处理**：批次内任一步骤失败 → 失败步骤发 `STEP_STATUS(ROLLED_BACK)` 并触发回滚，其余未执行步骤标记 `CANCELLED`，后续批次中止，`observer.onError(ToolException)`。
- **断点续存**：`skipIds` 中的已完成步骤直接跳过并 `progressStore.completeStep`，不重复执行。

### 单步骤执行（executeSingleStep）

```
取消检查 → updateStepStatus(RUNNING) → 发 STEP_STATUS(RUNNING) → progressStore.startStep
→ 等黑板输入：task.getArtifact(readKey).join()（5min 超时）
→ expert.execute(task, inputs, workspaceRoot, activityObserver)   // LLM 流式 / 工具调用
→ 白名单过滤：仅 step.writes 中的 Key 允许写入
→ task.putArtifact(key, value)    // 按 schema mergeStrategy 合并
→ 兜底：writes 中未被写入的 Key 补写空串（避免下游 join 永久阻塞）
→ updateStepStatus(SUCCESS) + addCompletedStep → 发 STEP_STATUS(SUCCESS) → StepCompletedEvent
```

`activityObserver` 会把专家产出的 `ACTIVITY` 注入 `stepId + expertName` 后转发，保证并发多步骤时前端看板活动文本归属正确。

## 2. 黑板（TaskContextImpl）

LangGraph 风格的缩略型黑板：每个 Plan 执行一个实例，`ConcurrentHashMap<String, CompletableFuture<Object>>` 提供生产者-消费者同步。

### 合并策略（由 blackboard-schema.yaml 驱动）

`schema.getField(key).mergeStrategy`：

| 策略 | 语义 | 适用 |
|------|------|------|
| `replace`（默认） | 新值覆盖旧值 | 单值产物（summary、code_summary、result） |
| `append` | 旧 List + 新值合并成新 List（跨步骤累积） | 列表产物（findings、files_changed、next_steps） |
| `merge` | 旧 Map + 新 Map 合并 | 键值产物（decisions） |

合并通过 `futures.compute` **原子完成**：旧 Future 未完成时直接 `complete` 唤醒所有等待的消费者（修复"消费者永久挂起"）；旧 Future 已完成则创建新 Future。

`blackboard-schema.yaml` 位于 `api/src/main/resources/`，字段含 `type / mergeStrategy / description`；`YamlBlackboardSchema` 懒加载解析，未配置字段视为 `replace`。

### 持久化与恢复

- `markPersistent(key)` 打标后，`putArtifact` 会异步（虚拟线程）`artifactStore.save(workspaceRoot, seqId, key, value)`。
- `restoreFromDisk(seqId)`：从磁盘加载已持久化 Key 为已完成 Future；清单有记录但文件缺失/损坏的 Key 记入 `missingKeys`，供上层（恢复流程）决策。
- `close()`：置取消标志，取消并清空所有未完成 Future。

## 3. 产物持久化（FileArtifactStore）

- **存储路径**：`{workspaceRoot}/.codepal/cache/artifacts/{seqId}/{key}.json`（`seqId` 为 `sessionId_version` 形式的确定性 Key，保证断点恢复可寻址）。
- **原子写入**：先写 `.tmp` 再 `ATOMIC_MOVE` rename。
- **manifest 清单**：每个任务目录维护 `manifest.json` 记录全部持久化 Key；`listKeys` 返回「清单 ∪ 磁盘枚举」并集（兼容清单丢失），`findMissingKeys` 逐个实际加载校验，用于发现"用户手动删除产物文件"。
- 常量 `LARGE_OBJECT_THRESHOLD = 1MB` 定义了大对象阈值。

## 4. 断点续存（SimpleOrchestrator.recoverInterruptedPlans）

引擎重启时自动恢复被中断的执行计划：

```
启动 → executionPlanRepository.findByStatus("RUNNING")
→ 对每个记录：
    plan = record.getPlan()
    skipIds = parse(completed_step_ids)              // 已完成的步骤 ID
    task = TaskContextImpl(recoveryKey=sessionId_version).restoreFromDisk()
    handleMissingArtifacts(plan, skipIds, task)      // 缺失产物 → 生产者步骤移出 skipIds 强制重跑
    skipIds 覆盖全部步骤 → 标记 COMPLETED
    否则 → 发布 PlanExecutionStartedEvent（重新整批备份）→ dispatcher.execute(..., skipIds)
```

配合 H2 中 `execution_plan` 表：`createPlan` 持久化 Plan，`updateStepStatus` 记录每步状态，`addCompletedStep` 累积 `completed_step_ids`，全部完成后 `updatePlanStatus(COMPLETED)`。步骤失败/取消时状态分别记 `FAILED` / `CANCELLED`。

## 5. 事件驱动的治理钩子

| 事件 | 时机 | 治理响应 |
|------|------|---------|
| `PlanExecutionStartedEvent` | 计划执行前（含恢复场景）一次性发布 | RollbackManager 整批备份（git blob 检查点 / snapshot） |
| `StepStartedEvent` / `StepCompletedEvent` | 每步开始/成功 | Auditor 审计 |
| `StepFailedEvent` | 步骤失败 | RollbackManager 会话级整批回滚 + Auditor |
| `StepRolledBackEvent` | 回滚完成 | Auditor 记录结果 |
| `LlmCallCompletedEvent` | 每次 LLM 调用结束 | TokenStatsService 按 seqId 累积 token |

> 备份在 Plan 执行前一次性完成，步骤事件零 IO，不占用执行线程池。
