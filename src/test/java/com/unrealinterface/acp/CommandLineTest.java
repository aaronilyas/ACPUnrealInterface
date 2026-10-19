package com.unrealinterface.acp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Comparator;
import java.util.List;

import org.junit.jupiter.api.Test;

class CommandLineTest {

    @Test
    void splitsWordsAndQuotes() {
        assertEquals(List.of("npx", "-y", "@agentclientprotocol/claude-agent-acp"),
                CommandLine.tokenize("npx -y @agentclientprotocol/claude-agent-acp"));
        assertEquals(List.of("echo", "hello world"), CommandLine.tokenize("echo \"hello world\""));
        assertEquals(List.of("tool", "a b"), CommandLine.tokenize("tool 'a b'"));
    }

    @Test
    void rejectsEmptyAndUnclosedQuotes() {
        assertThrows(IllegalArgumentException.class, () -> CommandLine.tokenize("   "));
        assertThrows(IllegalArgumentException.class, () -> CommandLine.tokenize("echo \"unterminated"));
    }

    @Test
    void findsProgramsOnPathAndRejectsMissingNames() {
        assertTrue(CommandLine.isExecutable("/bin/true"));
        assertFalse(CommandLine.isExecutable("unreal-interface-missing-agent-binary"));
    }

    @Test
    void treatsShellMetacharactersAsLiteralArguments() {
        assertEquals(List.of("npx", "-y", "pkg;rm", "-rf", "/"),
                CommandLine.tokenize("npx -y pkg;rm -rf /"));
        assertEquals(List.of("echo", "$(whoami)"), CommandLine.tokenize("echo '$(whoami)'"));
        assertEquals(List.of("echo", "a|b"), CommandLine.tokenize("echo \"a|b\""));
    }

    @Test
    void resolvesBareNamesOnlyFromAbsolutePathEntries() throws Exception {
        Path root = Files.createTempDirectory("command-line");
        try {
            Path realBin = root.resolve("real-bin");
            Path relativeBin = root.resolve("relative-bin");
            Files.createDirectories(realBin);
            Files.createDirectories(relativeBin);
            writeExecutable(realBin.resolve("tool"));
            writeExecutable(relativeBin.resolve("tool"));
            String pathEnv = "relative-bin" + File.pathSeparator + "." + File.pathSeparator + realBin;
            assertEquals(realBin.resolve("tool").normalize().toString(),
                    CommandLine.resolveExecutable("tool", pathEnv, root));
            assertEquals(root.resolve("base/bin/agent").normalize().toString(),
                    resolvedRelative(root));
            assertNull(CommandLine.resolveExecutable("missing-tool", realBin.toString(), root));
        } finally {
            deleteTree(root);
        }
    }

    private static String resolvedRelative(Path root) throws Exception {
        Path base = root.resolve("base");
        Path exe = base.resolve("bin/agent");
        Files.createDirectories(exe.getParent());
        writeExecutable(exe);
        return CommandLine.resolveExecutable("bin/agent", "", base);
    }

    private static void writeExecutable(Path path) throws Exception {
        Files.writeString(path, "#!/bin/sh\nexit 0\n");
        Files.setPosixFilePermissions(path, PosixFilePermissions.fromString("rwxr-xr-x"));
    }

    private static void deleteTree(Path root) throws Exception {
        try (var walk = Files.walk(root)) {
            walk.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (Exception ignored) {
                    // best-effort cleanup of the temp command fixtures
                }
            });
        }
    }
}
