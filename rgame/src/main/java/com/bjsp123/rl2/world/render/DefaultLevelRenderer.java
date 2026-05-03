package com.bjsp123.rl2.world.render;
import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.OrthographicCamera;
import com.badlogic.gdx.graphics.Pixmap;
import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.graphics.g2d.BitmapFont;
import com.badlogic.gdx.graphics.g2d.SpriteBatch;
import com.badlogic.gdx.graphics.g2d.TextureRegion;
import com.badlogic.gdx.graphics.glutils.ShaderProgram;
import com.bjsp123.rl2.model.Mob.CharacterClass;
import com.bjsp123.rl2.model.Item;
import com.bjsp123.rl2.model.Level;
import com.bjsp123.rl2.model.Mob;
import com.bjsp123.rl2.model.Mob.Behavior;
import com.bjsp123.rl2.model.Mob.MobType;
import com.bjsp123.rl2.model.Level.Surface;
import com.bjsp123.rl2.model.Tile;
import com.bjsp123.rl2.model.Level.Vegetation;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import com.bjsp123.rl2.world.anim.MobAnimState;
import com.bjsp123.rl2.logic.TurnSystem;

/**
 * Tile renderer. Loads three sheets directly:
 *
 * <ul>
 *   <li>{@code sprites/terrain_<theme>.png} — one per {@link com.bjsp123.rl2.model.Level.VisualTheme},
 *       sharing a 20-col grid (floors, walls, wall caps, internal walls, doors, stairs,
 *       chasm edges, water). Layout reference lives in the block immediately below.</li>
 *   <li>{@code sprites/mobs.png} — packed silhouette atlas for every NPC + the player
 *       class poses; cells indexed via the {@code MOB_CELL_*} constants.</li>
 *   <li>{@code sprites/surfaces.png} — liquid tiles (water / blood / oil), the shore-mask
 *       strip, and the vegetation sprites (grass / mushroom / tree).</li>
 * </ul>
 * Item, gem, and staff art is owned by {@link ItemSprites} and {@link GemSprites};
 * painted-fire sheets at {@code sprites/fire 1.png} / {@code sprites/fire 2.png} are
 * loaded optionally — if absent, fire tiles draw nothing.
 *
 * <p>Rendering walks the map in three passes:
 * <ol>
 *   <li><b>Floors + chasm</b> (any order). Base floor tile, stair glyphs, chasm black fill,
 *       and the "dripping edge" chasm tile pulled from the north neighbor.</li>
 *   <li><b>Per-cell content</b>, north→south, west→east. In each cell the layers compose
 *       in this order: surface (water / blood / oil) → floor-edge shadow → wall / door body
 *       + wall-base shadow + rear-corner shadows → rear vegetation → lamp → items → mobs →
 *       front vegetation (tucks over mob feet) → fx. Fx run even on unexplored tiles so
 *       the player sees their own projectiles.</li>
 *   <li><b>Wall + door tops</b>, north→south. Overhangs and internal-wall caps painted on
 *       the cell to the NORTH of each wall / door so tall scenery clips anything behind
 *       it. Sideways-door overhangs paint on the door cell itself.</li>
 * </ol>
 * Fog of war then runs once as a single soft-edge overlay with an additive lamp-light layer.
 */
public class DefaultLevelRenderer implements LevelRenderer {

    // ================================================================================
    // TILE ATLAS LAYOUT REFERENCE
    // --------------------------------------------------------------------------------
    // Authoritative map of every texture file this renderer loads and what lives at
    // each (col,row) inside it. Coordinates are ZERO-INDEXED. Unless stated otherwise,
    // cells are 16×16 px and "row"/"col" mean cells of that size from the image's
    // top-left corner (col increases rightward, row increases downward — matches the
    // raw PNG, NOT the y-up world coordinates the renderer draws into).
    //
    // KEEP THIS IN SYNC whenever a new tile, sprite, row, or file is added.
    // ================================================================================
    //
    // ─── sprites/terrain_default.png, terrain_blue.png, terrain_organic.png,
    //     terrain_neutral.png ────────────────────────────────────────────────────────
    //   All four per-theme sheets share the same 20-col grid. Default and neutral are
    //   currently exported at 32 px/cell (640×512); blue and organic are still 16 px/cell
    //   (320×256). Source cell size is auto-detected per atlas at load time
    //   (tex.width / TERRAIN_COLS); regardless of source size, every tile is drawn into
    //   a 16-px world cell, so 32-px source art is scaled 2:1 down. Rows not listed are
    //   reserved/blank for future additions. For rows that carry multiple variants, the
    //   variant index is a bitfield added to the base col (see constants at FLOOR..WATER
    //   below).
    //     (cols 0..2, row 1) — stone FLOOR variants, picked randomly per tile. Col 0 is
    //                       the canonical slot; cols 1 and 2 are alternates.
    //     (cols 0..2, row 2) — FLOOR_WOOD variants, picked randomly per tile. Col 0 is
    //                       the canonical slot; cols 1 and 2 are alternates.
    //     (col 0, row  3) — WALLS_OVERHANG (top cap of a raised wall, drawn one cell
    //                       NORTH of the wall so it overhangs entities behind it).
    //     (col 1, row  3) — WALLS_OVERHANG, right-south-open variant   (+1)
    //     (col 2, row  3) — WALLS_OVERHANG, left-south-open variant    (+2)
    //     (col 3, row  3) — WALLS_OVERHANG, both-open                  (+1+2)
    //     (col 0, row  4) — RAISED_WALL (solid wall body, stitch-0) — random pick per
    //                       tile with the alternates at cols 5, 6, 7 of row 4.
    //     (col 1, row  4) — RAISED_WALL, right-open variant            (+1)
    //     (col 2, row  4) — RAISED_WALL, left-open variant             (+2)
    //     (col 3, row  4) — RAISED_WALL, both-open                     (+1+2)
    //     (cols 5..7, row 4) — extra solid-wall art chosen randomly alongside (col 0).
    //     (cols 0..15, row 6) — WALLS_INTERNAL autotile variants. Bitfield:
    //                           +1 right, +2 right-south, +4 left-south, +8 left.
    //     (col 2, row  7) — alternate WALLS_INTERNAL for bitfield 2 (right-south
    //                       open) — picked randomly per tile alongside (col 2, row 6).
    //     (col 4, row  7) — alternate WALLS_INTERNAL for bitfield 4 (left-south
    //                       open) — picked randomly per tile alongside (col 4, row 6).
    //     (col 0, row  8) — RAISED_DOOR (north/south-facing CLOSED door body).
    //     (col 1, row  8) — DOOR_OVERHANG (arched top of a N/S-facing door, drawn in the
    //                       cell ABOVE the door body — appears for both open and closed).
    //     (col 2, row  8) — RAISED_DOOR_SIDEWAYS (E/W-facing door body — same sprite for
    //                       both open and closed; the open variant just drops its overlays).
    //     (cols 3..6, row 8) — DOOR_SIDEWAYS_OVERHANG_CLOSED variants (closed-only). Bitfield:
    //                          +1 right-south, +2 left-south (4 variants).
    //     (col 7, row  8) — DOOR_SIDEWAYS (wall-over-sideways-door cutout — closed only;
    //                       dropped when the door is open so the wall above renders normally).
    //     (col 0, row  9) — RAISED_DOOR_OPEN (N/S-facing door body when open — one row below
    //                       the closed body sprite).
    //     (col 17, row 0) — STATUE_SMALL (small statue, 1 cell, west-facing source —
    //                       east-facing variant produced by horizontal flip at draw time).
    //     (col 18, rows 0..1) — LAMP ornament (1×2 cells, 32×64 source); drawn anchored at
    //                       the floor cell so the upper half overhangs into the cell above.
    //     (col 19, rows 0..1) — STATUE_LARGE (tall statue, 1×2 cells, 32×64 source); same
    //                       overhang anchoring as the lamp, plus L/R facing flip at draw.
    //     (cols 0..1, rows 10..11) — STAIRS_UP ladder, 2×2 cells (64×64 source). Drawn
    //                       on top of a regular floor underlay, anchored at the bottom of
    //                       the stair cell, displayed at 2×2 world cells (32×32 px).
    //     (cols 2..3, rows 10..11) — STAIRS_DOWN ladder, 2×2 cells, same conventions as
    //                       STAIRS_UP.
    //     (col 0, row 12) — CHASM_FLOOR (floor dripping into chasm, N edge).
    //     (col 1, row 12) — CHASM_WALL  (wall dripping into chasm, N edge).
    //     (col 2, row 12) — CHASM_WATER (water dripping into chasm, N edge).
    //     (col 3, row 12) — CHASM_WOOD  (wood floor dripping into chasm, N edge).
    //     (cols 0..15, row 14) — WATER autotile variants. Bitfield:
    //                            +1 north, +2 east, +4 south, +8 west.
    //
    // ─── sprites/items.png ─────────────────────────────────────────────────────────
    //   32×32 cell grid loaded by ItemSprites (not this renderer directly). Rows:
    //     row 0 — wands; row 1 — food; row 2 — potions; row 3 — bombs.
    //     row 4 col 0 — armor; row 5 col 0 — shield; row 6 col 0 — amulet;
    //     row 7 col 0 — sword; row 7 col 1 — dagger.
    //
    // ─── sprites/spd/staff.png ─────────────────────────────────────────────────────
    //     (col 0, row 0) — staff "\\"  (whole file is one 16×16 tile, loaded by
    //                       ItemSprites).
    //
    // ─── sprites/surfaces.png (512×224) ────────────────────────────────────────
    //   Three zones. The top 192 px hold three seamless 64×64 liquid tiles stacked
    //   vertically (water, blood, oil); each is lifted into its own wrappable Texture
    //   so TextureWrap.Repeat stays within that one liquid. The bottom 32 px is a strip
    //   of 32×32 alpha masks sampled by the surface shader to cut the scrolling liquid
    //   to shape. The right side of the upper rows additionally holds vegetation
    //   sprites at 32-px cells, indexed (col, row).
    //     (col 0, y=0..63,    64×64) — water liquid tile
    //     (col 0, y=64..127,  64×64) — blood liquid tile (concentric rings)
    //     (col 0, y=128..191, 64×64) — oil   liquid tile (iridescent gooey)
    //     (cols 4..5, row 0, 32×32 each) — grass A / B variants
    //     (cols 4..5, row 1, 32×32 each) — mushroom A / B variants
    //     (cols 6..7, rows 0–1, 32×64 each) — tree A / B variants (double-height: the
    //         sprite is anchored at row 1 — its trunk — and extends upward into row 0
    //         for the canopy. Renderer splits the texture: lower half draws in the
    //         tree's own tile, upper half draws into the cell directly above.)
    //     (cols 0..15, y=192..223, 32×32 each) — 16 shore-variant alpha masks indexed
    //         by a 4-bit N/E/S/W neighbor bitfield. Convention: opaque white = "no water
    //         here" (shore cutout), transparent = "water here". The shader does the
    //         alpha inversion. Variant 0 (no shores) is synthesised at load time as a
    //         fully transparent tile so the cell reads as fully water-covered.
    //   Regenerate with: java tools/GenerateSurfaces.java assets/sprites/surfaces.png
    //
    // ================================================================================

    // Per-theme terrain atlas paths and texture loading live in TileSprites — single
    // source of truth so the look-screen tile portrait and this in-world renderer
    // never disagree on which file backs each VisualTheme.
    // surfaces.png layout, paths, and tile dimensions live in SurfaceSprites.
    // See SurfaceSprites.LIQUID_TILE / VEG_CELL / MASK_TILE / MASK_VARIANTS.
    /** On-screen size of a shore-mask tile when drawn — kept here because the
     *  shader-batch draw call uses it directly. Mirrors {@link SurfaceSprites#MASK_TILE}. */
    private static final int    SURFACE_MASK_TILE      = SurfaceSprites.MASK_TILE;


    // ─── mobs.png sprite sheet ────────────────────────────────────────────────────
    // Single packed sheet on a 32-px grid (see tools/PackMobsSheet.java). Each mob slot
    // below gives the (col, row) of the bottom-left cell of the sprite block plus the
    // block's width and height in cells. Sprites are bottom-aligned within their block
    // by the packer, so the visible silhouette stands on the cell-block's bottom edge —
    // no per-sprite yAdjust math needed at render time.
    // mobs.png path lives in MobSprites — single source of truth.
    private static final int    MOBS_CELL_PX   = 32;

    // (col, row, wCells, hCells) packed into one int per mob: ((col<<24)|(row<<16)|(w<<8)|h).
    // {@code row} is the TOP-LEFT cell of the source block (matching the packer's output
    // convention). For a 1×2 tall sprite that the player sees standing on row N, the
    // constant uses {@code row = N - 1} so the read covers the head + base together.
    // Player class sprites — row 0, cols 2..4. These are read by playerSprite() based on
    // Mob.characterClass.
    private static final int MOB_CELL_ROGUE              = mobCell(2, 0, 1, 2);
    private static final int MOB_CELL_MAGE               = mobCell(3, 0, 1, 2);
    private static final int MOB_CELL_WARRIOR            = mobCell(4, 0, 1, 2);
    // Apex — ghost stands on row 1 (top at 0); horror stands on row 7 (top at 6).
    private static final int MOB_CELL_GHOST              = mobCell(7, 0, 1, 2);
    private static final int MOB_CELL_HORROR             = mobCell(4, 6, 1, 2);
    // Insect line — row 2 across the full width (1×1 each).
    private static final int MOB_CELL_SPIDER             = mobCell(0, 2, 1, 1);
    private static final int MOB_CELL_LOATHESOME_BUG     = mobCell(1, 2, 1, 1);
    private static final int MOB_CELL_BAT                = mobCell(2, 2, 1, 1);
    private static final int MOB_CELL_MOUSE              = mobCell(3, 2, 1, 1);
    private static final int MOB_CELL_SOLDIER_BUG        = mobCell(4, 2, 1, 1);
    private static final int MOB_CELL_BUG_PRODIGY        = mobCell(5, 2, 1, 1);
    // Critters — dog at (7, 2) and cat at (6, 2), both 1×1.
    private static final int MOB_CELL_DOG                = mobCell(7, 2, 1, 1);
    private static final int MOB_CELL_CAT                = mobCell(6, 2, 1, 1);
    /** Kitten — dedicated 1×1 sprite at (col 1, row 3). */
    private static final int MOB_CELL_KITTEN             = mobCell(1, 3, 1, 1);
    private static final int MOB_CELL_KOBOLD_FIGHTER     = mobCell(0, 3, 1, 1);
    // Ants — 1×1, on row 3 next to the kitten.
    private static final int MOB_CELL_BLACK_ANT          = mobCell(2, 3, 1, 1);
    private static final int MOB_CELL_RED_ANT            = mobCell(3, 3, 1, 1);
    // Blobs — 2×2 grid each, rows 4..5.
    private static final int MOB_CELL_KISSYBLOB          = mobCell(0, 4, 2, 2);
    private static final int MOB_CELL_BLOB               = mobCell(2, 4, 2, 2);
    // Ant hills — 2×2 each, immediately right of the blob on rows 4..5.
    private static final int MOB_CELL_BLACK_ANT_HILL     = mobCell(4, 4, 2, 2);
    private static final int MOB_CELL_RED_ANT_HILL       = mobCell(6, 4, 2, 2);
    // Mask imps stand on row 7. Small ones are 1×1 at row 7; the tall variants stand on
    // row 7 with their head extending up to row 6.
    private static final int MOB_CELL_MASK_IMP           = mobCell(0, 7, 1, 1);
    private static final int MOB_CELL_LARGE_MASK_IMP     = mobCell(1, 7, 1, 1);
    private static final int MOB_CELL_DEVELOPED_MASK_IMP = mobCell(2, 6, 1, 2);
    private static final int MOB_CELL_HORRIBLE_MASK_IMP  = mobCell(3, 6, 1, 2);
    // Barbarian princess — stands on row 9, head at row 8.
    private static final int MOB_CELL_BARBARIAN_PRINCESS = mobCell(1, 8, 1, 2);

