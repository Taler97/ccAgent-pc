package com.ccagent.plugin.experts.impl;

import com.ccagent.api.context.TaskScope;
import com.ccagent.api.model.request.ToolCall;
import com.ccagent.api.model.response.ChatResponse;
import com.ccagent.api.model.response.ExecutionResult;
import com.ccagent.api.model.response.StreamChunk;
import com.ccagent.api.model.tool.ToolDefinition;
import com.ccagent.api.port.LlmPort;
import com.ccagent.api.port.StreamObserver;
import com.ccagent.api.port.ToolRegistryPort;
import com.ccagent.core.governance.context.ExecutionContextHolder;
import com.ccagent.core.orchestration.context.ExecutionContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * BaseExpert 共享能力单元测试
 * <p>通过测试子类 TestExpert 暴露 protected 方法，覆盖包装、上下文格式化、JSON 提取、ReAct 循环等。</p>
 */
@ExtendWith(MockitoExtension.class)
class BaseExpertTest {

    @Mock
    LlmPort llmPort;

    @Mock
    ToolRegistryPort toolRegistry;

    @Mock
    StreamObserver<StreamChunk> observer;

    @AfterEach
    void tearDown() {
        ExecutionContextHolder.clear();
    }

    /** 测试专用子类：暴露 BaseExpert 的 protected 方法 */
    static class TestExpert extends BaseExpert {
        TestExpert(ToolRegistryPort tr, LlmPort llm) { super(tr, llm); }
        @Override public String getName() { return "test"; }
        @Override public String getDescription() { return "测试专家"; }
        @Override protected String executeLegacy(String task, TaskScope ts, Map<String, Object> inputs,
                                                 String ws, StreamObserver<StreamChunk> obs) {
            return "legacy:" + task;
        }
        String fmtInputs(Map<String, Object> i) { return formatInputsAsContext(i); }
        String guidance(Set<String> w) { return structuredOutputGuidance(w); }
        @Override
        protected String extractJson(String c) { return super.extractJson(c); }
        List<String> filePaths(String t) { return extractFilePaths(t); }
        String firstFilePath(String t) { return extractFirstFilePath(t); }
        String trunc(String s, int m) { return truncate(s, m); }
        String reAct(String sys, String user, String ws, StreamObserver<StreamChunk> o) {
            return runReActLoop(sys, user, ws, o);
        }
        String llm(String sys, String user, StreamObserver<StreamChunk> o) { return callLlm(sys, user, o); }
        String effective(String base) { return buildEffectiveSystemPrompt(base); }
        List<ToolDefinition> tools() { return getAvailableTools(); }
    }

    private TestExpert newExpert() {
        return new TestExpert(toolRegistry, llmPort);
    }

    // ==================== 黑板模式包装 ====================

    @Test
    void shouldWrapLegacyResultIntoMap() {
        Map<String, Object> result = newExpert().execute("任务", null, Map.of(), "/ws", observer);

        assertThat(result).containsEntry("result", "legacy:任务");
    }

    // ==================== 上下文格式化 ====================

    @Test
    void shouldFormatInputsAsContext() {
        TestExpert expert = newExpert();
        assertThat(expert.fmtInputs(null)).isEmpty();
        assertThat(expert.fmtInputs(Map.of())).isEmpty();

        String ctx = expert.fmtInputs(Map.of(
                "files", List.of("a", "b", "c"),
                "meta", Map.of("k", "v"),
                "name", "very-long-value"));
        assertThat(ctx).contains("files: 3 项");
        assertThat(ctx).contains("meta: 1 个字段");
        assertThat(ctx).contains("name:");
    }

    @Test
    void shouldBuildStructuredOutputGuidance() {
        TestExpert expert = newExpert();
        assertThat(expert.guidance(null)).isEmpty();
        assertThat(expert.guidance(Set.of())).isEmpty();

        String guidance = expert.guidance(Set.of("analysis", "conclusion"));
        assertThat(guidance).contains("analysis").contains("conclusion");
    }

    // ==================== JSON / 路径提取 ====================

    @Test
    void shouldExtractJsonFromCodeBlock() {
        TestExpert expert = newExpert();
        assertThat(expert.extractJson(null)).isEmpty();
        assertThat(expert.extractJson("```json\n{\"a\":1}\n```")).isEqualTo("{\"a\":1}");
        assertThat(expert.extractJson("plain text")).isEqualTo("plain text");
    }

    @Test
    void shouldExtractFilePathsFromTask() {
        TestExpert expert = newExpert();
        List<String> paths = expert.filePaths("读取文件 src/main/Main.java 并修改 file: config.yaml");
        assertThat(paths).contains("src/main/Main.java", "config.yaml");
        assertThat(expert.firstFilePath("路径: /tmp/a.txt 和文件: /tmp/b.txt")).isEqualTo("/tmp/a.txt");
        assertThat(expert.firstFilePath("随便聊聊")).isEmpty();
    }

    @Test
    void shouldNotMatchFilePathKeywordInsideChineseWords() {
        // 回归：关键词必须后跟分隔符，避免"路径规划""文件夹"等中文词内误匹配
        TestExpert expert = newExpert();
        assertThat(expert.filePaths("这是路径规划的任务")).isEmpty();
        assertThat(expert.filePaths("请整理文件夹结构")).isEmpty();
        assertThat(expert.firstFilePath("路径规划很重要")).isEmpty();
        // 但合法的带分隔符场景仍能提取
        assertThat(expert.filePaths("路径: /a.txt 和文件 /b.txt")).contains("/a.txt", "/b.txt");
    }

