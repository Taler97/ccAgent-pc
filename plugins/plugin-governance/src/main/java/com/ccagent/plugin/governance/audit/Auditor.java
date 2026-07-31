package com.ccagent.plugin.governance.audit;

import com.ccagent.core.event.ExecutionCompletedEvent;
import com.ccagent.core.event.ExecutionFailedEvent;
import com.ccagent.core.event.ExecutionStartedEvent;
import com.ccagent.core.event.StepCompletedEvent;
import com.ccagent.core.event.StepFailedEvent;
import com.ccagent.core.event.StepRolledBackEvent;
import com.ccagent.core.event.StepStartedEvent;
import com.ccagent.core.governance.audit.AuditEvent;
import com.ccagent.core.governance.audit.AuditLevel;
import com.ccagent.core.sync.LocalDataRepository;
import com.ccagent.infrastructure.constant.Constants;
import com.ccagent.infrastructure.util.FileUtil;
import com.ccagent.infrastructure.util.JsonUtil;
import com.ccagent.kernel.event.EventBus;
import com.ccagent.kernel.event.Subscription;
import com.ccagent.core.plugin.ListenerExtensionPoint;
import com.ccagent.infrastructure.config.AppConfig;
import com.ccagent.kernel.plugin.PluginContext;

import java.nio.file.Paths;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;
import java.util.logging.Logger;

/**
 * 审计记录器
 * <p>异步队列 + 后台刷盘，记录全链路关键事件到 audit.log。</p>
 */
public class Auditor implements ListenerExtensionPoint {

    private static final Logger LOG = Logger.getLogger(Auditor.class.getName());

    private AuditLevel auditLevel;
    private BlockingQueue<AuditRecord> queue;
    private ScheduledExecutorService scheduler;
    private LocalDataRepository localDataRepository;
    private final List<Subscription> subscriptions = new ArrayList<>();
    private volatile boolean running = true;

    public Auditor() {}

