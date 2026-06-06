package com.bjsp123.rl2.world.render;
import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.graphics.g2d.TextureRegion;
import com.bjsp123.rl2.model.GemSpecies;
import com.bjsp123.rl2.model.Item;

import java.util.EnumMap;
import java.util.Map;

/**
 * Gem icons sourced from {@code sprites/gems2.png} (RL-47). {@link #regionFor} maps each
 * species to a fixed {@code (row, col)} cell; the on-floor renderer, inventory popup, action
 * bar, look popup, and encyclopedia all go through this lookup so a gem looks the same
 * wherever it appears. No size system - one cell per species.
 */
public final class GemSprites {

    private static Texture gemTex;
    private static Map<GemSpecies, TextureRegion> cache;

    /** Cell size on {@code sprites/gems2.png} (160x224 = 5 cols x 7 rows). */
    private static final int CELL = 32;

    private GemSprites() {}

    public static TextureRegion regionFor(Item item) {
        if (item == null || !item.isGem() || item.gemSpecies == null) return null;
        return regionFor(item.gemSpecies);
    }

    public static TextureRegion regionFor(GemSpecies species) {
        if (species == null) return null;
        if (cache == null) load();
        return cache.get(species);
    }

    /** (col, row) of each species on gems2.png. */
    private static int col(GemSpecies s) {
        return switch (s) {
            case LETTUSTONE, SILVER, BLOODHIVE -> 0;
            case HAMETHYST,  COPPER, BLACKGLASS -> 1;
            case SALAMITE,   GOLD,   MALACHOR   -> 2;
            case ICELANDSPAR,        FLUORON    -> 3;
        };
    }

    private static int row(GemSpecies s) {
        return switch (s.gemClass) {
            case BASIC  -> 0;
            case METAL  -> 1;
            case EXOTIC -> 4;
        };
    }

    private static void load() {
        cache = new EnumMap<>(GemSpecies.class);
        SpriteAtlas.load();
        gemTex = SpriteAtlas.texture();
        if (gemTex == null) return;
        for (GemSpecies s : GemSpecies.values()) {
            int x = col(s) * CELL;
            int y = SpriteAtlas.gemsY() + row(s) * CELL;
            cache.put(s, new TextureRegion(gemTex, x, y, CELL, CELL));
        }
    }

    /** Release cached regions. Texture is owned by {@link SpriteAtlas}. */
    public static void disposeShared() {
        gemTex = null;
        cache = null;
    }
}
