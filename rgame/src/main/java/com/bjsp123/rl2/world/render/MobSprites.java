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
    /** Cell dimensions for {@code sprites/player.png}: 32 wide, 64 tall.
     *  Row = class (0=ROGUE, 1=WARRIOR, 2=MAGE). Col = variant
     *  (0=regular player, 1=enemy player, 2=ghost player). */
    private static final int PLAYER_CELL_W = 32;
    private static final int PLAYER_CELL_H = 64;

    /** Column index in {@code sprites/player.png} used for enemy-player
     *  mobs while the dedicated "enemy" art (col 1) is unfinished. Set to
     *  {@code 2} (ghost variant) so enemy players read as visibly distinct
     *  from the real player. */
    private static final int ENEMY_PLAYER_VARIANT_COL = 2;

    private static Texture mobsTex;
    private static final Map<String, int[]> coords = new HashMap<>();
    private static final Map<String, TextureRegion> regions = new HashMap<>();
    /** Per-class regions for real player mobs. Lazily built in {@link #ensureLoaded}. */
    private static Map<CharacterClass, TextureRegion> playerRegions;
    /** Per-class regions for enemy-player mobs (ghost variant for now). */
    private static Map<CharacterClass, TextureRegion> enemyPlayerRegions;

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
     *  per-class sprite; enemy-player mobs ({@code mobType} prefix
     *  {@code ENEMY_PLAYER_}) use the same per-class atlas but with the
     *  "ghost" variant column. Everything else looks up its row in
     *  {@code mobs.csv}. Returns {@code null} if the texture didn't load
     *  or the mob's type has no mapping. */
    public static TextureRegion regionFor(Mob mob) {
        if (mob == null) return null;
        if (mob.characterClass != null) {
            if (mob.mobType != null && mob.mobType.startsWith("ENEMY_PLAYER_")) {
                ensureLoaded();
                return enemyPlayerRegions == null
                        ? null : enemyPlayerRegions.get(mob.characterClass);
            }
            return regionFor(mob.characterClass);
        }
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

    /** Region for an enemy-player class pose. Uses the ghost-variant column
     *  in {@code sprites/player.png} so the silhouette still reads as a
     *  member of that class but visibly differs from the real player. */
    public static TextureRegion enemyPlayerRegion(CharacterClass cls) {
        if (cls == null) return null;
        ensureLoaded();
        return enemyPlayerRegions == null ? null : enemyPlayerRegions.get(cls);
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
        // Player + enemy-player atlas: sprites/player.png. Row = class
        // (0=ROGUE, 1=WARRIOR, 2=MAGE); col = variant
        // (0=regular, 1=enemy art, 2=ghost). Real players use col 0;
        // enemy players use the ghost col for now.
        playerRegions = new EnumMap<>(CharacterClass.class);
        playerRegions.put(CharacterClass.ROGUE,   playerRegion(0, 0));
        playerRegions.put(CharacterClass.WARRIOR, playerRegion(1, 0));
        playerRegions.put(CharacterClass.MAGE,    playerRegion(2, 0));
        enemyPlayerRegions = new EnumMap<>(CharacterClass.class);
        enemyPlayerRegions.put(CharacterClass.ROGUE,
                playerRegion(0, ENEMY_PLAYER_VARIANT_COL));
        enemyPlayerRegions.put(CharacterClass.WARRIOR,
                playerRegion(1, ENEMY_PLAYER_VARIANT_COL));
        enemyPlayerRegions.put(CharacterClass.MAGE,
                playerRegion(2, ENEMY_PLAYER_VARIANT_COL));
    }

    private static TextureRegion mobRegion(int col, int row, int w, int h) {
        return new TextureRegion(mobsTex, col * CELL, SpriteAtlas.mobsY() + row * CELL, w * CELL, h * CELL);
    }

    /** Build a region pointing at one cell of {@code sprites/player.png}.
     *  {@code classRow} 0..2 = ROGUE / WARRIOR / MAGE; {@code variantCol}
     *  0..2 = regular / enemy / ghost. Cell pitch is
     *  {@value #PLAYER_CELL_W} x {@value #PLAYER_CELL_H} px. */
    private static TextureRegion playerRegion(int classRow, int variantCol) {
        return new TextureRegion(mobsTex,
                variantCol * PLAYER_CELL_W,
                SpriteAtlas.playerY() + classRow * PLAYER_CELL_H,
                PLAYER_CELL_W, PLAYER_CELL_H);
    }

    /** Release cached regions. Texture is owned by {@link SpriteAtlas}; call
     *  {@link SpriteAtlas#dispose()} to release it. */
    public static void disposeShared() {
        mobsTex = null;
        regions.clear();
        playerRegions = null;
        enemyPlayerRegions = null;
    }
}
