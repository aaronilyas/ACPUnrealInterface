package com.unrealinterface.unreal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.fasterxml.jackson.databind.JsonNode;
import com.unrealinterface.unreal.UnrealMcpSetup.Result;
import com.unrealinterface.unreal.UnrealMcpSetup.Status;

class UnrealMcpSetupTest {

    private static final String URL = "http://127.0.0.1:8000/mcp";

    @TempDir
    Path temp;

    @Test
    void skillDocumentsTheFilesTheAppWrites() throws Exception {
        String skill = Files.readString(Path.of("src/main/resources/skills-bundle/unreal-mcp-setup/SKILL.md"));
        assertTrue(skill.contains("[/Script/ModelContextProtocolEngine.ModelContextProtocolSettings]"));
        assertTrue(skill.contains("bAutoStartServer=True"));
        assertTrue(skill.contains("\"Name\": \"ModelContextProtocol\""));
        assertTrue(skill.contains("\"Name\": \"AllToolsets\""));
        assertTrue(skill.contains(URL));
        assertTrue(skill.contains("EditorPerProjectUserSettings.ini"));
        assertTrue(skill.contains("LinuxEditor"));
        assertTrue(skill.contains("WindowsEditor"));
        assertTrue(skill.contains("MacEditor"));
        assertTrue(skill.contains(".mcp.json"));
        assertTrue(skill.contains("[mcp_servers.unreal-mcp]"));
        assertTrue(skill.contains("list_toolsets"));
        assertTrue(skill.contains("File > New Project"));
        assertTrue(skill.contains("Switch Unreal Engine version"));
        assertTrue(skill.contains("~/.config/Epic/UnrealEngine/Install.ini"));
        assertTrue(skill.contains("LauncherInstalled.dat"));
    }

    @Test
    void noUprojectTellsTheUserHowToCreateOne() throws Exception {
        Path project = temp.resolve("empty");
        Files.createDirectories(project);
        Result result = setup(List.of()).setup(project);
        assertEquals(Status.NO_PROJECT, result.status());
        assertFalse(result.changed());
        assertTrue(result.message().contains("no .uproject"));
        assertTrue(result.message().contains("5.8"));
        assertTrue(result.message().contains("New Project"));
        assertTrue(result.message().contains("Epic Games Launcher"));
        assertFalse(Files.exists(project.resolve(".mcp.json")));
    }

    @Test
    void noUprojectWithASupportedEngineSkipsTheInstallStep() throws Exception {
        Path engine = engine(temp.resolve("ue"), 5, 8, 0, true);
        Path project = temp.resolve("empty");
        Files.createDirectories(project);
        Result result = setup(List.of(install(engine, "5.8"))).setup(project);
        assertEquals(Status.NO_PROJECT, result.status());
        assertTrue(result.message().contains("New Project"));
        assertFalse(result.message().contains("not installed"));
        assertFalse(result.message().contains("Epic Games Launcher"));
    }

    @Test
    void ambiguousProjectsAreLeftAlone() throws Exception {
        Path project = temp.resolve("games");
        Files.createDirectories(project);
        Files.writeString(project.resolve("A.uproject"), "{}\n");
        Files.writeString(project.resolve("B.uproject"), "{}\n");
        Result result = setup(List.of()).setup(project);
        assertEquals(Status.AMBIGUOUS_PROJECT, result.status());
        assertTrue(result.message().contains("A.uproject"));
        assertTrue(result.message().contains("B.uproject"));
        assertFalse(Files.exists(project.resolve(".mcp.json")));
        assertEquals("{}\n", Files.readString(project.resolve("A.uproject")));
    }

