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
            case BLAZINGSTAR -> new Point(size-1,0);
            case AZURITE     -> new Point(size-1,1);
            case AMBERGLEAM  -> new Point(size-1,2);
            case LETTUSTONE -> new Point(size-1,3);
            case PORQUOISE  -> new Point(size-1,4);
            case HAMETHYST  -> new Point(size-1,5);
            case CUPRIUM    -> new Point(4+size,3);
            case ARGENTEL   -> new Point(4+size,5);
            case AURELIUM   -> new Point(4+size,4);
            case BLOODGLASS -> new Point(4+size,0);
            case SLIPGLASS  -> new Point(4+size,1);
            case JADEGLASS  -> new Point(4+size,2);
            case SCINTILLIUM -> new Point(1,6);
            case GLITTERSHARD -> new Point(7,6);
            case PETRICHOR  -> new Point(5,6);
            case STEELROCK  -> new Point(8,6);
            case MILKSPAR   -> new Point(4,6);
            case MALACHOR   -> new Point(6,6);
            case FLUOROS    -> new Point(0,6);
            case PYRIUM     -> new Point(3,6);
            default   -> new Point(0,0);
        };
       
        if (gemRegions[(int)sq.x()][(int)sq.y()] == null && gemTex != null) {
            gemRegions[(int)sq.x()][(int)sq.y()] = new TextureRegion(gemTex,
                    (int)sq.x() * CELL, SpriteAtlas.gemsY() + (int)sq.y() * CELL, CELL, CELL);
        }

        return gemRegions[(int)sq.x()][(int)sq.y()];
        
    }

    private static void loadGemTexture() {
        if (gemTex == null) {
            SpriteAtlas.load();
            gemTex = SpriteAtlas.texture();
        }
    }

    /** Release cached regions. Texture is owned by {@link SpriteAtlas}. */
    public static void disposeShared() {
        gemTex = null;
        for (int i = 0; i < gemRegions.length; ++i)
            for (int j = 0; j < gemRegions[i].length; ++j)
                gemRegions[i][j] = null;
    }
}
