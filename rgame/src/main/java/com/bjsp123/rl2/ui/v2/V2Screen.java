package com.bjsp123.rl2.ui.v2;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.Input;
import com.badlogic.gdx.InputAdapter;
import com.badlogic.gdx.ScreenAdapter;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.glutils.ShapeRenderer;

import java.util.ArrayList;
import java.util.List;

/**
 * Base class for every V2 UI screen. Owns the input pipeline (touch / ESC),
 * a list of buttons (so subclasses just append rather than implementing hit
 * tests), and the always-on chrome (top-right burger, optional bottom-right
 * back). Screens override {@link #buildLayout()} once on show, and
 * {@link #drawBodyShape(UiCtx)} + {@link #drawBodyText(UiCtx)} every frame
 * (a shape pass then a batch/text pass).
 *
 * <p>Render lifecycle each frame:
 * <ol>
 *   <li>Clear framebuffer</li>
 *   <li>{@link UiCtx#applyProjection()} so shapes + batch share the viewport</li>
 *   <li>ShapeRenderer.Filled pass: subclass body shapes, then buttons, then chrome</li>
 *   <li>SpriteBatch pass: subclass body text, then button labels</li>
 * </ol>
 */
public abstract class V2Screen extends ScreenAdapter {

    protected final UiCtx ctx;
    /** Buttons hit-tested by the input pipeline - subclass populates in
     *  {@link #buildLayout()}. */
    protected final List<Btn> buttons = new ArrayList<>();
    protected Burger burger;
    protected BackBtn back;

    /** Bottom-anchored origin for laying out a vertical-button menu - used
     *  as a starting Y for screens that stack big buttons. Subclasses are
     *  free to ignore this and lay out from any point. */
    protected float layoutCursor;

    // -- Burger menu state (auto-driven by V2Screen) -------------------------
    /** Shared drop-down geometry + chrome; items (label + action) added by
     *  subclasses via {@link #addBurgerItem} / {@link #addStandardBurgerItems}.
     *  {@link BurgerMenu#release} fires the bound action itself. */
    private final BurgerMenu burgerMenu = new BurgerMenu();
    /** Popup actor that renders the burger overlay (scrim + panel + items)
     *  on the V2 stage's burger layer. Owned by every V2Screen so the
     *  drop-down sits on top of everything below - including any
     *  sub-popups - without per-screen rendering glue. */
    private final BurgerOverlayPopup burgerOverlay = new BurgerOverlayPopup();
    private final com.bjsp123.rl2.ui.v2.stage.V2PopupActor burgerOverlayActor =
            new com.bjsp123.rl2.ui.v2.stage.V2PopupActor(burgerOverlay);

    protected com.bjsp123.rl2.audio.SoundManager sounds;
    public void setSounds(com.bjsp123.rl2.audio.SoundManager s) { this.sounds = s; }

    /** Override to return the screen's identifier for popup-sound lookup,
     *  e.g. {@code "map"} → {@code sfx.ui.popup.map}. Default: null (uses root). */
    protected String screenId() { return null; }

