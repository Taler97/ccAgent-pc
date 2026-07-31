package com.ccagent.plugin.experts.impl;

import com.ccagent.api.context.TaskScope;
import com.ccagent.api.model.response.StreamChunk;
import com.ccagent.api.port.Expert;
import com.ccagent.api.port.StreamObserver;
import com.ccagent.core.expert.annotation.ExpertAnnotation;
import com.ccagent.infrastructure.constant.Constants;
import com.ccagent.infrastructure.util.FileUtil;
import com.ccagent.infrastructure.util.JsonUtil;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.*;
import java.util.logging.Logger;
import java.util.stream.Collectors;

/**
 * 步骤执行失败诊断专家
 * <p>分析步骤执行失败的原因，生成诊断报告。
 * 调用方式: {@code /diagnose <seqId>}，专家从 errors.log 读取错误记录，
 * 结合 event.log、audit.log 和工作区信息，调用 LLM 分析根因并给出解决方案。</p>
 *
 * <h3>报告结构</h3>
 * <ul>
 *   <li>基本信息（时间、会话、seqId、步骤、专家、错误类型/类名）</li>
 *   <li>报错原因</li>
 *   <li>如何复现</li>
 *   <li>解决方案</li>
 * </ul>
 */
@ExpertAnnotation(
    name = "fault-analyzer",
    description = "步骤执行失败诊断专家，分析报错原因并提供解决方案",
    category = "analysis"
)
public class FaultAnalyzerExpert extends BaseExpert {

    private static final Logger LOG = Logger.getLogger(FaultAnalyzerExpert.class.getName());

    /** 错误日志文件路径 */
    private static final String ERROR_LOG = Constants.BASE_DIR + "/errors.log";
    /** 诊断报告输出目录 */
    private static final String DIAGNOSTICS_DIR = Constants.BASE_DIR + "/diagnostics";

    @Override
    public String getName() {
        return "fault-analyzer";
    }

    @Override
    public String getDescription() {
        return "步骤执行失败诊断专家，分析报错原因并提供解决方案";
    }

    @Override
    public String execute(String task, TaskScope taskScope, Map<String, Object> inputs,
                           String workspaceRoot, StreamObserver<StreamChunk> observer) {
        // 从 inputs 或 task 中提取 seqId
        String seqId = null;
        if (inputs != null && inputs.containsKey("seqId")) {
            seqId = (String) inputs.get("seqId");
        }
        if (seqId == null || seqId.isBlank()) {
            // 从 task 中解析: "diagnose seq-a1b2"
            String t = task != null ? task.trim() : "";
            if (t.startsWith("diagnose ")) {
                seqId = t.substring("diagnose ".length()).trim();
            } else {
                seqId = t;
            }
        }
        if (seqId == null || seqId.isBlank()) {
            String err = "缺少 seqId 参数，用法: /diagnose <seqId>";
            observer.onNext(new StreamChunk(null, "ERROR", err, null));
            return err;
        }

        pushStatus(observer, "RUNNING", "正在收集诊断数据: " + seqId);

        try {
            // 1. 收集原始数据
            Map<String, Object> errorRecord = readErrorRecord(seqId);
            List<Map<String, Object>> eventChain = readEventChain(seqId);
            List<Map<String, Object>> auditRecords = readAuditRecords(seqId);

            if (errorRecord == null) {
                String err = "未找到 seqId=" + seqId + " 的错误记录";
                observer.onNext(new StreamChunk(null, "ERROR", err, null));
                return err;
            }

            pushStatus(observer, "RUNNING", "正在分析失败原因...");

            // 2. 构建 LLM 分析上下文
            String analysisPrompt = buildAnalysisPrompt(errorRecord, eventChain, auditRecords, workspaceRoot);

            // 3. 调用 LLM 分析
            String report = callLlm(buildSystemPrompt(), analysisPrompt, observer);
            if (report == null || report.isBlank()) {
                report = "LLM 分析无返回结果，请检查 LLM 配置";
            }

            // 4. 写入报告文件
            String reportPath = writeReport(seqId, report, errorRecord);

            // 5. 输出结果
            String summary = "诊断报告已生成: " + reportPath + "\n\n"
                    + "══════════════════ 诊断摘要 ══════════════════\n"
                    + truncate(report, 2000);
            observer.onNext(new StreamChunk(null, "STREAM_CHUNK", summary, null));
            pushStatus(observer, "SUCCESS", "诊断完成");

            return report;

        } catch (Exception e) {
            String err = "诊断失败: " + e.getMessage();
            LOG.warning(err);
            observer.onNext(new StreamChunk(null, "ERROR", err, null));
            return err;
        }
    }

    /**
     * 构建系统提示词
     */
    private String buildSystemPrompt() {
        return "你是一个专业的软件工程故障诊断专家。你的任务是根据以下上下文信息，分析步骤执行失败的原因。\n\n"
                + "请严格按照以下 Markdown 格式输出诊断报告，不要添加额外说明：\n\n"
                + "## 基本信息\n"
                + "- **时间**: {错误发生时间}\n"
                + "- **会话 ID**: {sessionId}\n"
                + "- **执行 ID**: {seqId}\n"
                + "- **失败步骤**: {stepId}/{totalSteps}\n"
                + "- **执行专家**: {expertName}\n"
                + "- **错误消息**: {error}\n"
                + "- **错误类型**: {根据错误消息推断}\n\n"
                + "## 报错原因\n"
                + "{分析导致步骤执行失败的根本原因}\n\n"
                + "## 复现步骤\n"
                + "1. {第一步}\n"
                + "2. {第二步}\n"
                + "...\n\n"
                + "## 解决方案\n"
                + "{提供具体的解决建议}\n\n"
                + "请基于提供的错误记录、事件链和审计记录进行分析。如果信息不足以确定根因，请如实说明。";
    }

