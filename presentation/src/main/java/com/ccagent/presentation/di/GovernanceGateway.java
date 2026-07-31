package com.ccagent.presentation.di;

import com.ccagent.api.model.memory.SessionInfo;
import com.ccagent.api.model.request.ExecuteRequest;
import com.ccagent.api.model.response.StreamChunk;
import com.ccagent.api.port.OrchestratorPort;
import com.ccagent.api.port.SafeStreamObserver;
import com.ccagent.api.port.StreamObserver;
import com.ccagent.core.event.ExecutionCompletedEvent;
import com.ccagent.core.event.ExecutionFailedEvent;
import com.ccagent.core.event.ExecutionStartedEvent;
import com.ccagent.core.governance.audit.AuditEvent;
import com.ccagent.plugin.governance.audit.Auditor;
import com.ccagent.plugin.governance.audit.TokenStatsService;
import com.ccagent.core.governance.context.ExecutionContextHolder;
import com.ccagent.core.governance.policy.PlanSanitizer;
import com.ccagent.core.governance.policy.PolicyEnforcer;
import com.ccagent.core.governance.policy.TokenQuotaService;
import com.ccagent.plugin.governance.rollback.RollbackManager;
import com.ccagent.core.orchestration.context.ExecutionContext;
import com.ccagent.core.orchestration.orchestrator.SimpleOrchestrator;
import com.ccagent.exception.base.AgentException;
import com.ccagent.kernel.event.EventBus;

import java.util.List;
import java.util.Map;

public class GovernanceGateway implements OrchestratorPort {

    private final SimpleOrchestrator orchestrator;
    private final PolicyEnforcer policyEnforcer;
    private final TokenQuotaService tokenQuotaService;
    private final PlanSanitizer planSanitizer;
    private final RollbackManager rollbackManager;
    private final Auditor auditor;
    private final TokenStatsService tokenStatsService;
    private final EventBus eventBus;

    public GovernanceGateway(SimpleOrchestrator orchestrator,
                              PolicyEnforcer policyEnforcer,
                              TokenQuotaService tokenQuotaService,
                              PlanSanitizer planSanitizer,
                              RollbackManager rollbackManager,
                              Auditor auditor,
                              TokenStatsService tokenStatsService,
                              EventBus eventBus) {
        this.orchestrator = orchestrator;
        this.policyEnforcer = policyEnforcer;
        this.tokenQuotaService = tokenQuotaService;
        this.planSanitizer = planSanitizer;
        this.rollbackManager = rollbackManager;
        this.auditor = auditor;
        this.tokenStatsService = tokenStatsService;
        this.eventBus = eventBus;
    }

    @Override
    public void execute(ExecuteRequest request, StreamObserver<StreamChunk> observer) {
        executeWithSeqId(request, java.util.UUID.randomUUID().toString(), observer);
    }

    @Override
    public void executeWithSeqId(ExecuteRequest request, String seqId, StreamObserver<StreamChunk> observer) {
        String sessionId = request.getSessionId();

        try {
            policyEnforcer.checkWorkspace(request.getWorkspaceId());
            tokenQuotaService.checkQuota(sessionId, 1000);

            ExecutionContext ctx = new ExecutionContext(
                    ExecutionContext.ExecutionStage.PLANNING,
                    sessionId,
                    request.getWorkspaceId(),
                    seqId
            );
            ExecutionContextHolder.setContext(ctx);

            eventBus.publish(new ExecutionStartedEvent(seqId, sessionId, request.getPrompt()));

            orchestrator.execute(request, seqId, new SafeStreamObserver(new StreamObserver<>() {
                @Override
                public void onNext(StreamChunk chunk) {
                    observer.onNext(chunk);
                }

                @Override
                public void onError(Throwable error) {
                    if (error instanceof AgentException) {
                        rollbackManager.cleanup(seqId);
                    }
                    String errorCode = error instanceof AgentException
                            ? ((AgentException) error).getErrorCode().name()
                            : "UNKNOWN_ERROR";
                    eventBus.publish(new ExecutionFailedEvent(seqId, sessionId, errorCode, error.getMessage()));
                    observer.onError(error);
                }

                @Override
                public void onComplete() {
                    eventBus.publish(new ExecutionCompletedEvent(seqId, sessionId, ""));
                    rollbackManager.cleanup(seqId);
                    observer.onComplete();
                }
            }));
        } catch (AgentException e) {
            observer.onNext(new StreamChunk(seqId, "ERROR",
                    "{\"errorCode\":\"" + e.getErrorCode().name()
                            + "\",\"message\":\"" + e.getMessage() + "\"}", null));
            observer.onError(e);
        } finally {
            ExecutionContextHolder.clear();
        }
    }

    @Override
    public boolean cancelExecution(String seqId) {
        auditor.record(AuditEvent.ERROR, Map.of("seqId", seqId, "action", "CANCEL"));
        return orchestrator.cancelExecution(seqId);
    }

    @Override
    public void onToolApproval(String seqId, boolean approved) {
    }

    @Override
    public void executePlan(String seqId, boolean confirmed) {
        orchestrator.confirmPlan(seqId, confirmed);
    }

    @Override
    public boolean cancelPlan(String seqId) {
        return orchestrator.cancelExecution(seqId);
    }

    @Override
    public boolean rollbackDialog(String seqId) {
        auditor.record(AuditEvent.ERROR, Map.of("seqId", seqId, "action", "ROLLBACK"));
        return rollbackManager.restoreAll(seqId);
    }

    @Override
    public void createSession(String sessionId, String rootPath) {
        orchestrator.createSession(sessionId, rootPath);
    }

    @Override
    public String loadWorkspaceRoot(String sessionId) {
        return orchestrator.loadWorkspaceRoot(sessionId);
    }

    @Override
    public List<SessionInfo> listSessions() {
        return orchestrator.listSessions();
    }
}