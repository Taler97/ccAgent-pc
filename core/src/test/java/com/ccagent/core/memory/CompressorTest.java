package com.ccagent.core.memory;

import com.ccagent.api.model.memory.ConversationTurn;
import com.ccagent.api.model.request.ChatRequest;
import com.ccagent.api.model.response.ChatResponse;
import com.ccagent.api.port.LlmPort;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CompressorTest {

    @Mock
    LlmPort llmPort;

    private List<ConversationTurn> turns(int count) {
        List<ConversationTurn> list = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            list.add(new ConversationTurn(i % 2 == 0 ? "user" : "assistant", "内容-" + i, 10));
        }
        return list;
    }

    @Test
    void shouldCompressFirstThirdOfTurns() {
        when(llmPort.chat(any())).thenReturn(new ChatResponse("新摘要", null, 100, 0, "stop"));

        Compressor.CompressResult result = new Compressor(llmPort).compress(turns(6), "");

        // 6 条轮次压缩最早 2 条，剩余 4 条
        assertThat(result.summary()).isEqualTo("新摘要");
        assertThat(result.remaining()).hasSize(4);
        assertThat(result.remaining().get(0).getContent()).isEqualTo("内容-2");
        assertThat(result.totalTurns()).isEqualTo(4);
    }

    @Test
    void shouldMergeWithExistingSummary() {
        when(llmPort.chat(any())).thenReturn(new ChatResponse("新增摘要", null, 100, 0, "stop"));

        Compressor.CompressResult result = new Compressor(llmPort).compress(turns(3), "旧摘要");

        assertThat(result.summary()).startsWith("旧摘要").endsWith("新增摘要");
    }

    @Test
    void shouldCompressAtLeastOneTurn() {
        when(llmPort.chat(any())).thenReturn(new ChatResponse("摘要", null, 100, 0, "stop"));

        Compressor.CompressResult result = new Compressor(llmPort).compress(turns(2), "");

        assertThat(result.remaining()).hasSize(1);
        assertThat(result.totalTurns()).isEqualTo(1);
    }

    @Test
    void shouldPassTurnsToLlmPrompt() {
        when(llmPort.chat(any())).thenReturn(new ChatResponse("摘要", null, 100, 0, "stop"));

        new Compressor(llmPort).compress(turns(3), "");

        ArgumentCaptor<ChatRequest> captor = ArgumentCaptor.forClass(ChatRequest.class);
        verify(llmPort).chat(captor.capture());
        assertThat(captor.getValue().getUserPrompt()).contains("user: 内容-0");
    }

    @Test
    void shouldTruncateOverlongMergedSummary() {
        // 构造超过截断阈值（maxTokens=2000 → targetLen=8000 字符）的既有摘要
        String longSummary = "长".repeat(9000);
        when(llmPort.chat(any())).thenReturn(new ChatResponse("新", null, 100, 0, "stop"));

        Compressor.CompressResult result = new Compressor(llmPort).compress(turns(3), longSummary);

        assertThat(result.summary()).startsWith("...");
        assertThat(result.summary().length()).isLessThan(longSummary.length());
    }

    @Test
    void shouldReturnNullWhenLlmFails() {
        when(llmPort.chat(any())).thenThrow(new RuntimeException("LLM 调用失败"));

        Compressor.CompressResult result = new Compressor(llmPort).compress(turns(3), "");

        assertThat(result).isNull();
    }

    @Test
    void shouldReturnNullForEmptyTurns() {
        // 回归（bug #1）：空轮次列表返回 null 而非抛 IndexOutOfBoundsException
        assertThat(new Compressor(llmPort).compress(List.of(), "")).isNull();
    }
}
