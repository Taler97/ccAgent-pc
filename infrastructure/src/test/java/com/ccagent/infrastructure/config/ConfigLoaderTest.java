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
}
