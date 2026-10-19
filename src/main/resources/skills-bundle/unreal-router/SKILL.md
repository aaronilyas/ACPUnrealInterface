---
name: unreal-router
description: "Use when the workspace has a .uproject, or the user asks for Unreal Engine work: MCP setup, Blueprints, C++ gameplay, Enhanced Input, placing actors, lighting, materials, or checking a change in the editor. Detects the .uproject and reads the matching Unreal skill before editing."
---

# Unreal router

Detect the Unreal project, then read exactly the skills that match the task. Do this before editor edits, asset edits, input bindings, or C++ gameplay changes.

## 1. Detect the project

Search the working directory and its parents for a `*.uproject`.

- If you find one, that file's directory is the project. Ignore sample `.uproject` files under `Engine/`.
- If you do not find one, stop. These Unreal skills do not apply.

## 2. Read the matching skills

Skills are sibling folders of this file (`../<skill>/SKILL.md` in `.agents/skills` or `.claude/skills`). Read each chosen file fully and follow it. Read one copy when both trees exist.

Always read `unreal-mcp-setup` first and run it. It is idempotent when the server is already configured.

Then read the one domain skill for the task:

| Task | Skill |
| --- | --- |
| Plugins, auto-start, `.mcp.json`, or a failed MCP connection | `unreal-mcp-setup` only |
| Blueprint classes, event graphs, Construction Script, interfaces, event dispatchers | `unreal-blueprints` |
| C++ gameplay types, when Blueprints are not enough | `unreal-cpp-gameplay` |
| Actor placement, levels, lighting, materials | `unreal-scene-building` |
| Enhanced Input, input actions, mapping contexts, or player bindings | `unreal-enhanced-input` |

A task that needs two domains is two passes. Finish one skill, then follow `unreal-iteration`, then start the next skill.

## 3. Close the loop

After any editor or asset change, read `unreal-iteration` and complete one observe-and-fix pass before reporting the change as done.
