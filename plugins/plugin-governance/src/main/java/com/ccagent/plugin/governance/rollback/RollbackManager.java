package com.ccagent.plugin.governance.rollback;

import com.ccagent.api.model.plan.Plan;
import com.ccagent.api.model.plan.Step;
import com.ccagent.api.model.response.ExecutionResult;
import com.ccagent.api.port.ToolRegistryPort;
import com.ccagent.core.event.PlanExecutionStartedEvent;
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

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 回滚管理器
 * <p>Plan 执行前批量备份所有受影响文件，失败或用户回退时整批恢复（会话级回滚）。</p>
 *
 * <h3>备份策略（rollbackProvider，默认 auto）</h3>
 * <ul>
 *   <li>git：工作区是 git 仓库时，用 `git hash-object -w` 将受影响文件内容写入对象库（blob 检查点），
 *       回滚时 `git cat-file blob` 原样还原。不污染 index 与分支历史，无引用对象由 git gc 回收。</li>
 *   <li>snapshot：文件快照到 staging 目录（非 git 仓库环境的回退）。</li>
 *   <li>auto：检测工作区是否为 git 仓库，是则 git，否则 snapshot。</li>
 * </ul>
 *
 * <h3>回滚粒度</h3>
 * <ul>
 *   <li>会话级：失败或用户回退时整批还原（与 Trae/Claude Code 一致，规避并行步骤的文件交叉污染）。</li>
 *   <li>以 seqId 作为检查点隔离键。</li>
 * </ul>
 */
public class RollbackManager implements ListenerExtensionPoint {

    private static final int GIT_CMD_TIMEOUT_SECONDS = 30;

    private ToolRegistryPort toolRegistry;
    private boolean backupEnabled;
    private int retryCount;
    private int retryIntervalSeconds;
    private String rollbackProvider = "auto";
    private final Map<String, BackupManifest> backups = new ConcurrentHashMap<>();
    private final Map<String, GitCheckpoint> gitCheckpoints = new ConcurrentHashMap<>();
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
        setRollbackProvider(govConfig.getRollbackProvider());

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

    public void setRollbackProvider(String rollbackProvider) {
        if (rollbackProvider != null && !rollbackProvider.isBlank()) {
            this.rollbackProvider = rollbackProvider;
        }
    }

