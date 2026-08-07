package com.ccagent.plugin.experts.impl;

import com.ccagent.api.model.request.ChatRequest;
import com.ccagent.api.model.response.ChatResponse;
import com.ccagent.api.model.response.StreamChunk;
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
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * SummarizerExpert 单元测试
 */
@ExtendWith(MockitoExtension.class)
class SummarizerExpertTest {

    @Mock
    LlmPort llmPort;

    @Mock
    ToolRegistryPort toolRegistry;

    @Mock
    StreamObserver<StreamChunk> observer;

    private SummarizerExpert summarizer;

    @BeforeEach
    void setUp() {
        summarizer = new SummarizerExpert(toolRegistry, llmPort);
        lenient().when(toolRegistry.getToolDefinitions()).thenReturn(List.of());
    }

    @Test
    void shouldExposeMetadata() {
        assertThat(summarizer.getName()).isEqualTo("summarizer");
        assertThat(summarizer.getDescription()).contains("摘要");
    }

    @Test
    void shouldExecuteWithPlainTask() {
        when(llmPort.chat(any())).thenReturn(new ChatResponse("提炼的要点", null, 10, 0, "stop"));

        Map<String, Object> result = summarizer.execute("提炼要点", null, Map.of(), "/ws", observer);

        assertThat(result).containsEntry("result", "提炼的要点");

        ArgumentCaptor<ChatRequest> captor = ArgumentCaptor.forClass(ChatRequest.class);
        verify(llmPort).chat(captor.capture());
        // ReAct 循环中用户消息放入 historyMessages[0]
        assertThat(captor.getValue().getHistoryMessages().get(0))
                .containsEntry("content", "提炼要点");
    }

    @Test
    void shouldWrapAnalysisContentInPrompt() {
        when(llmPort.chat(any())).thenReturn(new ChatResponse("摘要", null, 10, 0, "stop"));

        summarizer.execute("做摘要", null, Map.of("analysis", "待摘要的原始分析"), "/ws", observer);

        ArgumentCaptor<ChatRequest> captor = ArgumentCaptor.forClass(ChatRequest.class);
        verify(llmPort).chat(captor.capture());
        assertThat(captor.getValue().getHistoryMessages().get(0))
                .containsEntry("content",
                        "以下分析结果需要摘要：\n```\n待摘要的原始分析\n```\n\n摘要要求：\n做摘要");
    }

    @Test
    void shouldPushStatusEvents() {
        when(llmPort.chat(any())).thenReturn(new ChatResponse("摘要", null, 10, 0, "stop"));

        summarizer.execute("任务", null, Map.of(), "/ws", observer);

        verify(observer, org.mockito.Mockito.atLeast(3)).onNext(any());
    }
}
