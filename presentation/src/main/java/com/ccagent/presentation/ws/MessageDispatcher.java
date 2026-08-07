package com.ccagent.presentation.ws;

import com.ccagent.api.model.envelope.Envelope;
import com.ccagent.infrastructure.util.JsonUtil;
import com.ccagent.presentation.ws.MessageRouter.MessageSender;

import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.logging.Logger;

/**
 * 消息分发器: 按消息类型将消息分发到独立通道, 实现对话/工具/控制/配置四类功能互不阻塞.
 * <p>四通道隔离设计:
 * <ul>
 *   <li>dialogChannel - 对话/agent 执行 (EXECUTE/EXECUTE_PLAN/CANCEL_EXECUTION/CANCEL_PLAN/ROLLBACK_DIALOG/TOOL_APPROVAL)</li>
 *   <li>utilChannel - 只读工具操作 (CREATE_SESSION/FILE_LIST_REQ/QUERY_PROGRESS 等), 不阻塞对话</li>
 *   <li>controlChannel - 心跳/控制 (PING/ON_KICK), 最高优先级</li>
 *   <li>configChannel - 配置/导入 (UPDATE_LLM_CONFIG/QUERY_LLM_CONFIG/MCP_IMPORT_REQ), 旁路操作不碰会话状态</li>
 * </ul>
 * 所有通道均使用 Java 21 虚拟线程, IO 阻塞时自动让出 carrier 线程, 互不阻塞.</p>
 */
public class MessageDispatcher {

    private static final Logger LOG = Logger.getLogger(MessageDispatcher.class.getName());

    /** 对话类消息：涉及 agent 执行/审批/回滚 */
    public static final Set<String> DIALOG_TYPES = Set.of(
            "EXECUTE", "EXECUTE_PLAN", "CANCEL_EXECUTION",
            "CANCEL_PLAN", "ROLLBACK_DIALOG", "TOOL_APPROVAL"
    );

    /** 控制类消息：心跳/踢出，最高优先级 */
    public static final Set<String> CONTROL_TYPES = Set.of(
            "PING", "ON_KICK"
    );

    /** 配置/导入类消息：LLM 配置热更、MCP 导入，旁路操作不碰会话状态 */
    public static final Set<String> CONFIG_TYPES = Set.of(
            "UPDATE_LLM_CONFIG", "QUERY_LLM_CONFIG", "MCP_IMPORT_REQ"
    );

    private final MessageRouter router;
    private final ExecutorService dialogChannel = Executors.newVirtualThreadPerTaskExecutor();
    private final ExecutorService utilChannel = Executors.newVirtualThreadPerTaskExecutor();
    private final ExecutorService controlChannel = Executors.newVirtualThreadPerTaskExecutor();
    private final ExecutorService configChannel = Executors.newVirtualThreadPerTaskExecutor();

    public MessageDispatcher(MessageRouter router) {
        this.router = router;
    }

    /**
     * 解析消息并分发到对应通道。所有通道异步执行，不阻塞 onText 回调线程。
     */
    public void dispatch(String rawMessage, MessageSender sender) {
        Envelope envelope;
        try {
            envelope = JsonUtil.fromJson(rawMessage, Envelope.class);
        } catch (Exception e) {
            String preview = rawMessage.length() > 200
                    ? rawMessage.substring(0, 200) + "...(截断)" : rawMessage;
            LOG.warning("消息解析失败: " + e.getClass().getSimpleName() + " -> " + e.getMessage()
                    + " | 消息长度=" + rawMessage.length() + " | 消息头: " + preview);
            return;
        }

        String type = envelope.getType();
        ExecutorService channel = selectChannel(type);
        channel.submit(() -> {
            try {
                router.route(envelope, sender);
            } catch (Exception e) {
                LOG.warning("消息处理失败 [" + type + "]: " + e.getMessage());
            }
        });
    }

    private ExecutorService selectChannel(String type) {
        if (CONTROL_TYPES.contains(type)) return controlChannel;
        if (CONFIG_TYPES.contains(type)) return configChannel;
        if (DIALOG_TYPES.contains(type)) return dialogChannel;
        return utilChannel;
    }

    public void shutdown() {
        dialogChannel.shutdown();
        utilChannel.shutdown();
        controlChannel.shutdown();
        configChannel.shutdown();
    }
}
