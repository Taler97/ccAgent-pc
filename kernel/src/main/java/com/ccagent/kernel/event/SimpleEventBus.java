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
import java.util.logging.Level;
import java.util.logging.Logger;

public class SimpleEventBus implements EventBus {

    private static final Logger LOG = Logger.getLogger(SimpleEventBus.class.getName());

    private final Map<Class<?>, List<HandlerHolder<?>>> handlers = new ConcurrentHashMap<>();
    private final ExecutorService asyncExecutor;
    private final boolean persistenceEnabled;
    private final LinkedBlockingQueue<Event> persistenceQueue;
    private final ScheduledExecutorService persistenceScheduler;
    private final AtomicBoolean running = new AtomicBoolean(true);

    public SimpleEventBus() {
        this(true, 1000, 5);
    }

    public SimpleEventBus(boolean persistenceEnabled, int bufferSize, int flushIntervalSeconds) {
        this.asyncExecutor = Executors.newVirtualThreadPerTaskExecutor();
        this.persistenceEnabled = persistenceEnabled;
        this.persistenceQueue = new LinkedBlockingQueue<>(bufferSize);

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

        if (persistenceEnabled) {
            persistenceQueue.offer(event);
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
