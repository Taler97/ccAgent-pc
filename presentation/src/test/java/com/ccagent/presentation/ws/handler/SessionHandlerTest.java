package com.ccagent.presentation.ws.handler;

import com.ccagent.api.model.envelope.Envelope;
import com.ccagent.api.model.memory.SessionInfo;
import com.ccagent.api.port.OrchestratorPort;
import com.ccagent.infrastructure.util.JsonUtil;
import com.ccagent.presentation.ws.MessageRouter.MessageSender;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * SessionHandler 单元测试。
 * <p>验证：CREATE_SESSION 委托正确、rootPath 空值校验、seqId 默认 sessionId、
 * LIST_SESSIONS 响应结构。</p>
 */
@ExtendWith(MockitoExtension.class)
class SessionHandlerTest {

    @Mock OrchestratorPort gateway;

    static class CapturingSender implements MessageSender {
        final List<Envelope> sent = new ArrayList<>();
        public void send(Envelope e) { sent.add(e); }
    }

    private Envelope env(String type, String seqId, Object payload) {
        return new Envelope(type, seqId, "2026-01-01T00:00:00Z", payload);
    }

    // ===== CREATE_SESSION =====

    @Test
    void createSessionWithValidRootPath() {
        String tmpDir = System.getProperty("java.io.tmpdir");
        SessionHandler handler = new SessionHandler(gateway);
        Envelope envelope = env("CREATE_SESSION", "seq-1",
                Map.of("sessionId", "s-1", "rootPath", tmpDir));

        handler.handle(envelope, new CapturingSender());

        String expected = Path.of(tmpDir).toAbsolutePath().normalize().toString();
        verify(gateway).createSession("s-1", expected);
    }

    @Test
    void createSessionWithNullRootPath() {
        SessionHandler handler = new SessionHandler(gateway);
        Envelope envelope = env("CREATE_SESSION", "seq-2", Map.of("sessionId", "s-2"));

        CapturingSender sender = new CapturingSender();
        handler.handle(envelope, sender);

        verify(gateway, never()).createSession(anyString(), anyString());
        assertThat(sender.sent).hasSize(1);
        assertThat(sender.sent.get(0).getType()).isEqualTo("SESSION_CREATE_RES");
    }

    @Test
    void createSessionWithNonExistentPath() {
        SessionHandler handler = new SessionHandler(gateway);
        Envelope envelope = env("CREATE_SESSION", "seq-ne",
                Map.of("sessionId", "s-ne", "rootPath", "/nonexistent/path/xyz"));

        CapturingSender sender = new CapturingSender();
        handler.handle(envelope, sender);

        verify(gateway, never()).createSession(anyString(), anyString());
        assertThat(sender.sent).hasSize(1);
        assertThat(sender.sent.get(0).getType()).isEqualTo("SESSION_CREATE_RES");
    }

    @Test
    void createSessionUsesSeqIdAsDefault() {
        String tmpDir = System.getProperty("java.io.tmpdir");
        SessionHandler handler = new SessionHandler(gateway);
        // payload 无 sessionId，应回退到 seqId
        Envelope envelope = env("CREATE_SESSION", "fallback-seq",
                Map.of("rootPath", tmpDir));

        handler.handle(envelope, new CapturingSender());

        String expected = Path.of(tmpDir).toAbsolutePath().normalize().toString();
        verify(gateway).createSession("fallback-seq", expected);
    }

    // ===== WORKSPACE_VALIDATE_REQ =====

    @Test
    @SuppressWarnings("unchecked")
    void validateWorkspaceWithExistingPath() {
        String tmpDir = System.getProperty("java.io.tmpdir");
        SessionHandler handler = new SessionHandler(gateway);
        Envelope envelope = env("WORKSPACE_VALIDATE_REQ", "val-1",
                Map.of("path", tmpDir));

        CapturingSender sender = new CapturingSender();
        handler.handle(envelope, sender);

        assertThat(sender.sent).hasSize(1);
        assertThat(sender.sent.get(0).getType()).isEqualTo("WORKSPACE_VALIDATE_RES");
        Map<String, Object> payload = JsonUtil.fromJson((String) sender.sent.get(0).getPayload(), Map.class);
        assertThat(payload.get("valid")).isEqualTo(true);
        assertThat(payload.get("exists")).isEqualTo(true);
    }

