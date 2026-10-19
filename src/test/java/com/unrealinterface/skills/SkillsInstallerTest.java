package com.unrealinterface.skills;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SkillsInstallerTest {

    private static final int BUNDLED_FILE_COUNT =
            SkillsInstaller.SKILL_FILES.size() * 2 + SkillsInstaller.PROJECT_ROOT_FILES.size();

    @TempDir
    Path project;

    @Test
    void manifestListsEveryFileInTheBundle() throws Exception {
        Path bundle = Path.of("src/main/resources/skills-bundle");
        List<String> onDisk;
        try (Stream<Path> walk = Files.walk(bundle)) {
            onDisk = walk.filter(Files::isRegularFile)
                    .map(path -> bundle.relativize(path).toString().replace('\\', '/'))
                    .sorted()
                    .toList();
        }
        List<String> manifest = new ArrayList<>();
        manifest.addAll(SkillsInstaller.SKILL_FILES);
        manifest.addAll(SkillsInstaller.PROJECT_ROOT_FILES);
        manifest.sort(Comparator.naturalOrder());
        assertEquals(manifest, onDisk);
    }

    @Test
    void copiesTheBundleIntoHarnessLocationsAndLeavesExistingFiles() throws Exception {
        SkillsInstaller installer = new SkillsInstaller();
        var first = installer.install(project);
        assertEquals(BUNDLED_FILE_COUNT, first.created());
        assertEquals(0, first.leftInPlace());

        Path claudeRouter = project.resolve(".claude/skills/unreal-router/SKILL.md");
        Path agentsSetup = project.resolve(".agents/skills/unreal-mcp-setup/SKILL.md");
        Path agentsReference = project.resolve(".agents/skills/unreal-interface/reference.md");
        assertTrue(Files.isRegularFile(claudeRouter));
        assertTrue(Files.isRegularFile(agentsSetup));
        assertTrue(Files.readString(agentsReference).contains("session/new"));
        assertFalse(Files.exists(project.resolve(".agents/skills/AGENTS.md")));
        assertFalse(Files.exists(project.resolve(".claude/skills/project-root/AGENTS.md")));

        for (String relative : SkillsInstaller.SKILL_FILES) {
            if (!relative.endsWith("/SKILL.md")) {
                continue;
            }
            String skill = relative.substring(0, relative.indexOf('/'));
            for (String tree : List.of(".agents/skills", ".claude/skills")) {
                String text = Files.readString(project.resolve(tree).resolve(relative));
                assertTrue(text.startsWith("---\n"), relative);
                assertTrue(text.contains("name: " + skill), relative);
            }
        }

        String agents = Files.readString(project.resolve("AGENTS.md"));
        assertTrue(agents.contains("Prefer Unreal MCP tools"));
        assertTrue(agents.contains("Verify changes visually"));
        assertTrue(agents.contains("Keep each task small"));
        assertEquals("@AGENTS.md\n", Files.readString(project.resolve("CLAUDE.md")));

        Files.writeString(claudeRouter, "local edit\n");
        Files.writeString(project.resolve("AGENTS.md"), "project rules\n");
        var second = installer.install(project);
        assertEquals(0, second.created());
        assertEquals(BUNDLED_FILE_COUNT, second.leftInPlace());
        assertEquals("local edit\n", Files.readString(claudeRouter));
        assertEquals("project rules\n", Files.readString(project.resolve("AGENTS.md")));
        assertTrue(second.message().contains("already present"));
    }
}