    public void registerOnEventBus(EventBus eventBus) {
        subscriptions.add(eventBus.subscribe(PlanExecutionStartedEvent.class, event -> {
            // 整批备份在 Plan 执行前一次性完成，后续步骤事件不再重复 IO
            prepareBackup(event.getSeqId(), event.getPlan(), event.getWorkspaceRoot());
        }));
        subscriptions.add(eventBus.subscribe(StepStartedEvent.class, event -> {
            // 若整批备份已覆盖该步骤则零 IO 跳过，仅兼容未走整批备份的调用路径
            if (!isStepBackedUp(event.getSeqId(), event.getStepId())) {
                prepareStepBackup(event.getSeqId(), event.getStepId(), event.getAffectedFiles());
            }
        }));
        subscriptions.add(eventBus.subscribe(StepFailedEvent.class, event -> {
            // 会话级回滚：任一步骤失败即整批还原（restoreAll 幂等，批次内多个失败重复触发无害）
            boolean restored = restoreAll(event.getSeqId());
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
     * <p>按 rollbackProvider 选择备份策略：git 仓库用 git 检查点，否则文件快照。
     * 两者都记录受影响文件所属步骤（供兼容路径的步骤级判断），git 检查点为会话级。</p>
     *
     * @param seqId         消息序列号
     * @param plan          执行计划
     * @param workspaceRoot 工作区根路径（git 检测与命令执行目录）
     */
    public void prepareBackup(String seqId, Plan plan, String workspaceRoot) {
        if (!backupEnabled) return;

        List<String> allAffected = collectAffectedFiles(plan);
        if (allAffected.isEmpty()) return;

        if (useGit(workspaceRoot) && createGitCheckpoint(seqId, allAffected, workspaceRoot)) {
            return;
        }
        snapshotBackup(seqId, plan);
    }

    private List<String> collectAffectedFiles(Plan plan) {
        Set<String> files = new LinkedHashSet<>();
        if (plan.getSteps() != null) {
            for (Step step : plan.getSteps()) {
                if (step.getAffectedFiles() == null) continue;
                for (String f : step.getAffectedFiles()) {
                    if (f != null && !f.isBlank()) files.add(f);
                }
            }
        }
        return new ArrayList<>(files);
    }

    /**
     * 文件快照备份（非 git 仓库环境的回退）
     * <p>并行复制所有受影响文件到 staging 区域，并记录每个文件所属的步骤 ID，
     * 使 restoreStep 可以按步骤精确恢复。任一复制失败则清理本次备份并抛出异常。</p>
     */
    private void snapshotBackup(String seqId, Plan plan) {
        BackupManifest manifest = new BackupManifest(seqId);
        String stagingDir = Constants.STAGING_DIR + "/" + seqId;

        // 收集所有受影响文件：filePath -> 影响它的步骤 ID 列表（按步骤顺序）
        Map<String, List<Integer>> filesByStep = new LinkedHashMap<>();
        if (plan.getSteps() != null) {
            for (Step step : plan.getSteps()) {
                if (step.getAffectedFiles() == null) continue;
                for (String filePath : step.getAffectedFiles()) {
                    if (filePath == null || filePath.isBlank()) continue;
                    filesByStep.computeIfAbsent(filePath, k -> new ArrayList<>()).add(step.getId());
                }
            }
        }

        // 并行复制快照：filePath -> snapshotPath（复制成功的文件）
        Map<String, String> copied = new ConcurrentHashMap<>();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        List<CompletableFuture<Void>> tasks = new ArrayList<>();
        try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
            for (String filePath : filesByStep.keySet()) {
                tasks.add(CompletableFuture.runAsync(() -> {
                    try {
                        Path source = Paths.get(filePath);
                        if (!FileUtil.exists(source)) return;

                        String snapshotFileName = Integer.toHexString(filePath.hashCode()) + ".bak";
                        Path snapshotPath = Paths.get(stagingDir, "snapshots", snapshotFileName);
                        FileUtil.createDirectories(snapshotPath.getParent());
                        FileUtil.copy(source, snapshotPath);
                        copied.put(filePath, snapshotPath.toString());
                    } catch (Exception e) {
                        failure.compareAndSet(null, e);
                    }
                }, pool));
            }
        }
        CompletableFuture.allOf(tasks.toArray(new CompletableFuture[0])).join();

        if (failure.get() != null) {
            cleanup(seqId);
            throw new GovernanceException(ErrorCode.BACKUP_FAILED,
                    "备份失败: " + failure.get().getMessage(), failure.get());
        }

        // 记录 manifest：同一文件被多个步骤影响时，为每个步骤各记一条（快照相同）
        for (Map.Entry<String, List<Integer>> entry : filesByStep.entrySet()) {
            String snapshot = copied.get(entry.getKey());
            if (snapshot == null) continue; // 源文件不存在，跳过
            for (int stepId : entry.getValue()) {
                manifest.addEntry(entry.getKey(), snapshot, stepId);
            }
        }

        // 写入清单
        String manifestJson = JsonUtil.toJson(manifest);
        FileUtil.writeString(Paths.get(stagingDir, "manifest.json"), manifestJson);

        backups.put(seqId, manifest);
    }

    /**
     * 判断是否应使用 git 检查点：显式 git 或 auto 且工作区是 git 仓库
     */
    private boolean useGit(String workspaceRoot) {
        if (workspaceRoot == null || workspaceRoot.isBlank()) return false;
        if ("snapshot".equalsIgnoreCase(rollbackProvider)) return false;
        if ("git".equalsIgnoreCase(rollbackProvider)) return true;
        GitResult r = runGit(List.of("rev-parse", "--is-inside-work-tree"), Paths.get(workspaceRoot));
        return r.exitCode == 0 && "true".equals(r.stdout());
    }

    /**
     * 创建 git 检查点：对每个受影响文件 `git hash-object -w` 写入对象库并记录 blob hash，
     * 文件不存在记录 null（还原时删除）。不依赖跟踪状态、不污染 index 与分支历史，
     * 无引用对象由 git gc 回收。受影响文件必须都在仓库内，否则返回 false 回退快照。
     */
    private boolean createGitCheckpoint(String seqId, List<String> affectedFiles, String workspaceRoot) {
        Path ws = Paths.get(workspaceRoot).toAbsolutePath().normalize();
        GitResult top = runGit(List.of("rev-parse", "--show-toplevel"), ws);
        if (top.exitCode != 0 || top.stdout().isEmpty()) return false;
        Path repoRoot = Paths.get(top.stdout()).normalize();

        // 受影响文件必须位于仓库内，转换为仓库根相对路径；存在性检查
        Map<String, String> blobs = new LinkedHashMap<>(); // treePath -> blobHash（null=执行前不存在）
        List<String> existingFiles = new ArrayList<>();
        List<String> existingTreePaths = new ArrayList<>();
        for (String f : affectedFiles) {
            Path abs = Paths.get(f).toAbsolutePath().normalize();
            if (!abs.startsWith(repoRoot)) return false;
            String treePath = repoRoot.relativize(abs).toString().replace('\\', '/');
            blobs.put(treePath, null);
            if (Files.exists(abs)) {
                existingFiles.add(abs.toString());
                existingTreePaths.add(treePath);
            }
        }
        if (blobs.isEmpty()) return false;

        // 批量写入对象库：git hash-object -w --stdin-paths（每行一个绝对路径）
        if (!existingFiles.isEmpty()) {
            GitResult hr = runGit(List.of("hash-object", "-w", "--stdin-paths"),
                    repoRoot, String.join(System.lineSeparator(), existingFiles));
            if (hr.exitCode != 0) return false;
            List<String> hashes = hr.stdout().lines().filter(l -> !l.isBlank()).toList();
            if (hashes.size() != existingTreePaths.size()) return false;
            for (int i = 0; i < existingTreePaths.size(); i++) {
                blobs.put(existingTreePaths.get(i), hashes.get(i));
            }
        }

        gitCheckpoints.put(seqId, new GitCheckpoint(seqId, repoRoot.toString(), blobs));
        return true;
    }

    /**
     * 执行 git 命令并返回结果（UTF-8，超时强杀；stdin 可选写入）
     */
    private GitResult runGit(List<String> args, Path cwd) {
        return runGit(args, cwd, null);
    }

    private GitResult runGit(List<String> args, Path cwd, String stdin) {
        List<String> cmd = new ArrayList<>(args.size() + 1);
        cmd.add("git");
        cmd.addAll(args);
        try {
            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.directory(cwd.toFile());
            Process p = pb.start();
            if (stdin != null) {
                try (var os = p.getOutputStream()) {
                    os.write(stdin.getBytes(StandardCharsets.UTF_8));
                }
            }
            byte[] stdoutBytes = p.getInputStream().readAllBytes();
            String stderr = new String(p.getErrorStream().readAllBytes(), StandardCharsets.UTF_8);
            boolean done = p.waitFor(GIT_CMD_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            if (!done) {
                p.destroyForcibly();
                return new GitResult(-1, new byte[0], "git 命令超时: " + cmd);
            }
            return new GitResult(p.exitValue(), stdoutBytes, stderr);
        } catch (Exception e) {
            return new GitResult(-1, new byte[0], e.getMessage());
        }
    }

    /**
     * 判断指定步骤是否已有整批备份（内存判断，零 IO）
     */
    private boolean isStepBackedUp(String seqId, int stepId) {
        if (gitCheckpoints.containsKey(seqId)) return true;
        BackupManifest manifest = backups.get(seqId);
        if (manifest == null) return false;
        for (BackupManifest.BackupEntry e : manifest.getEntries()) {
            if (e.getStepId() == stepId) return true;
        }
        return false;
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
     * <p>仅快照模式支持步骤级恢复；git 检查点为会话级粒度，不支持步骤级，返回 false。</p>
     *
     * @param seqId  消息序列号
     * @param stepId 步骤编号
     * @return true 若全部恢复成功
     */
    public boolean restoreStep(String seqId, int stepId) {
        if (gitCheckpoints.containsKey(seqId)) return false;
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
     * 恢复所有备份文件（会话级整批还原）
     * <p>git 检查点优先，未命中时回退文件快照。</p>
     *
     * @param seqId 消息序列号
     * @return true 若全部恢复成功
     */
    public boolean restoreAll(String seqId) {
        if (gitCheckpoints.containsKey(seqId)) {
            return restoreGitCheckpoint(seqId);
        }
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
     * 用 git 检查点还原所有受影响文件
     * <p>blob 非 null：`git cat-file blob &lt;hash&gt;` 读取内容原样写回文件；
     * blob 为 null（执行前不存在）：删除当前文件。</p>
     */
    private boolean restoreGitCheckpoint(String seqId) {
        GitCheckpoint cp = gitCheckpoints.get(seqId);
        if (cp == null) return false;
        Path repoRoot = Paths.get(cp.repoRoot);

        boolean allSuccess = true;
        for (Map.Entry<String, String> entry : cp.blobs.entrySet()) {
            String treePath = entry.getKey();
            String blob = entry.getValue();
            Path f = repoRoot.resolve(treePath);
            if (blob != null) {
                GitResult r = runGit(List.of("cat-file", "blob", blob), repoRoot);
                if (r.exitCode != 0) {
                    allSuccess = false;
                    continue;
                }
                try {
                    if (f.getParent() != null) {
                        FileUtil.createDirectories(f.getParent());
                    }
                    java.nio.file.Files.write(f, r.stdoutBytes);
                } catch (Exception e) {
                    allSuccess = false;
                }
            } else {
                // 执行前不存在：删除执行中新建的文件
                try {
                    if (FileUtil.exists(f)) {
                        FileUtil.delete(f);
                    }
                } catch (Exception e) {
                    allSuccess = false;
                }
            }
        }
        return allSuccess;
    }

    /**
     * 清理备份
     * <p>git 检查点仅移除内存记录，对象留在对象库由 git gc 回收；快照删除 staging 目录。</p>
     */
    public void cleanup(String seqId) {
        gitCheckpoints.remove(seqId);
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

    /**
     * git 检查点：会话级快照（受影响文件 → blob hash，null 表示执行前不存在）
     */
    private static class GitCheckpoint {
        final String seqId;
        final String repoRoot;
        final Map<String, String> blobs;

        GitCheckpoint(String seqId, String repoRoot, Map<String, String> blobs) {
            this.seqId = seqId;
            this.repoRoot = repoRoot;
            this.blobs = blobs;
        }
    }

    /**
     * git 命令执行结果（stdout 保留原始字节，避免二进制内容被编码破坏）
     */
    private static class GitResult {
        final int exitCode;
        final byte[] stdoutBytes;
        final String stderr;

        GitResult(int exitCode, byte[] stdoutBytes, String stderr) {
            this.exitCode = exitCode;
            this.stdoutBytes = stdoutBytes;
            this.stderr = stderr;
        }

        String stdout() {
            return new String(stdoutBytes, StandardCharsets.UTF_8).trim();
        }
    }
}