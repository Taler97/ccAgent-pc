package com.ccagent.kernel.event;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public abstract class Event {

    private final String id;
    private final Instant timestamp;
    private final Map<String, Object> metadata;

    protected Event() {
        this.id = UUID.randomUUID().toString();
        this.timestamp = Instant.now();
        this.metadata = new HashMap<>();
    }

    protected Event(Map<String, Object> metadata) {
        this.id = UUID.randomUUID().toString();
        this.timestamp = Instant.now();
        this.metadata = metadata != null ? new HashMap<>(metadata) : new HashMap<>();
    }

    public String getId() {
        return id;
    }

    public Instant getTimestamp() {
        return timestamp;
    }

    public Map<String, Object> getMetadata() {
        return metadata;
    }

    public Object getMetadata(String key) {
        return metadata.get(key);
    }

    public void putMetadata(String key, Object value) {
        metadata.put(key, value);
    }

    public String getEventType() {
        return this.getClass().getSimpleName();
    }
}
