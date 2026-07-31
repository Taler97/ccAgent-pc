# infrastructure — 基础设施层

## 职责

提供项目运行的基础能力：配置加载、JSON/文件工具、日志初始化。通过 `CommonModule` 完成自身 DI 组装。

## 位置

`pcAgent/infrastructure/src/main/java/com/ccagent/infrastructure/`

## 关键类

| 包 | 类 | 职责 |
|---|-----|------|
| `config/` | `AppConfig.java` | 应用配置实体类，映射 `ccagent.yaml` |
| `config/` | `ConfigLoader.java` | YAML 配置文件加载器 |
| `constant/` | `Constants.java` | 全局常量定义 |
| `di/` | `CommonModule.java` | 基础设施层 DI 模块 |
| `logging/` | `LogConfig.java` | 日志配置初始化 |
| `util/` | `FileUtil.java` | 文件操作工具类（读写、遍历、目录等） |
| `util/` | `JsonUtil.java` | JSON 序列化/反序列化工具（基于 Jackson） |
| `util/` | `PathUtil.java` | 路径处理工具类 |

## 外部依赖

- Jackson（databind、dataformat-yaml、jsr310）
- HikariCP、H2（通过 adapter 使用，版本在此统一管理）

## 依赖关系

- **依赖**: `exception`
- **被依赖**: `kernel` → `api` → `core` → `adapter` → `presentation`
