package com.ccagent.plugin.tools.mcp;

import com.ccagent.core.tool.DefaultToolRegistry;
import com.ccagent.infrastructure.util.JsonUtil;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.json.jackson2.JacksonMcpJsonMapper;
import io.modelcontextprotocol.spec.McpSchema;

import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

/**
 * MCP 客户端管理器
 * <p>按 <code>.codepal/mcp/*.yaml</code> 配置连接外部 MCP server，
 * 将其工具包装为 {@link McpToolExecutor} 动态注册进 ToolRegistry（供专家调用）。
 * 单个 server 连接失败只跳过该 server，不影响引擎启动。</p>
 */
public class McpClientManager {

    private static final Logger LOG = Logger.getLogger(McpClientManager.class.getName());

    private final DefaultToolRegistry toolRegistry;
    private final Map<String, McpSyncClient> clients = new ConcurrentHashMap<>();
    private final Map<String, Integer> toolCounts = new ConcurrentHashMap<>();
    private final Map<String, Boolean> connected = new ConcurrentHashMap<>();
    private final Map<String, String> errors = new ConcurrentHashMap<>();

    public McpClientManager(DefaultToolRegistry toolRegistry) {
        this.toolRegistry = toolRegistry;
    }

    public void loadDefault() {
        load(new McpConfigLoader().loadDefault());
    }

    public void load(Path dir) {
        load(new McpConfigLoader().load(dir));
    }

    /**
     * 导入（新增/更新）MCP server 配置并热重载：
     * <p>先逐个落盘 <code>.codepal/mcp/&lt;name&gt;.yaml</code>，随后在后台虚拟线程走与启动相同的
     * {@link #load(List)} 重新扫描目录并连接（无需重启，且不阻塞导入接口返回）。
     * 单个配置落盘失败不影响其他。</p>
     *
     * @return {@code {saved: [name...], failed: [name: err...], servers: [status...]}}
     */
    public synchronized Map<String, Object> importConfigs(List<McpServerConfig> configs) {
        McpConfigLoader loader = new McpConfigLoader();
        List<String> saved = new ArrayList<>();
        List<String> failed = new ArrayList<>();
        for (McpServerConfig cfg : configs) {
            if (cfg == null || cfg.getName() == null || cfg.getName().isBlank()) {
                failed.add("(未命名配置)");
                continue;
            }
            try {
                loader.save(cfg);
                saved.add(cfg.getName());
            } catch (Exception e) {
                failed.add(cfg.getName() + ": " + e.getMessage());
                LOG.warning("MCP 配置落盘失败: " + cfg.getName() + " - " + e.getMessage());
            }
        }
        // 后台热重载：连接外部 server 可能耗时较长（单个 server 最多 30s 超时），不阻塞导入响应
        Thread.ofVirtual().start(() -> {
            try {
                load(loader.loadDefault());
            } catch (Exception e) {
                LOG.warning("MCP 后台热重载失败: " + e.getMessage());
            }
        });

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("saved", saved);
        result.put("failed", failed);
        result.put("servers", status());
        return result;
    }

    /**
     * 加载全部配置：先关闭旧连接，再逐个连接注册（失败降级）
     */
    public synchronized void load(List<McpServerConfig> configs) {
        closeAll();
        clients.clear();
        toolCounts.clear();
        connected.clear();
        errors.clear();

        for (McpServerConfig cfg : configs) {
            if (!cfg.isEnabled()) continue;
            connect(cfg);
        }
        LOG.info("MCP 加载完成，成功连接 " + clients.size() + " 个 server");
    }

    private void connect(McpServerConfig cfg) {
        String name = cfg.getName();
        try {
            McpSyncClient client;
            if ("stdio".equalsIgnoreCase(cfg.getTransport())) {
                client = connectStdio(cfg);
            } else {
                client = connectHttp(cfg);
            }
            client.initialize();

            List<McpSchema.Tool> tools = client.listTools().tools();
            String prefix = cfg.getToolPrefix() != null && !cfg.getToolPrefix().isBlank()
                    ? cfg.getToolPrefix() : name;
            int count = 0;
            for (McpSchema.Tool tool : tools) {
                String registered = "mcp_" + prefix + "_" + tool.name();
                toolRegistry.register(new McpToolExecutor(client, registered, tool));
                count++;
            }
            clients.put(name, client);
            toolCounts.put(name, count);
            connected.put(name, true);
            LOG.info("MCP 连接成功: " + name + "（注册 " + count + " 个工具）");
        } catch (Throwable e) {
            connected.put(name, false);
            errors.put(name, e.getMessage());
            LOG.warning("MCP 连接失败（跳过，不影响引擎）: " + name + " - " + e);
        }
    }

    private McpSyncClient connectHttp(McpServerConfig cfg) {
        // 显式注入 Jackson mapper：绕过 McpJsonDefaults 的 ServiceLoader 加载（在部分运行环境
        // （如 maven-exec-plugin 内联运行时）TCCL 无法找到 McpJsonMapperSupplier，导致
        // ServiceConfigurationError，使连接直接失败）
        McpJsonMapper jsonMapper = new JacksonMcpJsonMapper(JsonUtil.getMapper());
        HttpClientStreamableHttpTransport.Builder tb = HttpClientStreamableHttpTransport
                .builder(cfg.getUrl())
                .jsonMapper(jsonMapper);
        if (cfg.getHeaders() != null && !cfg.getHeaders().isEmpty()) {
            tb.customizeRequest(b -> {
                for (Map.Entry<String, String> e : cfg.getHeaders().entrySet()) {
                    b.header(e.getKey(), e.getValue());
                }
            });
        }
        HttpClientStreamableHttpTransport transport = tb.build();
        return McpClient.sync(transport)
                .requestTimeout(Duration.ofSeconds(30))
                .build();
    }

    private McpSyncClient connectStdio(McpServerConfig cfg) {
        // stdio 传输需要 mcp-transport-stdio 构件；当前未引入时给出降级提示
        throw new IllegalStateException("stdio 传输暂未启用，请使用 http（url）配置");
    }

    /**
     * 连接状态列表（供前端展示）
     */
    public List<Map<String, Object>> status() {
        List<Map<String, Object>> list = new ArrayList<>();
        for (String name : connected.keySet()) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("name", name);
            item.put("connected", connected.get(name));
            item.put("toolCount", toolCounts.getOrDefault(name, 0));
            if (errors.containsKey(name)) item.put("error", errors.get(name));
            list.add(item);
        }
        list.sort(Comparator.comparing(m -> (String) m.get("name")));
        return list;
    }

    public synchronized void closeAll() {
        for (McpSyncClient client : clients.values()) {
            try {
                client.close();
            } catch (Exception ignored) {
            }
        }
        clients.clear();
    }
}
