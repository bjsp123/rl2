# rl2 · libgdx Handoff Notes

How to take the HTML mockups in `rl2 UI.html` and apply them to a libgdx
codebase. The short version: **the design is built entirely from things
libgdx already does well** — 9-patches for the bevels, `Skin` for styling,
`Table` for layout, `Stage` per screen. There is no React-only magic.

---

## 1. Mental model

Each HTML component in `src/shared.jsx` has a 1:1 libgdx counterpart:

| HTML component              | libgdx equivalent                                  |
|-----------------------------|----------------------------------------------------|
| `PhoneShell`                | `Stage` + `FitViewport(390, 780)`                   |
| `Panel` (3-step bevel)      | `Table` with `NinePatchDrawable` background        |
| `PixelButton`               | `TextButton` (with `up`/`down`/`over` 9-patches)   |
| `IconBtn`                   | `ImageButton` (or `TextButton` with no text)       |
| `SegmentedPicker`           | `ButtonGroup<TextButton>` in a horizontal `Table`  |
| `Toggle`                    | `CheckBox` (or custom `Button`)                    |
| `MeterBar` (HP, XP, volume) | `ProgressBar` with custom 9-patch knob/bg          |
| `ScreenLayout`              | A `Screen` class with a root `Table`               |
| `PauseMenu` overlay         | A `Window` (or second `Stage` on top)              |
| `Glyph` (pixel SVGs)        | `TextureRegion` from a `TextureAtlas`              |
| `ClassPortrait`             | `Image` pulling from your sprite atlas             |
| `Dungeon` (game map)        | Your existing tile renderer — unchanged            |

The HTML's job was to pin down **layout, spacing, hierarchy, type, color**.
None of that is React — it all serializes into a skin file + a few `Table`
calls.

---

## 2. Asset checklist — what to bake first

These are the assets you'll need before the skin can be wired up. None
of them are unique to the mockup — they're what any pixel-art libgdx
game needs.

### Fonts (use Hiero or BMFont to bake .fnt + .png)

| Mockup font     | Use case               | Sizes to bake       |
|-----------------|------------------------|---------------------|
| `Press Start 2P`| Logo, big depth numbers| 32, 48              |
| `Silkscreen`    | Buttons, headers, tabs | 12, 14, 18, 22      |
| `VT323`         | Body, stats, log       | 14, 18, 22          |

Bake with **no antialiasing** (pixel fonts), padding 1px, and pack into a
single atlas page if they fit.

### 9-patches (`.9.png`) — the bevel system

The whole UI is one 3-step bevel reused everywhere. Author **two**
9-patches and you cover 90% of the surface:

1. **`panel.9.png`** — raised panel
   - Pixel pattern at the border: `bevelDk(1px) → bevelLt(1px) → bevelDk(1px) → fill`
   - Fill: `#3a2a24` (`RL.panel`)
   - Stretch zone: middle 1px row/col
2. **`panel_inset.9.png`** — recessed panel (for the dungeon viewport, the meter track, recessed cards)
   - `bevelDk(1px) → bevelMd(1px) → bevelDk(1px) → fill`
   - Fill: `#2c1f1a` (`RL.panel2`)
3. **`button_up.9.png`** — same as `panel.9.png` but fill `#4a3328` (`RL.button`)
4. **`button_down.9.png`** — inverted bevel (light first, dark next), fill `#2e2018`
5. **`button_selected.9.png`** — gold middle line for active tab/picker state
   - `bevelDk(1px) → gold(1px) → bevelDk(1px) → fill #2c1f1a`

Stretch and content zones in each: stretch from pixel 4 to pixel `w-4`,
content zone with 6px inset. Open them in libgdx's bundled `Ninepatch
Editor` (`com.badlogic.gdx.tools.texturepacker.NinePatchEditor`) or just
hand-draw the black "stretch markers" on the border.

### Icon atlas

Pack the pixel glyphs (`menu`, `back`, `close`, `gear`, `check`, `heart`,
`skull`, `coin`, `sword`, `shield`, `potion`, `star`, `down`, `plus`)
into one `ui.atlas` with TexturePacker. They're tiny (16×16), all of
mine are 1-bit so they recolor cleanly via `Image.setColor()`.

### Sprite atlas (your existing one)

Class portraits (warrior, rogue, mage, ranger) and mob sprites
(goblin, dungeon mouse, lich…) — drop your existing assets in.

---

## 3. Color tokens — drop into a single class

```java
public final class Pal {
    public static final Color BG         = Color.valueOf("13100d");
    public static final Color PANEL      = Color.valueOf("3a2a24");
    public static final Color PANEL2     = Color.valueOf("2c1f1a");
    public static final Color BUTTON     = Color.valueOf("4a3328");
    public static final Color BUTTON_HI  = Color.valueOf("5a4032");
    public static final Color BUTTON_LO  = Color.valueOf("2e2018");

