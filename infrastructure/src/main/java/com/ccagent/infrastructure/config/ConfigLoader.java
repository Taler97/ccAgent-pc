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

/**
 * 配置加载器
 * <p>启动时优先读取项目目录下的 {@code ccagent.yaml}，
 * 找不到则读取 {@code ~/.code-pal/config.yaml}。
 * 都找不到则使用默认值。</p>
 */
public class ConfigLoader {

    private static volatile AppConfig config;
    private static final ObjectMapper YAML_MAPPER = new ObjectMapper(new YAMLFactory());

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
            config = YAML_MAPPER.readValue(yaml, AppConfig.class);
            return config;
        } catch (IOException e) {
            throw new ToolException(
                    ErrorCode.PARSE_ERROR,
                    "配置文件解析失败: " + configPath,
                    e
            );
        }
    }

    /** 获取已加载的配置 */
    public static AppConfig getConfig() {
        if (config == null) {
            throw new IllegalStateException("配置尚未加载，请先调用 ConfigLoader.load()");
        }
        return config;
    }
}
