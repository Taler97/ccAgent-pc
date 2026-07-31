package com.ccagent.kernel.event;

@FunctionalInterface
public interface EventHandler<T extends Event> {

    void handle(T event);

    default boolean isAsync() {
        return false;
    }
}
