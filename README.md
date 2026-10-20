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

`mvn test` compiles the app and starts a local ACP agent process (a small Python peer). That test checks `initialize`, `session/new`, a `session/prompt` round trip, streamed `session/update` notifications, and a project file read requested by the agent. It also checks a missing program and a process that exits before the handshake. A smoke test copies the skills bundle into a temporary project and checks that Unreal MCP setup writes the plugin entries, the auto-start ini fragment, and `.mcp.json`.

## Continuous integration

[`.github/workflows/ci.yml`](.github/workflows/ci.yml) runs `mvn test` on every push and pull request to `main`. The runner is Ubuntu with Temurin 17. `python3` has to be on `PATH` for the ACP session test.

## Run

```bash
mvn javafx:run
```

On a first launch, and whenever no recent project is stored, a welcome pane sits above the project fields. It is not a dialog. Browse, the agent command, and Send stay usable. After setup finishes for a folder that contains one `.uproject`, later launches fill the project field from the saved path and leave the pane hidden. See [First launch](#first-launch).

1. Choose a project directory with **Browse**, or type a path and leave the field. Browse and starting a session copy the skills bundle into that directory. Leaving the field copies it only when the directory contains a `.uproject`. The copy never targets your home directory or its `.claude` and `.agents` folders, and it runs once per directory. Files land in `.agents/skills/` (Codex, Cursor, Gemini CLI) and `.claude/skills/` (Claude Code), plus `AGENTS.md` and a `CLAUDE.md` that imports it. Files you have edited are left alone. A `.agents` or `.claude` symlink that points outside the project is refused. When the folder contains a `.uproject`, the app also enables the Unreal MCP plugins, turns on auto-start, and writes `.mcp.json`. **Setup Unreal MCP** runs that step again. A version such as `5.8` also matches an unregistered engine under `~/Downloads` (`Linux_Unreal_Engine_*` or `UnrealEngine*`) or `~/UnrealEngine*`, or the root named by `UNREAL_ENGINE_ROOT`. A blank or missing `EngineAssociation` uses that override when it is 5.8 or newer, then a parent engine, then the highest unregistered engine that is 5.8 or newer. The status line names that path. A folder with no `.uproject`, or a machine without Unreal Engine 5.8 or newer, gets a status line that says what to do next.
2. Pick an agent. **Claude** and **Codex** fill the command field with their ACP adapters. **Custom** is any command you type. The command is split on words, not run through a shell. The program is resolved to an absolute path before the project becomes the working directory, so a file in the project cannot shadow `npx` or another command.
3. Click **New session**, or type a prompt and press **Send** (Ctrl+Enter). The first send opens the session.
4. The output pane streams assistant text, thoughts, tool calls, plans, and status. **Cancel** sends `session/cancel`. **Clear** wipes the pane and leaves the session running. **New session** starts a fresh agent process.

Permission requests open a dialog. **Auto-approve permissions** is off. Turn it on to select the agent's allow-once option when it offers one. An allow-always option is never selected automatically.

## First launch

The welcome pane explains that UnrealInterface is an ACP client that installs Unreal skills and auto-configures Unreal MCP. **Open project folder** uses the same directory chooser as **Browse**. A new game is created in Unreal Editor 5.8 or newer with File > New Project. Open the folder that contains the new `.uproject` here. The pane also has two example prompts, each with **Copy**.

The pane is hidden when setup finishes for that folder: the project is configured, it was already ready, or the engine or MCP plugin is missing. The status line still says what to do next. A folder with no `.uproject`, several `.uproject` files, an engine older than 5.8, or a failed setup leaves the pane in place.

The choice is stored so the pane does not return on every launch:

| Host | File |
| --- | --- |
| Linux | `~/.config/unreal-interface/state.properties` (`$XDG_CONFIG_HOME/unreal-interface/` when that variable is set) |
| macOS | `~/Library/Application Support/unreal-interface/state.properties` |
| Windows | `%APPDATA%\unreal-interface\state.properties` |

`welcomeSeen=true` and `recentProject` are written together. If the saved directory is gone, the pane is shown again.

### Agent commands

| Preset | Command |
| --- | --- |
| Claude | `npx -y @agentclientprotocol/claude-agent-acp` |
| Codex | `npx -y @agentclientprotocol/codex-acp` |
| Custom | Whatever you enter. A native ACP agent such as `gemini --acp` works here. |

`claude` and `codex` themselves are not ACP servers. Point the command at an adapter or at a CLI that has an ACP mode. The agent inherits the environment of UnrealInterface, including API keys and `PATH`.

## Packaging

`mvn javafx:run` and `mvn test` are unchanged. To build an app image that does not need Maven at runtime:

```bash
./scripts/package.sh
```

That runs `mvn -B -Pjpackage package`, then `jpackage`. The image is `target/dist/UnrealInterface`. Start `target/dist/UnrealInterface/bin/UnrealInterface` on Linux and Windows, or open `target/dist/UnrealInterface.app` on macOS. The script replaces `target/jpackage` and `target/dist` each time.

The skills bundle is inside the application jar (`skills-bundle/` on the classpath). The installer reads it from there, the same way `mvn javafx:run` does. The Claude and Codex presets are still `npx -y @agentclientprotocol/claude-agent-acp` and `npx -y @agentclientprotocol/codex-acp`. The packaged app resolves `npx` from `PATH`, so those presets need Node.js. A custom command is resolved the same way.

`app-image` needs a JDK 17 or newer that provides `jpackage`. Set `JPACKAGE_TYPE` to `deb`, `rpm`, `dmg`, `pkg`, `exe`, or `msi` for an installer. Those types need the usual platform tools (`dpkg-deb`, `rpmbuild`, or WiX). Extra arguments are passed to Maven, for example `./scripts/package.sh -DskipTests`.

The `jpackage` command is:

```bash
jpackage \
  --type app-image \
  --dest target/dist \
  --name UnrealInterface \
  --app-version 1.0.0 \
  --vendor UnrealInterface \
  --description "ACP client that installs Unreal skills and configures Unreal MCP" \
  --input target/jpackage/input \
  --main-jar unreal-interface-1.0.0.jar \
  --main-class com.unrealinterface.UnrealInterfaceApp \
  --module-path target/jpackage/javafx-mods \
  --add-modules javafx.controls \
  --java-options -Dfile.encoding=UTF-8
```

`mvn -Pjpackage package` only fills `target/jpackage/input` (the app jar and the runtime jars, without JavaFX) and `target/jpackage/javafx-all` (every JavaFX jar). The script keeps the host classifier (`linux`, `linux-aarch64`, `mac`, `mac-aarch64`, or `win`) in `target/jpackage/javafx-mods`. Those platform jars are the JavaFX modules. The empty JavaFX jars stay out of the module path.

## What the client implements

- `initialize` with the client name `unreal-interface` and file read/write capability
- `session/new` with the project directory as `cwd`. The agent process is started in that directory
- `session/prompt` with the text from the prompt box
- `session/update` rendered in the output pane
- `session/cancel`
- `session/request_permission`
- `fs/read_text_file` and `fs/write_text_file`, limited to the project directory. Symlinks and hard links that reach outside the project are rejected. A read above 4 MiB, or a write above 4 million characters, is rejected.

Terminal sessions stay inside the agent. UnrealInterface does not run commands for it.

## Errors

- **Program not found.** The command's first word is not an executable on `PATH` (or the absolute path is not executable). Install the adapter, or edit the command.
- **ACP handshake failed.** The process started, but `initialize` or `session/new` did not succeed. The message includes the agent process's recent stderr. Typical causes are a command that is not an ACP server, a crashed adapter, or an adapter that exits before the handshake (for example `npx` failing to download the package).

## Project layout

```
src/main/java/com/unrealinterface/UnrealInterfaceApp.java   JavaFX entry point
src/main/java/com/unrealinterface/ui/MainView.java          Window
src/main/java/com/unrealinterface/ui/WelcomeState.java      First-launch pane and recent project
src/main/java/com/unrealinterface/acp/AgentSession.java     ACP session
src/main/java/com/unrealinterface/skills/SkillsInstaller.java
src/main/java/com/unrealinterface/unreal/UnrealMcpSetup.java   Unreal MCP project setup
src/main/resources/skills-bundle/                           Skills and AGENTS.md copied into projects
src/test/resources/fake_agent.py                            Local ACP peer used by tests
scripts/package.sh                                          jpackage app image
.github/workflows/ci.yml                                    GitHub Actions test workflow
```

## License

MIT. See [LICENSE](LICENSE).
