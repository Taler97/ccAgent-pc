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
 * WriterExpert 单元测试
 */
@ExtendWith(MockitoExtension.class)
class WriterExpertTest {

    @Mock
    LlmPort llmPort;

    @Mock
    ToolRegistryPort toolRegistry;

    @Mock
    StreamObserver<StreamChunk> observer;

    private WriterExpert writer;

    @BeforeEach
    void setUp() {
        writer = new WriterExpert(toolRegistry, llmPort);
        lenient().when(toolRegistry.getToolDefinitions()).thenReturn(List.of());
    }

    // ==================== 元信息 ====================

    @Test
    void shouldExposeMetadata() {
        assertThat(writer.getName()).isEqualTo("writer");
        assertThat(writer.getDescription()).contains("撰写");
    }

    // ==================== 执行流程 ====================

    @Test
    void shouldExecuteWithoutAnalysis() {
        when(llmPort.chat(any())).thenReturn(new ChatResponse("完成的内容", null, 10, 0, "stop"));

        Map<String, Object> result = writer.execute("写一篇文档", null, Map.of(), "/ws", observer);

        assertThat(result).containsEntry("result", "完成的内容");

        ArgumentCaptor<ChatRequest> captor = ArgumentCaptor.forClass(ChatRequest.class);
        verify(llmPort).chat(captor.capture());
        // ReAct 循环中用户消息放入 historyMessages[0]
        assertThat(captor.getValue().getHistoryMessages().get(0))
                .containsEntry("content", "写一篇文档");
    }

    @Test
    void shouldIncludeAnalysisInPrompt() {
        when(llmPort.chat(any())).thenReturn(new ChatResponse("完成的内容", null, 10, 0, "stop"));

        writer.execute("写一篇文档", null, Map.of("analysis", "分析结果"), "/ws", observer);

        ArgumentCaptor<ChatRequest> captor = ArgumentCaptor.forClass(ChatRequest.class);
        verify(llmPort).chat(captor.capture());
        assertThat(captor.getValue().getHistoryMessages().get(0))
                .containsEntry("content", "参考分析结果：\n分析结果\n\n任务：\n写一篇文档");
    }

    @Test
    void shouldPushStatusEvents() {
        when(llmPort.chat(any())).thenReturn(new ChatResponse("内容", null, 10, 0, "stop"));

        writer.execute("任务", null, Map.of(), "/ws", observer);

        // RUNNING + SUCCESS 两个状态事件 + 流式文本块
        verify(observer, org.mockito.Mockito.atLeast(3)).onNext(any());
    }
}
