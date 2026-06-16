package com.bjsp123.rl2.ui.v2;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.glutils.ShapeRenderer;
import com.bjsp123.rl2.model.Level;
import com.bjsp123.rl2.model.Tile;
import com.bjsp123.rl2.model.World;

import java.util.ArrayList;
import java.util.List;

/**
 * Reusable renderer for the world's level graph - the schematic of every floor
 * laid out by depth (row) and {@code mapColumn} (column), each a small
 * trapezoidal mini-map tinted by tile type, with staircase arrows between
 * depths and a swirling void backdrop.
 *
 * <p>Extracted from {@link V2Map} so the same look can be reused outside the
 * map screen (RL-56 intro cinematic). The view is stateless between frames -
 * the caller sets the per-frame inputs ({@link #world}, {@link #layoutArea},
 * {@link #viewport}, pan/zoom, indices, animation clocks) and calls
 * {@link #draw(UiCtx)}; hit rects + unvisited-box centres are published back
 * into the public lists for the caller's input + text passes.
 *
 * <p>{@code layoutArea} is the rectangle the box grid is centred within (so the
 * caller can reserve chrome like a title row or info pane); {@code viewport} is
 * the scissor-clipped band the graph is allowed to paint into. For a
 * full-screen cinematic the two are the same rect.
 */
public final class WorldGraphView {

    public static final float BOX_W = 70f;
    public static final float BOX_H = 44f;
    public static final float GAP_X = 12f;
    public static final float GAP_Y = 22f;
    /** Top-edge inset (per side) of each mini-map's trapezoid - fakes a
     *  "tilted away from viewer" perspective. */
    public static final float TOP_INSET = 8f;

    private static final Color CHASM_TINT = new Color(0.08f, 0.08f, 0.10f, 1f);
    private static final Color FOG_TINT   = new Color(0.18f, 0.18f, 0.20f, 1f);
    private static final Color ARROW_TINT = new Color(1f, 0.82f, 0.40f, 1f);

    // --- Per-frame inputs (set by the caller before draw) -------------------
    public World world;
    public Rect  layoutArea = new Rect();
    public Rect  viewport   = new Rect();
    public float panX, panY;
    public float zoom = 1.0f;
    /** World index of the selected level, or -1. Drawn with the warn border. */
    public int selected = -1;
    /** World index of the current level. Drawn with the accent border. */
    public int currentIndex = -1;
    /** Wall-clock accumulators driving the swirl backdrop + beacon pulse. */
    public float swirlT, beaconPulseT;

    // --- Per-frame outputs (read by the caller after draw) ------------------
    public final List<Rect>    boxRects   = new ArrayList<>();
    public final List<Integer> boxIndex   = new ArrayList<>();
    public final List<Rect>    beaconRects  = new ArrayList<>();
    public final List<int[]>   beaconRefs   = new ArrayList<>();   // {levelIdx, tileX, tileY}
    public final List<Boolean> beaconActive = new ArrayList<>();
    public final List<float[]> unvisitedCenters = new ArrayList<>();

    // Cached layout bounds, recomputed each draw.
    private float minCol, maxCol, minD, maxD;

    private final com.badlogic.gdx.math.Rectangle scissorIn  = new com.badlogic.gdx.math.Rectangle();
    private final com.badlogic.gdx.math.Rectangle scissorOut = new com.badlogic.gdx.math.Rectangle();

    /** Per-theme (floor, wall) tints. Floor is the lighter, more saturated
     *  half; wall the darker neutral. */
    private static Color[] themePalette(Level.VisualTheme t) {
        if (t == null) t = Level.VisualTheme.CRYSTAL;
        return switch (t) {
            case CRYSTAL  -> new Color[] {
                    new Color(0.78f, 0.74f, 0.62f, 1f),
                    new Color(0.36f, 0.32f, 0.30f, 1f)
            };
            case CONCRETE -> new Color[] {
                    new Color(0.70f, 0.72f, 0.74f, 1f),
                    new Color(0.28f, 0.30f, 0.34f, 1f)
            };
            case SHINY    -> new Color[] {
                    new Color(0.62f, 0.78f, 0.82f, 1f),
                    new Color(0.22f, 0.40f, 0.52f, 1f)
            };
            case GOTHIC   -> new Color[] {
                    new Color(0.52f, 0.44f, 0.50f, 1f),
                    new Color(0.22f, 0.16f, 0.22f, 1f)
            };
        };
    }

