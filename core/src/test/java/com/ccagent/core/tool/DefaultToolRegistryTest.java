package com.ccagent.core.tool;

import com.ccagent.api.model.response.ExecutionResult;
import com.ccagent.api.model.tool.ToolDefinition;
import com.ccagent.exception.security.SecurityException;
import com.ccagent.exception.tool.ToolException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class DefaultToolRegistryTest {

    @Mock
    ToolExecutor readExecutor;

    @Mock
    ToolExecutor writeExecutor;

    private DefaultToolRegistry registry;

    @BeforeEach
    void setUp() {
        registry = new DefaultToolRegistry();
    }

    // ==================== 注册与定义 ====================

    @Test
    void shouldRegisterAndExposeDefinitions() {
        when(readExecutor.getName()).thenReturn("read_file");
        when(readExecutor.getDescription()).thenReturn("读取文件");
        when(readExecutor.isReadOnly()).thenReturn(true);
        when(readExecutor.getParameters()).thenReturn(Map.of("path", Map.of("type", "string")));

        registry.register(readExecutor);

        List<ToolDefinition> defs = registry.getToolDefinitions();
        assertThat(defs).hasSize(1);
        ToolDefinition def = defs.get(0);
        assertThat(def.getName()).isEqualTo("read_file");
        assertThat(def.getDescription()).isEqualTo("读取文件");
        assertThat(def.isReadOnly()).isTrue();
        assertThat(def.getParameters()).containsKey("properties");
    }

    @Test
    void shouldListRegisteredToolNamesSorted() {
        when(writeExecutor.getName()).thenReturn("write_file");
        when(readExecutor.getName()).thenReturn("read_file");
        registry.register(writeExecutor);
        registry.register(readExecutor);

        assertThat(registry.getRegisteredToolNames()).containsExactly("read_file", "write_file");
    }

    // ==================== 正常执行 ====================

    @Test
    void shouldExecuteRegisteredTool() {
        when(readExecutor.getName()).thenReturn("read_file");
        registry.register(readExecutor);

        ExecutionResult result = new ExecutionResult(true, "ok", null, 0, 0, null);
        when(readExecutor.execute(any(), any())).thenReturn(result);

        ExecutionResult actual = registry.executeTool("read_file", Map.of(), "/workspace");

        assertThat(actual).isSameAs(result);
        verify(readExecutor).execute(any(), eq("/workspace"));
    }

    @Test
    void shouldThrowWhenToolNotFound() {
        assertThatThrownBy(() -> registry.executeTool("no_such_tool", Map.of(), "/workspace"))
                .isInstanceOf(ToolException.class)
                .satisfies(e -> assertThat(((ToolException) e).getToolName()).isEqualTo("no_such_tool"));
    }

    // ==================== 路径安全 ====================

    @Test
    void shouldRejectPathTraversal(@TempDir Path workspace) {
        when(readExecutor.getName()).thenReturn("read_file");
        registry.register(readExecutor);

        assertThatThrownBy(() -> registry.executeTool(
                "read_file", Map.of("path", "../etc/passwd"), workspace.toString()))
                .isInstanceOf(SecurityException.class);
        verify(readExecutor, never()).execute(any(), any());
    }

    @Test
    void shouldAllowPathInsideWorkspace(@TempDir Path workspace) {
        when(readExecutor.getName()).thenReturn("read_file");
        registry.register(readExecutor);

        ExecutionResult result = new ExecutionResult(true, "ok", null, 0, 0, null);
        when(readExecutor.execute(any(), any())).thenReturn(result);

        ExecutionResult actual = registry.executeTool(
                "read_file", Map.of("path", "src/main/Main.java"), workspace.toString());

        assertThat(actual).isSameAs(result);
    }

    @Test
    void shouldNotCheckPathWhenArgsNull() {
        when(readExecutor.getName()).thenReturn("read_file");
        registry.register(readExecutor);

        ExecutionResult result = new ExecutionResult(true, "ok", null, 0, 0, null);
        when(readExecutor.execute(any(), any())).thenReturn(result);

        assertThat(registry.executeTool("read_file", null, "/workspace")).isSameAs(result);
    }

    // ==================== 修复执行 ====================

    @Test
    void shouldMatchToolNameCaseInsensitively() {
        when(readExecutor.getName()).thenReturn("read_file");
        registry.register(readExecutor);

        ExecutionResult result = new ExecutionResult(true, "ok", null, 0, 0, null);
        when(readExecutor.execute(any(), any())).thenReturn(result);

        assertThat(registry.executeToolWithRepair("READ_FILE", "{}", "/workspace")).isSameAs(result);
    }

    @Test
    void shouldRepairUnclosedJsonArgs() {
        when(readExecutor.getName()).thenReturn("read_file");
        registry.register(readExecutor);

        ExecutionResult result = new ExecutionResult(true, "ok", null, 0, 0, null);
        when(readExecutor.execute(any(), any())).thenReturn(result);

        // 缺少末尾大括号，repair 应自动补全
        assertThat(registry.executeToolWithRepair("read_file", "{\"path\": \"a.txt\"", "/workspace"))
                .isSameAs(result);
        verify(readExecutor).execute(argThat(args -> "a.txt".equals(args.get("path"))), any());
    }

    @Test
    void shouldThrowWhenRepairFails() {
        when(readExecutor.getName()).thenReturn("read_file");
        registry.register(readExecutor);

        assertThatThrownBy(() -> registry.executeToolWithRepair("read_file", "not-a-json{{{", "/workspace"))
                .isInstanceOf(ToolException.class);
        verify(readExecutor, never()).execute(any(), any());
    }

    @Test
    void shouldThrowWhenToolNameCannotBeMatched() {
        when(readExecutor.getName()).thenReturn("read_file");
        registry.register(readExecutor);

        assertThatThrownBy(() -> registry.executeToolWithRepair("readfil", "{}", "/workspace"))
                .isInstanceOf(ToolException.class);
    }
}
