package com.bjsp123.rl2.world.render;

import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.graphics.g2d.TextureRegion;
import com.bjsp123.rl2.model.Mob.CharacterClass;

import java.util.EnumMap;
import java.util.Map;

/**
 * Lazy-loaded sprite source for the HUD's character portrait. Pulls the head + upper
 * body (top 32x32 cell) of each PLAYER class pose from the same {@code sprites/mobs.png}
 * atlas {@link MobSprites} owns. The atlas Texture itself is borrowed via
 * {@link MobSprites#sheetTexture()} so the file is only ever loaded once.
 *
 * <p>Source layout (row 0 of mobs.png at 32 px per cell):
 * <ul>
 *   <li>col 2 - Rogue</li>
 *   <li>col 3 - Mage</li>
 *   <li>col 4 - Warrior</li>
 * </ul>
 * Each class occupies a 1x2 cell block; the upper cell ({@code y = 0..31}) is the
 * head + shoulders, which is what we extract for the portrait.
 */
public final class PortraitSprites {

    // Source column per class on row 0 - matches MobSprites: rogue=0, warrior=3, mage=4.
    private static final int COL_ROGUE   = 0;
    private static final int COL_MAGE    = 4;
    private static final int COL_WARRIOR = 3;

    private static Map<CharacterClass, TextureRegion> regions;

    private PortraitSprites() {}

    /** Region for the given class, or {@code null} if the underlying atlas didn't
     *  load (missing asset, headless boot, etc.). */
    public static TextureRegion regionFor(CharacterClass cls) {
        if (cls == null) return null;
        if (regions == null) load();
        return regions == null ? null : regions.get(cls);
    }

    private static void load() {
        Texture mobsTex = MobSprites.sheetTexture();
        if (mobsTex == null) return;
        int cell = MobSprites.cellSize();
        regions = new EnumMap<>(CharacterClass.class);
        regions.put(CharacterClass.WARRIOR, head(mobsTex, cell, COL_WARRIOR));
        regions.put(CharacterClass.MAGE,    head(mobsTex, cell, COL_MAGE));
        regions.put(CharacterClass.ROGUE,   head(mobsTex, cell, COL_ROGUE));
    }

    /** Crop the portrait window from the player column. We pull pixel rows 8-40
     *  (a 32-row band) rather than the top 32x32 cell because the player sprite's
     *  silhouette starts a few pixels down inside its cell - the top 8 rows are
     *  blank padding above the head, and rows 8-40 frame the head + chest cleanly
     *  for a HUD avatar. */
    private static final int PORTRAIT_Y0     = 10;
    private static final int PORTRAIT_HEIGHT = 32;

    private static TextureRegion head(Texture mobsTex, int cell, int col) {
        return new TextureRegion(mobsTex, col * cell, PORTRAIT_Y0, cell, PORTRAIT_HEIGHT);
    }

    /** No-op tear-down - the underlying texture is owned by {@link MobSprites};
     *  call {@link MobSprites#disposeShared()} on shutdown if you want to release it. */
    public static void disposeShared() {
        regions = null;
    }
}