    /**
     * 构建分析提示词
     */
    private String buildAnalysisPrompt(Map<String, Object> errorRecord,
                                        List<Map<String, Object>> eventChain,
                                        List<Map<String, Object>> auditRecords,
                                        String workspaceRoot) {
        StringBuilder sb = new StringBuilder();
        sb.append("## 错误记录\n\n");
        sb.append("```json\n").append(JsonUtil.toJson(errorRecord)).append("\n```\n\n");

        if (!eventChain.isEmpty()) {
            sb.append("## 事件链（event.log）\n\n");
            sb.append("```json\n");
            for (Map<String, Object> evt : eventChain) {
                sb.append(JsonUtil.toJson(evt)).append("\n");
            }
            sb.append("```\n\n");
        }

        if (!auditRecords.isEmpty()) {
            sb.append("## 审计记录（audit.log）\n\n");
            sb.append("包含工具调用链等信息：\n");
            sb.append("```json\n");
            for (Map<String, Object> record : auditRecords) {
                sb.append(JsonUtil.toJson(record)).append("\n");
            }
            sb.append("```\n\n");
        }

        if (workspaceRoot != null && !workspaceRoot.isBlank()) {
            sb.append("## 工作区根路径\n\n").append(workspaceRoot).append("\n\n");
        }

        sb.append("请根据以上信息生成诊断报告。");
        return sb.toString();
    }

    // ==================== 数据读取 ====================

    /**
     * 从 errors.log 读取指定 seqId 的错误记录
     */
    @SuppressWarnings("unchecked")
    private Map<String, Object> readErrorRecord(String seqId) {
        try {
            Path path = Paths.get(ERROR_LOG);
            if (!Files.exists(path)) return null;
            String content = FileUtil.readString(path);
            String[] lines = content.split("\n");
            for (String line : lines) {
                line = line.trim();
                if (line.isEmpty()) continue;
                try {
                    Map<String, Object> record = JsonUtil.fromJson(line, Map.class);
                    if (seqId.equals(record.get("seqId"))) {
                        return record;
                    }
                } catch (Exception ignored) {
                    // skip malformed lines
                }
            }
        } catch (Exception e) {
            LOG.warning("读取错误日志失败: " + e.getMessage());
        }
        return null;
    }

    /**
     * 从 event.log 读取指定 seqId 的事件链
     */
    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> readEventChain(String seqId) {
        List<Map<String, Object>> events = new ArrayList<>();
        try {
            Path path = Paths.get(Constants.EVENT_LOG);
            if (!Files.exists(path)) return events;
            String content = FileUtil.readString(path);
            String[] lines = content.split("\n");
            for (String line : lines) {
                line = line.trim();
                if (line.isEmpty()) continue;
                try {
                    Map<String, Object> event = JsonUtil.fromJson(line, Map.class);
                    if (event != null) {
                        Map<String, Object> metadata = (Map<String, Object>) event.get("metadata");
                        if (metadata != null && seqId.equals(metadata.get("seqId"))) {
                            events.add(event);
                        }
                    }
                } catch (Exception ignored) {
                }
            }
        } catch (Exception e) {
            LOG.warning("读取事件日志失败: " + e.getMessage());
        }
        return events;
    }

    /**
     * 从 audit.log 读取指定 seqId 的审计记录（特别是 TOOL_CALL）
     */
    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> readAuditRecords(String seqId) {
        List<Map<String, Object>> records = new ArrayList<>();
        try {
            Path path = Paths.get(Constants.AUDIT_LOG);
            if (!Files.exists(path)) return records;
            String content = FileUtil.readString(path);
            String[] lines = content.split("\n");
            for (String line : lines) {
                line = line.trim();
                if (line.isEmpty()) continue;
                try {
                    Map<String, Object> record = JsonUtil.fromJson(line, Map.class);
                    if (record != null) {
                        Map<String, Object> details = (Map<String, Object>) record.get("details");
                        if (details != null && seqId.equals(details.get("seqId"))) {
                            records.add(record);
                        }
                    }
                } catch (Exception ignored) {
                }
            }
        } catch (Exception e) {
            LOG.warning("读取审计日志失败: " + e.getMessage());
        }
        return records;
    }

    // ==================== 报告写入 ====================

    /**
     * 将诊断报告写入文件
     */
    private String writeReport(String seqId, String report, Map<String, Object> errorRecord) {
        try {
            Path dir = Paths.get(DIAGNOSTICS_DIR);
            FileUtil.createDirectories(dir);
            Path reportPath = dir.resolve(seqId + ".md");

            // 构建完整报告
            StringBuilder full = new StringBuilder();
            full.append("# 故障诊断报告\n\n");
            full.append("**生成时间**: ").append(Instant.now().toString()).append("\n\n");
            full.append("---\n\n");
            full.append(report);

            FileUtil.writeString(reportPath, full.toString());
            return reportPath.toString();
        } catch (Exception e) {
            LOG.warning("写入诊断报告失败: " + e.getMessage());
            return DIAGNOSTICS_DIR + "/" + seqId + ".md (写入失败: " + e.getMessage() + ")";
        }
    }
}
