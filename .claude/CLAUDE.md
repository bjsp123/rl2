Prefer Point to separate x and y coordinates
Prefer switches to polymorphism
In coordinates, x/col always precedes y/row
Put enums inside a larger class not in their own file

rlib contains the game model and logic.  it knows nothing about displaying anything on the screen.
rgame renders the game on the screen and operates the UI and presentation.

## Architecture map

### Modules
- `rlib` — pure model & logic, no rendering.
- `rgame` — rendering, UI, screen flow. Imports `rlib.model`; talks to `rlib.logic` only via `PlayController`.
- `desktop` — libGDX launcher. Runnable task is `:desktop:run`.

### rlib packages
- `model/` — `Tile`, `Point`, `Mob`, `Item`, `Level`, `World`, `Inventory`, `Buff`, `Perk`, `Surface`.
- `logic/` — `TurnSystem`, `MobSystem`, `LevelFactory`, `ItemSystem`, `InventorySystem`, `LootSystem`, `BuffSystem`, `FireSystem`, `GameBalance`, `MobRegistry`, `ItemRegistry`, `ThemedRoomRegistry`.
- `event/` — `GameEvent` sealed interface; one-way notifications from logic to renderer.
- `util/` — CSV parsing (`CsvTable`, `CsvRegistryStore`), `SeedCode`, `WorldDumpMain`.

### rgame packages
- `screen/` — `Rl2Game`, `PlayScreen` (main loop), `PlayController` (input→action), `TitleScreen`, `MenuScreen`, `ArenaScreen`, `SavesScreen`, `HallOfFameScreen`, `CharacterSelectScreen`.
- `input/` — `GameInput`, `CameraController`, `LookMode`.
- `world/render/` — `LevelRenderer` / `DefaultLevelRenderer`, `MobSprites`, `ItemSprites`, `TileSprites`, `SurfaceSprites`, `EffectStage`, `FxRenderer`, `Effect`.
- `world/anim/` — `Animator` (drains `Level.events` per render frame), `MobAnimState`, `AnimQueue`.
- `ui/hud/` — `ActionBar`, `HudRenderer`.
- `ui/overlay/` — `LookRenderer`, `TargetingOverlay`.
- `ui/popup/` — `InventoryRenderer`, `CraftingRenderer`, `CharacterStatsRenderer`, `EncyclopediaRenderer`.
- `ui/skin/` — `UiScale`, `UiPixelScale`, `UiStyleChoice`, `StoneUi`.
- `save/` — `SaveSystem`, `HallOfFame`, `HallOfFameStore`, `ArenaHallOfFame`.
- `persistence/` — `Persistence` save/load abstraction.

### Cross-cutting types
- `Tile` — terrain enum (FLOOR, WALL, CHASM, DOOR, STAIRS_*, etc.).
- `Point` — 2D coords; x/col before y/row.
- `Mob` — every actor: stats, AI mode, inventory, position.
- `Item` / `ItemDefinition` — instance vs. definition (the latter from CSV).
- `Level` — one floor: tile grid, mobs, items, surfaces, lighting cache, `events` queue.
- `GameEvent` — sealed; movement/attack/buff/damage notifications.
- `GameBalance` — static tunables loaded from `gamebalance.properties`.
- `TurnSystem` — game-clock driver; `tick(Level)` advances 1 game tick.
- `Animator` — render-frame consumer of `Level.events`; owns lerp/ghost/FX state.

### Entry points
- `desktop/.../DesktopLauncher.java` → `main()` → `new Rl2Game(persistence)`.
- `rgame/.../Rl2Game.java#create()` — loads CSV/properties, sets `TitleScreen`.
- `PlayScreen.render()` — drives `TurnSystem.tick()` then `Animator.render()`.
- Input: `GameInput` → `PlayController.<action>()` mutates `Level`, appends events.

### Data files (`assets/data/`)
- `gamebalance.properties` — combat, progression, level dims, mob caps, hunger.
- `mobs.csv` — mob species: stats, AI behavior, abilities, sprite coords. Consumed by both `MobRegistry` (rlib) and `MobSprites` (rgame).
- `items.csv` — item defs: slot, material, stats, special behaviors, scaling.
- `themedrooms.csv` — procedural room templates for `LevelFactory`.

### Module boundary
- rlib → rgame: `Level.events` (List<GameEvent>) appended during tick, drained once by `Animator` before next frame. No listeners/callbacks.
- rgame → rlib: direct calls through `PlayController` into `logic/` systems.
- Both modules read the same CSVs at startup.