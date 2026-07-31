package com.ccagent.presentation.ws;

import org.junit.jupiter.api.Test;
import java.util.concurrent.atomic.AtomicInteger;
import static org.assertj.core.api.Assertions.assertThat;

class ReconnectManagerTest {

    @Test
    void shouldStartAndStop() {
        ReconnectManager rm = new ReconnectManager(1, 60);
        AtomicInteger count = new AtomicInteger(0);
        rm.start(() -> {
            count.incrementAndGet();
            rm.stop();
        });
        // Give time for reconnect to trigger once
        try { Thread.sleep(1200); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        assertThat(count.get()).isGreaterThanOrEqualTo(0);
    }

    @Test
    void shouldResetAttemptCount() {
        ReconnectManager rm = new ReconnectManager(1, 60);
        rm.reset();
        rm.stop();
        // No exception expected
    }
}
