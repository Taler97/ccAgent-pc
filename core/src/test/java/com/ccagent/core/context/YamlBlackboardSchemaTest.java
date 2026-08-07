package com.ccagent.core.context;

import com.ccagent.api.context.BlackboardSchema;
import com.ccagent.api.context.FieldSchema;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * YamlBlackboardSchema 单元测试
 */
class YamlBlackboardSchemaTest {

    @Test
    void shouldLoadSchemaFromFile(@TempDir Path tmp) throws Exception {
        Path yaml = tmp.resolve("test-schema.yaml");
        java.nio.file.Files.writeString(yaml, ""
                + "fields:\n"
                + "  findings:\n"
                + "    type: list\n"
                + "    mergeStrategy: append\n"
                + "    description: \"关键发现\"\n"
                + "  summary:\n"
                + "    type: string\n"
                + "    mergeStrategy: replace\n"
                + "    description: \"摘要\"\n");

        BlackboardSchema schema = YamlBlackboardSchema.loadFromFile(yaml);

        assertThat(schema.isEmpty()).isFalse();
        assertThat(schema.getDefinedKeys()).containsExactlyInAnyOrder("findings", "summary");

        FieldSchema findings = schema.getField("findings");
        assertThat(findings.getType()).isEqualTo("list");
        assertThat(findings.isAppend()).isTrue();
        assertThat(findings.isReplace()).isFalse();

        FieldSchema summary = schema.getField("summary");
        assertThat(summary.getType()).isEqualTo("string");
        assertThat(summary.isReplace()).isTrue();
    }

    @Test
    void shouldReturnEmptyWhenClasspathFileMissing() {
        BlackboardSchema schema = YamlBlackboardSchema.loadFromClasspath("nonexistent-schema.yaml");
        assertThat(schema.isEmpty()).isTrue();
        assertThat(schema.getDefinedKeys()).isEmpty();
        assertThat(schema.getField("any")).isNull();
    }

    @Test
    void shouldLoadFromClasspath() {
        // blackboard-schema.yaml 在 api 模块的 resources 中, 通过传递依赖可见
        BlackboardSchema schema = YamlBlackboardSchema.loadFromClasspath("blackboard-schema.yaml");

        assertThat(schema.isEmpty()).isFalse();
        assertThat(schema.getDefinedKeys()).contains("findings", "relevant_files", "summary", "result");

        FieldSchema findings = schema.getField("findings");
        assertThat(findings.isAppend()).isTrue();

        FieldSchema decisions = schema.getField("decisions");
        assertThat(decisions.isMerge()).isTrue();

        FieldSchema result = schema.getField("result");
        assertThat(result.isReplace()).isTrue();
    }

    @Test
    void shouldReturnNullForUnknownKey() {
        BlackboardSchema schema = YamlBlackboardSchema.loadFromClasspath("blackboard-schema.yaml");
        assertThat(schema.getField("nonexistent_key")).isNull();
    }
}
