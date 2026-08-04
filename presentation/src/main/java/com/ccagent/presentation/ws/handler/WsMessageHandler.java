package com.ccagent.presentation.ws.handler;

import com.ccagent.api.model.envelope.Envelope;
import com.ccagent.presentation.ws.MessageRouter.MessageSender;

import java.util.List;

/**
 * WebSocket 消息处理器接口。
 * <p>每个实现类负责一组语义相关的 envelope type。
 * {@link com.ccagent.presentation.ws.MessageRouter} 只负责分发，不写业务逻辑。</p>
 */
public interface WsMessageHandler {

    /**
     * 本处理器支持的 envelope type 列表。
     * <p>MessageRouter 据此构建注册表，重复注册会抛异常。</p>
     *
     * @return 支持的 type 字符串列表，例如 ["CREATE_SESSION", "LIST_SESSIONS"]
     */
    List<String> supportedTypes();

    /**
     * 处理消息。
     *
     * @param envelope 已解析的消息（含 type/seqId/payload/timestamp）
     * @param sender   回信用的 sender
     */
    void handle(Envelope envelope, MessageSender sender);
}
