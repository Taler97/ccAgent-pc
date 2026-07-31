package com.ccagent.core.governance.context;

import com.ccagent.core.orchestration.context.ExecutionContext;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class ExecutionContextHolderTest {

    @Test
    void shouldDefaultToIdle() {
        assertThat(ExecutionContextHolder.getStage()).isEqualTo(ExecutionContext.ExecutionStage.IDLE);
        ExecutionContextHolder.clear();
    }

    @Test
    void shouldSetAndGetContext() {
        ExecutionContext ctx = new ExecutionContext(
                ExecutionContext.ExecutionStage.PLANNING,
                "sess-1", "/ws", "seq-1"
        );
        ExecutionContextHolder.setContext(ctx);
        assertThat(ExecutionContextHolder.isPlanning()).isTrue();
        assertThat(ExecutionContextHolder.isExecuting()).isFalse();
        ExecutionContextHolder.clear();
    }

    @Test
    void shouldClearContext() {
        ExecutionContext ctx = new ExecutionContext();
        ctx.setStage(ExecutionContext.ExecutionStage.EXECUTING);
        ExecutionContextHolder.setContext(ctx);
        ExecutionContextHolder.clear();
        assertThat(ExecutionContextHolder.getStage()).isEqualTo(ExecutionContext.ExecutionStage.IDLE);
    }
}
