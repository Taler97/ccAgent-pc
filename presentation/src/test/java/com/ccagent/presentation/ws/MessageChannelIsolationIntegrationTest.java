package com.ccagent.presentation.ws;

import com.ccagent.api.model.envelope.Envelope;
import com.ccagent.api.model.request.ExecuteRequest;
import com.ccagent.api.port.OrchestratorPort;
import com.ccagent.api.port.StreamObserver;
import com.ccagent.infrastructure.util.JsonUtil;
import com.ccagent.presentation.ws.MessageRouter.MessageSender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * 消息四通道隔离 + 异步执行集成测试。
 * <p>验证 P0-#1 修复：四通道隔离（dialogChannel/utilChannel/controlChannel/configChannel）。
 * EXECUTE 已异步提交（seqId 锁只持毫秒级），PING/CREATE_SESSION/MCP_IMPORT 等均不被对话通道阻塞，
 * EXECUTE_PLAN 等协作信号无需跳过锁即可 1s 内执行（D5 退役 DIALOG_SKIP_LOCK_TYPES）。</p>
 */
@ExtendWith(MockitoExtension.class)
class MessageChannelIsolationIntegrationTest {

    @Mock OrchestratorPort governanceGateway;

    @TempDir
    Path tempDir;

    private MessageDispatcher dispatcher;
    private MessageRouter router;

    @BeforeEach
    void setUp() {
        router = new MessageRouter(governanceGateway);
        dispatcher = new MessageDispatcher(router);
    }

    @AfterEach
    void tearDown() {
        dispatcher.shutdown();
    }

    /**
     * EXECUTE 持有 seqId 锁 2s 期间，PING（controlChannel）应立即返回 PONG。
     * 验证控制通道不被对话通道阻塞。
     */
    @Test
    void pingShouldNotBeBlockedByExecuteLock() throws Exception {
        // EXECUTE 持锁 2s
        CountDownLatch executeStarted = new CountDownLatch(1);
        doAnswer(inv -> {
            executeStarted.countDown();
            Thread.sleep(2000);
            return null;
        }).when(governanceGateway).executeWithSeqId(any(ExecuteRequest.class), anyString(), any(StreamObserver.class));

        // 记录 PONG 到达时间
        List<String> receivedTypes = new ArrayList<>();
        CountDownLatch pongReceived = new CountDownLatch(1);
        MessageSender sender = envelope -> {
            receivedTypes.add(envelope.getType());
            if ("PONG".equals(envelope.getType())) {
                pongReceived.countDown();
            }
        };

        // 1. 发送 EXECUTE（会加 seqId 锁 + sleep 2s）
        dispatcher.dispatch(toJson(new Envelope("EXECUTE", "seq-1",
                Instant.now().toString(),
                new ExecuteRequest("ws", "sess", "test", true))), sender);

        // 2. 等 EXECUTE 开始持锁
        assertThat(executeStarted.await(1, TimeUnit.SECONDS)).isTrue();
        Thread.sleep(200); // 确保 EXECUTE 已进入 withLock 临界区

        // 3. 发送 PING
        long pingStart = System.currentTimeMillis();
        dispatcher.dispatch(toJson(new Envelope("PING", "seq-2",
                Instant.now().toString(), Map.of())), sender);

        // 4. PONG 应在 1s 内返回（远小于 EXECUTE 的 2s 持锁）
        assertThat(pongReceived.await(1, TimeUnit.SECONDS))
                .as("PING 不应被 EXECUTE 的 seqId 锁阻塞")
                .isTrue();
        long pingLatency = System.currentTimeMillis() - pingStart;
        assertThat(pingLatency)
                .as("PONG 延迟应 < 1000ms，实际 " + pingLatency + "ms")
                .isLessThan(1000);

        assertThat(receivedTypes).contains("PONG");
    }

