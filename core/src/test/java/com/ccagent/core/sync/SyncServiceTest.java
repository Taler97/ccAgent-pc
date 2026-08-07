package com.ccagent.core.sync;

import com.ccagent.core.sync.model.SyncDiff;
import com.ccagent.core.sync.model.SyncManifest;
import com.ccagent.core.sync.model.SyncResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * SyncService 单元测试（严格）
 * <p>覆盖同步全流程：云端不可达、上传成功/失败/异常、下载成功/失败及各实体类型的写入。</p>
 */
@ExtendWith(MockitoExtension.class)
class SyncServiceTest {

    @Mock
    SyncClient syncClient;

    @Mock
    SyncManifestRepository manifestRepo;

    @Mock
    LocalDataRepository localDataRepo;

    private SyncService service;

    @BeforeEach
    void setUp() {
        service = new SyncService(syncClient, manifestRepo, localDataRepo);
    }

    private SyncManifest manifest(String type, String id, int version) {
        return new SyncManifest(type, id, version, Instant.now(), null);
    }

    private SyncDiff diff(List<SyncDiff.SyncManifestDiff> upload, List<SyncDiff.SyncManifestDiff> download) {
        return new SyncDiff(upload, download);
    }

    private SyncDiff.SyncManifestDiff entry(String type, String id, int local, int cloud) {
        return new SyncDiff.SyncManifestDiff(type, id, local, cloud);
    }

    // ==================== 云端不可达 ====================

    @Test
    void shouldReportErrorWhenCloudUnreachable() {
        when(syncClient.isCloudReachable()).thenReturn(false);

        SyncResult result = service.syncAll();

        assertThat(result.getUploaded()).isZero();
        assertThat(result.getDownloaded()).isZero();
        assertThat(result.getErrors()).isEqualTo(1);
        assertThat(result.hasError()).isTrue();
        verify(syncClient, never()).compareManifest(any());
        verify(manifestRepo, never()).getAll();
    }

    // ==================== 上传路径 ====================

    @Test
    void shouldUploadLocalEntitiesAndUpdateChecksum() {
        when(syncClient.isCloudReachable()).thenReturn(true);
        when(manifestRepo.getAll()).thenReturn(List.of(manifest("conversation", "seq-1", 1)));
        when(syncClient.compareManifest(any()))
                .thenReturn(diff(List.of(entry("conversation", "seq-1", 1, 0)), List.of()));
        when(localDataRepo.queryConversation("seq-1")).thenReturn(
                Map.of("seq_id", "seq-1", "session_id", "s1"));
        when(syncClient.uploadEntity(eq("conversation"), eq("seq-1"), eq(1), anyString(), anyString()))
                .thenReturn(true);

        SyncResult result = service.syncAll();

        assertThat(result.getUploaded()).isEqualTo(1);
        assertThat(result.getDownloaded()).isZero();
        assertThat(result.getErrors()).isZero();
        // 上传成功后更新校验码
        verify(manifestRepo).updateChecksum(eq("conversation"), eq("seq-1"), anyString());
    }

    @Test
    void shouldCountUploadRejectionAsError() {
        when(syncClient.isCloudReachable()).thenReturn(true);
        when(manifestRepo.getAll()).thenReturn(List.of(manifest("conversation", "seq-1", 1)));
        when(syncClient.compareManifest(any()))
                .thenReturn(diff(List.of(entry("conversation", "seq-1", 1, 0)), List.of()));
        when(localDataRepo.queryConversation("seq-1")).thenReturn(Map.of("seq_id", "seq-1"));
        when(syncClient.uploadEntity(anyString(), anyString(), anyInt(), anyString(), anyString()))
                .thenReturn(false);

        SyncResult result = service.syncAll();

        assertThat(result.getUploaded()).isZero();
        assertThat(result.getErrors()).isEqualTo(1);
        verify(manifestRepo, never()).updateChecksum(anyString(), anyString(), anyString());
    }

    @Test
    void shouldCountUploadExceptionAsError() {
        when(syncClient.isCloudReachable()).thenReturn(true);
        when(manifestRepo.getAll()).thenReturn(List.of(manifest("audit_log", "a-1", 1)));
        when(syncClient.compareManifest(any()))
                .thenReturn(diff(List.of(entry("audit_log", "a-1", 1, 0)), List.of()));
        when(localDataRepo.queryAuditLog("a-1")).thenThrow(new RuntimeException("查询失败"));

        SyncResult result = service.syncAll();

        assertThat(result.getUploaded()).isZero();
        assertThat(result.getErrors()).isEqualTo(1);
        verify(syncClient, never()).uploadEntity(anyString(), anyString(), anyInt(), anyString(), anyString());
    }

    // ==================== 下载路径 ====================

