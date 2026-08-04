# WebSocket 协议 — pcAgent 侧

> 本文档供 **cloud-server** 端查阅。pcAgent 作为 WebSocket 客户端主动连接 cloud-server。

## 连接方式

```
ws://{cloud-server}:8080/ws/pc?api_key={key}&deviceId={id}
```

- `api_key`：鉴权密钥，需与 cloud-server `application.yml` 中的 `app.api-key` 一致
- `deviceId`：设备标识，用于服务端识别和管理连接

---

## pcAgent 接收的消息（来自 cloud-server）

所有消息使用 JSON **Envelope** 格式：`{ type, seqId, payload }`

### EXECUTE

下发执行请求。

```json
{
  "type": "EXECUTE",
  "seqId": "req-xxxx",
  "payload": {
    "sessionId": "session-xxx",
    "prompt": "用户输入的指令"
  }
}
```

### CONFIRM_PLAN

用户确认 Plan，开始执行。

```json
{
  "type": "CONFIRM_PLAN",
  "seqId": "req-xxxx",
  "payload": {}
}
```

### REJECT_PLAN

用户拒绝 Plan。

```json
{
  "type": "REJECT_PLAN",
  "seqId": "req-xxxx",
  "payload": {}
}
```

### CANCEL

取消正在执行的任务。

```json
{
  "type": "CANCEL",
  "seqId": "req-xxxx",
  "payload": {}
}
```

### ROLLBACK

回滚指定执行。

```json
{
  "type": "ROLLBACK",
  "seqId": "req-xxxx",
  "payload": {}
}
```

### QUERY_PROGRESS

查询指定执行的实时进度快照（供前端轮询展示各子任务进度与活动）。

```json
{
  "type": "QUERY_PROGRESS",
  "seqId": "req-xxxx",
  "payload": {}
}
```

响应通过 `PROGRESS_RESP` 消息返回（见下文）。

### MCP_STATUS_REQ

查询 PC 端 MCP server 连接状态（供前端 MCP 面板展示）。

```json
{
  "type": "MCP_STATUS_REQ",
  "seqId": "req-xxxx",
  "payload": {}
}
```

响应通过 `MCP_STATUS_RES` 消息返回（见下文）。

---

## pcAgent 发送的消息（发给 cloud-server）

### AGENT_STATUS

Agent 状态更新，cloud-server 可据此了解 pcAgent 当前状态。

```json
{
  "type": "AGENT_STATUS",
  "seqId": "seq-xxx",
  "payload": {
    "agentState": "IDLE | PLANNING | EXECUTING",
    "currentOperation": "当前操作描述"
  }
}
```

### PLAN_READY

Plan 生成完毕，等待用户确认。

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

### STEP_STATUS

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

### EXECUTION_PROGRESS

执行进度更新。

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

### PROGRESS_RESP

对 `QUERY_PROGRESS` 的响应：各步骤状态（PENDING / RUNNING / SUCCESS / FAILED / CANCELLED）与活动描述。**数据为内存态，任务结束后销毁**，此时返回空结构。

```json
{
  "type": "PROGRESS_RESP",
  "seqId": "seq-xxx",
  "payload": {
    "seqId": "seq-xxx",
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

### MCP_STATUS_RES

对 `MCP_STATUS_REQ` 的响应：PC 端各 MCP server 的连接状态与已注册工具数。

```json
{
  "type": "MCP_STATUS_RES",
  "seqId": "seq-xxx",
  "payload": {
    "servers": [
      {
        "name": "github",
        "connected": true,
        "toolCount": 5
      },
      {
        "name": "internal-db",
        "connected": false,
        "toolCount": 0,
        "error": "Connection refused"
      }
    ]
  }
}
```

- `connected`：该 server 是否连接成功；失败时该 server 被跳过，不影响引擎启动
- `toolCount`：注册的工具数（`mcp_<prefix>_<tool>`）
- `error`：仅连接失败时存在，为失败原因

### EXECUTION_RESULT

执行完成结果。

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

### ERROR

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

### ROLLBACK_RESULT

回滚结果。

```json
{
  "type": "ROLLBACK_RESULT",
  "seqId": "seq-xxx",
  "payload": {
    "success": true,
    "message": "回滚完成"
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
- 收到 `CONFIRM_PLAN` 后进入 `EXECUTING`
- 执行完成发送 `EXECUTION_RESULT` 后回到 `IDLE`
- 执行失败发送 `ERROR` 后回到 `IDLE`
