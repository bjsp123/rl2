package com.bjsp123.rl2.world.render;
import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.graphics.g2d.TextureRegion;
import com.bjsp123.rl2.model.Buff.BuffType;

import java.util.EnumMap;
import java.util.Map;

/**
 * Lazy-loaded sprite source for the {@link BuffType} icons that live in
 * {@code sprites/buffs16.png}. Sheet layout:
 * <ul>
 *   <li>Top 16-px band - buff-icon row, 16x16 cells, single row.</li>
 *   <li>Second 32-px band (y = 32..64) - attack-slash sprites, 32x32 cells.</li>
 * </ul>
 * {@link #iconCol} maps each buff to its column in the buff row.
 *
 * <p>The texture is held as a singleton because it's tiny, doesn't need to
 * be reloaded between screens, and gets a fresh handle on every screen
 * create otherwise. Call {@link #disposeShared()} when the game shuts down.
 */
public final class BuffIcons {

    /** Edge length of one buff-icon cell in source pixels. */
    public static final int BUFF_CELL  = 16;
    /** Edge length of one slash-sprite cell in source pixels. */
    public static final int SLASH_CELL = 32;
    /** Top-left y of the slash band - first 32-px row is the buff band
     *  (16 px of icons + 16 px of blank), slashes start at the second. */
    private static final int SLASH_Y   = 32;

    private static Texture sheet;
    private static Map<BuffType, TextureRegion> cache;


    private BuffIcons() {}

    /** Texture-region for {@code type}, or {@code null} when the buff has no icon
     *  cell mapped or when the sheet failed to load. */
    public static TextureRegion regionFor(BuffType type) {
        if (cache == null) load();
        return cache.get(type);
    }

    /** True if at least one buff has an icon, i.e. the sheet loaded successfully. */
    public static boolean isLoaded() {
        if (cache == null) load();
        return sheet != null;
    }

    /** Texture-region for the buff-icon cell at flat atlas index {@code idx}
     *  (col + row * {@link #COLS_PER_ROW}). Lets non-{@link BuffType} callers
     *  (e.g. element-aware damage floaters) reach atlas slots that aren't
     *  bound to a real buff - SHOCK uses slot 23. Returns {@code null} on
     *  load failure or out-of-bounds. */
    public static TextureRegion regionForAtlasIndex(int idx) {
        if (cache == null) load();
        if (sheet == null || idx < 0) return null;
        int sx = (idx % COLS_PER_ROW) * BUFF_CELL;
        int sy = SpriteAtlas.buffsY() + (idx / COLS_PER_ROW) * BUFF_CELL;
        int sw = sheet.getWidth(), sh = sheet.getHeight();
        if (sx + BUFF_CELL > sw || sy + BUFF_CELL > sh) return null;
        return new TextureRegion(sheet, sx, sy, BUFF_CELL, BUFF_CELL);
    }

    /** Attack-flash sprite from {@code buffs16.png}'s slash band. Player
     *  slash at col 0 -> source (0, 32, 32, 32); mob slash at col 1 -> (32, 32,
     *  32, 32). Returns {@code null} if the sheet failed to load or doesn't
     *  have room for a 32x32 cell at the requested column. */
    public static TextureRegion attackFlashRegion(int col) {
        if (cache == null) load();
        if (sheet == null) return null;
        int sw = sheet.getWidth(), sh = sheet.getHeight();
        int slashY = SpriteAtlas.buffsY() + SLASH_Y;
        if (sh < slashY + SLASH_CELL || sw < (col + 1) * SLASH_CELL) return null;
        return new TextureRegion(sheet, col * SLASH_CELL, slashY, SLASH_CELL, SLASH_CELL);
    }

    /** Knockback graphic from the slash band - col 2, just to the right of
     *  the player + mob slashes. Plays on a unit that's been knocked back
     *  to signal the impact. Returns {@code null} on missing sheet / cell. */
    public static TextureRegion knockbackRegion() {
        return attackFlashRegion(2);
    }

