# Handoff: rl2 UI Redesign

## Overview

A complete UI redesign for **rl2**, a turn-based fantasy roguelike built on
libgdx and primarily targeting Android phones in portrait orientation.

Covers 10 screens that together form the entire shell of the game:

1. Main Menu (title screen)
2. Saved Games (save slot picker)
3. Class Select (new game flow)
4. Hall of Fame (run leaderboard + identified items)
5. Arena (CPU-vs-CPU battle configurator)
6. Settings (Controls / Display / Gameplay tabs)
7. Credits
8. Pause Menu (modal overlay, available from anywhere)
9. In-Game HUD (status, dungeon viewport, D-pad, action grid)
10. Character / Inventory (stats, equipment, item grid, detail panel)

The redesign fixes layout bugs in the existing UI: back buttons that overlap
panel borders, hamburger buttons that crowd screen titles, segmented
controls that wrap into other UI, and the original Arena/Hall-of-Fame
layouts that were unclear or visually flat.

## About the Design Files

The files in this bundle are **design references created in HTML** — a
React + JSX prototype that demonstrates the intended look, layout,
spacing, typography, color, and interaction behavior of every screen.

**Do not ship the HTML.** The target codebase is a **libgdx Java**
application using Scene2D + scene2d.ui. The task is to **recreate these
designs in libgdx**, using its established patterns (`Stage`, `Table`,
`Skin`, `NinePatchDrawable`, `BitmapFont`, `TextureAtlas`).

A detailed Java/libgdx implementation guide lives at
**`LIBGDX_HANDOFF.md`** in this bundle — start there. It maps every HTML
component to its Scene2D counterpart, provides a starter `Skin` JSON, a
reusable `RLScreen` base class, and worked code examples.

## Fidelity

**High-fidelity.** The HTML prototype is pixel-accurate for:

- Final color values (warm wood brown + parchment + gold accents)
- Final type system (Press Start 2P for logo, Silkscreen for buttons /
  headers, VT323 for body / stats)
- Final layout, spacing, and hierarchy on every screen
- Bevel system (3-step pixel border on every panel and button)
- Interactive states (hover, pressed, selected/active, disabled)
- All copy and microcopy

Class portraits and dungeon tiles are **placeholders** — the existing
sprite assets in the libgdx codebase should be used instead. Placeholder
treatment is noted in the per-screen sections below.

## Screens / Views

All screens use a **390 × 780** logical viewport (Android portrait phone,
~16:9 ratio matching the original screenshots). Use `FitViewport(390,
780)` and scale up.

Every meta screen (everything except the Main Menu and Game HUD) uses
the same skeleton:

```
┌──────────────────────────────┐
│  AppBar  [title]    [≡ menu] │  48px high
├──────────────────────────────┤
│  ┌────────────────────────┐  │  panel: 10px margin sides,
│  │                        │  │  3-step bevel border,
│  │   scrolling content    │  │  12px inner padding,
│  │                        │  │  80px bottom space for footer
│  └────────────────────────┘  │
│  [◀ back]      [action btns] │  ~60px footer w/ fade
└──────────────────────────────┘
```

### 1. Main Menu (`MainMenu`)

- **Purpose:** Entry point. One-tap continue, plus access to every other
  screen.
- **Layout:** Centered logo ("rl2" in Press Start 2P 48 gold with
  layered drop-shadows of `bevelDk` + `redDk` offset 4/8px) → tagline →
  **Continue card** (gold-bordered, shows class portrait + name + level +
  depth + play time, taps go straight into the HUD) → 6 stacked menu
  buttons (Saved Games, Hall of Fame, Arena, Settings, Credits, Quit) →
  version footer.
- **Notes:** Hamburger menu **omitted** here since the main menu IS the
  menu. The continue card is only shown when a last-played save exists.

### 2. Saved Games (`SavedGames`)

