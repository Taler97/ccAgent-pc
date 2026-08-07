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
import java.util.Set;
import java.util.logging.Logger;

/**
 * WebSocket 消息路由器（纯分发）。
 * <p>职责：解析 Envelope → 查 Handler 注册表 → 加锁 → 调用 Handler.handle()。
 * 所有业务逻辑已迁移到 {@code handler/} 包下的各个 Handler 类。</p>
 */
public class MessageRouter {

    private static final Logger LOG = Logger.getLogger(MessageRouter.class.getName());

    /**
     * 对话类协作信号：必须能打断 EXECUTE 持有的 seqId 锁（future.get 120s 阻塞），
     * 不能排队等待同一把锁，否则 APPROVAL_TIMEOUT。
     * 包含 ROLLBACK_DIALOG — 回滚是 EXECUTE 出错时的逃生通道，不能被锁阻塞。
     */
    private static final Set<String> DIALOG_SKIP_LOCK_TYPES = Set.of(
            "EXECUTE_PLAN", "CANCEL_PLAN", "TOOL_APPROVAL",
            "CANCEL_EXECUTION", "ROLLBACK_DIALOG"
    );

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
     * <p>锁策略：
     * <ul>
     *   <li>对话类协作信号（EXECUTE_PLAN/CANCEL_PLAN/TOOL_APPROVAL/CANCEL_EXECUTION/ROLLBACK_DIALOG）跳过锁，
     *       必须能打断 EXECUTE 持有的 seqId 锁</li>
     *   <li>对话类 EXECUTE 加 seqId 锁（保护同 seqId 的执行串行化）</li>
     *   <li>工具类/控制类消息不加锁（只读/短耗时，避免被 EXECUTE 阻塞 120s）</li>
     * </ul></p>
     */
    public void route(Envelope envelope, MessageSender sender) {
        WsMessageHandler handler = handlerRegistry.get(envelope.getType());
        if (handler == null) {
            LOG.warning("未知 envelope type: " + envelope.getType());
            return;
        }

        String type = envelope.getType();
        if (DIALOG_SKIP_LOCK_TYPES.contains(type)) {
            // 协作信号：跳过 seqId 锁，必须能打断 EXECUTE 的 future.get(120s) 阻塞
            handler.handle(envelope, sender);
        } else if (MessageDispatcher.DIALOG_TYPES.contains(type)) {
            // 对话类：加 seqId 锁保护执行串行化
            lockManager.withLock(envelope.getSeqId(), () -> handler.handle(envelope, sender));
        } else {
            // 工具类/控制类：无锁，避免只读查询被 EXECUTE 阻塞
            handler.handle(envelope, sender);
        }
    }

    // ===== MessageSender 接口（保留不变，CloudWebSocketClient 在用） =====

    public interface MessageSender {
        void send(Envelope envelope);
    }
}
