package com.ccagent.presentation.ws.handler;

import com.ccagent.api.model.envelope.Envelope;
import com.ccagent.infrastructure.util.JsonUtil;
import com.ccagent.presentation.ws.MessageRouter.MessageSender;
import com.fasterxml.jackson.core.type.TypeReference;

import java.time.Instant;
import java.util.Map;
import java.util.logging.Logger;

/**
 * Handler 基类：提供公共工具方法。
 * <p>所有 Handler 继承此类，复用 sendAgentStatus、payload 解析等能力。</p>
 */
public abstract class HandlerSupport {

    protected final Logger LOG = Logger.getLogger(getClass().getName());

    // ===== payload 解析（避免双重序列化） =====

    /**
     * 将 envelope.payload 直接转为 Map（不走 JSON 字节中间转换）。
     * <p>payload 通常是 Jackson 反序列化出的 LinkedHashMap，直接强转即可。
     * 如果 payload 是 String（云端发送了预序列化 JSON），则走 fromJson 兜底。</p>
     */
    @SuppressWarnings("unchecked")
    protected Map<String, Object> payloadAsMap(Envelope envelope) {
        Object raw = envelope.getPayload();
        if (raw instanceof Map) {
            return (Map<String, Object>) raw;
        }
        if (raw instanceof String s) {
            return JsonUtil.fromJson(s, Map.class);
        }
        return JsonUtil.getMapper().convertValue(raw, Map.class);
    }

    /**
     * 将 envelope.payload 转为强类型对象。
     */
    protected <T> T payloadAs(Envelope envelope, Class<T> targetClass) {
        Object raw = envelope.getPayload();
        if (raw instanceof String s) {
            return JsonUtil.fromJson(s, targetClass);
        }
        return JsonUtil.getMapper().convertValue(raw, targetClass);
    }

    /**
     * 将 envelope.payload 转为泛型类型（如 List&lt;Foo&gt;）。
     */
    protected <T> T payloadAs(Envelope envelope, TypeReference<T> typeRef) {
        Object raw = envelope.getPayload();
        if (raw instanceof String s) {
            return JsonUtil.fromJson(s, typeRef);
        }
        return JsonUtil.getMapper().convertValue(raw, typeRef);
    }

    // ===== 响应构造工具 =====

    /**
     * 发送 AGENT_STATUS 状态更新。
     */
    protected void sendAgentStatus(MessageSender sender, String seqId, String agentState, String operation) {
        String payload = "{\"agentState\":\"" + agentState + "\",\"currentOperation\":\""
                + operation.replace("\"", "'") + "\"}";
        sender.send(new Envelope("AGENT_STATUS", seqId, Instant.now().toString(), payload));
    }

    /**
     * 发送 ERROR 响应。
     */
    protected void sendError(MessageSender sender, String seqId, String error) {
        sender.send(new Envelope("ERROR", seqId, Instant.now().toString(),
                "{\"error\":\"" + error.replace("\"", "'") + "\"}"));
    }

    /**
     * 发送指定 type 的响应。
     */
    protected void sendResponse(MessageSender sender, String type, String seqId, Object payload) {
        sender.send(new Envelope(type, seqId, Instant.now().toString(),
                payload instanceof String s ? s : JsonUtil.toJson(payload)));
    }
}
