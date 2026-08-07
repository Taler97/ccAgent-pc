package com.ccagent.plugin.experts.impl;

import com.ccagent.api.model.plan.Plan;
import com.ccagent.api.model.plan.Step;
import com.ccagent.api.model.request.ChatRequest;
import com.ccagent.api.model.response.ChatResponse;
import com.ccagent.api.model.response.StreamChunk;
import com.ccagent.api.model.tool.ToolDefinition;
import com.ccagent.api.port.LlmPort;
import com.ccagent.api.port.StreamObserver;
import com.ccagent.api.port.ToolRegistryPort;
import com.ccagent.infrastructure.util.JsonUtil;
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
import static org.mockito.Mockito.*;

/**
 * PlannerExpert 单元测试
 * <p>覆盖核心执行流程、JSON 提取、回退策略、工具列表和提示词结构。</p>
 */
@ExtendWith(MockitoExtension.class)
class PlannerExpertTest {

    @Mock
    LlmPort llmPort;

    @Mock
    ToolRegistryPort toolRegistry;

    @Mock
    StreamObserver<StreamChunk> observer;

    private PlannerExpert planner;

    @BeforeEach
    void setUp() {
        planner = new PlannerExpert(toolRegistry, llmPort);
    }

    // ==================== 基本元信息 ====================

    @Test
    void shouldReturnPlannerName() {
        assertThat(planner.getName()).isEqualTo("planner");
    }

    @Test
    void shouldReturnPlannerDescription() {
        assertThat(planner.getDescription()).contains("任务规划师");
    }

    // ==================== 正常流程 ====================

    @Test
    void shouldGenerateSimplePlanFromPlainJson() {
        String planJson = """
                {
                  "summary": "分析架构并生成文档",
                  "estimatedImpact": ["README.md"],
                  "steps": [
                    {
                      "id": 1,
                      "expertName": "researcher",
                      "task": "分析项目模块依赖",
                      "affectedFiles": ["pom.xml"],
                      "reads": [],
                      "writes": ["analysis"],
                      "persistOutput": false
                    }
                  ]
                }
                """;

        when(toolRegistry.getToolDefinitions()).thenReturn(List.of());
        when(llmPort.chat(any())).thenReturn(
                new ChatResponse(planJson, null, 100, 0, "stop"));

        String result = planner.executeLegacy("分析架构", null, Map.of(), "/workspace", observer);

        Plan plan = JsonUtil.fromJson(result, Plan.class);
        assertThat(plan.getSummary()).isEqualTo("分析架构并生成文档");
        assertThat(plan.getEstimatedImpact()).containsExactly("README.md");
        assertThat(plan.getSteps()).hasSize(1);

        Step step = plan.getSteps().get(0);
        assertThat(step.getId()).isEqualTo(1);
        assertThat(step.getExpertName()).isEqualTo("researcher");
        assertThat(step.getTask()).isEqualTo("分析项目模块依赖");
        assertThat(step.getAffectedFiles()).containsExactly("pom.xml");
        assertThat(step.getWrites()).contains("analysis");
        assertThat(step.isPersistOutput()).isFalse();

        verify(observer, atLeast(2)).onNext(any());
    }

    @Test
    void shouldGeneratePlanFromMarkdownCodeBlock() {
        String planJson = """
                ```json
                {
                  "summary": "修复空指针异常",
                  "estimatedImpact": ["UserService.java"],
                  "steps": [
                    {
                      "id": 1,
                      "expertName": "writer",
                      "task": "修复 UserService.java 中的空指针检查",
                      "affectedFiles": ["src/main/java/com/example/UserService.java"]
                    }
                  ]
                }
                ```
                """;

        when(toolRegistry.getToolDefinitions()).thenReturn(List.of());
        when(llmPort.chat(any())).thenReturn(
                new ChatResponse(planJson, null, 100, 0, "stop"));

        String result = planner.executeLegacy("修复空指针", null, Map.of(), "/workspace", observer);

        Plan plan = JsonUtil.fromJson(result, Plan.class);
        assertThat(plan.getSummary()).isEqualTo("修复空指针异常");
        assertThat(plan.getSteps()).hasSize(1);
    }

