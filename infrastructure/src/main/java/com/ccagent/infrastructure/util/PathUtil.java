package com.ccagent.infrastructure.util;

import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * 路径规范化、安全校验工具
 * <p>纯函数式设计——所有方法无副作用、无状态、无 IO。
 * 不抛出业务异常，只返回 boolean 或规范化后的字符串。
 * 治理层的 PolicyEnforcer 在其上封装业务语义并抛出 SecurityException。</p>
 */
public final class PathUtil {

    private PathUtil() {}

    /**
     * 规范化路径（解析 .. 和 .）
     *
     * @param path 原始路径字符串
     * @return 规范化后的路径
     */
    public static String normalize(String path) {
        return Paths.get(path).normalize().toString();
    }

    /**
     * 安全拼接路径（防 .. 穿越）
     *
     * @param base  基础路径
     * @param child 子路径
     * @return 拼接并规范化后的路径
     */
    public static String resolve(String base, String child) {
        return Paths.get(base).resolve(child).normalize().toString();
    }

    /**
     * 判断 child 是否在 parent 目录下（安全校验核心方法）
     *
     * @param parent 父目录路径
     * @param child  子路径
     * @return true 若 child 在 parent 目录下（含等于 parent）
     */
    public static boolean isSubPath(String parent, String child) {
        Path parentPath = Paths.get(parent).normalize();
        Path childPath = Paths.get(child).normalize();
        return childPath.startsWith(parentPath);
    }

    /**
     * 获取文件名（含扩展名）
     *
     * @param path 文件路径
     * @return 文件名
     */
    public static String getFileName(String path) {
        return Paths.get(path).getFileName().toString();
    }

    /**
     * 获取文件扩展名（不含 '.'）
     *
     * @param path 文件路径
     * @return 扩展名，若无则返回空字符串
     */
    public static String getExtension(String path) {
        String fileName = getFileName(path);
        int dotIndex = fileName.lastIndexOf('.');
        if (dotIndex < 0 || dotIndex == fileName.length() - 1) {
            return "";
        }
        return fileName.substring(dotIndex + 1);
    }
}
