package com.unrealinterface.skills;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.unrealinterface.skills.SkillInstallPolicy.Outcome;
import com.unrealinterface.skills.SkillInstallPolicy.Trigger;

class SkillInstallPolicyTest {

    @TempDir
    Path root;

    @Test
    void autoInstallsAUprojectDirectoryOnceAndSkipsOtherFolders() throws Exception {
        Path home = root.resolve("home");
        Path game = home.resolve("games/Demo");
        Path notes = home.resolve("notes");
        Files.createDirectories(game);
        Files.createDirectories(notes);
        Files.writeString(game.resolve("Demo.uproject"), "{}\n");
        SkillInstallPolicy policy = new SkillInstallPolicy(home);

        SkillInstallPolicy.Decision first = policy.evaluate(game, Trigger.AUTO);
        assertEquals(Outcome.INSTALL, first.outcome());
        assertEquals(game.toRealPath(), first.directory());
        assertEquals(Outcome.SKIP, policy.evaluate(game, Trigger.AUTO).outcome());
        assertEquals(Outcome.SKIP, policy.evaluate(game, Trigger.EXPLICIT).outcome());
        assertEquals(Outcome.SKIP, policy.evaluate(notes, Trigger.AUTO).outcome());
        assertFalse(policy.evaluate(notes, Trigger.AUTO).report());

        SkillInstallPolicy.Decision explicit = policy.evaluate(notes, Trigger.EXPLICIT);
        assertEquals(Outcome.INSTALL, explicit.outcome());
        assertEquals(notes.toRealPath(), explicit.directory());
    }

    @Test
    void treatsASymlinkAndItsTargetAsTheSameDirectory() throws Exception {
        Path home = root.resolve("home");
        Path game = home.resolve("games/Demo");
        Files.createDirectories(game);
        Files.writeString(game.resolve("Demo.uproject"), "{}\n");
        Path link = root.resolve("demo-link");
        Files.createSymbolicLink(link, game);
        SkillInstallPolicy policy = new SkillInstallPolicy(home);

        assertEquals(Outcome.INSTALL, policy.evaluate(link, Trigger.AUTO).outcome());
        assertEquals(Outcome.SKIP, policy.evaluate(game, Trigger.AUTO).outcome());
    }

    @Test
    void neverSelectsHomeOrItsHarnessDirectories() throws Exception {
        Path home = root.resolve("home");
        Files.createDirectories(home.resolve(".claude/skills"));
        Files.createDirectories(home.resolve(".agents/skills"));
        Files.writeString(home.resolve("Home.uproject"), "{}\n");
        Files.writeString(home.resolve(".claude/Nested.uproject"), "{}\n");
        Path homeLink = root.resolve("home-link");
        Files.createSymbolicLink(homeLink, home);
        SkillInstallPolicy policy = new SkillInstallPolicy(home);

        assertForbidden(policy.evaluate(home, Trigger.AUTO), false);
        SkillInstallPolicy.Decision browseHome = policy.evaluate(home, Trigger.EXPLICIT);
        assertForbidden(browseHome, true);
        assertTrue(browseHome.message().contains("home"));
        assertForbidden(policy.evaluate(home, Trigger.EXPLICIT), false);
        assertForbidden(policy.evaluate(home.resolve(".claude"), Trigger.EXPLICIT), true);
        assertForbidden(policy.evaluate(home.resolve(".claude/skills"), Trigger.AUTO), false);
        assertForbidden(policy.evaluate(home.resolve(".agents"), Trigger.EXPLICIT), true);
        assertForbidden(policy.evaluate(homeLink, Trigger.AUTO), false);

        Path backup = home.resolve(".claude-backup");
        Files.createDirectories(backup);
        Files.writeString(backup.resolve("Backup.uproject"), "{}\n");
        assertEquals(Outcome.INSTALL, policy.evaluate(backup, Trigger.AUTO).outcome());

        assertFalse(Files.exists(home.resolve(".claude/skills/unreal-router/SKILL.md")));
        assertFalse(Files.exists(home.resolve("AGENTS.md")));
        assertFalse(Files.exists(home.resolve(".agents/skills/unreal-router/SKILL.md")));
    }

    @Test
    void ignoresADirectoryNamedLikeAUproject() throws Exception {
        Path home = root.resolve("home");
        Path folder = root.resolve("not-a-project");
        Files.createDirectories(folder.resolve("Game.uproject"));
        SkillInstallPolicy policy = new SkillInstallPolicy(home);
        assertEquals(Outcome.SKIP, policy.evaluate(folder, Trigger.AUTO).outcome());
        assertEquals(Outcome.INSTALL, policy.evaluate(folder, Trigger.EXPLICIT).outcome());
    }

    @Test
    void releaseLetsAFailedCopyTryAgain() throws Exception {
        Path home = root.resolve("home");
        Path game = root.resolve("Demo");
        Files.createDirectories(home);
        Files.createDirectories(game);
        Files.writeString(game.resolve("Demo.uproject"), "{}\n");
        SkillInstallPolicy policy = new SkillInstallPolicy(home);
        Path claimed = policy.evaluate(game, Trigger.AUTO).directory();
        assertEquals(Outcome.SKIP, policy.evaluate(game, Trigger.AUTO).outcome());
        policy.release(claimed);
        assertEquals(Outcome.INSTALL, policy.evaluate(game, Trigger.AUTO).outcome());
    }

    private static void assertForbidden(SkillInstallPolicy.Decision decision, boolean notify) {
        assertEquals(Outcome.FORBIDDEN, decision.outcome());
        assertEquals(notify, decision.report());
    }
}
