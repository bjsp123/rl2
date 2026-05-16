 package com.bjsp123.rl2.world.render;

import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.graphics.g2d.TextureRegion;
import com.bjsp123.rl2.model.Mob;
import com.bjsp123.rl2.model.Mob.CharacterClass;
import com.bjsp123.rl2.util.CsvTable;

import java.util.EnumMap;
import java.util.HashMap;
import java.util.Map;

/**
 * Single source of truth for {@code sprites/mobs_simple.png}. Mob -> atlas
 * region mappings are loaded from the sprite columns of {@code assets/data/mobs.csv}
 * via {@link #loadFromCsv(String)} (called once at startup from
 * {@code Rl2Game.create()}). The texture itself is loaded lazily on first
 * {@link #regionFor} call.
 *
 * <p>Source layout: 32-px grid. Most mobs occupy a 1x1 or 1x2 cell block; a handful
 * (blobs, ant hills) are 2x2. Returned regions are the full source rects at native
 * pixel size - callers scale to their target footprint.
 */
public final class MobSprites {

    private static final int CELL = 32;

    private static Texture mobsTex;
    private static final Map<String, int[]> coords = new HashMap<>();
    private static final Map<String, TextureRegion> regions = new HashMap<>();
    private static Map<CharacterClass, TextureRegion> playerRegions;

    private MobSprites() {}

    /** Populate the mob -> atlas-coord map from the sprite columns of
     *  {@code mobs.csv}. Called once at startup, before any {@link #regionFor}
     *  call. Reads only the {@code type, spriteCol, spriteRow, spriteW,
     *  spriteH} columns; gameplay columns are ignored. */
    public static void loadFromCsv(String csv) {
        coords.clear();
        regions.clear();
        if (csv == null || csv.isEmpty()) return;
        CsvTable table = CsvTable.parse(csv);
        for (Map<String, String> row : table.rows) {
            String type = CsvTable.str(row, "type", null);
            if (type == null) continue;
            int col = CsvTable.intCell(row, "spriteCol", 0);
            int rw  = CsvTable.intCell(row, "spriteRow", 0);
            int w   = CsvTable.intCell(row, "spriteW", 1);
            int h   = CsvTable.intCell(row, "spriteH", 1);
            coords.put(type, new int[] {col, rw, w, h});
        }
    }

    /** Region for the given mob - players (any {@link Mob#characterClass}) get the
     *  per-class sprite; everything else looks up its row in {@code mobs.csv}.
     *  Returns {@code null} if the texture didn't load or the mob's type has no mapping. */
    public static TextureRegion regionFor(Mob mob) {
        if (mob == null) return null;
        if (mob.characterClass != null) return regionFor(mob.characterClass);
        return regionFor(mob.mobType);
    }

    /** Region for a non-player mob type, or {@code null} if the type has no mapping
     *  or the texture didn't load. Player rows are skipped - use the
     *  {@link #regionFor(CharacterClass)} overload instead. */
    public static TextureRegion regionFor(String type) {
        if (type == null) return null;
        ensureLoaded();
        if (mobsTex == null) return null;
        TextureRegion cached = regions.get(type);
        if (cached != null) return cached;
        int[] c = coords.get(type);
        if (c == null) return null;
        TextureRegion r = mobRegion(c[0], c[1], c[2], c[3]);
        regions.put(type, r);
        return r;
    }

    /** Region for a player class pose. */
    public static TextureRegion regionFor(CharacterClass cls) {
        if (cls == null) return null;
        ensureLoaded();
        return playerRegions == null ? null : playerRegions.get(cls);
    }

    /** Source-of-truth atlas Texture. {@code DefaultLevelRenderer} and the HUD
     *  portrait helper share this same instance - there's no benefit to loading the
     *  ~150 KB sheet several times. Returns {@code null} if the asset didn't load. */
    public static Texture sheetTexture() {
        ensureLoaded();
        return mobsTex;
    }

    /** Source-pixel cell pitch on the atlas. Public so consumers building their
     *  own regions stay in sync with the layout this class assumes. */
    public static int cellSize() { return CELL; }

    private static void ensureLoaded() {
        if (mobsTex != null) return;
        SpriteAtlas.load();
        mobsTex = SpriteAtlas.texture();
        if (mobsTex == null) return;
        playerRegions = new EnumMap<>(CharacterClass.class);
        playerRegions.put(CharacterClass.WARRIOR, mobRegion(3, 0, 1, 2));
        playerRegions.put(CharacterClass.MAGE,    mobRegion(4, 0, 1, 2));
        playerRegions.put(CharacterClass.ROGUE,   mobRegion(0, 0, 1, 2));
    }

    private static TextureRegion mobRegion(int col, int row, int w, int h) {
        return new TextureRegion(mobsTex, col * CELL, SpriteAtlas.mobsY() + row * CELL, w * CELL, h * CELL);
    }

    /** Release cached regions. Texture is owned by {@link SpriteAtlas}; call
     *  {@link SpriteAtlas#dispose()} to release it. */
    public static void disposeShared() {
        mobsTex = null;
        regions.clear();
        playerRegions = null;
    }
}
