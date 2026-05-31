package com.bjsp123.rl2.ui.v2;

import com.badlogic.gdx.graphics.glutils.ShapeRenderer;

import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/**
 * Compact oval two-state switch for V2 settings rows. The current value
 * is pulled fresh from {@link #valueSupplier} every shape pass so a single
 * Toggle stays in sync with the underlying Settings field across rebuilds.
 *
 * <p>Rendered as a stadium (rect + two end-cap circles) with a smaller
 * filled thumb circle that slides to the left or right end. Track tint
 * conveys state - green for on, neutral slot grey for off - so the pill
 * reads without any inner text.
 *
 * <p>Two-pass render matches {@link Btn}: {@link #drawShape} inside the
 * ShapeRenderer.Filled block. {@link #drawText} is a no-op (no inline
 * label) but is kept for symmetry with {@link Slider} and friends.
 */
public final class Toggle {

    public final Rect rect = new Rect();
    public final BooleanSupplier valueSupplier;
    public final Consumer<Boolean> onChange;
    /** True while a finger is held on the pill - drives the pressed
     *  highlight, cleared on touchUp. */
    public boolean pressed;

    public Toggle(float x, float y, float w, float h,
                  BooleanSupplier valueSupplier, Consumer<Boolean> onChange) {
        this.rect.set(x, y, w, h);
        this.valueSupplier = valueSupplier;
        this.onChange = onChange;
    }

    public boolean hit(float px, float py) { return rect.contains(px, py); }

    public boolean isOn() { return valueSupplier.getAsBoolean(); }

    /** Flip the value and notify. The supplier is the source of truth - we
     *  read the current state, pass the inverse to the setter, and let the
     *  next render reflect the change. */
    public void click() {
        boolean now = valueSupplier.getAsBoolean();
        onChange.accept(!now);
    }

    /** Stadium-shaped track + thumb circle. End-caps are filled circles of
     *  radius = h/2; the body between them is a filled rectangle. Outline
     *  is drawn the same way one r bigger so the silhouette stays smooth. */
    public void drawShape(UiCtx ctx) {
        ShapeRenderer s = ctx.shapes;
        boolean on = isOn();
        float r = rect.h * 0.5f;
        float cyMid = rect.y + r;
        float lCx = rect.x + r;
        float rCx = rect.x + rect.w - r;

        // Outline - drawn one virtual pixel out from the fill so the pill
        // gets a clean rim. ACCENT when pressed for the touch highlight.
        s.setColor(pressed ? UIVars.ACCENT : UIVars.BORDER_MID);
        s.circle(lCx, cyMid, r);
        s.circle(rCx, cyMid, r);
        s.rect(lCx, rect.y, rCx - lCx, rect.h);

        // Track fill inset by 1 px so the outline rim shows. Tinted by
        // state.
        float ir = r - 1f;
        s.setColor(on ? UIVars.BOOST : UIVars.SLOT_BG);
        s.circle(lCx, cyMid, ir);
        s.circle(rCx, cyMid, ir);
        s.rect(lCx, rect.y + 1f, rCx - lCx, rect.h - 2f);

        // Thumb - smaller filled circle hugging the on/off end of the
        // track. Always the bright body colour so it pops against either
        // track tint.
        float thumbR = r - 3f;
        float thumbCx = on ? rCx : lCx;
        s.setColor(UIVars.TEXT_BODY);
        s.circle(thumbCx, cyMid, thumbR);
    }

    /** No inline text - the green/grey track tint + thumb position carry
     *  the state. Kept for parity with the other widgets' two-pass API. */
    public void drawText(UiCtx ctx) { }
}
