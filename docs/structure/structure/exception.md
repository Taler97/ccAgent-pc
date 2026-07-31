# exception — 异常层

## 职责

提供统一的错误码 + 异常类体系。**零外部依赖**，是所有模块的基础依赖。

## 位置

`pcAgent/exception/src/main/java/com/ccagent/exception/`

## 关键类

| 包 | 类 | 职责 |
|---|-----|------|
| `base/` | `AgentException.java` | 自定义运行时异常基类 |
| `base/` | `ErrorCode.java` | 统一错误码枚举 |
| `llm/` | `LlmException.java` | LLM 调用相关异常（超时、拒绝、格式错误等） |
| `tool/` | `ToolException.java` | 工具调用相关异常（执行失败、参数错误等） |
| `plan/` | `PlanCycleException.java` | 计划/步骤编排异常（循环依赖、状态异常等） |
| `governance/` | `GovernanceException.java` | 治理策略异常（配额超限、策略拒绝等） |
| `security/` | `SecurityException.java` | 安全相关异常（鉴权失败、越权访问等） |
| `workspace/` | `WorkspaceException.java` | 工作区操作异常（路径越界、权限不足等） |
| `approval/` | `ApprovalException.java` | 审批流程异常（审批拒绝、超时未审批等） |

## 依赖关系

- **被依赖**: `infrastructure` → `kernel` → `api` → `core` → `adapter` → `presentation`
- **自身依赖**: 无（零内部依赖）
