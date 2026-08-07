package com.ccagent.plugin.tools.mcp;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * MCP server 连接配置
 * <p>对应 <code>mcp/&lt;name&gt;.yaml</code>，声明一个外部 MCP server 的连接参数。</p>
 *
 * <h3>配置示例（.codepal/mcp/&lt;name&gt;.yaml）</h3>
 * <pre>
 * name: github
 * transport: http            # http（Streamable HTTP）/ stdio
 * url: https://mcp.example.com
 * headers:
 *   Authorization: "Bearer xxx"
 * command: npx              # stdio 时使用
 * args: ["-y", "server"]
 * enabled: true
 * toolPrefix: github         # 工具命名前缀，默认取 name
 * </pre>
 */
public class McpServerConfig {

    private String name;
    /** 传输方式：http / stdio */
    private String transport = "http";
    /** Streamable HTTP 端点（transport=http 时必填） */
    private String url;
    /** stdio 启动命令（transport=stdio 时使用） */
    private String command;
    /** stdio 启动参数 */
    private List<String> args;
    /** HTTP 请求头（鉴权等） */
    private Map<String, String> headers = new LinkedHashMap<>();
    private boolean enabled = true;
    /** 工具命名前缀，默认取 name */
    private String toolPrefix;

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getTransport() { return transport; }
    public void setTransport(String transport) { this.transport = transport; }
    public String getUrl() { return url; }
    public void setUrl(String url) { this.url = url; }
    public String getCommand() { return command; }
    public void setCommand(String command) { this.command = command; }
    public List<String> getArgs() { return args; }
    public void setArgs(List<String> args) { this.args = args; }
    public Map<String, String> getHeaders() { return headers; }
    public void setHeaders(Map<String, String> headers) { this.headers = headers; }
    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public String getToolPrefix() { return toolPrefix; }
    public void setToolPrefix(String toolPrefix) { this.toolPrefix = toolPrefix; }
}