    /**
     * EXECUTE 持有 seqId 锁 2s 期间，CREATE_SESSION（utilChannel）应立即完成。
     * 验证工具通道不被对话通道阻塞。
     */
    @Test
    void createSessionShouldNotBeBlockedByExecuteLock() throws Exception {
        // EXECUTE 持锁 2s
        CountDownLatch executeStarted = new CountDownLatch(1);
        doAnswer(inv -> {
            executeStarted.countDown();
            Thread.sleep(2000);
            return null;
        }).when(governanceGateway).executeWithSeqId(any(ExecuteRequest.class), anyString(), any(StreamObserver.class));

        // CREATE_SESSION 立即返回
        doNothing().when(governanceGateway).createSession(anyString(), anyString());

        // 记录响应
        CountDownLatch sessionCreated = new CountDownLatch(1);
        AtomicLong createSessionLatency = new AtomicLong(0);
        MessageSender sender = envelope -> {
            // CREATE_SESSION 无响应消息，用 governanceGateway 调用时间判断
        };

        // 1. 发送 EXECUTE
        dispatcher.dispatch(toJson(new Envelope("EXECUTE", "seq-1",
                Instant.now().toString(),
                new ExecuteRequest("ws", "sess", "test", true))), sender);

        // 2. 等 EXECUTE 开始持锁
        assertThat(executeStarted.await(1, TimeUnit.SECONDS)).isTrue();
        Thread.sleep(200);

        // 3. 发送 CREATE_SESSION
        long start = System.currentTimeMillis();
        dispatcher.dispatch(toJson(new Envelope("CREATE_SESSION", "seq-2",
                Instant.now().toString(),
                Map.of("sessionId", "s1", "rootPath", tempDir.toString()))), sender);

        // 4. governanceGateway.createSession 应在 1s 内被调用（不等 EXECUTE 的 2s）
        verify(governanceGateway, timeout(1000)).createSession("s1", tempDir.toAbsolutePath().normalize().toString());
        createSessionLatency.set(System.currentTimeMillis() - start);
        assertThat(createSessionLatency.get())
                .as("CREATE_SESSION 延迟应 < 1000ms，实际 " + createSessionLatency.get() + "ms")
                .isLessThan(1000);
    }

    /**
     * EXECUTE 异步提交（执行体 sleep 2s，但 handler 已快速返回）后，
     * EXECUTE_PLAN（相同 seqId）应 1s 内执行——无需 DIALOG_SKIP_LOCK_TYPES 跳过锁。
     * 验证 D5：异步提交使 seqId 锁只持毫秒级，协作信号不再被长阻塞。
     */
    @Test
    void executePlanNotBlockedByAsyncExecute() throws Exception {
        // EXECUTE 执行体 sleep 2s（异步线程，不占 seqId 锁）
        CountDownLatch executeStarted = new CountDownLatch(1);
        doAnswer(inv -> {
            executeStarted.countDown();
            Thread.sleep(2000);
            return null;
        }).when(governanceGateway).executeWithSeqId(any(ExecuteRequest.class), anyString(), any(StreamObserver.class));

        // EXECUTE_PLAN 调 governanceGateway.executePlan(seqId, confirmed)
        CountDownLatch planExecuted = new CountDownLatch(1);
        doAnswer(inv -> {
            planExecuted.countDown();
            return null;
        }).when(governanceGateway).executePlan(anyString(), anyBoolean());

        MessageSender sender = envelope -> {};

        // 1. 发送 EXECUTE（seqId=seq-1，执行体 sleep 2s）
        dispatcher.dispatch(toJson(new Envelope("EXECUTE", "seq-1",
                Instant.now().toString(),
                new ExecuteRequest("ws", "sess", "test", true))), sender);

        // 2. 等 EXECUTE 执行体开始
        assertThat(executeStarted.await(1, TimeUnit.SECONDS)).isTrue();
        Thread.sleep(200);

        // 3. 发送 EXECUTE_PLAN（相同 seqId=seq-1）
        long start = System.currentTimeMillis();
        dispatcher.dispatch(toJson(new Envelope("EXECUTE_PLAN", "seq-1",
                Instant.now().toString(),
                Map.of("confirmed", "true"))), sender);

        // 4. EXECUTE_PLAN 应在 1s 内执行（handler 已返回，seqId 锁空闲）
        assertThat(planExecuted.await(1, TimeUnit.SECONDS))
                .as("EXECUTE_PLAN 不应被 EXECUTE 的异步执行体阻塞")
                .isTrue();
        long latency = System.currentTimeMillis() - start;
        assertThat(latency)
                .as("EXECUTE_PLAN 延迟应 < 1000ms，实际 " + latency + "ms")
                .isLessThan(1000);
    }

