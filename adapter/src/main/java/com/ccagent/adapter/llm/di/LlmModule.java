package com.ccagent.adapter.llm.di;

import com.ccagent.adapter.llm.DeepSeekLlmAdapter;
import com.ccagent.api.port.LlmPort;
import com.ccagent.infrastructure.config.AppConfig;

public class LlmModule {

    private DeepSeekLlmAdapter llmAdapter;

    public void init(AppConfig.LlmConfig config) {
        llmAdapter = new DeepSeekLlmAdapter(
                config.getApiKey(),
                config.getBaseUrl(),
                config.getModel(),
                config.getTimeoutStreaming()
        );
    }

    public LlmPort getLlmPort() {
        return llmAdapter;
    }
}
