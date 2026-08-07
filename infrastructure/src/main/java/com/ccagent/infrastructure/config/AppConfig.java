package com.ccagent.infrastructure.config;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 全局配置 POJO
 * <p>对应 {@code ~/.code-pal/config.yaml} 的结构映射。</p>
 */
public class AppConfig {

    private LlmConfig llm = new LlmConfig();
    private MemoryConfig memory = new MemoryConfig();
    private WebSocketConfig websocket = new WebSocketConfig();
    private ApprovalConfig approval = new ApprovalConfig();
    private GovernanceConfig governance = new GovernanceConfig();
    private AuthConfig auth = new AuthConfig();
    private SyncConfig sync = new SyncConfig();
    private CliConfig cli = new CliConfig();
    private HttpConfig http = new HttpConfig();

    // ====== 分组配置 ======

    public static class LlmConfig {
        private String apiKey = "";
        private int timeoutStreaming = 60;
        private int timeoutSync = 30;
        private String model = "deepseek-v4-flash";
        private String baseUrl = "https://api.deepseek.com";
        // DeepSeek 思考模式：默认关闭，避免工具调用多轮时 reasoning_content 回传问题
        private boolean thinkingEnabled = false;
        // 思考强度：high / max
        private String reasoningEffort = "high";

        public String getApiKey() { return apiKey; }
        public void setApiKey(String apiKey) { this.apiKey = apiKey; }
        public int getTimeoutStreaming() { return timeoutStreaming; }
        public void setTimeoutStreaming(int timeoutStreaming) { this.timeoutStreaming = timeoutStreaming; }
        public int getTimeoutSync() { return timeoutSync; }
        public void setTimeoutSync(int timeoutSync) { this.timeoutSync = timeoutSync; }
        public String getModel() { return model; }
        public void setModel(String model) { this.model = model; }
        public String getBaseUrl() { return baseUrl; }
        public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }
        public boolean isThinkingEnabled() { return thinkingEnabled; }
        public void setThinkingEnabled(boolean thinkingEnabled) { this.thinkingEnabled = thinkingEnabled; }
        public String getReasoningEffort() { return reasoningEffort; }
        public void setReasoningEffort(String reasoningEffort) { this.reasoningEffort = reasoningEffort; }
    }

    public static class MemoryConfig {
        private int maxTurns = 30;
        private int flushInterval = 3;
        private boolean compressionEnabled = true;
        private int compressionRatio = 3;
        private int summaryMaxTokens = 2000;

        public int getMaxTurns() { return maxTurns; }
        public void setMaxTurns(int maxTurns) { this.maxTurns = maxTurns; }
        public int getFlushInterval() { return flushInterval; }
        public void setFlushInterval(int flushInterval) { this.flushInterval = flushInterval; }
        public boolean isCompressionEnabled() { return compressionEnabled; }
        public void setCompressionEnabled(boolean compressionEnabled) { this.compressionEnabled = compressionEnabled; }
        public int getCompressionRatio() { return compressionRatio; }
        public void setCompressionRatio(int compressionRatio) { this.compressionRatio = compressionRatio; }
        public int getSummaryMaxTokens() { return summaryMaxTokens; }
        public void setSummaryMaxTokens(int summaryMaxTokens) { this.summaryMaxTokens = summaryMaxTokens; }
    }

    public static class WebSocketConfig {
        private int heartbeatInterval = 30;
        private int heartbeatTimeout = 10;
        private int reconnectInitialDelay = 1;
        private int reconnectMaxDelay = 60;
        private String url = "ws://localhost:8080/ws";

        public int getHeartbeatInterval() { return heartbeatInterval; }
        public void setHeartbeatInterval(int heartbeatInterval) { this.heartbeatInterval = heartbeatInterval; }
        public int getHeartbeatTimeout() { return heartbeatTimeout; }
        public void setHeartbeatTimeout(int heartbeatTimeout) { this.heartbeatTimeout = heartbeatTimeout; }
        public int getReconnectInitialDelay() { return reconnectInitialDelay; }
        public void setReconnectInitialDelay(int reconnectInitialDelay) { this.reconnectInitialDelay = reconnectInitialDelay; }
        public int getReconnectMaxDelay() { return reconnectMaxDelay; }
        public void setReconnectMaxDelay(int reconnectMaxDelay) { this.reconnectMaxDelay = reconnectMaxDelay; }
        public String getUrl() { return url; }
        public void setUrl(String url) { this.url = url; }
    }

    public static class ApprovalConfig {
        private int timeout = 120;

        public int getTimeout() { return timeout; }
        public void setTimeout(int timeout) { this.timeout = timeout; }
    }

    public static class GovernanceConfig {
        private int maxTokensPerSession = 100000;
        private int maxPlanSteps = 10;
        private boolean backupEnabled = true;
        private int backupRetentionHours = 24;
        private int rollbackRetryCount = 3;
        private int rollbackRetryIntervalSeconds = 1;
        private String rollbackProvider = "auto";
        private String auditLevel = "summary";
        private int auditBufferSize = 1000;
        private int auditFlushIntervalSeconds = 5;
        private boolean tokenStatsEnabled = true;
        private PricingConfig pricing = new PricingConfig();
        private boolean prefixCacheOptimization = true;
        private boolean isolationEnabled = false;
        private String isolationProvider = "noop";
        private boolean eventPersistenceEnabled = true;
        private int eventBufferSize = 1000;
        private int eventFlushIntervalSeconds = 5;
        private int eventRateLimitPerSecond = 0;
        /** 额外允许访问的目录（工作区之外，路径校验白名单） */
        private List<String> allowedPaths = new ArrayList<>();

        public int getMaxTokensPerSession() { return maxTokensPerSession; }
        public void setMaxTokensPerSession(int maxTokensPerSession) { this.maxTokensPerSession = maxTokensPerSession; }
        public int getMaxPlanSteps() { return maxPlanSteps; }
        public void setMaxPlanSteps(int maxPlanSteps) { this.maxPlanSteps = maxPlanSteps; }
        public boolean isBackupEnabled() { return backupEnabled; }
        public void setBackupEnabled(boolean backupEnabled) { this.backupEnabled = backupEnabled; }
        public int getBackupRetentionHours() { return backupRetentionHours; }
        public void setBackupRetentionHours(int backupRetentionHours) { this.backupRetentionHours = backupRetentionHours; }
        public int getRollbackRetryCount() { return rollbackRetryCount; }
        public void setRollbackRetryCount(int rollbackRetryCount) { this.rollbackRetryCount = rollbackRetryCount; }
        public int getRollbackRetryIntervalSeconds() { return rollbackRetryIntervalSeconds; }
        public void setRollbackRetryIntervalSeconds(int rollbackRetryIntervalSeconds) { this.rollbackRetryIntervalSeconds = rollbackRetryIntervalSeconds; }
        public String getRollbackProvider() { return rollbackProvider; }
        public void setRollbackProvider(String rollbackProvider) { this.rollbackProvider = rollbackProvider; }
        public String getAuditLevel() { return auditLevel; }
        public void setAuditLevel(String auditLevel) { this.auditLevel = auditLevel; }
        public int getAuditBufferSize() { return auditBufferSize; }
        public void setAuditBufferSize(int auditBufferSize) { this.auditBufferSize = auditBufferSize; }
        public int getAuditFlushIntervalSeconds() { return auditFlushIntervalSeconds; }
        public void setAuditFlushIntervalSeconds(int auditFlushIntervalSeconds) { this.auditFlushIntervalSeconds = auditFlushIntervalSeconds; }
        public boolean isTokenStatsEnabled() { return tokenStatsEnabled; }
        public void setTokenStatsEnabled(boolean tokenStatsEnabled) { this.tokenStatsEnabled = tokenStatsEnabled; }
        public PricingConfig getPricing() { return pricing; }
        public void setPricing(PricingConfig pricing) { this.pricing = pricing; }
        public boolean isPrefixCacheOptimization() { return prefixCacheOptimization; }
        public void setPrefixCacheOptimization(boolean prefixCacheOptimization) { this.prefixCacheOptimization = prefixCacheOptimization; }
        public boolean isIsolationEnabled() { return isolationEnabled; }
        public void setIsolationEnabled(boolean isolationEnabled) { this.isolationEnabled = isolationEnabled; }
        public String getIsolationProvider() { return isolationProvider; }
        public void setIsolationProvider(String isolationProvider) { this.isolationProvider = isolationProvider; }
        public boolean isEventPersistenceEnabled() { return eventPersistenceEnabled; }
        public void setEventPersistenceEnabled(boolean eventPersistenceEnabled) { this.eventPersistenceEnabled = eventPersistenceEnabled; }
        public int getEventBufferSize() { return eventBufferSize; }
        public void setEventBufferSize(int eventBufferSize) { this.eventBufferSize = eventBufferSize; }
        public int getEventFlushIntervalSeconds() { return eventFlushIntervalSeconds; }
        public void setEventFlushIntervalSeconds(int eventFlushIntervalSeconds) { this.eventFlushIntervalSeconds = eventFlushIntervalSeconds; }
        public int getEventRateLimitPerSecond() { return eventRateLimitPerSecond; }
        public void setEventRateLimitPerSecond(int eventRateLimitPerSecond) { this.eventRateLimitPerSecond = eventRateLimitPerSecond; }
        public List<String> getAllowedPaths() { return allowedPaths; }
        public void setAllowedPaths(List<String> allowedPaths) { this.allowedPaths = allowedPaths; }
    }

    public static class PricingConfig {
        private double inputPerMillion = 0.07;
        private double cachedInputPerMillion = 0.014;
        private double outputPerMillion = 0.28;

        // getter/setter...
        public double getInputPerMillion() { return inputPerMillion; }
        public void setInputPerMillion(double inputPerMillion) { this.inputPerMillion = inputPerMillion; }
        public double getCachedInputPerMillion() { return cachedInputPerMillion; }
        public void setCachedInputPerMillion(double cachedInputPerMillion) { this.cachedInputPerMillion = cachedInputPerMillion; }
        public double getOutputPerMillion() { return outputPerMillion; }
        public void setOutputPerMillion(double outputPerMillion) { this.outputPerMillion = outputPerMillion; }
    }

    public static class AuthConfig {
        private String token = "";
        private String apiKey = "Qse2ffed";
        private String workspaceDir = "";

        public String getToken() { return token; }
        public void setToken(String token) { this.token = token; }
        public String getApiKey() { return apiKey; }
        public void setApiKey(String apiKey) { this.apiKey = apiKey; }
        public String getWorkspaceDir() { return workspaceDir; }
        public void setWorkspaceDir(String workspaceDir) { this.workspaceDir = workspaceDir; }
    }

    // ====== 顶层 getter/setter ======

    public LlmConfig getLlm() { return llm; }
    public void setLlm(LlmConfig llm) { this.llm = llm; }
    public MemoryConfig getMemory() { return memory; }
    public void setMemory(MemoryConfig memory) { this.memory = memory; }
    public WebSocketConfig getWebsocket() { return websocket; }
    public void setWebsocket(WebSocketConfig websocket) { this.websocket = websocket; }
    public ApprovalConfig getApproval() { return approval; }
    public void setApproval(ApprovalConfig approval) { this.approval = approval; }
    public AuthConfig getAuth() { return auth; }
    public void setAuth(AuthConfig auth) { this.auth = auth; }
    public GovernanceConfig getGovernance() { return governance; }
    public void setGovernance(GovernanceConfig governance) { this.governance = governance; }
    public SyncConfig getSync() { return sync; }
    public void setSync(SyncConfig sync) { this.sync = sync; }
    public CliConfig getCli() { return cli; }
    public void setCli(CliConfig cli) { this.cli = cli; }
    public HttpConfig getHttp() { return http; }
    public void setHttp(HttpConfig http) { this.http = http; }

    // ====== 新增配置段 ======

    public static class SyncConfig {
        private boolean enabled = true;
        private String cloudUrl = "http://localhost:8080";
        private String apiKey = "Qse2ffed";
        private int intervalMinutes = 5;
        private boolean autoSync = true;

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        public String getCloudUrl() { return cloudUrl; }
        public void setCloudUrl(String cloudUrl) { this.cloudUrl = cloudUrl; }
        public String getApiKey() { return apiKey; }
        public void setApiKey(String apiKey) { this.apiKey = apiKey; }
        public int getIntervalMinutes() { return intervalMinutes; }
        public void setIntervalMinutes(int intervalMinutes) { this.intervalMinutes = intervalMinutes; }
        public boolean isAutoSync() { return autoSync; }
        public void setAutoSync(boolean autoSync) { this.autoSync = autoSync; }
    }

    public static class CliConfig {
        private String prompt = "ccAgent> ";
        private boolean streamOutput = true;
        private boolean colorEnabled = true;

        public String getPrompt() { return prompt; }
        public void setPrompt(String prompt) { this.prompt = prompt; }
        public boolean isStreamOutput() { return streamOutput; }
        public void setStreamOutput(boolean streamOutput) { this.streamOutput = streamOutput; }
        public boolean isColorEnabled() { return colorEnabled; }
        public void setColorEnabled(boolean colorEnabled) { this.colorEnabled = colorEnabled; }
    }

    /** HTTP 服务配置 */
    public static class HttpConfig {
        private int port = 3000;

        public int getPort() { return port; }
        public void setPort(int port) { this.port = port; }
    }
}
