package com.ccagent.kernel.event;

import com.ccagent.infrastructure.constant.Constants;
import com.ccagent.infrastructure.util.FileUtil;
import com.ccagent.infrastructure.util.JsonUtil;

import java.nio.file.Paths;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Level;
import java.util.logging.Logger;

public class SimpleEventBus implements EventBus {

    private static final Logger LOG = Logger.getLogger(SimpleEventBus.class.getName());

    /** 持久化入队最大等待时间：轻微背压，避免立即丢弃 */
    private static final long ENQUEUE_TIMEOUT_MS = 100L;
    /** 丢弃告警的最小间隔，防止洪峰时刷屏 */
    private static final long DROP_WARNING_INTERVAL_NANOS = TimeUnit.SECONDS.toNanos(10);

    private final Map<Class<?>, List<HandlerHolder<?>>> handlers = new ConcurrentHashMap<>();
    private final ExecutorService asyncExecutor;
    private final boolean persistenceEnabled;
    private final LinkedBlockingQueue<Event> persistenceQueue;
    private final ScheduledExecutorService persistenceScheduler;
    private final AtomicBoolean running = new AtomicBoolean(true);

    /** 持久化队列丢弃（队列满/入队超时）的事件数 */
    private final AtomicLong droppedEventCount = new AtomicLong();
    /** 限流丢弃的事件数 */
    private final AtomicLong rateLimitedCount = new AtomicLong();
    private final AtomicLong lastDropWarningNanos = new AtomicLong();

    // 可选限流（令牌桶）：rateLimitPerSecond <= 0 表示不限流
    private final double rateLimitPerSecond;
    private final double rateCapacity;
    private double availableTokens;
    private long lastRefillNanos;

    public SimpleEventBus() {
        this(true, 1000, 5, 0);
    }

    public SimpleEventBus(boolean persistenceEnabled, int bufferSize, int flushIntervalSeconds) {
        this(persistenceEnabled, bufferSize, flushIntervalSeconds, 0);
    }