    /** Pack (col, row, wCells, hCells) into a single int for compact constants. */
    private static int mobCell(int col, int row, int wCells, int hCells) {
        return (col << 24) | (row << 16) | (wCells << 8) | hCells;
    }
    
    
    /** Liquid tile size in source pixels — matches {@link SurfaceSprites#LIQUID_TILE}. The shader's
     *  per-cell UV math divides world coordinates by this to get a UV that wraps once per
     *  tile. Bumped 32→64 when surfaces.png was doubled; without this update the shader
     *  would only sample the upper-left half of the new 64-px liquid art. */
    private static final float WATER_TEX_SIZE = SurfaceSprites.LIQUID_TILE;
    /** Speed of liquid texture drift. Slow on purpose — water/blood/oil should look like a
     *  gentle current, not a river. */
    private static final float WATER_SCROLL_PX_PER_SEC = 1.2f;

    private static final int CELL = 16;
    /** Alias for {@link TileSprites#TERRAIN_COLS}. Re-declared as a local constant so
     *  the many compile-time {@code N * TERRAIN_COLS + col} flat-cell expressions in
     *  this file stay readable. */
    private static final int TERRAIN_COLS = TileSprites.TERRAIN_COLS;
    /** Pixels above the cell's bottom edge where a mob's baseline sits — its feet end here
     *  and the sprite extends upward from this line. */
    private static final int ENTITY_Y_OFFSET = 2;

    /** Uniform on-screen size for every mob's VISIBLE silhouette (not including blank padding). */
    private static final int MOB_VISIBLE_W = 16;
    private static final int MOB_VISIBLE_H = 20;
    /** Extra Y lift (in y-up world pixels) applied to the player only — pushes the
     *  character a few pixels above the tile's baseline so they read as standing on the
     *  floor rather than embedded in it. NPCs keep the default baseline. */
    private static final float PLAYER_Y_LIFT = 4f;

    /** Alpha of the 1-px black outline drawn around every mob silhouette. 0.4 = "60%
     *  transparent" per the user-spec; 0 disables the outline pass. Each cardinal offset
     *  contributes its own draw, so straight edges read as the configured alpha while
     *  corners (where two offsets overlap) are slightly darker — typical for a four-pass
     *  pixel outline. */
    private static final float MOB_OUTLINE_ALPHA = 0.4f;
    /** Width of the thin shadow strip painted along the wall-facing edges of floor tiles. The
     *  strip uses a 4-pixel alpha gradient texture (opaque at the wall, fading into the floor). */
    private static final float FLOOR_SHADOW_PX    = 3f;
    /** Height of the gradient shadow painted across the base of a wall with floor to its south. */
    private static final float WALL_BASE_SHADOW_PX = 6f;

    // Per-theme shadow tints — cached so shadow passes don't allocate a new Color per cell.
    // Classic: cool blue-black. Urban: slightly reddish black. Organic: warm violet.
    // Neutral: mossy green-black. Crystal: pure black.
    private static final Color SHADOW_CLASSIC = new Color(0.04f, 0.05f, 0.14f, 0.55f);
    private static final Color SHADOW_URBAN   = new Color(0.16f, 0.05f, 0.07f, 0.55f);
    private static final Color SHADOW_ORGANIC = new Color(0.22f, 0.08f, 0.28f, 0.60f);
    private static final Color SHADOW_NEUTRAL = new Color(0.05f, 0.16f, 0.06f, 0.55f);
    private static final Color SHADOW_BLACK   = new Color(0f, 0f, 0f, 0.65f);
    /** Black with a faint warm brown bias — concrete tunnels and brutalist interiors
     *  sit in dim sodium-amber light, so shadows lean warm rather than neutral.
     *  Used for the CONCRETE theme. */
    private static final Color SHADOW_CONCRETE = new Color(0.06f, 0.04f, 0.02f, 0.65f);

    /**
     * Tint for every shadow pass (floor-edge, wall-base, rear-corner) on this level. One
     * switch, one place to adjust per-theme lighting.
     */
    private static Color getShadowColor(Level level) {
        return switch (level.theme) {
            case CRYSTAL  -> SHADOW_BLACK;
            case CONCRETE -> SHADOW_CONCRETE;
        };
    }

    /**
     * Width of the "rear corner shadows" — thin vertical bands painted along the left or right
     * edge of a wall tile that sits directly above a floor and has a perpendicular wall coming
     * out of the diagonally-down corner. Visually fakes the contact shadow of the rear-corner
     * wall against the side of the horizontal wall section.
     */
    private static final float WALL_REAR_CORNER_SHADOW_PX = 3f;
    /** Height of the rear-corner shadow band, measured from the bottom of the wall cell
     *  upward. The band covers only the lower portion of the wall — the top 5 pixels are
     *  left clean so the wall cap doesn't look smothered in shadow. */
    private static final float WALL_REAR_CORNER_SHADOW_H  = 11f;

    /** Shadow oval pinned under each mob at the baseline. Height is fixed; width tracks the
     *  mob's silhouette plus {@link #SHADOW_EXTRA_W} so the shadow always pokes slightly
     *  past the mob's feet on both sides (reads as planted on the floor, not perched atop it). */
    private static final int   SHADOW_H        = 6;
    private static final int   SHADOW_EXTRA_W  = 4;
    private static final float SHADOW_MAX_ALPHA = 0.45f;
    /** Size + peak-alpha of the ellipse cast under a lamp tile at its base. Lamps are static
     *  light sources that loom taller than any mob, so their contact shadow is wider, a touch
     *  taller, and noticeably darker than the mob shadow — otherwise the lamp reads as
     *  floating above the floor. */
    private static final int   LAMP_SHADOW_W         = 30;
    private static final int   LAMP_SHADOW_H         = 7;
    private static final float LAMP_SHADOW_MAX_ALPHA = 0.80f;

    // terrain1.png rows (see layout in the build script). Flat index = col + row*TERRAIN_COLS.
    // Variants ("+ bits") live in subsequent cols of the same row.
    /** Alternate stone-floor art. Each FLOOR cell picks one of these at random (deterministic
     *  per-tile hash) so adjacent floors don't look stamped from the same tile. All three
     *  variants live on row 1 across cols 0, 1, 2. */
    private static final int[] FLOOR_VARIANTS = {
            1 * TERRAIN_COLS + 0,   // (col 0, row 1) — the original FLOOR
            1 * TERRAIN_COLS + 1,   // (col 1, row 1)
            1 * TERRAIN_COLS + 2    // (col 2, row 1)
    };
    /** Alternate wood-floor art — row 2 across cols 0, 1, 2. */
    private static final int[] FLOOR_WOOD_VARIANTS = {
            2 * TERRAIN_COLS + 0,   // (col 0, row 2) — the original FLOOR_WOOD slot
            2 * TERRAIN_COLS + 1,   // (col 1, row 2)
            2 * TERRAIN_COLS + 2    // (col 2, row 2)
    };
    /** Alternate solid-wall art, used only for the stitch-0 (no neighbours open) variant —
     *  stitch variants 1..3 keep their unique tiles so the wall still reads as opening up at
     *  doorways. Picked randomly per tile. */
    private static final int[] RAISED_WALL_VARIANTS = {
            4 * TERRAIN_COLS + 0,   // (col 0, row 4) — the original RAISED_WALL stitch-0
            4 * TERRAIN_COLS + 5,   // (col 5, row 4)
            4 * TERRAIN_COLS + 6,   // (col 6, row 4)
            4 * TERRAIN_COLS + 7    // (col 7, row 4)
    };

    /**
     * WALLS_INTERNAL bitfield slots that ship a row-7 alternate the renderer rolls
     * against the row-6 original. 0-indexed cell columns; the user-facing terrain-sheet
     * convention is 1-indexed, so these are "columns 3 and 5" in the textured-sheet
     * sense. Bit values are the autotile bitfields they happen to map to (2 = right-
     * south open, 4 = left-south open) — the two single-south-corner variants. The
     * renderer hashes (x, y) deterministically so a tile's chosen variant stays stable
     * across frames.
     */
    private static final boolean[] WALLS_INTERNAL_HAS_ROW7_ALT = new boolean[16];
    static {
        WALLS_INTERNAL_HAS_ROW7_ALT[2] = true; // 0-idx col 2 = "col 3" in 1-idx
        WALLS_INTERNAL_HAS_ROW7_ALT[4] = true; // 0-idx col 4 = "col 5" in 1-idx
    }
    /** Two-element lookup that turns {@link #pickVariant} into a deterministic 50/50
     *  coin toss (returns 0 or 1 stably for a given tile). Used to decide whether a
     *  WALLS_INTERNAL cell with a row-7 alternate uses the row-6 original or the row-7
     *  alt. */
    private static final int[] ROW7_ALT_TOSS = { 0, 1 };
    private static final int WALLS_OVERHANG                =  3 * TERRAIN_COLS + 0; // +1 r-s open, +2 l-s open
    private static final int RAISED_WALL                   =  4 * TERRAIN_COLS + 0; // +1 r open, +2 l open
    private static final int WALLS_INTERNAL                =  6 * TERRAIN_COLS + 0; // +1 r, +2 r-s, +4 l-s, +8 l
    private static final int RAISED_DOOR                   =  8 * TERRAIN_COLS + 0;
    private static final int DOOR_OVERHANG                 =  8 * TERRAIN_COLS + 1;
    private static final int RAISED_DOOR_SIDEWAYS          =  8 * TERRAIN_COLS + 2;
    private static final int DOOR_SIDEWAYS_OVERHANG_CLOSED =  8 * TERRAIN_COLS + 3; // +1 r-s, +2 l-s
    private static final int DOOR_SIDEWAYS                 =  8 * TERRAIN_COLS + 7; // wall-over-sideways-door cutout
    /** Open-door body for the front-facing variant — one row below {@link #RAISED_DOOR}. The
     *  sideways open-door body re-uses {@link #RAISED_DOOR_SIDEWAYS}; only its wall-cap and
     *  closed-overhang overlays are dropped. */
    private static final int RAISED_DOOR_OPEN              =  9 * TERRAIN_COLS + 0;

    // ─── Top-right ornaments (statues + lamp) ─────────────────────────────────
    // These three sprites live above the regular floor/wall grid and are pulled as
    // dedicated TextureRegions per theme — see smallStatueByTheme / lampByTheme /
    // largeStatueByTheme. The lamp and large statue are 1-cell wide × 2-cells tall, so
    // they're drawn anchored at the floor cell with the upper half overhanging into the
    // cell above (same convention as the legacy mob.png lamp). Source faces west;
    // {@code _R} mob/tile facing produces the east variant by drawing with negative width.

    /** Source column of the small-statue sprite (1 cell, 32×32 source). */
    private static final int STATUE_SMALL_COL = 17;
    /** Source column of the lamp sprite (1 cell wide × 2 cells tall, 32×64 source). */
    private static final int LAMP_COL         = 18;
    /** Source column of the tall (large) statue sprite (1 cell wide × 2 cells tall). */
    private static final int STATUE_LARGE_COL = 19;
    /** Source row offsets (in cells) for the small-statue, lamp, and tall-statue top edges.
     *  The small statue lives one row south of the lamp + tall statue (col 17 row 1) — the
     *  lamp + tall statue still occupy 1×2 starting at row 0. Tweak if the texture moves them. */
    private static final int STATUE_SMALL_ROW = 1;
    private static final int LAMP_ROW         = 0;
    private static final int STATUE_LARGE_ROW = 0;
    /** Up-ladder source rect — 2×2 atlas cells starting at (col 0, row 10). */
    private static final int STAIRS_UP_COL    = 0;
    private static final int STAIRS_UP_ROW    = 10;
    /** Down-ladder source rect — sits at col 2, immediately to the right of the up
     *  ladder per your literal spec ("2 columns to the right of [col 0]"). If the
     *  texture has the down-ladder art elsewhere, edit this constant. */
    private static final int STAIRS_DOWN_COL  = 2;
    private static final int STAIRS_DOWN_ROW  = 10;
    private static final int STAIRS_W_CELLS   = 2;
    private static final int STAIRS_H_CELLS   = 2;
    private static final int CHASM_FLOOR                   = 12 * TERRAIN_COLS + 0;
    private static final int CHASM_WALL                    = 12 * TERRAIN_COLS + 1;
    private static final int CHASM_WATER                   = 12 * TERRAIN_COLS + 2; // chasm with water dripping from above
    private static final int CHASM_WOOD                    = 12 * TERRAIN_COLS + 3; // chasm under a wood floor to the north
    private static final int WATER                         = 14 * TERRAIN_COLS + 0; // +1 n, +2 e, +4 s, +8 w

    private SpriteBatch     batch;
    private BitmapFont      font;
    /** Per-theme extracted tile regions, one TextureRegion per (col, row) cell of the
     *  theme's atlas. {@link #tiles} is re-pointed at the current level's theme at the
     *  top of every {@link #render}. The backing textures themselves are owned by
     *  {@link TileSprites}. */
    private final java.util.EnumMap<com.bjsp123.rl2.model.Level.VisualTheme, TextureRegion[]>
            terrainTilesByTheme = new java.util.EnumMap<>(com.bjsp123.rl2.model.Level.VisualTheme.class);
    /** Per-theme dedicated regions for the three top-right ornaments. {@code current*}
     *  fields below are repointed at the start of {@link #render} to the active theme so
     *  draw helpers don't have to look the level up. */
    private final java.util.EnumMap<com.bjsp123.rl2.model.Level.VisualTheme, TextureRegion>
            smallStatueByTheme = new java.util.EnumMap<>(com.bjsp123.rl2.model.Level.VisualTheme.class);
    private final java.util.EnumMap<com.bjsp123.rl2.model.Level.VisualTheme, TextureRegion>
            largeStatueByTheme = new java.util.EnumMap<>(com.bjsp123.rl2.model.Level.VisualTheme.class);
    private final java.util.EnumMap<com.bjsp123.rl2.model.Level.VisualTheme, TextureRegion>
            lampOrnamentByTheme = new java.util.EnumMap<>(com.bjsp123.rl2.model.Level.VisualTheme.class);
    private final java.util.EnumMap<com.bjsp123.rl2.model.Level.VisualTheme, TextureRegion>
            stairsUpByTheme = new java.util.EnumMap<>(com.bjsp123.rl2.model.Level.VisualTheme.class);
    private final java.util.EnumMap<com.bjsp123.rl2.model.Level.VisualTheme, TextureRegion>
            stairsDownByTheme = new java.util.EnumMap<>(com.bjsp123.rl2.model.Level.VisualTheme.class);
    private TextureRegion currentSmallStatue;
    private TextureRegion currentLargeStatue;
    private TextureRegion currentLampOrnament;
    private TextureRegion currentStairsUp;
    private TextureRegion currentStairsDown;
    /** Mob the player is currently inspecting via look mode. When non-null, the renderer
     *  overlays its state-of-mind above its tile and draws this mob's attitude toward
     *  every other visible mob. {@link com.bjsp123.rl2.screen.PlayScreen} updates it each
     *  frame from {@code LookMode.mobAtCursor()}; null clears the overlay. */
    private Mob lookedAtMob;
    public void setLookedAtMob(Mob m) { this.lookedAtMob = m; }
    private Texture         waterTex;
    private Texture         bloodTex;
    private Texture         oilTex;
    private Texture         iceTex;

