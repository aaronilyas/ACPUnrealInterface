---
name: unreal-iteration
description: "Use after any Unreal Editor change. Run one change, then Play In Editor or a viewport screenshot, then observe, then fix. Visual verification is required before calling a visible change done."
---

# Iteration

A visible change is done when you have looked at it. A successful tool result is not that look.

## Loop

1. Make one small change.
2. Observe it with the check below.
3. Say what you expected and what you actually saw.
4. Fix only the difference.
5. Observe again. Stop when the capture matches the request.

Do not start a second change on top of one you have not looked at.

## What to capture

Read the Calling tools section of `../unreal-mcp-setup/SKILL.md`. Use an MCP capture tool when `describe_toolset` shows one.

- Level, actor, light, or material: capture the editor viewport. If no capture tool is registered, run the console command `HighResShot` and open the image it writes under `Saved/Screenshots`.
- Gameplay, input, or a Blueprint that runs only during play: start Play In Editor through an MCP play tool, capture, then stop Play In Editor.
- If no play tool is registered, ask the user to press Play and use the screenshot or log they send back. Do not invent a console command for starting play.

Stop Play In Editor before the next asset edit or compile. Editor tools return misleading results while play is running.

Open the image and judge it. If you cannot capture, say what you could not verify.