    @Override
    public void init(PluginContext context) {
        AppConfig config = context.getService(AppConfig.class);
        AppConfig.GovernanceConfig govConfig = config.getGovernance();
        try {
            this.auditLevel = AuditLevel.valueOf(govConfig.getAuditLevel().toUpperCase());
        } catch (Exception e) {
            this.auditLevel = AuditLevel.SUMMARY;
        }
        this.queue = new LinkedBlockingQueue<>(govConfig.getAuditBufferSize());
        this.localDataRepository = context.getService(LocalDataRepository.class);
        this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "auditor-flusher");
            t.setDaemon(true);
            return t;
        });
        scheduler.scheduleAtFixedRate(this::flush,
                govConfig.getAuditFlushIntervalSeconds(),
                govConfig.getAuditFlushIntervalSeconds(), TimeUnit.SECONDS);

        EventBus eventBus = context.getService(EventBus.class);
        if (eventBus != null) {
            registerOnEventBus(eventBus);
        }
    }

    public Auditor(AuditLevel auditLevel, int bufferSize, int flushIntervalSeconds) {
        this(auditLevel, bufferSize, flushIntervalSeconds, null);
    }

    public Auditor(AuditLevel auditLevel, int bufferSize, int flushIntervalSeconds,
                   LocalDataRepository localDataRepository) {
        this.auditLevel = auditLevel;
        this.queue = new LinkedBlockingQueue<>(bufferSize);
        this.localDataRepository = localDataRepository;
        this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "auditor-flusher");
            t.setDaemon(true);
            return t;
        });

        scheduler.scheduleAtFixedRate(this::flush,
                flushIntervalSeconds, flushIntervalSeconds, TimeUnit.SECONDS);
    }

    public void registerOnEventBus(EventBus eventBus) {
        subscriptions.add(eventBus.subscribeAsync(ExecutionStartedEvent.class, event -> {
            record(AuditEvent.STEP_START, Map.of(
                    "seqId", event.getSeqId(),
                    "sessionId", event.getSessionId(),
                    "prompt", event.getPrompt() != null ? event.getPrompt() : ""
            ));
        }));
        subscriptions.add(eventBus.subscribeAsync(ExecutionCompletedEvent.class, event -> {
            record(AuditEvent.EXECUTION_COMPLETE, Map.of(
                    "seqId", event.getSeqId(),
                    "sessionId", event.getSessionId(),
                    "summary", event.getSummary() != null ? event.getSummary() : ""
            ));
        }));
        subscriptions.add(eventBus.subscribeAsync(ExecutionFailedEvent.class, event -> {
            record(AuditEvent.ERROR, Map.of(
                    "seqId", event.getSeqId(),
                    "sessionId", event.getSessionId(),
                    "errorCode", event.getErrorCode() != null ? event.getErrorCode() : "",
                    "errorMessage", event.getErrorMessage() != null ? event.getErrorMessage() : ""
            ));
        }));
        subscriptions.add(eventBus.subscribeAsync(StepStartedEvent.class, event -> {
            record(AuditEvent.STEP_START, Map.of(
                    "seqId", event.getSeqId(),
                    "sessionId", event.getSessionId(),
                    "stepId", event.getStepId(),
                    "totalSteps", event.getTotalSteps(),
                    "expertName", event.getExpertName() != null ? event.getExpertName() : ""
            ));
        }));
        subscriptions.add(eventBus.subscribeAsync(StepCompletedEvent.class, event -> {
            record(AuditEvent.STEP_COMPLETE, Map.of(
                    "seqId", event.getSeqId(),
                    "sessionId", event.getSessionId(),
                    "stepId", event.getStepId(),
                    "totalSteps", event.getTotalSteps(),
                    "expertName", event.getExpertName() != null ? event.getExpertName() : ""
            ));
        }));
        subscriptions.add(eventBus.subscribeAsync(StepFailedEvent.class, event -> {
            record(AuditEvent.ERROR, Map.of(
                    "seqId", event.getSeqId(),
                    "sessionId", event.getSessionId(),
                    "stepId", event.getStepId(),
                    "expertName", event.getExpertName() != null ? event.getExpertName() : "",
                    "error", event.getErrorMessage() != null ? event.getErrorMessage() : ""
            ));
        }));
        subscriptions.add(eventBus.subscribeAsync(StepRolledBackEvent.class, event -> {
            record(AuditEvent.BACKUP_RESTORED, Map.of(
                    "seqId", event.getSeqId(),
                    "sessionId", event.getSessionId(),
                    "stepId", event.getStepId(),
                    "expertName", event.getExpertName() != null ? event.getExpertName() : ""
            ));
        }));
    }

    public void unregisterAll() {
        for (Subscription sub : subscriptions) {
            sub.unsubscribe();
        }
        subscriptions.clear();
    }

    /**
     * 记录一条审计事件
     */
    public void record(AuditEvent event, Map<String, Object> details) {
        if (auditLevel == AuditLevel.OFF) return;
        if (auditLevel == AuditLevel.SUMMARY && !isSummaryEvent(event)) return;

        AuditRecord record = new AuditRecord(event, details, Instant.now());
        if (!queue.offer(record)) {
            LOG.warning("审计队列已满，丢弃事件: " + event);
        }
    }

    /**
     * 记录一条简单审计事件（无详情）
     */
    public void record(AuditEvent event) {
        record(event, Map.of());
    }

    /**
     * 强制刷盘（关闭前调用）
     */
    public void flush() {
        if (queue.isEmpty()) return;

        java.util.List<AuditRecord> batch = new java.util.ArrayList<>();
        queue.drainTo(batch);

        StringBuilder sb = new StringBuilder();
        for (AuditRecord record : batch) {
            sb.append(JsonUtil.toJson(record)).append(System.lineSeparator());
        }
        try {
            FileUtil.appendString(Paths.get(Constants.AUDIT_LOG), sb.toString());
        } catch (Exception e) {
            LOG.warning("审计日志写入失败: " + e.getMessage());
        }

        // 同步写入本地数据库（供离线模式和云同步使用）
        if (localDataRepository != null) {
            for (AuditRecord record : batch) {
                try {
                    localDataRepository.saveAuditLog(
                            record.getDetails() != null ? (String) record.getDetails().get("seqId") : null,
                            record.getType().name(),
                            JsonUtil.toJson(record.getDetails())
                    );
                } catch (Exception e) {
                    LOG.warning("审计日志数据库写入失败: " + e.getMessage());
                }
            }
        }
    }

    /**
     * 关闭审计器
     */
    public void shutdown() {
        running = false;
        scheduler.shutdown();
        try {
            scheduler.awaitTermination(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        flush();
    }

    private boolean isSummaryEvent(AuditEvent event) {
        return event == AuditEvent.PLAN_READY
                || event == AuditEvent.EXECUTION_COMPLETE
                || event == AuditEvent.DIALOG_ROLLBACK;
    }

    /**
     * 审计记录内部类
     */
    public static class AuditRecord {
        private AuditEvent type;
        private Map<String, Object> details;
        private Instant timestamp;

        public AuditRecord() {}

        public AuditRecord(AuditEvent type, Map<String, Object> details, Instant timestamp) {
            this.type = type;
            this.details = details;
            this.timestamp = timestamp;
        }

        public AuditEvent getType() { return type; }
        public void setType(AuditEvent type) { this.type = type; }
        public Map<String, Object> getDetails() { return details; }
        public void setDetails(Map<String, Object> details) { this.details = details; }
        public Instant getTimestamp() { return timestamp; }
        public void setTimestamp(Instant timestamp) { this.timestamp = timestamp; }
    }
}