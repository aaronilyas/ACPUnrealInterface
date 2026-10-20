package com.unrealinterface.unreal;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * Enables the official Unreal Engine 5.8 MCP server for one project folder.
 * Edits stay inside that folder, skip files that already match, and leave
 * unreadable JSON untouched.
 */
public final class UnrealMcpSetup {

    public enum Status {
        CONFIGURED,
        READY,
        ENGINE_MISSING,
        PLUGIN_MISSING,
        ENGINE_TOO_OLD,
        NO_PROJECT,
        AMBIGUOUS_PROJECT,
        FAILED
    }

    public record Result(Status status, String message, boolean changed) {
    }

    private static final Pattern VERSION_TEXT = Pattern.compile("^(\\d+)\\.(\\d+)(?:\\.\\d+)?$");
    private static final String PLUGIN_RELATIVE = "Engine/Plugins/Experimental/ModelContextProtocol";

    private final EngineInstallations.Source engines;
    private final HostOs os;
    private final List<Path> unregisteredRoots;
    private final Supplier<String> engineRootOverride;

    public UnrealMcpSetup() {
        this(EngineInstallations.system(), HostOs.current(),
                EngineInstallations.defaultUnregisteredRoots(
                        Path.of(System.getProperty("user.home", ".")), HostOs.current()),
                EngineInstallations::configuredEngineRoot);
    }

    UnrealMcpSetup(EngineInstallations.Source engines, HostOs os) {
        this(engines, os, List.of(), () -> null);
    }

    UnrealMcpSetup(EngineInstallations.Source engines, HostOs os, List<Path> unregisteredRoots,
            Supplier<String> engineRootOverride) {
        this.engines = engines == null ? () -> List.of() : engines;
        this.os = os == null ? HostOs.current() : os;
        this.unregisteredRoots = unregisteredRoots == null ? List.of() : List.copyOf(unregisteredRoots);
        this.engineRootOverride = engineRootOverride == null ? () -> null : engineRootOverride;
    }

    public Result setup(Path projectDirectory) {
        try {
            if (projectDirectory == null) {
                return noProject(List.of());
            }
            if (!Files.exists(projectDirectory)) {
                return new Result(Status.FAILED,
                        "Project directory does not exist: " + projectDirectory
                                + ". Install Unreal Engine 5.8 or newer, create a project, then select the folder that contains the .uproject.",
                        false);
            }
            if (!Files.isDirectory(projectDirectory)) {
                return new Result(Status.FAILED,
                        "Project path is not a folder: " + projectDirectory
                                + ". Select the folder that contains the .uproject.",
                        false);
            }
            return setupInside(projectDirectory);
        } catch (IOException ex) {
            String detail = ex.getMessage() == null ? ex.toString() : ex.getMessage();
            return new Result(Status.FAILED,
                    "Could not set up Unreal MCP in " + projectDirectory + ". " + detail
                            + ". Check that the folder is writable, then click Setup Unreal MCP.",
                    false);
        }
    }

