package com.ccagent.api.model.response;

/**
 * 流式响应块
 * <p>LLM 流式调用或执行过程中的增量数据块。</p>
 */
public class StreamChunk {

    private String seqId;
    private String type;
    private String content;
    private String finishReason;
    private int totalTokens;
    private int cachedTokens;

    public StreamChunk() {}

    public StreamChunk(String seqId, String type, String content, String finishReason) {
        this.seqId = seqId;
        this.type = type;
        this.content = content;
        this.finishReason = finishReason;
    }

    public String getSeqId() { return seqId; }
    public void setSeqId(String seqId) { this.seqId = seqId; }
    public String getType() { return type; }
    public void setType(String type) { this.type = type; }
    public String getContent() { return content; }
    public void setContent(String content) { this.content = content; }
    public String getFinishReason() { return finishReason; }
    public void setFinishReason(String finishReason) { this.finishReason = finishReason; }
    public int getTotalTokens() { return totalTokens; }
    public void setTotalTokens(int totalTokens) { this.totalTokens = totalTokens; }
    public int getCachedTokens() { return cachedTokens; }
    public void setCachedTokens(int cachedTokens) { this.cachedTokens = cachedTokens; }
}
