package com.unrealinterface.acp;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Splits an agent command the way a shell would split words, without starting a shell.
 * ACP agents must own stdout, so the program is executed directly.
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
        if (program == null || program.isBlank()) {
            return false;
        }
        Path path = Path.of(program);
        if (program.contains("/") || program.contains("\\")) {
            return Files.isExecutable(path);
        }
        String pathEnv = System.getenv("PATH");
        if (pathEnv == null || pathEnv.isBlank()) {
            return false;
        }
        for (String directory : pathEnv.split(File.pathSeparator)) {
            if (directory.isEmpty()) {
                continue;
            }
            if (Files.isExecutable(Path.of(directory).resolve(program))) {
                return true;
            }
        }
        return false;
    }
}
