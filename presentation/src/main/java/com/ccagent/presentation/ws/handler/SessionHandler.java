package com.ccagent.presentation.ws.handler;

import com.ccagent.api.model.envelope.Envelope;
import com.ccagent.api.model.memory.SessionInfo;
import com.ccagent.api.port.OrchestratorPort;
import com.ccagent.infrastructure.util.JsonUtil;
import com.ccagent.presentation.ws.MessageRouter.MessageSender;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 会话管理 Handler：CREATE_SESSION / LIST_SESSIONS / WORKSPACE_VALIDATE_REQ / SESSION_STATUS_REQ
 */
public class SessionHandler extends HandlerSupport implements WsMessageHandler {

    private final OrchestratorPort governanceGateway;

    public SessionHandler(OrchestratorPort governanceGateway) {
        this.governanceGateway = governanceGateway;
    }

    @Override
    public List<String> supportedTypes() {
        return List.of("CREATE_SESSION", "LIST_SESSIONS", "WORKSPACE_VALIDATE_REQ", "SESSION_STATUS_REQ");
    }

    @Override
    public void handle(Envelope envelope, MessageSender sender) {
        switch (envelope.getType()) {
            case "CREATE_SESSION" -> createSession(envelope, sender);
            case "LIST_SESSIONS"  -> listSessions(envelope, sender);
            case "WORKSPACE_VALIDATE_REQ" -> validateWorkspace(envelope, sender);
            case "SESSION_STATUS_REQ" -> sessionStatus(envelope, sender);
        }
    }

    private void createSession(Envelope envelope, MessageSender sender) {
        String seqId = envelope.getSeqId();
        Map<String, Object> data = payloadAsMap(envelope);
        String sessionId = (String) data.getOrDefault("sessionId", seqId);
        String rootPath = (String) data.get("rootPath");

        if (rootPath == null || rootPath.isBlank()) {
            sendResponse(sender, "SESSION_CREATE_RES", seqId,
                    Map.of("success", false, "error", "WORKSPACE_PATH_REQUIRED"));
            return;
        }

        if (isPathTraversal(rootPath)) {
            sendResponse(sender, "SESSION_CREATE_RES", seqId,
                    Map.of("success", false, "error", "PATH_TRAVERSAL_DETECTED"));
            return;
        }

        Path resolved = Paths.get(rootPath).toAbsolutePath().normalize();
        if (!Files.isDirectory(resolved)) {
            sendResponse(sender, "SESSION_CREATE_RES", seqId,
                    Map.of("success", false, "error", "WORKSPACE_PATH_NOT_EXIST",
                            "path", resolved.toString()));
            return;
        }

        try {
            governanceGateway.createSession(sessionId, resolved.toString());
            sendResponse(sender, "SESSION_CREATE_RES", seqId,
                    Map.of("success", true, "sessionId", sessionId, "workspaceRoot", resolved.toString()));
        } catch (Exception e) {
            sendResponse(sender, "SESSION_CREATE_RES", seqId,
                    Map.of("success", false, "error", e.getMessage() != null ? e.getMessage() : "CREATE_FAILED"));
        }
    }

    private void validateWorkspace(Envelope envelope, MessageSender sender) {
        String seqId = envelope.getSeqId();
        Map<String, Object> data = payloadAsMap(envelope);
        String path = (String) data.get("path");

        if (path == null || path.isBlank()) {
            sendResponse(sender, "WORKSPACE_VALIDATE_RES", seqId,
                    Map.of("valid", false, "error", "WORKSPACE_PATH_REQUIRED"));
            return;
        }

        if (isPathTraversal(path)) {
            sendResponse(sender, "WORKSPACE_VALIDATE_RES", seqId,
                    Map.of("valid", false, "error", "PATH_TRAVERSAL_DETECTED"));
            return;
        }

        Path resolved = Paths.get(path).toAbsolutePath().normalize();
        boolean exists = Files.isDirectory(resolved);

        sendResponse(sender, "WORKSPACE_VALIDATE_RES", seqId,
                Map.of(
                        "valid", exists,
                        "exists", exists,
                        "absolutePath", resolved.toString(),
                        "isDirectory", exists
                ));
    }

    private void sessionStatus(Envelope envelope, MessageSender sender) {
        String seqId = envelope.getSeqId();
        Map<String, Object> data = payloadAsMap(envelope);
        String sessionId = (String) data.get("sessionId");

        if (sessionId == null || sessionId.isBlank()) {
            sendResponse(sender, "SESSION_STATUS_RES", seqId,
                    Map.of("bound", false, "error", "MISSING_SESSION_ID"));
            return;
        }

        String workspaceRoot = governanceGateway.loadWorkspaceRoot(sessionId);
        boolean bound = workspaceRoot != null && !workspaceRoot.isBlank();

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("sessionId", sessionId);
        result.put("bound", bound);
        result.put("workspaceRoot", bound ? workspaceRoot : "");
        sendResponse(sender, "SESSION_STATUS_RES", seqId, result);
    }

    private boolean isPathTraversal(String path) {
        if (path == null) return false;
        String normalized = path.replace("\\", "/").toLowerCase();
        return normalized.contains("..");
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
