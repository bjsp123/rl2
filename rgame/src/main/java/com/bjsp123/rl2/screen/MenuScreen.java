package com.bjsp123.rl2.screen;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.Input;
import com.badlogic.gdx.InputAdapter;
import com.badlogic.gdx.InputMultiplexer;
import com.badlogic.gdx.Screen;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.scenes.scene2d.InputEvent;
import com.badlogic.gdx.scenes.scene2d.Stage;
import com.badlogic.gdx.scenes.scene2d.ui.Container;
import com.badlogic.gdx.scenes.scene2d.ui.Label;
import com.badlogic.gdx.scenes.scene2d.ui.Skin;
import com.badlogic.gdx.scenes.scene2d.ui.Table;
import com.badlogic.gdx.scenes.scene2d.ui.TextButton;
import com.badlogic.gdx.scenes.scene2d.utils.ClickListener;
import com.badlogic.gdx.scenes.scene2d.utils.Drawable;
import com.badlogic.gdx.utils.viewport.ScreenViewport;
import com.bjsp123.rl2.ui.skin.StoneUi;
import com.bjsp123.rl2.ui.skin.UiPixelScale;
import com.bjsp123.rl2.ui.skin.UiScale;
import com.bjsp123.rl2.ui.skin.UiTextures;

/**
 * Base for full-screen menu screens, built on scene2d.ui. Subclasses populate the {@link #root}
 * table inside {@link #build(Table)}; the base handles the {@link Stage}, {@link Skin}, viewport
 * (which scales with {@link UiScale}), keyboard escape routing, and disposal.
 *
 * <p>The {@link Skin} comes from {@link StoneUi#newSkin()} so widgets render with the same stone
 * 9-patches used by the HUD.
 */
public abstract class MenuScreen implements Screen {

    protected Stage    stage;
    protected StoneUi  ui;
    protected Skin     skin;
    /** Root table — fills the viewport. Subclasses add their content here. */
    protected Table    root;

    @Override
    public void show() {
        ui = new StoneUi();
        ui.create();
        skin = ui.newSkin();

        ScreenViewport vp = new ScreenViewport();
        vp.setUnitsPerPixel(1f / effectiveScale(
                com.badlogic.gdx.Gdx.graphics.getWidth(),
                com.badlogic.gdx.Gdx.graphics.getHeight()));
        stage = new Stage(vp);

        root = new Table();
        root.setFillParent(true);
        stage.addActor(root);

        InputMultiplexer mux = new InputMultiplexer();
        mux.addProcessor(stage);
        mux.addProcessor(new InputAdapter() {
            @Override public boolean keyDown(int keycode) {
                if (keycode == Input.Keys.ESCAPE) { onEscape(); return true; }
                return false;
            }
        });
        Gdx.input.setInputProcessor(mux);

        build(root);
    }

    /**
     * Subclasses populate {@link #root} here. Called once on {@link #show()}; for layouts that
     * change with state (selection, etc.) call {@link #rebuild()} to clear and rerun.
     */
    protected abstract void build(Table root);

    /** Default escape handler — subclasses override to navigate "back". */
    protected void onEscape() {}

    /** Tear down and rerun {@link #build(Table)} — used after state changes. */
    protected void rebuild() {
        root.clear();
        build(root);
    }

    @Override
    public void render(float delta) {
        Gdx.gl.glClearColor(0.06f, 0.06f, 0.08f, 1f);
        Gdx.gl.glClear(GL20.GL_COLOR_BUFFER_BIT);
        stage.act(delta);
        stage.draw();
    }

    @Override
    public void resize(int w, int h) {
        ScreenViewport vp = (ScreenViewport) stage.getViewport();
        vp.setUnitsPerPixel(1f / effectiveScale(w, h));
        vp.update(w, h, true);
        // The new viewport size may invalidate cached layout — rerun the build so cells whose
        // sizes were keyed off the previous viewport (column counts, wraps) reflow.
        rebuild();
    }