    /** Glow sprite for perk-type powerups (col 4 of the slash band). */
    public static TextureRegion perkGlowRegion() {
        return attackFlashRegion(4);
    }

    /** Glow sprite for HP/mana-type powerups (col 5 of the slash band). */
    public static TextureRegion hpManaGlowRegion() {
       return attackFlashRegion(5);
    }

    /** Surprise-attack marker (col 6 of the 32x32 slash band). */
    public static TextureRegion surpriseRegion() {
        return attackFlashRegion(6);
    }

    /** Beacon-glow halo (32x32). Sprite lives at row 1, col 9 of the
     *  32x32 grid - the bottom-right cell of the current buffs16.png
     *  layout. Addressed by row/col from the top-left so the lookup
     *  stays stable if the sheet is later extended downward or
     *  rightward. */
    public static TextureRegion beaconGlowRegion() {
        return slashCell(/*row*/ 1, /*col*/ 9);
    }

    /** Look up a 32x32 cell of {@code buffs16.png} by grid row/col, with
     *  the 32x32 grid origin at the top of the sheet. Row 0 is the buff-
     *  icon row + blank strip (the buff icons themselves are 16x16 inside
     *  that row); row 1 onward is the 32x32 cell grid (slash band,
     *  particle grid, beacon halo, ...). Returns {@code null} on sheet
     *  load failure or out-of-bounds. */
    private static TextureRegion slashCell(int row, int col) {
        if (cache == null) load();
        if (sheet == null) return null;
        int sx = col * SLASH_CELL;
        int sy = SpriteAtlas.buffsY() + row * SLASH_CELL;
        int sw = sheet.getWidth(), sh = sheet.getHeight();
        if (sx + SLASH_CELL > sw || sy + SLASH_CELL > sh) return null;
        return new TextureRegion(sheet, sx, sy, SLASH_CELL, SLASH_CELL);
    }

    /** Edge length of one particle cell (16x16). */
    private static final int PARTICLE_CELL = 16;
    /** X origin of the 2x3 particle grid in {@code buffs16.png} - sits to
     *  the right of the {@link #surpriseRegion} (col 6 of the 32x32 slash
     *  band ends at x = 7 * 32 = 224). The grid then runs 3 cells wide,
     *  2 rows tall. */
    private static final int PARTICLE_X0   = 7 * SLASH_CELL;   // 224
    /** Particle grid sits in the same y-band as the 32x32 slash row, but
     *  the cells are 16-tall so it occupies y = SLASH_Y..SLASH_Y+31. */
    private static final int PARTICLE_GRID_COLS = 3;
    private static final int PARTICLE_GRID_ROWS = 2;

    /** Particle sprite from the 2x3 grid in {@code buffs16.png}, indexed
     *  0..5 reading left-to-right, top-to-bottom. Used by particle-based
     *  effects (burst / splash / fountain / motes) instead of the
     *  whiteRegion stamp. Returns {@code null} if the sheet failed to load
     *  or the requested cell falls outside the sheet's bounds. */
    public static TextureRegion particleRegion(int idx) {
        if (cache == null) load();
        if (sheet == null) return null;
        if (idx < 0 || idx >= PARTICLE_GRID_COLS * PARTICLE_GRID_ROWS) return null;
        int col = idx % PARTICLE_GRID_COLS;
        int row = idx / PARTICLE_GRID_COLS;
        int sx  = PARTICLE_X0 + col * PARTICLE_CELL;
        int sy  = SpriteAtlas.buffsY() + SLASH_Y + row * PARTICLE_CELL;
        int sw  = sheet.getWidth(), sh = sheet.getHeight();
        if (sx + PARTICLE_CELL > sw || sy + PARTICLE_CELL > sh) return null;
        return new TextureRegion(sheet, sx, sy, PARTICLE_CELL, PARTICLE_CELL);
    }

