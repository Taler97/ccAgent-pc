package com.ccagent.presentation.cli;

import com.ccagent.api.model.request.ExecuteRequest;
import com.ccagent.api.model.response.StreamChunk;
import com.ccagent.api.port.Expert;
import com.ccagent.api.port.ExpertRegistryPort;
import com.ccagent.api.port.OrchestratorPort;
import com.ccagent.api.port.StreamObserver;
import com.ccagent.core.sync.LocalDataRepository;
import com.ccagent.core.sync.SyncScheduler;
import com.ccagent.infrastructure.config.AppConfig;
import com.ccagent.infrastructure.constant.Constants;
import com.ccagent.infrastructure.util.FileUtil;
import com.ccagent.infrastructure.util.JsonUtil;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Scanner;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Logger;

/**
 * 独立 CLI 运行器
 * <p>引擎嵌入进程，直接对话。执行异步运行，主循环持续接受命令。</p>
 */
public class StandaloneAgentRunner {

    private static final Logger LOG = Logger.getLogger(StandaloneAgentRunner.class.getName());

    private final OrchestratorPort orchestrator;
    private final AppConfig config;
    private final SyncScheduler syncScheduler;
    private final LocalDataRepository localRepo;
    private final ExpertRegistryPort expertRegistry;
    private final String promptSymbol;
    private volatile boolean running = true;
    private String currentSessionId;

    // 执行状态（跨线程可见，同包测试可访问）
    volatile boolean isExecuting = false;
    volatile boolean planPending = false;
    volatile String pendingSeqId = null;
    final ConcurrentHashMap<Integer, StepState> stepStates = new ConcurrentHashMap<>();
    private final AtomicInteger totalSteps = new AtomicInteger(0);
    private String planSummary = "";

    // 步骤状态记录（同包测试可访问）
    static class StepState {
        final int id;
        String expertName;
        String task;
        String status = "pending";
        String error;

        StepState(int id) { this.id = id; }
    }

    public StandaloneAgentRunner(OrchestratorPort orchestrator, AppConfig config,
                                  SyncScheduler syncScheduler, LocalDataRepository localRepo,
                                  ExpertRegistryPort expertRegistry) {
        this.orchestrator = orchestrator;
        this.config = config;
        this.syncScheduler = syncScheduler;
        this.localRepo = localRepo;
        this.expertRegistry = expertRegistry;
        this.promptSymbol = config.getCli() != null ? config.getCli().getPrompt() : "ccAgent> ";
        this.currentSessionId = "session-" + UUID.randomUUID().toString().substring(0, 8);
    }

    // 测试用：注入 Scanner
    StandaloneAgentRunner(OrchestratorPort orchestrator, AppConfig config,
                          SyncScheduler syncScheduler, LocalDataRepository localRepo,
                          ExpertRegistryPort expertRegistry, Scanner testScanner) {
        this(orchestrator, config, syncScheduler, localRepo, expertRegistry);
        this.testScanner = testScanner;
    }
    private Scanner testScanner;

    // ==================== 主循环 ====================

    public void start() {
        String workspaceDir = config.getAuth().getWorkspaceDir();
        if (workspaceDir == null || workspaceDir.isBlank()) {
            workspaceDir = ".";
        }
        orchestrator.createSession(currentSessionId, workspaceDir);

        if (syncScheduler != null) {
            syncScheduler.start();
        }

        printBanner();

        Scanner scanner = testScanner != null ? testScanner : new Scanner(System.in);
        while (running) {
            if (!isExecuting) {
                System.out.print(promptSymbol);
            } else if (!planPending) {
                System.out.print("exec> ");
            }

            String input = scanner.nextLine();
            if (input == null) break;

            String trimmed = input.trim();
            if (trimmed.isEmpty()) continue;

            if (trimmed.startsWith("/")) {
                handleCommand(trimmed);
            } else if (!isExecuting) {
                executePromptAsync(trimmed);
            } else {
                System.out.println("执行中，请输入控制命令 (/cancel, /confirm, /reject)");
            }
        }

        if (syncScheduler != null) {
            if (syncScheduler.isCloudReachable()) {
                System.out.println("正在同步数据到云端...");
                syncScheduler.syncNow();
            }
            syncScheduler.shutdown();
        }
        System.out.println("ccAgent 已退出");
    }

    // ==================== 异步执行 ====================

