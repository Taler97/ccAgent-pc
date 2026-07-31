# presentation — 接入通信层

## 职责

引擎的顶层入口，负责 CLI 交互、WebSocket 远程连接、以及全部 DI 模块的组装。是用户与 PC Agent 引擎之间的桥梁。

## 位置

`pcAgent/presentation/src/main/java/com/ccagent/presentation/`

## 关键类

### cli — CLI 入口

| 类 | 职责 |
|-----|------|
| `AgentMain.java` | **统一主入口**：默认 CLI 模式、`--headless` 守护模式、`--standalone` 旧版兼容 |
| `StandaloneAgentRunner.java` | **CLI 交互运行器**：主循环、命令处理、流式输出、会话管理、文件浏览、Token 统计 |
| `AgentDaemon.java` | **守护进程管理器**：无 HTTP 的后台运行模式，通过 WebSocket 接受远程调度 |
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

| 类 | 职责 |
|-----|------|
| `CloudWebSocketClient.java` | 云端 WebSocket 客户端，连接 Cloud Server 接受远程任务调度 |
| `MessageRouter.java` | 消息路由分发器，将 WS 消息路由到对应的处理器 |
| `ReconnectManager.java` | 断线重连管理器（指数退避） |
| `StateManager.java` | 连接状态管理 |

### observer — 观察者

| 类 | 职责 |
|-----|------|
| `StatusObserver.java` | 状态观察者，输出执行状态到控制台 |

## 启动模式

| 模式 | 命令 | 说明 |
|------|------|------|
| **CLI 交互** | `java ... AgentMain` | 引擎嵌入进程，命令行直接对话 |
| **守护进程** | `java ... AgentMain --headless` | 引擎后台运行，通过 WebSocket 接受调度 |
| **旧版兼容** | `java ... AgentMain --standalone` | 保留旧版入口兼容 |

## 依赖关系

- **依赖**: `core`、`adapter`、`api`、`exception`、`infrastructure`、所有插件模块
- **外部依赖**: picocli