    /**
     * Subclasses override to declare the smallest virtual canvas their content needs to fit.
     * If the window is smaller than {@code minVirtualWidth × minVirtualHeight} at the current
     * {@link UiScale}, {@link #effectiveScale(int, int)} shrinks below the user's UI scale so
     * the canvas still fits — that's the reactive part.
     */
    protected float minVirtualWidth()  { return 480; }
    protected float minVirtualHeight() { return 360; }

    /**
     * The UI-scale we'll actually apply: the user's preferred {@link UiScale} unless the screen
     * is too small to fit our minimum virtual canvas at that scale, in which case we scale
     * down so it does fit.
     */
    protected float effectiveScale(int screenW, int screenH) {
        float fitW = screenW / minVirtualWidth();
        float fitH = screenH / minVirtualHeight();
        // UiScale is clamped to what the window can fit — a large UiScale on a tiny window
        // shrinks so the minimum virtual canvas still displays. UiPixelScale is applied on
        // top UNCLAMPED: pixel chunkiness is the user's explicit choice and must show up on
        // title / character-select / settings screens the same way it does in-game, even if
        // that means the UI overflows the window a bit.
        float uiScaleClamped = Math.min(UiScale.scale(), Math.min(fitW, fitH));
        return uiScaleClamped * UiPixelScale.scale();
    }

    @Override public void pause()  {}
    @Override public void resume() {}
    @Override public void hide()   { dispose(); }

    @Override
    public void dispose() {
        if (stage != null) { stage.dispose(); stage = null; }
        if (skin  != null) { skin.dispose();  skin  = null; }
        if (ui    != null) { ui.dispose();    ui    = null; }
    }

    // ── widget helpers ──────────────────────────────────────────────────────

    /** Build a stone {@link TextButton} that runs {@code onClick} when pressed. */
    protected TextButton button(String label, Runnable onClick) {
        TextButton b = new TextButton(label, skin);
        b.addListener(new ClickListener() {
            @Override public void clicked(InputEvent event, float x, float y) {
                onClick.run();
            }
        });
        return b;
    }

    /** Like {@link #button(String, Runnable)} but with the inner label scaled
     *  up by {@code labelScale} (default {@code 1.4f}). Use this for the
     *  title-screen / character-select buttons that need to read at arm's
     *  length on a phone. */
    protected TextButton chunkyButton(String label, Runnable onClick) {
        return chunkyButton(label, 1.4f, onClick);
    }

    protected TextButton chunkyButton(String label, float labelScale, Runnable onClick) {
        TextButton b = button(label, onClick);
        b.getLabel().setFontScale(labelScale);
        return b;
    }

    /** Pixels of inset between the back-icon button and the framed panel's
     *  bottom-right corner. Same value across every screen so the back
     *  affordance always lives in the same spot relative to the corner. */
    protected static final float BACK_BUTTON_INSET = 12f;
    /** On-screen footprint of the back-icon button. Bigger than the legacy
     *  48-px size so it reads as thumb-sized on a phone. */
    protected static final float BACK_BUTTON_SIZE  = 56f;

    /** Standard back button for non-title screens — an icon-only Button using
     *  the {@link com.bjsp123.rl2.world.render.IconSprites.Icon#BACK} glyph
     *  on top of the regular action-icon chrome. Use this instead of a text
     *  "Back" label so every screen's back affordance reads identically.
     *  Position via {@link #framedWithBack} so the inset from the panel's
     *  bottom-right corner is uniform. */
    protected com.badlogic.gdx.scenes.scene2d.ui.Button backIconButton(Runnable onClick) {
        com.badlogic.gdx.scenes.scene2d.ui.Button btn =
                new com.badlogic.gdx.scenes.scene2d.ui.Button(skin, "action-icon");
        com.badlogic.gdx.graphics.g2d.TextureRegion region =
                com.bjsp123.rl2.world.render.IconSprites.regionFor(
                        com.bjsp123.rl2.world.render.IconSprites.Icon.BACK);
        if (region != null) {
            com.badlogic.gdx.scenes.scene2d.ui.Image icon =
                    new com.badlogic.gdx.scenes.scene2d.ui.Image(region);
            icon.setScaling(com.badlogic.gdx.utils.Scaling.fit);
            btn.add(icon).size(32, 32).pad(8);
        } else {
            btn.add(new Label("Back", skin, "default")).pad(8);
        }
        btn.addListener(new ClickListener() {
            @Override public void clicked(InputEvent event, float x, float y) {
                onClick.run();
            }
        });
        return btn;
    }

