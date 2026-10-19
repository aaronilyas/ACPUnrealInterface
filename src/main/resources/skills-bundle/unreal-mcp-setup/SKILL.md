---
name: unreal-mcp-setup
description: "Use when connecting an agent to Unreal Engine 5.8 or newer, when an editor tool call cannot connect, or when a project needs ModelContextProtocol, auto-start, or .mcp.json. Covers a missing engine install and a folder with no .uproject. Idempotent. Does not ask the user to open Editor Preferences."
---

# Unreal MCP setup

Enable the official Unreal Engine 5.8+ MCP server by editing project files. Do not ask the user to open Editor Preferences or the Plugins window.

UnrealInterface does this when a folder containing a `.uproject` is selected, and when the user clicks **Setup Unreal MCP**. Verify the files below. Write a file only when its contents differ from the target.

Default endpoint: `http://127.0.0.1:8000/mcp` (port `8000`, path `/mcp`). Use another port or path only when that ini section already sets `ServerPortNumber` or `ServerUrlPath`, or when the editor log says the port is taken.

## Find the project

Search the working directory and its parents for a `*.uproject`. Skip files under `Engine/`. One file: that file's directory is the project. None: stop. This is not an Unreal project yet. Tell the user to install Unreal Engine 5.8 or newer, choose File > New Project, then open the folder that contains the new `.uproject`. More than one `.uproject` in the same folder: do not edit. Ask the user to keep one project per folder.

Read `EngineAssociation` when the file parses.

- Compare a dotted version as integers on the first two numbers. `5.10` is newer than `5.8`. A third component does not matter. `5.8.2` is 5.8.
- Older than 5.8: stop. Do not write any setup file. Tell the user to install Unreal Engine 5.8 or newer, right-click the `.uproject`, choose Switch Unreal Engine version, then run this setup again.
- A GUID, a path, or a missing value is not a version. Continue.

## Engine

An engine root contains `Engine/Build/Build.version`. The MCP plugin directory is `Engine/Plugins/Experimental/ModelContextProtocol`. `MajorVersion` and `MinorVersion` in `Build.version` use the same integer comparison as `EngineAssociation`.

| Host | Where installs are registered |
| --- | --- |
| Windows | `C:\ProgramData\Epic\UnrealEngineLauncher\LauncherInstalled.dat` (`InstallationList`, `AppName` such as `UE_5.8`, `InstallLocation` is the engine root). Source builds: `HKCU\Software\Epic Games\Unreal Engine\Builds` (value name is the association, value is the engine root). |
| macOS | `~/Library/Application Support/Epic/UnrealEngine/Install.ini`, section `[Installations]`, `key=engine root` |
| Linux | `~/.config/Epic/UnrealEngine/Install.ini`, section `[Installations]`, `key=engine root` |

Resolve the engine in this order: a path association (relative to the project, or `Engine` itself when the path ends there); a version match on `Build.version` or a `UE_<major>.<minor>` folder or key; a GUID or other key in the registration table, compared without braces; a blank association walks parents for an engine root. Do not pair a version or GUID with a different engine.

Write the project files unless the resolved engine, or an explicit association version, is older than 5.8. Then say which case applies:

- No engine registered, or the associated version is not on disk: Unreal Engine is not installed. Install it from the Epic Games Launcher, open the `.uproject`, and allow one editor restart. The project files are already written.
- Some other engine is installed: right-click the `.uproject`, choose Switch Unreal Engine version, select 5.8 or newer, and restart the editor.
- The engine root exists but `Engine/Plugins/Experimental/ModelContextProtocol` does not: install or rebuild Unreal Engine 5.8 or newer with that plugin, then restart the editor.
- `Saved/` does not exist: the project has not been opened in the editor yet. Launch the `.uproject`. The new settings apply on that launch.

## 1. Plugins in the `.uproject`

`ModelContextProtocol` is the server. `AllToolsets` exposes the tools. The server without `AllToolsets` starts and advertises no tools. `ToolsetRegistry` enables itself as a dependency. Do not add it.

Edit the `.uproject` as JSON. Keep every existing field and every other plugin object.

```json
{
  "Name": "ModelContextProtocol",
  "Enabled": true
}
```

```json
{
  "Name": "AllToolsets",
  "Enabled": true
}
```

- Ensure `Plugins` is an array. Create it only when it is missing.
- Find the object whose `Name` matches. Match case.
- If that object is missing, append one object with `Name` and `Enabled` set to true.
- If `Enabled` is not boolean `true`, set it to true. Keep the object's other fields.
- Never add a second object with the same `Name`.
- Write the file only when an entry changed. If the JSON does not parse, or `Plugins` is not an array, stop and report the file. Do not overwrite it.

## 2. Auto-start

The editor reads auto-start from per-user config. This file is the Auto Start Server checkbox. It is not source-controlled.

`<project>/Saved/Config/<Platform>Editor/EditorPerProjectUserSettings.ini`

| Host OS | Directory |
| --- | --- |
| Windows | `WindowsEditor` |
| macOS | `MacEditor` |
| Linux | `LinuxEditor` |

