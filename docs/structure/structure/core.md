# core — 业务核心层

## 职责

核心业务逻辑层，包含编排、治理、工具注册、同步、事件、上下文管理等所有核心领域逻辑。

## 位置

`pcAgent/core/src/main/java/com/ccagent/core/`

## 关键类

### orchestration — 编排层

| 子包 | 类 | 职责 |
|------|-----|------|
| `agent/` | `ChatAgent.java` | 聊天 Agent，处理用户对话与执行流程 |
| `agent/` | `ChatResult.java` | 聊天结果封装 |
| `context/` | `ExecutionContext.java` | 执行上下文（会话、步骤、状态） |
| `dispatcher/` | `Dispatcher.java` | 任务分发器，按 Plan 调度 Expert 执行 |
| `orchestrator/` | `SimpleOrchestrator.java` | 简易编排器实现，支持 Plan 中断恢复 |
| `repository/` | `ExecutionPlanRepository.java` | 执行计划仓库接口 |

### governance — 治理层

| 子包 | 类 | 职责 |
|------|-----|------|
| `audit/` | `AuditEvent.java`, `AuditLevel.java` | 审计事件与级别定义 |
| `context/` | `ExecutionContextHolder.java` | 执行上下文持有者（线程级） |
| `isolation/` | `ExecutionIsolator.java` | 执行隔离器接口 |
| `isolation/` | `IsolationContext.java` | 隔离上下文 |
| `isolation/` | `NoOpIsolator.java` | 无操作隔离器（默认实现） |
| `policy/` | `PlanSanitizer.java` | Plan 清洗器（安全过滤） |
| `policy/` | `PolicyEnforcer.java` | 策略执行器 |
| `policy/` | `TokenQuotaService.java` | Token 配额管理服务 |

### tool — 工具引擎

| 子包 | 类 | 职责 |
|------|-----|------|
| `annotation/` | `@Tool.java` | 工具注解 |
| `annotation/` | `@Param.java` | 参数注解 |
| 直接 | `DefaultToolRegistry.java` | 工具注册表默认实现 |
| 直接 | `ToolCallRepairer.java` | 工具调用参数修复 |
| 直接 | `ToolExecutor.java` | 工具执行器 |

### expert — 专家注册

| 类 | 职责 |
|-----|------|
| `DefaultExpertRegistry.java` | 专家注册表默认实现 |

### plugin — 插件扩展点接口

| 类 | 职责 |
|-----|------|
| `ToolExtensionPoint.java` | 工具插件扩展点 |
| `ExpertExtensionPoint.java` | 专家插件扩展点 |
| `ListenerExtensionPoint.java` | 监听器插件扩展点 |

### context — 任务上下文与产物存储

| 类 | 职责 |
|-----|------|
| `TaskContextImpl.java` | 任务上下文实现，管理执行生命周期 |
| `ArtifactStore.java` | 产物存储接口 |
| `FileArtifactStore.java` | 基于文件的产物存储实现 |

### event — 领域事件

| 类 | 触发时机 |
|-----|---------|
| `ExecutionStartedEvent.java` | 执行开始 |
| `ExecutionCompletedEvent.java` | 执行完成 |
| `ExecutionFailedEvent.java` | 执行失败 |
| `StepStartedEvent.java` | 步骤开始 |
| `StepCompletedEvent.java` | 步骤完成 |
| `StepFailedEvent.java` | 步骤失败 |
| `StepRolledBackEvent.java` | 步骤回滚 |
| `LlmCallCompletedEvent.java` | LLM 调用完成（用于 Token 统计） |

### memory — 记忆

| 类 | 职责 |
|-----|------|
| `Compressor.java` | 对话记忆压缩器 |

### sync — 同步机制

| 类 | 职责 |
|-----|------|
| `SyncClient.java` | 同步客户端 |
| `SyncService.java` | 同步服务 |
| `SyncScheduler.java` | 同步调度器（定时触发） |
| `SyncManifestRepository.java` | 同步清单仓库 |
| `LocalDataRepository.java` | 本地数据仓库 |
| `di/SyncModule.java` | 同步模块 DI 配置 |
| `model/SyncDiff.java` | 同步差异模型 |
| `model/SyncManifest.java` | 同步清单模型 |
| `model/SyncResult.java` | 同步结果模型 |

## 依赖关系

- **依赖**: `api`、`kernel`、`exception`、`infrastructure`
- **被依赖**: `adapter` → `presentation`
