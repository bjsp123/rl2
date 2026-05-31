package com.bjsp123.rl2.ui.v2;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.glutils.ShapeRenderer;
import com.bjsp123.rl2.logic.TextCatalog;
import com.bjsp123.rl2.logic.Registries;
import com.bjsp123.rl2.model.Level;
import com.bjsp123.rl2.model.Mob;
import com.bjsp123.rl2.model.Point;
import com.bjsp123.rl2.model.Tile;
import com.bjsp123.rl2.Rl2Game;
import com.bjsp123.rl2.model.World;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * V2 map screen - schematic of the world's level graph. Each level renders
 * as a small trapezoidal mini-map (slight perspective tilt) coloured by
 * tile type for the explored area, with arrows between depths showing the
 * staircase graph. Tap a visited level to bring up an info panel at the
 * bottom of the window with the level's theme, unique themed rooms, and
 * unique mob species.
 */
public final class V2Map extends V2Screen {

    private final Rl2Game game;
    private final Runnable onBack;
    private final World world;
    private final Rect window = new Rect();

    private static final float BOX_W = 70f;
    private static final float BOX_H = 44f;
    private static final float GAP_X = 12f;
    private static final float GAP_Y = 22f;
    /** Top-edge inset (per side) of each mini-map's trapezoid - pulls the
     *  top of the map inward to fake a "tilted away from viewer"
     *  perspective. Total visible top width = BOX_W - 2 x TOP_INSET. */
    private static final float TOP_INSET = 8f;
    /** Height of the bottom info pane, drawn under the map graph when a
     *  visited level is selected. */
    private static final float INFO_PANE_H = 96f;

    /** Chasm + fog tints don't vary by theme - voids and unexplored regions
     *  read the same regardless of the surrounding biome. The wall/floor
     *  tints are per-{@link Level.VisualTheme} via {@link #themePalette}. */
    private static final Color CHASM_TINT = new Color(0.08f, 0.08f, 0.10f, 1f);
    private static final Color FOG_TINT   = new Color(0.18f, 0.18f, 0.20f, 1f);
    private static final Color ARROW_TINT = new Color(1f, 0.82f, 0.40f, 1f);

    /** Per-theme (floor, wall) tints. Floor is the lighter, more saturated
     *  half so the room outlines pop; wall is the darker neutral. Picked
     *  by reading the level's {@link Level#theme} field when a mini-map
     *  cell is painted. */
    private static Color[] themePalette(Level.VisualTheme t) {
        if (t == null) t = Level.VisualTheme.CRYSTAL;
        return switch (t) {
            case CRYSTAL  -> new Color[] {
                    new Color(0.78f, 0.74f, 0.62f, 1f),  // floor: warm sand
                    new Color(0.36f, 0.32f, 0.30f, 1f)   // wall:  dark warm grey
            };
            case CONCRETE -> new Color[] {
                    new Color(0.70f, 0.72f, 0.74f, 1f),  // floor: pale slate
                    new Color(0.28f, 0.30f, 0.34f, 1f)   // wall:  cool dark grey
            };
            case SHINY    -> new Color[] {
                    new Color(0.62f, 0.78f, 0.82f, 1f),  // floor: pale teal
                    new Color(0.22f, 0.40f, 0.52f, 1f)   // wall:  deep teal
            };
            case GOTHIC   -> new Color[] {
                    new Color(0.52f, 0.44f, 0.50f, 1f),  // floor: dusty mauve
                    new Color(0.22f, 0.16f, 0.22f, 1f)   // wall:  near-black violet
            };
        };
    }

    /** World index of the currently-selected level, or -1 for none. */
    private int selected = -1;
    /** Hit rect per visited level, populated each frame in
     *  {@link #drawBodyShape} so {@link #onTouchDownInBody} can resolve a
     *  tap to a level index. Parallel to {@link #boxIndex}. Stored in
     *  screen-space (after pan + zoom). */
    private final List<Rect> boxRects   = new ArrayList<>();
    private final List<Integer> boxIndex = new ArrayList<>();

