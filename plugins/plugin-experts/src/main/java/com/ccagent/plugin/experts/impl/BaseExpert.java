package com.ccagent.plugin.experts.impl;

import com.ccagent.core.expert.annotation.ExpertAnnotation;
import com.ccagent.api.context.TaskScope;
import com.ccagent.api.model.request.ChatRequest;
import com.ccagent.api.model.request.ToolCall;
import com.ccagent.api.model.response.ChatResponse;
import com.ccagent.api.model.response.ExecutionResult;
import com.ccagent.api.model.response.StreamChunk;
import com.ccagent.api.model.tool.ToolDefinition;
import com.ccagent.api.port.Expert;
import com.ccagent.api.port.LlmPort;
import com.ccagent.api.port.StreamObserver;
import com.ccagent.api.port.ToolRegistryPort;
import com.ccagent.core.plugin.ExpertExtensionPoint;
import com.ccagent.kernel.plugin.PluginContext;

import java.util.*;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * 专家基类
 * <p>提供 LLM 调用、文件读写、状态推送等共享能力。
 * 子类实现 {@link #execute(String, TaskScope, Map, String, StreamObserver)} 方法。</p>
 */
public abstract class BaseExpert implements ExpertExtensionPoint {

    protected ToolRegistryPort toolRegistry;
    protected LlmPort llmPort;

    /**
     * 无参构造（插件化加载用）
     */
    protected BaseExpert() {}

    /**
     * 构造函数（手动组装用，保留兼容）
     */
    protected BaseExpert(ToolRegistryPort toolRegistry, LlmPort llmPort) {
        this.toolRegistry = toolRegistry;
        this.llmPort = llmPort;
    }

    @Override
    public void init(PluginContext context) {
        this.toolRegistry = context.getService(ToolRegistryPort.class);
        this.llmPort = context.getService(LlmPort.class);
    }

    /**
     * 旧接口（不支持黑板），转发至新方法
     */
    @Deprecated
    @Override
    public void execute(String task, String workspaceRoot, StreamObserver<StreamChunk> observer) {
        execute(task, null, Map.of(), workspaceRoot, observer);
    }

    /**
     * 调用 LLM 生成内容（流式）
     * <p>使用 chatStream() 逐块推送到 observer，同时收集完整文本并返回。</p>
     *
     * @param systemPrompt 系统提示词
     * @param userPrompt   用户提示词（包含上下文）
     * @param observer     流式回调（每收到一个 LLM 块时触发 onNext）
     * @return LLM 响应全文
     */
    protected String callLlm(String systemPrompt, String userPrompt,
                              StreamObserver<StreamChunk> observer) {
        ChatRequest request = new ChatRequest(
                systemPrompt, userPrompt, List.of(),
                false, 4096, 0.3, 60
        );
        StringBuilder fullContent = new StringBuilder();
        CountDownLatch latch = new CountDownLatch(1);
        final Throwable[] errorRef = new Throwable[1];

        llmPort.chatStream(request, new StreamObserver<>() {
            @Override
            public void onNext(StreamChunk chunk) {
                if (chunk.getContent() != null && !chunk.getContent().isEmpty()) {
                    fullContent.append(chunk.getContent());
                    observer.onNext(new StreamChunk(null, "STREAM_CHUNK",
                            chunk.getContent(), null));
                }
            }

            @Override
            public void onError(Throwable error) {
                errorRef[0] = error;
                latch.countDown();
            }

            @Override
            public void onComplete() {
                latch.countDown();
            }
        });

        try {
            latch.await(request.getTimeoutSeconds(), TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        if (errorRef[0] != null && fullContent.isEmpty()) {
            return "";
        }

        return fullContent.toString();
    }

    /**
     * 读取文件内容
     *
     * @param path          完整文件路径（支持相对/绝对）
     * @param workspaceRoot 工作区根路径（安全校验用，传 null 跳过校验）
     * @return 文件内容
     */
    protected String readFile(String path, String workspaceRoot) {
        ExecutionResult result = toolRegistry.executeTool(
                "read_file", Map.of("path", path), workspaceRoot);
        return result.getMessage();
    }

    /**
     * 写入文件内容
     *
     * @param path          完整文件路径
     * @param content       文件内容
     * @param workspaceRoot 工作区根路径（安全校验用）
     */
    protected void writeFile(String path, String content, String workspaceRoot) {
        toolRegistry.executeTool(
                "write_file", Map.of("path", path, "content", content), workspaceRoot);
    }

    /**
     * 推送专家状态
     */
    protected void pushStatus(StreamObserver<StreamChunk> observer, String status, String message) {
        observer.onNext(new StreamChunk(null, "EXPERT_STATUS",
                "{\"expert\":\"" + getName() + "\",\"status\":\"" + status
                        + "\",\"message\":\"" + escape(message) + "\"}", null));
    }

    /**
     * 从 LLM 响应中提取 JSON 内容（如果有 JSON 代码块则提取）
     */
    protected String extractJson(String content) {
        if (content == null) return "";
        if (content.contains("```json")) {
            int start = content.indexOf("```json") + 7;
            int end = content.indexOf("```", start);
            if (end > start) return content.substring(start, end).trim();
        }
        return content;
    }

    private String escape(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }

    /**
     * 从 task 中提取文件路径列表
     */
    protected java.util.List<String> extractFilePaths(String task) {
        java.util.regex.Matcher m = java.util.regex.Pattern.compile(
                "(?:file|路径|文件)[：:\\s]*([^\\s,，\\n]+)").matcher(task);
        java.util.List<String> paths = new java.util.ArrayList<>();
        while (m.find()) paths.add(m.group(1).trim());
        return paths;
    }

    /**
     * 从 task 中提取第一个文件路径
     */
    protected String extractFirstFilePath(String task) {
        java.util.regex.Matcher m = java.util.regex.Pattern.compile(
                "(?:file|路径|文件|source)[：:\\s]*([^\\s,，\\n]+)").matcher(task);
        return m.find() ? m.group(1).trim() : "";
    }

    /**
     * 截断长文本
     */
    protected String truncate(String s, int max) {
        return s != null && s.length() > max ? s.substring(0, max) + "..." : (s != null ? s : "");
    }

    /**
     * 获取当前专家可用的工具列表（从 @ExpertAnnotation.tools 读取）
     * <p>若注解未声明 tools，则返回全部工具。</p>
     */
    protected List<ToolDefinition> getAvailableTools() {
        ExpertAnnotation annotation = getClass().getAnnotation(ExpertAnnotation.class);
        List<ToolDefinition> allTools = toolRegistry.getToolDefinitions();
        if (annotation == null || annotation.tools().length == 0) {
            return allTools;
        }
        Set<String> allowed = Set.of(annotation.tools());
        return allTools.stream()
                .filter(t -> allowed.contains(t.getName()))
                .collect(Collectors.toList());
    }

    /**
     * 执行 ReAct 循环：LLM 自主调用工具，直到返回最终答案
     *
     * @param systemPrompt  系统提示词
     * @param userPrompt    用户提示词
     * @param workspaceRoot 工作区根路径
     * @param observer      流式回调（LLM 输出文本时推送）
     * @return 最终结果文本
     */
    protected String runReActLoop(String systemPrompt, String userPrompt,
                                   String workspaceRoot,
                                   StreamObserver<StreamChunk> observer) {
        List<ToolDefinition> tools = getAvailableTools();
        List<Map<String, String>> messages = new ArrayList<>();
        messages.add(Map.of("role", "user", "content", userPrompt));

        StringBuilder finalContent = new StringBuilder();
        int loopCount = 0;
        final int maxLoops = 10;

        while (loopCount < maxLoops) {
            loopCount++;

            ChatRequest request = new ChatRequest(
                    systemPrompt,
                    "",
                    tools,
                    true,
                    4096,
                    0.3,
                    60,
                    messages
            );

            ChatResponse response = llmPort.chat(request);
            String content = response.getContent();
            List<ToolCall> toolCalls = response.getToolCalls();

            if (content != null && !content.isEmpty()) {
                finalContent.append(content);
                observer.onNext(new StreamChunk(null, "STREAM_CHUNK", content, null));
            }

            if (toolCalls == null || toolCalls.isEmpty()) {
                break;
            }

            for (ToolCall tc : toolCalls) {
                if (content != null) {
                    messages.add(Map.of("role", "assistant", "content", content));
                }
                String toolResult = executeToolSafe(tc.getName(), tc.getArguments(), workspaceRoot);
                messages.add(Map.of("role", "tool", "name", tc.getName(), "content", toolResult));
            }
        }

        if (loopCount >= maxLoops) {
            String limitMsg = "\n\n(达到最大工具调用轮次，结果可能不完整)";
            finalContent.append(limitMsg);
            observer.onNext(new StreamChunk(null, "STREAM_CHUNK", limitMsg, null));
        }

        return finalContent.toString();
    }

    /**
     * 安全执行工具（捕获异常返回错误信息，不抛出）
     */
    private String executeToolSafe(String toolName, Map<String, Object> args, String workspaceRoot) {
        try {
            ExecutionResult result = toolRegistry.executeTool(toolName, args, workspaceRoot);
            return result.isSuccess()
                    ? result.getMessage()
                    : "失败: " + result.getMessage();
        } catch (Exception e) {
            return "工具执行失败: " + e.getMessage();
        }
    }
}