package com.bjsp123.rl2.ui.skin;
import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.Pixmap;
import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.graphics.g2d.BitmapFont;
import com.badlogic.gdx.graphics.g2d.NinePatch;
import com.badlogic.gdx.scenes.scene2d.ui.Button;
import com.badlogic.gdx.scenes.scene2d.ui.Label;
import com.badlogic.gdx.scenes.scene2d.ui.Skin;
import com.badlogic.gdx.scenes.scene2d.ui.TextButton;
import com.badlogic.gdx.scenes.scene2d.ui.TextField;
import com.badlogic.gdx.scenes.scene2d.utils.BaseDrawable;
import com.badlogic.gdx.scenes.scene2d.utils.NinePatchDrawable;
import com.badlogic.gdx.utils.Disposable;

import java.util.ArrayList;
import java.util.List;

/**
 * Central holder for the "stonebase" UI sprites used across the game's menus, HUD bar, frames,
 * and inventory. Each frame/button asset is wrapped as a libGDX {@link NinePatch} so it can
 * stretch to arbitrary widget sizes; corner sizes are picked per-asset to match the art.
 *
 * <p>Rendering code pulls the {@link NinePatch} fields directly and calls
 * {@code ninePatch.draw(batch, x, y, w, h)} — this avoids pulling in the full scene2d {@code Skin}
 * stack while still using the first-class libGDX 9-slice rendering path.
 */
public class StoneUi implements Disposable {

    private static final String BASE_STONEBASE  = "ui/stonebase/Assets/";
    /** Minimalist theme lives beside stonebase; icons + portraits fall back to stonebase because
     *  the minimalist art pack doesn't ship its own. */
    private static final String BASE_MINIMALIST = "ui/gold/";

    /** Default button background (released). */
    public NinePatch buttonUp;
    /** Default button background (pressed / focused). */
    public NinePatch buttonDown;

    /** Ornate panel / window frame. Used for modal dialogs and inventory. */
    public NinePatch panel;
    /** Plain panel frame. Used for smaller popups. */
    public NinePatch simplePanel;
    /** Metal / chunky panel frame. Used for the HUD status bar. */
    public NinePatch metalPanel;

    /** Inset square used for inventory item slots. */
    public NinePatch itemSlot;

    /** Horizontal "action bar" strip — used as the bottom HUD bar background. */
    public NinePatch hudBar;

    /** Stone "action box" used for the bottom-right action buttons. Chunky 33×34 art with bevels. */
    public NinePatch actionBox;

    /** Tab background — inactive state (recessed look). Falls back to {@link #buttonUp}
     *  on themes that don't define a dedicated tab style. */
    public NinePatch tab;
    /** Tab background — active / checked state (merges with the body panel below it). */
    public NinePatch tabActive;
    /** Equipment-cell background — same shape as {@link #itemSlot} but with a brighter
     *  fill so the equipment row reads as elevated relative to the bag grid. Falls back
     *  to {@link #itemSlot} on themes that don't define a dedicated equip style. */
    public NinePatch equipSlot;

    /** Panel with no top border (and square top corners). Used when something else —
     *  typically a tab strip — sits above the panel and provides the visual top edge.
     *  Falls back to {@link #panel} on themes that don't define this variant. */
    public NinePatch panelOpenTop;

    /** Character portrait — head shot in a stone roundel. Fixed-size 32×32, drawn unscaled. */
    public Texture portraitTex;
    /** 16×16 info-button icons — drawn as Image actors inside {@link #actionBox} buttons. */
    public Texture iconCompassTex, iconQuestionTex, iconMarkerTex, iconCogTex, iconChestTex,
                   iconExclamationTex;

    /** Decorative corner variants for the minimalist frame theme. Null when the stonebase
     *  theme is active — the stonebase frames already have ornate corners baked into the
     *  9-patch art. First index = shape (0..8), second index = position (0=TL, 1=TR,
     *  2=BL, 3=BR). Each variant is pre-lit for top-left light direction so we don't flip
     *  at runtime (runtime flipping would also flip the lighting, producing wrong bevels
     *  in three of four corners). */
    public com.badlogic.gdx.graphics.g2d.TextureRegion[][] minimalistCorners;
    public static final int POS_TL = 0, POS_TR = 1, POS_BL = 2, POS_BR = 3;
    /** Corner-decoration on-screen size in stage units for minimalist frames. The corner
     *  source PNGs are 24×24; this is the target draw size in stage units so each source
     *  pixel lands on an integer number of stage units (48 = 2×, 72 = 3× etc.) for crisp
     *  nearest-neighbor rendering. */
    public static final int MINIMALIST_CORNER_SIZE = 48;

