package com.unrealinterface.unreal;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;

/**
 * Finds Unreal Engine installs the way the editor's version selector does:
 * Install.ini on macOS and Linux, the launcher manifest and registry on Windows.
 */
final class EngineInstallations {

    record Install(Path root, String key) {
    }

    @FunctionalInterface
    interface Source {
        List<Install> list();
    }

    private static final Pattern REGISTRY_VALUE = Pattern.compile("^\\s*(.*?)\\s+REG_SZ\\s+(.*\\S)\\s*$");

    private EngineInstallations() {
    }

    static Source system() {
        return () -> {
            try {
                HostOs os = HostOs.current();
                Path home = Path.of(System.getProperty("user.home", "."));
                String programData = System.getenv("PROGRAMDATA");
                Path program = programData == null || programData.isBlank()
                        ? Path.of("C:\\ProgramData")
                        : Path.of(programData);
                Supplier<String> registry = os == HostOs.WINDOWS ? EngineInstallations::queryRegistry : () -> "";
                return readConfigured(home, program, os, registry);
            } catch (RuntimeException ex) {
                return List.of();
            }
        };
    }

    static List<Install> readConfigured(Path home, Path programData, HostOs os, Supplier<String> registry) {
        List<Install> parsed = new ArrayList<>();
        try {
            if (os == HostOs.WINDOWS) {
                Path manifest = programData.resolve("Epic").resolve("UnrealEngineLauncher")
                        .resolve("LauncherInstalled.dat");
                parsed.addAll(parseLauncherInstalled(readQuiet(manifest)));
                parsed.addAll(parseRegistryQuery(registry == null ? "" : safeGet(registry)));
            } else {
                parsed.addAll(parseInstallIni(readQuiet(installIni(home, os))));
            }
        } catch (RuntimeException ex) {
            return List.of();
        }
        return existingEngines(parsed);
    }

    static Path installIni(Path home, HostOs os) {
        return switch (os) {
            case MAC -> home.resolve("Library").resolve("Application Support").resolve("Epic")
                    .resolve("UnrealEngine").resolve("Install.ini");
            case LINUX -> home.resolve(".config").resolve("Epic").resolve("UnrealEngine").resolve("Install.ini");
            case WINDOWS -> null;
        };
    }

    static List<Install> parseInstallIni(String text) {
        if (text == null || text.isBlank()) {
            return List.of();
        }
        boolean inSection = false;
        LinkedHashMap<String, Install> byKey = new LinkedHashMap<>();
        for (String line : text.replace("\r\n", "\n").replace('\r', '\n').split("\n", -1)) {
            String trimmed = line.trim();
            if (trimmed.isEmpty() || trimmed.startsWith(";") || trimmed.startsWith("#")) {
                continue;
            }
            if (trimmed.startsWith("[") && trimmed.endsWith("]")) {
                inSection = trimmed.equalsIgnoreCase("[Installations]");
                continue;
            }
            if (!inSection) {
                continue;
            }
            int eq = trimmed.indexOf('=');
            if (eq <= 0) {
                continue;
            }
            String key = trimmed.substring(0, eq).trim();
            String value = stripQuotes(trimmed.substring(eq + 1).trim());
            if (key.isEmpty() || value.isEmpty()) {
                continue;
            }
            byKey.put(canon(key), new Install(Path.of(value), key));
        }
        return List.copyOf(byKey.values());
    }

    static List<Install> parseLauncherInstalled(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            JsonNode root = JsonTexts.MAPPER.readTree(json);
            JsonNode list = root.get("InstallationList");
            if (list == null || !list.isArray()) {
                return List.of();
            }
            List<Install> installs = new ArrayList<>();
            for (JsonNode item : list) {
                String location = item.path("InstallLocation").asText("");
                if (location.isBlank()) {
                    continue;
                }
                String key = item.path("AppName").asText("");
                installs.add(new Install(Path.of(location), key));
            }
            return installs;
        } catch (JsonProcessingException ex) {
            return List.of();
        }
    }

    static List<Install> parseRegistryQuery(String text) {
        if (text == null || text.isBlank()) {
            return List.of();
        }
        List<Install> installs = new ArrayList<>();
        for (String line : text.replace("\r\n", "\n").split("\n", -1)) {
            Matcher matcher = REGISTRY_VALUE.matcher(line);
            if (!matcher.matches()) {
                continue;
            }
            String key = matcher.group(1).trim();
            String path = stripQuotes(matcher.group(2).trim());
            if (key.isEmpty() || path.isEmpty()) {
                continue;
            }
            installs.add(new Install(Path.of(path), key));
        }
        return installs;
    }

    static String canon(String key) {
        if (key == null) {
            return "";
        }
        String trimmed = key.trim();
        if (trimmed.startsWith("{") && trimmed.endsWith("}") && trimmed.length() >= 2) {
            trimmed = trimmed.substring(1, trimmed.length() - 1);
        }
        return trimmed.toLowerCase(Locale.ROOT);
    }

    private static List<Install> existingEngines(List<Install> parsed) {
        List<Install> existing = new ArrayList<>();
        for (Install install : parsed) {
            if (install.root() == null) {
                continue;
            }
            try {
                Path root = install.root().toAbsolutePath().normalize();
                if (Files.isRegularFile(root.resolve("Engine").resolve("Build").resolve("Build.version"))) {
                    existing.add(new Install(root, install.key()));
                }
            } catch (RuntimeException ex) {
                // Skip an install path that cannot be resolved.
            }
        }
        return existing;
    }

    private static String readQuiet(Path file) {
        try {
            if (file == null || !Files.isRegularFile(file)) {
                return "";
            }
            return Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException ex) {
            return "";
        }
    }

    private static String safeGet(Supplier<String> registry) {
        try {
            String value = registry.get();
            return value == null ? "" : value;
        } catch (RuntimeException ex) {
            return "";
        }
    }

    private static String stripQuotes(String value) {
        if (value.length() >= 2 && value.startsWith("\"") && value.endsWith("\"")) {
            return value.substring(1, value.length() - 1);
        }
        return value;
    }

    /** Source-build map. The value name is the .uproject EngineAssociation. */
    private static String queryRegistry() {
        Process process = null;
        try {
            process = new ProcessBuilder("reg", "query", "HKCU\\Software\\Epic Games\\Unreal Engine\\Builds")
                    .redirectErrorStream(true)
                    .start();
            boolean finished = process.waitFor(5, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                return "";
            }
            return new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException ex) {
            return "";
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            return "";
        } finally {
            if (process != null) {
                process.destroy();
            }
        }
    }
}
