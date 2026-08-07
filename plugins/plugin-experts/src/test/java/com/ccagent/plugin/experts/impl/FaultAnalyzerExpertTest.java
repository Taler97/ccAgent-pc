package com.ccagent.plugin.experts.impl;

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

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;

/**
 * FaultAnalyzerExpert 单元测试
 * <p>覆盖 seqId 提取分支与错误处理（不依赖真实日志文件）。</p>
 */
@ExtendWith(MockitoExtension.class)
class FaultAnalyzerExpertTest {

    @Mock
    LlmPort llmPort;

    @Mock
    ToolRegistryPort toolRegistry;

    @Mock
    StreamObserver<StreamChunk> observer;

    private FaultAnalyzerExpert expert;

    @BeforeEach
    void setUp() {
        expert = new FaultAnalyzerExpert();
        // 同包内直接注入 protected 字段（等价于 init(PluginContext) 的注入路径）
        expert.toolRegistry = toolRegistry;
        expert.llmPort = llmPort;
    }

    @Test
    void shouldExposeMetadata() {
        assertThat(expert.getName()).isEqualTo("fault-analyzer");
        assertThat(expert.getDescription()).contains("诊断");
    }

    @Test
    void shouldReturnErrorWhenSeqIdMissing() {
        String result = expert.executeLegacy("", null, Map.of(), "/ws", observer);

        assertThat(result).contains("缺少 seqId 参数");

        ArgumentCaptor<StreamChunk> captor = ArgumentCaptor.forClass(StreamChunk.class);
        verify(observer).onNext(captor.capture());
        assertThat(captor.getValue().getType()).isEqualTo("ERROR");
    }

    @Test
    void shouldExtractSeqIdFromInputs() {
        String result = expert.executeLegacy("诊断失败原因", null, Map.of("seqId", "seq-abc"), "/ws", observer);

        // errors.log 不存在 → 返回"未找到错误记录"（证明 seqId 从 inputs 成功提取）
        assertThat(result).contains("seq-abc").contains("未找到");
    }

    @Test
    void shouldParseSeqIdFromDiagnoseTask() {
        String result = expert.executeLegacy("diagnose seq-x1", null, Map.of(), "/ws", observer);

        assertThat(result).contains("seq-x1").contains("未找到");
    }

    @Test
    void shouldUseWholeTaskAsSeqIdWhenNoPrefix() {
        String result = expert.executeLegacy("seq-y2", null, Map.of(), "/ws", observer);

        assertThat(result).contains("seq-y2");
    }
}