    @Test
    void enablesPluginsAutoStartAndClientConfig() throws Exception {
        Path engine = engine(temp.resolve("ue"), 5, 8, 1, true);
        Path project = temp.resolve("game");
        Files.createDirectories(project);
        Files.writeString(project.resolve("Game.uproject"), """
                {
                  "FileVersion": 3,
                  "EngineAssociation": "5.8",
                  "Modules": [{ "Name": "Game", "Type": "Runtime", "LoadingPhase": "Default" }]
                }
                """);
        UnrealMcpSetup mcp = setup(List.of(install(engine, "5.8")));
        Result first = mcp.setup(project);
        assertEquals(Status.CONFIGURED, first.status());
        assertTrue(first.changed());
        assertTrue(first.message().contains("has not been opened"));
        assertTrue(first.message().contains("Launch Game.uproject"));
        assertTrue(first.message().contains("Saved/Config/LinuxEditor/EditorPerProjectUserSettings.ini"));
        assertTrue(first.message().contains(URL));

        JsonNode uproject = JsonTexts.MAPPER.readTree(project.resolve("Game.uproject").toFile());
        assertEquals(3, uproject.get("FileVersion").asInt());
        assertEquals("Game", uproject.at("/Modules/0/Name").asText());
        assertEquals("ModelContextProtocol", uproject.at("/Plugins/0/Name").asText());
        assertTrue(uproject.at("/Plugins/0/Enabled").asBoolean());
        assertEquals("AllToolsets", uproject.at("/Plugins/1/Name").asText());
        assertTrue(uproject.at("/Plugins/1/Enabled").asBoolean());

        String ini = Files.readString(iniPath(project, HostOs.LINUX));
        assertTrue(ini.contains("[/Script/ModelContextProtocolEngine.ModelContextProtocolSettings]\n"));
        assertTrue(ini.contains("bAutoStartServer=True\n"));
        JsonNode mcpJson = JsonTexts.MAPPER.readTree(project.resolve(".mcp.json").toFile());
        assertEquals("http", mcpJson.at("/mcpServers/unreal-mcp/type").asText());
        assertEquals(URL, mcpJson.at("/mcpServers/unreal-mcp/url").asText());
        assertEquals("[mcp_servers.unreal-mcp]\nurl = \"" + URL + "\"\n",
                Files.readString(project.resolve(".codex/config.toml")));

        String uprojectBytes = Files.readString(project.resolve("Game.uproject"));
        String iniBytes = Files.readString(iniPath(project, HostOs.LINUX));
        String mcpBytes = Files.readString(project.resolve(".mcp.json"));
        String tomlBytes = Files.readString(project.resolve(".codex/config.toml"));
        Result second = mcp.setup(project);
        assertEquals(Status.READY, second.status());
        assertFalse(second.changed());
        assertTrue(second.message().contains("already set up"));
        assertTrue(second.message().contains("list_toolsets"));
        assertEquals(uprojectBytes, Files.readString(project.resolve("Game.uproject")));
        assertEquals(iniBytes, Files.readString(iniPath(project, HostOs.LINUX)));
        assertEquals(mcpBytes, Files.readString(project.resolve(".mcp.json")));
        assertEquals(tomlBytes, Files.readString(project.resolve(".codex/config.toml")));
    }

    @Test
    void flipsDisabledPluginsAndDoesNotDuplicateThem() throws Exception {
        Path project = temp.resolve("game");
        Files.createDirectories(project);
        Files.writeString(project.resolve("Game.uproject"), """
                {
                  "FileVersion": 3,
                  "EngineAssociation": "5.8",
                  "Plugins": [
                    { "Name": "ModelContextProtocol", "Enabled": false, "FriendlyName": "Keep" },
                    { "Name": "Water", "Enabled": true },
                    { "Name": "AllToolsets", "Enabled": true },
                    { "Name": "ModelContextProtocol", "Enabled": false }
                  ]
                }
                """);
        Result result = setup(List.of()).setup(project);
        assertEquals(Status.ENGINE_MISSING, result.status());
        JsonNode plugins = JsonTexts.MAPPER.readTree(project.resolve("Game.uproject").toFile()).get("Plugins");
        assertEquals(4, plugins.size());
        assertEquals("Keep", plugins.get(0).get("FriendlyName").asText());
        assertTrue(plugins.get(0).get("Enabled").asBoolean());
        assertEquals("Water", plugins.get(1).get("Name").asText());
        assertTrue(plugins.get(2).get("Enabled").asBoolean());
        assertFalse(plugins.get(3).get("Enabled").asBoolean());
    }

