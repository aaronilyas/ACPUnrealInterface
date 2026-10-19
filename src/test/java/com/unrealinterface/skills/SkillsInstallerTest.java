package com.unrealinterface.skills;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SkillsInstallerTest {

    @TempDir
    Path project;

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
}