Use the OS that will launch the editor. UnrealInterface writes the directory for the OS it is running on. If the editor runs on another OS, run setup there too.

```ini
[/Script/ModelContextProtocolEngine.ModelContextProtocolSettings]
bAutoStartServer=True
```

- Create missing parent directories and the file.
- If the section is missing, append it after a blank line.
- If `bAutoStartServer` is already `true` in any case, leave that line.
- If the key is present with another value, replace that line only.
- If the section exists without the key, add the key under its header.
- Leave every other section, comment, and key untouched. Do not set `ServerPortNumber` or `ServerUrlPath` unless a recovery step below says to.
- If those keys are already set, the endpoint uses them: `http://127.0.0.1:<ServerPortNumber><ServerUrlPath>`. `ServerUrlPath` gains a leading `/` when it has none.

The setting applies on the next editor launch. If the editor is open, it keeps the previous setting until restart. Say that once.

A single launch can also pass `-ModelContextProtocolStartServer`. Prefer the ini. It persists.

## 3. Client config

Write these files in the project directory, next to the `.uproject`. That is the directory UnrealInterface uses as the agent working directory. Do not require the editor console.

`.mcp.json`:

```json
{
  "mcpServers": {
    "unreal-mcp": {
      "type": "http",
      "url": "http://127.0.0.1:8000/mcp"
    }
  }
}
```

- If the file is missing, create it with that object and the endpoint from step 2.
- If it is valid JSON, keep every other server. Set `mcpServers.unreal-mcp` to `type` `http` and that `url` when either differs. If `type` and `url` already match, do not write the file, and keep extra keys on that object.
- If `mcpServers` is missing, create the object. If it is not an object, stop and report the file. Do not overwrite it.
- If the file is not valid JSON, stop and report it. Do not overwrite it.

Codex reads `.codex/config.toml` in the project directory, not `.mcp.json`:

```toml
[mcp_servers.unreal-mcp]
url = "http://127.0.0.1:8000/mcp"
```

- Create `.codex/config.toml` with only that table when the file is missing.
- If the table is missing, append it. Leave every other table.
- If the table has `command`, leave the table. It is a proxy or stdio override.
- If `url` already equals the endpoint, leave the file.
- If `url` differs and there is no `command`, replace that line only.
- If the file is not text, leave it and report it.

`ModelContextProtocol.GenerateClientConfig` can write Claude Code, Cursor, VS Code, Gemini, and Codex configs once the editor is running. Codex refuses to overwrite an existing toml, so edit that file directly. Skip the console command once `.mcp.json` and the Codex table match the endpoint.

Source build: Epic's command writes `.mcp.json` beside `Engine/`, because the editor root is the engine tree. When the agent cwd is a parent that contains `Engine/` and that directory is not the project directory, write the same `.mcp.json` and Codex table there too. UnrealInterface writes only the project directory.

## Restart and check

Plugin and auto-start edits apply when the editor launches. Ask the user to start or restart the editor when those files changed. A project that has never been opened needs a launch, not a restart.

After launch, confirm the server with a live call to `list_toolsets`, not with a config file that merely exists. A result that lists toolsets means the client is connected. If the call fails, read the editor log for the listen line before changing files again.

## Recovery

- No `.uproject`: install Unreal Engine 5.8 or newer if needed, choose File > New Project, then select that project's folder.
- Several `.uproject` files: keep one project in the folder and run setup again.
- Engine older than 5.8, or the plugin directory is missing: install or rebuild 5.8 or newer, choose Switch Unreal Engine version, then run setup again. Do not invent plugin entries for an older engine.
- Engine not installed or not registered: install from the Epic Games Launcher, or add the source build to the registration file in the table above, open the `.uproject`, and allow one restart. Project files can already be in place.
- `.uproject` or `.mcp.json` is not valid JSON, or `Plugins` / `mcpServers` has the wrong JSON type: leave that file unchanged, fix it, and run setup again.
- A setup file points outside the project through a symlink: select the real project folder. Do not follow the link.
- The editor was already open: restart it. Auto-start is read at launch.
- The log says the port is taken: set `ServerPortNumber` in the same ini section, put that port in `.mcp.json` and in the Codex `url`, and restart. Do not change `ServerUrlPath` unless the user asked.
- The server starts and `list_toolsets` is empty: `AllToolsets` is not enabled. Fix that `.uproject` entry and restart.
- `list_toolsets` fails and the log has no listen line: confirm the editor is 5.8 or newer, both plugins are enabled, and `bAutoStartServer=True` is in the ini for the OS that launched the editor.

## Calling tools

Tool search is the default. The server exposes `list_toolsets`, `describe_toolset`, and `call_tool`. Domain tools are not native MCP tools.

1. Call `describe_toolset` for the domain you already know. Call `list_toolsets` only to find the toolset name.
2. Call `call_tool` with `toolset_name`, `tool_name`, and `arguments` taken from that schema.
3. Read the result. A returned error stops the step.
4. Do not invent tool names. They differ by engine version.

Serialize calls that edit the same asset. Save after a mutation. Stop Play In Editor before creating or compiling assets.
