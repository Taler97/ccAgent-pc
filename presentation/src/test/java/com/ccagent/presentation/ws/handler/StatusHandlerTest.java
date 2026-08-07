package com.ccagent.presentation.ws.handler;

import com.ccagent.api.model.envelope.Envelope;
import com.ccagent.api.port.OrchestratorPort;
import com.ccagent.infrastructure.util.JsonUtil;
import com.ccagent.presentation.ws.MessageRouter.MessageSender;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

/**
 * StatusHandler 单元测试。
 * <p>验证：QUERY_PROGRESS 透传/空兜底、SKILL_LIST_REQ 空 registry、MCP_STATUS_REQ 空 manager。
 * SkillRegistry 和 McpClientManager 为 null 时应返回空列表而非 NPE。</p>
 */
@ExtendWith(MockitoExtension.class)
class StatusHandlerTest {

    @Mock OrchestratorPort gateway;

    static class CapturingSender implements MessageSender {
        final List<Envelope> sent = new ArrayList<>();
        public void send(Envelope e) { sent.add(e); }
    }

    private Envelope env(String type, String seqId, Object payload) {
        return new Envelope(type, seqId, "2026-01-01T00:00:00Z", payload);
    }

    // ===== QUERY_PROGRESS =====

    @Test
    void queryProgressReturnsSnapshot() {
        when(gateway.getExecutionProgress("seq-1"))
                .thenReturn("{\"totalSteps\":3,\"completedSteps\":1}");

        StatusHandler handler = new StatusHandler(gateway, null, null);
        CapturingSender sender = new CapturingSender();

        handler.handle(env("QUERY_PROGRESS", "seq-1", Map.of()), sender);

        assertThat(sender.sent).hasSize(1);
        Envelope resp = sender.sent.get(0);
        assertThat(resp.getType()).isEqualTo("PROGRESS_RESP");
        assertThat(resp.getSeqId()).isEqualTo("seq-1");
        String payload = (String) resp.getPayload();
        assertThat(payload).contains("totalSteps");
        assertThat(payload).contains("3");
    }

    @Test
    void queryProgressReturnsEmptyWhenNull() {
        when(gateway.getExecutionProgress("seq-2")).thenReturn(null);

        StatusHandler handler = new StatusHandler(gateway, null, null);
        CapturingSender sender = new CapturingSender();

        handler.handle(env("QUERY_PROGRESS", "seq-2", Map.of()), sender);

        assertThat(sender.sent).hasSize(1);
        String payload = (String) sender.sent.get(0).getPayload();
        assertThat(payload).contains("totalSteps");
        assertThat(payload).contains("0");
    }

    // ===== SKILL_LIST_REQ =====

    @Test
    @SuppressWarnings("unchecked")
    void skillListWithNullRegistryReturnsEmpty() {
        StatusHandler handler = new StatusHandler(gateway, null, null);
        CapturingSender sender = new CapturingSender();

        handler.handle(env("SKILL_LIST_REQ", "seq-3", Map.of()), sender);

        Map<String, Object> payload = JsonUtil.fromJson((String) sender.sent.get(0).getPayload(), Map.class);
        List<?> skills = (List<?>) payload.get("skills");
        assertThat(skills).isEmpty();
    }

    // ===== MCP_STATUS_REQ =====

    @Test
    @SuppressWarnings("unchecked")
    void mcpStatusWithNullManagerReturnsEmpty() {
        StatusHandler handler = new StatusHandler(gateway, null, null);
        CapturingSender sender = new CapturingSender();

        handler.handle(env("MCP_STATUS_REQ", "seq-4", Map.of()), sender);

        Map<String, Object> payload = JsonUtil.fromJson((String) sender.sent.get(0).getPayload(), Map.class);
        List<?> servers = (List<?>) payload.get("servers");
        assertThat(servers).isEmpty();
    }
}
