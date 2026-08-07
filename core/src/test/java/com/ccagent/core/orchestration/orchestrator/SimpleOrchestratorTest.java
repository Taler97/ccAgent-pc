package com.ccagent.core.orchestration.orchestrator;

import com.ccagent.api.model.request.ExecuteRequest;
import com.ccagent.api.model.response.StreamChunk;
import com.ccagent.api.port.ExpertRegistryPort;
import com.ccagent.api.port.MemoryPort;
import com.ccagent.api.port.StreamObserver;
import com.ccagent.core.orchestration.agent.ChatAgent;
import com.ccagent.core.orchestration.agent.ChatResult;
import com.ccagent.core.orchestration.dispatcher.Dispatcher;
import com.ccagent.core.orchestration.repository.ExecutionPlanRepository;
import com.ccagent.exception.base.ErrorCode;
import com.ccagent.exception.tool.ToolException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SimpleOrchestratorTest {

    @Mock ChatAgent chatAgent;
    @Mock Dispatcher dispatcher;
    @Mock MemoryPort memoryPort;
    @Mock ExpertRegistryPort expertRegistry;
    @Mock ExecutionPlanRepository executionPlanRepository;

    @Test
    void shouldCancelNonExistentExecution() {
        SimpleOrchestrator orchestrator = new SimpleOrchestrator(
                chatAgent, dispatcher, memoryPort, expertRegistry,
                executionPlanRepository);
        boolean result = orchestrator.cancelExecution("nonexistent-seq");
        assert !result : "取消不存在的执行应返回 false";
    }

    @Test
    void shouldConfirmNonExistentPlan() {
        SimpleOrchestrator orchestrator = new SimpleOrchestrator(
                chatAgent, dispatcher, memoryPort, expertRegistry,
                executionPlanRepository);
        orchestrator.confirmPlan("seq-1", true);
        orchestrator.confirmPlan("seq-1", false);
    }

    /**
     * 同会话忙时（第一次执行仍在决策）再次 EXECUTE → 拒绝（SESSION_BUSY，D1）。
     * 执行结束后会话恢复空闲，可再次执行。
     */
    @Test
    void secondExecuteOnBusySessionRejectedWithSessionBusy() throws Exception {
        SimpleOrchestrator orchestrator = new SimpleOrchestrator(
                chatAgent, dispatcher, memoryPort, expertRegistry,
                executionPlanRepository);

        // 第一次执行阻塞在 ChatAgent 决策（模拟长任务）
        CountDownLatch firstEntered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        when(chatAgent.chat(anyString(), anyString(), anyString(), any()))
                .thenAnswer(inv -> {
                    firstEntered.countDown();
                    release.await(5, TimeUnit.SECONDS);
                    return ChatResult.reply("你好");
                });

        ExecuteRequest req = new ExecuteRequest();
        req.setWorkspaceId("ws-1");
        req.setSessionId("s-1");
        req.setPrompt("你好");
        req.setAutoMode(true);

        // 第一个执行
        AtomicReference<Throwable> err1 = new AtomicReference<>();
        Thread t1 = Thread.startVirtualThread(() -> {
            try {
                orchestrator.execute(req, "seq-1", new NoopObserver());
            } catch (Throwable e) {
                err1.set(e);
            }
        });

        assertThat(firstEntered.await(2, TimeUnit.SECONDS)).isTrue();

        // 第二个执行：会话忙 → SESSION_BUSY
        AtomicReference<Throwable> err2 = new AtomicReference<>();
        Thread t2 = Thread.startVirtualThread(() -> {
            try {
                orchestrator.execute(req, "seq-2", new NoopObserver());
            } catch (Throwable e) {
                err2.set(e);
            }
        });
        t2.join(3000);

        assertThat(err2.get()).isInstanceOf(ToolException.class);
        assertThat(((ToolException) err2.get()).getErrorCode()).isEqualTo(ErrorCode.SESSION_BUSY);

        // 释放第一次执行，随后会话恢复空闲
        release.countDown();
        t1.join(5000);
        assertThat(err1.get()).isNull();

        // 第三次执行应成功（会话已复位）
        orchestrator.execute(req, "seq-3", new NoopObserver());
    }

    static class NoopObserver implements StreamObserver<StreamChunk> {
        @Override public void onNext(StreamChunk chunk) {}
        @Override public void onError(Throwable error) {}
        @Override public void onComplete() {}
    }
}
