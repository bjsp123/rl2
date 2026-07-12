##Rules:

Prefer Point to separate x and y coordinates
Prefer switches to polymorphism, and prefer data-driven to switches.
In coordinates, x/col always precedes y/row
Put enums inside a larger class not in their own file

rlib contains the game model and logic.  it knows nothing about displaying anything on the screen.
rgame renders the game on the screen and operates the UI and presentation.

Name variables consistenty and systematically even if it means sometimes refactoring and renaming.

Classify items and mobs by their stats / buffs / behavior fields, never by
name or type string (`isHealingItem` reads `useBehavior`/`appliesBuff`, not
`"HEALING_POTION".equals(type)`). This is a project-wide rule.

"Is this the player?" is `Mob.isPlayer`, never `behavior == PLAYER`.
`behavior` encodes control only (human input vs AI turn driver); autoplay
flips the player to `SMART`, so behavior-based identity checks are bugs.

Three time domains, encoded in names — never mix them:
| Domain | Method suffix | Param | Constant suffix |
|---|---|---|---|
| Game ticks (in-game time; same currency as `Mob.moveCost`) | `*GameTicks(level, gameTicks)` | `gameTicks` | `_TICKS` |
| Game turns (once per game-time advancement) | `*PerTurn(level)` | — | — |
| Real time (per render frame, runs while paused) | `*RealTime(level, dtMs)` | `dtMs` | `_MS` |
Javadoc for any such method leads with its cadence.

CSV parsers must be defensive: the data files in `assets/data/` are
hand-edited, so a bad enum/number cell degrades gracefully (skip / default +
`[csv]` stderr warning), never throws and aborts the load. Quote any CSV
value containing a comma (RFC-4180) — `CsvTable` truncates unquoted commas;
never hack the parser to tolerate them.

## Workflow rules

- Commit directly to `main`. No feature branches — solo project, explicit
  owner preference.
- Never add a `Co-Authored-By: Claude ...` trailer to commits.
- Do not build `:android:*` or web targets as implicit verification; the
  owner opts in explicitly. Desktop compile + tests are the default check.
- The runnable app task is `:desktop:run` (rgame is just the core library;
  the launcher lives in `desktop`).

## Invariants & recurring regressions

**Ranged attacks resolve before the defender can act.** Thrown items, wand
missiles/rays, and innate mob shots lock their impact tile at fire time
(`firstMobBlocking`), bill the action cost, and queue the mutation via
`MobSystem.queuePendingImpact`; the `pendingImpactCount` gate plus the
drain at the top of `MobAi.processAllAiTurns` guarantee it resolves before
any other mob's brain runs — gameplay-equivalent to synchronous. The rgame
`Animator` arc-completion callbacks are visual-only; routing the *gameplay*
mutation through the Animator "reads clean" and has been reintroduced and
re-fixed multiple times. If an `apply*Impact` is reachable only from
`Animator`, that's the regression. Invariant: "the attacker must complete
the attack and deal damage before the defender gets to move." Locked by
`rlib` tests `RangedShotLifecycleTest` / `ThrowAndWandLifecycleTest`.

**SmartAi's top level is a hardcoded priority tree, not a goal-score race.**
Branch order is fixed (explored→descend / fight-or-escape / heal / pickup /
seek / explore); only *within* a branch do utility scores pick the concrete
action. Never add top-level scores, branch-boost overrides, or short-circuit
reroutes — the owner rejected goal-score selection repeatedly. If a branch's
action can't execute, fall through to `ActionWait` and let the wait-streak
escape break the deadlock.

**Effects layering (rgame):** `Effect.java` = game-named factories,
`EffectBuilder.java` = parameterised primitives. Never two `Effect.*`
methods differing only in defaults over the same primitive — merge them.
Composing several primitives into one game-named factory is fine.

**Door visuals:** `CRYSTAL_DOOR_OPEN` renders as a wood open door (the
crystal "shatters into" wood — deliberate, corrected twice).
`ONETIME_DOOR` = crystal body + pink danger overlay; blocks non-player mobs
only and becomes `FLOOR` when the player crosses.

**Tile atlas comment:** the TILE ATLAS LAYOUT REFERENCE lives in
`TileSprites`' class javadoc — update it whenever terrain sprites move.



## Backlog

