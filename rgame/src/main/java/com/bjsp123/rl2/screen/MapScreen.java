package com.bjsp123.rl2.screen;

import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.Pixmap;
import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.graphics.g2d.Batch;
import com.badlogic.gdx.graphics.g2d.BitmapFont;
import com.badlogic.gdx.graphics.g2d.GlyphLayout;
import com.badlogic.gdx.graphics.glutils.ShapeRenderer;
import com.badlogic.gdx.scenes.scene2d.ui.Container;
import com.badlogic.gdx.scenes.scene2d.ui.ScrollPane;
import com.badlogic.gdx.scenes.scene2d.ui.Table;
import com.badlogic.gdx.scenes.scene2d.ui.Widget;
import com.bjsp123.rl2.model.Level;
import com.bjsp123.rl2.model.Level.LevelFlag;
import com.bjsp123.rl2.model.Tile;
import com.bjsp123.rl2.model.World;

import java.util.HashMap;
import java.util.Map;

/**
 * Graphical overview of the dungeon. The world is treated as an arbitrary layered DAG —
 * the screen reads {@link Level#depth} and {@link Level#mapColumn} from every level for
 * its layout, and reads the per-level {@code stairs(Up|Down)(Alt)Target} fields for the
 * edges. Nothing here knows the diamond shape specifically, so any topology the world
 * generator produces (more levels, branching trees, parallel branches at the same depth)
 * renders without changes.
 *
 * <p>Each level is drawn as a slightly-perspective box (top edge inset by ~10% on each
 * side) so the layout reads as cards laid out on a table. A box's contents depend on
 * whether the player has set foot on that level:
 * <ul>
 *   <li><b>Visited</b> — a pixel-per-tile mini-map: <em>pale grey</em> for explored
 *       floor-like cells, <em>white</em> for walls, <em>black</em> for chasms,
 *       <em>dark grey</em> for any cell the player hasn't yet seen.</li>
 *   <li><b>Unvisited</b> — a flat dark fill with a centred "?" glyph.</li>
 * </ul>
 *
 * <p>Connections between levels are drawn as double-headed arrows along the edges. Flag
 * glyphs sit on the <em>left</em> of west levels ({@link Level.Side#WEST}) and on the
 * <em>right</em> of every other level so they don't clash with the centre column where
 * arrows run.
 */
public class MapScreen extends MenuScreen {

    private final Runnable onBack;
    private final World world;

    /** Per-level mini-map textures, lazily built on first draw and disposed with the screen. */
    private final Map<Integer, Texture> miniTextures = new HashMap<>();
    /** 1×1 white texture for flat-colour fills (vertex-coloured at draw time). */
    private Texture whitePixel;
    private ShapeRenderer shapes;

    /** Held so {@link #build} can force a layout pass at the end and centre the
     *  scroll on the map's middle column for the first frame. */
    private ScrollPane scroll;

    public MapScreen(Runnable onBack, World world) {
        this.onBack = onBack;
        this.world  = world;
    }

    @Override protected float minVirtualWidth()  { return 540; }
    @Override protected float minVirtualHeight() { return 700; }

    @Override
    protected void build(Table root) {
        if (whitePixel == null) whitePixel = makeWhitePixel();
        if (shapes     == null) shapes     = new ShapeRenderer();

        Table panel = new Table();
        panel.pad(16);
        panel.add(label("Dungeon Map", "title", 1.6f)).padBottom(10).row();

        // ScrollPane around the map body — the body declares its preferred size via the
        // bounding box of every level's (depth, mapColumn), so taller worlds (more depths)
        // or wider worlds (more parallel columns) just get a longer scroll region.
        MapBodyActor body = new MapBodyActor();
        scroll = new ScrollPane(body);
        scroll.setFadeScrollBars(false);
        scroll.setScrollingDisabled(false, false);

        // Frame around the map — wraps the scroll area in its own panel chrome so the
        // map sits inside a clear visual boundary, distinct from the outer modal frame.
        Table mapFrame = new Table();
        mapFrame.setBackground(skin.getDrawable("simple-panel"));
        mapFrame.pad(6);
        mapFrame.add(scroll).expand().fill();
        panel.add(mapFrame).expand().fill().row();

        panel.add(button("Back", onBack)).width(180).height(36).padTop(12);

        // Fixed-size outer panel — does not resize with map dimensions.
        Container<Table> framed = fixedPanel(panel, 520, 660);
        root.center().add(framed);

        // Force a layout pass so the ScrollPane knows its scroll bounds, then centre
        // the view on the map's middle column. Without this the scroll starts at the
        // leftmost edge and a wide world's centre is hidden off to the right.
        root.validate();
        scroll.setScrollPercentX(0.5f);
        scroll.updateVisualScroll();
    }