    @Test
    void shouldDownloadAndPersistConversation() {
        when(syncClient.isCloudReachable()).thenReturn(true);
        when(manifestRepo.getAll()).thenReturn(List.of());
        when(syncClient.compareManifest(any()))
                .thenReturn(diff(List.of(), List.of(entry("conversation", "seq-9", 0, 3))));
        String json = """
                {"seq_id":"seq-9","session_id":"s1","workspace_id":"w1","prompt":"hi",\
                "plan_summary":"p","plan_steps":"[]","assistant_response":"a",\
                "execution_result":"{}","status":"SUCCESS","total_tokens":100,\
                "cached_tokens":50,"cache_hit_rate":0.4,"estimated_cost":0.01}
                """;
        when(syncClient.downloadEntity("conversation", "seq-9")).thenReturn(json);

        SyncResult result = service.syncAll();

        assertThat(result.getDownloaded()).isEqualTo(1);
        assertThat(result.getErrors()).isZero();
        // 严格验证落库参数
        verify(localDataRepo).saveConversation(
                "seq-9", "s1", "w1", "hi", "p", "[]", "a", "{}", "SUCCESS",
                100, 50, 0.4, 0.01);
        verify(manifestRepo).insertOrUpdate("conversation", "seq-9", 3);
    }

    @Test
    void shouldDownloadAndPersistTokenStats() {
        when(syncClient.isCloudReachable()).thenReturn(true);
        when(manifestRepo.getAll()).thenReturn(List.of());
        when(syncClient.compareManifest(any()))
                .thenReturn(diff(List.of(), List.of(entry("token_stats", "s9-2026-08-07", 0, 2))));
        String json = "{\"session_id\":\"s9\",\"total_tokens\":10,\"cached_tokens\":5,\"total_cost\":0.1}";
        when(syncClient.downloadEntity("token_stats", "s9-2026-08-07")).thenReturn(json);

        SyncResult result = service.syncAll();

        assertThat(result.getDownloaded()).isEqualTo(1);
        assertThat(result.getErrors()).isZero();
        verify(localDataRepo).saveTokenStats("s9", 10, 5, 0.1);
        verify(manifestRepo).insertOrUpdate("token_stats", "s9-2026-08-07", 2);
    }

    @Test
    void shouldDownloadAndMergeAuditLog() {
        when(syncClient.isCloudReachable()).thenReturn(true);
        when(manifestRepo.getAll()).thenReturn(List.of());
        when(syncClient.compareManifest(any()))
                .thenReturn(diff(List.of(), List.of(entry("audit_log", "a-1", 0, 1))));
        String json = "[{\"seq_id\":\"a-1\",\"event_type\":\"TOOL_CALL\",\"details\":\"{}\","
                + "\"created_at\":\"2026-01-01T00:00:00Z\"}]";
        when(syncClient.downloadEntity("audit_log", "a-1")).thenReturn(json);

        SyncResult result = service.syncAll();

        assertThat(result.getDownloaded()).isEqualTo(1);
        assertThat(result.getErrors()).isZero();
        verify(localDataRepo).mergeAuditLog("a-1", "TOOL_CALL", "{}", "2026-01-01T00:00:00Z");
    }

    @Test
    void shouldCountNullDownloadAsError() {
        when(syncClient.isCloudReachable()).thenReturn(true);
        when(manifestRepo.getAll()).thenReturn(List.of());
        when(syncClient.compareManifest(any()))
                .thenReturn(diff(List.of(), List.of(entry("conversation", "seq-x", 0, 1))));
        when(syncClient.downloadEntity("conversation", "seq-x")).thenReturn(null);

        SyncResult result = service.syncAll();

        assertThat(result.getDownloaded()).isZero();
        assertThat(result.getErrors()).isEqualTo(1);
        verify(manifestRepo, never()).insertOrUpdate(anyString(), anyString(), anyInt());
    }

    @Test
    void shouldSkipAndErrorOnUnknownEntityTypeUpload() {
        // 回归：未知实体类型不应被当作 "type:id" 字符串上传，应计为 error 并跳过
        when(syncClient.isCloudReachable()).thenReturn(true);
        when(manifestRepo.getAll()).thenReturn(List.of());
        when(syncClient.compareManifest(any()))
                .thenReturn(diff(List.of(entry("unknown_type", "x-1", 1, 0)), List.of()));

        SyncResult result = service.syncAll();

        assertThat(result.getUploaded()).isZero();
        assertThat(result.getErrors()).isEqualTo(1);
        verify(syncClient, never()).uploadEntity(anyString(), anyString(), anyInt(), anyString(), anyString());
        verify(manifestRepo, never()).updateChecksum(anyString(), anyString(), anyString());
    }
}
