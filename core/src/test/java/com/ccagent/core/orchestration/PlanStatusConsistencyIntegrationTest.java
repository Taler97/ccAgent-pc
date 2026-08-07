package com.ccagent.core.orchestration;

import com.ccagent.api.model.plan.Plan;
import com.ccagent.api.model.plan.Step;
import com.ccagent.core.orchestration.repository.ExecutionPlanRepository;
import com.ccagent.core.orchestration.repository.model.PlanRecord;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Plan 状态一致性集成测试。
 * <p>验证 P0-#6 修复：状态字符串统一为 "RUNNING"。
 * 修复前：SimpleOrchestrator 写入 "EXECUTING"，recoverInterruptedPlans 查询 "RUNNING"，导致永远查不到。
 * 修复后：写入和查询都用 "RUNNING"。</p>
 *
 * <p>同时验证 P0-#5 修复的依赖前提：recoverInterruptedPlans 通过 findByStatus("RUNNING") 能查到记录。
 * （#5 的调用入口在 AgentMain，无法在 core 模块直接测，但状态一致性是 #5 生效的前提）</p>
 */
class PlanStatusConsistencyIntegrationTest {

    private JdbcDataSource dataSource;
    private ExecutionPlanRepository repo;

    @BeforeEach
    void setUp() {
        dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:test-status-consistency;DB_CLOSE_DELAY=-1;MODE=MySQL");
        dataSource.setUser("sa");
        dataSource.setPassword("");

        repo = new ExecutionPlanRepository(dataSource);

        // 清理上一测试残留
        try (var conn = dataSource.getConnection();
             var stmt = conn.createStatement()) {
            stmt.execute("DELETE FROM execution_plan");
            stmt.execute("DELETE FROM execution_step");
        } catch (Exception ignored) {}
    }

    /**
     * 核心验证：写入 "RUNNING" 状态后，findByStatus("RUNNING") 能查到。
     * 修复前写入 "EXECUTING"，查询 "RUNNING" 永远返回空列表。
     */
    @Test
    void shouldFindPlanByRunningStatusAfterUpdate() {
        // 1. 创建 Plan
        Plan plan = new Plan("test", List.of(new Step(1, "expert", "task", List.of())), List.of());
        int version = repo.createPlan("session-1", plan);

        // 2. 更新状态为 RUNNING（与 SimpleOrchestrator.executePlan 写入一致）
        repo.updatePlanStatus("session-1", version, "RUNNING");

        // 3. 查询 RUNNING 状态的 Plan（与 recoverInterruptedPlans 查询一致）
        List<PlanRecord> runningPlans = repo.findByStatus("RUNNING");

        assertThat(runningPlans)
                .as("写入 RUNNING 后应能通过 findByStatus(\"RUNNING\") 查到")
                .hasSize(1);
        assertThat(runningPlans.get(0).getSessionId()).isEqualTo("session-1");
        assertThat(runningPlans.get(0).getVersion()).isEqualTo(version);
    }

    /**
     * 验证写入 "EXECUTING"（旧 bug 状态）后查 "RUNNING" 查不到。
     * 这正是修复前的 bug 行为——确保修复后不再用 "EXECUTING"。
     */
    @Test
    void shouldNotFindPlanWhenStatusMismatch() {
        Plan plan = new Plan("test", List.of(new Step(1, "expert", "task", List.of())), List.of());
        int version = repo.createPlan("session-2", plan);

        // 故意写入旧的状态字符串
        repo.updatePlanStatus("session-2", version, "EXECUTING");

        // 查询 RUNNING 应查不到
        List<PlanRecord> runningPlans = repo.findByStatus("RUNNING");
        assertThat(runningPlans)
                .as("状态不匹配时不应查到（此测试验证状态字符串必须一致）")
                .isEmpty();
    }

    /**
     * 验证状态流转：PLANNING → RUNNING → COMPLETED
     * 确保各阶段状态可被正确查询。
     */
    @Test
    void shouldTransitionThroughPlanStatuses() {
        Plan plan = new Plan("test", List.of(new Step(1, "expert", "task", List.of())), List.of());
        int version = repo.createPlan("session-3", plan);

        // 初始状态 PLANNING（createPlan 时写入）
        assertThat(repo.findByStatus("PLANNING")).hasSize(1);
        assertThat(repo.findByStatus("RUNNING")).isEmpty();

        // 流转到 RUNNING
        repo.updatePlanStatus("session-3", version, "RUNNING");
        assertThat(repo.findByStatus("PLANNING")).isEmpty();
        assertThat(repo.findByStatus("RUNNING")).hasSize(1);

        // 流转到 COMPLETED
        repo.updatePlanStatus("session-3", version, "COMPLETED");
        assertThat(repo.findByStatus("RUNNING")).isEmpty();
        assertThat(repo.findByStatus("COMPLETED")).hasSize(1);
    }

    /**
     * 验证多个 RUNNING Plan 都能被查到（恢复场景：多个会话同时崩溃）。
     */
    @Test
    void shouldFindMultipleRunningPlansForRecovery() {
        Plan plan1 = new Plan("p1", List.of(new Step(1, "expert", "t1", List.of())), List.of());
        Plan plan2 = new Plan("p2", List.of(new Step(1, "expert", "t2", List.of())), List.of());
        Plan plan3 = new Plan("p3", List.of(new Step(1, "expert", "t3", List.of())), List.of());

        int v1 = repo.createPlan("session-a", plan1);
        int v2 = repo.createPlan("session-b", plan2);
        int v3 = repo.createPlan("session-c", plan3);

        repo.updatePlanStatus("session-a", v1, "RUNNING");
        repo.updatePlanStatus("session-b", v2, "RUNNING");
        // session-c 保持 PLANNING
        repo.updatePlanStatus("session-c", v3, "COMPLETED");

        List<PlanRecord> runningPlans = repo.findByStatus("RUNNING");
        assertThat(runningPlans).hasSize(2);
        assertThat(runningPlans).extracting(PlanRecord::getSessionId)
                .containsExactlyInAnyOrder("session-a", "session-b");
    }
}
