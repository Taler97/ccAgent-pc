package com.ccagent.presentation.di;

import com.ccagent.core.expert.DefaultExpertRegistry;
import com.ccagent.core.plugin.ExpertExtensionPoint;
import com.ccagent.api.port.ExpertRegistryPort;
import com.ccagent.api.port.LlmPort;
import com.ccagent.api.port.ToolRegistryPort;
import com.ccagent.kernel.plugin.PluginContext;
import com.ccagent.kernel.plugin.PluginManager;
import com.ccagent.kernel.plugin.PluginType;

import java.util.List;

public class ExpertModule {

    private DefaultExpertRegistry expertRegistry;

    public void init(ToolRegistryPort toolRegistry, LlmPort llmPort) {
        expertRegistry = new DefaultExpertRegistry();
    }

    public void init(PluginContext ctx, PluginManager pm) {
        expertRegistry = new DefaultExpertRegistry();
        List<ExpertExtensionPoint> experts = pm.loadExtensions(PluginType.EXPERT, ctx)
                .stream()
                .map(ext -> (ExpertExtensionPoint) ext)
                .toList();
        for (ExpertExtensionPoint expert : experts) {
            expertRegistry.register(expert);
        }
    }

    public ExpertRegistryPort getExpertRegistry() {
        return expertRegistry;
    }
}