    private final List<Texture> loaded = new ArrayList<>();

    /** Programmatic theme generator — non-null only when {@link UiStyleChoice} is set
     *  to {@link UiStyleChoice.Mode#SHATTERED}. Owns the small generated textures
     *  used for that theme's drawables; disposed alongside this {@code StoneUi}. */
    private ShatteredSkin shatteredSkin;

    public void create() {
        switch (UiStyleChoice.mode()) {
            case SHATTERED  -> createShattered();
            case MINIMALIST -> createMinimalist();
        }
    }

    /**
     * SPD-style flat dark slate with thin lighter borders and gold accents. The five
     * conceptual primitives ({@link ShatteredSkin}: slot, action, combination, panel,
     * dashboard) map onto the existing {@link NinePatch} field names so existing
     * consumers don't need to know the new theme exists. {@code itemSlot} and
     * {@code actionBox} both resolve to the same {@code slot} drawable — visual
     * consistency is the point. Icon textures fall back to the stonebase pack since
     * SHATTERED ships no asset files (per the iterative roll-out — programmatic
     * geometry first, real art later).
     */
    private void createShattered() {
        shatteredSkin = new ShatteredSkin();
        shatteredSkin.create();

        buttonUp     = shatteredSkin.slot;
        buttonDown   = shatteredSkin.slotPressed;
        panel        = shatteredSkin.panel;
        simplePanel  = shatteredSkin.panel;
        metalPanel   = shatteredSkin.dashboard;
        // Inventory + action-bar cells use the LIGHT slot so item sprites land on a
        // high-contrast surface; the icons paint a 1-px black outline (OutlinedImage)
        // to keep their silhouette crisp against the pale fill.
        itemSlot     = shatteredSkin.slotLight;
        hudBar       = shatteredSkin.hudStrip;
        actionBox    = shatteredSkin.slotLight;
        tab          = shatteredSkin.tab;
        tabActive    = shatteredSkin.tabActive;
        equipSlot    = shatteredSkin.slotLight;
        panelOpenTop = shatteredSkin.panelOpenTop;

        portraitTex        = loadTexFrom(BASE_STONEBASE, "Portrait.png");
        iconCompassTex     = loadTexFrom(BASE_STONEBASE, "IconCompass.png");
        iconQuestionTex    = loadTexFrom(BASE_STONEBASE, "IconQuestion.png");
        iconMarkerTex      = loadTexFrom(BASE_STONEBASE, "IconMapmarker.png");
        iconCogTex         = loadTexFrom(BASE_STONEBASE, "Cog.png");
        iconChestTex       = loadTexFrom(BASE_STONEBASE, "IconChest.png");
        iconExclamationTex = loadTexFrom(BASE_STONEBASE, "IconExclamation.png");
    }

