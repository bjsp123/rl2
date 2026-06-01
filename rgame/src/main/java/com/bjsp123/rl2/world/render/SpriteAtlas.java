package com.bjsp123.rl2.world.render;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.graphics.Pixmap;
import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.graphics.g2d.TextureRegion;
import com.bjsp123.rl2.model.Level.VisualTheme;

/**
 * Packs all Nearest-filtered sprite sheets into a single Texture, eliminating
 * SpriteBatch texture-switch flushes between tiles, mobs, items, gems, buffs, surfaces,
 * and contact-shadow ovals.
 * CSV sprite coordinates are unchanged — each sprite class adds its section's Y offset.
 *
 * <p>Layout (top to bottom):
 * mobs → items → gems → buffs → terrain_crystal → terrain_concrete → terrain_shiny
 * → surfaces → shadow_oval → lamp_shadow_oval
 *
 * <p>Not included: liquid textures (Linear+Repeat, shader scroll), mask textures
 * (bound individually), fire sheets (returned as whole Texture by FxRenderer), iceTex,
 * utility-atlas gradient strips (Linear filter required for smooth interpolation).
 */
final class SpriteAtlas {

    // Shadow oval dimensions and peak alpha — must match DefaultLevelRenderer constants.
    static final int   SHADOW_W     = 32,  SHADOW_H     = 8;
    static final int   LAMP_W       = 40,  LAMP_H       = 10;
    private static final float SHADOW_ALPHA = 0.45f;
    private static final float LAMP_ALPHA   = 0.80f;

    private static Texture atlas;
    private static int mobsOffsetY, itemsOffsetY, gemsOffsetY, buffsOffsetY;
    private static int terrainCrystalOffsetY, terrainConcreteOffsetY, terrainShinyOffsetY, terrainGothicOffsetY;
    private static int surfacesOffsetY, shadowOvalOffsetY, lampShadowOvalOffsetY;
    private static int playerOffsetY;

    private SpriteAtlas() {}

    static void load() {
        if (atlas != null) return;
        try {
            Pixmap mobs     = new Pixmap(Gdx.files.internal("sprites/mobs_simple.png"));
            Pixmap player   = new Pixmap(Gdx.files.internal("sprites/player.png"));
            Pixmap items    = new Pixmap(Gdx.files.internal("sprites/items.png"));
            Pixmap gems     = new Pixmap(Gdx.files.internal("sprites/gems.png"));
            Pixmap buffs    = new Pixmap(Gdx.files.internal("sprites/buffs16.png"));
            Pixmap crystal  = new Pixmap(Gdx.files.internal("sprites/terrain_crystal.png"));
            Pixmap concrete = new Pixmap(Gdx.files.internal("sprites/terrain_concrete.png"));
            Pixmap shiny    = new Pixmap(Gdx.files.internal("sprites/terrain_shiny.png"));
            Pixmap gothic   = new Pixmap(Gdx.files.internal("sprites/terrain_gothic.png"));
            Pixmap surfaces = new Pixmap(Gdx.files.internal("sprites/surfaces.png"));
            Pixmap shadow   = buildOval(SHADOW_W, SHADOW_H, SHADOW_ALPHA);
            Pixmap lamp     = buildOval(LAMP_W,   LAMP_H,   LAMP_ALPHA);

            int w = Math.max(
                    Math.max(Math.max(mobs.getWidth(),  items.getWidth()),
                             Math.max(gems.getWidth(),  buffs.getWidth())),
                    Math.max(Math.max(crystal.getWidth(), concrete.getWidth()),
                             Math.max(Math.max(shiny.getWidth(), gothic.getWidth()),
                                      Math.max(surfaces.getWidth(), player.getWidth()))));

            mobsOffsetY            = 0;
            playerOffsetY          = mobsOffsetY            + mobs.getHeight();
            itemsOffsetY           = playerOffsetY          + player.getHeight();
            gemsOffsetY            = itemsOffsetY           + items.getHeight();
            buffsOffsetY           = gemsOffsetY            + gems.getHeight();
            terrainCrystalOffsetY  = buffsOffsetY           + buffs.getHeight();
            terrainConcreteOffsetY = terrainCrystalOffsetY  + crystal.getHeight();
            terrainShinyOffsetY    = terrainConcreteOffsetY + concrete.getHeight();
            terrainGothicOffsetY   = terrainShinyOffsetY    + shiny.getHeight();
            surfacesOffsetY        = terrainGothicOffsetY   + gothic.getHeight();
            shadowOvalOffsetY      = surfacesOffsetY        + surfaces.getHeight();
            lampShadowOvalOffsetY  = shadowOvalOffsetY      + SHADOW_H;
            int totalH             = lampShadowOvalOffsetY  + LAMP_H;

            Pixmap combined = new Pixmap(nextPow2(w), nextPow2(totalH), Pixmap.Format.RGBA8888);
            combined.setBlending(Pixmap.Blending.None);

            combined.drawPixmap(mobs,     0, mobsOffsetY);
            combined.drawPixmap(player,   0, playerOffsetY);
            combined.drawPixmap(items,    0, itemsOffsetY);
            combined.drawPixmap(gems,     0, gemsOffsetY);
            combined.drawPixmap(buffs,    0, buffsOffsetY);
            combined.drawPixmap(crystal,  0, terrainCrystalOffsetY);
            combined.drawPixmap(concrete, 0, terrainConcreteOffsetY);
            combined.drawPixmap(shiny,    0, terrainShinyOffsetY);
            combined.drawPixmap(gothic,   0, terrainGothicOffsetY);
            combined.drawPixmap(surfaces, 0, surfacesOffsetY);
            combined.drawPixmap(shadow,   0, shadowOvalOffsetY);
            combined.drawPixmap(lamp,     0, lampShadowOvalOffsetY);

            mobs.dispose();   player.dispose();   items.dispose();
            gems.dispose();   buffs.dispose();
            crystal.dispose();concrete.dispose();
            shiny.dispose();  gothic.dispose();
            surfaces.dispose();
            shadow.dispose(); lamp.dispose();

            atlas = new Texture(combined);
            atlas.setFilter(Texture.TextureFilter.Nearest, Texture.TextureFilter.Nearest);
            combined.dispose();
        } catch (Exception ignored) {
            atlas = null;
        }
    }

