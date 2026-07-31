package com.ccagent.presentation.di;

import com.ccagent.api.port.LlmPort;
import com.ccagent.core.governance.policy.PlanSanitizer;
import com.ccagent.core.governance.policy.PolicyEnforcer;
import com.ccagent.core.governance.policy.TokenQuotaService;
import com.ccagent.core.sync.LocalDataRepository;
import com.ccagent.infrastructure.config.AppConfig;
import com.ccagent.infrastructure.constant.Constants;
import com.ccagent.kernel.event.EventBus;
import com.ccagent.kernel.event.SimpleEventBus;
import com.ccagent.kernel.plugin.ExtensionPoint;
import com.ccagent.kernel.plugin.PluginContext;
import com.ccagent.kernel.plugin.PluginHotReloader;
import com.ccagent.kernel.plugin.PluginManager;
import com.ccagent.kernel.plugin.PluginType;
import com.ccagent.kernel.plugin.SimplePluginContext;
import com.ccagent.plugin.governance.audit.Auditor;
import com.ccagent.plugin.governance.audit.TokenStatsService;
import com.ccagent.plugin.governance.llmproxy.TokenTrackingLlmProxy;
import com.ccagent.plugin.governance.rollback.RollbackManager;

import java.nio.file.Paths;
import java.util.List;
import java.util.function.Consumer;
import java.util.logging.Logger;

/**
 * 治理模块 DI
 * <p>治理监听器（Auditor / RollbackManager / TokenStatsService）统一由 LISTENER 插件通道产出，
 * 与插件化架构保持一致（单一实例、可热插拔），不再双份实例化。</p>
 *
 * <h3>热插拔</h3>
 * <ul>
 *   <li>监听 {@link Constants#PLUGIN_DIR} 插件目录的 jar 变化（WatchService + 防抖）</li>
 *   <li>变化时 reloadListeners()：取消旧订阅 → 关闭隔离类加载器 → 重新加载 → 更新引用与注册表 → 通知 GovernanceGateway</li>
 * </ul>
 */
public class GovernanceModule {

    private static final Logger LOG = Logger.getLogger(GovernanceModule.class.getName());

    private TokenTrackingLlmProxy tokenTrackingLlmProxy;
    private RollbackManager rollbackManager;
    private TokenQuotaService tokenQuotaService;
    private TokenStatsService tokenStatsService;
    private PolicyEnforcer policyEnforcer;
    private PlanSanitizer planSanitizer;
    private Auditor auditor;
    private EventBus eventBus;

    private PluginContext ctx;
    private PluginManager pluginManager;
    private AppConfig.GovernanceConfig governanceConfig;
    private LocalDataRepository localDataRepo;
    private PluginHotReloader hotReloader;
    private Consumer<ListenerBundle> listenerChangeListener;

    public void init(AppConfig.GovernanceConfig governanceConfig,
                     LlmPort llmPort,
                     LocalDataRepository localDataRepo,
                     PluginContext ctx,
                     PluginManager pm) {

        this.ctx = ctx;
        this.pluginManager = pm;
        this.governanceConfig = governanceConfig;
        this.localDataRepo = localDataRepo;

        eventBus = new SimpleEventBus(
                governanceConfig.isEventPersistenceEnabled(),
                governanceConfig.getEventBufferSize(),
                governanceConfig.getEventFlushIntervalSeconds(),
                governanceConfig.getEventRateLimitPerSecond());

        tokenQuotaService = new TokenQuotaService(governanceConfig.getMaxTokensPerSession());
        policyEnforcer = new PolicyEnforcer();
        planSanitizer = new PlanSanitizer(governanceConfig.getMaxPlanSteps());

        if (ctx instanceof SimplePluginContext spc) {
            spc.registerService(EventBus.class, eventBus);
            spc.registerService(TokenQuotaService.class, tokenQuotaService);
            spc.registerService(PolicyEnforcer.class, policyEnforcer);
            spc.registerService(PlanSanitizer.class, planSanitizer);
        }

        // 治理监听器统一从 LISTENER 插件通道加载（唯一实例）
        loadListeners();

        tokenTrackingLlmProxy = new TokenTrackingLlmProxy(
                llmPort, tokenQuotaService, localDataRepo, eventBus
        );

        // 启动插件目录监听（热插拔）
        try {
            hotReloader = new PluginHotReloader(Paths.get(Constants.PLUGIN_DIR), p -> reloadListeners());
        } catch (Exception e) {
            LOG.warning("插件目录监听启动失败（热插拔不可用）: " + e.getMessage());
        }
    }