    @Test
    void leavesACorrectUprojectUntouched() throws Exception {
        Path project = temp.resolve("game");
        Files.createDirectories(project);
        String original = """
                {"FileVersion":3,"EngineAssociation":"5.8","Plugins":[{"Name":"ModelContextProtocol","Enabled":true,"MarketplaceURL":"keep"},{"Name":"AllToolsets","Enabled":true}]}
                """;
        Files.writeString(project.resolve("Game.uproject"), original);
        Result result = setup(List.of()).setup(project);
        assertTrue(result.changed());
        assertEquals(original, Files.readString(project.resolve("Game.uproject")));
        assertTrue(Files.isRegularFile(project.resolve(".mcp.json")));
    }

    @Test
    void doesNotOverwriteInvalidUprojectJson() throws Exception {
        Path project = temp.resolve("game");
        Files.createDirectories(project);
        Files.writeString(project.resolve("Game.uproject"), "{");
        Result result = setup(List.of()).setup(project);
        assertEquals(Status.FAILED, result.status());
        assertFalse(result.changed());
        assertTrue(result.message().contains("not valid JSON"));
        assertTrue(result.message().contains("left unchanged"));
        assertEquals("{", Files.readString(project.resolve("Game.uproject")));
        assertFalse(Files.exists(project.resolve(".mcp.json")));
        assertFalse(Files.exists(project.resolve("Saved")));
    }

    @Test
    void doesNotOverwriteAPluginsValueThatIsNotAnArray() throws Exception {
        Path project = temp.resolve("game");
        Files.createDirectories(project);
        String original = "{\"FileVersion\":3,\"EngineAssociation\":\"5.8\",\"Plugins\":{}}\n";
        Files.writeString(project.resolve("Game.uproject"), original);
        Result result = setup(List.of()).setup(project);
        assertEquals(Status.FAILED, result.status());
        assertEquals(original, Files.readString(project.resolve("Game.uproject")));
        assertFalse(Files.exists(iniPath(project, HostOs.LINUX)));
    }

    @Test
    void keepsOtherMcpServersAndUsesAnExistingPort() throws Exception {
        Path project = temp.resolve("game");
        Files.createDirectories(project);
        writeUproject(project, "5.8");
        Path ini = iniPath(project, HostOs.LINUX);
        Files.createDirectories(ini.getParent());
        Files.writeString(ini, """
                ;METADATA=(Diff=true, UseCommands=true)
                [/Script/Some.Other]
                Foo=1

                [/Script/ModelContextProtocolEngine.ModelContextProtocolSettings]
                ServerPortNumber=8123
                ServerUrlPath=bridge

                [/Script/Tail]
                Bar=2
                """);
        Files.writeString(project.resolve(".mcp.json"), """
                {
                  "mcpServers": {
                    "other": { "type": "http", "url": "http://127.0.0.1:9/x" }
                  }
                }
                """);
        Result result = setup(List.of()).setup(project);
        assertEquals(Status.ENGINE_MISSING, result.status());
        String updated = Files.readString(ini);
        assertTrue(updated.contains(";METADATA=(Diff=true, UseCommands=true)"));
        assertTrue(updated.contains("[/Script/Some.Other]\nFoo=1"));
        assertTrue(updated.contains("bAutoStartServer=True"));
        assertTrue(updated.contains("ServerPortNumber=8123"));
        assertTrue(updated.contains("[/Script/Tail]\nBar=2"));
        JsonNode mcp = JsonTexts.MAPPER.readTree(project.resolve(".mcp.json").toFile());
        assertEquals("http://127.0.0.1:9/x", mcp.at("/mcpServers/other/url").asText());
        assertEquals("http://127.0.0.1:8123/bridge", mcp.at("/mcpServers/unreal-mcp/url").asText());
        assertTrue(Files.readString(project.resolve(".codex/config.toml")).contains("http://127.0.0.1:8123/bridge"));
        assertTrue(result.message().contains("http://127.0.0.1:8123/bridge"));
    }