    private Result setupInside(Path requested) throws IOException {
        Path directory = requested.toRealPath();
        List<EngineInstallations.Install> installs = loadInstalls();
        List<Path> projects = uprojects(directory);
        if (projects.isEmpty()) {
            return noProject(installs);
        }
        if (projects.size() > 1) {
            String names = projects.stream()
                    .map(path -> path.getFileName().toString())
                    .reduce((left, right) -> left + ", " + right)
                    .orElse("");
            return new Result(Status.AMBIGUOUS_PROJECT,
                    "This folder contains more than one .uproject (" + names
                            + "). Unreal MCP was not set up. Keep a single project in the folder, then click Setup Unreal MCP.",
                    false);
        }
        Path uproject = projects.get(0);
        Path realUproject = uproject.toRealPath();
        String projectFile = realUproject.getFileName().toString();
        if (!realUproject.startsWith(directory)) {
            return new Result(Status.FAILED,
                    projectFile + " points outside the selected folder, so Unreal MCP was not set up. "
                            + "Select the real project folder, then click Setup Unreal MCP.",
                    false);
        }

        String original = Files.readString(realUproject, StandardCharsets.UTF_8);
        UprojectPlugins.Parsed parsed = UprojectPlugins.parse(original);
        if (parsed.error() != null) {
            return new Result(Status.FAILED,
                    "Could not set up Unreal MCP. " + projectFile + " " + parsed.error()
                            + ". Fix that file, then click Setup Unreal MCP.",
                    false);
        }
        ObjectNode root = parsed.root();
        String association = UprojectPlugins.association(root);
        EngineFinding engine = findEngine(directory, association, installs);
        if (engine.state == EngineState.TOO_OLD) {
            return new Result(Status.ENGINE_TOO_OLD, describe(Status.ENGINE_TOO_OLD, projectFile, false, engine,
                    EditorUserSettings.DEFAULT_URL, null, false, iniRelative(), false), false);
        }

        UprojectPlugins.Edit plugins = UprojectPlugins.enable(root);
        if (plugins.error() != null) {
            return new Result(Status.FAILED,
                    "Could not set up Unreal MCP. " + projectFile + " " + plugins.error()
                            + ". Fix that file, then click Setup Unreal MCP.",
                    false);
        }

        boolean neverOpened = !Files.isDirectory(directory.resolve("Saved"));
        Path iniPath = directory.resolve("Saved").resolve("Config").resolve(os.editorDirectory())
                .resolve("EditorPerProjectUserSettings.ini");
        requireInside(directory, iniPath);
        String iniOriginal = Files.isRegularFile(iniPath) ? Files.readString(iniPath, StandardCharsets.UTF_8) : null;
        if (Files.exists(iniPath) && iniOriginal == null) {
            return new Result(Status.FAILED,
                    iniPath.getFileName() + " is not a file. Remove it, then click Setup Unreal MCP.",
                    false);
        }
        EditorUserSettings.Result ini = EditorUserSettings.apply(iniOriginal);
        String url = EditorUserSettings.endpoint(ini.port(), ini.urlPath());

        Path mcpPath = directory.resolve(".mcp.json");
        requireInside(directory, mcpPath);
        String mcpOriginal = readRegular(mcpPath, ".mcp.json");
        McpClientConfig.Edit mcp = McpClientConfig.mcpJson(mcpOriginal, url);

        Path tomlPath = directory.resolve(".codex").resolve("config.toml");
        requireInside(directory, tomlPath);
        String tomlOriginal = readRegular(tomlPath, ".codex/config.toml");
        McpClientConfig.Edit toml = McpClientConfig.codexToml(tomlOriginal, url);

        boolean projectChanged = false;
        boolean changed = false;
        if (plugins.shouldWrite()) {
            projectChanged |= TextFiles.writeIfChanged(realUproject, plugins.content());
        }
        if (ini.changed()) {
            projectChanged |= TextFiles.writeIfChanged(iniPath, ini.content());
        }
        changed |= projectChanged;
        if (mcp.shouldWrite()) {
            changed |= TextFiles.writeIfChanged(mcpPath, mcp.content());
        }
        if (toml.shouldWrite()) {
            changed |= TextFiles.writeIfChanged(tomlPath, toml.content());
        }

        if (mcp.failed() || toml.failed()) {
            return new Result(Status.FAILED, partialFailure(projectFile, url, projectChanged, mcp, toml), changed);
        }

        Status status = switch (engine.state) {
            case OK -> changed ? Status.CONFIGURED : Status.READY;
            case PLUGIN_MISSING -> Status.PLUGIN_MISSING;
            case NOT_INSTALLED, UNRESOLVED -> Status.ENGINE_MISSING;
            case TOO_OLD -> Status.ENGINE_TOO_OLD;
        };
        String message = describe(status, projectFile, neverOpened, engine, url, ini.warning(),
                toml.customCommand(), iniRelative(), changed);
        return new Result(status, message, changed);
    }

    /** Rejects a path whose real parent leaves the selected project, including through a symlink. */
    private static void requireInside(Path directory, Path file) throws IOException {
        Path existing = file;
        while (existing != null && !Files.exists(existing)) {
            existing = existing.getParent();
        }
        if (existing == null) {
            throw new IOException(file.getFileName() + " is outside the selected folder");
        }
        Path real = existing.toRealPath();
        if (!real.startsWith(directory)) {
            throw new IOException(file.getFileName() + " points outside the selected folder");
        }
        Path resolved = real.resolve(existing.relativize(file)).normalize();
        if (!resolved.startsWith(directory)) {
            throw new IOException(file.getFileName() + " points outside the selected folder");
        }
    }

