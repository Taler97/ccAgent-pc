package com.ccagent.presentation.ws;

import com.ccagent.adapter.llm.DeepSeekLlmAdapter;
import com.ccagent.api.model.envelope.Envelope;
import com.ccagent.api.port.OrchestratorPort;
import com.ccagent.core.skill.SkillRegistry;
import com.ccagent.infrastructure.config.AppConfig;
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
        this(governanceGateway, skillRegistry, mcpManager, null, null);
    }

    /**
     * 完整构造函数：支持注入 LLM 适配器与 AppConfig，以启用 UPDATE_LLM_CONFIG 热更。
     */
    public MessageRouter(OrchestratorPort governanceGateway, SkillRegistry skillRegistry,
                         McpClientManager mcpManager,
                         DeepSeekLlmAdapter llmAdapter, AppConfig appConfig) {
        register(new SessionHandler(governanceGateway));
        register(new ExecutionHandler(governanceGateway));
        register(new FileHandler(governanceGateway));
        register(new StatusHandler(governanceGateway, skillRegistry, mcpManager));
        register(new MiscHandler());
        if (llmAdapter != null && appConfig != null) {
            register(new ConfigHandler(llmAdapter, appConfig));
        }
    }

    private void register(WsMessageHandler handler) {
        for (String type : handler.supportedTypes()) {
            if (handlerRegistry.put(type, handler) != null) {
                throw new IllegalStateException("重复注册的 envelope type: " + type);
            }
        }
    }

    // ===== 纯分发 =====

    /**
     * 兼容方法：接收原始 JSON 字符串，解析后委托给 {@link #route(Envelope, MessageSender)}。
     * 供测试代码使用；生产路径走 {@link MessageDispatcher}，已预解析 Envelope。
     */
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
        route(envelope, sender);
    }

    /**
     * 核心路由：按消息类型决定是否加 seqId 锁，然后调用对应 Handler。
     * <p>锁策略（D5：EXECUTE 已异步提交，seqId 锁只持毫秒级，无需再跳过锁）：
     * <ul>
     *   <li>对话类消息加 seqId 锁（保护同 seqId 消息串行，锁内代码极短）</li>
     *   <li>工具/控制/配置类消息不加锁（只读/旁路，避免被对话阻塞）</li>
     * </ul></p>
     */
    public void route(Envelope envelope, MessageSender sender) {
        WsMessageHandler handler = handlerRegistry.get(envelope.getType());
        if (handler == null) {
            LOG.warning("未知 envelope type: " + envelope.getType());
            return;
        }

        if (MessageDispatcher.DIALOG_TYPES.contains(envelope.getType())) {
            // 对话类：加 seqId 锁保护同 seqId 消息串行（handler 快速返回，锁内极短）
            lockManager.withLock(envelope.getSeqId(), () -> handler.handle(envelope, sender));
        } else {
            // 工具/控制/配置类：无锁，避免只读查询与旁路操作被对话阻塞
            handler.handle(envelope, sender);
        }
    }

    // ===== MessageSender 接口（保留不变，CloudWebSocketClient 在用） =====

    public interface MessageSender {
        void send(Envelope envelope);
    }
}