    /** Recompute the (col, depth) bounds across all levels. Returns false when
     *  there is nothing to draw. */
    private boolean computeBounds() {
        if (world == null || world.levels == null) return false;
        minCol = Float.POSITIVE_INFINITY; maxCol = Float.NEGATIVE_INFINITY;
        minD = Float.POSITIVE_INFINITY;   maxD = Float.NEGATIVE_INFINITY;
        for (Level lvl : world.levels) {
            if (lvl == null) continue;
            if (lvl.mapColumn < minCol) minCol = lvl.mapColumn;
            if (lvl.mapColumn > maxCol) maxCol = lvl.mapColumn;
            if (lvl.depth     < minD)   minD   = lvl.depth;
            if (lvl.depth     > maxD)   maxD   = lvl.depth;
        }
        if (minCol == Float.POSITIVE_INFINITY) return false;
        return true;
    }

    /** Pan offset that centres {@code levelIndex}'s box in the viewport at the
     *  current zoom. Returns {panX, panY}; caller assigns + clamps. */
    public float[] panToCenter(int levelIndex) {
        if (!computeBounds()) return new float[] { 0f, 0f };
        if (levelIndex < 0 || world.levels == null
                || levelIndex >= world.levels.length
                || world.levels[levelIndex] == null) {
            return new float[] { 0f, 0f };
        }
        Level cur = world.levels[levelIndex];
        float logCx = boxX(cur) + BOX_W * 0.5f;
        float logCy = boxY(cur) + BOX_H * 0.5f;
        return new float[] {
                -(logCx - viewport.cx()) * zoom,
                -(logCy - viewport.cy()) * zoom
        };
    }

    /** Shape pass: swirl backdrop, arrows, mini-map boxes, beacons. Clipped to
     *  {@link #viewport}. Populates the hit + unvisited-centre lists. */
    public void draw(UiCtx ctx) {
        boxRects.clear();
        boxIndex.clear();
        beaconRects.clear();
        beaconRefs.clear();
        beaconActive.clear();
        unvisitedCenters.clear();
        if (!computeBounds()) return;

        ShapeRenderer s = ctx.shapes;
        Gdx.gl.glEnable(GL20.GL_BLEND);
        Gdx.gl.glBlendFunc(GL20.GL_SRC_ALPHA, GL20.GL_ONE_MINUS_SRC_ALPHA);

        s.flush();
        scissorIn.set(viewport.x, viewport.y, viewport.w, viewport.h);
        ctx.viewport.calculateScissors(s.getTransformMatrix(), scissorIn, scissorOut);
        boolean clipped = com.badlogic.gdx.scenes.scene2d.utils.ScissorStack
                .pushScissors(scissorOut);

        drawSwirlBackground(s, viewport);

        // Pass A - connection arrows under the boxes.
        s.setColor(ARROW_TINT);
        for (Level src : world.levels) {
            if (src == null) continue;
            drawArrowBetween(s, src, src.stairsDownTarget);
            drawArrowBetween(s, src, src.stairsDownAltTarget);
        }

        // Pass B - level mini-maps as trapezoids.
        float bw = BOX_W * zoom;
        float bh = BOX_H * zoom;
        float ti = TOP_INSET * zoom;
        for (int i = 0; i < world.levels.length; i++) {
            Level lvl = world.levels[i];
            if (lvl == null) continue;
            float[] xy = transformBox(lvl);
            float bx = xy[0], by = xy[1];
            boolean current = i == currentIndex;
            boolean isSel   = i == selected;
            boolean unvisited = !lvl.visited;
            Color border = current ? UIVars.ACCENT
                          : isSel  ? UIVars.TEXT_WARN
                          : unvisited ? UIVars.BORDER_INNER
                                      : UIVars.BORDER_MID;
            drawTrapezoidBorder(s, bx, by, bw, bh, ti, border);

            if (lvl.visited && lvl.tiles != null) {
                drawMiniMap(s, lvl, bx, by, bw, bh, ti);
            } else {
                fillTrapezoid(s, bx, by, bw, bh, ti, FOG_TINT);
                unvisitedCenters.add(new float[] { bx + bw * 0.5f, by + bh * 0.5f });
            }

            boxRects.add(new Rect(bx, by, bw, bh));
            boxIndex.add(i);

            if (lvl.visited && lvl.tiles != null) {
                drawBeaconsOnBox(s, lvl, i, bx, by, bw, bh, ti);
            }
        }

        s.flush();
        if (clipped) {
            com.badlogic.gdx.scenes.scene2d.utils.ScissorStack.popScissors();
        }
        Gdx.gl.glDisable(GL20.GL_BLEND);
    }

