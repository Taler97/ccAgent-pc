package com.ccagent.plugin.governance.rollback;

import com.ccagent.api.model.plan.Plan;
import com.ccagent.api.model.plan.Step;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * RollbackManager git 模式集成测试
 * <p>验证：git 仓库工作区下 prepareBackup 创建检查点，restoreAll 将会话级整批还原
 * （已跟踪文件内容恢复、未跟踪文件恢复、执行中新建文件删除）。</p>
 */
class GitRollbackTest {

    @TempDir
    Path repoDir;

    private Path trackedFile;
    private Path untrackedFile;
    private RollbackManager rollbackManager;

    @BeforeEach
    void setUp() throws Exception {
        // 环境无 git 时跳过
        Process p = new ProcessBuilder("git", "--version").start();
        p.waitFor(5, TimeUnit.SECONDS);
        assumeTrue(p.exitValue() == 0, "git 不可用，跳过 git 模式测试");

        // 初始化 git 仓库并提交初始状态
        runGit("init");
        runGit("config", "user.name", "test");
        runGit("config", "user.email", "test@test.local");

        trackedFile = repoDir.resolve("src.txt");
        Files.writeString(trackedFile, "v1-tracked", StandardCharsets.UTF_8);
        runGit("add", "src.txt");
        runGit("commit", "-m", "init");

        // 未跟踪文件：用户新建但未提交的文件
        untrackedFile = repoDir.resolve("new.txt");
        Files.writeString(untrackedFile, "v1-untracked", StandardCharsets.UTF_8);

        rollbackManager = new RollbackManager(null, true, 3, 1);
        rollbackManager.setRollbackProvider("git");
    }

    @Test
    void restoreAll_restoresTrackedAndUntrackedAndDeletesNewlyCreated() {
        String seqId = "git-test-seq";
        // 受影响文件：已跟踪 + 未跟踪 + 执行前不存在（声明将新建）的文件
        Path newlyCreated = repoDir.resolve("created-during-exec.txt");
        Plan plan = planWithFiles(List.of(
                trackedFile.toString(), untrackedFile.toString(), newlyCreated.toString()));

        rollbackManager.prepareBackup(seqId, plan, repoDir.toString());

        // 模拟 AI 执行修改：改已跟踪文件、改未跟踪文件、新建文件
        write(trackedFile, "v2-tracked-modified");
        write(untrackedFile, "v2-untracked-modified");
        write(newlyCreated, "should be deleted");

        boolean restored = rollbackManager.restoreAll(seqId);

        assertTrue(restored, "git 检查点还原应成功");
        assertEquals("v1-tracked", read(trackedFile), "已跟踪文件应恢复到执行前内容");
        assertEquals("v1-untracked", read(untrackedFile), "未跟踪文件应恢复到执行前内容");
        assertFalse(Files.exists(newlyCreated), "执行中新建的文件应被删除");

        rollbackManager.cleanup(seqId);
    }

    @Test
    void prepareBackup_inNonGitDirFallsBackToSnapshot() {
        // 非 git 目录：不应创建 git 检查点，应走快照路径（staging 由 Constants.BASE_DIR 决定）
        Path plainDir = repoDir.resolve("no-git");
        try {
            Files.createDirectories(plainDir);
        } catch (IOException ignored) {
        }
        Path f = plainDir.resolve("a.txt");
        write(f, "origin");

        Plan plan = planWithFiles(List.of(f.toString()));
        rollbackManager.prepareBackup("snap-seq", plan, plainDir.toString());

        write(f, "changed");
        boolean restored = rollbackManager.restoreAll("snap-seq");

        assertTrue(restored, "快照回退应可还原");
        assertEquals("origin", read(f), "快照应还原文件内容");
        rollbackManager.cleanup("snap-seq");
    }

    private Plan planWithFiles(List<String> files) {
        Plan plan = new Plan();
        Step step = new Step();
        step.setId(1);
        step.setAffectedFiles(files);
        plan.setSteps(List.of(step));
        return plan;
    }

    private void write(Path p, String content) {
        try {
            Files.writeString(p, content, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private String read(Path p) {
        try {
            return Files.readString(p, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private void runGit(String... args) throws Exception {
        ProcessBuilder pb = new ProcessBuilder("git");
        pb.command().addAll(List.of(args));
        pb.directory(repoDir.toFile());
        Process p = pb.start();
        p.getInputStream().readAllBytes();
        p.getErrorStream().readAllBytes();
        assumeTrue(p.waitFor(30, TimeUnit.SECONDS), "git 命令失败: " + String.join(" ", args));
    }
}
