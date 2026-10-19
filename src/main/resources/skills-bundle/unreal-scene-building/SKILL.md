---
name: unreal-scene-building
description: "Use when placing or moving actors, editing a level, lighting a scene, or creating and assigning materials in Unreal Editor through Unreal MCP tools."
---

# Scene building

Change the level through Unreal MCP. Read the Calling tools section of `../unreal-mcp-setup/SKILL.md` before the first call. Do not hand-edit `.umap` files.

Unreal units are centimeters. The world is Z-up, X forward, Y right.

Save the level after placement, light, or material assignment. Then follow `../unreal-iteration/SKILL.md`.

## Level

Confirm which level is loaded. Open the level the user named before adding anything. Work in the current level when they did not name one.

## Actors

1. Describe the actor or level toolset and spawn into the loaded level.
2. Set location, rotation, and scale from the request. A missing coordinate stays at the tool default.
3. Set the actor's label to the name the user used.
4. Move, duplicate, or delete only the actors the user identified. Read the current transform back when placement has to line up with something already in the level.

## Lighting

Add lights when the user asks for lighting, or when a capture shows an unlit level.

- One directional light for the sun, pitched downward.
- A sky atmosphere and a sky light, so the level is not black outside that sun.
- Fog or a post-process volume only when the user asks.
- Leave mobility at the tool default. Use Movable when the user will move the light. Use Static or Stationary only when they ask for a baked light.
- Do not build lighting unless the user asks.

## Materials

1. Describe the material toolset.
2. Create a Material Instance when an existing material can take the color or roughness the user described. Create a Material when there is no parent to instance.
3. Put the asset under `/Game` in a folder the project already uses for materials.
4. Set base color, roughness, and metallic from the request.
5. Assign it to the mesh slot the user named, or to slot 0.
6. Save the material asset and the level.
