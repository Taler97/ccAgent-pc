package com.ccagent.core.governance.policy;

import com.ccagent.api.model.plan.Plan;
import com.ccagent.api.model.plan.Step;
import com.ccagent.exception.governance.GovernanceException;
import com.ccagent.exception.security.SecurityException;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PolicyEnforcerTest {

    private final PolicyEnforcer enforcer = new PolicyEnforcer();

    @Test
    void shouldRejectEmptyWorkspace() {
        assertThatThrownBy(() -> enforcer.checkWorkspace(null))
                .isInstanceOf(com.ccagent.exception.workspace.WorkspaceException.class);
    }

    @Test
    void shouldRejectPathTraversal() {
        assertThatThrownBy(() -> enforcer.checkPath("/workspace", "/workspace/../../etc/passwd"))
                .isInstanceOf(SecurityException.class);
    }

    @Test
    void shouldAcceptValidPath() {
        enforcer.checkPath("/workspace", "/workspace/src/main");
        // No exception expected
    }
}

class PlanSanitizerTest {

    private final PlanSanitizer sanitizer = new PlanSanitizer(10);

    @Test
    void shouldRejectNullPlan() {
        assertThatThrownBy(() -> sanitizer.validate(null))
                .isInstanceOf(GovernanceException.class);
    }

    @Test
    void shouldRejectEmptySteps() {
        Plan plan = new Plan("empty", List.of(), List.of());
        assertThatThrownBy(() -> sanitizer.validate(plan))
                .isInstanceOf(GovernanceException.class);
    }

    @Test
    void shouldRejectTooManySteps() {
        Plan plan = new Plan("too many",
                List.of(new Step(1, "e1", "task", List.of()),
                        new Step(2, "e2", "task", List.of()),
                        new Step(3, "e3", "task", List.of()),
                        new Step(4, "e4", "task", List.of()),
                        new Step(5, "e5", "task", List.of()),
                        new Step(6, "e6", "task", List.of()),
                        new Step(7, "e7", "task", List.of()),
                        new Step(8, "e8", "task", List.of()),
                        new Step(9, "e9", "task", List.of()),
                        new Step(10, "e10", "task", List.of()),
                        new Step(11, "e11", "task", List.of())),
                List.of());
        assertThatThrownBy(() -> sanitizer.validate(plan))
                .isInstanceOf(GovernanceException.class);
    }

    @Test
    void shouldAcceptValidPlan() {
        Plan plan = new Plan("valid",
                List.of(new Step(1, "code-writer", "write code", List.of("src/Main.java"))),
                List.of("src/Main.java"));
        sanitizer.validate(plan); // No exception expected
    }

    @Test
    void shouldRejectEmptyTask() {
        Plan plan = new Plan("empty task",
                List.of(new Step(1, "code-writer", "ab", List.of())),
                List.of());
        assertThatThrownBy(() -> sanitizer.validate(plan))
                .isInstanceOf(GovernanceException.class);
    }

    @Test
    void shouldRejectDuplicateStepIds() {
        Plan plan = new Plan("dup",
                List.of(new Step(1, "e1", "task one", List.of()),
                        new Step(1, "e2", "task two", List.of())),
                List.of());
        assertThatThrownBy(() -> sanitizer.validate(plan))
                .isInstanceOf(GovernanceException.class);
    }
}

class TokenQuotaServiceTest {

    @Test
    void shouldAllowWithinQuota() {
        TokenQuotaService q = new TokenQuotaService(1000);
        q.checkQuota("sess-1", 500); // No exception
    }

    @Test
    void shouldRejectOverQuota() {
        TokenQuotaService q = new TokenQuotaService(1000);
        q.recordUsage("sess-1", 800);
        assertThatThrownBy(() -> q.checkQuota("sess-1", 300))
                .isInstanceOf(GovernanceException.class);
    }

    @Test
    void shouldTrackUsage() {
        TokenQuotaService q = new TokenQuotaService(1000);
        q.recordUsage("sess-1", 300);
        q.recordUsage("sess-1", 200);
        assertThat(q.getCurrentUsage("sess-1")).isEqualTo(500);
    }

    @Test
    void shouldResetSession() {
        TokenQuotaService q = new TokenQuotaService(1000);
        q.recordUsage("sess-1", 500);
        q.resetSession("sess-1");
        assertThat(q.getCurrentUsage("sess-1")).isEqualTo(0);
    }
}
