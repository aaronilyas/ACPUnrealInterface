---
name: unreal-mcp-setup
description: "Use when connecting an agent to Unreal Engine 5.8 or newer, or when an editor tool call cannot connect. Enables ModelContextProtocol and AllToolsets in the .uproject, sets bAutoStartServer in EditorPerProjectUserSettings.ini, and writes .mcp.json. Idempotent. Does not ask the user to open Editor Preferences."
---

# Unreal MCP setup

Enable the official Unreal Engine 5.8+ MCP server by editing project files. Do not ask the user to open Editor Preferences or the Plugins window.

Run every step. Change a file only when its contents differ from the target. If a step is already satisfied, leave that file alone and continue.

## Find the project

1. Use the `.uproject` chosen by `unreal-router`. If you have not run that skill, find the `.uproject` in the working directory or a parent. Skip `.uproject` files under `Engine/`.
2. Read `EngineAssociation` when it is present.
   - Split a dotted version on `.` and compare the first two numbers as integers. `5.10` is newer than `5.8`. A third component does not matter.
   - Stop when the version is older than 5.8. That engine has no official MCP plugin.
   - A GUID, or a missing value, is fine. Continue.
3. Call the `.uproject` directory the project directory.

## 1. Plugins in the `.uproject`

`ModelContextProtocol` is the server. `AllToolsets` exposes the tools. The server without `AllToolsets` starts and advertises no tools. `ToolsetRegistry` enables itself as a dependency.

Edit the `.uproject` as JSON. Keep every existing field.

- Ensure `Plugins` is an array. Create it only when it is missing.
- For `ModelContextProtocol` and `AllToolsets`, find the object whose `Name` matches.
- If that object is missing, append `{"Name":"<name>","Enabled":true}`.
- If `Enabled` is not `true`, set it to `true`.
- Never add a second object with the same `Name`.
- Write the file only when an entry changed. If the JSON does not parse, stop and report the file. Do not overwrite it.

## 2. Auto-start in `EditorPerProjectUserSettings.ini`

The editor reads auto-start from per-user config. Write that file. This replaces the Editor Preferences checkbox.

Path: `<project>/Saved/Config/<Platform>Editor/EditorPerProjectUserSettings.ini`

| Host OS | Directory |
| --- | --- |
| Windows | `WindowsEditor` |
| macOS | `MacEditor` |
| Linux | `LinuxEditor` |

Use the OS that will launch the editor. Target section:

```ini
[/Script/ModelContextProtocolEngine.ModelContextProtocolSettings]
bAutoStartServer=True
```

- Create missing parent directories and the file.
- If the section is missing, append it after a blank line.
- If `bAutoStartServer` is already `True`, leave that line.
- If the key is present with another value, replace that line only.
- If the section exists without the key, add the key under its header.
- Leave every other section and key untouched.
- Do not set `ServerPortNumber` or `ServerUrlPath` unless the user asked. The defaults are port `8000` and path `/mcp`, so the URL is `http://127.0.0.1:8000/mcp`.

The setting applies on the next editor launch. If the editor is open, it keeps the previous setting until restart. Say that once.

A single launch can also pass `-ModelContextProtocolStartServer`. Prefer the ini. It persists.

## 3. Generate `.mcp.json`

Write the client file yourself. Do not require the editor console.

Write `.mcp.json` in the project directory, next to the `.uproject`. Do that for a launcher build and for a source build. Do not walk parents looking for an `Engine` folder, and do not write the file beside `Engine/`. Claude Code and Cursor load `.mcp.json` from the directory the session was started in. A session started in the project does not read a file that sits next to the engine.

File: `<project directory>/.mcp.json`

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

- If the file is missing, create it with that object.
- If it exists and is valid JSON, keep every other server. Set `mcpServers.unreal-mcp` to the object above when `type` or `url` differs. If the entry already matches, do not write the file.
- If the ini overrides the port or path, use that URL instead.
- If the file exists and is not valid JSON, stop and report it. Do not overwrite it.

Codex reads `.codex/config.toml` in that same project directory, not `.mcp.json` and not a file beside `Engine/`. If `unreal-mcp` is already defined there, leave it. Otherwise add this table without changing other tables:

```toml
[mcp_servers.unreal-mcp]
url = "http://127.0.0.1:8000/mcp"
```

Create `.codex/config.toml` with only that table when the file is missing. `ModelContextProtocol.GenerateClientConfig Codex` refuses to overwrite an existing toml, so edit it directly.

When the editor is already running, `ModelContextProtocol.GenerateClientConfig All` also writes Cursor, VS Code, and Gemini configs and merges JSON. Skip it once `.mcp.json` and, for Codex, the toml table are in place.

## Restart and check

Plugin and auto-start edits apply when the editor launches. Ask the user to start or restart the editor when those files changed.

After launch, confirm the server with a live call to `list_toolsets`, not with a config file that merely exists. A result that lists toolsets means the client is connected. If the call fails, read the editor log for the listen line before changing files again.

If the log says the port is taken, set `ServerPortNumber` in the same ini section, put that port in both URLs, and restart.

## Calling tools

Tool search is the default. The server exposes `list_toolsets`, `describe_toolset`, and `call_tool`. Domain tools are not native MCP tools.

1. Call `describe_toolset` for the domain you already know. Call `list_toolsets` only to find the toolset name.
2. Call `call_tool` with `toolset_name`, `tool_name`, and `arguments` taken from that schema.
3. Read the result. A returned error stops the step.
4. Do not invent tool names. They differ by engine version.

Serialize calls that edit the same asset. Save after a mutation. Stop Play In Editor before creating or compiling assets.
