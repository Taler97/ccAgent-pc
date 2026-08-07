package com.ccagent.plugin.tools.impl;

import com.ccagent.core.tool.DefaultToolRegistry;
import com.ccagent.exception.security.SecurityException;
import com.ccagent.exception.tool.ToolException;
import com.ccagent.infrastructure.util.PathUtil;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import java.util.Map;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DefaultToolRegistryTest {

    private DefaultToolRegistry registry;

    @BeforeEach
    void setUp() {
        registry = new DefaultToolRegistry();
        registry.register(new ReadFileTool());
        registry.register(new WriteFileTool());
        registry.register(new ListDirectoryTool());
    }

    @Test
    void shouldReturnToolDefinitionsInAlphabeticalOrder() {
        var defs = registry.getToolDefinitions();
        assertThat(defs).hasSize(3);
        assertThat(defs.get(0).getName()).isEqualTo("list_directory");
        assertThat(defs.get(1).getName()).isEqualTo("read_file");
        assertThat(defs.get(2).getName()).isEqualTo("write_file");
    }

    @Test
    void shouldThrowOnUnknownTool() {
        assertThatThrownBy(() -> registry.executeTool("unknown", Map.of(), "/ws"))
                .isInstanceOf(ToolException.class);
    }

    @Test
    void shouldRejectPathOutsideWorkspace() {
        assertThatThrownBy(() -> registry.executeTool("read_file",
                Map.of("path", "/nonexistent"), "/ws"))
                .isInstanceOf(SecurityException.class);
    }

    @Test
    void shouldAllowPathInsideWorkspace() {
        assertThatThrownBy(() -> registry.executeTool("read_file",
                Map.of("path", "nonexistent"), "/ws"))
                .isInstanceOf(ToolException.class);
    }

    @Test
    void shouldValidateSourceAndTargetKeys() {
        // copy/move 类工具使用 source/target 键，同样走路径校验
        assertThatThrownBy(() -> registry.executeTool("read_file",
                Map.of("source", "/nonexistent"), "/ws"))
                .isInstanceOf(SecurityException.class);
        assertThatThrownBy(() -> registry.executeTool("read_file",
                Map.of("target", "/nonexistent"), "/ws"))
                .isInstanceOf(SecurityException.class);
    }

    @Test
    void shouldAllowWhitelistedPathOutsideWorkspace() {
        PathUtil.setAllowedPaths(java.util.List.of(java.io.File.separator + "shared"));
        try {
            // 白名单内路径通过校验，进入工具执行（文件不存在 → ToolException）
            assertThatThrownBy(() -> registry.executeTool("read_file",
                    Map.of("path", java.io.File.separator + "shared" + java.io.File.separator + "nonexistent"), "/ws"))
                    .isInstanceOf(ToolException.class);
        } finally {
            PathUtil.setAllowedPaths(java.util.List.of());
        }
    }
}