    @Test
    void preservesCrlfAndAnAlreadyTrueAutoStartLine() throws Exception {
        String original = "[/Script/ModelContextProtocolEngine.ModelContextProtocolSettings]\r\nbAutoStartServer=true\r\n";
        EditorUserSettings.Result result = EditorUserSettings.apply(original);
        assertFalse(result.changed());
        assertEquals(original, result.content());

        EditorUserSettings.Result flipped = EditorUserSettings.apply(
                "[Other]\r\nA=1\r\n\r\n[/Script/ModelContextProtocolEngine.ModelContextProtocolSettings]\r\nbAutoStartServer=False\r\n");
        assertTrue(flipped.changed());
        assertTrue(flipped.content().contains("\r\n"));
        assertTrue(flipped.content().contains("[Other]\r\nA=1\r\n"));
        assertTrue(flipped.content().contains("bAutoStartServer=True\r\n"));
        assertFalse(flipped.content().contains("False"));
    }

    @Test
    void addsTheAutoStartKeyUnderAnExistingSection() {
        EditorUserSettings.Result result = EditorUserSettings.apply("""
                [/Script/ModelContextProtocolEngine.ModelContextProtocolSettings]
                ;bAutoStartServer=False
                ServerPortNumber=8000

                [Next]
                X=1
                """);
        assertTrue(result.changed());
        assertTrue(result.content().contains(";bAutoStartServer=False"));
        assertTrue(result.content().contains("bAutoStartServer=True"));
        assertEquals(8000, result.port());
        int section = result.content().indexOf("[/Script/ModelContextProtocolEngine.ModelContextProtocolSettings]");
        int key = result.content().indexOf("bAutoStartServer=True");
        int next = result.content().indexOf("[Next]");
        assertTrue(section < key && key < next);
    }

    @Test
    void doesNotOverwriteInvalidMcpJson() throws Exception {
        Path project = temp.resolve("game");
        Files.createDirectories(project);
        writeUproject(project, "5.8");
        Files.writeString(project.resolve(".mcp.json"), "{");
        Result result = setup(List.of()).setup(project);
        assertEquals(Status.FAILED, result.status());
        assertTrue(result.changed());
        assertEquals("{", Files.readString(project.resolve(".mcp.json")));
        assertTrue(result.message().contains(".mcp.json"));
        assertTrue(result.message().contains("not valid JSON"));
        assertTrue(result.message().contains("left unchanged"));
        JsonNode uproject = JsonTexts.MAPPER.readTree(project.resolve("Game.uproject").toFile());
        assertEquals("ModelContextProtocol", uproject.at("/Plugins/0/Name").asText());
        assertTrue(Files.isRegularFile(iniPath(project, HostOs.LINUX)));
    }

    @Test
    void leavesACodexCommandEntryAndOtherTablesAlone() throws Exception {
        Path engine = engine(temp.resolve("ue"), 5, 8, 0, true);
        Path project = temp.resolve("game");
        Files.createDirectories(project);
        writeUproject(project, "5.8");
        String toml = """
                model = "gpt-5"

                [mcp_servers.unreal-mcp]
                command = "proxy"
                url = "http://127.0.0.1:9/mcp"

                [other]
                x = 1
                """;
        Files.createDirectories(project.resolve(".codex"));
        Files.writeString(project.resolve(".codex/config.toml"), toml);
        Result result = setup(List.of(install(engine, "5.8"))).setup(project);
        assertEquals(Status.CONFIGURED, result.status());
        assertEquals(toml, Files.readString(project.resolve(".codex/config.toml")));
        assertTrue(result.message().contains("left unchanged"));
        assertEquals(URL, JsonTexts.MAPPER.readTree(project.resolve(".mcp.json").toFile())
                .at("/mcpServers/unreal-mcp/url").asText());
    }

