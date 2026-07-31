package com.ccagent.exception.base;

import com.ccagent.exception.llm.LlmException;
import com.ccagent.exception.tool.ToolException;
import com.ccagent.exception.workspace.WorkspaceException;
import com.ccagent.exception.approval.ApprovalException;
import com.ccagent.exception.security.SecurityException;
import com.ccagent.exception.governance.GovernanceException;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class AgentExceptionTest {

    @Test
    void shouldCreateLlmExceptionWithTimeout() {
        LlmException e = new LlmException(ErrorCode.LLM_TIMEOUT, "LLM 超时", 30);
        assertThat(e.getErrorCode()).isEqualTo(ErrorCode.LLM_TIMEOUT);
        assertThat(e.getMessage()).isEqualTo("LLM 超时");
        assertThat(e.getTimeoutSeconds()).isEqualTo(30);
        assertThat(e).isInstanceOf(AgentException.class);
        assertThat(e).isInstanceOf(RuntimeException.class);
    }

    @Test
    void shouldCreateLlmExceptionWithCause() {
        Exception cause = new java.io.IOException("connection reset");
        LlmException e = new LlmException(ErrorCode.LLM_SERVICE_UNAVAILABLE,
                "服务不可用", cause, 30, "/v1/chat");
        assertThat(e.getCause()).isEqualTo(cause);
        assertThat(e.getEndpoint()).isEqualTo("/v1/chat");
        assertThat(e.getTimeoutSeconds()).isEqualTo(30);
    }

    @Test
    void shouldCreateToolException() {
        ToolException e = new ToolException(ErrorCode.TOOL_NOT_FOUND,
                "工具不存在", "write_file", null);
        assertThat(e.getToolName()).isEqualTo("write_file");
        assertThat(e.getErrorCode()).isEqualTo(ErrorCode.TOOL_NOT_FOUND);
    }

    @Test
    void shouldCreateToolExceptionWithCause() {
        Exception cause = new java.io.IOException("permission denied");
        ToolException e = new ToolException(ErrorCode.TOOL_EXECUTION_FAILED,
                "执行失败", cause, "read_file", "/tmp/test.java");
        assertThat(e.getCause()).isEqualTo(cause);
        assertThat(e.getFilePath()).isEqualTo("/tmp/test.java");
    }

    @Test
    void shouldCreateWorkspaceException() {
        WorkspaceException e = new WorkspaceException(ErrorCode.SESSION_NOT_FOUND,
                "会话不存在", "ws-123", "sess-abc");
        assertThat(e.getWorkspaceId()).isEqualTo("ws-123");
        assertThat(e.getSessionId()).isEqualTo("sess-abc");
    }

    @Test
    void shouldCreateApprovalException() {
        ApprovalException e = new ApprovalException(ErrorCode.APPROVAL_TIMEOUT,
                "审批超时", 120, "seq-001");
        assertThat(e.getTimeoutSeconds()).isEqualTo(120);
        assertThat(e.getSeqId()).isEqualTo("seq-001");
    }

    @Test
    void shouldCreateSecurityException() {
        SecurityException e = new SecurityException(ErrorCode.PATH_TRAVERSAL_DETECTED,
                "路径穿越", "/etc/passwd", "/home/workspace");
        assertThat(e.getRequestedPath()).isEqualTo("/etc/passwd");
        assertThat(e.getWorkspaceRoot()).isEqualTo("/home/workspace");
    }

    @Test
    void shouldCreateGovernanceExceptionWithQuota() {
        GovernanceException e = new GovernanceException(ErrorCode.QUOTA_EXCEEDED,
                "配额超限", 80000, 100000);
        assertThat(e.getCurrentUsage()).isEqualTo(80000);
        assertThat(e.getMaxLimit()).isEqualTo(100000);
    }

    @Test
    void shouldCreateGovernanceExceptionWithPlanInvalid() {
        GovernanceException e = new GovernanceException(ErrorCode.PLAN_INVALID,
                "Plan 校验失败", "步骤数 12 超过上限 10");
        assertThat(e.getValidationDetails()).isEqualTo("步骤数 12 超过上限 10");
    }

    @Test
    void shouldCreateGovernanceExceptionWithBackupFailed() {
        GovernanceException e = new GovernanceException(ErrorCode.BACKUP_FAILED,
                "备份失败", new RuntimeException("disk full"), "/staging/seq-1");
        assertThat(e.getBackupPath()).isEqualTo("/staging/seq-1");
        assertThat(e.getCause()).isInstanceOf(RuntimeException.class);
    }
}