    /** Two grass sprite variants pulled from surfaces.png (atlas cells (5,0) and (6,0) at
     *  {@value #VEG_SPRITE_PX} px). The A/B pick per cell is hash-driven by
     *  {@link #vegRearVariantBit} so adjacent grass tiles read as a mix instead of a stamped
     *  uniform. Same scheme for mushrooms (rows 1) and trees (rows 2). */
    private Texture         grassTexA, grassTexB;
    private Texture         mushroomTexA, mushroomTexB;
    private Texture         treeTexA, treeTexB;
    /** Packed sprite sheet — every NPC + the player class poses. Borrowed from
     *  {@link MobSprites#sheetTexture()} so the atlas is only loaded once across the
     *  whole game. NOT disposed here — {@link MobSprites#disposeShared()} owns it. */
    private Texture mobsTex;
    /** Two painted 8-frame fire animation sheets — each 256×48 (8 frames of 32×48). Held
     *  here only for {@link #dispose()}; the per-frame draw machinery lives in
     *  {@link FxRenderer}, which receives both as constructor args. Optional: a missing
     *  file is loaded as null and FxRenderer draws nothing for that variant. */
    private Texture         fire1Tex, fire2Tex;
    /** West-facing + east-facing (mirrored) pairs for the three small critters + the player. */
    private Sprite[]        mouseFacing, catFacing, kittenFacing, dogFacing,
                            warriorFacing, mageFacing, rogueFacing;
    /** Insect-line facing pairs (all from mobs.png). */
    private Sprite[]        spiderFacing, loathesomeBugFacing, batFacing,
                            soldierBugFacing, bugProdigyFacing,
                            blackAntFacing, redAntFacing;
    /** Humanoids and the apex line. */
    private Sprite[]        koboldFighterFacing, princessFacing, ghostFacing, horrorFacing;
    /** Mask-imp facing pairs. */
    private Sprite[]        maskImpFacing, largeMaskImpFacing,
                            developedMaskImpFacing, horribleMaskImpFacing;
    /** Large-mob facing pairs — rendered at natural pixel size. */
    private Sprite[]        blobFacing, kissyblobFacing,
                            blackAntHillFacing, redAntHillFacing;
    private Texture         whiteTex;
    private Texture         shadowTex;
    /** Dedicated soft-oval shadow for lamp tiles — bigger and darker than the mob shadow. */
    private Texture         lampShadowTex;
    private Texture         wallBaseShadowTex;
    /** 1×4 gradient: alpha 0 at image-top, 255 at image-bottom. Used for N/S floor shadows. */
    private Texture         floorShadowVertTex;
    /** 4×1 gradient: alpha 255 at image-left, 0 at image-right. Used for E/W floor shadows. */
    private Texture         floorShadowHorzTex;
    /** 16-way alpha mask tiles lifted from the bottom strip of surfaces.png. Index 0 is a
     *  synthesized solid-white tile (the art leaves variant 0 fully transparent). */
    private Texture[]       surfaceMaskTex;
    /** Custom shader that draws a scrolling surface modulated by a per-cell alpha mask. */
    private ShaderProgram   surfaceMaskShader;
    private float           waterTime;
    /** Reference to the world for cross-level lookups (currently used by the stair labels
     *  to read the destination level's depth + side). Set via {@link #setWorld}; null if
     *  the renderer is being used outside a full game (e.g. tests). */
    private com.bjsp123.rl2.model.World world;
    /** In-world animator providing per-mob {@link com.bjsp123.rl2.world.anim.MobAnimState}.
     *  Set via {@link #setAnimator} during PlayScreen init; required for all mob draws. */
    private com.bjsp123.rl2.world.anim.Animator animator;
    /** Real-time accumulator for the stair-label fade animation, in seconds. Bumped each
     *  frame from {@link com.badlogic.gdx.Gdx#graphics}. */
    private float           stairLabelTime;
    private TextureRegion   whiteRegion;
    private TextureRegion[] tiles;
    private final FogOverlay fog = new FogOverlay();

    /** All effect/particle/fire/sleep-Z rendering lives here. Constructed lazily in
     *  {@link #create()} once the underlying batch + font + fire textures are loaded. */
    private FxRenderer fxRenderer;

    /** Cached per-cell item index. Rebuilt lazily when {@link #indexesDirty} is true —
     *  items only move on game ticks (pickup, drop, throw) so between-tick frames can
     *  reuse the prior frame's index verbatim. */
    private Map<Long, List<Item>> cachedItemsByCell;
    /** Cached per-cell mob index. Same caching scheme as {@link #cachedItemsByCell} —
     *  mobs' positions only change on ticks. The flicker / fade of a dying mob is a
     *  frame-counter bump inside {@link Mob}, not a position change, so it doesn't need
     *  the index rebuilt. */
    private Map<Long, List<Mob>>  cachedMobsByCell;
    /** When true, {@link #cachedItemsByCell} and {@link #cachedMobsByCell} need to be
     *  repopulated from the current level. Set by {@link #markDirty()} and also on the
     *  very first render after creation. Fx are NOT cached — they get added / removed
     *  every frame via {@code advanceEffects}, so the fx index is rebuilt per frame. */
    private boolean indexesDirty = true;

    /**
     * One drawable entity frame.
     * <ul>
     *   <li>{@code w, h} — the source frame's total native pixel dimensions.</li>
     *   <li>{@code visibleW, visibleH} — pixel size of the non-transparent silhouette
     *       (bounding box of all opaque pixels). Mobs scale X by {@code visibleW} and Y by
     *       {@code visibleH} independently so every mob's silhouette lands at exactly
     *       {@link #MOB_VISIBLE_W} × {@link #MOB_VISIBLE_H} regardless of how much blank
     *       canvas the artist left around the character.</li>
     *   <li>{@code visibleLeft} — source-pixel x of the leftmost opaque column, used to
     *       recentre the silhouette on the tile after scaling (since side padding may be
     *       asymmetric).</li>
     *   <li>{@code yAdjust} — source-pixel offset added to the cell baseline when drawing:
     *       negative pulls the frame down to compensate for blank bottom rows so feet land
     *       on the shared baseline; positive lifts for flying mobs. Scales with the sprite
     *       at draw time.</li>
     * </ul>
     */
    private static final class Sprite {
        final TextureRegion region;
        final int w, h, visibleW, visibleH, visibleLeft, yAdjust;
        /** When true, {@link #drawMobSprite} draws this at its native pixel size instead of
         *  scaling the silhouette to {@link #MOB_VISIBLE_W} × {@link #MOB_VISIBLE_H}. Used
         *  for "large" mobs like the blob whose shape is meant to extend past the tile. */
        final boolean natural;
        Sprite(TextureRegion region, int w, int h,
               int visibleW, int visibleH, int visibleLeft, int yAdjust, boolean natural) {
            this.region = region;
            this.w = w; this.h = h;
            this.visibleW = visibleW; this.visibleH = visibleH;
            this.visibleLeft = visibleLeft; this.yAdjust = yAdjust;
            this.natural = natural;
        }
    }

    /** Extra pixels a flying mob hovers above the shared ground baseline. */
    private static final int FLYING_HOVER_PX = 4;

    @Override
    public void create() {
        batch = new SpriteBatch();
        font  = new BitmapFont();

        // Pre-build per-cell TextureRegions for every theme so level.render() can pick a
        // pre-built tiles[] by theme without any per-frame texture lookup. Textures and
        // cell sizes come from TileSprites — the shared atlas registry — rather than
        // being loaded again here.
        for (com.bjsp123.rl2.model.Level.VisualTheme themeKey
                : com.bjsp123.rl2.model.Level.VisualTheme.values()) {
            Texture tex = TileSprites.textureFor(themeKey);
            if (tex == null) continue;
            // Source cell size is detected per-atlas from its width — default and neutral
            // are exported at 32 px/cell (640×512) while older blue/organic atlases stay
            // at 16 px/cell (320×256). The grid layout (20 cols × N rows) is the same in
            // either case, so flat indices into FLOOR/WALL/etc. constants are stable; only
            // the source pixel stride changes. The TextureRegion is drawn into a 16-px
            // world cell either way, so 32-px source art is downscaled 2:1 by libgdx.
            int srcCell = Math.max(1, TileSprites.cellSizeFor(themeKey));
            int rows = tex.getHeight() / srcCell;
            TextureRegion[] arr = new TextureRegion[rows * TERRAIN_COLS];
            for (int r = 0; r < rows; r++) {
                for (int c = 0; c < TERRAIN_COLS; c++) {
                    arr[r * TERRAIN_COLS + c] =
                            new TextureRegion(tex, c * srcCell, r * srcCell, srcCell, srcCell);
                }
            }
            terrainTilesByTheme.put(themeKey, arr);

            // Top-right ornaments. Small statue is one source cell; lamp + large statue
            // span two cells vertically (1×2) and are drawn anchored at the floor cell so
            // the upper half overhangs into the cell above. Out-of-bounds rows (e.g. an
            // older atlas without these slots) just yield a no-op draw later.
            int statueX = STATUE_SMALL_COL * srcCell;
            int statueY = STATUE_SMALL_ROW * srcCell;
            int lampX   = LAMP_COL         * srcCell;
            int largeX  = STATUE_LARGE_COL * srcCell;
            int largeY  = STATUE_LARGE_ROW * srcCell;
            if (statueX + srcCell <= tex.getWidth() && statueY + srcCell <= tex.getHeight()) {
                smallStatueByTheme.put(themeKey,
                        new TextureRegion(tex, statueX, statueY,
                                          srcCell, srcCell));
            }
            if (lampX + srcCell <= tex.getWidth() && 2 * srcCell <= tex.getHeight()) {
                lampOrnamentByTheme.put(themeKey,
                        new TextureRegion(tex, lampX, LAMP_ROW * srcCell,
                                          srcCell, 2 * srcCell));
            }
            if (largeX + srcCell <= tex.getWidth() && largeY + 2 * srcCell <= tex.getHeight()) {
                largeStatueByTheme.put(themeKey,
                        new TextureRegion(tex, largeX, largeY,
                                          srcCell, 2 * srcCell));
            }
            // Stair ladders — 2×2 source cells each.
            int stairsUpX   = STAIRS_UP_COL   * srcCell;
            int stairsUpY   = STAIRS_UP_ROW   * srcCell;
            int stairsDownX = STAIRS_DOWN_COL * srcCell;
            int stairsDownY = STAIRS_DOWN_ROW * srcCell;
            int stairsWPx   = STAIRS_W_CELLS  * srcCell;
            int stairsHPx   = STAIRS_H_CELLS  * srcCell;
            if (stairsUpX + stairsWPx <= tex.getWidth()
                    && stairsUpY + stairsHPx <= tex.getHeight()) {
                stairsUpByTheme.put(themeKey,
                        new TextureRegion(tex, stairsUpX, stairsUpY, stairsWPx, stairsHPx));
            }
            if (stairsDownX + stairsWPx <= tex.getWidth()
                    && stairsDownY + stairsHPx <= tex.getHeight()) {
                stairsDownByTheme.put(themeKey,
                        new TextureRegion(tex, stairsDownX, stairsDownY, stairsWPx, stairsHPx));
            }
        }
        // Default binding before a level renders; render() re-points at the per-level theme.
        tiles = terrainTilesByTheme.get(com.bjsp123.rl2.model.Level.VisualTheme.CRYSTAL);
        currentSmallStatue  = smallStatueByTheme .get(com.bjsp123.rl2.model.Level.VisualTheme.CRYSTAL);
        currentLargeStatue  = largeStatueByTheme .get(com.bjsp123.rl2.model.Level.VisualTheme.CRYSTAL);
        currentLampOrnament = lampOrnamentByTheme.get(com.bjsp123.rl2.model.Level.VisualTheme.CRYSTAL);
        currentStairsUp     = stairsUpByTheme    .get(com.bjsp123.rl2.model.Level.VisualTheme.CRYSTAL);
        currentStairsDown   = stairsDownByTheme  .get(com.bjsp123.rl2.model.Level.VisualTheme.CRYSTAL);

        // ─── mobs.png — borrowed from MobSprites (single load across the whole game) ─
        mobsTex = MobSprites.sheetTexture();
        // Player class poses (row 0).
        warriorFacing          = mobsFacingPair(MOB_CELL_WARRIOR);
        mageFacing             = mobsFacingPair(MOB_CELL_MAGE);
        rogueFacing            = mobsFacingPair(MOB_CELL_ROGUE);
        // Insects (row 2).
        spiderFacing           = mobsFacingPair(MOB_CELL_SPIDER);
        loathesomeBugFacing    = mobsFacingPair(MOB_CELL_LOATHESOME_BUG);
        // Bat is flying — gets the floating y-lift.
        batFacing              = mobsFloatingFacingPair(MOB_CELL_BAT);
        soldierBugFacing       = mobsFacingPair(MOB_CELL_SOLDIER_BUG);
        bugProdigyFacing       = mobsFacingPair(MOB_CELL_BUG_PRODIGY);
        blackAntFacing         = mobsFacingPair(MOB_CELL_BLACK_ANT);
        redAntFacing           = mobsFacingPair(MOB_CELL_RED_ANT);
        // Critters.
        mouseFacing            = mobsFacingPair(MOB_CELL_MOUSE);
        catFacing              = mobsFacingPair(MOB_CELL_CAT);
        kittenFacing           = mobsFacingPair(MOB_CELL_KITTEN);
        dogFacing              = mobsFacingPair(MOB_CELL_DOG);
        // Humanoids.
        koboldFighterFacing    = mobsFacingPair(MOB_CELL_KOBOLD_FIGHTER);
        princessFacing         = mobsFacingPair(MOB_CELL_BARBARIAN_PRINCESS);
        // Blobs (2×2 each).
        blobFacing             = mobsFacingPair(MOB_CELL_BLOB);
        kissyblobFacing        = mobsFacingPair(MOB_CELL_KISSYBLOB);
        blackAntHillFacing     = mobsFacingPair(MOB_CELL_BLACK_ANT_HILL);
        redAntHillFacing       = mobsFacingPair(MOB_CELL_RED_ANT_HILL);
        // Mask imps.
        maskImpFacing          = mobsFacingPair(MOB_CELL_MASK_IMP);
        largeMaskImpFacing     = mobsFacingPair(MOB_CELL_LARGE_MASK_IMP);
        developedMaskImpFacing = mobsFacingPair(MOB_CELL_DEVELOPED_MASK_IMP);
        horribleMaskImpFacing  = mobsFacingPair(MOB_CELL_HORRIBLE_MASK_IMP);
        // Apex.
        ghostFacing            = mobsFloatingFacingPair(MOB_CELL_GHOST);
        horrorFacing           = mobsFacingPair(MOB_CELL_HORROR);

       
        Pixmap wp = new Pixmap(1, 1, Pixmap.Format.RGBA8888);
        wp.setColor(Color.WHITE);
        wp.fill();
        whiteTex = new Texture(wp);
        wp.dispose();
        whiteRegion = new TextureRegion(whiteTex);

        shadowTex     = makeShadowTexture(32, 8, SHADOW_MAX_ALPHA);
        lampShadowTex = makeShadowTexture(40, 10, LAMP_SHADOW_MAX_ALPHA);
        wallBaseShadowTex = makeWallBaseShadowTexture();
        floorShadowVertTex = makeAlphaGradient(1, 4, /*horizontal*/ false);
        floorShadowHorzTex = makeAlphaGradient(4, 1, /*horizontal*/ true);

        // Surface / vegetation / mask / fire textures all come from SurfaceSprites
        // — the single owner of surfaces.png and the two fire sheets across the whole
        // game. We borrow Texture references; SurfaceSprites.disposeShared() releases
        // them, never this dispose().
        surfaceMaskTex = new Texture[com.bjsp123.rl2.world.render.SurfaceSprites.MASK_VARIANTS];
        for (int i = 0; i < surfaceMaskTex.length; i++) {
            surfaceMaskTex[i] = com.bjsp123.rl2.world.render.SurfaceSprites.maskTexture(i);
        }
        surfaceMaskShader = buildSurfaceMaskShader();

        waterTex = com.bjsp123.rl2.world.render.SurfaceSprites.liquidTexture(
                com.bjsp123.rl2.model.Level.Surface.WATER);
        bloodTex = com.bjsp123.rl2.world.render.SurfaceSprites.liquidTexture(
                com.bjsp123.rl2.model.Level.Surface.BLOOD);
        oilTex   = com.bjsp123.rl2.world.render.SurfaceSprites.liquidTexture(
                com.bjsp123.rl2.model.Level.Surface.OIL);
        iceTex   = com.bjsp123.rl2.world.render.SurfaceSprites.liquidTexture(
                com.bjsp123.rl2.model.Level.Surface.ICE);
        grassTexA    = com.bjsp123.rl2.world.render.SurfaceSprites.vegetationTextureA(
                com.bjsp123.rl2.model.Level.Vegetation.GRASS);
        grassTexB    = com.bjsp123.rl2.world.render.SurfaceSprites.vegetationTextureB(
                com.bjsp123.rl2.model.Level.Vegetation.GRASS);
        mushroomTexA = com.bjsp123.rl2.world.render.SurfaceSprites.vegetationTextureA(
                com.bjsp123.rl2.model.Level.Vegetation.MUSHROOMS);
        mushroomTexB = com.bjsp123.rl2.world.render.SurfaceSprites.vegetationTextureB(
                com.bjsp123.rl2.model.Level.Vegetation.MUSHROOMS);
        treeTexA = com.bjsp123.rl2.world.render.SurfaceSprites.vegetationTextureA(
                com.bjsp123.rl2.model.Level.Vegetation.TREES);
        treeTexB = com.bjsp123.rl2.world.render.SurfaceSprites.vegetationTextureB(
                com.bjsp123.rl2.model.Level.Vegetation.TREES);
        fire1Tex = com.bjsp123.rl2.world.render.SurfaceSprites.fire1Texture();
        fire2Tex = com.bjsp123.rl2.world.render.SurfaceSprites.fire2Texture();

        fxRenderer = new FxRenderer(batch, font, whiteRegion, fire1Tex, fire2Tex);
    }


