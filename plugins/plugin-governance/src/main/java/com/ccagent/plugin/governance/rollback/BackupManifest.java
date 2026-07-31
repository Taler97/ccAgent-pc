package com.ccagent.plugin.governance.rollback;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * 备份清单模型
 * <p>记录一次备份操作的所有文件快照信息。</p>
 */
public class BackupManifest {

    private String seqId;
    private Instant createdAt;
    private List<BackupEntry> entries = new ArrayList<>();

    public BackupManifest() {}

    public BackupManifest(String seqId) {
        this.seqId = seqId;
        this.createdAt = Instant.now();
    }

    public String getSeqId() { return seqId; }
    public void setSeqId(String seqId) { this.seqId = seqId; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
    public List<BackupEntry> getEntries() { return entries; }
    public void setEntries(List<BackupEntry> entries) { this.entries = entries; }

    public void addEntry(String filePath, String snapshotPath) {
        entries.add(new BackupEntry(filePath, snapshotPath, -1));
    }

    public void addEntry(String filePath, String snapshotPath, int stepId) {
        entries.add(new BackupEntry(filePath, snapshotPath, stepId));
    }

    /**
     * 单条备份记录
     */
    public static class BackupEntry {
        private String originalPath;
        private String snapshotPath;
        private int stepId = -1;

        public BackupEntry() {}

        public BackupEntry(String originalPath, String snapshotPath) {
            this(originalPath, snapshotPath, -1);
        }

        public BackupEntry(String originalPath, String snapshotPath, int stepId) {
            this.originalPath = originalPath;
            this.snapshotPath = snapshotPath;
            this.stepId = stepId;
        }

        public String getOriginalPath() { return originalPath; }
        public void setOriginalPath(String originalPath) { this.originalPath = originalPath; }
        public String getSnapshotPath() { return snapshotPath; }
        public void setSnapshotPath(String snapshotPath) { this.snapshotPath = snapshotPath; }
        public int getStepId() { return stepId; }
        public void setStepId(int stepId) { this.stepId = stepId; }
    }
}