package com.ccagent.api.model.response;

/**
 * 执行结果
 * <p>工具调用或对话执行的结果封装。</p>
 */
public class ExecutionResult {

    private boolean success;
    private String message;
    private String path;
    private int linesChanged;
    private int tokensUsed;
    private String backupId;

    public ExecutionResult() {}

    public ExecutionResult(boolean success, String message, String path,
                           int linesChanged, int tokensUsed, String backupId) {
        this.success = success;
        this.message = message;
        this.path = path;
        this.linesChanged = linesChanged;
        this.tokensUsed = tokensUsed;
        this.backupId = backupId;
    }

    public boolean isSuccess() { return success; }
    public void setSuccess(boolean success) { this.success = success; }
    public String getMessage() { return message; }
    public void setMessage(String message) { this.message = message; }
    public String getPath() { return path; }
    public void setPath(String path) { this.path = path; }
    public int getLinesChanged() { return linesChanged; }
    public void setLinesChanged(int linesChanged) { this.linesChanged = linesChanged; }
    public int getTokensUsed() { return tokensUsed; }
    public void setTokensUsed(int tokensUsed) { this.tokensUsed = tokensUsed; }
    public String getBackupId() { return backupId; }
    public void setBackupId(String backupId) { this.backupId = backupId; }
}