    /** One entry per beacon (any state) visible on the map. Parallel arrays
     *  with {@link #beaconRects} - populated each frame in {@link #drawBodyShape}
     *  so {@link #onTouchDownInBody} can resolve a tap on a plus-sign to a
     *  (levelIndex, beaconPos) target. Only {@link #beaconActive}-true
     *  entries are valid teleport targets. */
    private final List<Rect> beaconRects = new ArrayList<>();
    private final List<int[]> beaconRefs = new ArrayList<>();   // {levelIdx, tileX, tileY}
    private final List<Boolean> beaconActive = new ArrayList<>();

    /** Centre (x, y) of every unvisited-level box, populated by
     *  {@link #drawBodyShape} so {@link #drawBodyText} can paint a "?" glyph
     *  on top without re-walking the graph layout. */
    private final List<float[]> unvisitedCenters = new ArrayList<>();

    /** Wall-clock accumulator for the pulsing-plus-sign animation. Driven
     *  by {@code Gdx.graphics.getDeltaTime()} in {@link #drawBodyShape}. */
    private float beaconPulseT;
    /** Wall-clock accumulator for the swirling backdrop blobs. Same dt as
     *  {@link #beaconPulseT}; a separate field keeps the swirl phase
     *  independent of the plus-sign pulse. */
    private float bgSwirlT;

    /** Open / closed flag for the teleport-confirmation modal. While
     *  {@code true} the rest of the map screen ignores input - the only
     *  active controls are the Yes / No buttons inside the popup. */
    private boolean confirmOpen;
    /** Destination level index + beacon position that the player is about
     *  to teleport to once they confirm. Set when an active beacon is
     *  clicked; consumed (or discarded) by the Yes / No handler. */
    private int   pendingDestLevel = -1;
    private Point pendingBeaconPos;
    /** Hit rects for the confirmation Yes / No buttons. Populated each
     *  frame in {@link #drawBodyShape} when {@link #confirmOpen} is set. */
    private final Rect confirmYes = new Rect();
    private final Rect confirmNo  = new Rect();
    /** Confirmation popup geometry. Recomputed each frame from the map
     *  window so it tracks resizes. */
    private final Rect confirmPanel = new Rect();

    /** Pan offset applied to the level graph (post-zoom). Drag-in-body
     *  shifts these. Zero by default - graph centred on the window. */
    private float panX, panY;
    /** Zoom factor for the level graph. Snapped to one of {@link #ZOOM_STEPS}
     *  so the map jumps cleanly between four well-defined sizes instead of
     *  drifting through continuous factors. */
    private float zoom = 1.0f;
    /** Discrete zoom steps the wheel/pinch cycles through. Indexed by
     *  {@link #zoomIndex}; defaults to index 1 (1x). */
    private static final float[] ZOOM_STEPS = { 0.5f, 1.0f, 2.0f, 3.0f };
    private int zoomIndex = 1;
    /** Last known cursor / finger position; updated on touchDown so
     *  drag-pan can compute deltas without losing the anchor across
     *  release-then-redrag pairs. */
    private float dragLastX, dragLastY;
    private boolean dragging;
    /** Reusable scissor scratch - the graph viewport's bounding rect.
     *  Computed each frame in drawBodyShape from {@link #graphViewport}
     *  and pushed onto {@link com.badlogic.gdx.scenes.scene2d.utils.ScissorStack}
     *  so a zoomed-out / panned graph can't bleed into the title or
     *  info pane. */
    private final com.badlogic.gdx.math.Rectangle scissorIn  = new com.badlogic.gdx.math.Rectangle();
    private final com.badlogic.gdx.math.Rectangle scissorOut = new com.badlogic.gdx.math.Rectangle();

    public V2Map(Rl2Game game, UiCtx ctx, Runnable onBack, World world) {
        super(ctx);
        this.game   = game;
        this.onBack = onBack;
        this.world  = world;
    }