    private void executePromptAsync(String prompt) {
        isExecuting = true;
        planPending = false;
        pendingSeqId = null;
        stepStates.clear();
        totalSteps.set(0);
        planSummary = "";

        String seqId = "seq-" + UUID.randomUUID().toString().substring(0, 8);
        pendingSeqId = seqId;

        Thread.startVirtualThread(() -> {
            try {
                ExecuteRequest request = new ExecuteRequest(
                        currentSessionId, currentSessionId, prompt, false);

                System.out.println();
                orchestrator.executeWithSeqId(request, seqId, new StreamObserver<>() {
                    @Override
                    public void onNext(StreamChunk chunk) {
                        handleStreamChunk(chunk, seqId);
                    }

                    @Override
                    public void onError(Throwable error) {
                        System.out.println("\n[错误] " + error.getMessage());
                        resetExecutionState();
                    }

                    @Override
                    public void onComplete() {
                        printStepSummary();
                        System.out.println();
                        resetExecutionState();
                    }
                });
            } catch (Exception e) {
                System.out.println("\n执行失败: " + e.getMessage());
                resetExecutionState();
            }
        });
    }

    // ==================== 流式事件处理 ====================

    void handleStreamChunk(StreamChunk chunk, String seqId) {
        String type = chunk.getType();
        String content = chunk.getContent();
        if (content == null) content = "";

        switch (type) {
            case "PLAN_READY" -> handlePlanReady(content, seqId);
            case "STEP_STATUS" -> handleStepStatus(content, seqId);
            case "STREAM_CHUNK" -> {
                System.out.print(content);
                System.out.flush();
            }
            case "EXPERT_STATUS" -> System.out.println("\n[专家] " + content);
            case "EXECUTION_RESULT" -> handleExecutionResult(content);
            case "PLAN_CANCELLED" -> System.out.println("\n[Plan 已取消]");
            case "ERROR" -> System.out.println("\n[错误] " + content);
        }
    }

    @SuppressWarnings("unchecked")
    private void handlePlanReady(String content, String seqId) {
        try {
            Map<String, Object> data = JsonUtil.fromJson(content, Map.class);
            planSummary = (String) data.getOrDefault("summary", "");
            Object stepsRaw = data.get("steps");
            int stepCount = 0;
            if (stepsRaw instanceof Number n) {
                stepCount = n.intValue();
            }
            totalSteps.set(stepCount);

            System.out.println("\n╔══════════════════════════════════════════════╗");
            System.out.println("║  执行计划                                     ║");
            System.out.println("╠══════════════════════════════════════════════╣");
            if (!planSummary.isBlank()) {
                System.out.println("║  目标: " + truncate(planSummary, 50));
            }
            System.out.println("║  步骤: " + stepCount + " 步                                        ║");
            System.out.println("╚══════════════════════════════════════════════╝");

            planPending = true;
            pendingSeqId = seqId;
            System.out.println("输入 /confirm 执行  |  /reject 拒绝");

        } catch (Exception e) {
            System.out.println("\n[Plan 解析失败] " + e.getMessage());
        }
    }

    @SuppressWarnings("unchecked")
    private void handleStepStatus(String content, String seqId) {
        try {
            Map<String, Object> map = JsonUtil.fromJson(content, Map.class);
            int stepId = map.containsKey("stepId") ? ((Number) map.get("stepId")).intValue() : 0;
            int total = map.containsKey("totalSteps") ? ((Number) map.get("totalSteps")).intValue() : 0;
            String status = (String) map.getOrDefault("status", "UNKNOWN");
            String expertName = (String) map.get("expertName");

            if (total > 0) totalSteps.set(total);

            StepState state = stepStates.computeIfAbsent(stepId, StepState::new);
            if (expertName != null) state.expertName = expertName;
            state.status = status;

            // 失败时持久化错误信息
            if ("FAILED".equals(status) && map.containsKey("error")) {
                String error = (String) map.get("error");
                state.error = error;
                persistError(seqId, stepId, total, expertName, error);
            }

            String icon = switch (status) {
                case "RUNNING" -> "🔄";
                case "SUCCESS" -> "✅";
                case "FAILED" -> "❌";
                case "ROLLED_BACK" -> "↩️";
                case "CANCELLED" -> "⏭️";
                default -> "⬜";
            };

            String expertLabel = expertName != null ? expertName : "?";
            System.out.println("\n  " + icon + " 步骤 " + stepId + "/" + total
                    + " (" + expertLabel + "): " + status);

        } catch (Exception e) {
            System.out.println("\n[步骤状态解析失败] " + e.getMessage());
        }
    }