    public SimpleEventBus(boolean persistenceEnabled, int bufferSize, int flushIntervalSeconds,
                          int rateLimitPerSecond) {
        this.asyncExecutor = Executors.newVirtualThreadPerTaskExecutor();
        this.persistenceEnabled = persistenceEnabled;
        this.persistenceQueue = new LinkedBlockingQueue<>(bufferSize);
        this.rateLimitPerSecond = Math.max(0, rateLimitPerSecond);
        this.rateCapacity = Math.max(1, this.rateLimitPerSecond);
        this.availableTokens = this.rateCapacity;
        this.lastRefillNanos = System.nanoTime();

        if (persistenceEnabled) {
            this.persistenceScheduler = Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "eventbus-flusher");
                t.setDaemon(true);
                return t;
            });
            this.persistenceScheduler.scheduleAtFixedRate(
                    this::flushPersistence,
                    flushIntervalSeconds,
                    flushIntervalSeconds,
                    TimeUnit.SECONDS
            );
        } else {
            this.persistenceScheduler = null;
        }
    }

    @Override
    public void publish(Event event) {
        if (!running.get()) return;

        // 可选限流：超限事件直接丢弃（默认关闭）
        if (rateLimitPerSecond > 0 && !tryAcquire()) {
            rateLimitedCount.incrementAndGet();
            logDropWarning("事件发布超限流阈值");
            return;
        }

        if (persistenceEnabled && !tryEnqueuePersistence(event)) {
            // 持久化入队失败（队列满/超时）：丢弃并告警；仍继续分发给订阅者，
            // 各订阅者有自己的队列与丢弃策略，互不影响
        }

        Class<?> eventClass = event.getClass();
        while (eventClass != null && eventClass != Object.class) {
            List<HandlerHolder<?>> holderList = handlers.get(eventClass);
            if (holderList != null) {
                for (HandlerHolder<?> holder : holderList) {
                    dispatch(holder, event);
                }
            }
            eventClass = eventClass.getSuperclass();
        }
    }

    private boolean tryEnqueuePersistence(Event event) {
        try {
            if (persistenceQueue.offer(event, ENQUEUE_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                return true;
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        droppedEventCount.incrementAndGet();
        logDropWarning("事件持久化队列已满");
        return false;
    }

    /**
     * 限流令牌桶：按固定速率补充令牌，容量 = 每秒速率
     */
    private synchronized boolean tryAcquire() {
        long now = System.nanoTime();
        double elapsedSeconds = (now - lastRefillNanos) / 1_000_000_000.0;
        lastRefillNanos = now;
        availableTokens = Math.min(rateCapacity, availableTokens + elapsedSeconds * rateLimitPerSecond);
        if (availableTokens >= 1.0) {
            availableTokens -= 1.0;
            return true;
        }
        return false;
    }

    private void logDropWarning(String reason) {
        long now = System.nanoTime();
        long last = lastDropWarningNanos.get();
        if (now - last > DROP_WARNING_INTERVAL_NANOS
                && lastDropWarningNanos.compareAndSet(last, now)) {
            LOG.warning(reason + "，丢弃事件（累计持久化丢弃 " + droppedEventCount.get()
                    + " 条，限流丢弃 " + rateLimitedCount.get() + " 条）");
        }
    }

    /** 持久化队列累计丢弃事件数 */
    public long getDroppedEventCount() {
        return droppedEventCount.get();
    }

    /** 限流累计丢弃事件数 */
    public long getRateLimitedCount() {
        return rateLimitedCount.get();
    }

    /** 持久化队列当前积压量 */
    public int getPersistenceQueueSize() {
        return persistenceQueue.size();
    }

    /** 持久化队列容量 */
    public int getPersistenceBufferSize() {
        return persistenceQueue.size() + persistenceQueue.remainingCapacity();
    }

    @SuppressWarnings("unchecked")
    private <T extends Event> void dispatch(HandlerHolder<T> holder, Event event) {
        EventHandler<T> handler = holder.handler;
        T typedEvent = (T) event;

        if (holder.async) {
            asyncExecutor.submit(() -> {
                try {
                    handler.handle(typedEvent);
                } catch (Exception e) {
                    LOG.log(Level.WARNING, "异步事件处理器异常: " + event.getEventType(), e);
                }
            });
        } else {
            try {
                handler.handle(typedEvent);
            } catch (Exception e) {
                LOG.log(Level.WARNING, "同步事件处理器异常: " + event.getEventType(), e);
            }
        }
    }

    @Override
    public <T extends Event> Subscription subscribe(Class<T> eventType, EventHandler<T> handler) {
        return doSubscribe(eventType, handler, false);
    }

    @Override
    public <T extends Event> Subscription subscribeAsync(Class<T> eventType, EventHandler<T> handler) {
        return doSubscribe(eventType, handler, true);
    }

    private <T extends Event> Subscription doSubscribe(Class<T> eventType, EventHandler<T> handler, boolean async) {
        HandlerHolder<T> holder = new HandlerHolder<>(handler, async);
        List<HandlerHolder<?>> list = handlers.computeIfAbsent(eventType, k -> new CopyOnWriteArrayList<>());
        list.add(holder);

        return new Subscription() {
            private volatile boolean active = true;

            @Override
            public void unsubscribe() {
                if (active) {
                    active = false;
                    list.remove(holder);
                }
            }

            @Override
            public boolean isActive() {
                return active;
            }
        };
    }

    private void flushPersistence() {
        if (persistenceQueue.isEmpty()) return;

        List<Event> batch = new java.util.ArrayList<>();
        persistenceQueue.drainTo(batch);

        StringBuilder sb = new StringBuilder();
        for (Event event : batch) {
            try {
                Map<String, Object> record = Map.of(
                        "id", event.getId(),
                        "type", event.getEventType(),
                        "timestamp", event.getTimestamp().toString(),
                        "metadata", event.getMetadata()
                );
                sb.append(JsonUtil.toJson(record)).append(System.lineSeparator());
            } catch (Exception e) {
                LOG.warning("事件序列化失败: " + event.getEventType());
            }
        }

        try {
            FileUtil.createDirectories(Paths.get(Constants.BASE_DIR));
            FileUtil.appendString(Paths.get(Constants.EVENT_LOG), sb.toString());
        } catch (Exception e) {
            LOG.warning("事件日志写入失败: " + e.getMessage());
        }
    }

    @Override
    public void shutdown() {
        if (running.compareAndSet(true, false)) {
            asyncExecutor.shutdown();
            if (persistenceScheduler != null) {
                persistenceScheduler.shutdown();
                try {
                    persistenceScheduler.awaitTermination(5, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                flushPersistence();
            }
            try {
                asyncExecutor.awaitTermination(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private static class HandlerHolder<T extends Event> {
        final EventHandler<T> handler;
        final boolean async;

        HandlerHolder(EventHandler<T> handler, boolean async) {
            this.handler = handler;
            this.async = async;
        }
    }
}
