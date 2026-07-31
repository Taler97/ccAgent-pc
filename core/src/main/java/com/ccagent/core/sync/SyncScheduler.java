package com.ccagent.core.sync;

import com.ccagent.core.sync.model.SyncResult;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.logging.Logger;

public class SyncScheduler {

    private static final Logger LOG = Logger.getLogger(SyncScheduler.class.getName());

    private final SyncService syncService;
    private final SyncClient syncClient;
    private final int intervalMinutes;
    private final ScheduledExecutorService scheduler;
    private volatile boolean cloudReachable = false;
    private Consumer<SyncResult> onSyncComplete;

    public SyncScheduler(SyncService syncService, SyncClient syncClient, int intervalMinutes) {
        this.syncService = syncService;
        this.syncClient = syncClient;
        this.intervalMinutes = intervalMinutes;
        this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "sync-scheduler");
            t.setDaemon(true);
            return t;
        });
    }

    public void start() {
        scheduler.scheduleAtFixedRate(this::syncIfReachable,
                intervalMinutes, intervalMinutes, TimeUnit.MINUTES);
        LOG.info("同步调度已启动，间隔: " + intervalMinutes + " 分钟");
    }

    public void onSyncComplete(Consumer<SyncResult> callback) {
        this.onSyncComplete = callback;
    }

    public void syncIfReachable() {
        if (!syncClient.isCloudReachable()) {
            cloudReachable = false;
            return;
        }
        cloudReachable = true;
        SyncResult result = syncService.syncAll();
        LOG.info("自动同步完成: " + result.getMessage());
        if (onSyncComplete != null) {
            onSyncComplete.accept(result);
        }
    }

    public void syncNow() {
        LOG.info("手动触发同步...");
        SyncResult result = syncService.syncAll();
        LOG.info("手动同步完成: " + result.getMessage());
        if (onSyncComplete != null) {
            onSyncComplete.accept(result);
        }
    }

    public void onCloudConnected() {
        LOG.info("云连接已建立，立即执行同步...");
        syncNow();
    }

    public void onCloudDisconnected() {
        cloudReachable = false;
        LOG.info("云连接已断开");
    }

    public boolean isCloudReachable() {
        return cloudReachable;
    }

    public void shutdown() {
        scheduler.shutdown();
        try {
            scheduler.awaitTermination(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