    @Test
    void updatesOnlyTheCodexUrlLine() throws Exception {
        String original = """
                # keep
                [mcp_servers.unreal-mcp]
                url = "http://127.0.0.1:9/mcp"

                [other]
                x = 1
                """;
        McpClientConfig.Edit edit = McpClientConfig.codexToml(original, URL);
        assertTrue(edit.shouldWrite());
        assertTrue(edit.content().contains("# keep\n"));
        assertTrue(edit.content().contains("[other]\nx = 1\n"));
        assertTrue(edit.content().contains("url = \"" + URL + "\"\n"));
        assertFalse(edit.content().contains("127.0.0.1:9"));
        assertFalse(McpClientConfig.codexToml(edit.content(), URL).shouldWrite());
    }

    @Test
    void refusesAnEngineOlderThan58() throws Exception {
        Path project = temp.resolve("game");
        Files.createDirectories(project);
        String original = uprojectJson("5.4");
        Files.writeString(project.resolve("Game.uproject"), original);
        Result result = setup(List.of()).setup(project);
        assertEquals(Status.ENGINE_TOO_OLD, result.status());
        assertFalse(result.changed());
        assertTrue(result.message().contains("5.4"));
        assertTrue(result.message().contains("5.8"));
        assertTrue(result.message().contains("Switch Unreal Engine version"));
        assertTrue(result.message().contains("No files were changed"));
        assertEquals(original, Files.readString(project.resolve("Game.uproject")));
        assertFalse(Files.exists(project.resolve("Saved")));
        assertFalse(Files.exists(project.resolve(".mcp.json")));
    }

    @Test
    void treats510AsNewerThan58AndDoesNotUseA58InstallForIt() throws Exception {
        Path engine = engine(temp.resolve("ue58"), 5, 8, 0, true);
        Path project = temp.resolve("game");
        Files.createDirectories(project);
        writeUproject(project, "5.10");
        Result result = setup(List.of(install(engine, "5.8"))).setup(project);
        assertEquals(Status.ENGINE_MISSING, result.status());
        assertTrue(result.changed());
        assertTrue(result.message().contains("5.10"));
        assertFalse(result.message().contains("not installed"));
        assertTrue(result.message().contains("Switch Unreal Engine version"));
        assertTrue(Files.isRegularFile(project.resolve(".mcp.json")));
    }

    @Test
    void missingEngineStillWritesSettingsAndSaysHowToInstall() throws Exception {
        Path project = temp.resolve("game");
        Files.createDirectories(project);
        writeUproject(project, "5.8");
        Result result = setup(List.of()).setup(project);
        assertEquals(Status.ENGINE_MISSING, result.status());
        assertTrue(result.changed());
        assertTrue(result.message().contains("not installed"));
        assertTrue(result.message().contains("Epic Games Launcher"));
        assertTrue(result.message().contains("open Game.uproject"));
        assertTrue(result.message().contains(URL));
        assertTrue(Files.readString(iniPath(project, HostOs.LINUX)).contains("bAutoStartServer=True"));
    }

    @Test
    void guidAssociationUsesTheRegisteredEngine() throws Exception {
        String guid = "{AAAAAAAA-BBBB-CCCC-DDDD-EEEEEEEEEEEE}";
        Path engine = engine(temp.resolve("dist"), 5, 8, 2, true);
        Path project = temp.resolve("game");
        Files.createDirectories(project);
        writeUproject(project, guid);
        Result result = setup(List.of(install(engine, guid))).setup(project);
        assertEquals(Status.CONFIGURED, result.status());
        assertFalse(result.message().contains("not installed"));
        assertTrue(result.message().contains("set up"));
    }

