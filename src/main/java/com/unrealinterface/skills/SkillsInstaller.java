package com.unrealinterface.skills;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import com.unrealinterface.acp.ProjectFiles;

/**
 * Copies the bundled Agent Skills into the directories the harnesses search,
 * and copies the project instruction files those harnesses load every session.
 * A file that is already there is left alone, so local edits survive. A
 * destination whose real path leaves the project (a symlinked {@code .claude}
 * or {@code .agents}, for example) is refused before anything is copied.
 *
 * Skills land in {@code .agents/skills} (Codex, Cursor, Gemini CLI) and
 * {@code .claude/skills} (Claude Code). {@code AGENTS.md} is the instruction
 * file. {@code CLAUDE.md} imports it, so Claude Code, which prefers
 * {@code CLAUDE.md} when that file exists, still receives the same text.
 */
public final class SkillsInstaller {

    static final List<String> SKILL_FILES = List.of(
            "unreal-interface/SKILL.md",
            "unreal-interface/reference.md",
            "unreal-router/SKILL.md",
            "unreal-mcp-setup/SKILL.md",
            "unreal-blueprints/SKILL.md",
            "unreal-cpp-gameplay/SKILL.md",
            "unreal-scene-building/SKILL.md",
            "unreal-enhanced-input/SKILL.md",
            "unreal-iteration/SKILL.md");

    /** Bundle paths. Copied to the project root under the file name only. */
    static final List<String> PROJECT_ROOT_FILES = List.of(
            "project-root/AGENTS.md",
            "project-root/CLAUDE.md");

    private static final List<Path> SKILL_DESTINATIONS = List.of(
            Path.of(".agents", "skills"),
            Path.of(".claude", "skills"));

    public record InstallResult(int created, int leftInPlace) {
        public String message() {
            if (created == 0) {
                return "Skills bundle already present in .agents/skills, .claude/skills, and the project root";
            }
            String files = created == 1 ? "1 new file" : created + " new files";
            if (leftInPlace > 0) {
                return "Installed skills bundle (" + files + "); left " + leftInPlace
                        + " existing file" + (leftInPlace == 1 ? "" : "s") + " in place";
            }
            return "Installed skills bundle (" + files
                    + ") into .agents/skills, .claude/skills, and the project root";
        }
    }

    private record PlannedCopy(String relative, Path target) {
    }

    public InstallResult install(Path projectDirectory) throws IOException {
        if (projectDirectory == null || !Files.isDirectory(projectDirectory)) {
            throw new IOException("Project directory does not exist: " + projectDirectory);
        }
        List<PlannedCopy> planned = new ArrayList<>();
        for (Path destination : SKILL_DESTINATIONS) {
            for (String relative : SKILL_FILES) {
                plan(planned, projectDirectory, relative, projectDirectory.resolve(destination).resolve(relative));
            }
        }
        for (String relative : PROJECT_ROOT_FILES) {
            plan(planned, projectDirectory, relative, projectDirectory.resolve(Path.of(relative).getFileName()));
        }
        int created = 0;
        int leftInPlace = 0;
        for (PlannedCopy copy : planned) {
            if (Files.exists(copy.target(), LinkOption.NOFOLLOW_LINKS)) {
                leftInPlace++;
                continue;
            }
            try {
                copyResource(copy.relative(), copy.target());
                created++;
            } catch (FileAlreadyExistsException e) {
                leftInPlace++;
            }
        }
        return new InstallResult(created, leftInPlace);
    }

    private static void plan(List<PlannedCopy> planned, Path projectDirectory, String relative, Path lexical)
            throws IOException {
        Path normalized = lexical.normalize();
        try {
            planned.add(new PlannedCopy(relative, ProjectFiles.resolveInside(projectDirectory, normalized.toString())));
        } catch (IllegalArgumentException e) {
            throw new IOException("Refusing to copy skills outside the project: " + normalized, e);
        }
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
            Files.copy(input, target);
        }
    }
}
