package com.ccagent.presentation.cli;

import com.ccagent.api.port.LlmPort;
import com.ccagent.api.port.ToolRegistryPort;
import com.ccagent.plugin.governance.audit.Auditor;
import com.ccagent.plugin.governance.audit.TokenStatsService;
import com.ccagent.plugin.governance.rollback.RollbackManager;
import com.ccagent.core.plugin.ExpertExtensionPoint;
import com.ccagent.core.plugin.ListenerExtensionPoint;
import com.ccagent.core.plugin.ToolExtensionPoint;
import com.ccagent.core.sync.LocalDataRepository;
import com.ccagent.infrastructure.config.AppConfig;
import com.ccagent.kernel.event.SimpleEventBus;
import com.ccagent.kernel.plugin.ExtensionPoint;
import com.ccagent.kernel.plugin.PluginContext;
import com.ccagent.kernel.plugin.PluginManager;
import com.ccagent.kernel.plugin.PluginType;
import com.ccagent.kernel.plugin.SimplePluginContext;
import com.ccagent.kernel.plugin.SimplePluginManager;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * PluginManager 运行时集成验证
 * <p>验证 SimplePluginManager 在完整 classpath 下能正确扫描 plugin.yaml、
 * 拓扑排序、反射实例化并注入依赖。</p>
 */
@ExtendWith(MockitoExtension.class)
class PluginManagerIntegrationTest {

    @Mock
    private ToolRegistryPort toolRegistry;

    @Mock
    private LlmPort llmPort;

    @Mock
    private LocalDataRepository localDataRepository;

    private SimplePluginContext pluginContext;
    private PluginManager pluginManager;

    @BeforeEach
    void setUp() {
        pluginContext = new SimplePluginContext();
        pluginContext.registerService(AppConfig.class, new AppConfig());
        pluginContext.registerService(ToolRegistryPort.class, toolRegistry);
        pluginContext.registerService(LlmPort.class, llmPort);
        pluginContext.registerService(LocalDataRepository.class, localDataRepository);
        pluginContext.registerService(com.ccagent.kernel.event.EventBus.class,
                new SimpleEventBus(false, 100, 1));

        pluginManager = new SimplePluginManager();
    }

    @Test
    void shouldScanAllPluginDescriptors() {
        List<com.ccagent.kernel.plugin.PluginDescriptor> descriptors =
                pluginManager.getLoadedDescriptors();

        // SimplePluginManager 延迟扫描，首次 getLoadedDescriptors 返回空
        // 触发一次加载后再验证
        pluginManager.loadExtensions(PluginType.TOOL, pluginContext);
        descriptors = pluginManager.getLoadedDescriptors();

        assertThat(descriptors)
                .hasSize(16)
                .extracting(com.ccagent.kernel.plugin.PluginDescriptor::getType)
                .containsExactlyInAnyOrder(
                        PluginType.TOOL, PluginType.TOOL, PluginType.TOOL,
                        PluginType.TOOL, PluginType.TOOL, PluginType.TOOL,
                        PluginType.EXPERT, PluginType.EXPERT, PluginType.EXPERT,
                        PluginType.EXPERT, PluginType.EXPERT, PluginType.EXPERT,
                        PluginType.EXPERT,
                        PluginType.LISTENER, PluginType.LISTENER, PluginType.LISTENER
                );
    }

    @Test
    void shouldLoadAllToolPlugins() {
        List<ExtensionPoint> extensions = pluginManager.loadExtensions(PluginType.TOOL, pluginContext);

        assertThat(extensions).hasSize(6);

        Set<String> classNames = extensions.stream()
                .map(Object::getClass)
                .map(Class::getName)
                .collect(Collectors.toSet());

        assertThat(classNames).containsExactlyInAnyOrder(
                "com.ccagent.plugin.tools.impl.ListDirectoryTool",
                "com.ccagent.plugin.tools.impl.ReadFileTool",
                "com.ccagent.plugin.tools.impl.SearchContentTool",
                "com.ccagent.plugin.tools.impl.WriteFileTool",
                "com.ccagent.plugin.tools.impl.EditFileTool",
                "com.ccagent.plugin.tools.impl.FileOperationTool"
        );

        // 验证所有工具都实现了 ToolExtensionPoint
        assertThat(extensions).allMatch(ext -> ext instanceof ToolExtensionPoint);
    }