Ticket source of truth is **Notion** — see [BACKLOG.md](../BACKLOG.md) for
the URL. At session start, when the user prompt is ambiguous ("what's
next?", "let's keep working"), consult the `Next up` view in Notion and
propose the top 1–3 tickets. At session end, propose status updates
(`Done` / `Blocked` / `Backlog`) for any tickets touched — always
propose, never auto-apply.

Do **not** create new files in `.claude/plans/` for routine work. The
plans directory is reserved for genuinely multi-session design
investigations (it may not exist at all — that's normal). Routine work is
tracked in Notion only.

## Architecture map

### Modules
- `rlib` — pure model & logic, no rendering, no libGDX graphics.
- `rgame` — rendering, UI, screen flow. Imports `rlib.model`; talks to `rlib.logic` only via `PlayController`.
- `desktop` — libGDX desktop launcher. Runnable task is `:desktop:run`; also owns the shipping fat `jar` task.
- `rai` — headless automated-play module: the SMART player agent (`SmartAi`, `Decider`, `WorldState`, `action/Action*` library, `eval/*` scorers) plus autoplay/regression harness mains (`game/AutoplayGame`, `AutoplayRunMain`, `util/RegressionRunMain`, `SmartAgentRunMain`, `SmartArenaRankMain`). Tests here (`SmartAiSmokeTest`) also guard seed determinism.
- `android` — Android launcher/packaging (signing via `RL2_*` env vars).
- `web` — TeaVM browser build (`:web:buildWeb`); uses the `backend/supabase` cloud-save backend (see `backend/supabase/README.md`).

### rlib packages
- `model/` — `Tile`, `Point`, `Mob`, `Item`, `Level`, `World`, `WorldTopology`, `Inventory`, `Buff`, `Perk`, `StatBlock`, `MinMax`, `LogEvent`, `HallOfFameEntry`, `RunStats`, `DoorBehavior`, `GemSpecies`, `TileQuery`.
- `logic/` — the systems. Highlights: `TurnSystem` (game clock), `MobSystem` (actions/combat; largest file) with satellites `MobAi`, `MobBrains`, `MobQueries`, `MobStats`, `MobVisibility`, `MobHooks`, `MobFactory`, `MobDefinition`, `MobProgression`; `LevelFactory` + `LevelFactoryPopulate`/`Special`/`ThemedRooms`/`Utils` and `ThemedRoomDefinition`/`Painter`/`Populator`; `ItemSystem`, `ItemFactory`, `ItemGenerator`, `ItemStats`, `ItemNames`, `ItemDefinition`; `InventorySystem`, `LootSystem`, `BuffSystem`, `FireSystem`, `CloudSystem`, `SurfaceSystem`, `VegetationSystem`; `BrandSystem`/`BrandDefinition`, `GemSystem`/`GemDefinition`/`GemRecipe`/`RecipeSystem`; `GameBalance`, `Registries` (CSV-backed definition registries), `TextCatalog`, `Messages`, `EventLog`, `Pathfinder`, `ShadowCaster`, `AutoExplore`, `CombatArena` (headless arena used by tests/tools).
- `event/` — `GameEvent` sealed interface; one-way notifications from logic to renderer.
- `util/` — CSV parsing (`CsvTable`, `CsvRegistryStore`), `SeedCode`, `SimRng`, `Fmt`, `ArenaHarness` (headless data-loading bootstrap used by tests and all diagnostic mains), and the diagnostic `*Main` entry points (see docs/diagnostics.md).

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
- `config.csv` — combat, progression, level dims, mob caps (gamebalance rows), plus animation/ui/other config rows.
- `mobs.csv` — mob species: stats, AI behavior, abilities, sprite coords. Consumed by both `Registries` (rlib) and `MobSprites` (rgame).
- `items.csv` — item defs: slot, material, stats, special behaviors, scaling.
- `themedrooms.csv` — procedural room templates for `LevelFactory`.
- `brands.csv`, `gems.csv`, `recipes.csv` — weapon brands, gem species, gem-hearth crafting recipes.
- `strings.csv`, `help.csv`, `tip.csv` — UI text, encyclopedia/help copy, loading-screen tips (`TextCatalog`).
- `sounds.csv` — sound-effect table.
- `uivars.properties` — runtime overrides for `UIVars` colours/geometry.

All CSVs are hand-edited; see the defensive-parsing rule at the top of this file. Data validity is guarded by `rlib` tests (`RegistryDataTest`, `ConfigCsvTest`, `StringsCatalogTest`) — run `:rlib:test` after any data edit.

## Verification ladder

Run these in order after a change; stop at the rung that matches the blast
radius (see also `docs/diagnostics.md`):

1. **Compile** — `./gradlew :rlib:compileJava :rgame:compileJava :rai:compileJava`.
2. **Tests** — `./gradlew test` (runs rlib + rai + rgame test suites; JUnit 5,
   data-driven tests load the real `assets/data` CSVs headlessly via
   `ArenaHarness`). Required after any rlib/rai change or CSV data edit.
3. **AI regression sweep** (balance/AI/combat changes) —
   `./gradlew :rai:regression [--args=N]`: SMART agent × classes ×
   difficulty levels. **Interpretation matters:** wins are RARE (~0–1 per
   45-run sweep); zero wins is NOT by itself a regression. Compare average
   depth and the HP-lost-by-mechanism/attacker tables against a baseline
   run of `main`. `:rai:autoplay` always starts at character level 1 and
   is ~0% wins by design.
4. **Run the game** — `./gradlew :desktop:run` and exercise the changed flow
   by playing. The only rung that covers rendering/UI/input.

Never build `:android:*` / `:web:*` as implicit verification (owner opt-in
only). CI (`.github/workflows/ci.yml`) runs rungs 1–2 on every push.

### Module boundary
- rlib → rgame: `Level.events` (List<GameEvent>) appended during tick, drained once by `Animator` before next frame. No listeners/callbacks.
- rgame → rlib: direct calls through `PlayController` into `logic/` systems.
- Both modules read the same CSVs at startup.

## UI principles

### Structure
- **Burger menu is always present**, top-right corner, every screen, and every burger gets its items from the single canonical populator (`BurgerMenu.populateStandard`). It always offers Settings and Encyclopedia; in a run it adds Level Info, Map, and Log; it ends with Main Menu — except the title screen (which IS the main menu) shows Credits instead. Owners differ only in how a destination opens (screens push screens, the HUD opens popups), never in the item list.
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
- **Where the screenshot reference doesn't have an analog**, deviate consciously. We have much more information to surface for mobs and items than SPD does — when an info window's content overflows, wrap the body in a scrolling panel (`ScrollBand` + `Scroller`) rather than splitting it into yet another window.

### Settings as the granular-options bucket
- **Settings holds every granular toggle and preference.** If a control is "do this less often" / "show this kind of thing" / "tweak this number", it lives in Settings, not on the HUD. Examples: the log filter buttons (log on/off, low-priority, non-player, expand) currently in the HUD all belong under Settings.
- **Settings itself is compact + tabbed.** Tabs for Display, Gameplay, Log, Audio, etc., each tab a vertical list of toggles / sliders / dropdowns.