package com.ccagent.presentation.ws.handler;

import com.ccagent.api.model.envelope.Envelope;
import com.ccagent.infrastructure.constant.Constants;
import com.ccagent.infrastructure.util.JsonUtil;
import com.ccagent.presentation.ws.MessageRouter.MessageSender;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * MiscHandler 单元测试。
 * <p>验证：PING → PONG 响应、ON_KICK 仅日志不回信、VERSION_REQ → VERSION_RES。</p>
 */
class MiscHandlerTest {

    static class CapturingSender implements MessageSender {
        final List<Envelope> sent = new ArrayList<>();
        public void send(Envelope e) { sent.add(e); }
    }

    private Envelope env(String type, String seqId, Object payload) {
        return new Envelope(type, seqId, "2026-01-01T00:00:00Z", payload);
    }

    @Test
    void pingReturnsPong() {
        MiscHandler handler = new MiscHandler();
        CapturingSender sender = new CapturingSender();

        handler.handle(env("PING", "seq-1", Map.of()), sender);

        assertThat(sender.sent).hasSize(1);
        Envelope resp = sender.sent.get(0);
        assertThat(resp.getType()).isEqualTo("PONG");
        assertThat(resp.getSeqId()).isEqualTo("seq-1");
        assertThat((String) resp.getPayload()).contains("pong");
        assertThat((String) resp.getPayload()).contains("true");
    }

    @Test
    void onKickLogsWarningNoResponse() {
        MiscHandler handler = new MiscHandler();
        CapturingSender sender = new CapturingSender();

        handler.handle(env("ON_KICK", "seq-2", Map.of()), sender);

        // ON_KICK 只记录日志，不回信
        assertThat(sender.sent).isEmpty();
    }

    @Test
    @SuppressWarnings("unchecked")
    void versionReqReturnsVersionRes() {
        MiscHandler handler = new MiscHandler();
        CapturingSender sender = new CapturingSender();

        handler.handle(env("VERSION_REQ", "seq-3", Map.of()), sender);

        assertThat(sender.sent).hasSize(1);
        Envelope resp = sender.sent.get(0);
        assertThat(resp.getType()).isEqualTo("VERSION_RES");
        assertThat(resp.getSeqId()).isEqualTo("seq-3");

        Map<String, Object> payload = JsonUtil.fromJson((String) resp.getPayload(), Map.class);
        assertThat(payload).containsKeys("version", "name");
        assertThat(payload.get("version")).isNotNull();
        assertThat(payload.get("name")).isEqualTo(Constants.APP_NAME);
    }
}
