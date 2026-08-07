package com.ccagent.core.skill;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SkillLoaderTest {

    @TempDir Path tempDir;

    @Test
    void shouldLoadDirectoryFormWithFrontmatter() throws Exception {
        Path skillDir = Files.createDirectories(tempDir.resolve("writer-enhance"));
        Files.writeString(skillDir.resolve("SKILL.md"), """
                ---
                name: writer-enhance
                description: 写作规范
                expert: writer
                ---
                请始终使用中文注释。
                """);

        List<Skill> skills = new SkillLoader().load(tempDir);

        assertThat(skills).hasSize(1);
        Skill skill = skills.get(0);
        assertThat(skill.getName()).isEqualTo("writer-enhance");
        assertThat(skill.getDescription()).isEqualTo("写作规范");
        assertThat(skill.getExpert()).isEqualTo("writer");
        assertThat(skill.getPrompt()).contains("中文注释");
    }

    @Test
    void shouldLoadSingleFileFormWithoutFrontmatter() throws Exception {
        Files.writeString(tempDir.resolve("notes.md"), "记录要点即可。");

        List<Skill> skills = new SkillLoader().load(tempDir);

        assertThat(skills).hasSize(1);
        assertThat(skills.get(0).getName()).isEqualTo("notes");
        assertThat(skills.get(0).getPrompt()).isEqualTo("记录要点即可。");
    }

    @Test
    void shouldSkipDisabledSkill() throws Exception {
        Files.writeString(tempDir.resolve("disabled.md"), """
                ---
                name: disabled
                enabled: false
                ---
                不应加载。
                """);

        List<Skill> skills = new SkillLoader().load(tempDir);

        assertThat(skills).isEmpty();
    }

    @Test
    void shouldSkipBlankPrompt() throws Exception {
        Files.writeString(tempDir.resolve("empty.md"), "   ");

        List<Skill> skills = new SkillLoader().load(tempDir);

        assertThat(skills).isEmpty();
    }

    @Test
    void registryShouldResolvePromptAndSkipMissing() throws Exception {
        Files.writeString(tempDir.resolve("a.md"), "技能A正文");
        Files.writeString(tempDir.resolve("b.md"), "技能B正文");
        SkillRegistry registry = new SkillRegistry(tempDir);

        String prompt = registry.resolvePrompt(List.of("a", "missing", "b"));

        assertThat(prompt).contains("技能A正文").contains("技能B正文");
        assertThat(prompt).doesNotContain("missing");
        assertThat(registry.listNames()).containsExactly("a", "b");
    }

    @Test
    void registryShouldReload() throws Exception {
        Files.writeString(tempDir.resolve("a.md"), "正文");
        SkillRegistry registry = new SkillRegistry(tempDir);
        assertThat(registry.listNames()).containsExactly("a");

        Files.writeString(tempDir.resolve("c.md"), "正文C");
        registry.reload();

        assertThat(registry.listNames()).containsExactly("a", "c");
    }
}
