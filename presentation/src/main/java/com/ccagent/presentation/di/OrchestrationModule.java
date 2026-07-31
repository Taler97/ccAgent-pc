package com.ccagent.presentation.di;

import com.ccagent.api.port.ExpertRegistryPort;
import com.ccagent.api.port.LlmPort;
import com.ccagent.api.port.MemoryPort;
import com.ccagent.api.port.OrchestratorPort;
import com.ccagent.core.context.ArtifactStore;
import com.ccagent.core.context.FileArtifactStore;
import com.ccagent.plugin.governance.audit.Auditor;
import com.ccagent.plugin.governance.audit.TokenStatsService;
import com.ccagent.core.governance.policy.PlanSanitizer;
import com.ccagent.core.governance.policy.PolicyEnforcer;
import com.ccagent.core.governance.policy.TokenQuotaService;
import com.ccagent.plugin.governance.rollback.RollbackManager;
import com.ccagent.core.orchestration.agent.ChatAgent;
import com.ccagent.core.orchestration.dispatcher.Dispatcher;
import com.ccagent.core.orchestration.orchestrator.SimpleOrchestrator;
import com.ccagent.core.orchestration.progress.ProgressStore;
import com.ccagent.core.orchestration.repository.ExecutionPlanRepository;
import com.ccagent.core.sync.LocalDataRepository;
import com.ccagent.kernel.event.EventBus;

import javax.sql.DataSource;

public class OrchestrationModule {

    private GovernanceGateway governanceGateway;
    private Dispatcher dispatcher;

    public void init(
            DataSource dataSource,
            LlmPort proxyLlm,
            MemoryPort memoryPort,
            ExpertRegistryPort expertRegistry,
            RollbackManager rollbackManager,
            TokenQuotaService tokenQuotaService,
            TokenStatsService tokenStatsService,
            PolicyEnforcer policyEnforcer,
            PlanSanitizer planSanitizer,
            Auditor auditor,
            LocalDataRepository localDataRepo,
            EventBus eventBus) {

        ExecutionPlanRepository planRepo = new ExecutionPlanRepository(dataSource);

        ProgressStore progressStore = new ProgressStore();
        ChatAgent chatAgent = new ChatAgent(proxyLlm, memoryPort);
        ArtifactStore artifactStore = new FileArtifactStore();
        Dispatcher dispatcher = new Dispatcher(expertRegistry, planRepo, artifactStore, eventBus, progressStore);
        SimpleOrchestrator orchestrator = new SimpleOrchestrator(
                chatAgent, dispatcher, memoryPort, expertRegistry,
                planRepo, localDataRepo, artifactStore, eventBus);

        governanceGateway = new GovernanceGateway(
                orchestrator, policyEnforcer, tokenQuotaService,
                planSanitizer, rollbackManager,
                auditor, tokenStatsService, eventBus, progressStore);
    }

    public OrchestratorPort getGovernanceGateway() {
        return governanceGateway;
    }

    /**
     * 返回具体类型的治理网关（供热插拔装配链调用 replaceListeners）
     */
    public GovernanceGateway getGovernanceGatewayImpl() {
        return governanceGateway;
    }

    public Dispatcher getDispatcher() {
        return dispatcher;
    }
}