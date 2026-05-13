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
 * {@link #drawBody(UiCtx)} every frame.
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
    /** {@code true} when the burger drop-down panel is up - gates input so
     *  taps on menu items fire instead of falling through to the body. */
    protected boolean burgerOpen;
    /** Items added by subclasses via {@link #addBurgerItem}. The action runs
     *  when the corresponding label is tapped; the menu closes first. */
    private final List<String>   burgerLabels  = new ArrayList<>();
    private final List<Runnable> burgerActions = new ArrayList<>();
    private final List<Rect>     burgerItemRects = new ArrayList<>();
    private final Rect           burgerPanel    = new Rect();
    private int burgerItemPressed = -1;
    /** Popup actor that renders the burger overlay (scrim + panel + items)
     *  on the V2 stage's burger layer. Owned by every V2Screen so the
     *  drop-down sits on top of everything below - including any
     *  sub-popups - without per-screen rendering glue. */
    private final BurgerOverlayPopup burgerOverlay = new BurgerOverlayPopup();
    private final com.bjsp123.rl2.ui.v2.stage.V2PopupActor burgerOverlayActor =
            new com.bjsp123.rl2.ui.v2.stage.V2PopupActor(burgerOverlay);

    private final InputAdapter input = new InputAdapter() {
        @Override
        public boolean touchDown(int sx, int sy, int pointer, int button) {
            float vx = ctx.unprojectX(sx, sy);
            float vy = ctx.unprojectY(sx, sy);

            // Burger drop-down - when open, intercepts everything. Tap on
            // an item captures it; tap outside the panel + outside the
            // burger button closes the menu.
            if (burgerOpen) {
                for (int i = 0; i < burgerItemRects.size(); i++) {
                    if (burgerItemRects.get(i).contains(vx, vy)) {
                        burgerItemPressed = i;
                        return true;
                    }
                }
                if (!burgerPanel.contains(vx, vy)
                        && !(burger != null && burger.hit(vx, vy))) {
                    burgerOpen = false;
                    return true;
                }
                return true;
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

            // Burger menu item release - fire the bound action and close
            // the menu. The action runs AFTER closing so a navigation
            // target rebuilds layout cleanly.
            if (burgerItemPressed >= 0) {
                int idx = burgerItemPressed;
                burgerItemPressed = -1;
                if (idx < burgerItemRects.size()
                        && burgerItemRects.get(idx).contains(vx, vy)) {
                    burgerOpen = false;
                    Runnable a = burgerActions.get(idx);
                    if (a != null) a.run();
                }
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
                if (back.hit(vx, vy)) back.click();
                return true;
            }
            for (Btn b : buttons) {
                if (b.pressed) {
                    b.pressed = false;
                    if (b.hit(vx, vy) && b.onClick != null) b.onClick.run();
                    return true;
                }
            }
            return false;
        }

        @Override
        public boolean keyDown(int keycode) {
            if (keycode == Input.Keys.ESCAPE || keycode == Input.Keys.BACK) {
                if (burgerOpen) { burgerOpen = false; return true; }
                onEscape();
                return true;
            }
            return false;
        }

        @Override
        public boolean touchDragged(int sx, int sy, int pointer) {
            if (burgerOpen) return false;
            return onTouchDragged(ctx.unprojectX(sx, sy),
                    ctx.unprojectY(sx, sy));
        }

        @Override
        public boolean scrolled(float amountX, float amountY) {
            if (burgerOpen) return false;
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

    /** Hook for ESC / device back-button. Default: route through the back
     *  button's click action if one exists; otherwise no-op. */
    protected void onEscape() {
        if (back != null) back.click();
    }

    @Override
    public void show() {
        Gdx.input.setInputProcessor(input);
        rebuildLayout();
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
        burgerOpen = false;
        burgerItemPressed = -1;
        burgerLabels.clear();
        burgerActions.clear();
        burgerItemRects.clear();
        buildLayout();
    }

    /** Construct a burger button whose tap toggles {@link #burgerOpen}.
     *  Subclasses should use this in preference to {@code new Burger(ctx, ...)}
     *  if they want the auto-driven drop-down menu. */
    protected Burger makeBurger() {
        return new Burger(ctx, () -> burgerOpen = !burgerOpen);
    }

    /** Append a labelled item to the burger drop-down. Items render in
     *  insertion order from top to bottom. The action fires AFTER the
     *  menu closes - typical use is a navigation target. */
    protected void addBurgerItem(String label, Runnable action) {
        burgerLabels.add(label);
        burgerActions.add(action);
        burgerItemRects.add(new Rect());
    }

    /** Add the standard set of burger destinations every screen offers:
     *  Main Menu / Settings, plus Level Info / Map / Log when a run is in
     *  progress. Call once in {@link #buildLayout()} after {@link #makeBurger()}. */
    protected final void addStandardBurgerItems(com.bjsp123.rl2.Rl2Game game) {
        addBurgerItem("Main Menu", () -> game.setRootScreen(new V2Title(game, ctx)));
        addBurgerItem("Settings",  () -> game.pushScreen(new V2Settings(game, ctx)));
        if (game.currentPlay != null) {
            addBurgerItem("Level Info", () -> game.pushScreen(new V2LevelInfo(game, ctx,
                    game::popScreen,
                    game.currentPlay.getWorld().currentLevel())));
            addBurgerItem("Map", () -> game.pushScreen(new V2Map(game, ctx,
                    game::popScreen,
                    game.currentPlay.getWorld())));
            addBurgerItem("Log", () -> game.setRootScreen(game.currentPlay));
        }
    }

    /** Position the burger panel + per-item rects centred on the viewport
     *  as a chunky column-of-buttons window - same shape as every other V2
     *  modal so the user reads it as "a window with a list of choices",
     *  not a corner dropdown. Called from render(); cheap to recompute
     *  every frame. */
    private void layoutBurgerOverlay() {
        if (!burgerOpen || burger == null || burgerLabels.isEmpty()) return;
        int   n        = burgerLabels.size();
        float itemH    = 56f;
        float itemGap  = 8f;
        float padX     = 18f;
        float padTop   = 18f;
        float padBot   = 18f;
        float panelW   = Math.min(320f, ctx.worldW() - 24f);
        float panelH   = padTop + padBot + n * itemH + (n - 1) * itemGap;
        float panelX   = (ctx.worldW() - panelW) * 0.5f;
        float panelY   = (ctx.worldH() - panelH) * 0.5f;
        burgerPanel.set(panelX, panelY, panelW, panelH);
        // Top item gets the highest y; libGDX is y-up, so first label is
        // the highest one on screen - matches reading order top-down.
        for (int i = 0; i < n; i++) {
            float iy = panelY + panelH - padTop - (i + 1) * itemH - i * itemGap;
            burgerItemRects.get(i).set(panelX + padX, iy,
                    panelW - 2 * padX, itemH);
        }
    }

    @Override
    public void render(float delta) {
        ctx.clear();
        ctx.applyProjection();
        layoutBurgerOverlay();

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
     *  any sub-popups underneath. Reads V2Screen state directly -
     *  inner class so {@code burgerOpen}, {@code burgerLabels},
     *  {@code burgerItemRects}, and {@code burgerItemPressed} are
     *  accessible without copying. */
    private final class BurgerOverlayPopup
            implements com.bjsp123.rl2.ui.v2.stage.V2Popup {

        @Override
        public boolean isOpen() {
            return burgerOpen && !burgerLabels.isEmpty();
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
            ShapeRenderer s = ctx.shapes;
            s.begin(ShapeRenderer.ShapeType.Filled);
            Window.drawShape(ctx,
                    burgerPanel.x, burgerPanel.y, burgerPanel.w, burgerPanel.h);
            for (int i = 0; i < burgerItemRects.size(); i++) {
                Rect r = burgerItemRects.get(i);
                Edges.drawTriLine(s, r.x, r.y, r.w, r.h, UIVars.HUD_LINE_W);
                s.setColor(i == burgerItemPressed
                        ? UIVars.BTN_PRESSED_BG : UIVars.BTN_BG);
                s.rect(r.x + UIVars.HUD_BORDER, r.y + UIVars.HUD_BORDER,
                        r.w - 2 * UIVars.HUD_BORDER, r.h - 2 * UIVars.HUD_BORDER);
            }
            s.end();
            Gdx.gl.glDisable(GL20.GL_BLEND);

            ctx.batch.begin();
            for (int i = 0; i < burgerLabels.size(); i++) {
                Rect r = burgerItemRects.get(i);
                TextDraw.centre(ctx, ctx.fontHeader,
                        i == burgerItemPressed
                                ? UIVars.ACCENT : UIVars.TEXT_BODY,
                        burgerLabels.get(i),
                        r.cx(), r.cy() + 8f);
            }
            ctx.batch.end();
        }
    }
}