    @Test
    void shouldTruncateLongText() {
        TestExpert expert = newExpert();
        assertThat(expert.trunc("短文本", 10)).isEqualTo("短文本");
        assertThat(expert.trunc("超长文本内容", 4)).endsWith("...");
        assertThat(expert.trunc(null, 10)).isEmpty();
    }

    // ==================== 工具列表 ====================

    @Test
    void shouldReturnAllToolsWhenNoAnnotation() {
        when(toolRegistry.getToolDefinitions()).thenReturn(List.of(
                new ToolDefinition("read_file", "读取", Map.of(), true),
                new ToolDefinition("write_file", "写入", Map.of(), false)));

        assertThat(newExpert().tools()).hasSize(2);
    }

    // ==================== 会话级技能注入 ====================

    @Test
    void shouldInjectSessionSkillsIntoPrompt() {
        ExecutionContext ctx = new ExecutionContext(ExecutionContext.ExecutionStage.EXECUTING,
                "s1", "/ws", "seq-1");
        ctx.setSkillsPrompt("### 技能规范内容");
        ExecutionContextHolder.setContext(ctx);

        String prompt = newExpert().effective("基础提示词");
        assertThat(prompt).startsWith("基础提示词");
        assertThat(prompt).contains("## 本会话启用的技能规范");
        assertThat(prompt).contains("### 技能规范内容");
    }

    // ==================== ReAct 循环 ====================

    @Test
    void shouldRunReActLoopWithToolCalls() {
        when(toolRegistry.getToolDefinitions()).thenReturn(List.of());
        ToolCall call = new ToolCall("read_file", Map.of("path", "a.txt"));
        call.setId("call-1");
        when(llmPort.chat(any()))
                .thenReturn(new ChatResponse("先读取文件。", List.of(call), 10, 0, "stop"))
                .thenReturn(new ChatResponse("最终结果", List.of(), 10, 0, "stop"));
        when(toolRegistry.executeTool("read_file", Map.of("path", "a.txt"), "/ws"))
                .thenReturn(new ExecutionResult(true, "文件内容", null, 0, 0, null));

        String result = newExpert().reAct("sys", "user", "/ws", observer);

        assertThat(result).contains("先读取文件。").contains("最终结果");
        verify(toolRegistry).executeTool("read_file", Map.of("path", "a.txt"), "/ws");
        // 状态推送：ACTIVITY + STREAM_CHUNK
        verify(observer, atLeast(2)).onNext(any());
    }

    @Test
    void shouldHandleToolExecutionFailure() {
        when(toolRegistry.getToolDefinitions()).thenReturn(List.of());
        ToolCall call = new ToolCall("write_file", Map.of("path", "b.txt"));
        when(llmPort.chat(any()))
                .thenReturn(new ChatResponse("写文件。", List.of(call), 10, 0, "stop"))
                .thenReturn(new ChatResponse("完成", List.of(), 10, 0, "stop"));
        when(toolRegistry.executeTool(any(), any(), any()))
                .thenThrow(new RuntimeException("磁盘满"));

        String result = newExpert().reAct("sys", "user", "/ws", observer);

        // 工具失败不抛异常，继续循环
        assertThat(result).contains("完成");
        // 第二次请求的 history 中 tool 消息携带失败原因
        verify(llmPort, times(2)).chat(any());
    }

    @Test
    void shouldCapReActLoopAtMaxIterations() {
        when(toolRegistry.getToolDefinitions()).thenReturn(List.of());
        ToolCall call = new ToolCall("read_file", Map.of("path", "a.txt"));
        when(llmPort.chat(any()))
                .thenReturn(new ChatResponse("循环", List.of(call), 10, 0, "stop"));
        when(toolRegistry.executeTool(any(), any(), any()))
                .thenReturn(new ExecutionResult(true, "ok", null, 0, 0, null));

        String result = newExpert().reAct("sys", "user", "/ws", observer);

        assertThat(result).contains("达到最大工具调用轮次");
        verify(llmPort, times(10)).chat(any());
    }

    // ==================== callLlm 流式 ====================

    @Test
    void shouldCollectStreamedContent() {
        doAnswer(invocation -> {
            @SuppressWarnings("unchecked")
            StreamObserver<StreamChunk> inner = invocation.getArgument(1);
            inner.onNext(new StreamChunk(null, "STREAM_CHUNK", "块1", null));
            inner.onNext(new StreamChunk(null, "STREAM_CHUNK", "块2", null));
            inner.onComplete();
            return null;
        }).when(llmPort).chatStream(any(), any());

        String full = newExpert().llm("sys", "user", observer);

        assertThat(full).isEqualTo("块1块2");
        verify(observer, times(2)).onNext(any());
    }

    @Test
    void shouldReturnEmptyWhenStreamFailsBeforeAnyChunk() {
        doAnswer(invocation -> {
            @SuppressWarnings("unchecked")
            StreamObserver<StreamChunk> inner = invocation.getArgument(1);
            inner.onError(new RuntimeException("LLM 超时"));
            return null;
        }).when(llmPort).chatStream(any(), any());

        String full = newExpert().llm("sys", "user", observer);

        assertThat(full).isEmpty();
    }
}
