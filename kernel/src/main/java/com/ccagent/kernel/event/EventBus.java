package com.ccagent.kernel.event;

public interface EventBus {

    void publish(Event event);

    <T extends Event> Subscription subscribe(Class<T> eventType, EventHandler<T> handler);

    <T extends Event> Subscription subscribeAsync(Class<T> eventType, EventHandler<T> handler);

    void shutdown();
}
