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
 * {@code sprites/terrain_*.png} once on demand and exposes everything the in-world
 * renderer and UI panels need to paint terrain: per-cell {@link TextureRegion} grids,
 * dedicated ornament regions (statue / lamp / stairs), the flat-index constants for
 * autotile bases, and deterministic variant pickers.
 *
 * <p>Atlas grids share a {@link #TERRAIN_COLS}-wide layout. Source pixel pitch varies
 * between themes (some sheets ship 16-px cells and others 32-px) - auto-detected per
 * atlas at load time from the sheet's width.
 *
 * <h2>TILE ATLAS LAYOUT REFERENCE</h2>
 * Authoritative map of where every tile lives inside a terrain sheet. Coordinates are
 * ZERO-INDEXED. {@code col} increases rightward, {@code row} increases downward
 * (matches the raw PNG, NOT the y-up world coordinates the renderer draws into).
 * <p>KEEP THIS IN SYNC whenever a tile, sprite, or row is added.
 *
 * <pre>
 *   (cols 0..2, row 1) - stone FLOOR variants, picked randomly per tile. Col 0 is
 *                     the canonical slot; cols 1 and 2 are alternates.
 *   (cols 0..2, row 2) - FLOOR_WOOD variants, picked randomly per tile. Col 0 is
 *                     the canonical slot; cols 1 and 2 are alternates.
 *   (col 6, row  1) - FLOOR_SPECIAL base (decorative floor variant).
 *   (col 7, row  1) - FLOOR_SPECIAL edge overlay drawn on top of a regular FLOOR
 *                     whose neighbour to the WEST is FLOOR_SPECIAL.
 *   (col 7, row  2) - FLOOR_SPECIAL edge overlay, neighbour-to-EAST is special.
 *   (col 8, row  1) - FLOOR_SPECIAL edge overlay, neighbour-to-SOUTH is special.
 *   (col 8, row  2) - FLOOR_SPECIAL edge overlay, neighbour-to-NORTH is special.
 *   (col 9, row  1) - FLOOR_SPECIAL corner overlay, diagonal-SE is special.
 *   (col 10, row 1) - FLOOR_SPECIAL corner overlay, diagonal-SW is special.
 *   (col 9, row  2) - FLOOR_SPECIAL corner overlay, diagonal-NE is special.
 *   (col 10, row 2) - FLOOR_SPECIAL corner overlay, diagonal-NW is special.
 *   (cols 12..14, row 4) - ALTAR (3x1 sprite); the centre cell is the anchor and
 *                     the sprite extends one cell west and one cell east of it.
 *   (col 15, rows 0..1) - THRONE (1x2 sprite, west-facing source); same overhang
 *                     anchoring as the lamp / large statue. {@code _R} variant
 *                     produced by horizontal flip at draw time.
 *   (col 0, row  3) - WALLS_OVERHANG (top cap of a raised wall, drawn one cell
 *                     NORTH of the wall so it overhangs entities behind it).
 *   (col 1, row  3) - WALLS_OVERHANG, right-south-open variant   (+1)
 *   (col 2, row  3) - WALLS_OVERHANG, left-south-open variant    (+2)
 *   (col 3, row  3) - WALLS_OVERHANG, both-open                  (+1+2)
 *   (col 0, row  4) - RAISED_WALL (solid wall body, stitch-0) - random pick per
 *                     tile with the alternates at cols 5, 6, 7 of row 4.
 *   (col 1, row  4) - RAISED_WALL, right-open variant            (+1)
 *   (col 2, row  4) - RAISED_WALL, left-open variant             (+2)
 *   (col 3, row  4) - RAISED_WALL, both-open                     (+1+2)
 *   (cols 5..7, row 4) - extra solid-wall art chosen randomly alongside (col 0).
 *   (cols 0..15, row 6) - WALLS_INTERNAL autotile variants. Bitfield:
 *                         +1 right, +2 right-south, +4 left-south, +8 left.
 *   (col 2, row  7) - alternate WALLS_INTERNAL for bitfield 2 (right-south
 *                     open) - picked randomly per tile alongside (col 2, row 6).
 *   (col 4, row  7) - alternate WALLS_INTERNAL for bitfield 4 (left-south
 *                     open) - picked randomly per tile alongside (col 4, row 6).
 *   (col 0, row  8) - RAISED_DOOR (north/south-facing CLOSED door body).
 *   (col 1, row  8) - DOOR_OVERHANG (arched top of a N/S-facing door, drawn in
 *                     the cell ABOVE the door body - appears for both open and closed).
 *   (col 2, row  8) - RAISED_DOOR_SIDEWAYS (E/W-facing door body - same sprite
 *                     for both open and closed; the open variant just drops its overlays).
 *   (cols 3..6, row 8) - DOOR_SIDEWAYS_OVERHANG_CLOSED variants (closed-only).
 *                        Bitfield: +1 right-south, +2 left-south (4 variants).
 *   (col 7, row  8) - DOOR_SIDEWAYS (wall-over-sideways-door cutout - closed only;
 *                     dropped when the door is open so the wall above renders normally).
 *   (col 0, row  9) - RAISED_DOOR_OPEN (N/S-facing door body when open - one row
 *                     below the closed body sprite).
 *   (col 17, row 1) - STATUE_SMALL (small statue, 1 cell, west-facing source -
 *                     east-facing variant produced by horizontal flip at draw time).
 *   (col 18, rows 0..1) - LAMP ornament (1x2 cells); drawn anchored at the floor
 *                     cell so the upper half overhangs into the cell above.
 *   (col 19, rows 0..1) - STATUE_LARGE (tall statue, 1x2 cells); same overhang
 *                     anchoring as the lamp, plus L/R facing flip at draw.
 *   (cols 0..1, rows 10..11) - STAIRS_UP ladder, 2x2 cells. Drawn on top of a
 *                     regular floor underlay, anchored at the bottom of the stair cell.
 *   (cols 2..3, rows 10..11) - STAIRS_DOWN ladder, 2x2 cells, same conventions.
 *   (col 0, row 12) - CHASM_FLOOR (floor dripping into chasm, N edge).
 *   (col 1, row 12) - CHASM_WALL  (wall dripping into chasm, N edge).
 *   (col 2, row 12) - CHASM_WATER (water dripping into chasm, N edge).
 *   (col 3, row 12) - CHASM_WOOD  (wood floor dripping into chasm, N edge).
 *   (cols 0..15, row 14) - WATER autotile variants. Bitfield:
 *                          +1 north, +2 east, +4 south, +8 west.
 * </pre>
 */
public final class TileSprites {

    /** Number of columns in every terrain atlas. */
    public static final int TERRAIN_COLS = 20;

    // -- Flat-index bases for each tile family. The renderer composes a final
    //    flat index by adding an autotile bitfield: e.g. WATER + (+1 north, +2 east,
    //    +4 south, +8 west). Variant pickers below produce stable per-tile slots
    //    inside families that ship multiple alternates.
    public static final int WALLS_OVERHANG                =  3 * TERRAIN_COLS + 0; // +1 r-s, +2 l-s
    public static final int RAISED_WALL                   =  4 * TERRAIN_COLS + 0; // +1 r,   +2 l
    public static final int WALLS_INTERNAL                =  6 * TERRAIN_COLS + 0; // +1 r, +2 r-s, +4 l-s, +8 l
    public static final int RAISED_DOOR                   =  8 * TERRAIN_COLS + 0;
    public static final int DOOR_OVERHANG                 =  8 * TERRAIN_COLS + 1;
    public static final int RAISED_DOOR_SIDEWAYS          =  8 * TERRAIN_COLS + 2;
    public static final int DOOR_SIDEWAYS_OVERHANG_CLOSED =  8 * TERRAIN_COLS + 3; // +1 r-s, +2 l-s
    public static final int DOOR_SIDEWAYS                 =  8 * TERRAIN_COLS + 7; // wall-over-sideways-door cutout
    /** Open-door body for the front-facing variant - one row below {@link #RAISED_DOOR}. */
    public static final int RAISED_DOOR_OPEN              =  9 * TERRAIN_COLS + 0;
    public static final int CHASM_FLOOR                   = 12 * TERRAIN_COLS + 0;
    public static final int CHASM_WALL                    = 12 * TERRAIN_COLS + 1;
    public static final int CHASM_WATER                   = 12 * TERRAIN_COLS + 2;
    public static final int CHASM_WOOD                    = 12 * TERRAIN_COLS + 3;
    public static final int WATER                         = 14 * TERRAIN_COLS + 0; // +1 n, +2 e, +4 s, +8 w

    // -- Variant tables. Private - picks go through the helpers below so callers
    //    don't see the index arithmetic. Each entry is a flat index into the
    //    per-cell TextureRegion[] grid (col + row * TERRAIN_COLS).
    private static final int[] FLOOR_VARIANTS = {
            1 * TERRAIN_COLS + 0,
            1 * TERRAIN_COLS + 1,
            1 * TERRAIN_COLS + 2,
            1 * TERRAIN_COLS + 3,
            1 * TERRAIN_COLS + 4,
            1 * TERRAIN_COLS + 5,
    };
    private static final int[] FLOOR_WOOD_VARIANTS = {
            2 * TERRAIN_COLS + 0,
            2 * TERRAIN_COLS + 1,
            2 * TERRAIN_COLS + 2,
            2 * TERRAIN_COLS + 3,
            2 * TERRAIN_COLS + 4,
            2 * TERRAIN_COLS + 5,
    };
    /** Stitch-0 (no neighbours open) only - bits 1..3 keep their unique slots. */
    private static final int[] RAISED_WALL_VARIANTS = {
            4 * TERRAIN_COLS + 0,
            4 * TERRAIN_COLS + 5,
            4 * TERRAIN_COLS + 6,
            4 * TERRAIN_COLS + 7,
            4 * TERRAIN_COLS + 8,
            4 * TERRAIN_COLS + 9,
            4 * TERRAIN_COLS + 10,
            4 * TERRAIN_COLS + 11,
            4 * TERRAIN_COLS + 12,
    };


    // -- Top-right ornament source coords (statues + lamp + stairs). The lamp and
    //    tall statue are 1-cell wide x 2-cells tall - drawn anchored at the floor
    //    cell with the upper half overhanging into the cell above. Stairs are 2x2.
    private static final int STATUE_SMALL_COL = 17;
    private static final int STATUE_SMALL_ROW = 1;
    private static final int LAMP_COL         = 18;
    private static final int LAMP_ROW         = 0;
    private static final int STATUE_LARGE_COL = 19;
    private static final int STATUE_LARGE_ROW = 0;
    private static final int STAIRS_UP_COL    = 0;
    private static final int STAIRS_UP_ROW    = 10;
    private static final int STAIRS_DOWN_COL  = 2;
    private static final int STAIRS_DOWN_ROW  = 10;
    private static final int STAIRS_W_CELLS   = 2;
    private static final int STAIRS_H_CELLS   = 2;

    private static final int SPECIAL_FLOOR_COL = 6;
    private static final int SPECIAL_FLOOR_ROW = 1;
    /** {@code SPECIAL_FLOOR_EDGE[direction]} = atlas (col, row) for the overlay
     *  drawn on a regular FLOOR cell whose adjacent neighbour in that cardinal
     *  direction is FLOOR_SPECIAL. Direction order matches {@link Edge}. */
    private static final int[][] SPECIAL_FLOOR_EDGE = {
            {7, 1},  // W neighbour is special
            {7, 2},  // E neighbour is special
            {8, 1},  // S neighbour is special
            {8, 2},  // N neighbour is special
    };
    /** {@code SPECIAL_FLOOR_CORNER[corner]} - overlay for a regular FLOOR cell
     *  whose diagonal neighbour is FLOOR_SPECIAL. Order matches {@link Corner}. */
    private static final int[][] SPECIAL_FLOOR_CORNER = {
            {9,  1},  // SE
            {10, 1},  // SW
            {9,  2},  // NE
            {10, 2},  // NW
    };
    private static final int ALTAR_COL  = 12;   // leftmost cell - sprite spans cols 12..14
    private static final int ALTAR_ROW  = 4;
    private static final int ALTAR_W_CELLS = 3;
    private static final int THRONE_COL = 15;
    private static final int THRONE_ROW = 0;
    private static final int THRONE_H_CELLS = 2;

    /** Cardinal direction for {@link #SPECIAL_FLOOR_EDGE} and the public
     *  {@link #specialFloorEdge} accessor. */
    public enum Edge { W, E, S, N }

    /** Diagonal corner for {@link #SPECIAL_FLOOR_CORNER} and the public
     *  {@link #specialFloorCorner} accessor. */
    public enum Corner { SE, SW, NE, NW }

    private static final Map<VisualTheme, String> PATHS = new EnumMap<>(VisualTheme.class);
    static {
        PATHS.put(VisualTheme.CRYSTAL,         "sprites/terrain_crystal.png");
        PATHS.put(VisualTheme.CONCRETE,        "sprites/terrain_concrete.png");
        PATHS.put(VisualTheme.STRAIGHTFORWARD, "sprites/terrain_shiny.png");
    }

    private static Map<VisualTheme, Texture> textures;
    private static Map<VisualTheme, Integer> cellSizes;
    private static Map<VisualTheme, TextureRegion[]> regionsByTheme;
    private static Map<VisualTheme, TextureRegion> smallStatueByTheme;
    private static Map<VisualTheme, TextureRegion> largeStatueByTheme;
    private static Map<VisualTheme, TextureRegion> lampByTheme;
    private static Map<VisualTheme, TextureRegion> stairsUpByTheme;
    private static Map<VisualTheme, TextureRegion> stairsDownByTheme;
    private static Map<VisualTheme, TextureRegion> altarByTheme;
    private static Map<VisualTheme, TextureRegion> throneByTheme;
    /** Per-theme average floor + wall colours, sampled from the terrain atlas
     *  during {@link #load}. Used by the world map to tint level cards by
     *  theme. RGBA-packed via {@link com.badlogic.gdx.graphics.Color#toIntBits}. */
    private static Map<VisualTheme, com.badlogic.gdx.graphics.Color> floorTintByTheme;
    private static Map<VisualTheme, com.badlogic.gdx.graphics.Color> wallTintByTheme;

    private TileSprites() {}

    /** The loaded terrain atlas for {@code theme}, or {@code null} if the asset was
     *  missing / failed to load. Lazy: triggers a one-shot load of every registered
     *  theme on first call. */
    public static Texture textureFor(VisualTheme theme) {
        if (textures == null) load();
        return textures.get(theme != null ? theme : VisualTheme.CRYSTAL);
    }

    /** Pre-built per-cell {@link TextureRegion} grid for {@code theme}, indexed by
     *  flat cell number ({@code col + row * TERRAIN_COLS}). The renderer binds this
     *  array once per frame and indexes by the constants / variant helpers above.
     *  Returns the CRYSTAL grid if the requested theme isn't loaded. */
    public static TextureRegion[] regionsFor(VisualTheme theme) {
        if (regionsByTheme == null) load();
        if (regionsByTheme == null) return null;
        TextureRegion[] arr = regionsByTheme.get(theme);
        return arr != null ? arr : regionsByTheme.get(VisualTheme.CRYSTAL);
    }

    /** Small-statue ornament region (1 cell). Falls back to CRYSTAL's region. */
    public static TextureRegion smallStatue(VisualTheme theme) {
        if (smallStatueByTheme == null) load();
        return ornament(smallStatueByTheme, theme);
    }

    /** Tall-statue ornament region (1x2 cells, anchored at the floor cell). */
    public static TextureRegion largeStatue(VisualTheme theme) {
        if (largeStatueByTheme == null) load();
        return ornament(largeStatueByTheme, theme);
    }

    /** Lamp ornament region (1x2 cells, anchored at the floor cell). */
    public static TextureRegion lampOrnament(VisualTheme theme) {
        if (lampByTheme == null) load();
        return ornament(lampByTheme, theme);
    }

    /** Up-ladder ornament region (2x2 cells, anchored at the bottom of the stair cell). */
    public static TextureRegion stairsUp(VisualTheme theme) {
        if (stairsUpByTheme == null) load();
        return ornament(stairsUpByTheme, theme);
    }

    /** Down-ladder ornament region (2x2 cells). */
    public static TextureRegion stairsDown(VisualTheme theme) {
        if (stairsDownByTheme == null) load();
        return ornament(stairsDownByTheme, theme);
    }

    /** Altar ornament region (3x1 cells, anchored at the central tile so the
     *  sprite extends one cell west and one cell east). */
    public static TextureRegion altar(VisualTheme theme) {
        if (altarByTheme == null) load();
        return ornament(altarByTheme, theme);
    }

    /** Throne ornament region (1x2 cells, west-facing source - east-facing
     *  variant produced by horizontal flip at draw time, like statues). */
    public static TextureRegion throne(VisualTheme theme) {
        if (throneByTheme == null) load();
        return ornament(throneByTheme, theme);
    }

    /** Flat region-index for the FLOOR_SPECIAL base sprite. The renderer adds
     *  the same per-cell index lookup used for FLOOR variants on top of the
     *  per-theme {@link #regionsFor} array. */
    public static int specialFloor() {
        return SPECIAL_FLOOR_ROW * TERRAIN_COLS + SPECIAL_FLOOR_COL;
    }

    /** Flat region-index for the edge overlay drawn on top of a regular FLOOR
     *  whose neighbour in {@code edge} is FLOOR_SPECIAL. */
    public static int specialFloorEdge(Edge edge) {
        int[] cr = SPECIAL_FLOOR_EDGE[edge.ordinal()];
        return cr[1] * TERRAIN_COLS + cr[0];
    }

    /** Flat region-index for the corner overlay drawn on top of a regular FLOOR
     *  whose diagonal neighbour in {@code corner} is FLOOR_SPECIAL. */
    public static int specialFloorCorner(Corner corner) {
        int[] cr = SPECIAL_FLOOR_CORNER[corner.ordinal()];
        return cr[1] * TERRAIN_COLS + cr[0];
    }

    /** Average floor-tile colour for {@code theme}, sampled from the terrain
     *  atlas at startup. Falls back to CRYSTAL's tint if {@code theme} didn't
     *  load. Used by the world map to colour level cards by theme. */
    public static com.badlogic.gdx.graphics.Color floorTint(VisualTheme theme) {
        if (floorTintByTheme == null) load();
        return tintLookup(floorTintByTheme, theme);
    }

    /** Average wall-tile colour for {@code theme}; companion to {@link #floorTint}. */
    public static com.badlogic.gdx.graphics.Color wallTint(VisualTheme theme) {
        if (wallTintByTheme == null) load();
        return tintLookup(wallTintByTheme, theme);
    }

    private static com.badlogic.gdx.graphics.Color tintLookup(
            Map<VisualTheme, com.badlogic.gdx.graphics.Color> map, VisualTheme theme) {
        if (map == null) return com.badlogic.gdx.graphics.Color.GRAY;
        com.badlogic.gdx.graphics.Color c = map.get(theme);
        if (c == null) c = map.get(VisualTheme.CRYSTAL);
        return c != null ? c : com.badlogic.gdx.graphics.Color.GRAY;
    }

    private static TextureRegion ornament(Map<VisualTheme, TextureRegion> map, VisualTheme theme) {
        if (map == null) return null;
        TextureRegion r = map.get(theme);
        return r != null ? r : map.get(VisualTheme.CRYSTAL);
    }

    /** Representative region for {@code tile} on {@code theme}'s atlas, or {@code null}
     *  if the atlas didn't load or the tile has no portrait mapping. UI panels use this
     *  for tile-icon previews; the in-world renderer uses {@link #regionsFor} instead. */
    public static TextureRegion regionFor(Tile tile, VisualTheme theme) {
        if (tile == null) return null;
        Texture tex = textureFor(theme);
        if (tex == null) return null;
        int cell = 32;
        int[] spec = canonicalCell(tile);
        if (spec == null) return null;
        int col = spec[0], row = spec[1], w = spec[2], h = spec[3];
        return new TextureRegion(tex, col * cell, row * cell, w * cell, h * cell);
    }

    private static int[] canonicalCell(Tile tile) {
        return switch (tile) {
            case FLOOR         -> new int[]{0, 1, 1, 1};
            case FLOOR_WOOD    -> new int[]{0, 2, 1, 1};
            case FLOOR_SPECIAL -> new int[]{SPECIAL_FLOOR_COL, SPECIAL_FLOOR_ROW, 1, 1};
            case WALL          -> new int[]{0, 4, 1, 1};
            case DOOR          -> new int[]{0, 8, 1, 1};
            case DOOR_OPEN     -> new int[]{0, 9, 1, 1};
            case CHASM         -> new int[]{0, 12, 1, 1};
            case LAMP          -> new int[]{LAMP_COL, LAMP_ROW, 1, 2};
            case STAIRS_UP     -> new int[]{STAIRS_UP_COL,   STAIRS_UP_ROW,   STAIRS_W_CELLS, STAIRS_H_CELLS};
            case STAIRS_DOWN   -> new int[]{STAIRS_DOWN_COL, STAIRS_DOWN_ROW, STAIRS_W_CELLS, STAIRS_H_CELLS};
            case STATUE_SMALL_L, STATUE_SMALL_R -> new int[]{STATUE_SMALL_COL, STATUE_SMALL_ROW, 1, 1};
            case STATUE_LARGE_L, STATUE_LARGE_R -> new int[]{STATUE_LARGE_COL, STATUE_LARGE_ROW, 1, 2};
            case ALTAR         -> new int[]{ALTAR_COL, ALTAR_ROW, ALTAR_W_CELLS, 1};
            case THRONE_L, THRONE_R -> new int[]{THRONE_COL, THRONE_ROW, 1, THRONE_H_CELLS};
        };
    }

    // -- Variant pickers - take a deterministic per-tile hash from {@link #variantHash}
    //    so the same (x, y) keeps the same pick across frames. Splitting the picker
    //    from the hash lets callers hash on whatever they want (e.g. animation frame).

    /** Stable per-tile hash. Same {@code (x, y)} always maps to the same int. The
     *  multipliers are mutually coprime so adjacent cells produce uncorrelated hashes. */
    public static int variantHash(int x, int y) {
        return x * 92613371 ^ y * 17391317;
    }

    /** FLOOR variant slot for {@code hash}. */
    public static int floorVariant(int hash) {
        return FLOOR_VARIANTS[Math.floorMod(hash, FLOOR_VARIANTS.length)];
    }

    /** FLOOR_WOOD variant slot for {@code hash}. */
    public static int floorWoodVariant(int hash) {
        return FLOOR_WOOD_VARIANTS[Math.floorMod(hash, FLOOR_WOOD_VARIANTS.length)];
    }

    /** Raised-wall slot for the given autotile {@code bits} (E/W neighbour mask:
     *  +1 right open, +2 left open). Stitch-0 (solid block) picks deterministically
     *  from the multiple art alternatives at row 4 using {@code hash}; bits 1..3 keep
     *  their unique stitch slots so doorway openings still read correctly. */
    public static int raisedWallVariant(int hash, int bits) {
        if (bits == 0) {
            return RAISED_WALL_VARIANTS[Math.floorMod(hash, RAISED_WALL_VARIANTS.length)];
        }
        return RAISED_WALL + bits;
    }

    /** Internal-wall slot for the given autotile {@code bits} (4-bit corner mask:
     *  +1 right, +2 right-south, +4 left-south, +8 left). The two single-south-corner
     *  variants (bits 2 and 4) ship a row-7 alternate; the renderer flips a 50/50
     *  coin via {@code hash} between the row-6 original and the row-7 alt. */
    public static int internalWallVariant(int hash, int bits) {
        int result = WALLS_INTERNAL + bits;
        if (Math.floorMod(hash, 2) == 1) {
            result += TERRAIN_COLS;
        }
        return result;
    }

    private static void load() {
        textures           = new EnumMap<>(VisualTheme.class);
        cellSizes          = new EnumMap<>(VisualTheme.class);
        regionsByTheme     = new EnumMap<>(VisualTheme.class);
        smallStatueByTheme = new EnumMap<>(VisualTheme.class);
        largeStatueByTheme = new EnumMap<>(VisualTheme.class);
        lampByTheme        = new EnumMap<>(VisualTheme.class);
        stairsUpByTheme    = new EnumMap<>(VisualTheme.class);
        stairsDownByTheme  = new EnumMap<>(VisualTheme.class);
        altarByTheme       = new EnumMap<>(VisualTheme.class);
        throneByTheme      = new EnumMap<>(VisualTheme.class);
        floorTintByTheme   = new EnumMap<>(VisualTheme.class);
        wallTintByTheme    = new EnumMap<>(VisualTheme.class);
        for (Map.Entry<VisualTheme, String> e : PATHS.entrySet()) {
            try {
                // Read the atlas as a Pixmap first so we can sample floor/wall
                // pixel averages for the world-map theme tint, then upload it as
                // the renderer's Texture.
                com.badlogic.gdx.graphics.Pixmap pm =
                        new com.badlogic.gdx.graphics.Pixmap(Gdx.files.internal(e.getValue()));
                Texture tex = new Texture(Gdx.files.internal(e.getValue()));
                tex.setFilter(Texture.TextureFilter.Nearest, Texture.TextureFilter.Nearest);
                textures.put(e.getKey(), tex);
                int srcCell = Math.max(1, pm.getWidth() / TERRAIN_COLS);
                cellSizes.put(e.getKey(), srcCell);
                buildRegions(e.getKey(), tex, srcCell);
                floorTintByTheme.put(e.getKey(), sampleAverage(pm, srcCell, 1, 6, 2));
                wallTintByTheme .put(e.getKey(), sampleAverage(pm, srcCell, 4, 6, 1));
                pm.dispose();
            } catch (Exception ignored) {
                // Skip missing/broken atlases - accessors return null for that theme.
            }
        }
    }

    /** Average opaque-pixel colour over {@code cols} x {@code rows} cells starting
     *  at atlas grid position {@code (0, startRow)}, in {@code srcCell}-pixel units.
     *  Transparent pixels are ignored so the variant cells (which often have alpha
     *  edges) don't bleed black into the average. */
    private static com.badlogic.gdx.graphics.Color sampleAverage(
            com.badlogic.gdx.graphics.Pixmap pm, int srcCell,
            int startRow, int cols, int rows) {
        long rSum = 0, gSum = 0, bSum = 0, n = 0;
        int x0 = 0;
        int y0 = startRow * srcCell;
        int w  = Math.min(cols * srcCell, pm.getWidth()  - x0);
        int h  = Math.min(rows * srcCell, pm.getHeight() - y0);
        if (w <= 0 || h <= 0) return new com.badlogic.gdx.graphics.Color(0.5f, 0.5f, 0.5f, 1f);
        for (int y = y0; y < y0 + h; y++) {
            for (int x = x0; x < x0 + w; x++) {
                int rgba = pm.getPixel(x, y);
                int a = rgba & 0xff;
                if (a < 16) continue;
                rSum += (rgba >>> 24) & 0xff;
                gSum += (rgba >>> 16) & 0xff;
                bSum += (rgba >>>  8) & 0xff;
                n++;
            }
        }
        if (n == 0) return new com.badlogic.gdx.graphics.Color(0.5f, 0.5f, 0.5f, 1f);
        return new com.badlogic.gdx.graphics.Color(
                rSum / 255f / n, gSum / 255f / n, bSum / 255f / n, 1f);
    }

    /** Slice {@code tex} into per-cell regions and pull out the 1x2 / 2x2 ornaments
     *  the renderer accesses by name. Out-of-bounds rows (older atlases without an
     *  ornament slot) just leave the corresponding map entry empty. */
    private static void buildRegions(VisualTheme theme, Texture tex, int srcCell) {
        int rows = tex.getHeight() / srcCell;
        TextureRegion[] arr = new TextureRegion[rows * TERRAIN_COLS];
        for (int r = 0; r < rows; r++) {
            for (int c = 0; c < TERRAIN_COLS; c++) {
                arr[r * TERRAIN_COLS + c] =
                        new TextureRegion(tex, c * srcCell, r * srcCell, srcCell, srcCell);
            }
        }
        regionsByTheme.put(theme, arr);

        int statueX = STATUE_SMALL_COL * srcCell;
        int statueY = STATUE_SMALL_ROW * srcCell;
        if (statueX + srcCell <= tex.getWidth() && statueY + srcCell <= tex.getHeight()) {
            smallStatueByTheme.put(theme,
                    new TextureRegion(tex, statueX, statueY, srcCell, srcCell));
        }
        int lampX = LAMP_COL * srcCell;
        int lampY = LAMP_ROW * srcCell;
        if (lampX + srcCell <= tex.getWidth() && lampY + 2 * srcCell <= tex.getHeight()) {
            lampByTheme.put(theme,
                    new TextureRegion(tex, lampX, lampY, srcCell, 2 * srcCell));
        }
        int largeX = STATUE_LARGE_COL * srcCell;
        int largeY = STATUE_LARGE_ROW * srcCell;
        if (largeX + srcCell <= tex.getWidth() && largeY + 2 * srcCell <= tex.getHeight()) {
            largeStatueByTheme.put(theme,
                    new TextureRegion(tex, largeX, largeY, srcCell, 2 * srcCell));
        }
        int stairsWPx = STAIRS_W_CELLS * srcCell;
        int stairsHPx = STAIRS_H_CELLS * srcCell;
        int stairsUpX = STAIRS_UP_COL * srcCell;
        int stairsUpY = STAIRS_UP_ROW * srcCell;
        if (stairsUpX + stairsWPx <= tex.getWidth()
                && stairsUpY + stairsHPx <= tex.getHeight()) {
            stairsUpByTheme.put(theme,
                    new TextureRegion(tex, stairsUpX, stairsUpY, stairsWPx, stairsHPx));
        }
        int stairsDownX = STAIRS_DOWN_COL * srcCell;
        int stairsDownY = STAIRS_DOWN_ROW * srcCell;
        if (stairsDownX + stairsWPx <= tex.getWidth()
                && stairsDownY + stairsHPx <= tex.getHeight()) {
            stairsDownByTheme.put(theme,
                    new TextureRegion(tex, stairsDownX, stairsDownY, stairsWPx, stairsHPx));
        }
        int altarX = ALTAR_COL * srcCell;
        int altarY = ALTAR_ROW * srcCell;
        int altarWPx = ALTAR_W_CELLS * srcCell;
        if (altarX + altarWPx <= tex.getWidth() && altarY + srcCell <= tex.getHeight()) {
            altarByTheme.put(theme, new TextureRegion(tex, altarX, altarY, altarWPx, srcCell));
        }
        int throneX = THRONE_COL * srcCell;
        int throneY = THRONE_ROW * srcCell;
        int throneHPx = THRONE_H_CELLS * srcCell;
        if (throneX + srcCell <= tex.getWidth() && throneY + throneHPx <= tex.getHeight()) {
            throneByTheme.put(theme, new TextureRegion(tex, throneX, throneY, srcCell, throneHPx));
        }
    }

    /** Release the cached terrain atlases. Subsequent accessors reload. */
    public static void disposeShared() {
        if (textures != null) {
            for (Texture t : textures.values()) if (t != null) t.dispose();
            textures = null;
        }
        cellSizes          = null;
        regionsByTheme     = null;
        smallStatueByTheme = null;
        largeStatueByTheme = null;
        lampByTheme        = null;
        stairsUpByTheme    = null;
        stairsDownByTheme  = null;
        altarByTheme       = null;
        throneByTheme      = null;
        floorTintByTheme   = null;
        wallTintByTheme    = null;
    }
}
