package com.ccagent.presentation.ws;

import com.ccagent.adapter.llm.DeepSeekLlmAdapter;
import com.ccagent.api.port.OrchestratorPort;
import com.ccagent.core.skill.SkillRegistry;
import com.ccagent.plugin.tools.mcp.McpClientManager;
import com.ccagent.infrastructure.config.AppConfig;

import java.util.logging.Logger;

/**
 * 云端 WebSocket 会话（WS 云端层）
 * <p>组合 WS 客户端四件套（{@link CloudWebSocketClient}、{@link MessageRouter}、
 * {@link StateManager}、{@link ReconnectManager}），提供统一的启动/停止入口。
 * 由 {@code --background} 形态挂载到引擎进程。</p>
 */
public class CloudWsSession {

    private static final Logger LOG = Logger.getLogger(CloudWsSession.class.getName());

    private final CloudWebSocketClient wsClient;
    private final StateManager stateManager;
    private final ReconnectManager reconnectManager;
    private final String cloudUrl;

    private volatile boolean started = false;

    public CloudWsSession(AppConfig config, OrchestratorPort gateway) {
        this(config, gateway, null, null, null, null);
    }

    public CloudWsSession(AppConfig config, OrchestratorPort gateway, SkillRegistry skillRegistry) {
        this(config, gateway, skillRegistry, null, null, null);
    }

    public CloudWsSession(AppConfig config, OrchestratorPort gateway, SkillRegistry skillRegistry,
                          McpClientManager mcpManager) {
        this(config, gateway, skillRegistry, mcpManager, null, null);
    }

    /**
     * 完整构造函数：注入 LLM 适配器与 AppConfig 以启用 UPDATE_LLM_CONFIG 热更。
     */
    public CloudWsSession(AppConfig config, OrchestratorPort gateway, SkillRegistry skillRegistry,
                          McpClientManager mcpManager,
                          DeepSeekLlmAdapter llmAdapter, AppConfig appConfig) {
        this.cloudUrl = config.getWebsocket().getUrl();
        this.stateManager = new StateManager();
        this.reconnectManager = new ReconnectManager(
                config.getWebsocket().getReconnectInitialDelay(),
                config.getWebsocket().getReconnectMaxDelay()
        );
        MessageRouter router = new MessageRouter(gateway, skillRegistry, mcpManager, llmAdapter, appConfig);
        MessageDispatcher dispatcher = new MessageDispatcher(router);
        this.wsClient = new CloudWebSocketClient(
                this.cloudUrl, dispatcher, stateManager, reconnectManager
        );
    }

    /**
     * 启动 WS 客户端，连接 Cloud Server。
     */
    public synchronized void start() {
        if (started) return;
        wsClient.start();
        started = true;
        LOG.info("WebSocket 客户端已启动: " + cloudUrl);
    }

    /**
     * 优雅停止：关闭 WS 连接并停止重连。
     */
    public synchronized void stop() {
        if (!started) return;
        try {
            wsClient.shutdown();
        } catch (Exception e) {
            LOG.warning("WebSocket 停止异常: " + e.getMessage());
        }
        started = false;
    }

    public StateManager getStateManager() {
        return stateManager;
    }

    public boolean isStarted() {
        return started;
    }
}
