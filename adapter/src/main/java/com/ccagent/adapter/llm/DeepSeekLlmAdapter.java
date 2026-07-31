package com.ccagent.adapter.llm;

import com.ccagent.api.model.request.ChatRequest;
import com.ccagent.api.model.request.ToolCall;
import com.ccagent.api.model.response.ChatResponse;
import com.ccagent.api.model.response.StreamChunk;
import com.ccagent.api.model.tool.ToolDefinition;
import com.ccagent.api.port.LlmPort;
import com.ccagent.api.port.StreamObserver;
import com.ccagent.exception.base.ErrorCode;
import com.ccagent.exception.llm.LlmException;
import com.ccagent.infrastructure.util.JsonUtil;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

public class DeepSeekLlmAdapter implements LlmPort {

    private final HttpClient httpClient;
    private final String apiKey;
    private final String baseUrl;
    private final String model;
    private final int timeoutStreaming;

    private final ConcurrentHashMap<String, CompletableFuture<?>> requestFutures = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Thread> requestThreads = new ConcurrentHashMap<>();

    public DeepSeekLlmAdapter(String apiKey, String baseUrl, String model) {
        this(apiKey, baseUrl, model, 60);
    }

    public DeepSeekLlmAdapter(String apiKey, String baseUrl, String model, int timeoutStreaming) {
        this.apiKey = apiKey;
        this.baseUrl = baseUrl;
        this.model = model;
        this.timeoutStreaming = timeoutStreaming;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(30))
                .build();
    }

    @Override
    public ChatResponse chat(ChatRequest request) {
        String requestId = java.util.UUID.randomUUID().toString();
        String url = baseUrl + "/chat/completions";

        String requestBody = buildChatRequestBody(request, false);
        HttpRequest httpReq = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Authorization", "Bearer " + apiKey)
                .header("Content-Type", "application/json")
                .timeout(Duration.ofSeconds(request.getTimeoutSeconds()))
                .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                .build();

        CompletableFuture<ChatResponse> future = new CompletableFuture<>();
        requestFutures.put(requestId, future);

        Thread vt = Thread.startVirtualThread(() -> {
            try {
                HttpResponse<String> response = httpClient.send(
                        httpReq, HttpResponse.BodyHandlers.ofString());

                if (response.statusCode() == 401) {
                    throw new LlmException(ErrorCode.LLM_AUTH_FAILED, "API Key 无效");
                }
                if (response.statusCode() == 429) {
                    throw new LlmException(ErrorCode.LLM_SERVICE_UNAVAILABLE, "请求频率超限");
                }
                if (response.statusCode() != 200) {
                    throw new LlmException(ErrorCode.LLM_SERVICE_UNAVAILABLE,
                            "DeepSeek 服务返回错误: HTTP " + response.statusCode());
                }

                future.complete(parseChatResponse(response.body()));
            } catch (LlmException e) {
                future.completeExceptionally(e);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                future.completeExceptionally(e);
            } catch (Exception e) {
                future.completeExceptionally(new LlmException(ErrorCode.LLM_SERVICE_UNAVAILABLE,
                        "LLM 调用失败: " + e.getMessage(), e));
            } finally {
                requestFutures.remove(requestId);
            }
        });
        requestThreads.put(requestId, vt);

        try {
            return future.get(request.getTimeoutSeconds(), TimeUnit.SECONDS);
        } catch (java.util.concurrent.TimeoutException e) {
            vt.interrupt();
            throw new LlmException(ErrorCode.LLM_TIMEOUT,
                    "LLM 调用超时", e, request.getTimeoutSeconds(), baseUrl);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new LlmException(ErrorCode.LLM_TIMEOUT,
                    "LLM 调用被中断", e, request.getTimeoutSeconds(), baseUrl);
        } catch (java.util.concurrent.ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof LlmException le) throw le;
            throw new LlmException(ErrorCode.LLM_SERVICE_UNAVAILABLE,
                    "LLM 调用失败: " + cause.getMessage(), cause);
        } finally {
            requestThreads.remove(requestId);
        }
    }

    @Override
    public void chatStream(ChatRequest request, StreamObserver<StreamChunk> observer) {
        String requestId = java.util.UUID.randomUUID().toString();
        String url = baseUrl + "/chat/completions";

        try {
            String requestBody = buildChatRequestBody(request, true);

            HttpRequest httpReq = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("Authorization", "Bearer " + apiKey)
                    .header("Content-Type", "application/json")
                    .header("Accept", "text/event-stream")
                    .timeout(Duration.ofSeconds(timeoutStreaming))
                    .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                    .build();

            Thread vt = Thread.startVirtualThread(() -> {
                try {
                    HttpResponse<java.io.InputStream> response = httpClient.send(
                            httpReq, HttpResponse.BodyHandlers.ofInputStream());

                    int statusCode = response.statusCode();
                    if (statusCode == 401) {
                        observer.onError(new LlmException(ErrorCode.LLM_AUTH_FAILED, "API Key 无效"));
                        return;
                    }
                    if (statusCode == 429) {
                        observer.onError(new LlmException(ErrorCode.LLM_SERVICE_UNAVAILABLE, "请求频率超限"));
                        return;
                    }
                    if (statusCode != 200) {
                        observer.onError(new LlmException(ErrorCode.LLM_SERVICE_UNAVAILABLE,
                                "DeepSeek 服务返回错误: HTTP " + statusCode));
                        return;
                    }

                    int totalTokens = 0;
                    int cachedTokens = 0;

                    try (BufferedReader reader = new BufferedReader(
                            new InputStreamReader(response.body(), StandardCharsets.UTF_8))) {
                        String line;
                        while ((line = reader.readLine()) != null) {
                            if (Thread.currentThread().isInterrupted()) return;

                            if (!line.startsWith("data: ")) continue;

                            String data = line.substring(6).trim();
                            if ("[DONE]".equals(data)) break;

                            @SuppressWarnings("unchecked")
                            Map<String, Object> event = JsonUtil.fromJson(data, Map.class);
                            if (event == null) continue;

                            if (event.containsKey("error")) {
                                @SuppressWarnings("unchecked")
                                Map<String, Object> error = (Map<String, Object>) event.get("error");
                                String errMsg = error != null ? (String) error.get("message") : "未知错误";
                                observer.onError(new LlmException(ErrorCode.LLM_SERVICE_UNAVAILABLE, errMsg));
                                return;
                            }

                            @SuppressWarnings("unchecked")
                            List<Map<String, Object>> choices =
                                    (List<Map<String, Object>>) event.get("choices");
                            if (choices == null || choices.isEmpty()) continue;

                            Map<String, Object> choice = choices.get(0);
                            @SuppressWarnings("unchecked")
                            Map<String, Object> delta = (Map<String, Object>) choice.get("delta");
                            String content = delta != null ? (String) delta.get("content") : null;
                            String finishReason = (String) choice.get("finish_reason");

                            @SuppressWarnings("unchecked")
                            Map<String, Object> usage = (Map<String, Object>) event.get("usage");
                            if (usage != null) {
                                totalTokens = ((Number) usage.getOrDefault("total_tokens", 0)).intValue();
                                cachedTokens = ((Number) usage.getOrDefault("prompt_cache_hit_tokens", 0)).intValue();
                            }

                            if (content != null && !content.isEmpty()) {
                                observer.onNext(new StreamChunk(
                                        requestId, "STREAM_CHUNK", content, null));
                            }

                            if (finishReason != null && !finishReason.isEmpty()) {
                                StreamChunk finalChunk = new StreamChunk(
                                        requestId, "STREAM_CHUNK", "", finishReason);
                                finalChunk.setTotalTokens(totalTokens);
                                finalChunk.setCachedTokens(cachedTokens);
                                observer.onNext(finalChunk);
                            }
                        }
                    }
                    observer.onComplete();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } catch (Exception e) {
                    if (!Thread.currentThread().isInterrupted()) {
                        observer.onError(new LlmException(ErrorCode.LLM_SERVICE_UNAVAILABLE,
                                "流式读取失败: " + e.getMessage(), e));
                    }
                } finally {
                    requestThreads.remove(requestId);
                }
            });

            requestThreads.put(requestId, vt);

        } catch (Exception e) {
            requestThreads.remove(requestId);
            observer.onError(new LlmException(ErrorCode.LLM_SERVICE_UNAVAILABLE,
                    "流式调用失败: " + e.getMessage(), e));
        }
    }

    @Override
    public boolean cancel(String requestId) {
        Thread t = requestThreads.get(requestId);
        if (t != null && t.isAlive()) {
            t.interrupt();
            return true;
        }
        CompletableFuture<?> future = requestFutures.get(requestId);
        if (future != null && !future.isDone()) {
            return future.cancel(true);
        }
        return false;
    }

    @SuppressWarnings("unchecked")
    private String buildChatRequestBody(ChatRequest request, boolean stream) {
        var messages = new ArrayList<Map<String, Object>>();
        messages.add(Map.of("role", "system", "content", request.getSystemPrompt()));

        if (request.getHistoryMessages() != null) {
            for (Map<String, String> history : request.getHistoryMessages()) {
                messages.add(Map.of("role", history.get("role"), "content", history.get("content")));
            }
        }

        messages.add(Map.of("role", "user", "content", request.getUserPrompt()));

        var body = new LinkedHashMap<String, Object>();
        body.put("model", model);
        body.put("messages", messages);
        body.put("max_tokens", request.getMaxTokens());
        body.put("stream", stream);
        if (request.getTemperature() != null) {
            body.put("temperature", request.getTemperature());
        }

        if (request.isAllowTools() && request.getTools() != null && !request.getTools().isEmpty()) {
            var toolsList = new ArrayList<Map<String, Object>>();
            for (ToolDefinition tool : request.getTools()) {
                var func = new LinkedHashMap<String, Object>();
                func.put("name", tool.getName());
                func.put("description", tool.getDescription());
                if (tool.getParameters() != null) {
                    func.put("parameters", tool.getParameters());
                }
                toolsList.add(Map.of("type", "function", "function", func));
            }
            body.put("tools", toolsList);
        }

        return JsonUtil.toJson(body);
    }

    @SuppressWarnings("unchecked")
    private ChatResponse parseChatResponse(String responseBody) {
        try {
            Map<String, Object> body = JsonUtil.fromJson(responseBody, Map.class);
            var choices = (List<Map<String, Object>>) body.get("choices");
            if (choices == null || choices.isEmpty()) {
                throw new LlmException(ErrorCode.LLM_SERVICE_UNAVAILABLE, "LLM 返回空 choices");
            }

            Map<String, Object> choice = choices.get(0);
            Map<String, Object> message = (Map<String, Object>) choice.get("message");
            String content = message != null ? (String) message.get("content") : null;
            String finishReason = (String) choice.get("finishReason");
            if (finishReason == null) finishReason = (String) choice.get("finish_reason");

            List<ToolCall> toolCalls = null;
            if (message != null && message.containsKey("tool_calls")) {
                var rawCalls = (List<Map<String, Object>>) message.get("tool_calls");
                toolCalls = new ArrayList<>();
                for (Map<String, Object> raw : rawCalls) {
                    Map<String, Object> func = (Map<String, Object>) raw.get("function");
                    if (func == null) continue;
                    ToolCall tc = new ToolCall(
                            (String) func.get("name"),
                            JsonUtil.fromJson((String) func.get("arguments"), Map.class));
                    tc.setId((String) raw.get("id"));
                    toolCalls.add(tc);
                }
            }

            Map<String, Object> usage = (Map<String, Object>) body.get("usage");
            int totalTokens = 0;
            int cachedTokens = 0;
            if (usage != null) {
                totalTokens = ((Number) usage.getOrDefault("total_tokens", 0)).intValue();
                cachedTokens = ((Number) usage.getOrDefault("prompt_cache_hit_tokens", 0)).intValue();
            }

            return new ChatResponse(content, toolCalls, totalTokens, cachedTokens, finishReason);

        } catch (LlmException e) {
            throw e;
        } catch (Exception e) {
            throw new LlmException(ErrorCode.LLM_SERVICE_UNAVAILABLE,
                    "解析 LLM 响应失败", e);
        }
    }
}
