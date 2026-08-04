# presentation — 接入通信层

## 职责

引擎的顶层入口，负责 CLI 交互、WebSocket 远程连接、以及全部 DI 模块的组装。是用户与 PC Agent 引擎之间的桥梁。

## 位置

`pcAgent/presentation/src/main/java/com/ccagent/presentation/`

## 关键类

### cli — CLI 入口

| 类 | 职责 |
|-----|------|
| `AgentMain.java` | **唯一主入口**：默认 CLI 模式、`--background` 远程模式、`--standalone` 旧版兼容；公共启动流程（单实例锁 + 引擎组装） |
| `DaemonLock.java` | **单实例锁**：`.codepal/daemon.lock` 文件锁，保证同一时刻只有一个引擎进程 |
| `StandaloneAgentRunner.java` | **CLI 交互运行器**：主循环、命令处理、流式输出、会话管理、文件浏览、Token 统计 |
| `DependencyGraph.java` | **依赖图表**：协调所有 DI 模块的初始化顺序 |

### di — 依赖注入模块

| 类 | 职责 |
|-----|------|
| `OrchestrationModule.java` | 编排模块 DI 配置（注入 EventBus、Dispatcher 等） |
| `ToolModule.java` | 工具模块 DI 配置（加载 TOOL 插件，注册 ToolRegistry） |
| `ExpertModule.java` | 专家模块 DI 配置（加载 EXPERT 插件，注册 ExpertRegistry） |
| `GovernanceModule.java` | 治理模块 DI 配置（加载 LISTENER 插件，初始化 EventBus 监听器） |
| `GovernanceGateway.java` | 治理网关，对外暴露执行入口 |

### ws — WebSocket 客户端

> 完整消息类型与 Envelope 定义见 [protocol.md](../../protocol.md)。

| 类 | 职责 |
|-----|------|
| `CloudWebSocketClient.java` | 云端 WebSocket 客户端，连接 Cloud Server 接受远程任务调度 |
| `CloudWsSession.java` | WS 会话封装：持有 CloudWebSocketClient 引用并实现 MessageSender 接口 |
| `MessageRouter.java` | **纯分发器**（~81 行，无业务代码）：解析 Envelope → `handlerRegistry` 按 type 查表 → `SeqLockManager` 加锁 → 调用对应 `WsMessageHandler` |
| `SeqLockManager.java` | seqId 粒度锁管理，按请求流水号串行化防止并发 |
| `ReconnectManager.java` | 断线重连管理器（指数退避） |
| `StateManager.java` | 连接状态管理 |

#### handler/ — WS 消息处理器（5 个）

> 每个 Handler 实现 `WsMessageHandler` 接口（`supportedTypes()` + `handle()`），`HandlerSupport` 基类提供 payload 解析、响应构造、状态上报等通用方法。

| Handler | 处理的 type | 分类 | 是否走 LLM |
|---------|-----------|------|-----------|
| `SessionHandler.java` | `CREATE_SESSION` / `LIST_SESSIONS` | CONTROL / QUERY | 否 |
| `ExecutionHandler.java` | `EXECUTE` / `EXECUTE_PLAN` / `CANCEL_PLAN` / `CANCEL_EXECUTION` / `ROLLBACK_DIALOG` / `TOOL_APPROVAL` | INTELLIGENT + CONTROL | EXECUTE ✅ / 其他 ❌ |
| `FileHandler.java` | `FILE_LIST_REQ` / `FILE_READ_REQ` | QUERY | 否 |
| `StatusHandler.java` | `QUERY_PROGRESS` / `SKILL_LIST_REQ` / `MCP_STATUS_REQ` | QUERY | 否 |
| `MiscHandler.java` | `PING` / `ON_KICK` | QUERY / CONTROL | 否 |

> **新增业务流程**：新建 `XxxHandler extends HandlerSupport implements WsMessageHandler` → 在 `MessageRouter` 构造函数里 `register(new XxxHandler(...))` 一行。

### observer — 观察者

| 类 | 职责 |
|-----|------|
| `StatusObserver.java` | 状态观察者，输出执行状态到控制台 |

## 启动模式

| 模式 | 命令 | 说明 |
|------|------|------|
| **CLI 交互** | `java ... AgentMain` | 引擎嵌入进程，命令行直接对话，纯本地不连云端 |
| **远程模式** | `java ... AgentMain --background` | 引擎后台运行，通过 WebSocket 接受云端调度（旧参数 `--headless` 兼容） |
| **旧版兼容** | `java ... AgentMain --standalone` | 保留旧版入口兼容 |

## 依赖关系

- **依赖**: `core`、`adapter`、`api`、`exception`、`infrastructure`、所有插件模块
- **外部依赖**: picocli
