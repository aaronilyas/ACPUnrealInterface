package com.unrealinterface.skills;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;

/**
 * Copies the bundled skill into the project directories agents already search.
 * A file that is already there is left alone, so local edits survive.
 */
public final class SkillsInstaller {

    static final List<String> FILES = List.of(
            "unreal-interface/SKILL.md",
            "unreal-interface/reference.md");

    private static final List<Path> DESTINATIONS = List.of(
            Path.of(".agents", "skills"),
            Path.of(".claude", "skills"));

    public record InstallResult(int created, int leftInPlace) {
        public String message() {
            if (created == 0) {
                return "Skills bundle already present in .agents/skills and .claude/skills";
            }
            String files = created == 1 ? "1 new file" : created + " new files";
            if (leftInPlace > 0) {
                return "Installed skills bundle (" + files + "); left " + leftInPlace
                        + " existing file" + (leftInPlace == 1 ? "" : "s") + " in place";
            }
            return "Installed skills bundle (" + files + ") into .agents/skills and .claude/skills";
        }
    }

    public InstallResult install(Path projectDirectory) throws IOException {
        if (projectDirectory == null || !Files.isDirectory(projectDirectory)) {
            throw new IOException("Project directory does not exist: " + projectDirectory);
        }
        int created = 0;
        int leftInPlace = 0;
        for (Path destination : DESTINATIONS) {
            for (String relative : FILES) {
                Path target = projectDirectory.resolve(destination).resolve(relative);
                if (Files.exists(target)) {
                    leftInPlace++;
                    continue;
                }
                copyResource(relative, target);
                created++;
            }
        }
        return new InstallResult(created, leftInPlace);
    }

    private static void copyResource(String relative, Path target) throws IOException {
        String resource = "skills-bundle/" + relative;
        try (InputStream input = SkillsInstaller.class.getClassLoader().getResourceAsStream(resource)) {
            if (input == null) {
                throw new IOException("Missing bundled skill resource: " + resource);
            }
            Path parent = target.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Files.copy(input, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }
}
