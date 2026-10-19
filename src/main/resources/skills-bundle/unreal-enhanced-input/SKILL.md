---
name: unreal-enhanced-input
description: "Use when the player should move, jump, look, or otherwise act through Enhanced Input: Input Actions, Input Mapping Contexts, key and gamepad bindings, or adding a mapping context at play. Trigger on IA_ and IMC_ assets, Enhanced Input, and input bindings."
---

# Enhanced Input

Bind player actions through Enhanced Input. Read the Calling tools section of `../unreal-mcp-setup/SKILL.md` before the first call. Do not hand-edit `.uasset` files.

Unreal Engine 5.8 projects use Enhanced Input for new gameplay. Do not add bindings under Project Settings > Input, the legacy axis and action list, unless the user asked for that system.

Discover the input or asset toolset with `describe_toolset`. Save every new asset. Then follow `../unreal-iteration/SKILL.md` and press the new bindings in Play In Editor.

## Project defaults

The third-person template already enables Enhanced Input. When a project has no Input Action assets and the defaults are still the legacy classes, do both of these before creating assets:

- In the `.uproject`, enable `EnhancedInput` the same way other plugins are enabled: one object with `Name` `EnhancedInput` and `Enabled` true. Do not add a second object with that name.
- In `Config/DefaultInput.ini`, set `DefaultPlayerInputClass` to `/Script/EnhancedInput.EnhancedPlayerInput` and `DefaultInputComponentClass` to `/Script/EnhancedInput.EnhancedInputComponent` under `[/Script/Engine.InputSettings]`. Leave every other key. If those two keys already point at Enhanced Input, leave the file.

## Actions

Create one Input Action per verb the user named. Put the assets under `/Game`, in a folder the project already uses for input. Name them `IA_<Verb>`, for example `IA_Jump`, `IA_Move`, and `IA_Look`.

- A press such as jump or interact: value type Digital (bool).
- Movement or look: value type Axis2D.
- A single analog channel: value type Axis1D.

The third-person template already has `IA_Move`, `IA_Look`, and `IA_Jump`. Extend those when they exist. Do not reuse one action for two verbs.

## Mapping context

Create one Input Mapping Context named `IMC_<Context>` for the mode the user named. Use `IMC_Default` for on-foot play when the project does not already have one. Add one mapping per action:

- Map the keys and gamepad controls the user named. When they did not name controls, use a common layout and say what you bound: `IA_Move` to WASD and the left stick, `IA_Look` to mouse XY and the right stick, `IA_Jump` to Space and the gamepad bottom face button.
- Add a mapping only for the actions this task needs.
- Swizzle or negate a mapping only when the axis direction is wrong.

Leave mapping contexts the user did not name alone.

## Apply the context

A context does nothing until a pawn or player controller adds it.

1. On the possessed pawn, or on the player controller, at `BeginPlay`, get the Enhanced Input Local Player Subsystem for that player's local player.
2. Call Add Mapping Context with the context you created and priority `0`. A higher priority wins when two contexts map the same key.
3. Remove that context on `EndPlay` when the mode should not outlive the pawn.
4. Wire each Input Action with an Enhanced Input Action event. `IA_Move` and `IA_Look` read the action value while Triggered. `IA_Jump` uses Started so the jump fires on the press.

The third-person template already adds `IMC_Default` from the character. Extend that context when the project has it. Do not add a second subsystem call for the same context.

## Check

Play In Editor. Press each new binding. A binding that does not move, look, or jump is not done. Fix the mapping or the event, then play again.
