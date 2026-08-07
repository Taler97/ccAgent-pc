package com.ccagent.adapter.memory.schema;

import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * DatabaseInitializer 集成测试（真实 H2 内存库）
 * <p>覆盖：表结构创建、幂等性（重复初始化不报错）、默认偏好行。</p>
 */
class DatabaseInitializerTest {

    private DataSource dataSource;

    @BeforeEach
    void setUp() {
        JdbcDataSource ds = new JdbcDataSource();
        ds.setURL("jdbc:h2:mem:init-test-" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1");
        ds.setUser("sa");
        ds.setPassword("");
        dataSource = ds;
    }

    private List<String> tableNames() throws Exception {
        List<String> tables = new ArrayList<>();
        try (Connection conn = dataSource.getConnection();
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(
                     "SELECT TABLE_NAME FROM INFORMATION_SCHEMA.TABLES WHERE TABLE_SCHEMA = 'PUBLIC'")) {
            while (rs.next()) {
                tables.add(rs.getString(1));
            }
        }
        return tables;
    }

    @Test
    void shouldCreateAllTables() throws Exception {
        new DatabaseInitializer(dataSource).initialize();

        List<String> tables = tableNames().stream().map(String::toLowerCase).toList();
        assertThat(tables)
                .contains("session", "preferences", "sync_manifest",
                        "local_conversation", "local_token_stats", "local_audit_log");
    }

    @Test
    void shouldBeIdempotent() {
        DatabaseInitializer initializer = new DatabaseInitializer(dataSource);
        assertThatCode(initializer::initialize).doesNotThrowAnyException();
        assertThatCode(initializer::initialize).doesNotThrowAnyException();
    }

    @Test
    void shouldSeedDefaultPreferencesRow() throws Exception {
        new DatabaseInitializer(dataSource).initialize();

        try (Connection conn = dataSource.getConnection();
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(
                     "SELECT tech_stack, code_style, constraints FROM preferences WHERE id = 1")) {
            assertThat(rs.next()).isTrue();
            assertThat(rs.getString(1)).isEqualTo("{}");
            assertThat(rs.getString(2)).isEqualTo("{}");
            assertThat(rs.getString(3)).isEqualTo("{}");
        }
    }
}