    @Test
    void unknownGuidWithAnotherEngineInstalledAsksForAVersionSwitch() throws Exception {
        Path engine = engine(temp.resolve("ue"), 5, 8, 0, true);
        Path project = temp.resolve("game");
        Files.createDirectories(project);
        writeUproject(project, "{BBBBBBBB-BBBB-CCCC-DDDD-EEEEEEEEEEEE}");
        Result result = setup(List.of(install(engine, "{AAAAAAAA-AAAA-AAAA-AAAA-AAAAAAAAAAAA}"))).setup(project);
        assertEquals(Status.ENGINE_MISSING, result.status());
        assertTrue(result.message().contains("Switch Unreal Engine version"));
        assertFalse(result.message().contains("not installed"));
        assertTrue(result.changed());
    }

    @Test
    void oldRegisteredEngineIsNotModified() throws Exception {
        String guid = "{CCCCCCCC-CCCC-CCCC-CCCC-CCCCCCCCCCCC}";
        Path engine = engine(temp.resolve("old"), 5, 4, 0, false);
        Path project = temp.resolve("game");
        Files.createDirectories(project);
        String original = uprojectJson(guid);
        Files.writeString(project.resolve("Game.uproject"), original);
        Result result = setup(List.of(install(engine, guid))).setup(project);
        assertEquals(Status.ENGINE_TOO_OLD, result.status());
        assertEquals(original, Files.readString(project.resolve("Game.uproject")));
        assertFalse(Files.exists(project.resolve(".mcp.json")));
    }

    @Test
    void engineWithoutThePluginReportsTheExpectedFolder() throws Exception {
        Path engine = engine(temp.resolve("ue"), 5, 8, 0, false);
        Path project = temp.resolve("game");
        Files.createDirectories(project);
        writeUproject(project, "5.8");
        Result result = setup(List.of(install(engine, "5.8"))).setup(project);
        assertEquals(Status.PLUGIN_MISSING, result.status());
        assertTrue(result.message().contains("Engine/Plugins/Experimental/ModelContextProtocol"));
        assertTrue(result.message().contains(engine.toString()));
        assertTrue(Files.isRegularFile(project.resolve(".mcp.json")));
    }

    @Test
    void blankAssociationFindsAParentSourceBuild() throws Exception {
        Path root = temp.resolve("source");
        engine(root, 5, 8, 0, true);
        Path project = root.resolve("Samples").resolve("Game");
        Files.createDirectories(project);
        writeUproject(project, "");
        Result result = setup(List.of()).setup(project);
        assertEquals(Status.CONFIGURED, result.status());
        assertFalse(result.message().contains("not installed"));
    }

    @Test
    void relativeEngineAssociationResolvesBesideTheProject() throws Exception {
        Path engine = engine(temp.resolve("EngineDist"), 5, 8, 3, true);
        Path project = temp.resolve("game");
        Files.createDirectories(project);
        writeUproject(project, "../EngineDist");
        Result result = setup(List.of()).setup(project);
        assertEquals(Status.CONFIGURED, result.status());
        assertTrue(result.message().contains("set up"));
        assertTrue(engine.startsWith(temp));
    }

    @Test
    void patchVersionDoesNotMake582OlderThan58() throws Exception {
        Path engine = engine(temp.resolve("ue"), 5, 8, 1, true);
        Path project = temp.resolve("game");
        Files.createDirectories(project);
        writeUproject(project, "5.8.2");
        Result result = setup(List.of(install(engine, "UE_5.8"))).setup(project);
        assertEquals(Status.CONFIGURED, result.status());
    }

    @Test
    void restartedProjectDoesNotClaimItHasNeverBeenOpened() throws Exception {
        Path engine = engine(temp.resolve("ue"), 5, 8, 0, true);
        Path project = temp.resolve("game");
        Files.createDirectories(project.resolve("Saved"));
        writeUproject(project, "5.8");
        Result result = setup(List.of(install(engine, "5.8"))).setup(project);
        assertEquals(Status.CONFIGURED, result.status());
        assertFalse(result.message().contains("has not been opened"));
        assertTrue(result.message().contains("Restart Unreal Editor"));
        assertTrue(result.message().contains("list_toolsets"));
    }