    /** Paint a "?" glyph over each unvisited box. Caller invokes this from its
     *  batch/text pass (the lists are filled by {@link #draw}). */
    public void drawUnvisitedGlyphs(UiCtx ctx) {
        for (float[] cxy : unvisitedCenters) {
            // The shape pass scissor-clips the boxes to the viewport, but this
            // text pass has no scissor - skip glyphs whose box centre is outside
            // the viewport so a "?" can't bleed onto the title / chrome.
            if (!viewport.contains(cxy[0], cxy[1])) continue;
            TextDraw.centre(ctx, ctx.fontHeader, UIVars.BORDER_MID,
                    "?", cxy[0], cxy[1] + ctx.headerLineH() * 0.35f);
        }
    }

    private void drawArrowBetween(ShapeRenderer s, Level src, int targetIdx) {
        if (targetIdx < 0 || targetIdx >= world.levels.length) return;
        Level dst = world.levels[targetIdx];
        if (dst == null) return;
        float bw = BOX_W * zoom, bh = BOX_H * zoom;
        float[] sxy = transformBox(src);
        float[] dxy = transformBox(dst);
        float x1 = sxy[0] + bw * 0.5f;
        float y1 = sxy[1] + bh * 0.5f;
        float x2 = dxy[0] + bw * 0.5f;
        float y2 = dxy[1] + bh * 0.5f;
        float dx = x2 - x1, dy = y2 - y1;
        float len = (float) Math.sqrt(dx * dx + dy * dy);
        if (len < 1e-3f) return;
        float ux = dx / len, uy = dy / len;
        float inset = 0.5f * (Math.abs(ux) * bw + Math.abs(uy) * bh) + 1f;
        float ax1 = x1 + ux * inset, ay1 = y1 + uy * inset;
        float ax2 = x2 - ux * inset, ay2 = y2 - uy * inset;
        s.rectLine(ax1, ay1, ax2, ay2, 2f * Math.max(0.5f, zoom));
        float headLen = 7f * Math.max(0.5f, zoom);
        float headW   = 6f * Math.max(0.5f, zoom);
        float bx = ax2 - ux * headLen;
        float by = ay2 - uy * headLen;
        float px = -uy, py = ux;
        s.triangle(ax2, ay2,
                bx + px * headW * 0.5f, by + py * headW * 0.5f,
                bx - px * headW * 0.5f, by - py * headW * 0.5f);
    }

    /** Screen-space bottom-left corner of {@code lvl}'s box, with pan + zoom
     *  applied (pivoting on the viewport centre). */
    public float[] transformBox(Level lvl) {
        float logCx = boxX(lvl) + BOX_W * 0.5f;
        float logCy = boxY(lvl) + BOX_H * 0.5f;
        float pivotX = viewport.cx();
        float pivotY = viewport.cy();
        float scrCx = pivotX + (logCx - pivotX) * zoom + panX;
        float scrCy = pivotY + (logCy - pivotY) * zoom + panY;
        float bw = BOX_W * zoom;
        float bh = BOX_H * zoom;
        return new float[] { scrCx - bw * 0.5f, scrCy - bh * 0.5f };
    }

