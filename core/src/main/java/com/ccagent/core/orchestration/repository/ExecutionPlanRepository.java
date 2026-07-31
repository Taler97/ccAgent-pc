package com.ccagent.core.orchestration.repository;

import com.ccagent.api.model.plan.Plan;
import com.ccagent.api.model.plan.Step;
import com.ccagent.core.orchestration.repository.model.PlanRecord;
import com.ccagent.core.orchestration.repository.model.StepRecord;
import com.ccagent.infrastructure.util.JsonUtil;

import javax.sql.DataSource;
import java.sql.*;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class ExecutionPlanRepository {

    private final DataSource dataSource;

    public ExecutionPlanRepository(DataSource dataSource) {
        this.dataSource = dataSource;
        initTables();
    }

    private void initTables() {
        try (Connection conn = dataSource.getConnection();
             Statement stmt = conn.createStatement()) {

            stmt.execute(
                    "CREATE TABLE IF NOT EXISTS execution_plan (" +
                            "  session_id VARCHAR(36) NOT NULL," +
                            "  version INT NOT NULL," +
                            "  plan_json CLOB NOT NULL," +
                            "  overall_status VARCHAR(20) NOT NULL DEFAULT 'PLANNING'," +
                            "  completed_step_ids CLOB DEFAULT '[]'," +
                            "  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP," +
                            "  updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP," +
                            "  PRIMARY KEY (session_id, version)" +
                            ")"
            );

            // 兼容已有数据库：补充 completed_step_ids 列
            try {
                stmt.execute("ALTER TABLE execution_plan ADD COLUMN IF NOT EXISTS completed_step_ids CLOB DEFAULT '[]'");
            } catch (SQLException ignored) {}

            stmt.execute(
                    "CREATE TABLE IF NOT EXISTS execution_step (" +
                            "  session_id VARCHAR(36) NOT NULL," +
                            "  version INT NOT NULL," +
                            "  step_id INT NOT NULL," +
                            "  expert_name VARCHAR(64) NOT NULL," +
                            "  task CLOB NOT NULL," +
                            "  affected_files CLOB," +
                            "  status VARCHAR(20) NOT NULL DEFAULT 'PENDING'," +
                            "  error_message CLOB," +
                            "  started_at TIMESTAMP," +
                            "  completed_at TIMESTAMP," +
                            "  PRIMARY KEY (session_id, version, step_id)" +
                            ")"
            );

        } catch (SQLException e) {
            throw new RuntimeException("执行计划状态表初始化失败", e);
        }
    }

    public int createPlan(String sessionId, Plan plan) {
        int version = nextVersion(sessionId);
        String planJson = JsonUtil.toJson(plan);
        String sql = "INSERT INTO execution_plan (session_id, version, plan_json, overall_status, created_at, updated_at) " +
                "VALUES (?, ?, ?, 'PLANNING', ?, ?)";
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, sessionId);
            ps.setInt(2, version);
            ps.setString(3, planJson);
            Timestamp now = Timestamp.from(Instant.now());
            ps.setTimestamp(4, now);
            ps.setTimestamp(5, now);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("保存执行计划失败: sessionId=" + sessionId, e);
        }

        insertSteps(sessionId, version, plan.getSteps());

        return version;
    }

    private void insertSteps(String sessionId, int version, List<Step> steps) {
        String sql = "INSERT INTO execution_step (session_id, version, step_id, expert_name, task, affected_files, status) " +
                "VALUES (?, ?, ?, ?, ?, ?, 'PENDING')";
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            for (Step step : steps) {
                ps.setString(1, sessionId);
                ps.setInt(2, version);
                ps.setInt(3, step.getId());
                ps.setString(4, step.getExpertName());
                ps.setString(5, step.getTask());
                ps.setString(6, step.getAffectedFiles() != null
                        ? JsonUtil.toJson(step.getAffectedFiles()) : "[]");
                ps.addBatch();
            }
            ps.executeBatch();
        } catch (SQLException e) {
            throw new RuntimeException("保存执行步骤失败: sessionId=" + sessionId, e);
        }
    }

    public void updatePlanStatus(String sessionId, int version, String status) {
        String sql = "UPDATE execution_plan SET overall_status = ?, updated_at = ? WHERE session_id = ? AND version = ?";
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, status);
            ps.setTimestamp(2, Timestamp.from(Instant.now()));
            ps.setString(3, sessionId);
            ps.setInt(4, version);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("更新执行计划状态失败", e);
        }
    }

    public void updateStepStatus(String sessionId, int version, int stepId, String status) {
        updateStepStatus(sessionId, version, stepId, status, null);
    }

    public void updateStepStatus(String sessionId, int version, int stepId, String status, String errorMessage) {
        String sql = "UPDATE execution_step SET status = ?, error_message = ?, " +
                "started_at = CASE WHEN ? = 'RUNNING' AND started_at IS NULL THEN ? ELSE started_at END, " +
                "completed_at = CASE WHEN ? IN ('SUCCESS','FAILED','CANCELLED') THEN ? ELSE completed_at END " +
                "WHERE session_id = ? AND version = ? AND step_id = ?";
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, status);
            ps.setString(2, errorMessage);
            ps.setString(3, status);
            Timestamp now = Timestamp.from(Instant.now());
            ps.setTimestamp(4, now);
            ps.setString(5, status);
            ps.setTimestamp(6, now);
            ps.setString(7, sessionId);
            ps.setInt(8, version);
            ps.setInt(9, stepId);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("更新执行步骤状态失败", e);
        }
    }

    public PlanRecord getPlan(String sessionId, int version) {
        String sql = "SELECT session_id, version, plan_json, overall_status, completed_step_ids, created_at, updated_at " +
                "FROM execution_plan WHERE session_id = ? AND version = ?";
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, sessionId);
            ps.setInt(2, version);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    PlanRecord record = new PlanRecord();
                    record.setSessionId(rs.getString("session_id"));
                    record.setVersion(rs.getInt("version"));
                    String planJson = rs.getString("plan_json");
                    record.setPlan(JsonUtil.fromJson(planJson, Plan.class));
                    record.setOverallStatus(rs.getString("overall_status"));
                    String completedIdsJson = rs.getString("completed_step_ids");
                    record.setCompletedStepIds(completedIdsJson != null ? completedIdsJson : "[]");
                    record.setCreatedAt(rs.getTimestamp("created_at").toInstant());
                    record.setUpdatedAt(rs.getTimestamp("updated_at").toInstant());
                    record.setSteps(getSteps(sessionId, version));
                    return record;
                }
            }
        } catch (SQLException e) {
            throw new RuntimeException("查询执行计划失败", e);
        }
        return null;
    }

    public List<StepRecord> getSteps(String sessionId, int version) {
        String sql = "SELECT step_id, expert_name, task, affected_files, status, error_message, started_at, completed_at " +
                "FROM execution_step WHERE session_id = ? AND version = ? ORDER BY step_id";
        List<StepRecord> steps = new ArrayList<>();
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, sessionId);
            ps.setInt(2, version);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    StepRecord step = new StepRecord();
                    step.setStepId(rs.getInt("step_id"));
                    step.setExpertName(rs.getString("expert_name"));
                    step.setTask(rs.getString("task"));
                    step.setStatus(rs.getString("status"));
                    step.setErrorMessage(rs.getString("error_message"));
                    Timestamp started = rs.getTimestamp("started_at");
                    if (started != null) step.setStartedAt(started.toInstant());
                    Timestamp completed = rs.getTimestamp("completed_at");
                    if (completed != null) step.setCompletedAt(completed.toInstant());
                    steps.add(step);
                }
            }
        } catch (SQLException e) {
            throw new RuntimeException("查询执行步骤失败", e);
        }
        return steps;
    }

    private int nextVersion(String sessionId) {
        String sql = "SELECT COALESCE(MAX(version), 0) + 1 FROM execution_plan WHERE session_id = ?";
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, sessionId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getInt(1) : 1;
            }
        } catch (SQLException e) {
            return 1;
        }
    }

    /**
     * 追加已完成的步骤 ID（断点续存用）
     */
    public void addCompletedStep(String sessionId, int version, int stepId) {
        Set<Integer> current = getCompletedStepIds(sessionId, version);
        current.add(stepId);
        String json = JsonUtil.toJson(current);

        String sql = "UPDATE execution_plan SET completed_step_ids = ?, updated_at = ? " +
                "WHERE session_id = ? AND version = ?";
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, json);
            ps.setTimestamp(2, Timestamp.from(Instant.now()));
            ps.setString(3, sessionId);
            ps.setInt(4, version);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("更新已完成步骤列表失败", e);
        }
    }

    /**
     * 获取已完成的步骤 ID 集合
     */
    public Set<Integer> getCompletedStepIds(String sessionId, int version) {
        String sql = "SELECT completed_step_ids FROM execution_plan WHERE session_id = ? AND version = ?";
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, sessionId);
            ps.setInt(2, version);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    String json = rs.getString("completed_step_ids");
                    if (json == null || json.isBlank()) return new HashSet<>();
                    Integer[] ids = JsonUtil.fromJson(json, Integer[].class);
                    return new HashSet<>(List.of(ids));
                }
            }
        } catch (SQLException e) {
            throw new RuntimeException("查询已完成步骤列表失败", e);
        }
        return new HashSet<>();
    }

    /**
     * 按状态查询执行计划（断点恢复用）
     */
    public List<PlanRecord> findByStatus(String status) {
        String sql = "SELECT session_id, version FROM execution_plan WHERE overall_status = ?";
        List<PlanRecord> records = new ArrayList<>();
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, status);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String sessionId = rs.getString("session_id");
                    int version = rs.getInt("version");
                    PlanRecord record = getPlan(sessionId, version);
                    if (record != null) records.add(record);
                }
            }
        } catch (SQLException e) {
            throw new RuntimeException("按状态查询执行计划失败", e);
        }
        return records;
    }
}