    public static final Color BEVEL_DK   = Color.valueOf("1a120e");
    public static final Color BEVEL_MD   = Color.valueOf("5a4a3a");
    public static final Color BEVEL_LT   = Color.valueOf("8a7565");

    public static final Color TEXT       = Color.valueOf("f0e6d2");
    public static final Color TEXT_DIM   = Color.valueOf("a89683");
    public static final Color TEXT_FAINT = Color.valueOf("6b5a4a");

    public static final Color GOLD       = Color.valueOf("f0c674");
    public static final Color GOLD_DK    = Color.valueOf("a87a36");
    public static final Color RED        = Color.valueOf("d65a4a");
    public static final Color RED_DK     = Color.valueOf("7a2a20");
    public static final Color GREEN      = Color.valueOf("6db35a");
    public static final Color BLUE       = Color.valueOf("5a8cc4");
    public static final Color VIOLET     = Color.valueOf("a675d6");

    private Pal() {}
}
```

Pair this with a `Skin` constants class for spacing tokens:

```java
public final class Sp {
    public static final int GAP_XS = 4, GAP_SM = 6, GAP_MD = 10, GAP_LG = 14;
    public static final int PAD_PANEL = 12, PAD_SCREEN = 10;
    public static final int H_APPBAR = 48, H_FOOTER = 60;
    public static final int H_BTN = 44, H_BTN_LG = 56, H_ICONBTN = 36;
}
```

---

## 4. Skin JSON skeleton

`ui/rl2.json` — the bones, fill in font/atlas names to match what you baked:

```json
{
  "com.badlogic.gdx.graphics.Color": {
    "gold":      { "hex": "f0c674" },
    "text":      { "hex": "f0e6d2" },
    "textDim":   { "hex": "a89683" },
    "textFaint": { "hex": "6b5a4a" },
    "red":       { "hex": "d65a4a" },
    "green":     { "hex": "6db35a" }
  },

  "com.badlogic.gdx.graphics.g2d.BitmapFont": {
    "logo-48":   { "file": "fonts/press-start-2p-48.fnt" },
    "px-18":     { "file": "fonts/silkscreen-18.fnt" },
    "px-14":     { "file": "fonts/silkscreen-14.fnt" },
    "px-11":     { "file": "fonts/silkscreen-11.fnt" },
    "body-18":   { "file": "fonts/vt323-18.fnt" },
    "body-22":   { "file": "fonts/vt323-22.fnt" }
  },

  "com.badlogic.gdx.scenes.scene2d.ui.TextButton$TextButtonStyle": {
    "default": {
      "font": "px-18", "fontColor": "text",
      "up":   "button_up",   "down": "button_down",
      "over": "button_up",   "checked": "button_selected", "checkedFontColor": "gold"
    },
    "danger": {
      "font": "px-18", "fontColor": "red",
      "up":   "button_danger_up", "down": "button_danger_down"
    },
    "primary": {
      "font": "px-22", "fontColor": "green",
      "up":   "button_primary_up", "down": "button_primary_down"
    },
    "segment": {
      "font": "px-14", "fontColor": "text",
      "up":   "button_up", "checked": "button_selected", "checkedFontColor": "gold"
    }
  },

  "com.badlogic.gdx.scenes.scene2d.ui.Label$LabelStyle": {
    "title-gold":   { "font": "px-18", "fontColor": "gold" },
    "header-gold":  { "font": "px-11", "fontColor": "gold" },
    "body":         { "font": "body-18", "fontColor": "text" },
    "body-dim":     { "font": "body-18", "fontColor": "textDim" },
    "stat-value":   { "font": "body-22", "fontColor": "text" }
  },

  "com.badlogic.gdx.scenes.scene2d.ui.ProgressBar$ProgressBarStyle": {
    "hp": {
      "background": "meter_bg",
      "knobBefore": "meter_green"
    }
  }
}
```

`button_up`/`button_down`/etc. are the 9-patch region names in your
`ui.atlas`. The 9-patch suffix `.9.png` makes libgdx auto-detect the
stretch markers.

---

## 5. Layout pattern — the `ScreenLayout` macro

Every meta screen (Saved Games, Hall of Fame, Arena, Settings, Credits)
uses the same skeleton: app bar at top, scrolling panel in the middle,
sticky footer at the bottom. Write this **once** as a helper:

```java
public abstract class RLScreen extends ScreenAdapter {
    protected final Stage stage;
    protected final Skin  skin;
    protected final Table appBar, content, footer;

