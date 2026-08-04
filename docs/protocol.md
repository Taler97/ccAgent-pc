# WebSocket 协议 — pcAgent 侧

> 本文档供 **cloud-server** 端查阅。pcAgent 作为 WebSocket 客户端主动连接 cloud-server。
>
> 完整消息处理流水线参见 [presentation.md](../structure/structure/presentation.md#ws--websocket-%E5%AE%A2%E6%88%B7%E7%AB%AF) 中的 ws 章节。

## 连接方式

```
ws://{cloud-server}:8080/ws/pc?api_key={key}&deviceId={id}
```

- `api_key`：鉴权密钥，需与 cloud-server `application.yml` 中的 `app.api-key` 一致
- `deviceId`：设备标识，用于服务端识别和管理连接

---

## Envelope 格式

所有 WebSocket 消息使用统一的 Envelope 格式：

```json
{
  "type": "操作码",
  "seqId": "流水号",
  "timestamp": "2026-08-05T10:30:00Z",
  "payload": { ... } | "预序列化 JSON 字符串"
}
```

---

## Envelope 处理流水线

```
cloud-server 发 WebSocket 帧
        ↓
CloudWebSocketClient.onText()  分片拼接 → 完整 JSON 字符串
        ↓
MessageRouter.route()          纯分发：
        │                       ├─ JsonUtil.fromJson() 解析 Envelope
        │                       ├─ handlerRegistry.get(type) 查表
        │                       └─ SeqLockManager.withLock(seqId) 加锁
        ↓
具体 Handler.handle()          业务入口（5 类之一）
        │
        ├─ SessionHandler      会话管理
        ├─ ExecutionHandler    执行控制（唯一走 LLM）
        ├─ FileHandler         文件操作
        ├─ StatusHandler       状态查询
        └─ MiscHandler         杂项
```

---

## pcAgent 接收的消息（来自 cloud-server）

### SessionHandler（会话管理 · 纯状态）

> 对应 Handler 代码：[SessionHandler.java](../presentation/src/main/java/com/ccagent/presentation/ws/handler/SessionHandler.java)

#### CREATE_SESSION

创建/激活会话上下文，将 sessionId 映射到工作区根路径。由前端切换对话时的 `POST /api/sessions/{id}/activate` 触发。

```json
{
  "type": "CREATE_SESSION",
  "seqId": "act-xxxx",
  "payload": {
    "sessionId": "session-xxx",
    "rootPath": "C:\\projects\\my-app"
  }
}
```

#### LIST_SESSIONS

列出 PC 端内存中的会话列表。

```json
{
  "type": "LIST_SESSIONS",
  "seqId": "ls-xxxx",
  "payload": {}
}
```

响应通过 `SESSION_LIST_RES` 返回。

---

### ExecutionHandler（执行控制）

> 对应 Handler 代码：[ExecutionHandler.java](../presentation/src/main/java/com/ccagent/presentation/ws/handler/ExecutionHandler.java)
>
> 仅 `EXECUTE` 和 `EXECUTE_PLAN` 的规划阶段走 LLM。其余均为纯控制命令。

#### EXECUTE ⚡ 走 LLM

下发用户指令，触发 LLM 规划 + 执行。由 `POST /api/execute` 触发。

```json
{
  "type": "EXECUTE",
  "seqId": "exec-xxxx",
  "payload": {
    "sessionId": "session-xxx",
    "prompt": "用户输入的指令"
  }
}
```

#### EXECUTE_PLAN

用户确认 Plan，开始执行步骤。由 `POST /api/execute/plan` 且 `confirmed=true` 触发。

```json
{
  "type": "EXECUTE_PLAN",
  "seqId": "exec-xxxx",
  "payload": {
    "confirmed": true
  }
}
```

#### CANCEL_PLAN

拒绝 Plan，丢弃待执行计划。由 `POST /api/execute/plan` 且 `confirmed=false` 触发。

```json
{
  "type": "CANCEL_PLAN",
  "seqId": "exec-xxxx",
  "payload": {
    "confirmed": false
  }
}
```

#### CANCEL_EXECUTION

取消正在执行的任务。由 `POST /api/execute/cancel` 触发。

```json
{
  "type": "CANCEL_EXECUTION",
  "seqId": "exec-xxxx",
  "payload": {}
}
```

#### ROLLBACK_DIALOG

回滚指定执行的文件变更。由 `POST /api/execute/rollback` 触发。

```json
{
  "type": "ROLLBACK_DIALOG",
  "seqId": "exec-xxxx",
  "payload": {}
}
```

#### TOOL_APPROVAL

工具审批结果。由前端审批面板提交后云端下发。

```json
{
  "type": "TOOL_APPROVAL",
  "seqId": "exec-xxxx",
  "payload": {
    "approved": true
  }
}
```

---

### FileHandler（文件操作 · 纯查询）

> 对应 Handler 代码：[FileHandler.java](../presentation/src/main/java/com/ccagent/presentation/ws/handler/FileHandler.java)
>
> 使用 `sessionId` 反查工作区根路径，对路径做沙箱校验（必须在工作区下）。

#### FILE_LIST_REQ

请求工作区文件列表。由 `GET /api/pc/files/list` 触发。

```json
{
  "type": "FILE_LIST_REQ",
  "seqId": "fl-xxxx",
  "payload": {
    "sessionId": "session-xxx",
    "path": "/src/main"
  }
}
```

- `path` 可选，默认为 `/`（工作区根）
- 响应通过 `FILE_LIST_RES` 返回

#### FILE_READ_REQ

请求读取工作区单个文件。由 `GET /api/pc/files/read` 触发。

```json
{
  "type": "FILE_READ_REQ",
  "seqId": "fr-xxxx",
  "payload": {
    "sessionId": "session-xxx",
    "path": "/src/Main.java"
  }
}
```

- 响应通过 `FILE_READ_RES` 返回
- 路径越出工作区根 → `PATH_OUTSIDE_WORKSPACE`
- 文件不存在 → `FILE_NOT_FOUND`

---

### StatusHandler（状态查询 · 纯查询）

> 对应 Handler 代码：[StatusHandler.java](../presentation/src/main/java/com/ccagent/presentation/ws/handler/StatusHandler.java)

#### QUERY_PROGRESS

查询指定 seqId 的实时进度快照（各子步骤状态 + 活动描述）。

```json
{
  "type": "QUERY_PROGRESS",
  "seqId": "exec-xxxx",
  "payload": {}
}
```

响应通过 `PROGRESS_RESP` 返回。

#### SKILL_LIST_REQ

请求 PC 端已注册的 skill 列表（`.codepal/skills/*.md`）。由 `GET /api/pc/skills/list` 触发。

```json
{
  "type": "SKILL_LIST_REQ",
  "seqId": "skl-xxxx",
  "payload": {}
}
```

响应通过 `SKILL_LIST_RES` 返回。

#### MCP_STATUS_REQ

请求 PC 端 MCP server 连接状态。由 `GET /api/pc/mcp/status` 触发。

```json
{
  "type": "MCP_STATUS_REQ",
  "seqId": "mcp-xxxx",
  "payload": {}
}
```

响应通过 `MCP_STATUS_RES` 返回。

---

### MiscHandler（杂项）

> 对应 Handler 代码：[MiscHandler.java](../presentation/src/main/java/com/ccagent/presentation/ws/handler/MiscHandler.java)

#### PING

连通性测试。由 `POST /api/execute/ping` 触发。`sendToPcAndWait` 同步等待 PONG 响应（5s 超时）。

```json
{
  "type": "PING",
  "seqId": "ping-xxxx",
  "payload": {
    "pingAt": 1722840000000
  }
}
```

响应通过 `PONG` 返回。

#### ON_KICK

旧连接被踢出通知。服务端检测到同一 deviceId 新建连接时发给旧连接。收到后只打日志，连接会被关闭。

```json
{
  "type": "ON_KICK",
  "seqId": "kick-xxxx",
  "payload": {}
}
```

---

## pcAgent 发送的消息（发给 cloud-server）

### 状态与事件

#### AGENT_STATUS

Agent 状态更新，cloud-server 可据此了解 pcAgent 当前状态并做 SSE 缓存。

```json
{
  "type": "AGENT_STATUS",
  "seqId": "seq-xxx",
  "payload": {
    "agentState": "IDLE | PLANNING | EXECUTING | ROLLBACK | ERROR",
    "currentOperation": "当前操作描述"
  }
}
```

#### PLAN_READY

Plan 生成完毕，等待用户确认。需要前端弹确认面板。

```json
{
  "type": "PLAN_READY",
  "seqId": "seq-xxx",
  "payload": {
    "summary": "执行目标描述",
    "seqId": "seq-xxx",
    "steps": [
      { "id": 1, "expertName": "planner", "task": "步骤描述", "affectedFiles": ["src/xxx.java"] }
    ]
  }
}
```

#### STEP_STATUS

步骤执行状态变更。

```json
{
  "type": "STEP_STATUS",
  "seqId": "seq-xxx",
  "payload": {
    "stepId": 1,
    "totalSteps": 3,
    "expertName": "writer",
    "task": "任务描述",
    "status": "RUNNING | SUCCESS | FAILED | ROLLED_BACK | CANCELLED",
    "timestamp": "2026-01-01T00:00:00Z"
  }
}
```

#### EXECUTION_PROGRESS

执行进度更新（流式，高频率）。

```json
{
  "type": "EXECUTION_PROGRESS",
  "seqId": "seq-xxx",
  "payload": {
    "agentState": "EXECUTING",
    "currentOperation": "执行步骤描述",
    "progress": 66,
    "stepProgress": { "current": 2, "total": 3 }
  }
}
```

#### EXECUTION_RESULT

执行完成（成功或失败）。云端 SSE 推完后会关闭连接，并持久化到历史记录。

```json
{
  "type": "EXECUTION_RESULT",
  "seqId": "seq-xxx",
  "payload": {
    "success": true,
    "message": "执行完成信息",
    "affectedFiles": ["src/xxx.java"],
    "tokensUsed": 12500,
    "cachedTokens": 8200,
    "cacheHitRate": 0.656,
    "estimatedCost": 0.00042
  }
}
```

#### ERROR

执行错误报告。

```json
{
  "type": "ERROR",
  "seqId": "seq-xxx",
  "payload": {
    "error": "错误信息"
  }
}
```

#### ROLLBACK_RESULT

回滚结果。

```json
{
  "type": "ROLLBACK_RESULT",
  "seqId": "seq-xxx",
  "payload": {
    "status": "SUCCESS | FAILED"
  }
}
```

---

### 查询响应（同步往返）

#### PROGRESS_RESP

对 `QUERY_PROGRESS` 的响应。**内存态，任务结束后销毁**，此时返回空结构。

```json
{
  "type": "PROGRESS_RESP",
  "seqId": "exec-xxxx",
  "payload": {
    "seqId": "exec-xxxx",
    "totalSteps": 11,
    "completedSteps": 6,
    "overallProgress": 54,
    "steps": [
      {
        "stepId": 8,
        "expertName": "writer",
        "task": "执行全量测试",
        "status": "RUNNING",
        "activity": "正在执行命令: mvn test",
        "elapsedMs": 180000
      }
    ]
  }
}
```

- `activity`：步骤内部实时动作（活动层），由专家通过 `ACTIVITY` 事件上报，不持久化
- `elapsedMs`：当前步骤已运行毫秒数，任务结束后返回 0

#### FILE_LIST_RES

对 `FILE_LIST_REQ` 的响应。

```json
{
  "type": "FILE_LIST_RES",
  "seqId": "fl-xxxx",
  "payload": {
    "files": [
      { "name": "src", "path": "/src", "type": "directory" },
      { "name": "pom.xml", "path": "/pom.xml", "type": "file" }
    ]
  }
}
```

错误时 `error` 字段替换 `files`：`{ "files": [], "error": "INVALID_PATH | PC_OFFLINE | ..." }`

#### FILE_READ_RES

对 `FILE_READ_REQ` 的响应。

```json
{
  "type": "FILE_READ_RES",
  "seqId": "fr-xxxx",
  "payload": {
    "path": "/src/Main.java",
    "content": "public class Main { ... }",
    "size": 1024
  }
}
```

错误时：`{ "error": "PATH_OUTSIDE_WORKSPACE | FILE_NOT_FOUND | ..." }`

#### SKILL_LIST_RES

对 `SKILL_LIST_REQ` 的响应。

```json
{
  "type": "SKILL_LIST_RES",
  "seqId": "skl-xxxx",
  "payload": {
    "skills": [
      { "name": "writer", "description": "撰写、润色和改写文字内容", "expert": "writer" },
      { "name": "researcher", "description": "信息检索、资料分析", "expert": "researcher" }
    ]
  }
}
```

#### MCP_STATUS_RES

对 `MCP_STATUS_REQ` 的响应。

```json
{
  "type": "MCP_STATUS_RES",
  "seqId": "mcp-xxxx",
  "payload": {
    "servers": [
      { "name": "github", "connected": true, "toolCount": 5 },
      { "name": "internal-db", "connected": false, "toolCount": 0, "error": "Connection refused" }
    ]
  }
}
```

- `connected`：该 server 是否连接成功；失败时该 server 被跳过，不影响引擎启动
- `toolCount`：注册的工具数（`mcp_<prefix>_<tool>`）
- `error`：仅连接失败时存在，为失败原因

#### SESSION_LIST_RES

对 `LIST_SESSIONS` 的响应。

```json
{
  "type": "SESSION_LIST_RES",
  "seqId": "ls-xxxx",
  "payload": {
    "sessions": [
      {
        "sessionId": "sess-xxxx",
        "rootPath": "C:\\projects\\my-app",
        "totalTurns": 5,
        "summary": "对话摘要",
        "createdAt": "2026-08-01T10:00:00Z",
        "updatedAt": "2026-08-04T10:30:00Z"
      }
    ]
  }
}
```

#### PONG

对 `PING` 的响应。

```json
{
  "type": "PONG",
  "seqId": "ping-xxxx",
  "payload": {
    "pong": true
  }
}
```

---

## 状态流转

```
IDLE → PLANNING → (PLAN_READY) → 等待确认 → EXECUTING → IDLE
                                                      ↘ FAILED → IDLE
```

- pcAgent 启动后发送 `AGENT_STATUS(IDLE)`
- 收到 `EXECUTE` 后进入 `PLANNING`，完成后发送 `PLAN_READY`
- 收到 `EXECUTE_PLAN` 后进入 `EXECUTING`
- 执行完成发送 `EXECUTION_RESULT` 后回到 `IDLE`
- 执行失败发送 `ERROR` 后回到 `IDLE`

---

## Handler · Type · 分类速查

| Envelope type | 方向 | Handler | 分类 | 是否走 LLM |
|---------------|------|---------|------|-----------|
| `CREATE_SESSION` | Cloud→PC | SessionHandler | CONTROL | 否 |
| `LIST_SESSIONS` | Cloud→PC | SessionHandler | QUERY | 否 |
| `EXECUTE` | Cloud→PC | ExecutionHandler | **INTELLIGENT** | **是** |
| `EXECUTE_PLAN` | Cloud→PC | ExecutionHandler | INTELLIGENT（续执行） | 规划后 |
| `CANCEL_PLAN` | Cloud→PC | ExecutionHandler | CONTROL | 否 |
| `CANCEL_EXECUTION` | Cloud→PC | ExecutionHandler | CONTROL | 否 |
| `ROLLBACK_DIALOG` | Cloud→PC | ExecutionHandler | CONTROL | 否 |
| `TOOL_APPROVAL` | Cloud→PC | ExecutionHandler | CONTROL | 否 |
| `FILE_LIST_REQ` | Cloud→PC | FileHandler | QUERY | 否 |
| `FILE_READ_REQ` | Cloud→PC | FileHandler | QUERY | 否 |
| `QUERY_PROGRESS` | Cloud→PC | StatusHandler | QUERY | 否 |
| `SKILL_LIST_REQ` | Cloud→PC | StatusHandler | QUERY | 否 |
| `MCP_STATUS_REQ` | Cloud→PC | StatusHandler | QUERY | 否 |
| `PING` | Cloud→PC | MiscHandler | QUERY | 否 |
| `ON_KICK` | Cloud→PC | MiscHandler | CONTROL | 否 |

> 云端 REST API 与 Envelope type 的对照见 cloud-server 的 [api.md](../../cloud-server/docs/api.md#三handler-与端点映射速查) 第三章速查表。