    /**
     * Minimalist gold-on-dark look. One panel 9-patch doubles up for the three frame variants
     * (they're stylistically identical here), and the slot 9-patch covers both item slots and
     * the chunky action box. Icons + portrait fall back to the stonebase pack because the
     * minimalist set doesn't ship its own.
     */
    private void createMinimalist() {
        buttonUp     = npFrom(BASE_MINIMALIST, "button_up.png",    5);
        buttonDown   = npFrom(BASE_MINIMALIST, "button_down.png",  5);
        // Panel + slot backgrounds load via {@link #npFromHalfAlpha} — every
        // pixel's alpha is multiplied by 0.5 at load time so the gold-on-dark
        // chrome becomes 50% transparent and the dungeon view shows through it.
        // Buttons stay fully opaque so they remain visually solid for tapping.
        NinePatch framed = npFromHalfAlpha(BASE_MINIMALIST, "panel.png", 8);
        panel        = framed;
        simplePanel  = framed;
        metalPanel   = framed;
        NinePatch slot9 = npFromHalfAlpha(BASE_MINIMALIST, "slot.png", 5);
        itemSlot     = slot9;
        actionBox    = slot9;
        // hudBar needs a strip that stretches horizontally but not vertically — reuse the
        // panel's corners for the L/R caps and zero top/bottom so the bevel stays put.
        hudBar       = npFromHalfAlpha(BASE_MINIMALIST, "panel.png", 8, 8, 0, 0);

        portraitTex        = loadTexFrom(BASE_STONEBASE, "Portrait.png");
        iconCompassTex     = loadTexFrom(BASE_STONEBASE, "IconCompass.png");
        iconQuestionTex    = loadTexFrom(BASE_STONEBASE, "IconQuestion.png");
        iconMarkerTex      = loadTexFrom(BASE_STONEBASE, "IconMapmarker.png");
        iconCogTex         = loadTexFrom(BASE_STONEBASE, "Cog.png");
        iconChestTex       = loadTexFrom(BASE_STONEBASE, "IconChest.png");
        iconExclamationTex = loadTexFrom(BASE_STONEBASE, "IconExclamation.png");

        // Nine extracted corner ornaments from corners.webp, oriented top-left; the
        // composite drawable flips horizontally/vertically for the other three corners.
        // Nine ornament shapes × four positions = 36 PNGs, each pre-lit for a top-left
        // light direction so no runtime flipping is needed (flipping would also flip the
        // highlight/shadow and produce inconsistent bevels in three of the four corners).
        String[] posSuffix = { "TL", "TR", "BL", "BR" };
        minimalistCorners = new com.badlogic.gdx.graphics.g2d.TextureRegion[9][posSuffix.length];
        for (int shape = 0; shape < 9; shape++) {
            for (int pos = 0; pos < posSuffix.length; pos++) {
                Texture t = loadTexFrom(BASE_MINIMALIST + "corners/",
                        String.format("corner_%02d_%s.png", shape, posSuffix[pos]));
                t.setFilter(Texture.TextureFilter.Nearest, Texture.TextureFilter.Nearest);
                minimalistCorners[shape][pos] = new com.badlogic.gdx.graphics.g2d.TextureRegion(t);
            }
        }
    }

    private NinePatch npFrom(String base, String filename, int corner) {
        return npFrom(base, filename, corner, corner, corner, corner);
    }
    private NinePatch npFrom(String base, String filename, int l, int r, int t, int b) {
        Texture tex = new Texture(Gdx.files.internal(base + filename));
        tex.setFilter(Texture.TextureFilter.Nearest, Texture.TextureFilter.Nearest);
        loaded.add(tex);
        return new NinePatch(tex, l, r, t, b);
    }

    /** Same as {@link #npFrom} but every source pixel's alpha channel is
     *  multiplied by 0.5 before the texture is uploaded — the resulting
     *  9-patch renders 50% transparent so the scene behind it shows through.
     *  Used by the Minimalist theme's panel / slot / hud-strip backgrounds. */
    private NinePatch npFromHalfAlpha(String base, String filename, int corner) {
        return npFromHalfAlpha(base, filename, corner, corner, corner, corner);
    }
    private NinePatch npFromHalfAlpha(String base, String filename,
                                      int l, int r, int t, int b) {
        Pixmap src = new Pixmap(Gdx.files.internal(base + filename));
        Pixmap pm  = new Pixmap(src.getWidth(), src.getHeight(), Pixmap.Format.RGBA8888);
        pm.setBlending(Pixmap.Blending.None);
        pm.drawPixmap(src, 0, 0);
        // Halve alpha across every pixel. The Pixmap encoding is RGBA8888 —
        // the low 8 bits are the alpha channel, integer-divide by 2 to halve.
        for (int y = 0; y < pm.getHeight(); y++) {
            for (int x = 0; x < pm.getWidth(); x++) {
                int rgba = pm.getPixel(x, y);
                int a    = rgba & 0xFF;
                int newA = a >>> 1;             // a / 2 with no sign-bit weirdness
                pm.drawPixel(x, y, (rgba & 0xFFFFFF00) | newA);
            }
        }
        Texture tex = new Texture(pm);
        tex.setFilter(Texture.TextureFilter.Nearest, Texture.TextureFilter.Nearest);
        pm.dispose();
        src.dispose();
        loaded.add(tex);
        return new NinePatch(tex, l, r, t, b);
    }

    private Texture loadTexFrom(String base, String filename) {
        Texture t = new Texture(Gdx.files.internal(base + filename));
        t.setFilter(Texture.TextureFilter.Nearest, Texture.TextureFilter.Nearest);
        loaded.add(t);
        return t;
    }

    /** Path to the bundled Pixel Operator TTF — Jayvee Enaguas's CC-0 raster
     *  pixel font, sized to render crisply at 16-px native cap height. The
     *  {@code Mono} variant is monospace; the regular {@code PixelOperator.ttf}
     *  is proportional and reads more naturally for body text. */
    private static final String FONT_PATH = "ui/fonts/PixelOperator.ttf";
    /** Native-pixel rendering size for the Pixel Operator font. The face is
     *  hand-pixelled at this size — rendering at any other size produces
     *  fuzz, so we always rasterise at 16 and let {@link UiFontScale} scale
     *  the resulting bitmap up via integer-multiple nearest-neighbour. */
    private static final int FONT_PX = 16;