    private void handleExecutionResult(String content) {
        try {
            Map<String, Object> data = JsonUtil.fromJson(content, Map.class);
            System.out.println();
            if (data.containsKey("message")) {
                System.out.println(data.get("message"));
            }
        } catch (Exception e) {
            if (!content.isBlank()) {
                System.out.println("\n" + content);
            }
        }
    }

    // ==================== 命令处理 ====================

    void handleCommand(String cmd) {
        String[] parts = cmd.split("\\s+", 2);
        String command = parts[0].toLowerCase();

        switch (command) {
            case "/exit", "/quit" -> handleExit();
            case "/help" -> printHelp();
            case "/sync" -> handleSync();
            case "/sessions" -> handleSessions();
            case "/session" -> handleSession(parts);
            case "/switch" -> handleSwitch(parts);
            case "/status" -> handleStatus();
            case "/confirm" -> handleConfirm();
            case "/reject" -> handleReject();
            case "/cancel" -> handleCancel();
            case "/rollback" -> handleRollback(parts);
            case "/history" -> handleHistory(parts);
            case "/ls" -> handleLs(parts);
            case "/cat" -> handleCat(parts);
            case "/tokens" -> handleTokens();
            case "/experts" -> handleExperts();
            case "/clear" -> handleClear();
            case "/diagnose" -> handleDiagnose(parts);
            default -> System.out.println("未知命令: " + command + " (输入 /help 查看命令列表)");
        }
    }

    // ==================== 执行控制 ====================

    private void handleConfirm() {
        if (!planPending || pendingSeqId == null) {
            System.out.println("当前没有待确认的 Plan");
            return;
        }
        try {
            orchestrator.executePlan(pendingSeqId, true);
            planPending = false;
            System.out.println("Plan 已确认，开始执行...");
        } catch (Exception e) {
            System.out.println("确认 Plan 失败: " + e.getMessage());
        }
    }

    private void handleReject() {
        if (!planPending || pendingSeqId == null) {
            System.out.println("当前没有待确认的 Plan");
            return;
        }
        try {
            orchestrator.executePlan(pendingSeqId, false);
            planPending = false;
            System.out.println("Plan 已拒绝");
        } catch (Exception e) {
            System.out.println("拒绝 Plan 失败: " + e.getMessage());
        }
    }

    private void handleCancel() {
        if (!isExecuting || pendingSeqId == null) {
            System.out.println("当前没有正在执行的任务");
            return;
        }
        try {
            orchestrator.cancelExecution(pendingSeqId);
            System.out.println("取消请求已发送");
        } catch (Exception e) {
            System.out.println("取消执行失败: " + e.getMessage());
        }
    }

    private void handleRollback(String[] parts) {
        String seqId = parts.length > 1 ? parts[1].trim() : pendingSeqId;
        if (seqId == null || seqId.isBlank()) {
            System.out.println("用法: /rollback [seqId]");
            return;
        }
        try {
            boolean success = orchestrator.rollbackDialog(seqId);
            System.out.println(success ? "回滚成功" : "回滚失败");
        } catch (Exception e) {
            System.out.println("回滚失败: " + e.getMessage());
        }
    }

    // ==================== 会话管理 ====================

    private void handleSession(String[] parts) {
        if (parts.length < 2 || parts[1].isBlank()) {
            System.out.println("用法:");
            System.out.println("  /session <id>                   查看会话详情");
            System.out.println("  /session create --name <n> --dir <p>  新建会话");
            return;
        }
        String arg = parts[1].trim();
        if (arg.startsWith("create")) {
            handleSessionCreate(parts);
            return;
        }
        // /session <id> — 查看详情
        showSessionDetail(arg);
    }

    private void handleSessionCreate(String[] parts) {
        String name = "";
        String dir = "";
        String full = parts.length > 1 ? parts[1] : "";
        // 简单解析 --name <v> --dir <v>
        String[] tokens = full.split("\\s+");
        for (int i = 0; i < tokens.length; i++) {
            if ("--name".equals(tokens[i]) && i + 1 < tokens.length) name = tokens[++i];
            if ("--dir".equals(tokens[i]) && i + 1 < tokens.length) dir = tokens[++i];
        }
        String newId = "session-" + UUID.randomUUID().toString().substring(0, 8);
        String wsDir = dir.isBlank() ? config.getAuth().getWorkspaceDir() : dir;
        if (wsDir == null || wsDir.isBlank()) wsDir = ".";
        orchestrator.createSession(newId, wsDir);
        currentSessionId = newId;
        String label = !name.isBlank() ? name : newId;
        System.out.println("已创建并切换到会话: " + label + "  (工作区: " + wsDir + ")");
    }

