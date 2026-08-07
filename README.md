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
| **PolicyEnforcer** | 策略执行，校验 Plan 合法性 + 路径白名单校验 |
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

### 路径安全白名单（Path Whitelist）

路径校验采用 **"工作区 ∪ 白名单"** 放行策略：既允许会话工作区（动态白名单）内的所有路径访问，也允许用户声明的、位于工作区之外的关联目录（静态白名单），防止路径穿越的同时保证跨目录协作。

- **动态白名单**：当前会话的工作区根目录，自动放行其下所有路径
- **静态白名单**：用户在配置文件中声明的额外目录（如共享资源库、企业数据目录）

**配置方式**（`~/.code-pal/config.yaml`）：

```yaml
governance:
  allowedPaths:
    - "D:\shared\assets"     # 工作区之外的关联文件夹
    - "/home/user/data"
```

**放行判定**：请求路径规范化后，命中工作区根目录或任一白名单目录（含其子目录）即放行；否则抛出 `SecurityException`（`PATH_TRAVERSAL_DETECTED`）。

**校验入口统一走白名单**：

| 入口 | 覆盖场景 |
|------|---------|
| `PolicyEnforcer.checkPath` | 治理层统一路径校验 |
| `DefaultToolRegistry.executeTool` | 工具调用参数校验（`path` / `source` / `target` 键） |
| `FileOperationTool.validatePath` | 文件操作工具内部校验（copy/move/delete/mkdir/stat） |
| `FileHandler` | 前端文件浏览（FILE_LIST）与文件读取（FILE_READ） |

白名单为进程级启动配置，由 `DependencyGraph` 装配时一次性注入 `PathUtil`，运行时不可变更。

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

### 技能体系（Skill.md）

对齐 Claude Agent Skills 约定，通过 `SKILL.md` 声明技能，为专家注入领域规范。支持目录与单文件两种形式：

```
.codepal/skills/
  ├── writer-enhance/SKILL.md   # 目录形式（name 默认取目录名）
  └── analyzer.md               # 单文件形式（name 默认取文件名）
```

**SKILL.md 格式**（YAML frontmatter + 正文 prompt）：

```markdown
---
name: writer-enhance
description: 写作规范
expert: writer        # 可选：绑定专家
enabled: true         # 可选：默认 true，false 时跳过加载
---
正文 prompt（引擎启动时注入专家的 system prompt）
```

**使用方式：**

1. 在 `.codepal/skills/` 下新建 `<name>/SKILL.md`（或 `<name>.md`），按上述格式填写
2. 重启引擎，`SkillRegistry` 启动时扫描加载（`enabled: false` 或正文为空自动跳过）
3. 绑定专家：自定义专家 [experts/*.yaml](presentation/src/main/java/com/ccagent/presentation/di/CustomExpertLoader.java) 中声明 `skills: [writer-enhance]`，专家启动时经 `SkillRegistry.resolvePrompt()` 将技能正文合并进 system prompt
4. 前端可通过 `SKILL_LIST_REQ` 查询已注册技能列表（名称/描述/绑定专家）

### MCP 外部工具（Model Context Protocol）

通过 MCP 标准协议接入外部工具服务（如 GitHub、数据库、企业私有服务），由 `McpClientManager` 连接并**动态注册**为工具供专家 ReAct 循环调用，无需改代码即可扩展工具集。

**配置**：在 `.codepal/mcp/` 下放置 `<name>.yaml`（参考 [.codepal/mcp/example.yaml](.codepal/mcp/example.yaml)）：

```yaml
name: github
transport: http            # http（Streamable HTTP）；stdio 预留
url: https://mcp.example.com
headers:
  Authorization: "Bearer xxx"
enabled: true              # false 时跳过该 server
toolPrefix: github         # 工具命名前缀，默认取 name
```

**使用方式：**

1. 复制 `example.yaml` 为 `<name>.yaml`，填写真实 `url` / `headers`，`enabled: true`
2. 重启引擎，启动时自动连接（`initialize` + `listTools`），失败仅跳过该 server，不影响引擎
3. 每个远端工具包装为 `mcp_<prefix>_<tool>` 注册进 ToolRegistry，参数 JSON Schema 一并暴露给 LLM 生成调用参数
4. 前端 MCP 面板（云端 `/api/pc/mcp/status` 经 WS 转发）实时展示各 server 连接状态与工具数

**当前边界**：传输支持 `http`（Streamable HTTP）；`stdio` 传输预留（需额外引入 `mcp-transport-stdio` 构件）。

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
| **CLI 交互** | `java ... AgentMain`（无参数） | 引擎嵌入进程，命令行直接输入指令交互，纯本地不连云端 |
| **远程模式** | `java ... AgentMain --background` | 引擎后台运行，通过 WebSocket 连接 Cloud Server 接受远程任务调度（旧参数 `--headless` 兼容） |

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
- **Jackson 2.20**（JSON/YAML 序列化）
- **H2 Database 2.2** + **HikariCP 5.1**（嵌入式持久化）
- **picocli 4.7**（CLI 参数解析）
- **MCP Java SDK 0.18**（Model Context Protocol 客户端）
- **DeepSeek API**（运行时需配置 API Key）