    /**
     * 从 LISTENER 插件通道加载治理监听器（单实例）
     * <p>按类型从插件实例中提取 Auditor / RollbackManager / TokenStatsService；
     * 插件通道缺失时回退到直接构造（防御），保证治理能力始终可用。</p>
     */
    private void loadListeners() {
        List<ExtensionPoint> listeners = pluginManager.loadExtensions(PluginType.LISTENER, ctx);

        auditor = findOrCreate(listeners, Auditor.class,
                new Auditor(
                        com.ccagent.core.governance.audit.AuditLevel.valueOf(governanceConfig.getAuditLevel().toUpperCase()),
                        governanceConfig.getAuditBufferSize(),
                        governanceConfig.getAuditFlushIntervalSeconds(),
                        localDataRepo),
                a -> a.registerOnEventBus(eventBus));

        rollbackManager = findOrCreate(listeners, RollbackManager.class,
                new RollbackManager(null,
                        governanceConfig.isBackupEnabled(),
                        governanceConfig.getRollbackRetryCount(),
                        governanceConfig.getRollbackRetryIntervalSeconds()),
                rm -> {
                    rm.setRollbackProvider(governanceConfig.getRollbackProvider());
                    rm.registerOnEventBus(eventBus);
                });

        tokenStatsService = findOrCreate(listeners, TokenStatsService.class,
                new TokenStatsService(
                        governanceConfig.isTokenStatsEnabled(),
                        governanceConfig.getPricing() != null ? governanceConfig.getPricing().getInputPerMillion() : 0.0,
                        governanceConfig.getPricing() != null ? governanceConfig.getPricing().getCachedInputPerMillion() : 0.0,
                        governanceConfig.getPricing() != null ? governanceConfig.getPricing().getOutputPerMillion() : 0.0),
                ts -> ts.registerOnEventBus(eventBus));

        if (ctx instanceof SimplePluginContext spc) {
            spc.registerService(TokenStatsService.class, tokenStatsService);
            spc.registerService(RollbackManager.class, rollbackManager);
            spc.registerService(Auditor.class, auditor);
        }
    }

    @SuppressWarnings("unchecked")
    private <T extends ExtensionPoint> T findOrCreate(List<ExtensionPoint> listeners, Class<T> type,
                                                       T fallback, Consumer<T> fallbackRegister) {
        T instance = (T) listeners.stream()
                .filter(type::isInstance)
                .map(type::cast)
                .findFirst()
                .orElse(null);
        if (instance == null) {
            instance = fallback;
            fallbackRegister.accept(instance);
            LOG.warning("插件通道缺少 " + type.getSimpleName() + "，回退到内置实现");
        }
        return instance;
    }

    /**
     * 热重载治理监听器（插件目录 jar 变化时触发）
     * <p>取消旧订阅 → 关闭隔离类加载器 → 重新加载 → 更新引用与注册表 → 通知装配链替换引用。</p>
     */
    public synchronized void reloadListeners() {
        if (ctx == null || pluginManager == null) return;

        // 1. 取消旧实例的 EventBus 订阅并释放资源
        if (auditor != null) {
            auditor.unregisterAll();
            auditor.shutdown();
        }
        if (rollbackManager != null) rollbackManager.unregisterAll();
        if (tokenStatsService != null) tokenStatsService.unregisterAll();

        // 2. 关闭隔离类加载器（卸载旧插件类）
        pluginManager.closeAllLoaders();

        // 3. 重新加载并更新实例
        loadListeners();

        // 4. 通知装配链替换 GovernanceGateway 引用
        if (listenerChangeListener != null) {
            listenerChangeListener.accept(new ListenerBundle(auditor, rollbackManager, tokenStatsService));
        }
        LOG.info("治理监听器已热重载: auditor=" + (auditor != null)
                + ", rollbackManager=" + (rollbackManager != null)
                + ", tokenStatsService=" + (tokenStatsService != null));
    }

    /** 监听器变更通知（由装配链注册，用于热插拔时替换 GovernanceGateway 引用） */
    public void setListenerChangeListener(Consumer<ListenerBundle> listenerChangeListener) {
        this.listenerChangeListener = listenerChangeListener;
    }

    /** 治理监听器集合（热重载后打包通知装配链） */
    public record ListenerBundle(Auditor auditor, RollbackManager rollbackManager,
                                 TokenStatsService tokenStatsService) {}

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

    public void shutdown() {
        if (hotReloader != null) hotReloader.close();
        if (auditor != null) auditor.shutdown();
    }
}
