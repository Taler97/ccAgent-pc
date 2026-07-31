package com.ccagent.core.tool;

import org.junit.jupiter.api.Test;
import java.util.Map;
import java.util.Set;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ToolCallRepairerTest {

    private final ToolCallRepairer repairer = new ToolCallRepairer();
    private final Set<String> registeredTools = Set.of("read_file", "write_file", "list_directory");

    @Test
    void shouldParseValidJson() {
        Map<String, Object> args = repairer.repair("read_file", "{\"path\":\"/test\"}", registeredTools);
        assertThat(args).containsEntry("path", "/test");
    }

    @Test
    void shouldFixMissingBrackets() {
        Map<String, Object> args = repairer.repair("read_file", "{\"path\":\"/test\"", registeredTools);
        assertThat(args).containsEntry("path", "/test");
    }

    @Test
    void shouldFixTrailingComma() {
        Map<String, Object> args = repairer.repair("read_file", "{\"path\":\"/test\",}", registeredTools);
        assertThat(args).containsEntry("path", "/test");
    }

    @Test
    void shouldHandleSingleQuotes() {
        Map<String, Object> args = repairer.repair("read_file", "{'path':'/test'}", registeredTools);
        assertThat(args).containsEntry("path", "/test");
    }

    @Test
    void shouldThrowOnUnfixable() {
        assertThatThrownBy(() -> repairer.repair("read_file", "{{{{invalid", registeredTools))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void shouldHandleEmptyArgs() {
        Map<String, Object> args = repairer.repair("read_file", "", registeredTools);
        assertThat(args).isEmpty();
    }

    @Test
    void shouldHandleNullArgs() {
        Map<String, Object> args = repairer.repair("read_file", null, registeredTools);
        assertThat(args).isEmpty();
    }
}
