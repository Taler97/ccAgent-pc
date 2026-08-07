# 会话并发模型（concurrency-model）

> 本文档是会话并发改造的**规范与决策记录**，覆盖"通道 / 锁 / 串行性"三层规约、四象限分类、现状对照与改造范围。所有架构决策（D1-D7）已确认，改动按阶段落地。
>
> **实施状态（已完成）**：阶段 1（configChannel）与阶段 2+3（会话状态机 + 锁瘦身）已落地并通过全量回归；阶段 4（前端 `SESSION_BUSY` 适配 + 本文档最终态）完成。云端无需代码改动——`SESSION_BUSY` 作为 ERROR envelope 经现有 `PcWebSocketHandler` 的 ERROR 分支透传。

## 0. 背景与问题

现状：`SimpleOrchestrator.execute()` 用一把 **session 级 ReentrantLock** 把"状态变更 → LLM 决策 → 规划 → 审批等待(120s) → DAG 执行"整段串完，seqId 锁也被 EXECUTE 同步长占。

不拆分的问题：
1. 同会话二次执行（多标签页/双设备/脚本直连）→ `tryLock(10s)` 超时抛错，而非优雅排队；
2. 审批等待期锁被空占，任何未来"等待期碰会话状态"的功能会被挡；
3. 新增碰会话状态的功能会被一个 30s LLM 或 120s 审批卡住；
4. 审批打断依赖 `DIALOG_SKIP_LOCK_TYPES` 脆弱补丁，锁策略一改即失效。

## 1. 第一性原理

- **锁的本质是保护"状态不变量"，不是"执行串行"本身**。
- 串行性可以脱离锁实现（single-writer）：会话事实来源 = 单一状态对象 + 队列/标志。
- 一次状态转移常涉及多字段（agentState / inFlight / approvalFuture / cancelFlag / RUNNING 记录），必须**原子一起变**——这就是"锁内极短"的正确含义。
- 锁内不做 I/O（WS 推送、DB 写入）。

## 2. 三层正交规约

| 层 | 职责 | 载体 | 现状 |
|----|------|------|------|
| **通道** | 消息隔离（会话执行 / 只读查询 / 控制 / 配置） | `MessageDispatcher` 四通道 | 三通道，配置混在 util（**改造中**） |
| **锁** | 只护状态转移（≤1ms，无 I/O） | `SessionStateMachine.transition()` 原子 + 细粒度锁/CAS | session 长锁全程持有（**改造中**） |
| **串行性** | 同会话串行由"状态机裁决 + 前端排队"保证 | `SessionState`（inFlight 标志）+ 前端 `pendingQueue` | 靠长锁兜底（**改造中**） |

## 3. 四象限决策表

| 操作类型 | 典型场景 | 是否持有会话锁 | 是否阻塞对话 | 隔离策略 |
|---|---|---|---|---|
| 状态变更（瞬时） | 启动执行、取消、状态流转 | 是（≤1ms） | 是（仅毫秒级） | 锁内仅原子状态转移，不夹杂业务 |
| 长耗时决策（I/O） | LLM 推理（Chat/Plan）、工具执行 | 否（锁外） | 否（异步） | 异步流水线 + 回调经状态机更新状态 |
| 阻塞等待（用户） | `future.get(120s)` 审批 | 否（锁外挂起） | 否（虚拟线程挂起不占锁） | 释放锁后挂起，唤醒后走状态机 |
| 全局配置/导入（旁路） | 更新 LLM 配置、导入 MCP | 完全不需要 | 完全不阻塞 | 独立 `configChannel` |

## 4. 现状对照（改造前）

| 象限 | 现状 | 合规 |
|---|---|---|
| ① 瞬时状态变更 | 无独立短锁路径，状态流转夹杂在长流程内 | ✗ |
| ② 长耗时 I/O | ChatAgent/planner/DAG 全在 session 锁 + EXECUTE 的 seqId 锁内 | ✗ |
| ③ 人工等待 | `future.get(120s)` 在锁内 | ✗ |
| ④ 旁路配置/导入 | utilChannel 无锁独立，但与查询共用通道 | △ |

