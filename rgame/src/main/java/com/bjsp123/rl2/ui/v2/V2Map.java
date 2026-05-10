package com.bjsp123.rl2.ui.v2;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.glutils.ShapeRenderer;
import com.bjsp123.rl2.logic.MobRegistry;
import com.bjsp123.rl2.logic.ThemedRoomRegistry;
import com.bjsp123.rl2.model.Level;
import com.bjsp123.rl2.model.Mob;
import com.bjsp123.rl2.model.Tile;
import com.bjsp123.rl2.model.World;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * V2 map screen — schematic of the world's level graph. Each level renders
 * as a small trapezoidal mini-map (slight perspective tilt) coloured by
 * tile type for the explored area, with arrows between depths showing the
 * staircase graph. Tap a visited level to bring up an info panel at the
 * bottom of the window with the level's theme, unique themed rooms, and
 * unique mob species.
 */
public final class V2Map extends V2Screen {

    private final Runnable onBack;
    private final World world;
    private final Rect window = new Rect();

    private static final float BOX_W = 70f;
    private static final float BOX_H = 44f;
    private static final float GAP_X = 12f;
    private static final float GAP_Y = 22f;
    /** Top-edge inset (per side) of each mini-map's trapezoid — pulls the
     *  top of the map inward to fake a "tilted away from viewer"
     *  perspective. Total visible top width = BOX_W − 2 × TOP_INSET. */
    private static final float TOP_INSET = 8f;
    /** Height of the bottom info pane, drawn under the map graph when a
     *  visited level is selected. */
    private static final float INFO_PANE_H = 96f;

    /** Tile palette for the mini-map fill. Floor-like is a light warm
     *  grey; wall / blocker is a darker neutral; chasm is near-black so
     *  void tiles read as missing. Unexplored tiles draw at FOG. */
    private static final Color FLOOR_TINT = new Color(0.78f, 0.74f, 0.62f, 1f);
    private static final Color WALL_TINT  = new Color(0.30f, 0.30f, 0.32f, 1f);
    private static final Color CHASM_TINT = new Color(0.08f, 0.08f, 0.10f, 1f);
    private static final Color FOG_TINT   = new Color(0.18f, 0.18f, 0.20f, 1f);
    private static final Color ARROW_TINT = new Color(1f, 0.82f, 0.40f, 1f);

    /** World index of the currently-selected level, or -1 for none. */
    private int selected = -1;
    /** Hit rect per visited level, populated each frame in
     *  {@link #drawBodyShape} so {@link #onTouchDownInBody} can resolve a
     *  tap to a level index. Parallel to {@link #boxIndex}. Stored in
     *  screen-space (after pan + zoom). */
    private final List<Rect> boxRects   = new ArrayList<>();
    private final List<Integer> boxIndex = new ArrayList<>();

    /** Pan offset applied to the level graph (post-zoom). Drag-in-body
     *  shifts these. Zero by default — graph centred on the window. */
    private float panX, panY;
    /** Zoom factor for the level graph. 1.0 is the design size; the
     *  mouse wheel multiplies / divides this. Clamped to {@link #ZOOM_MIN}
     *  / {@link #ZOOM_MAX}. */
    private float zoom = 1.0f;
    private static final float ZOOM_MIN = 0.4f;
    private static final float ZOOM_MAX = 2.5f;
    private static final float ZOOM_STEP = 1.15f;
    /** Last known cursor / finger position; updated on touchDown so
     *  drag-pan can compute deltas without losing the anchor across
     *  release-then-redrag pairs. */
    private float dragLastX, dragLastY;
    private boolean dragging;
    /** Reusable scissor scratch — the graph viewport's bounding rect.
     *  Computed each frame in drawBodyShape from {@link #graphViewport}
     *  and pushed onto {@link com.badlogic.gdx.scenes.scene2d.utils.ScissorStack}
     *  so a zoomed-out / panned graph can't bleed into the title or
     *  info pane. */
    private final com.badlogic.gdx.math.Rectangle scissorIn  = new com.badlogic.gdx.math.Rectangle();
    private final com.badlogic.gdx.math.Rectangle scissorOut = new com.badlogic.gdx.math.Rectangle();

