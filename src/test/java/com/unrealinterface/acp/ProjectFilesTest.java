package com.unrealinterface.acp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.io.TempDir;

import com.agentclientprotocol.sdk.spec.AcpSchema.ReadTextFileRequest;
import com.agentclientprotocol.sdk.spec.AcpSchema.WriteTextFileRequest;

class ProjectFilesTest {

    @TempDir
    Path project;

    @TempDir
    Path outside;

    @Test
    void readsAndWritesInsideTheProject() throws Exception {
        Path file = project.resolve("notes").resolve("hello.txt");
        ProjectFiles.write(project, new WriteTextFileRequest("sess", file.toString(), "one\ntwo\nthree\n"));
        assertEquals("one\ntwo\nthree\n",
                ProjectFiles.read(project, new ReadTextFileRequest("sess", file.toString(), null, null)));
        assertEquals("two",
                ProjectFiles.read(project, new ReadTextFileRequest("sess", file.toString(), 2, 1)));
    }

    @Test
    void rejectsPathsOutsideTheProject() {
        var error = assertThrows(IllegalArgumentException.class,
                () -> ProjectFiles.read(project, new ReadTextFileRequest("sess", "/etc/hostname", null, null)));
        assertTrue(error.getMessage().contains("outside"));
    }

    @Test
    void rejectsRelativePaths() {
        assertThrows(IllegalArgumentException.class,
                () -> ProjectFiles.read(project, new ReadTextFileRequest("sess", "notes.txt", null, null)));
        assertTrue(Files.isDirectory(project));
    }

    @Test
    void rejectsSymlinksThatLeaveTheProject() throws Exception {
        Path secret = outside.resolve("secret.txt");
        Files.writeString(secret, "SECRET");
        Files.createSymbolicLink(project.resolve("leak-file"), secret);
        assertOutside(project.resolve("leak-file").toString());

        Files.createSymbolicLink(project.resolve("leak-dir"), outside);
        var write = assertThrows(IllegalArgumentException.class, () -> ProjectFiles.write(project,
                new WriteTextFileRequest("sess", project.resolve("leak-dir").resolve("pwned.txt").toString(), "PWN")));
        assertTrue(write.getMessage().contains("outside"), write.getMessage());
        assertFalse(Files.exists(outside.resolve("pwned.txt")));

        Files.createSymbolicLink(project.resolve("broken"), outside.resolve("missing-dir"));
        assertOutside(project.resolve("broken").resolve("pwned.txt").toString());

        Files.createSymbolicLink(project.resolve("up"), project.getParent());
        assertOutside(project.resolve("up").resolve(outside.getFileName()).resolve("secret.txt").toString());
    }

    @Test
    void followsASymlinkThatStaysInsideTheProject() throws Exception {
        Path file = project.resolve("inside.txt");
        Files.writeString(file, "INSIDE");
        Files.createSymbolicLink(project.resolve("alias.txt"), file);
        assertEquals("INSIDE",
                ProjectFiles.read(project, new ReadTextFileRequest("sess", project.resolve("alias.txt").toString(), null, null)));
        ProjectFiles.write(project, new WriteTextFileRequest("sess", project.resolve("alias.txt").toString(), "UPDATED"));
        assertEquals("UPDATED", Files.readString(file));
    }

    @Test
    void rejectsHardLinks() throws Exception {
        Path secret = outside.resolve("secret.txt");
        Files.writeString(secret, "SECRET");
        Path link = project.resolve("hard.txt");
        try {
            Files.createLink(link, secret);
        } catch (UnsupportedOperationException | java.io.IOException e) {
            Assumptions.abort("hard links are not supported here");
        }
        assertThrows(IllegalArgumentException.class,
                () -> ProjectFiles.read(project, new ReadTextFileRequest("sess", link.toString(), null, null)));
        assertThrows(IllegalArgumentException.class,
                () -> ProjectFiles.write(project, new WriteTextFileRequest("sess", link.toString(), "PWN")));
        assertEquals("SECRET", Files.readString(secret));
    }

    @Test
    void rejectsPrefixSiblingsAndParentTraversal() throws Exception {
        Path sibling = project.getParent().resolve(project.getFileName() + "-evil");
        Files.createDirectories(sibling);
        Files.writeString(sibling.resolve("x.txt"), "EVIL");
        try {
            assertOutside(sibling.resolve("x.txt").toString());
            assertOutside(project.resolve("..").resolve(outside.getFileName()).resolve("nope.txt").toString());
        } finally {
            Files.deleteIfExists(sibling.resolve("x.txt"));
            Files.deleteIfExists(sibling);
        }
    }

    @Test
    void lineLimitDoesNotOverflowAndHugeFilesAreRejected() throws Exception {
        Path file = project.resolve("lines.txt");
        Files.writeString(file, "one\ntwo\nthree\n");
        assertEquals("two\nthree\n",
                ProjectFiles.read(project, new ReadTextFileRequest("sess", file.toString(), 2, Integer.MAX_VALUE)));

        Path big = project.resolve("big.bin");
        byte[] data = new byte[ProjectFiles.MAX_READ_BYTES + 1];
        Arrays.fill(data, (byte) 'a');
        Files.write(big, data);
        assertThrows(IllegalArgumentException.class,
                () -> ProjectFiles.read(project, new ReadTextFileRequest("sess", big.toString(), null, null)));

        char[] chars = new char[ProjectFiles.MAX_WRITE_CHARS + 1];
        Arrays.fill(chars, 'b');
        assertThrows(IllegalArgumentException.class, () -> ProjectFiles.write(project,
                new WriteTextFileRequest("sess", project.resolve("too-big.txt").toString(), new String(chars))));
        assertFalse(Files.exists(project.resolve("too-big.txt")));
    }

    private void assertOutside(String path) {
        var error = assertThrows(IllegalArgumentException.class,
                () -> ProjectFiles.read(project, new ReadTextFileRequest("sess", path, null, null)));
        assertTrue(error.getMessage().contains("outside") || error.getMessage().contains("hard-linked")
                || error.getMessage().contains("symlink"), error.getMessage());
    }
}
