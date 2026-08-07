package com.ccagent.core.context;

import com.ccagent.api.context.BlackboardSchema;
import com.ccagent.api.context.FieldSchema;
import com.ccagent.api.model.plan.Plan;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * TaskContextImpl 缩略型黑板合并策略测试 (LangGraph 风格)
 * <p>验证 putArtifact 按 BlackboardSchema 的 mergeStrategy 合并:
 * <ul>
 *   <li>replace - 直接覆盖</li>
 *   <li>append  - 列表追加</li>
 *   <li>merge   - Map 合并</li>
 * </ul></p>
 */
class TaskContextImplMergeTest {

    @TempDir Path tempDir;

    private BlackboardSchema buildTestSchema() {
        return new BlackboardSchema() {
            @Override public FieldSchema getField(String key) {
                return switch (key) {
                    case "findings"   -> new FieldSchema("list", "append", "发现列表");
                    case "files"      -> new FieldSchema("list", "append", "文件列表");
                    case "summary"    -> new FieldSchema("string", "replace", "摘要");
                    case "decisions"  -> new FieldSchema("map", "merge", "决策");
                    default -> null;
                };
            }
            @Override public java.util.Set<String> getDefinedKeys() {
                return java.util.Set.of("findings", "files", "summary", "decisions");
            }
            @Override public boolean isEmpty() { return false; }
        };
    }

    private TaskContextImpl newTaskWithSchema() {
        Plan plan = new Plan("test", java.util.List.of(), java.util.List.of());
        return new TaskContextImpl("seq-merge", plan, new FileArtifactStore(),
                tempDir.toString(), buildTestSchema());
    }

    // ========== append 策略 ==========

    @Test
    void appendShouldAccumulateSingleValues() {
        TaskContextImpl task = newTaskWithSchema();

        task.putArtifact("findings", "发现1");
        task.putArtifact("findings", "发现2");
        task.putArtifact("findings", "发现3");

        @SuppressWarnings("unchecked")
        List<String> findings = (List<String>) task.<Object>getArtifact("findings").getNow(null);

        assertThat(findings).containsExactly("发现1", "发现2", "发现3");
    }

    @Test
    void appendShouldMergeListValues() {
        TaskContextImpl task = newTaskWithSchema();

        task.putArtifact("findings", List.of("发现1", "发现2"));
        task.putArtifact("findings", List.of("发现3"));

        @SuppressWarnings("unchecked")
        List<String> findings = (List<String>) task.<Object>getArtifact("findings").getNow(null);

        assertThat(findings).containsExactly("发现1", "发现2", "发现3");
    }

    @Test
    void appendShouldWorkAcrossSteps() {
        TaskContextImpl task = newTaskWithSchema();

        // 模拟 Step 1 写
        task.putArtifact("files", "file1.java");
        // 模拟 Step 2 写
        task.putArtifact("files", "file2.java");
        // 模拟 Step 3 写
        task.putArtifact("files", List.of("file3.java", "file4.java"));

        @SuppressWarnings("unchecked")
        List<String> files = (List<String>) task.<Object>getArtifact("files").getNow(null);

        assertThat(files).containsExactly("file1.java", "file2.java", "file3.java", "file4.java");
    }

    // ========== replace 策略 ==========

    @Test
    void replaceShouldOverwriteOldValue() {
        TaskContextImpl task = newTaskWithSchema();

        task.putArtifact("summary", "第一版摘要");
        task.putArtifact("summary", "第二版摘要");

        String summary = task.<String>getArtifact("summary").getNow(null);

        assertThat(summary).isEqualTo("第二版摘要");
    }

    // ========== merge 策略 ==========

    @Test
    void mergeShouldCombineMaps() {
        TaskContextImpl task = newTaskWithSchema();

        Map<String, Object> d1 = new LinkedHashMap<>();
        d1.put("framework", "Spring Boot");
        d1.put("java", "21");
        task.putArtifact("decisions", d1);

        Map<String, Object> d2 = new LinkedHashMap<>();
        d2.put("build", "Maven");
        d2.put("java", "21");  // 重复 key, 应该覆盖
        task.putArtifact("decisions", d2);

        @SuppressWarnings("unchecked")
        Map<String, Object> decisions = (Map<String, Object>) task.<Object>getArtifact("decisions").getNow(null);

        assertThat(decisions).hasSize(3);
        assertThat(decisions.get("framework")).isEqualTo("Spring Boot");
        assertThat(decisions.get("build")).isEqualTo("Maven");
        assertThat(decisions.get("java")).isEqualTo("21");
    }

    // ========== 无 schema 时走 fallback ==========

    @Test
    void shouldFallbackToReplaceWhenSchemaEmpty() {
        Plan plan = new Plan("test", java.util.List.of(), java.util.List.of());
        TaskContextImpl task = new TaskContextImpl("seq-empty", plan, new FileArtifactStore(),
                tempDir.toString(), BlackboardSchema.EMPTY);

        task.putArtifact("unknown_key", "value1");
        task.putArtifact("unknown_key", "value2");

        String value = task.<String>getArtifact("unknown_key").getNow(null);
        assertThat(value).isEqualTo("value2");
    }

    @Test
    void shouldFallbackToReplaceWhenKeyNotInSchema() {
        TaskContextImpl task = newTaskWithSchema();

        task.putArtifact("custom_key", "a");
        task.putArtifact("custom_key", "b");

        String value = task.<String>getArtifact("custom_key").getNow(null);
        assertThat(value).isEqualTo("b");
    }

    // ========== 多 Key 并行写入演示 (LangGraph 风格) ==========

    @Test
    void shouldSimulateLangGraphStyleWorkflow() {
        TaskContextImpl task = newTaskWithSchema();

        // Step 1: researcher 产出多 Key
        task.putArtifact("findings", "项目用 Java 21");
        task.putArtifact("findings", "PathUtil 有 bug");
        task.putArtifact("files", "PathUtil.java");
        task.putArtifact("summary", "分析完成");

        // Step 2: writer 消费上游, 产出自己的 Key
        task.putArtifact("findings", "修复方案已确认");  // append
        task.putArtifact("files", List.of("PathUtil.java", "PathUtilTest.java"));  // append
        task.putArtifact("summary", "修复完成");  // replace 覆盖

        // Step 3: summarizer 产出
        task.putArtifact("summary", "全部完成");  // replace 覆盖

        // 验证黑板最终状态
        @SuppressWarnings("unchecked")
        List<String> findings = (List<String>) task.<Object>getArtifact("findings").getNow(null);
        @SuppressWarnings("unchecked")
        List<String> files = (List<String>) task.<Object>getArtifact("files").getNow(null);
        String summary = task.<String>getArtifact("summary").getNow(null);

        assertThat(findings).containsExactly(
                "项目用 Java 21", "PathUtil 有 bug", "修复方案已确认");
        assertThat(files).containsExactly(
                "PathUtil.java", "PathUtil.java", "PathUtilTest.java");
        assertThat(summary).isEqualTo("全部完成");
    }
}
