package com.ccagent.infrastructure.constant;

import java.util.Properties;

/**
 * 全局常量定义
 * <p>集中管理所有路径、文件名、版本号等常量。
 * 所有文件存放在项目目录下的 {@code .codepal/} 目录中，
 * 配置文件为项目根目录的 {@code ccagent.yaml}。</p>
 */
public final class Constants {

    private Constants() {}

    /** 应用版本（从 META-INF/maven pom.properties 读取，开发环境 fallback 到 dev） */
    public static final String APP_VERSION = loadVersion();
    /** 应用名称 */
    public static final String APP_NAME = "CodePal";

    private static String loadVersion() {
        try (var is = Constants.class.getResourceAsStream(
                "/META-INF/maven/com.ccagent/infrastructure/pom.properties")) {
            if (is != null) {
                var props = new Properties();
                props.load(is);
                String v = props.getProperty("version");
                if (v != null && !v.isBlank()) return v;
            }
        } catch (Exception ignored) {}
        return "dev";
    }

    /** 运行时数据目录（项目本地） */
    public static final String BASE_DIR = "./.codepal";
    /** 配置文件路径（项目根目录） */
    public static final String CONFIG_FILE = "./ccagent.yaml";
    /** 凭证文件 */
    public static final String CREDENTIALS_FILE = BASE_DIR + "/credentials";
    /** 设备 ID 文件 */
    public static final String DEVICE_ID_FILE = BASE_DIR + "/deviceId";
    /** 工作区元数据文件 */
    public static final String WORKSPACES_FILE = BASE_DIR + "/workspaces.properties";
    /** 偏好配置文件 */
    public static final String PREFERENCES_FILE = BASE_DIR + "/preferences.json";
    /** 数据库目录 */
    public static final String DB_DIR = BASE_DIR + "/db";
    /** H2 数据库文件 */
    public static final String DB_FILE = DB_DIR + "/codepal.mv.db";
    /** 备份暂存区根目录 */
    public static final String STAGING_DIR = BASE_DIR + "/staging";
    /** 系统日志目录 */
    public static final String LOG_DIR = BASE_DIR + "/logs";
    /** 系统日志文件 */
    public static final String AGENT_LOG = LOG_DIR + "/agent.log";
    /** 审计日志文件路径 */
    public static final String AUDIT_LOG = BASE_DIR + "/audit.log";
    /** 事件日志文件路径 */
    public static final String EVENT_LOG = BASE_DIR + "/event.log";
    /** Prompt 文件目录 */
    public static final String PROMPTS_DIR = BASE_DIR + "/prompts";
    /** Planner System Prompt */
    public static final String PLANNER_PROMPT = PROMPTS_DIR + "/planner.md";
    /** 记忆压缩 System Prompt */
    public static final String COMPRESSOR_PROMPT = PROMPTS_DIR + "/compressor.md";
    /** 产物缓存目录 */
    public static final String ARTIFACT_CACHE_DIR = BASE_DIR + "/cache/artifacts";
    /** 插件目录（可选：放置独立 jar 的插件，由独立 ClassLoader 隔离加载，支持卸载重载） */
    public static final String PLUGIN_DIR = BASE_DIR + "/plugins";
    /** 技能（skill.md）目录：<name>/SKILL.md 或 <name>.md */
    public static final String SKILLS_DIR = BASE_DIR + "/skills";
    /** 自定义专家配置目录：<name>.yaml */
    public static final String EXPERTS_DIR = BASE_DIR + "/experts";
    /** MCP server 配置目录：<name>.yaml */
    public static final String MCP_DIR = BASE_DIR + "/mcp";
}
