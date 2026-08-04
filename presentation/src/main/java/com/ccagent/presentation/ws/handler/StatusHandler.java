package com.ccagent.presentation.ws.handler;

import com.ccagent.api.model.envelope.Envelope;
import com.ccagent.api.port.OrchestratorPort;
import com.ccagent.core.skill.Skill;
import com.ccagent.core.skill.SkillRegistry;
import com.ccagent.infrastructure.util.JsonUtil;
import com.ccagent.plugin.tools.mcp.McpClientManager;
import com.ccagent.presentation.ws.MessageRouter.MessageSender;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 状态查询 Handler：QUERY_PROGRESS / SKILL_LIST_REQ / MCP_STATUS_REQ
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
        return List.of("QUERY_PROGRESS", "SKILL_LIST_REQ", "MCP_STATUS_REQ");
    }

    @Override
    public void handle(Envelope envelope, MessageSender sender) {
        switch (envelope.getType()) {
            case "QUERY_PROGRESS" -> queryProgress(envelope, sender);
            case "SKILL_LIST_REQ"  -> skillList(envelope, sender);
            case "MCP_STATUS_REQ"  -> mcpStatus(envelope, sender);
        }
    }

    private void queryProgress(Envelope envelope, MessageSender sender) {
        String seqId = envelope.getSeqId();
        String snapshot = governanceGateway.getExecutionProgress(seqId);
        if (snapshot == null) {
            snapshot = "{\"seqId\":\"" + seqId + "\",\"totalSteps\":0,\"completedSteps\":0,"
                    + "\"overallProgress\":0,\"steps\":[]}";
        }
        sendResponse(sender, "PROGRESS_RESP", seqId, snapshot);
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
}