    /** The arrow-up particle sprite - the cell immediately right of the top droplet
     *  ({@code DROP_0}, particle-grid col 2) in {@code buffs16.png}'s particle band.
     *  Used for rising "up arrow" effects (regeneration buff, powerup pickups) instead
     *  of a font glyph. Returns {@code null} on load / bounds failure. */
    public static TextureRegion arrowUpRegion() {
        if (cache == null) load();
        if (sheet == null) return null;
        int sx = PARTICLE_X0 + 3 * PARTICLE_CELL;   // col 3 = right of the top droplet (col 2)
        int sy = SpriteAtlas.buffsY() + SLASH_Y;     // top row of the particle band
        if (sx + PARTICLE_CELL > sheet.getWidth() || sy + PARTICLE_CELL > sheet.getHeight()) {
            return null;
        }
        return new TextureRegion(sheet, sx, sy, PARTICLE_CELL, PARTICLE_CELL);
    }

    /** Number of icon columns per row in the buff-icon band. */
    private static final int COLS_PER_ROW = 20;

    private static void load() {
        cache = new EnumMap<>(BuffType.class);
        SpriteAtlas.load();
        sheet = SpriteAtlas.texture();
        if (sheet == null) return;
        for (BuffType t : BuffType.values()) {
            int idx = iconIndex(t);
            if (idx < 0) continue;
            int sx = (idx % COLS_PER_ROW) * BUFF_CELL;
            int sy = SpriteAtlas.buffsY() + (idx / COLS_PER_ROW) * BUFF_CELL;
            cache.put(t, new TextureRegion(sheet, sx, sy, BUFF_CELL, BUFF_CELL));
        }
    }

    /**
     * Flat index into the buff-icon grid in {@code sprites/buffs16.png}.
     * Index = col + row * {@link #COLS_PER_ROW}; the loader converts to
     * pixel (x, y). Renderer-side mapping — lives in rgame because the
     * sheet layout is presentation, not game logic.
     *
     * <p>Row 0 (left → right): on fire, invisible, frightened, oily,
     * sorcerous, levitating, regenerating, poisoned, blessed, ghostly,
     * hasted, protection, anti-magic, ESP, chilled, (unused), recharging
     * (cooldown), killer, wet, bleeding.
     * <p>Row 1: phase (col 0), frozen (col 1).
     */
    /** Flat atlas index for {@code type}, or -1 when the buff has no mapped
     *  cell. Public so non-cache consumers (e.g. the buff-expired floater)
     *  can compose composite effects without duplicating the switch. */
    public static int iconIndexFor(BuffType type) { return iconIndex(type); }

    private static int iconIndex(BuffType type) {
        return switch (type) {
            case ON_FIRE      -> 0;
            case INVISIBLE    -> 1;
            case FRIGHTENED   -> 2;
            case OILY         -> 3;
            case SORCERY      -> 4;
            case LEVITATING   -> 5;
            case REGENERATION -> 6;
            case POISONED     -> 7;
            case HOPE         -> 8;
            case GHOSTLY      -> 9;
            case HASTED       -> 10;
            case PROTECTION   -> 11;
            case ANTI_MAGIC   -> 12;
            // Two distinct eye icons sit right of the CANCELLED glyph (slot 26).
            case ESP          -> 27;
            case INSIGHT      -> 28;
            case CHILLED      -> 14;
            // Cooldown / dormant buffs share the "recharging" (clock) icon.
            case TELEPORT_COOLDOWN, RANGED_COOLDOWN, HASTE_COOLDOWN, HEAL_COOLDOWN,
                 PHASE_DODGE_COOLDOWN, HIDING -> 16;
            case KILLER       -> 17;
            case WET          -> 18;
            case BLEEDING     -> 19;
            // Row 1
            case PHASE        -> 20;
            case FROZEN       -> 21;
            case SHIELDED     -> 22;
            default           -> 6;     // regeneration as fallback
        };
    }

    /** Release cached regions. Texture is owned by {@link SpriteAtlas}. */
    public static void disposeShared() {
        sheet = null;
        cache = null;
    }
}
