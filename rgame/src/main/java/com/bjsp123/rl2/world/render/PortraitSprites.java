package com.bjsp123.rl2.world.render;

import com.badlogic.gdx.graphics.g2d.TextureRegion;
import com.bjsp123.rl2.model.Mob.CharacterClass;

import java.util.EnumMap;
import java.util.Map;

/**
 * Lazy-loaded sprite source for character portraits (HUD avatar, hall of fame,
 * game-over / victory screens). {@code sprites/player.png} carries dedicated
 * large portrait heads per class alongside the pose columns:
 * col 3 = angry, col 4 = happy (sparkles), col 5 = neutral. Each is cropped to
 * the head band and served per (class, expression).
 */
public final class PortraitSprites {

    /** Portrait expression. NEUTRAL is the resting face; ANGRY flashes when
     *  the player takes damage, HAPPY when they pick up an item. */
    public enum Expression { NEUTRAL, ANGRY, HAPPY }

    private static final int COL_ANGRY   = 3;
    private static final int COL_HAPPY   = 4;
    private static final int COL_NEUTRAL = 5;

    private static Map<CharacterClass, Map<Expression, TextureRegion>> regions;

    private PortraitSprites() {}

    /** Neutral portrait for the given class, or {@code null} if the underlying
     *  atlas didn't load (missing asset, headless boot, etc.). */
    public static TextureRegion regionFor(CharacterClass cls) {
        return regionFor(cls, Expression.NEUTRAL);
    }

    /** Portrait for (class, expression), or {@code null} if the atlas didn't load. */
    public static TextureRegion regionFor(CharacterClass cls, Expression expr) {
        if (cls == null) return null;
        if (regions == null) load();
        if (regions == null) return null;
        Map<Expression, TextureRegion> byExpr = regions.get(cls);
        if (byExpr == null) return null;
        TextureRegion r = byExpr.get(expr == null ? Expression.NEUTRAL : expr);
        return r != null ? r : byExpr.get(Expression.NEUTRAL);
    }

    private static void load() {
        Map<CharacterClass, Map<Expression, TextureRegion>> built =
                new EnumMap<>(CharacterClass.class);
        for (CharacterClass cls : CharacterClass.values()) {
            TextureRegion neutral = MobSprites.playerCell(cls, COL_NEUTRAL);
            if (neutral == null) return;   // atlas not loaded yet - retry next call
            Map<Expression, TextureRegion> byExpr = new EnumMap<>(Expression.class);
            byExpr.put(Expression.NEUTRAL, head(neutral));
            byExpr.put(Expression.ANGRY,   head(MobSprites.playerCell(cls, COL_ANGRY)));
            byExpr.put(Expression.HAPPY,   head(MobSprites.playerCell(cls, COL_HAPPY)));
            built.put(cls, byExpr);
        }
        regions = built;
    }

    /** Crop the head band out of a portrait cell: the drawn head spans roughly
     *  pixel rows 28-62 of the 32x64 cell (the top rows are blank padding plus
     *  the happy column's sparkles), so this band frames the face cleanly. */
    private static final int PORTRAIT_Y0     = 28;
    private static final int PORTRAIT_HEIGHT = 34;

    private static TextureRegion head(TextureRegion full) {
        if (full == null) return null;
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