    /** Cut a single mob silhouette out of {@link #mobsTex} at the (col, row, w, h) cell
     *  block packed into {@code cellSpec}. Render half source pixel size — the sheet is
     *  authored at 2× display resolution so a 32-px source cell becomes a 16-px on-screen
     *  cell. Sprites are bottom-aligned within their cell block by the packer
     *  ({@code tools/PackMobsSheet.java}), so the silhouette's visible base lands at the
     *  cell-block's bottom edge automatically. */
    private Sprite mobsSingleSprite(int cellSpec) {
        return mobsSingleSprite(cellSpec, /*yAdjust*/ 0);
    }

    /** Same as {@link #mobsSingleSprite(int)} but with an extra y lift — used for flying
     *  mobs (FLYING_HOVER_PX). */
    private Sprite mobsSingleSprite(int cellSpec, int yAdjust) {
        int col   = (cellSpec >>> 24) & 0xFF;
        int row   = (cellSpec >>> 16) & 0xFF;
        int wCell = (cellSpec >>>  8) & 0xFF;
        int hCell =  cellSpec         & 0xFF;
        int srcX  = col   * MOBS_CELL_PX;
        int srcY  = row   * MOBS_CELL_PX;
        int srcW  = wCell * MOBS_CELL_PX;
        int srcH  = hCell * MOBS_CELL_PX;
        int dispW = srcW / 2, dispH = srcH / 2;
        return new Sprite(new TextureRegion(mobsTex, srcX, srcY, srcW, srcH),
                          dispW, dispH, dispW, dispH, 0, yAdjust, /*natural*/ true);
    }

    private Sprite[] mobsFacingPair(int cellSpec) {
        Sprite left  = mobsSingleSprite(cellSpec);
        Sprite right = flipHorizontal(left);
        return new Sprite[]{ left, right };
    }

    private Sprite[] mobsFloatingFacingPair(int cellSpec) {
        Sprite left  = mobsSingleSprite(cellSpec, FLYING_HOVER_PX);
        Sprite right = flipHorizontal(left);
        return new Sprite[]{ left, right };
    }


    private static Sprite flipHorizontal(Sprite src) {
        TextureRegion flipped = new TextureRegion(src.region);
        flipped.flip(true, false);
        // Horizontal flip swaps left and right padding, which shifts visibleLeft.
        int flippedLeft = src.w - src.visibleLeft - src.visibleW;
        return new Sprite(flipped, src.w, src.h,
                          src.visibleW, src.visibleH, flippedLeft, src.yAdjust, src.natural);
    }

    // Liquid + vegetation extraction lives in SurfaceSprites — the single owner of
    // surfaces.png-derived Textures. Both kinds (Linear+Repeat liquids, Nearest veg
    // sprites) come back from there as already-built Textures.

    /** Invalidate cached fog + per-cell item/mob indexes. Called by {@code PlayScreen}
     *  after game ticks and level transitions — between ticks we skip both rebuilds. */
    @Override
    public void markDirty() {
        fog.markDirty();
        indexesDirty = true;
    }

    @Override
    public void setWorld(com.bjsp123.rl2.model.World world) {
        this.world = world;
    }

    @Override
    public void setAnimator(com.bjsp123.rl2.world.anim.Animator animator) {
        this.animator = animator;
    }

    @Override
    public void render(Level level, OrthographicCamera camera) {
        fog.createFor(level.width, level.height);
        fog.update(level);
        float dt = Gdx.graphics.getDeltaTime();
        waterTime      += dt;
        stairLabelTime += dt;

        // Bind the tile atlas matching this level's theme. Falls back to CRYSTAL if a theme
        // slipped through without a registered atlas (e.g. an older save on a dev build).
        TextureRegion[] themed = terrainTilesByTheme.get(level.theme);
        tiles = themed != null ? themed
                               : terrainTilesByTheme.get(com.bjsp123.rl2.model.Level.VisualTheme.CRYSTAL);
        currentSmallStatue  = smallStatueByTheme .getOrDefault(level.theme,
                smallStatueByTheme .get(com.bjsp123.rl2.model.Level.VisualTheme.CRYSTAL));
        currentLargeStatue  = largeStatueByTheme .getOrDefault(level.theme,
                largeStatueByTheme .get(com.bjsp123.rl2.model.Level.VisualTheme.CRYSTAL));
        currentLampOrnament = lampOrnamentByTheme.getOrDefault(level.theme,
                lampOrnamentByTheme.get(com.bjsp123.rl2.model.Level.VisualTheme.CRYSTAL));
        currentStairsUp     = stairsUpByTheme.getOrDefault(level.theme,
                stairsUpByTheme.get(com.bjsp123.rl2.model.Level.VisualTheme.CRYSTAL));
        currentStairsDown   = stairsDownByTheme.getOrDefault(level.theme,
                stairsDownByTheme.get(com.bjsp123.rl2.model.Level.VisualTheme.CRYSTAL));

        // Bucket every item / mob / effect into the cell it will draw in. Items and mobs
        // only shift on ticks (pickup / drop / throw / step), so their indexes are cached
        // across frames and rebuilt only when the PlayScreen flags {@link #indexesDirty}
        // via {@link #markDirty}. Effects are volatile — added / removed every frame by
        // {@code TurnSystem.advanceEffects} — so the fx index is rebuilt per frame.
        // Effects that span two tiles (missiles, thrown items) anchor at their SOUTHMOST
        // endpoint so the N→S loop paints them after every mob along their path.
        if (indexesDirty || cachedItemsByCell == null || cachedMobsByCell == null) {
            cachedItemsByCell = indexItemsByCell(level);
            cachedMobsByCell  = indexMobsByCell(level);
            indexesDirty = false;
        }
        Map<Long, List<Item>>   itemsByCell = cachedItemsByCell;
        Map<Long, List<Mob>>    mobsByCell  = cachedMobsByCell;
        Map<Long, List<Effect>> fxByCell    = indexEffectsByCell(animator.stage);

        batch.setProjectionMatrix(camera.combined);
        batch.begin();
        batch.setColor(Color.WHITE);

        // FOUR PASSES over the level:
        //   Pass 1 — FLOORS + CHASM. Every floor, stair glyph, chasm black fill, and chasm
        //            edge tile lands first so the rest of the scene can layer on top without
        //            ever peeking through to a floorless background.
        //   Pass 2 — SURFACES (water/blood/oil). Lifted out of the per-cell pass so the
        //            mask shader is bound exactly once for all surface cells instead of
        //            thrashing on/off per-cell. Sits between floors and shadows so liquid
        //            visually rests on the floor and tucks under wall-base shadows.
        //   Pass 3 — PER-CELL CONTENT (N→S). Shadows / wall body / vegetation / lamps /
        //            items / mobs / front veg / fx / cloud. Still one-tile-at-a-time so
        //            overlapping cases (grass tucking over feet, tall walls over mobs in
        //            the same cell, etc.) resolve consistently.
        //   Pass 4 — WALL + DOOR TOPS (N→S). Wall overhangs, internal-wall caps, N/S-facing
        //            door overhangs, sideways-door overhangs — every "ceiling" tile painted
        //            on top of the already-laid scene.
        // Fog then runs once after pass 4.
        for (int y = 0; y < level.height; y++) {
            for (int x = 0; x < level.width; x++) {
                if (!level.explored[x][y]) continue;
                drawFloorAt(level, x, y);
                drawChasmEdgeAt(level, x, y);
            }
        }

        drawSurfacesPass(level);

        for (int y = level.height - 1; y >= 0; y--) {
            for (int x = 0; x < level.width; x++) {
                boolean explored = level.explored[x][y];
                if (explored) {
                    drawFloorEdgeShadowAt(level, x, y);
                    drawWallAt(level, x, y);
                    drawWallBaseShadowAt(level, x, y);
                    drawRearVegetationAt(level, x, y);
                    drawLampAt(level, x, y);
                    drawStairsAt(level, x, y);
                    drawStatueAt(level, x, y);
                    drawItemsAt(level, x, y, itemsByCell);
                    drawMobsAt(level, x, y, mobsByCell);
                    drawFrontVegetationAt(level, x, y);
                }
                // Fx run regardless of explored — magic missiles are intentionally drawn
                // across dark tiles so the player can see their own projectile. Each
                // effect's own draw code decides whether to honor level.visible[].
                drawFxAt(level, x, y, fxByCell);
            }
        }

        for (int y = level.height - 1; y >= 0; y--) {
            for (int x = 0; x < level.width; x++) {
                if (!level.explored[x][y]) continue;
                drawWallOverlayAt(level, x, y);
            }
        }

        // Look-mode annotations layer — drawn above the world but under the fog overlay
        // so unseen tiles still darken normally. Only fires when the player is looking at
        // a mob; otherwise it's a no-op.
        if (lookedAtMob != null) drawLookAnnotations(level);

        fog.render(batch);
        batch.end();
    }

    /**
     * Overlay text annotations driven by {@link #lookedAtMob}. Above the looked-at mob's
     * tile we print its state-of-mind ("asleep" / "awake" / "hiding" / "following"); above
     * every other visible mob we print a single-character attitude marker indicating how
     * the looked-at mob feels about it ({@code !} hostile red, {@code ?} fleeing yellow,
     * {@code ·} neutral grey, {@code ♥} ally green). Cleared when the look cursor leaves
     * the mob (PlayScreen sets {@link #lookedAtMob} back to null).
     */
    private void drawLookAnnotations(Level level) {
        Mob anchor = lookedAtMob;
        if (anchor == null) return;
        int ax = anchor.position.tileX(), ay = anchor.position.tileY();
        if (ax < 0 || ay < 0 || ax >= level.width || ay >= level.height) return;

        // The looked-at mob: state of mind above its tile.
        drawAnnotationAbove(anchor, stateOfMindLabel(anchor.stateOfMind), Color.WHITE);

        // Every other visible mob: attitude label.
        for (Mob m : level.mobs) {
            if (m == anchor) continue;
            int mx = m.position.tileX(), my = m.position.tileY();
            if (mx < 0 || my < 0 || mx >= level.width || my >= level.height) continue;
            if (!level.visible[mx][my]) continue;
            String text;
            Color   color;
            if (com.bjsp123.rl2.logic.MobSystem.getAttitudeToMob(anchor, m)
                    == com.bjsp123.rl2.logic.MobSystem.Attitude.ALLY) {
                text = "+"; color = Color.GREEN;
            } else {
                com.bjsp123.rl2.logic.MobSystem.Attitude att =
                        com.bjsp123.rl2.logic.MobSystem.getAttitudeToMob(anchor, m);
                switch (att) {
                    case ATTACK -> { text = "!"; color = Color.RED; }
                    case FLEE   -> { text = "?"; color = Color.YELLOW; }
                    default     -> { text = "·"; color = Color.LIGHT_GRAY; }
                }
            }
            drawAnnotationAbove(m, text, color);
        }
    }

    /** Draw {@code text} centered slightly above {@code mob}'s tile using the renderer's
     *  shared {@link #font}. Uses the same world-y offset convention as floating text so
     *  multi-line stacks (state-of-mind on the looked-at mob, "+1" floating heal text,
     *  etc.) layer cleanly. */
    private void drawAnnotationAbove(Mob mob, String text, Color color) {
        if (text == null || text.isEmpty()) return;
        int gx = mob.position.tileX(), gy = mob.position.tileY();
        float wx = gx * (float) CELL;
        float wy = gy * (float) CELL + CELL * 2f;     // ~one cell above sprite top
        font.setColor(color);
        font.draw(batch, text, wx, wy);
        font.setColor(Color.WHITE);
    }

    private static String stateOfMindLabel(com.bjsp123.rl2.model.Mob.StateOfMind s) {
        if (s == null) return "";
        return switch (s) {
            case ASLEEP         -> "asleep";
            case AWAKE          -> "awake";
            case SEEKING_HIDING -> "fleeing";
            case HIDING         -> "hiding";
            case FOLLOWING      -> "following";
        };
    }

    private static long cellKey(int x, int y) {
        return (((long) y) << 32) | (x & 0xFFFFFFFFL);
    }

    private static Map<Long, List<Item>> indexItemsByCell(Level level) {
        Map<Long, List<Item>> out = new HashMap<>();
        for (Item it : level.items) {
            if (it.location == null) continue;
            out.computeIfAbsent(cellKey(it.location.tileX(), it.location.tileY()),
                                k -> new ArrayList<>()).add(it);
        }
        return out;
    }

    private static Map<Long, List<Mob>> indexMobsByCell(Level level) {
        Map<Long, List<Mob>> out = new HashMap<>();
        for (Mob mob : level.mobs) {
            if (mob.position == null) continue;
            out.computeIfAbsent(cellKey(mob.position.tileX(), mob.position.tileY()),
                                k -> new ArrayList<>()).add(mob);
        }
        return out;
    }

    /**
     * Anchor each effect at its SOUTHMOST endpoint's cell. In the N→S loop that means the
     * effect renders only after every mob it could visually overlap (source, destination,
     * or any cell between) has already drawn, so missiles and thrown items stay on top.
     */
    private static Map<Long, List<Effect>> indexEffectsByCell(EffectStage stage) {
        Map<Long, List<Effect>> out = new HashMap<>();
        for (Effect e : stage.active) {
            if (e.location == null) continue;
            int ax = e.location.tileX();
            int ay = e.location.tileY();
            if (e.endLocation != null && e.endLocation.tileY() < ay) {
                ax = e.endLocation.tileX();
                ay = e.endLocation.tileY();
            }
            out.computeIfAbsent(cellKey(ax, ay), k -> new ArrayList<>()).add(e);
        }
        return out;
    }

