package com.ccagent.presentation.di;

import com.ccagent.api.port.ExpertRegistryPort;
import com.ccagent.api.port.LlmPort;
import com.ccagent.api.port.ToolRegistryPort;
import com.ccagent.core.skill.SkillRegistry;
import com.ccagent.infrastructure.constant.Constants;
import com.ccagent.infrastructure.util.FileUtil;
import com.ccagent.kernel.plugin.SimplePluginContext;
import com.ccagent.plugin.experts.impl.GenericExpert;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

/**
 * 自定义专家加载器
 * <p>扫描 <code>experts/&lt;name&gt;.yaml</code>，为每个配置创建一个
 * {@link GenericExpert} 实例并注册到专家注册表，无需编写 Java 类即可新增专家。</p>
 *
 * <h3>配置格式（.codepal/experts/&lt;name&gt;.yaml）</h3>
 * <pre>
 * name: my-expert
 * description: 我的自定义专家
 * prompt: |
 *   你是一个……
 * tools: [read_file, write_file]     # 可选：限定可用工具
 * skills: [writer-enhance]           # 可选：绑定 skill.md
 * </pre>
 */
public class CustomExpertLoader {

    private static final Logger LOG = Logger.getLogger(CustomExpertLoader.class.getName());
    private static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory());

    private CustomExpertLoader() {}

    /**
     * 加载全部自定义专家（覆盖注册）
     *
     * @param registry     专家注册表（注册目标）
     * @param toolRegistry 工具注册表（注入专家）
     * @param llmPort      LLM 端口（注入专家）
     * @param skillRegistry 技能注册表（注入专家，可空）
     * @return 加载的专家数量
     */
    public static int load(ExpertRegistryPort registry, ToolRegistryPort toolRegistry,
                           LlmPort llmPort, SkillRegistry skillRegistry) {
        return load(registry, toolRegistry, llmPort, skillRegistry, Paths.get(Constants.EXPERTS_DIR));
    }

    public static int load(ExpertRegistryPort registry, ToolRegistryPort toolRegistry,
                           LlmPort llmPort, SkillRegistry skillRegistry, Path dir) {
        if (dir == null || !Files.isDirectory(dir)) return 0;

        int count = 0;
        try (var stream = Files.list(dir)) {
            List<Path> files = stream.filter(p -> {
                String n = p.getFileName().toString();
                return n.endsWith(".yaml") || n.endsWith(".yml");
            }).toList();

            for (Path file : files) {
                try {
                    Map<String, Object> cfg = YAML.readValue(FileUtil.readString(file),
                            new TypeReference<Map<String, Object>>() {});
                    GenericExpert expert = buildExpert(cfg, file);
                    if (expert == null) continue;

                    // 通过插件上下文注入依赖（复用插件专家的 init 通道）
                    SimplePluginContext ctx = new SimplePluginContext();
                    ctx.registerService(ToolRegistryPort.class, toolRegistry);
                    ctx.registerService(LlmPort.class, llmPort);
                    if (skillRegistry != null) {
                        ctx.registerService(SkillRegistry.class, skillRegistry);
                    }
                    expert.init(ctx);
                    registry.register(expert);
                    count++;
                    LOG.info("已加载自定义专家: " + expert.getName() + " (" + file.getFileName() + ")");
                } catch (Exception e) {
                    LOG.warning("加载自定义专家失败: " + file + " - " + e.getMessage());
                }
            }
        } catch (Exception e) {
            LOG.warning("扫描自定义专家目录失败: " + e.getMessage());
        }
        return count;
    }

    @SuppressWarnings("unchecked")
    private static GenericExpert buildExpert(Map<String, Object> cfg, Path file) {
        String name = str(cfg.get("name"));
        if (name == null || name.isBlank()) {
            LOG.warning("自定义专家缺少 name: " + file);
            return null;
        }
        String prompt = str(cfg.get("prompt"));
        if (prompt == null || prompt.isBlank()) {
            // 支持 promptFile 引用（相对 .codepal/prompts/ 或绝对路径）
            String promptFile = str(cfg.get("promptFile"));
            if (promptFile != null && !promptFile.isBlank()) {
                Path p = Paths.get(promptFile);
                if (!p.isAbsolute()) p = Paths.get(Constants.PROMPTS_DIR).resolve(promptFile);
                if (Files.isRegularFile(p)) prompt = FileUtil.readString(p);
            }
        }
        if (prompt == null || prompt.isBlank()) {
            LOG.warning("自定义专家缺少 prompt 或 promptFile: " + name);
            return null;
        }

        GenericExpert expert = new GenericExpert();
        expert.setExpertName(name);
        expert.setDescription(str(cfg.get("description")));
        expert.setSystemPrompt(prompt);
        expert.setTools(toList(cfg.get("tools")));
        expert.setSkills(toList(cfg.get("skills")));
        return expert;
    }

    private static List<String> toList(Object o) {
        List<String> result = new ArrayList<>();
        if (o instanceof List<?> list) {
            for (Object item : list) {
                if (item != null) result.add(item.toString());
            }
        }
        return result;
    }

    private static String str(Object o) {
        return o != null ? o.toString() : null;
    }
}
