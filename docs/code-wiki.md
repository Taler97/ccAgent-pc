# ccAgent Code Wiki

> 常驻本地的 AI 程序员团队引擎 — 基于 Java 21 的智能体运行时。
>
> 本文档是 ccAgent 项目的完整代码 Wiki，覆盖**整体架构、模块职责、关键类与函数、依赖关系、运行方式**等核心信息。

---

## 目录

1. [项目概览](#1-项目概览)
2. [整体架构](#2-整体架构)
3. [模块详解](#3-模块详解)
   - 3.1 [exception — 异常层](#31-exception--异常层)
   - 3.2 [infrastructure — 基础设施层](#32-infrastructure--基础设施层)
   - 3.3 [kernel — 微内核层](#33-kernel--微内核层)
   - 3.4 [api — 接口契约层](#34-api--接口契约层)
   - 3.5 [core — 业务核心层](#35-core--业务核心层)
   - 3.6 [adapter — 适配器层](#36-adapter--适配器层)
   - 3.7 [plugins — 插件层](#37-plugins--插件层)
   - 3.8 [presentation — 接入通信层](#38-presentation--接入通信层)
4. [核心运行流程](#4-核心运行流程)
5. [依赖关系总览](#5-依赖关系总览)
6. [配置说明](#6-配置说明)
7. [构建与运行](#7-构建与运行)
8. [关键设计机制索引](#8-关键设计机制索引)

---

## 1. 项目概览

| 项 | 说明 |
|---|---|
| **项目名** | ccAgent（PC Agent） |
| **定位** | 常驻本地的 AI 程序员团队引擎 |
| **语言/版本** | Java 21（虚拟线程、模式匹配、Records） |
| **构建工具** | Maven 3.9+（多模块） |
| **架构风格** | 六边形架构（Ports & Adapters）+ 微内核 + 事件驱动 + 插件化 |
| **运行模式** | 本地 CLI 交互 / 远程 WebSocket 守护进程 |
| **外部集成** | DeepSeek LLM、H2 嵌入式数据库、MCP 外部工具协议、Cloud Server |

### 核心技术栈

| 技术 | 用途 |
|------|------|
| **Java 21 虚拟线程** | 高并发 IO 任务调度 |
| **Maven 多模块** | 8 个子模块，严格单向依赖 |
| **六边形架构** | Ports & Adapters，核心与外部解耦 |
| **微内核** | 自研 EventBus + PluginManager |
| **事件驱动** | 治理层通过事件监听实现横切关注点 |
| **插件体系** | TOOL / EXPERT / LISTENER 三类，YAML 声明注册，运行时热加载 |
| **手工 DI** | 无框架依赖，各模块自维护 DI 模块，DependencyGraph 统一组装 |
| **H2 + HikariCP** | 嵌入式数据库 + 连接池 |
| **Jackson** | JSON/YAML 序列化 |
| **picocli** | CLI 参数解析 |
| **MCP Java SDK** | Model Context Protocol 外部工具接入 |

---

## 2. 整体架构

### 2.1 分层架构图

```
┌─────────────────────────────────────────────────────────────────┐
│                    presentation (接入通信层)                      │
│   CLI(AgentMain) · WS(CloudWebSocketClient) · DI(DependencyGraph) │
├─────────────────────────────────────────────────────────────────┤
│                      adapter (适配器层)                           │
│        LLM(DeepSeekLlmAdapter) · Memory(H2MemoryAdapter)          │
├─────────────────────────────────────────────────────────────────┤
│                      core (业务核心层)                            │
│   Orchestration · Governance · ToolEngine · Expert · Sync · Skill │
├─────────────────────────────────────────────────────────────────┤
│                      api (接口契约层)                             │
│      Port接口(LlmPort/MemoryPort/OrchestratorPort) · DTO模型      │
├─────────────────────────────────────────────────────────────────┤
│                    kernel (微内核层)                              │
│        EventBus(SimpleEventBus) · PluginManager(SimplePluginManager)
├──────────────────────┬──────────────────────────────────────────┤
│   infrastructure     │   exception                              │
│   Config/Json/File    │   AgentException / ErrorCode             │
├──────────────────────┴──────────────────────────────────────────┤
│                      plugins (插件层)                            │
│      plugin-tools · plugin-experts · plugin-governance           │
└─────────────────────────────────────────────────────────────────┘
```

### 2.2 依赖方向

**严格单向依赖（从下往上）**：

```
presentation → adapter → core → api → kernel → infrastructure / exception
                                        ↑
                                      plugins（运行时由 kernel 加载，物理隔离）
```

- 上层依赖下层，下层绝不反向依赖上层。
- `api` 层只定义接口与 DTO，不含实现，保证契约稳定。
- `adapter` 实现 `api` 定义的端口（LlmPort、MemoryPort）。
- `plugins` 模块物理隔离，运行时通过 `kernel.PluginManager` 加载，不参与编译期依赖链。

### 2.3 核心架构特征

| 特征 | 说明 |
|------|------|
| **六边形架构** | `api` 定义 Port 接口，`adapter` 实现外部适配，`core` 只依赖端口不依赖实现 |
| **微内核** | `kernel` 提供 EventBus + PluginManager 两大基础设施，所有业务组件挂载其上 |
| **事件驱动** | 编排层不直接调用治理组件，通过发布领域事件让治理监听器自动响应 |
| **插件化** | 工具/专家/治理监听器均通过 YAML 声明式注册，支持隔离加载 + 热插拔 |
| **手工 DI** | 每个模块有 `di/` 子包，由 `DependencyGraph` 按序组装 |
| **虚拟线程** | IO 密集任务用虚拟线程，CPU 密集任务分流到平台线程池 |

---

## 3. 模块详解

### 3.1 exception — 异常层

**职责**：提供统一的错误码 + 异常类体系。**零外部依赖**，是所有模块的基础。

**路径**：`exception/src/main/java/com/ccagent/exception/`

| 包 | 类 | 职责 |
|---|-----|------|
| `base/` | `AgentException` | 自定义运行时异常基类，所有业务异常的父类 |
| `base/` | `ErrorCode` | 统一错误码枚举（如 `TOOL_NOT_FOUND`、`PATH_TRAVERSAL_DETECTED`） |
| `llm/` | `LlmException` | LLM 调用异常（超时、拒绝、格式错误） |
| `tool/` | `ToolException` | 工具调用异常（执行失败、参数错误、工具不存在） |
| `plan/` | `PlanCycleException` | 计划编排异常（循环依赖、状态异常） |
| `governance/` | `GovernanceException` | 治理策略异常（配额超限、策略拒绝） |
| `security/` | `SecurityException` | 安全异常（鉴权失败、路径越界） |
| `workspace/` | `WorkspaceException` | 工作区操作异常（路径越界、权限不足） |
| `approval/` | `ApprovalException` | 审批流程异常（审批拒绝、超时未审批） |

**依赖关系**：自身无依赖；被所有上层模块依赖。

---

### 3.2 infrastructure — 基础设施层

**职责**：提供配置加载、JSON/文件工具、日志初始化。通过 `CommonModule` 完成自身 DI。

**路径**：`infrastructure/src/main/java/com/ccagent/infrastructure/`

| 包 | 类 | 职责 |
|---|-----|------|
| `config/` | `AppConfig` | 应用配置实体，映射 `ccagent.yaml`（auth/llm/websocket/sync/cli/agent/http/governance） |
| `config/` | `ConfigLoader` | YAML 配置加载器，支持 `${VAR}` / `${VAR:default}` 环境变量占位符 |
| `constant/` | `Constants` | 全局常量（目录路径、文件命名等） |
| `di/` | `CommonModule` | 基础设施 DI 模块，初始化 DataSource（HikariCP + H2） |
| `logging/` | `LogConfig` | 日志配置初始化 |
| `util/` | `FileUtil` | 文件操作工具（读写、遍历、目录创建） |
| `util/` | `JsonUtil` | JSON 序列化/反序列化（Jackson） |
| `util/` | `PathUtil` | 路径处理工具，含**白名单路径校验**（`setAllowedPaths` / `isAllowedPath` / `resolve`） |

**关键点**：`PathUtil.setAllowedPaths()` 在启动时由 `DependencyGraph` 注入用户白名单，进程级不可变更。

**依赖关系**：依赖 `exception`；被 `kernel → api → core → adapter → presentation` 依赖。

---

### 3.3 kernel — 微内核层

**职责**：提供系统两大核心机制——**事件总线**（EventBus）和**插件管理器**（PluginManager），是微内核架构的基石。

**路径**：`kernel/src/main/java/com/ccagent/kernel/`

#### 3.3.1 event — 事件总线

| 类 | 职责 |
|---|------|
| `Event` | 事件标记接口 |
| `EventBus` | 事件总线接口（同步/异步发布、订阅、关闭） |
| `EventHandler<T>` | 事件处理器接口（`handle(T event)`） |
| `SimpleEventBus` | 默认实现：同步/异步发布 + 持久化日志 |
| `Subscription` | 订阅封装，可取消订阅 |

**SimpleEventBus 核心机制**：
- 异步消费走虚拟线程池（`Executors.newVirtualThreadPerTaskExecutor`）。
- **有界持久化队列**（默认 1000）：队列满时先背压 100ms（`ENQUEUE_TIMEOUT_MS`）再丢弃并计数。
- **丢弃限频告警**：丢弃告警最小间隔 10 秒，防刷屏。
- **可选令牌桶限流**：`rateLimitPerSecond`（默认 0=关闭），超限事件直接丢弃。
- 事件持久化到 `event.log`，定时 flush。
- 暴露队列水位与丢弃指标（`droppedEventCount`、`rateLimitedCount`）。

#### 3.3.2 plugin — 插件框架

| 类 | 职责 |
|---|------|
| `PluginManager` | 插件管理器接口（扫描、加载、初始化、重载、关闭） |
| `SimplePluginManager` | 默认实现：classpath + 插件目录扫描，反射实例化 |
| `PluginClassLoader` | 插件隔离类加载器（child-first），类冲突隔离、可卸载 |
| `PluginHotReloader` | 插件目录热插拔：WatchService 监控 jar 变化 + 500ms 防抖 |
| `PluginDescriptor` | 插件描述符（name、type、class、依赖、isolated、sourceUrl） |
| `PluginContext` | 插件上下文接口（访问核心服务） |
| `SimplePluginContext` | 插件上下文默认实现（`registerService` / `getService`） |
| `ExtensionPoint` | 扩展点接口标记（`init` + 默认 `shutdown` 清理钩子） |
| `PluginType` | 插件类型枚举：`TOOL` / `EXPERT` / `LISTENER` |
| `PluginLoadReport` | 插件加载健康检查报告（扫描数、成功/失败数、分类列表） |

**SimplePluginManager 加载流程**：
1. 扫描 classpath 与插件目录（`.codepal/plugins`）下所有 `META-INF/plugins/*.yaml`。
2. 按 `type` 过滤 + `dependencies` 拓扑排序。
3. 反射创建无参实例 → 调用 `init(context)`。
4. 返回已初始化的扩展点列表。

**隔离与容错**：
- 插件目录 jar 由独立 `PluginClassLoader`（child-first）加载；内核/API 包 parent-first 保证类型兼容。
- 坏插件（`NoClassDefFoundError`、`ExceptionInInitializerError`、init 异常）被捕获记录，不击穿内核。
- init 失败自动调用 `shutdown()` 回滚半初始化资源。
- `PluginHotReloader` 支持运行时热重载 LISTENER 治理插件并替换引用。

**依赖关系**：依赖 `exception`、`infrastructure`；被 `api → core → adapter → presentation` 依赖。

---

### 3.4 api — 接口契约层

**职责**：定义模块间的**端口接口**（Port）和**数据传输对象**（DTO）。所有跨模块调用必须通过此层定义的接口。

**路径**：`api/src/main/java/com/ccagent/api/`

#### 3.4.1 port — 端口接口

| 接口 | 职责 |
|------|------|
| `LlmPort` | LLM 服务端口（同步/流式调用） |
| `MemoryPort` | 记忆存储端口（会话、偏好、对话上下文） |
| `ToolRegistryPort` | 工具注册端口（获取工具定义、执行工具） |
| `McpPort` | MCP 协议端口 |
| `Expert` | 专家角色接口（`execute` + `getName` + `getDescription`） |
| `ExpertRegistryPort` | 专家注册端口 |
| `OrchestratorPort` | 编排器端口（`execute` / `executeWithSeqId` + 会话管理） |
| `StreamObserver<T>` | 流式观察者（`onNext` / `onError` / `onComplete`） |
| `SafeStreamObserver<T>` | 安全流式观察者封装（异常保护） |

**Expert 接口要点**：
- 旧接口 `execute(task, workspaceRoot, observer)`（已 @Deprecated）。
- 黑板模式 `execute(task, taskScope, inputs, workspaceRoot, observer)` 返回 `Map<String,Object>` 缩略结构化产物，由 Dispatcher 按 schema 合并进黑板。

#### 3.4.2 context — 任务上下文

| 类 | 职责 |
|---|------|
| `TaskScope` | 任务级黑板作用域，管理专家执行生命周期与产物交换 |
| `BlackboardSchema` | 黑板字段 schema（字段定义与合并策略） |
| `FieldSchema` | 单个黑板字段定义 |
| `TaskScope` | 任务级黑板作用域 |

**TaskScope 核心 API**：
- `getArtifact(key)` / `putArtifact(key, value)` — 基于 `CompletableFuture` 的生产者-消费者同步。
- `computeIfAbsent(key, supplier)` — 单次计算保证。
- `markPersistent(key)` / `isPersistent(key)` — 持久化标记。
- `restoreFromDisk(seqId)` — 从磁盘恢复持久化产物（断点续存）。

#### 3.4.3 model — 数据模型

| 子包 | 关键类 |
|------|--------|
| `envelope/` | `Envelope` — WebSocket 消息信封（type、seqId、timestamp、payload） |
| `mcp/` | `McpResource`、`McpTool` |
| `memory/` | `ConversationTurn`、`SessionInfo`、`StructuredContext`、`UserPreferences` |
| `plan/` | `Plan`（summary、steps、estimatedImpact）、`Step` |
| `request/` | `ChatRequest`、`ExecuteRequest`、`ToolCall` |
| `response/` | `ChatResponse`、`ExecutionResult`、`FileNode`、`StreamChunk` |
| `tool/` | `ToolDefinition` |

**Step 模型关键字段**：
```
id, expertName, task, affectedFiles,
reads (黑板 Key 依赖), writes (黑板 Key 产出),
persistOutput, cpuIntensive (CPU 密集标志)
```

**依赖关系**：依赖 `exception`、`kernel`；被 `core → adapter → presentation` 依赖。

---

### 3.5 core — 业务核心层

**职责**：核心业务逻辑，包含编排、治理、工具注册、同步、事件、上下文管理等所有领域逻辑。

**路径**：`core/src/main/java/com/ccagent/core/`

#### 3.5.1 orchestration — 编排层

| 子包 | 类 | 职责 |
|------|-----|------|
| `agent/` | `ChatAgent` | 对话入口：判断简单回复 vs 需要规划执行，返回 `ChatResult` |
| `agent/` | `ChatResult` | 聊天结果封装（`type: chat/plan`） |
| `context/` | `ExecutionContext` | 执行上下文（会话、步骤、状态） |
| `dispatcher/` | `Dispatcher` | 按 DAG 调度 Plan 步骤，虚拟线程并行 + 平台线程池 CPU 分流 |
| `orchestrator/` | `SimpleOrchestrator` | 编排器实现，支持 Plan 中断恢复 |
| `repository/` | `ExecutionPlanRepository` | 执行计划仓库（持久化 completed_step_ids 支持断点续存） |
| `progress/` | `ProgressStore` | 进度存储，供查询链路读取快照 |
| `state/` | `SessionStateMachine` | 会话状态机，裁决串行性 |

**ChatAgent 决策逻辑**：
- 加载会话记忆（summary + recent turns）。
- 调用 LLM 判断输出 `{"type":"chat","content":...}` 或 `{"type":"plan","task":...}`。
- system prompt 可通过 `agent.system-prompt` 配置自定义。

**Dispatcher 执行策略**：
- 基于 `affectedFiles` + `reads/writes` 构建 DAG，无依赖步骤并行执行。
- 同批次任一步骤失败 → 取消批次其余步骤、触发回滚、中止后续批次。
- **CPU 分流**：`cpuIntensive: true` 的步骤走平台线程池（大小 = CPU 核数），其余走虚拟线程。
- 黑板同步：单步骤等待黑板产物超时 5 分钟，单批次超时 10 分钟。
- 断点续存：`skipIds` 跳过已完成步骤，`restoreFromDisk` 恢复持久化产物。
- 循环检测：Key 级循环依赖抛出 `PlanCycleException`。

**SimpleOrchestrator 关键方法**：
- `execute(ExecuteRequest, seqId, observer)` — 主执行入口。
- `recoverInterruptedPlans()` — 启动时查询 RUNNING 状态计划，从磁盘恢复并重跑。

#### 3.5.2 governance — 治理层

| 子包 | 类 | 职责 |
|------|-----|------|
| `audit/` | `AuditEvent`、`AuditLevel` | 审计事件与级别定义 |
| `context/` | `ExecutionContextHolder` | 执行上下文持有者（线程级） |
| `isolation/` | `ExecutionIsolator`、`IsolationContext`、`NoOpIsolator` | 执行隔离（默认 NoOp） |
| `policy/` | `PolicyEnforcer` | 策略执行：校验工作区、路径白名单（防路径穿越） |
| `policy/` | `TokenQuotaService` | Token 配额管理 |
| `policy/` | `PlanSanitizer` | Plan 安全清洗 |

**PolicyEnforcer 路径校验**：
- `checkPath(workspaceRoot, requestedPath)` — 规范化后校验是否在工作区或白名单内。
- 采用**"工作区 ∪ 白名单"**放行策略：动态白名单（当前工作区）+ 静态白名单（配置文件声明）。
- 校验入口：`PolicyEnforcer.checkPath`、`DefaultToolRegistry.executeTool`、`FileOperationTool.validatePath`、`FileHandler`。

#### 3.5.3 tool — 工具引擎

| 类 | 职责 |
|---|------|
| `@Tool` | 工具类注解（name、description、readOnly） |
| `@Param` | 参数注解 |
| `DefaultToolRegistry` | 工具注册表：注册、获取定义、执行工具（含路径校验 + 工具名模糊匹配） |
| `ToolCallRepairer` | 工具调用参数修复（LLM 产出参数容错） |
| `ToolExecutor` | 工具执行器接口（getName、execute、getParameters） |

**DefaultToolRegistry.executeTool 流程**：
1. 查找工具，不存在抛 `ToolException(TOOL_NOT_FOUND)`。
2. 对 `path`/`source`/`target` 参数做路径白名单校验。
3. 调用 `ToolExecutor.execute(args, workspaceRoot)`。

#### 3.5.4 expert — 专家注册

| 类 | 职责 |
|---|------|
| `DefaultExpertRegistry` | 专家注册表默认实现 |

#### 3.5.5 plugin — 插件扩展点接口

| 类 | 职责 |
|---|------|
| `ToolExtensionPoint` | 工具插件扩展点 |
| `ExpertExtensionPoint` | 专家插件扩展点 |
| `ListenerExtensionPoint` | 监听器插件扩展点 |

#### 3.5.6 context — 任务上下文与产物存储

| 类 | 职责 |
|---|------|
| `TaskContextImpl` | `TaskScope` 实现，管理黑板产物 + CompletableFuture 同步 |
| `ArtifactStore` | 产物存储接口 |
| `FileArtifactStore` | 基于文件的产物存储（`@Persistent` POJO 自动序列化，>1MB 存路径引用） |
| `YamlBlackboardSchema` | 从 `blackboard-schema.yaml` 加载黑板字段定义 |

#### 3.5.7 event — 领域事件

| 事件 | 触发时机 | 监听者 |
|------|---------|--------|
| `ExecutionStartedEvent` | 执行开始 | Auditor、TokenStatsService |
| `ExecutionCompletedEvent` | 执行完成 | Auditor |
| `ExecutionFailedEvent` | 执行失败 | Auditor |
| `PlanExecutionStartedEvent` | Plan 确认后、Dispatcher 执行前 | RollbackManager（整批备份） |
| `StepStartedEvent` | 步骤开始 | Auditor、RollbackManager |
| `StepCompletedEvent` | 步骤完成 | Auditor |
| `StepFailedEvent` | 步骤失败 | Auditor、RollbackManager（触发回滚） |
| `StepRolledBackEvent` | 步骤回滚完成 | Auditor |
| `LlmCallCompletedEvent` | LLM 调用完成 | TokenStatsService（Token 统计） |

#### 3.5.8 memory — 记忆

| 类 | 职责 |
|---|------|
| `Compressor` | 对话记忆压缩器：超过 30 轮时调用 LLM 压缩为 summary，保留近期 turns |

#### 3.5.9 sync — 同步机制

| 类 | 职责 |
|---|------|
| `SyncClient` | HTTP 客户端，与 Cloud Server 交换 Manifest |
| `SyncService` | 同步服务：计算 diff、上传/下载增量数据 |
| `SyncScheduler` | 定时调度器（`interval-minutes` 间隔） |
| `SyncManifestRepository` | 同步清单仓库 |
| `LocalDataRepository` | 本地数据仓库（会话、偏好、对话记录 CRUD） |
| `model/SyncDiff` | 本地与云端差异模型 |
| `model/SyncManifest` | 同步清单模型 |
| `model/SyncResult` | 同步结果模型 |

#### 3.5.10 skill — 技能体系

| 类 | 职责 |
|---|------|
| `Skill` | 技能模型（name、description、expert 绑定、enabled、prompt 正文） |
| `SkillLoader` | 从 `.codepal/skills/` 加载 SKILL.md（支持目录与单文件形式） |
| `SkillRegistry` | 技能注册表：按名查询、`resolvePrompt` 合并多技能正文、`reload` 热更新 |

**SKILL.md 格式**：YAML frontmatter（name/description/expert/enabled）+ 正文 prompt。绑定专家时，专家启动经 `SkillRegistry.resolvePrompt()` 合并进 system prompt。

**依赖关系**：依赖 `api`、`kernel`、`exception`、`infrastructure`；被 `adapter → presentation` 依赖。

---

### 3.6 adapter — 适配器层

**职责**：实现 api 层定义的端口接口。当前包含 LLM 适配（DeepSeek）和持久化适配（H2）。

**路径**：`adapter/src/main/java/com/ccagent/adapter/`

#### 3.6.1 llm — LLM 适配

| 类 | 职责 |
|---|------|
| `DeepSeekLlmAdapter` | 实现 `LlmPort`，调用 DeepSeek API（流式 + 同步） |
| `di/LlmModule` | LLM 模块 DI 组装 |

**DeepSeekLlmAdapter 能力**：
- 支持流式与同步调用。
- 思考模式（`thinking-enabled`）产出 THINKING_CHUNK。
- 超时配置：`timeout-streaming`（60s）、`timeout-sync`（30s）。
- 由 `TokenTrackingLlmProxy` 包装，调用完成后发布 `LlmCallCompletedEvent`。

#### 3.6.2 memory — 持久化适配

| 类 | 职责 |
|---|------|
| `H2MemoryAdapter` | 实现 `MemoryPort`，H2 嵌入式数据库持久化 |
| `di/MemoryModule` | 记忆模块 DI 组装 |
| `repository/PreferencesRepository` | 用户偏好持久化 |
| `repository/SessionRepository` | 会话持久化（recent turns、summary） |
| `schema/DatabaseInitializer` | DDL 自动建表 |

**H2MemoryAdapter 记忆压缩**：
- 每轮对话调用 `saveTurn`，turns > 30 时触发 `Compressor.compress`。
- 压缩失败降级为直接保存，不阻塞。

**依赖关系**：依赖 `core`、`api`、`exception`、`infrastructure`；被 `presentation` 依赖。

---

### 3.7 plugins — 插件层

**职责**：物理隔离的插件实现，运行时由 `kernel.PluginManager` 热加载。分三类。

**路径**：`plugins/`

#### 3.7.1 plugin-tools — 工具插件（6 个）

| YAML 描述符 | 插件类 | 职责 |
|-------------|--------|------|
| `read-file-tool.yaml` | `ReadFileTool` | 读取文件内容 |
| `write-file-tool.yaml` | `WriteFileTool` | 写入/创建文件 |
| `edit-file-tool.yaml` | `EditFileTool` | 编辑/修改已有文件 |
| `list-directory-tool.yaml` | `ListDirectoryTool` | 列出目录内容 |
| `search-content-tool.yaml` | `SearchContentTool` | 在文件中搜索文本 |
| `file-operation-tool.yaml` | `FileOperationTool` | 文件操作（复制、移动、删除、mkdir、stat） |

**额外组件**：
- `mcp/McpClientManager` — MCP 外部工具管理器：按 `.codepal/mcp/*.yaml` 连接外部 MCP server，将工具包装为 `mcp_<prefix>_<tool>` 动态注册进 ToolRegistry。单 server 连接失败只跳过该 server。

#### 3.7.2 plugin-experts — 专家插件（7 个）

| YAML 描述符 | 插件类 | 职责 |
|-------------|--------|------|
| `planner-expert.yaml` | `PlannerExpert` | 计划制定：分析需求，生成执行计划 |
| `researcher-expert.yaml` | `ResearcherExpert` | 调研：代码/文档调研分析 |
| `writer-expert.yaml` | `WriterExpert` | 编码：代码生成与修改 |
| `summarizer-expert.yaml` | `SummarizerExpert` | 总结：内容总结 |
| `note-taker-expert.yaml` | `NoteTakerExpert` | 笔记：记录关键信息 |
| `organizer-expert.yaml` | `OrganizerExpert` | 组织：信息整理归类 |
| `fault-analyzer-expert.yaml` | `FaultAnalyzerExpert` | 故障分析：诊断执行失败原因 |

#### 3.7.3 plugin-governance — 治理监听器插件（3 个）

| YAML 描述符 | 插件类 | 职责 |
|-------------|--------|------|
| `auditor-listener.yaml` | `Auditor` | 审计日志记录：监听执行/步骤事件写入审计日志 |
| `token-stats-listener.yaml` | `TokenStatsService` | Token 用量统计：监听 `LlmCallCompletedEvent` |
| `rollback-manager-listener.yaml` | `RollbackManager` | 回滚管理：监听 `StepFailedEvent` 执行会话级整批回滚 |

**RollbackManager 双模备份策略**（`rollbackProvider`，默认 auto）：
- `git`：工作区是 git 仓库时，`git hash-object -w` 将受影响文件写入对象库，回滚时 `git cat-file blob` 还原。
- `snapshot`：文件快照到 `.codepal/staging/`（非 git 环境回退）。
- `auto`：自动检测，是 git 则用 git，否则 snapshot。
- 备份时机：`PlanExecutionStartedEvent` 时一次完成整批备份，步骤事件零 IO。

**依赖关系**：运行时由 `kernel.SimplePluginManager` 加载（classpath 扫描 + 插件目录 jar 隔离加载）；物理隔离在独立 Maven 模块。

---

### 3.8 presentation — 接入通信层

**职责**：引擎顶层入口，负责 CLI 交互、WebSocket 远程连接、全部 DI 模块组装。

**路径**：`presentation/src/main/java/com/ccagent/presentation/`

#### 3.8.1 cli — CLI 入口

| 类 | 职责 |
|---|------|
| `AgentMain` | **唯一主入口**：参数解析（`--background`/`--standalone`）→ 单实例锁 → 配置 → DependencyGraph 组装 → 恢复中断计划 → 按形态挂载交互层 |
| `DaemonLock` | 单实例锁：`.codepal/daemon.lock` 文件锁，保证同时只有一个引擎进程 |
| `StandaloneAgentRunner` | CLI 交互运行器：主循环、命令处理、流式输出、会话管理、文件浏览、Token 统计 |
| `DependencyGraph` | 依赖组装器：按序组装 8 个模块级 DI，输出插件加载报告 |

**DependencyGraph 组装顺序**：
```
PathUtil 白名单注入
  → PluginContext + PluginManager
  → CommonModule (DataSource + AppConfig)
  → ToolModule (TOOL 插件 + ToolRegistry)
  → McpClientManager (外部 MCP 工具)
  → LlmModule (LlmPort)
  → MemoryModule (MemoryPort)
  → SkillRegistry (skill.md)
  → ExpertModule (EXPERT 插件 + 自定义专家)
  → SyncModule (同步)
  → GovernanceModule (LISTENER 插件 + 治理组件)
  → OrchestrationModule (GovernanceGateway)
  → 热插拔监听器替换回调
  → 输出 PluginLoadReport
```

#### 3.8.2 di — 依赖注入模块

| 类 | 职责 |
|---|------|
| `OrchestrationModule` | 编排 DI：EventBus、Dispatcher、SimpleOrchestrator、GovernanceGateway |
| `ToolModule` | 工具 DI：加载 TOOL 插件，注册 ToolRegistry |
| `ExpertModule` | 专家 DI：加载 EXPERT 插件，注册 ExpertRegistry |
| `GovernanceModule` | 治理 DI：加载 LISTENER 插件，初始化 EventBus 监听器 |
| `GovernanceGateway` | 治理网关：实现 `OrchestratorPort`，对外暴露执行入口；支持监听器热替换 |
| `CustomExpertLoader` | 自定义专家加载：`experts/*.yaml` → GenericExpert |

**GovernanceGateway 执行流程**：
1. `policyEnforcer.checkPath` 校验路径。
2. `tokenQuotaService` 检查配额。
3. 发布 `ExecutionStartedEvent`。
4. 调用 `SimpleOrchestrator.execute`。
5. 成功发布 `ExecutionCompletedEvent`，失败发布 `ExecutionFailedEvent`。

#### 3.8.3 ws — WebSocket 客户端

| 类 | 职责 |
|---|------|
| `CloudWebSocketClient` | 云端 WS 客户端，连接 Cloud Server 接受远程调度 |
| `CloudWsSession` | WS 会话封装，持有客户端并实现 MessageSender |
| `MessageRouter` | 纯分发器：解析 Envelope → handlerRegistry 查表 → SeqLockManager 加锁 → Handler |
| `SeqLockManager` | seqId 粒度锁，按请求流水号串行化防并发 |
| `ReconnectManager` | 断线重连（指数退避，1s → 60s） |
| `StateManager` | 连接状态管理 |
| `MessageDispatcher` | 消息分发器 |

**5 个消息处理器**（均实现 `WsMessageHandler`，继承 `HandlerSupport`）：

| Handler | 处理 type | 是否走 LLM |
|---------|-----------|-----------|
| `SessionHandler` | `CREATE_SESSION` / `LIST_SESSIONS` | 否 |
| `ExecutionHandler` | `EXECUTE` / `EXECUTE_PLAN` / `CANCEL_PLAN` / `CANCEL_EXECUTION` / `ROLLBACK_DIALOG` / `TOOL_APPROVAL` | EXECUTE ✅ |
| `FileHandler` | `FILE_LIST_REQ` / `FILE_READ_REQ` | 否 |
| `StatusHandler` | `QUERY_PROGRESS` / `SKILL_LIST_REQ` / `MCP_STATUS_REQ` | 否 |
| `MiscHandler` | `PING` / `ON_KICK` | 否 |

**WebSocket Envelope 格式**：
```json
{
  "type": "操作码",
  "seqId": "流水号",
  "timestamp": "2026-08-05T10:30:00Z",
  "payload": { ... }
}
```

#### 3.8.4 observer — 观察者

| 类 | 职责 |
|---|------|
| `StatusObserver` | 状态观察者，输出执行状态到控制台 |

**启动模式**：

| 模式 | 命令 | 说明 |
|------|------|------|
| CLI 交互 | `java ... AgentMain`（无参数） | 引擎嵌入进程，命令行对话，纯本地不连云端 |
| 远程模式 | `java ... AgentMain --background` | 引擎后台运行，WS 连接 Cloud Server 接受调度（`--headless` 兼容） |
| 旧版兼容 | `java ... AgentMain --standalone` | 保留旧版入口（已废弃） |

**依赖关系**：依赖 `core`、`adapter`、`api`、`exception`、`infrastructure`、所有插件模块；外部依赖 picocli。

---

## 4. 核心运行流程

### 4.1 启动流程

```
AgentMain.main(args)
  ├─ DaemonLock.tryAcquire()              # 单实例锁
  ├─ ConfigLoader.load()                  # 加载 ccagent.yaml
  ├─ autoSetupWorkDir()                   # 工作区元数据
  ├─ LogConfig.init()
  ├─ DependencyGraph.assemble(config)     # 组装全部模块
  ├─ recoverInterruptedPlans()            # 异步恢复中断计划
  └─ 按形态挂载交互层
       ├─ runLocalCli → StandaloneAgentRunner.start()
       └─ runBackground → CloudWsSession.start() + SyncScheduler.start()
```

### 4.2 任务执行流程（CLI / EXECUTE 消息）

```
用户输入 / EXECUTE 消息
  → GovernanceGateway.executeWithSeqId
       ├─ PolicyEnforcer.checkPath        # 路径白名单校验
       ├─ TokenQuotaService 检查配额
       ├─ EventBus.publish(ExecutionStartedEvent)   # 治理监听器响应
       └─ SimpleOrchestrator.execute
            ├─ ChatAgent.chat → 决策 chat/plan
            ├─ 若是 plan：
            │    ├─ PlannerExpert 生成 Plan (Steps)
            │    ├─ PlanSanitizer 清洗
            │    ├─ EventBus.publish(PlanExecutionStartedEvent)  # RollbackManager 整批备份
            │    └─ Dispatcher.execute(plan)
            │         ├─ 构建 DAG (affectedFiles + reads/writes)
            │         ├─ 按批次拓扑调度
            │         │    ├─ 无依赖 Step 虚拟线程并行
            │         │    ├─ CPU 密集 Step 走平台线程池
            │         │    ├─ StepStartedEvent / StepCompletedEvent
            │         │    ├─ 失败 → StepFailedEvent → RollbackManager 整批回滚
            │         │    └─ 黑板产物通过 TaskScope 交换
            │         └─ ExecutionPlanRepository 持久化 completed_step_ids
            └─ EventBus.publish(ExecutionCompletedEvent / FailedEvent)
```

### 4.3 插件加载流程

```
DependencyGraph.assemble
  → ToolModule/ExpertModule/GovernanceModule.init
       → SimplePluginManager.loadExtensions(type, context)
            ├─ scanDescriptors()        # classpath + 插件目录 *.yaml
            ├─ topologicalSort()        # 按 dependencies 拓扑排序
            └─ 逐个 instantiate + init
                 ├─ 异常 → PluginLoadReport 记录，不击穿
                 └─ init 失败 → shutdown() 回滚
```

### 4.4 事件驱动治理流程

```
Dispatcher 执行步骤
  ├─ publish(StepStartedEvent)      → Auditor 记录
  ├─ publish(StepCompletedEvent)    → Auditor 记录
  └─ 失败:
       └─ publish(StepFailedEvent)
            ├─ Auditor 记录失败
            └─ RollbackManager 整批回滚 → publish(StepRolledBackEvent)

TokenTrackingLlmProxy 包装 LLM 调用
  └─ publish(LlmCallCompletedEvent) → TokenStatsService 累计 Token
```

---

## 5. 依赖关系总览

### 5.1 模块依赖链

```
exception (零依赖)
   ↑
infrastructure (→ exception)
   ↑
kernel (→ exception, infrastructure)
   ↑
api (→ exception, kernel)
   ↑
core (→ api, kernel, exception, infrastructure)
   ↑
adapter (→ core, api, exception, infrastructure)
   ↑
presentation (→ core, adapter, api, exception, infrastructure, plugins)
```

### 5.2 关键接口与实现对应

| Port 接口（api） | 实现（adapter / core） |
|---|---|
| `LlmPort` | `DeepSeekLlmAdapter`（adapter） |
| `MemoryPort` | `H2MemoryAdapter`（adapter） |
| `ToolRegistryPort` | `DefaultToolRegistry`（core） |
| `ExpertRegistryPort` | `DefaultExpertRegistry`（core） |
| `OrchestratorPort` | `GovernanceGateway`（presentation/di） |
| `EventBus` | `SimpleEventBus`（kernel） |
| `PluginManager` | `SimplePluginManager`（kernel） |
| `TaskScope` | `TaskContextImpl`（core） |

### 5.3 关键依赖注入模块

| DI 模块 | 所在模块 | 产出 |
|---------|---------|------|
| `CommonModule` | infrastructure | DataSource + AppConfig |
| `LlmModule` | adapter | LlmPort |
| `MemoryModule` | adapter | MemoryPort |
| `ToolModule` | presentation | ToolRegistryPort |
| `ExpertModule` | presentation | ExpertRegistryPort |
| `SyncModule` | core | SyncScheduler + LocalDataRepository |
| `GovernanceModule` | presentation | 治理组件 + EventBus |
| `OrchestrationModule` | presentation | GovernanceGateway (OrchestratorPort) |

---

## 6. 配置说明

配置文件 `ccagent.yaml`（模板见 `ccagent.example.yaml`），敏感项通过环境变量 `${VAR}` / `${VAR:default}` 注入。

### 6.1 配置项

```yaml
auth:
  api-key: "${CCAGENT_CLOUD_API_KEY:Qse2ffed}"   # Cloud API Key
  workspace-dir: "${CCAGENT_WORKSPACE_DIR:}"      # 工作区目录

llm:
  api-key: "${DEEPSEEK_API_KEY:}"                 # DeepSeek API Key
  model: "deepseek-v4-flash"
  base-url: "https://api.deepseek.com"
  timeout-streaming: 60
  timeout-sync: 30
  thinking-enabled: false
  reasoning-effort: "high"

websocket:
  url: "${CCAGENT_WS_URL:ws://localhost:8080/ws}"
  heartbeat-interval: 30
  heartbeat-timeout: 10
  reconnect-initial-delay: 1
  reconnect-max-delay: 60

sync:
  enabled: true
  cloud-url: "${CCAGENT_CLOUD_URL:http://localhost:8080}"
  api-key: "${CCAGENT_CLOUD_API_KEY:Qse2ffed}"
  interval-minutes: 5
  auto-sync: true

cli:
  prompt: "ccAgent> "
  stream-output: true
  color-enabled: true

agent:
  system-prompt: |-
    你是一个智能助手...  # 主 Agent system prompt

http:
  port: 3000

governance:
  allowedPaths:                 # 路径白名单（工作区之外可访问目录）
    - "/home/user/data"
```

### 6.2 关键运行时目录

| 目录/文件 | 用途 |
|-----------|------|
| `.codepal/daemon.lock` | 单实例锁 |
| `.codepal/plugins/` | 插件 jar 目录（隔离加载） |
| `.codepal/skills/` | SKILL.md 技能目录 |
| `.codepal/mcp/` | MCP server 配置目录 |
| `.codepal/staging/` | 回滚快照目录 |
| `event.log` | 事件持久化日志 |
| `workspaces.properties` | 工作区元数据 |

---

## 7. 构建与运行

### 7.1 环境要求

- **JDK 21**（需 `--enable-preview` 运行测试）
- **Maven 3.9+**
- 运行时需配置 `DEEPSEEK_API_KEY` 环境变量

### 7.2 构建命令

```bash
# 编译全部模块
mvn clean compile

# 运行测试
mvn test

# 打包
mvn clean package -DskipTests
```

### 7.3 启动命令

```bash
# 启动 CLI 交互模式
mvn exec:java -pl presentation

# 或直接运行（需 classpath）
java -cp ... com.ccagent.presentation.cli.AgentMain

# 远程守护进程模式
java ... com.ccagent.presentation.cli.AgentMain --background
```

### 7.4 测试覆盖

各模块均有单元测试与集成测试：

| 模块 | 测试重点 |
|------|---------|
| `core` | Dispatcher DAG 调度、SimpleOrchestrator 断点恢复、TaskContext 黑板合并、PolicyAndQuota 治理、SessionStateMachine、SyncService、ToolCallRepairer、SkillLoader |
| `kernel` | SimpleEventBus 队列/限流、PluginManager 隔离加载/热重载 |
| `api` | DTO 序列化 |
| `adapter` | H2 数据库仓库、DatabaseInitializer |
| `presentation` | WS 消息处理器、SeqLockManager 并发、ReconnectManager、端到端集成 |

---

## 8. 关键设计机制索引

| 机制 | 核心类 | 说明 |
|------|--------|------|
| **断点续存** | `SimpleOrchestrator.recoverInterruptedPlans` + `ExecutionPlanRepository` | 重启恢复 RUNNING 计划，跳过 `completed_step_ids`，从磁盘恢复黑板产物 |
| **DAG 调度** | `Dispatcher` | 基于 `affectedFiles` + `reads/writes` 构建依赖图，无依赖并行，循环检测抛 `PlanCycleException` |
| **黑板（Blackboard）** | `TaskScope` / `TaskContextImpl` / `YamlBlackboardSchema` | LangGraph 风格缩略黑板，`append`/`replace`/`merge` 合并策略，基于 CompletableFuture 同步 |
| **产物持久化** | `ArtifactStore` / `FileArtifactStore` | `@Persistent` POJO 自动序列化，>1MB 存路径引用 |
| **事件驱动治理** | `EventBus` + 领域事件 + LISTENER 插件 | 编排层不直接调用治理，通过事件解耦 |
| **整批回滚** | `RollbackManager` | `git hash-object` 或 snapshot 双模备份，`StepFailedEvent` 触发整批还原 |
| **路径安全白名单** | `PolicyEnforcer` + `PathUtil` | "工作区 ∪ 白名单" 放行，多入口统一校验 |
| **CPU 密集分流** | `Dispatcher` | `cpuIntensive: true` 的 Step 走平台线程池，其余走虚拟线程 |
| **插件热插拔** | `PluginHotReloader` + `PluginClassLoader` | WatchService 监听 jar 变化，自动重载 LISTENER 并替换运行时引用 |
| **记忆压缩** | `Compressor` | turns > 30 时调用 LLM 压缩为 summary |
| **云端同步** | `SyncService` / `SyncScheduler` | Manifest diff 增量同步会话/Token 数据 |
| **MCP 外部工具** | `McpClientManager` | 动态注册外部 server 工具为 `mcp_<prefix>_<tool>` |
| **技能体系** | `SkillRegistry` / `SkillLoader` | SKILL.md 注入专家 system prompt |
| **会话串行** | `SessionStateMachine` + `SeqLockManager` | 状态机裁决 + seqId 粒度锁 |

---

## 附录：相关文档

| 文档 | 说明 |
|------|------|
| [README.md](../README.md) | 项目概览与核心功能 |
| [protocol.md](protocol.md) | WebSocket 协议（消息格式、类型、状态流转） |
| [core-mechanisms.md](core-mechanisms.md) | 核心机制：DAG 调度、黑板合并、产物持久化、断点续存、事件治理 |
| [concurrency-model.md](concurrency-model.md) | 会话并发模型：通道/锁/串行三层规约 |
| [structure/structure.md](structure/structure.md) | 模块结构索引 |