    @Override
    public void dispose() {
        super.dispose();
        for (Texture t : miniTextures.values()) t.dispose();
        miniTextures.clear();
        if (whitePixel != null) { whitePixel.dispose(); whitePixel = null; }
        if (shapes     != null) { shapes.dispose();     shapes     = null; }
    }

    @Override
    protected void onEscape() { onBack.run(); }

    // ── mini-map texture cache ──────────────────────────────────────────────

    /** Build a {@code level.width × level.height} pixmap where each pixel encodes what the
     *  player knows about that tile. Cached on first request; disposed with the screen.
     *  We don't invalidate while the screen is open — the player can't move or explore
     *  more cells while the map is in front of them. */
    private Texture miniTextureFor(int idx, Level lvl) {
        Texture tex = miniTextures.get(idx);
        if (tex != null) return tex;
        Pixmap pm = new Pixmap(lvl.width, lvl.height, Pixmap.Format.RGBA8888);
        Color cChasm   = new Color(0.05f, 0.05f, 0.05f, 1f);
        Color cWall    = new Color(0.95f, 0.95f, 0.95f, 1f);
        Color cFloor   = new Color(0.65f, 0.65f, 0.65f, 1f);
        Color cUnknown = new Color(0.22f, 0.22f, 0.22f, 1f);
        for (int x = 0; x < lvl.width; x++) {
            for (int y = 0; y < lvl.height; y++) {
                Color c;
                if (lvl.explored == null || !lvl.explored[x][y]) {
                    c = cUnknown;
                } else {
                    Tile t = lvl.tiles[x][y];
                    c = (t == Tile.CHASM)     ? cChasm
                      : (t == Tile.WALL)      ? cWall
                      : cFloor;   // floors, doors, stairs, lamps — all "ground you can walk on"
                }
                pm.setColor(c);
                // Pixmap row 0 is the top of the resulting texture, but our level convention
                // has y increasing northward — flip Y so high-Y tiles render at the top of
                // the box (the "north up" map orientation a player would expect).
                pm.drawPixel(x, lvl.height - 1 - y);
            }
        }
        tex = new Texture(pm);
        // Nearest-neighbour so the pixel-per-tile look stays crisp when stretched into the box.
        tex.setFilter(Texture.TextureFilter.Nearest, Texture.TextureFilter.Nearest);
        pm.dispose();
        miniTextures.put(idx, tex);
        return tex;
    }

    private static Texture makeWhitePixel() {
        Pixmap pm = new Pixmap(1, 1, Pixmap.Format.RGBA8888);
        pm.setColor(Color.WHITE);
        pm.fill();
        Texture t = new Texture(pm);
        pm.dispose();
        return t;
    }

    // ── flag glyph mapping (kept ASCII so the default bitmap font handles it) ──────

    private static String flagGlyph(LevelFlag f) {
        return switch (f) {
            case WATER          -> "~";
            case WALKWAY_LEVEL  -> "=";
            case PLANTS         -> "\"";
            case BIGROOMS       -> "O";
            case BIGLEVEL       -> "+";
            case ROUGH          -> "&";
        };
    }

    // ── custom-drawn body ──────────────────────────────────────────────────

