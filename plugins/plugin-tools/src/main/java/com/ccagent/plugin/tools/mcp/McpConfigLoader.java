package com.ccagent.plugin.tools.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.ccagent.infrastructure.constant.Constants;
import com.ccagent.infrastructure.util.FileUtil;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

/**
 * MCP 配置加载器
 * <p>扫描 <code>mcp/&lt;name&gt;.yaml</code>，解析为 {@link McpServerConfig} 列表。</p>
 */
public class McpConfigLoader {

    private static final Logger LOG = Logger.getLogger(McpConfigLoader.class.getName());
    private static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory());

    public List<McpServerConfig> loadDefault() {
        return load(Paths.get(Constants.MCP_DIR));
    }

    /**
     * 将配置持久化到 <code>mcp/&lt;name&gt;.yaml</code>（目录不存在则创建，已存在则覆盖）。
     * <p>name 会净化掉路径分隔符，防止路径穿越。</p>
     */
    public Path save(McpServerConfig cfg) throws IOException {
        String safeName = cfg.getName().trim().replaceAll("[\\\\/:*?\"<>|]", "_");
        Path dir = Paths.get(Constants.MCP_DIR);
        Files.createDirectories(dir);
        Path file = dir.resolve(safeName + ".yaml");
        YAML.writeValue(file.toFile(), cfg);
        return file;
    }

    public List<McpServerConfig> load(Path dir) {
        List<McpServerConfig> configs = new ArrayList<>();
        if (dir == null || !Files.isDirectory(dir)) return configs;

        try (var stream = Files.list(dir)) {
            for (Path file : stream.toList()) {
                String fileName = file.getFileName().toString();
                if (!fileName.endsWith(".yaml") && !fileName.endsWith(".yml")) continue;
                try {
                    McpServerConfig cfg = YAML.readValue(FileUtil.readString(file), McpServerConfig.class);
                    if (cfg == null || cfg.getName() == null || cfg.getName().isBlank()) {
                        LOG.warning("MCP 配置缺少 name: " + file);
                        continue;
                    }
                    // 兼容嵌套 headers 反序列化为 Object 的情况
                    cfg.setHeaders(normalizeHeaders(cfg.getHeaders()));
                    configs.add(cfg);
                } catch (Exception e) {
                    LOG.warning("解析 MCP 配置失败: " + file + " - " + e.getMessage());
                }
            }
        } catch (Exception e) {
            LOG.warning("扫描 MCP 配置目录失败: " + e.getMessage());
        }
        return configs;
    }

    @SuppressWarnings("unchecked")
    private Map<String, String> normalizeHeaders(Object raw) {
        java.util.LinkedHashMap<String, String> result = new java.util.LinkedHashMap<>();
        if (raw instanceof Map<?, ?> map) {
            for (Map.Entry<?, ?> e : map.entrySet()) {
                result.put(String.valueOf(e.getKey()), e.getValue() != null ? String.valueOf(e.getValue()) : "");
            }
        }
        return result;
    }
}
