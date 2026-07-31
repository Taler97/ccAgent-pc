package com.ccagent.plugin.governance.rollback;

import com.ccagent.api.model.plan.Plan;
import com.ccagent.api.model.plan.Step;
import com.ccagent.api.model.response.ExecutionResult;
import com.ccagent.api.port.ToolRegistryPort;
import com.ccagent.core.event.StepFailedEvent;
import com.ccagent.core.event.StepRolledBackEvent;
import com.ccagent.core.event.StepStartedEvent;
import com.ccagent.exception.base.ErrorCode;
import com.ccagent.exception.governance.GovernanceException;
import com.ccagent.infrastructure.constant.Constants;
import com.ccagent.infrastructure.util.FileUtil;
import com.ccagent.infrastructure.util.JsonUtil;
import com.ccagent.kernel.event.EventBus;
import com.ccagent.kernel.event.Subscription;
import com.ccagent.core.plugin.ListenerExtensionPoint;
import com.ccagent.infrastructure.config.AppConfig;
import com.ccagent.kernel.plugin.PluginContext;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.*;

/**
 * 回滚管理器
 * <p>Plan 执行前批量备份所有受影响文件，失败或用户回退时整批恢复。</p>
 *
 * <h3>备份颗粒度</h3>
 * <ul>
 *   <li>一轮对话（Plan 确认后一次性批量备份）</li>
 *   <li>以 seqId 作为备份目录隔离键</li>
 * </ul>
 */
public class RollbackManager implements ListenerExtensionPoint {

    private ToolRegistryPort toolRegistry;
    private boolean backupEnabled;
    private int retryCount;
    private int retryIntervalSeconds;
    private final Map<String, BackupManifest> backups = new HashMap<>();
    private final List<Subscription> subscriptions = new ArrayList<>();

    public RollbackManager() {}

    @Override
    public void init(PluginContext context) {
        AppConfig config = context.getService(AppConfig.class);
        AppConfig.GovernanceConfig govConfig = config.getGovernance();
        this.toolRegistry = context.getService(ToolRegistryPort.class);
        this.backupEnabled = govConfig.isBackupEnabled();
        this.retryCount = govConfig.getRollbackRetryCount();
        this.retryIntervalSeconds = govConfig.getRollbackRetryIntervalSeconds();

        EventBus eventBus = context.getService(EventBus.class);
        if (eventBus != null) {
            registerOnEventBus(eventBus);
        }
    }

    public RollbackManager(ToolRegistryPort toolRegistry, boolean backupEnabled,
                           int retryCount, int retryIntervalSeconds) {
        this.toolRegistry = toolRegistry;
        this.backupEnabled = backupEnabled;
        this.retryCount = retryCount;
        this.retryIntervalSeconds = retryIntervalSeconds;
    }

    public void registerOnEventBus(EventBus eventBus) {
        subscriptions.add(eventBus.subscribe(StepStartedEvent.class, event -> {
            prepareStepBackup(event.getSeqId(), event.getStepId(), event.getAffectedFiles());
        }));
        subscriptions.add(eventBus.subscribe(StepFailedEvent.class, event -> {
            boolean restored = restoreStep(event.getSeqId(), event.getStepId());
            if (restored) {
                eventBus.publish(new StepRolledBackEvent(
                        event.getSeqId(), event.getSessionId(), event.getStepId(),
                        event.getTotalSteps(), event.getExpertName(), event.getAffectedFiles()));
            }
        }));
    }

    public void unregisterAll() {
        for (Subscription sub : subscriptions) {
            sub.unsubscribe();
        }
        subscriptions.clear();
    }

    /**
     * 为 Plan 准备备份（执行前调用）
     * <p>备份所有受影响文件到 staging 区域。</p>
     *
     * @param seqId 消息序列号
     * @param plan  执行计划
     */
    public void prepareBackup(String seqId, Plan plan) {
        if (!backupEnabled) return;

        BackupManifest manifest = new BackupManifest(seqId);
        String stagingDir = Constants.STAGING_DIR + "/" + seqId;

        // 收集所有受影响文件
        Set<String> allAffected = new HashSet<>();
        for (Step step : plan.getSteps()) {
            if (step.getAffectedFiles() != null) {
                allAffected.addAll(step.getAffectedFiles());
            }
        }

        // 逐个备份
        for (String filePath : allAffected) {
            Path source = Paths.get(filePath);
            if (!FileUtil.exists(source)) continue;

            // 快照路径：staging/{seqId}/snapshots/{filePath hash}
            String snapshotFileName = Integer.toHexString(filePath.hashCode()) + ".bak";
            Path snapshotPath = Paths.get(stagingDir, "snapshots", snapshotFileName);

            // 确保目录存在
            FileUtil.createDirectories(snapshotPath.getParent());

            // 复制文件到快照
            try {
                FileUtil.copy(source, snapshotPath);
                manifest.addEntry(filePath, snapshotPath.toString());
            } catch (Exception e) {
                // 备份失败：清理已备份的文件并抛出异常
                cleanup(seqId);
                throw new GovernanceException(ErrorCode.BACKUP_FAILED,
                        "备份失败: " + filePath, e, filePath);
            }
        }

        // 写入清单
        String manifestJson = JsonUtil.toJson(manifest);
        FileUtil.writeString(Paths.get(stagingDir, "manifest.json"), manifestJson);

        backups.put(seqId, manifest);
    }

