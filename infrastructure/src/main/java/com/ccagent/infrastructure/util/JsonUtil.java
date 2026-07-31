package com.ccagent.infrastructure.util;

import com.ccagent.exception.base.ErrorCode;
import com.ccagent.exception.tool.ToolException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.json.JsonReadFeature;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import java.time.Instant;

/**
 * JSON 序列化/反序列化工具
 * <p>封装 Jackson，持有全局共享的 ObjectMapper 单例。</p>
 */
public final class JsonUtil {

    private static final ObjectMapper MAPPER = JsonMapper.builder()
            .addModule(new JavaTimeModule())
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
            .configure(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS, false)
            .enable(JsonReadFeature.ALLOW_UNESCAPED_CONTROL_CHARS)
            .enable(JsonReadFeature.ALLOW_BACKSLASH_ESCAPING_ANY_CHARACTER)
            .build();

    private JsonUtil() {}

    /**
     * 将对象序列化为 JSON 字符串
     *
     * @param obj 要序列化的对象
     * @return JSON 字符串
     * @throws ToolException 序列化失败时抛出
     */
    public static String toJson(Object obj) {
        try {
            return MAPPER.writeValueAsString(obj);
        } catch (JsonProcessingException e) {
            throw new ToolException(ErrorCode.PARSE_ERROR, "JSON 序列化失败", e);
        }
    }

    /**
     * 将 JSON 字符串反序列化为指定类型的对象
     *
     * @param json  JSON 字符串
     * @param clazz 目标类型
     * @param <T>   目标类型
     * @return 反序列化后的对象
     * @throws ToolException 反序列化失败时抛出
     */
    public static <T> T fromJson(String json, Class<T> clazz) {
        try {
            return MAPPER.readValue(json, clazz);
        } catch (JsonProcessingException e) {
            throw new ToolException(ErrorCode.PARSE_ERROR, "JSON 反序列化失败", e);
        }
    }

    /**
     * 将 JSON 字符串反序列化为泛型类型的对象（如 List&lt;Plan&gt;）
     *
     * @param json        JSON 字符串
     * @param typeRef     类型引用
     * @param <T>         目标类型
     * @return 反序列化后的对象
     * @throws ToolException 反序列化失败时抛出
     */
    public static <T> T fromJson(String json, TypeReference<T> typeRef) {
        try {
            return MAPPER.readValue(json, typeRef);
        } catch (JsonProcessingException e) {
            throw new ToolException(ErrorCode.PARSE_ERROR, "JSON 反序列化失败", e);
        }
    }

    /**
     * 获取底层 ObjectMapper 实例（供高级场景使用）
     */
    public static ObjectMapper getMapper() {
        return MAPPER;
    }
}
