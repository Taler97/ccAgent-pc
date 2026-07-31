package com.ccagent.core.sync;

import com.ccagent.core.sync.model.SyncManifest;

import javax.sql.DataSource;
import java.sql.*;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

public class SyncManifestRepository {

    private final DataSource dataSource;

    public SyncManifestRepository(DataSource dataSource) {
        this.dataSource = dataSource;
        initTable();
    }

    private void initTable() {
        try (Connection conn = dataSource.getConnection();
             Statement stmt = conn.createStatement()) {
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
        } catch (SQLException e) {
            throw new RuntimeException("sync_manifest 表初始化失败", e);
        }
    }

    public void insertOrUpdate(String entityType, String entityId) {
        String sql = "MERGE INTO sync_manifest (entity_type, entity_id, version, updated_at) " +
                "KEY(entity_type, entity_id) VALUES (?, ?, " +
                "(SELECT COALESCE(MAX(version), 0) + 1 FROM sync_manifest " +
                " WHERE entity_type = ? AND entity_id = ?), ?)";
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, entityType);
            ps.setString(2, entityId);
            ps.setString(3, entityType);
            ps.setString(4, entityId);
            ps.setTimestamp(5, Timestamp.from(Instant.now()));
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("更新同步清单失败", e);
        }
    }

    public void insertOrUpdate(String entityType, String entityId, int version) {
        String sql = "MERGE INTO sync_manifest (entity_type, entity_id, version, updated_at) " +
                "KEY(entity_type, entity_id) VALUES (?, ?, ?, ?)";
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, entityType);
            ps.setString(2, entityId);
            ps.setInt(3, version);
            ps.setTimestamp(4, Timestamp.from(Instant.now()));
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("更新同步清单失败", e);
        }
    }

    public Optional<SyncManifest> get(String entityType, String entityId) {
        String sql = "SELECT entity_type, entity_id, version, updated_at, checksum " +
                "FROM sync_manifest WHERE entity_type = ? AND entity_id = ?";
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, entityType);
            ps.setString(2, entityId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    SyncManifest m = new SyncManifest();
                    m.setEntityType(rs.getString("entity_type"));
                    m.setEntityId(rs.getString("entity_id"));
                    m.setVersion(rs.getInt("version"));
                    m.setUpdatedAt(rs.getTimestamp("updated_at").toInstant());
                    String cs = rs.getString("checksum");
                    if (cs != null) m.setChecksum(cs);
                    return Optional.of(m);
                }
            }
        } catch (SQLException e) {
            throw new RuntimeException("查询同步清单失败", e);
        }
        return Optional.empty();
    }

    public List<SyncManifest> getAll() {
        String sql = "SELECT entity_type, entity_id, version, updated_at, checksum FROM sync_manifest ORDER BY entity_type, entity_id";
        List<SyncManifest> list = new ArrayList<>();
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                SyncManifest m = new SyncManifest();
                m.setEntityType(rs.getString("entity_type"));
                m.setEntityId(rs.getString("entity_id"));
                m.setVersion(rs.getInt("version"));
                m.setUpdatedAt(rs.getTimestamp("updated_at").toInstant());
                String cs = rs.getString("checksum");
                if (cs != null) m.setChecksum(cs);
                list.add(m);
            }
        } catch (SQLException e) {
            throw new RuntimeException("查询全部同步清单失败", e);
        }
        return list;
    }

    public void updateChecksum(String entityType, String entityId, String checksum) {
        String sql = "UPDATE sync_manifest SET checksum = ? WHERE entity_type = ? AND entity_id = ?";
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, checksum);
            ps.setString(2, entityType);
            ps.setString(3, entityId);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("更新同步校验码失败", e);
        }
    }
}