    private final InputAdapter input = new InputAdapter() {
        @Override
        public boolean touchDown(int sx, int sy, int pointer, int button) {
            float vx = ctx.unprojectX(sx, sy);
            float vy = ctx.unprojectY(sx, sy);

            // Burger drop-down - when open, intercepts everything. Tap on
            // an item captures it; tap outside the panel + outside the
            // burger button closes the menu.
            if (burgerMenu.open) {
                return burgerMenu.touchDown(vx, vy,
                        burger != null ? burger.rect : null);
            }

            // Burger and back take priority - they sit ON TOP of body buttons
            // visually so they take input first.
            if (burger != null && burger.hit(vx, vy)) { burger.pressed = true; return true; }
            if (back   != null && back.hit(vx, vy))   { back.pressed = true;   return true; }
            for (Btn b : buttons) {
                if (b.hit(vx, vy)) { b.pressed = true; return true; }
            }
            // Subclass body hook - scrollable screens claim the touch here
            // so subsequent touchDragged events route to them.
            if (onTouchDownInBody(vx, vy)) return true;
            // Tap outside the screen's modal window acts like Back -
            // matches the V2 popup convention. Subclasses expose their
            // window via {@link #modalWindow}; default returns null and
            // the screen ignores stray taps.
            Rect w = modalWindow();
            if (w != null && !w.contains(vx, vy)) {
                onEscape();
                return true;
            }
            return false;
        }

        @Override
        public boolean touchUp(int sx, int sy, int pointer, int button) {
            float vx = ctx.unprojectX(sx, sy);
            float vy = ctx.unprojectY(sx, sy);

            // Burger menu item release - BurgerMenu closes itself and fires
            // the bound action (menu closes first so a navigation target
            // rebuilds layout cleanly).
            if (burgerMenu.hasPress()) {
                burgerMenu.release(vx, vy);
                return true;
            }

            // Fire only if the release is over the same control that captured
            // the press - drag-off cancels, matching native button behaviour.
            if (burger != null && burger.pressed) {
                burger.pressed = false;
                if (burger.hit(vx, vy)) burger.click();
                return true;
            }
            if (back != null && back.pressed) {
                back.pressed = false;
                if (back.hit(vx, vy)) {
                    if (sounds != null) sounds.play("sfx.ui.cancel");
                    back.click();
                }
                return true;
            }
            for (Btn b : buttons) {
                if (b.pressed) {
                    b.pressed = false;
                    if (b.hit(vx, vy) && b.onClick != null) {
                        if (sounds != null) sounds.play("sfx.ui.click");
                        b.onClick.run();
                    }
                    return true;
                }
            }
            // Body-owned controls (sliders, custom toggles) get a release
            // hook so they can clear drag state without redefining the
            // whole input adapter.
            if (onTouchUpInBody(vx, vy)) return true;
            return false;
        }

        @Override
        public boolean keyDown(int keycode) {
            if (keycode == Input.Keys.ESCAPE || keycode == Input.Keys.BACK) {
                if (burgerMenu.open) { burgerMenu.open = false; return true; }
                onEscape();
                return true;
            }
            return false;
        }

        @Override
        public boolean touchDragged(int sx, int sy, int pointer) {
            if (burgerMenu.open) return false;
            return onTouchDragged(ctx.unprojectX(sx, sy),
                    ctx.unprojectY(sx, sy));
        }

        @Override
        public boolean scrolled(float amountX, float amountY) {
            if (burgerMenu.open) return false;
            return onScrolled(amountY);
        }
    };

    /** Optional touch-down hook for scrollable screens. Returning {@code true}
     *  claims the touch so subsequent touchDragged events route through
     *  this screen. Default: don't claim. */
    protected boolean onTouchDownInBody(float vx, float vy) { return false; }

    /** Optional accessor for the screen's main modal window rect. When
     *  non-null, taps that fall outside this rect (and miss every button,
     *  burger, and back affordance) are routed to {@link #onEscape}, so
     *  the screen dismisses on tap-outside the same way the V2 popups do.
     *  Default: {@code null} (no tap-outside handling). */
    protected Rect modalWindow() { return null; }

    /** Optional touch-drag hook for scrollable screens. Subclasses that want
     *  to scroll their content override this and return {@code true} to
     *  consume the drag (suppressing the V2Screen tap-detection state).
     *  Default: ignore. */
    protected boolean onTouchDragged(float vx, float vy) { return false; }

    /** Optional touch-up hook for body-owned controls that capture a drag
     *  (sliders, custom toggles). Returning {@code true} signals the touch
     *  was consumed. Default: ignore. */
    protected boolean onTouchUpInBody(float vx, float vy) { return false; }

    /** Optional mouse-wheel hook. Returns true to consume. Default: ignore. */
    protected boolean onScrolled(float amountY) { return false; }

    protected V2Screen(UiCtx ctx) {
        this.ctx = ctx;
    }

    /** One-time layout - populate {@link #buttons}, build the burger / back
     *  helpers, and stash any per-screen rect maths the body draw needs. */
    protected abstract void buildLayout();

    /** Frame body. Called inside the ShapeRenderer.Filled pass - subclasses
     *  paint window chrome, divider lines, etc. The buttons + burger + back
     *  are drawn AFTER this returns, so body rects sit underneath them. */
    protected abstract void drawBodyShape(UiCtx ctx);

