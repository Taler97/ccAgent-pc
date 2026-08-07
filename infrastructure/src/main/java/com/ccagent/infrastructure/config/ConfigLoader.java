package com.ccagent.infrastructure.config;

import com.ccagent.exception.base.ErrorCode;
import com.ccagent.exception.tool.ToolException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 配置加载器
 * <p>启动时优先读取项目目录下的 {@code ccagent.yaml}，
 * 找不到则读取 {@code ~/.code-pal/config.yaml}。
 * 都找不到则使用默认值。</p>
 *
 * <p>支持环境变量占位符：配置值可写 {@code ${VAR}}（取环境变量 VAR，未设置则为空）
 * 或 {@code ${VAR:default}}（未设置时回退 default），在 YAML 反序列化前统一解析。
 * 敏感项（如 LLM API Key、前后端地址）建议通过占位符引用，避免密钥落盘。</p>
 */
public class ConfigLoader {

    private static volatile AppConfig config;
    private static final ObjectMapper YAML_MAPPER = new ObjectMapper(new YAMLFactory());

    /** 环境变量占位符：${NAME} 或 ${NAME:default} */
    private static final Pattern ENV_PLACEHOLDER = Pattern.compile("\\$\\{([A-Za-z_][A-Za-z0-9_]*)(?::([^}]*))?\\}");

    static {
        YAML_MAPPER.setPropertyNamingStrategy(PropertyNamingStrategies.KEBAB_CASE);
    }

    /** 项目目录配置文件名 */
    private static final String PROJECT_CONFIG = "ccagent.yaml";
    /** 用户目录配置路径 */
    private static final String USER_CONFIG =
            System.getProperty("user.home") + "/.code-pal/config.yaml";

    private ConfigLoader() {}

    /**
     * 加载配置：项目目录 > 用户目录 > 默认值
     */
    public static synchronized AppConfig load() {
        // 1. 项目目录 ccagent.yaml
        Path projectPath = Paths.get(PROJECT_CONFIG);
        if (Files.exists(projectPath)) {
            return load(projectPath.toString());
        }
        // 2. 用户目录 ~/.code-pal/config.yaml
        Path userPath = Paths.get(USER_CONFIG);
        if (Files.exists(userPath)) {
            return load(userPath.toString());
        }
        // 3. 都不存在，使用默认值
        config = new AppConfig();
        return config;
    }

    /**
     * 从指定路径加载配置
     */
    public static synchronized AppConfig load(String configPath) {
        Path path = Paths.get(configPath);
        if (!Files.exists(path)) {
            config = new AppConfig();
            return config;
        }
        try {
            String yaml = Files.readString(path);
            config = YAML_MAPPER.readValue(resolveEnvPlaceholders(yaml), AppConfig.class);
            return config;
        } catch (IOException e) {
            throw new ToolException(
                    ErrorCode.PARSE_ERROR,
                    "配置文件解析失败: " + configPath,
                    e
            );
        }
    }

    /**
     * 解析环境变量占位符：{@code ${VAR}} → 环境变量值（未设置则为空），
     * {@code ${VAR:default}} → 未设置时回退默认值。
     * 仅替换未被引号转义的占位符，避免误伤 YAML 中的 ${...} 字面量（当前约定不做转义）。
     */
    static String resolveEnvPlaceholders(String yaml) {
        Matcher m = ENV_PLACEHOLDER.matcher(yaml);
        StringBuilder sb = new StringBuilder(yaml.length());
        while (m.find()) {
            String name = m.group(1);
            String fallback = m.group(2);
            String value = System.getenv(name);
            if (value == null || value.isBlank()) {
                value = fallback != null ? fallback : "";
            }
            // 转义替换值中的 $ 与反斜杠，防止正则替换时被二次解析
            m.appendReplacement(sb, Matcher.quoteReplacement(value));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    /** 获取已加载的配置 */
    public static AppConfig getConfig() {
        if (config == null) {
            throw new IllegalStateException("配置尚未加载，请先调用 ConfigLoader.load()");
        }
        return config;
    }
}
