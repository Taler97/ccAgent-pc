package com.ccagent.core.skill;

/**
 * 技能（skill.md）
 * <p>一个 skill = 一段可注入专家的 prompt 规范。来源：<code>&lt;skills&gt;/&lt;name&gt;/SKILL.md</code>
 * 或 <code>&lt;skills&gt;/&lt;name&gt;.md</code>，frontmatter 声明元数据，正文即 prompt。</p>
 */
public class Skill {

    private String name;
    private String description;
    /** 绑定专家名（可选，模式 B：专家声明式绑定） */
    private String expert;
    /** 正文 prompt */
    private String prompt;
    /** 源文件路径 */
    private String path;

    public Skill() {}

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public String getExpert() { return expert; }
    public void setExpert(String expert) { this.expert = expert; }
    public String getPrompt() { return prompt; }
    public void setPrompt(String prompt) { this.prompt = prompt; }
    public String getPath() { return path; }
    public void setPath(String path) { this.path = path; }

    @Override
    public String toString() {
        return "Skill{name='" + name + "', expert='" + expert + "'}";
    }
}
