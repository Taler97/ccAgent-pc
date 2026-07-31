# ccAgent — PC Agent

**常驻本地的 AI 程序员团队引擎。** 一个基于 Java 21 的智能体运行时，采用 **六边形架构 + 微内核 + 事件驱动 + 插件化** 设计，既可独立 CLI 交互，也可作为守护进程通过 WebSocket 接受云端远程调度。

---

## 核心技术栈

| 技术 | 用途 |
|------|------|
| **Java 21** | 虚拟线程（Virtual Threads）实现高并发任务执行 |
| **Maven 多模块** | 8 个子模块，严格单向依赖 |
| **六边形架构** | Ports & Adapters 模式，核心逻辑与外部适配解耦 |
| **微内核** | 自研 EventBus + PluginManager 作为系统内核 |
| **事件驱动** | 组件间通过领域事件通信，治理层通过监听事件实现横切关注点 |
| **插件体系** | 三类插件（TOOL / EXPERT / LISTENER），YAML 声明式注册，运行时热加载 |
| **手工 DI** | 无框架依赖，各模块自维护 DI 模块，通过 DependencyGraph 统一组装 |
| **H2 Database** | 嵌入式数据库，零配置本地持久化 |
| **WebSocket** | 守护进程模式通过 WS 与 Cloud Server 双向通信 |

---

## 架构详解

### 六边形架构（Ports & Adapters）

```
┌─────────────────────────────────────────────────────────────┐
│                   presentation (接入通信层)                    │
│  CLI(AgentMain) · WS(CloudWebSocketClient) · DI(DependencyGraph)
├─────────────────────────────────────────────────────────────┤
│                    adapter (适配器层)                         │
│   LLM(DeepSeekLlmAdapter) · Memory(H2MemoryAdapter)         │
├─────────────────────────────────────────────────────────────┤
│                    core (业务核心层)                          │
│  Orchestration · Governance · ToolEngine · Expert · Sync    │
├─────────────────────────────────────────────────────────────┤
│                    api (接口契约层)                           │
│  Port接口(LlmPort/MemoryPort/OrchestratorPort) · DTO模型    │
├─────────────────────────────────────────────────────────────┤
│              kernel (微内核层)                                │
│   EventBus(SimpleEventBus) · PluginManager(SimplePluginManager)
├────────────────────────┬────────────────────────────────────┤
│  infrastructure        │  exception                          │
│  Config/JsonUtil/FileUtil│  AgentException/ErrorCode          │
├────────────────────────┴────────────────────────────────────┤
│                    plugins (插件层)                           │
│  plugin-tools · plugin-experts · plugin-governance           │
└─────────────────────────────────────────────────────────────┘
```

**依赖方向：** 从下往上单向依赖，上层依赖下层，下层绝不反向依赖上层。

### 微内核（Kernel）

系统的两大核心基础设施：

- **EventBus（SimpleEventBus）**：支持同步/异步发布，事件日志持久化到 `event.log`。内置有界持久化队列 + 丢弃计数与限频告警（队列满时轻微背压 100ms 而非静默丢弃）、可选令牌桶限流（`eventRateLimitPerSecond`，默认关闭）、队列水位与丢弃指标暴露。所有组件间通信通过事件进行，实现松耦合。
- **PluginManager（SimplePluginManager）**：通过 `ClassLoader.getResources()` 扫描 `META-INF/plugins/*.yaml` 描述符，反射加载插件实例，通过 PluginContext 注入核心服务。生成健康检查报告。

**插件隔离与热插拔**：

