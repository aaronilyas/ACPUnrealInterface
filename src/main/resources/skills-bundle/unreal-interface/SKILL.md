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
- The client can answer `fs/read_text_file` and `fs/write_text_file` only for files inside this project. Paths outside the project are rejected. Use your own tools for anything else.
- Permission requests are approved in the window, or automatically when the user turned auto-approve on.
- Do not try to implement features inside the UnrealInterface application unless the user asked you to change that program.

## More detail

See [reference.md](reference.md) in this skill folder for the client surface and the directories the skill is copied to.