    @SuppressWarnings("unchecked")
    private void showSessionDetail(String sessionId) {
        try {
            var sessions = orchestrator.listSessions();
            var opt = sessions.stream().filter(s -> s.getSessionId().equals(sessionId)).findFirst();
            if (opt.isEmpty()) {
                System.out.println("会话不存在: " + sessionId);
                return;
            }
            var s = opt.get();
            int convCount = 0;
            try {
                convCount = localRepo != null ? localRepo.countConversationsBySession(sessionId) : 0;
            } catch (Exception ignored) {}

            System.out.println("╔══════════════════════════════════════════╗");
            System.out.println("║  会话详情                               ║");
            System.out.println("╠══════════════════════════════════════════╣");
            System.out.println("║  ID:         " + s.getSessionId());
            System.out.println("║  工作区:     " + (s.getRootPath() != null ? s.getRootPath() : "-"));
            System.out.println("║  对话轮次:   " + s.getTotalTurns());
            System.out.println("║  对话记录:   " + convCount + " 条");
            if (s.getSummary() != null && !s.getSummary().isBlank()) {
                System.out.println("║  摘要:       " + truncate(s.getSummary(), 36));
            }
            System.out.println("╚══════════════════════════════════════════╝");
        } catch (Exception e) {
            System.out.println("查看会话详情失败: " + e.getMessage());
        }
    }

    private void handleSessions() {
        try {
            var sessions = orchestrator.listSessions();
            if (sessions.isEmpty()) {
                System.out.println("暂无会话");
                return;
            }
            System.out.println("╔══════════════════════════════════════════════════════════╗");
            System.out.println("║  会话列表                                                ║");
            System.out.println("╠══════════════════════════════════════════════════════════╣");
            for (var s : sessions) {
                String marker = s.getSessionId().equals(currentSessionId) ? " ← 当前" : "";
                String turns = "  轮次:" + s.getTotalTurns();
                String summary = s.getSummary() != null ? s.getSummary() : "";
                String line = "║  " + s.getSessionId() + marker;
                line = padRight(line, 55) + "║";
                System.out.println(line);
                String detail = "║    " + turns;
                if (!summary.isBlank()) detail += "  " + truncate(summary, 35);
                detail = padRight(detail, 55) + "║";
                System.out.println(detail);
            }
            System.out.println("╚══════════════════════════════════════════════════════════╝");
        } catch (Exception e) {
            System.out.println("获取会话列表失败: " + e.getMessage());
        }
    }

    private void handleSwitch(String[] parts) {
        if (parts.length < 2) {
            System.out.println("用法: /switch <sessionId>");
            return;
        }
        String newId = parts[1].trim();
        String workspaceDir = config.getAuth().getWorkspaceDir();
        if (workspaceDir == null || workspaceDir.isBlank()) workspaceDir = ".";
        orchestrator.createSession(newId, workspaceDir);
        currentSessionId = newId;
        System.out.println("已切换到会话: " + currentSessionId);
    }

    // ==================== 历史记录 ====================

    @SuppressWarnings("unchecked")
    private void handleHistory(String[] parts) {
        if (localRepo == null) {
            System.out.println("本地数据仓库不可用");
            return;
        }
        String sub = parts.length > 1 ? parts[1].trim().toLowerCase() : "";
        try {
            if ("detail".equals(sub) && parts.length > 2) {
                showHistoryDetail(parts[2].trim());
            } else if ("search".equals(sub) && parts.length > 2) {
                String keyword = parts[1].substring("search".length()).trim();
                if (keyword.isEmpty() && parts.length > 2) keyword = parts[2];
                searchHistory(keyword);
            } else {
                listHistory();
            }
        } catch (Exception e) {
            System.out.println("历史记录操作失败: " + e.getMessage());
        }
    }

    private void listHistory() {
        List<Map<String, Object>> rows = localRepo.listConversationsBySession(currentSessionId, 1, 20);
        if (rows.isEmpty()) {
            System.out.println("暂无历史记录");
            return;
        }
        System.out.println("╔══════════════════════════════════════════════════════════╗");
        System.out.println("║  历史记录 (最近 20 条)                                    ║");
        System.out.println("╠══════════════════════════════════════════════════════════╣");
        for (Map<String, Object> row : rows) {
            String seqId = str(row.get("seq_id"));
            String prompt = str(row.get("prompt"));
            String status = str(row.get("status"));
            String tokens = row.containsKey("total_tokens") ? row.get("total_tokens").toString() : "-";
            String time = str(row.get("created_at"));
            time = time.length() > 16 ? time.substring(0, 16) : time;
            String line = "║  " + truncate(seqId, 12) + "  " + truncate(prompt, 22)
                    + "  " + statusIcon(status) + "  " + padLeft(tokens, 6) + "  " + time;
            line = padRight(line, 55) + "║";
            System.out.println(line);
        }
        System.out.println("╚══════════════════════════════════════════════════════════╝");
        System.out.println("用法: /history detail <seqId>  |  /history search <keyword>");
    }

