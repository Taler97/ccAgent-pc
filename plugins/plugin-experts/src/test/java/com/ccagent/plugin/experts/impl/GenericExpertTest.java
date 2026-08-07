package com.ccagent.plugin.experts.impl;

import com.ccagent.api.model.request.ChatRequest;
import com.ccagent.api.model.response.ChatResponse;
import com.ccagent.api.model.response.StreamChunk;
import com.ccagent.api.model.tool.ToolDefinition;
import com.ccagent.api.port.LlmPort;
import com.ccagent.api.port.StreamObserver;
import com.ccagent.api.port.ToolRegistryPort;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * GenericExpert 单元测试（配置式通用专家）
 */
@ExtendWith(MockitoExtension.class)
class GenericExpertTest {

    @Mock
    LlmPort llmPort;

    @Mock
    ToolRegistryPort toolRegistry;

    @Mock
    StreamObserver<StreamChunk> observer;

    private GenericExpert expert;

    @BeforeEach
    void setUp() {
        expert = new GenericExpert();
        // 同包内直接注入 protected 字段（等价于 init(PluginContext) 的注入路径）
        expert.toolRegistry = toolRegistry;
        expert.llmPort = llmPort;
    }

    // ==================== 配置注入 ====================

    @Test
    void shouldReflectConfiguredMetadata() {
        expert.setExpertName("code-reviewer");
        expert.setDescription("代码评审专家");
        expert.setSystemPrompt("你是一个代码评审专家");

        assertThat(expert.getName()).isEqualTo("code-reviewer");
        assertThat(expert.getDescription()).isEqualTo("代码评审专家");
        assertThat(expert.getSkills()).isEmpty();
    }

    @Test
    void shouldBindSkills() {
        expert.setSkills(List.of("skill-a", "skill-b"));
        assertThat(expert.getSkills()).containsExactly("skill-a", "skill-b");
    }

    // ==================== 工具过滤 ====================

    @Test
    void shouldExposeAllToolsWhenNoConfig() {
        when(toolRegistry.getToolDefinitions()).thenReturn(List.of(
                new ToolDefinition("read_file", "读取", Map.of(), true),
                new ToolDefinition("write_file", "写入", Map.of(), false)));

        expert.setTools(null);
        assertThat(expert.getAvailableTools()).hasSize(2);
    }

    @Test
    void shouldFilterToolsByConfig() {
        when(toolRegistry.getToolDefinitions()).thenReturn(List.of(
                new ToolDefinition("read_file", "读取", Map.of(), true),
                new ToolDefinition("write_file", "写入", Map.of(), false),
                new ToolDefinition("search_content", "搜索", Map.of(), true)));

        expert.setTools(List.of("read_file"));
        assertThat(expert.getAvailableTools())
                .extracting(ToolDefinition::getName)
                .containsExactly("read_file");
    }

    // ==================== 执行流程 ====================

    @Test
    void shouldRunReActAndReturnWrappedResult() {
        when(toolRegistry.getToolDefinitions()).thenReturn(List.of());
        expert.setSystemPrompt("配置的系统提示词");
        when(llmPort.chat(any())).thenReturn(new ChatResponse("评审意见", null, 10, 0, "stop"));

        Map<String, Object> result = expert.execute("评审代码", null, Map.of(), "/ws", observer);

        assertThat(result).containsEntry("result", "评审意见");

        ArgumentCaptor<ChatRequest> captor = ArgumentCaptor.forClass(ChatRequest.class);
        verify(llmPort).chat(captor.capture());
        assertThat(captor.getValue().getSystemPrompt()).isEqualTo("配置的系统提示词");
        // ReAct 循环中用户消息放入 historyMessages[0]
        assertThat(captor.getValue().getHistoryMessages().get(0))
                .containsEntry("content", "评审代码");
    }
}
