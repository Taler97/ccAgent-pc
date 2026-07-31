package com.ccagent.plugin.governance.audit;

import org.junit.jupiter.api.Test;
import java.util.Map;
import static org.assertj.core.api.Assertions.assertThat;

class TokenStatsServiceTest {

    @Test
    void shouldRecordAndRetrieveStats() {
        TokenStatsService stats = new TokenStatsService(true, 0.07, 0.014, 0.28);
        stats.recordLlmCall("sess-1", "seq-1", 1000, 600);

        TokenStatsService.SessionTokenStats session = stats.getSessionStats("sess-1");
        assertThat(session.getTotalTokens()).isEqualTo(1000);
        assertThat(session.getLlmCallCount()).isEqualTo(1);
    }

    @Test
    void shouldAggregateMultipleCalls() {
        TokenStatsService stats = new TokenStatsService(true, 0.07, 0.014, 0.28);
        stats.recordLlmCall("sess-1", "seq-1", 1000, 600);
        stats.recordLlmCall("sess-1", "seq-2", 500, 300);

        TokenStatsService.SessionTokenStats session = stats.getSessionStats("sess-1");
        assertThat(session.getTotalTokens()).isEqualTo(1500);
        assertThat(session.getLlmCallCount()).isEqualTo(2);
    }

    @Test
    void shouldReturnEmptyWhenDisabled() {
        TokenStatsService stats = new TokenStatsService(false, 0.07, 0.014, 0.28);
        stats.recordLlmCall("sess-1", "seq-1", 1000, 600);

        TokenStatsService.SessionTokenStats session = stats.getSessionStats("sess-1");
        assertThat(session.getTotalTokens()).isEqualTo(0);
    }

    @Test
    void shouldCalculateCacheHitRate() {
        TokenStatsService stats = new TokenStatsService(true, 0.07, 0.014, 0.28);
        stats.recordLlmCall("sess-1", "seq-1", 1000, 600);

        TokenStatsService.SessionTokenStats session = stats.getSessionStats("sess-1");
        // 600 / 1000 = 0.6
        assertThat(session.getCacheHitRate()).isEqualTo(0.6);
    }
}
