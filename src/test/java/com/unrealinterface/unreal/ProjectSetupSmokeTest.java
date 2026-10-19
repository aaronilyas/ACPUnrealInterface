package com.unrealinterface.unreal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.fasterxml.jackson.databind.JsonNode;
import com.unrealinterface.skills.SkillsInstaller;
import com.unrealinterface.unreal.UnrealMcpSetup.Result;
import com.unrealinterface.unreal.UnrealMcpSetup.Status;

class ProjectSetupSmokeTest {

    @TempDir
    Path temp;

    @Test
    void installsSkillsAndWritesMcpFilesForAFixtureProject() throws Exception {
        Path project = temp.resolve("Sample");
        Files.createDirectories(project);
        Files.writeString(project.resolve("Sample.uproject"), """
                {
                  "FileVersion": 3,
                  "EngineAssociation": "5.8",
                  "Plugins": [ { "Name": "Water", "Enabled": true } ]
                }
                """);

        SkillsInstaller.InstallResult installed = new SkillsInstaller().install(project);
        assertTrue(installed.created() > 0);
        assertTrue(Files.isRegularFile(project.resolve(".agents/skills/unreal-router/SKILL.md")));
        assertTrue(Files.isRegularFile(project.resolve(".claude/skills/unreal-mcp-setup/SKILL.md")));
        assertTrue(Files.isRegularFile(project.resolve("AGENTS.md")));

        Result result = new UnrealMcpSetup(() -> List.of(), HostOs.LINUX).setup(project);
        assertEquals(Status.ENGINE_MISSING, result.status());
        assertTrue(result.changed());

        JsonNode uproject = JsonTexts.MAPPER.readTree(project.resolve("Sample.uproject").toFile());
        assertEquals("Water", uproject.at("/Plugins/0/Name").asText());
        assertTrue(uproject.at("/Plugins/0/Enabled").asBoolean());
        assertEquals("ModelContextProtocol", uproject.at("/Plugins/1/Name").asText());
        assertTrue(uproject.at("/Plugins/1/Enabled").asBoolean());
        assertEquals("AllToolsets", uproject.at("/Plugins/2/Name").asText());
        assertTrue(uproject.at("/Plugins/2/Enabled").asBoolean());

        String ini = Files.readString(project.resolve("Saved/Config/LinuxEditor/EditorPerProjectUserSettings.ini"));
        assertTrue(ini.contains("[/Script/ModelContextProtocolEngine.ModelContextProtocolSettings]\n"));
        assertTrue(ini.contains("bAutoStartServer=True\n"));

        JsonNode mcp = JsonTexts.MAPPER.readTree(project.resolve(".mcp.json").toFile());
        assertEquals("http", mcp.at("/mcpServers/unreal-mcp/type").asText());
        assertEquals("http://127.0.0.1:8000/mcp", mcp.at("/mcpServers/unreal-mcp/url").asText());
    }
}
