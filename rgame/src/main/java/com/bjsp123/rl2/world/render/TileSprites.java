package com.bjsp123.rl2.world.render;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.graphics.g2d.TextureRegion;
import com.bjsp123.rl2.model.Level.VisualTheme;
import com.bjsp123.rl2.model.Tile;

import java.util.EnumMap;
import java.util.Map;

/**
 * Single source of truth for terrain-atlas access. Loads each {@link VisualTheme}'s
 * {@code sprites/terrain_*.png} once on demand and exposes:
 * <ul>
 *   <li>{@link #textureFor} — the loaded {@link Texture}, used by the in-world
 *       {@code DefaultLevelRenderer} to pre-build its bitfield-variant region table.</li>
 *   <li>{@link #cellSizeFor} — the per-atlas source-pixel cell size (auto-detected
 *       from the sheet's width because some themes ship 16-px cells and others 32-px).</li>
 *   <li>{@link #regionFor} — a representative one-cell portrait of a {@link Tile} for
 *       UI panels (look screen). Picks the canonical cell only; not the full
 *       bitfield-driven variant the world renderer chooses.</li>
 * </ul>
 *
 * <p>Atlas grids share a {@link #TERRAIN_COLS}-wide layout per the "TILE ATLAS LAYOUT
 * REFERENCE" block in {@code DefaultLevelRenderer}.
 */
public final class TileSprites {

    /** Number of columns in every terrain atlas. All sheets share this grid; only
     *  the source pixel pitch (cell size) varies between themes. */
    public static final int TERRAIN_COLS = 20;

    private static final Map<VisualTheme, String> PATHS = new EnumMap<>(VisualTheme.class);
    static {
        PATHS.put(VisualTheme.CRYSTAL,  "sprites/terrain_crystal.png");
        PATHS.put(VisualTheme.CONCRETE, "sprites/terrain_concrete.png");
    }

    private static Map<VisualTheme, Texture> textures;
    private static Map<VisualTheme, Integer> cellSizes;

    private TileSprites() {}

    /** The loaded terrain atlas for {@code theme}, or {@code null} if the asset was
     *  missing / failed to load. Lazy: triggers a one-shot load of every registered
     *  theme on first call. */
    public static Texture textureFor(VisualTheme theme) {
        if (textures == null) load();
        return textures.get(theme != null ? theme : VisualTheme.CRYSTAL);
    }

    /** Source-pixel size of one cell in {@code theme}'s atlas, auto-detected from the
     *  sheet's width / {@link #TERRAIN_COLS}. Returns 16 if the theme isn't loaded
     *  (safe fallback — most callers multiply by this and a missing theme just yields
     *  zero-area regions). */
    public static int cellSizeFor(VisualTheme theme) {
        if (textures == null) load();
        Integer c = cellSizes.get(theme != null ? theme : VisualTheme.CRYSTAL);
        return c != null ? c : 16;
    }

    /** Representative region for {@code tile} on {@code theme}'s atlas, or {@code null}
     *  if the atlas didn't load or the tile has no portrait mapping. */
    public static TextureRegion regionFor(Tile tile, VisualTheme theme) {
        if (tile == null) return null;
        Texture tex = textureFor(theme);
        if (tex == null) return null;
        int cell = cellSizeFor(theme);
        int[] spec = canonicalCell(tile);
        if (spec == null) return null;
        int col = spec[0], row = spec[1], w = spec[2], h = spec[3];
        return new TextureRegion(tex, col * cell, row * cell, w * cell, h * cell);
    }

    private static int[] canonicalCell(Tile tile) {
        return switch (tile) {
            case FLOOR        -> new int[]{0, 1, 1, 1};
            case FLOOR_WOOD   -> new int[]{0, 2, 1, 1};
            case WALL         -> new int[]{0, 4, 1, 1};
            case DOOR         -> new int[]{0, 8, 1, 1};
            case DOOR_OPEN    -> new int[]{0, 9, 1, 1};
            case CHASM        -> new int[]{0, 12, 1, 1};
            case LAMP         -> new int[]{18, 0, 1, 2};
            case STAIRS_UP    -> new int[]{0, 10, 2, 2};
            case STAIRS_DOWN  -> new int[]{2, 10, 2, 2};
            case STATUE_SMALL_L, STATUE_SMALL_R -> new int[]{17, 1, 1, 1};
            case STATUE_LARGE_L, STATUE_LARGE_R -> new int[]{19, 0, 1, 2};
        };
    }

    private static void load() {
        textures  = new EnumMap<>(VisualTheme.class);
        cellSizes = new EnumMap<>(VisualTheme.class);
        for (Map.Entry<VisualTheme, String> e : PATHS.entrySet()) {
            try {
                Texture tex = new Texture(Gdx.files.internal(e.getValue()));
                tex.setFilter(Texture.TextureFilter.Nearest, Texture.TextureFilter.Nearest);
                textures.put(e.getKey(), tex);
                cellSizes.put(e.getKey(), tex.getWidth() / TERRAIN_COLS);
            } catch (Exception ignored) {
                // Skip missing/broken atlases — regionFor returns null for that theme.
            }
        }
    }

    /** Release the cached terrain atlases. Subsequent {@link #regionFor} calls reload. */
    public static void disposeShared() {
        if (textures != null) {
            for (Texture t : textures.values()) if (t != null) t.dispose();
            textures = null;
        }
        cellSizes = null;
    }
}
