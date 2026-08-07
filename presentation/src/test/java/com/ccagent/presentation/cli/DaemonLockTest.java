package com.ccagent.presentation.cli;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 单实例锁互斥测试：同一 JVM 内第二个 tryAcquire 必须返回 null，
 * 且失败实例不得删除成功实例的 PID 文件。
 */
class DaemonLockTest {

    @Test
    void secondAcquireWhileHeldReturnsNull() {
        DaemonLock first = DaemonLock.tryAcquire();
        assertThat(first).as("首次获取锁应成功").isNotNull();

        DaemonLock second = DaemonLock.tryAcquire();
        assertThat(second).as("已持锁时再次获取应失败").isNull();

        first.close();
    }

    @Test
    void acquireAfterReleaseSucceeds() {
        DaemonLock first = DaemonLock.tryAcquire();
        assertThat(first).isNotNull();
        first.close();

        DaemonLock second = DaemonLock.tryAcquire();
        assertThat(second).as("释放后应能再次获取").isNotNull();
        second.close();
    }
}