    @Test
    void shouldLoadAllExpertPlugins() {
        List<ExtensionPoint> extensions = pluginManager.loadExtensions(PluginType.EXPERT, pluginContext);

        assertThat(extensions).hasSize(7);

        Set<String> classNames = extensions.stream()
                .map(Object::getClass)
                .map(Class::getName)
                .collect(Collectors.toSet());

        assertThat(classNames).containsExactlyInAnyOrder(
                "com.ccagent.plugin.experts.impl.WriterExpert",
                "com.ccagent.plugin.experts.impl.ResearcherExpert",
                "com.ccagent.plugin.experts.impl.OrganizerExpert",
                "com.ccagent.plugin.experts.impl.NoteTakerExpert",
                "com.ccagent.plugin.experts.impl.SummarizerExpert",
                "com.ccagent.plugin.experts.impl.PlannerExpert",
                "com.ccagent.plugin.experts.impl.FaultAnalyzerExpert"
        );

        // 验证所有专家都实现了 ExpertExtensionPoint
        assertThat(extensions).allMatch(ext -> ext instanceof ExpertExtensionPoint);

        // 验证依赖注入成功（toolRegistry 和 llmPort 不为 null）
        for (ExtensionPoint ext : extensions) {
            ExpertExtensionPoint expert = (ExpertExtensionPoint) ext;
            assertThat(expert).extracting("toolRegistry").isNotNull();
            assertThat(expert).extracting("llmPort").isNotNull();
        }
    }

    @Test
    void shouldLoadAllListenerPlugins() {
        List<ExtensionPoint> extensions = pluginManager.loadExtensions(PluginType.LISTENER, pluginContext);

        assertThat(extensions).hasSize(3);

        Set<String> classNames = extensions.stream()
                .map(Object::getClass)
                .map(Class::getName)
                .collect(Collectors.toSet());

        assertThat(classNames).containsExactlyInAnyOrder(
                "com.ccagent.plugin.governance.audit.Auditor",
                "com.ccagent.plugin.governance.rollback.RollbackManager",
                "com.ccagent.plugin.governance.audit.TokenStatsService"
        );

        // 验证所有监听器都实现了 ListenerExtensionPoint
        assertThat(extensions).allMatch(ext -> ext instanceof ListenerExtensionPoint);
    }

    @Test
    void shouldReportCorrectLoadedCount() {
        pluginManager.loadExtensions(PluginType.TOOL, pluginContext);
        pluginManager.loadExtensions(PluginType.EXPERT, pluginContext);
        pluginManager.loadExtensions(PluginType.LISTENER, pluginContext);

        assertThat(pluginManager.getLoadedCount()).isEqualTo(16);
    }

    @Test
    void dependencyGraphShouldAssembleWithPluginLoading() {
        AppConfig config = new AppConfig();
        DependencyGraph graph = new DependencyGraph();

        // 不抛出异常即成功
        graph.assemble(config);

        // 验证关键组件已初始化
        assertThat(graph.toolModule.getToolRegistry()).isNotNull();
        assertThat(graph.expertModule.getExpertRegistry()).isNotNull();
        assertThat(graph.governanceModule.getEventBus()).isNotNull();
        assertThat(graph.governanceModule.getAuditor()).isNotNull();
        assertThat(graph.governanceModule.getRollbackManager()).isNotNull();
        assertThat(graph.governanceModule.getTokenStatsService()).isNotNull();
    }
}
