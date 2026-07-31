package com.ccagent.core.sync.model;

public class SyncResult {
    private int uploaded;
    private int downloaded;
    private int errors;
    private long durationMs;
    private String message;

    public SyncResult() {}

    public SyncResult(int uploaded, int downloaded, int errors, long durationMs) {
        this.uploaded = uploaded;
        this.downloaded = downloaded;
        this.errors = errors;
        this.durationMs = durationMs;
        this.message = "同步完成: 上传 " + uploaded + " 条, 下载 " + downloaded + " 条, 错误 " + errors + " 条";
    }

    public int getUploaded() { return uploaded; }
    public void setUploaded(int uploaded) { this.uploaded = uploaded; }
    public int getDownloaded() { return downloaded; }
    public void setDownloaded(int downloaded) { this.downloaded = downloaded; }
    public int getErrors() { return errors; }
    public void setErrors(int errors) { this.errors = errors; }
    public long getDurationMs() { return durationMs; }
    public void setDurationMs(long durationMs) { this.durationMs = durationMs; }
    public String getMessage() { return message; }
    public void setMessage(String message) { this.message = message; }

    public boolean hasError() { return errors > 0; }
}
