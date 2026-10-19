# UnrealInterface client surface

UnrealInterface copies this skill into the opened project so an agent can discover it:

- `.agents/skills/unreal-interface/` for Codex, Cursor, Gemini CLI, and other harnesses that read `.agents/skills`
- `.claude/skills/unreal-interface/` for Claude Code, which does not read `.agents/skills`

The same copy installs the sibling Unreal skills into those two directories, plus `AGENTS.md` at the project root. `CLAUDE.md` in the project root imports `AGENTS.md`, because Claude Code loads `CLAUDE.md` and skips `AGENTS.md` when `CLAUDE.md` is present.

Existing files are left in place. Delete a copied file if you want the client to write it again the next time the project is opened.

## Session lifecycle

1. The client starts the agent command with its working directory set to the project.
2. `initialize` exchanges protocol version 1 and capabilities. The client advertises project file read and write.
3. `session/new` opens a session whose `cwd` is the project directory.
4. `session/prompt` sends the user's text.
5. `session/update` notifications are appended to the output pane: agent text, thoughts, tool calls, plans, usage, and mode changes.
6. `session/cancel` stops the current prompt when the user clicks Cancel.

The client does not edit code, run builds, or call a model. That work stays in the agent process.