    @Test
    void writesTheEditorDirectoryForEachHostOs() throws Exception {
        for (HostOs os : HostOs.values()) {
            Path project = temp.resolve(os.name());
            Files.createDirectories(project);
            writeUproject(project, "5.8");
            Result result = new UnrealMcpSetup(() -> List.of(), os).setup(project);
            assertEquals(Status.ENGINE_MISSING, result.status());
            assertTrue(Files.isRegularFile(iniPath(project, os)));
            assertTrue(result.message().contains("Saved/Config/" + os.editorDirectory() + "/EditorPerProjectUserSettings.ini"));
        }
    }

    @Test
    void symlinkUprojectOutsideTheFolderIsNotEdited() throws Exception {
        Path outside = temp.resolve("outside");
        Files.createDirectories(outside);
        Path target = outside.resolve("Game.uproject");
        Files.writeString(target, "{}\n");
        Path project = temp.resolve("project");
        Files.createDirectories(project);
        Files.createSymbolicLink(project.resolve("Game.uproject"), target);
        Result result = setup(List.of()).setup(project);
        assertEquals(Status.FAILED, result.status());
        assertTrue(result.message().contains("outside"));
        assertEquals("{}\n", Files.readString(target));
        assertFalse(Files.exists(project.resolve(".mcp.json")));
    }

    @Test
    void directorySymlinkReceivesFilesInTheRealProject() throws Exception {
        Path engine = engine(temp.resolve("ue"), 5, 8, 0, true);
        Path real = temp.resolve("real");
        Files.createDirectories(real);
        writeUproject(real, "5.8");
        Path link = temp.resolve("link");
        Files.createSymbolicLink(link, real);
        Result result = setup(List.of(install(engine, "5.8"))).setup(link);
        assertEquals(Status.CONFIGURED, result.status());
        assertTrue(Files.isRegularFile(real.resolve(".mcp.json")));
        assertFalse(Files.isRegularFile(link.resolve(".mcp.json")) && !Files.isRegularFile(real.resolve(".mcp.json")));
    }

    @Test
    void systemLocatorDoesNotThrowWhenTheFolderHasNoProject() {
        Result result = new UnrealMcpSetup().setup(temp.resolve("empty-system"));
        assertEquals(Status.FAILED, result.status());
        assertTrue(result.message().contains("does not exist"));
        Path created = temp.resolve("created");
        assertTrue(created.toFile().mkdir());
        Result empty = new UnrealMcpSetup().setup(created);
        assertEquals(Status.NO_PROJECT, empty.status());
        assertTrue(empty.message().contains("no .uproject"));
        assertTrue(empty.message().contains("5.8"));
    }

    @Test
    void installIniParserKeepsOnlyTheInstallationsSection() {
        List<EngineInstallations.Install> installs = EngineInstallations.parseInstallIni("""
                ; comment
                [Other]
                Skip=/nope

                [Installations]
                {AAAAAAAA-BBBB-CCCC-DDDD-EEEEEEEEEEEE}=/opt/UE_5.8
                5.4="/opt/UE 5.4"

                [Later]
                Also=/skip
                """);
        assertEquals(2, installs.size());
        assertEquals("/opt/UE_5.8", installs.get(0).root().toString());
        assertEquals("{AAAAAAAA-BBBB-CCCC-DDDD-EEEEEEEEEEEE}", installs.get(0).key());
        assertEquals("/opt/UE 5.4", installs.get(1).root().toString());
    }

