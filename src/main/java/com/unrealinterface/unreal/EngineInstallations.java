package com.unrealinterface.unreal;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
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
 * Also lists unregistered trees, such as a zip extracted under Downloads, and
 * reads the optional {@code UNREAL_ENGINE_ROOT} override.
 */
final class EngineInstallations {

    /** JVM system property. Wins over {@link #ROOT_ENV} when both are set. */
    static final String ROOT_PROPERTY = "unreal.engine.root";

    /** Process environment variable naming an engine root. */
    static final String ROOT_ENV = "UNREAL_ENGINE_ROOT";

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

    /**
     * Directories whose immediate children may be an unregistered engine.
     * Listing is one level deep. Callers still require {@code Engine/Build/Build.version}.
     */
    static List<Path> defaultUnregisteredRoots(Path home, HostOs os) {
        List<Path> roots = new ArrayList<>();
        if (home != null) {
            roots.add(home.resolve("Downloads"));
            roots.add(home);
        }
        HostOs host = os == null ? HostOs.current() : os;
        switch (host) {
            case LINUX -> roots.add(Path.of("/opt"));
            case MAC -> roots.add(Path.of("/Users/Shared/Epic Games"));
            case WINDOWS -> {
                String programFiles = System.getenv("ProgramFiles");
                Path programs = programFiles == null || programFiles.isBlank()
                        ? Path.of("C:\\Program Files")
                        : Path.of(programFiles);
                roots.add(programs.resolve("Epic Games"));
            }
        }
        return List.copyOf(roots);
    }

    /**
     * Child directories whose names look like an extracted or source engine.
     * Prefixes: {@code Linux_Unreal_Engine*}, {@code UnrealEngine*}, {@code UE_*}.
     */
    static List<Path> unregisteredCandidates(List<Path> directories) {
        if (directories == null || directories.isEmpty()) {
            return List.of();
        }
        List<Path> found = new ArrayList<>();
        for (Path directory : directories) {
            if (directory == null || !Files.isDirectory(directory)) {
                continue;
            }
            try (DirectoryStream<Path> listing = Files.newDirectoryStream(directory)) {
                for (Path path : listing) {
                    Path name = path.getFileName();
                    if (name != null && matchesEngineDirectoryName(name.toString()) && Files.isDirectory(path)) {
                        found.add(path);
                    }
                }
            } catch (IOException | RuntimeException ex) {
                // Skip a directory that cannot be listed.
            }
        }
        return found;
    }

    static boolean matchesEngineDirectoryName(String name) {
        if (name == null || name.isEmpty()) {
            return false;
        }
        return startsWithIgnoreCase(name, "Linux_Unreal_Engine")
                || startsWithIgnoreCase(name, "UnrealEngine")
                || startsWithIgnoreCase(name, "UE_");
    }

    /** Property, then environment variable. Blank values are ignored. */
    static String configuredEngineRoot() {
        return configuredEngineRoot(System.getProperty(ROOT_PROPERTY), System.getenv(ROOT_ENV));
    }

    static String configuredEngineRoot(String property, String env) {
        if (property != null && !property.isBlank()) {
            return property.trim();
        }
        if (env != null && !env.isBlank()) {
            return env.trim();
        }
        return null;
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

    private static boolean startsWithIgnoreCase(String text, String prefix) {
        return text.regionMatches(true, 0, prefix, 0, prefix.length());
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
