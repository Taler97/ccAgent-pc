package com.ccagent.kernel.event;

public interface Subscription {

    void unsubscribe();

    boolean isActive();
}