- **隔离加载**：插件目录（`./.codepal/plugins`）中的 jar 由独立 `PluginClassLoader`（child-first）加载——插件类与内核/其他插件类隔离，类冲突互不影响，`close()` 后类可被 GC 回收。
- **坏插件不击穿内核**：任何插件异常（含 `NoClassDefFoundError` 依赖缺失、`ExceptionInInitializerError` 静态初始化失败）都被捕获并记录到 `PluginLoadReport`，单插件失败不影响其他插件与内核；`init` 失败时自动调用 `shutdown()` 回滚半初始化资源。
- **热插拔**：`PluginHotReloader` 通过 WatchService 监听插件目录 jar 变化（500ms 防抖），自动触发重载——取消旧订阅 → 关闭隔离类加载器 → 重新加载 → 更新引用与注册表，治理监听器（LISTENER）实现运行时热替换。

### 事件驱动架构

核心模块不再直接调用治理组件，而是通过事件发布让治理组件自动响应：

| 事件 | 发布者 | 监听者 | 作用 |
|------|--------|--------|------|
| `ExecutionStartedEvent` | GovernanceGateway | Auditor, TokenStatsService | 记录执行开始 |
| `PlanExecutionStartedEvent` | SimpleOrchestrator | RollbackManager | Plan 执行前触发整批备份（git 检查点/快照） |
| `StepStartedEvent` | Dispatcher | Auditor, RollbackManager | 记录步骤开始 |
| `StepCompletedEvent` | Dispatcher | Auditor | 记录步骤完成 |
| `StepFailedEvent` | Dispatcher | Auditor, RollbackManager | 触发会话级整批回滚或审计 |
| `StepRolledBackEvent` | Dispatcher | Auditor | 记录回滚结果 |
| `LlmCallCompletedEvent` | TokenTrackingLlmProxy | TokenStatsService | 统计 Token 用量 |

### 插件体系

三类扩展点，通过 YAML 声明式注册：

| 类型 | 扩展点接口 | 实例数 | 示例 |
|------|-----------|--------|------|
| **TOOL** | `ToolExtensionPoint` | 6 | ReadFileTool, WriteFileTool, SearchContentTool 等 |
| **EXPERT** | `ExpertExtensionPoint` | 7 | PlannerExpert, WriterExpert, FaultAnalyzerExpert 等 |
| **LISTENER** | `ListenerExtensionPoint` | 3 | Auditor, RollbackManager, TokenStatsService |

插件物理隔离在独立 Maven 模块（`plugin-tools/`、`plugin-experts/`、`plugin-governance/`）中，运行时通过 SimplePluginManager 加载。

### 编排引擎（Orchestration）

- **ChatAgent**：对话入口，处理用户输入
- **Dispatcher**：将 Plan 分解为 Step，按 DAG 依赖图调度 Expert 执行
- **SimpleOrchestrator**：支持 Plan 中断恢复（重启时查询 RUNNING 状态计划，从磁盘恢复）
- **ExecutionPlanRepository**：持久化执行计划，记录 `completed_step_ids` 支持断点续执行

### 任务上下文与产物（TaskScope）

- **TaskScope**：每个执行任务独立作用域，Expert 通过 `getArtifact()` / `putArtifact()` 交换产物
- **ArtifactStore**：`@Persistent` 注解的 POJO 自动序列化到磁盘，大文件（>1MB）存储路径引用
- **Blackboard**：全局黑板，支持 `append` / `replace` 合并策略，键值对持久化到文件

### 治理层（Governance）

| 组件 | 职责 |
|------|------|
| **PolicyEnforcer** | 策略执行，校验 Plan 合法性 |
| **TokenQuotaService** | Token 配额管理，防止超额 |
| **PlanSanitizer** | Plan 安全清洗 |
| **ExecutionIsolator** | 执行隔离（NoOp/沙箱模式） |
| **Auditor** | 审计日志记录 |
| **RollbackManager** | 步骤回滚管理 |

---

## 核心功能

### 断点续存（Plan Recovery）

引擎意外中断后，重启时自动恢复未完成的执行计划。

- **恢复机制**：`SimpleOrchestrator` 启动时查询 `RUNNING` 状态的执行计划，从磁盘恢复 ArtifactStore 和 Blackboard 数据
- **跳过已完成的步骤**：`ExecutionPlanRepository` 持久化已完成的步骤 ID（`completed_step_ids`），恢复时跳过，从断点继续执行
- **确定性 Key 路径**：Blackboard 产物存储路径使用 `sessionId_version` 作为确定性 Key

