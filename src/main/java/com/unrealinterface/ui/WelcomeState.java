package com.unrealinterface.ui;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.Locale;
import java.util.Properties;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.unrealinterface.unreal.UnrealMcpSetup;

/**
 * Remembers the welcome pane and the last project whose Unreal MCP setup finished.
 * The file is {@code state.properties} in the user config directory
 * ({@code ~/.config/unreal-interface} on Linux, {@code ~/Library/Application Support/unreal-interface}
 * on macOS, {@code %APPDATA%/unreal-interface} on Windows).
 * {@code unrealinterface.config} overrides that directory.
 *
 * <p>The pane is shown on first launch and when no usable recent project is stored.
 * Setup that finishes for one {@code .uproject} stores the project and
 * {@code welcomeSeen=true}, so later launches skip the pane and reopen that project.
 */
public final class WelcomeState {

    static final String FILE_NAME = "state.properties";

    public static final String SUMMARY = "UnrealInterface is an ACP client that installs Unreal skills "
            + "into the project you open and auto-configures Unreal MCP.";

    public static final String NEW_PROJECT = "To create a project, install Unreal Engine 5.8 or newer, "
            + "choose File > New Project, then open the folder that contains the .uproject.";

    public static final List<String> EXAMPLE_PROMPTS = List.of(
            "Create a simple third-person character that can run and jump in a basic level",
            "Set up a basic lit level with a player start and a few static meshes");

    private static final Logger log = LoggerFactory.getLogger(WelcomeState.class);
    private static final String KEY_SEEN = "welcomeSeen";
    private static final String KEY_PROJECT = "recentProject";

    private final Path file;
    private boolean welcomeSeen;
    private String recentProject;

    public WelcomeState() {
        this(configDirectory().resolve(FILE_NAME));
    }

    public WelcomeState(Path file) {
        this.file = file;
        load();
    }

    public static Path configDirectory() {
        String override = System.getProperty("unrealinterface.config");
        if (override != null && !override.isBlank()) {
            return Path.of(override).toAbsolutePath().normalize();
        }
        Path home = Path.of(System.getProperty("user.home", "."));
        return configDirectory(home, System.getProperty("os.name", ""),
                System.getenv("XDG_CONFIG_HOME"), System.getenv("APPDATA"));
    }

    static Path configDirectory(Path home, String osName, String xdgConfigHome, String appData) {
        String os = osName == null ? "" : osName.toLowerCase(Locale.ROOT);
        // "darwin" contains "win", so the Mac check has to come first.
        if (os.contains("mac") || os.contains("darwin")) {
            return home.resolve("Library").resolve("Application Support").resolve("unreal-interface");
        }
        if (os.contains("win")) {
            Path base = appData == null || appData.isBlank()
                    ? home.resolve("AppData").resolve("Roaming")
                    : Path.of(appData);
            return base.resolve("unreal-interface");
        }
        Path base = xdgConfigHome == null || xdgConfigHome.isBlank()
                ? home.resolve(".config")
                : Path.of(xdgConfigHome);
        return base.resolve("unreal-interface");
    }

    /**
     * Setup finished for one project folder. Engine and plugin gaps still count:
     * the project files were handled, and the status line says what remains.
     * No project, several projects, and failures do not.
     */
    public static boolean hidesWelcome(UnrealMcpSetup.Status status) {
        return status == UnrealMcpSetup.Status.CONFIGURED
                || status == UnrealMcpSetup.Status.READY
                || status == UnrealMcpSetup.Status.ENGINE_MISSING
                || status == UnrealMcpSetup.Status.PLUGIN_MISSING;
    }

    public boolean shouldShow() {
        return !welcomeSeen || recentProject() == null;
    }

    /** Absolute directory stored last, or null when it is missing or not a directory. */
    public Path recentProject() {
        if (recentProject == null || recentProject.isBlank()) {
            return null;
        }
        try {
            Path path = Path.of(recentProject).toAbsolutePath().normalize();
            if (!Files.isDirectory(path)) {
                return null;
            }
            return path;
        } catch (InvalidPathException ex) {
            return null;
        }
    }

    /** Records a finished setup. A second call with the same directory does not rewrite the file. */
    public void complete(Path project) throws IOException {
        if (project == null) {
            throw new IOException("Project directory is missing");
        }
        String path = project.toAbsolutePath().normalize().toString();
        if (welcomeSeen && path.equals(recentProject)) {
            return;
        }
        welcomeSeen = true;
        recentProject = path;
        write();
    }

    private void load() {
        if (file == null || !Files.isRegularFile(file)) {
            return;
        }
        Properties properties = new Properties();
        try (InputStream in = Files.newInputStream(file)) {
            properties.load(in);
        } catch (IOException | IllegalArgumentException ex) {
            log.warn("Could not read {}: {}", file, ex.toString());
            welcomeSeen = false;
            recentProject = null;
            return;
        }
        welcomeSeen = Boolean.parseBoolean(properties.getProperty(KEY_SEEN, "false"));
        String project = properties.getProperty(KEY_PROJECT);
        recentProject = project == null || project.isBlank() ? null : project;
    }

    private void write() throws IOException {
        if (Files.exists(file) && !Files.isRegularFile(file)) {
            throw new IOException(file.getFileName() + " is not a file");
        }
        Properties properties = new Properties();
        properties.setProperty(KEY_SEEN, Boolean.toString(welcomeSeen));
        if (recentProject != null) {
            properties.setProperty(KEY_PROJECT, recentProject);
        }
        Path parent = file.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        } else {
            parent = Path.of(".");
        }
        Path tmp = Files.createTempFile(parent, ".unreal-interface-", ".tmp");
        try {
            try (OutputStream out = Files.newOutputStream(tmp)) {
                properties.store(out, "UnrealInterface");
            }
            try {
                Files.move(tmp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException ex) {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(tmp);
        }
    }
}
