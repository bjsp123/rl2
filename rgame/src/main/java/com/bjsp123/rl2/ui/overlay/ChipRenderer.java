package com.bjsp123.rl2.ui.overlay;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.OrthographicCamera;
import com.badlogic.gdx.graphics.g2d.BitmapFont;
import com.badlogic.gdx.graphics.glutils.ShapeRenderer;
import com.badlogic.gdx.math.Vector3;
import com.bjsp123.rl2.logic.ItemStats;
import com.bjsp123.rl2.logic.MobStats;
import com.bjsp123.rl2.model.Item;
import com.bjsp123.rl2.model.MinMax;
import com.bjsp123.rl2.model.Mob;
import com.bjsp123.rl2.model.Point;
import com.bjsp123.rl2.ui.v2.UIVars;
import com.bjsp123.rl2.ui.v2.UiCtx;
import com.bjsp123.rl2.world.render.LevelRenderer;

import java.util.List;

/**
 * Shared renderer for the small in-world "85% · ~6 dmg" annotation chips:
 * projects a world tile into V2 space and draws a dark pill backdrop plus
 * down-scaled accent text above the tile. Used by {@link TargetingOverlay}
 * (wand / bomb / throw aiming) and by PlayScreen's melee preview pass over
 * adjacent hostiles, so both read identically.
 *
 * <p>The compose helpers route through the SAME {@link MobStats} formulas the
 * combat resolution uses ({@code hitChance} / {@code netDamageRange}), so a
 * chip is a promise, not an estimate.
 */
public final class ChipRenderer {

    /** One chip ready to render - tile in world space, text already formatted. */
    public record Chip(Point tile, String text) {}

    private static final Vector3 PROJECT_BUF = new Vector3();

    private ChipRenderer() {}

    /** Draw every chip: one shape pass for the pill backdrops, one batch pass
     *  for the text. {@code fontScale} multiplies the regular font (targeting
     *  uses {@link UIVars#CHIP_SCALE}; the melee preview its own). Caller has
     *  NO active batch/shape renderer. */
    public static void render(UiCtx uiCtx, OrthographicCamera worldCamera,
                              List<Chip> chips, float fontScale) {
        if (chips == null || chips.isEmpty() || uiCtx == null || worldCamera == null) return;

        uiCtx.applyProjection();
        BitmapFont font = uiCtx.fontRegular;
        float prevScale = font.getScaleX();
        font.getData().setScale(prevScale * fontScale);

        // Compute positions and per-chip rects from the world tile centres.
        float[][] geom = new float[chips.size()][];
        for (int i = 0; i < chips.size(); i++) {
            Chip cp = chips.get(i);
            PROJECT_BUF.set(cp.tile().tileX() * LevelRenderer.TILE_SIZE
                            + LevelRenderer.TILE_SIZE * 0.5f,
                    cp.tile().tileY() * LevelRenderer.TILE_SIZE
                            + LevelRenderer.TILE_SIZE,
                    0f);
            worldCamera.project(PROJECT_BUF);
            int sx = Math.round(PROJECT_BUF.x);
            int sy = Gdx.graphics.getHeight() - Math.round(PROJECT_BUF.y);
            float vx = uiCtx.unprojectX(sx, sy);
            float vy = uiCtx.unprojectY(sx, sy);
            uiCtx.layout.setText(font, cp.text());
            float w = uiCtx.layout.width;
            float h = uiCtx.layout.height;
            // baseline sits at vy + lift; the visible glyph rises ABOVE that
            // baseline by `h` (cap-height in libGDX), so the background rect
            // ranges [baseline-1, baseline+h+1] vertically.
            float baselineY = vy + UIVars.CHIP_LIFT;
            float bgX = vx - w * 0.5f - UIVars.CHIP_PAD;
            float bgY = baselineY - 2f;
            float bgW = w + 2 * UIVars.CHIP_PAD;
            float bgH = h + 2 * UIVars.CHIP_PAD;
            geom[i] = new float[] { vx - w * 0.5f, baselineY, bgX, bgY, bgW, bgH };
        }

        Gdx.gl.glEnable(GL20.GL_BLEND);
        Gdx.gl.glBlendFunc(GL20.GL_SRC_ALPHA, GL20.GL_ONE_MINUS_SRC_ALPHA);
        uiCtx.shapes.begin(ShapeRenderer.ShapeType.Filled);
        uiCtx.shapes.setColor(0f, 0f, 0f, UIVars.CHIP_BG_ALPHA);
        for (float[] g : geom) uiCtx.shapes.rect(g[2], g[3], g[4], g[5]);
        uiCtx.shapes.end();
        Gdx.gl.glDisable(GL20.GL_BLEND);

        uiCtx.batch.begin();
        font.setColor(UIVars.ACCENT);
        for (int i = 0; i < chips.size(); i++) {
            float[] g = geom[i];
            font.draw(uiCtx.batch, chips.get(i).text(), g[0], g[1] + uiCtx.layout.height);
        }
        font.setColor(Color.WHITE);
        uiCtx.batch.end();

        font.getData().setScale(prevScale);
    }

    /** Hit% + damage chip text. Used wherever the hit-roll matters (melee,
     *  single-target throws, wand MISSILE). */
    public static String hitAndDamage(Mob attacker, Mob victim) {
        return hitPct(attacker, victim) + " · " + damageString(attacker, victim, null);
    }

    /** Compact hit%-only chip text - the crowded-melee form. */
    public static String hitOnly(Mob attacker, Mob victim) {
        return hitPct(attacker, victim);
    }

    /** Damage-only chip text. Used for always-hit sources (bombs, AoE wands) -
     *  the hit-roll is meaningless because the action lands at the target
     *  tile regardless. For bomb-class sources the chip is the bomb's own
     *  damage, not the equipped weapon's. */
    public static String damageOnly(Mob attacker, Mob victim, Item source) {
        return damageString(attacker, victim, source);
    }

    private static String hitPct(Mob attacker, Mob victim) {
        return (int) Math.round(MobStats.hitChance(attacker, victim) * 100.0) + "%";
    }

    /** Build the "~6-3=~3 dmg" / "~6 dmg" half of a chip. When {@code source}
     *  is non-null, uses the source item's damage range; otherwise falls
     *  back to the attacker's equipped raw range (melee / generic). Routes
     *  through {@link MobStats#netDamageRange(MinMax, MinMax, MinMax)} so
     *  the chip and the actual combat resolution use the identical formula. */
    private static String damageString(Mob attacker, Mob victim, Item source) {
        MinMax raw = source != null
                ? ItemStats.effectiveDamageRange(source,
                        ItemStats.effectiveLevel(source, attacker))
                : MobStats.rawDamageRange(attacker);
        MinMax armor = MobStats.resistRange(victim);
        MinMax ap = MobStats.apDamageRange(attacker);
        MinMax net = MobStats.netDamageRange(raw, armor, ap);
        int netMid = (net.min() + net.max()) / 2;
        int rawMid = (raw.min() + raw.max()) / 2;
        int armorMid = (armor.min() + armor.max()) / 2;
        if (armorMid > 0 && rawMid > 0) {
            return "~" + rawMid + "-" + armorMid + "=~" + netMid + " dmg";
        }
        return "~" + netMid + " dmg";
    }
}
