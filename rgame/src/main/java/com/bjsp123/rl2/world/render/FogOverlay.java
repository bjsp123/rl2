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
 * with linear filtering — adjacent pixels of different fog densities blend smoothly during the
 * upscale, giving the characteristic soft gradient at visibility boundaries.
 *
 * A wall with another wall immediately to its south is
 * treated as "internal" and its left/right halves are sampled independently (self + horizontal
 * neighbor + south-diagonal if that neighbor is also a wall). Camera-facing walls (wall with
 * non-wall south) pick the darker of self and the cell south of them. This is what makes a wall
 * appear fully opaque when the cell beyond it has never been seen, and soften naturally when the
 * far side is known — the "far side rule".
 *
 *  We use a 4th fog level between LIT and EXPLORED so the
 * soft gradient also smooths the lit/visible-but-unlit transition.
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
            0x30000000, // VISIBLE:  ~19% black — slight dim, clearly brighter than EXPLORED
            0xA0000000, // EXPLORED: ~63% black — noticeably dim
            0xFF000000, // UNSEEN:   fully opaque black
    };

    // Warm lamplight glow, additively blended on top. Lit cells in current view get the full
    // glow; lit cells the player has seen before but can't currently see still emit a faint
    // remembered glow so the floor plan doesn't look eerily dark where a lamp clearly is. The
    // dim value is low enough that it never outshines a currently-visible lit cell next to it.
    private static final int LIGHT_NONE_ARGB = 0x00000000;
    private static final int LIGHT_DIM_ARGB  = 0x08FFE69A; // ~3% alpha
    private static final int LIGHT_FULL_ARGB = 0x24FFE69A; // ~14% alpha

    // Returned by cellLightLevel — ordinals matter (Math.max picks the brighter in corner sampling).
    private static final int LIGHT_NONE = 0;
    private static final int LIGHT_DIM  = 1;
    private static final int LIGHT_FULL = 2;

    private int mapW, mapH, pxW, pxH;
    private Pixmap        fogPixmap;
    private Texture       fogTexture;
    private TextureRegion fogRegion;
    private Pixmap        lightPixmap;
    private Texture       lightTexture;
    private TextureRegion lightRegion;
    private boolean       created;
    /** Tracks whether the fog/light pixmaps are stale vs the level's visibility+lit state.
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

        fogPixmap = newPixmap(FOG_ARGB[UNSEEN]);
        fogTexture = newLinearTexture(fogPixmap);
        fogRegion = new TextureRegion(fogTexture);
        fogRegion.flip(false, true);

        lightPixmap = newPixmap(LIGHT_NONE_ARGB);
        lightTexture = newLinearTexture(lightPixmap);
        lightRegion = new TextureRegion(lightTexture);
        lightRegion.flip(false, true);

        created = true;
        // Fresh pixmaps — need a full population on the next update(), regardless of the
        // caller's dirty tracking.
        dirty = true;
    }

    /** Flag the fog + light pixmaps as stale vs the level. The caller (PlayScreen) is
     *  responsible for calling this whenever visibility or lighting could have changed —
     *  primarily after a game tick, an amulet pickup/drop, or a level transition. */
    public void markDirty() { this.dirty = true; }

    private Pixmap newPixmap(int argb) {
        Pixmap p = new Pixmap(pxW, pxH, Pixmap.Format.RGBA8888);
        p.setBlending(Pixmap.Blending.None);
        p.setColor(argbToRgba8888(argb));
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
        fogTexture.draw(fogPixmap, 0, 0);
        lightTexture.draw(lightPixmap, 0, 0);
        dirty = false;
    }

    /**
     * Two-pass render:
     *   1) fog darkens by alpha blending (default blend)
     *   2) lamplight brightens LIT cells by additive blending
     * Both use linear filtering, so both the visibility and the light/dark transitions have the
     * same soft gradient look.
     */
    public void render(SpriteBatch batch) {
        if (!created) return;
        batch.setColor(1f, 1f, 1f, 1f);
        batch.draw(fogRegion, 0, 0, mapW * TILE_SIZE, mapH * TILE_SIZE);

        batch.setBlendFunction(GL20.GL_SRC_ALPHA, GL20.GL_ONE);
        batch.draw(lightRegion, 0, 0, mapW * TILE_SIZE, mapH * TILE_SIZE);
        batch.setBlendFunction(GL20.GL_SRC_ALPHA, GL20.GL_ONE_MINUS_SRC_ALPHA);
    }

    public void dispose() {
        if (fogTexture   != null) fogTexture.dispose();
        if (fogPixmap    != null) fogPixmap.dispose();
        if (lightTexture != null) lightTexture.dispose();
        if (lightPixmap  != null) lightPixmap.dispose();
        fogTexture = null; fogPixmap = null; fogRegion = null;
        lightTexture = null; lightPixmap = null; lightRegion = null;
        created = false;
    }

    /**
     * Fill the 4 pixmap pixels of a cell's 2×2 block by corner-sampling. Each pixel picks the
     * darkest fog (and OR of lit) from its own cell plus the 3 diagonal-adjacent cells that share
     * that corner. This makes the fog gradient START inside an explored cell when its neighbor is
     * unseen — so by the time the rendered image reaches the cell boundary (where the underlying
     * terrain abruptly ends), the fog alpha has already saturated to opaque black and the
     * "terrain-suddenly-disappears" hard edge is hidden by the dark fog. Same reason the lit/unlit
     * transition is soft: it's the pixmap values themselves that bleed across boundaries.
     */
    private void fillForCell(Level level, int x, int y) {
        int pxX = x * PIX_PER_TILE;
        int pxY = y * PIX_PER_TILE;
        int halfX = PIX_PER_TILE / 2;
        int halfY = PIX_PER_TILE / 2;

        if (level.tiles[x][y] == Tile.WALL) {
            // Fog uniform (see earlier note about keeping the wall sprite's cap visible).
            int fog = cellFog(level, x, y);
            fillFog(pxX, pxY, PIX_PER_TILE, PIX_PER_TILE, fog);

            // Light corner-sampled, but EXCLUDING the wall's own lit state. Walls stop light, so
            // a wall shouldn't propagate its own "lit" status. Each pixmap corner only picks up
            // light from the 3 non-self cells sharing it — meaning the south corners light up
            // when a lit floor is south of the wall, but the north corners stay dark and no
            // light bleeds through to unseen cells beyond.
            for (int iy = 0; iy < 2; iy++) {
                int dy = (iy == 0) ? -1 : +1;
                for (int ix = 0; ix < 2; ix++) {
                    int dx = (ix == 0) ? -1 : +1;
                    int lit = Math.max(Math.max(
                                cellLightLevel(level, x + dx, y),
                                cellLightLevel(level, x,      y + dy)),
                                cellLightLevel(level, x + dx, y + dy));
                    fillLight(pxX + ix * halfX, pxY + iy * halfY, halfX, halfY, lit);
                }
            }
            return;
        }

        // Non-wall: corner sampling so explored/unseen edges saturate inside an explored cell,
        // giving the blurry visibility boundary we want for floors/chasms/water.
        for (int iy = 0; iy < 2; iy++) {
            int dy = (iy == 0) ? -1 : +1;
            for (int ix = 0; ix < 2; ix++) {
                int dx = (ix == 0) ? -1 : +1;
                int fog = max4(
                        cellFog(level, x,      y),
                        cellFog(level, x + dx, y),
                        cellFog(level, x,      y + dy),
                        cellFog(level, x + dx, y + dy));
                fillFog(pxX + ix * halfX, pxY + iy * halfY, halfX, halfY, fog);
            }
        }

        // Light: strictly this cell's state. Walls stop light, so the warm glow must not bleed
        // onto wall caps, onto chasm/unseen blackness, or into any cell that isn't itself lit.
        fillLight(pxX, pxY, PIX_PER_TILE, PIX_PER_TILE, cellLightLevel(level, x, y));
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
        // Walls block light — they never emit it, even if their `lit` flag got set by
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

    private void fillFog(int x, int y, int w, int h, int state) {
        fogPixmap.setColor(argbToRgba8888(FOG_ARGB[state]));
        fogPixmap.fillRectangle(x, y, w, h);
    }

    private void fillLight(int x, int y, int w, int h, int level) {
        int argb = switch (level) {
            case LIGHT_FULL -> LIGHT_FULL_ARGB;
            case LIGHT_DIM  -> LIGHT_DIM_ARGB;
            default         -> LIGHT_NONE_ARGB;
        };
        lightPixmap.setColor(argbToRgba8888(argb));
        lightPixmap.fillRectangle(x, y, w, h);
    }

    private static int argbToRgba8888(int argb) {
        return (argb << 8) | (argb >>> 24);
    }
}
