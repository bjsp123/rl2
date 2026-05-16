package com.bjsp123.rl2.world.render;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.Pixmap;
import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.graphics.g2d.SpriteBatch;
import com.badlogic.gdx.graphics.g2d.TextureRegion;
import com.bjsp123.rl2.model.Level;
import com.bjsp123.rl2.model.Tile;

/**
 * Soft-edge fog-of-war overlay. Maintains a pixmap at 2 pixels
 * per tile (so each pixel covers 8 screen pixels of a 16px tile) and draws it on top of the map
 * with linear filtering - adjacent pixels of different fog densities blend smoothly during the
 * upscale, giving the characteristic soft gradient at visibility boundaries.
 *
 * A wall with another wall immediately to its south is
 * treated as "internal" and its left/right halves are sampled independently (self + horizontal
 * neighbor + south-diagonal if that neighbor is also a wall). Camera-facing walls (wall with
 * non-wall south) pick the darker of self and the cell south of them. This is what makes a wall
 * appear fully opaque when the cell beyond it has never been seen, and soften naturally when the
 * far side is known - the "far side rule".
 *
 *  We use a 4th fog level between LIT and EXPLORED so the
 * soft gradient also smooths the lit/visible-but-unlit transition.
 *
 * <p>Fog darkening and lamplight glow are combined into a single premultiplied-RGBA8888 texture
 * drawn with {@code glBlendFunc(GL_ONE, GL_ONE_MINUS_SRC_ALPHA)}. This achieves:
 * {@code dst = lampRGB·lampAlpha + world·(1−fogAlpha)}, which is identical to the sequential
 * darken-then-glow of the old two-pass system but in one full-screen fragment pass instead of two.
 */
public class FogOverlay {

    private static final int PIX_PER_TILE = 2;
    private static final int TILE_SIZE    = 16;

    // Lower index = less fog (Math.max picks the darker fog).
    private static final int LIT      = 0;
    private static final int VISIBLE  = 1; // visible, not lit
    private static final int EXPLORED = 2; // seen once, not currently visible
    private static final int UNSEEN   = 3;

    // ARGB (0xAARRGGBB). Converted to Pixmap's RGBA8888 at fill time.
    // Three distinct brightness tiers on top of "fully bright" LIT: a mild dim for cells the
    // player can currently see (outside any lamp), a pronounced dim for cells that were seen
    // before but aren't currently in view, and fully opaque for unexplored.
    private static final int[] FOG_ARGB = {
            0x00000000, // LIT:      fully transparent (full brightness + lamp additive glow)
            0x30000000, // VISIBLE:  ~19% black - slight dim, clearly brighter than EXPLORED
            0xA0000000, // EXPLORED: ~63% black - noticeably dim
            0xFF000000, // UNSEEN:   fully opaque black
    };

    // Warm lamplight glow, combined additively in the single pass.
    private static final int LIGHT_NONE_ARGB = 0x00000000;
    private static final int LIGHT_DIM_ARGB  = 0x08FFE69A; // ~3% alpha
    private static final int LIGHT_FULL_ARGB = 0x24FFE69A; // ~14% alpha

    // Returned by cellLightLevel - ordinals matter (Math.max picks the brighter in corner sampling).
    private static final int LIGHT_NONE = 0;
    private static final int LIGHT_DIM  = 1;
    private static final int LIGHT_FULL = 2;

