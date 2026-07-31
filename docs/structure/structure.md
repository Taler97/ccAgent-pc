# pcAgent 模块结构

## 概述

pcAgent 是 ccAgent 项目的 PC 端核心产品，一个常驻本地的 AI 智能体引擎。采用六边形架构 + 微内核 + 插件化设计。

**依赖方向**（从下往上单向依赖）：

```
presentation → adapter → core → api → kernel → infrastructure / exception
```

## 模块清单

| 模块 | artifactId | 层级 | 说明 |
|------|-----------|------|------|
| exception | `exception` | 异常层 | 统一错误码 + 异常类体系，零外部依赖 |
| infrastructure | `infrastructure` | 基础设施层 | 配置加载、JSON/文件工具、日志 |
| kernel | `kernel` | 微内核层 | EventBus、PluginManager、扩展点机制 |
| api | `api` | 接口契约层 | Port 接口 + DTO 模型（模块间契约） |
| core | `core` | 业务核心层 | 编排、治理、工具注册、同步、事件 |
| adapter | `adapter` | 适配器层 | LLM（DeepSeek）、持久化（H2） |
| plugins | `plugins` | 插件层 | 3 个子模块：6 工具 + 7 专家 + 3 治理监听器 |
| presentation | `presentation` | 接入通信层 | CLI 入口、WebSocket 客户端、DI 组装 |

## 文档引用关系

各模块的详细说明见 `structure/` 目录下的对应文件：

| 模块 | 文档文件 | 内容概要 |
|------|---------|---------|
| **exception** | → [structure/exception.md](structure/exception.md) | 9 个异常类（AgentException 基类、Llm/Tool/Plan/Governance 等子类） |
| **infrastructure** | → [structure/infrastructure.md](structure/infrastructure.md) | 配置加载、JsonUtil、FileUtil、日志、CommonModule |
| **kernel** | → [structure/kernel.md](structure/kernel.md) | EventBus（SimpleEventBus）、PluginManager（SimplePluginManager）、8 个核心类 |
| **api** | → [structure/api.md](structure/api.md) | 9 个 Port 接口、TaskScope、7 组 DTO 模型 |
| **core** | → [structure/core.md](structure/core.md) | 编排（Dispatcher、SimpleOrchestrator）、治理、工具引擎、同步、事件 |
| **adapter** | → [structure/adapter.md](structure/adapter.md) | DeepSeekLlmAdapter、H2MemoryAdapter、数据库仓库 |
| **plugins** | → [structure/plugins.md](structure/plugins.md) | 3 个子模块：6 工具插件 + 7 专家插件 + 3 治理监听器插件 |
| **presentation** | → [structure/presentation.md](structure/presentation.md) | CLI 入口、DI 模块、WebSocket 客户端、启动模式 |

## 架构层次

```
┌──────────────────────────────────────────────────────────┐
│              presentation（接入通信层）                     │
│    CLI · WebSocket · DI 模块（DependencyGraph）            │
├──────────────────────────────────────────────────────────┤
│                  adapter（适配器层）                        │
│            LLM（DeepSeek）· Memory（H2）                   │
├──────────────────────────────────────────────────────────┤
│              core（业务核心层）                             │
│  Orchestration · Governance · Tool · Expert · Sync       │
├──────────────────────────────────────────────────────────┤
│              api（接口契约层）                              │
│           Port 接口 · DTO 模型 · TaskScope                │
├──────────────────────────────────────────────────────────┤
│            kernel（微内核层）                              │
│        EventBus · PluginManager · ExtensionPoint          │
├─────────────────────┬────────────────────────────────────┤
│   infrastructure    │   exception                         │
│   配置/JSON/文件     │   错误码/异常体系                    │
├─────────────────────┴────────────────────────────────────┤
│                  plugins（插件层）                         │
│      plugin-tools · plugin-experts · plugin-governance    │
└──────────────────────────────────────────────────────────┘
```

## 关键架构特征

| 特征 | 说明 |
|------|------|
| **六边形架构** | kernel + api 定义核心逻辑和端口，adapter 实现外部系统适配 |
| **微内核 + 插件化** | PluginManager + EventBus 作为内核，3 类插件通过 YAML 声明式注册 |
| **事件驱动** | 治理组件（Auditor、RollbackManager 等）通过事件监听解耦 |
| **手工 DI** | 各模块下有 `di/` 子包，通过 DependencyGraph 统一组装 |
| **异常体系分层** | 按领域划分异常子包，继承自 `AgentException` 基类 |
| **同步机制** | core/sync 提供本地与云端的双向数据同步能力 |
