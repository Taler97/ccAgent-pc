package com.ccagent.core.orchestration.repository;

import com.ccagent.api.model.plan.Plan;
import com.ccagent.api.model.plan.Step;
import com.ccagent.core.orchestration.repository.model.PlanRecord;
import com.ccagent.core.orchestration.repository.model.StepRecord;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ExecutionPlanRepository 集成测试（真实 H2 内存库）
 * <p>覆盖：计划创建/读取、版本递增、状态流转、断点续存（completed_step_ids）、按状态查询。</p>
 */
class ExecutionPlanRepositoryTest {

    private ExecutionPlanRepository repository;

    @BeforeEach
    void setUp() {
        JdbcDataSource ds = new JdbcDataSource();
        ds.setURL("jdbc:h2:mem:plan-test-" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1");
        ds.setUser("sa");
        ds.setPassword("");
        repository = new ExecutionPlanRepository(ds);
    }

    private Plan samplePlan() {
        Step s1 = new Step(1, "researcher", "分析模块依赖", List.of("pom.xml"));
        Step s2 = new Step(2, "writer", "编写文档", List.of("README.md"));
        s2.setReads(Set.of("analysis"));
        s2.setWrites(Set.of("doc"));
        return new Plan("生成架构文档", List.of(s1, s2), List.of("README.md"));
    }

    // ==================== 创建与读取 ====================

    @Test
    void shouldCreatePlanWithVersionOne() {
        int version = repository.createPlan("sess-1", samplePlan());

        assertThat(version).isEqualTo(1);

        PlanRecord record = repository.getPlan("sess-1", 1);
        assertThat(record).isNotNull();
        assertThat(record.getVersion()).isEqualTo(1);
        assertThat(record.getOverallStatus()).isEqualTo("PLANNING");
        assertThat(record.getPlan().getSummary()).isEqualTo("生成架构文档");
        assertThat(record.getPlan().getSteps()).hasSize(2);
        assertThat(record.getSteps()).hasSize(2);
    }

    @Test
    void shouldIncrementVersionOnRecreate() {
        repository.createPlan("sess-1", samplePlan());
        int version2 = repository.createPlan("sess-1", samplePlan());

        assertThat(version2).isEqualTo(2);
        assertThat(repository.getPlan("sess-1", 1)).isNotNull();
        assertThat(repository.getPlan("sess-1", 2)).isNotNull();
    }

    @Test
    void shouldReturnNullForMissingPlan() {
        assertThat(repository.getPlan("no-such-session", 1)).isNull();
    }

    // ==================== 状态流转 ====================

    @Test
    void shouldUpdatePlanStatus() {
        repository.createPlan("sess-1", samplePlan());

        repository.updatePlanStatus("sess-1", 1, "RUNNING");
        assertThat(repository.getPlan("sess-1", 1).getOverallStatus()).isEqualTo("RUNNING");

        repository.updatePlanStatus("sess-1", 1, "SUCCESS");
        assertThat(repository.getPlan("sess-1", 1).getOverallStatus()).isEqualTo("SUCCESS");
    }

    @Test
    void shouldUpdateStepStatusAndTrackTimestamps() {
        repository.createPlan("sess-1", samplePlan());

        repository.updateStepStatus("sess-1", 1, 1, "RUNNING");
        StepRecord running = repository.getSteps("sess-1", 1).get(0);
        assertThat(running.getStatus()).isEqualTo("RUNNING");
        assertThat(running.getStartedAt()).isNotNull();

        repository.updateStepStatus("sess-1", 1, 1, "FAILED", "工具执行失败");
        StepRecord failed = repository.getSteps("sess-1", 1).get(0);
        assertThat(failed.getStatus()).isEqualTo("FAILED");
        assertThat(failed.getErrorMessage()).isEqualTo("工具执行失败");
        assertThat(failed.getCompletedAt()).isNotNull();
    }

    // ==================== 断点续存 ====================

    @Test
    void shouldTrackCompletedStepIds() {
        repository.createPlan("sess-1", samplePlan());

        assertThat(repository.getCompletedStepIds("sess-1", 1)).isEmpty();

        repository.addCompletedStep("sess-1", 1, 1);
        repository.addCompletedStep("sess-1", 1, 2);

        assertThat(repository.getCompletedStepIds("sess-1", 1)).containsExactlyInAnyOrder(1, 2);
        assertThat(repository.getPlan("sess-1", 1).getCompletedStepIds()).isNotNull();
    }

    @Test
    void shouldNotDuplicateCompletedStepIds() {
        repository.createPlan("sess-1", samplePlan());

        repository.addCompletedStep("sess-1", 1, 1);
        repository.addCompletedStep("sess-1", 1, 1);

        assertThat(repository.getCompletedStepIds("sess-1", 1)).containsExactly(1);
    }

    // ==================== 按状态查询 ====================

    @Test
    void shouldFindPlansByStatus() {
        repository.createPlan("sess-1", samplePlan());
        repository.updatePlanStatus("sess-1", 1, "RUNNING");

        List<PlanRecord> running = repository.findByStatus("RUNNING");
        assertThat(running).hasSize(1);
        assertThat(running.get(0).getSessionId()).isEqualTo("sess-1");
        assertThat(running.get(0).getPlan().getSummary()).isEqualTo("生成架构文档");

        assertThat(repository.findByStatus("SUCCESS")).isEmpty();
    }
}
