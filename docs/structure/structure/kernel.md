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
| `EventBus.java` | 事件总线接口（同步/异步发布、重载/关闭） |
| `EventHandler.java` | 事件处理器接口 |
| `SimpleEventBus.java` | 事件总线默认实现：同步/异步发布 + 持久化日志；有界持久化队列（默认 1000）+ 入队背压（100ms）+ 丢弃计数与限频告警；可选令牌桶限流（`rateLimitPerSecond`，0=不限）；暴露队列水位与丢弃指标 |
| `Subscription.java` | 事件订阅封装 |

### plugin — 插件框架

| 类 | 职责 |
|---|------|
| `PluginManager.java` | 插件管理器接口（扫描、加载、初始化、重载、关闭） |
| `SimplePluginManager.java` | 插件管理器默认实现：classpath + 插件目录扫描，反射实例化；坏插件隔离（catch Throwable/LinkageError）；init 失败调用 shutdown() 回滚；reloadExtensions/closeAllLoaders |
| `PluginClassLoader.java` | 插件隔离类加载器（child-first）：内核/API 包 parent-first 保证类型兼容，插件自身类 child-first 实现类冲突隔离与卸载 |
| `PluginHotReloader.java` | 插件目录热插拔监听器：WatchService 监控 jar 变化 + 500ms 防抖，自动触发重载回调 |
| `PluginDescriptor.java` | 插件描述符（name、type、class、依赖、isolated/sourceUrl 等元数据） |
| `PluginContext.java` | 插件上下文接口（访问核心服务） |
| `SimplePluginContext.java` | 插件上下文默认实现 |
| `ExtensionPoint.java` | 扩展点接口标记（含 init + 默认 shutdown 清理钩子） |
| `PluginType.java` | 插件类型枚举（TOOL / EXPERT / LISTENER） |
| `PluginLoadReport.java` | 插件加载健康检查报告 |

## 核心机制

1. **EventBus**: 组件间通过事件通信，解耦编排层与治理层。异步消费走虚拟线程池；有界持久化队列满时先背压 100ms 再丢弃并计数（限频告警防刷屏）；可选令牌桶限制发布速率。
2. **PluginManager**: 扫描 `META-INF/plugins/*.yaml` 描述符（classpath + 插件目录 jar），通过反射加载插件，通过 PluginContext 注入核心服务。
   - **隔离加载**：插件目录 jar 由独立 `PluginClassLoader` 加载，类冲突隔离、可卸载（close 后 GC 回收）。
   - **失败隔离**：依赖缺失（NoClassDefFoundError）、静态初始化失败（ExceptionInInitializerError）、init 异常均被捕获记录，单插件失败不影响内核与其他插件；init 失败自动调用 `shutdown()` 回滚半初始化资源。
   - **热插拔**：`PluginHotReloader` 监听插件目录变化 → 自动重载（取消旧订阅 → 关闭类加载器 → 重新加载 → 替换引用）。

## 依赖关系

- **依赖**: `exception`、`infrastructure`
- **被依赖**: `api` → `core` → `adapter` → `presentation`
