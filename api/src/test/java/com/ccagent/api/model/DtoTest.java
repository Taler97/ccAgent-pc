package com.ccagent.api.model;

import com.ccagent.api.model.envelope.Envelope;
import com.ccagent.api.model.request.*;
import com.ccagent.api.model.response.*;
import com.ccagent.api.model.plan.*;
import com.ccagent.api.model.memory.*;
import com.ccagent.api.model.tool.ToolDefinition;
import com.ccagent.api.model.mcp.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import static org.assertj.core.api.Assertions.assertThat;

class DtoTest {

    private final ObjectMapper mapper = new ObjectMapper()
            .findAndRegisterModules();

    @Test
    void shouldSerializeEnvelope() throws Exception {
        Envelope env = new Envelope("EXECUTE", "seq-1", "2026-01-01T00:00:00Z", "{\"key\":\"val\"}");
        String json = mapper.writeValueAsString(env);
        Envelope deserialized = mapper.readValue(json, Envelope.class);
        assertThat(deserialized.getType()).isEqualTo("EXECUTE");
        assertThat(deserialized.getSeqId()).isEqualTo("seq-1");
    }

    @Test
    void shouldSerializeExecuteRequest() throws Exception {
        ExecuteRequest req = new ExecuteRequest("ws-1", "sess-1", "hello", false);
        String json = mapper.writeValueAsString(req);
        ExecuteRequest deserialized = mapper.readValue(json, ExecuteRequest.class);
        assertThat(deserialized.getPrompt()).isEqualTo("hello");
        assertThat(deserialized.isAutoMode()).isFalse();
    }

    @Test
    void shouldSerializeChatRequest() throws Exception {
        ChatRequest req = new ChatRequest("sys", "user", List.of(), true, 4096, 0.7, 30);
        String json = mapper.writeValueAsString(req);
        ChatRequest deserialized = mapper.readValue(json, ChatRequest.class);
        assertThat(deserialized.getSystemPrompt()).isEqualTo("sys");
        assertThat(deserialized.getTemperature()).isEqualTo(0.7);
    }

    @Test
    void shouldSerializeChatResponse() throws Exception {
        ChatResponse resp = new ChatResponse("hello", null, 100, 50, "stop");
        String json = mapper.writeValueAsString(resp);
        ChatResponse deserialized = mapper.readValue(json, ChatResponse.class);
        assertThat(deserialized.getContent()).isEqualTo("hello");
        assertThat(deserialized.getTotalTokens()).isEqualTo(100);
        assertThat(deserialized.getCachedTokens()).isEqualTo(50);
    }

    @Test
    void shouldSerializePlan() throws Exception {
        Step step = new Step(1, "code-writer", "update file", List.of("src/Main.java"));
        Plan plan = new Plan("refactor", List.of(step), List.of("src/Main.java"));
        String json = mapper.writeValueAsString(plan);
        Plan deserialized = mapper.readValue(json, Plan.class);
        assertThat(deserialized.getSummary()).isEqualTo("refactor");
        assertThat(deserialized.getSteps()).hasSize(1);
        assertThat(deserialized.getSteps().get(0).getExpertName()).isEqualTo("code-writer");
    }

    @Test
    void shouldSerializeToolCall() throws Exception {
        ToolCall tc = new ToolCall("read_file", Map.of("path", "/test.txt"));
        String json = mapper.writeValueAsString(tc);
        ToolCall deserialized = mapper.readValue(json, ToolCall.class);
        assertThat(deserialized.getName()).isEqualTo("read_file");
        assertThat(deserialized.getArguments()).containsEntry("path", "/test.txt");
    }

    @Test
    void shouldSerializeExecutionResult() throws Exception {
        ExecutionResult result = new ExecutionResult(true, "ok", "/path", 10, 500, "backup-1");
        String json = mapper.writeValueAsString(result);
        ExecutionResult deserialized = mapper.readValue(json, ExecutionResult.class);
        assertThat(deserialized.isSuccess()).isTrue();
        assertThat(deserialized.getLinesChanged()).isEqualTo(10);
    }

    @Test
    void shouldSerializeStructuredContext() throws Exception {
        ConversationTurn turn = new ConversationTurn("user", "hello", 10);
        StructuredContext ctx = new StructuredContext("summary", List.of(turn), 5);
        String json = mapper.writeValueAsString(ctx);
        StructuredContext deserialized = mapper.readValue(json, StructuredContext.class);
        assertThat(deserialized.getSummary()).isEqualTo("summary");
        assertThat(deserialized.getTotalTurns()).isEqualTo(5);
        assertThat(deserialized.getRecentTurns()).hasSize(1);
    }

    @Test
    void shouldSerializeToolDefinition() throws Exception {
        ToolDefinition def = new ToolDefinition("read_file", "read files", Map.of("type", "object"), true);
        String json = mapper.writeValueAsString(def);
        ToolDefinition deserialized = mapper.readValue(json, ToolDefinition.class);
        assertThat(deserialized.getName()).isEqualTo("read_file");
        assertThat(deserialized.isReadOnly()).isTrue();
    }

    @Test
    void shouldSerializeMcpTool() throws Exception {
        McpTool tool = new McpTool("list_dir", "list directory", Map.of("type", "object"));
        String json = mapper.writeValueAsString(tool);
        McpTool deserialized = mapper.readValue(json, McpTool.class);
        assertThat(deserialized.getName()).isEqualTo("list_dir");
    }

    @Test
    void shouldSerializeStreamChunk() throws Exception {
        StreamChunk chunk = new StreamChunk("seq-1", "STEP_STATUS", "content", "stop");
        String json = mapper.writeValueAsString(chunk);
        StreamChunk deserialized = mapper.readValue(json, StreamChunk.class);
        assertThat(deserialized.getType()).isEqualTo("STEP_STATUS");
        assertThat(deserialized.getFinishReason()).isEqualTo("stop");
    }

    @Test
    void shouldSerializeFileNode() throws Exception {
        FileNode node = new FileNode("test.java", false, 1024, Instant.parse("2026-01-01T00:00:00Z"));
        String json = mapper.writeValueAsString(node);
        FileNode deserialized = mapper.readValue(json, FileNode.class);
        assertThat(deserialized.getName()).isEqualTo("test.java");
        assertThat(deserialized.getSize()).isEqualTo(1024);
    }

    @Test
    void shouldSerializeUserPreferences() throws Exception {
        UserPreferences prefs = new UserPreferences("java", "google", "no lombok");
        String json = mapper.writeValueAsString(prefs);
        UserPreferences deserialized = mapper.readValue(json, UserPreferences.class);
        assertThat(deserialized.getTechStack()).isEqualTo("java");
    }
}