    @Test
    @SuppressWarnings("unchecked")
    void validateWorkspaceWithNonExistentPath() {
        SessionHandler handler = new SessionHandler(gateway);
        Envelope envelope = env("WORKSPACE_VALIDATE_REQ", "val-2",
                Map.of("path", "/nonexistent/path/xyz"));

        CapturingSender sender = new CapturingSender();
        handler.handle(envelope, sender);

        assertThat(sender.sent).hasSize(1);
        Map<String, Object> payload = JsonUtil.fromJson((String) sender.sent.get(0).getPayload(), Map.class);
        assertThat(payload.get("valid")).isEqualTo(false);
        assertThat(payload.get("exists")).isEqualTo(false);
    }

    // ===== SESSION_STATUS_REQ =====

    @Test
    @SuppressWarnings("unchecked")
    void sessionStatusWhenBound() {
        SessionHandler handler = new SessionHandler(gateway);
        when(gateway.loadWorkspaceRoot("s-bound")).thenReturn("/some/path");

        CapturingSender sender = new CapturingSender();
        handler.handle(env("SESSION_STATUS_REQ", "ss-1", Map.of("sessionId", "s-bound")), sender);

        assertThat(sender.sent).hasSize(1);
        assertThat(sender.sent.get(0).getType()).isEqualTo("SESSION_STATUS_RES");
        Map<String, Object> payload = JsonUtil.fromJson((String) sender.sent.get(0).getPayload(), Map.class);
        assertThat(payload.get("bound")).isEqualTo(true);
        assertThat(payload.get("workspaceRoot")).isEqualTo("/some/path");
    }

    @Test
    @SuppressWarnings("unchecked")
    void sessionStatusWhenNotBound() {
        SessionHandler handler = new SessionHandler(gateway);
        when(gateway.loadWorkspaceRoot("s-free")).thenReturn(null);

        CapturingSender sender = new CapturingSender();
        handler.handle(env("SESSION_STATUS_REQ", "ss-2", Map.of("sessionId", "s-free")), sender);

        Map<String, Object> payload = JsonUtil.fromJson((String) sender.sent.get(0).getPayload(), Map.class);
        assertThat(payload.get("bound")).isEqualTo(false);
    }

    // ===== LIST_SESSIONS =====

    @Test
    @SuppressWarnings("unchecked")
    void listSessionsReturnsSessionListRes() {
        SessionHandler handler = new SessionHandler(gateway);
        when(gateway.listSessions()).thenReturn(List.of(
                new SessionInfo("s-1", "ws-1", "/root1", 5, "summary1",
                        Instant.parse("2026-01-01T00:00:00Z"),
                        Instant.parse("2026-01-02T00:00:00Z"))
        ));

        CapturingSender sender = new CapturingSender();
        handler.handle(env("LIST_SESSIONS", "seq-3", Map.of()), sender);

        assertThat(sender.sent).hasSize(1);
        Envelope resp = sender.sent.get(0);
        assertThat(resp.getType()).isEqualTo("SESSION_LIST_RES");
        assertThat(resp.getSeqId()).isEqualTo("seq-3");

        Map<String, Object> payload = JsonUtil.fromJson((String) resp.getPayload(), Map.class);
        List<Map<String, Object>> sessions = (List<Map<String, Object>>) payload.get("sessions");
        assertThat(sessions).hasSize(1);
        assertThat(sessions.get(0).get("sessionId")).isEqualTo("s-1");
        assertThat(sessions.get(0).get("rootPath")).isEqualTo("/root1");
        assertThat(sessions.get(0).get("totalTurns")).isEqualTo(5);
    }
}