    /**
     * MCP_IMPORT_REQ（configChannel）在 EXECUTE 持锁期间应立即响应。
     * 验证配置/导入通道不被对话通道阻塞（四通道隔离）。
     */
    @Test
    void mcpImportShouldNotBeBlockedByExecuteLock() throws Exception {
        // EXECUTE 持锁 2s
        CountDownLatch executeStarted = new CountDownLatch(1);
        doAnswer(inv -> {
            executeStarted.countDown();
            Thread.sleep(2000);
            return null;
        }).when(governanceGateway).executeWithSeqId(any(ExecuteRequest.class), anyString(), any(StreamObserver.class));

        // MCP_IMPORT_REQ → configChannel → StatusHandler.importMcp（mcpManager 为 null → 回 ERROR）
        List<String> receivedTypes = new ArrayList<>();
        CountDownLatch errorReceived = new CountDownLatch(1);
        MessageSender sender = envelope -> {
            receivedTypes.add(envelope.getType());
            if ("ERROR".equals(envelope.getType())) {
                errorReceived.countDown();
            }
        };

        // 1. 发送 EXECUTE（seqId=seq-1，持锁 2s）
        dispatcher.dispatch(toJson(new Envelope("EXECUTE", "seq-1",
                Instant.now().toString(),
                new ExecuteRequest("ws", "sess", "test", true))), sender);

        // 2. 等 EXECUTE 开始持锁
        assertThat(executeStarted.await(1, TimeUnit.SECONDS)).isTrue();
        Thread.sleep(200);

        // 3. 发送 MCP_IMPORT_REQ（configChannel，空 configs）
        long start = System.currentTimeMillis();
        dispatcher.dispatch(toJson(new Envelope("MCP_IMPORT_REQ", "seq-2",
                Instant.now().toString(),
                Map.of("configs", List.of()))), sender);

        // 4. ERROR 响应应在 1s 内返回（configChannel 不被 dialogChannel 阻塞）
        assertThat(errorReceived.await(1, TimeUnit.SECONDS))
                .as("MCP_IMPORT_REQ 不应被 EXECUTE 的 seqId 锁阻塞")
                .isTrue();
        long latency = System.currentTimeMillis() - start;
        assertThat(latency)
                .as("MCP 导入延迟应 < 1000ms，实际 " + latency + "ms")
                .isLessThan(1000);
    }

    /**
     * QUERY_PROGRESS（utilChannel）在 EXECUTE 持锁期间应立即返回。
     * 这是用户反馈"连通测试/查询进度被阻塞"的核心场景。
     */
    @Test
    void queryProgressShouldNotBeBlockedByExecuteLock() throws Exception {
        // EXECUTE 持锁 2s
        CountDownLatch executeStarted = new CountDownLatch(1);
        doAnswer(inv -> {
            executeStarted.countDown();
            Thread.sleep(2000);
            return null;
        }).when(governanceGateway).executeWithSeqId(any(ExecuteRequest.class), anyString(), any(StreamObserver.class));

        // QUERY_PROGRESS 调 governanceGateway.getExecutionProgress(seqId) 返回快照
        when(governanceGateway.getExecutionProgress(anyString()))
                .thenReturn("{\"seqId\":\"seq-1\",\"totalSteps\":0}");

        MessageSender sender = envelope -> {};

        // 1. 发送 EXECUTE
        dispatcher.dispatch(toJson(new Envelope("EXECUTE", "seq-1",
                Instant.now().toString(),
                new ExecuteRequest("ws", "sess", "test", true))), sender);

        // 2. 等 EXECUTE 开始持锁
        assertThat(executeStarted.await(1, TimeUnit.SECONDS)).isTrue();
        Thread.sleep(200);

        // 3. 发送 QUERY_PROGRESS（相同 seqId）
        long start = System.currentTimeMillis();
        dispatcher.dispatch(toJson(new Envelope("QUERY_PROGRESS", "seq-1",
                Instant.now().toString(), Map.of())), sender);

        // 4. getExecutionProgress 应在 1s 内被调用
        verify(governanceGateway, timeout(1000)).getExecutionProgress("seq-1");
        long latency = System.currentTimeMillis() - start;
        assertThat(latency)
                .as("QUERY_PROGRESS 延迟应 < 1000ms，实际 " + latency + "ms")
                .isLessThan(1000);
    }

    private static String toJson(Envelope envelope) {
        return JsonUtil.toJson(envelope);
    }
}