    private float boxX(Level lvl) {
        float n = maxCol - minCol;
        float gridW = (n + 1) * BOX_W + n * GAP_X;
        float baseX = layoutArea.cx() - gridW * 0.5f;
        return baseX + (lvl.mapColumn - minCol) * (BOX_W + GAP_X);
    }

    private float boxY(Level lvl) {
        float topY = layoutArea.top();
        float bottomY = layoutArea.y;
        int rows = (int) Math.max(1f, maxD - minD + 1);
        float rowSpan = BOX_H + GAP_Y;
        float graphH = rows * rowSpan - GAP_Y;
        float startY = Math.max(bottomY, topY - graphH);
        return startY + (rows - 1 - (int) (lvl.depth - minD)) * rowSpan;
    }

    private void drawMiniMap(ShapeRenderer s, Level lvl,
                             float bx, float by, float w, float h, float topInset) {
        Tile[][] tiles = lvl.tiles;
        boolean[][] explored = lvl.explored;
        int lw = lvl.width, lh = lvl.height;
        if (lh <= 0 || lw <= 0) return;
        Color[] palette = themePalette(lvl.theme);
        float cellH = h / (float) lh;
        for (int ty = 0; ty < lh; ty++) {
            float v = ty / (float) lh;
            float vNext = (ty + 1) / (float) lh;
            float rowLeftX  = bx + topInset * v;
            float rowRightX = bx + w - topInset * v;
            float rowLeftXN  = bx + topInset * vNext;
            float rowRightXN = bx + w - topInset * vNext;
            float rowY = by + h * v;
            float rowH = cellH;
            float cellW = (rowRightX - rowLeftX) / lw;
            float cellWN = (rowRightXN - rowLeftXN) / lw;
            for (int tx = 0; tx < lw; tx++) {
                float u = tx / (float) lw;
                float xL  = rowLeftX  + (rowRightX  - rowLeftX)  * u;
                float xLN = rowLeftXN + (rowRightXN - rowLeftXN) * u;
                Color tint = tileTint(tiles[tx][ty],
                        explored != null && explored[tx][ty], palette);
                s.setColor(tint);
                float xR  = xL  + cellW;
                float xRN = xLN + cellWN;
                s.triangle(xL, rowY, xR, rowY, xRN, rowY + rowH);
                s.triangle(xL, rowY, xRN, rowY + rowH, xLN, rowY + rowH);
            }
        }
    }

    private void drawSwirlBackground(ShapeRenderer s, Rect vp) {
        s.setColor(0.04f, 0.04f, 0.07f, 1f);
        s.rect(vp.x, vp.y, vp.w, vp.h);
        float t = swirlT;
        float cx = vp.cx();
        float cy = vp.cy();
        float orbitR = Math.min(vp.w, vp.h) * 0.45f;
        drawSwirlBlob(s,
                cx + (float) Math.cos(t * 0.18f)        * orbitR * 0.7f,
                cy + (float) Math.sin(t * 0.18f)        * orbitR * 0.4f,
                Math.min(vp.w, vp.h) * 0.55f,
                0.18f, 0.10f, 0.28f, 0.55f);
        drawSwirlBlob(s,
                cx + (float) Math.cos(t * 0.27f + 2.1f) * orbitR * 0.5f,
                cy + (float) Math.sin(t * 0.22f + 0.7f) * orbitR * 0.6f,
                Math.min(vp.w, vp.h) * 0.42f,
                0.10f, 0.14f, 0.32f, 0.50f);
        drawSwirlBlob(s,
                cx + (float) Math.cos(t * 0.12f + 4.0f) * orbitR * 0.8f,
                cy + (float) Math.sin(t * 0.16f + 3.3f) * orbitR * 0.5f,
                Math.min(vp.w, vp.h) * 0.36f,
                0.22f, 0.08f, 0.20f, 0.45f);
    }

