package com.ccagent.adapter.memory.schema;

import com.ccagent.infrastructure.constant.Constants;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * 数据库初始化器
 * <p>在启动时创建 H2 数据库表结构。</p>
 */
public class DatabaseInitializer {

    private final DataSource dataSource;

    public DatabaseInitializer(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    /**
     * 创建 HikariCP 数据源并初始化表结构
     */
    public static DataSource createDataSource() {
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl("jdbc:h2:file:" + Constants.DB_FILE);
        config.setUsername("sa");
        config.setPassword("");
        config.setMaximumPoolSize(5);
        config.setMinimumIdle(1);
        config.setIdleTimeout(30000);
        config.setConnectionTimeout(5000);
        config.setDriverClassName("org.h2.Driver");
        return new HikariDataSource(config);
    }

    /**
     * 初始化表结构（幂等：CREATE IF NOT EXISTS + 迁移）
     */
    public void initialize() {
        try (Connection conn = dataSource.getConnection();
             Statement stmt = conn.createStatement()) {

            // 会话表
            stmt.execute(
                    "CREATE TABLE IF NOT EXISTS session (" +
                            "  session_id VARCHAR(36) PRIMARY KEY," +
                            "  workspace_id VARCHAR(64) NOT NULL," +
                            "  root_path VARCHAR(1024) NOT NULL DEFAULT ''," +
                            "  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP," +
                            "  updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP," +
                            "  total_turns INT DEFAULT 0," +
                            "  summary TEXT," +
                            "  recent_turns TEXT" +
                            ")"
            );

            // 迁移：已有表无 root_path 列时补上
            stmt.execute("ALTER TABLE session ADD COLUMN IF NOT EXISTS root_path VARCHAR(1024) NOT NULL DEFAULT ''");

            // 偏好表
            stmt.execute(
                    "CREATE TABLE IF NOT EXISTS preferences (" +
                            "  id INT PRIMARY KEY," +
                            "  tech_stack TEXT," +
                            "  code_style TEXT," +
                            "  constraints TEXT," +
                            "  updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP" +
                            ")"
            );

            // 初始化偏好默认行
            stmt.execute("MERGE INTO preferences (id, tech_stack, code_style, constraints, updated_at) " +
                    "KEY(id) VALUES (1, '{}', '{}', '{}', CURRENT_TIMESTAMP)");

            // ====== 独立运行模式新增表 ======

            // 同步清单表
            stmt.execute(
                    "CREATE TABLE IF NOT EXISTS sync_manifest (" +
                            "  entity_type VARCHAR(32) NOT NULL," +
                            "  entity_id VARCHAR(64) NOT NULL," +
                            "  version INT NOT NULL DEFAULT 1," +
                            "  updated_at TIMESTAMP NOT NULL," +
                            "  checksum VARCHAR(64)," +
                            "  PRIMARY KEY (entity_type, entity_id)" +
                            ")"
            );

            // 本地对话记录表（对应 cloud-server 的 conversations 表）
            stmt.execute(
                    "CREATE TABLE IF NOT EXISTS local_conversation (" +
                            "  seq_id VARCHAR(36) PRIMARY KEY," +
                            "  session_id VARCHAR(36) NOT NULL," +
                            "  workspace_id VARCHAR(64) NOT NULL," +
                            "  prompt CLOB NOT NULL," +
                            "  plan_summary TEXT," +
                            "  plan_steps TEXT," +
                            "  assistant_response CLOB," +
                            "  execution_result TEXT," +
                            "  status VARCHAR(20) DEFAULT 'SUCCESS'," +
                            "  total_tokens INT DEFAULT 0," +
                            "  cached_tokens INT DEFAULT 0," +
                            "  cache_hit_rate DOUBLE DEFAULT 0.0," +
                            "  estimated_cost DOUBLE DEFAULT 0.0," +
                            "  created_at TIMESTAMP NOT NULL," +
                            "  updated_at TIMESTAMP NOT NULL" +
                            ")"
            );

            // 本地 Token 统计表（对应 cloud-server 的 token_daily_stats 表）
            stmt.execute(
                    "CREATE TABLE IF NOT EXISTS local_token_stats (" +
                            "  id INT AUTO_INCREMENT PRIMARY KEY," +
                            "  session_id VARCHAR(36) NOT NULL," +
                            "  date DATE NOT NULL," +
                            "  total_tokens INT DEFAULT 0," +
                            "  cached_tokens INT DEFAULT 0," +
                            "  total_cost DOUBLE DEFAULT 0.0," +
                            "  updated_at TIMESTAMP NOT NULL" +
                            ")"
            );

            // 本地审计日志表（代替文件存储）
            stmt.execute(
                    "CREATE TABLE IF NOT EXISTS local_audit_log (" +
                            "  id INT AUTO_INCREMENT PRIMARY KEY," +
                            "  seq_id VARCHAR(36)," +
                            "  event_type VARCHAR(32) NOT NULL," +
                            "  details TEXT," +
                            "  created_at TIMESTAMP NOT NULL" +
                            ")"
            );

        } catch (SQLException e) {
            throw new RuntimeException("数据库初始化失败", e);
        }
    }
}
