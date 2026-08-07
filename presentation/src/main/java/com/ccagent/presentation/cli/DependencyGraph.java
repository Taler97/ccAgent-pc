package com.ccagent.presentation.cli;

import com.ccagent.adapter.llm.di.LlmModule;
import com.ccagent.adapter.memory.di.MemoryModule;
import com.ccagent.api.port.ExpertRegistryPort;
import com.ccagent.api.port.LlmPort;
import com.ccagent.api.port.MemoryPort;
import com.ccagent.api.port.OrchestratorPort;
import com.ccagent.api.port.ToolRegistryPort;
import com.ccagent.core.skill.SkillRegistry;
import com.ccagent.presentation.di.CustomExpertLoader;
import com.ccagent.presentation.di.ExpertModule;
import com.ccagent.presentation.di.GovernanceModule;
import com.ccagent.presentation.di.OrchestrationModule;
import com.ccagent.presentation.di.ToolModule;
import com.ccagent.core.sync.LocalDataRepository;
import com.ccagent.core.sync.SyncManifestRepository;
import com.ccagent.core.sync.SyncScheduler;
import com.ccagent.core.sync.di.SyncModule;
import com.ccagent.plugin.tools.mcp.McpClientManager;
import com.ccagent.infrastructure.config.AppConfig;
import com.ccagent.infrastructure.constant.Constants;
import com.ccagent.infrastructure.di.CommonModule;
import com.ccagent.infrastructure.util.PathUtil;
import com.ccagent.kernel.event.EventBus;
import com.ccagent.kernel.plugin.PluginContext;
import com.ccagent.kernel.plugin.PluginManager;
import com.ccagent.kernel.plugin.PluginLoadReport;
import com.ccagent.kernel.plugin.SimplePluginContext;
import com.ccagent.kernel.plugin.SimplePluginManager;

import java.nio.file.Paths;

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
    public SkillRegistry skillRegistry;
    public McpClientManager mcpManager;

    private SimplePluginContext pluginContext;
    private PluginManager pluginManager;

    public void assemble(AppConfig config) {
        // 0. 注入路径校验白名单（用户配置，工作区之外可访问的目录）
        PathUtil.setAllowedPaths(config.getGovernance().getAllowedPaths());

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

        // 4.5 MCP 客户端（按 .codepal/mcp/*.yaml 连接外部 MCP server，动态注册工具）
        mcpManager = new McpClientManager(toolModule.getToolRegistryImpl());
        mcpManager.loadDefault();

        // 5. LlmModule
        llmModule = new LlmModule();
        llmModule.init(config.getLlm());
        pluginContext.registerService(LlmPort.class, llmModule.getLlmPort());

        // 6. MemoryModule
        memoryModule = new MemoryModule();
        memoryModule.init(commonModule.getDataSource(), llmModule.getLlmPort());
        pluginContext.registerService(MemoryPort.class, memoryModule.getMemoryPort());

        // 6.5 SkillRegistry（加载 skill.md，注册进插件上下文供专家注入）
        skillRegistry = new SkillRegistry(Paths.get(Constants.SKILLS_DIR));
        pluginContext.registerService(SkillRegistry.class, skillRegistry);

        // 7. ExpertModule（插件化加载 EXPERT）
        expertModule = new ExpertModule();
        expertModule.init(pluginContext, pluginManager);

        // 7.5 自定义专家（experts/*.yaml → GenericExpert）
        CustomExpertLoader.load(
                expertModule.getExpertRegistry(),
                toolModule.getToolRegistry(),
                llmModule.getLlmPort(),
                skillRegistry);

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
                governanceModule.getEventBus(),
                skillRegistry
        );

        // 热插拔：治理监听器重载后，原子替换 GovernanceGateway 的引用
        governanceModule.setListenerChangeListener(bundle ->
                orchestrationModule.getGovernanceGatewayImpl().replaceListeners(
                        bundle.rollbackManager(), bundle.auditor(), bundle.tokenStatsService()));

        // 11. 输出插件加载报告
        PluginLoadReport report = pluginManager.getLoadReport();
        System.out.println(report.toReportText());
    }

    public OrchestratorPort getGovernanceGateway() {
        return orchestrationModule.getGovernanceGateway();
    }

    /**
     * 返回编排器实例（供启动期调用 recoverInterruptedPlans 等生命周期方法）
     */
    public com.ccagent.core.orchestration.orchestrator.SimpleOrchestrator getOrchestrator() {
        return orchestrationModule.getOrchestrator();
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
        if (governanceModule != null) {
            governanceModule.shutdown();
        }
        if (orchestrationModule != null && orchestrationModule.getDispatcher() != null) {
            orchestrationModule.getDispatcher().shutdown();
        }
        if (pluginManager != null) {
            pluginManager.shutdown();
        }
        if (mcpManager != null) {
            mcpManager.closeAll();
        }
    }
}
