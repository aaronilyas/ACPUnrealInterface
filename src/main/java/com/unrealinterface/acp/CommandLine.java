package com.unrealinterface.acp;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Splits an agent command the way a shell would split words, without starting a shell.
 * ACP agents must own stdout, so the program is executed directly.
 *
 * <p>The program is resolved to an absolute path before the process starts. The agent
 * working directory is the project, which is not a trusted place to look up a command:
 * a relative program is resolved against this process's directory, and a bare name is
 * taken only from an absolute PATH entry. Entries of {@code .} or a relative directory
 * are skipped so a file in the project cannot shadow {@code npx} or {@code python3}.
 */
public final class CommandLine {

    private CommandLine() {
    }

    public static List<String> tokenize(String commandLine) {
        if (commandLine == null || commandLine.isBlank()) {
            throw new IllegalArgumentException("Agent command is empty");
        }
        List<String> tokens = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean inSingle = false;
        boolean inDouble = false;
        boolean hasToken = false;
        for (int i = 0; i < commandLine.length(); i++) {
            char c = commandLine.charAt(i);
            if (inSingle) {
                if (c == '\'') {
                    inSingle = false;
                } else {
                    current.append(c);
                }
                hasToken = true;
                continue;
            }
            if (inDouble) {
                if (c == '"') {
                    inDouble = false;
                } else if (c == '\\' && i + 1 < commandLine.length()) {
                    current.append(commandLine.charAt(++i));
                } else {
                    current.append(c);
                }
                hasToken = true;
                continue;
            }
            if (Character.isWhitespace(c)) {
                if (hasToken) {
                    tokens.add(current.toString());
                    current.setLength(0);
                    hasToken = false;
                }
                continue;
            }
            if (c == '\'') {
                inSingle = true;
                hasToken = true;
                continue;
            }
            if (c == '"') {
                inDouble = true;
                hasToken = true;
                continue;
            }
            if (c == '\\' && i + 1 < commandLine.length()) {
                current.append(commandLine.charAt(++i));
                hasToken = true;
                continue;
            }
            current.append(c);
            hasToken = true;
        }
        if (inSingle || inDouble) {
            throw new IllegalArgumentException("Unclosed quote in agent command");
        }
        if (hasToken) {
            tokens.add(current.toString());
        }
        if (tokens.isEmpty()) {
            throw new IllegalArgumentException("Agent command is empty");
        }
        return List.copyOf(tokens);
    }

    public static boolean isExecutable(String program) {
        return resolveExecutable(program) != null;
    }

    /**
     * Absolute path of {@code program}, or null when it is not an executable file.
     */
    public static String resolveExecutable(String program) {
        return resolveExecutable(program, System.getenv("PATH"), Path.of(System.getProperty("user.dir", ".")));
    }

    static String resolveExecutable(String program, String pathEnv, Path baseDirectory) {
        if (program == null || program.isBlank() || program.indexOf('\0') >= 0) {
            return null;
        }
        try {
            if (isExplicitPath(program)) {
                Path path = Path.of(program);
                Path absolute = path.isAbsolute() ? path.normalize() : baseDirectory.resolve(path).normalize();
                return executableFile(absolute) ? absolute.toString() : null;
            }
        } catch (InvalidPathException e) {
            return null;
        }
        if (pathEnv == null || pathEnv.isBlank()) {
            return null;
        }
        for (String directory : pathEnv.split(File.pathSeparator)) {
            if (directory.isEmpty() || ".".equals(directory)) {
                continue;
            }
            Path dir;
            try {
                dir = Path.of(directory);
            } catch (InvalidPathException e) {
                continue;
            }
            if (!dir.isAbsolute()) {
                continue;
            }
            Path candidate = dir.resolve(program).normalize();
            if (executableFile(candidate)) {
                return candidate.toString();
            }
        }
        return null;
    }

    private static boolean isExplicitPath(String program) {
        return program.indexOf('/') >= 0 || program.indexOf('\\') >= 0;
    }

    private static boolean executableFile(Path candidate) {
        return Files.isExecutable(candidate) && !Files.isDirectory(candidate);
    }
}