    @Test
    void shouldHandleMultiStepPlanWithDependencies() {
        String planJson = """
                {
                  "summary": "添加用户注册功能",
                  "estimatedImpact": ["UserController.java", "UserService.java"],
                  "steps": [
                    {
                      "id": 1,
                      "expertName": "researcher",
                      "task": "分析现有 UserController 代码结构",
                      "affectedFiles": ["UserController.java"],
                      "writes": ["analysis"]
                    },
                    {
                      "id": 2,
                      "expertName": "writer",
                      "task": "实现用户注册接口",
                      "affectedFiles": ["UserController.java", "UserService.java"],
                      "reads": ["analysis"],
                      "writes": ["code"],
                      "persistOutput": true
                    },
                    {
                      "id": 3,
                      "expertName": "summarizer",
                      "task": "总结代码变更",
                      "affectedFiles": [],
                      "reads": ["code"],
                      "writes": ["summary"]
                    }
                  ]
                }
                """;

        when(toolRegistry.getToolDefinitions()).thenReturn(List.of());
        when(llmPort.chat(any())).thenReturn(
                new ChatResponse(planJson, null, 200, 0, "stop"));

        String result = planner.executeLegacy("添加用户注册功能", null, Map.of(), "/workspace", observer);

        Plan plan = JsonUtil.fromJson(result, Plan.class);
        assertThat(plan.getSteps()).hasSize(3);

        Step step1 = plan.getSteps().get(0);
        assertThat(step1.getId()).isEqualTo(1);
        assertThat(step1.getExpertName()).isEqualTo("researcher");
        assertThat(step1.getWrites()).contains("analysis");
        assertThat(step1.getReads()).isNullOrEmpty();

        Step step2 = plan.getSteps().get(1);
        assertThat(step2.getId()).isEqualTo(2);
        assertThat(step2.getExpertName()).isEqualTo("writer");
        assertThat(step2.getReads()).contains("analysis");
        assertThat(step2.getWrites()).contains("code");
        assertThat(step2.isPersistOutput()).isTrue();

        Step step3 = plan.getSteps().get(2);
        assertThat(step3.getId()).isEqualTo(3);
        assertThat(step3.getExpertName()).isEqualTo("summarizer");
        assertThat(step3.getReads()).contains("code");
        assertThat(step3.getWrites()).contains("summary");
    }

    // ==================== 异常/回退流程 ====================

    @Test
    void shouldReturnFallbackWhenLlmReturnsNonJsonContent() {
        when(toolRegistry.getToolDefinitions()).thenReturn(List.of());
        when(llmPort.chat(any())).thenReturn(
                new ChatResponse("抱歉，我没有足够的信息来制定计划", null, 10, 0, "stop"));

        String result = planner.executeLegacy("xxx", null, Map.of(), "/workspace", observer);

        Plan fallback = JsonUtil.fromJson(result, Plan.class);
        assertThat(fallback.getSummary()).isEqualTo("规划失败");
        assertThat(fallback.getSteps()).isEmpty();
    }

    @Test
    void shouldReturnFallbackWhenLlmReturnsEmpty() {
        when(toolRegistry.getToolDefinitions()).thenReturn(List.of());
        when(llmPort.chat(any())).thenReturn(
                new ChatResponse("", null, 0, 0, "stop"));

        String result = planner.executeLegacy("xxx", null, Map.of(), "/workspace", observer);

        Plan fallback = JsonUtil.fromJson(result, Plan.class);
        assertThat(fallback.getSummary()).isEqualTo("规划失败");
        assertThat(fallback.getSteps()).isEmpty();
    }

    @Test
    void shouldReturnFallbackWhenLlmReturnsOnlyWhitespace() {
        when(toolRegistry.getToolDefinitions()).thenReturn(List.of());
        when(llmPort.chat(any())).thenReturn(
                new ChatResponse("   \n  \t  ", null, 0, 0, "stop"));

        String result = planner.executeLegacy("xxx", null, Map.of(), "/workspace", observer);

        Plan fallback = JsonUtil.fromJson(result, Plan.class);
        assertThat(fallback.getSummary()).isEqualTo("规划失败");
        assertThat(fallback.getSteps()).isEmpty();
    }

