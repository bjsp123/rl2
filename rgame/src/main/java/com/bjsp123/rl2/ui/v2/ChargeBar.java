package com.bjsp123.rl2.ui.v2;

import com.badlogic.gdx.graphics.Color;

/**
 * Shared charge-bar geometry + fill logic for item cells. The bar is a thin
 * strip along the bottom of an item slot: a single fill for a one-charge item,
 * or {@code max} segmented slots for a multi-charge wand, each empty / partial /
 * full per the item's current charge.
 *
 * <p>The two call sites render with different backends - {@link ItemCell} via a
 * {@code SpriteBatch} + white pixel, {@link V2Inventory} via a
 * {@code ShapeRenderer} - so the rect-fill is abstracted behind {@link Filler}.
 * Geometry and the {@code BAR_CHARGE_*} colors live here only, so the two stay
 * in lockstep. Colors come from {@link UIVars} so a config override hits both.
 */
final class ChargeBar {
    private ChargeBar() {}

    /** Fill a rect with a color - lets the caller bind its own batch/shapes. */
    interface Filler {
        void fill(Color c, float x, float y, float w, float h);
    }

    /** Draw the bar for an item with {@code charge} fill out of {@code max}
     *  total charges, anchored to the bottom of the cell at {@code (x, y)} with
     *  width {@code w}. */
    static void draw(int max, float charge, float x, float y, float w, Filler fill) {
        float pad = 4f, barH = 3f;
        float barW = w - 2 * pad;
        float bx = x + pad, by = y + 4f;

        fill.fill(UIVars.BAR_CHARGE_BACKDROP, bx - 1, by - 1, barW + 2, barH + 2);

        if (max <= 1) {
            fill.fill(UIVars.BAR_CHARGE_EMPTY, bx, by, barW, barH);
            if (charge >= 1f) {
                fill.fill(UIVars.BAR_CHARGE_FULL, bx, by, barW, barH);
            } else if (charge > 0f) {
                fill.fill(UIVars.BAR_CHARGE_PARTIAL, bx, by, barW * charge, barH);
            }
        } else {
            float slotW = (barW - (max - 1)) / max;
            for (int i = 0; i < max; i++) {
                float sx = bx + i * (slotW + 1f);
                float filled = Math.min(1f, Math.max(0f, charge - i));
                fill.fill(UIVars.BAR_CHARGE_EMPTY, sx, by, slotW, barH);
                if (filled >= 1f) {
                    fill.fill(UIVars.BAR_CHARGE_FULL, sx, by, slotW, barH);
                } else if (filled > 0f) {
                    fill.fill(UIVars.BAR_CHARGE_PARTIAL, sx, by, slotW * filled, barH);
                }
            }
        }
    }
}
