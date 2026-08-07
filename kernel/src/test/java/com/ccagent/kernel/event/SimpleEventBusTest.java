package com.ccagent.kernel.event;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * SimpleEventBus 单元测试（严格）
 * <p>覆盖：同步/异步分发、父类事件分发、取消订阅、异常隔离、限流、shutdown 语义与并发安全。</p>
 */
class SimpleEventBusTest {

    private SimpleEventBus bus;

    @AfterEach
    void tearDown() throws IOException {
        if (bus != null) bus.shutdown();
        // 清理持久化测试可能写入的运行时目录
        Path dir = Paths.get("./.codepal");
        if (Files.exists(dir)) {
            try (var stream = Files.walk(dir)) {
                stream.sorted(Comparator.reverseOrder()).forEach(p -> {
                    try {
                        Files.deleteIfExists(p);
                    } catch (IOException ignored) {
                    }
                });
            }
        }
    }

    // ==================== 事件类型 ====================

    static class BaseEvent extends Event {
        BaseEvent() { super(); }
        BaseEvent(Map<String, Object> metadata) { super(metadata); }
    }

    static class ChildEvent extends BaseEvent {
        ChildEvent() { super(); }
        ChildEvent(Map<String, Object> metadata) { super(metadata); }
    }

    // ==================== 同步分发 ====================

    @Test
    void shouldDeliverToSyncSubscriber() {
        bus = new SimpleEventBus(false, 100, 5);
        List<Event> received = new ArrayList<>();
        bus.subscribe(BaseEvent.class, received::add);

        bus.publish(new ChildEvent());

        assertThat(received).hasSize(1);
        assertThat(received.get(0)).isInstanceOf(ChildEvent.class);
    }

    @Test
    void shouldDeliverToAllSubscribers() {
        bus = new SimpleEventBus(false, 100, 5);
        AtomicInteger first = new AtomicInteger();
        AtomicInteger second = new AtomicInteger();
        bus.subscribe(BaseEvent.class, e -> first.incrementAndGet());
        bus.subscribe(BaseEvent.class, e -> second.incrementAndGet());

        bus.publish(new ChildEvent());

        assertThat(first.get()).isEqualTo(1);
        assertThat(second.get()).isEqualTo(1);
    }

    // ==================== 父类分发 ====================

    @Test
    void shouldDeliverChildEventToParentSubscriber() {
        bus = new SimpleEventBus(false, 100, 5);
        List<String> received = new ArrayList<>();
        bus.subscribe(BaseEvent.class, e -> received.add(e.getEventType()));

        bus.publish(new ChildEvent());

        assertThat(received).containsExactly("ChildEvent");
    }

    @Test
    void shouldNotDeliverParentEventToChildSubscriber() {
        bus = new SimpleEventBus(false, 100, 5);
        AtomicInteger childOnly = new AtomicInteger();
        bus.subscribe(ChildEvent.class, e -> childOnly.incrementAndGet());

        bus.publish(new BaseEvent());
        bus.publish(new ChildEvent());

        assertThat(childOnly.get()).isEqualTo(1); // 仅 ChildEvent 命中
    }

    // ==================== 取消订阅 ====================

    @Test
    void shouldStopDeliveryAfterUnsubscribe() {
        bus = new SimpleEventBus(false, 100, 5);
        AtomicInteger count = new AtomicInteger();
        Subscription sub = bus.subscribe(BaseEvent.class, e -> count.incrementAndGet());

        bus.publish(new ChildEvent());
        assertThat(count.get()).isEqualTo(1);

        sub.unsubscribe();
        bus.publish(new ChildEvent());
        assertThat(count.get()).isEqualTo(1); // 取消后不再收到
    }

    @Test
    void shouldBeIdempotentOnDoubleUnsubscribe() {
        bus = new SimpleEventBus(false, 100, 5);
        AtomicInteger count = new AtomicInteger();
        Subscription sub = bus.subscribe(BaseEvent.class, e -> count.incrementAndGet());

        sub.unsubscribe();
        assertThat(sub.isActive()).isFalse();
        sub.unsubscribe(); // 不抛异常
        bus.publish(new ChildEvent());
        assertThat(count.get()).isZero();
    }

