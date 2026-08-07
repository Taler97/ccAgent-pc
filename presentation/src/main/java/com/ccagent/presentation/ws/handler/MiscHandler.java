package com.ccagent.presentation.ws.handler;

import com.ccagent.api.model.envelope.Envelope;
import com.ccagent.infrastructure.constant.Constants;
import com.ccagent.presentation.ws.MessageRouter.MessageSender;

import java.util.List;
import java.util.Map;

/**
 * 杂项 Handler：PING / ON_KICK / VERSION_REQ
 */
public class MiscHandler extends HandlerSupport implements WsMessageHandler {

    @Override
    public List<String> supportedTypes() {
        return List.of("PING", "ON_KICK", "VERSION_REQ");
    }

    @Override
    public void handle(Envelope envelope, MessageSender sender) {
        switch (envelope.getType()) {
            case "PING"        -> ping(envelope, sender);
            case "ON_KICK"     -> onKick(envelope);
            case "VERSION_REQ" -> version(envelope, sender);
        }
    }

    private void ping(Envelope envelope, MessageSender sender) {
        sendResponse(sender, "PONG", envelope.getSeqId(), "{\"pong\":true}");
    }

    private void onKick(Envelope envelope) {
        LOG.warning("收到云端踢出通知（ON_KICK），旧连接已被替换");
    }

    private void version(Envelope envelope, MessageSender sender) {
        sendResponse(sender, "VERSION_RES", envelope.getSeqId(),
                Map.of("version", Constants.APP_VERSION, "name", Constants.APP_NAME));
    }
}
