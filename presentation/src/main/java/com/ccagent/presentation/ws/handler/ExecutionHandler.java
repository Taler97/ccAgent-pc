package com.ccagent.presentation.ws.handler;

import com.ccagent.api.model.envelope.Envelope;
import com.ccagent.api.model.request.ExecuteRequest;
import com.ccagent.api.model.response.StreamChunk;
import com.ccagent.api.port.OrchestratorPort;
import com.ccagent.api.port.StreamObserver;
import com.ccagent.presentation.ws.MessageRouter.MessageSender;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 执行控制 Handler：EXECUTE / CANCEL_EXECUTION / ROLLBACK_DIALOG / TOOL_APPROVAL / EXECUTE_PLAN / CANCEL_PLAN
 */
public class ExecutionHandler extends HandlerSupport implements WsMessageHandler {

    private final OrchestratorPort governanceGateway;
    /** 执行体异步执行器：handler 快速返回，seqId 锁只持毫秒级（D5：长任务锁外） */
    private final ExecutorService executionExecutor = Executors.newVirtualThreadPerTaskExecutor();

    public ExecutionHandler(OrchestratorPort governanceGateway) {
        this.governanceGateway = governanceGateway;
    }

    @Override
    public List<String> supportedTypes() {
        return List.of(
                "EXECUTE", "CANCEL_EXECUTION", "ROLLBACK_DIALOG",
                "TOOL_APPROVAL", "EXECUTE_PLAN", "CANCEL_PLAN"
        );
    }

    @Override
    public void handle(Envelope envelope, MessageSender sender) {
        switch (envelope.getType()) {
            case "EXECUTE"          -> execute(envelope, sender);
            case "CANCEL_EXECUTION" -> cancel(envelope, sender);
            case "ROLLBACK_DIALOG"  -> rollback(envelope, sender);
            case "TOOL_APPROVAL"    -> approval(envelope);
            case "EXECUTE_PLAN"     -> executePlan(envelope, sender);
            case "CANCEL_PLAN"      -> cancelPlan(envelope, sender);
        }
    }

    private void execute(Envelope envelope, MessageSender sender) {
        String seqId = envelope.getSeqId();
        sendAgentStatus(sender, seqId, "PLANNING", "正在生成执行计划...");

        ExecuteRequest request = payloadAs(envelope, ExecuteRequest.class);
        // 异步提交执行体：handler 快速返回，seqId 锁只持毫秒级（D5：长任务锁外执行）
        executionExecutor.submit(() -> {
            try {
                governanceGateway.executeWithSeqId(request, seqId, new StreamObserver<>() {
                    @Override
                    public void onNext(StreamChunk chunk) {
                        // Plan 就绪即进入"等待用户确认"阶段：补发 WAIT_APPROVAL，让前端
                        // 以 AGENT_STATUS 为唯一状态源派生交互状态，无需自行推断 planPending
                        if ("PLAN_READY".equals(chunk.getType())) {
                            sendAgentStatus(sender, seqId, "WAIT_APPROVAL", "等待确认执行计划");
                        }
                        sender.send(new Envelope(chunk.getType(), seqId,
                                Instant.now().toString(), chunk.getContent()));
                    }

                    @Override
                    public void onError(Throwable error) {
                        sendAgentStatus(sender, seqId, "ERROR", error.getMessage());
                        sender.send(new Envelope("ERROR", seqId,
                                Instant.now().toString(),
                                "{\"error\":\"" + safeMsg(error.getMessage()) + "\"}"));
                    }

                    @Override
                    public void onComplete() {
                        sendAgentStatus(sender, seqId, "IDLE", "");
                        // 不重复发送 EXECUTION_RESULT：SimpleOrchestrator 已通过 onNext 发送带 token 统计的结果消息，
                        // 此处再发 {"status":"COMPLETED"} 会覆盖云端 conversation 的 token 数据（同 seqId 覆盖保存）
                    }
                });
            } catch (Throwable t) {
                sendAgentStatus(sender, seqId, "ERROR", t.getMessage());
                sender.send(new Envelope("ERROR", seqId,
                        Instant.now().toString(),
                        "{\"error\":\"" + safeMsg(t.getMessage()) + "\"}"));
            }
        });
    }

    private static String safeMsg(String msg) {
        return msg != null ? msg.replace("\"", "'") : "执行异常";
    }

    private void cancel(Envelope envelope, MessageSender sender) {
        String seqId = envelope.getSeqId();
        sendAgentStatus(sender, seqId, "IDLE", "已取消执行");
        governanceGateway.cancelExecution(seqId);
    }

    private void rollback(Envelope envelope, MessageSender sender) {
        String seqId = envelope.getSeqId();
        sendAgentStatus(sender, seqId, "ROLLBACK", "正在回滚文件变更...");
        boolean success = governanceGateway.rollbackDialog(seqId);
        sendAgentStatus(sender, seqId, "IDLE", success ? "回滚成功" : "回滚失败");
        String status = success ? "SUCCESS" : "FAILED";
        sender.send(new Envelope("ROLLBACK_RESULT", seqId,
                Instant.now().toString(),
                "{\"status\":\"" + status + "\"}"));
    }

    private void approval(Envelope envelope) {
        Map<String, Object> data = payloadAsMap(envelope);
        boolean approved = Boolean.TRUE.equals(data.get("approved"));
        governanceGateway.onToolApproval(envelope.getSeqId(), approved);
    }

    private void executePlan(Envelope envelope, MessageSender sender) {
        String seqId = envelope.getSeqId();
        Map<String, Object> data = payloadAsMap(envelope);
        boolean confirmed = Boolean.TRUE.equals(data.get("confirmed"));
        // 按确认结果上报状态：确认 → EXECUTING；拒绝 → IDLE（此前无条件发 EXECUTING，拒绝时前端误入执行中）
        sendAgentStatus(sender, seqId, confirmed ? "EXECUTING" : "IDLE",
                confirmed ? "正在执行任务..." : "已拒绝执行计划");
        governanceGateway.executePlan(seqId, confirmed);
    }

    private void cancelPlan(Envelope envelope, MessageSender sender) {
        String seqId = envelope.getSeqId();
        sendAgentStatus(sender, seqId, "IDLE", "已取消 Plan");
        governanceGateway.cancelPlan(seqId);
    }
}
