package com.ccagent.presentation.di;

import com.ccagent.api.port.LlmPort;
import com.ccagent.core.governance.audit.AuditLevel;
import com.ccagent.core.governance.policy.PlanSanitizer;
import com.ccagent.core.governance.policy.PolicyEnforcer;
import com.ccagent.core.governance.policy.TokenQuotaService;
import com.ccagent.core.plugin.ListenerExtensionPoint;
import com.ccagent.core.sync.LocalDataRepository;
import com.ccagent.infrastructure.config.AppConfig;
import com.ccagent.kernel.event.EventBus;
import com.ccagent.kernel.event.SimpleEventBus;
import com.ccagent.kernel.plugin.PluginContext;
import com.ccagent.kernel.plugin.PluginManager;
import com.ccagent.kernel.plugin.PluginType;
import com.ccagent.kernel.plugin.SimplePluginContext;
import com.ccagent.plugin.governance.audit.Auditor;
import com.ccagent.plugin.governance.audit.TokenStatsService;
import com.ccagent.plugin.governance.llmproxy.TokenTrackingLlmProxy;
import com.ccagent.plugin.governance.rollback.RollbackManager;

import java.util.List;

public class GovernanceModule {

    private TokenTrackingLlmProxy tokenTrackingLlmProxy;
    private RollbackManager rollbackManager;
    private TokenQuotaService tokenQuotaService;
    private TokenStatsService tokenStatsService;
    private PolicyEnforcer policyEnforcer;
    private PlanSanitizer planSanitizer;
    private Auditor auditor;
    private EventBus eventBus;

    public void init(AppConfig.GovernanceConfig governanceConfig,
                     LlmPort llmPort,
                     LocalDataRepository localDataRepo,
                     PluginContext ctx,
                     PluginManager pm) {

        eventBus = new SimpleEventBus();

        tokenQuotaService = new TokenQuotaService(governanceConfig.getMaxTokensPerSession());
        policyEnforcer = new PolicyEnforcer();
        planSanitizer = new PlanSanitizer(governanceConfig.getMaxPlanSteps());
        tokenStatsService = new TokenStatsService(
                governanceConfig.isTokenStatsEnabled(),
                governanceConfig.getPricing() != null ? governanceConfig.getPricing().getInputPerMillion() : 0.0,
                governanceConfig.getPricing() != null ? governanceConfig.getPricing().getCachedInputPerMillion() : 0.0,
                governanceConfig.getPricing() != null ? governanceConfig.getPricing().getOutputPerMillion() : 0.0
        );
        auditor = new Auditor(
                AuditLevel.valueOf(governanceConfig.getAuditLevel().toUpperCase()),
                governanceConfig.getAuditBufferSize(),
                governanceConfig.getAuditFlushIntervalSeconds(),
                localDataRepo
        );
        rollbackManager = new RollbackManager(
                null,
                governanceConfig.isBackupEnabled(),
                governanceConfig.getRollbackRetryCount(),
                governanceConfig.getRollbackRetryIntervalSeconds()
        );

        tokenTrackingLlmProxy = new TokenTrackingLlmProxy(
                llmPort, tokenQuotaService, tokenStatsService, localDataRepo, eventBus
        );

        if (ctx instanceof SimplePluginContext spc) {
            spc.registerService(EventBus.class, eventBus);
            spc.registerService(TokenQuotaService.class, tokenQuotaService);
            spc.registerService(TokenStatsService.class, tokenStatsService);
            spc.registerService(PolicyEnforcer.class, policyEnforcer);
            spc.registerService(PlanSanitizer.class, planSanitizer);
            spc.registerService(RollbackManager.class, rollbackManager);
            spc.registerService(Auditor.class, auditor);
        }

        List<ListenerExtensionPoint> listeners = pm.loadExtensions(PluginType.LISTENER, ctx)
                .stream()
                .map(ext -> (ListenerExtensionPoint) ext)
                .toList();
        for (ListenerExtensionPoint listener : listeners) {
            listener.init(ctx);
        }

        rollbackManager.registerOnEventBus(eventBus);
        auditor.registerOnEventBus(eventBus);
        tokenStatsService.registerOnEventBus(eventBus);
    }

    public LlmPort getTokenTrackingLlmProxy() {
        return tokenTrackingLlmProxy;
    }

    public RollbackManager getRollbackManager() {
        return rollbackManager;
    }

    public TokenQuotaService getTokenQuotaService() {
        return tokenQuotaService;
    }

    public TokenStatsService getTokenStatsService() {
        return tokenStatsService;
    }

    public PolicyEnforcer getPolicyEnforcer() {
        return policyEnforcer;
    }

    public PlanSanitizer getPlanSanitizer() {
        return planSanitizer;
    }

    public Auditor getAuditor() {
        return auditor;
    }

    public EventBus getEventBus() {
        return eventBus;
    }
}