    // Precomputed premultiplied RGBA8888 values: [fogState 0..3][lightLevel 0..2].
    // Each entry encodes the combined fog+light pixel as (lampR·a, lampG·a, lampB·a, fogAlpha)
    // ready for GL_ONE / GL_ONE_MINUS_SRC_ALPHA blending.
    private static final int[][] COMBINED_RGBA = new int[4][3];
    static {
        int[] fogAlphas = { 0x00, 0x30, 0xA0, 0xFF };
        int[] lampARGBs = { LIGHT_NONE_ARGB, LIGHT_DIM_ARGB, LIGHT_FULL_ARGB };
        for (int f = 0; f < 4; f++) {
            for (int l = 0; l < 3; l++) {
                int fogA = fogAlphas[f];
                int la   = (lampARGBs[l] >>> 24) & 0xFF;
                int lr   = (lampARGBs[l] >>> 16) & 0xFF;
                int lg   = (lampARGBs[l] >>>  8) & 0xFF;
                int lb   =  lampARGBs[l]          & 0xFF;
                int pr   = Math.round(lr * la / 255f);
                int pg   = Math.round(lg * la / 255f);
                int pb   = Math.round(lb * la / 255f);
                COMBINED_RGBA[f][l] = (pr << 24) | (pg << 16) | (pb << 8) | fogA;
            }
        }
    }

    private int mapW, mapH, pxW, pxH;
    private Pixmap        combinedPixmap;
    private Texture       combinedTexture;
    private TextureRegion combinedRegion;
    private boolean       created;
    /** Tracks whether the combined pixmap is stale vs the level's visibility+lit state.
     *  Set by {@link #markDirty}, cleared by a successful {@link #update}. Between ticks
     *  neither array changes, so the work + texture upload in {@code update} only needs
     *  to run when the caller announces a change. */
    private boolean       dirty;

    public void createFor(int mapW, int mapH) {
        if (created && this.mapW == mapW && this.mapH == mapH) return;
        dispose();
        this.mapW = mapW;
        this.mapH = mapH;
        this.pxW  = mapW * PIX_PER_TILE;
        this.pxH  = mapH * PIX_PER_TILE;

        combinedPixmap  = newPixmap(COMBINED_RGBA[UNSEEN][LIGHT_NONE]);
        combinedTexture = newLinearTexture(combinedPixmap);
        combinedRegion  = new TextureRegion(combinedTexture);
        combinedRegion.flip(false, true);

        created = true;
        dirty = true;
    }

    /** Flag the combined pixmap as stale vs the level. The caller (PlayScreen) is
     *  responsible for calling this whenever visibility or lighting could have changed -
     *  primarily after a game tick, an amulet pickup/drop, or a level transition. */
    public void markDirty() { this.dirty = true; }

    private Pixmap newPixmap(int rgba8888) {
        Pixmap p = new Pixmap(pxW, pxH, Pixmap.Format.RGBA8888);
        p.setBlending(Pixmap.Blending.None);
        p.setColor(rgba8888);
        p.fill();
        return p;
    }

    private static Texture newLinearTexture(Pixmap p) {
        Texture t = new Texture(p);
        t.setFilter(Texture.TextureFilter.Linear, Texture.TextureFilter.Linear);
        return t;
    }

    public void update(Level level) {
        if (!created || !dirty) return;
        for (int y = 0; y < mapH; y++) {
            for (int x = 0; x < mapW; x++) {
                fillForCell(level, x, y);
            }
        }
        combinedTexture.draw(combinedPixmap, 0, 0);
        dirty = false;
    }

    /**
     * Single-pass render: premultiplied alpha blend combines fog darkening and lamplight
     * glow in one full-screen fragment pass.
     * {@code glBlendFunc(GL_ONE, GL_ONE_MINUS_SRC_ALPHA)} produces:
     * {@code dst = src.rgb + dst.rgb * (1 − src.a) = lampRGB·lampAlpha + world·(1−fogAlpha)}.
     */
    public void render(SpriteBatch batch) {
        if (!created) return;
        batch.setColor(1f, 1f, 1f, 1f);
        batch.setBlendFunction(GL20.GL_ONE, GL20.GL_ONE_MINUS_SRC_ALPHA);
        batch.draw(combinedRegion, 0, 0, mapW * TILE_SIZE, mapH * TILE_SIZE);
        batch.setBlendFunction(GL20.GL_SRC_ALPHA, GL20.GL_ONE_MINUS_SRC_ALPHA);
    }

