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
 * ResearcherExpert 单元测试（结构同 NoteTaker/Organizer 的 ReAct 专家）
 */
@ExtendWith(MockitoExtension.class)
class ResearcherExpertTest {

    @Mock
    LlmPort llmPort;

    @Mock
    ToolRegistryPort toolRegistry;

    @Mock
    StreamObserver<StreamChunk> observer;

    private ResearcherExpert researcher;

    @BeforeEach
    void setUp() {
        researcher = new ResearcherExpert(toolRegistry, llmPort);
        lenient().when(toolRegistry.getToolDefinitions()).thenReturn(List.of());
    }

    @Test
    void shouldExposeMetadata() {
        assertThat(researcher.getName()).isEqualTo("researcher");
        assertThat(researcher.getDescription()).contains("调研");
    }

    @Test
    void shouldRunReActAndReturnWrappedResult() {
        when(llmPort.chat(any())).thenReturn(new ChatResponse("研究报告", null, 10, 0, "stop"));

        Map<String, Object> result = researcher.execute("调研模块结构", null, Map.of(), "/ws", observer);

        assertThat(result).containsEntry("result", "研究报告");

        ArgumentCaptor<ChatRequest> captor = ArgumentCaptor.forClass(ChatRequest.class);
        verify(llmPort).chat(captor.capture());
        assertThat(captor.getValue().getHistoryMessages().get(0))
                .containsEntry("content", "调研模块结构");
    }

    @Test
    void shouldPushRunningAndSuccessStatus() {
        when(llmPort.chat(any())).thenReturn(new ChatResponse("报告", null, 10, 0, "stop"));

        researcher.execute("任务", null, Map.of(), "/ws", observer);

        verify(observer, org.mockito.Mockito.atLeast(3)).onNext(any());
    }
}

/**
 * NoteTakerExpert 单元测试
 */
@ExtendWith(MockitoExtension.class)
class NoteTakerExpertTest {

    @Mock
    LlmPort llmPort;

    @Mock
    ToolRegistryPort toolRegistry;

    @Mock
    StreamObserver<StreamChunk> observer;

    private NoteTakerExpert noteTaker;

    @BeforeEach
    void setUp() {
        noteTaker = new NoteTakerExpert(toolRegistry, llmPort);
        lenient().when(toolRegistry.getToolDefinitions()).thenReturn(List.of());
    }

    @Test
    void shouldExposeMetadata() {
        assertThat(noteTaker.getName()).isEqualTo("note-taker");
        assertThat(noteTaker.getDescription()).contains("笔记");
    }

    @Test
    void shouldRunReActAndReturnWrappedResult() {
        when(llmPort.chat(any())).thenReturn(new ChatResponse("整理好的笔记", null, 10, 0, "stop"));

        Map<String, Object> result = noteTaker.execute("记录会议纪要", null, Map.of(), "/ws", observer);

        assertThat(result).containsEntry("result", "整理好的笔记");

        ArgumentCaptor<ChatRequest> captor = ArgumentCaptor.forClass(ChatRequest.class);
        verify(llmPort).chat(captor.capture());
        assertThat(captor.getValue().getHistoryMessages().get(0))
                .containsEntry("content", "记录会议纪要");
    }
}

/**
 * OrganizerExpert 单元测试
 */
@ExtendWith(MockitoExtension.class)
class OrganizerExpertTest {

    @Mock
    LlmPort llmPort;

    @Mock
    ToolRegistryPort toolRegistry;

    @Mock
    StreamObserver<StreamChunk> observer;

    private OrganizerExpert organizer;

    @BeforeEach
    void setUp() {
        organizer = new OrganizerExpert(toolRegistry, llmPort);
        lenient().when(toolRegistry.getToolDefinitions()).thenReturn(List.of());
    }

    @Test
    void shouldExposeMetadata() {
        assertThat(organizer.getName()).isEqualTo("organizer");
        assertThat(organizer.getDescription()).contains("整理");
    }

    @Test
    void shouldRunReActAndReturnWrappedResult() {
        when(llmPort.chat(any())).thenReturn(new ChatResponse("整理建议", null, 10, 0, "stop"));

        Map<String, Object> result = organizer.execute("整理 downloads 目录", null, Map.of(), "/ws", observer);

        assertThat(result).containsEntry("result", "整理建议");

        ArgumentCaptor<ChatRequest> captor = ArgumentCaptor.forClass(ChatRequest.class);
        verify(llmPort).chat(captor.capture());
        assertThat(captor.getValue().getHistoryMessages().get(0))
                .containsEntry("content", "整理 downloads 目录");
    }
}
