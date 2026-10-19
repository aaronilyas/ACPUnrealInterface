package com.unrealinterface.acp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.agentclientprotocol.sdk.spec.AcpSchema.ReadTextFileRequest;
import com.agentclientprotocol.sdk.spec.AcpSchema.WriteTextFileRequest;

class ProjectFilesTest {

    @TempDir
    Path project;

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
}