    /**
     * Floor layer for one cell — everything that should read as "floor". FLOOR / FLOOR_WOOD,
     * LAMP (base), STAIRS (floor underlay + stair glyph), plus the black fill for CHASM cells.
     * Walls, doors, and the "chasm edge" stripes are painted later by {@link #drawWallAt} so
     * they sit on top of surfaces drawn in between.
     */
    private void drawFloorAt(Level level, int x, int y) {
        Tile t = level.tiles[x][y];
        if (t == Tile.CHASM) {
            drawBlackFill(x, y);
            return;
        }
        if (t == Tile.WALL) return;
        // Doors get a floor underlay so the door body (drawn later in drawWallAt) sits on
        // a continuous floor — picked from the neighbour to the N first, failing that the
        // neighbour to the W, failing that the default stone-floor variant. Matching the
        // neighbour's variant keeps wood-floored corridors reading as wood right up to
        // (and under) the door.
        if (t == Tile.DOOR || t == Tile.DOOR_OPEN) {
            int variant = floorVisualAt(level, x, y + 1);   // N (y-up)
            if (variant < 0) variant = floorVisualAt(level, x - 1, y);
            if (variant < 0) variant = FLOOR_VARIANTS[0];
            drawTile(variant, x, y);
            return;
        }
        // Stairs sit on top of a regular floor tile — the stair glyphs only carve out the
        // step geometry, not a full cell, so without the floor under them the surrounding
        // area shows through.
        if (t == Tile.STAIRS_UP || t == Tile.STAIRS_DOWN) {
            // Stair underlay uses the same floor-variant picker as neighboring floors so
            // it doesn't stand out from the surrounding random-variant pattern.
            drawTile(pickVariant(x, y, FLOOR_VARIANTS), x, y);
        }
        int visual = terrainVisual(level, x, y);
        if (visual < 0) return;
        drawTile(visual, x, y);
    }

    /** Floor sprite that the cell at (x, y) would render — used by the door-underlay
     *  logic to fetch a neighbour's floor variant. Returns -1 if the cell isn't a
     *  floor type. */
    private int floorVisualAt(Level level, int x, int y) {
        if (x < 0 || y < 0 || x >= level.width || y >= level.height) return -1;
        Tile t = level.tiles[x][y];
        if (t == Tile.FLOOR)      return pickVariant(x, y, FLOOR_VARIANTS);
        if (t == Tile.FLOOR_WOOD) return pickVariant(x, y, FLOOR_WOOD_VARIANTS);
        return -1;
    }

    /**
     * Wall body for one cell — raised walls and door bodies. Runs after the surface layer
     * so liquid pools at a wall base don't paint over the wall itself. Chasm edges are drawn
     * in pass 1 by {@link #drawChasmEdgeAt} instead so every floor-level detail (floors, chasm
     * black fill, chasm edges) is committed before per-cell content starts layering on top.
     */
    private void drawWallAt(Level level, int x, int y) {
        Tile t = level.tiles[x][y];
        if (t != Tile.WALL && t != Tile.DOOR && t != Tile.DOOR_OPEN) return;
        int visual = terrainVisual(level, x, y);
        if (visual < 0) return;
        // Sideways open door (walls both north and south) — the door body art reads as
        // anchored against the wall above, so we shift the sprite one cell north and let
        // the floor underlay show through where the door body used to sit. The wall at
        // (x, y+1) still renders in its own pass; the door body overlays it. Closed
        // sideways doors keep the in-cell draw so the doorway fills the gap.
        if (t == Tile.DOOR_OPEN
                && isWallish(level, x, y - 1)
                && isWallish(level, x, y + 1)) {
            drawTile(visual, x, y + 1);
            return;
        }
        drawTile(visual, x, y);
    }

    /**
     * Chasm-edge tile for one cell — picks the "dripping edge" glyph for a CHASM cell based
     * on what's in the cell to the north:
     * <ul>
     *   <li>Water surface → {@link #CHASM_WATER}</li>
     *   <li>Wall or door → {@link #CHASM_WALL}</li>
     *   <li>Wood floor → {@link #CHASM_WOOD} (wood planks overhanging the pit)</li>
     *   <li>Anything else → {@link #CHASM_FLOOR}</li>
     * </ul>
     * No-op for non-chasm cells; no-op for chasm cells whose north neighbor is also chasm
     * (there's nothing for the edge to "drip" from).
     */
    private void drawChasmEdgeAt(Level level, int x, int y) {
        if (level.tiles[x][y] != Tile.CHASM) return;
        Tile north = tileAt(level, x, y + 1);
        if (north == null || north == Tile.CHASM) return;
        int edge;
        if      (surfaceAt(level, x, y + 1) == Surface.WATER) edge = CHASM_WATER;
        else if (north == Tile.WALL || north == Tile.DOOR
              || north == Tile.DOOR_OPEN)                    edge = CHASM_WALL;
        else if (north == Tile.FLOOR_WOOD)                   edge = CHASM_WOOD;
        else                                                 edge = CHASM_FLOOR;
        drawTile(edge, x, y);
    }

    /**
     * Paint a thin shadow strip on each side of a floor tile that touches a wall, for one
     * cell. The strip is {@value #FLOOR_SHADOW_PX} pixels deep (perpendicular to the wall)
     * and uses a 4-pixel alpha gradient that's opaque right at the wall and fades to
     * transparent into the floor. Negative {@code width}/{@code height} args to
     * {@code batch.draw} flip the gradient when we need it pointing the other way, so a
     * single vertical and a single horizontal texture cover all four sides. Runs AFTER the
     * surface layer so the shadow sits on top of any water/blood/oil at the wall's foot
     * (matching the real-world order: wall casts shadow onto liquid).
     */
    private void drawFloorEdgeShadowAt(Level level, int x, int y) {
        if (!level.tiles[x][y].isFloorLike()) return;
        batch.setColor(getShadowColor(level));
        float px = x * (float) CELL;
        float py = y * (float) CELL;
        if (isWallCell(level, x, y + 1)) {
            // Wall to north: strip at top of cell, opaque at top — flip vertical
            // gradient by drawing with negative height anchored at the cell's top.
            batch.draw(floorShadowVertTex, px, py + CELL,
                       CELL, -FLOOR_SHADOW_PX);
        }
        if (isWallCell(level, x, y - 1)) {
            // Wall to south: strip at bottom of cell, opaque at bottom — natural orientation.
            batch.draw(floorShadowVertTex, px, py, CELL, FLOOR_SHADOW_PX);
        }
        if (isWallCell(level, x - 1, y)) {
            // Wall to west: strip on left, opaque at left — natural orientation.
            batch.draw(floorShadowHorzTex, px, py, FLOOR_SHADOW_PX, CELL);
        }
        if (isWallCell(level, x + 1, y)) {
            // Wall to east: strip on right, opaque at right — flip horizontally.
            batch.draw(floorShadowHorzTex, px + CELL, py,
                       -FLOOR_SHADOW_PX, CELL);
        }
        batch.setColor(Color.WHITE);
    }

