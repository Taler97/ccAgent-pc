package com.ccagent.adapter.memory.di;

import com.ccagent.adapter.memory.H2MemoryAdapter;
import com.ccagent.api.port.LlmPort;
import com.ccagent.api.port.MemoryPort;

import javax.sql.DataSource;

public class MemoryModule {

    private H2MemoryAdapter memoryAdapter;

    public void init(DataSource dataSource, LlmPort llmPort) {
        memoryAdapter = new H2MemoryAdapter(dataSource, llmPort);
    }

    public MemoryPort getMemoryPort() {
        return memoryAdapter;
    }
}
