package com.bjsp123.rl2.world.render;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.graphics.g2d.TextureRegion;
import com.bjsp123.rl2.model.Mob;
import com.bjsp123.rl2.model.Mob.CharacterClass;
import com.bjsp123.rl2.model.Mob.MobType;

import java.util.EnumMap;
import java.util.Map;

/**
 * Lazy-loaded sprite source for the look panel's mob portrait. Mirrors the cell layout
 * of {@link DefaultLevelRenderer}'s in-world mob renderer (see the {@code MOB_CELL_*}
 * constants) but exposes a simple {@link #regionFor(Mob)} lookup so UI screens can
 * paint a mob's full body sprite without reaching into the renderer's private state.
 *
 * <p>Source layout: {@code sprites/mobs.png} on a 32-px grid. Most mobs occupy a 1×1
 * or 1×2 cell block; a handful (blobs, ant hills) are 2×2. The returned region is the
 * full source rect at native pixel size — callers scale to their target footprint.
 */
public final class MobSprites {

    private static final String MOBS_PATH = "sprites/mobs.png";
    private static final int CELL = 32;

    private static Texture mobsTex;
    private static Map<MobType, TextureRegion> regions;
    private static Map<CharacterClass, TextureRegion> playerRegions;

    private MobSprites() {}

    /** Region for the given mob (handles {@code PLAYER} via {@link Mob#characterClass}).
     *  Returns {@code null} if the texture didn't load or the mob's type has no mapping. */
    public static TextureRegion regionFor(Mob mob) {
        if (mob == null || mob.mobType == null) return null;
        if (regions == null) load();
        if (regions == null) return null;
        if (mob.mobType == MobType.PLAYER) {
            return mob.characterClass != null ? playerRegions.get(mob.characterClass) : null;
        }
        return regions.get(mob.mobType);
    }

    /** Source-of-truth atlas Texture. {@code DefaultLevelRenderer} and the HUD
     *  portrait helper share this same instance — there's no benefit to loading the
     *  ~150 KB sheet several times. Returns {@code null} if the asset didn't load. */
    public static Texture sheetTexture() {
        if (mobsTex == null) load();
        return mobsTex;
    }

    /** Source-pixel cell pitch on the atlas. Public so consumers building their
     *  own regions stay in sync with the layout this class assumes. */
    public static int cellSize() { return CELL; }

    private static void load() {
        try {
            mobsTex = new Texture(Gdx.files.internal(MOBS_PATH));
            mobsTex.setFilter(Texture.TextureFilter.Nearest, Texture.TextureFilter.Nearest);
        } catch (Exception ignored) {
            return;
        }
        regions = new EnumMap<>(MobType.class);
        playerRegions = new EnumMap<>(CharacterClass.class);

        // Cell coordinates mirror DefaultLevelRenderer's MOB_CELL_* constants.
        put(MobType.SPIDER,             0, 2, 1, 1);
        put(MobType.LOATHESOME_BUG,     1, 2, 1, 1);
        put(MobType.BAT,                2, 2, 1, 1);
        put(MobType.MOUSE,              3, 2, 1, 1);
        put(MobType.SOLDIER_BUG,        4, 2, 1, 1);
        put(MobType.BUG_PRODIGY,        5, 2, 1, 1);
        put(MobType.DOG,                7, 2, 1, 1);
        put(MobType.CAT,                6, 2, 1, 1);
        put(MobType.KITTEN,             1, 3, 1, 1);
        put(MobType.KOBOLD_FIGHTER,     0, 3, 1, 1);
        put(MobType.BLACK_ANT,          2, 3, 1, 1);
        put(MobType.RED_ANT,            3, 3, 1, 1);
        put(MobType.BLOB,               2, 4, 2, 2);
        put(MobType.KISSYBLOB,          0, 4, 2, 2);
        put(MobType.BLACK_ANT_HILL,     4, 4, 2, 2);
        put(MobType.RED_ANT_HILL,       6, 4, 2, 2);
        put(MobType.MASK_IMP,           0, 7, 1, 1);
        put(MobType.LARGE_MASK_IMP,     1, 7, 1, 1);
        put(MobType.DEVELOPED_MASK_IMP, 2, 6, 1, 2);
        put(MobType.HORRIBLE_MASK_IMP,  3, 6, 1, 2);
        put(MobType.GHOST,              7, 0, 1, 2);
        put(MobType.HORROR,             4, 6, 1, 2);
        put(MobType.BARBARIAN_PRINCESS, 1, 8, 1, 2);
        // Blazing firemouse rides the regular mouse art — same silhouette, fire VFX
        // in the world distinguishes it. Look panel shows the mouse sprite.
        put(MobType.BLAZING_FIREMOUSE,  3, 2, 1, 1);

        playerRegions.put(CharacterClass.WARRIOR, mobRegion(4, 0, 1, 2));
        playerRegions.put(CharacterClass.MAGE,    mobRegion(3, 0, 1, 2));
        playerRegions.put(CharacterClass.ROGUE,   mobRegion(2, 0, 1, 2));
    }

    private static void put(MobType type, int col, int row, int w, int h) {
        regions.put(type, mobRegion(col, row, w, h));
    }

    private static TextureRegion mobRegion(int col, int row, int w, int h) {
        return new TextureRegion(mobsTex, col * CELL, row * CELL, w * CELL, h * CELL);
    }

    /** Release the shared texture. Subsequent {@link #regionFor} calls will reload. */
    public static void disposeShared() {
        if (mobsTex != null) { mobsTex.dispose(); mobsTex = null; }
        regions = null;
        playerRegions = null;
    }
}