    private String readRegular(Path file, String label) throws IOException {
        if (!Files.exists(file)) {
            return null;
        }
        if (!Files.isRegularFile(file)) {
            throw new IOException(label + " is not a file");
        }
        return Files.readString(file, StandardCharsets.UTF_8);
    }

    private String iniRelative() {
        return "Saved/Config/" + os.editorDirectory() + "/EditorPerProjectUserSettings.ini";
    }

    private List<EngineInstallations.Install> loadInstalls() {
        try {
            List<EngineInstallations.Install> listed = engines.list();
            return listed == null ? List.of() : listed;
        } catch (RuntimeException ex) {
            return List.of();
        }
    }

    private Result noProject(List<EngineInstallations.Install> installs) {
        boolean any = false;
        boolean anySupported = false;
        for (EngineInstallations.Install install : installs) {
            if (install.root() == null || !isEngineRoot(install.root())) {
                continue;
            }
            any = true;
            EngineVersion version = readVersion(install.root());
            if (hasPlugin(install.root()) || (version != null && !version.olderThanMcp())) {
                anySupported = true;
            }
        }
        if (!anySupported) {
            for (Path root : locatedUnregisteredRoots()) {
                any = true;
                EngineVersion version = readVersion(root);
                if (hasPlugin(root) || (version != null && !version.olderThanMcp())) {
                    anySupported = true;
                }
            }
        }
        String message;
        if (!any) {
            message = "This folder has no .uproject, so Unreal MCP was not set up. Install Unreal Engine 5.8 or newer "
                    + "from the Epic Games Launcher, choose File > New Project, then select the folder that contains the new .uproject.";
        } else if (!anySupported) {
            message = "This folder has no .uproject, so Unreal MCP was not set up. The Unreal Engine installs found here "
                    + "are older than 5.8. Install Unreal Engine 5.8 or newer, choose File > New Project, then select the folder that contains the new .uproject.";
        } else {
            message = "This folder has no .uproject, so Unreal MCP was not set up. In Unreal Editor 5.8 or newer, "
                    + "choose File > New Project, then select the folder that contains the new .uproject. "
                    + "UnrealInterface enables the MCP server when that folder is selected.";
        }
        return new Result(Status.NO_PROJECT, message, false);
    }

    private static String partialFailure(String projectFile, String url, boolean wroteProjectFiles,
            McpClientConfig.Edit mcp, McpClientConfig.Edit toml) {
        StringBuilder message = new StringBuilder();
        if (wroteProjectFiles) {
            message.append("Updated plugins and auto-start for ").append(projectFile).append(". ");
        }
        if (mcp.failed()) {
            message.append(".mcp.json ").append(mcp.error())
                    .append(". Fix or replace it so mcpServers.unreal-mcp is type http and url ")
                    .append(url).append(". ");
        }
        if (toml.failed()) {
            message.append(toml.error());
            if (!toml.error().endsWith(".")) {
                message.append(".");
            }
            message.append(" ");
        }
        message.append("Click Setup Unreal MCP to try again.");
        return message.toString();
    }

    private static String describe(Status status, String projectFile, boolean neverOpened, EngineFinding engine,
            String url, String warning, boolean codexCustom, String iniRelative, boolean changed) {
        String message = switch (status) {
            case ENGINE_TOO_OLD -> tooOld(projectFile, engine);
            case PLUGIN_MISSING -> lead(changed, projectFile) + "The engine at " + engine.root
                    + " does not include " + PLUGIN_RELATIVE
                    + ". Install or rebuild Unreal Engine 5.8 or newer with that plugin, open " + projectFile
                    + ", and restart the editor. The server listens at " + url + ".";
            case ENGINE_MISSING -> engine.state == EngineState.NOT_INSTALLED
                    ? notInstalled(projectFile, engine, url, changed)
                    : unresolved(projectFile, engine, url, changed);
            case CONFIGURED -> configured(projectFile, neverOpened, url, codexCustom, iniRelative, engine);
            case READY -> ready(projectFile, url, codexCustom, engine);
            case NO_PROJECT, AMBIGUOUS_PROJECT, FAILED -> "";
        };
        if (status == Status.ENGINE_MISSING || status == Status.PLUGIN_MISSING) {
            message = message + " Auto-start is in " + iniRelative + ".";
        }
        if (warning != null && !warning.isBlank() && !message.isEmpty()) {
            message = message + " " + warning;
        }
        return message;
    }

