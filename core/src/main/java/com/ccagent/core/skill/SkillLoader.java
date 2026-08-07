package com.ccagent.core.skill;

import com.ccagent.infrastructure.util.FileUtil;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

/**
 * 技能加载器
 * <p>扫描技能目录下的 skill.md，解析 frontmatter（YAML）与正文 prompt。</p>
 *
 * <h3>目录约定（对齐 Claude Agent Skills）</h3>
 * <pre>
 * skills/
 *   ├── &lt;name&gt;/SKILL.md      # 目录形式
 *   └── &lt;name&gt;.md            # 单文件形式（name = 文件名）
 * </pre>
 *
 * <h3>SKILL.md 格式</h3>
 * <pre>
 * ---
 * name: writer-enhance
 * description: 写作规范
 * expert: writer        # 可选：绑定专家
 * enabled: true         # 可选：默认 true
 * ---
 * 正文 prompt...
 * </pre>
 */
public class SkillLoader {

    private static final Logger LOG = Logger.getLogger(SkillLoader.class.getName());
    private static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory());
    private static final String SKILL_FILE = "SKILL.md";

    /**
     * 扫描目录下所有 skill.md
     */
    public List<Skill> load(Path skillsDir) {
        List<Skill> skills = new ArrayList<>();
        if (skillsDir == null || !Files.isDirectory(skillsDir)) return skills;

        try (var stream = Files.list(skillsDir)) {
            for (Path entry : stream.toList()) {
                String fileName = entry.getFileName().toString();
                Path file;
                String defaultName;
                if (Files.isDirectory(entry)) {
                    file = entry.resolve(SKILL_FILE);
                    defaultName = fileName;
                } else if (fileName.endsWith(".md")) {
                    file = entry;
                    defaultName = fileName.substring(0, fileName.length() - 3);
                } else {
                    continue;
                }
                if (!Files.isRegularFile(file)) continue;

                Skill skill = parse(defaultName, file);
                if (skill != null) skills.add(skill);
            }
        } catch (IOException e) {
            LOG.warning("扫描技能目录失败: " + e.getMessage());
        }
        return skills;
    }

    /**
     * 解析单个 SKILL.md 文件
     *
     * @param defaultName 默认技能名（无 frontmatter name 时使用）
     * @param file        文件路径
     * @return Skill，解析失败或 enabled=false 返回 null
     */
    Skill parse(String defaultName, Path file) {
        try {
            String content = FileUtil.readString(file);
            if (content == null || content.isBlank()) return null;

            Skill skill = new Skill();
            skill.setPath(file.toString());

            String body = content;
            // frontmatter：--- 开头，第二个 --- 结束
            if (content.startsWith("---")) {
                int end = content.indexOf("---", 3);
                if (end > 0) {
                    String fm = content.substring(3, end).trim();
                    body = content.substring(end + 3).trim();
                    Map<String, Object> meta = parseFrontmatter(fm);
                    skill.setName(str(meta.get("name")));
                    skill.setDescription(str(meta.get("description")));
                    skill.setExpert(str(meta.get("expert")));
                    Object enabled = meta.get("enabled");
                    if (enabled instanceof Boolean b && !b) return null;
                }
            }

            if (skill.getName() == null || skill.getName().isBlank()) {
                skill.setName(defaultName);
            }
            skill.setPrompt(body);
            if (skill.getPrompt() == null || skill.getPrompt().isBlank()) return null;
            return skill;
        } catch (Exception e) {
            LOG.warning("解析技能文件失败: " + file + " - " + e.getMessage());
            return null;
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> parseFrontmatter(String fm) {
        try {
            return YAML.readValue(fm, new TypeReference<Map<String, Object>>() {});
        } catch (Exception e) {
            LOG.warning("frontmatter 解析失败（按空处理）: " + e.getMessage());
            return Map.of();
        }
    }

    private static String str(Object o) {
        return o != null ? o.toString() : null;
    }
}