    public RLScreen(Skin skin) {
        this.skin = skin;
        this.stage = new Stage(new FitViewport(390, 780));

        Table root = new Table();
        root.setFillParent(true);
        root.setBackground(skin.getDrawable("bg_tile"));   // tiled dot pattern

        appBar = new Table();
        appBar.add().expandX();                            // 44px placeholder for back symmetry
        Label title = new Label(getTitle(), skin, "title-gold");
        appBar.add(title).expandX().center();
        appBar.add(makeMenuButton()).right().size(36).pad(0, 0, 0, 12);

        Table panelHost = new Table();
        Table panel = new Table();
        panel.setBackground(skin.getDrawable("panel"));
        panel.pad(Sp.PAD_PANEL);
        content = panel;
        ScrollPane scroll = new ScrollPane(panel, skin);
        scroll.setFadeScrollBars(false);
        panelHost.add(scroll).expand().fill().pad(0, 10, 80, 10);

        footer = new Table();
        footer.bottom().pad(8, 12, 8, 12);

        Stack stack = new Stack();
        Table layered = new Table();
        layered.add(appBar).height(Sp.H_APPBAR).fillX().row();
        layered.add(panelHost).expand().fill();
        stack.add(layered);

        Table footerLayer = new Table();
        footerLayer.bottom().setFillParent(false);
        footerLayer.add(footer).expandX().fillX();
        stack.add(footerLayer);

        root.add(stack).expand().fill();
        stage.addActor(root);

        buildContent(content);   // subclass fills `content`
    }

    protected abstract String getTitle();
    protected abstract void buildContent(Table content);

    @Override public void render(float dt) {
        ScreenUtils.clear(Pal.BG);
        stage.act(dt); stage.draw();
    }
    @Override public void resize(int w, int h) { stage.getViewport().update(w, h, true); }
    @Override public void show() { Gdx.input.setInputProcessor(stage); }
    @Override public void dispose() { stage.dispose(); }
}
```

Every screen then becomes ~30 lines.

---

## 6. Worked example — Main Menu

```java
public class MainMenuScreen extends RLScreen {
    private final SaveSlot lastSave;

    public MainMenuScreen(Skin skin, SaveSlot lastSave) {
        super(skin);
        this.lastSave = lastSave;
    }

    @Override protected String getTitle() { return ""; }   // logo replaces title

    @Override protected void buildContent(Table c) {
        // Logo block
        Label logo = new Label("rl2", skin, "logo");        // Press Start 2P 48
        logo.setColor(Pal.GOLD);
        c.add(logo).padTop(24).padBottom(4).row();

        Label tag = new Label("A TURN-BASED ROGUELIKE", skin, "header-gold");
        tag.setColor(Pal.TEXT_DIM);
        c.add(tag).padBottom(18).row();

        // Continue card
        if (lastSave != null) {
            Table cont = makeContinueCard(lastSave);
            c.add(cont).fillX().padBottom(14).row();
        }

        // Stack of buttons — same .pad/.row pattern for every entry
        c.add(menuBtn("Saved Games",  () -> game.setScreen(new SavedGamesScreen(skin)))).fillX().height(Sp.H_BTN_LG).row();
        c.add(menuBtn("Hall of Fame", () -> game.setScreen(new HallOfFameScreen(skin)))).fillX().height(Sp.H_BTN_LG).padTop(8).row();
        c.add(menuBtn("Arena",        () -> game.setScreen(new ArenaScreen(skin)))).fillX().height(Sp.H_BTN_LG).padTop(8).row();
        c.add(menuBtn("Settings",     () -> game.setScreen(new SettingsScreen(skin)))).fillX().height(Sp.H_BTN_LG).padTop(8).row();
        c.add(menuBtn("Credits",      () -> game.setScreen(new CreditsScreen(skin)))).fillX().height(Sp.H_BTN_LG).padTop(8).row();
        c.add(menuBtn("Quit", "danger", () -> Gdx.app.exit())).fillX().height(Sp.H_BTN_LG).padTop(8).row();
    }