    /** Like {@link #fixedPanel} but ALSO overlays the back-icon button at the
     *  bottom-right corner of the framed panel via a {@code Stack}. Every
     *  non-title screen should use this so the back glyph anchors at exactly
     *  {@link #BACK_BUTTON_INSET} from each edge regardless of inner-panel
     *  layout (no more per-screen padTop / padBottom calls to muscle the
     *  button into roughly the right corner). */
    protected com.badlogic.gdx.scenes.scene2d.ui.Stack framedWithBack(
            Table content, float fixedW, float fixedH, Runnable onBack) {
        Container<Table> framed = fixedPanel(content, fixedW, fixedH);
        com.badlogic.gdx.scenes.scene2d.ui.Stack stack =
                new com.badlogic.gdx.scenes.scene2d.ui.Stack();
        stack.add(framed);

        com.badlogic.gdx.scenes.scene2d.ui.Container<com.badlogic.gdx.scenes.scene2d.ui.Button>
                backHolder = new com.badlogic.gdx.scenes.scene2d.ui.Container<>(
                        backIconButton(onBack));
        backHolder.bottom().right().pad(BACK_BUTTON_INSET);
        backHolder.prefSize(BACK_BUTTON_SIZE, BACK_BUTTON_SIZE);
        // childrenOnly: the holder itself doesn't catch taps (the inner button
        // handles its own clicks), so taps on the empty area beside the back
        // glyph still pass through to whatever's behind in the Stack.
        backHolder.setTouchable(com.badlogic.gdx.scenes.scene2d.Touchable.childrenOnly);
        stack.add(backHolder);
        return stack;
    }

    /** {@link Label} with optional font scale and the named style ({@code default}, {@code title}, {@code dim}). */
    protected Label label(String text, String style, float fontScale) {
        Label l = new Label(text, skin, style);
        l.setFontScale(fontScale);
        return l;
    }

    /** The outermost-popup background drawable — texture from {@link UiTextures} when
     *  available, falling back to the skin's "panel" 9-patch when the UI atlas failed
     *  to load. Centralised so every popup helper picks the same fallback. */
    private Drawable outerPanelBackground() {
        return UiTextures.windowBackgroundOr(skin.getDrawable("panel"));
    }

    /**
     * Wrap content in the outermost-popup chrome. The container clamps to
     * {@code maxW × maxH} but shrinks to fit when the viewport is smaller — that's the
     * responsive part: when the screen can't accommodate the preferred size, scene2d
     * trims the cell to what's available. Background comes from {@link UiTextures} so
     * every popup's surrounding window shares the same texture.
     */
    protected Container<Table> panel(Table content, float maxW, float maxH) {
        content.setBackground(outerPanelBackground());
        Container<Table> c = new Container<>(content);
        c.maxSize(maxW, maxH);
        c.fill();
        return c;
    }

    /**
     * Wrap content in the outermost-popup chrome with a FIXED preferred size — the
     * container doesn't pack down to its content nor grow with it. Use this for menus
     * where the layout should be deterministic regardless of which tab is active or how
     * much dynamic content is currently inside.
     */
    protected Container<Table> fixedPanel(Table content, float fixedW, float fixedH) {
        content.setBackground(outerPanelBackground());
        Container<Table> c = new Container<>(content);
        c.size(fixedW, fixedH);
        c.fill();
        return c;
    }

}
