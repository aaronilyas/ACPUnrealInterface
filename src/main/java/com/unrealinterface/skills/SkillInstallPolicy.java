package com.unrealinterface.skills;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Decides when the window may copy the skills bundle into a directory.
 * Typing a path and leaving the field installs only into a folder that contains
 * a {@code .uproject}. Browse and session start may install into any other
 * directory. The home directory and its {@code .claude} and {@code .agents}
 * trees are never targets, and each resolved directory is installed once.
 */
public final class SkillInstallPolicy {

    public enum Trigger {
        /** Project field commit or focus loss. */
        AUTO,
        /** Browse, or the start of a session. */
        EXPLICIT
    }

    public enum Outcome {
        INSTALL, SKIP, FORBIDDEN
    }

    public record Decision(Outcome outcome, Path directory, boolean report, String message) {
        static Decision skip() {
            return new Decision(Outcome.SKIP, null, false, "");
        }

        static Decision install(Path directory) {
            return new Decision(Outcome.INSTALL, directory, false, "");
        }

        static Decision forbidden(boolean report) {
            return new Decision(Outcome.FORBIDDEN, null, report,
                    "Refusing to install skills into the home directory or its .claude or .agents folders");
        }
    }

    private final Path homeAbsolute;
    private final Path homeReal;
    private final Set<Path> installed = ConcurrentHashMap.newKeySet();
    private final Set<Path> forbiddenNoted = ConcurrentHashMap.newKeySet();

    public SkillInstallPolicy() {
        this(Path.of(System.getProperty("user.home", "")));
    }

    public SkillInstallPolicy(Path userHome) {
        this.homeAbsolute = userHome.toAbsolutePath().normalize();
        Path real = homeAbsolute;
        try {
            if (Files.exists(userHome)) {
                real = userHome.toRealPath();
            }
        } catch (IOException e) {
            real = homeAbsolute;
        }
        this.homeReal = real;
    }

    public Decision evaluate(Path directory, Trigger trigger) throws IOException {
        if (directory == null || !Files.isDirectory(directory)) {
            return Decision.skip();
        }
        Path absolute = directory.toAbsolutePath().normalize();
        Path resolved = directory.toRealPath();
        if (isForbidden(absolute, homeAbsolute) || isForbidden(resolved, homeReal)) {
            boolean tell = trigger == Trigger.EXPLICIT && forbiddenNoted.add(resolved);
            return Decision.forbidden(tell);
        }
        if (trigger == Trigger.AUTO && !containsUproject(resolved)) {
            return Decision.skip();
        }
        if (!installed.add(resolved)) {
            return Decision.skip();
        }
        return Decision.install(resolved);
    }

    /** Lets a failed copy try again for the same directory. */
    public void release(Path directory) {
        if (directory != null) {
            installed.remove(directory);
        }
    }

    static boolean containsUproject(Path directory) throws IOException {
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(directory, "*.uproject")) {
            for (Path candidate : stream) {
                if (Files.isRegularFile(candidate)) {
                    return true;
                }
            }
        }
        return false;
    }

    static boolean isForbidden(Path candidate, Path home) {
        if (candidate.equals(home)) {
            return true;
        }
        return candidate.startsWith(home.resolve(".claude")) || candidate.startsWith(home.resolve(".agents"));
    }
}