    private TextButton menuBtn(String label, Runnable onClick) {
        return menuBtn(label, "default", onClick);
    }
    private TextButton menuBtn(String label, String style, Runnable onClick) {
        TextButton b = new TextButton(label, skin, style);
        b.addListener(new ClickListener() {
            @Override public void clicked(InputEvent e, float x, float y) { onClick.run(); }
        });
        return b;
    }
}
```

---

## 7. Worked example — Settings (the segmented-picker pattern)

The segmented picker is just a `ButtonGroup` of toggle-styled `TextButton`s.

```java
private Table segmented(String[] labels, Object[] values, Object selected, Consumer<Object> onPick) {
    Table row = new Table();
    ButtonGroup<TextButton> group = new ButtonGroup<>();
    group.setMaxCheckCount(1);
    group.setMinCheckCount(1);
    for (int i = 0; i < labels.length; i++) {
        Object v = values[i];
        TextButton b = new TextButton(labels[i], skin, "segment");
        b.setProgrammaticChangeEvents(false);
        if (v.equals(selected)) b.setChecked(true);
        b.addListener(new ClickListener() {
            @Override public void clicked(InputEvent e, float x, float y) { onPick.accept(v); }
        });
        group.add(b);
        row.add(b).height(36).minWidth(44).pad(0, 0, 0, 6);
    }
    return row;
}
```

Use it for **every** option set (UI scale, font size, outline width,
language, level, count) — identical look across the whole settings
screen.

---

## 8. Mapping each mockup screen to a `Screen` class

```
MainMenu.html       → MainMenuScreen.java
SavedGames.html     → SavedGamesScreen.java       (Table of save-row Cells)
ClassSelect.html    → NewGameScreen.java          (carousel via ButtonGroup of class portraits)
HallOfFame.html     → HallOfFameScreen.java       (tabs = two stages of content via Stack)
Arena.html          → ArenaScreen.java            (two TeamConfig Actors + a center Stack divider)
Settings.html       → SettingsScreen.java         (tabs + segmented controls + toggles)
Credits.html        → CreditsScreen.java          (vertical Table of Labels)
GameHUD.html        → GameScreen.java             (your existing screen + an HUD Stage overlay)
Inventory.html      → InventoryScreen.java        (grid via Table + ItemCell Actors)
PauseMenu.html      → PauseDialog.java            (Window) — used by every screen
```

`GameScreen` should layer two stages:

```java
hudStage  = new Stage(new FitViewport(390, 780));
worldStage = new Stage(new ExtendViewport(...));  // your existing dungeon
// in render:
worldStage.act(dt); worldStage.draw();
hudStage.act(dt);   hudStage.draw();
// in input:
Gdx.input.setInputProcessor(new InputMultiplexer(hudStage, worldInput));
```

The HUD `Stage` is laid out exactly like the mockup: top status bar
`Table`, meta strip `Table`, then the world viewport leaves a gap, then
the bottom DPad + action grid.

---

## 9. Things to handle that the mockup glosses over

- **UI scale setting**: change the FitViewport size (e.g. 390/scale × 780/scale)
  on the fly when the user picks 1x / 1.5x / 2x in Settings, or use a
  `ScreenViewport` with `setUnitsPerPixel`. Don't restart the screen.
- **Safe area / notches**: Android cutouts. Pad `appBar` top by
  `Gdx.graphics.getSafeInsetTop() / Gdx.graphics.getDensity()`.
- **Hit targets**: every tap button is ≥36×36 in the mockup; keep that
  minimum in actor sizes since libgdx doesn't enforce it.
- **Fonts and DPI**: Bake your bitmap fonts at the **target on-screen
  pixel size**, not at a reference resolution. If you support multiple
  UI scales, bake each font at each scale and swap in the skin.
- **Persisting settings**: `Preferences` is fine for everything in the
  Settings screen.

---

## 10. Order I'd ship in

1. Bake fonts + 9-patches + icon atlas → get `Skin` loading.
2. Port the **MainMenu** screen first — proves the layout helper works.
3. Settings + Saved Games next — proves the segmented picker + list-row patterns.
4. Hall of Fame + Arena — most layout-dense, but pure `Table` work after
   the helpers exist.
5. HUD overlay on top of the existing game screen.
6. Inventory last — slot grid + drag/drop is the most work.

If you want, I can also write out a starter `Skin.java` builder that
constructs the skin **programmatically** instead of from JSON — useful
during early iteration before all the atlas regions are baked.
