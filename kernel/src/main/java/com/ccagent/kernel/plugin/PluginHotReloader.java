package com.ccagent.kernel.plugin;

import java.io.IOException;
import java.nio.file.ClosedWatchServiceException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardWatchEventKinds;
import java.nio.file.WatchEvent;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.logging.Logger;

/**
 * 插件目录热插拔监听器
 * <p>监控插件目录中 jar 文件的新增/修改/删除，防抖合并后触发回调（重新加载插件）。</p>
 *
 * <ul>
 *   <li>基于 {@link WatchService}，daemon 线程阻塞监听</li>
 *   <li>防抖窗口 {@value #DEBOUNCE_MS}ms：批量替换 jar 只触发一次重载</li>
 *   <li>close() 关闭监听，退出时释放资源</li>
 * </ul>
 */
public class PluginHotReloader implements AutoCloseable {

    private static final Logger LOG = Logger.getLogger(PluginHotReloader.class.getName());
    private static final long DEBOUNCE_MS = 500;

    private final WatchService watchService;
    private final Thread watcher;
    private final ScheduledExecutorService debouncer;
    private final Consumer<Path> onChange;
    private final AtomicBoolean closed = new AtomicBoolean(false);

    /**
     * @param pluginDir 插件目录（不存在则自动创建）
     * @param onChange  插件目录变化（防抖后）回调，参数为插件目录路径
     */
    public PluginHotReloader(Path pluginDir, Consumer<Path> onChange) throws IOException {
        this.onChange = onChange;
        Files.createDirectories(pluginDir);
        this.watchService = FileSystems.getDefault().newWatchService();
        pluginDir.register(watchService,
                StandardWatchEventKinds.ENTRY_CREATE,
                StandardWatchEventKinds.ENTRY_MODIFY,
                StandardWatchEventKinds.ENTRY_DELETE);

        this.debouncer = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "plugin-reload-debounce");
            t.setDaemon(true);
            return t;
        });

        this.watcher = new Thread(() -> poll(pluginDir), "plugin-watcher");
        this.watcher.setDaemon(true);
        this.watcher.start();
    }

    private void poll(Path pluginDir) {
        while (!closed.get()) {
            WatchKey key;
            try {
                key = watchService.take();
            } catch (InterruptedException | ClosedWatchServiceException e) {
                return;
            }

            boolean jarChanged = false;
            for (WatchEvent<?> event : key.pollEvents()) {
                if (event.kind() == StandardWatchEventKinds.OVERFLOW) {
                    jarChanged = true; // 事件溢出视为有变化，保守触发
                    continue;
                }
                Object ctx = event.context();
                if (ctx instanceof Path p && p.toString().endsWith(".jar")) {
                    jarChanged = true;
                }
            }
            if (jarChanged) {
                scheduleReload(pluginDir);
            }
            if (!key.reset()) {
                return;
            }
        }
    }

    private void scheduleReload(Path pluginDir) {
        debouncer.schedule(() -> {
            try {
                onChange.accept(pluginDir);
            } catch (Exception e) {
                LOG.warning("插件热重载回调异常: " + e.getMessage());
            }
        }, DEBOUNCE_MS, TimeUnit.MILLISECONDS);
    }

    @Override
    public void close() {
        if (closed.compareAndSet(false, true)) {
            try {
                watchService.close();
            } catch (IOException ignored) {
                // 关闭失败不阻塞
            }
            watcher.interrupt();
            debouncer.shutdownNow();
        }
    }
}
