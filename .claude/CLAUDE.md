##Rules:

Prefer Point to separate x and y coordinates
Prefer switches to polymorphism, and prefer data-driven to switches.
In coordinates, x/col always precedes y/row
Put enums inside a larger class not in their own file

rlib contains the game model and logic.  it knows nothing about displaying anything on the screen.
rgame renders the game on the screen and operates the UI and presentation.

Name variables consistenty and systematically even if it means sometimes refactoring and renaming.



## Backlog

Ticket source of truth is **Notion** — see [BACKLOG.md](../BACKLOG.md) for
the URL. At session start, when the user prompt is ambiguous ("what's
next?", "let's keep working"), consult the `Next up` view in Notion and
propose the top 1–3 tickets. At session end, propose status updates
(`Done` / `Blocked` / `Backlog`) for any tickets touched — always
propose, never auto-apply.

Do **not** create new files in `.claude/plans/` for routine work. The
plans directory is reserved for genuinely multi-session design
investigations. Routine work is tracked in Notion only.

The master release-readiness plan lives at
[`.claude/plans/what-if-we-wanted-toasty-duckling.md`](plans/what-if-we-wanted-toasty-duckling.md);
Notion tickets seed from it.

## Architecture map

### Modules
- `rlib` — pure model & logic, no rendering.
- `rgame` — rendering, UI, screen flow. Imports `rlib.model`; talks to `rlib.logic` only via `PlayController`.
- `desktop` — libGDX launcher. Runnable task is `:desktop:run`.

### rlib packages
- `model/` — `Tile`, `Point`, `Mob`, `Item`, `Level`, `World`, `Inventory`, `Buff`, `Perk`, `Surface`, `LogEvent`, `HallOfFameEntry`.
- `logic/` — `TurnSystem`, `MobSystem`, `LevelFactory`, `LevelSystem`, `ItemSystem`, `InventorySystem`, `LootSystem`, `BuffSystem`, `FireSystem`, `MobProgression`, `GameBalance`, `MobRegistry`, `ItemRegistry`, `ThemedRoomRegistry`, `Messages`, `EventLog`.
- `event/` — `GameEvent` sealed interface; one-way notifications from logic to renderer.
- `util/` — CSV parsing (`CsvTable`, `CsvRegistryStore`), `SeedCode`, `WorldDumpMain`.

### rgame packages
- `screen/` — `Rl2Game`, `PlayScreen` (main loop), `PlayController` (input→action). All navigable screens are V2-prefixed and live in `ui/v2/`.
- `input/` — `GameInput`, `CameraController`, `LookMode`.
- `world/render/` — `LevelRenderer` / `DefaultLevelRenderer`, `MobSprites`, `ItemSprites`, `TileSprites`, `SurfaceSprites`, `PortraitSprites`, `IconSprites`, `EffectStage`, `FxRenderer`, `Effect`, `BuffIcons`.
- `world/anim/` — `Animator` (drains `Level.events` per render frame), `MobAnimState`, `AnimQueue`.
- `ui/overlay/` — `TargetingOverlay`.
- `ui/skin/` — `UiScale`, `AnimationSpeed`, `UiFontScale`.
- `ui/v2/` — entire V2 UI layer (see below).
- `save/` — `SaveSystem`, `HallOfFame`, `HallOfFameStore`, `ArenaHallOfFame`.
- `persistence/` — `Persistence` save/load abstraction.

**Base classes / screens:**
- `V2Screen` — base for every navigable screen. Subclass overrides `buildLayout()` (called on show/resize), `drawBodyShape(UiCtx)` (shape pass), `drawBodyText(UiCtx)` (batch/text pass). Owns `List<Btn> buttons`, `Burger burger`, `BackBtn back`. Call `baseInput()` when chaining an `InputMultiplexer` for sub-popups.
- `V2Popup` interface — `isOpen()` + `renderSelf()`. Wired into `V2Stage` via `V2PopupActor`.
- `V2Stage` — z-ordered popup layers: `addToSubPopup()`, `addToBurger()`. Renders on top of the screen each frame.

**Screens (all in `ui/v2/`):**
`V2Title`, `V2Saves`, `V2CharacterSelect`, `V2Settings`, `V2HallOfFame`, `V2GameOver`, `V2Encyclopedia`, `V2Arena`, `V2ArenaSetup`, `V2Credits`, `V2Map`, `V2LevelInfo`, `V2CharacterStats`, `V2Inventory`, `V2Look`, `V2BuffInfo`.

**Popups (also in `ui/v2/`):**
`V2Log` — scrollable event log; `V2Hud` — in-play HUD overlay.

**Primitives:**
- `Btn` — rectangular button with shape + text passes. Set `btn.icon` (TextureRegion) to draw an icon instead of label.
- `Rect` — hit-test rectangle. `contains(vx,vy)`, `cx()`, `cy()`, `top()`, `right()`.
- `Scroller` — vertical scroll state. `scrollY() > 0` = content pushed up, older entries visible.
- `TextDraw` — `left/centre/right` draw helpers + `wrap(font, text, maxW, maxLines, List<String>)`.
- `UiCtx` — shared viewport, batch, shapes, fonts (`fontHeader`, `fontRegular`), `layout` (GlyphLayout), `unprojectX/Y`.
- `UIVars` — single source of truth for all V2 colour constants, alpha values, and chrome geometry. Replaces the deleted `Pal` / `UiColors`. Loaded once at startup from `assets/data/uivars.properties` for runtime overrides.
- `Window.drawShape(ctx, x, y, w, h)` — standard window chrome.
- `Edges.drawTriLine(...)` — three-line border primitive.

**Sub-popup pattern (InputMultiplexer):**
When a screen hosts a closable popup (e.g. log overlay), override `show()`:
```java
Gdx.input.setInputProcessor(new InputMultiplexer(popup.input(), baseInput()));
```
The popup's `input()` returns false when closed, so events fall through to the screen.

### Cross-cutting types
- `Tile` — terrain enum (FLOOR, WALL, CHASM, DOOR, STAIRS_*, etc.).
- `Point` — 2D coords; x/col before y/row.
- `Mob` — every actor: stats, AI mode, inventory, position. `Mob.CharacterClass` is the player-class enum.
- `Item` / `ItemDefinition` — instance vs. definition (the latter from CSV).
  - `Item.InventoryCategory` — WEAPON/OFFHAND/ARMOR/AMULET/WAND/ITEM/TOOL go in the Equipment bag group; GEM → Gems; FOOD → Food; POTION/BOMB/ORB → Items. Only POTION/BOMB/FOOD are stackable.
  - `Item.UseBehavior.POWERUP` — walk-over items; never enter the bag, consumed on tile step.
- `Level` — one floor: tile grid, mobs, items, surfaces, lighting cache, `events` queue.
- `GameEvent` — sealed; movement/attack/buff/damage notifications.
- `GameBalance` — static tunables loaded from `config.csv` (gamebalance-kind rows).
- `TurnSystem` — game-clock driver; `tick(Level)` advances 1 game tick.
- `Animator` — render-frame consumer of `Level.events`; owns lerp/ghost/FX state.
- `EventLog` — static process-wide rolling log (max 400 entries). `EventLog.all()` oldest-first. Cleared on new game. Used by `V2Log` and death-message capture.
- `LogEvent` — single log entry: `text`, `priority` (HIGH/LOW), `involvesPlayer`.
- `Messages` — factory for all `LogEvent` instances. Add new message types here.
- `InventorySystem` — all bag/equip mutations (add, remove, equip, unequip). `bagLimitFor(InventoryCategory)` returns the slot cap for a category's bag group.
- `HallOfFameEntry` — one run record: `charClass`, `score`, `depth`, `equipment`, `deathMessage`, `timestampMillis`. Serialised via libGDX reflective JSON; new fields need a default value.

### Entry points
- `desktop/.../DesktopLauncher.java` → `main()` → `new Rl2Game(persistence)`.
- `rgame/.../Rl2Game.java#create()` — loads CSV/properties, sets `TitleScreen`.
- `PlayScreen.render()` — drives `TurnSystem.tick()` then `Animator.render()`.
- Input: `GameInput` → `PlayController.<action>()` mutates `Level`, appends events.

### Data files (`assets/data/`)
- `config.csv` — combat, progression, level dims, mob caps, hunger (gamebalance rows), plus animation/ui/other config rows.
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
- **Windows are either lists OR info, never both.** A list shows many items so the user can pick one; an info window shows the details of one thing. Tapping a list entry replaces the list with the info window for that entry; the info window's back button returns to the list.
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