    public V2Map(UiCtx ctx, Runnable onBack, World world) {
        super(ctx);
        this.onBack = onBack;
        this.world  = world;
    }

    @Override
    protected Rect modalWindow() { return window; }

    @Override
    protected void buildLayout() {
        float vw = ctx.worldW();
        float vh = ctx.worldH();
        float winW = Math.min(420f, vw - 24f);
        float winH = Math.min(Pal.VIRTUAL_H - 120f, vh - 120f);
        float winY = (vh - winH) * 0.5f;
        window.set((vw - winW) * 0.5f, winY, winW, winH);

        back   = new BackBtn(ctx, onBack);
        back.anchorBottomRightOf(window);
        burger = makeBurger();
    }

    @Override
    protected boolean onTouchDownInBody(float vx, float vy) {
        // Reset drag tracking — onTouchDragged will detect motion past the
        // ~2-pixel threshold and start panning then. A clean touch release
        // (no drag) leaves dragging=false, so the box-tap below still fires.
        dragLastX = vx;
        dragLastY = vy;
        dragging  = false;
        for (int i = 0; i < boxRects.size(); i++) {
            if (boxRects.get(i).contains(vx, vy)) {
                int worldIdx = boxIndex.get(i);
                Level lvl = world.levels[worldIdx];
                // Only visited levels open the info pane — unexplored
                // levels have nothing to report.
                if (lvl != null && lvl.visited) {
                    selected = (selected == worldIdx) ? -1 : worldIdx;
                }
                return true;
            }
        }
        // Tap inside the window but outside any box clears the selection
        // and arms the pan-drag tracker so a subsequent drag pans the
        // graph.
        if (graphViewport().contains(vx, vy)) {
            selected = -1;
            return true;   // claim the touch so onTouchDragged routes here
        }
        return false;
    }

    @Override
    protected boolean onTouchDragged(float vx, float vy) {
        if (!graphViewport().contains(vx, vy)
                && !graphViewport().contains(dragLastX, dragLastY)) {
            return false;
        }
        float dx = vx - dragLastX;
        float dy = vy - dragLastY;
        if (!dragging && Math.hypot(dx, dy) < 2f) return true;
        dragging = true;
        panX += dx;
        panY += dy;
        dragLastX = vx;
        dragLastY = vy;
        clampPan();
        return true;
    }

    @Override
    protected boolean onScrolled(float amountY) {
        // Mouse-wheel zoom — zoom out when scrolled down, in when up. A
        // multiplicative step keeps the feel symmetric near zoom=1.
        float prevZoom = zoom;
        if (amountY > 0) zoom /= ZOOM_STEP;
        else             zoom *= ZOOM_STEP;
        if (zoom < ZOOM_MIN) zoom = ZOOM_MIN;
        if (zoom > ZOOM_MAX) zoom = ZOOM_MAX;
        // Anchor the zoom on the graph centre — pan stays the same in
        // logical coords across the change. Good enough; zoom-toward-cursor
        // is a refinement we can add later if it matters.
        if (zoom != prevZoom) clampPan();
        return true;
    }

