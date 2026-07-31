# plugins — 插件层

## 职责

物理隔离的插件实现模块，通过 `kernel` 的 PluginManager 在运行时热加载。分为三类：工具插件、专家插件、治理监听器插件。

## 位置

`pcAgent/plugins/`（聚合 3 个子模块）

---

## plugin-tools — 工具插件（6 个）

### 定义位置

`pcAgent/plugins/plugin-tools/src/main/resources/META-INF/plugins/`

### 插件列表

| YAML 描述符 | 插件类 | 职责 |
|-------------|--------|------|
| `read-file-tool.yaml` | `ReadFileTool` | 读取文件内容 |
| `write-file-tool.yaml` | `WriteFileTool` | 写入/创建文件 |
| `edit-file-tool.yaml` | `EditFileTool` | 编辑/修改已有文件 |
| `list-directory-tool.yaml` | `ListDirectoryTool` | 列出目录内容 |
| `search-content-tool.yaml` | `SearchContentTool` | 在文件中搜索文本 |
| `file-operation-tool.yaml` | `FileOperationTool` | 文件操作（复制、移动、删除） |

---

## plugin-experts — 专家插件（7 个）

### 定义位置

`pcAgent/plugins/plugin-experts/src/main/resources/META-INF/plugins/`

### 插件列表

| YAML 描述符 | 插件类 | 职责 |
|-------------|--------|------|
| `planner-expert.yaml` | `PlannerExpert` | 计划制定专家：分析需求，生成执行计划 |
| `researcher-expert.yaml` | `ResearcherExpert` | 调研专家：代码/文档调研分析 |
| `writer-expert.yaml` | `WriterExpert` | 编码专家：代码生成与修改 |
| `summarizer-expert.yaml` | `SummarizerExpert` | 总结专家：内容总结 |
| `note-taker-expert.yaml` | `NoteTakerExpert` | 笔记专家：记录关键信息 |
| `organizer-expert.yaml` | `OrganizerExpert` | 组织专家：信息整理归类 |
| `fault-analyzer-expert.yaml` | `FaultAnalyzerExpert` | 故障分析专家：诊断执行失败原因 |

---

## plugin-governance — 治理监听器插件（3 个）

### 定义位置

`pcAgent/plugins/plugin-governance/src/main/resources/META-INF/plugins/`

### 插件列表

| YAML 描述符 | 插件类 | 职责 |
|-------------|--------|------|
| `auditor-listener.yaml` | `Auditor` | 审计日志记录器，监听执行事件并记录审计日志 |
| `token-stats-listener.yaml` | `TokenStatsService` | Token 用量统计，监听 LlmCallCompletedEvent |
| `rollback-manager-listener.yaml` | `RollbackManager` | 回滚管理器，监听 StepFailedEvent 执行回滚 |

---

## 插件类型枚举

| 类型 | 说明 |
|------|------|
| `TOOL` | 工具插件，注册到 ToolRegistry |
| `EXPERT` | 专家插件，注册到 ExpertRegistry |
| `LISTENER` | 监听器插件，注册到 EventBus |

## 依赖关系

- **加载方式**: 运行时通过 `kernel` 的 `SimplePluginManager` 热加载
- **物理隔离**: 各插件子模块独立打包，通过 SPI 方式被 PluginManager 发现