    private static String lead(boolean changed, String projectFile) {
        if (changed) {
            return "Wrote Unreal MCP settings for " + projectFile + ". ";
        }
        return "Unreal MCP settings are already in place for " + projectFile + ". ";
    }

    private static String clientSentence(String url, boolean codexCustom) {
        if (codexCustom) {
            return ".mcp.json points at " + url
                    + ". .codex/config.toml already defines unreal-mcp and was left unchanged";
        }
        return ".mcp.json and .codex/config.toml point at " + url;
    }

    private static String configured(String projectFile, boolean neverOpened, String url, boolean codexCustom,
            String iniRelative, EngineFinding engine) {
        String clients = clientSentence(url, codexCustom);
        String where = usingEngine(engine);
        if (neverOpened) {
            return "Unreal MCP is set up for " + projectFile + where
                    + ". This project has not been opened in the editor yet. Launch " + projectFile
                    + " in Unreal Editor 5.8 or newer. ModelContextProtocol and AllToolsets are enabled, bAutoStartServer is True in "
                    + iniRelative + ", and " + clients + ".";
        }
        return "Unreal MCP is set up for " + projectFile + where
                + ". ModelContextProtocol and AllToolsets are enabled, bAutoStartServer is True in " + iniRelative
                + ", and " + clients + ". Restart Unreal Editor so the server starts, then call list_toolsets.";
    }

    private static String ready(String projectFile, String url, boolean codexCustom, EngineFinding engine) {
        return "Unreal MCP is already set up for " + projectFile + usingEngine(engine) + ". "
                + clientSentence(url, codexCustom)
                + ". Restart Unreal Editor if a tool call cannot connect, then call list_toolsets. The Output Log shows the MCP listen line.";
    }

    /** Names an engine found outside the registration table. Empty for a registered or path association. */
    private static String usingEngine(EngineFinding engine) {
        if (engine == null || !engine.discovered() || engine.root() == null) {
            return "";
        }
        return " using the Unreal Engine at " + engine.root();
    }

    private static String unresolved(String projectFile, EngineFinding engine, String url, boolean changed) {
        String version = engine.versionLabel == null || engine.versionLabel.isBlank()
                ? "a 5.8 or newer engine"
                : "Unreal Engine " + engine.versionLabel;
        return lead(changed, projectFile) + "Unreal Engine is installed, but this project does not point at "
                + version
                + ". Right-click " + projectFile
                + ", choose Switch Unreal Engine version, select 5.8 or newer, and restart the editor. The server listens at "
                + url + ".";
    }

    private static String notInstalled(String projectFile, EngineFinding engine, String url, boolean changed) {
        String version = engine.versionLabel == null || engine.versionLabel.isBlank()
                ? "5.8 or newer"
                : engine.versionLabel + " or newer";
        return lead(changed, projectFile) + "Unreal Engine " + version
                + " is not installed. Install it from the Epic Games Launcher, open " + projectFile
                + ", and allow the editor to restart once. The server listens at " + url + ".";
    }

    private static String tooOld(String projectFile, EngineFinding engine) {
        String version = engine.versionLabel == null || engine.versionLabel.isBlank()
                ? "an older release"
                : engine.versionLabel;
        String where = engine.root == null ? "" : " at " + engine.root;
        return projectFile + " targets Unreal Engine " + version + where
                + ". Official MCP requires Unreal Engine 5.8 or newer. Install 5.8 or newer, right-click "
                + projectFile + ", choose Switch Unreal Engine version, then click Setup Unreal MCP. No files were changed.";
    }

