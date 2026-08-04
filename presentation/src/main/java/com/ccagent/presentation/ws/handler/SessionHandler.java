package com.ccagent.presentation.ws.handler;

import com.ccagent.api.model.envelope.Envelope;
import com.ccagent.api.model.memory.SessionInfo;
import com.ccagent.api.port.OrchestratorPort;
import com.ccagent.infrastructure.util.JsonUtil;
import com.ccagent.presentation.ws.MessageRouter.MessageSender;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 会话管理 Handler：CREATE_SESSION / LIST_SESSIONS
 */
public class SessionHandler extends HandlerSupport implements WsMessageHandler {

    private final OrchestratorPort governanceGateway;

    public SessionHandler(OrchestratorPort governanceGateway) {
        this.governanceGateway = governanceGateway;
    }

    @Override
    public List<String> supportedTypes() {
        return List.of("CREATE_SESSION", "LIST_SESSIONS");
    }

    @Override
    public void handle(Envelope envelope, MessageSender sender) {
        switch (envelope.getType()) {
            case "CREATE_SESSION" -> createSession(envelope);
            case "LIST_SESSIONS"  -> listSessions(envelope, sender);
        }
    }

    private void createSession(Envelope envelope) {
        Map<String, Object> data = payloadAsMap(envelope);
        String sessionId = (String) data.getOrDefault("sessionId", envelope.getSeqId());
        String rootPath = (String) data.get("rootPath");
        if (rootPath == null || rootPath.isBlank()) {
            LOG.warning("CREATE_SESSION: rootPath 不能为空");
            return;
        }
        governanceGateway.createSession(sessionId, rootPath);
    }

    private void listSessions(Envelope envelope, MessageSender sender) {
        List<SessionInfo> sessions = governanceGateway.listSessions();
        List<Map<String, Object>> list = new ArrayList<>();
        for (SessionInfo s : sessions) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("sessionId", s.getSessionId());
            item.put("rootPath", s.getRootPath());
            item.put("totalTurns", s.getTotalTurns());
            item.put("summary", s.getSummary() != null ? s.getSummary() : "");
            item.put("createdAt", s.getCreatedAt() != null ? s.getCreatedAt().toString() : "");
            item.put("updatedAt", s.getUpdatedAt() != null ? s.getUpdatedAt().toString() : "");
            list.add(item);
        }
        sendResponse(sender, "SESSION_LIST_RES", envelope.getSeqId(), Map.of("sessions", list));
    }
}
