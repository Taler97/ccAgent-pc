# api — 接口契约层

## 职责

定义模块间的**端口接口**（Port）和**数据传输对象**（DTO）。作为六边形架构的核心契约层，所有跨模块调用必须通过此层定义的接口。

## 位置

`pcAgent/api/src/main/java/com/ccagent/api/`

## 关键类

### port — 端口接口

| 接口 | 职责 |
|------|------|
| `LlmPort.java` | LLM 服务端口，定义大模型调用契约 |
| `MemoryPort.java` | 记忆存储端口（会话、偏好、上下文） |
| `ToolRegistryPort.java` | 工具注册端口 |
| `McpPort.java` | MCP 协议端口 |
| `Expert.java` | 专家角色接口（旧版 execute + 新版 executeWithScope） |
| `ExpertRegistryPort.java` | 专家注册端口 |
| `OrchestratorPort.java` | 编排器端口（执行 + 会话管理） |
| `StreamObserver.java` | 流式观察者接口（onNext/onError/onComplete） |
| `SafeStreamObserver.java` | 安全流式观察者封装（异常保护） |

### context — 上下文

| 类 | 职责 |
|---|------|
| `TaskScope.java` | 任务作用域上下文，管理专家执行的生命周期与产物交换 |

### model — 数据模型

| 子包 | 关键类 |
|------|--------|
| `envelope/` | `Envelope.java` — 消息信封/通用包裹 |
| `mcp/` | `McpResource.java`, `McpTool.java` |
| `memory/` | `ConversationTurn.java`, `SessionInfo.java`, `StructuredContext.java`, `UserPreferences.java` |
| `plan/` | `Plan.java`, `Step.java` |
| `request/` | `ChatRequest.java`, `ExecuteRequest.java`, `ToolCall.java` |
| `response/` | `ChatResponse.java`, `ExecutionResult.java`, `FileNode.java`, `StreamChunk.java` |
| `tool/` | `ToolDefinition.java` |

## 依赖关系

- **依赖**: `exception`、`kernel`
- **被依赖**: `core` → `adapter` → `presentation`
