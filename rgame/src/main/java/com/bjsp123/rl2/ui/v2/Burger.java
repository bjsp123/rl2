package com.bjsp123.rl2.ui.v2;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.glutils.ShapeRenderer;

/**
 * Top-right burger icon - present on every V2 screen per CLAUDE.md ("burger
 * menu always present"). Draws a square button ({@link UIVars#BURGER_SIZE}
 * per side) containing three short horizontal bars stacked vertically.
 * The tap dispatches to a screen-supplied {@link Runnable} - normally
 * {@code V2Screen.makeBurger()}'s toggle, which opens the shared
 * {@link BurgerMenu} drop-down (Title / Settings / Encyclopedia + in-run
 * destinations).
 */
public final class Burger {

    public static final float INSET = 8f;

    public final Rect rect = new Rect();
    public boolean pressed;
    private final Runnable onClick;

    public Burger(UiCtx ctx, Runnable onClick) {
        this.onClick = onClick;
        // Anchor at top-right of the current virtual viewport - reads
        // ctx.worldW()/H() so the burger re-positions on layout rebuild
        // after a UiScale change.
        rect.set(ctx.worldW() - UIVars.BURGER_SIZE - INSET,
                ctx.worldH() - UIVars.BURGER_SIZE - INSET,
                UIVars.BURGER_SIZE, UIVars.BURGER_SIZE);
    }

    public void drawShape(UiCtx ctx) {
        ButtonChrome.shape(ctx, rect, pressed, false, false, UIVars.HUD_BG);
        ButtonChrome.burgerGlyph(ctx, rect, pressed);
    }

    /** Single-call draw: owns the full shape renderer lifecycle. */
    public void draw(UiCtx ctx) {
        Gdx.gl.glEnable(GL20.GL_BLEND);
        Gdx.gl.glBlendFunc(GL20.GL_SRC_ALPHA, GL20.GL_ONE_MINUS_SRC_ALPHA);
        ctx.shapes.begin(ShapeRenderer.ShapeType.Filled);
        drawShape(ctx);
        ctx.shapes.end();
        Gdx.gl.glDisable(GL20.GL_BLEND);
    }

    public boolean hit(float px, float py) { return rect.contains(px, py); }

    public void click() { if (onClick != null) onClick.run(); }
}
