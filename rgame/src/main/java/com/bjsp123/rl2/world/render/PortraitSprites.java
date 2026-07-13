package com.bjsp123.rl2.world.render;

import com.badlogic.gdx.graphics.g2d.TextureRegion;
import com.bjsp123.rl2.model.Mob.CharacterClass;

import java.util.EnumMap;
import java.util.Map;

/**
 * Lazy-loaded sprite source for character portraits (HUD avatar, hall of fame,
 * game-over / victory screens). Crops the head + shoulders band out of each
 * class's real {@code sprites/player.png} pose, borrowed via
 * {@link MobSprites#regionFor(CharacterClass)} so the portrait always tracks
 * wherever the player art actually lives.
 */
public final class PortraitSprites {

    private static Map<CharacterClass, TextureRegion> regions;

    private PortraitSprites() {}

    /** Region for the given class, or {@code null} if the underlying atlas didn't
     *  load (missing asset, headless boot, etc.). */
    public static TextureRegion regionFor(CharacterClass cls) {
        if (cls == null) return null;
        if (regions == null) load();
        return regions == null ? null : regions.get(cls);
    }

    /** Crop each class's portrait band out of its {@code player.png} pose via
     *  {@link MobSprites#regionFor(CharacterClass)} - the single source of
     *  truth for where player art lives. (This used to point at legacy cells
     *  on the mobs sheet, which went blank when player art moved to
     *  player.png - portraits silently vanished.) */
    private static void load() {
        Map<CharacterClass, TextureRegion> built = new EnumMap<>(CharacterClass.class);
        for (CharacterClass cls : CharacterClass.values()) {
            TextureRegion full = MobSprites.regionFor(cls);
            if (full == null) return;   // atlas not loaded yet - retry next call
            built.put(cls, head(full));
        }
        regions = built;
    }

    /** Crop the portrait window from the class pose. We pull pixel rows 10-42
     *  (a 32-row band) rather than the top of the 32x64 cell because the
     *  sprite's silhouette starts a few pixels down inside its cell - the top
     *  rows are blank padding above the head, and rows 10-42 frame the head +
     *  chest cleanly for an avatar. */
    private static final int PORTRAIT_Y0     = 10;
    private static final int PORTRAIT_HEIGHT = 32;

    private static TextureRegion head(TextureRegion full) {
        int h = Math.min(PORTRAIT_HEIGHT, full.getRegionHeight() - PORTRAIT_Y0);
        return new TextureRegion(full.getTexture(),
                full.getRegionX(), full.getRegionY() + PORTRAIT_Y0,
                full.getRegionWidth(), Math.max(1, h));
    }

    /** No-op tear-down - the underlying texture is owned by {@link MobSprites};
     *  call {@link MobSprites#disposeShared()} on shutdown if you want to release it. */
    public static void disposeShared() {
        regions = null;
    }
}