### 任务上下文与产物交换（TaskScope & ArtifactStore）

每个执行任务拥有独立的作用域，专家之间通过产物解耦交换数据。

- **TaskScope**：任务级作用域，管理 `CompletableFuture` 同步 + `computeIfAbsent` 保证单次计算
- **ArtifactStore**：`@Persistent` 注解的 POJO 自动序列化到磁盘，大文件（>1MB）仅存储路径引用
- **Blackboard**：全局键值黑板，支持 `append`（追加）和 `replace`（替换）两种合并策略，持久化到文件用于断点恢复

### 事件驱动治理

核心编排层不直接调用治理组件，而是通过事件发布让治理层自动响应。

| 场景 | 事件链路 |
|------|---------|
| **步骤失败自动回滚** | `StepFailedEvent` → RollbackManager 触发逆向回滚 |
| **LLM 调用自动计费** | `LlmCallCompletedEvent` → TokenStatsService 统计 Token 用量 |
| **全流程审计** | `ExecutionStartedEvent` / `StepStartedEvent` / `StepCompletedEvent` → Auditor 写入审计日志 |

### DAG 依赖调度

Dispatcher 按 DAG 依赖图调度步骤执行，支持文件级和 Key 级别的依赖关系。

- **文件依赖**：Step A 的输出文件是 Step B 的输入
- **Key 依赖**：Step A 写入 Blackboard 的 Key 是 Step B 读取的 Key
- **循环检测**：检测到 Key 级循环依赖时抛出 `PlanCycleException`
- **并行执行**：无依赖关系的 Step 可并发执行

### 回滚机制

支持**会话级整批回滚**，RollbackManager 监听 `StepFailedEvent` 触发还原（与 Trae/Claude Code 对齐，规避并行步骤的文件交叉污染）。

- **双模备份策略**（`rollbackProvider`，默认 auto）：
  - `git`：工作区是 git 仓库时，执行前用 `git hash-object -w` 将受影响文件写入 git 对象库（blob 检查点），回滚时 `git cat-file blob` 原样还原。零 index/分支历史污染，无引用对象由 git gc 回收。
  - `snapshot`：文件快照到 `.codepal/staging/`（非 git 仓库环境的回退）。
  - `auto`：检测工作区是否为 git 仓库，是则 git，否则 snapshot。
- 任一步骤失败 → 整批还原所有受影响文件（成功步骤的修改一并还原，幂等）
- 备份时机：Plan 执行前一次完成（`PlanExecutionStartedEvent`），步骤事件零 IO，不占执行线程池
- 每个 `StepRolledBackEvent` 记录回滚结果
- 回滚策略通过事件驱动，不侵入编排核心逻辑

### 双向同步（Cloud Sync）

与 Cloud Server 的双向数据同步能力：

- **SyncScheduler**：定时触发同步任务
- **SyncClient**：通过 HTTP API 与 Cloud Server 交换数据清单（Manifest）
- **SyncDiff**：计算本地与云端的差异，仅同步增量数据
- **LocalDataRepository**：本地数据仓库，提供会话、偏好、对话记录的 CRUD

### 插件热加载

三类插件通过 YAML 声明式注册，运行时由 PluginManager 统一加载：

- **TOOL 插件**（6 个）：文件读写、搜索、目录操作等工具
- **EXPERT 插件**（7 个）：Planner、Writer、Researcher 等 AI 专家角色
- **LISTENER 插件**（3 个）：Auditor、RollbackManager、TokenStatsService（治理监听器单实例，统一由插件通道产出）

启动时生成**健康检查报告**，汇总扫描数、成功/失败数和分类列表。