- **Purpose:** Pick a save to load, or start a new run.
- **Layout:** Title "Saved Games" / `≡` menu. List of save rows: class
  portrait (48×48 framed) + name + level + depth + play time + HP
  mini-bar (green > 30%, red below) + red `X` delete button (separated
  by a darker red bevel so it's clearly destructive). Footer: back
  button (left) + "+ New Game" button.
- **Empty state:** Centered text + a single "Start a New Run" CTA.

### 3. Class Select (`ClassSelect`)

- **Purpose:** Pick class for a new run; see stats + starting gear +
  perks.
- **Layout:** Title "New Game" + `≡`. Tab strip of 4 classes (each tab
  = portrait + name, selected = gold bevel + gold text). Main area:
  class name (gold), large portrait (132×132 framed), blurb (one
  sentence), stat rows (HP / Acc / Eva / Atk / Armor — dotted leader
  lines between label and value), Gear list, Perks list (with gold star
  bullets). Footer: back (left) + green PLAY button (right). **Fixes the
  original overlap bug** by giving the footer a fixed gradient strip
  that the panel content stops above.
- **Classes:** Rogue, Warrior, Mage, Ranger. Stats are placeholder; use
  the game's actual class data.

### 4. Hall of Fame (`HallOfFame`)

- **Purpose:** Record of best runs + bestiary of identified items.
- **Layout:** Title + `≡`. Two tabs: **Runs** | **Bestiary**.
  - **Runs tab:** Personal-best banner card (gold bevel, large depth
    number on the right) → 3-cell totals strip (Runs / Kills / Deaths) →
    ordered list of past runs. Top 3 entries get gold/silver/bronze rank
    badges and a gold-bevel card; rest get plain bevel. Each row: rank
    badge, class portrait, name + class + level, cause-of-death (with
    skull glyph), depth number on the right.
  - **Bestiary tab:** Header with "n / N IDENTIFIED" count. Each entry:
    `?` glyph in a frame + item name (or "???" if unidentified) +
    times-used count + green check if identified. Unidentified entries
    are dimmed to 55% opacity.
- **Fix for original:** Death descriptions no longer break across the
  card. Depth is the headline metric (the achievement in a roguelike).

### 5. Arena (`Arena`)

- **Purpose:** Configure a CPU-vs-CPU duel between two teams of fighters.
  Watch and wager.
- **Layout:** Title "Arena" + `≡` + subtitle "CPU VS CPU · WATCH &
  WAGER". Two team cards stacked, divided by a VS banner that doubles
  as an odds bar.
  - **Team card:** Bevel color = team color (gold for A, red for B).
    Header row: TEAM tag + a mini-portrait stack (shows count visually)
    + "× count · L level" summary. Carousel: `<` button | portrait +
    name + kind ("class" / "mob") + "i/N" indicator | `>` button.
    Below: Level segmented picker (1 / 5 / 10 / 15) and Count picker (1
    / 3 / 5 / 8).
  - **VS divider:** Horizontal bar split gold/red proportional to a
    cheap level×count heuristic. Centered VS pill (Press Start 2P 14
    red). Caption row showing "A · X%" and "Y% · B".
- **Footer:** back + Hall of Fame + green ▶ FIGHT button.
- **Fighters:** 4 classes + 7 mob types in the prototype — replace with
  the actual roster.
- **Fix for original:** Footer buttons no longer overlap each other or
  the panel border. The VS bar gives instant visual feedback for team
  balance.

### 6. Settings (`Settings`)

- **Purpose:** Configure display, controls, and gameplay.
- **Layout:** Title + `≡`. Three tabs: **CONTROLS** | **DISPLAY** |
  **GAMEPLAY**. Each setting row uses the same shape: gold uppercase
  label (Silkscreen 11) + optional sub-description + control.
- **Controls tab:** Handedness (L/R segmented), Joystick style (Fixed /
  Floating / Swipe / D-Pad), Tap-to-move toggle, Confirm-attacks
  toggle, Haptics toggle, Music volume meter, SFX volume meter.
- **Display tab:** UI Scale (1 / 1.5 / 2 / 2.5 / 3 / 3.5 x), UI Font
  Size (0.75 / 1 / 1.5 / 2 x), Mob Outline Width (0 / 0.3 / 0.6 / 1 /
  1.5 / 2), Mob Outline Darkness (0.3 / 0.55 / 0.75 / 1), Outline
  Smoothing (Smooth / Pixel).
- **Gameplay tab:** Animation Speed (Slow / Normal / Fast / Instant),
  Auto-switch weapon toggle, Language (EN / ES / FR / DE / JP), Danger
  Zone with red buttons (Reset Tutorial, Clear All Saves).
- **Fix for original:** Segmented pickers wrap consistently. Tabs use
  text labels (not just icons) for clarity. Back button has its own
  footer area.

### 7. Credits (`Credits`)

- **Purpose:** Show attribution.
- **Layout:** Title + `≡`. Centered "rl2" mini-logo + version line.
  Section list (GAME BY / PROGRAMMING / ART / AUDIO / WRITING / SPECIAL
  THANKS / BUILT WITH), each with a gold caps header, a 60px dotted
  divider underline, and centered VT323 18px lines. Footer: heart
  glyph + "made with love & coffee".
- **Content is placeholder** — replace with real credits.

### 8. Pause Menu (`PauseMenu`)

- **Purpose:** Modal overlay reachable from any screen via `≡`.
- **Layout:** Full-screen 70% black scrim. Centered panel (max 320px)
  with title "Paused" + close `×`. Buttons: Resume, Character, Settings,
  Hall of Fame, "Save & Quit to Title" (red).

### 9. In-Game HUD (`GameHUD`)

- **Purpose:** The actual play screen.
- **Layout — top to bottom:**
  1. **Status bar** (panel-colored): 38×38 portrait, name + level/class,
     HP bar with heart glyph (green / red < 30%), XP bar with star glyph.
     Hamburger right.
  2. **Meta strip** (panel2-colored): DEPTH 06 (gold) · TURN 1284 ·
     spacer · coin glyph + gold count · status-effect badges
     (violet-bordered glyph for buffs, red for debuffs).
  3. **Dungeon viewport** (large, padded). Uses recessed bevel. Pixel
     tiles with ASCII glyphs as a placeholder for the real tilemap
     (`@` = player gold, `g` = goblin green, `r` = rat orange, etc.).
     FOV applied via 25% opacity outside a Manhattan-distance radius.
     Top-right mini-map (56×56 panel2-bevelled).
  4. **Combat log** (3 lines visible, recessed dark `#0a0805` panel):
     green for good ("you hit for 7"), red for warning ("a rat
     appears!"), parchment for info. Older lines fade.
  5. **Bottom action region** (panel-colored, 8px top + bottom + sides
     padding): D-pad on the **left** (3×3 grid of 38×38 buttons, center
     = idle indicator dot), 2 rows of 3 **action buttons** on the
     **right** (Attack hot/gold-bevel, Use, Stairs, Defend, Magic,
     Inventory). Each action button is 44×44, has glyph + caption, and
     can show a red badge (e.g. potion count). Tap Inventory to go to
     screen 10.
- **Replace the placeholder dungeon with the existing libgdx tile
  renderer.** The HUD is a separate `Stage` overlay; the world stays in
  the existing world stage.

### 10. Character / Inventory (`Inventory`)

- **Purpose:** Manage equipment + items + see stats.
- **Layout:** Title "Character" + `≡`. Sections:
  1. **Identity strip** (panel2 bevel): 54×54 portrait, name + level/class,
     HP meter (with text label "HP 18/25"), XP meter ("XP 320/500").
  2. **Stat row** (grid of 4): STR / DEX / INT / WIL with caps label +
     big Press Start 2P number.
  3. **Equipment grid** (6 cells, aspect-ratio 1): HEAD / BODY / MAIN /
     OFF / RING / RING. Filled slots show the item glyph; empty show
     dim caps label.
  4. **Inventory grid** (4×4, 16 slots): each cell shows item glyph,
     optional green "equipped" dot top-left, optional violet `?`
     overlay for unidentified items, optional ×N quantity bottom-right,
     optional red 45° hatch for broken items. Selected cell gets gold
     bevel.
  5. **Item detail panel** (only when an item is selected): item icon +
     name (gold) + tier ("Common" / "Rare" / "Epic") + state badges +
     description.
- **Footer:** back + "Equip / Use" (green primary) + "Unequip" + "Drop"
  (red, fixed width).

## Interactions & Behavior

- **All buttons** have three visual states: `up` (default raised bevel),
  `down` (inverted bevel + translateY 1px), `hover` (lighter face). In
  libgdx use `TextButtonStyle.up` / `down` / `over`.
- **Tabs and segmented controls:** selected state = gold inner bevel +
  gold text + `panel2` darker fill. Unselected = standard `button`
  fill.
- **Navigation:** Every screen has a hamburger top-right that opens the
  Pause overlay (or returns to title from in-game). Every meta screen
  has a back button bottom-left that returns one level up.
- **Continue card** (Main Menu): tap routes directly into the HUD with
  the last save loaded.
- **Save row** (Saved Games): tap row → HUD with that save. Tap red `X` →
  delete (in production: show a confirm dialog if `confirm dangerous
  actions` is on).
- **Class tabs** (Class Select): tap a tab to swap the body content.
  PLAY routes to the HUD with a fresh run.
- **Settings:** Every control mutates local state immediately and
  persists to `Preferences`. UI Scale changes should update the
  `FitViewport` size live, not on restart.
- **Arena carousel:** `<` and `>` wrap around. Mini-portrait stack and
  odds bar update on every change.
- **Inventory cell:** tap an item → it becomes selected → footer
  actions activate. Equipped items can be unequipped; non-equipped
  equippable items show "Equip / Use".
- **Pause overlay:** scrim is non-dismissible by tapping outside (avoids
  accidental dismissals during long sessions); close `×` and Resume
  both dismiss.
- **Press feedback:** in libgdx, pair the bevel-inversion with a 1px
  vertical translate via `actor.setY(actor.getY() - 1)` on press, or
  with the `pressedOffsetX/Y` field on `TextButtonStyle`.

## State Management

Per-screen state (use the equivalent in libgdx — local fields on the
Screen class, or a shared `GameState` singleton):

- **Main Menu:** `lastSave: SaveSlot?` — sourced from `Preferences`.
- **Saved Games:** `saves: List<SaveSlot>` with `onDelete(id)`.
- **Class Select:** `selectedIndex: int` (0..3).
- **Hall of Fame:** `tab: "runs" | "scrolls"`, plus read-only run list
  and bestiary state from `Preferences`/persistence layer.
- **Arena:** `teamA: { fighter, level, count }`, `teamB: { ... }`. Odds
  derived from `level * count`.
- **Settings:** All settings keys held in `Preferences`:
  `uiScale, fontSize, outlineW, outlineDark, outlineSmooth, music, sfx,
   haptics, leftHand, joystickStyle, tapMove, confirmAttack, autoSwitch,
   animSpeed, lang`. Each picker reads on open + writes on change.
- **Pause:** `from: ScreenId` (so "Resume" returns to the right screen).
- **HUD:** Live game state from the existing dungeon model. The HUD is
  a view — no state of its own except UI flicker timers.
- **Inventory:** `selectedSlot: int`. Items come from the player model.

## Design Tokens

### Color

| Token        | Hex        | Use                                |
|--------------|------------|------------------------------------|
| `bg`         | `#13100d`  | screen background outside panel    |
| `panel`      | `#3a2a24`  | raised panel fill                  |
| `panel2`     | `#2c1f1a`  | recessed panel / dark cards        |
| `button`     | `#4a3328`  | default button face                |
| `buttonHi`   | `#5a4032`  | button hover                       |
| `buttonLo`   | `#2e2018`  | button pressed                     |
| `bevelDk`    | `#1a120e`  | outer pixel border (dark)          |
| `bevelMd`    | `#5a4a3a`  | inner shadow line                  |
| `bevelLt`    | `#8a7565`  | top-light highlight                |
| `text`       | `#f0e6d2`  | parchment white — primary text     |
| `textDim`    | `#a89683`  | secondary text                     |
| `textFaint`  | `#6b5a4a`  | very dim / version                 |
| `gold`       | `#f0c674`  | titles, selected state, currency   |
| `goldDk`     | `#a87a36`  | gold shadow / detail               |
| `red`        | `#d65a4a`  | HP-low, danger, delete             |
| `redDk`      | `#7a2a20`  | red shadow / destructive bg        |
| `green`      | `#6db35a`  | HP-good, primary CTA, success      |
| `blue`       | `#5a8cc4`  | mana, water tiles                  |
| `violet`     | `#a675d6`  | magic, unidentified, status buff   |

### Typography

| Family          | License/Source | Use                                           |
|-----------------|----------------|-----------------------------------------------|
| Press Start 2P  | Google Fonts   | Logo, large depth numbers (32, 48)            |
| Silkscreen      | Google Fonts   | Buttons, headers, tabs, caps labels (8–22)    |
| VT323           | Google Fonts   | Body, stats, combat log, dialogue (14, 18, 22)|

Bake each at the exact target on-screen pixel size with **no
anti-aliasing**. Letter spacing: Silkscreen 0.5–2px depending on size;
VT323 default; Press Start 2P 1–4px.

### Spacing & sizing

| Token            | Value | Use                                          |
|------------------|-------|----------------------------------------------|
| `GAP_XS`         | 4px   | tight grid gaps                              |
| `GAP_SM`         | 6px   | row/column gap                               |
| `GAP_MD`         | 10px  | card padding, larger gaps                    |
| `GAP_LG`         | 14px  | section breaks                               |
| `PAD_PANEL`      | 12px  | inner padding of bevelled panels             |
| `PAD_SCREEN`     | 10px  | margin from screen edge to panel             |
| `H_APPBAR`       | 48px  | top bar height                               |
| `H_FOOTER`       | 60px  | bottom safe area                             |
| `H_BTN`          | 44px  | default button height (min tap target)       |
| `H_BTN_LG`       | 56px  | hero button (Continue, PLAY)                 |
| `H_ICONBTN`      | 36px  | square icon button (hamburger, back, X)      |

### Bevel system

Every raised surface uses **three 1px lines** at its border:

```
outer:    bevelDk  (#1a120e)
inner:    bevelLt  (#8a7565)
deepest:  bevelDk  (#1a120e)
fill:     button / panel / panel2 depending on role
```

Recessed surfaces flip middle to `bevelMd` (`#5a4a3a`).
Selected state (tab active, picker active) replaces inner with `gold`.

### Glyph atlas (16×16, 1-bit)

`menu`, `back`, `close`, `gear`, `check`, `heart`, `skull`, `coin`,
`sword`, `shield`, `potion`, `star`, `down`, `plus`. All hand-drawn in
SVG with `shapeRendering="crispEdges"`. Re-export as 16×16 PNGs and pack
into `ui.atlas`. Recolor at runtime via `Image.setColor()`.

## Assets

- **Class portraits in the prototype are placeholders.** The libgdx
  build should use the existing pixel-art sprites for Rogue, Warrior,
  Mage, Ranger (and the mob roster for Arena Team B).
- **Dungeon tiles in the HUD are ASCII placeholders.** Replace with the
  existing tile renderer.
- **No image assets need to be copied from the prototype.** The icon
  glyphs are simple enough to redraw as PNGs at the target resolution.
- **Fonts:** Google Fonts (free, OFL). Download `.ttf` → bake `.fnt`
  with Hiero.

## Files

The prototype source (everything needed to view the design):

- `rl2 UI.html` — entry point
- `design-canvas.jsx` — pan/zoom canvas (review only; not part of the design)
- `src/shared.jsx` — palette constants, fonts, primitives
  (`PhoneShell`, `Panel`, `PixelButton`, `IconBtn`, `Glyph`,
  `ClassPortrait`, `ScreenTitle`, `StatRow`, `MeterBar`)
- `src/screens-menu.jsx` — Main Menu, Saved Games, Class Select, Pause
- `src/screens-meta.jsx` — Hall of Fame, Arena, Settings, Credits
- `src/screens-game.jsx` — Game HUD, Inventory
- `src/app.jsx` — design canvas wiring all 10 artboards

The libgdx implementation guide:

- **`LIBGDX_HANDOFF.md`** — read this first. Component mapping table,
  asset bake checklist, starter `Skin` JSON, reusable `RLScreen` base
  class, worked code examples for `MainMenuScreen` and the
  segmented-picker pattern, file-by-file mapping of HTML screens to
  Java screen classes.

## Suggested implementation order

1. Bake fonts + author the 5 9-patches + pack the icon atlas → `Skin` loads.
2. **Main Menu** first — proves the layout helper and bevel system.
3. **Settings** + **Saved Games** — proves segmented pickers and list rows.
4. **Hall of Fame** + **Arena** — layout-dense but pure `Table` work.
5. **HUD overlay** on top of the existing game `Stage`.
6. **Inventory** last (slot grid + selection is the most fiddly).
