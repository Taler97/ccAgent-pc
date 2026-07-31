package com.ccagent.core.event;

import com.ccagent.kernel.event.Event;

public class LlmCallCompletedEvent extends Event {

    private final String sessionId;
    private final String seqId;
    private final String model;
    private final int inputTokens;
    private final int cachedInputTokens;
    private final int outputTokens;
    private final double cost;
    private final long durationMs;

    public LlmCallCompletedEvent(String sessionId, String seqId, String model,
                                  int inputTokens, int cachedInputTokens,
                                  int outputTokens, double cost, long durationMs) {
        this.sessionId = sessionId;
        this.seqId = seqId;
        this.model = model;
        this.inputTokens = inputTokens;
        this.cachedInputTokens = cachedInputTokens;
        this.outputTokens = outputTokens;
        this.cost = cost;
        this.durationMs = durationMs;
        putMetadata("sessionId", sessionId);
        putMetadata("seqId", seqId != null ? seqId : "");
        putMetadata("model", model != null ? model : "");
        putMetadata("inputTokens", inputTokens);
        putMetadata("cachedInputTokens", cachedInputTokens);
        putMetadata("outputTokens", outputTokens);
        putMetadata("cost", cost);
        putMetadata("durationMs", durationMs);
    }

    public String getSessionId() { return sessionId; }
    public String getSeqId() { return seqId; }
    public String getModel() { return model; }
    public int getInputTokens() { return inputTokens; }
    public int getCachedInputTokens() { return cachedInputTokens; }
    public int getOutputTokens() { return outputTokens; }
    public double getCost() { return cost; }
    public long getDurationMs() { return durationMs; }
}
