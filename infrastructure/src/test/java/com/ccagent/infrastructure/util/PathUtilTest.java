package com.ccagent.infrastructure.util;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import static org.assertj.core.api.Assertions.assertThat;

class PathUtilTest {

    @Test
    void shouldNormalizePath() {
        // normalize 会使用系统路径分隔符
        String sep = java.io.File.separator;
        assertThat(PathUtil.normalize("/foo/bar/../baz")).endsWith("foo" + sep + "baz");
        assertThat(PathUtil.normalize("/foo/./bar")).endsWith("foo" + sep + "bar");
    }

    @Test
    void shouldResolvePath() {
        String result = PathUtil.resolve("/home/workspace", "src/main");
        // 路径分隔符 Windows 是 \ Unix 是 /
        String expectedEnding = "src" + java.io.File.separator + "main";
        assertThat(result).endsWith(expectedEnding);
    }

    @Test
    void shouldDetectSubPath() {
        String sep = java.io.File.separator;
        String ws = sep + "workspace";
        String sub = ws + sep + "src" + sep + "main";
        assertThat(PathUtil.isSubPath(ws, sub)).isTrue();
        assertThat(PathUtil.isSubPath(ws, ws)).isTrue();
    }

    @Test
    void shouldRejectTraversal() {
        String sep = java.io.File.separator;
        assertThat(PathUtil.isSubPath(sep + "workspace", sep + "etc" + sep + "passwd")).isFalse();
    }

    @Test
    void shouldGetFileName() {
        assertThat(PathUtil.getFileName(java.io.File.separator + "a" + java.io.File.separator + "b" + java.io.File.separator + "Test.java")).isEqualTo("Test.java");
    }

    @Test
    void shouldGetExtension() {
        String sep = java.io.File.separator;
        assertThat(PathUtil.getExtension(sep + "a" + sep + "b" + sep + "Test.java")).isEqualTo("java");
        assertThat(PathUtil.getExtension("noext")).isEqualTo("");
    }

    @Test
    void shouldRejectTraversalWithResolve() {
        String sep = java.io.File.separator;
        String resolved = PathUtil.resolve(sep + "workspace", "../../etc/passwd");
        assertThat(PathUtil.isSubPath(sep + "workspace", resolved)).isFalse();
    }

    @Test
    void shouldAllowWorkspaceRootByDefault(@TempDir Path tempDir) {
        Path ws = tempDir.resolve("ws");
        PathUtil.setAllowedPaths(java.util.List.of());
        assertThat(PathUtil.isAllowedPath(ws.toString(), ws.resolve("a.txt").toString())).isTrue();
        assertThat(PathUtil.isAllowedPath(ws.toString(), tempDir.resolve("b.txt").toString())).isFalse();
    }

    @Test
    void shouldAllowWhitelistedPath(@TempDir Path tempDir) {
        Path ws = tempDir.resolve("ws");
        Path shared = tempDir.resolve("shared");
        PathUtil.setAllowedPaths(java.util.List.of(shared.toString()));
        try {
            // 白名单目录内（工作区之外）放行
            assertThat(PathUtil.isAllowedPath(ws.toString(), shared.resolve("data.txt").toString())).isTrue();
            // 工作区内仍然放行
            assertThat(PathUtil.isAllowedPath(ws.toString(), ws.resolve("a.txt").toString())).isTrue();
        } finally {
            PathUtil.setAllowedPaths(java.util.List.of());
        }
    }

    @Test
    void shouldRejectPathOutsideWorkspaceAndWhitelist(@TempDir Path tempDir) {
        Path ws = tempDir.resolve("ws");
        Path shared = tempDir.resolve("shared");
        Path outside = tempDir.resolve("outside");
        PathUtil.setAllowedPaths(java.util.List.of(shared.toString()));
        try {
            assertThat(PathUtil.isAllowedPath(ws.toString(), outside.resolve("x.txt").toString())).isFalse();
        } finally {
            PathUtil.setAllowedPaths(java.util.List.of());
        }
    }

    @Test
    void shouldIgnoreBlankWhitelistEntries(@TempDir Path tempDir) {
        Path ws = tempDir.resolve("ws");
        PathUtil.setAllowedPaths(java.util.List.of("", "  "));
        assertThat(PathUtil.isAllowedPath(ws.toString(), tempDir.resolve("x.txt").toString())).isFalse();
    }
}
