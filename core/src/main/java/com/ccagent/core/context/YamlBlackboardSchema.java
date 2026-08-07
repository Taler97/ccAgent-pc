package com.ccagent.core.context;

import com.ccagent.api.context.BlackboardSchema;
import com.ccagent.api.context.FieldSchema;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * 从 blackboard-schema.yaml 加载的 schema 实现
 * <p>生产环境通过 {@link #loadFromClasspath(String)} 加载 classpath 中的 yaml,
 * 测试通过 {@link #loadFromFile(Path)} 加载自定义 schema.</p>
 */
public class YamlBlackboardSchema implements BlackboardSchema {

    private final Map<String, FieldSchema> fields;

    private YamlBlackboardSchema(Map<String, FieldSchema> fields) {
        this.fields = Collections.unmodifiableMap(fields);
    }

    @Override
    public FieldSchema getField(String key) {
        return fields.get(key);
    }

    @Override
    public Set<String> getDefinedKeys() {
        return fields.keySet();
    }

    @Override
    public boolean isEmpty() {
        return fields.isEmpty();
    }

    // ========== 加载方法 ==========

    /**
     * 从 classpath 加载 schema yaml. 文件不存在时返回 {@link #empty()}.
     */
    public static BlackboardSchema loadFromClasspath(String classpathLocation) {
        try (InputStream is = YamlBlackboardSchema.class.getClassLoader().getResourceAsStream(classpathLocation)) {
            if (is == null) return BlackboardSchema.EMPTY;
            return parse(is);
        } catch (IOException e) {
            throw new RuntimeException("加载黑板 schema 失败: " + classpathLocation + " - " + e.getMessage(), e);
        }
    }

    /**
     * 从文件路径加载 schema yaml (测试用)
     */
    public static BlackboardSchema loadFromFile(Path yamlPath) {
        try (InputStream is = Files.newInputStream(yamlPath)) {
            return parse(is);
        } catch (IOException e) {
            throw new RuntimeException("加载黑板 schema 失败: " + yamlPath + " - " + e.getMessage(), e);
        }
    }

    @SuppressWarnings("unchecked")
    private static BlackboardSchema parse(InputStream is) throws IOException {
        ObjectMapper mapper = new ObjectMapper(new YAMLFactory());
        Map<String, Object> root = mapper.readValue(is, Map.class);
        Map<String, Object> fieldsNode = (Map<String, Object>) root.get("fields");
        if (fieldsNode == null) return BlackboardSchema.EMPTY;

        Map<String, FieldSchema> fields = new LinkedHashMap<>();
        for (Map.Entry<String, Object> e : fieldsNode.entrySet()) {
            Map<String, Object> node = (Map<String, Object>) e.getValue();
            String type = (String) node.get("type");
            String strategy = (String) node.get("mergeStrategy");
            String desc = (String) node.get("description");
            fields.put(e.getKey(), new FieldSchema(type, strategy, desc));
        }
        return new YamlBlackboardSchema(fields);
    }
}
