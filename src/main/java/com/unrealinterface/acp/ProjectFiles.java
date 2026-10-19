package com.unrealinterface.acp;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystemLoopException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;

import com.agentclientprotocol.sdk.spec.AcpSchema.ReadTextFileRequest;
import com.agentclientprotocol.sdk.spec.AcpSchema.WriteTextFileRequest;

/**
 * Project-scoped file access for ACP {@code fs/read_text_file} and
 * {@code fs/write_text_file}. Paths must stay inside the session directory
 * after symlink resolution. Hard links are refused, because writing one would
 * change an inode that also lives outside the project.
 */
public final class ProjectFiles {

    /** Whole-file reads larger than this are rejected so one request cannot exhaust the heap. */
    static final int MAX_READ_BYTES = 4 * 1024 * 1024;

    /** Writes larger than this are rejected. The agent can split a bigger edit. */
    static final int MAX_WRITE_CHARS = 4 * 1024 * 1024;

    private ProjectFiles() {
    }

    public static String read(Path projectDirectory, ReadTextFileRequest request) throws IOException {
        Path file = resolveInside(projectDirectory, request.path());
        rejectLinkedFile(file, request.path());
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
            throw new IllegalArgumentException("Not a file: " + request.path());
        }
        byte[] bytes = readCapped(file, request.path());
        String content = new String(bytes, StandardCharsets.UTF_8);
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
        int to = request.limit() == null
                ? lines.length
                : (int) Math.min(lines.length, (long) from + request.limit());
        if (to < from) {
            to = from;
        }
        return String.join("\n", Arrays.copyOfRange(lines, from, to));
    }

    public static void write(Path projectDirectory, WriteTextFileRequest request) throws IOException {
        Path file = resolveInside(projectDirectory, request.path());
        String content = request.content() == null ? "" : request.content();
        if (content.length() > MAX_WRITE_CHARS) {
            throw new IllegalArgumentException(
                    "Refusing to write " + content.length() + " characters (limit " + MAX_WRITE_CHARS + ")");
        }
        rejectLinkedFile(file, request.path());
        Path parent = file.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
            Path root = projectDirectory.toRealPath();
            if (!isInside(root, parent.toRealPath())) {
                throw new IllegalArgumentException("Path is outside the project: " + request.path());
            }
        }
        rejectLinkedFile(file, request.path());
        try (OutputStream out = Files.newOutputStream(file, StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS)) {
            out.write(content.getBytes(StandardCharsets.UTF_8));
        }
    }

    /**
     * Resolves {@code rawPath} and returns a path that is inside {@code projectDirectory}
     * after symlink resolution. The path must be absolute. A symlink or a {@code ..}
     * segment that leaves the project is rejected, including a broken symlink whose
     * target does not exist.
     */
    public static Path resolveInside(Path projectDirectory, String rawPath) throws IOException {
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
        while (existing != null && !Files.exists(existing, LinkOption.NOFOLLOW_LINKS)) {
            existing = existing.getParent();
        }
        if (existing == null) {
            throw new IllegalArgumentException("Path is outside the project: " + rawPath);
        }
        Path existingReal = realExisting(existing, rawPath);
        if (!isInside(root, existingReal)) {
            throw new IllegalArgumentException("Path is outside the project: " + rawPath);
        }
        Path resolved = existingReal.resolve(existing.relativize(normalized)).normalize();
        if (!isInside(root, resolved)) {
            throw new IllegalArgumentException("Path is outside the project: " + rawPath);
        }
        return resolved;
    }

    private static Path realExisting(Path existing, String rawPath) {
        try {
            return existing.toRealPath();
        } catch (NoSuchFileException | FileSystemLoopException e) {
            throw new IllegalArgumentException("Path is outside the project: " + rawPath, e);
        } catch (IOException e) {
            throw new IllegalArgumentException("Could not resolve path: " + rawPath, e);
        }
    }

    private static byte[] readCapped(Path file, String rawPath) throws IOException {
        try (InputStream in = Files.newInputStream(file, LinkOption.NOFOLLOW_LINKS)) {
            byte[] bytes = in.readNBytes(MAX_READ_BYTES + 1);
            if (bytes.length > MAX_READ_BYTES) {
                throw new IllegalArgumentException(
                        "File is too large to read (limit " + MAX_READ_BYTES + " bytes): " + rawPath);
            }
            return bytes;
        }
    }

    /**
     * Refuses the final path when it is a symlink or a regular file with another
     * hard link. {@link #resolveInside} already rejects a symlink that points
     * outside; this catches a link that appears at the resolved path.
     */
    private static void rejectLinkedFile(Path file, String rawPath) throws IOException {
        if (!Files.exists(file, LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        if (Files.isSymbolicLink(file)) {
            throw new IllegalArgumentException("Refusing to follow a symlink: " + rawPath);
        }
        long links = linkCount(file);
        if (links > 1 && Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
            throw new IllegalArgumentException("Refusing to use a hard-linked file: " + rawPath);
        }
    }

    private static long linkCount(Path file) {
        try {
            Object nlink = Files.getAttribute(file, "unix:nlink", LinkOption.NOFOLLOW_LINKS);
            if (nlink instanceof Number count) {
                return count.longValue();
            }
        } catch (UnsupportedOperationException | IllegalArgumentException | IOException ignored) {
            // The file store has no link count. Hard-link escapes are not representable there.
        }
        return 1;
    }

    private static boolean isInside(Path root, Path candidate) {
        return candidate.startsWith(root);
    }
}