    @Test
    void shouldReturnFallbackWhenJsonIsMalformed() {
        when(toolRegistry.getToolDefinitions()).thenReturn(List.of());
        when(llmPort.chat(any())).thenReturn(
                new ChatResponse("{summary: missing quotes}", null, 10, 0, "stop"));

        String result = planner.executeLegacy("xxx", null, Map.of(), "/workspace", observer);

        Plan fallback = JsonUtil.fromJson(result, Plan.class);
        assertThat(fallback.getSummary()).isEqualTo("规划失败");
        assertThat(fallback.getSteps()).isEmpty();
    }

    // ==================== 工具配置 ====================

    @Test
    void shouldOnlyUseReadOnlyToolsInChatRequest() {
        ToolDefinition readFile = new ToolDefinition("read_file", "读取文件", Map.of(), true);
        ToolDefinition searchContent = new ToolDefinition("search_content", "搜索内容", Map.of(), true);
        ToolDefinition listDir = new ToolDefinition("list_directory", "列出目录", Map.of(), true);
        ToolDefinition writeFile = new ToolDefinition("write_file", "写入文件", Map.of(), false);
        ToolDefinition editFile = new ToolDefinition("edit_file", "编辑文件", Map.of(), false);

        when(toolRegistry.getToolDefinitions()).thenReturn(
                List.of(readFile, writeFile, editFile, searchContent, listDir));
        when(llmPort.chat(any())).thenReturn(
                new ChatResponse("{}", null, 10, 0, "stop"));

        planner.execute("test", null, Map.of(), "/workspace", observer);

        ArgumentCaptor<ChatRequest> captor = ArgumentCaptor.forClass(ChatRequest.class);
        verify(llmPort).chat(captor.capture());

        List<ToolDefinition> toolsInRequest = captor.getValue().getTools();
        assertThat(toolsInRequest)
                .extracting(ToolDefinition::getName)
                .containsExactlyInAnyOrder("read_file", "search_content", "list_directory");
        assertThat(toolsInRequest).hasSize(3);
    }

    // ==================== 提示词结构 ====================

    @Test
    void shouldIncludeAllExpertRolesInSystemPrompt() {
        when(toolRegistry.getToolDefinitions()).thenReturn(List.of());
        when(llmPort.chat(any())).thenReturn(
                new ChatResponse("{}", null, 10, 0, "stop"));

        planner.execute("test", null, Map.of(), "/workspace", observer);

        ArgumentCaptor<ChatRequest> captor = ArgumentCaptor.forClass(ChatRequest.class);
        verify(llmPort).chat(captor.capture());

        String systemPrompt = captor.getValue().getSystemPrompt();
        assertThat(systemPrompt).contains("任务规划师");
        assertThat(systemPrompt).contains("writer");
        assertThat(systemPrompt).contains("researcher");
        assertThat(systemPrompt).contains("organizer");
        assertThat(systemPrompt).contains("note-taker");
        assertThat(systemPrompt).contains("summarizer");
        assertThat(systemPrompt).contains("read_file");
        assertThat(systemPrompt).contains("search_content");
        assertThat(systemPrompt).contains("list_directory");
        assertThat(systemPrompt).contains("expertName");
        assertThat(systemPrompt).contains("affectedFiles");
    }

    @Test
    void shouldPassUserPromptCorrectly() {
        when(toolRegistry.getToolDefinitions()).thenReturn(List.of());
        when(llmPort.chat(any())).thenReturn(
                new ChatResponse("{}", null, 10, 0, "stop"));

        String task = "重构用户模块，拆分为多个服务类";
        planner.execute(task, null, Map.of(), "/workspace", observer);

        ArgumentCaptor<ChatRequest> captor = ArgumentCaptor.forClass(ChatRequest.class);
        verify(llmPort).chat(captor.capture());

        List<Map<String, Object>> history = captor.getValue().getHistoryMessages();
        assertThat(history).isNotEmpty();
        assertThat(history.get(0))
                .containsEntry("role", "user")
                .containsEntry("content", "请为以下任务生成执行计划：\n\n" + task);
    }
}