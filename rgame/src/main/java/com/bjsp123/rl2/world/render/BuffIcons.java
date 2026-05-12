package com.bjsp123.rl2.world.render;
import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.graphics.g2d.TextureRegion;
import com.bjsp123.rl2.model.Buff.BuffType;

import java.util.EnumMap;
import java.util.Map;

/**
 * Lazy-loaded sprite source for the {@link BuffType} icons that live in
 * {@code sprites/buffs16.png}. Sheet layout:
 * <ul>
 *   <li>Top 16-px band — buff-icon row, 16×16 cells, single row.</li>
 *   <li>Second 32-px band (y = 32..64) — attack-slash sprites, 32×32 cells.</li>
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
    /** Top-left y of the slash band — first 32-px row is the buff band
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
     *  slash at col 0 → source (0, 32, 32, 32); mob slash at col 1 → (32, 32,
     *  32, 32). Returns {@code null} if the sheet failed to load or doesn't
     *  have room for a 32×32 cell at the requested column. */
    public static TextureRegion attackFlashRegion(int col) {
        if (cache == null) load();
        if (sheet == null) return null;
        int sw = sheet.getWidth(), sh = sheet.getHeight();
        if (sh < SLASH_Y + SLASH_CELL || sw < (col + 1) * SLASH_CELL) return null;
        return new TextureRegion(sheet, col * SLASH_CELL, SLASH_Y, SLASH_CELL, SLASH_CELL);
    }

    /** Knockback graphic from the slash band — col 2, just to the right of
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

    private static void load() {
        cache = new EnumMap<>(BuffType.class);
        try {
            sheet = new Texture(Gdx.files.internal("sprites/buffs16.png"));
            sheet.setFilter(Texture.TextureFilter.Nearest, Texture.TextureFilter.Nearest);
            for (BuffType t : BuffType.values()) {
                int col = iconCol(t);
                if (col < 0) continue;
                cache.put(t, new TextureRegion(sheet,
                        col * BUFF_CELL, 0, BUFF_CELL, BUFF_CELL));
            }
        } catch (Exception ignored) {
            sheet = null;
            cache = new EnumMap<>(BuffType.class);
        }
    }

    /**
     * Column on the single buff-icon row in {@code sprites/buffs16.png}.
     * Renderer-side mapping — lives in rgame because the sheet layout is
     * presentation, not game logic.
     *
     * <p>Single-row layout (left → right): on fire, invisible, frightened,
     * oily, sorcerous, levitating, regenerating, poisoned, blessed,
     * ghostly, hasted, protection, anti-magic, ESP, chilled, starving,
     * recharging (cooldown), killer.
     */
    private static int iconCol(BuffType type) {
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
            default           -> 6;     // regeneration as fallback
        };
    }

    /** Release the shared texture. Safe to call repeatedly; subsequent
     *  {@link #regionFor} calls will reload on demand. */
    public static void disposeShared() {
        if (sheet != null) { sheet.dispose(); sheet = null; }
        cache = null;
    }
}
