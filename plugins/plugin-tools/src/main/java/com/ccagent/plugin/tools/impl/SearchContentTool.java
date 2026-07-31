package com.ccagent.plugin.tools.impl;

import com.ccagent.core.plugin.ToolExtensionPoint;
import com.ccagent.kernel.plugin.PluginContext;
import com.ccagent.core.tool.annotation.Tool;
import com.ccagent.api.model.response.ExecutionResult;
import com.ccagent.exception.base.ErrorCode;
import com.ccagent.exception.tool.ToolException;
import com.ccagent.infrastructure.util.PathUtil;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@Tool(
        name = "search_content",
        description = "在文件中搜索匹配指定模式的内容（支持正则、文件过滤、上下文行数）",
        readOnly = true
)
public class SearchContentTool implements ToolExtensionPoint {

    @Override
    public void init(PluginContext context) {}

    private static final int MAX_FILES = 100;
    private static final int MAX_MATCHES_PER_FILE = 50;
    private static final int MAX_TOTAL_MATCHES = 500;
    private static final long MAX_FILE_SIZE = 1_000_000;
    private static final int DEFAULT_CONTEXT_LINES = 0;

    @Override
    public ExecutionResult execute(Map<String, Object> args, String workspaceRoot) {
        String pattern = args != null ? (String) args.get("pattern") : "";
        String searchPath = args != null ? (String) args.get("path") : "";
        String filePattern = args != null ? (String) args.get("file_pattern") : null;
        String mode = args != null ? (String) args.get("mode") : "regex";
        int contextLines = args != null && args.get("context_lines") != null
                ? toInt(args.get("context_lines"), DEFAULT_CONTEXT_LINES) : DEFAULT_CONTEXT_LINES;

        if (pattern == null || pattern.isBlank()) {
            throw new ToolException(ErrorCode.TOOL_EXECUTION_FAILED,
                    "参数 pattern 不能为空", getName(), null);
        }

        String resolvedPath = PathUtil.resolve(workspaceRoot, searchPath.isEmpty() ? "." : searchPath);
        Path basePath = Paths.get(resolvedPath);

        if (!Files.exists(basePath)) {
            throw new ToolException(ErrorCode.TOOL_EXECUTION_FAILED,
                    "路径不存在: " + resolvedPath, getName(), resolvedPath);
        }

        try {
            Pattern regex;
            if ("text".equalsIgnoreCase(mode)) {
                regex = Pattern.compile(Pattern.quote(pattern));
            } else {
                regex = Pattern.compile(pattern);
            }

            List<String> results = new ArrayList<>();
            int totalMatches = 0;
            int[] filesSearched = {0};

            if (Files.isDirectory(basePath)) {
                try (Stream<Path> walk = Files.walk(basePath)) {
                    List<Path> files = walk
                            .filter(Files::isRegularFile)
                            .filter(p -> matchesFileName(p, filePattern))
                            .limit(MAX_FILES)
                            .collect(Collectors.toList());

                    for (Path file : files) {
                        int count = searchInFile(file, regex, results, contextLines, MAX_MATCHES_PER_FILE);
                        totalMatches += count;
                        if (count > 0) filesSearched[0]++;
                        if (totalMatches >= MAX_TOTAL_MATCHES) break;
                    }
                }
            } else {
                int count = searchInFile(basePath, regex, results, contextLines, MAX_MATCHES_PER_FILE);
                totalMatches += count;
                if (count > 0) filesSearched[0]++;
            }

            if (results.isEmpty()) {
                return new ExecutionResult(true,
                        "未找到匹配内容 (搜索了 " + filesSearched[0] + " 个文件)",
                        resolvedPath, 0, 0, null);
            }

            String header = "找到 " + totalMatches + " 处匹配 (来自 " + filesSearched[0] + " 个文件):\n";
            String result = header + String.join("\n", results);
            if (totalMatches >= MAX_TOTAL_MATCHES) {
                result += "\n... (结果超过 " + MAX_TOTAL_MATCHES + " 条，已截断)";
            }

            return new ExecutionResult(true, result, resolvedPath, 0, 0, null);

        } catch (Exception e) {
            throw new ToolException(ErrorCode.TOOL_EXECUTION_FAILED,
                    "搜索失败: " + e.getMessage(), e, getName(), resolvedPath);
        }
    }

    private boolean matchesFileName(Path file, String filePattern) {
        if (filePattern == null || filePattern.isBlank()) return true;
        String fileName = file.getFileName().toString();
        String regex = globToRegex(filePattern);
        return fileName.matches(regex);
    }

    private String globToRegex(String glob) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < glob.length(); i++) {
            char c = glob.charAt(i);
            switch (c) {
                case '*' -> sb.append(".*");
                case '?' -> sb.append('.');
                case '.' -> sb.append("\\.");
                case '\\' -> sb.append("\\\\");
                default -> sb.append(c);
            }
        }
        return sb.toString();
    }

    private int searchInFile(Path file, Pattern regex, List<String> results,
                             int contextLines, int maxPerFile) {
        try {
            if (Files.size(file) > MAX_FILE_SIZE) return 0;
        } catch (IOException ignored) {
            return 0;
        }

        try {
            List<String> allLines = Files.readAllLines(file);
            String fileName = file.getFileName().toString();
            int matchCount = 0;

            for (int i = 0; i < allLines.size() && matchCount < maxPerFile; i++) {
                String line = allLines.get(i);
                if (regex.matcher(line).find()) {
                    matchCount++;

                    if (contextLines > 0) {
                        int ctxStart = Math.max(0, i - contextLines);
                        int ctxEnd = Math.min(allLines.size() - 1, i + contextLines);

                        results.add(fileName + ":" + (i + 1) + ": " + line.trim());
                        for (int j = ctxStart; j <= ctxEnd; j++) {
                            if (j == i) continue;
                            String prefix = (j < i) ? "  " : "  ";
                            results.add(prefix + (j + 1) + ": " + allLines.get(j).trim());
                        }
                        results.add("---");
                    } else {
                        results.add(fileName + ":" + (i + 1) + ": " + line.trim());
                    }
                }
            }
            return matchCount;
        } catch (Exception ignored) {
            return 0;
        }
    }

    private int toInt(Object obj, int defaultValue) {
        if (obj instanceof Integer i) return i;
        if (obj instanceof Number n) return n.intValue();
        if (obj instanceof String s) {
            try { return Integer.parseInt(s.trim()); }
            catch (NumberFormatException e) { return defaultValue; }
        }
        return defaultValue;
    }

    @Override
    public String getName() {
        return "search_content";
    }

    @Override
    public String getDescription() {
        return "在文件中搜索内容（支持正则/文本模式、文件过滤、上下文）";
    }

    @Override
    public boolean isReadOnly() {
        return true;
    }
}