    /** Construct the standard UI font — Pixel Operator (CC-0), rasterised once
     *  at its native 16-px size with a 1-px black outline baked in by FreeType.
     *  Every text surface in the game (HUD, popups, encyclopedia, character
     *  stats, log) routes through this helper so they all share the same
     *  crisp pixel-art aesthetic.
     *
     *  <p>The font's base scale is multiplied by {@link UiFontScale#scale()} so
     *  the user-facing "UI Font Size" setting controls the size of every label
     *  in the game from a single point. Per-call {@code setFontScale()} bumps
     *  (e.g. a 1.6× title) compose on top of this base.
     *
     *  <p>If the TTF can't be loaded (e.g. a stripped asset bundle), falls back
     *  to the hand-encoded {@link PixelFont} so the UI still renders. */
    public static BitmapFont newDefaultFont() {
        BitmapFont font;
        try {
            com.badlogic.gdx.graphics.g2d.freetype.FreeTypeFontGenerator gen =
                    new com.badlogic.gdx.graphics.g2d.freetype.FreeTypeFontGenerator(
                            Gdx.files.internal(FONT_PATH));
            com.badlogic.gdx.graphics.g2d.freetype.FreeTypeFontGenerator.FreeTypeFontParameter
                    p = new com.badlogic.gdx.graphics.g2d.freetype.FreeTypeFontGenerator.FreeTypeFontParameter();
            p.size          = FONT_PX;
            p.borderWidth   = 1f;
            p.borderColor   = com.badlogic.gdx.graphics.Color.BLACK;
            p.borderStraight = true;            // crisp non-anti-aliased outline
            p.minFilter     = Texture.TextureFilter.Nearest;
            p.magFilter     = Texture.TextureFilter.Nearest;
            font = gen.generateFont(p);
            gen.dispose();
        } catch (Exception e) {
            Gdx.app.error("StoneUi",
                    "Pixel Operator load failed (" + e.getMessage()
                  + "), falling back to PixelFont", e);
            font = PixelFont.create();
        }
        font.setUseIntegerPositions(true);
        font.getData().setScale(UiFontScale.scale());
        return font;
    }

