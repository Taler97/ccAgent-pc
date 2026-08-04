package com.ccagent.presentation.ws.handler;

import com.ccagent.api.model.envelope.Envelope;
import com.ccagent.presentation.ws.MessageRouter.MessageSender;

import java.util.List;

/**
 * 杂项 Handler：PING / ON_KICK
 */
public class MiscHandler extends HandlerSupport implements WsMessageHandler {

    @Override
    public List<String> supportedTypes() {
        return List.of("PING", "ON_KICK");
    }

    @Override
    public void handle(Envelope envelope, MessageSender sender) {
        switch (envelope.getType()) {
            case "PING"   -> ping(envelope, sender);
            case "ON_KICK" -> onKick(envelope);
        }
    }

    private void ping(Envelope envelope, MessageSender sender) {
        sendResponse(sender, "PONG", envelope.getSeqId(), "{\"pong\":true}");
    }

    private void onKick(Envelope envelope) {
        LOG.warning("收到云端踢出通知（ON_KICK），旧连接已被替换");
    }
}