    public void dispose() {
        if (combinedTexture != null) combinedTexture.dispose();
        if (combinedPixmap  != null) combinedPixmap.dispose();
        combinedTexture = null; combinedPixmap = null; combinedRegion = null;
        created = false;
    }

    /**
     * Fill the 4 pixmap pixels of a cell's 2x2 block by corner-sampling. Each pixel picks the
     * darkest fog (and OR of lit) from its own cell plus the 3 diagonal-adjacent cells that share
     * that corner. This makes the fog gradient START inside an explored cell when its neighbor is
     * unseen - so by the time the rendered image reaches the cell boundary (where the underlying
     * terrain abruptly ends), the fog alpha has already saturated to opaque black and the
     * "terrain-suddenly-disappears" hard edge is hidden by the dark fog. Same reason the lit/unlit
     * transition is soft: it's the pixmap values themselves that bleed across boundaries.
     */
    private void fillForCell(Level level, int x, int y) {
        int pxX = x * PIX_PER_TILE;
        int pxY = y * PIX_PER_TILE;

        if (level.tiles[x][y] == Tile.WALL) {
            // Fog uniform for the whole wall cell; light corner-sampled but excluding the
            // wall's own lit state (walls stop light - see class javadoc for the far-side rule).
            int fog = cellFog(level, x, y);
            for (int iy = 0; iy < 2; iy++) {
                int dy = (iy == 0) ? -1 : +1;
                for (int ix = 0; ix < 2; ix++) {
                    int dx = (ix == 0) ? -1 : +1;
                    int lit = Math.max(Math.max(
                                cellLightLevel(level, x + dx, y),
                                cellLightLevel(level, x,      y + dy)),
                                cellLightLevel(level, x + dx, y + dy));
                    combinedPixmap.drawPixel(pxX + ix, pxY + iy, COMBINED_RGBA[fog][lit]);
                }
            }
            return;
        }

        // Non-wall: fog is corner-sampled; light is uniform for the whole cell.
        int cellLight = cellLightLevel(level, x, y);
        int selfFog   = cellFog(level, x, y);
        for (int iy = 0; iy < 2; iy++) {
            int dy = (iy == 0) ? -1 : +1;
            for (int ix = 0; ix < 2; ix++) {
                int dx = (ix == 0) ? -1 : +1;
                int fog = max4(
                        selfFog,
                        cellFog(level, x + dx, y),
                        cellFog(level, x,      y + dy),
                        cellFog(level, x + dx, y + dy));
                if (level.visible[x][y]) fog = Math.min(fog, selfFog);
                combinedPixmap.drawPixel(pxX + ix, pxY + iy, COMBINED_RGBA[fog][cellLight]);
            }
        }
    }

    private int cellFog(Level level, int x, int y) {
        if (x < 0 || y < 0 || x >= mapW || y >= mapH) return UNSEEN;
        if (!level.explored[x][y]) return UNSEEN;
        if (!level.visible[x][y])  return EXPLORED;
        if (level.lit[x][y])       return LIT;
        return VISIBLE;
    }

    private int cellLightLevel(Level level, int x, int y) {
        if (x < 0 || y < 0 || x >= mapW || y >= mapH) return LIGHT_NONE;
        // Walls block light - they never emit it, even if their `lit` flag got set by
        // propagateToWalls. Excluding them here stops the corner-sampler from walking light
        // along a wall row and then into unseen cells on the far side.
        if (level.tiles[x][y] == Tile.WALL) return LIGHT_NONE;
        if (!level.lit[x][y])       return LIGHT_NONE;
        if (level.visible[x][y])    return LIGHT_FULL;
        if (level.explored[x][y])   return LIGHT_DIM;
        return LIGHT_NONE;
    }

    private static int max4(int a, int b, int c, int d) {
        return Math.max(Math.max(a, b), Math.max(c, d));
    }

}