    private static List<Path> uprojects(Path directory) throws IOException {
        try (Stream<Path> listing = Files.list(directory)) {
            return listing
                    .filter(path -> path.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".uproject"))
                    .filter(Files::isRegularFile)
                    .sorted(Comparator.comparing(path -> path.getFileName().toString()))
                    .toList();
        }
    }

    private EngineFinding findEngine(Path project, String association, List<EngineInstallations.Install> installs) {
        List<EngineInstallations.Install> existing = new ArrayList<>();
        for (EngineInstallations.Install install : installs) {
            if (install.root() != null && isEngineRoot(install.root())) {
                existing.add(install);
            }
        }
        boolean any = !existing.isEmpty();
        Version requested = parseVersion(association);
        if (isPath(association)) {
            Path root = resolveEnginePath(project, association);
            if (root == null) {
                return missing(any, requested);
            }
            return judge(root);
        }
        if (requested != null && requested.olderThanMcp()) {
            Path root = matchVersion(existing, requested);
            return new EngineFinding(EngineState.TOO_OLD, root, requested.label());
        }
        if (association == null || association.isBlank()) {
            return findBlankAssociation(project, any);
        }
        if (requested != null) {
            Path root = matchVersion(existing, requested);
            if (root == null) {
                Path parent = walkParents(project);
                EngineVersion parentVersion = parent == null ? null : readVersion(parent);
                if (parentVersion != null && parentVersion.major() == requested.major
                        && parentVersion.minor() == requested.minor) {
                    root = parent;
                }
            }
            if (root == null) {
                return discoverUnregistered(any, requested);
            }
            return judge(root);
        }
        Path root = matchKey(existing, association);
        if (root == null) {
            return missing(any, null);
        }
        return judge(root);
    }

    private static EngineFinding missing(boolean anyInstall, Version requested) {
        if (!anyInstall) {
            String label = requested == null ? "" : requested.label();
            return new EngineFinding(EngineState.NOT_INSTALLED, null, label);
        }
        String label = requested == null ? "" : requested.label();
        return new EngineFinding(EngineState.UNRESOLVED, null, label);
    }

    private static EngineFinding judge(Path root) {
        return judge(root, false);
    }

    private static EngineFinding judge(Path root, boolean discovered) {
        EngineVersion version = readVersion(root);
        String label = version == null ? "" : version.major + "." + version.minor;
        if (version != null && version.olderThanMcp()) {
            return new EngineFinding(EngineState.TOO_OLD, root, label, discovered);
        }
        if (!hasPlugin(root)) {
            return new EngineFinding(EngineState.PLUGIN_MISSING, root, label, discovered);
        }
        return new EngineFinding(EngineState.OK, root, label, discovered);
    }

    /**
     * Blank or missing association. The override wins when it is 5.8 or newer, then a parent
     * engine of any version, then the highest unregistered engine that is 5.8 or newer.
     * An older parent stops the search. Directory listing runs only after those two miss.
     */
    private EngineFinding findBlankAssociation(Path project, boolean anyRegistered) {
        Path override = resolveOverride(readOverride());
        if (accepts(override, null)) {
            return judge(override, true);
        }
        Path parent = walkParents(project);
        if (parent != null) {
            return judge(parent);
        }
        return discoverUnregistered(anyRegistered, null);
    }

    private EngineFinding discoverUnregistered(boolean anyRegistered, Version requested) {
        List<Path> unregistered = locatedUnregisteredRoots();
        Path discovered = selectUnregistered(unregistered, requested);
        if (discovered != null) {
            return judge(discovered, true);
        }
        return missing(anyRegistered || hasSupportedUnregistered(unregistered), requested);
    }

    /**
     * Highest 5.8+ root. A version must match major and minor. A null version accepts any
     * 5.8 or newer engine. The override wins when it matches, ahead of the directory search.
     */
    private Path selectUnregistered(List<Path> roots, Version requested) {
        Path override = resolveOverride(readOverride());
        if (override != null && accepts(override, requested)) {
            return override;
        }
        Path best = null;
        EngineVersion bestVersion = null;
        for (Path root : roots) {
            if (!accepts(root, requested)) {
                continue;
            }
            EngineVersion version = readVersion(root);
            if (version == null) {
                continue;
            }
            if (betterEngine(root, version, best, bestVersion)) {
                best = root;
                bestVersion = version;
            }
        }
        return best;
    }

    private boolean hasSupportedUnregistered(List<Path> roots) {
        for (Path root : roots) {
            if (accepts(root, null)) {
                return true;
            }
        }
        return false;
    }

    private List<Path> locatedUnregisteredRoots() {
        List<Path> roots = new ArrayList<>();
        addRoot(roots, resolveOverride(readOverride()));
        for (Path candidate : EngineInstallations.unregisteredCandidates(unregisteredRoots)) {
            addRoot(roots, asEngineRoot(candidate));
        }
        return roots;
    }

    private String readOverride() {
        try {
            return engineRootOverride.get();
        } catch (RuntimeException ex) {
            return null;
        }
    }

    private static void addRoot(List<Path> roots, Path root) {
        if (root != null && !roots.contains(root)) {
            roots.add(root);
        }
    }

    /** An absolute engine root from the override. A path that ends in {@code Engine} uses its parent. */
    private static Path resolveOverride(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String text = value.trim();
        if (text.equals("~")) {
            text = System.getProperty("user.home", "");
        } else if (text.startsWith("~/") || text.startsWith("~\\")) {
            text = System.getProperty("user.home", "") + text.substring(1);
        }
        Path path;
        try {
            path = Path.of(text);
        } catch (RuntimeException ex) {
            return null;
        }
        if (!path.isAbsolute()) {
            return null;
        }
        try {
            path = path.toAbsolutePath().normalize();
        } catch (RuntimeException ex) {
            return null;
        }
        if (isEngineRoot(path)) {
            return path;
        }
        if (path.getFileName() != null && "Engine".equals(path.getFileName().toString())
                && Files.isRegularFile(path.resolve("Build").resolve("Build.version"))) {
            Path parent = path.getParent();
            if (parent != null && isEngineRoot(parent)) {
                return parent.normalize();
            }
        }
        return null;
    }

    private static Path asEngineRoot(Path candidate) {
        if (candidate == null) {
            return null;
        }
        try {
            Path root = candidate.toAbsolutePath().normalize();
            return isEngineRoot(root) ? root : null;
        } catch (RuntimeException ex) {
            return null;
        }
    }

    /** {@code requested} null accepts any 5.8 or newer engine. Otherwise major and minor must match. */
    private static boolean accepts(Path root, Version requested) {
        if (root == null || !isEngineRoot(root)) {
            return false;
        }
        EngineVersion version = readVersion(root);
        if (version == null || version.olderThanMcp()) {
            return false;
        }
        if (requested == null) {
            return true;
        }
        return version.major == requested.major && version.minor == requested.minor;
    }

    private static boolean betterEngine(Path candidate, EngineVersion candidateVersion, Path current,
            EngineVersion currentVersion) {
        if (current == null || currentVersion == null) {
            return true;
        }
        if (candidateVersion.major != currentVersion.major) {
            return candidateVersion.major > currentVersion.major;
        }
        if (candidateVersion.minor != currentVersion.minor) {
            return candidateVersion.minor > currentVersion.minor;
        }
        if (candidateVersion.patch != currentVersion.patch) {
            return candidateVersion.patch > currentVersion.patch;
        }
        return hasPlugin(candidate) && !hasPlugin(current);
    }

    private static Path matchVersion(List<EngineInstallations.Install> installs, Version requested) {
        List<Path> matches = new ArrayList<>();
        for (EngineInstallations.Install install : installs) {
            EngineVersion version = readVersion(install.root());
            if (version != null && version.major == requested.major && version.minor == requested.minor) {
                matches.add(install.root());
            } else if (version == null && (keyMatchesVersion(install.key(), requested)
                    || folderMatches(install.root(), requested))) {
                matches.add(install.root());
            }
        }
        return prefer(matches);
    }

    private static Path matchKey(List<EngineInstallations.Install> installs, String association) {
        String wanted = EngineInstallations.canon(association);
        if (wanted.isEmpty()) {
            return null;
        }
        List<Path> matches = new ArrayList<>();
        for (EngineInstallations.Install install : installs) {
            if (wanted.equals(EngineInstallations.canon(install.key()))) {
                matches.add(install.root());
            }
        }
        return prefer(matches);
    }

    /** Prefer an install that contains the MCP plugin, then the highest patch. */
    private static Path prefer(List<Path> matches) {
        Path best = null;
        boolean bestPlugin = false;
        int bestPatch = Integer.MIN_VALUE;
        for (Path root : matches) {
            boolean plugin = hasPlugin(root);
            int patch = patchOf(root);
            if (best == null || (plugin && !bestPlugin) || (plugin == bestPlugin && patch > bestPatch)) {
                best = root;
                bestPlugin = plugin;
                bestPatch = patch;
            }
        }
        return best;
    }

    private static boolean keyMatchesVersion(String key, Version requested) {
        if (key == null || key.isBlank()) {
            return false;
        }
        Version parsed = parseVersion(key.trim());
        if (parsed != null) {
            return parsed.major == requested.major && parsed.minor == requested.minor;
        }
        String prefix = "UE_" + requested.major + "." + requested.minor;
        String upper = key.trim().toUpperCase(Locale.ROOT);
        return upper.equals(prefix) || upper.startsWith(prefix + ".");
    }

    private static boolean folderMatches(Path root, Version requested) {
        Path name = root.getFileName();
        return name != null && keyMatchesVersion(name.toString(), requested);
    }

    private static Path walkParents(Path project) {
        for (Path dir = project; dir != null; dir = dir.getParent()) {
            if (isEngineRoot(dir)) {
                return dir;
            }
        }
        return null;
    }

    private static Path resolveEnginePath(Path project, String value) {
        String text = value.trim();
        if (text.equals("~")) {
            text = System.getProperty("user.home", "");
        } else if (text.startsWith("~/")) {
            text = System.getProperty("user.home", "") + text.substring(1);
        }
        Path path = Path.of(text);
        if (!path.isAbsolute()) {
            path = project.resolve(path);
        }
        path = path.normalize();
        if (isEngineRoot(path)) {
            return path;
        }
        if (path.getFileName() != null && "Engine".equals(path.getFileName().toString())
                && Files.isRegularFile(path.resolve("Build").resolve("Build.version"))) {
            return path.getParent();
        }
        return null;
    }

    private static boolean isPath(String value) {
        if (value == null || value.isBlank()) {
            return false;
        }
        return value.startsWith(".") || value.startsWith("~") || value.indexOf('/') >= 0 || value.indexOf('\\') >= 0;
    }

    private static boolean isEngineRoot(Path root) {
        return root != null && Files.isRegularFile(root.resolve("Engine").resolve("Build").resolve("Build.version"));
    }

    private static boolean hasPlugin(Path root) {
        return Files.isDirectory(root.resolve(PLUGIN_RELATIVE));
    }

    private static Version parseVersion(String text) {
        if (text == null) {
            return null;
        }
        Matcher matcher = VERSION_TEXT.matcher(text.trim());
        if (!matcher.matches()) {
            return null;
        }
        return new Version(Integer.parseInt(matcher.group(1)), Integer.parseInt(matcher.group(2)));
    }

    private static EngineVersion readVersion(Path root) {
        Path file = root.resolve("Engine").resolve("Build").resolve("Build.version");
        try {
            JsonNode node = JsonTexts.MAPPER.readTree(JsonTexts.stripBom(Files.readString(file, StandardCharsets.UTF_8)));
            if (!node.path("MajorVersion").isNumber() || !node.path("MinorVersion").isNumber()) {
                return null;
            }
            int patch = node.path("PatchVersion").isNumber() ? node.path("PatchVersion").asInt() : 0;
            return new EngineVersion(node.path("MajorVersion").asInt(), node.path("MinorVersion").asInt(), patch);
        } catch (IOException ex) {
            return null;
        }
    }

    private static int patchOf(Path root) {
        EngineVersion version = readVersion(root);
        return version == null ? 0 : version.patch;
    }

    private enum EngineState {
        OK, TOO_OLD, NOT_INSTALLED, UNRESOLVED, PLUGIN_MISSING
    }

    private record EngineFinding(EngineState state, Path root, String versionLabel, boolean discovered) {
        private EngineFinding(EngineState state, Path root, String versionLabel) {
            this(state, root, versionLabel, false);
        }
    }

    private record Version(int major, int minor) {
        boolean olderThanMcp() {
            return major < 5 || (major == 5 && minor < 8);
        }

        String label() {
            return major + "." + minor;
        }
    }

    private record EngineVersion(int major, int minor, int patch) {
        boolean olderThanMcp() {
            return major < 5 || (major == 5 && minor < 8);
        }
    }
}
