package com.ccagent.presentation.ws.handler;

import com.ccagent.api.model.envelope.Envelope;
import com.ccagent.api.port.OrchestratorPort;
import com.ccagent.infrastructure.util.FileUtil;
import com.ccagent.infrastructure.util.PathUtil;
import com.ccagent.presentation.ws.MessageRouter.MessageSender;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 文件操作 Handler：FILE_LIST_REQ / FILE_READ_REQ
 */
public class FileHandler extends HandlerSupport implements WsMessageHandler {

    private final OrchestratorPort governanceGateway;

    public FileHandler(OrchestratorPort governanceGateway) {
        this.governanceGateway = governanceGateway;
    }

    @Override
    public List<String> supportedTypes() {
        return List.of("FILE_LIST_REQ", "FILE_READ_REQ");
    }

    @Override
    public void handle(Envelope envelope, MessageSender sender) {
        switch (envelope.getType()) {
            case "FILE_LIST_REQ" -> fileList(envelope, sender);
            case "FILE_READ_REQ" -> fileRead(envelope, sender);
        }
    }

    private void fileList(Envelope envelope, MessageSender sender) {
        String seqId = envelope.getSeqId();
        Map<String, Object> data = payloadAsMap(envelope);
        String sessionId = (String) data.get("sessionId");
        String path = (String) data.get("path");
        if (sessionId == null || sessionId.isBlank()) {
            sendResponse(sender, "FILE_LIST_RES", seqId,
                    "{\"files\":[],\"error\":\"MISSING_SESSION_ID\"}");
            return;
        }

        try {
            String rootPath = governanceGateway.loadWorkspaceRoot(sessionId);
            if (rootPath == null || rootPath.isBlank()) {
                sendResponse(sender, "FILE_LIST_RES", seqId,
                        "{\"files\":[],\"error\":\"WORKSPACE_NOT_BOUND\"}");
                return;
            }
            Path wsRoot = Paths.get(rootPath).toAbsolutePath().normalize();

            Path targetDir = (path == null || path.isBlank() || "/".equals(path))
                    ? wsRoot : wsRoot.resolve(path.startsWith("/") ? path.substring(1) : path).normalize();

            if (!PathUtil.isAllowedPath(wsRoot.toString(), targetDir.toString()) || !Files.isDirectory(targetDir)) {
                sendResponse(sender, "FILE_LIST_RES", seqId,
                        "{\"files\":[],\"error\":\"INVALID_PATH\"}");
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

            sendResponse(sender, "FILE_LIST_RES", seqId, Map.of("files", fileList));

        } catch (Exception e) {
            sendResponse(sender, "FILE_LIST_RES", seqId,
                    "{\"files\":[],\"error\":\"" + e.getMessage() + "\"}");
        }
    }

    private void fileRead(Envelope envelope, MessageSender sender) {
        String seqId = envelope.getSeqId();
        Map<String, Object> data = payloadAsMap(envelope);
        String sessionId = (String) data.get("sessionId");
        String filePath = (String) data.get("path");
        if (sessionId == null || sessionId.isBlank() || filePath == null || filePath.isBlank()) {
            sendResponse(sender, "FILE_READ_RES", seqId,
                    "{\"error\":\"MISSING_PARAMS\"}");
            return;
        }

        try {
            String rootPath = governanceGateway.loadWorkspaceRoot(sessionId);
            if (rootPath == null || rootPath.isBlank()) {
                sendResponse(sender, "FILE_READ_RES", seqId,
                        "{\"error\":\"WORKSPACE_NOT_BOUND\"}");
                return;
            }
            Path wsRoot = Paths.get(rootPath).toAbsolutePath().normalize();
            Path target = wsRoot.resolve(filePath.startsWith("/") ? filePath.substring(1) : filePath).normalize();

            if (!PathUtil.isAllowedPath(wsRoot.toString(), target.toString())) {
                sendResponse(sender, "FILE_READ_RES", seqId,
                        "{\"error\":\"PATH_OUTSIDE_WORKSPACE\"}");
                return;
            }
            if (!Files.isRegularFile(target)) {
                sendResponse(sender, "FILE_READ_RES", seqId,
                        "{\"error\":\"FILE_NOT_FOUND\"}");
                return;
            }

            String content = FileUtil.readString(target);
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("path", "/" + wsRoot.relativize(target).toString().replace("\\", "/"));
            result.put("content", content);
            result.put("size", Files.size(target));

            sendResponse(sender, "FILE_READ_RES", seqId, result);

        } catch (Exception e) {
            sendResponse(sender, "FILE_READ_RES", seqId,
                    "{\"error\":\"" + e.getMessage() + "\"}");
        }
    }
}
