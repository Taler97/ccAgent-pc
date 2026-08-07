package com.ccagent.core.orchestration.state;

import com.ccagent.core.event.SessionStateChangedEvent;
import com.ccagent.kernel.event.Event;
import com.ccagent.kernel.event.SimpleEventBus;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 会话状态机单元测试。
 * <p>验证：忙判定与拒绝（D1）、状态转移合法性与事件发布（D2）、审批确认/取消唤醒、finish 复位。</p>
 */
class SessionStateMachineTest {

    private SimpleEventBus eventBus;
    private SessionStateMachine machine;
    private final List<Event> stateEvents = new ArrayList<>();

    @BeforeEach
    void setUp() {
        eventBus = new SimpleEventBus(false, 10, 1);
        eventBus.subscribe(SessionStateChangedEvent.class, e -> stateEvents.add(e));
        machine = new SessionStateMachine(eventBus);
    }

    @AfterEach
    void tearDown() {
        eventBus.shutdown();
    }

    @Test
    void tryBeginAcquiresSessionAndPublishesPlanning() {
        assertThat(machine.tryBegin("s1", "seq-1")).isTrue();
        assertThat(machine.isBusy("s1")).isTrue();
        assertThat(machine.getAgentState("s1")).isEqualTo(SessionStateMachine.PLANNING);
        assertThat(stateEvents).hasSize(1);
        assertThat(((SessionStateChangedEvent) stateEvents.get(0)).getToState())
                .isEqualTo(SessionStateMachine.PLANNING);
    }

    @Test
    void secondTryBeginOnBusySessionRejected() {
        assertThat(machine.tryBegin("s1", "seq-1")).isTrue();
        assertThat(machine.tryBegin("s1", "seq-2")).isFalse();
        assertThat(machine.getAgentState("s1")).isEqualTo(SessionStateMachine.PLANNING);
        // 同会话第二个 seqId 不应建立映射
        assertThat(machine.confirmApproval("seq-2", true)).isFalse();
    }

    @Test
    void approvalFlowTransitionAndConfirm() {
        machine.tryBegin("s1", "seq-1");
        CompletableFuture<Boolean> future = new CompletableFuture<>();
        machine.markApproval("s1", "seq-1", future);
        assertThat(machine.getAgentState("s1")).isEqualTo(SessionStateMachine.WAIT_APPROVAL);

        assertThat(machine.confirmApproval("seq-1", true)).isTrue();
        assertThat(future.join()).isTrue();
        assertThat(machine.getAgentState("s1")).isEqualTo(SessionStateMachine.EXECUTING);
    }

    @Test
    void confirmApprovalUnknownSeqIgnored() {
        assertThat(machine.confirmApproval("unknown", true)).isFalse();
    }

    @Test
    void requestCancelSetsFlagAndWakesApprovalWithFalse() {
        machine.tryBegin("s1", "seq-1");
        CompletableFuture<Boolean> future = new CompletableFuture<>();
        machine.markApproval("s1", "seq-1", future);

        assertThat(machine.requestCancel("seq-1")).isTrue();
        assertThat(machine.isCancelled("s1")).isTrue();
        assertThat(future.join()).isFalse();
    }

    @Test
    void requestCancelUnknownSeqIgnored() {
        assertThat(machine.requestCancel("unknown")).isFalse();
    }

    @Test
    void finishResetsSessionAndRemovesSeqMapping() {
        machine.tryBegin("s1", "seq-1");
        machine.finish("s1", "seq-1");
        assertThat(machine.isBusy("s1")).isFalse();
        assertThat(machine.getAgentState("s1")).isEqualTo(SessionStateMachine.IDLE);
        // finish 后 seq 映射清除，confirmApproval 不再生效
        assertThat(machine.confirmApproval("seq-1", true)).isFalse();
    }

    @Test
    void cancelFlagReusableAcrossExecutions() {
        machine.tryBegin("s1", "seq-1");
        assertThat(machine.getCancelFlag("s1")).isNotNull();
        machine.finish("s1", "seq-1");

        // 再次 tryBegin 复用同一 holder，取消标志被重置
        machine.tryBegin("s1", "seq-2");
        assertThat(machine.isCancelled("s1")).isFalse();
        machine.finish("s1", "seq-2");
    }

    @Test
    void markErrorThenFinish() {
        machine.tryBegin("s1", "seq-1");
        machine.markError("s1", "seq-1");
        assertThat(machine.getAgentState("s1")).isEqualTo(SessionStateMachine.ERROR);
        machine.finish("s1", "seq-1");
        assertThat(machine.getAgentState("s1")).isEqualTo(SessionStateMachine.IDLE);
        assertThat(machine.isBusy("s1")).isFalse();
    }
}
