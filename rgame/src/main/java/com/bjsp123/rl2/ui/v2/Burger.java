package com.bjsp123.rl2.ui.v2;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.glutils.ShapeRenderer;

/**
 * Top-right burger icon - present on every V2 screen per CLAUDE.md ("burger
 * menu always present"). Draws a {@code SIZE}x{@code SIZE} (36px) button
 * containing three short horizontal bars stacked vertically. Tap behaviour is owned by the screen
 * (typically: open a small overlay with Title / Settings / Encyclopaedia).
 *
 * <p>For now the click target is wired but the overlay isn't implemented - it
 * dispatches to a screen-supplied {@link Runnable} that may be a no-op while
 * the V2 chain is being built up.
 */
public final class Burger {

    /** Burger button size. Shared by the HUD (V2Hud) so the burger is
     *  identical in-play and on menu screens. Kept small per RL-27. */
    public static final float SIZE  = 36f;
    public static final float INSET = 8f;

    public final Rect rect = new Rect();
    public boolean pressed;
    private final Runnable onClick;

    public Burger(UiCtx ctx, Runnable onClick) {
        this.onClick = onClick;
        // Anchor at top-right of the current virtual viewport - reads
        // ctx.worldW()/H() so the burger re-positions on layout rebuild
        // after a UiScale change.
        rect.set(ctx.worldW() - SIZE - INSET, ctx.worldH() - SIZE - INSET, SIZE, SIZE);
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
