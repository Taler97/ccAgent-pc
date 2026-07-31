package com.ccagent.core.sync.di;

import com.ccagent.core.sync.LocalDataRepository;
import com.ccagent.core.sync.SyncClient;
import com.ccagent.core.sync.SyncManifestRepository;
import com.ccagent.core.sync.SyncService;
import com.ccagent.core.sync.SyncScheduler;
import com.ccagent.infrastructure.config.AppConfig;

import javax.sql.DataSource;

public class SyncModule {

    private SyncManifestRepository manifestRepo;
    private LocalDataRepository localDataRepo;
    private SyncClient syncClient;
    private SyncService syncService;
    private SyncScheduler syncScheduler;

    public void init(DataSource dataSource, AppConfig.SyncConfig syncConfig) {
        manifestRepo = new SyncManifestRepository(dataSource);
        localDataRepo = new LocalDataRepository(dataSource, manifestRepo);

        if (syncConfig != null && syncConfig.isEnabled()) {
            syncClient = new SyncClient(syncConfig.getCloudUrl(), syncConfig.getApiKey());
            syncService = new SyncService(syncClient, manifestRepo, localDataRepo);
            syncScheduler = new SyncScheduler(syncService, syncClient, syncConfig.getIntervalMinutes());
        }
    }

    public SyncManifestRepository getSyncManifestRepository() {
        return manifestRepo;
    }

    public LocalDataRepository getLocalDataRepository() {
        return localDataRepo;
    }

    public SyncClient getSyncClient() {
        return syncClient;
    }

    public SyncService getSyncService() {
        return syncService;
    }

    public SyncScheduler getSyncScheduler() {
        return syncScheduler;
    }
}