    private void drawSwirlBlob(ShapeRenderer s, float cx, float cy,
                               float outerR, float r, float g, float b, float alpha) {
        int rings = 5;
        for (int i = 0; i < rings; i++) {
            float ringR  = outerR * (1f - i / (float) rings);
            float ringA  = alpha * ((i + 1) / (float) rings);
            s.setColor(r, g, b, ringA);
            s.circle(cx, cy, ringR);
        }
    }

    private void drawBeaconsOnBox(ShapeRenderer s, Level lvl, int worldIdx,
                                  float bx, float by, float bw, float bh, float topInset) {
        int lw = lvl.width, lh = lvl.height;
        if (lw <= 0 || lh <= 0) return;
        float pulse = 0.5f + 0.5f * (float) Math.sin(beaconPulseT * 3.0);
        float activeScale = 1.0f + 0.35f * pulse;
        float baseArm = 5f * Math.max(0.5f, zoom);
        float baseThick = 2f * Math.max(0.5f, zoom);
        for (int ty = 0; ty < lh; ty++) {
            for (int tx = 0; tx < lw; tx++) {
                Tile t = lvl.tiles[tx][ty];
                if (t != Tile.BEACON_INACTIVE && t != Tile.BEACON_ACTIVE) continue;
                float u = (tx + 0.5f) / (float) lw;
                float v = (ty + 0.5f) / (float) lh;
                float rowLeftX  = bx + topInset * v;
                float rowRightX = bx + bw - topInset * v;
                float cx = rowLeftX + (rowRightX - rowLeftX) * u;
                float cy = by + bh * v;
                boolean active = (t == Tile.BEACON_ACTIVE);
                float arm = active ? baseArm * activeScale : baseArm;
                float thick = baseThick;
                s.setColor(0f, 0f, 0f, 1f);
                s.rect(cx - arm - 1f, cy - thick * 0.5f - 1f,
                       (arm + 1f) * 2f, thick + 2f);
                s.rect(cx - thick * 0.5f - 1f, cy - arm - 1f,
                       thick + 2f, (arm + 1f) * 2f);
                if (active) s.setColor(1f, 0.85f, 0.30f, 0.6f + 0.4f * pulse);
                else        s.setColor(0.72f, 0.74f, 0.78f, 1f);
                s.rect(cx - arm, cy - thick * 0.5f, arm * 2f, thick);
                s.rect(cx - thick * 0.5f, cy - arm, thick, arm * 2f);
                float hitArm = baseArm * (active ? 1.35f : 1f) + 2f;
                beaconRects.add(new Rect(cx - hitArm, cy - hitArm,
                        hitArm * 2f, hitArm * 2f));
                beaconRefs.add(new int[]{worldIdx, tx, ty});
                beaconActive.add(active);
            }
        }
    }

    private static Color tileTint(Tile t, boolean explored, Color[] palette) {
        if (!explored) return FOG_TINT;
        if (t == null) return CHASM_TINT;
        if (t == Tile.CHASM)         return CHASM_TINT;
        if (t.isFloorLike())         return palette[0];
        if (t.blocksMovement())      return palette[1];
        return palette[1];
    }

    private static void fillTrapezoid(ShapeRenderer s,
                                      float x, float y, float w, float h,
                                      float topInset, Color colour) {
        s.setColor(colour);
        float bl = x,                  br = x + w;
        float tl = x + topInset,       tr = x + w - topInset;
        s.triangle(bl, y, br, y, tr, y + h);
        s.triangle(bl, y, tr, y + h, tl, y + h);
    }

    private void drawTrapezoidBorder(ShapeRenderer s,
                                     float x, float y, float w, float h,
                                     float topInset, Color border) {
        s.setColor(border);
        float bl = x,                  br = x + w;
        float tl = x + topInset,       tr = x + w - topInset;
        s.rectLine(bl, y,        br, y,         2f);
        s.rectLine(tl, y + h,    tr, y + h,     2f);
        s.rectLine(bl, y,        tl, y + h,     2f);
        s.rectLine(br, y,        tr, y + h,     2f);
    }
}
