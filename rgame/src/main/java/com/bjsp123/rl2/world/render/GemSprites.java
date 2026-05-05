package com.bjsp123.rl2.world.render;
import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.graphics.g2d.TextureRegion;
import com.bjsp123.rl2.model.GemSpecies;
import com.bjsp123.rl2.model.Item;
import com.bjsp123.rl2.model.Point;

/**
 * Gem icons sourced from {@code sprites/gems.png}. {@link #regionFor} maps each
 * (species, size) to a fixed cell on the sheet; the on-floor renderer, inventory popup,
 * action bar, and crafting cells all go through this lookup so a gem looks the same
 * wherever it appears.
 */
public final class GemSprites {

    private static Texture gemTex;
    /** [col][row] cache of extracted regions; cols always first. */
    private static final TextureRegion[][] gemRegions = new TextureRegion[10][10];

    /** Cell size on {@code sprites/gems.png}. */
    private static final int CELL = 32;

    private GemSprites() {}

    public static TextureRegion regionFor(Item item) {
        if (item == null || !item.isGem() || item.gemSpecies == null) return null;
        return regionFor(item.gemSpecies, Math.max(1, item.gemSize));
    };

    public static TextureRegion regionFor(GemSpecies species, int size) {
        loadGemTexture();
        Point sq = switch (species) {
            case BLAZINGSTAR -> new Point(size-1,1);
            case AZURITE     -> new Point(size-1,2);
            case AMBERGLEAM  -> new Point(size-1,3);
            case CUPRIUM    -> new Point(4+size,4);
            case ARGENTEL     -> new Point(4+size,5);
            case AURELIUM  -> new Point(4+size,6);
            case BLOODGLASS    -> new Point(4+size,4);
            case SLIPGLASS     -> new Point(4+size,5);
            case JADEGLASS  -> new Point(4+size,6);
            case SCINTILLIUM -> new Point(1,6);
            case GLITTERSHARD -> new Point(7,6);
            case PETRICHOR   -> new Point(5,6);
            case STEELROCK   -> new Point(8,6);
            case MILKSPAR   -> new Point(4,6);
            case MALACHOR   -> new Point(6,6);
            default   -> new Point(0,0);
        };
       
        if (gemRegions[(int)sq.x()][(int)sq.y()] == null && gemTex != null) {
            gemRegions[(int)sq.x()][(int)sq.y()] = new TextureRegion(gemTex, (int)sq.x() * CELL, (int)sq.y() * CELL, CELL, CELL);
        }

        return gemRegions[(int)sq.x()][(int)sq.y()];
        
    }

    private static void loadGemTexture() {
        if (gemTex == null) {
            try {
                gemTex = new Texture(com.badlogic.gdx.Gdx.files.internal("sprites/gems.png"));
                gemTex.setFilter(Texture.TextureFilter.Nearest, Texture.TextureFilter.Nearest);
            } catch (Exception ignored) {
                gemTex = null;
            }
        }
    }

    /** Release the gem sheet. Call on shutdown if you care about clean tear-down; the
     *  next {@link #regionFor} reloads it. */
    public static void disposeShared() {
        if (gemTex != null) {
            gemTex.dispose();
            gemTex = null;
        }
        for (int i = 0; i < gemRegions.length; ++i)
            for (int j = 0; j < gemRegions[i].length; ++j)
                gemRegions[i][j] = null;
    }
}
