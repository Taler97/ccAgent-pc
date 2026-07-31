# adapter — 适配器层

## 职责

外部系统适配层，实现 api 层定义的端口接口。当前包含 LLM 适配（DeepSeek）和持久化适配（H2 数据库）。

## 位置

`pcAgent/adapter/src/main/java/com/ccagent/adapter/`

## 关键类

### llm — LLM 适配

| 类 | 职责 |
|-----|------|
| `DeepSeekLlmAdapter.java` | DeepSeek 大模型适配器，实现 `LlmPort` 接口 |
| `di/LlmModule.java` | LLM 模块 DI 组装 |

### memory — 持久化适配

| 类 | 职责 |
|-----|------|
| `H2MemoryAdapter.java` | H2 数据库适配器，实现 `MemoryPort` 接口 |
| `di/MemoryModule.java` | 记忆模块 DI 组装 |
| `repository/PreferencesRepository.java` | 用户偏好持久化仓库 |
| `repository/SessionRepository.java` | 会话持久化仓库 |
| `schema/DatabaseInitializer.java` | 数据库 Schema 初始化器（DDL 自动建表） |

## 外部依赖

- H2 Database（嵌入式数据库）
- HikariCP（连接池）

## 依赖关系

- **依赖**: `core`、`api`、`exception`、`infrastructure`
- **被依赖**: `presentation`
