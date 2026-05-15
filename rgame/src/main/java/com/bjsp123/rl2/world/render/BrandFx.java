package com.bjsp123.rl2.world.render;

import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.g2d.SpriteBatch;
import com.badlogic.gdx.graphics.g2d.TextureRegion;
import com.bjsp123.rl2.logic.BrandDefinition;
import com.bjsp123.rl2.model.Item;

/** Utility for rendering brand-spark particle effects inside UI slot rects. */
public final class BrandFx {
    private BrandFx() {}

    /** Draw animated rising sparks for a branded item inside a UI slot.
     *  Must be called inside an active {@link SpriteBatch} begin/end block.
     *  Temporarily switches to additive blending and restores standard on exit.
     *  {@code rx, ry} is the slot's bottom-left corner in virtual coords. */
    public static void drawSparks(SpriteBatch batch, TextureRegion white,
                                  float rx, float ry, float rw, float rh,
                                  BrandDefinition brand, float phaseOffset) {
        drawSparks(batch, white, rx, ry, rw, rh, brand, phaseOffset,
                animationTimeSeconds(), 5, 0.12f);
    }

    public static void drawSparks(SpriteBatch batch, TextureRegion white,
                                  float rx, float ry, float rw, float rh,
                                  BrandDefinition brand, float phaseOffset,
                                  float timeSeconds, int count, float sizeFrac) {
        if (brand == null || white == null) return;
        int hex = brand.colorHex;
        float cr = ((hex >> 16) & 0xFF) / 255f;
        float cg = ((hex >>  8) & 0xFF) / 255f;
        float cb = ( hex        & 0xFF) / 255f;
        float pSize = Math.max(2f, Math.min(rw, rh) * sizeFrac);
        batch.setBlendFunction(GL20.GL_SRC_ALPHA, GL20.GL_ONE);

        for (int i = 0; i < count; i++) {
            float seed  = (i * 37.0f + phaseOffset * 11.0f) % 97.0f;
            float speed = 0.45f + (i % 4) * 0.15f;
            float t     = (timeSeconds * speed + seed * 0.137f) % 1.0f;
            float alpha = t < 0.20f ? t / 0.20f : (1.0f - t) / 0.80f;
            alpha = Math.max(0.25f, alpha) * 0.76f;
            float lane = ((seed * 0.618f) % 1.0f);
            float wobble = (float) Math.sin(timeSeconds * 4.0f + seed) * rw * 0.06f;
            float px = rx + rw * (0.18f + lane * 0.64f) + wobble - pSize * 0.5f;
            float py = ry + rh * (0.10f + t * 0.78f);
            drawSpark(batch, white, px, py, pSize, cr, cg, cb, alpha);
        }
        batch.setBlendFunction(GL20.GL_SRC_ALPHA, GL20.GL_ONE_MINUS_SRC_ALPHA);
        batch.setColor(Color.WHITE);
    }

    private static void drawSpark(SpriteBatch batch, TextureRegion white,
                                  float px, float py, float size,
                                  float cr, float cg, float cb, float alpha) {
        float arm = Math.max(1f, size * 0.28f);
        float len = size;
        float cx = px + size * 0.5f;
        float cy = py + size * 0.5f;

        batch.setColor(cr, cg, cb, alpha);
        batch.draw(white, cx - arm * 0.5f, cy - len * 0.5f, arm, len);
        batch.draw(white, cx - len * 0.5f, cy - arm * 0.5f, len, arm);

        float core = Math.max(2f, size * 0.42f);
        batch.setColor(1f, 1f, 1f, alpha * 0.85f);
        batch.draw(white, cx - core * 0.5f, cy - core * 0.5f, core, core);
    }

    /** Draw the shared spark language for branded items. */
    public static void drawItemSparks(SpriteBatch batch, TextureRegion white,
                                      float rx, float ry, float rw, float rh,
                                      Item item) {
        if (item == null || white == null) return;
        if (item.brand != null) {
            drawSparks(batch, white, rx, ry, rw, rh, item.brand, phaseFor(item));
        }
    }

    public static void drawWorldItemSparks(SpriteBatch batch, TextureRegion white,
                                           float rx, float ry, float rw, float rh,
                                           Item item, float timeSeconds) {
        if (item == null || item.brand == null || item.location == null) return;
        float phase = item.location.tileX() * 1.7f + item.location.tileY() * 2.3f;
        drawSparks(batch, white, rx, ry, rw, rh, item.brand, phase,
                timeSeconds, 4, 0.18f);
    }

    /** Stable per-item phase offset so nearby branded items animate independently.
     *  Derived from the item type string (consistent across frames). */
    public static float phaseFor(Item item) {
        return (item.type != null ? item.type.hashCode() & 0xFF : 0) * 0.1f;
    }

    private static float animationTimeSeconds() {
        return (System.nanoTime() % 60_000_000_000L) / 1_000_000_000f;
    }
}