    /**
     * Build a fresh scene2d {@link Skin} backed by these stone 9-patches and a default
     * {@link BitmapFont}. Registered styles:
     * <ul>
     *   <li>{@code default} — {@link TextButton.TextButtonStyle} (stone button, yellow on press)</li>
     *   <li>{@code default}, {@code title}, {@code dim} — {@link Label.LabelStyle}</li>
     *   <li>{@code panel}, {@code simple-panel}, {@code metal-panel}, {@code item-slot}, {@code hud-bar}
     *       — {@link NinePatchDrawable}s for backgrounds</li>
     * </ul>
     * The Skin owns the font; the underlying 9-patch textures stay owned by this {@link StoneUi}.
     */
    public Skin newSkin() {
        Skin skin = new Skin();
        BitmapFont font = newDefaultFont();
        skin.add("default-font", font);

        // Per-theme text palette. SHATTERED uses muted whites + gold accents that match
        // its slate panel chrome; the stonebase / minimalist themes keep the legacy
        // bright-white-on-yellow scheme that sat well against their warmer art.
        boolean shattered = UiStyleChoice.mode() == UiStyleChoice.Mode.SHATTERED;
        Color textDefault = shattered ? ShatteredSkin.TEXT_WHITE : Color.WHITE;
        Color textTitle   = shattered ? ShatteredSkin.TEXT_TITLE : Color.YELLOW;
        Color textDim     = shattered ? ShatteredSkin.TEXT_DIM   : Color.LIGHT_GRAY;
        Color textWarn    = shattered ? ShatteredSkin.TEXT_WARN  : Color.RED;
        Color textAccent  = shattered ? ShatteredSkin.ACCENT     : Color.YELLOW;

        skin.add("default", new Label.LabelStyle(font, textDefault));
        skin.add("title",   new Label.LabelStyle(font, textTitle));
        skin.add("dim",     new Label.LabelStyle(font, textDim));
        skin.add("warn",    new Label.LabelStyle(font, textWarn));

        NinePatchDrawable up   = new NinePatchDrawable(buttonUp);
        NinePatchDrawable down = new NinePatchDrawable(buttonDown);
        TextButton.TextButtonStyle btn = new TextButton.TextButtonStyle();
        btn.up = up;
        btn.down = down;
        btn.over = down;
        btn.checked = down;
        btn.font = font;
        btn.fontColor         = textDefault;
        btn.downFontColor     = textAccent;
        btn.overFontColor     = textAccent;
        btn.checkedFontColor  = textAccent;
        btn.disabledFontColor = Color.GRAY;
        skin.add("default", btn);

        // "tab" — TextButtonStyle for tabbed UIs (inventory, crafting, character stats).
        // Inactive tabs use the dim {@code tab} drawable so they read as recessed below
        // the body panel; active (checked) tabs use the brighter {@code tabActive} which
        // matches the body panel fill, so the selected tab visually merges with the
        // content area beneath it. Falls back to {@code buttonUp}/{@code buttonDown} on
        // themes that don't define dedicated tab drawables.
        NinePatchDrawable tabUp     = new NinePatchDrawable(tab       != null ? tab       : buttonUp);
        NinePatchDrawable tabActDr  = new NinePatchDrawable(tabActive != null ? tabActive : buttonDown);
        TextButton.TextButtonStyle tabStyle = new TextButton.TextButtonStyle();
        tabStyle.up             = tabUp;
        tabStyle.down           = tabActDr;
        tabStyle.over           = tabActDr;
        tabStyle.checked        = tabActDr;
        tabStyle.font           = font;
        tabStyle.fontColor      = textDim;
        tabStyle.downFontColor  = textDefault;
        tabStyle.overFontColor  = textDefault;
        tabStyle.checkedFontColor = textAccent;
        tabStyle.disabledFontColor = Color.GRAY;
        skin.add("tab", tabStyle);

        // "tab-icon" — Button.ButtonStyle (no text cell) reusing the same tab
        // drawables. Tabs that show an icon (encyclopedia / character stats)
        // pick this style and add an Image child; tabs that show text use the
        // "tab" TextButtonStyle above.
        Button.ButtonStyle tabIconStyle = new Button.ButtonStyle();
        tabIconStyle.up      = tabUp;
        tabIconStyle.down    = tabActDr;
        tabIconStyle.over    = tabActDr;
        tabIconStyle.checked = tabActDr;
        skin.add("tab-icon", tabIconStyle);

        // Register the bare NinePatch — Skin.getDrawable() will lazily wrap it as a
        // NinePatchDrawable on lookup, which is what we want for panel backgrounds.
        // INVARIANT: every drawable registered here as a panel BACKGROUND must have
        // non-zero corner sizes on all four sides (top, bottom, left, right). A panel
        // missing one or two corner pieces reads as a "strip" instead of a frame and
        // looks broken when used inside a centered widget. The legacy {@code hud-bar}
        // strip violates this rule (0 top/bottom) and is therefore NOT registered.
        skin.add("panel",        panel);
        skin.add("simple-panel", simplePanel);
        skin.add("metal-panel",  metalPanel);
        skin.add("item-slot",    itemSlot);
        skin.add("action-box",   actionBox);
        // Equipment cells. Falls back to the bag-cell drawable on themes that don't
        // define a dedicated equip-slot (stonebase / minimalist).
        skin.add("equip-slot",   equipSlot != null ? equipSlot : itemSlot);
        // Panel with an open top — used by panels that have a tab strip above them
        // (so the active tab's interior flows seamlessly into the panel below).
        // Falls back to the standard panel on themes without a dedicated variant.
        skin.add("panel-open-top", panelOpenTop != null ? panelOpenTop : panel);

        // Minimalist theme overlay: re-register the three panel drawables wrapped in a
        // CornerDecoratedDrawable so every panel gets four ornamental corners pre-lit for
        // top-left light direction. Each named panel uses its own name-hash seed so the
        // inventory frame, the simple popup, and the metal HUD bar each pick distinct
        // ornament shapes (stable across opens within a session). getDrawable("panel")
        // prefers these Drawable entries over the bare NinePatch entries registered above.
        if (minimalistCorners != null && minimalistCorners.length > 0) {
            skin.add("panel",        corners("panel",        panel),
                    com.badlogic.gdx.scenes.scene2d.utils.Drawable.class);
            skin.add("simple-panel", corners("simple-panel", simplePanel),
                    com.badlogic.gdx.scenes.scene2d.utils.Drawable.class);
            skin.add("metal-panel",  corners("metal-panel",  metalPanel),
                    com.badlogic.gdx.scenes.scene2d.utils.Drawable.class);
        }

        // Square stone-tile button styles for HUD widgets.
        NinePatchDrawable abUp = new NinePatchDrawable(actionBox);

        // "action-text" — TextButton with stone-tile background, used for the "Inv" and the
        // numbered bottom-right action buttons.
        TextButton.TextButtonStyle actText = new TextButton.TextButtonStyle();
        actText.up = abUp;
        actText.down = down;
        actText.over = down;
        actText.checked = down;
        actText.font = font;
        actText.fontColor = textDefault;
        actText.downFontColor = textAccent;
        actText.checkedFontColor = textAccent;
        actText.disabledFontColor = Color.GRAY;
        skin.add("action-text", actText);

        // "action-icon" — plain Button (no label cell) holding a centred Image. Used for the
        // top-right info buttons (compass / question / map-marker / cog).
        Button.ButtonStyle actIcon = new Button.ButtonStyle();
        actIcon.up = abUp;
        actIcon.down = down;
        actIcon.over = down;
        actIcon.checked = down;
        skin.add("action-icon", actIcon);

        // TextField — reuses the small panel's 9-patch as the inset background and
        // a 1-px white-pixel block for both cursor and text-selection highlight.
        // Built here once so any screen needing text input ({@code TextField text =
        // new TextField("", skin)}) gets a consistent look without inlining a style.
        TextField.TextFieldStyle tfStyle = new TextField.TextFieldStyle();
        tfStyle.font          = font;
        tfStyle.fontColor     = textDefault;
        tfStyle.background    = new NinePatchDrawable(simplePanel);
        tfStyle.cursor        = new SolidColorDrawable(textAccent, 1f, 14f);
        tfStyle.selection     = new SolidColorDrawable(textAccent, 1f, 14f);
        skin.add("default", tfStyle);

        return skin;
    }

