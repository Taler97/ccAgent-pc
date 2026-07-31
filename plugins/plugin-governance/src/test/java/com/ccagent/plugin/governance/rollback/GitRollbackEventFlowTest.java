package com.ccagent.plugin.governance.rollback;

import com.ccagent.api.model.plan.Plan;
import com.ccagent.api.model.plan.Step;
import com.ccagent.core.event.PlanExecutionStartedEvent;
import com.ccagent.core.event.StepFailedEvent;
import com.ccagent.core.event.StepStartedEvent;
import com.ccagent.kernel.event.SimpleEventBus;
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
 * git 会话级回滚真实场景测试
 * <p>通过事件总线走完整执行流：Plan 执行前创建 git 检查点 → 两个并行步骤执行
 * （修改已跟踪文本 / 未跟踪文件 / 二进制文件）→ 步骤 2 失败 → 整批还原。</p>
 *
 * <p>核心验证点：</p>
 * <ul>
 *   <li><b>会话级语义</b>：任一步骤失败，已成功步骤的修改也被整批还原（与步骤级回滚的区别）</li>
 *   <li><b>二进制安全</b>：含 \0 字节的文件经 git 对象库往返后内容逐字节一致</li>
 *   <li><b>零污染</b>：还原后 git 工作区/暂存区与执行前一致（仅保留原有的未跟踪文件）</li>
 * </ul>
 */
class GitRollbackEventFlowTest {

    @TempDir
    Path repoDir;

    private SimpleEventBus eventBus;
    private RollbackManager rollbackManager;

    private final String seqId = "flow-seq-001";
    private final String sessionId = "session-flow-test";

    // 受影响文件：已跟踪文本、未跟踪文本、已跟踪二进制
    private Path fileA;
    private Path fileB;
    private Path newFile;
    private Path binaryFile;

    @BeforeEach
    void setUp() throws Exception {
        Process p = new ProcessBuilder("git", "--version").start();
        p.waitFor(5, TimeUnit.SECONDS);
        assumeTrue(p.exitValue() == 0, "git 不可用，跳过 git 场景测试");

        runGit("init");
        runGit("config", "user.name", "test");
        runGit("config", "user.email", "test@test.local");

        // 初始状态：A、B、二进制文件已提交；newFile 是用户未跟踪的新文件
        fileA = repoDir.resolve("src/main/java/A.java");
        fileB = repoDir.resolve("src/main/java/B.java");
        binaryFile = repoDir.resolve("assets/model.bin");
        Files.createDirectories(fileA.getParent());
        Files.createDirectories(fileB.getParent());
        Files.createDirectories(binaryFile.getParent());
        Files.writeString(fileA, "public class A { int x = 1; }", StandardCharsets.UTF_8);
        Files.writeString(fileB, "public class B { int y = 2; }", StandardCharsets.UTF_8);
        byte[] bin = new byte[]{0x00, 0x01, 0x02, (byte) 0xFF, 0x00, 0x7F};
        Files.write(binaryFile, bin);
        runGit("add", ".");
        runGit("commit", "-m", "init");

        newFile = repoDir.resolve("src/newFeature.txt");
        Files.createDirectories(newFile.getParent());
        Files.writeString(newFile, "draft-v1", StandardCharsets.UTF_8);

        // 装配：git 模式的 RollbackManager + 事件总线（关闭持久化，避免写本地日志）
        rollbackManager = new RollbackManager(null, true, 3, 1);
        rollbackManager.setRollbackProvider("git");
        eventBus = new SimpleEventBus(false, 100, 1);
        rollbackManager.registerOnEventBus(eventBus);
    }

    @Test
    void stepFailure_triggersSessionLevelRollbackViaEventBus() throws Exception {
        // 1. 规划一个 2 步 Plan：step1 改 A/newFile/二进制，step2 改 B
        Plan plan = planWithSteps(
                step(1, List.of(fileA.toString(), newFile.toString(), binaryFile.toString())),
                step(2, List.of(fileB.toString())));

        // 2. Plan 确认执行 → 触发整批 git 检查点（RollbackManager 同步回调）
        eventBus.publish(new PlanExecutionStartedEvent(seqId, sessionId, plan, repoDir.toString()));

        // 3. 模拟 DAG 批次执行：step1 与 step2 并行
        //    step1 成功：A、newFile、二进制被修改
        eventBus.publish(new StepStartedEvent(seqId, sessionId, 1, 2, "writer",
                List.of(fileA.toString(), newFile.toString(), binaryFile.toString())));
        write(fileA, "public class A { int x = 999; } // step1 modified");
        write(newFile, "draft-v2-step1");
        Files.write(binaryFile, new byte[]{0x00, 0x00, 0x00, 0x11, 0x22, 0x33});

        //    step2 失败：B 已被修改，然后执行报错
        eventBus.publish(new StepStartedEvent(seqId, sessionId, 2, 2, "writer",
                List.of(fileB.toString())));
        write(fileB, "public class B { int y = 999; } // step2 partial");
        eventBus.publish(new StepFailedEvent(seqId, sessionId, 2, 2, "writer",
                List.of(fileB.toString()), "编译错误: B.java:3"));

        // 4. 会话级整批还原：即使 step1 成功，其修改也一并还原（对齐 Trae/Claude Code 语义）
        assertEquals("public class A { int x = 1; }", read(fileA), "已成功步骤的文件修改应被整批还原");
        assertEquals("public class B { int y = 2; }", read(fileB), "失败步骤的文件应还原到执行前");
        assertEquals("draft-v1", read(newFile), "未跟踪文件应还原到执行前内容");
        assertArrayEquals(new byte[]{0x00, 0x01, 0x02, (byte) 0xFF, 0x00, 0x7F},
                Files.readAllBytes(binaryFile), "二进制文件应逐字节还原");

        // 5. 零污染：工作区/暂存区与执行前一致（只有原有未跟踪文件，无新增修改/暂存条目）
        assertEquals("?? src/newFeature.txt", runGit("status", "--porcelain").trim(),
                "git 工作区与暂存区应无污染");

        rollbackManager.cleanup(seqId);
        eventBus.shutdown();
    }

    private Plan planWithSteps(Step... steps) {
        Plan plan = new Plan();
        plan.setSummary("真实场景：git 会话级回滚");
        plan.setSteps(List.of(steps));
        return plan;
    }

    private Step step(int id, List<String> files) {
        Step s = new Step();
        s.setId(id);
        s.setExpertName("writer");
        s.setTask("修改文件");
        s.setAffectedFiles(files);
        return s;
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

    private String runGit(String... args) throws Exception {
        ProcessBuilder pb = new ProcessBuilder("git");
        pb.command().addAll(List.of(args));
        pb.directory(repoDir.toFile());
        Process p = pb.start();
        String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        p.getErrorStream().readAllBytes();
        assumeTrue(p.waitFor(30, TimeUnit.SECONDS), "git 命令失败: " + String.join(" ", args));
        return out;
    }
}
