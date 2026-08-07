package com.ccagent.adapter.memory.repository;

import com.ccagent.adapter.memory.schema.DatabaseInitializer;
import com.ccagent.api.model.memory.ConversationTurn;
import com.ccagent.api.model.memory.SessionInfo;
import com.ccagent.api.model.memory.StructuredContext;
import com.ccagent.exception.workspace.WorkspaceException;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * SessionRepository 集成测试（真实 H2 内存库）
 */
class SessionRepositoryTest {

    private SessionRepository repository;

    @BeforeEach
    void setUp() {
        JdbcDataSource ds = new JdbcDataSource();
        ds.setURL("jdbc:h2:mem:session-test-" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1");
        ds.setUser("sa");
        ds.setPassword("");
        DataSource dataSource = ds;
        new DatabaseInitializer(dataSource).initialize();
        repository = new SessionRepository(dataSource);
    }

    // ==================== 创建与读取 ====================

    @Test
    void shouldCreateAndLoadEmptySession() {
        repository.createSession("s1", "w1", "/root");

        StructuredContext ctx = repository.loadStructuredContext("s1");
        assertThat(ctx.getSummary()).isEmpty();
        assertThat(ctx.getRecentTurns()).isEmpty();
        assertThat(ctx.getTotalTurns()).isZero();
    }

    @Test
    void shouldCreateSessionIdempotently() {
        repository.createSession("s1", "w1", "/root");
        repository.createSession("s1", "w1", "/root"); // MERGE 不抛异常

        assertThat(repository.getTotalTurns("s1")).isZero();
    }

    @Test
    void shouldThrowWhenSessionMissing() {
        assertThatThrownBy(() -> repository.loadStructuredContext("missing"))
                .isInstanceOf(WorkspaceException.class);
    }

    // ==================== 记忆更新 ====================

    @Test
    void shouldUpdateMemoryAndReadBack() {
        repository.createSession("s1", "w1", "/root");

        repository.updateMemory("s1", "摘要", "[{\"role\":\"user\",\"content\":\"你好\",\"tokenCount\":5}]", 1);

        StructuredContext ctx = repository.loadStructuredContext("s1");
        assertThat(ctx.getSummary()).isEqualTo("摘要");
        assertThat(ctx.getTotalTurns()).isEqualTo(1);
        assertThat(ctx.getRecentTurns()).hasSize(1);
        assertThat(ctx.getRecentTurns().get(0).getRole()).isEqualTo("user");
        assertThat(ctx.getRecentTurns().get(0).getContent()).isEqualTo("你好");
    }

    @Test
    void shouldTrackTotalTurns() {
        repository.createSession("s1", "w1", "/root");
        repository.updateMemory("s1", "", "[]", 5);

        assertThat(repository.getTotalTurns("s1")).isEqualTo(5);
    }

    // ==================== 工作区 ====================

    @Test
    void shouldReturnWorkspaceIdAndRootPath() {
        repository.createSession("s1", "w1", "/workspace/root");

        assertThat(repository.getWorkspaceId("s1")).isEqualTo("w1");
        assertThat(repository.getRootPath("s1")).isEqualTo("/workspace/root");
    }

    @Test
    void shouldThrowWhenRootPathBlank() {
        repository.createSession("s1", "w1", "");

        assertThatThrownBy(() -> repository.getRootPath("s1"))
                .isInstanceOf(WorkspaceException.class);
    }

    // ==================== 会话列表 ====================

    @Test
    void shouldListAllSessionsOrderedByUpdatedAtDesc() throws InterruptedException {
        repository.createSession("s1", "w1", "/root1");
        Thread.sleep(10);
        repository.createSession("s2", "w2", "/root2");

        List<SessionInfo> sessions = repository.listAllSessions();
        assertThat(sessions).hasSize(2);
        assertThat(sessions.get(0).getSessionId()).isEqualTo("s2"); // 最新在前
        assertThat(sessions.get(1).getSessionId()).isEqualTo("s1");
        assertThat(sessions.get(0).getWorkspaceId()).isEqualTo("w2");
        assertThat(sessions.get(0).getRootPath()).isEqualTo("/root2");
    }

    @Test
    void shouldReturnEmptyListWhenNoSessions() {
        assertThat(repository.listAllSessions()).isEmpty();
    }
}