    /** Frame body text. Called inside the SpriteBatch pass - subclasses paint
     *  any text outside the button labels (titles, descriptions). Button
     *  labels are drawn AFTER this returns. */
    protected abstract void drawBodyText(UiCtx ctx);

    /** Total vertical space to reserve from {@code window.top()} before
     *  body content begins. The title is always drawn at
     *  {@code window.top() - ctx.headerLineH()}, so this band encompasses
     *  that title plus a full regular-line of breathing room below it.
     *  Both terms scale with {@link com.bjsp123.rl2.ui.skin.UiFontScale},
     *  making content placement reactive to font-size changes. */
    protected float headerBandH() {
        return ctx.headerLineH() + ctx.lineH();
    }

    /** Optional full-screen backdrop hook. Called after clear/projection and before
     *  normal UI shape/text passes, so screens can draw world or bitmap scenes under
     *  their chrome. */
    protected void drawBackground(float delta) {
        ctx.renderAttract(delta);
    }

    /** Hook for ESC / device back-button. Default: route through the back
     *  button's click action if one exists; otherwise no-op. */
    protected void onEscape() {
        if (back != null) back.click();
    }

    @Override
    public void show() {
        Gdx.input.setInputProcessor(input);
        rebuildLayout();
        if (sounds != null) {
            String id = screenId();
            sounds.play(id != null ? "sfx.ui.popup." + id : "sfx.ui.popup");
        }
        // Park the burger overlay actor on the stage's burger layer so it
        // renders on top of every popup. Multiple V2Screen instances
        // share ctx.v2Stage; remove() in hide() prevents stale actors
        // from accumulating across screen swaps.
        ctx.v2Stage.addToBurger(burgerOverlayActor);
    }

    @Override
    public void hide() {
        ctx.v2Stage.remove(burgerOverlayActor);
    }

    /** Subclass-accessible handle on the V2Screen's base input adapter so a
     *  screen with its own popup-owned InputProcessor can chain it ahead
     *  via an {@link com.badlogic.gdx.InputMultiplexer}. Most screens never
     *  call this - only screens with a sub-popup that needs first dibs on
     *  taps (V2Saves's delete-confirm) do. */
    protected com.badlogic.gdx.InputProcessor baseInput() { return input; }

    @Override
    public void resize(int width, int height) {
        ctx.resize(width, height);
        // World dims may have changed (e.g. ExtendViewport extending an axis
        // to fit a wider device, or a UiScale change shrinking the world);
        // re-run buildLayout so anchors that read ctx.worldW()/H() pick up
        // the new values.
        rebuildLayout();
    }

    /** Clear button list + chrome and run the subclass's {@link #buildLayout}.
     *  Called on show() and on resize() so layouts always reflect the
     *  current viewport. Chooser callbacks in V2Settings call show()
     *  directly to force a rebuild after preference changes. */
    private void rebuildLayout() {
        buttons.clear();
        burger = null;
        back   = null;
        burgerMenu.clearItems();
        buildLayout();
    }

    /** Construct a burger button whose tap toggles the drop-down menu.
     *  Subclasses should use this in preference to {@code new Burger(ctx, ...)}
     *  if they want the auto-driven drop-down menu. */
    protected Burger makeBurger() {
        return new Burger(ctx, () -> burgerMenu.open = !burgerMenu.open);
    }

    /** Append a labelled item to the burger drop-down. Items render in
     *  insertion order from top to bottom. The action fires AFTER the
     *  menu closes - typical use is a navigation target. */
    protected void addBurgerItem(String label, Runnable action) {
        burgerMenu.add(label, action);
    }

    /** Add the standard burger destinations via the single canonical
     *  populator ({@link BurgerMenu#populateStandard}). Call once in
     *  {@link #buildLayout()} after {@link #makeBurger()}. */
    protected final void addStandardBurgerItems(com.bjsp123.rl2.Rl2Game game) {
        addStandardBurgerItems(game, false);
    }

