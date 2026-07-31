package com.ccagent.presentation.ws;

import com.ccagent.api.model.envelope.Envelope;
import com.ccagent.api.model.memory.SessionInfo;
import com.ccagent.api.model.request.ExecuteRequest;
import com.ccagent.api.model.response.StreamChunk;
import com.ccagent.api.port.OrchestratorPort;
import com.ccagent.api.port.StreamObserver;
import com.ccagent.infrastructure.util.FileUtil;
import com.ccagent.infrastructure.util.JsonUtil;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import java.util.logging.Logger;

public class MessageRouter {

    private static final Logger LOG = Logger.getLogger(MessageRouter.class.getName());

    private final OrchestratorPort governanceGateway;
    private final Map<String, ReentrantLock> seqLocks = new ConcurrentHashMap<>();

    public MessageRouter(OrchestratorPort governanceGateway) {
        this.governanceGateway = governanceGateway;
    }

    public void route(String message, MessageSender sender) {
        Envelope envelope;
        try {
            envelope = JsonUtil.fromJson(message, Envelope.class);
        } catch (Exception e) {
            String preview = message.length() > 200 ? message.substring(0, 200) + "...(截断)" : message;
            LOG.warning("消息解析失败: " + e.getClass().getSimpleName() + " -> " + e.getMessage()
                    + " | 消息长度=" + message.length()
                    + " | 消息头: " + preview);
            return;
        }

        String type = envelope.getType();
        String seqId = envelope.getSeqId();
        String payload;
        Object rawPayload = envelope.getPayload();
        if (rawPayload instanceof String s) {
            payload = s;
        } else {
            payload = JsonUtil.toJson(rawPayload);
        }

        ReentrantLock lock = seqLocks.computeIfAbsent(seqId, k -> new ReentrantLock());
        lock.lock();
        try {
            switch (type) {
                case "CREATE_SESSION" -> routeCreateSession(seqId, payload);
                case "LIST_SESSIONS" -> routeListSessions(seqId, sender);
                case "EXECUTE" -> routeExecute(seqId, payload, sender);
                case "CANCEL_EXECUTION" -> routeCancel(seqId, sender);
                case "ROLLBACK_DIALOG" -> routeRollback(seqId, sender);
                case "TOOL_APPROVAL" -> routeApproval(seqId, payload, sender);
                case "EXECUTE_PLAN" -> routeExecutePlan(seqId, payload, sender);
                case "CANCEL_PLAN" -> routeCancelPlan(seqId, sender);
                case "FILE_LIST_REQ" -> routeFileList(seqId, payload, sender);
                case "FILE_READ_REQ" -> routeFileRead(seqId, payload, sender);
                case "ON_KICK" -> routeOnKick(seqId);
                default -> {}
            }
        } finally {
            lock.unlock();
            seqLocks.remove(seqId);
        }
    }

    private void routeExecute(String seqId, String payload, MessageSender sender) {
        sendAgentStatus(sender, seqId, "PLANNING", "正在生成执行计划...");

        ExecuteRequest request = JsonUtil.fromJson(payload, ExecuteRequest.class);
        governanceGateway.executeWithSeqId(request, seqId, new StreamObserver<>() {
            @Override
            public void onNext(StreamChunk chunk) {
                sender.send(new Envelope(chunk.getType(), seqId,
                        java.time.Instant.now().toString(), chunk.getContent()));
            }

            @Override
            public void onError(Throwable error) {
                sendAgentStatus(sender, seqId, "ERROR", error.getMessage());
                sender.send(new Envelope("ERROR", seqId,
                        java.time.Instant.now().toString(),
                        "{\"error\":\"" + error.getMessage() + "\"}"));
            }

            @Override
            public void onComplete() {
                sendAgentStatus(sender, seqId, "IDLE", "");
                sender.send(new Envelope("EXECUTION_RESULT", seqId,
                        java.time.Instant.now().toString(),
                        "{\"status\":\"COMPLETED\"}"));
            }
        });
    }

    private void routeCreateSession(String seqId, String payload) {
        Map<String, Object> data = JsonUtil.fromJson(payload, Map.class);
        String sessionId = (String) data.getOrDefault("sessionId", seqId);
        String rootPath = (String) data.get("rootPath");
        if (rootPath == null || rootPath.isBlank()) {
            LOG.warning("CREATE_SESSION: rootPath 不能为空");
            return;
        }
        governanceGateway.createSession(sessionId, rootPath);
    }

    private void routeListSessions(String seqId, MessageSender sender) {
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
        sender.send(new Envelope("SESSION_LIST_RES", seqId,
                java.time.Instant.now().toString(),
                JsonUtil.toJson(Map.of("sessions", list))));
    }

    private void routeCancel(String seqId, MessageSender sender) {
        sendAgentStatus(sender, seqId, "IDLE", "已取消执行");
        governanceGateway.cancelExecution(seqId);
    }

    private void routeRollback(String seqId, MessageSender sender) {
        sendAgentStatus(sender, seqId, "ROLLBACK", "正在回滚文件变更...");
        boolean success = governanceGateway.rollbackDialog(seqId);
        sendAgentStatus(sender, seqId, "IDLE", success ? "回滚成功" : "回滚失败");
        String status = success ? "SUCCESS" : "FAILED";
        sender.send(new Envelope("ROLLBACK_RESULT", seqId,
                java.time.Instant.now().toString(),
                "{\"status\":\"" + status + "\"}"));
    }

