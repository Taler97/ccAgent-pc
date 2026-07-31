package com.ccagent.plugin.tools.impl;

import com.ccagent.api.model.response.ExecutionResult;
import com.ccagent.exception.tool.ToolException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ToolImplementationTest {

    // ─── list_directory ────────────────────────────────────────────────

    @Test
    void listDirectoryShouldReturnEntries(@TempDir Path tempDir) throws Exception {
        Files.createFile(tempDir.resolve("a.txt"));
        Files.createDirectory(tempDir.resolve("subdir"));
        ListDirectoryTool tool = new ListDirectoryTool();
        ExecutionResult result = tool.execute(Map.of("path", tempDir.toString()), tempDir.toString());
        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getMessage()).contains("a.txt");
        assertThat(result.getMessage()).contains("[DIR]");
    }

    @Test
    void listDirectoryRecursiveShouldReturnNestedFiles(@TempDir Path tempDir) throws Exception {
        Files.createDirectories(tempDir.resolve("a/b"));
        Files.createFile(tempDir.resolve("a/b/c.txt"));
        ListDirectoryTool tool = new ListDirectoryTool();
        ExecutionResult result = tool.execute(
                Map.of("path", tempDir.toString(), "recursive", true),
                tempDir.toString());
        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getMessage()).contains("a/b/c.txt");
    }

    @Test
    void listDirectoryWithIncludeFilter(@TempDir Path tempDir) throws Exception {
        Files.createFile(tempDir.resolve("a.java"));
        Files.createFile(tempDir.resolve("b.txt"));
        ListDirectoryTool tool = new ListDirectoryTool();
        ExecutionResult result = tool.execute(
                Map.of("path", tempDir.toString(), "include", "*.java"),
                tempDir.toString());
        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getMessage()).contains("a.java");
        assertThat(result.getMessage()).doesNotContain("b.txt");
    }

    @Test
    void listDirectoryWithExcludeFilter(@TempDir Path tempDir) throws Exception {
        Files.createFile(tempDir.resolve("a.java"));
        Files.createFile(tempDir.resolve("b.txt"));
        ListDirectoryTool tool = new ListDirectoryTool();
        ExecutionResult result = tool.execute(
                Map.of("path", tempDir.toString(), "exclude", "*.txt"),
                tempDir.toString());
        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getMessage()).contains("a.java");
        assertThat(result.getMessage()).doesNotContain("b.txt");
    }

    // ─── read_file ────────────────────────────────────────────────────

    @Test
    void readFileShouldReturnContent(@TempDir Path tempDir) throws Exception {
        Path file = tempDir.resolve("test.txt");
        Files.writeString(file, "hello world");
        ReadFileTool tool = new ReadFileTool();
        ExecutionResult result = tool.execute(Map.of("path", file.toString()), tempDir.toString());
        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getMessage()).isEqualTo("hello world");
    }

    @Test
    void readFileWithLineRange(@TempDir Path tempDir) throws Exception {
        Path file = tempDir.resolve("test.txt");
        String content = "line1\nline2\nline3\nline4\nline5";
        Files.writeString(file, content);
        ReadFileTool tool = new ReadFileTool();
        ExecutionResult result = tool.execute(
                Map.of("path", file.toString(), "start_line", 2, "end_line", 4),
                tempDir.toString());
        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getMessage()).contains("line2");
        assertThat(result.getMessage()).contains("line3");
        assertThat(result.getMessage()).contains("line4");
        assertThat(result.getMessage()).doesNotContain("line1");
        assertThat(result.getMessage()).doesNotContain("line5");
    }

    @Test
    void readFileStartLineOnly(@TempDir Path tempDir) throws Exception {
        Path file = tempDir.resolve("test.txt");
        StringBuilder sb = new StringBuilder();
        for (int i = 1; i <= 3000; i++) {
            sb.append("line").append(i).append("\n");
        }
        Files.writeString(file, sb.toString().trim());
        ReadFileTool tool = new ReadFileTool();
        ExecutionResult result = tool.execute(
                Map.of("path", file.toString(), "start_line", 100),
                tempDir.toString());
        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getMessage()).contains("line100");
    }

    // ─── write_file ───────────────────────────────────────────────────

    @Test
    void writeFileShouldCreateFile(@TempDir Path tempDir) throws Exception {
        Path file = tempDir.resolve("new.txt");
        WriteFileTool tool = new WriteFileTool();
        ExecutionResult result = tool.execute(
                Map.of("path", file.toString(), "content", "test content"),
                tempDir.toString());
        assertThat(result.isSuccess()).isTrue();
        assertThat(Files.readString(file)).isEqualTo("test content");
    }

    @Test
    void writeFileShouldCreateParentDirectories(@TempDir Path tempDir) throws Exception {
        Path file = tempDir.resolve("a/b/c/deep.txt");
        WriteFileTool tool = new WriteFileTool();
        ExecutionResult result = tool.execute(
                Map.of("path", file.toString(), "content", "deep"),
                tempDir.toString());
        assertThat(result.isSuccess()).isTrue();
        assertThat(Files.exists(file)).isTrue();
    }

    // ─── edit_file ────────────────────────────────────────────────────

    @Test
    void editFileReplaceMode(@TempDir Path tempDir) throws Exception {
        Path file = tempDir.resolve("test.txt");
        Files.writeString(file, "hello world");
        EditFileTool tool = new EditFileTool();
        ExecutionResult result = tool.execute(
                Map.of("path", file.toString(), "mode", "replace",
                        "target", "world", "replacement", "there"),
                tempDir.toString());
        assertThat(result.isSuccess()).isTrue();
        assertThat(Files.readString(file)).isEqualTo("hello there");
    }

    @Test
    void editFileReplaceWithMultiLineTarget(@TempDir Path tempDir) throws Exception {
        Path file = tempDir.resolve("test.txt");
        Files.writeString(file, "line1\nline2\nline3\nline4");
        EditFileTool tool = new EditFileTool();
        ExecutionResult result = tool.execute(
                Map.of("path", file.toString(), "mode", "replace",
                        "target", "line2\nline3", "replacement", "middle"),
                tempDir.toString());
        assertThat(result.isSuccess()).isTrue();
        assertThat(Files.readString(file)).isEqualTo("line1\nmiddle\nline4");
    }

    @Test
    void editFileReplaceTargetNotFoundShouldThrow(@TempDir Path tempDir) throws Exception {
        Path file = tempDir.resolve("test.txt");
        Files.writeString(file, "hello world");
        EditFileTool tool = new EditFileTool();
        assertThatThrownBy(() -> tool.execute(
                Map.of("path", file.toString(), "mode", "replace",
                        "target", "nonexist", "replacement", "x"),
                tempDir.toString())).isInstanceOf(ToolException.class);
    }

    @Test
    void editFileReplaceAmbiguousTargetShouldThrow(@TempDir Path tempDir) throws Exception {
        Path file = tempDir.resolve("test.txt");
        Files.writeString(file, "foo bar foo");
        EditFileTool tool = new EditFileTool();
        assertThatThrownBy(() -> tool.execute(
                Map.of("path", file.toString(), "mode", "replace",
                        "target", "foo", "replacement", "x"),
                tempDir.toString())).isInstanceOf(ToolException.class);
    }

    @Test
    void editFileInsertByLineNumber(@TempDir Path tempDir) throws Exception {
        Path file = tempDir.resolve("test.txt");
        Files.writeString(file, "line1\nline3");
        EditFileTool tool = new EditFileTool();
        ExecutionResult result = tool.execute(
                Map.of("path", file.toString(), "mode", "insert",
                        "line", 2, "content", "line2"),
                tempDir.toString());
        assertThat(result.isSuccess()).isTrue();
        assertThat(Files.readString(file)).isEqualTo("line1\nline2\nline3");
    }

    @Test
    void editFileInsertByInsertAfter(@TempDir Path tempDir) throws Exception {
        Path file = tempDir.resolve("test.txt");
        Files.writeString(file, "public class Foo {\n    int x;\n}");
        EditFileTool tool = new EditFileTool();
        ExecutionResult result = tool.execute(
                Map.of("path", file.toString(), "mode", "insert",
                        "insert_after", "int x;", "content", "    int y;"),
                tempDir.toString());
        assertThat(result.isSuccess()).isTrue();
        assertThat(Files.readString(file)).contains("int y;");
    }

    @Test
    void editFileDeleteByLineRange(@TempDir Path tempDir) throws Exception {
        Path file = tempDir.resolve("test.txt");
        Files.writeString(file, "line1\nline2\nline3\nline4");
        EditFileTool tool = new EditFileTool();
        ExecutionResult result = tool.execute(
                Map.of("path", file.toString(), "mode", "delete",
                        "start_line", 2, "end_line", 3),
                tempDir.toString());
        assertThat(result.isSuccess()).isTrue();
        assertThat(Files.readString(file)).isEqualTo("line1\nline4");
    }

    @Test
    void editFileDeleteByTarget(@TempDir Path tempDir) throws Exception {
        Path file = tempDir.resolve("test.txt");
        Files.writeString(file, "keep\nREMOVE_ME\nkeep");
        EditFileTool tool = new EditFileTool();
        ExecutionResult result = tool.execute(
                Map.of("path", file.toString(), "mode", "delete",
                        "target", "REMOVE_ME"),
                tempDir.toString());
        assertThat(result.isSuccess()).isTrue();
        assertThat(Files.readString(file)).isEqualTo("keep\nkeep");
    }

    @Test
    void editFileInvalidModeShouldThrow(@TempDir Path tempDir) throws Exception {
        Path file = tempDir.resolve("test.txt");
        Files.writeString(file, "test");
        EditFileTool tool = new EditFileTool();
        assertThatThrownBy(() -> tool.execute(
                Map.of("path", file.toString(), "mode", "invalid"),
                tempDir.toString())).isInstanceOf(ToolException.class);
    }

    // ─── file_operation ───────────────────────────────────────────────

    @Test
    void fileOperationCopyFile(@TempDir Path tempDir) throws Exception {
        Path src = tempDir.resolve("src.txt");
        Files.writeString(src, "hello");
        FileOperationTool tool = new FileOperationTool();
        ExecutionResult result = tool.execute(
                Map.of("operation", "copy", "source", "src.txt", "target", "dst.txt"),
                tempDir.toString());
        assertThat(result.isSuccess()).isTrue();
        assertThat(Files.readString(tempDir.resolve("dst.txt"))).isEqualTo("hello");
    }

    @Test
    void fileOperationMoveFile(@TempDir Path tempDir) throws Exception {
        Path src = tempDir.resolve("old.txt");
        Files.writeString(src, "moved");
        FileOperationTool tool = new FileOperationTool();
        ExecutionResult result = tool.execute(
                Map.of("operation", "move", "source", "old.txt", "target", "new.txt"),
                tempDir.toString());
        assertThat(result.isSuccess()).isTrue();
        assertThat(Files.notExists(src)).isTrue();
        assertThat(Files.readString(tempDir.resolve("new.txt"))).isEqualTo("moved");
    }

    @Test
    void fileOperationDeleteFile(@TempDir Path tempDir) throws Exception {
        Path file = tempDir.resolve("todelete.txt");
        Files.writeString(file, "bye");
        FileOperationTool tool = new FileOperationTool();
        ExecutionResult result = tool.execute(
                Map.of("operation", "delete", "path", "todelete.txt"),
                tempDir.toString());
        assertThat(result.isSuccess()).isTrue();
        assertThat(Files.notExists(file)).isTrue();
    }

    @Test
    void fileOperationMkdir(@TempDir Path tempDir) throws Exception {
        FileOperationTool tool = new FileOperationTool();
        ExecutionResult result = tool.execute(
                Map.of("operation", "mkdir", "path", "a/b/c"),
                tempDir.toString());
        assertThat(result.isSuccess()).isTrue();
        assertThat(Files.isDirectory(tempDir.resolve("a/b/c"))).isTrue();
    }

    @Test
    void fileOperationStat(@TempDir Path tempDir) throws Exception {
        Path file = tempDir.resolve("stat.txt");
        Files.writeString(file, "stats");
        FileOperationTool tool = new FileOperationTool();
        ExecutionResult result = tool.execute(
                Map.of("operation", "stat", "path", "stat.txt"),
                tempDir.toString());
        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getMessage()).contains("类型: file");
        assertThat(result.getMessage()).contains("大小: 5 bytes");
        assertThat(result.getMessage()).contains("创建时间:");
        assertThat(result.getMessage()).contains("修改时间:");
    }

    @Test
    void fileOperationCopyDirectory(@TempDir Path tempDir) throws Exception {
        Files.createDirectories(tempDir.resolve("src/sub"));
        Files.writeString(tempDir.resolve("src/a.txt"), "a");
        Files.writeString(tempDir.resolve("src/sub/b.txt"), "b");
        FileOperationTool tool = new FileOperationTool();
        ExecutionResult result = tool.execute(
                Map.of("operation", "copy", "source", "src", "target", "dst"),
                tempDir.toString());
        assertThat(result.isSuccess()).isTrue();
        assertThat(Files.readString(tempDir.resolve("dst/a.txt"))).isEqualTo("a");
        assertThat(Files.readString(tempDir.resolve("dst/sub/b.txt"))).isEqualTo("b");
    }

    @Test
    void fileOperationInvalidOperationShouldThrow(@TempDir Path tempDir) {
        FileOperationTool tool = new FileOperationTool();
        assertThatThrownBy(() -> tool.execute(
                Map.of("operation", "invalid", "path", "."),
                tempDir.toString())).isInstanceOf(ToolException.class);
    }

    // ─── search_content ───────────────────────────────────────────────

    @Test
    void searchContentShouldFindMatches(@TempDir Path tempDir) throws Exception {
        Path file = tempDir.resolve("test.txt");
        Files.writeString(file, "hello world\nfoo bar\nhello again");
        SearchContentTool tool = new SearchContentTool();
        ExecutionResult result = tool.execute(
                Map.of("pattern", "hello", "path", tempDir.toString()),
                tempDir.toString());
        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getMessage()).contains("hello world");
        assertThat(result.getMessage()).contains("hello again");
        assertThat(result.getMessage()).contains("找到 2 处匹配");
    }

    @Test
    void searchContentWithFilePatternFilter(@TempDir Path tempDir) throws Exception {
        Files.writeString(tempDir.resolve("a.java"), "public class A {}");
        Files.writeString(tempDir.resolve("b.txt"), "hello");
        SearchContentTool tool = new SearchContentTool();
        ExecutionResult result = tool.execute(
                Map.of("pattern", "hello", "path", tempDir.toString(),
                        "file_pattern", "*.java"),
                tempDir.toString());
        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getMessage()).contains("未找到匹配内容");
    }

    @Test
    void searchContentWithContextLines(@TempDir Path tempDir) throws Exception {
        Path file = tempDir.resolve("test.txt");
        Files.writeString(file, "line1\nline2\nTARGET\nline4\nline5");
        SearchContentTool tool = new SearchContentTool();
        ExecutionResult result = tool.execute(
                Map.of("pattern", "TARGET", "path", tempDir.toString(),
                        "context_lines", 1),
                tempDir.toString());
        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getMessage()).contains("line2");
        assertThat(result.getMessage()).contains("line4");
    }

    @Test
    void searchContentTextMode(@TempDir Path tempDir) throws Exception {
        Path file = tempDir.resolve("test.txt");
        Files.writeString(file, "a.b.c\ndot");
        SearchContentTool tool = new SearchContentTool();
        ExecutionResult result = tool.execute(
                Map.of("pattern", "a.b", "path", tempDir.toString(), "mode", "text"),
                tempDir.toString());
        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getMessage()).contains("a.b.c");
    }
}