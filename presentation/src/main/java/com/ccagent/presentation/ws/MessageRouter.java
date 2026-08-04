package com.ccagent.presentation.ws;

import com.ccagent.api.model.envelope.Envelope;
import com.ccagent.api.port.OrchestratorPort;
import com.ccagent.core.skill.SkillRegistry;
import com.ccagent.infrastructure.util.JsonUtil;
import com.ccagent.plugin.tools.mcp.McpClientManager;
import com.ccagent.presentation.ws.handler.*;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.logging.Logger;

/**
 * WebSocket 消息路由器（纯分发）。
 * <p>职责：解析 Envelope → 查 Handler 注册表 → 加锁 → 调用 Handler.handle()。
 * 所有业务逻辑已迁移到 {@code handler/} 包下的各个 Handler 类。</p>
 */
public class MessageRouter {

    private static final Logger LOG = Logger.getLogger(MessageRouter.class.getName());

    private final Map<String, WsMessageHandler> handlerRegistry = new LinkedHashMap<>();
    private final SeqLockManager lockManager = new SeqLockManager();

    // ===== 构造函数（向后兼容） =====

    public MessageRouter(OrchestratorPort governanceGateway) {
        this(governanceGateway, null, null);
    }

    public MessageRouter(OrchestratorPort governanceGateway, SkillRegistry skillRegistry) {
        this(governanceGateway, skillRegistry, null);
    }

    public MessageRouter(OrchestratorPort governanceGateway, SkillRegistry skillRegistry,
                         McpClientManager mcpManager) {
        register(new SessionHandler(governanceGateway));
        register(new ExecutionHandler(governanceGateway));
        register(new FileHandler(governanceGateway));
        register(new StatusHandler(governanceGateway, skillRegistry, mcpManager));
        register(new MiscHandler());
    }

    private void register(WsMessageHandler handler) {
        for (String type : handler.supportedTypes()) {
            if (handlerRegistry.put(type, handler) != null) {
                throw new IllegalStateException("重复注册的 envelope type: " + type);
            }
        }
    }

    // ===== 纯分发 =====

    public void route(String message, MessageSender sender) {
        Envelope envelope;
        try {
            envelope = JsonUtil.fromJson(message, Envelope.class);
        } catch (Exception e) {
            String preview = message.length() > 200 ? message.substring(0, 200) + "...(截断)" : message;
            LOG.warning("消息解析失败: " + e.getClass().getSimpleName() + " -> " + e.getMessage()
                    + " | 消息长度=" + message.length()
                    + " | 消息头: " + preview);
            return;
        }

        WsMessageHandler handler = handlerRegistry.get(envelope.getType());
        if (handler == null) {
            LOG.warning("未知 envelope type: " + envelope.getType());
            return;
        }

        lockManager.withLock(envelope.getSeqId(), () -> handler.handle(envelope, sender));
    }

    // ===== MessageSender 接口（保留不变，CloudWebSocketClient 在用） =====

    public interface MessageSender {
        void send(Envelope envelope);
    }
}