**隔离与热插拔**：插件目录（`./.codepal/plugins`）中的 jar 由独立 `PluginClassLoader` 隔离加载（类冲突隔离、可卸载）；坏插件（依赖缺失/init 异常）被隔离记录不击穿内核，init 失败自动调用 `shutdown()` 回滚半初始化资源；`PluginHotReloader` 监听插件目录 jar 变化（WatchService + 防抖），自动热重载 LISTENER 治理插件并替换运行时引用。

### 虚拟线程并发 + CPU 密集分流

使用 Java 21 虚拟线程（Virtual Threads）实现高并发任务调度，并针对 CPU 密集任务提供线程模型分流：

- `ChatAgent` 使用虚拟线程处理每个对话请求
- `Dispatcher` 使用虚拟线程并发调度无依赖的 Step（`Executors.newVirtualThreadPerTaskExecutor`）
- `TaskContextImpl` 使用 `CompletableFuture` 实现跨线程的产物同步

**线程模型分流机制**：每个 Step 可声明 `cpuIntensive` 标志：

| Step 类型 | 执行池 | 适用场景 |
|-----------|--------|---------|
| IO 密集（默认） | 虚拟线程 | LLM 调用、文件读写、等待黑板产物 |
| CPU 密集（`cpuIntensive: true`） | 平台线程池（大小 = CPU 核数） | 代码扫描、正则匹配、复杂计算 |

虚拟线程不加速 CPU 密集计算，且会抢占有限的 carrier 线程影响其他 IO 任务，因此 CPU 密集步骤被分流到独立的平台线程池执行，两池通过 `CompletableFuture.allOf()` 统一等待批次完成。

---

## 模块一览

| 模块 | artifactId | 层级 | 说明 |
|------|-----------|------|------|
| [exception](docs/structure/structure/exception.md) | `exception` | 异常层 | 统一错误码 + 异常类体系，零外部依赖 |
| [infrastructure](docs/structure/structure/infrastructure.md) | `infrastructure` | 基础设施层 | 配置加载、JSON/文件工具、日志 |
| [kernel](docs/structure/structure/kernel.md) | `kernel` | 微内核层 | EventBus、PluginManager、扩展点机制 |
| [api](docs/structure/structure/api.md) | `api` | 接口契约层 | Port 接口 + DTO 模型（模块间契约） |
| [core](docs/structure/structure/core.md) | `core` | 业务核心层 | 编排、治理、工具注册、同步、事件 |
| [adapter](docs/structure/structure/adapter.md) | `adapter` | 适配器层 | LLM（DeepSeek）、持久化（H2） |
| [plugins](docs/structure/structure/plugins.md) | `plugins` | 插件层 | 3 个子模块：6 工具 + 7 专家 + 3 治理监听器 |
| [presentation](docs/structure/structure/presentation.md) | `presentation` | 接入通信层 | CLI 入口、WebSocket 客户端、DI 组装 |

---

## 启动模式

| 模式 | 命令 | 说明 |
|------|------|------|
| **CLI 交互** | `java ... AgentMain`（无参数） | 引擎嵌入进程，命令行直接输入指令交互 |
| **守护进程** | `java ... AgentMain --headless` | 后台运行，通过 WebSocket 连接 Cloud Server 接受远程任务调度 |

---

## 快速开始

```bash
# 编译全部模块
mvn clean compile

# 运行测试
mvn test

# 启动 CLI 交互模式
mvn exec:java -pl presentation
```

---

## 外部接口

| 文档 | 说明 | 面向 |
|------|------|------|
| [docs/protocol.md](docs/protocol.md) | WebSocket 协议（消息格式、类型、状态流转） | Cloud Server |

---

## 技术依赖

- **Java 21**（虚拟线程、模式匹配、Records）
- **Maven 3.9+**
- **Jackson 2.17**（JSON/YAML 序列化）
- **H2 Database 2.2** + **HikariCP 5.1**（嵌入式持久化）
- **picocli 4.7**（CLI 参数解析）
- **DeepSeek API**（运行时需配置 API Key）