    private void showHistoryDetail(String seqId) {
        Map<String, Object> row = localRepo.queryConversation(seqId);
        if (row.isEmpty()) {
            System.out.println("未找到记录: " + seqId);
            return;
        }
        System.out.println("╔══════════════════════════════════════════╗");
        System.out.println("║  对话详情                               ║");
        System.out.println("╠══════════════════════════════════════════╣");
        System.out.println("║  ID:   " + str(row.get("seq_id")));
        System.out.println("║  时间: " + str(row.get("created_at")));
        System.out.println("║  状态: " + str(row.get("status")));
        System.out.println("║  Token: " + row.getOrDefault("total_tokens", "-")
                + "  缓存: " + row.getOrDefault("cached_tokens", "-"));
        System.out.println("║  提示: " + truncate(str(row.get("prompt")), 40));
        String resp = str(row.get("assistant_response"));
        if (!resp.isBlank()) {
            System.out.println("║  回复: " + truncate(resp, 40));
        }
        System.out.println("╚══════════════════════════════════════════╝");
    }

    private void searchHistory(String keyword) {
        List<Map<String, Object>> rows = localRepo.searchConversations(keyword, 1, 10);
        if (rows.isEmpty()) {
            System.out.println("未找到匹配的记录，关键词: " + keyword);
            return;
        }
        System.out.println("╔══════════════════════════════════════════════════════════╗");
        System.out.println("║  搜索结果: " + truncate(keyword, 20) + "                          ║");
        System.out.println("╠══════════════════════════════════════════════════════════╣");
        for (Map<String, Object> row : rows) {
            String seqId = str(row.get("seq_id"));
            String prompt = str(row.get("prompt"));
            String status = str(row.get("status"));
            String line = "║  " + truncate(seqId, 12) + "  " + truncate(prompt, 30)
                    + "  " + statusIcon(status);
            line = padRight(line, 55) + "║";
            System.out.println(line);
        }
        System.out.println("╚══════════════════════════════════════════════════════════╝");
    }

    // ==================== 文件浏览 ====================

    private void handleLs(String[] parts) {
        try {
            String wsRoot = orchestrator.loadWorkspaceRoot(currentSessionId);
            if (wsRoot == null || wsRoot.isBlank()) {
                System.out.println("当前会话未绑定工作区");
                return;
            }
            Path base = Paths.get(wsRoot).normalize();
            String subPath = parts.length > 1 ? parts[1].trim() : "";
            Path target = subPath.isEmpty() ? base : base.resolve(subPath).normalize();
            if (!target.startsWith(base)) {
                System.out.println("路径超出工作区范围");
                return;
            }
            if (!Files.isDirectory(target)) {
                System.out.println("目录不存在: " + (subPath.isEmpty() ? "." : subPath));
                return;
            }
            List<Path> entries = FileUtil.listFiles(target);
            System.out.println("╔══════════════════════════════════════════╗");
            System.out.println("║  " + base.relativize(target).toString().replace("\\", "/")
                    + "  (" + entries.size() + " 项)");
            System.out.println("╠══════════════════════════════════════════╣");
            for (Path entry : entries) {
                String name = entry.getFileName().toString();
                String type = Files.isDirectory(entry) ? "📁" : "📄";
                long size = Files.isRegularFile(entry) ? Files.size(entry) : 0;
                String sizeStr = size > 0 ? (size > 1024 ? (size / 1024) + "KB" : size + "B") : "";
                System.out.println("  " + type + "  " + name + (sizeStr.isEmpty() ? "" : "  (" + sizeStr + ")"));
            }
            System.out.println("╚══════════════════════════════════════════╝");
        } catch (Exception e) {
            System.out.println("列出目录失败: " + e.getMessage());
        }
    }

