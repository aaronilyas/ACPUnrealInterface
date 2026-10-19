package com.unrealinterface.ui;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.unrealinterface.unreal.UnrealMcpSetup;

class WelcomeStateTest {

    @TempDir
    Path temp;

    @Test
    void firstLaunchShowsTheWelcome() {
        WelcomeState state = new WelcomeState(temp.resolve("config/state.properties"));
        assertTrue(state.shouldShow());
        assertNull(state.recentProject());
        assertFalse(Files.exists(temp.resolve("config/state.properties")));
    }

    @Test
    void finishedSetupHidesTheWelcomeAndSurvivesAReload() throws Exception {
        Path project = temp.resolve("game");
        Files.createDirectories(project);
        Path file = temp.resolve("config/state.properties");
        WelcomeState state = new WelcomeState(file);
        state.complete(project);

        assertFalse(state.shouldShow());
        assertEquals(project.toAbsolutePath().normalize(), state.recentProject());
        assertTrue(Files.readString(file).contains("welcomeSeen=true"));
        assertTrue(Files.readString(file).contains("recentProject="));

        byte[] stored = Files.readAllBytes(file);
        state.complete(project);
        assertArrayEquals(stored, Files.readAllBytes(file));

        WelcomeState again = new WelcomeState(file);
        assertFalse(again.shouldShow());
        assertEquals(project.toAbsolutePath().normalize(), again.recentProject());
        again.complete(project);
        assertArrayEquals(stored, Files.readAllBytes(file));
    }

    @Test
    void aMissingProjectShowsTheWelcomeAgain() throws Exception {
        Path project = temp.resolve("game");
        Files.createDirectories(project);
        Path file = temp.resolve("config/state.properties");
        new WelcomeState(file).complete(project);
        Files.delete(project);

        WelcomeState state = new WelcomeState(file);
        assertTrue(state.shouldShow());
        assertNull(state.recentProject());
    }

    @Test
    void aStoredProjectWithoutTheSeenFlagStillShowsTheWelcome() throws Exception {
        Path project = temp.resolve("game");
        Files.createDirectories(project);
        Path file = temp.resolve("config/state.properties");
        Files.createDirectories(file.getParent());
        Files.writeString(file, "recentProject=" + project.toAbsolutePath().normalize() + "\n");

        WelcomeState state = new WelcomeState(file);
        assertTrue(state.shouldShow());
        assertEquals(project.toAbsolutePath().normalize(), state.recentProject());
    }

    @Test
    void aPathWithSpacesRoundTrips() throws Exception {
        Path project = temp.resolve("my project");
        Files.createDirectories(project);
        Path file = temp.resolve("config/state.properties");
        new WelcomeState(file).complete(project);
        assertEquals(project.toAbsolutePath().normalize(), new WelcomeState(file).recentProject());
    }

    @Test
    void anUnreadableFileIsAFirstLaunch() throws Exception {
        Path file = temp.resolve("state.properties");
        Files.writeString(file, "welcomeSeen=" + (char) '\\' + "uZZZZ\n");
        WelcomeState state = new WelcomeState(file);
        assertTrue(state.shouldShow());
        assertNull(state.recentProject());
    }

    @Test
    void completeRejectsAMissingDirectoryArgument() {
        WelcomeState state = new WelcomeState(temp.resolve("state.properties"));
        assertThrows(java.io.IOException.class, () -> state.complete(null));
    }

    @Test
    void onlyAFinishedProjectSetupHidesTheWelcome() {
        assertTrue(WelcomeState.hidesWelcome(UnrealMcpSetup.Status.CONFIGURED));
        assertTrue(WelcomeState.hidesWelcome(UnrealMcpSetup.Status.READY));
        assertTrue(WelcomeState.hidesWelcome(UnrealMcpSetup.Status.ENGINE_MISSING));
        assertTrue(WelcomeState.hidesWelcome(UnrealMcpSetup.Status.PLUGIN_MISSING));
        assertFalse(WelcomeState.hidesWelcome(UnrealMcpSetup.Status.NO_PROJECT));
        assertFalse(WelcomeState.hidesWelcome(UnrealMcpSetup.Status.AMBIGUOUS_PROJECT));
        assertFalse(WelcomeState.hidesWelcome(UnrealMcpSetup.Status.ENGINE_TOO_OLD));
        assertFalse(WelcomeState.hidesWelcome(UnrealMcpSetup.Status.FAILED));
    }

    @Test
    void configDirectoryFollowsTheHost() {
        Path home = Path.of("/home/dev");
        assertEquals(home.resolve(".config").resolve("unreal-interface"),
                WelcomeState.configDirectory(home, "Linux", null, null));
        assertEquals(home.resolve(".config").resolve("unreal-interface"),
                WelcomeState.configDirectory(home, "Linux", "  ", null));
        assertEquals(Path.of("/xdg/unreal-interface"),
                WelcomeState.configDirectory(home, "Linux", "/xdg", null));
        assertEquals(Path.of("/Users/dev/Library/Application Support/unreal-interface"),
                WelcomeState.configDirectory(Path.of("/Users/dev"), "Mac OS X", null, null));
        assertEquals(Path.of("/Users/dev/Library/Application Support/unreal-interface"),
                WelcomeState.configDirectory(Path.of("/Users/dev"), "Darwin", "/ignored", null));
        assertEquals(Path.of("C:/Users/dev/AppData/Roaming/unreal-interface"),
                WelcomeState.configDirectory(Path.of("C:/Users/dev"), "Windows 11", null, null));
        assertEquals(Path.of("D:/Roaming/unreal-interface"),
                WelcomeState.configDirectory(Path.of("C:/Users/dev"), "Windows 11", null, "D:/Roaming"));
    }

    @Test
    void promptsExplainTheClientAndCanBeCopied() {
        assertTrue(WelcomeState.SUMMARY.contains("ACP client"));
        assertTrue(WelcomeState.SUMMARY.contains("Unreal skills"));
        assertTrue(WelcomeState.SUMMARY.contains("Unreal MCP"));
        assertTrue(WelcomeState.NEW_PROJECT.contains("5.8"));
        assertTrue(WelcomeState.NEW_PROJECT.contains("File > New Project"));
        assertTrue(WelcomeState.NEW_PROJECT.contains(".uproject"));
        assertEquals(2, WelcomeState.EXAMPLE_PROMPTS.size());
        assertEquals("Create a simple third-person character that can run and jump in a basic level",
                WelcomeState.EXAMPLE_PROMPTS.get(0));
        assertEquals("Set up a basic lit level with a player start and a few static meshes",
                WelcomeState.EXAMPLE_PROMPTS.get(1));
    }

    @Test
    void configDirectoryHonorsAnOverride() {
        String key = "unrealinterface.config";
        String previous = System.setProperty(key, temp.resolve("custom").toString());
        try {
            assertEquals(temp.resolve("custom").toAbsolutePath().normalize(), WelcomeState.configDirectory());
        } finally {
            if (previous == null) {
                System.clearProperty(key);
            } else {
                System.setProperty(key, previous);
            }
        }
    }
}
