---
name: unreal-interface
description: Use when this project was opened in UnrealInterface, or when asked how that desktop Agent Client Protocol client expects work to be done. UnrealInterface only displays the session. The agent reads, edits, and runs commands in the project.
---

# UnrealInterface

This project was opened in UnrealInterface, a desktop client for the Agent Client Protocol (ACP).

The client launches you as a subprocess and talks JSON-RPC over stdio. It is a front end. Do the work in this project yourself.

## How to work

- Treat the session working directory as the project root.
- Read and edit project files, and run commands, with your own tools.
- Report progress as ordinary ACP session updates: assistant text, tool calls, and plans. The window shows those as they arrive.
- The client can answer `fs/read_text_file` and `fs/write_text_file` only for files inside this project. Paths outside the project are rejected, including symlinks and hard links that leave the project. Reads above 4 MiB are rejected. Use your own tools for anything else.
- Permission requests are shown in the window. Auto-approve is off unless the user turns it on, and then it selects only an allow-once option.
- Do not try to implement features inside the UnrealInterface application unless the user asked you to change that program.

## Unreal Engine

When this directory contains a `.uproject`, read the sibling `unreal-router` skill and follow it before editor, asset, or gameplay work. That skill reads the MCP, Blueprint, C++, scene, and iteration skills that match the task.

## More detail

See [reference.md](reference.md) in this skill folder for the client surface and the directories the skill is copied to.
