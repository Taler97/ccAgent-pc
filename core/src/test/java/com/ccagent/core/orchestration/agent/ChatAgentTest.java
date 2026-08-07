package com.ccagent.core.orchestration.agent;

import com.ccagent.api.model.memory.ConversationTurn;
import com.ccagent.api.model.memory.StructuredContext;
import com.ccagent.api.model.request.ChatRequest;
import com.ccagent.api.model.response.ChatResponse;
import com.ccagent.api.port.LlmPort;
import com.ccagent.api.port.MemoryPort;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ChatAgentTest {

    @Mock
    LlmPort llmPort;

    @Mock
    MemoryPort memoryPort;

    private ChatAgent newAgent() {
        return new ChatAgent(llmPort, memoryPort);
    }

    private void mockMemory(String summary, List<ConversationTurn> turns) {
        when(memoryPort.loadStructuredContext(anyString()))
                .thenReturn(new StructuredContext(summary, turns, turns.size()));
    }

    // ==================== 解析纯 JSON ====================

    @Test
    void shouldReplyFromPlainJson() {
        mockMemory("", List.of());
        when(llmPort.chat(any())).thenReturn(
                new ChatResponse("{\"type\":\"chat\",\"content\":\"你好\"}", null, 10, 0, "stop"));

        ChatResult result = newAgent().chat("s1", "w1", "你好", null);

        assertThat(result.isReply()).isTrue();
        assertThat(result.getReplyContent()).isEqualTo("你好");
    }

    @Test
    void shouldPlanFromPlainJson() {
        mockMemory("", List.of());
        when(llmPort.chat(any())).thenReturn(
                new ChatResponse("{\"type\":\"plan\",\"task\":\"重构用户模块\"}", null, 10, 0, "stop"));

        ChatResult result = newAgent().chat("s1", "w1", "重构", null);

        assertThat(result.isPlan()).isTrue();
        assertThat(result.getTaskDescription()).isEqualTo("重构用户模块");
    }

    // ==================== 解析代码块 JSON ====================

    @Test
    void shouldParseJsonFromCodeBlock() {
        mockMemory("", List.of());
        when(llmPort.chat(any())).thenReturn(
                new ChatResponse("```json\n{\"type\":\"chat\",\"content\":\"你好\"}\n```", null, 10, 0, "stop"));

        ChatResult result = newAgent().chat("s1", "w1", "你好", null);

        assertThat(result.isReply()).isTrue();
        assertThat(result.getReplyContent()).isEqualTo("你好");
    }

    @Test
    void shouldParseBareJsonAfterNaturalLanguagePrefix() {
        mockMemory("", List.of());
        when(llmPort.chat(any())).thenReturn(
                new ChatResponse("我来帮你处理。{\"type\":\"plan\",\"task\":\"创建文件\"} 请确认", null, 10, 0, "stop"));

        ChatResult result = newAgent().chat("s1", "w1", "创建文件", null);

        assertThat(result.isPlan()).isTrue();
        assertThat(result.getTaskDescription()).isEqualTo("创建文件");
    }

    // ==================== 回退流程 ====================

    @Test
    void shouldTreatNonJsonAsPlainReply() {
        mockMemory("", List.of());
        when(llmPort.chat(any())).thenReturn(
                new ChatResponse("今天天气不错", null, 10, 0, "stop"));

        ChatResult result = newAgent().chat("s1", "w1", "天气", null);

        assertThat(result.isReply()).isTrue();
        assertThat(result.getReplyContent()).isEqualTo("今天天气不错");
    }

    @Test
    void shouldReplyEmptyWhenContentBlank() {
        mockMemory("", List.of());
        when(llmPort.chat(any())).thenReturn(new ChatResponse("", null, 0, 0, "stop"));

        ChatResult result = newAgent().chat("s1", "w1", "x", null);

        assertThat(result.isReply()).isTrue();
        assertThat(result.getReplyContent()).isEmpty();
    }

    @Test
    void shouldUseTaskFieldAsFallbackContent() {
        mockMemory("", List.of());
        // type=chat 但没有 content，用 task 字段兜底
        when(llmPort.chat(any())).thenReturn(
                new ChatResponse("{\"type\":\"chat\",\"task\":\"兜底内容\"}", null, 10, 0, "stop"));

        ChatResult result = newAgent().chat("s1", "w1", "x", null);

        assertThat(result.isReply()).isTrue();
        assertThat(result.getReplyContent()).isEqualTo("兜底内容");
    }

    // ==================== 历史与记忆 ====================

    @Test
    void shouldPreferExternalHistoryOverMemoryTurns() {
        mockMemory("", List.of(new ConversationTurn("user", "旧轮次", 5)));
        when(llmPort.chat(any())).thenReturn(
                new ChatResponse("{\"type\":\"chat\",\"content\":\"ok\"}", null, 10, 0, "stop"));

        List<Map<String, String>> external = List.of(
                Map.of("role", "user", "content", "外部历史"));

        newAgent().chat("s1", "w1", "x", external);

        ArgumentCaptor<ChatRequest> captor = ArgumentCaptor.forClass(ChatRequest.class);
        verify(llmPort).chat(captor.capture());
        assertThat(captor.getValue().getHistoryMessages()).hasSize(1);
        assertThat(captor.getValue().getHistoryMessages().get(0)).containsEntry("content", "外部历史");
    }

    @Test
    void shouldUseMemoryTurnsWhenNoExternalHistory() {
        mockMemory("", List.of(new ConversationTurn("user", "记忆轮次", 5)));
        when(llmPort.chat(any())).thenReturn(
                new ChatResponse("{\"type\":\"chat\",\"content\":\"ok\"}", null, 10, 0, "stop"));

        newAgent().chat("s1", "w1", "x", null);

        ArgumentCaptor<ChatRequest> captor = ArgumentCaptor.forClass(ChatRequest.class);
        verify(llmPort).chat(captor.capture());
        assertThat(captor.getValue().getHistoryMessages().get(0))
                .containsEntry("role", "user")
                .containsEntry("content", "记忆轮次");
    }

    @Test
    void shouldInjectMemorySummaryIntoPrompt() {
        mockMemory("用户偏好 Java 21", List.of());
        when(llmPort.chat(any())).thenReturn(
                new ChatResponse("{\"type\":\"chat\",\"content\":\"ok\"}", null, 10, 0, "stop"));

        newAgent().chat("s1", "w1", "写代码", null);

        ArgumentCaptor<ChatRequest> captor = ArgumentCaptor.forClass(ChatRequest.class);
        verify(llmPort).chat(captor.capture());
        assertThat(captor.getValue().getUserPrompt())
                .contains("对话记忆摘要")
                .contains("用户偏好 Java 21")
                .contains("写代码");
    }

    @Test
    void shouldDegradeGracefullyWhenMemoryFails() {
        when(memoryPort.loadStructuredContext(anyString()))
                .thenThrow(new RuntimeException("DB 不可用"));
        when(llmPort.chat(any())).thenReturn(
                new ChatResponse("{\"type\":\"chat\",\"content\":\"ok\"}", null, 10, 0, "stop"));

        ChatResult result = newAgent().chat("s1", "w1", "x", null);

        assertThat(result.isReply()).isTrue();
        assertThat(result.getReplyContent()).isEqualTo("ok");
    }

    // ==================== 外部 system prompt ====================

    @Test
    void shouldUseConfiguredSystemPrompt() {
        mockMemory("", List.of());
        when(llmPort.chat(any())).thenReturn(
                new ChatResponse("{\"type\":\"chat\",\"content\":\"ok\"}", null, 10, 0, "stop"));

        new ChatAgent(llmPort, memoryPort, "自定义系统提示词").chat("s1", "w1", "x", null);

        ArgumentCaptor<ChatRequest> captor = ArgumentCaptor.forClass(ChatRequest.class);
        verify(llmPort).chat(captor.capture());
        assertThat(captor.getValue().getSystemPrompt()).isEqualTo("自定义系统提示词");
    }

    @Test
    void shouldFallBackToDefaultSystemPromptWhenConfiguredBlank() {
        mockMemory("", List.of());
        when(llmPort.chat(any())).thenReturn(
                new ChatResponse("{\"type\":\"chat\",\"content\":\"ok\"}", null, 10, 0, "stop"));

        new ChatAgent(llmPort, memoryPort, "  ").chat("s1", "w1", "x", null);

        ArgumentCaptor<ChatRequest> captor = ArgumentCaptor.forClass(ChatRequest.class);
        verify(llmPort).chat(captor.capture());
        assertThat(captor.getValue().getSystemPrompt())
                .startsWith("你是一个智能助手")
                .contains("{\"type\": \"plan\", \"task\": \"用户任务的详细描述\"}");
    }
}
