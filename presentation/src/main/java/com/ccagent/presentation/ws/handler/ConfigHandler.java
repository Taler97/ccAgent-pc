package com.ccagent.presentation.ws.handler;

import com.ccagent.adapter.llm.DeepSeekLlmAdapter;
import com.ccagent.api.model.envelope.Envelope;
import com.ccagent.infrastructure.config.AppConfig;
import com.ccagent.presentation.ws.MessageRouter.MessageSender;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 配置热更 Handler：UPDATE_LLM_CONFIG
 * <p>接收云端转发的 LLM 配置更新指令（思考模式开关 / 思考强度），
 * 调用 {@link DeepSeekLlmAdapter#updateThinkingConfig} 免重启生效，
 * 并同步到 {@link AppConfig} 以便后续持久化或其他模块读取。</p>
 */
public class ConfigHandler extends HandlerSupport implements WsMessageHandler {

    private final DeepSeekLlmAdapter llmAdapter;
    private final AppConfig appConfig;

    public ConfigHandler(DeepSeekLlmAdapter llmAdapter, AppConfig appConfig) {
        this.llmAdapter = llmAdapter;
        this.appConfig = appConfig;
    }

    @Override
    public List<String> supportedTypes() {
        return List.of("UPDATE_LLM_CONFIG", "QUERY_LLM_CONFIG");
    }

    @Override
    public void handle(Envelope envelope, MessageSender sender) {
        switch (envelope.getType()) {
            case "UPDATE_LLM_CONFIG" -> updateLlmConfig(envelope, sender);
            case "QUERY_LLM_CONFIG"  -> queryLlmConfig(envelope, sender);
        }
    }

    private void queryLlmConfig(Envelope envelope, MessageSender sender) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("thinkingEnabled", appConfig.getLlm().isThinkingEnabled());
        data.put("reasoningEffort", appConfig.getLlm().getReasoningEffort());
        data.put("model", appConfig.getLlm().getModel());
        data.put("baseUrl", appConfig.getLlm().getBaseUrl());
        sendResponse(sender, "LLM_CONFIG_RESP", envelope.getSeqId(), data);
    }

    private void updateLlmConfig(Envelope envelope, MessageSender sender) {
        String seqId = envelope.getSeqId();
        try {
            Map<String, Object> payload = payloadAsMap(envelope);
            if (payload == null || payload.isEmpty()) {
                sendError(sender, seqId, "UPDATE_LLM_CONFIG payload 为空");
                return;
            }

            // thinkingEnabled：可选，未传则保持原值
            boolean newEnabled;
            if (payload.containsKey("thinkingEnabled")) {
                Object v = payload.get("thinkingEnabled");
                newEnabled = v instanceof Boolean ? (Boolean) v : Boolean.parseBoolean(String.valueOf(v));
            } else {
                newEnabled = appConfig.getLlm().isThinkingEnabled();
            }

            // reasoningEffort：可选，未传则保持原值；接受 high / max（low/medium/xhigh 由云端规范化）
            String newEffort;
            if (payload.containsKey("reasoningEffort")) {
                newEffort = String.valueOf(payload.get("reasoningEffort"));
            } else {
                newEffort = appConfig.getLlm().getReasoningEffort();
            }

            // 1. 热更适配器（生效于下一次 chat / chatStream 调用）
            if (llmAdapter != null) {
                llmAdapter.updateThinkingConfig(newEnabled, newEffort);
            }
            // 2. 同步到 AppConfig（供其他模块读取、持久化或重启后恢复）
            appConfig.getLlm().setThinkingEnabled(newEnabled);
            appConfig.getLlm().setReasoningEffort(newEffort);

            LOG.info("LLM 配置已热更: thinkingEnabled=" + newEnabled + ", reasoningEffort=" + newEffort);

            Map<String, Object> resp = new LinkedHashMap<>();
            resp.put("ok", true);
            resp.put("thinkingEnabled", newEnabled);
            resp.put("reasoningEffort", newEffort);
            resp.put("model", appConfig.getLlm().getModel());
            sendResponse(sender, "LLM_CONFIG_UPDATED", seqId, resp);

        } catch (Exception e) {
            LOG.warning("UPDATE_LLM_CONFIG 处理失败: " + e.getMessage());
            sendError(sender, seqId, "LLM 配置更新失败: " + e.getMessage());
        }
    }
}
