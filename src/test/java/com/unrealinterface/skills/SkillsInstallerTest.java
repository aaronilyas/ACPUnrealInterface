package com.unrealinterface.skills;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SkillsInstallerTest {

    @TempDir
    Path project;

    @TempDir
    Path outside;

    @Test
    void copiesTheBundleIntoBothSkillTreesAndLeavesExistingFiles() throws Exception {
        SkillsInstaller installer = new SkillsInstaller();
        var first = installer.install(project);
        assertEquals(4, first.created());
        assertEquals(0, first.leftInPlace());
        Path claudeSkill = project.resolve(".claude/skills/unreal-interface/SKILL.md");
        Path agentsReference = project.resolve(".agents/skills/unreal-interface/reference.md");
        assertTrue(Files.isRegularFile(claudeSkill));
        assertTrue(Files.readString(claudeSkill).contains("name: unreal-interface"));
        assertTrue(Files.readString(agentsReference).contains("session/new"));

        Files.writeString(claudeSkill, "local edit\n");
        var second = installer.install(project);
        assertEquals(0, second.created());
        assertEquals(4, second.leftInPlace());
        assertEquals("local edit\n", Files.readString(claudeSkill));
        assertTrue(second.message().contains("already present"));
    }

    @Test
    void refusesASkillTreeThatSymlinksOutsideTheProject() throws Exception {
        Path planted = outside.resolve("skills/unreal-interface/SKILL.md");
        Files.createDirectories(planted.getParent());
        Files.writeString(planted, "keep-me\n");
        Files.createSymbolicLink(project.resolve(".claude"), outside);

        IOException error = assertThrows(IOException.class, () -> new SkillsInstaller().install(project));
        assertTrue(error.getMessage().contains("outside"), error.getMessage());
        assertEquals("keep-me\n", Files.readString(planted));
        assertFalse(Files.exists(outside.resolve("skills/unreal-interface/reference.md")));
        assertFalse(Files.exists(project.resolve(".agents/skills/unreal-interface/SKILL.md")));
    }

    @Test
    void followsASkillTreeSymlinkThatStaysInsideTheProject() throws Exception {
        Path realClaude = project.resolve("claude-real");
        Files.createDirectories(realClaude);
        Files.createSymbolicLink(project.resolve(".claude"), realClaude);
        var result = new SkillsInstaller().install(project);
        assertEquals(4, result.created());
        assertTrue(Files.isRegularFile(realClaude.resolve("skills/unreal-interface/SKILL.md")));
        assertTrue(Files.isRegularFile(project.resolve(".agents/skills/unreal-interface/SKILL.md")));
    }
}
