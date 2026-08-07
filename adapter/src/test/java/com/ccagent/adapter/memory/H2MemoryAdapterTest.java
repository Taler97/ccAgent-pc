package com.ccagent.adapter.memory;

import com.ccagent.api.model.memory.ConversationTurn;
import com.ccagent.api.model.memory.SessionInfo;
import com.ccagent.api.model.memory.StructuredContext;
import com.ccagent.api.model.memory.UserPreferences;
import com.ccagent.api.port.LlmPort;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import javax.sql.DataSource;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * H2MemoryAdapter 集成测试（真实 H2 内存库 + mock LLM）
 */
@ExtendWith(MockitoExtension.class)
class H2MemoryAdapterTest {

    @Mock
    LlmPort llmPort;

    private H2MemoryAdapter adapter;

    @BeforeEach
    void setUp() {
        JdbcDataSource ds = new JdbcDataSource();
        ds.setURL("jdbc:h2:mem:adapter-test-" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1");
        ds.setUser("sa");
        ds.setPassword("");
        DataSource dataSource = ds;
        adapter = new H2MemoryAdapter(dataSource, llmPort);
    }

    private ConversationTurn turn(String content) {
        return new ConversationTurn("user", content, 5);
    }

    // ==================== 会话管理 ====================

    @Test
    void shouldCreateAndLoadWorkspaceRoot() {
        adapter.createSession("s1", "w1", "/root");

        assertThat(adapter.loadWorkspaceRoot("s1")).isEqualTo("/root");
    }

    @Test
    void shouldListSessions() {
        adapter.createSession("s1", "w1", "/root1");
        adapter.createSession("s2", "w2", "/root2");

        List<SessionInfo> sessions = adapter.listSessions();
        assertThat(sessions).extracting(SessionInfo::getSessionId)
                .containsExactlyInAnyOrder("s1", "s2");
    }

    // ==================== 对话轮次 ====================

    @Test
    void shouldSaveAndLoadTurns() {
        adapter.createSession("s1", "w1", "/root");

        adapter.saveTurn("s1", turn("你好"));
        adapter.saveTurn("s1", new ConversationTurn("assistant", "在的", 5));

        StructuredContext ctx = adapter.loadStructuredContext("s1");
        assertThat(ctx.getTotalTurns()).isEqualTo(2);
        assertThat(ctx.getRecentTurns()).hasSize(2);
        assertThat(adapter.getTotalTurns("s1")).isEqualTo(2);
    }

    @Test
    void shouldReturnLimitedRecentTurns() {
        adapter.createSession("s1", "w1", "/root");
        for (int i = 0; i < 5; i++) {
            adapter.saveTurn("s1", turn("内容-" + i));
        }

        List<ConversationTurn> recent = adapter.getRecentTurns("s1", 3);
        assertThat(recent).hasSize(3);
        assertThat(recent.get(0).getContent()).isEqualTo("内容-2");
        assertThat(recent.get(2).getContent()).isEqualTo("内容-4");
    }

    // ==================== 压缩 ====================

    @Test
    void shouldCompressWhenTurnsExceedThreshold() {
        when(llmPort.chat(any())).thenReturn(
                new com.ccagent.api.model.response.ChatResponse("压缩摘要", null, 100, 0, "stop"));

        adapter.createSession("s1", "w1", "/root");
        // 31 轮触发压缩（>30）：压缩最早 10 轮，剩余 21 轮
        for (int i = 0; i < 31; i++) {
            adapter.saveTurn("s1", turn("内容-" + i));
        }

        StructuredContext ctx = adapter.loadStructuredContext("s1");
        assertThat(ctx.getSummary()).isEqualTo("压缩摘要");
        assertThat(ctx.getTotalTurns()).isEqualTo(21);
        assertThat(ctx.getRecentTurns()).hasSize(21);
        assertThat(ctx.getRecentTurns().get(0).getContent()).isEqualTo("内容-10");
    }

    @Test
    void shouldDegradeToDirectSaveWhenCompressionFails() {
        when(llmPort.chat(any())).thenThrow(new RuntimeException("LLM 失败"));

        adapter.createSession("s1", "w1", "/root");
        for (int i = 0; i < 31; i++) {
            adapter.saveTurn("s1", turn("内容-" + i));
        }

        // 压缩失败降级：仍保存全部 31 轮
        StructuredContext ctx = adapter.loadStructuredContext("s1");
        assertThat(ctx.getTotalTurns()).isEqualTo(31);
        assertThat(ctx.getRecentTurns()).hasSize(31);
    }

    @Test
    void shouldCompressOnDemand() {
        when(llmPort.chat(any())).thenReturn(
                new com.ccagent.api.model.response.ChatResponse("按需摘要", null, 100, 0, "stop"));

        adapter.createSession("s1", "w1", "/root");
        adapter.saveTurn("s1", turn("内容-0"));
        adapter.saveTurn("s1", turn("内容-1"));
        adapter.saveTurn("s1", turn("内容-2"));

        adapter.compressContext("s1");

        StructuredContext ctx = adapter.loadStructuredContext("s1");
        assertThat(ctx.getSummary()).isEqualTo("按需摘要");
        assertThat(ctx.getTotalTurns()).isEqualTo(2); // 3 条压缩 1 条，剩余 2 条
    }

    @Test
    void shouldNotThrowWhenCompressingEmptySession() {
        // 回归：空会话 compressContext 不应抛 IndexOutOfBoundsException（bug #1）
        adapter.createSession("s1", "w1", "/root");

        org.assertj.core.api.Assertions.assertThatCode(() -> adapter.compressContext("s1"))
                .doesNotThrowAnyException();
    }

    // ==================== 偏好 ====================

    @Test
    void shouldLoadAndSavePreferences() {
        UserPreferences prefs = adapter.loadPreferences("w1");
        assertThat(prefs.getTechStack()).isEqualTo("{}");

        adapter.savePreferences("w1", new UserPreferences(
                "{\"java\":21}", "{\"style\":\"google\"}", "{}"));

        UserPreferences updated = adapter.loadPreferences("w1");
        assertThat(updated.getTechStack()).isEqualTo("{\"java\":21}");
        assertThat(updated.getCodeStyle()).isEqualTo("{\"style\":\"google\"}");
    }
}