    private void handleCat(String[] parts) {
        if (parts.length < 2 || parts[1].isBlank()) {
            System.out.println("用法: /cat <文件路径>");
            return;
        }
        try {
            String wsRoot = orchestrator.loadWorkspaceRoot(currentSessionId);
            if (wsRoot == null || wsRoot.isBlank()) {
                System.out.println("当前会话未绑定工作区");
                return;
            }
            Path base = Paths.get(wsRoot).normalize();
            Path target = base.resolve(parts[1].trim()).normalize();
            if (!target.startsWith(base)) {
                System.out.println("路径超出工作区范围");
                return;
            }
            if (!Files.isRegularFile(target)) {
                System.out.println("文件不存在: " + parts[1].trim());
                return;
            }
            String content = FileUtil.readString(target);
            int maxLines = 200;
            String[] lines = content.split("\n", -1);
            if (lines.length > maxLines) {
                System.out.println("(文件 " + lines.length + " 行，显示前 " + maxLines + " 行)");
                StringBuilder sb = new StringBuilder();
                for (int i = 0; i < maxLines; i++) sb.append(lines[i]).append("\n");
                content = sb.toString();
            }
            System.out.println("╔══════════════════════════════════════════╗");
            System.out.println("║  " + target.getFileName() + "  (" + lines.length + " 行)");
            System.out.println("╚══════════════════════════════════════════╝");
            System.out.println(content);
        } catch (Exception e) {
            System.out.println("读取文件失败: " + e.getMessage());
        }
    }

    // ==================== Token 统计 ====================

    private void handleTokens() {
        if (localRepo == null) {
            System.out.println("本地数据仓库不可用");
            return;
        }
        try {
            long totalTokens = 0;
            long totalCached = 0;
            int convCount = 0;
            int page = 1;
            while (true) {
                var rows = localRepo.listConversationsBySession(currentSessionId, page, 50);
                if (rows.isEmpty()) break;
                for (var row : rows) {
                    totalTokens += toLong(row.get("total_tokens"));
                    totalCached += toLong(row.get("cached_tokens"));
                    convCount++;
                }
                if (rows.size() < 50) break;
                page++;
            }
            double cacheRate = totalTokens > 0 ? (double) totalCached / totalTokens : 0;
            double cost = (totalTokens - totalCached) / 1_000_000.0 * 0.07
                    + totalCached / 1_000_000.0 * 0.014;

            System.out.println("╔══════════════════════════════════════════╗");
            System.out.println("║  Token 用量                              ║");
            System.out.println("╠══════════════════════════════════════════╣");
            System.out.println("║  对话轮次:   " + convCount);
            System.out.println("║  总 Token:   " + totalTokens + "  (缓存 " + totalCached + ")");
            System.out.println("║  缓存命中率: " + String.format("%.1f%%", cacheRate * 100));
            System.out.println("║  预估费用:   $" + String.format("%.6f", cost));
            System.out.println("╚══════════════════════════════════════════╝");
        } catch (Exception e) {
            System.out.println("查询 Token 统计失败: " + e.getMessage());
        }
    }

    // ==================== 专家列表 ====================

    private void handleExperts() {
        System.out.println("可用专家: planner  writer  researcher  organizer  note-taker  summarizer");
    }

    // ==================== 清屏 ====================

    private void handleClear() {
        try {
            if (System.getProperty("os.name").contains("Windows")) {
                new ProcessBuilder("cmd", "/c", "cls").inheritIO().start().waitFor();
            } else {
                System.out.print("\033[H\033[2J");
                System.out.flush();
            }
        } catch (Exception e) {
            // fallback
            for (int i = 0; i < 50; i++) System.out.println();
        }
    }

    // ==================== 故障诊断 ====================

    private void handleDiagnose(String[] parts) {
        if (expertRegistry == null) {
            System.out.println("专家注册表不可用");
            return;
        }
        try {
            // /diagnose --list 列出最近错误
            if (parts.length > 1 && "--list".equals(parts[1].trim())) {
                listErrorRecords();
                return;
            }

            String seqId = parts.length > 1 ? parts[1].trim() : "";
            if (seqId.isBlank()) {
                System.out.println("用法: /diagnose <seqId>  分析指定失败");
                System.out.println("      /diagnose --list   列出最近错误记录");
                return;
            }

            // 获取诊断专家
            Expert expert = expertRegistry.getExpert("fault-analyzer");
            if (expert == null) {
                System.out.println("诊断专家不可用");
                return;
            }

            System.out.println("正在诊断 seqId=" + seqId + " ...");

            // 调用专家（使用旧接口，无需 TaskScope）
            StringBuilder output = new StringBuilder();
            expert.execute(seqId, config.getAuth().getWorkspaceDir(), new StreamObserver<>() {
                @Override
                public void onNext(StreamChunk chunk) {
                    String content = chunk.getContent();
                    if (content != null && !content.isEmpty()) {
                        System.out.print(content);
                        System.out.flush();
                        output.append(content);
                    }
                }

                @Override
                public void onError(Throwable error) {
                    System.out.println("\n诊断失败: " + error.getMessage());
                }

                @Override
                public void onComplete() {
                    System.out.println();
                }
            });
        } catch (Exception e) {
            System.out.println("诊断失败: " + e.getMessage());
        }
    }

