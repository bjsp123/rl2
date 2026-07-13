package com.bjsp123.rl2.ui.v2;

import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.Pixmap;
import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.graphics.g2d.SpriteBatch;
import com.badlogic.gdx.graphics.g2d.TextureRegion;

/**
 * Algorithmic "primal swirls" backdrop: shifting clouds/nebula drawn behind the
 * world graph. Instead of a shader (unreliable on some D3D-backed GL stacks), a
 * single tileable fractal-noise texture is generated once, then drawn as a few
 * layers scrolling/drifting at different speeds, scales, and tints - the layers
 * sliding over each other read as slowly churning clouds.
 *
 * <p>The noise texture stores white RGB with a fractal-noise alpha, so each
 * layer's {@link SpriteBatch} colour tints it and additive blends stack into
 * brighter wisps. Texture wrap is {@code Repeat}, so scrolling the UV offset
 * past 1 tiles seamlessly.
 */
public final class SwirlBackground {

    private static final int NOISE_SIZE = 256;
    private static Texture noiseTex;
    private static TextureRegion region;

    private SwirlBackground() {}

    private static void ensure() {
        if (noiseTex != null) return;
        Pixmap pm = buildTileableFbm(NOISE_SIZE);
        noiseTex = new Texture(pm);
        noiseTex.setFilter(Texture.TextureFilter.Linear, Texture.TextureFilter.Linear);
        noiseTex.setWrap(Texture.TextureWrap.Repeat, Texture.TextureWrap.Repeat);
        pm.dispose();
        region = new TextureRegion(noiseTex);
    }

    /**
     * Draw the shifting swirls into {@code [x,y,w,h]} (caller should already have
     * painted a dark base; the clouds blend over it). {@code t} is a wall-clock
     * accumulator in seconds. Leaves the batch on the standard alpha blend +
     * white colour.
     */
    public static void render(SpriteBatch batch, float x, float y,
                              float w, float h, float t) {
        render(batch, x, y, w, h, t, 0f, 1f);
    }

    /**
     * As {@link #render(SpriteBatch, float, float, float, float, float)}, plus
     * a shared vertical scroll offset {@code vScroll} (in texture repeats;
     * layers move at parallax multiples of it, positive = clouds drift down)
     * and an overall {@code alpha} multiplier (0..1) so the whole cloud stack
     * can fade in/out - used by the level-transition cinematic.
     */
    public static void render(SpriteBatch batch, float x, float y,
                              float w, float h, float t, float vScroll, float alpha) {
        ensure();
        float aspect = h / Math.max(1f, w);
        // Layer 1 - broad, slow drift, alpha-blended deep violet base haze.
        drawLayer(batch, x, y, w, h, /*span*/ 2.2f, aspect,
                t * 0.010f, t * 0.006f + vScroll,
                0.18f, 0.09f, 0.28f, 0.55f * alpha, /*additive*/ false);
        // Layer 2 - faster, opposite drift, additive cool-blue wisps.
        drawLayer(batch, x, y, w, h, 3.3f, aspect,
                -t * 0.013f + 0.4f, t * 0.009f + vScroll * 1.6f,
                0.10f, 0.12f, 0.30f, 0.45f * alpha, true);
        // Layer 3 - large, very slow, additive magenta glow for depth.
        drawLayer(batch, x, y, w, h, 1.5f, aspect,
                t * 0.005f + 0.7f, -t * 0.004f + vScroll * 0.7f,
                0.22f, 0.06f, 0.20f, 0.35f * alpha, true);
        batch.setBlendFunction(GL20.GL_SRC_ALPHA, GL20.GL_ONE_MINUS_SRC_ALPHA);
        batch.setColor(Color.WHITE);
    }

    private static void drawLayer(SpriteBatch batch, float x, float y, float w, float h,
                                  float span, float aspect, float u0, float v0,
                                  float r, float g, float b, float a, boolean additive) {
        region.setRegion(u0, v0, u0 + span, v0 + span * aspect);
        batch.setBlendFunction(GL20.GL_SRC_ALPHA,
                additive ? GL20.GL_ONE : GL20.GL_ONE_MINUS_SRC_ALPHA);
        batch.setColor(r, g, b, a);
        batch.draw(region, x, y, w, h);
    }

    /** Build a seamlessly-tiling fractal (value-noise) field: white RGB, alpha =
     *  contrast-curved fbm. Periodic lattices (wrap on {@code period}) keep it
     *  tileable so scrolling never shows a seam. */
    private static Pixmap buildTileableFbm(int n) {
        Pixmap pm = new Pixmap(n, n, Pixmap.Format.RGBA8888);
        float[] acc = new float[n * n];
        java.util.Random rng = new java.util.Random(0xC10D5L);
        float amp = 1f, total = 0f;
        for (int period : new int[] { 4, 8, 16, 32, 64 }) {
            float[] lattice = new float[period * period];
            for (int i = 0; i < lattice.length; i++) lattice[i] = rng.nextFloat();
            for (int y = 0; y < n; y++) {
                for (int xx = 0; xx < n; xx++) {
                    float fx = (float) xx / n * period;
                    float fy = (float) y / n * period;
                    int x0 = (int) Math.floor(fx) % period;
                    int y0 = (int) Math.floor(fy) % period;
                    int x1 = (x0 + 1) % period;
                    int y1 = (y0 + 1) % period;
                    float tx = fx - (float) Math.floor(fx);
                    float ty = fy - (float) Math.floor(fy);
                    tx = tx * tx * (3f - 2f * tx);
                    ty = ty * ty * (3f - 2f * ty);
                    float a00 = lattice[y0 * period + x0];
                    float a10 = lattice[y0 * period + x1];
                    float a01 = lattice[y1 * period + x0];
                    float a11 = lattice[y1 * period + x1];
                    float top = a00 + (a10 - a00) * tx;
                    float bot = a01 + (a11 - a01) * tx;
                    acc[y * n + xx] += (top + (bot - top) * ty) * amp;
                }
            }
            total += amp;
            amp *= 0.55f;
        }
        for (int y = 0; y < n; y++) {
            for (int xx = 0; xx < n; xx++) {
                float v = acc[y * n + xx] / total;            // 0..1
                v = clamp01((v - 0.35f) / 0.40f);             // contrast: wispy
                v = v * v * (3f - 2f * v);                    // smooth
                pm.setColor(1f, 1f, 1f, v);
                pm.drawPixel(xx, y);
            }
        }
        return pm;
    }

    private static float clamp01(float v) {
        return v < 0f ? 0f : (v > 1f ? 1f : v);
    }

    /** Release the shared noise texture (optional; reloads on next use). */
    public static void disposeShared() {
        if (noiseTex != null) { noiseTex.dispose(); noiseTex = null; region = null; }
    }
}
