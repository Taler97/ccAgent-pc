package com.ccagent.core.context;

import com.ccagent.infrastructure.constant.Constants;
import com.ccagent.infrastructure.util.FileUtil;
import com.ccagent.infrastructure.util.JsonUtil;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;

/**
 * 基于 JSON 文件的 ArtifactStore 实现
 * <p>存储路径：{workspaceRoot}/.codepal/cache/artifacts/{seqId}/{key}.json
 * 原子写入：先写 .tmp 再 rename。</p>
 */
public class FileArtifactStore implements ArtifactStore {

    private static final int LARGE_OBJECT_THRESHOLD = 1_048_576; // 1MB

    @Override
    public void save(String workspaceRoot, String seqId, String key, Object value) {
        try {
            Path dir = getArtifactDir(workspaceRoot, seqId);
            FileUtil.createDirectories(dir);

            String json = JsonUtil.toJson(value);
            Path tmpFile = dir.resolve(key + ".json.tmp");
            Path finalFile = dir.resolve(key + ".json");

            FileUtil.writeString(tmpFile, json);
            Files.move(tmpFile, finalFile,
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                    java.nio.file.StandardCopyOption.ATOMIC_MOVE);
        } catch (Exception e) {
            throw new RuntimeException("保存产物失败: seqId=" + seqId + ", key=" + key, e);
        }
    }

    @Override
    public <T> Optional<T> load(String workspaceRoot, String seqId, String key, Class<T> type) {
        Path file = getArtifactDir(workspaceRoot, seqId).resolve(key + ".json");
        if (!FileUtil.exists(file)) {
            return Optional.empty();
        }
        try {
            String json = FileUtil.readString(file);
            return Optional.of(JsonUtil.fromJson(json, type));
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    @Override
    public Set<String> listKeys(String workspaceRoot, String seqId) {
        Path dir = getArtifactDir(workspaceRoot, seqId);
        Set<String> keys = new HashSet<>();
        if (!FileUtil.isDirectory(dir)) {
            return keys;
        }
        try {
            for (Path file : FileUtil.listFiles(dir)) {
                String name = file.getFileName().toString();
                if (name.endsWith(".json")) {
                    keys.add(name.substring(0, name.length() - 5));
                }
            }
        } catch (Exception ignored) {}
        return keys;
    }

    @Override
    public void deleteAll(String workspaceRoot, String seqId) {
        Path dir = getArtifactDir(workspaceRoot, seqId);
        if (FileUtil.isDirectory(dir)) {
            try {
                for (Path file : FileUtil.listFiles(dir)) {
                    FileUtil.delete(file);
                }
                FileUtil.delete(dir);
            } catch (Exception ignored) {}
        }
    }

    private Path getArtifactDir(String workspaceRoot, String seqId) {
        String base = workspaceRoot != null && !workspaceRoot.isBlank()
                ? workspaceRoot + "/" + Constants.ARTIFACT_CACHE_DIR
                : Constants.ARTIFACT_CACHE_DIR;
        return Paths.get(base, seqId);
    }
}
