package com.ccagent.exception.base;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class ErrorCodeTest {

    @Test
    void shouldHave15ErrorCodes() {
        assertThat(ErrorCode.values()).hasSize(15);
    }

    @Test
    void shouldHaveDefaultTemplateForAllCodes() {
        for (ErrorCode code : ErrorCode.values()) {
            assertThat(code.getDefaultTemplate())
                    .as("ErrorCode %s 必须有默认消息模板", code.name())
                    .isNotNull()
                    .isNotEmpty();
        }
    }

    @Test
    void shouldFormatMessage() {
        String msg = ErrorCode.LLM_TIMEOUT.formatMessage(30);
        assertThat(msg).isEqualTo("LLM 调用超时（30 秒）");
    }

    @Test
    void shouldFormatQuotaExceeded() {
        String msg = ErrorCode.QUOTA_EXCEEDED.formatMessage(50000, 100000);
        assertThat(msg).isEqualTo("Token 配额超限（当前 50000，上限 100000）");
    }

    @Test
    void shouldFormatPathTraversal() {
        String msg = ErrorCode.PATH_TRAVERSAL_DETECTED.formatMessage("/etc/passwd");
        assertThat(msg).isEqualTo("路径 /etc/passwd 超出工作区范围");
    }

    @Test
    void shouldFormatParseError() {
        String msg = ErrorCode.PARSE_ERROR.formatMessage("invalid json");
        assertThat(msg).isEqualTo("解析失败: invalid json");
    }
}
