package com.ccagent.presentation.cli;

import com.ccagent.adapter.llm.di.LlmModule;
import com.ccagent.adapter.memory.di.MemoryModule;
import com.ccagent.api.port.ExpertRegistryPort;
import com.ccagent.api.port.LlmPort;
import com.ccagent.api.port.MemoryPort;
import com.ccagent.api.port.OrchestratorPort;
import com.ccagent.api.port.ToolRegistryPort;
import com.ccagent.presentation.di.ExpertModule;
import com.ccagent.presentation.di.GovernanceModule;
import com.ccagent.presentation.di.OrchestrationModule;
import com.ccagent.presentation.di.ToolModule;
import com.ccagent.core.sync.LocalDataRepository;
import com.ccagent.core.sync.SyncManifestRepository;
import com.ccagent.core.sync.SyncScheduler;
import com.ccagent.core.sync.di.SyncModule;
import com.ccagent.infrastructure.config.AppConfig;
import com.ccagent.infrastructure.di.CommonModule;
import com.ccagent.kernel.event.EventBus;
import com.ccagent.kernel.plugin.PluginContext;
import com.ccagent.kernel.plugin.PluginManager;
import com.ccagent.kernel.plugin.PluginLoadReport;
import com.ccagent.kernel.plugin.SimplePluginContext;
import com.ccagent.kernel.plugin.SimplePluginManager;

/**
 * 依赖组装器
 * <p>按模块 DI：组合 8 个模块级 DI 类，不参与具体的组件创建细节。</p>
 * <pre>
 *   CommonModule → DataSource + AppConfig
 *   ToolModule   → ToolRegistryPort + McpPort（插件化）
 *   LlmModule    → LlmPort
 *   MemoryModule → MemoryPort
 *   ExpertModule → ExpertRegistryPort（插件化）
 *   SyncModule   → SyncManifestRepository + LocalDataRepository + SyncScheduler
 *   GovernanceModule → TokenTrackingLlmProxy + RollbackManager + Auditor + 治理组件（插件化）
 *   OrchestrationModule → GovernanceGateway (OrchestratorPort)
 * </pre>
 *
 * <h3>插件化改造</h3>
 * <p>通过 PluginContext + PluginManager 加载 TOOL/EXPERT/LISTENER 插件。</p>
 */
public class DependencyGraph {

    public CommonModule commonModule;
    public ToolModule toolModule;
    public LlmModule llmModule;
    public MemoryModule memoryModule;
    public ExpertModule expertModule;
    public SyncModule syncModule;
    public GovernanceModule governanceModule;
    public OrchestrationModule orchestrationModule;

    private SimplePluginContext pluginContext;
    private PluginManager pluginManager;

    public void assemble(AppConfig config) {
        // 1. 创建插件上下文和管理器
        pluginContext = new SimplePluginContext();
        pluginManager = new SimplePluginManager();

        // 2. 注册核心服务到插件上下文（供插件 init 使用）
        pluginContext.registerService(AppConfig.class, config);

        // 3. CommonModule
        commonModule = new CommonModule();
        commonModule.init(config);

        // 4. ToolModule（插件化加载 TOOL）
        toolModule = new ToolModule();
        toolModule.init(pluginContext, pluginManager);

        // 5. LlmModule
        llmModule = new LlmModule();
        llmModule.init(config.getLlm());
        pluginContext.registerService(LlmPort.class, llmModule.getLlmPort());

        // 6. MemoryModule
        memoryModule = new MemoryModule();
        memoryModule.init(commonModule.getDataSource(), llmModule.getLlmPort());
        pluginContext.registerService(MemoryPort.class, memoryModule.getMemoryPort());

        // 7. ExpertModule（插件化加载 EXPERT）
        expertModule = new ExpertModule();
        expertModule.init(pluginContext, pluginManager);

        // 8. SyncModule
        syncModule = new SyncModule();
        syncModule.init(commonModule.getDataSource(), config.getSync());
        pluginContext.registerService(LocalDataRepository.class, syncModule.getLocalDataRepository());

        // 9. GovernanceModule（插件化加载 LISTENER）
        governanceModule = new GovernanceModule();
        governanceModule.init(
                config.getGovernance(),
                llmModule.getLlmPort(),
                syncModule.getLocalDataRepository(),
                pluginContext,
                pluginManager
        );

        // 10. OrchestrationModule（不变）
        orchestrationModule = new OrchestrationModule();
        orchestrationModule.init(
                commonModule.getDataSource(),
                governanceModule.getTokenTrackingLlmProxy(),
                memoryModule.getMemoryPort(),
                expertModule.getExpertRegistry(),
                governanceModule.getRollbackManager(),
                governanceModule.getTokenQuotaService(),
                governanceModule.getTokenStatsService(),
                governanceModule.getPolicyEnforcer(),
                governanceModule.getPlanSanitizer(),
                governanceModule.getAuditor(),
                syncModule.getLocalDataRepository(),
                governanceModule.getEventBus()
        );

        // 11. 输出插件加载报告
        PluginLoadReport report = pluginManager.getLoadReport();
        System.out.println(report.toReportText());
    }

    public OrchestratorPort getGovernanceGateway() {
        return orchestrationModule.getGovernanceGateway();
    }

    public MemoryPort getMemoryPort() {
        return memoryModule.getMemoryPort();
    }

    public ExpertRegistryPort getExpertRegistry() {
        return expertModule.getExpertRegistry();
    }

    public SyncManifestRepository getSyncManifestRepository() {
        return syncModule.getSyncManifestRepository();
    }

    public LocalDataRepository getLocalDataRepository() {
        return syncModule.getLocalDataRepository();
    }

    public SyncScheduler getSyncScheduler() {
        return syncModule.getSyncScheduler();
    }

    public void shutdown() {
        if (governanceModule != null && governanceModule.getAuditor() != null) {
            governanceModule.getAuditor().shutdown();
        }
    }
}