    @Override
    protected Rect modalWindow() { return window; }

    @Override protected String screenId() { return "map"; }

    @Override
    protected void buildLayout() {
        float vw = ctx.worldW();
        float vh = ctx.worldH();
        float winW = Math.min(420f, vw - UIVars.PAD_MODAL);
        float winH = Math.min(UIVars.VIRTUAL_H - 120f, vh - 120f);
        float winY = (vh - winH) * 0.5f;
        window.set((vw - winW) * 0.5f, winY, winW, winH);

        back   = new BackBtn(ctx, onBack);
        burger = makeBurger();
        addStandardBurgerItems(game);
    }

    @Override
    protected boolean onTouchDownInBody(float vx, float vy) {
        // Reset drag tracking - onTouchDragged will detect motion past the
        // ~2-pixel threshold and start panning then. A clean touch release
        // (no drag) leaves dragging=false, so the box-tap below still fires.
        dragLastX = vx;
        dragLastY = vy;
        dragging  = false;
        // While the confirmation popup is open, ALL input goes through its
        // Yes / No buttons - no panning, no beacon clicks, no level
        // selection. Touch outside the popup is swallowed so the player
        // can't accidentally close it by tapping the map.
        if (confirmOpen) {
            if (confirmYes.contains(vx, vy)) {
                confirmTeleport();
            } else if (confirmNo.contains(vx, vy)) {
                cancelTeleportConfirm();
            }
            return true;
        }
        // Beacon plus-signs take priority over the level box behind them -
        // a tap on an active beacon opens the teleport confirmation, on
        // an inactive one does nothing (eaten so it doesn't fall through
        // to box selection).
        for (int i = 0; i < beaconRects.size(); i++) {
            if (!beaconRects.get(i).contains(vx, vy)) continue;
            if (!Boolean.TRUE.equals(beaconActive.get(i))) return true;
            int[] ref = beaconRefs.get(i);
            // Out-of-orbs: silently swallow the click. The orb counter at
            // the top of the screen tells the player why nothing happened.
            if (teleportOrbCount() <= 0) return true;
            pendingDestLevel = ref[0];
            pendingBeaconPos = new Point(ref[1], ref[2]);
            confirmOpen      = true;
            return true;
        }
        for (int i = 0; i < boxRects.size(); i++) {
            if (boxRects.get(i).contains(vx, vy)) {
                int worldIdx = boxIndex.get(i);
                Level lvl = world.levels[worldIdx];
                // Only visited levels open the info pane - unexplored
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
        // Mouse-wheel zoom - jump to the next discrete step in or out. Wheel
        // up (negative amountY) -> larger; down -> smaller. Pan anchored on
        // the graph centre after the step lands.
        int prev = zoomIndex;
        if (amountY > 0) zoomIndex = Math.max(0, zoomIndex - 1);
        else             zoomIndex = Math.min(ZOOM_STEPS.length - 1, zoomIndex + 1);
        if (zoomIndex != prev) {
            zoom = ZOOM_STEPS[zoomIndex];
            clampPan();
        }
        return true;
    }

    @Override
    protected void drawBodyShape(UiCtx ctx) {
        Window.drawShape(ctx, window.x, window.y, window.w, window.h);
        boxRects.clear();
        boxIndex.clear();
        beaconRects.clear();
        beaconRefs.clear();
        beaconActive.clear();
        unvisitedCenters.clear();
        float dt = Gdx.graphics.getDeltaTime();
        beaconPulseT += dt;
        bgSwirlT     += dt;

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
        // Border around the scrollable viewport - drawn BEFORE scissor
        // pushes so the border itself stays visible. 1-px frame in the
        // standard mid-tone so the boundary reads clearly even when the
        // graph is empty.
        s.setColor(UIVars.BORDER_MID);
        s.rect(viewport.x,                  viewport.y,                  viewport.w, 1f);
        s.rect(viewport.x,                  viewport.y + viewport.h - 1, viewport.w, 1f);
        s.rect(viewport.x,                  viewport.y,                  1f,         viewport.h);
        s.rect(viewport.x + viewport.w - 1, viewport.y,                  1f,         viewport.h);
        s.flush();
        scissorIn.set(viewport.x, viewport.y, viewport.w, viewport.h);
        ctx.viewport.calculateScissors(s.getTransformMatrix(),
                scissorIn, scissorOut);
        boolean clipped = com.badlogic.gdx.scenes.scene2d.utils.ScissorStack
                .pushScissors(scissorOut);

        // Background: solid near-black fill plus a few slowly orbiting soft
        // dark-purple blobs so the viewport feels like a deep void rather
        // than a flat panel. Drawn first inside the scissor so everything
        // else paints on top.
        drawSwirlBackground(s, viewport);

        // Pass A - connection arrows under the level boxes. Bright accent
        // colour so the staircase graph reads through the dim chrome.
        s.setColor(ARROW_TINT);
        for (Level src : world.levels) {
            if (src == null) continue;
            drawArrowBetween(s, src, src.stairsDownTarget,    minCol, maxCol, minD, maxD);
            drawArrowBetween(s, src, src.stairsDownAltTarget, minCol, maxCol, minD, maxD);
        }

        // Pass B - level mini-maps drawn as trapezoids with tile-type
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

            // Unvisited levels read as "unknown" - dimmer border so they
            // don't compete with visited boxes for the eye, and a "?" glyph
            // painted on top later in drawBodyText.
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
                // Stash this box's centre for the "?" glyph painted in the
                // text pass (drawBodyText) so we don't have to re-walk the
                // graph layout.
                unvisitedCenters.add(new float[] { bx + bw * 0.5f, by + bh * 0.5f });
            }

            boxRects.add(new Rect(bx, by, bw, bh));
            boxIndex.add(i);

            // Beacon plus-signs - one per beacon tile on visited levels.
            // Currently at most one beacon room per level (perLevelUnique),
            // so the loop hits at most a handful per box. Active beacons
            // pulse; inactive ones render flat. Both share a thin black
            // border for legibility against the mini-map fill.
            if (lvl.visited && lvl.tiles != null) {
                drawBeaconsOnBox(s, lvl, i, bx, by, bw, bh, ti);
            }
        }

        s.flush();
        if (clipped) {
            com.badlogic.gdx.scenes.scene2d.utils.ScissorStack.popScissors();
        }

        // Bottom info pane - drawn as a sub-window inside the map window
        // when a visited level is selected. Pure chrome here; text
        // populates in drawBodyText.
        if (selected >= 0 && selected < world.levels.length) {
            Level lvl = world.levels[selected];
            if (lvl != null && lvl.visited) {
                Rect info = infoPaneRect();
                Window.drawShape(ctx, info.x, info.y, info.w, info.h);
            }
        }

        // Teleport confirmation popup chrome - drawn last so it sits on
        // top of every other shape in this pass.
        drawConfirmShape(s);

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

    /** Visible band the graph is allowed to occupy - between the title
     *  row and the bottom orb-count strip + info-pane reservation. Used
     *  for scissor clipping and pan clamping. */
    private Rect graphViewport() {
        float top    = window.top() - headerBandH();
        float bottom = window.y + INFO_PANE_H + ORB_STRIP_H + 16f;
        return new Rect(window.x + 8f, bottom,
                window.w - 16f, top - bottom);
    }

    /** Height of the orb-count strip drawn between the graph viewport and
     *  the bottom info pane. Sized for one line of regular text plus
     *  padding. */
    private static final float ORB_STRIP_H = 20f;

    /** Keep the pan within sane bounds - the graph's bounding box must
     *  retain at least a small overlap with the viewport so the user
     *  can't pan it entirely off-screen. */
    private void clampPan() {
        // Soft cap proportional to viewport - generous, only kicks in
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
     *  {@code 2 x topInset} so the box reads as if tilted away from the
     *  viewer. Each tile row interpolates its x extent linearly between
     *  bottom and top widths. Cells are colour-coded by tile type for
     *  explored cells; unexplored cells use the fog tint. */
    private void drawMiniMap(ShapeRenderer s, Level lvl,
                             float bx, float by, float w, float h, float topInset) {
        Tile[][] tiles = lvl.tiles;
        boolean[][] explored = lvl.explored;
        int lw = lvl.width, lh = lvl.height;
        if (lh <= 0 || lw <= 0) return;
        Color[] palette = themePalette(lvl.theme);
        // Step values - cellH is constant, cellW shrinks with row.
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
                        explored != null && explored[tx][ty], palette);
                s.setColor(tint);
                // Quadrilateral cell - bottom edge wider than top, matching
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

    /** Paint a very dark base into the graph viewport, then layer a few
     *  slowly-orbiting soft blobs in deep blue / purple so the panel reads
     *  as a quiet swirling void. Driven by {@link #bgSwirlT}; alpha and
     *  positions modulate so the swirl moves continuously without ever
     *  feeling busy. Assumes the caller already holds a scissor on the
     *  viewport rect and that GL blend is enabled. */
    private void drawSwirlBackground(ShapeRenderer s, Rect vp) {
        // Solid near-black base.
        s.setColor(0.04f, 0.04f, 0.07f, 1f);
        s.rect(vp.x, vp.y, vp.w, vp.h);

        // Three orbiting blobs. Each blob is drawn as a stack of concentric
        // circles with decreasing alpha to fake a soft falloff with the
        // ShapeRenderer's filled-shape pipeline.
        float t = bgSwirlT;
        float cx = vp.cx();
        float cy = vp.cy();
        float orbitR = Math.min(vp.w, vp.h) * 0.45f;
        drawSwirlBlob(s,
                cx + (float) Math.cos(t * 0.18f)        * orbitR * 0.7f,
                cy + (float) Math.sin(t * 0.18f)        * orbitR * 0.4f,
                /*outerR*/ Math.min(vp.w, vp.h) * 0.55f,
                /*r*/ 0.18f, /*g*/ 0.10f, /*b*/ 0.28f, /*alpha*/ 0.55f);
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

    /** Soft radial blob built from 5 concentric circles with linearly
     *  decreasing alpha. ShapeRenderer's filled circle has no gradient
     *  primitive so we fake one with overdraw. */
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

    /** Count of TELEPORT_ORBs the player currently carries in their bag.
     *  Used to gate beacon clicks and label the orb counter in the header.
     *  Returns 0 if the play session isn't fully wired (defensive - the
     *  map screen can be opened in attract mode too). */
    private int teleportOrbCount() {
        if (game.currentPlay == null) return 0;
        Mob player = com.bjsp123.rl2.logic.TurnSystem.findPlayer(world.currentLevel());
        if (player == null || player.inventory == null) return 0;
        int n = 0;
        for (com.bjsp123.rl2.model.Item it : player.inventory.bag) {
            if (it == null) continue;
            if ("TELEPORT_ORB".equals(it.type)) n += Math.max(1, it.count);
        }
        return n;
    }

    /** Pop one TELEPORT_ORB from the player's bag. Decrements a stack or
     *  removes the item if the stack was 1. No-op if the player or bag
     *  isn't available. */
    private void consumeOneTeleportOrb() {
        if (game.currentPlay == null) return;
        Mob player = com.bjsp123.rl2.logic.TurnSystem.findPlayer(world.currentLevel());
        if (player == null || player.inventory == null) return;
        for (com.bjsp123.rl2.model.Item it : new ArrayList<>(player.inventory.bag)) {
            if (it == null) continue;
            if (!"TELEPORT_ORB".equals(it.type)) continue;
            com.bjsp123.rl2.logic.InventorySystem.removeOneFromBag(player.inventory, it);
            return;
        }
    }

    /** Confirmation popup "Yes" handler - consume one orb and teleport,
     *  then close the map back to the play screen. */
    private void confirmTeleport() {
        if (pendingBeaconPos == null || pendingDestLevel < 0) {
            cancelTeleportConfirm();
            return;
        }
        if (teleportOrbCount() <= 0) {
            cancelTeleportConfirm();
            return;
        }
        consumeOneTeleportOrb();
        boolean ok = game.currentPlay != null
                && game.currentPlay.teleportToBeacon(pendingDestLevel, pendingBeaconPos);
        confirmOpen      = false;
        pendingDestLevel = -1;
        pendingBeaconPos = null;
        if (ok) onBack.run();
    }

    /** Confirmation popup "No" handler - dismiss without consuming an orb. */
    private void cancelTeleportConfirm() {
        confirmOpen      = false;
        pendingDestLevel = -1;
        pendingBeaconPos = null;
    }

    /** Position + draw the confirmation popup chrome (panel + Yes / No
     *  button bodies). Sized as a small modal centred on the map window.
     *  Hit rects are written into {@link #confirmYes} / {@link #confirmNo}
     *  so {@link #onTouchDownInBody} can dispatch the tap. Text labels
     *  are painted later in {@link #drawConfirmText}. */
    private void drawConfirmShape(ShapeRenderer s) {
        if (!confirmOpen) return;
        float panelW = 240f;
        float panelH = 90f;
        float panelX = window.cx() - panelW * 0.5f;
        float panelY = window.cy() - panelH * 0.5f;
        confirmPanel.set(panelX, panelY, panelW, panelH);
        // Panel background - fully opaque so the swirling backdrop and any
        // label text behind the modal can't bleed through. Single solid
        // fill + a clear 2-px border so the dialog reads as a proper
        // modal, not a translucent overlay.
        s.setColor(0.06f, 0.06f, 0.09f, 1f);
        s.rect(panelX, panelY, panelW, panelH);
        s.setColor(UIVars.ACCENT);
        // 2-px border by drawing 4 thin rects.
        s.rect(panelX,            panelY,            panelW, 2f);
        s.rect(panelX,            panelY + panelH-2, panelW, 2f);
        s.rect(panelX,            panelY,            2f,     panelH);
        s.rect(panelX + panelW-2, panelY,            2f,     panelH);

        // Two big buttons centred along the bottom half of the popup.
        float btnW = 80f;
        float btnH = 26f;
        float btnY = panelY + 10f;
        float gap  = 16f;
        float yesX = window.cx() - btnW - gap * 0.5f;
        float noX  = window.cx() + gap * 0.5f;
        confirmYes.set(yesX, btnY, btnW, btnH);
        confirmNo .set(noX,  btnY, btnW, btnH);
        s.setColor(UIVars.BORDER_MID);
        s.rect(yesX, btnY, btnW, btnH);
        s.rect(noX,  btnY, btnW, btnH);
        s.setColor(0f, 0f, 0f, 1f);
        s.rect(yesX + 1f, btnY + 1f, btnW - 2f, btnH - 2f);
        s.rect(noX  + 1f, btnY + 1f, btnW - 2f, btnH - 2f);
    }

    /** Paint the confirmation popup's text labels: prompt at the top,
     *  "Yes" / "No" on the two buttons. */
    private void drawConfirmText(UiCtx ctx) {
        if (!confirmOpen) return;
        int depth = -1;
        if (pendingDestLevel >= 0 && pendingDestLevel < world.levels.length
                && world.levels[pendingDestLevel] != null) {
            depth = world.levels[pendingDestLevel].depth;
        }
        String prompt = TextCatalog.format("ui.map.teleportConfirm",
                TextCatalog.vars("depth", depth,
                        "orbs", teleportOrbCount()));
        TextDraw.centre(ctx, ctx.fontRegular, UIVars.TEXT_BODY,
                prompt, window.cx(), confirmPanel.y + confirmPanel.h - 14f);
        TextDraw.centre(ctx, ctx.fontRegular, UIVars.ACCENT,
                TextCatalog.get("ui.map.teleportYes"),
                confirmYes.cx(), confirmYes.cy() - 4f);
        TextDraw.centre(ctx, ctx.fontRegular, UIVars.TEXT_BODY,
                TextCatalog.get("ui.map.teleportNo"),
                confirmNo.cx(), confirmNo.cy() - 4f);
    }

    /** Scan {@code lvl} for beacon tiles and draw a plus-sign for each on top
     *  of the level's mini-map box. Active beacons pulse via {@link #beaconPulseT}
     *  and read as valid teleport targets; inactive ones draw flat. Each
     *  plus is outlined with a thin black border (drawn as a slightly-larger
     *  plus underneath) so the silhouette stays readable against any
     *  mini-map fill colour. Records hit rects in {@link #beaconRects} for
     *  {@link #onTouchDownInBody}. */
    private void drawBeaconsOnBox(ShapeRenderer s, Level lvl, int worldIdx,
                                  float bx, float by, float bw, float bh, float topInset) {
        int lw = lvl.width, lh = lvl.height;
        if (lw <= 0 || lh <= 0) return;
        // Pulse amplitude for active beacons - scale and alpha modulated by a
        // ~1.5 Hz sine. Border draws at the static base scale so the
        // silhouette doesn't strobe.
        float pulse = 0.5f + 0.5f * (float) Math.sin(beaconPulseT * 3.0);
        float activeScale = 1.0f + 0.35f * pulse;
        float baseArm = 5f * Math.max(0.5f, zoom);
        float baseThick = 2f * Math.max(0.5f, zoom);
        for (int ty = 0; ty < lh; ty++) {
            for (int tx = 0; tx < lw; tx++) {
                Tile t = lvl.tiles[tx][ty];
                if (t != Tile.BEACON_INACTIVE && t != Tile.BEACON_ACTIVE) continue;
                // Map tile (tx, ty) into the trapezoid - same maths as
                // drawMiniMap, but evaluated at one cell centre.
                float u = (tx + 0.5f) / (float) lw;
                float v = (ty + 0.5f) / (float) lh;
                float rowLeftX  = bx + topInset * v;
                float rowRightX = bx + bw - topInset * v;
                float cx = rowLeftX + (rowRightX - rowLeftX) * u;
                float cy = by + bh * v;

                boolean active = (t == Tile.BEACON_ACTIVE);
                float arm = active ? baseArm * activeScale : baseArm;
                float thick = baseThick;
                // Black border: same plus, slightly larger, drawn first.
                s.setColor(0f, 0f, 0f, 1f);
                s.rect(cx - arm - 1f, cy - thick * 0.5f - 1f,
                       (arm + 1f) * 2f, thick + 2f);
                s.rect(cx - thick * 0.5f - 1f, cy - arm - 1f,
                       thick + 2f, (arm + 1f) * 2f);
                // Fill: warm yellow for active (visible from far), cool
                // grey for inactive.
                if (active) s.setColor(1f, 0.85f, 0.30f, 0.6f + 0.4f * pulse);
                else        s.setColor(0.72f, 0.74f, 0.78f, 1f);
                s.rect(cx - arm, cy - thick * 0.5f, arm * 2f, thick);
                s.rect(cx - thick * 0.5f, cy - arm, thick, arm * 2f);

                // Hit rect = bounding box of the plus arms.
                float hitArm = baseArm * (active ? 1.35f : 1f) + 2f;
                beaconRects.add(new Rect(cx - hitArm, cy - hitArm,
                        hitArm * 2f, hitArm * 2f));
                beaconRefs.add(new int[]{worldIdx, tx, ty});
                beaconActive.add(active);
            }
        }
    }

    /** Tint for one mini-map cell. Floor + wall come from {@link #themePalette},
     *  chasm is always near-black, unexplored is fog. {@code palette} is the
     *  level's pre-resolved (floor, wall) pair so the caller doesn't redo the
     *  theme lookup per-cell. */
    private static Color tileTint(Tile t, boolean explored, Color[] palette) {
        if (!explored) return FOG_TINT;
        if (t == null) return CHASM_TINT;
        if (t == Tile.CHASM)         return CHASM_TINT;
        if (t.isFloorLike())         return palette[0];
        if (t.blocksMovement())      return palette[1];
        // Doors, statues etc - treat as wall-equivalent for the mini-map.
        return palette[1];
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
        TextDraw.centre(ctx, ctx.fontHeader, UIVars.ACCENT,
                TextCatalog.get("ui.map.title"),
                window.cx(), window.top() - ctx.headerLineH());
        // "?" glyphs on every unvisited level box - reads as "you haven't
        // been here yet" so the player doesn't mistake an empty fog tile
        // for an explored-but-empty one. Centred over the box's centre.
        for (float[] cxy : unvisitedCenters) {
            TextDraw.centre(ctx, ctx.fontHeader, UIVars.BORDER_MID,
                    "?", cxy[0], cxy[1] + ctx.headerLineH() * 0.35f);
        }
        // Orb counter sits in its own strip BELOW the scrollable graph and
        // ABOVE the info pane. Painted by drawBodyText since it's text.
        float orbY = window.y + INFO_PANE_H + 16f + ORB_STRIP_H * 0.5f + 4f;
        TextDraw.centre(ctx, ctx.fontRegular, UIVars.TEXT_BODY,
                TextCatalog.format("ui.map.orbCount",
                        TextCatalog.vars("count", teleportOrbCount())),
                window.cx(), orbY);
        if (world == null || world.levels == null) return;

        // Per-box depth labels were intentionally removed - each level's
        // box already conveys position via column + row; an additional
        // "L{n}" caption adds clutter without information.

        // Bottom info pane text - only when a visited level is selected.
        if (selected >= 0 && selected < world.levels.length) {
            Level lvl = world.levels[selected];
            if (lvl != null && lvl.visited) {
                drawInfoPaneText(ctx, lvl);
            }
        }
        // Confirmation popup text - drawn last so it sits on top of every
        // other label in this pass.
        drawConfirmText(ctx);
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
        String header = TextCatalog.format("ui.map.level", TextCatalog.vars("depth", lvl.depth));
        if (lvl.theme != null) {
            header += "   " + lvl.theme.name().toLowerCase();
        }
        float textW = info.right() - left - 12f;
        TextDraw.leftFit(ctx, ctx.fontRegular, UIVars.ACCENT, header, left, top, textW);
        top -= 18f;

        // Unique themed rooms - scan level.rooms for rooms whose kind is
        // flagged unique in the registry.
        String roomsLine = collectUniqueRooms(lvl);
        if (!roomsLine.isEmpty()) {
            TextDraw.leftFit(ctx, ctx.fontRegular, UIVars.TEXT_BODY,
                    TextCatalog.format("ui.map.rooms",
                            TextCatalog.vars("rooms", roomsLine)),
                    left, top, textW);
            top -= 16f;
        }

        // Unique mob species present.
        String mobsLine = collectUniqueMobs(lvl);
        if (!mobsLine.isEmpty()) {
            TextDraw.leftFit(ctx, ctx.fontRegular, UIVars.TEXT_BODY,
                    TextCatalog.format("ui.map.mobs",
                            TextCatalog.vars("mobs", mobsLine)),
                    left, top, textW);
        }
    }

    private static String collectUniqueRooms(Level lvl) {
        if (lvl.rooms == null || lvl.rooms.isEmpty()) return "";
        Set<String> seen = new HashSet<>();
        StringBuilder sb = new StringBuilder();
        for (Level.RoomSnapshot r : lvl.rooms) {
            if (r == null || r.kind == null) continue;
            var def = Registries.themedRoom(r.kind);
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
            var def = Registries.mob(m.mobType);
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
        float topY = window.top() - headerBandH() - 10f;
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