    /**
     * 为单个 Step 准备备份（执行前调用）
     * <p>备份该 Step 受影响文件的当前版本，供失败时单步回滚。</p>
     *
     * @param seqId         消息序列号
     * @param stepId        步骤编号
     * @param affectedFiles 受影响的文件列表
     */
    public void prepareStepBackup(String seqId, int stepId, List<String> affectedFiles) {
        if (!backupEnabled || affectedFiles == null || affectedFiles.isEmpty()) return;

        String stepDir = Constants.STAGING_DIR + "/" + seqId + "/step_" + stepId;

        for (String filePath : affectedFiles) {
            if (filePath == null || filePath.isBlank()) continue;
            Path source = Paths.get(filePath);
            if (!FileUtil.exists(source)) continue;

            String snapshotFileName = Integer.toHexString(filePath.hashCode()) + ".bak";
            Path snapshotPath = Paths.get(stepDir, "snapshots", snapshotFileName);

            FileUtil.createDirectories(snapshotPath.getParent());

            try {
                FileUtil.copy(source, snapshotPath);
                BackupManifest manifest = (BackupManifest) backups.computeIfAbsent(seqId, k -> {
                    BackupManifest m = new BackupManifest(seqId);
                    FileUtil.createDirectories(Paths.get(Constants.STAGING_DIR + "/" + seqId));
                    return m;
                });
                manifest.addEntry(filePath, snapshotPath.toString(), stepId);
            } catch (Exception e) {
                throw new GovernanceException(ErrorCode.BACKUP_FAILED,
                        "步骤 " + stepId + " 备份失败: " + filePath, e, filePath);
            }
        }
    }

    /**
     * 恢复单个 Step 的备份文件
     *
     * @param seqId  消息序列号
     * @param stepId 步骤编号
     * @return true 若全部恢复成功
     */
    public boolean restoreStep(String seqId, int stepId) {
        BackupManifest manifest = backups.get(seqId);
        if (manifest == null) {
            manifest = loadManifestFromDisk(seqId);
            if (manifest == null) return false;
        }

        boolean allSuccess = true;
        for (BackupManifest.BackupEntry entry : manifest.getEntries()) {
            if (entry.getStepId() == stepId) {
                boolean restored = restoreWithRetry(entry);
                if (!restored) allSuccess = false;
            }
        }
        return allSuccess;
    }

    /**
     * 恢复所有备份文件
     *
     * @param seqId 消息序列号
     * @return true 若全部恢复成功
     */
    public boolean restoreAll(String seqId) {
        BackupManifest manifest = backups.get(seqId);
        if (manifest == null) {
            // 尝试从磁盘加载
            manifest = loadManifestFromDisk(seqId);
            if (manifest == null) return false;
        }

        boolean allSuccess = true;
        for (BackupManifest.BackupEntry entry : manifest.getEntries()) {
            boolean restored = restoreWithRetry(entry);
            if (!restored) allSuccess = false;
        }

        return allSuccess;
    }

    /**
     * 清理备份文件
     */
    public void cleanup(String seqId) {
        backups.remove(seqId);
        String stagingDir = Constants.STAGING_DIR + "/" + seqId;
        Path dir = Paths.get(stagingDir);
        if (FileUtil.exists(dir)) {
            try {
                FileUtil.delete(dir);
            } catch (Exception ignored) {
                // 清理失败不阻塞
            }
        }
    }

    private boolean restoreWithRetry(BackupManifest.BackupEntry entry) {
        for (int i = 0; i < retryCount; i++) {
            try {
                Path snapshot = Paths.get(entry.getSnapshotPath());
                Path original = Paths.get(entry.getOriginalPath());

                if (FileUtil.exists(snapshot)) {
                    FileUtil.copy(snapshot, original);
                    return true;
                }
                return false;
            } catch (Exception e) {
                if (i < retryCount - 1) {
                    try {
                        Thread.sleep(retryIntervalSeconds * 1000L);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        return false;
                    }
                }
            }
        }
        return false;
    }

    private BackupManifest loadManifestFromDisk(String seqId) {
        try {
            String manifestPath = Constants.STAGING_DIR + "/" + seqId + "/manifest.json";
            String json = FileUtil.readString(Paths.get(manifestPath));
            BackupManifest manifest = JsonUtil.fromJson(json, BackupManifest.class);
            backups.put(seqId, manifest);
            return manifest;
        } catch (Exception e) {
            return null;
        }
    }
}