    /** Layout constants. {@code BOX_W/H} are the on-screen size of one level node;
     *  {@code ROW_PITCH} is the vertical spacing per unit-of-depth and {@code COL_PITCH}
     *  the horizontal spacing per unit-of-mapColumn. The map body takes its preferred
     *  size from the bounding box of all levels' (depth, mapColumn). */
    private static final float BOX_W           = 96f;
    private static final float BOX_H           = 64f;
    private static final float BOX_PERSPECTIVE = 0.12f; // top edge inset = w * this on each side
    private static final float ROW_PITCH       = 96f;
    private static final float COL_PITCH       = 90f;
    private static final float MARGIN          = 32f;  // gutter around the laid-out diamond
    private static final float ARROW_HEAD_LEN  = 9f;
    private static final float ARROW_HEAD_W    = 7f;
    private static final float FLAG_GUTTER     = 12f;

    private class MapBodyActor extends Widget {

        /** Bounds of (mapColumn, depth) across the world, computed lazily. */
        private float minCol, maxCol, minDepth, maxDepth;
        private boolean boundsValid;

        private void computeBounds() {
            if (boundsValid) return;
            boundsValid = true;
            if (world == null || world.levels == null || world.levels.length == 0) {
                minCol = maxCol = minDepth = maxDepth = 0f;
                return;
            }
            minCol = Float.POSITIVE_INFINITY; maxCol = Float.NEGATIVE_INFINITY;
            minDepth = Float.POSITIVE_INFINITY; maxDepth = Float.NEGATIVE_INFINITY;
            for (Level lvl : world.levels) {
                if (lvl == null) continue;
                if (lvl.mapColumn < minCol)   minCol   = lvl.mapColumn;
                if (lvl.mapColumn > maxCol)   maxCol   = lvl.mapColumn;
                if (lvl.depth     < minDepth) minDepth = lvl.depth;
                if (lvl.depth     > maxDepth) maxDepth = lvl.depth;
            }
        }

        @Override
        public float getPrefWidth() {
            computeBounds();
            float colSpan = Math.max(0f, maxCol - minCol);
            return colSpan * COL_PITCH + BOX_W + 2 * MARGIN + 2 * FLAG_GUTTER + 64f;
        }

        @Override
        public float getPrefHeight() {
            computeBounds();
            float depthSpan = Math.max(0f, maxDepth - minDepth);
            return depthSpan * ROW_PITCH + BOX_H + 2 * MARGIN;
        }

        /** Pixel position of the lower-left corner of {@code lvl}'s box in actor-local
         *  coords. Depth grows downward: larger-depth → smaller Y. */
        private float boxX(Level lvl) {
            float colOffset = (lvl.mapColumn - (minCol + maxCol) * 0.5f) * COL_PITCH;
            return getX() + getWidth() * 0.5f - BOX_W * 0.5f + colOffset;
        }
        private float boxY(Level lvl) {
            // The deepest level sits at the bottom; the topmost at the top.
            float depthOffset = (lvl.depth - minDepth) * ROW_PITCH;
            return getY() + getHeight() - BOX_H - MARGIN - depthOffset;
        }

