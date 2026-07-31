package com.ccagent.core.orchestration.context;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class ExecutionContextTest {

    @Test
    void shouldCreateWithDefaults() {
        ExecutionContext ctx = new ExecutionContext();
        assertThat(ctx.getStage()).isEqualTo(ExecutionContext.ExecutionStage.IDLE);
    }

    @Test
    void shouldSetStage() {
        ExecutionContext ctx = new ExecutionContext();
        ctx.setStage(ExecutionContext.ExecutionStage.PLANNING);
        assertThat(ctx.getStage()).isEqualTo(ExecutionContext.ExecutionStage.PLANNING);
        ctx.setStage(ExecutionContext.ExecutionStage.EXECUTING);
        assertThat(ctx.getStage()).isEqualTo(ExecutionContext.ExecutionStage.EXECUTING);
    }

    @Test
    void shouldCarryFields() {
        ExecutionContext ctx = new ExecutionContext(
                ExecutionContext.ExecutionStage.EXECUTING,
                "sess-1", "/workspace", "seq-1"
        );
        assertThat(ctx.getSessionId()).isEqualTo("sess-1");
        assertThat(ctx.getWorkspaceRoot()).isEqualTo("/workspace");
        assertThat(ctx.getSeqId()).isEqualTo("seq-1");
    }
}
