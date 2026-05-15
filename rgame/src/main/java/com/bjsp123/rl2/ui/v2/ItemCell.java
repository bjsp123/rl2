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

        // Level badge - top-right; green when a buff/perk/gear is boosting above base
        int lvl     = ItemStats.effectiveLevel(item, holder);
        int baseLvl = Math.max(0, item.level);
        if (lvl > 0) {
            com.badlogic.gdx.graphics.Color badgeColor =
                    (lvl > baseLvl) ? UIVars.BOOST : UIVars.ACCENT;
            TextDraw.right(ctx, ctx.fontRegular, badgeColor,
                    "+" + lvl, x + w - 2f, y + h - 4f);
        }

        // Stack count - bottom-right
        if (item.count > 1) {
            TextDraw.right(ctx, ctx.fontRegular, UIVars.TEXT_BODY,
                    Integer.toString(item.count), x + w - 2f, y + ctx.lineH());
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

    private static void drawChargeBar(UiCtx ctx, Item item, Mob holder,
                                      float x, float y, float w) {
        int max = ItemStats.effectiveMaxCharge(item, holder);
        float pad = 4f, barH = 3f;
        float barW = w - 2 * pad;
        float bx = x + pad, by = y + 4f;

        ctx.batch.setColor(UIVars.BAR_CHARGE_BACKDROP);
        ctx.batch.draw(ctx.whitePixel, bx - 1, by - 1, barW + 2, barH + 2);

        if (max <= 1) {
            ctx.batch.setColor(UIVars.BAR_CHARGE_EMPTY);
            ctx.batch.draw(ctx.whitePixel, bx, by, barW, barH);
            if (item.charge >= 1f) {
                ctx.batch.setColor(UIVars.BAR_CHARGE_FULL);
                ctx.batch.draw(ctx.whitePixel, bx, by, barW, barH);
            } else if (item.charge > 0f) {
                ctx.batch.setColor(UIVars.BAR_CHARGE_PARTIAL);
                ctx.batch.draw(ctx.whitePixel, bx, by, barW * item.charge, barH);
            }
        } else {
            float slotW = (barW - (max - 1)) / max;
            for (int i = 0; i < max; i++) {
                float sx = bx + i * (slotW + 1f);
                float filled = Math.min(1f, Math.max(0f, item.charge - i));
                ctx.batch.setColor(UIVars.BAR_CHARGE_EMPTY);
                ctx.batch.draw(ctx.whitePixel, sx, by, slotW, barH);
                if (filled >= 1f) {
                    ctx.batch.setColor(UIVars.BAR_CHARGE_FULL);
                    ctx.batch.draw(ctx.whitePixel, sx, by, slotW, barH);
                } else if (filled > 0f) {
                    ctx.batch.setColor(UIVars.BAR_CHARGE_PARTIAL);
                    ctx.batch.draw(ctx.whitePixel, sx, by, slotW * filled, barH);
                }
            }
        }
        ctx.batch.setColor(Color.WHITE);
    }
}