    @Override
    protected void drawBodyShape(UiCtx ctx) {
        Window.drawShape(ctx, window.x, window.y, window.w, window.h);
        boxRects.clear();
        boxIndex.clear();

        if (world == null || world.levels == null) return;

        // Compute layout bounds.
        float minCol = Float.POSITIVE_INFINITY, maxCol = Float.NEGATIVE_INFINITY;
        float minD = Float.POSITIVE_INFINITY, maxD = Float.NEGATIVE_INFINITY;
        for (Level lvl : world.levels) {
            if (lvl == null) continue;
            if (lvl.mapColumn < minCol) minCol = lvl.mapColumn;
            if (lvl.mapColumn > maxCol) maxCol = lvl.mapColumn;
            if (lvl.depth     < minD)   minD   = lvl.depth;
            if (lvl.depth     > maxD)   maxD   = lvl.depth;
        }
        if (minCol == Float.POSITIVE_INFINITY) return;

        ShapeRenderer s = ctx.shapes;
        Gdx.gl.glEnable(GL20.GL_BLEND);
        Gdx.gl.glBlendFunc(GL20.GL_SRC_ALPHA, GL20.GL_ONE_MINUS_SRC_ALPHA);

        // Scissor-clip the graph rendering to the visible band so a
        // panned / zoomed graph can't bleed into the title or info pane.
        Rect viewport = graphViewport();
        s.flush();
        scissorIn.set(viewport.x, viewport.y, viewport.w, viewport.h);
        ctx.viewport.calculateScissors(s.getTransformMatrix(),
                scissorIn, scissorOut);
        boolean clipped = com.badlogic.gdx.scenes.scene2d.utils.ScissorStack
                .pushScissors(scissorOut);

        // Pass A — connection arrows under the level boxes. Bright accent
        // colour so the staircase graph reads through the dim chrome.
        s.setColor(ARROW_TINT);
        for (Level src : world.levels) {
            if (src == null) continue;
            drawArrowBetween(s, src, src.stairsDownTarget,    minCol, maxCol, minD, maxD);
            drawArrowBetween(s, src, src.stairsDownAltTarget, minCol, maxCol, minD, maxD);
        }

        // Pass B — level mini-maps drawn as trapezoids with tile-type
        // tinted cells. Position + size feed through {@link #transformBox}
        // so pan + zoom apply uniformly.
        float bw = BOX_W * zoom;
        float bh = BOX_H * zoom;
        float ti = TOP_INSET * zoom;
        for (int i = 0; i < world.levels.length; i++) {
            Level lvl = world.levels[i];
            if (lvl == null) continue;
            float[] xy = transformBox(lvl, minCol, maxCol, minD, maxD);
            float bx = xy[0], by = xy[1];
            boolean current = i == world.currentLevelIndex;
            boolean isSel   = i == selected;

            Color border = current ? Pal.ACCENT
                          : isSel  ? Pal.WARN
                          : Pal.BORDER;
            drawTrapezoidBorder(s, bx, by, bw, bh, ti, border);

            if (lvl.visited && lvl.tiles != null) {
                drawMiniMap(s, lvl, bx, by, bw, bh, ti);
            } else {
                fillTrapezoid(s, bx, by, bw, bh, ti, FOG_TINT);
            }

            boxRects.add(new Rect(bx, by, bw, bh));
            boxIndex.add(i);
        }

        s.flush();
        if (clipped) {
            com.badlogic.gdx.scenes.scene2d.utils.ScissorStack.popScissors();
        }

        // Bottom info pane — drawn as a sub-window inside the map window
        // when a visited level is selected. Pure chrome here; text
        // populates in drawBodyText.
        if (selected >= 0 && selected < world.levels.length) {
            Level lvl = world.levels[selected];
            if (lvl != null && lvl.visited) {
                Rect info = infoPaneRect();
                Window.drawShape(ctx, info.x, info.y, info.w, info.h);
            }
        }

        Gdx.gl.glDisable(GL20.GL_BLEND);
    }

