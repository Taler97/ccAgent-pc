package com.ccagent.kernel.plugin;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 插件目录热插拔监听测试
 * <p>验证：jar 变化触发防抖回调、非 jar 变化不触发、close 后停止监听。</p>
 */
class PluginHotReloaderTest {

    @TempDir
    Path dir;

    @Test
    void jarChange_triggersDebouncedCallback() throws Exception {
        AtomicInteger count = new AtomicInteger();
        try (PluginHotReloader reloader = new PluginHotReloader(dir, p -> count.incrementAndGet())) {
            Thread.sleep(150); // 等待 WatchService 注册

            // 连续写入 3 次 jar（模拟批量替换），防抖后应只合并触发（>=1 次即可，重点是触发）
            Files.write(dir.resolve("plugin-v1.jar"), new byte[]{1});
            Files.write(dir.resolve("plugin-v2.jar"), new byte[]{2});
            Files.write(dir.resolve("plugin-v2.jar"), new byte[]{3});

            waitUntil(() -> count.get() >= 1, 5000);
            assertTrue(count.get() >= 1, "jar 变化应触发热重载回调");
        }
    }

    @Test
    void nonJarChange_doesNotTrigger() throws Exception {
        AtomicInteger count = new AtomicInteger();
        try (PluginHotReloader reloader = new PluginHotReloader(dir, p -> count.incrementAndGet())) {
            Thread.sleep(150);
            Files.writeString(dir.resolve("readme.txt"), "not a plugin");
            Thread.sleep(1200); // 超过防抖窗口
            assertEquals(0, count.get(), "非 jar 变化不应触发重载");
        }
    }

    @Test
    void close_stopsWatching() throws Exception {
        AtomicInteger count = new AtomicInteger();
        PluginHotReloader reloader = new PluginHotReloader(dir, p -> count.incrementAndGet());
        Thread.sleep(150);
        Files.write(dir.resolve("early.jar"), new byte[]{1});
        waitUntil(() -> count.get() >= 1, 5000);

        reloader.close();
        int before = count.get();
        Files.write(dir.resolve("late.jar"), new byte[]{2});
        Thread.sleep(1200);
        assertEquals(before, count.get(), "close 后不应再触发回调");
    }

    private void waitUntil(java.util.function.BooleanSupplier cond, long timeoutMs) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            if (cond.getAsBoolean()) return;
            Thread.sleep(50);
        }
        fail("等待条件超时");
    }
}
