package com.ccagent.infrastructure.util;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 路径规范化、安全校验工具
 * <p>纯函数式设计——所有方法无副作用、无状态、无 IO。
 * 不抛出业务异常，只返回 boolean 或规范化后的字符串。
 * 治理层的 PolicyEnforcer 在其上封装业务语义并抛出 SecurityException。</p>
 *
 * <p>额外说明：{@link #allowedPaths} 为进程级启动配置（由装配链在启动时一次性注入），
 * 一旦设置不再变化；{@link #isAllowedPath} 是"工作区 ∪ 白名单"的放行判定。</p>
 */
public final class PathUtil {

    /** 额外允许访问的目录（用户配置，位于工作区之外）。静态启动配置，仅装配链写入。 */
    private static volatile List<String> allowedPaths = Collections.emptyList();

    private PathUtil() {}

    /**
     * 设置额外允许访问的目录（白名单），启动时由装配链注入一次。
     *
     * @param paths 白名单目录列表；为空或 null 时等价于未配置
     */
    public static void setAllowedPaths(List<String> paths) {
        if (paths == null || paths.isEmpty()) {
            allowedPaths = Collections.emptyList();
            return;
        }
        List<String> normalized = new ArrayList<>(paths.size());
        for (String p : paths) {
            if (p != null && !p.isBlank()) {
                normalized.add(Paths.get(p).toAbsolutePath().normalize().toString());
            }
        }
        allowedPaths = normalized;
    }

    /**
     * 判断路径是否可访问：命中工作区根目录或任一白名单目录即放行。
     *
     * @param workspaceRoot 工作区根路径（动态白名单）
     * @param requestedPath 请求的路径
     * @return true 若在工作区内或任一白名单目录内（含等于）
     */
    public static boolean isAllowedPath(String workspaceRoot, String requestedPath) {
        if (workspaceRoot != null && !workspaceRoot.isBlank()
                && isSubPath(workspaceRoot, requestedPath)) {
            return true;
        }
        for (String allowed : allowedPaths) {
            if (isSubPath(allowed, requestedPath)) {
                return true;
            }
        }
        return false;
    }

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
     * <p>Windows 下路径比较需处理：大小写不敏感、斜杠方向、绝对路径转换。</p>
     *
     * @param parent 父目录路径
     * @param child  子路径
     * @return true 若 child 在 parent 目录下（含等于 parent）
     */
    public static boolean isSubPath(String parent, String child) {
        Path parentPath = Paths.get(parent).toAbsolutePath().normalize();
        Path childPath = Paths.get(child).toAbsolutePath().normalize();
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