    /** Draw a thin line + triangular arrowhead from {@code src}'s box centre
     *  to the box centre of {@code targetIdx} in {@link World#levels}.
     *  Endpoints feed through {@link #transformBox} so the arrow tracks
     *  pan + zoom along with the boxes. No-op when {@code targetIdx} is
     *  out of range or the target slot is null. */
    private void drawArrowBetween(ShapeRenderer s, Level src, int targetIdx,
                                  float minCol, float maxCol,
                                  float minD,   float maxD) {
        if (targetIdx < 0 || targetIdx >= world.levels.length) return;
        Level dst = world.levels[targetIdx];
        if (dst == null) return;
        float bw = BOX_W * zoom, bh = BOX_H * zoom;
        float[] sxy = transformBox(src, minCol, maxCol, minD, maxD);
        float[] dxy = transformBox(dst, minCol, maxCol, minD, maxD);
        float x1 = sxy[0] + bw * 0.5f;
        float y1 = sxy[1] + bh * 0.5f;
        float x2 = dxy[0] + bw * 0.5f;
        float y2 = dxy[1] + bh * 0.5f;

        // Pull endpoints inward so the line + head stop at the box border.
        float dx = x2 - x1, dy = y2 - y1;
        float len = (float) Math.sqrt(dx * dx + dy * dy);
        if (len < 1e-3f) return;
        float ux = dx / len, uy = dy / len;
        float inset = 0.5f * (Math.abs(ux) * bw + Math.abs(uy) * bh) + 1f;
        float ax1 = x1 + ux * inset, ay1 = y1 + uy * inset;
        float ax2 = x2 - ux * inset, ay2 = y2 - uy * inset;
        s.rectLine(ax1, ay1, ax2, ay2, 2f * Math.max(0.5f, zoom));

        // Triangular head at the destination end. Scales with zoom so a
        // zoomed-out graph doesn't have giant arrowheads relative to its
        // shrunken boxes.
        float headLen = 7f * Math.max(0.5f, zoom);
        float headW   = 6f * Math.max(0.5f, zoom);
        float bx = ax2 - ux * headLen;
        float by = ay2 - uy * headLen;
        float px = -uy, py = ux;
        s.triangle(ax2, ay2,
                bx + px * headW * 0.5f, by + py * headW * 0.5f,
                bx - px * headW * 0.5f, by - py * headW * 0.5f);
    }

    /** Screen-space bottom-left corner of {@code lvl}'s box, with pan +
     *  zoom applied. Returns {@code float[2]} = {x, y}. The unscaled
     *  position is computed from {@link #boxX}/{@link #boxY}; the result
     *  is then scaled around the graph centre and offset by the active
     *  pan. */
    private float[] transformBox(Level lvl,
                                 float minCol, float maxCol,
                                 float minD, float maxD) {
        float logX = boxX(lvl, minCol, maxCol);
        float logY = boxY(lvl, minD,   maxD);
        float logCx = logX + BOX_W * 0.5f;
        float logCy = logY + BOX_H * 0.5f;
        Rect vp = graphViewport();
        float pivotX = vp.cx();
        float pivotY = vp.cy();
        float scrCx = pivotX + (logCx - pivotX) * zoom + panX;
        float scrCy = pivotY + (logCy - pivotY) * zoom + panY;
        float bw = BOX_W * zoom;
        float bh = BOX_H * zoom;
        return new float[] { scrCx - bw * 0.5f, scrCy - bh * 0.5f };
    }

    /** Visible band the graph is allowed to occupy — between the title
     *  row and the bottom info-pane reservation. Used for scissor
     *  clipping and pan clamping. */
    private Rect graphViewport() {
        float top    = window.top() - 50f;
        float bottom = window.y + INFO_PANE_H + 16f;
        return new Rect(window.x + 8f, bottom,
                window.w - 16f, top - bottom);
    }

    /** Keep the pan within sane bounds — the graph's bounding box must
     *  retain at least a small overlap with the viewport so the user
     *  can't pan it entirely off-screen. */
    private void clampPan() {
        // Soft cap proportional to viewport — generous, only kicks in
        // when the user pans really far. The graph stays at least
        // half-visible no matter how far the pan tries to go.
        Rect vp = graphViewport();
        float maxPan = Math.max(vp.w, vp.h) * 1.5f;
        if (panX >  maxPan) panX =  maxPan;
        if (panX < -maxPan) panX = -maxPan;
        if (panY >  maxPan) panY =  maxPan;
        if (panY < -maxPan) panY = -maxPan;
    }

