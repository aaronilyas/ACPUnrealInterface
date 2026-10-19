# UnrealInterface

UnrealInterface is a desktop [Agent Client Protocol](https://agentclientprotocol.com/) (ACP) client. It launches a coding agent as a subprocess, speaks JSON-RPC over stdio, and shows the session in a window.

The window is only a front end. The agent process does the work: it reads the project, edits files, and runs commands. When a project directory is opened or a session starts there, UnrealInterface copies a skills bundle into the project so the agent can discover how this client behaves and how to work in Unreal Engine 5.8+.

The client uses the [ACP Java SDK](https://github.com/agentclientprotocol/java-sdk) (`com.agentclientprotocol:acp-core` 0.80.0) for `initialize`, `session/new`, `session/prompt`, and streaming `session/update` notifications.

## Requirements

- Java 17 or newer
- Maven 3.9 or newer
- A desktop session with GTK 3 (JavaFX)
- An ACP agent command. Claude Code and Codex do not speak ACP themselves; the presets launch their official adapters through `npx`

[mise](https://mise.jdx.dev/) users can install the toolchain from `mise.toml`:

```bash
mise install
```

## Build and test

```bash
mvn test
```

`mvn test` compiles the app and starts a local ACP agent process (a small Python peer). That test checks `initialize`, `session/new`, a `session/prompt` round trip, streamed `session/update` notifications, and a project file read requested by the agent. It also checks a missing program and a process that exits before the handshake.

## Run

```bash
mvn javafx:run
```

1. Choose a project directory, or type one and leave the field. The app copies the skills bundle into `.agents/skills/` (Codex, Cursor, Gemini CLI) and `.claude/skills/` (Claude Code). It also writes `AGENTS.md` and a `CLAUDE.md` that imports it. The same copy runs when a session starts. Files you have edited are left alone. When the folder contains a `.uproject`, the app also enables the Unreal MCP plugins, turns on auto-start, and writes `.mcp.json`. **Setup Unreal MCP** runs that step again. A folder with no `.uproject`, or a machine without Unreal Engine 5.8 or newer, gets a status line that says what to do next.
2. Pick an agent. **Claude** and **Codex** fill the command field with their ACP adapters. **Custom** is any command you type. The command is executed directly, not through a shell, so quotes work and pipes do not.
3. Click **New session**, or type a prompt and press **Send** (Ctrl+Enter). The first send opens the session.
4. The output pane streams assistant text, thoughts, tool calls, plans, and status. **Cancel** sends `session/cancel`. **Clear** wipes the pane and leaves the session running. **New session** starts a fresh agent process.

Auto-approve answers permission requests with the agent's allow-once option when it offers one. Turn it off to pick an option in a dialog.

### Agent commands

| Preset | Command |
| --- | --- |
| Claude | `npx -y @agentclientprotocol/claude-agent-acp` |
| Codex | `npx -y @agentclientprotocol/codex-acp` |
| Custom | Whatever you enter. A native ACP agent such as `gemini --acp` works here. |

`claude` and `codex` themselves are not ACP servers. Point the command at an adapter or at a CLI that has an ACP mode. The agent inherits the environment of UnrealInterface, including API keys and `PATH`.

## What the client implements

- `initialize` with the client name `unreal-interface` and file read/write capability
- `session/new` with the project directory as `cwd`. The agent process is started in that directory
- `session/prompt` with the text from the prompt box
- `session/update` rendered in the output pane
- `session/cancel`
- `session/request_permission`
- `fs/read_text_file` and `fs/write_text_file`, limited to the project directory

Terminal sessions stay inside the agent. UnrealInterface does not run commands for it.

## Errors

- **Program not found.** The command's first word is not an executable on `PATH` (or the absolute path is not executable). Install the adapter, or edit the command.
- **ACP handshake failed.** The process started, but `initialize` or `session/new` did not succeed. The message includes the agent process's recent stderr. Typical causes are a command that is not an ACP server, a crashed adapter, or an adapter that exits before the handshake (for example `npx` failing to download the package).

## Project layout

```
src/main/java/com/unrealinterface/UnrealInterfaceApp.java   JavaFX entry point
src/main/java/com/unrealinterface/ui/MainView.java          Window
src/main/java/com/unrealinterface/acp/AgentSession.java     ACP session
src/main/java/com/unrealinterface/skills/SkillsInstaller.java
src/main/java/com/unrealinterface/unreal/UnrealMcpSetup.java   Unreal MCP project setup
src/main/resources/skills-bundle/                           Skills and AGENTS.md copied into projects
src/test/resources/fake_agent.py                            Local ACP peer used by tests
```

## License

MIT. See [LICENSE](LICENSE).
