package com.ccagent.core.sync;

import com.ccagent.core.sync.model.SyncManifest;

import javax.sql.DataSource;
import java.sql.*;
import java.time.Instant;
import java.time.LocalDate;
import java.util.*;

import com.ccagent.infrastructure.util.JsonUtil;

public class LocalDataRepository {

    private final DataSource dataSource;
    private final SyncManifestRepository manifestRepo;

    public LocalDataRepository(DataSource dataSource, SyncManifestRepository manifestRepo) {
        this.dataSource = dataSource;
        this.manifestRepo = manifestRepo;
    }

    public void saveAuditLog(String seqId, String eventType, String details) {
        String sql = "INSERT INTO local_audit_log (seq_id, event_type, details, created_at) VALUES (?, ?, ?, ?)";
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, seqId);
            ps.setString(2, eventType);
            ps.setString(3, details);
            ps.setTimestamp(4, Timestamp.from(Instant.now()));
            ps.executeUpdate();
            String entityId = seqId != null ? seqId : "batch-" + System.currentTimeMillis();
            manifestRepo.insertOrUpdate("audit_log", entityId);
        } catch (SQLException e) {
            throw new RuntimeException("保存审计日志失败", e);
        }
    }

    public void saveTokenStats(String sessionId, int totalTokens, int cachedTokens, double cost) {
        String sql = "MERGE INTO local_token_stats (session_id, date, total_tokens, cached_tokens, total_cost, updated_at) " +
                "KEY(session_id, date) VALUES (?, CURRENT_DATE, " +
                "COALESCE((SELECT total_tokens + ? FROM local_token_stats WHERE session_id = ? AND date = CURRENT_DATE), ?), " +
                "COALESCE((SELECT cached_tokens + ? FROM local_token_stats WHERE session_id = ? AND date = CURRENT_DATE), ?), " +
                "COALESCE((SELECT total_cost + ? FROM local_token_stats WHERE session_id = ? AND date = CURRENT_DATE), ?), ?)";
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, sessionId);
            ps.setInt(2, totalTokens);
            ps.setString(3, sessionId);
            ps.setInt(4, totalTokens);
            ps.setInt(5, cachedTokens);
            ps.setString(6, sessionId);
            ps.setInt(7, cachedTokens);
            ps.setDouble(8, cost);
            ps.setString(9, sessionId);
            ps.setDouble(10, cost);
            ps.setTimestamp(11, Timestamp.from(Instant.now()));
            ps.executeUpdate();
            manifestRepo.insertOrUpdate("token_stats", sessionId + "-" + LocalDate.now());
        } catch (SQLException e) {
            throw new RuntimeException("保存 Token 统计失败", e);
        }
    }

    public void saveConversation(String seqId, String sessionId, String workspaceId,
                                  String prompt, String planSummary, String planSteps,
                                  String assistantResponse, String executionResult,
                                  String status, int totalTokens, int cachedTokens,
                                  double cacheHitRate, double estimatedCost) {
        String sql = "MERGE INTO local_conversation (seq_id, session_id, workspace_id, prompt, " +
                "plan_summary, plan_steps, assistant_response, execution_result, status, " +
                "total_tokens, cached_tokens, cache_hit_rate, estimated_cost, created_at, updated_at) " +
                "KEY(seq_id) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, seqId);
            ps.setString(2, sessionId);
            ps.setString(3, workspaceId);
            ps.setString(4, prompt);
            setText(ps, 5, planSummary);
            setText(ps, 6, planSteps);
            setText(ps, 7, assistantResponse);
            setText(ps, 8, executionResult);
            ps.setString(9, status);
            ps.setInt(10, totalTokens);
            ps.setInt(11, cachedTokens);
            ps.setDouble(12, cacheHitRate);
            ps.setDouble(13, estimatedCost);
            Timestamp now = Timestamp.from(Instant.now());
            ps.setTimestamp(14, now);
            ps.setTimestamp(15, now);
            int affected = ps.executeUpdate();
            if (affected > 0) {
                manifestRepo.insertOrUpdate("conversation", seqId);
            }
        } catch (SQLException e) {
            throw new RuntimeException("保存对话记录失败: seqId=" + seqId, e);
        }
    }

    public Optional<String> getConversationResult(String seqId) {
        String sql = "SELECT execution_result FROM local_conversation WHERE seq_id = ?";
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, seqId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return Optional.ofNullable(rs.getString("execution_result"));
                }
            }
        } catch (SQLException e) {
            throw new RuntimeException("查询对话记录失败", e);
        }
        return Optional.empty();
    }

    /**
     * 按 seqId 查询审计日志记录
     */
    public List<Map<String, Object>> queryAuditLog(String seqId) {
        String sql = "SELECT seq_id, event_type, details, created_at FROM local_audit_log WHERE seq_id = ? ORDER BY created_at";
        List<Map<String, Object>> result = new ArrayList<>();
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, seqId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("seq_id", rs.getString("seq_id"));
                    row.put("event_type", rs.getString("event_type"));
                    row.put("details", rs.getString("details"));
                    row.put("created_at", rs.getTimestamp("created_at").toInstant().toString());
                    result.add(row);
                }
            }
        } catch (SQLException e) {
            throw new RuntimeException("查询审计日志失败: seqId=" + seqId, e);
        }
        return result;
    }

    /**
     * 按 seqId 查询对话记录
     */
    public Map<String, Object> queryConversation(String seqId) {
        String sql = "SELECT * FROM local_conversation WHERE seq_id = ?";
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, seqId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("seq_id", rs.getString("seq_id"));
                    row.put("session_id", rs.getString("session_id"));
                    row.put("workspace_id", rs.getString("workspace_id"));
                    row.put("prompt", rs.getString("prompt"));
                    row.put("plan_summary", rs.getString("plan_summary"));
                    row.put("plan_steps", rs.getString("plan_steps"));
                    row.put("assistant_response", rs.getString("assistant_response"));
                    row.put("execution_result", rs.getString("execution_result"));
                    row.put("status", rs.getString("status"));
                    row.put("total_tokens", rs.getInt("total_tokens"));
                    row.put("cached_tokens", rs.getInt("cached_tokens"));
                    row.put("cache_hit_rate", rs.getDouble("cache_hit_rate"));
                    row.put("estimated_cost", rs.getDouble("estimated_cost"));
                    row.put("created_at", rs.getTimestamp("created_at").toInstant().toString());
                    row.put("updated_at", rs.getTimestamp("updated_at").toInstant().toString());
                    return row;
                }
            }
        } catch (SQLException e) {
            throw new RuntimeException("查询对话记录失败: seqId=" + seqId, e);
        }
        return Collections.emptyMap();
    }

    /**
     * 按 entityId（格式：sessionId-date）查询 Token 统计
     */
    public Map<String, Object> queryTokenStats(String entityId) {
        String[] parts = entityId.split("-", 2);
        if (parts.length != 2) {
            return Collections.emptyMap();
        }
        String sessionId = parts[0];
        LocalDate date;
        try {
            date = LocalDate.parse(parts[1]);
        } catch (Exception e) {
            return Collections.emptyMap();
        }

        String sql = "SELECT * FROM local_token_stats WHERE session_id = ? AND date = ?";
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, sessionId);
            ps.setDate(2, java.sql.Date.valueOf(date));
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("session_id", rs.getString("session_id"));
                    row.put("date", rs.getDate("date").toLocalDate().toString());
                    row.put("total_tokens", rs.getInt("total_tokens"));
                    row.put("cached_tokens", rs.getInt("cached_tokens"));
                    row.put("total_cost", rs.getDouble("total_cost"));
                    row.put("updated_at", rs.getTimestamp("updated_at").toInstant().toString());
                    return row;
                }
            }
        } catch (SQLException e) {
            throw new RuntimeException("查询 Token 统计失败: entityId=" + entityId, e);
        }
        return Collections.emptyMap();
    }

    /**
     * 保存审计日志（MERGE 语义：相同 seq_id + event_type 时跳过重复）
     */
    public void mergeAuditLog(String seqId, String eventType, String details, String createdAt) {
        String selectSql = "SELECT id FROM local_audit_log WHERE seq_id = ? AND event_type = ?";
        String insertSql = "INSERT INTO local_audit_log (seq_id, event_type, details, created_at) VALUES (?, ?, ?, ?)";
        try (Connection conn = dataSource.getConnection()) {
            try (PreparedStatement ps = conn.prepareStatement(selectSql)) {
                ps.setString(1, seqId);
                ps.setString(2, eventType);
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) {
                        try (PreparedStatement insert = conn.prepareStatement(insertSql)) {
                            insert.setString(1, seqId);
                            insert.setString(2, eventType);
                            insert.setString(3, details);
                            insert.setTimestamp(4, createdAt != null
                                    ? Timestamp.from(Instant.parse(createdAt))
                                    : Timestamp.from(Instant.now()));
                            insert.executeUpdate();
                        }
                    }
                }
            }
        } catch (SQLException e) {
            throw new RuntimeException("合并审计日志失败: seqId=" + seqId + ", event=" + eventType, e);
        }
    }

    // ====== 历史记录查询（API 用） ======

    /**
     * 按 session 分页查询对话记录
     */
    public List<Map<String, Object>> listConversationsBySession(String sessionId, int page, int limit) {
        int offset = (page - 1) * limit;
        String sql = "SELECT * FROM local_conversation WHERE session_id = ? ORDER BY created_at DESC LIMIT ? OFFSET ?";
        return queryConversationList(sql, sessionId, limit, offset);
    }

    /**
     * 按关键词搜索对话记录
     */
    public List<Map<String, Object>> searchConversations(String keyword, int page, int limit) {
        int offset = (page - 1) * limit;
        String like = "%" + keyword + "%";
        String sql = "SELECT * FROM local_conversation WHERE prompt LIKE ? OR plan_summary LIKE ? " +
                "OR assistant_response LIKE ? ORDER BY created_at DESC LIMIT ? OFFSET ?";
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, like);
            ps.setString(2, like);
            ps.setString(3, like);
            ps.setInt(4, limit);
            ps.setInt(5, offset);
            try (ResultSet rs = ps.executeQuery()) {
                return extractConversationRows(rs);
            }
        } catch (SQLException e) {
            throw new RuntimeException("搜索对话记录失败: keyword=" + keyword, e);
        }
    }

    /**
     * 删除指定 session 的所有对话记录
     */
    public int deleteConversationsBySession(String sessionId) {
        String sql = "DELETE FROM local_conversation WHERE session_id = ?";
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, sessionId);
            return ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("删除对话记录失败: sessionId=" + sessionId, e);
        }
    }

    /**
     * 按 session 统计对话总数
     */
    public int countConversationsBySession(String sessionId) {
        String sql = "SELECT COUNT(*) FROM local_conversation WHERE session_id = ?";
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, sessionId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getInt(1) : 0;
            }
        } catch (SQLException e) {
            throw new RuntimeException("统计对话记录失败: sessionId=" + sessionId, e);
        }
    }

    private List<Map<String, Object>> queryConversationList(String sql, String sessionId, int limit, int offset) {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, sessionId);
            ps.setInt(2, limit);
            ps.setInt(3, offset);
            try (ResultSet rs = ps.executeQuery()) {
                return extractConversationRows(rs);
            }
        } catch (SQLException e) {
            throw new RuntimeException("查询对话记录列表失败", e);
        }
    }

    private List<Map<String, Object>> extractConversationRows(ResultSet rs) throws SQLException {
        List<Map<String, Object>> result = new ArrayList<>();
        while (rs.next()) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("seq_id", rs.getString("seq_id"));
            row.put("session_id", rs.getString("session_id"));
            row.put("workspace_id", rs.getString("workspace_id"));
            row.put("prompt", rs.getString("prompt"));
            row.put("plan_summary", rs.getString("plan_summary"));
            row.put("plan_steps", rs.getString("plan_steps"));
            row.put("assistant_response", rs.getString("assistant_response"));
            row.put("execution_result", rs.getString("execution_result"));
            row.put("status", rs.getString("status"));
            row.put("total_tokens", rs.getInt("total_tokens"));
            row.put("cached_tokens", rs.getInt("cached_tokens"));
            row.put("cache_hit_rate", rs.getDouble("cache_hit_rate"));
            row.put("estimated_cost", rs.getDouble("estimated_cost"));
            row.put("created_at", rs.getTimestamp("created_at").toInstant().toString());
            row.put("updated_at", rs.getTimestamp("updated_at").toInstant().toString());
            result.add(row);
        }
        return result;
    }

    private void setText(PreparedStatement ps, int index, String value) throws SQLException {
        if (value != null) {
            ps.setString(index, value);
        } else {
            ps.setNull(index, Types.CLOB);
        }
    }
}