    /** Paint the level's tile grid into a trapezoidal footprint. The bottom
     *  of the trapezoid is the full {@code w}; the top is narrowed by
     *  {@code 2 × topInset} so the box reads as if tilted away from the
     *  viewer. Each tile row interpolates its x extent linearly between
     *  bottom and top widths. Cells are colour-coded by tile type for
     *  explored cells; unexplored cells use the fog tint. */
    private void drawMiniMap(ShapeRenderer s, Level lvl,
                             float bx, float by, float w, float h, float topInset) {
        Tile[][] tiles = lvl.tiles;
        boolean[][] explored = lvl.explored;
        int lw = lvl.width, lh = lvl.height;
        if (lh <= 0 || lw <= 0) return;
        // Step values — cellH is constant, cellW shrinks with row.
        float cellH = h / (float) lh;
        for (int ty = 0; ty < lh; ty++) {
            float v = ty / (float) lh;          // 0 = bottom, 1 = top
            float vNext = (ty + 1) / (float) lh;
            // Row x bounds at this y-band's top edge.
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
                        explored != null && explored[tx][ty]);
                s.setColor(tint);
                // Quadrilateral cell — bottom edge wider than top, matching
                // the trapezoid. Two triangles approximate the cell.
                float xR  = xL  + cellW;
                float xRN = xLN + cellWN;
                s.triangle(xL, rowY,
                           xR, rowY,
                           xRN, rowY + rowH);
                s.triangle(xL, rowY,
                           xRN, rowY + rowH,
                           xLN, rowY + rowH);
            }
        }
    }

    /** Tint for one mini-map cell. Floor-like → warm grey; chasm →
     *  near-black; wall / blocking → mid grey; unexplored → fog. */
    private static Color tileTint(Tile t, boolean explored) {
        if (!explored) return FOG_TINT;
        if (t == null) return CHASM_TINT;
        if (t == Tile.CHASM)         return CHASM_TINT;
        if (t.isFloorLike())         return FLOOR_TINT;
        if (t.blocksMovement())      return WALL_TINT;
        // Doors, statues etc — treat as wall-equivalent for the mini-map.
        return WALL_TINT;
    }

    /** Fill the trapezoid uniformly with {@code colour}. Two triangles. */
    private static void fillTrapezoid(ShapeRenderer s,
                                      float x, float y, float w, float h,
                                      float topInset, Color colour) {
        s.setColor(colour);
        float bl = x,                  br = x + w;
        float tl = x + topInset,       tr = x + w - topInset;
        s.triangle(bl, y, br, y, tr, y + h);
        s.triangle(bl, y, tr, y + h, tl, y + h);
    }

    /** Three-line border around the trapezoid using the given border colour. */
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

    @Override
    protected void drawBodyText(UiCtx ctx) {
        TextDraw.centre(ctx, ctx.fontHeader, Pal.ACCENT, "Map",
                window.cx(), window.top() - 22f);
        if (world == null || world.levels == null) return;

        // Depth label centred on each box.
        float minCol = Float.POSITIVE_INFINITY, maxCol = Float.NEGATIVE_INFINITY;
        float minD = Float.POSITIVE_INFINITY, maxD = Float.NEGATIVE_INFINITY;
        for (Level lvl : world.levels) {
            if (lvl == null) continue;
            if (lvl.mapColumn < minCol) minCol = lvl.mapColumn;
            if (lvl.mapColumn > maxCol) maxCol = lvl.mapColumn;
            if (lvl.depth     < minD)   minD   = lvl.depth;
            if (lvl.depth     > maxD)   maxD   = lvl.depth;
        }
        if (minCol == Float.POSITIVE_INFINITY) return;

        // Scissor labels into the same graph viewport as the boxes so
        // labels for off-screen panned levels don't peek into the title /
        // info-pane bands.
        Rect vp = graphViewport();
        ctx.batch.flush();
        scissorIn.set(vp.x, vp.y, vp.w, vp.h);
        ctx.viewport.calculateScissors(ctx.batch.getTransformMatrix(),
                scissorIn, scissorOut);
        boolean labelClipped = com.badlogic.gdx.scenes.scene2d.utils
                .ScissorStack.pushScissors(scissorOut);
        float bw = BOX_W * zoom;
        for (int i = 0; i < world.levels.length; i++) {
            Level lvl = world.levels[i];
            if (lvl == null) continue;
            float[] xy = transformBox(lvl, minCol, maxCol, minD, maxD);
            String label = lvl.visited ? "L" + lvl.depth : "?";
            // Place the label in the lower band of the trapezoid (the
            // wider, closer edge) so it stays legible above the
            // perspective-shrunk top.
            TextDraw.centre(ctx, ctx.fontRegular,
                    lvl.visited ? Pal.WHITE : Pal.DIM,
                    label, xy[0] + bw * 0.5f, xy[1] + 4f * zoom);
        }
        ctx.batch.flush();
        if (labelClipped) {
            com.badlogic.gdx.scenes.scene2d.utils.ScissorStack.popScissors();
        }

        // Bottom info pane text — only when a visited level is selected.
        if (selected >= 0 && selected < world.levels.length) {
            Level lvl = world.levels[selected];
            if (lvl != null && lvl.visited) {
                drawInfoPaneText(ctx, lvl);
            }
        }
    }

    private Rect infoPaneRect() {
        float pad = 12f;
        return new Rect(window.x + pad, window.y + pad,
                window.w - 2 * pad, INFO_PANE_H);
    }

    private void drawInfoPaneText(UiCtx ctx, Level lvl) {
        Rect info = infoPaneRect();
        float left = info.x + 12f;
        float top  = info.top() - 18f;
        // Header: depth + theme.
        String header = "L" + lvl.depth;
        if (lvl.theme != null) {
            header += "   " + lvl.theme.name().toLowerCase();
        }
        TextDraw.left(ctx, ctx.fontRegular, Pal.ACCENT, header, left, top);
        top -= 18f;

        // Unique themed rooms — scan level.rooms for rooms whose kind is
        // flagged unique in the registry.
        String roomsLine = collectUniqueRooms(lvl);
        if (!roomsLine.isEmpty()) {
            TextDraw.left(ctx, ctx.fontRegular, Pal.WHITE,
                    "Rooms: " + roomsLine, left, top);
            top -= 16f;
        }

        // Unique mob species present.
        String mobsLine = collectUniqueMobs(lvl);
        if (!mobsLine.isEmpty()) {
            TextDraw.left(ctx, ctx.fontRegular, Pal.WHITE,
                    "Mobs:  " + mobsLine, left, top);
        }
    }

    private static String collectUniqueRooms(Level lvl) {
        if (lvl.rooms == null || lvl.rooms.isEmpty()) return "";
        Set<String> seen = new HashSet<>();
        StringBuilder sb = new StringBuilder();
        for (Level.RoomSnapshot r : lvl.rooms) {
            if (r == null || r.kind == null) continue;
            var def = ThemedRoomRegistry.get(r.kind);
            if (def == null || !def.unique) continue;
            String label = r.kind.toLowerCase().replace('_', ' ');
            if (!seen.add(label)) continue;
            if (sb.length() > 0) sb.append(", ");
            sb.append(label);
        }
        return sb.toString();
    }

    private static String collectUniqueMobs(Level lvl) {
        if (lvl.mobs == null || lvl.mobs.isEmpty()) return "";
        Set<String> seen = new HashSet<>();
        StringBuilder sb = new StringBuilder();
        for (Mob m : lvl.mobs) {
            if (m == null || m.mobType == null) continue;
            var def = MobRegistry.get(m.mobType);
            if (def == null || !def.unique) continue;
            String label = m.name != null && !m.name.isEmpty()
                    ? m.name : m.mobType.toLowerCase();
            if (!seen.add(label)) continue;
            if (sb.length() > 0) sb.append(", ");
            sb.append(label);
        }
        return sb.toString();
    }

    private float boxX(Level lvl, float minCol, float maxCol) {
        float n = maxCol - minCol;
        float gridW = (n + 1) * BOX_W + n * GAP_X;
        float baseX = window.cx() - gridW * 0.5f;
        return baseX + (lvl.mapColumn - minCol) * (BOX_W + GAP_X);
    }
    private float boxY(Level lvl, float minD, float maxD) {
        // Reserve the bottom of the window for the info pane; the level
        // graph fills the remaining vertical space above it.
        float topY = window.top() - 60f;
        float bottomY = window.y + INFO_PANE_H + 20f;
        int rows = (int) Math.max(1f, maxD - minD + 1);
        float rowSpan = BOX_H + GAP_Y;
        float graphH = rows * rowSpan - GAP_Y;
        float startY = Math.max(bottomY, topY - graphH);
        return startY + (rows - 1 - (int) (lvl.depth - minD)) * rowSpan;
    }

    @Override
    protected void onEscape() { onBack.run(); }
}
