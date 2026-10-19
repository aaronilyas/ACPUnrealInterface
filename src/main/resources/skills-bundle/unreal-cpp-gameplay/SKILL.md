---
name: unreal-cpp-gameplay
description: "Use when Unreal gameplay needs C++ because Blueprints are insufficient: a new reflected type, GameMode, GameState, PlayerController, Pawn, Character, or Actor Component, or a hot path. Trigger on UCLASS, UPROPERTY, AActor, and Live Coding."
---

# Unreal C++ gameplay

Use C++ when a Blueprint cannot express the type, cannot call the API, or will not meet the performance the user asked for. Keep behavior in a Blueprint when the Blueprint skill can do the task.

Follow `unreal-blueprints` for graph work. This skill covers the gameplay framework types those graphs extend.

## Where the code goes

Add the class to the game module that already owns that area, under `Source/<Module>/`. Do not create a module for one class.

Name the file after the class without the prefix (`ADoor` → `Door.h` / `Door.cpp`). Include `"<Class>.generated.h"` last in the header. Put `GENERATED_BODY()` in the class body. Export the class with the module's `*_API` macro.

Constructor: create default subobjects and defaults only. Do not use the world for gameplay there.

`BeginPlay`: the world exists. Start gameplay here.

Tick: set `PrimaryActorTick.bCanEverTick = false` in the constructor unless the class needs per-frame work.

Expose designer fields with `UPROPERTY(EditAnywhere, BlueprintReadWrite)` and entry points with `UFUNCTION(BlueprintCallable)` when Blueprints should use them.

## Which type

| Need | Type |
| --- | --- |
| Rules for the match. Server only. | `AGameModeBase` |
| Replicated match state every client reads | `AGameStateBase` |
| One player's input and possessed pawn | `APlayerController` |
| Replicated data about that player | `APlayerState` |
| The body that moves through the level | `APawn` or `ACharacter` |
| Behavior reused across actors | `UActorComponent` |
| Data that survives level travel | `UGameInstance` or a `UGameInstanceSubsystem` |

Do not put per-player UI or input on the GameMode. Do not store match score only on a local Pawn when other clients must see it. Put that on GameState.

## Compile

Live Coding can update bodies of functions that already exist. Discover the Live Coding toolset and call its compile tool, then read the diagnostics.

A new `UCLASS`, `UFUNCTION`, `UPROPERTY`, or reflected specifier needs Unreal Header Tool. Build the editor target with the engine Build script for this OS (`Build.bat`, `Mac/Build.sh`, or `Linux/Build.sh`): `<ProjectName>Editor`, the host platform, `Development`, and `-Project=` pointing at the `.uproject`. Relaunch the editor afterward so MCP auto-start comes back.

Do not claim the class exists until that compile has succeeded.
