package com.ccagent.adapter.memory.repository;

import com.ccagent.api.model.memory.SessionInfo;
import com.ccagent.api.model.memory.StructuredContext;
import com.ccagent.exception.base.ErrorCode;
import com.ccagent.exception.workspace.WorkspaceException;
import com.ccagent.infrastructure.util.JsonUtil;

import javax.sql.DataSource;
import java.sql.*;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

public class SessionRepository {

    private final DataSource dataSource;

    public SessionRepository(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    public void createSession(String sessionId, String workspaceId, String rootPath) {
        String sql = "MERGE INTO session (session_id, workspace_id, root_path, created_at, updated_at, total_turns, summary, recent_turns) KEY (session_id) " +
                "VALUES (?, ?, ?, ?, ?, 0, '', '[]')";
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, sessionId);
            ps.setString(2, workspaceId);
            ps.setString(3, rootPath);
            ps.setTimestamp(4, Timestamp.from(Instant.now()));
            ps.setTimestamp(5, Timestamp.from(Instant.now()));
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new WorkspaceException(ErrorCode.WORKSPACE_NOT_FOUND,
                    "创建会话失败: " + sessionId, e);
        }
    }

    public StructuredContext loadStructuredContext(String sessionId) {
        String sql = "SELECT summary, recent_turns, total_turns FROM session WHERE session_id = ?";
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, sessionId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    String summary = rs.getString("summary");
                    String recentTurnsJson = rs.getString("recent_turns");
                    int totalTurns = rs.getInt("total_turns");

                    var recentTurns = JsonUtil.fromJson(recentTurnsJson,
                            new com.fasterxml.jackson.core.type.TypeReference<java.util.List<com.ccagent.api.model.memory.ConversationTurn>>() {});

                    return new StructuredContext(
                            summary != null ? summary : "",
                            recentTurns,
                            totalTurns
                    );
                }
            }
            throw new WorkspaceException(ErrorCode.SESSION_NOT_FOUND,
                    "会话不存在: " + sessionId, null, sessionId);
        } catch (SQLException e) {
            throw new WorkspaceException(ErrorCode.WORKSPACE_NOT_FOUND,
                    "查询会话失败: " + sessionId, e);
        }
    }

    public void updateMemory(String sessionId, String summary, String recentTurnsJson, int totalTurns) {
        String sql = "UPDATE session SET summary = ?, recent_turns = ?, total_turns = ?, updated_at = ? WHERE session_id = ?";
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, summary);
            ps.setString(2, recentTurnsJson);
            ps.setInt(3, totalTurns);
            ps.setTimestamp(4, Timestamp.from(Instant.now()));
            ps.setString(5, sessionId);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new WorkspaceException(ErrorCode.WORKSPACE_NOT_FOUND,
                    "更新会话记忆失败: " + sessionId, e);
        }
    }

    public int getTotalTurns(String sessionId) {
        String sql = "SELECT total_turns FROM session WHERE session_id = ?";
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, sessionId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return rs.getInt("total_turns");
                }
            }
            throw new WorkspaceException(ErrorCode.SESSION_NOT_FOUND,
                    "会话不存在: " + sessionId, null, sessionId);
        } catch (SQLException e) {
            throw new WorkspaceException(ErrorCode.WORKSPACE_NOT_FOUND,
                    "查询会话轮次失败: " + sessionId, e);
        }
    }

    public String getWorkspaceId(String sessionId) {
        String sql = "SELECT workspace_id FROM session WHERE session_id = ?";
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, sessionId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return rs.getString("workspace_id");
                }
            }
            throw new WorkspaceException(ErrorCode.SESSION_NOT_FOUND,
                    "会话不存在: " + sessionId, null, sessionId);
        } catch (SQLException e) {
            throw new WorkspaceException(ErrorCode.WORKSPACE_NOT_FOUND,
                    "查询会话工作区失败: " + sessionId, e);
        }
    }

    public String getRootPath(String sessionId) {
        String sql = "SELECT root_path FROM session WHERE session_id = ?";
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, sessionId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    String rootPath = rs.getString("root_path");
                    if (rootPath == null || rootPath.isBlank()) {
                        throw new WorkspaceException(ErrorCode.WORKSPACE_NOT_FOUND,
                                "会话未绑定工作区根目录: " + sessionId, null, sessionId);
                    }
                    return rootPath;
                }
            }
            throw new WorkspaceException(ErrorCode.SESSION_NOT_FOUND,
                    "会话不存在: " + sessionId, null, sessionId);
        } catch (SQLException e) {
            throw new WorkspaceException(ErrorCode.WORKSPACE_NOT_FOUND,
                    "查询会话根目录失败: " + sessionId, e);
        }
    }

    public List<SessionInfo> listAllSessions() {
        String sql = "SELECT session_id, workspace_id, root_path, total_turns, summary, created_at, updated_at " +
                "FROM session ORDER BY updated_at DESC";
        List<SessionInfo> sessions = new ArrayList<>();
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                sessions.add(new SessionInfo(
                        rs.getString("session_id"),
                        rs.getString("workspace_id"),
                        rs.getString("root_path"),
                        rs.getInt("total_turns"),
                        rs.getString("summary"),
                        rs.getTimestamp("created_at").toInstant(),
                        rs.getTimestamp("updated_at").toInstant()
                ));
            }
        } catch (SQLException e) {
            throw new WorkspaceException(ErrorCode.WORKSPACE_NOT_FOUND,
                    "查询会话列表失败", e);
        }
        return sessions;
    }
}