    private void routeApproval(String seqId, String payload, MessageSender sender) {
        Map<String, Object> data = JsonUtil.fromJson(payload, Map.class);
        boolean approved = Boolean.TRUE.equals(data.get("approved"));
        governanceGateway.onToolApproval(seqId, approved);
    }

    private void routeExecutePlan(String seqId, String payload, MessageSender sender) {
        sendAgentStatus(sender, seqId, "EXECUTING", "正在执行任务...");
        Map<String, Object> data = JsonUtil.fromJson(payload, Map.class);
        boolean confirmed = Boolean.TRUE.equals(data.get("confirmed"));
        governanceGateway.executePlan(seqId, confirmed);
    }

    private void routeCancelPlan(String seqId, MessageSender sender) {
        sendAgentStatus(sender, seqId, "IDLE", "已取消 Plan");
        governanceGateway.cancelPlan(seqId);
    }

    private void routeFileList(String seqId, String payload, MessageSender sender) {
        Map<String, Object> data = JsonUtil.fromJson(payload, Map.class);
        String sessionId = (String) data.get("sessionId");
        String path = (String) data.get("path");
        if (sessionId == null || sessionId.isBlank()) {
            sender.send(new Envelope("FILE_LIST_RES", seqId,
                    java.time.Instant.now().toString(),
                    "{\"files\":[],\"error\":\"MISSING_SESSION_ID\"}"));
            return;
        }

        try {
            String rootPath = governanceGateway.loadWorkspaceRoot(sessionId);
            Path wsRoot = Paths.get(rootPath).normalize();

            Path targetDir = (path == null || path.isBlank() || "/".equals(path))
                    ? wsRoot : wsRoot.resolve(path.startsWith("/") ? path.substring(1) : path).normalize();

            if (!targetDir.startsWith(wsRoot) || !Files.isDirectory(targetDir)) {
                sender.send(new Envelope("FILE_LIST_RES", seqId,
                        java.time.Instant.now().toString(),
                        "{\"files\":[],\"error\":\"INVALID_PATH\"}"));
                return;
            }

            List<Path> entries = FileUtil.listFiles(targetDir);
            List<Map<String, Object>> fileList = new ArrayList<>();
            for (Path entry : entries) {
                Map<String, Object> node = new LinkedHashMap<>();
                String relPath = "/" + wsRoot.relativize(entry).toString().replace("\\", "/");
                node.put("name", entry.getFileName().toString());
                node.put("path", relPath);
                node.put("type", Files.isDirectory(entry) ? "directory" : "file");
                fileList.add(node);
            }

            sender.send(new Envelope("FILE_LIST_RES", seqId,
                    java.time.Instant.now().toString(),
                    JsonUtil.toJson(Map.of("files", fileList))));

        } catch (Exception e) {
            sender.send(new Envelope("FILE_LIST_RES", seqId,
                    java.time.Instant.now().toString(),
                    "{\"files\":[],\"error\":\"" + e.getMessage() + "\"}"));
        }
    }

    private void routeFileRead(String seqId, String payload, MessageSender sender) {
        Map<String, Object> data = JsonUtil.fromJson(payload, Map.class);
        String sessionId = (String) data.get("sessionId");
        String filePath = (String) data.get("path");
        if (sessionId == null || sessionId.isBlank() || filePath == null || filePath.isBlank()) {
            sender.send(new Envelope("FILE_READ_RES", seqId,
                    java.time.Instant.now().toString(),
                    "{\"error\":\"MISSING_PARAMS\"}"));
            return;
        }

        try {
            String rootPath = governanceGateway.loadWorkspaceRoot(sessionId);
            Path wsRoot = Paths.get(rootPath).normalize();
            Path target = wsRoot.resolve(filePath.startsWith("/") ? filePath.substring(1) : filePath).normalize();

            if (!target.startsWith(wsRoot)) {
                sender.send(new Envelope("FILE_READ_RES", seqId,
                        java.time.Instant.now().toString(),
                        "{\"error\":\"PATH_OUTSIDE_WORKSPACE\"}"));
                return;
            }
            if (!Files.isRegularFile(target)) {
                sender.send(new Envelope("FILE_READ_RES", seqId,
                        java.time.Instant.now().toString(),
                        "{\"error\":\"FILE_NOT_FOUND\"}"));
                return;
            }

            String content = FileUtil.readString(target);
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("path", "/" + wsRoot.relativize(target).toString().replace("\\", "/"));
            result.put("content", content);
            result.put("size", Files.size(target));

            sender.send(new Envelope("FILE_READ_RES", seqId,
                    java.time.Instant.now().toString(),
                    JsonUtil.toJson(result)));

        } catch (Exception e) {
            sender.send(new Envelope("FILE_READ_RES", seqId,
                    java.time.Instant.now().toString(),
                    "{\"error\":\"" + e.getMessage() + "\"}"));
        }
    }

    private void routeOnKick(String seqId) {
        LOG.warning("收到云端踢出通知（ON_KICK），旧连接已被替换");
    }

    private static void sendAgentStatus(MessageSender sender, String seqId, String agentState, String operation) {
        String payload = "{\"agentState\":\"" + agentState + "\",\"currentOperation\":\""
                + operation.replace("\"", "'") + "\"}";
        sender.send(new Envelope("AGENT_STATUS", seqId,
                java.time.Instant.now().toString(), payload));
    }

    public interface MessageSender {
        void send(Envelope envelope);
    }
}