        @Override
        public void draw(Batch batch, float parentAlpha) {
            if (world == null || world.levels == null) return;
            computeBounds();

            int n = world.levels.length;
            float[] xs = new float[n];
            float[] ys = new float[n];
            for (int i = 0; i < n; i++) {
                Level lvl = world.levels[i];
                if (lvl == null) continue;
                xs[i] = boxX(lvl);
                ys[i] = boxY(lvl);
            }

            // ── 1. arrows (between SpriteBatch frames) ─────────────────────
            batch.end();
            shapes.setProjectionMatrix(batch.getProjectionMatrix());
            shapes.setTransformMatrix(batch.getTransformMatrix());
            shapes.begin(ShapeRenderer.ShapeType.Filled);
            shapes.setColor(0.65f, 0.6f, 0.45f, 1f);
            for (int i = 0; i < n; i++) {
                Level lvl = world.levels[i];
                if (lvl == null) continue;
                // Only iterate down-targets — every edge appears exactly once that way,
                // and the arrow we draw is double-headed so the "up" direction is implicit.
                drawEdge(i, lvl.stairsDownTarget,    xs, ys);
                drawEdge(i, lvl.stairsDownAltTarget, xs, ys);
            }
            shapes.end();
            batch.begin();

            // ── 2. box fills (perspective trapezoid via custom vertex draw) ───
            for (int i = 0; i < n; i++) {
                Level lvl = world.levels[i];
                if (lvl == null) continue;
                Texture tex;
                Color tint;
                if (lvl.visited) {
                    tex  = miniTextureFor(i, lvl);
                    tint = Color.WHITE;
                } else {
                    tex  = whitePixel;
                    tint = new Color(0.12f, 0.12f, 0.16f, 1f);
                }
                drawTrapezoid(batch, tex, tint, xs[i], ys[i], BOX_W, BOX_H, BOX_PERSPECTIVE);
            }

            // ── 3. unvisited "?" glyphs ───────────────────────────────────
            BitmapFont font = skin.getFont("default-font");
            float prevScale = font.getData().scaleX;
            font.getData().setScale(2.4f);
            font.setColor(0.85f, 0.85f, 0.5f, 1f);
            GlyphLayout layout = new GlyphLayout();
            for (int i = 0; i < n; i++) {
                Level lvl = world.levels[i];
                if (lvl == null || lvl.visited) continue;
                layout.setText(font, "?");
                float qx = xs[i] + (BOX_W - layout.width)  * 0.5f;
                float qy = ys[i] + (BOX_H + layout.height) * 0.5f;
                font.draw(batch, "?", qx, qy);
            }
            font.getData().setScale(prevScale);
            font.setColor(Color.WHITE);

            // ── 4. flag glyphs on the gutter side of each box ─────────────
            font.getData().setScale(1.3f);
            font.setColor(0.95f, 0.85f, 0.55f, 1f);
            for (int i = 0; i < n; i++) {
                Level lvl = world.levels[i];
                if (lvl == null || lvl.flags == null || lvl.flags.isEmpty()) continue;
                drawFlagColumn(batch, font, layout, lvl, xs[i], ys[i]);
            }
            font.getData().setScale(prevScale);
            font.setColor(Color.WHITE);

            // ── 5. box outlines (current level highlighted) ──────────────
            batch.end();
            shapes.begin(ShapeRenderer.ShapeType.Line);
            for (int i = 0; i < n; i++) {
                if (world.levels[i] == null) continue;
                if (i == world.currentLevelIndex) {
                    shapes.setColor(1f, 0.85f, 0.3f, 1f);
                } else {
                    shapes.setColor(0.75f, 0.7f, 0.55f, 1f);
                }
                drawTrapezoidOutline(xs[i], ys[i], BOX_W, BOX_H, BOX_PERSPECTIVE);
            }
            shapes.end();
            batch.begin();
        }

        /** If {@code targetIdx} is a valid level index, draw a double-headed arrow from
         *  level {@code srcIdx}'s box centre to that target's. */
        private void drawEdge(int srcIdx, int targetIdx, float[] xs, float[] ys) {
            if (targetIdx < 0 || targetIdx >= world.levels.length) return;
            if (world.levels[targetIdx] == null) return;
            float x1 = xs[srcIdx]    + BOX_W * 0.5f;
            float y1 = ys[srcIdx]    + BOX_H * 0.5f;
            float x2 = xs[targetIdx] + BOX_W * 0.5f;
            float y2 = ys[targetIdx] + BOX_H * 0.5f;
            drawDoubleHeadedArrow(x1, y1, x2, y2);
        }

