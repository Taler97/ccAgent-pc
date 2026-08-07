package com.ccagent.presentation.ws.handler;

import com.ccagent.api.model.envelope.Envelope;
import com.ccagent.api.port.OrchestratorPort;
import com.ccagent.core.skill.Skill;
import com.ccagent.core.skill.SkillRegistry;
import com.ccagent.infrastructure.util.JsonUtil;
import com.ccagent.plugin.tools.mcp.McpClientManager;
import com.ccagent.plugin.tools.mcp.McpServerConfig;
import com.ccagent.presentation.ws.MessageRouter.MessageSender;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 状态查询 Handler：QUERY_PROGRESS / SKILL_LIST_REQ / MCP_STATUS_REQ / MCP_IMPORT_REQ
 */
public class StatusHandler extends HandlerSupport implements WsMessageHandler {

    private final OrchestratorPort governanceGateway;
    private final SkillRegistry skillRegistry;
    private final McpClientManager mcpManager;

    public StatusHandler(OrchestratorPort governanceGateway, SkillRegistry skillRegistry,
                         McpClientManager mcpManager) {
        this.governanceGateway = governanceGateway;
        this.skillRegistry = skillRegistry;
        this.mcpManager = mcpManager;
    }

    @Override
    public List<String> supportedTypes() {
        return List.of("QUERY_PROGRESS", "SKILL_LIST_REQ", "MCP_STATUS_REQ", "MCP_IMPORT_REQ");
    }

    @Override
    public void handle(Envelope envelope, MessageSender sender) {
        switch (envelope.getType()) {
            case "QUERY_PROGRESS"  -> queryProgress(envelope, sender);
            case "SKILL_LIST_REQ"  -> skillList(envelope, sender);
            case "MCP_STATUS_REQ"  -> mcpStatus(envelope, sender);
            case "MCP_IMPORT_REQ"  -> importMcp(envelope, sender);
        }
    }

    private void queryProgress(Envelope envelope, MessageSender sender) {
        // seqId 需从 payload 中取：云端 sendToPcAndWait 会用 req-xxx 作为 envelope.seqId，
        // 而 ProgressStore 以真实执行 seqId（exec-xxx）为 key
        Map<String, Object> payload = payloadAsMap(envelope);
        String targetSeqId = payload != null && payload.get("seqId") != null
                ? String.valueOf(payload.get("seqId"))
                : envelope.getSeqId();
        String snapshot = governanceGateway.getExecutionProgress(targetSeqId);
        if (snapshot == null) {
            snapshot = "{\"seqId\":\"" + targetSeqId + "\",\"totalSteps\":0,\"completedSteps\":0,"
                    + "\"overallProgress\":0,\"steps\":[]}";
        }
        sendResponse(sender, "PROGRESS_RESP", envelope.getSeqId(), snapshot);
    }

    private void skillList(Envelope envelope, MessageSender sender) {
        List<Map<String, Object>> list = new ArrayList<>();
        if (skillRegistry != null) {
            for (Skill skill : skillRegistry.list()) {
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("name", skill.getName());
                item.put("description", skill.getDescription() != null ? skill.getDescription() : "");
                item.put("expert", skill.getExpert() != null ? skill.getExpert() : "");
                list.add(item);
            }
        }
        sendResponse(sender, "SKILL_LIST_RES", envelope.getSeqId(), Map.of("skills", list));
    }

    private void mcpStatus(Envelope envelope, MessageSender sender) {
        List<Map<String, Object>> servers = new ArrayList<>();
        if (mcpManager != null) {
            servers = mcpManager.status();
        }
        sendResponse(sender, "MCP_STATUS_RES", envelope.getSeqId(), Map.of("servers", servers));
    }

    /**
     * 导入（新增/更新）MCP server 配置并热重载。
     * payload: { configs: [ {name, transport, url, headers, command, args, enabled, toolPrefix} ] }
     */
    private void importMcp(Envelope envelope, MessageSender sender) {
        Map<String, Object> payload = payloadAsMap(envelope);
        Object raw = payload.get("configs");
        List<McpServerConfig> configs = new ArrayList<>();
        if (raw instanceof List<?> list) {
            for (Object o : list) {
                try {
                    McpServerConfig cfg = JsonUtil.getMapper().convertValue(o, McpServerConfig.class);
                    if (cfg != null) configs.add(cfg);
                } catch (Exception e) {
                    LOG.warning("MCP 导入配置解析失败: " + e.getMessage());
                }
            }
        }
        if (mcpManager == null || configs.isEmpty()) {
            sendError(sender, envelope.getSeqId(), "configs 为空或解析失败");
            return;
        }
        sendResponse(sender, "MCP_IMPORT_RES", envelope.getSeqId(), mcpManager.importConfigs(configs));
    }
}
