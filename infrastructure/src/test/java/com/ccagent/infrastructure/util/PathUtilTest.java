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
}