    // ==================== 异常隔离 ====================

    @Test
    void shouldIsolateHandlerException() {
        bus = new SimpleEventBus(false, 100, 5);
        AtomicInteger healthy = new AtomicInteger();
        bus.subscribe(BaseEvent.class, e -> {
            throw new RuntimeException("handler boom");
        });
        bus.subscribe(BaseEvent.class, e -> healthy.incrementAndGet());

        // publish 不因 handler 异常而抛出
        bus.publish(new ChildEvent());

        assertThat(healthy.get()).isEqualTo(1);
    }

    // ==================== 异步订阅 ====================

    @Test
    void shouldDeliverAsyncOnAnotherThread() throws InterruptedException {
        bus = new SimpleEventBus(false, 100, 5);
        CountDownLatch latch = new CountDownLatch(1);
        Thread[] executedOn = new Thread[1];
        bus.subscribeAsync(BaseEvent.class, e -> {
            executedOn[0] = Thread.currentThread();
            latch.countDown();
        });

        bus.publish(new ChildEvent());

        assertThat(latch.await(2, TimeUnit.SECONDS)).isTrue();
        assertThat(executedOn[0]).isNotSameAs(Thread.currentThread());
    }

    // ==================== shutdown 语义 ====================

    @Test
    void shouldIgnorePublishAfterShutdown() {
        bus = new SimpleEventBus(false, 100, 5);
        AtomicInteger count = new AtomicInteger();
        bus.subscribe(BaseEvent.class, e -> count.incrementAndGet());

        bus.publish(new ChildEvent());
        bus.shutdown();
        bus.publish(new ChildEvent()); // shutdown 后静默忽略

        assertThat(count.get()).isEqualTo(1);
    }

    // ==================== 限流 ====================

    @Test
    void shouldRateLimitPublishes() {
        // 每秒 1 个令牌：第一个事件允许，随后立即发布的事件被限流丢弃
        bus = new SimpleEventBus(false, 100, 5, 1);
        AtomicInteger delivered = new AtomicInteger();
        bus.subscribe(BaseEvent.class, e -> delivered.incrementAndGet());

        int publishCount = 6;
        for (int i = 0; i < publishCount; i++) {
            bus.publish(new ChildEvent());
        }

        assertThat(delivered.get()).isEqualTo(1);
        assertThat(bus.getRateLimitedCount()).isEqualTo(publishCount - 1);
    }

    // ==================== 持久化队列 ====================

    @Test
    void shouldEnqueueEventsWhenPersistenceEnabled() {
        // flush 间隔 3600s，避免测试期间触发写盘
        bus = new SimpleEventBus(true, 100, 3600, 0);
        assertThat(bus.getPersistenceQueueSize()).isZero();

        bus.publish(new ChildEvent());
        bus.publish(new ChildEvent());

        assertThat(bus.getPersistenceQueueSize()).isEqualTo(2);
        assertThat(bus.getPersistenceBufferSize()).isEqualTo(100);
    }

    @Test
    void shouldNotEnqueueWhenPersistenceDisabled() {
        bus = new SimpleEventBus(false, 100, 5);
        bus.publish(new ChildEvent());
        assertThat(bus.getPersistenceQueueSize()).isZero();
    }

    // ==================== 并发安全 ====================

    @Test
    void shouldDeliverAllEventsUnderConcurrentPublish() throws InterruptedException {
        bus = new SimpleEventBus(false, 100, 5);
        int threads = 4;
        int perThread = 100;
        ConcurrentLinkedQueue<Event> received = new ConcurrentLinkedQueue<>();
        bus.subscribe(BaseEvent.class, received::add);

        CountDownLatch start = new CountDownLatch(1);
        List<Thread> publishers = new ArrayList<>();
        for (int t = 0; t < threads; t++) {
            Thread th = new Thread(() -> {
                try {
                    start.await();
                    for (int i = 0; i < perThread; i++) {
                        bus.publish(new ChildEvent());
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
            th.start();
            publishers.add(th);
        }
        start.countDown();
        for (Thread th : publishers) {
            th.join(5000);
        }

        assertThat(received).hasSize(threads * perThread);
    }
}