    /**
     * Build a 1-D alpha-only gradient as an RGBA texture. Pixels are pure white; alpha ramps
     * from 0 at one end to 255 at the other along the major axis.
     * <ul>
     *   <li>{@code horizontal=false} (vertical strip): alpha 0 at image-top row 0, 255 at
     *       image-bottom row {@code h-1}. SpriteBatch maps image-top to high screen-y, so the
     *       drawn rect is opaque at its bottom edge.</li>
     *   <li>{@code horizontal=true}: alpha 255 at image-left column 0, 0 at image-right
     *       column {@code w-1}. Drawn rect is opaque at its left edge.</li>
     * </ul>
     * Linear filtering smooths the ramp at any render size.
     */
    private static Texture makeAlphaGradient(int w, int h, boolean horizontal) {
        Pixmap p = new Pixmap(w, h, Pixmap.Format.RGBA8888);
        p.setBlending(Pixmap.Blending.None);
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                float t = horizontal
                        ? 1f - (w == 1 ? 0f : x / (float) (w - 1)) // 1 at left, 0 at right
                        :       (h == 1 ? 1f : y / (float) (h - 1)); // 0 at top,  1 at bottom
                int a = Math.round(t * 255f);
                int rgba = (255 << 24) | (255 << 16) | (255 << 8) | a; // RGBA8888 white + alpha
                p.drawPixel(x, y, rgba);
            }
        }
        Texture t = new Texture(p);
        p.dispose();
        t.setFilter(Texture.TextureFilter.Linear, Texture.TextureFilter.Linear);
        return t;
    }

    /** A wall or a door — both read as solid wall sections for shadow purposes, regardless
     *  of which axis the door sits across or whether the door is open. Floor cells adjacent
     *  to either get the same floor-edge shadow strip, and the cell itself gets the same
     *  wall-base shadow. */
    private static boolean isWallCell(Level level, int x, int y) {
        if (x < 0 || y < 0 || x >= level.width || y >= level.height) return false;
        Tile t = level.tiles[x][y];
        return t == Tile.WALL || t == Tile.DOOR || t == Tile.DOOR_OPEN;
    }

    /**
     * For one cell: if it's a WALL with a non-chasm floor-like tile directly south, paint a
     * gradient shadow across the base of the wall (opaque at the bottom edge, fading a few
     * pixels up) plus rear-corner shadow stripes when a perpendicular wall stub meets it
     * diagonally. Gives the raised wall a soft contact shadow that reads as "the wall
     * catches light from above and casts darkness where it meets the floor". CHASM to the
     * south is explicitly excluded — a wall at the edge of a pit has nothing to shadow onto.
     */
    private void drawWallBaseShadowAt(Level level, int x, int y) {
        if (!isWallCell(level, x, y)) return;
        Tile south = tileAt(level, x, y - 1);
        if (south == null || south == Tile.CHASM || isWallCell(level, x, y - 1)) return;
        batch.setColor(getShadowColor(level));
        float px = x * (float) CELL;
        float py = y * (float) CELL;
        batch.draw(wallBaseShadowTex, px, py, CELL, WALL_BASE_SHADOW_PX);

        // Rear corner shadows — thin vertical bands along the wall's left/right edge
        // when the floor at the south has a perpendicular wall stub at the opposite
        // diagonal. Only fires when south is a true floor tile, since chasm/wall to
        // the south already disqualified us above.
        if (south.isFloorLike()) {
            Tile sw = tileAt(level, x - 1, y - 1);
            Tile se = tileAt(level, x + 1, y - 1);
            if (sw == Tile.WALL) {
                // Wall is northeast of the SW wall stub → shadow on this wall's LEFT edge.
                // Only the lower 11 px of the 16-px cell — keeps the top 5 px of the wall
                // clean so the cap doesn't read as fully darkened.
                batch.draw(floorShadowHorzTex, px, py,
                           WALL_REAR_CORNER_SHADOW_PX, WALL_REAR_CORNER_SHADOW_H);
            }
            if (se == Tile.WALL) {
                // Wall is northwest of the SE wall stub → shadow on this wall's RIGHT edge.
                batch.draw(floorShadowHorzTex, px + CELL, py,
                           -WALL_REAR_CORNER_SHADOW_PX, WALL_REAR_CORNER_SHADOW_H);
            }
        }
        batch.setColor(Color.WHITE);
    }

    // Shore-mask extraction also lives in SurfaceSprites — see SurfaceSprites.maskTexture.

    /**
     * Shader that pairs each sampled pixel of the sprite (the mask tile) with a sample of a
     * secondary texture (the scrolling liquid, bound to TEXTURE1). Output RGB comes from the
     * liquid; output alpha is {@code 1 - mask.alpha} — i.e. opaque white in the mask means
     * NO water at that pixel and transparent mask pixels show full water. Authoring rule:
     * paint the shore-cutout pixels opaque white, leave the water-filled interior fully
     * transparent. Standard alpha blending in the batch then blends the resulting liquid
     * over the already-drawn floor using that profile as its opacity.
     */
    private static ShaderProgram buildSurfaceMaskShader() {
        String vert = "attribute vec4 a_position;\n"
                + "attribute vec4 a_color;\n"
                + "attribute vec2 a_texCoord0;\n"
                + "uniform mat4 u_projTrans;\n"
                + "varying vec4 v_color;\n"
                + "varying vec2 v_texCoords;\n"
                + "void main() {\n"
                + "    v_color = a_color;\n"
                + "    v_color.a = v_color.a * (255.0/254.0);\n"
                + "    v_texCoords = a_texCoord0;\n"
                + "    gl_Position = u_projTrans * a_position;\n"
                + "}";
        String frag = "#ifdef GL_ES\n"
                + "precision mediump float;\n"
                + "#endif\n"
                + "varying vec4 v_color;\n"
                + "varying vec2 v_texCoords;\n"
                + "uniform sampler2D u_texture;\n"      // mask (sprite's own texture)
                + "uniform sampler2D u_surfaceTex;\n"    // scrolling surface, bound to TEXTURE1
                + "uniform vec4 u_surfaceUv;\n"          // (u1, v1, u2, v2) over this cell
                + "void main() {\n"
                + "    float maskA = texture2D(u_texture, v_texCoords).a;\n"
                + "    vec2 uv = u_surfaceUv.xy + v_texCoords * (u_surfaceUv.zw - u_surfaceUv.xy);\n"
                + "    vec4 surf = texture2D(u_surfaceTex, uv);\n"
                + "    gl_FragColor = vec4(surf.rgb * v_color.rgb, (1.0 - maskA) * v_color.a);\n"
                + "}";
        ShaderProgram sp = new ShaderProgram(vert, frag);
        if (!sp.isCompiled()) {
            throw new IllegalStateException("surface-mask shader compile failed: " + sp.getLog());
        }
        return sp;
    }

    /**
     * Build a 1×8 vertical gradient texture with alpha 0 at the top row and alpha 255 at the
     * bottom row. Linear filtering smooths the ramp at whatever render size we ask for.
     * Stored as black so the batch color becomes the final tint.
     */
    private static Texture makeWallBaseShadowTexture() {
        int w = 1, h = 8;
        Pixmap p = new Pixmap(w, h, Pixmap.Format.RGBA8888);
        p.setBlending(Pixmap.Blending.None);
        for (int y = 0; y < h; y++) {
            // y=0 is the image's top row, which libGDX renders at the HIGHEST screen y of
            // the drawn rect. We want that end transparent and the image-bottom opaque so the
            // shadow sits at the cell floor and fades upward on screen.
            float t = y / (float) (h - 1);
            int alpha = Math.round(t * 255f);
            p.drawPixel(0, y, alpha); // RGBA8888: R=G=B=0, A=alpha
        }
        Texture t = new Texture(p);
        p.dispose();
        t.setFilter(Texture.TextureFilter.Linear, Texture.TextureFilter.Linear);
        return t;
    }

    /**
     * Surface pass — draws every water/blood/oil cell on the level using the surface-mask
     * shader. Runs as a single dedicated pass right after floors are laid down, before
     * shadows / walls / vegetation / mobs / etc., so liquid sits on the floor and tucks
     * under wall-base shadows. Bundling the cells means the mask shader gets bound exactly
     * once for the whole level instead of swapped on/off per cell, and the liquid texture
     * (TEXTURE1) is rebound only when the surface kind changes between cells.
     */
    private void drawSurfacesPass(Level level) {
        if (level.surface == null) return;
        // Cheap bail-out when the level has no surfaces at all — keeps the shader off the
        // GPU pipeline on dry levels.
        boolean any = false;
        outer:
        for (int x = 0; x < level.width && !any; x++) {
            for (int y = 0; y < level.height; y++) {
                if (level.explored[x][y] && level.surface[x][y] != null) { any = true; break outer; }
            }
        }
        if (!any) return;

        batch.setShader(surfaceMaskShader);
        surfaceMaskShader.setUniformi("u_surfaceTex", 1);
        Surface lastSurf = null;
        for (int y = 0; y < level.height; y++) {
            for (int x = 0; x < level.width; x++) {
                if (!level.explored[x][y]) continue;
                Surface s = level.surface[x][y];
                if (s == null) continue;
                if (s != lastSurf) {
                    // The bound liquid texture is per-pass state, so flush any buffered
                    // draws under the old binding before swapping in the next surface kind.
                    batch.flush();
                    textureFor(s).bind(1);
                    Gdx.gl.glActiveTexture(GL20.GL_TEXTURE0);
                    lastSurf = s;
                }
                drawMaskedSurfaceCell(level, s, x, y);
            }
        }
        batch.setShader(null);
        batch.setColor(Color.WHITE);
    }

    private void drawMaskedSurfaceCell(Level level, Surface s, int gx, int gy) {
        int variant = stitchSurface(level, gx, gy, s) - WATER;
        float scroll = waterTime * WATER_SCROLL_PX_PER_SEC;
        // Sampling the texture at higher u shifts the visible pattern LEFT on screen; sampling
        // at lower v shifts it DOWN. Use that to drift the liquid south-west over time.
        float u1 = (gx * CELL + scroll) / WATER_TEX_SIZE;
        float v1 = (gy * CELL - scroll) / WATER_TEX_SIZE;
        float u2 = u1 + CELL / WATER_TEX_SIZE;
        float v2 = v1 + CELL / WATER_TEX_SIZE;

        // Commit the previous cell with its uniform BEFORE updating to this cell's value.
        batch.flush();
        surfaceMaskShader.setUniformf("u_surfaceUv", u1, v1, u2, v2);
        batch.draw(surfaceMaskTex[variant], gx * (float) CELL, gy * (float) CELL, CELL, CELL);
    }

    private Texture textureFor(Surface s) {
        return switch (s) {
            case WATER -> waterTex;
            case BLOOD -> bloodTex;
            case OIL   -> oilTex;
            case ICE   -> iceTex;
        };
    }

    /**
     * Small flora on top of the floor. Drawn between the surface pass and the entity pass so
     * grass is under mobs/items but above any water it sits next to.
     */
    /**
     * Rear vegetation pass: runs before items/mobs so the rear sprite (variant A or B per
     * the per-tile {@link #vegRearVariantBit} hash) sits behind anything standing on the tile.
     * X jitter is applied via {@link #vegJitterX} and reused by {@link #drawFrontVegetationAt}
     * so the rear and front layers line up horizontally; vertical lift comes from
     * {@link #vegRearLiftPx} (3–6 px above the tile floor).
     */
    private void drawRearVegetationAt(Level level, int x, int y) {
        // Tree canopies extend into the cell ABOVE their tile. Render order is top-down
        // (highest y first), so a tree at (x, y-1) needs its canopy painted in this cell's
        // rear-veg phase — that way it sits behind any mob that's standing in (x, y).
        if (y - 1 >= 0 && level.vegetation[x][y - 1] == Vegetation.TREES) {
            drawTreeCanopy(x, y - 1);
        }

        Vegetation v = level.vegetation[x][y];
        if (v == null) return;
        if (v == Vegetation.FIRE) { fxRenderer.drawFireAt(x, y); return; }

        if (v == Vegetation.TREES) {
            drawTreeTrunk(x, y);
            return;
        }

        Texture tex = rearVegTexture(v, x, y);
        if (tex == null) return;
        batch.setColor(Color.WHITE);
        int jx = vegJitterX(x, y);
        int lift = vegRearLiftPx(x, y);
        // Rear vegetation rides 3–6 px above the tile floor so the front sprite (drawn at
        // the base) reads as the foreground blades and the rear sprite reads as the body
        // poking up behind it.
        batch.draw(tex, x * (float) CELL + jx, y * (float) CELL + lift, CELL, CELL);
    }

    /** Paint the upper (canopy) half of the tree-at-({@code treeX}, {@code treeY}) sprite
     *  into the cell directly above it (world (treeX, treeY+1)). Called from that cell's
     *  rear-veg phase so the canopy sits behind any mob standing on the cell above. The
     *  same vertical lift used for normal rear vegetation is applied so the trunk + canopy
     *  read as a single shifted sprite. */
    private void drawTreeCanopy(int treeX, int treeY) {
        Texture tex = rearVegTexture(Vegetation.TREES, treeX, treeY);
        if (tex == null) return;
        int jx = vegJitterX(treeX, treeY);
        int lift = vegRearLiftPx(treeX, treeY);
        int half = tex.getHeight() / 2;
        batch.setColor(Color.WHITE);
        batch.draw(tex,
                treeX * (float) CELL + jx, (treeY + 1) * (float) CELL + lift,
                CELL, CELL,
                0, 0,                           // src origin: top of texture = canopy
                tex.getWidth(), half,
                false, false);
    }

    /** Paint the lower (trunk) half of the tree sprite at world (x, y), lifted by the
     *  shared 3–6 px rear-veg offset. */
    private void drawTreeTrunk(int x, int y) {
        Texture tex = rearVegTexture(Vegetation.TREES, x, y);
        if (tex == null) return;
        int jx = vegJitterX(x, y);
        int lift = vegRearLiftPx(x, y);
        int half = tex.getHeight() / 2;
        batch.setColor(Color.WHITE);
        batch.draw(tex,
                x * (float) CELL + jx, y * (float) CELL + lift,
                CELL, CELL,
                0, half,                        // src origin: middle of texture = top of trunk
                tex.getWidth(), half,
                false, false);
    }

    /** Deterministic per-tile vertical lift for rear vegetation, in pixels in the range
     *  {@code [3..6]}. Adjacent tiles get slightly different lifts so a row of grass reads
     *  as a textured field rather than a stamped band. */
    private static int vegRearLiftPx(int x, int y) {
        int h = (x * 0x9E3779B1) ^ (y * 0xC2B2AE3D);
        return 3 + ((h >>> 11) & 3);    // 3..6
    }

    /**
     * Front vegetation pass: runs AFTER items/mobs so the front sprite (the "second"
     * variant per {@link #frontVegTexture}, e.g. grassTexB) sits in front of any mob on
     * the tile. Drawn at the tile's base — no Y jitter — so the foreground blades anchor
     * to the floor. The art is authored mostly transparent above the blade tops, so a
     * full-cell draw produces the "mob's feet tucked into grass" silhouette.
     */
    private void drawFrontVegetationAt(Level level, int x, int y) {
        Vegetation v = level.vegetation[x][y];
        if (v == null) return;
        // Fire is drawn whole in the rear pass — no front overlay (the flame shouldn't
        // tuck back over the mob's feet the way grass does).
        if (v == Vegetation.FIRE) return;
        Texture tex = frontVegTexture(v, x, y);
        if (tex == null) return;
        batch.setColor(Color.WHITE);
        // Front vegetation always sits at the tile's base (no Y lift, unlike the rear
        // pass) and renders the full sprite. The art for the front variants is authored
        // with mostly-transparent upper rows + foreground blades near the bottom, so a
        // full-cell draw naturally produces the "feet-tucked-into-grass" effect on the
        // tile's occupant. X jitter matches the rear sprite so the two layers line up
        // horizontally.
        int jx = vegJitterX(x, y);
        batch.draw(tex, x * (float) CELL + jx, y * (float) CELL, CELL, CELL);
    }

    /** Rear-pass texture pick: each veg type has two source variants in surfaces.png; the
     *  per-tile {@link #vegRearVariantBit} hash picks which one renders so adjacent tiles
     *  break up the grid. The chosen sprite goes behind any mob standing on the tile. */
    private Texture rearVegTexture(Vegetation v, int x, int y) {
        boolean b = vegRearVariantBit(x, y);
        switch (v) {
            case GRASS:     return b ? grassTexB    : grassTexA;
            case MUSHROOMS: return b ? mushroomTexB : mushroomTexA;
            case TREES:     return b ? treeTexB     : treeTexA;
            default:        return null;
        }
    }

    /** Front-pass texture pick: each tile picks one of the two grass variants (for grass
     *  and tree tiles) or one of the two mushroom variants (for fungus tiles), randomised
     *  by an independent hash from the rear pick so a tile can mix variants between its
     *  two layers. Fire has no front overlay. */
    private Texture frontVegTexture(Vegetation v, int x, int y) {
        boolean b = vegFrontVariantBit(x, y);
        switch (v) {
            case GRASS:     return b ? grassTexB    : grassTexA;
            case MUSHROOMS: return b ? mushroomTexB : mushroomTexA;
            case TREES:     return b ? grassTexB    : grassTexA;   // grass for tree front
            default:        return null;
        }
    }

    /** Deterministic 1-bit per-tile hash used to pick the rear A/B variant. Chosen so
     *  adjacent tiles flip frequently, breaking up the grid. */
    private static boolean vegRearVariantBit(int x, int y) {
        int h = (x * 73856093) ^ (y * 19349663);
        return ((h >>> 7) & 1) == 1;
    }

    /** Independent per-tile hash for the front variant, so a tile's rear and front layers
     *  may use different variants. Different multipliers + shift than {@link #vegRearVariantBit}. */
    private static boolean vegFrontVariantBit(int x, int y) {
        int h = (x * 0x27D4EB2F) ^ (y * 0x165667B1);
        return ((h >>> 11) & 1) == 1;
    }

    /** Half-range of the deterministic per-tile vegetation jitter: outputs span
     *  {@code -VEG_JITTER_PX .. +VEG_JITTER_PX} per axis, picked by a per-tile hash so
     *  adjacent grass / mushroom tiles don't look stamped from a single rubber stamp. */
    private static final int VEG_JITTER_PX = 3;

    /**
     * Deterministic per-tile horizontal jitter in {@code [-VEG_JITTER_PX, +VEG_JITTER_PX]}.
     * Both rear and front veg passes use this so a tile's two layers stay aligned with
     * each other while neighbours look offset. Vertical positioning is split between the
     * passes: rear uses {@link #vegRearLiftPx} (3–6 px above base), front always sits at
     * the base.
     */
    private static int vegJitterX(int x, int y) {
        int span = VEG_JITTER_PX * 2 + 1;
        int h = x * 73856093 ^ y * 19349663;
        return Math.floorMod(h, span) - VEG_JITTER_PX;
    }

    /**
     * Lamp overlay for one cell — the floor base was already drawn by {@link #drawFloorAt};
     * here we overlay the lamp sprite from the terrain atlas so the stem rises into the
     * cell above. Drawn before items/mobs so anything standing on a lamp tile renders on
     * top of it. Falls back to a no-op if the active theme didn't ship a lamp ornament.
     */
    private void drawLampAt(Level level, int x, int y) {
        if (level.tiles[x][y] != Tile.LAMP) return;
        if (currentLampOrnament == null) return;
        batch.setColor(Color.WHITE);
        drawLampShadow(x, y);
        // 1 cell wide × 2 cells tall, anchored at the floor cell so the upper half
        // overhangs into the cell above (matching the source 32×64 art).
        float dx = x * (float) CELL;
        float dy = y * (float) CELL;
        batch.draw(currentLampOrnament, dx, dy, CELL, 2f * CELL);
    }

    /** Width of the contact shadow under a small statue, in screen pixels. */
    private static final int STATUE_SMALL_SHADOW_W = 10;
    /** Width of the contact shadow under a tall statue. Wider than the small variant so a
     *  large pedestal silhouette reads as planted on the floor. */
    private static final int STATUE_LARGE_SHADOW_W = 16;

    /**
     * Stair-ladder overlay for one cell. Source is 2×2 atlas cells (64×64 source) and is
     * drawn as 2×2 world cells (32×32 px), anchored at the bottom of the stair cell with
     * the ladder horizontally centered on the cell. The top half overhangs into the cell
     * to the north — same convention as the lamp ornament. Floor underlay was painted in
     * {@link #drawFloorAt}.
     */
    private void drawStairsAt(Level level, int x, int y) {
        Tile t = level.tiles[x][y];
        TextureRegion r;
        if (t == Tile.STAIRS_UP)        r = currentStairsUp;
        else if (t == Tile.STAIRS_DOWN) r = currentStairsDown;
        else return;
        if (r == null) return;
        batch.setColor(Color.WHITE);
        float dispW = 2f * CELL;   // 32 px wide
        float dispH = 2f * CELL;   // 32 px tall
        float drawX = x * (float) CELL + CELL / 2f - dispW / 2f;   // centered horizontally
        float drawY = y * (float) CELL;                             // base at floor cell
        batch.draw(r, drawX, drawY, dispW, dispH);
        drawStairLabelAt(level, x, y);
    }

    /** Pulsing glow-text overlay on a stair tile. Reads which of the four named stair points
     *  ({@code stairs(Up|Down)(Alt)}) this cell is, looks up the corresponding target level
     *  on the {@link #world}, and draws "↑/↓ lvl N W/E/?" with a sine-modulated alpha so the
     *  writing fades in and out. Drawn twice — a wide dim outer pass + a tight bright inner
     *  pass — for a soft halo. No-op if the world reference isn't set or the cell isn't a
     *  named stair point on this level. */
    private void drawStairLabelAt(Level level, int x, int y) {
        if (world == null) return;
        boolean ascending;
        int target;
        if (matches(level.stairsUp, x, y))         { ascending = true;  target = level.stairsUpTarget; }
        else if (matches(level.stairsUpAlt, x, y)) { ascending = true;  target = level.stairsUpAltTarget; }
        else if (matches(level.stairsDown, x, y))  { ascending = false; target = level.stairsDownTarget; }
        else if (matches(level.stairsDownAlt, x, y)){ ascending = false; target = level.stairsDownAltTarget; }
        else return;
        if (target < 0 || target >= world.levels.length) return;
        Level dst = world.levels[target];
        if (dst == null) return;

        String text = stairLabelText(ascending, dst);
        // Per-stair phase derived from cell coords so neighbouring stairs don't pulse in
        // perfect lockstep. Cycle period ~2.4 s; alpha sweeps through [0.25, 0.95].
        float phase = (x * 0.37f + y * 0.61f);
        float pulse = 0.5f + 0.5f * (float) Math.sin(stairLabelTime * 2.6f + phase);
        float alpha = 0.25f + 0.70f * pulse;

        // Position: centred horizontally on the stair cell, just above the sprite top so it
        // floats over the staircase like a sign nailed to the wall above the entrance.
        com.badlogic.gdx.graphics.g2d.GlyphLayout layout =
                new com.badlogic.gdx.graphics.g2d.GlyphLayout();
        layout.setText(font, text);
        float wx = x * (float) CELL + CELL / 2f - layout.width  / 2f;
        float wy = y * (float) CELL + 2f * CELL + 4f;   // just above the 32-px sprite

        // Outer glow — dim warm halo at 1.6× scale.
        float prevScaleX = font.getData().scaleX;
        float prevScaleY = font.getData().scaleY;
        font.getData().setScale(prevScaleX * 1.6f);
        font.setColor(1f, 0.85f, 0.45f, alpha * 0.35f);
        com.badlogic.gdx.graphics.g2d.GlyphLayout glow = new com.badlogic.gdx.graphics.g2d.GlyphLayout();
        glow.setText(font, text);
        font.draw(batch, text,
                x * (float) CELL + CELL / 2f - glow.width / 2f,
                wy + (glow.height - layout.height) * 0.5f);
        // Inner crisp text.
        font.getData().setScale(prevScaleX, prevScaleY);
        font.setColor(1f, 0.95f, 0.7f, alpha);
        font.draw(batch, text, wx, wy);
        font.setColor(Color.WHITE);
    }

    private static boolean matches(com.bjsp123.rl2.model.Point p, int x, int y) {
        return p != null && p.tileX() == x && p.tileY() == y;
    }

    /** Build the floating label string: arrow + "lvl N" + slot character (W / E / ?). */
    private static String stairLabelText(boolean ascending, Level dst) {
        char slot = switch (dst.side == null ? Level.Side.CENTER : dst.side) {
            case WEST   -> 'W';
            case EAST   -> 'E';
            case CENTER -> '?';
        };
        // ASCII arrows so the default bitmap font renders them reliably across platforms.
        String arrow = ascending ? "^" : "v";
        return arrow + " lvl " + dst.depth + " " + slot;
    }

    /**
     * Statue overlay for one cell. Paints a soft floor shadow first so the figure reads as
     * planted on the floor, then the sprite. The small-statue source is one cell tall and
     * draws into just the floor cell; the large-statue source is two cells tall and is
     * drawn anchored at the floor cell with the upper half overhanging into the cell to
     * the north (same convention as the lamp). Source art faces west; the {@code _R} tile
     * variant is rendered with negative draw width so one sprite covers both facings.
     */
    private void drawStatueAt(Level level, int x, int y) {
        Tile t = level.tiles[x][y];
        boolean small = (t == Tile.STATUE_SMALL_L || t == Tile.STATUE_SMALL_R);
        boolean large = (t == Tile.STATUE_LARGE_L || t == Tile.STATUE_LARGE_R);
        if (!small && !large) return;
        TextureRegion r = small ? currentSmallStatue : currentLargeStatue;
        if (r == null) return;
        boolean flip = (t == Tile.STATUE_SMALL_R || t == Tile.STATUE_LARGE_R);
        // Contact shadow first, beneath the sprite.
        drawStatueShadow(x, y, large);
        batch.setColor(Color.WHITE);
        float dx = x * (float) CELL;
        float dy = y * (float) CELL;
        // Display dimensions: small → 1 cell square; large → 1 cell wide × 2 cells tall.
        float dw = CELL;
        float dh = small ? CELL : 2f * CELL;
        if (flip) {
            batch.draw(r, dx + dw, dy, -dw, dh);
        } else {
            batch.draw(r, dx, dy, dw, dh);
        }
    }

    /** Soft elliptical contact shadow under a statue at the base of its tile. Re-uses the
     *  mob shadow texture (lighter than the lamp shadow) so the figure reads as standing
     *  on the floor without looking as heavy as a planted lamp. */
    private void drawStatueShadow(int gx, int gy, boolean large) {
        if (shadowTex == null) return;
        float cx = gx * CELL + CELL / 2f;
        float by = gy * CELL + ENTITY_Y_OFFSET;
        int shadowW = large ? STATUE_LARGE_SHADOW_W : STATUE_SMALL_SHADOW_W;
        batch.setColor(Color.WHITE);
        batch.draw(shadowTex, cx - shadowW / 2f, by - SHADOW_H / 2f, shadowW, SHADOW_H);
    }

    /**
     * Soft elliptical shadow cast under a lamp at the base of its tile. Uses the dedicated
     * {@link #lampShadowTex} (wider, taller, darker than the mob shadow) so the lamp reads
     * as firmly planted on the floor rather than floating above it.
     */
    private void drawLampShadow(int gx, int gy) {
        float cx = gx * CELL + CELL / 2f;
        float by = gy * CELL + ENTITY_Y_OFFSET;
        batch.setColor(Color.WHITE);
        batch.draw(lampShadowTex, cx - LAMP_SHADOW_W / 2f, by - LAMP_SHADOW_H / 2f,
                LAMP_SHADOW_W, LAMP_SHADOW_H);
    }

    private static Surface surfaceAt(Level level, int x, int y) {
        if (x < 0 || y < 0 || x >= level.width || y >= level.height) return null;
        return level.surface[x][y];
    }

    private void drawBlackFill(int gx, int gy) {
        batch.setColor(Color.BLACK);
        batch.draw(whiteRegion, gx * (float) CELL, gy * (float) CELL, CELL, CELL);
        batch.setColor(Color.WHITE);
    }

    /**
     * "Ceiling" for one cell — paints overhang / cap tiles belonging to a wall or door in
     * the cell to the SOUTH. Three cases:
     * <ul>
     *   <li>Walls: overhang and internal-cap paint at the cell NORTH of the wall, on top of
     *       that cell's mob so tall walls clip anything behind them.</li>
     *   <li>Doors (N/S-facing): door top paints at the cell NORTH of the door — same
     *       convention as walls, so the arched top sits above the door on screen.</li>
     *   <li>Sideways doors (E/W-facing): overhang paints on the door cell itself.</li>
     * </ul>
     */
    private void drawWallOverlayAt(Level level, int x, int y) {
        Tile t = level.tiles[x][y];

        if (t == Tile.WALL) {
            if (isWallish(level, x, y - 1)) {
                int bits = 0;
                if (!stitchBarrier(level, x + 1, y    )) bits += 1;
                if (!stitchBarrier(level, x + 1, y - 1)) bits += 2;
                if (!stitchBarrier(level, x - 1, y - 1)) bits += 4;
                if (!stitchBarrier(level, x - 1, y    )) bits += 8;
                int result = WALLS_INTERNAL + bits;
                // Bitfields 2 and 4 (the single-south-corner-open variants — 0-indexed
                // cols 2 and 4 of row 6, "cols 3 and 5" in the user-facing 1-indexed
                // convention) ship a row-7 alternate. Pick deterministically per tile so
                // a wall's chosen variant stays stable across frames.
                if (WALLS_INTERNAL_HAS_ROW7_ALT[bits]
                        && pickVariant(x, y, ROW7_ALT_TOSS) == 1) {
                    result += TERRAIN_COLS;
                }
                drawTile(result, x, y);
            } else if (level.tiles[x][y - 1] == Tile.DOOR) {
                // Wall-over-sideways-door cutout: only painted when the door is CLOSED. When
                // the sideways door opens, the cutout sprite is dropped — the wall above
                // renders normally without a special "door top" overlay.
                drawTile(DOOR_SIDEWAYS, x, y);
            }
            return;
        }

        if (t == Tile.DOOR && isWallish(level, x, y - 1)) {
            int result = DOOR_SIDEWAYS_OVERHANG_CLOSED;
            if (!stitchBarrier(level, x + 1, y - 1)) result += 1;
            if (!stitchBarrier(level, x - 1, y - 1)) result += 2;
            drawTile(result, x, y);
            return;
        }

        if (isWallish(level, x, y - 1)) {
            int result = WALLS_OVERHANG;
            if (!stitchBarrier(level, x + 1, y - 1)) result += 1;
            if (!stitchBarrier(level, x - 1, y - 1)) result += 2;
            drawTile(result, x, y);
            return;
        }
        // Door top paints at the cell NORTH of the door (y+1 = north in y-up) — same cell
        // as wall overhangs use. The SPD convention: the door body occupies its own cell and
        // the arched top visually sits in the tile above on screen.
        if (isDoorAt(level, x, y - 1)) {
            drawTile(DOOR_OVERHANG, x, y);
        }
    }

    private int terrainVisual(Level level, int x, int y) {
        Tile t = level.tiles[x][y];
        return switch (t) {
            case FLOOR       -> pickVariant(x, y, FLOOR_VARIANTS);
            case FLOOR_WOOD  -> pickVariant(x, y, FLOOR_WOOD_VARIANTS);
            case LAMP        -> pickVariant(x, y, FLOOR_VARIANTS); // base; lamp sprite drawn in drawLampAt
            // Stairs render their floor underlay in drawFloorAt and the 2×2 ladder sprite
            // in the per-cell content pass (drawStairsAt). Returning -1 here skips the
            // legacy 1×1 glyph draw.
            case STAIRS_UP, STAIRS_DOWN -> -1;
            case CHASM       -> -1; // handled inline
            case WALL        -> raisedWall(level, x, y);
            case DOOR, DOOR_OPEN -> raisedDoor(level, x, y);
            // Statues sit on a regular floor base; the statue sprite itself is layered on
            // top in drawStatueAt so the L/R facing flip can be applied at draw time.
            case STATUE_SMALL_L, STATUE_SMALL_R,
                 STATUE_LARGE_L, STATUE_LARGE_R -> pickVariant(x, y, FLOOR_VARIANTS);
        };
    }

    /**
     * Deterministic per-tile pick from a variant list. Same (x, y) always returns the same
     * entry, so a floor tile keeps its look across frames instead of flickering between
     * variants. Different constants from {@code vegJitterX}/{@code vegJitterY} so terrain
     * variants aren't correlated with grass-offset jitter.
     */
    private static int pickVariant(int x, int y, int[] variants) {
        int h = x * 92613371 ^ y * 17391317;
        return variants[Math.floorMod(h, variants.length)];
    }

    /**
     * SPD's 4-neighbor stitch, adapted to the surface model: a neighbor counts as "shore" if
     * it's a walkable tile that does NOT share this cell's surface, so adjacent pool cells
     * blend seamlessly while edges against dry floor get the shore overlay. +1 north, +2 east,
     * +4 south, +8 west.
     */
    private int stitchSurface(Level level, int x, int y, Surface self) {
        int result = WATER;
        if (isSurfaceShore(level, x,     y + 1, self)) result += 1;
        if (isSurfaceShore(level, x + 1, y,     self)) result += 2;
        if (isSurfaceShore(level, x,     y - 1, self)) result += 4;
        if (isSurfaceShore(level, x - 1, y,     self)) result += 8;
        return result;
    }

    /**
     * True when the cell at (x, y) marks a "shore" boundary for a surface of type {@code self}
     * sitting in the source cell — meaning {@code self}'s mask should fade on that side. The
     * test is "the neighbour does NOT also hold {@code self}": a dry tile, a different
     * surface, or out-of-bounds all qualify. This is what makes per-surface passes work — in
     * the water pass, a blood-neighbour cell is a shore for water just like a dry tile would
     * be, so the water surface fades there independently of what blood draws in its own pass.
     * Walls / chasms are excluded because they're opaque scenery that already hides anything
     * we'd draw underneath.
     */
    private static boolean isSurfaceShore(Level level, int x, int y, Surface self) {
        if (x < 0 || y < 0 || x >= level.width || y >= level.height) return false;
        Tile t = level.tiles[x][y];
        if (t == Tile.WALL || t == Tile.CHASM) return false;
        return level.surface[x][y] != self;
    }

    private int raisedWall(Level level, int x, int y) {
        Tile south = tileAt(level, x, y - 1);
        if (south == Tile.WALL) return -1;
        // Skip only when there's truly nothing visible behind the wall — OOB or unexplored
        // chasm. An EXPLORED chasm south (e.g. the interior of a visible chasm-filled room)
        // is a legitimate backdrop: the wall still renders so the player can see it.
        if (south == null) return -1;
        if (south == Tile.CHASM && !level.explored[x][y - 1]) return -1;

        int bits = 0;
        if (!stitchBarrier(level, x + 1, y)) bits += 1;
        if (!stitchBarrier(level, x - 1, y)) bits += 2;
        // Stitch variants 1..3 (at least one neighbour open) stay at their unique cells so
        // the wall still reads as opening up at doorways / corners. Only the stitch-0
        // "solid block" variant picks from the multiple art alternatives at row 4.
        if (bits == 0) {
            return pickVariant(x, y, RAISED_WALL_VARIANTS);
        }
        return RAISED_WALL + bits;
    }

    private int raisedDoor(Level level, int x, int y) {
        Tile t = level.tiles[x][y];
        // Sideways door (wall-flanked along the N/S axis): same body sprite for open and
        // closed; the difference shows in the dropped overlays handled by drawWallOverlayAt.
        // Front-facing door swaps the body sprite for the row-9 "open" variant when open.
        if (isWallish(level, x, y - 1)) return RAISED_DOOR_SIDEWAYS;
        return t == Tile.DOOR_OPEN ? RAISED_DOOR_OPEN : RAISED_DOOR;
    }

    private static boolean isWallish(Level level, int x, int y) {
        if (x < 0 || y < 0 || x >= level.width || y >= level.height) return true;
        return level.tiles[x][y] == Tile.WALL;
    }

    /**
     * Stitching neighbor test for the 4-bit variant math. Walls and OOB are always barriers.
     * An UNEXPLORED chasm is a barrier (stands in for "outside the map" so walls don't stitch
     * open on the unseen side), but an EXPLORED chasm is an open interior — walls of a visible
     * chasm-filled room should stitch toward it, not dead-end like they would at the map edge.
     */
    private static boolean stitchBarrier(Level level, int x, int y) {
        if (x < 0 || y < 0 || x >= level.width || y >= level.height) return true;
        Tile t = level.tiles[x][y];
        if (t == Tile.WALL) return true;
        if (t == Tile.CHASM && !level.explored[x][y]) return true;
        return false;
    }

    private static boolean isDoorAt(Level level, int x, int y) {
        if (x < 0 || y < 0 || x >= level.width || y >= level.height) return false;
        Tile t = level.tiles[x][y];
        return t == Tile.DOOR || t == Tile.DOOR_OPEN;
    }

    private static Tile tileAt(Level level, int x, int y) {
        if (x < 0 || y < 0 || x >= level.width || y >= level.height) return null;
        return level.tiles[x][y];
    }

    private void drawTile(int visual, int x, int y) {
        if (visual < 0 || visual >= tiles.length) return;
        TextureRegion r = tiles[visual];
        if (r == null) return;
        batch.draw(r, x * (float) CELL, y * (float) CELL, CELL, CELL);
    }

    private static boolean inBounds(Level level, int x, int y) {
        return x >= 0 && y >= 0 && x < level.width && y < level.height;
    }

    private void drawItemsAt(Level level, int x, int y, Map<Long, List<Item>> itemsByCell) {
        List<Item> list = itemsByCell.get(cellKey(x, y));
        if (list == null) return;
        for (Item it : list) drawItem(level, it);
    }

    private void drawMobsAt(Level level, int x, int y, Map<Long, List<Mob>> mobsByCell) {
        List<Mob> list = mobsByCell.get(cellKey(x, y));
        if (list == null) return;
        for (Mob mob : list) drawMob(level, mob);
    }

    private void drawFxAt(Level level, int x, int y, Map<Long, List<Effect>> fxByCell) {
        List<Effect> list = fxByCell.get(cellKey(x, y));
        if (list == null) return;
        batch.setColor(Color.WHITE);
        for (Effect e : list) fxRenderer.drawEffect(level, e);
        batch.setColor(Color.WHITE);
    }

    private void drawItem(Level level, Item it) {
        if(it==null) return;
        if(it.location == null) {
            System.err .println("Item " + it.type + " has null location, skipping draw");
            return;
        }
        int x = it.location.tileX(), y = it.location.tileY();
        if (!inBounds(level, x, y) || !level.visible[x][y]) return;
        // Prefer the shared ItemSprites lookup so the on-floor icon matches the
        // inventory + action-bar art. Falls back to the glyph if no sprite is registered.
        com.badlogic.gdx.graphics.g2d.TextureRegion region = ItemSprites.regionFor(it);
        if (region != null) {
            batch.setColor(Color.WHITE);
            // Source art is 32×32, world cell is 16×16 — libGDX scales 2:1 down with
            // nearest-neighbour filtering for crisp pixels.
            batch.draw(region, x * (float) CELL, y * (float) CELL,
                    (float) CELL, (float) CELL);
        } else {
            System.err.println("No sprite for item " + it.type + " at (" + x + ", " + y + ")");
            //here draw a placeholder
        }
        if (it.level > 0) drawItemLevelBadge(it, x, y);
    }

    /** Small {@code +N} marker drawn at the top-right corner of an item's tile to
     *  signal its enchant level. Yellow, half-scale font so it doesn't dominate the
     *  sprite. */
    private void drawItemLevelBadge(Item it, int x, int y) {
        float prevScale = font.getData().scaleX;
        font.getData().setScale(prevScale * 0.55f);
        font.setColor(1f, 0.92f, 0.4f, 1f);
        String text = "+" + it.level;
        com.badlogic.gdx.graphics.g2d.GlyphLayout layout =
                new com.badlogic.gdx.graphics.g2d.GlyphLayout(font, text);
        // Top-right corner: x = right edge - text width, y = top of cell (font draws
        // baseline-aligned, hence the +height adjust).
        float bx = x * CELL + CELL - layout.width  - 1f;
        float by = y * CELL + CELL - 1f;
        font.draw(batch, text, bx, by);
        font.getData().setScale(prevScale);
        font.setColor(Color.WHITE);
    }

    private void drawMob(Level level, Mob mob) {
        int mx = mob.position.tileX(), my = mob.position.tileY();
        com.bjsp123.rl2.world.anim.MobAnimState as = animator.stateOf(mob);
        // Teleport-fade overrides: during the first half of the fade the mob is drawn at
        // its origin tile (alpha decreasing); during the second half at its destination
        // (alpha rising). Skip the visibility test against the actual logical position
        // for phase 1 — origin/destination might fall in different visibility regions and
        // we want the fade-out to play even if the destination tile is dark.
        float teleportAlpha = 1f;
        if (as.teleportFadeMs > 0) {
            int half = com.bjsp123.rl2.logic.MobSystem.TELEPORT_FADE_HALF_MS;
            int total = com.bjsp123.rl2.logic.MobSystem.TELEPORT_FADE_TOTAL_MS;
            if (as.teleportFadeMs > half) {
                // Departing: render at origin, alpha 1 → 0 across the first half.
                mx = as.teleportFromX;
                my = as.teleportFromY;
                teleportAlpha = (as.teleportFadeMs - half) / (float) (total - half);
            } else {
                // Arriving: render at destination (already mob.position), alpha 0 → 1.
                teleportAlpha = 1f - as.teleportFadeMs / (float) half;
            }
        }
        if (!inBounds(level, mx, my) || !level.visible[mx][my]) return;
        // Melee-lunge / hit-flinch offset: world y-up matches MobAnimState.animOffsetY()
        // (positive Y = north = up on screen).
        float ox = as.animOffsetX();
        float oy = as.animOffsetY();
        // Step-interpolation offset — slides the mob from its previous tile into its
        // current logical tile linearly over the animation. Suppressed during a teleport
        // fade (the mob isn't sliding, it's blinking from one cell to another).
        if (as.stepTotal > 0 && as.teleportFadeMs <= 0) {
            float t = Math.min(1f, as.stepFrame / (float) as.stepTotal);
            ox += as.stepFromDx * (1f - t) * CELL;
            oy += as.stepFromDy * (1f - t) * CELL;
        }
        // Player-only lift — sits the character a few pixels off the tile floor so they
        // read as a figure standing on the ground rather than rooted into it.
        if (mob.behavior == Behavior.PLAYER) {
            oy += PLAYER_Y_LIFT;
        }
        // Live mobs render fully opaque; the death-fade lives on rgame's ghost list,
        // not on the mob itself (killMob removes the mob from level.mobs immediately).
        // Teleport fade still multiplies in.
        float alpha = teleportAlpha;
        if (alpha <= 0f) return;
        Sprite s = spriteForMob(mob);
        if (s != null) {
            drawMobSprite(s, mx, my, ox, oy, alpha);
        } else {
            System.err.println("No sprite for mob " + mob.mobType + " at (" + mx + ", " + my + ")");
            //placeholder drawn here?
        }
        if (com.bjsp123.rl2.logic.BuffSystem.hasBuff(mob,
                com.bjsp123.rl2.model.Buff.BuffType.ON_FIRE)) {
            fxRenderer.drawFireOnMob(mob, mx, my, ox, oy);
        }
        // Equipped gems bob around the player's head. The orbit + bob math is purely
        // visual; we leave the rest of the per-mob block alone.
        if (mob.behavior == Behavior.PLAYER) {
            drawPlayerGems(mob, mx, my, ox, oy, alpha);
        }
        // Wounded enemy mobs get a small HP bar over their head. Skip the player (they
        // see HP via the HUD) and full-HP mobs.
        double maxHp = mob.effectiveStats().maxHp;
        if (mob.behavior != Behavior.PLAYER && maxHp > 0 && mob.hp < maxHp) {
            drawMobHpBar(mob, mx, my, ox, oy, alpha);
        }
    }

    /** Render any of the three gem slots (GEM1/GEM2/GEM3) that are filled, orbiting +
     *  bobbing above the player's head. Each gem occupies a fixed slot on a horizontal
     *  arc with a sine offset in y. The clock is the renderer's own real-time
     *  accumulator so motion is wall-clock smooth (independent of game ticks). */
    private void drawPlayerGems(Mob mob, int mx, int my, float ox, float oy, float alpha) {
        com.bjsp123.rl2.model.Item.ItemSlot[] gemSlots = {
                com.bjsp123.rl2.model.Item.ItemSlot.GEM1,
                com.bjsp123.rl2.model.Item.ItemSlot.GEM2,
                com.bjsp123.rl2.model.Item.ItemSlot.GEM3
        };
        java.util.List<com.bjsp123.rl2.model.Item> gems = new java.util.ArrayList<>(3);
        for (com.bjsp123.rl2.model.Item.ItemSlot s : gemSlots) {
            com.bjsp123.rl2.model.Item g = mob.inventory.equipped(s);
            if (g != null) gems.add(g);
        }
        int n = gems.size();
        if (n <= 0) return;
        // Centre the cluster on the player sprite's vertical middle so the GEM2 slot
        // (the centre orbit position when 3 gems are equipped) sits over the player's
        // chest rather than above their head. Horizontal centre is the tile centre.
        float cx     = mx * CELL + CELL * 0.5f + ox;
        float midY   = my * CELL + CELL * 0.5f + oy + PLAYER_Y_LIFT;
        float t      = (float) ((System.nanoTime() / 1_000_000L) % 1_000_000L) / 1000f;
        float iconSize = CELL * 0.6f;
        // Wider spread + larger bob amplitude so the gems are clearly orbiting around
        // the player rather than huddled overhead.
        float spread    = CELL * 0.85f;
        float bobAmp    = 4.0f;
        for (int i = 0; i < n; i++) {
            // Spread n gems evenly over [-spread, +spread]; for a single gem (n==1),
            // u = 0 (centered).
            float u = (n == 1) ? 0f : ((i / (float) (n - 1)) - 0.5f) * 2f;
            float bobPhase = (i * 1.7f) + t * 1.6f;
            float bx = u * spread;
            float by = (float) Math.sin(bobPhase) * bobAmp;
            float drawX = cx + bx - iconSize * 0.5f;
            float drawY = midY - iconSize * 0.5f + by;
            com.badlogic.gdx.graphics.g2d.TextureRegion r = GemSprites.regionFor(gems.get(i));
            if (r == null) continue;
            batch.setColor(1f, 1f, 1f, alpha);
            batch.draw(r, drawX, drawY, iconSize, iconSize);
        }
        batch.setColor(Color.WHITE);
    }

    /** Thin horizontal HP bar over a wounded mob's head. Red fill on a black backing,
     *  width matches the mob's tile, drawn 4 px above the tile top so it doesn't
     *  collide with the sprite or the mob glyph. */
    private void drawMobHpBar(Mob mob, int mx, int my, float ox, float oy, float alpha) {
        float frac = (float) Math.max(0, mob.hp) / (float) mob.effectiveStats().maxHp;
        if (frac > 0.999f) return;
        float barW = CELL - 2f;          // 14 px — leaves a 1-px gutter on each side
        float barH = 2f;
        float bx   = mx * CELL + 1f + ox;
        float by   = my * CELL + CELL + 2f + oy;
        // Backing — dark frame so the bar is legible against any tile.
        batch.setColor(0f, 0f, 0f, 0.85f * alpha);
        batch.draw(whiteRegion, bx - 1f, by - 1f, barW + 2f, barH + 2f);
        // Empty fill behind the red — flat dim red so the loss reads as missing HP
        // rather than nothing.
        batch.setColor(0.35f, 0.05f, 0.05f, alpha);
        batch.draw(whiteRegion, bx, by, barW, barH);
        // Filled portion.
        batch.setColor(0.85f, 0.18f, 0.18f, alpha);
        batch.draw(whiteRegion, bx, by, barW * frac, barH);
        batch.setColor(Color.WHITE);
    }

    /**
     * Draw a mob so its VISIBLE silhouette is exactly {@link #MOB_VISIBLE_W} × {@link
     * #MOB_VISIBLE_H} on screen, regardless of how much blank canvas its source frame has.
     * Width and height scale independently (non-uniform) so skinny sprites and chubby ones
     * both hit the same footprint. The visible silhouette is recentred on the tile by
     * shifting the draw origin by {@code visibleLeft * scaleX}. A semi-transparent oval
     * shadow is drawn first, anchored at the baseline.
     */
    private void drawMobSprite(Sprite s, int gx, int gy, float offsetX, float offsetY, float alpha) {
        // "Natural" sprites (large blobs etc.) draw at source scale; everything else gets
        // normalised to MOB_VISIBLE_W × MOB_VISIBLE_H so silhouettes read consistently.
        float scaleX = s.natural ? 1f : MOB_VISIBLE_W / (float) s.visibleW;
        float scaleY = s.natural ? 1f : MOB_VISIBLE_H / (float) s.visibleH;
        float dw = s.w * scaleX;
        float dh = s.h * scaleY;
        float yAdj = s.yAdjust * scaleY;
        // Offsets (from the lunge / flinch animation) shift both the mob and its shadow
        // together — the whole silhouette leans into the strike or recoils from a hit.
        float tileCenterX = gx * CELL + CELL / 2f + offsetX;
        float baselineY   = gy * CELL + ENTITY_Y_OFFSET + offsetY;

        // Centre the visible silhouette horizontally on the tile. Source center-x of the
        // silhouette is (visibleLeft + visibleW/2), which scales to that × scaleX in draw px;
        // the frame's draw origin (left edge) therefore sits tileCenterX − that amount.
        float silhouetteCenterScaled = (s.visibleLeft + s.visibleW / 2f) * scaleX;
        float drawX = tileCenterX - silhouetteCenterScaled;
        float drawY = baselineY + yAdj;

        // Mob and its shadow share an alpha so the death flicker / fade dims both together.
        batch.setColor(1f, 1f, 1f, alpha);
        // Shadow width tracks the silhouette plus a small margin so wide mobs (blobs) get a
        // wide shadow and narrow mobs don't look pinpointed at the feet.
        float shadowW = s.visibleW * scaleX + SHADOW_EXTRA_W;
        batch.draw(shadowTex, tileCenterX - shadowW / 2f, baselineY - SHADOW_H / 2f,
                shadowW, SHADOW_H);
        // 1-px black outline — render the sprite four times at ±1 px cardinal offsets in
        // pure black at MOB_OUTLINE_ALPHA. SpriteBatch multiplies the sprite's RGB by the
        // batch colour, so RGB=0 zeroes out the original colour while preserving the alpha
        // mask shape; alpha is multiplied by the batch alpha. Where the original sprite is
        // transparent, the offset draw is also transparent — so the outline only "shows"
        // around the silhouette's edge. Death-fade alpha modulates the outline too.
        float outlineA = MOB_OUTLINE_ALPHA * alpha;
        if (outlineA > 0f)
        {
            batch.setColor(0f, 0f, 0f, outlineA);
            batch.draw(s.region, drawX - 1, drawY,     dw, dh);
            batch.draw(s.region, drawX + 1, drawY,     dw, dh);
            batch.draw(s.region, drawX,     drawY - 1, dw, dh);
            batch.draw(s.region, drawX,     drawY + 1, dw, dh);
        }
        batch.setColor(1f, 1f, 1f, alpha);
        batch.draw(s.region, drawX, drawY, dw, dh);
        batch.setColor(Color.WHITE);
    }

    /**
     * Build a soft-edged oval shadow as a small RGBA texture. The source is drawn at arbitrary
     * display size with linear filtering, giving a blurry ellipse. Parabolic alpha falloff
     * (1 − r²) is plenty soft without needing a full blur pass. {@code peakAlpha} is the alpha
     * at the center of the ellipse — mob shadows use a light value; lamp shadows use a
     * heavier one to sell the lamp as physically planted on the floor.
     */
    private static Texture makeShadowTexture(int w, int h, float peakAlpha) {
        Pixmap p = new Pixmap(w, h, Pixmap.Format.RGBA8888);
        p.setBlending(Pixmap.Blending.None);
        p.setColor(0, 0, 0, 0);
        p.fill();
        float cx = (w - 1) / 2f;
        float cy = (h - 1) / 2f;
        float rx = w / 2f;
        float ry = h / 2f;
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                float dx = (x - cx) / rx;
                float dy = (y - cy) / ry;
                float falloff = Math.max(0f, 1f - (dx * dx + dy * dy));
                int alpha = Math.round(falloff * peakAlpha * 255f);
                if (alpha > 0) p.drawPixel(x, y, alpha); // RGBA8888: R=G=B=0, A=alpha
            }
        }
        Texture t = new Texture(p);
        p.dispose();
        t.setFilter(Texture.TextureFilter.Linear, Texture.TextureFilter.Linear);
        return t;
    }

    /**
     * Pick the right frame for a mob, dispatching purely on {@link Mob#mobType}. Every
     * non-PLAYER species comes from mobs.png as a left-facing pose mirrored at draw time.
     * The PLAYER case consults {@link Mob#characterClass} since the three classes share
     * one {@link MobType#PLAYER} but use different sprite slots.
     */
    private Sprite spriteForMob(Mob mob) {
        int f = mob.facingEast ? 1 : 0;
        if (mob.mobType == null) return null;
        return switch (mob.mobType) {
            case PLAYER             -> playerSprite(mob, f);
            // Insects.
            case SPIDER             -> spiderFacing[f];
            case LOATHESOME_BUG     -> loathesomeBugFacing[f];
            case BAT                -> batFacing[f];
            case SOLDIER_BUG        -> soldierBugFacing[f];
            case BUG_PRODIGY        -> bugProdigyFacing[f];
            case BLACK_ANT          -> blackAntFacing[f];
            case RED_ANT            -> redAntFacing[f];
            case BLACK_ANT_HILL     -> blackAntHillFacing[f];
            case RED_ANT_HILL       -> redAntHillFacing[f];
            // Critters.
            case MOUSE              -> mouseFacing[f];
            // Blazing firemouse rides the mouse sprite for now — same silhouette, with the
            // game's fire VFX picking up the slack visually whenever the mouse is engulfed.
            case BLAZING_FIREMOUSE  -> mouseFacing[f];
            case CAT                -> catFacing[f];
            case KITTEN             -> kittenFacing[f];
            case DOG                -> dogFacing[f];
            // Humanoids.
            case KOBOLD_FIGHTER     -> koboldFighterFacing[f];
            case BARBARIAN_PRINCESS -> princessFacing[f];
            // Blobs.
            case BLOB               -> blobFacing[f];
            case KISSYBLOB          -> kissyblobFacing[f];
            // Mask imp line.
            case MASK_IMP           -> maskImpFacing[f];
            case LARGE_MASK_IMP     -> largeMaskImpFacing[f];
            case DEVELOPED_MASK_IMP -> developedMaskImpFacing[f];
            case HORRIBLE_MASK_IMP  -> horribleMaskImpFacing[f];
            // Apex.
            case GHOST              -> ghostFacing[f];
            case HORROR             -> horrorFacing[f];
        };
    }

    private Sprite playerSprite(Mob mob, int f) {
        CharacterClass cls = mob.characterClass;
        if (cls == CharacterClass.ROGUE) return rogueFacing[f];
        if (cls == CharacterClass.MAGE)  return mageFacing[f];
        return warriorFacing[f];
    }


    @Override
    public void dispose() {
        if (batch        != null) batch.dispose();
        if (font         != null) font.dispose();
        // Atlas-derived textures are owned by their respective sprite-source helpers
        // (MobSprites, TileSprites, SurfaceSprites). They live for the JVM lifetime
        // and aren't disposed by an individual renderer instance.
        if (whiteTex     != null) whiteTex.dispose();
        if (shadowTex    != null) shadowTex.dispose();
        if (lampShadowTex != null) lampShadowTex.dispose();
        if (wallBaseShadowTex != null) wallBaseShadowTex.dispose();
        if (floorShadowVertTex != null) floorShadowVertTex.dispose();
        if (floorShadowHorzTex != null) floorShadowHorzTex.dispose();
        if (surfaceMaskShader != null) surfaceMaskShader.dispose();
        fog.dispose();
    }
}