    /** Title-screen variant: {@code titleScreen} suppresses the Main Menu
     *  item (the title IS the main menu). */
    protected final void addStandardBurgerItems(com.bjsp123.rl2.Rl2Game game,
                                                boolean titleScreen) {
        boolean inRun = game.currentPlay != null && !titleScreen;
        burgerMenu.populateStandard(titleScreen, inRun, new BurgerMenu.Destinations() {
            @Override public void openSettings() { game.pushScreen(new V2Settings(game, ctx)); }
            @Override public void openEncyclopedia() { game.pushScreen(new V2EncyclopediaScreen(game)); }
            @Override public void openCredits() { game.pushScreen(new V2Credits(game)); }
            @Override public void goMainMenu() { game.setRootScreen(new V2Title(game, ctx)); }
            @Override public void openLevelInfo() {
                game.pushScreen(new V2LevelInfo(game, ctx, game::popScreen,
                        game.currentPlay.getWorld().currentLevel()));
            }
            @Override public void openMap() {
                game.pushScreen(new V2Map(game, ctx, game::popScreen,
                        game.currentPlay.getWorld()));
            }
            @Override public void openLog() { game.setRootScreen(game.currentPlay); }
        });
    }

    /** Position the burger panel + per-item rects via the shared
     *  {@link BurgerMenu} geometry. Called from render(); cheap to
     *  recompute every frame. */
    private void layoutBurgerOverlay() {
        if (!burgerMenu.open || burger == null || burgerMenu.isEmpty()) return;
        burgerMenu.layout(ctx);
    }

    @Override
    public void render(float delta) {
        ctx.clear();
        ctx.applyProjection();
        layoutBurgerOverlay();
        drawBackground(delta);

        // Pass 1 - body shapes.
        Gdx.gl.glEnable(GL20.GL_BLEND);
        Gdx.gl.glBlendFunc(GL20.GL_SRC_ALPHA, GL20.GL_ONE_MINUS_SRC_ALPHA);
        ctx.shapes.begin(ShapeRenderer.ShapeType.Filled);
        drawBodyShape(ctx);
        for (Btn b : buttons) b.drawShape(ctx);
        ctx.shapes.end();
        Gdx.gl.glDisable(GL20.GL_BLEND);

        // Pass 2 - body text / icons.
        ctx.batch.begin();
        drawBodyText(ctx);
        for (Btn b : buttons) b.drawText(ctx);
        ctx.batch.end();

        // Pass 3 - chrome: each widget owns its renderer lifecycle.
        if (back   != null) back.draw(ctx);
        if (burger != null) burger.draw(ctx);

        // Stage layer - sub-popups (e.g. V2Saves's delete-confirm) and
        // the burger overlay. Renders LAST so the scrim cleanly hides
        // everything above. The Stage walks its children in z-order
        // and skips closed popups via their
        // {@link com.bjsp123.rl2.ui.v2.stage.V2PopupActor#act}
        // visibility mirror, so this is a no-op on screens with no
        // active popup.
        ctx.v2Stage.act(delta);
        ctx.v2Stage.draw();
    }

    /** Burger drop-down overlay rendered on the V2 stage's top
     *  ({@code burgerLayer}) so it cleanly hides every screen body and
     *  any sub-popups underneath. Chrome + labels come from the shared
     *  {@link BurgerMenu}; only the scrim + renderer lifecycle live here. */
    private final class BurgerOverlayPopup
            implements com.bjsp123.rl2.ui.v2.stage.V2Popup {

        @Override
        public boolean isOpen() {
            return burgerMenu.open && !burgerMenu.isEmpty();
        }

        @Override
        public void renderSelf() {
            // Modal dim - paints over the screen body that V2Screen.render
            // already drew, so titles / buttons / chrome behind the menu
            // all read as backgrounded.
            com.bjsp123.rl2.ui.v2.stage.Scrim.draw(ctx);

            ctx.applyProjection();
            Gdx.gl.glEnable(GL20.GL_BLEND);
            Gdx.gl.glBlendFunc(GL20.GL_SRC_ALPHA, GL20.GL_ONE_MINUS_SRC_ALPHA);
            ctx.shapes.begin(ShapeRenderer.ShapeType.Filled);
            burgerMenu.drawShapes(ctx);
            ctx.shapes.end();
            Gdx.gl.glDisable(GL20.GL_BLEND);

            ctx.batch.begin();
            burgerMenu.drawLabels(ctx);
            ctx.batch.end();
        }
    }
}
