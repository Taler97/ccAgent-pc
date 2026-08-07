package com.ccagent.core.context;

import com.ccagent.infrastructure.constant.Constants;
import com.ccagent.infrastructure.util.FileUtil;
import com.ccagent.infrastructure.util.JsonUtil;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 基于 JSON 文件的 ArtifactStore 实现
 * <p>存储路径：{workspaceRoot}/.codepal/cache/artifacts/{seqId}/{key}.json
 * 原子写入：先写 .tmp 再 rename。</p>
 *
 * <h3>manifest 清单（断点恢复的完整性校验）</h3>
 * <p>每个任务目录维护 {@code manifest.json}，记录该任务持久化过的全部 Key。
 * 恢复时按清单校验而非枚举磁盘，可发现"用户手动删除产物文件"导致的缺失：
 * </p>
 * <ul>
 *   <li>{@link #listKeys} 返回 清单 ∪ 磁盘枚举 的并集（兼容清单丢失/老数据）</li>
 *   <li>{@link #findMissingKeys} 返回清单中有但磁盘文件缺失/损坏的 Key</li>
 * </ul>
 */
public class FileArtifactStore implements ArtifactStore {

    private static final int LARGE_OBJECT_THRESHOLD = 1_048_576; // 1MB
    /** 清单文件名（枚举目录时需排除，避免被当作产物 Key） */
    private static final String MANIFEST_FILE = "manifest.json";

    /** 按任务目录粒度的清单写锁，避免并发 save 时清单读-改-写竞争 */
    private final ConcurrentHashMap<String, Object> manifestLocks = new ConcurrentHashMap<>();

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

            addManifestKey(dir, key);
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
        // 清单 ∪ 磁盘枚举：清单能发现"被删但仍应存在"的 Key；磁盘枚举兼容清单丢失/老数据
        Set<String> keys = readManifest(dir);
        if (FileUtil.isDirectory(dir)) {
            try {
                for (Path file : FileUtil.listFiles(dir)) {
                    String name = file.getFileName().toString();
                    if (name.endsWith(".json") && !MANIFEST_FILE.equals(name)) {
                        keys.add(name.substring(0, name.length() - 5));
                    }
                }
            } catch (Exception ignored) {}
        }
        return keys;
    }

    /**
     * 查找清单中记录但磁盘文件缺失（或损坏不可读）的 Key。
     * <p>这是恢复断点时检测"用户手动删除产物"的依据。</p>
     *
     * @return 缺失 Key 集合；清单不存在时返回空集（视为无校验信息）
     */
    public Set<String> findMissingKeys(String workspaceRoot, String seqId) {
        Set<String> manifestKeys = readManifest(getArtifactDir(workspaceRoot, seqId));
        if (manifestKeys.isEmpty()) {
            return new HashSet<>();
        }
        Set<String> missing = new HashSet<>();
        for (String key : manifestKeys) {
            // 逐一实际加载验证：文件缺失或内容损坏（解析失败）均视为缺失
            if (load(workspaceRoot, seqId, key, String.class).isEmpty()) {
                missing.add(key);
            }
        }
        return missing;
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
        manifestLocks.remove(dir.toString());
    }

    // ==================== manifest 清单维护 ====================

    /**
     * 将 Key 追加进清单（幂等，按任务目录加锁后原子写回）。
     */
    private void addManifestKey(Path dir, String key) {
        Object lock = manifestLocks.computeIfAbsent(dir.toString(), k -> new Object());
        synchronized (lock) {
            try {
                Set<String> keys = readManifest(dir);
                if (!keys.add(key)) {
                    return; // 已存在，无需重写
                }
                Path tmp = dir.resolve(MANIFEST_FILE + ".tmp");
                FileUtil.writeString(tmp, JsonUtil.toJson(Map.of("keys", List.copyOf(keys))));
                Files.move(tmp, dir.resolve(MANIFEST_FILE),
                        java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                        java.nio.file.StandardCopyOption.ATOMIC_MOVE);
            } catch (Exception e) {
                // 清单写入失败不阻塞产物保存，仅降级为无校验能力
                return;
            }
        }
    }

    /**
     * 读取清单中的 Key 集合；清单不存在或损坏返回空集。
     */
    private Set<String> readManifest(Path dir) {
        Path manifest = dir.resolve(MANIFEST_FILE);
        if (!FileUtil.exists(manifest)) {
            return new HashSet<>();
        }
        try {
            String json = FileUtil.readString(manifest);
            Map<String, Object> data = JsonUtil.fromJson(json, Map.class);
            Object raw = data.get("keys");
            if (raw instanceof List<?> list) {
                Set<String> keys = new HashSet<>();
                for (Object item : list) {
                    keys.add(String.valueOf(item));
                }
                return keys;
            }
        } catch (Exception ignored) {}
        return new HashSet<>();
    }

    private Path getArtifactDir(String workspaceRoot, String seqId) {
        String base = workspaceRoot != null && !workspaceRoot.isBlank()
                ? workspaceRoot + "/" + Constants.ARTIFACT_CACHE_DIR
                : Constants.ARTIFACT_CACHE_DIR;
        return Paths.get(base, seqId);
    }
}
