package com.ccagent.infrastructure.util;

import com.ccagent.exception.base.ErrorCode;
import com.ccagent.exception.tool.ToolException;

import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static java.nio.file.StandardOpenOption.*;

/**
 * 通用文件操作工具
 * <p>封装 java.nio.file.Files，提供便捷的读写、复制、删除等操作。
 * 操作失败时统一抛出 ToolException。</p>
 */
public final class FileUtil {

    private FileUtil() {}

    /**
     * 读取文件全部内容为字符串（UTF-8）
     */
    public static String readString(Path path) {
        try {
            return Files.readString(path);
        } catch (NoSuchFileException e) {
            throw new ToolException(ErrorCode.TOOL_EXECUTION_FAILED, "文件不存在: " + path, e);
        } catch (AccessDeniedException e) {
            throw new ToolException(ErrorCode.TOOL_EXECUTION_FAILED, "权限不足: " + path, e);
        } catch (IOException e) {
            throw new ToolException(ErrorCode.TOOL_EXECUTION_FAILED, "读取文件失败: " + path, e);
        }
    }

    /**
     * 写入字符串到文件（UTF-8，覆盖模式）
     */
    public static void writeString(Path path, String content) {
        try {
            Files.writeString(path, content);
        } catch (AccessDeniedException e) {
            throw new ToolException(ErrorCode.TOOL_EXECUTION_FAILED, "权限不足: " + path, e);
        } catch (IOException e) {
            throw new ToolException(ErrorCode.TOOL_EXECUTION_FAILED, "写入文件失败: " + path, e);
        }
    }

    /**
     * 追加字符串到文件（UTF-8，追加模式，自动创建父目录）
     */
    public static void appendString(Path path, String content) {
        try {
            Files.createDirectories(path.getParent());
            Files.writeString(path, content, CREATE, APPEND);
        } catch (AccessDeniedException e) {
            throw new ToolException(ErrorCode.TOOL_EXECUTION_FAILED, "权限不足: " + path, e);
        } catch (IOException e) {
            throw new ToolException(ErrorCode.TOOL_EXECUTION_FAILED, "追加文件失败: " + path, e);
        }
    }

    /**
     * 复制文件或目录（递归复制整个树）
     */
    public static void copy(Path source, Path target) {
        try {
            if (Files.isDirectory(source)) {
                copyDirectory(source, target);
            } else {
                Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            throw new ToolException(ErrorCode.TOOL_EXECUTION_FAILED,
                    "复制失败: " + source + " -> " + target, e);
        }
    }

    private static void copyDirectory(Path source, Path target) throws IOException {
        Files.walkFileTree(source, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                Files.createDirectories(target.resolve(source.relativize(dir)));
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                Files.copy(file, target.resolve(source.relativize(file)),
                        StandardCopyOption.REPLACE_EXISTING);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    /**
     * 删除文件或目录（递归删除整个树）
     */
    public static void delete(Path path) {
        try {
            if (Files.isDirectory(path)) {
                try (Stream<Path> walk = Files.walk(path)) {
                    // 先删文件，再删目录（从最深开始）
                    List<Path> sorted = walk.sorted(Comparator.reverseOrder()).collect(Collectors.toList());
                    for (Path p : sorted) {
                        Files.deleteIfExists(p);
                    }
                }
            } else {
                Files.deleteIfExists(path);
            }
        } catch (IOException e) {
            throw new ToolException(ErrorCode.TOOL_EXECUTION_FAILED, "删除失败: " + path, e);
        }
    }

    /**
     * 判断文件或目录是否存在
     */
    public static boolean exists(Path path) {
        return Files.exists(path);
    }

    /**
     * 判断是否为目录
     */
    public static boolean isDirectory(Path path) {
        return Files.isDirectory(path);
    }

    /**
     * 创建目录（含父级）
     */
    public static void createDirectories(Path path) {
        try {
            Files.createDirectories(path);
        } catch (IOException e) {
            throw new ToolException(ErrorCode.TOOL_EXECUTION_FAILED, "创建目录失败: " + path, e);
        }
    }

    /**
     * 列出目录下所有文件（非递归）
     */
    public static List<Path> listFiles(Path dir) {
        try (Stream<Path> stream = Files.list(dir)) {
            return stream.collect(Collectors.toList());
        } catch (NoSuchFileException e) {
            throw new ToolException(ErrorCode.TOOL_EXECUTION_FAILED, "目录不存在: " + dir, e);
        } catch (IOException e) {
            throw new ToolException(ErrorCode.TOOL_EXECUTION_FAILED, "列出目录失败: " + dir, e);
        }
    }

    /**
     * 获取文件大小（字节）
     */
    public static long size(Path path) {
        try {
            return Files.size(path);
        } catch (IOException e) {
            throw new ToolException(ErrorCode.TOOL_EXECUTION_FAILED, "获取文件大小失败: " + path, e);
        }
    }
}
