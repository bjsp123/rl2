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

## UI principles

### Structure
- **Burger menu is always present**, top-right corner, every screen. It always opens to the same three destinations: title screen, settings, encyclopedia.
- **Non-game screens are vertical lists of large buttons.** No dense layouts, no side-by-side panels. One column, one button per row, big tap targets. Title, save list, hall of fame, settings, credits, arena setup all follow this shape.
- **Every window is fully modal.** When a popup is open, only that popup receives input — the world, the HUD, and any windows behind it are inert. Stacked popups are forbidden; close-then-open or replace-in-place.
- **Windows are either lists OR info, never both.** A list shows many items so the user can pick one; an info window shows the details of one thing. Tapping a list entry replaces the list with the info window for that entry; the info window's back button returns to the list. The encyclopedia today violates this (list + detail side-by-side) and needs to be split.
- **The map counts as a "list" too.** The whole UX is a single pattern: user picks from a grid, a list, or the map → next screen is an info window about whatever was picked.
- **Lists of "a few" items → vertical button list** (tabs of a popup, menu screens — column of full-width buttons).
- **Lists of "many" items → grid** (inventory bag, encyclopedia entries — uniform cells laid out in a grid).
- **Minimize window-tree depth.** A user shouldn't need three taps to do a one-tap action. Inline destructive controls where they read clearly — e.g. a delete glyph rendered on top of each row in the saved-game list (not "tap row → confirm dialog → delete"). The confirmation lives at the action site, not behind another screen.

### Visual style
- **Chunky.** Big fonts, big tap targets, integer pixel positions, nearest-neighbour filtering. Adopt the proportions of the user-supplied screenshots in [assets/scratch/shots/](assets/scratch/shots/) — thin single-line gray slot cells, manila-folder tabs at the bottom of modal panels, flat HUD strip at the bottom of the screen.
- **Few nested or multi-part visuals.** A button is a button — not a Button containing a Container containing a Frame containing a Label inside a Stack. When tempted to layer chrome, ask: "could this be one drawable instead?". Replace 9-patch frames with flat fills + 1-px outline where reasonable.
- **Where the screenshot reference doesn't have an analog**, deviate consciously. We have much more information to surface for mobs and items than SPD does — when an info window's content overflows, wrap the body in a scrolling panel (`ScrollHinted`) rather than splitting it into yet another window.

### Settings as the granular-options bucket
- **Settings holds every granular toggle and preference.** If a control is "do this less often" / "show this kind of thing" / "tweak this number", it lives in Settings, not on the HUD. Examples: the log filter buttons (log on/off, low-priority, non-player, expand) currently in the HUD all belong under Settings.
- **Settings itself is compact + tabbed.** Tabs for Display, Gameplay, Log, Audio, etc., each tab a vertical list of toggles / sliders / dropdowns.