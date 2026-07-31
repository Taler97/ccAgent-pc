package com.ccagent.core.sync.model;

import java.util.ArrayList;
import java.util.List;

public class SyncDiff {
    private List<SyncManifestDiff> toUpload;
    private List<SyncManifestDiff> toDownload;

    public SyncDiff() {
        this.toUpload = new ArrayList<>();
        this.toDownload = new ArrayList<>();
    }

    public SyncDiff(List<SyncManifestDiff> toUpload, List<SyncManifestDiff> toDownload) {
        this.toUpload = toUpload;
        this.toDownload = toDownload;
    }

    public List<SyncManifestDiff> getToUpload() { return toUpload; }
    public void setToUpload(List<SyncManifestDiff> toUpload) { this.toUpload = toUpload; }
    public List<SyncManifestDiff> getToDownload() { return toDownload; }
    public void setToDownload(List<SyncManifestDiff> toDownload) { this.toDownload = toDownload; }

    public boolean hasChanges() {
        return !toUpload.isEmpty() || !toDownload.isEmpty();
    }

    public static class SyncManifestDiff {
        private String entityType;
        private String entityId;
        private int localVersion;
        private int cloudVersion;

        public SyncManifestDiff() {}

        public SyncManifestDiff(String entityType, String entityId,
                                int localVersion, int cloudVersion) {
            this.entityType = entityType;
            this.entityId = entityId;
            this.localVersion = localVersion;
            this.cloudVersion = cloudVersion;
        }

        public String getEntityType() { return entityType; }
        public void setEntityType(String entityType) { this.entityType = entityType; }
        public String getEntityId() { return entityId; }
        public void setEntityId(String entityId) { this.entityId = entityId; }
        public int getLocalVersion() { return localVersion; }
        public void setLocalVersion(int localVersion) { this.localVersion = localVersion; }
        public int getCloudVersion() { return cloudVersion; }
        public void setCloudVersion(int cloudVersion) { this.cloudVersion = cloudVersion; }
    }
}
