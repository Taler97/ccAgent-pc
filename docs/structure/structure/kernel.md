# kernel — 微内核层

## 职责

提供系统的两大核心机制：**事件总线**（EventBus）和**插件管理器**（PluginManager）。是整个微内核架构的基石。

## 位置

`pcAgent/kernel/src/main/java/com/ccagent/kernel/`

## 关键类

### event — 事件总线

| 类 | 职责 |
|---|------|
| `Event.java` | 事件接口/基类 |
| `EventBus.java` | 事件总线接口（同步/异步发布） |
| `EventHandler.java` | 事件处理器接口 |
| `SimpleEventBus.java` | 事件总线默认实现，支持同步/异步发布 + 持久化日志 |
| `Subscription.java` | 事件订阅封装 |

### plugin — 插件框架

| 类 | 职责 |
|---|------|
| `PluginManager.java` | 插件管理器接口（扫描、加载、初始化） |
| `SimplePluginManager.java` | 插件管理器默认实现，基于 ClassLoader.getResources 扫描 |
| `PluginDescriptor.java` | 插件描述符（name、type、class、依赖等元数据） |
| `PluginContext.java` | 插件上下文接口（访问核心服务） |
| `SimplePluginContext.java` | 插件上下文默认实现 |
| `ExtensionPoint.java` | 扩展点接口标记 |
| `PluginType.java` | 插件类型枚举（TOOL / EXPERT / LISTENER） |
| `PluginLoadReport.java` | 插件加载健康检查报告 |

## 核心机制

1. **EventBus**: 组件间通过事件通信，解耦编排层与治理层
2. **PluginManager**: 扫描 `META-INF/plugins/*.yaml` 描述符，通过反射加载插件，通过 PluginContext 注入核心服务

## 依赖关系

- **依赖**: `exception`、`infrastructure`
- **被依赖**: `api` → `core` → `adapter` → `presentation`
