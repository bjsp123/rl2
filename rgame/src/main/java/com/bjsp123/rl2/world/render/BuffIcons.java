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
     * hasted, protection, anti-magic, ESP, chilled, starving, recharging
     * (cooldown), killer, wet, bleeding.
     * <p>Row 1: phase (col 0), frozen (col 1).
     */
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
            case ESP, INSIGHT -> 13;
            case CHILLED      -> 14;
            case STARVING     -> 15;
            // Cooldown / dormant buffs share the "recharging" icon.
            case TELEPORT_COOLDOWN, RANGED_COOLDOWN, HIDING -> 16;
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