        /** Draw a perspective-skewed quad: bottom edge full width, top edge inset by
         *  {@code w * inset} on each side so the shape reads as a slightly-tilted card. */
        private void drawTrapezoid(Batch batch, Texture tex, Color tint,
                                   float x, float y, float w, float h, float inset) {
            float skew = w * inset;
            float c = tint.toFloatBits();
            // Vertex order: V0=BL, V1=TL, V2=TR, V3=BR (matches SpriteBatch's index buffer).
            // Texture V is flipped: V=0 at top, V=1 at bottom.
            float[] verts = {
                x,          y,        c, 0f, 1f,     // BL
                x + skew,   y + h,    c, 0f, 0f,     // TL
                x + w - skew, y + h,  c, 1f, 0f,     // TR
                x + w,      y,        c, 1f, 1f      // BR
            };
            batch.draw(tex, verts, 0, 20);
        }

        /** Box border, drawn as four lines forming the same trapezoid as
         *  {@link #drawTrapezoid}. ShapeRenderer must already be in line mode. */
        private void drawTrapezoidOutline(float x, float y, float w, float h, float inset) {
            float skew = w * inset;
            shapes.line(x,            y,     x + w,        y);
            shapes.line(x + w,        y,     x + w - skew, y + h);
            shapes.line(x + w - skew, y + h, x + skew,     y + h);
            shapes.line(x + skew,     y + h, x,            y);
        }

        /** Stack {@link Level#flags} on the side appropriate for {@link Level#side} — west
         *  boxes get glyphs on the left, every other side puts them on the right (so flags
         *  never overlap arrows running through the centre column). */
        private void drawFlagColumn(Batch batch, BitmapFont font, GlyphLayout layout,
                                    Level lvl, float boxX, float boxY) {
            boolean rightSide = lvl.side != Level.Side.WEST;
            float lineH = font.getLineHeight();
            int   count = lvl.flags.size();
            float startY = boxY + (BOX_H + (count - 1) * lineH) * 0.5f;

            for (LevelFlag flag : LevelFlag.values()) {
                if (!lvl.flags.contains(flag)) continue;
                String g = flagGlyph(flag);
                layout.setText(font, g);
                float gx = rightSide
                        ? boxX + BOX_W + FLAG_GUTTER
                        : boxX - FLAG_GUTTER - layout.width;
                font.draw(batch, g, gx, startY);
                startY -= lineH;
            }
        }

        /** Draw a thick segment from {@code (x1,y1)} to {@code (x2,y2)} with a triangular
         *  arrowhead at each end. ShapeRenderer must already be in {@code Filled} mode. */
        private void drawDoubleHeadedArrow(float x1, float y1, float x2, float y2) {
            // Pull the visible endpoints inward so the line + heads stop at the box border
            // rather than burying themselves under it. Inset half the diagonal so the
            // arrowhead clears the corner regardless of the segment's angle.
            float dx = x2 - x1, dy = y2 - y1;
            float len = (float) Math.sqrt(dx * dx + dy * dy);
            if (len < 1e-3f) return;
            float ux = dx / len, uy = dy / len;
            float inset = 0.5f * (Math.abs(ux) * BOX_W + Math.abs(uy) * BOX_H) + 2f;
            float ax1 = x1 + ux * inset, ay1 = y1 + uy * inset;
            float ax2 = x2 - ux * inset, ay2 = y2 - uy * inset;
            shapes.rectLine(ax1, ay1, ax2, ay2, 2.0f);
            arrowHead(ax2, ay2,  ux,  uy);
            arrowHead(ax1, ay1, -ux, -uy);
        }

        /** Filled triangular arrowhead at {@code (tipX, tipY)} pointing in direction
         *  ({@code ux}, {@code uy}). */
        private void arrowHead(float tipX, float tipY, float ux, float uy) {
            float bx = tipX - ux * ARROW_HEAD_LEN;
            float by = tipY - uy * ARROW_HEAD_LEN;
            float px = -uy, py = ux; // perpendicular unit vector
            float lx = bx + px * ARROW_HEAD_W * 0.5f;
            float ly = by + py * ARROW_HEAD_W * 0.5f;
            float rx = bx - px * ARROW_HEAD_W * 0.5f;
            float ry = by - py * ARROW_HEAD_W * 0.5f;
            shapes.triangle(tipX, tipY, lx, ly, rx, ry);
        }
    }
}