    /**
     * 列出 errors.log 中的错误记录
     */
    @SuppressWarnings("unchecked")
    private void listErrorRecords() {
        try {
            Path path = Paths.get(Constants.BASE_DIR, "errors.log");
            if (!Files.exists(path)) {
                System.out.println("暂无错误记录");
                return;
            }
            String content = FileUtil.readString(path);
            String[] lines = content.split("\n");
            if (lines.length == 0 || (lines.length == 1 && lines[0].isBlank())) {
                System.out.println("暂无错误记录");
                return;
            }

            System.out.println("╔══════════════════════════════════════════════════════════╗");
            System.out.println("║  最近错误记录  (" + lines.length + " 条)                              ║");
            System.out.println("╠══════════════════════════════════════════════════════════╣");
            // 显示最近 10 条（从后往前）
            int start = Math.max(0, lines.length - 10);
            for (int i = start; i < lines.length; i++) {
                String line = lines[i].trim();
                if (line.isEmpty()) continue;
                try {
                    Map<String, Object> record = JsonUtil.fromJson(line, Map.class);
                    String sid = str(record.get("seqId"));
                    String expert = str(record.get("expertName"));
                    String err = str(record.get("error"));
                    String ts = str(record.get("timestamp"));
                    if (ts.length() > 19) ts = ts.substring(0, 19);
                    System.out.println("║  " + padRight(sid, 14) + " " + padRight(expert, 12)
                            + " " + padRight(ts, 19) + "  ║");
                    System.out.println("║    " + truncate(err, 50) + "  ║");
                } catch (Exception ignored) {
                }
            }
            System.out.println("╚══════════════════════════════════════════════════════════╝");
            System.out.println("用法: /diagnose <seqId>  分析指定失败");
        } catch (Exception e) {
            System.out.println("读取错误记录失败: " + e.getMessage());
        }
    }

    /** 错误日志文件路径 */
    private static final String ERROR_LOG = Constants.BASE_DIR + "/errors.log";

    /**
     * 持久化错误信息到 errors.log
     */
    private void persistError(String seqId, int stepId, int total, String expertName, String error) {
        try {
            Map<String, Object> record = new LinkedHashMap<>();
            record.put("seqId", seqId);
            record.put("sessionId", currentSessionId);
            record.put("timestamp", Instant.now().toString());
            record.put("stepId", stepId);
            record.put("totalSteps", total);
            record.put("expertName", expertName != null ? expertName : "");
            record.put("error", error);
            if (planSummary != null && !planSummary.isBlank()) {
                record.put("planSummary", planSummary);
            }
            String ws = config.getAuth().getWorkspaceDir();
            if (ws != null && !ws.isBlank()) {
                record.put("workspaceRoot", ws);
            }

            String json = JsonUtil.toJson(record);
            FileUtil.createDirectories(Paths.get(Constants.BASE_DIR));
            FileUtil.appendString(Paths.get(ERROR_LOG), json + System.lineSeparator());
        } catch (Exception e) {
            LOG.warning("写入错误日志失败: " + e.getMessage());
        }
    }

    // ==================== 系统命令 ====================

    private void handleExit() {
        if (isExecuting) {
            System.out.println("执行中，输入 /exit 再次确认退出");
            running = false;
        } else {
            running = false;
        }
    }

    private void handleSync() {
        System.out.println("正在同步数据到云端...");
        if (syncScheduler != null) syncScheduler.syncNow();
    }

    private void handleStatus() {
        System.out.println("╔══════════════════════════════════════════╗");
        System.out.println("║  状态                                    ║");
        System.out.println("╠══════════════════════════════════════════╣");
        System.out.println("║  当前会话: " + currentSessionId);
        String ws = config.getAuth().getWorkspaceDir();
        System.out.println("║  工作区:   " + (ws != null && !ws.isBlank() ? ws : "."));
        System.out.println("║  云端连接: " + (syncScheduler != null && syncScheduler.isCloudReachable() ? "已连接" : "未连接"));
        System.out.println("║  执行状态: " + (isExecuting ? "运行中" : "空闲"));
        if (planPending) {
            System.out.println("║  Plan:    待确认                            ║");
        }
        System.out.println("╚══════════════════════════════════════════╝");
    }

