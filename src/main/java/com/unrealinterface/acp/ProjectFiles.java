package com.unrealinterface.acp;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

import com.agentclientprotocol.sdk.spec.AcpSchema.ReadTextFileRequest;
import com.agentclientprotocol.sdk.spec.AcpSchema.WriteTextFileRequest;

/**
 * Project-scoped file access for ACP {@code fs/read_text_file} and
 * {@code fs/write_text_file}. Paths must stay inside the session directory
 * after symlink resolution.
 */
public final class ProjectFiles {

    private ProjectFiles() {
    }

    public static String read(Path projectDirectory, ReadTextFileRequest request) throws IOException {
        Path file = resolveInside(projectDirectory, request.path());
        if (!Files.isRegularFile(file)) {
            throw new IllegalArgumentException("Not a file: " + request.path());
        }
        String content = Files.readString(file, StandardCharsets.UTF_8);
        if (request.line() == null && request.limit() == null) {
            return content;
        }
        String[] lines = content.split("\n", -1);
        int start = request.line() == null ? 1 : request.line();
        if (start < 1) {
            throw new IllegalArgumentException("line must be >= 1");
        }
        if (request.limit() != null && request.limit() < 0) {
            throw new IllegalArgumentException("limit must be >= 0");
        }
        int from = start - 1;
        if (from >= lines.length) {
            return "";
        }
        int to = request.limit() == null ? lines.length : Math.min(lines.length, from + request.limit());
        return String.join("\n", Arrays.copyOfRange(lines, from, to));
    }

    public static void write(Path projectDirectory, WriteTextFileRequest request) throws IOException {
        Path file = resolveInside(projectDirectory, request.path());
        Path parent = file.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        String content = request.content() == null ? "" : request.content();
        Files.writeString(file, content, StandardCharsets.UTF_8);
    }

    static Path resolveInside(Path projectDirectory, String rawPath) throws IOException {
        if (rawPath == null || rawPath.isBlank()) {
            throw new IllegalArgumentException("Path is required");
        }
        Path requested = Path.of(rawPath);
        if (!requested.isAbsolute()) {
            throw new IllegalArgumentException("Path must be absolute: " + rawPath);
        }
        Path root = projectDirectory.toRealPath();
        Path normalized = requested.normalize();
        Path existing = normalized;
        while (existing != null && !Files.exists(existing)) {
            existing = existing.getParent();
        }
        if (existing == null) {
            throw new IllegalArgumentException("Path is outside the project: " + rawPath);
        }
        Path existingReal = existing.toRealPath();
        if (!isInside(root, existingReal)) {
            throw new IllegalArgumentException("Path is outside the project: " + rawPath);
        }
        Path resolved = existingReal.resolve(existing.relativize(normalized)).normalize();
        if (!isInside(root, resolved)) {
            throw new IllegalArgumentException("Path is outside the project: " + rawPath);
        }
        return resolved;
    }

    private static boolean isInside(Path root, Path candidate) {
        return candidate.startsWith(root);
    }
}