    /** Tiny solid-fill drawable used as the TextField cursor + selection highlight.
     *  Sized so the cursor reads as a thin caret rather than a thick block. */
    private static final class SolidColorDrawable extends BaseDrawable {
        private final Color color;
        SolidColorDrawable(Color c, float w, float h) {
            this.color = new Color(c);
            setMinWidth(w); setMinHeight(h);
        }
        @Override
        public void draw(com.badlogic.gdx.graphics.g2d.Batch batch,
                         float x, float y, float w, float h) {
            Color prev = batch.getColor();
            batch.setColor(color);
            batch.draw(StoneUiHolder.get(), x, y, w, h);
            batch.setColor(prev);
        }
    }

    /** Lazy-init holder for the 1×1 white texture so SolidColorDrawable can stay
     *  static (otherwise we'd need a per-Skin {@code whitePixel()} call which
     *  would create N copies). */
    private static final class StoneUiHolder {
        private static Texture whiteTex;
        static Texture get() {
            if (whiteTex == null) {
                Pixmap p = new Pixmap(1, 1, Pixmap.Format.RGBA8888);
                p.setColor(1, 1, 1, 1); p.fill();
                whiteTex = new Texture(p);
                p.dispose();
            }
            return whiteTex;
        }
    }

    /** Build a {@link CornerDecoratedDrawable} wrapping the given 9-patch. Seeded from the
     *  drawable's registered name so each named panel (panel / simple-panel / metal-panel)
     *  gets its own stable-per-session set of corner ornaments. */
    private com.badlogic.gdx.scenes.scene2d.utils.Drawable corners(String name, NinePatch np) {
        NinePatchDrawable base = new NinePatchDrawable(np);
        return new CornerDecoratedDrawable(base, minimalistCorners,
                MINIMALIST_CORNER_SIZE, name.hashCode());
    }

    /** Tiny 1×1 white texture handy for flat fills (dimming, solid bars). */
    public Texture whitePixel() {
        if (whiteTex == null) {
            Pixmap p = new Pixmap(1, 1, Pixmap.Format.RGBA8888);
            p.setColor(1, 1, 1, 1); p.fill();
            whiteTex = new Texture(p);
            p.dispose();
        }
        return whiteTex;
    }
    private Texture whiteTex;

    @Override
    public void dispose() {
        for (Texture t : loaded) t.dispose();
        loaded.clear();
        if (whiteTex != null) { whiteTex.dispose(); whiteTex = null; }
        if (shatteredSkin != null) { shatteredSkin.dispose(); shatteredSkin = null; }
    }
}