    // ==================== 工具方法 ====================

    private void printStepSummary() {
        if (stepStates.isEmpty()) return;
        int done = (int) stepStates.values().stream()
                .filter(s -> "SUCCESS".equals(s.status)).count();
        int failed = (int) stepStates.values().stream()
                .filter(s -> "FAILED".equals(s.status)).count();
        int total = stepStates.size();
        System.out.println("\n  步骤: " + done + "/" + total + " 成功"
                + (failed > 0 ? ", " + failed + " 失败" : ""));
    }

    private void resetExecutionState() {
        isExecuting = false;
        planPending = false;
        pendingSeqId = null;
    }

    private static String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, Math.max(0, max - 3)) + "...";
    }

    private static String padRight(String s, int len) {
        StringBuilder sb = new StringBuilder(s);
        while (sb.length() < len) sb.append(' ');
        return sb.toString();
    }

    private static String padLeft(String s, int len) {
        StringBuilder sb = new StringBuilder();
        while (sb.length() + s.length() < len) sb.append(' ');
        sb.append(s);
        return sb.toString();
    }

    private static String str(Object o) {
        return o != null ? o.toString() : "";
    }

    private static long toLong(Object o) {
        if (o == null) return 0;
        if (o instanceof Number n) return n.longValue();
        try { return Long.parseLong(o.toString()); } catch (Exception e) { return 0; }
    }

    private static String statusIcon(String status) {
        if (status == null) return " ";
        return switch (status.toUpperCase()) {
            case "SUCCESS", "COMPLETED" -> "✅";
            case "FAILED", "ERROR" -> "❌";
            case "RUNNING", "EXECUTING" -> "🔄";
            case "CANCELLED" -> "⏭️";
            default -> " ";
        };
    }

    private void printBanner() {
        System.out.println("╔══════════════════════════════════════════╗");
        System.out.println("║        ccAgent 独立运行模式               ║");
        System.out.println("║  会话: " + currentSessionId + "          ║");
        System.out.println("║  输入 /help 查看命令列表                 ║");
        System.out.println("║  输入 /exit 退出                         ║");
        System.out.println("╚══════════════════════════════════════════╝");
    }

    private void printHelp() {
        System.out.println("╔══════════════════════════════════════════╗");
        System.out.println("║  执行控制                                 ║");
        System.out.println("║  /confirm      确认当前 Plan 并执行      ║");
        System.out.println("║  /reject       拒绝当前 Plan             ║");
        System.out.println("║  /cancel       取消正在执行的任务        ║");
        System.out.println("║  /rollback     回滚上次操作              ║");
        System.out.println("║                                           ║");
        System.out.println("║  会话管理                                 ║");
        System.out.println("║  /session      查看/创建会话             ║");
        System.out.println("║  /sessions     列出所有会话              ║");
        System.out.println("║  /switch <id>  切换会话                  ║");
        System.out.println("║                                           ║");
        System.out.println("║  历史记录                                 ║");
        System.out.println("║  /history      查看对话历史              ║");
        System.out.println("║  /history detail <seqId>  查看详情       ║");
        System.out.println("║  /history search <kw>     搜索历史       ║");
        System.out.println("║                                           ║");
        System.out.println("║  文件浏览                                 ║");
        System.out.println("║  /ls [path]    列出工作区目录            ║");
        System.out.println("║  /cat <file>   查看文件内容              ║");
        System.out.println("║                                           ║");
        System.out.println("║  故障诊断                                 ║");
        System.out.println("║  /diagnose <seqId>  分析执行失败原因      ║");
        System.out.println("║  /diagnose --list   列出最近错误记录      ║");
        System.out.println("║                                           ║");
        System.out.println("║  系统                                     ║");
        System.out.println("║  /status       显示当前状态              ║");
        System.out.println("║  /sync         手动同步数据到云端        ║");
        System.out.println("║  /exit         退出程序                   ║");
        System.out.println("║  /help         显示此帮助                 ║");
        System.out.println("║                                           ║");
        System.out.println("║  直接输入文本即可与 AI 对话              ║");
        System.out.println("╚══════════════════════════════════════════╝");
    }

    public void stop() {
        running = false;
    }
}
