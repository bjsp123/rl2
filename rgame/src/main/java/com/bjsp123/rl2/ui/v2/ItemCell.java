package com.bjsp123.rl2.ui.v2;

import com.badlogic.gdx.graphics.Color;
import com.bjsp123.rl2.logic.ItemStats;
import com.bjsp123.rl2.model.Item;
import com.bjsp123.rl2.model.Mob;
import com.bjsp123.rl2.world.render.BrandFx;
import com.bjsp123.rl2.world.render.ItemSprites;

/**
 * Canonical item-cell renderer for V2 UI surfaces. Call inside a SpriteBatch pass.
 *
 * <p>Badge layout (positions never overlap):
 * <ul>
 *   <li>Top-right - enchant level {@code "+N"} when effectiveLevel &gt; 0</li>
 *   <li>Bottom-right - stack count when {@code item.count &gt; 1}</li>
 *   <li>Bottom strip - charge bar when {@code showCharge} and the item has charges</li>
 * </ul>
 */
public final class ItemCell {
    private ItemCell() {}

    private static final float PAD = 4f;

    /**
     * Draw icon + overlaid badges into {@code (x, y, w, h)}.
     *
     * @param holder     the Mob holding the item (for WANDMASTER / BOMB_JACK bonus);
     *                   pass {@code null} for floor items and encyclopedia views.
     * @param showCharge {@code true} for inventory and action buttons;
     *                   {@code false} for map and info screens.
     */
    public static void draw(UiCtx ctx, Item item, Mob holder,
                            float x, float y, float w, float h,
                            boolean showCharge) {
        if (item == null) return;

        var region = ItemSprites.regionFor(item);
        if (region != null) {
            ctx.batch.draw(region, x + PAD, y + PAD, w - 2 * PAD, h - 2 * PAD);
        }

        BrandFx.drawItemSparks(ctx.batch, ctx.whitePixel, x, y, w, h, item);

        // Level badge - top-right, always yellow (ACCENT). The earlier
        // green-when-boosted variant collided visually with the white "xN"
        // stack badge in the opposite corner; sticking to yellow for level
        // and white for count keeps the two badges immediately distinct.
        int lvl = ItemStats.effectiveLevel(item, holder);
        if (lvl > 0) {
            TextDraw.right(ctx, ctx.fontRegular, UIVars.ACCENT,
                    "+" + lvl, x + w - 2f, y + h - 4f);
        }

        // Stack count - bottom-right, white. The "x" prefix prevents a "3"
        // from being misread as a tiny level badge.
        if (item.count > 1) {
            TextDraw.right(ctx, ctx.fontRegular, UIVars.TEXT_BODY,
                    "x" + item.count, x + w - 2f, y + ctx.lineH());
        }

        // Charge bar - bottom strip via whitePixel tinting
        if (showCharge && item.baseChargeMax > 0) {
            drawChargeBar(ctx, item, holder, x, y, w);
        }
    }

    /** Convenience overload - no charge bar (map, info screens). */
    public static void draw(UiCtx ctx, Item item, Mob holder,
                            float x, float y, float w, float h) {
        draw(ctx, item, holder, x, y, w, h, false);
    }

    /** Reusable Filler bound via {@link #fillerCtx} - a capturing lambda here
     *  would allocate a fresh closure per slot per frame on the HUD hot path.
     *  Safe as a single static: rendering is single-threaded. */
    private static UiCtx fillerCtx;
    private static final ChargeBar.Filler BATCH_FILLER = (c, rx, ry, rw, rh) -> {
        fillerCtx.batch.setColor(c);
        fillerCtx.batch.draw(fillerCtx.whitePixel, rx, ry, rw, rh);
    };

    private static void drawChargeBar(UiCtx ctx, Item item, Mob holder,
                                      float x, float y, float w) {
        int max = ItemStats.effectiveMaxCharge(item, ItemStats.effectiveLevel(item, holder));
        fillerCtx = ctx;
        ChargeBar.draw(max, item.charge, x, y, w, BATCH_FILLER);
        fillerCtx = null;
        ctx.batch.setColor(Color.WHITE);
    }
}
