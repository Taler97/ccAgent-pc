package com.ccagent.adapter.memory.repository;

import com.ccagent.api.model.memory.UserPreferences;
import com.ccagent.infrastructure.util.JsonUtil;

import javax.sql.DataSource;
import java.sql.*;
import java.time.Instant;

/**
 * 偏好数据访问层
 */
public class PreferencesRepository {

    private final DataSource dataSource;

    public PreferencesRepository(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    /**
     * 加载用户偏好
     */
    public UserPreferences loadPreferences() {
        String sql = "SELECT tech_stack, code_style, constraints FROM preferences WHERE id = 1";
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            if (rs.next()) {
                return new UserPreferences(
                        rs.getString("tech_stack"),
                        rs.getString("code_style"),
                        rs.getString("constraints")
                );
            }
            return new UserPreferences("{}", "{}", "{}");
        } catch (SQLException e) {
            return new UserPreferences("{}", "{}", "{}");
        }
    }

    /**
     * 保存用户偏好
     */
    public void savePreferences(UserPreferences preferences) {
        String sql = "UPDATE preferences SET tech_stack = ?, code_style = ?, constraints = ?, updated_at = ? WHERE id = 1";
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, preferences.getTechStack() != null ? preferences.getTechStack() : "{}");
            ps.setString(2, preferences.getCodeStyle() != null ? preferences.getCodeStyle() : "{}");
            ps.setString(3, preferences.getConstraints() != null ? preferences.getConstraints() : "{}");
            ps.setTimestamp(4, Timestamp.from(Instant.now()));
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("保存偏好失败", e);
        }
    }
}
