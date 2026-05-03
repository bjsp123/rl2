package com.bjsp123.rl2.world.render;
import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.graphics.g2d.TextureRegion;
import com.bjsp123.rl2.model.Buff.BuffType;

import java.util.EnumMap;
import java.util.Map;
import com.bjsp123.rl2.ui.hud.HudRenderer;
import com.bjsp123.rl2.ui.overlay.LookRenderer;

/**
 * Lazy-loaded sprite source for the {@link BuffType} icons that live in
 * {@code sprites/buffs.png}. The texture is an 8×8-cell grid (cell pitch 8 px,
 * art 6×6 px top-left within each cell — we sample the full 8-px cell so the renderer
 * can decide on padding). {@link #iconCell} maps each buff to its {@code (col, row)}
 * location.
 *
 * <p>Used by:
 * <ul>
 *   <li>{@link HudRenderer} — buff strip under the player portrait</li>
 *   <li>{@link LookRenderer} — buff list on the looked-at mob</li>
 *   <li>{@link DefaultLevelRenderer} — floating-icon "buff applied" effect</li>
 * </ul>
 *
 * <p>The texture is held as a singleton because it's ~80 × 32 = tiny, doesn't need to
 * be reloaded between screens, and gets a fresh handle on every screen create otherwise.
 * Call {@link #disposeShared()} when the game shuts down.
 */
public final class BuffIcons {

    /** On-screen pitch and content size of an icon cell in the source texture. The
     *  sheet uses 8×8 cells with 7×7 art top-left within each, so we sample 7×7 to
     *  drop the single-pixel right/bottom padding cleanly. */
    public static final int SOURCE_CELL  = 8;
    public static final int SOURCE_ART   = 7;

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

    /** Attack-flash sprite from {@code buffs.png}. The sheet's top 16-px band carries
     *  the buff-icon grid (8×8 cells); the bottom 16-px band carries the slash sprites
     *  on a 16×16 grid. Player slash at (col 0, row 1) → source pixels (0, 16).
     *  Mob slash at (col 1, row 1) → (16, 16). Returns {@code null} if the sheet
     *  failed to load or doesn't have room for a 16×16 cell at row 1. */
    public static TextureRegion attackFlashRegion(int col) {
        if (cache == null) load();
        if (sheet == null) return null;
        int sw = sheet.getWidth(), sh = sheet.getHeight();
        if (sh < 32 || sw < (col + 1) * 16) return null;
        return new TextureRegion(sheet, col * 16, 16, 16, 16);
    }

    private static void load() {
        cache = new EnumMap<>(BuffType.class);
        try {
            sheet = new Texture(Gdx.files.internal("sprites/buffs.png"));
            sheet.setFilter(Texture.TextureFilter.Nearest, Texture.TextureFilter.Nearest);
            for (BuffType t : BuffType.values()) {
                int[] cell = iconCell(t);
                if (cell == null) continue;
                cache.put(t, new TextureRegion(sheet,
                        cell[0] * SOURCE_CELL, cell[1] * SOURCE_CELL,
                        SOURCE_ART, SOURCE_ART));
            }
        } catch (Exception ignored) {
            sheet = null;
            cache = new EnumMap<>(BuffType.class);
        }
    }

    /**
     * Cell coordinates {@code (col, row)} on the 8×8-grid {@code sprites/buffs.png}
     * sheet for a given buff. Renderer-side mapping — lives in rgame because the
     * sheet layout is presentation, not game logic.
     *
     * <p>Sheet layout per the user-supplied art:
     * <pre>
     *   row 0: 0=on fire | 1=invisible | 2=frightened | 3=oily | 4=sorcerous
     *          5=levitating | 6=regenerating | 7=poisoned | 8=blessed | 9=ghostly
     *   row 1: 0=hasted | 1=protection | 2=anti-magic | 3=ESP | 4=chilled
     *          5=starving | 6=recharging (cooldown) | 7=killer
     * </pre>
     */
    private static int[] iconCell(BuffType type) {
        return switch (type) {
            case ON_FIRE      -> new int[]{0, 0};
            case INVISIBLE    -> new int[]{1, 0};
            case FRIGHTENED   -> new int[]{2, 0};
            case OILY         -> new int[]{3, 0};
            case SORCERY      -> new int[]{4, 0};
            case LEVITATING   -> new int[]{5, 0};
            case REGENERATION -> new int[]{6, 0};
            case POISONED     -> new int[]{7, 0};
            case HOPE         -> new int[]{8, 0};
            case GHOSTLY      -> new int[]{9, 0};
            case HASTED       -> new int[]{0, 1};
            case PROTECTION   -> new int[]{1, 1};
            case ANTI_MAGIC   -> new int[]{2, 1};
            case ESP          -> new int[]{3, 1};
            case CHILLED      -> new int[]{4, 1};
            case STARVING     -> new int[]{5, 1};
            // Cooldown / dormant buffs share the "recharging" icon at (col 6, row 1).
            case TELEPORT_COOLDOWN -> new int[]{6, 1};
            case RANGED_COOLDOWN   -> new int[]{6, 1};
            case HIDING            -> new int[]{6, 1};
            // Killer's own slot, sitting to the right of recharging.
            case KILLER            -> new int[]{7, 1};
            default -> new int[]{6, 0};//regeneration as default
        };
    }

    /** Release the shared texture. Safe to call repeatedly; subsequent
     *  {@link #regionFor} calls will reload on demand. */
    public static void disposeShared() {
        if (sheet != null) { sheet.dispose(); sheet = null; }
        cache = null;
    }
}