## 5. 决策记录（D1-D7，已确认）

| 编号 | 决策点 | 结论 |
|---|---|---|
| D1 | 同会话忙时再收 EXECUTE | **后端拒绝**（错误码 `SESSION_BUSY`）+ 前端 `pendingQueue` 排队 |
| D2 | 状态机形态 | **事件驱动状态机**（EventBus 监听 + 统一 `transition()`） |
| D3 | 审批等待期开放度 | **中级**：可查看进度 + 取消 |
| D4 | 旁路消息通道 | **新建 configChannel**（第四通道） |
| D5 | EXECUTE 执行体 | **异步提交**，seqId 锁毫秒级，**退役 `DIALOG_SKIP_LOCK_TYPES`** |
| D6 | 取消/回滚 | **一并重构为状态机驱动** |
| D7 | 交付顺序 | **阶段 2+3 合并** |

## 6. 目标架构

```
四通道：dialog（会话执行） · util（只读查询） · control（心跳/踢出） · config（配置/导入）
会话事实来源：SessionStateMachine（agentState + inFlight + approvalFuture + cancelFlag + seqTokens）
执行流：EXECUTE → 通道分发 → tryBegin（忙则拒绝 SESSION_BUSY）→ 锁内原子置 PLANNING
        → 释放锁 → 锁外：决策/规划/审批等待/DAG → 结束 finish()（状态机驱动）
```

## 7. 改造范围（按阶段）

### 阶段 1 · configChannel（✅ 已完成）
- `MessageDispatcher`：新增 `CONFIG_TYPES` + `configChannel` 执行器 + `shutdown()`。
- 收录：`UPDATE_LLM_CONFIG` / `QUERY_LLM_CONFIG` / `MCP_IMPORT_REQ`（写/导入）；`MCP_STATUS_REQ` / `SKILL_LIST_REQ` 保留 utilChannel（只读查询）。
- 测试：`MessageChannelIsolationIntegrationTest` 扩展（`mcpImportShouldNotBeBlockedByExecuteLock`）。

### 阶段 2+3 · 会话锁瘦身 + 事件驱动状态机（✅ 已完成）
- 新增：`SessionStateMachine`（`core/orchestration/state`）、`SessionStateChangedEvent`、`SESSION_BUSY` 错误码。
- 改造：`SimpleOrchestrator.execute`（`tryBegin` 瞬时准入 → 锁外流水线 → `finish`）、`ExecutionHandler`（EXECUTE 异步提交）、`MessageRouter`（退役 `DIALOG_SKIP_LOCK_TYPES`）、`GovernanceGateway` 无需改动（`executeWithSeqId` 原样适配异步）。
- 前端：`useChat.js` 识别 `SESSION_BUSY`（提示"会话忙"并恢复 idle）。
- 测试：`SessionStateMachineTest`、`SimpleOrchestratorTest`（忙拒绝）、异步化改造后的 Handler/E2E/隔离测试。

### 阶段 4 · 回归与文档（✅ 已完成）
- 全量回归：`mvn test -DexcludedGroups=real-llm` 通过（审批/取消/回滚/断点续存/并发）。
- 本文档已更新为最终态。

## 8. 风险与对策

| 风险 | 对策 |
|---|---|
| 同会话并发跑两个 Plan → 黑板 `sessionId_version` 覆盖、整批回滚交叉 | D1 拒绝语义兜底 + 并发测试 |
| 状态机多字段不一致 | `transition()` 原子 + 转移表单测 |
| 审批/取消/回滚竞态 | 状态机裁决 + 竞态专项测试 |
| 断点续存与 in-flight 交互 | `SessionStateMachine` 启动时按 RUNNING 计划重建 |

## 9. 关键实现约定

- 状态机关键路径（`tryBegin` / `finish` / cancel 裁决）**同步投递**保证忙判定及时；审计类事件异步。
- `SESSION_BUSY` 错误码：前端提示"会话忙，指令已排队"，云端透传。
- 执行体迁移线程时用 `ExecutionContextHolder.copyContext()/propagate()`（Dispatcher 已有先例）。
