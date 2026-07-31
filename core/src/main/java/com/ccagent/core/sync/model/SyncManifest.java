package com.ccagent.core.sync.model;

import java.time.Instant;

public class SyncManifest {
    private String entityType;
    private String entityId;
    private int version;
    private Instant updatedAt;
    private String checksum;

    public SyncManifest() {}

    public SyncManifest(String entityType, String entityId, int version,
                        Instant updatedAt, String checksum) {
        this.entityType = entityType;
        this.entityId = entityId;
        this.version = version;
        this.updatedAt = updatedAt;
        this.checksum = checksum;
    }

    public String getEntityType() { return entityType; }
    public void setEntityType(String entityType) { this.entityType = entityType; }
    public String getEntityId() { return entityId; }
    public void setEntityId(String entityId) { this.entityId = entityId; }
    public int getVersion() { return version; }
    public void setVersion(int version) { this.version = version; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
    public String getChecksum() { return checksum; }
    public void setChecksum(String checksum) { this.checksum = checksum; }
}
