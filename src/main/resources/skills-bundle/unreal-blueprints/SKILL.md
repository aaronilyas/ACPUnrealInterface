---
name: unreal-blueprints
description: "Use when creating or editing Unreal Blueprint classes, event graphs, Construction Script, Blueprint Interfaces, or event dispatchers. Trigger on BP_ assets, Blueprint graphs, and communication between Blueprints."
---

# Unreal Blueprints

Edit Blueprints through Unreal MCP. Read the Calling tools section of `../unreal-mcp-setup/SKILL.md` before the first call. Do not hand-edit `.uasset` files.

Discover the Blueprint toolset with `describe_toolset`, then compile and read the compile result after every graph change. Save the asset after a successful compile.

## Class

Create a Blueprint asset under `/Game`, in a folder the project already uses for Blueprints. Parent it to the class the user named. Use `Actor` when they did not name one.

Open that asset before adding graphs, variables, or components.

## Event graph

- `BeginPlay` starts runtime behavior.
- Add a function for reusable logic that returns a value. Add a custom event for a one-way call from another graph.
- Enable `Tick` only when the user asked for per-frame work.
- Set designer-facing values as variables on the class, not as literals buried in the graph.

## Construction Script

Construction Script runs in the editor when the actor is placed or a property changes, and again at spawn before `BeginPlay`. It can run many times for one actor.

Use it for component setup and viewport preview that must follow exposed variables.

Do not use it for dispatcher bindings, queries that only make sense during play, or work that must run once. Those belong in the event graph, usually `BeginPlay`.

## Communication

Pick one mechanism. Do not combine them for the same signal.

**Blueprint Interface**, when the caller must not depend on a concrete Blueprint class:

1. Create a Blueprint Interface asset and add the functions.
2. On the receiver, implement that interface.
3. From the caller, send the interface message. A message to an object that does not implement the interface does nothing.
4. Use a typed interface call only when the reference is already known to implement it.

**Event dispatcher**, when one Blueprint must notify listeners that already hold a reference to it:

1. Declare the dispatcher on the broadcaster.
2. Bind listeners in `BeginPlay`.
3. Unbind in `EndPlay`.
4. Broadcast from the broadcaster at the moment the event happens.

Cast to a concrete Blueprint only when that is the type the user named.

## Finish

Compile, read errors, save, then follow `../unreal-iteration/SKILL.md` when the change is visible or playable.
