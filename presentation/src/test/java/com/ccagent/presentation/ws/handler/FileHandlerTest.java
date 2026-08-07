package com.ccagent.presentation.ws.handler;

import com.ccagent.api.model.envelope.Envelope;
import com.ccagent.api.port.OrchestratorPort;
import com.ccagent.infrastructure.util.JsonUtil;
import com.ccagent.presentation.ws.MessageRouter.MessageSender;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

/**
 * FileHandler 单元测试。
 * <p>使用 @TempDir 真实磁盘验证：文件列表、文件读取、路径越界防护、参数缺失校验。</p>
 */
@ExtendWith(MockitoExtension.class)
class FileHandlerTest {

    @Mock OrchestratorPort gateway;

    @TempDir Path tempDir;

    static class CapturingSender implements MessageSender {
        final List<Envelope> sent = new ArrayList<>();
        public void send(Envelope e) { sent.add(e); }
    }

    private Envelope env(String type, String seqId, Object payload) {
        return new Envelope(type, seqId, "2026-01-01T00:00:00Z", payload);
    }

    // ===== FILE_LIST_REQ =====

    @Test
    @SuppressWarnings("unchecked")
    void fileListReturnsDirectoryContents() throws Exception {
        // 在 tempDir 下创建文件和目录
        Files.writeString(tempDir.resolve("a.txt"), "hello");
        Files.createDirectory(tempDir.resolve("subdir"));

        when(gateway.loadWorkspaceRoot("s-1")).thenReturn(tempDir.toString());

        FileHandler handler = new FileHandler(gateway);
        CapturingSender sender = new CapturingSender();

        handler.handle(env("FILE_LIST_REQ", "seq-1",
                Map.of("sessionId", "s-1", "path", "/")), sender);

        assertThat(sender.sent).hasSize(1);
        Envelope resp = sender.sent.get(0);
        assertThat(resp.getType()).isEqualTo("FILE_LIST_RES");

        Map<String, Object> payload = JsonUtil.fromJson((String) resp.getPayload(), Map.class);
        List<Map<String, Object>> files = (List<Map<String, Object>>) payload.get("files");
        assertThat(files).hasSize(2);
        // 验证包含 a.txt 和 subdir
        assertThat(files).anyMatch(f -> "a.txt".equals(f.get("name")) && "file".equals(f.get("type")));
        assertThat(files).anyMatch(f -> "subdir".equals(f.get("name")) && "directory".equals(f.get("type")));
    }

    @Test
    void fileListMissingSessionIdReturnsError() {
        FileHandler handler = new FileHandler(gateway);
        CapturingSender sender = new CapturingSender();

        handler.handle(env("FILE_LIST_REQ", "seq-2", Map.of("path", "/")), sender);

        assertThat(sender.sent).hasSize(1);
        assertThat(sender.sent.get(0).getType()).isEqualTo("FILE_LIST_RES");
        String payload = (String) sender.sent.get(0).getPayload();
        assertThat(payload).contains("MISSING_SESSION_ID");
    }

    @Test
    void fileListPathTraversalReturnsInvalidPath() {
        when(gateway.loadWorkspaceRoot("s-1")).thenReturn(tempDir.toString());

        FileHandler handler = new FileHandler(gateway);
        CapturingSender sender = new CapturingSender();

        handler.handle(env("FILE_LIST_REQ", "seq-3",
                Map.of("sessionId", "s-1", "path", "../../../etc")), sender);

        assertThat(sender.sent).hasSize(1);
        String payload = (String) sender.sent.get(0).getPayload();
        assertThat(payload).contains("INVALID_PATH");
    }

    // ===== FILE_READ_REQ =====

    @Test
    @SuppressWarnings("unchecked")
    void fileReadReturnsFileContent() throws Exception {
        Files.writeString(tempDir.resolve("test.txt"), "line1\nline2");

        when(gateway.loadWorkspaceRoot("s-1")).thenReturn(tempDir.toString());

        FileHandler handler = new FileHandler(gateway);
        CapturingSender sender = new CapturingSender();

        handler.handle(env("FILE_READ_REQ", "seq-4",
                Map.of("sessionId", "s-1", "path", "/test.txt")), sender);

        assertThat(sender.sent).hasSize(1);
        Envelope resp = sender.sent.get(0);
        assertThat(resp.getType()).isEqualTo("FILE_READ_RES");

        Map<String, Object> payload = JsonUtil.fromJson((String) resp.getPayload(), Map.class);
        assertThat(payload.get("content")).isEqualTo("line1\nline2");
        assertThat(payload.get("size")).isEqualTo(11);
        assertThat(payload.get("path")).isEqualTo("/test.txt");
    }

    @Test
    void fileReadMissingParamsReturnsError() {
        FileHandler handler = new FileHandler(gateway);
        CapturingSender sender = new CapturingSender();

        handler.handle(env("FILE_READ_REQ", "seq-5",
                Map.of("sessionId", "s-1")), sender);

        String payload = (String) sender.sent.get(0).getPayload();
        assertThat(payload).contains("MISSING_PARAMS");
    }

    @Test
    void fileReadPathOutsideWorkspaceReturnsError() {
        when(gateway.loadWorkspaceRoot("s-1")).thenReturn(tempDir.toString());

        FileHandler handler = new FileHandler(gateway);
        CapturingSender sender = new CapturingSender();

        handler.handle(env("FILE_READ_REQ", "seq-6",
                Map.of("sessionId", "s-1", "path", "/../../../etc/passwd")), sender);

        String payload = (String) sender.sent.get(0).getPayload();
        // 路径越界或文件不存在（取决于 normalize 后是否仍在 wsRoot 内）
        assertThat(payload).containsAnyOf("PATH_OUTSIDE_WORKSPACE", "FILE_NOT_FOUND");
    }
}
