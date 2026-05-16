package com.bjsp123.rl2.world.render;

import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.graphics.g2d.SpriteBatch;
import com.badlogic.gdx.graphics.g2d.TextureRegion;
import com.bjsp123.rl2.ui.skin.Settings;

/** Draws sprite silhouettes using the generated outline atlas when safe, radial taps otherwise. */
final class OutlineRenderer {
    private static final int OUTLINE_TAPS = 8;
    private static final float[] OUTLINE_DX = new float[OUTLINE_TAPS];
    private static final float[] OUTLINE_DY = new float[OUTLINE_TAPS];

    static {
        for (int i = 0; i < OUTLINE_TAPS; i++) {
            double a = i * Math.PI * 2.0 / OUTLINE_TAPS;
            OUTLINE_DX[i] = (float) Math.cos(a);
            OUTLINE_DY[i] = (float) Math.sin(a);
        }
    }

    private final OutlineAtlas atlas = new OutlineAtlas();

    void register(TextureRegion region) {
        atlas.register(region);
    }

    void register(Texture texture) {
        atlas.register(texture);
    }

    void register(Texture texture, int srcX, int srcY, int srcW, int srcH) {
        atlas.register(texture, srcX, srcY, srcW, srcH);
    }

    void rebuild() {
        atlas.rebuild();
    }

    void ensureCurrent() {
        atlas.ensureCurrent();
    }

    void dispose() {
        atlas.dispose();
    }

    void drawRegion(SpriteBatch batch, TextureRegion region, float x, float y, float w, float h) {
        float width = Settings.mobOutlineWidth();
        float alpha = Settings.mobOutlineDarkness();
        if (width <= 0f || alpha <= 0f || region == null) return;
        if (drawAtlas(batch, region, x, y, w, h, 0f, 0f, 0f, alpha)) return;
        drawRegionTaps(batch, region, x, y, w, h, width, 0f, 0f, 0f, alpha);
    }

    void drawRegionTinted(SpriteBatch batch, TextureRegion region, float x, float y, float w, float h,
                          float r, float g, float b, float strength) {
        float width = Settings.mobOutlineWidth();
        float alpha = Settings.mobOutlineDarkness();
        if (width <= 0f || alpha <= 0f || region == null) return;
        float tr = r * strength;
        float tg = g * strength;
        float tb = b * strength;
        if (drawAtlas(batch, region, x, y, w, h, tr, tg, tb, alpha)) return;
        drawRegionTaps(batch, region, x, y, w, h, width, tr, tg, tb, alpha);
    }

    void drawRegionSrc(SpriteBatch batch, TextureRegion region, float x, float y, float w, float h,
                       int srcX, int srcY, int srcW, int srcH) {
        float width = Settings.mobOutlineWidth();
        float alpha = Settings.mobOutlineDarkness();
        if (width <= 0f || alpha <= 0f || region == null) return;
        Texture texture = region.getTexture();
        int absX = region.getRegionX() + srcX;
        int absY = region.getRegionY() + srcY;
        if (drawAtlas(batch, texture, absX, absY, srcW, srcH, x, y, w, h, 0f, 0f, 0f, alpha)) return;
        drawSourceTaps(batch, texture, absX, absY, srcW, srcH, x, y, w, h, width, 0f, 0f, 0f, alpha);
    }

    void drawMob(SpriteBatch batch, TextureRegion region, float x, float y, float w, float h,
                 float r, float g, float b, float alpha) {
        float width = Settings.mobOutlineWidth();
        if (width <= 0f || alpha <= 0f || region == null) return;
        // Mob facings must use the same outline path in both directions. The atlas path is
        // source-orientation only for now, so use radial taps here to keep edges consistent.
        drawRegionTaps(batch, region, x, y, w, h, width, r, g, b, alpha);
    }

    private void drawRegionTaps(SpriteBatch batch, TextureRegion region,
                                float x, float y, float w, float h, float width,
                                float r, float g, float b, float a) {
        batch.setColor(r, g, b, a);
        for (int i = 0; i < OUTLINE_TAPS; i++) {
            batch.draw(region, x + OUTLINE_DX[i] * width, y + OUTLINE_DY[i] * width, w, h);
        }
        batch.setColor(Color.WHITE);
    }

    private void drawSourceTaps(SpriteBatch batch, Texture texture, int srcX, int srcY, int srcW, int srcH,
                                float x, float y, float w, float h, float width,
                                float r, float g, float b, float a) {
        batch.setColor(r, g, b, a);
        for (int i = 0; i < OUTLINE_TAPS; i++) {
            batch.draw(texture,
                    x + OUTLINE_DX[i] * width, y + OUTLINE_DY[i] * width,
                    w, h, srcX, srcY, srcW, srcH, false, false);
        }
        batch.setColor(Color.WHITE);
    }

    private boolean drawAtlas(SpriteBatch batch, TextureRegion src, float x, float y, float w, float h,
                              float r, float g, float b, float a) {
        // Atlas frames are generated in source orientation. Mirrored draws fall back to taps so
        // the outline and sprite share exactly the same transform.
        if (src.isFlipX() || src.isFlipY() || w < 0f || h < 0f) return false;
        OutlineAtlas.Frame frame = atlas.frame(src);
        if (frame == null) return false;
        drawAtlasFrame(batch, frame, x, y, w, h, r, g, b, a);
        return true;
    }

    private boolean drawAtlas(SpriteBatch batch, Texture texture, int srcX, int srcY, int srcW, int srcH,
                              float x, float y, float w, float h,
                              float r, float g, float b, float a) {
        if (w < 0f || h < 0f) return false;
        OutlineAtlas.Frame frame = atlas.frame(texture, srcX, srcY, srcW, srcH);
        if (frame == null) return false;
        drawAtlasFrame(batch, frame, x, y, w, h, r, g, b, a);
        return true;
    }

    private void drawAtlasFrame(SpriteBatch batch, OutlineAtlas.Frame frame, float x, float y, float w, float h,
                                float r, float g, float b, float a) {
        float scaleX = Math.abs(w) / Math.max(1f, frame.sourceW);
        float scaleY = Math.abs(h) / Math.max(1f, frame.sourceH);
        float padX = frame.padX * scaleX;
        float padY = frame.padY * scaleY;
        float dx = Math.min(x, x + w) - padX;
        float dy = Math.min(y, y + h) - padY;
        float dw = Math.abs(w) + padX * 2f;
        float dh = Math.abs(h) + padY * 2f;
        batch.setColor(r, g, b, a);
        batch.draw(frame.region, dx, dy, dw, dh);
        batch.setColor(Color.WHITE);
    }
}
