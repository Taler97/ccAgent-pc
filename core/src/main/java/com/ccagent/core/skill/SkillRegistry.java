package com.ccagent.core.skill;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

/**
 * 技能注册表
 * <p>管理所有 skill.md，提供按名查询、prompt 合并、重新加载（热更新）。
 * 由 DI 组装时加载并注册到 PluginContext，供专家（BaseExpert）注入使用。</p>
 */
public class SkillRegistry {

    private static final Logger LOG = Logger.getLogger(SkillRegistry.class.getName());

    private final Map<String, Skill> skills = new ConcurrentHashMap<>();
    private final SkillLoader loader = new SkillLoader();
    private volatile Path skillsDir;

    public SkillRegistry() {}

    public SkillRegistry(Path skillsDir) {
        load(skillsDir);
    }

    /**
     * 从目录加载全部技能（覆盖旧注册）
     */
    public synchronized void load(Path dir) {
        this.skillsDir = dir;
        Map<String, Skill> loaded = new HashMap<>();
        for (Skill s : loader.load(dir)) {
            loaded.put(s.getName(), s);
        }
        skills.clear();
        skills.putAll(loaded);
        LOG.info("已加载 " + skills.size() + " 个技能: " + skills.keySet());
    }

    /**
     * 重新加载（支持运行时热更新，监听文件变化后调用）
     */
    public synchronized void reload() {
        if (skillsDir != null) load(skillsDir);
    }

    /**
     * 按名查询技能
     */
    public Skill get(String name) {
        return name == null ? null : skills.get(name);
    }

    /**
     * 全部技能（按名排序）
     */
    public List<Skill> list() {
        List<Skill> list = new ArrayList<>(skills.values());
        list.sort(Comparator.comparing(Skill::getName));
        return list;
    }

    /**
     * 将多个技能的正文合并为一段提示词（带标题与描述分隔）。
     * 不存在的技能跳过并告警；列表为空返回空串。
     */
    public String resolvePrompt(Collection<String> names) {
        if (names == null || names.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        for (String name : names) {
            Skill skill = skills.get(name);
            if (skill == null) {
                LOG.warning("技能不存在: " + name);
                continue;
            }
            if (sb.length() > 0) sb.append("\n\n");
            sb.append("### 技能[").append(skill.getName()).append("]");
            if (skill.getDescription() != null && !skill.getDescription().isBlank()) {
                sb.append("（").append(skill.getDescription()).append("）");
            }
            sb.append("\n").append(skill.getPrompt());
        }
        return sb.toString();
    }

    /**
     * 获取已注册的技能名列表
     */
    public List<String> listNames() {
        List<String> names = new ArrayList<>(skills.keySet());
        names.sort(String::compareTo);
        return names;
    }
}
