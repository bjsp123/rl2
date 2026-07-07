package com.bjsp123.rl2.world.render;

import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.graphics.g2d.SpriteBatch;
import com.badlogic.gdx.graphics.g2d.TextureRegion;
import com.bjsp123.rl2.ui.skin.Settings;

/** Draws sprite silhouettes using the generated outline atlas when safe, radial taps otherwise. */
public final class OutlineRenderer {
    // Pre-baking disabled: bake time at startup was unacceptably long.
    // All draw paths fall through to the 8-tap fallback. Re-enable once
    // baking is moved to a background thread or incremental build.
    private static boolean ATLAS_ENABLED = false;
    // Set to false to suppress all outline draws (profiling / A-B testing).
    static boolean DRAW_OUTLINES = true;
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
        if (ATLAS_ENABLED) atlas.rebuild();
    }

    void ensureCurrent() {
        if (ATLAS_ENABLED) atlas.ensureCurrent();
    }

    void dispose() {
        atlas.dispose();
    }

    /** Single gate for every world outline draw. Outlines are pure legibility
     *  polish, and each one interleaves the outline atlas with the sprite's own
     *  atlas - i.e. up to two extra texture-switch flushes PER SPRITE. Profiling
     *  showed 100-170 batch flushes/frame with outlines the largest contributor;
     *  on WebGL each flush is an expensive JS-boundary GL call, so Fast graphics
     *  drops them wholesale. */
    private static boolean outlinesOff() {
        return !DRAW_OUTLINES || Settings.fastGraphics();
    }

    void drawRegion(SpriteBatch batch, TextureRegion region, float x, float y, float w, float h) {
        if (outlinesOff()) return;
        float width = Settings.mobOutlineWidth();
        float alpha = Settings.mobOutlineDarkness();
        if (width <= 0f || alpha <= 0f || region == null) return;
        if (drawAtlas(batch, region, x, y, w, h, 0f, 0f, 0f, alpha)) return;
        drawTaps(batch, region, x, y, w, h, width, 0f, 0f, 0f, alpha);
    }

    void drawRegionTinted(SpriteBatch batch, TextureRegion region, float x, float y, float w, float h,
                          float r, float g, float b, float strength) {
        if (outlinesOff()) return;
        float width = Settings.mobOutlineWidth();
        float alpha = Settings.mobOutlineDarkness();
        if (width <= 0f || alpha <= 0f || region == null) return;
        float tr = r * strength;
        float tg = g * strength;
        float tb = b * strength;
        if (drawAtlas(batch, region, x, y, w, h, tr, tg, tb, alpha)) return;
        drawTaps(batch, region, x, y, w, h, width, tr, tg, tb, alpha);
    }

    void drawRegionSrc(SpriteBatch batch, TextureRegion region, float x, float y, float w, float h,
                       int srcX, int srcY, int srcW, int srcH) {
        if (outlinesOff()) return;
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
        if (outlinesOff()) return;
        float width = Settings.mobOutlineWidth();
        if (width <= 0f || alpha <= 0f || region == null) return;
        if (drawAtlas(batch, region, x, y, w, h, r, g, b, alpha)) return;
        drawTaps(batch, region, x, y, w, h, width, r, g, b, alpha);
    }

    /** Public tap-based outline draw for callers that cannot use the atlas (e.g. UI screens).
     *  Reads width and darkness from Settings; adjusts per-tap alpha so N accumulated passes
     *  produce exactly {@code outlineDarkness} opacity — matching the single-pass atlas path. */
    public static void drawTaps(SpriteBatch batch, TextureRegion region, float x, float y, float w, float h) {
        if (!DRAW_OUTLINES) return;
        float width = Settings.mobOutlineWidth();
        float alpha = Settings.mobOutlineDarkness();
        if (width <= 0f || alpha <= 0f || region == null) return;
        drawTaps(batch, region, x, y, w, h, width, 0f, 0f, 0f, alpha);
    }

    private static void drawTaps(SpriteBatch batch, TextureRegion region,
                                 float x, float y, float w, float h, float width,
                                 float r, float g, float b, float a) {
        // Adjust per-tap alpha so OUTLINE_TAPS accumulated passes equal exactly `a`,
        // matching the single-pass opacity of the atlas path.
        float aTap = 1f - (float) Math.pow(1f - a, 1f / OUTLINE_TAPS);
        batch.setColor(r, g, b, aTap);
        for (int i = 0; i < OUTLINE_TAPS; i++) {
            batch.draw(region, x + OUTLINE_DX[i] * width, y + OUTLINE_DY[i] * width, w, h);
        }
        batch.setColor(Color.WHITE);
    }

    private void drawSourceTaps(SpriteBatch batch, Texture texture, int srcX, int srcY, int srcW, int srcH,
                                float x, float y, float w, float h, float width,
                                float r, float g, float b, float a) {
        float aTap = 1f - (float) Math.pow(1f - a, 1f / OUTLINE_TAPS);
        batch.setColor(r, g, b, aTap);
        for (int i = 0; i < OUTLINE_TAPS; i++) {
            batch.draw(texture,
                    x + OUTLINE_DX[i] * width, y + OUTLINE_DY[i] * width,
                    w, h, srcX, srcY, srcW, srcH, false, false);
        }
        batch.setColor(Color.WHITE);
    }

    private boolean drawAtlas(SpriteBatch batch, TextureRegion src, float x, float y, float w, float h,
                              float r, float g, float b, float a) {
        if (!ATLAS_ENABLED || src.isFlipY() || w < 0f || h < 0f) return false;
        OutlineAtlas.Frame frame = atlas.frame(src);
        if (frame == null) return false;
        // Key is normalized to the non-flipped origin, so both orientations share one frame.
        // Mirror the draw when the source region is flipped.
        drawAtlasFrame(batch, frame, x, y, w, h, r, g, b, a, src.isFlipX());
        return true;
    }

    private boolean drawAtlas(SpriteBatch batch, Texture texture, int srcX, int srcY, int srcW, int srcH,
                              float x, float y, float w, float h,
                              float r, float g, float b, float a) {
        if (!ATLAS_ENABLED || w < 0f || h < 0f) return false;
        OutlineAtlas.Frame frame = atlas.frame(texture, srcX, srcY, srcW, srcH);
        if (frame == null) return false;
        drawAtlasFrame(batch, frame, x, y, w, h, r, g, b, a, false);
        return true;
    }

    private static void drawAtlasFrame(SpriteBatch batch, OutlineAtlas.Frame frame,
                                       float x, float y, float w, float h,
                                       float r, float g, float b, float a, boolean flipX) {
        float scaleX = Math.abs(w) / Math.max(1f, frame.sourceW);
        float scaleY = Math.abs(h) / Math.max(1f, frame.sourceH);
        float padX = frame.padX * scaleX;
        float padY = frame.padY * scaleY;
        float dx = Math.min(x, x + w) - padX;
        float dy = Math.min(y, y + h) - padY;
        float dw = Math.abs(w) + padX * 2f;
        float dh = Math.abs(h) + padY * 2f;
        batch.setColor(r, g, b, a);
        if (flipX) {
            frame.region.flip(true, false);
            batch.draw(frame.region, dx, dy, dw, dh);
            frame.region.flip(true, false);
        } else {
            batch.draw(frame.region, dx, dy, dw, dh);
        }
        batch.setColor(Color.WHITE);
    }
}
