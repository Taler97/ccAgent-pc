package com.ccagent.infrastructure.config;

import com.ccagent.exception.tool.ToolException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Files;
import java.nio.file.Path;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ConfigLoaderTest {

    @Test
    void shouldLoadDefaultConfigWhenFileNotExists() {
        AppConfig config = ConfigLoader.load("/nonexistent/config.yaml");
        assertThat(config).isNotNull();
        assertThat(config.getLlm().getModel()).isEqualTo("deepseek-v4-flash");
        assertThat(config.getMemory().getMaxTurns()).isEqualTo(30);
        assertThat(config.getGovernance().getMaxTokensPerSession()).isEqualTo(100000);
    }

    @Test
    void shouldThrowOnInvalidYaml(@TempDir Path tempDir) throws Exception {
        Path configFile = tempDir.resolve("config.yaml");
        Files.writeString(configFile, "{ invalid yaml: [");
        assertThatThrownBy(() -> ConfigLoader.load(configFile.toString()))
                .isInstanceOf(ToolException.class);
    }

    @Test
    void shouldProvideDefaultConfigValues() {
        AppConfig config = ConfigLoader.load("/nonexistent/notfound.yaml");
        assertThat(config.getWebsocket().getHeartbeatInterval()).isEqualTo(30);
        assertThat(config.getWebsocket().getReconnectMaxDelay()).isEqualTo(60);
        assertThat(config.getApproval().getTimeout()).isEqualTo(120);
        assertThat(config.getGovernance().getMaxPlanSteps()).isEqualTo(10);
    }

    // ===== 环境变量占位符解析 =====

    @Test
    void shouldResolveEnvPlaceholderWithFallback() {
        String resolved = ConfigLoader.resolveEnvPlaceholders(
                "api-key: ${CCAGENT_TEST_KEY:sk-default}");
        assertThat(resolved).isEqualTo("api-key: sk-default");
    }

    @Test
    void shouldResolveEnvPlaceholderFromEnvironment() {
        // 设置临时环境变量不可行（Process Environment 只读），
        // 这里仅验证：未设置且无默认 → 空字符串
        String resolved = ConfigLoader.resolveEnvPlaceholders(
                "url: ${CCAGENT_DEFINITELY_UNSET_VAR_xyz}");
        assertThat(resolved).isEqualTo("url: ");
    }

    @Test
    void shouldResolveMultiplePlaceholdersInOneYaml() {
        String resolved = ConfigLoader.resolveEnvPlaceholders(
                "a: ${X:1}\nb: ${Y:2}\nc: ${X:9}");
        assertThat(resolved).isEqualTo("a: 1\nb: 2\nc: 9");
    }

    @Test
    void shouldLoadConfigWithEnvPlaceholders(@TempDir Path tempDir) throws Exception {
        Path configFile = tempDir.resolve("config.yaml");
        Files.writeString(configFile, """
                llm:
                  api-key: "${CCAGENT_UNSET_LLM_KEY:sk-fallback}"
                  model: "deepseek-v4-flash"
                websocket:
                  url: "ws://localhost:8080/ws"
                """);
        AppConfig config = ConfigLoader.load(configFile.toString());
        assertThat(config.getLlm().getApiKey()).isEqualTo("sk-fallback");
        assertThat(config.getLlm().getModel()).isEqualTo("deepseek-v4-flash");
    }
}
