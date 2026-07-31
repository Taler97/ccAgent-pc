package com.ccagent.core.sync;

import com.ccagent.core.sync.model.SyncDiff;
import com.ccagent.core.sync.model.SyncManifest;
import com.ccagent.core.sync.model.SyncResult;
import com.ccagent.infrastructure.util.JsonUtil;

import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

import com.fasterxml.jackson.core.type.TypeReference;

public class SyncService {

    private static final Logger LOG = Logger.getLogger(SyncService.class.getName());

    private final SyncClient syncClient;
    private final SyncManifestRepository manifestRepo;
    private final LocalDataRepository localDataRepo;

    public SyncService(SyncClient syncClient, SyncManifestRepository manifestRepo,
                       LocalDataRepository localDataRepo) {
        this.syncClient = syncClient;
        this.manifestRepo = manifestRepo;
        this.localDataRepo = localDataRepo;
    }

    public SyncResult syncAll() {
        long start = System.currentTimeMillis();
        int uploaded = 0;
        int downloaded = 0;
        int errors = 0;

        try {
            if (!syncClient.isCloudReachable()) {
                return new SyncResult(0, 0, 1, System.currentTimeMillis() - start);
            }

            List<SyncManifest> localManifest = manifestRepo.getAll();
            SyncDiff diff = syncClient.compareManifest(localManifest);

            for (SyncDiff.SyncManifestDiff entry : diff.getToUpload()) {
                try {
                    Object data = readLocalEntityData(entry.getEntityType(), entry.getEntityId());
                    String dataJson = JsonUtil.toJson(data);
                    String checksum = Integer.toHexString(dataJson.hashCode());
                    boolean ok = syncClient.uploadEntity(entry.getEntityType(), entry.getEntityId(),
                            entry.getLocalVersion(), dataJson, checksum);
                    if (ok) {
                        manifestRepo.updateChecksum(entry.getEntityType(), entry.getEntityId(), checksum);
                        uploaded++;
                    } else {
                        errors++;
                    }
                } catch (Exception e) {
                    LOG.warning("上传失败: " + entry.getEntityType() + "/" + entry.getEntityId() + ": " + e.getMessage());
                    errors++;
                }
            }

            for (SyncDiff.SyncManifestDiff entry : diff.getToDownload()) {
                try {
                    String dataJson = syncClient.downloadEntity(entry.getEntityType(), entry.getEntityId());
                    if (dataJson != null) {
                        writeRemoteEntityData(entry.getEntityType(), entry.getEntityId(),
                                entry.getCloudVersion(), dataJson);
                        manifestRepo.insertOrUpdate(entry.getEntityType(), entry.getEntityId(), entry.getCloudVersion());
                        downloaded++;
                    } else {
                        errors++;
                    }
                } catch (Exception e) {
                    LOG.warning("下载失败: " + entry.getEntityType() + "/" + entry.getEntityId() + ": " + e.getMessage());
                    errors++;
                }
            }

        } catch (Exception e) {
            LOG.warning("同步过程异常: " + e.getMessage());
            errors++;
        }

        return new SyncResult(uploaded, downloaded, errors, System.currentTimeMillis() - start);
    }

    private Object readLocalEntityData(String entityType, String entityId) {
        return switch (entityType) {
            case "audit_log" -> localDataRepo.queryAuditLog(entityId);
            case "conversation" -> localDataRepo.queryConversation(entityId);
            case "token_stats" -> localDataRepo.queryTokenStats(entityId);
            default -> {
                LOG.warning("未知同步实体类型: " + entityType);
                yield entityType + ":" + entityId;
            }
        };
    }

    @SuppressWarnings("unchecked")
    private void writeRemoteEntityData(String entityType, String entityId, int version, String dataJson) {
        try {
            switch (entityType) {
                case "audit_log" -> {
                    List<Map<String, Object>> records = JsonUtil.fromJson(dataJson, new TypeReference<List<Map<String, Object>>>() {});
                    for (Map<String, Object> record : records) {
                        localDataRepo.mergeAuditLog(
                                (String) record.get("seq_id"),
                                (String) record.get("event_type"),
                                (String) record.get("details"),
                                (String) record.get("created_at")
                        );
                    }
                }
                case "conversation" -> {
                    Map<String, Object> conv = JsonUtil.fromJson(dataJson, new TypeReference<Map<String, Object>>() {});
                    localDataRepo.saveConversation(
                            (String) conv.get("seq_id"),
                            (String) conv.get("session_id"),
                            (String) conv.get("workspace_id"),
                            (String) conv.get("prompt"),
                            (String) conv.get("plan_summary"),
                            (String) conv.get("plan_steps"),
                            (String) conv.get("assistant_response"),
                            (String) conv.get("execution_result"),
                            (String) conv.getOrDefault("status", "SUCCESS"),
                            toInt(conv.get("total_tokens")),
                            toInt(conv.get("cached_tokens")),
                            toDouble(conv.get("cache_hit_rate")),
                            toDouble(conv.get("estimated_cost"))
                    );
                }
                case "token_stats" -> {
                    Map<String, Object> stats = JsonUtil.fromJson(dataJson, new TypeReference<Map<String, Object>>() {});
                    localDataRepo.saveTokenStats(
                            (String) stats.get("session_id"),
                            toInt(stats.get("total_tokens")),
                            toInt(stats.get("cached_tokens")),
                            toDouble(stats.get("total_cost"))
                    );
                }
                default -> LOG.warning("未知同步实体类型，跳过写入: " + entityType);
            }
        } catch (Exception e) {
            LOG.warning("写入同步数据失败: " + entityType + "/" + entityId + ": " + e.getMessage());
        }
    }

    private static int toInt(Object value) {
        if (value instanceof Number) return ((Number) value).intValue();
        return 0;
    }

    private static double toDouble(Object value) {
        if (value instanceof Number) return ((Number) value).doubleValue();
        return 0.0;
    }
}