    @Test
    void launcherAndRegistryParsersReadEngineRoots() {
        List<EngineInstallations.Install> launcher = EngineInstallations.parseLauncherInstalled("""
                {"InstallationList":[{"InstallLocation":"D:\\\\Epic\\\\UE_5.8","AppName":"UE_5.8"}]}
                """);
        assertEquals(1, launcher.size());
        assertEquals("UE_5.8", launcher.get(0).key());
        assertEquals(List.of(), EngineInstallations.parseLauncherInstalled("{"));

        String registry = """
                HKEY_CURRENT_USER\\Software\\Epic Games\\Unreal Engine\\Builds
                    {AAAAAAAA-BBBB-CCCC-DDDD-EEEEEEEEEEEE}    REG_SZ    D:\\Engines\\UE_5.8
                    5.7    REG_SZ    C:\\Program Files\\Epic Games\\UE_5.7
                """;
        List<EngineInstallations.Install> builds = EngineInstallations.parseRegistryQuery(registry);
        assertEquals(2, builds.size());
        assertEquals("{AAAAAAAA-BBBB-CCCC-DDDD-EEEEEEEEEEEE}", builds.get(0).key());
        assertEquals("D:\\Engines\\UE_5.8", builds.get(0).root().toString());
        assertEquals("5.7", builds.get(1).key());
    }

    @Test
    void readConfiguredIgnoresMissingPathsAndKeepsARealEngine() throws Exception {
        Path engine = engine(temp.resolve("installed"), 5, 8, 0, true);
        Path home = temp.resolve("home");
        Path ini = EngineInstallations.installIni(home, HostOs.LINUX);
        Files.createDirectories(ini.getParent());
        Files.writeString(ini, "[Installations]\n5.8=" + engine + "\nmissing=/no/such/engine\n");
        List<EngineInstallations.Install> found = EngineInstallations.readConfigured(
                home, temp.resolve("program"), HostOs.LINUX, () -> "");
        assertEquals(1, found.size());
        assertEquals(engine.toRealPath(), found.get(0).root().toRealPath());

        Path manifest = temp.resolve("program").resolve("Epic").resolve("UnrealEngineLauncher")
                .resolve("LauncherInstalled.dat");
        Files.createDirectories(manifest.getParent());
        Files.writeString(manifest, "{\"InstallationList\":[{\"InstallLocation\":\""
                + engine.toString().replace("\\", "\\\\") + "\",\"AppName\":\"UE_5.8\"}]}");
        List<EngineInstallations.Install> windows = EngineInstallations.readConfigured(
                home, temp.resolve("program"), HostOs.WINDOWS, () -> "not a registry dump");
        assertEquals(1, windows.size());
        assertEquals("UE_5.8", windows.get(0).key());
    }

    @Test
    void macInstallIniPathMatchesTheVersionSelector() {
        Path ini = EngineInstallations.installIni(Path.of("/Users/dev"), HostOs.MAC);
        assertEquals(Path.of("/Users/dev/Library/Application Support/Epic/UnrealEngine/Install.ini"), ini);
    }

    private UnrealMcpSetup setup(List<EngineInstallations.Install> installs) {
        return new UnrealMcpSetup(() -> installs, HostOs.LINUX);
    }

    private static EngineInstallations.Install install(Path root, String key) {
        return new EngineInstallations.Install(root, key);
    }

    private static void writeUproject(Path project, String association) throws Exception {
        Files.writeString(project.resolve("Game.uproject"), uprojectJson(association));
    }

    private static String uprojectJson(String association) {
        return """
                {
                  "FileVersion": 3,
                  "EngineAssociation": "%s"
                }
                """.formatted(association);
    }

    private static Path iniPath(Path project, HostOs os) {
        return project.resolve("Saved").resolve("Config").resolve(os.editorDirectory())
                .resolve("EditorPerProjectUserSettings.ini");
    }

    private static Path engine(Path root, int major, int minor, int patch, boolean plugin) throws Exception {
        Files.createDirectories(root.resolve("Engine/Build"));
        Files.writeString(root.resolve("Engine/Build/Build.version"), """
                { "MajorVersion": %d, "MinorVersion": %d, "PatchVersion": %d }
                """.formatted(major, minor, patch), StandardCharsets.UTF_8);
        if (plugin) {
            Files.createDirectories(root.resolve("Engine/Plugins/Experimental/ModelContextProtocol"));
        }
        return root;
    }
}
