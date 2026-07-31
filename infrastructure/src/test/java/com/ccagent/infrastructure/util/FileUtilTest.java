package com.ccagent.infrastructure.util;

import com.ccagent.exception.tool.ToolException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FileUtilTest {

    @Test
    void shouldReadWriteString(@TempDir Path tempDir) {
        Path file = tempDir.resolve("test.txt");
        FileUtil.writeString(file, "Hello, World!");
        assertThat(FileUtil.readString(file)).isEqualTo("Hello, World!");
    }

    @Test
    void shouldThrowOnNonExistentFile() {
        assertThatThrownBy(() -> FileUtil.readString(Path.of("/nonexistent/file.txt")))
                .isInstanceOf(ToolException.class);
    }

    @Test
    void shouldCreateDirectories(@TempDir Path tempDir) {
        Path nested = tempDir.resolve("a/b/c");
        FileUtil.createDirectories(nested);
        assertThat(Files.exists(nested)).isTrue();
    }

    @Test
    void shouldListFiles(@TempDir Path tempDir) throws Exception {
        Files.createFile(tempDir.resolve("f1.txt"));
        Files.createFile(tempDir.resolve("f2.txt"));
        List<Path> files = FileUtil.listFiles(tempDir);
        assertThat(files).hasSize(2);
    }

    @Test
    void shouldCopyFile(@TempDir Path tempDir) throws Exception {
        Path src = tempDir.resolve("src.txt");
        Path dst = tempDir.resolve("dst.txt");
        FileUtil.writeString(src, "content");
        FileUtil.copy(src, dst);
        assertThat(FileUtil.readString(dst)).isEqualTo("content");
    }

    @Test
    void shouldDeleteFile(@TempDir Path tempDir) throws Exception {
        Path file = tempDir.resolve("to-delete.txt");
        FileUtil.writeString(file, "delete me");
        FileUtil.delete(file);
        assertThat(Files.exists(file)).isFalse();
    }

    @Test
    void shouldCheckExistence(@TempDir Path tempDir) {
        assertThat(FileUtil.exists(tempDir)).isTrue();
        assertThat(FileUtil.exists(tempDir.resolve("nonexistent"))).isFalse();
    }

    @Test
    void shouldGetSize(@TempDir Path tempDir) throws Exception {
        Path file = tempDir.resolve("size.txt");
        FileUtil.writeString(file, "12345");
        assertThat(FileUtil.size(file)).isEqualTo(5);
    }
}
