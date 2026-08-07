package com.ccagent.core.context;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * FileArtifactStore manifest 清单行为测试
 * <p>验证：保存产物记录清单；手动删除产物文件后能检测缺失；清单丢失时回退磁盘枚举。</p>
 */
class FileArtifactStoreTest {

    @TempDir Path tempDir;

    private static final String SEQ = "seq-1";

    private FileArtifactStore newStore() {
        return new FileArtifactStore();
    }

    private Path artifactFile(String key) {
        return tempDir.resolve(".codepal/cache/artifacts/" + SEQ + "/" + key + ".json");
    }

    @Test
    void savePersistsAndRecordsManifest() {
        FileArtifactStore store = newStore();
        store.save(tempDir.toString(), SEQ, "analysis", "analysis-data");
        store.save(tempDir.toString(), SEQ, "code", "code-data");

        // 产物文件落盘
        assertThat(artifactFile("analysis")).exists();
        assertThat(artifactFile("code")).exists();

        // 清单与磁盘枚举一致
        assertThat(store.listKeys(tempDir.toString(), SEQ))
                .containsExactlyInAnyOrder("analysis", "code");
        // 无缺失
        assertThat(store.findMissingKeys(tempDir.toString(), SEQ)).isEmpty();
    }

    @Test
    void detectsDeletedArtifactAsMissing() throws Exception {
        FileArtifactStore store = newStore();
        store.save(tempDir.toString(), SEQ, "analysis", "analysis-data");
        store.save(tempDir.toString(), SEQ, "code", "code-data");

        // 模拟用户手动删除 analysis 产物文件
        Files.delete(artifactFile("analysis"));

        // 清单仍记录该 Key，可被检测为缺失
        assertThat(store.findMissingKeys(tempDir.toString(), SEQ))
                .containsExactly("analysis");

        // listKeys 仍包含缺失 Key（清单 ∪ 磁盘），供恢复层感知
        assertThat(store.listKeys(tempDir.toString(), SEQ))
                .containsExactlyInAnyOrder("analysis", "code");

        // load 缺失 Key 返回 empty
        assertThat(store.load(tempDir.toString(), SEQ, "analysis", String.class)).isEmpty();
    }

    @Test
    void detectsCorruptedArtifactAsMissing() throws Exception {
        FileArtifactStore store = newStore();
        store.save(tempDir.toString(), SEQ, "analysis", "analysis-data");

        // 模拟产物文件损坏（非法 JSON）
        Files.writeString(artifactFile("analysis"), "{ not-json");

        assertThat(store.findMissingKeys(tempDir.toString(), SEQ))
                .containsExactly("analysis");
        assertThat(store.load(tempDir.toString(), SEQ, "analysis", String.class)).isEmpty();
    }

    @Test
    void fallsBackToDiskEnumerationWhenManifestLost() throws Exception {
        FileArtifactStore store = newStore();
        store.save(tempDir.toString(), SEQ, "analysis", "analysis-data");

        // 模拟清单文件也被删除（老数据/用户误删）
        Files.delete(tempDir.resolve(".codepal/cache/artifacts/" + SEQ + "/manifest.json"));

        // 无法校验缺失，但 listKeys 仍从磁盘枚举恢复能力
        assertThat(store.listKeys(tempDir.toString(), SEQ))
                .containsExactly("analysis");
        assertThat(store.findMissingKeys(tempDir.toString(), SEQ)).isEmpty();
    }

    @Test
    void loadReturnsEmptyWhenArtifactNeverSaved() {
        FileArtifactStore store = newStore();
        assertThat(store.load(tempDir.toString(), SEQ, "nonexistent", String.class)).isEmpty();
        assertThat(store.listKeys(tempDir.toString(), SEQ)).isEmpty();
        assertThat(store.findMissingKeys(tempDir.toString(), SEQ)).isEmpty();
    }

    @Test
    void deleteAllClearsManifestAndArtifacts() {
        FileArtifactStore store = newStore();
        store.save(tempDir.toString(), SEQ, "analysis", "analysis-data");

        store.deleteAll(tempDir.toString(), SEQ);

        assertThat(store.listKeys(tempDir.toString(), SEQ)).isEmpty();
        assertThat(store.findMissingKeys(tempDir.toString(), SEQ)).isEmpty();
    }
}