    static Texture       texture()          { return atlas;                   }
    static int           mobsY()            { return mobsOffsetY;             }
    static int           playerY()          { return playerOffsetY;           }
    static int           itemsY()           { return itemsOffsetY;            }
    static int           gemsY()            { return gemsOffsetY;             }
    static int           buffsY()           { return buffsOffsetY;            }
    static int           surfacesY()        { return surfacesOffsetY;         }

    static int terrainY(VisualTheme theme) {
        if (theme == null) return terrainCrystalOffsetY;
        return switch (theme) {
            case CRYSTAL  -> terrainCrystalOffsetY;
            case CONCRETE -> terrainConcreteOffsetY;
            case SHINY    -> terrainShinyOffsetY;
            case GOTHIC   -> terrainGothicOffsetY;
        };
    }

    static TextureRegion shadowOvalRegion() {
        if (atlas == null) return null;
        return new TextureRegion(atlas, 0, shadowOvalOffsetY, SHADOW_W, SHADOW_H);
    }

    static TextureRegion lampShadowOvalRegion() {
        if (atlas == null) return null;
        return new TextureRegion(atlas, 0, lampShadowOvalOffsetY, LAMP_W, LAMP_H);
    }

    static TextureRegion maskRegion(int variant) {
        if (atlas == null) return null;
        int x = variant * SurfaceSprites.MASK_TILE;
        int y = surfacesOffsetY + 3 * SurfaceSprites.LIQUID_TILE; // MASK_STRIP_Y = 192
        return new TextureRegion(atlas, x, y, SurfaceSprites.MASK_TILE, SurfaceSprites.MASK_TILE);
    }

    static void dispose() {
        if (atlas != null) { atlas.dispose(); atlas = null; }
        mobsOffsetY = playerOffsetY = itemsOffsetY = gemsOffsetY = buffsOffsetY = 0;
        terrainCrystalOffsetY = terrainConcreteOffsetY = terrainShinyOffsetY = 0;
        surfacesOffsetY = shadowOvalOffsetY = lampShadowOvalOffsetY = 0;
    }

    private static Pixmap buildOval(int w, int h, float peakAlpha) {
        Pixmap pm = new Pixmap(w, h, Pixmap.Format.RGBA8888);
        pm.setBlending(Pixmap.Blending.None);
        float cx = (w - 1) / 2f, cy = (h - 1) / 2f;
        float rx = w / 2f,       ry = h / 2f;
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                float dx = (x - cx) / rx, dy = (y - cy) / ry;
                int a = Math.round(Math.max(0f, 1f - dx * dx - dy * dy) * peakAlpha * 255f);
                if (a > 0) pm.drawPixel(x, y, 0xffffff00 | a);
            }
        }
        return pm;
    }

    private static int nextPow2(int v) {
        int p = 1;
        while (p < v) p <<= 1;
        return p;
    }
}
