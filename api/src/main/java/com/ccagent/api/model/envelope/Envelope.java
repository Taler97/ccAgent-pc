package com.ccagent.api.model.envelope;

/**
 * WebSocket 统一消息信封
 * <p>所有 WebSocket 消息的统一外层包装。</p>
 */
public class Envelope {

    private String type;
    private String seqId;
    private String timestamp;
    private Object payload;

    public Envelope() {}

    public Envelope(String type, String seqId, String timestamp, Object payload) {
        this.type = type;
        this.seqId = seqId;
        this.timestamp = timestamp;
        this.payload = payload;
    }

    public String getType() { return type; }
    public void setType(String type) { this.type = type; }
    public String getSeqId() { return seqId; }
    public void setSeqId(String seqId) { this.seqId = seqId; }
    public String getTimestamp() { return timestamp; }
    public void setTimestamp(String timestamp) { this.timestamp = timestamp; }
    public Object getPayload() { return payload; }
    public void setPayload(Object payload) { this.payload = payload; }
}
