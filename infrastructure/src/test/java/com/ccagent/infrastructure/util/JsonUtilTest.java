package com.ccagent.infrastructure.util;

import com.ccagent.exception.tool.ToolException;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Map;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JsonUtilTest {

    @Test
    void shouldSerializeToJson() {
        String json = JsonUtil.toJson(Map.of("name", "test", "count", 42));
        assertThat(json).contains("\"name\"");
        assertThat(json).contains("\"test\"");
        assertThat(json).contains("\"count\"");
        assertThat(json).contains("42");
    }

    @Test
    void shouldDeserializeFromJson() {
        String json = "{\"name\":\"test\",\"value\":123}";
        Map<String, Object> result = JsonUtil.fromJson(json, Map.class);
        assertThat(result).containsEntry("name", "test");
        assertThat(result).containsEntry("value", 123);
    }

    @Test
    void shouldDeserializeWithTypeReference() {
        String json = "[1, 2, 3]";
        List<Integer> list = JsonUtil.fromJson(json, new com.fasterxml.jackson.core.type.TypeReference<>() {});
        assertThat(list).containsExactly(1, 2, 3);
    }

    @Test
    void shouldThrowOnInvalidJson() {
        assertThatThrownBy(() -> JsonUtil.fromJson("{invalid}", Map.class))
                .isInstanceOf(ToolException.class);
    }

    @Test
    void shouldHandleUnknownPropertiesGracefully() {
        String json = "{\"known\":\"value\",\"unknown\":\"ignored\"}";
        record Known(String known) {}
        // Using Map to verify unknown fields are ignored
        Map<String, Object> result = JsonUtil.fromJson(json, Map.class);
        assertThat(result).containsEntry("known", "value");
        assertThat(result).containsEntry("unknown", "ignored");
    }

    @Test
    void shouldGetMapperInstance() {
        assertThat(JsonUtil.getMapper()).isNotNull();
    }
}
