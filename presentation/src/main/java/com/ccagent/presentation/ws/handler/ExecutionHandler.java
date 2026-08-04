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

/**
 * 执行控制 Handler：EXECUTE / CANCEL_EXECUTION / ROLLBACK_DIALOG / TOOL_APPROVAL / EXECUTE_PLAN / CANCEL_PLAN
 */
public class ExecutionHandler extends HandlerSupport implements WsMessageHandler {

    private final OrchestratorPort governanceGateway;

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
        governanceGateway.executeWithSeqId(request, seqId, new StreamObserver<>() {
            @Override
            public void onNext(StreamChunk chunk) {
                sender.send(new Envelope(chunk.getType(), seqId,
                        Instant.now().toString(), chunk.getContent()));
            }

            @Override
            public void onError(Throwable error) {
                sendAgentStatus(sender, seqId, "ERROR", error.getMessage());
                sender.send(new Envelope("ERROR", seqId,
                        Instant.now().toString(),
                        "{\"error\":\"" + error.getMessage() + "\"}"));
            }

            @Override
            public void onComplete() {
                sendAgentStatus(sender, seqId, "IDLE", "");
                sender.send(new Envelope("EXECUTION_RESULT", seqId,
                        Instant.now().toString(),
                        "{\"status\":\"COMPLETED\"}"));
            }
        });
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
        sendAgentStatus(sender, seqId, "EXECUTING", "正在执行任务...");
        Map<String, Object> data = payloadAsMap(envelope);
        boolean confirmed = Boolean.TRUE.equals(data.get("confirmed"));
        governanceGateway.executePlan(seqId, confirmed);
    }

    private void cancelPlan(Envelope envelope, MessageSender sender) {
        String seqId = envelope.getSeqId();
        sendAgentStatus(sender, seqId, "IDLE", "已取消 Plan");
        governanceGateway.cancelPlan(seqId);
    }
}
