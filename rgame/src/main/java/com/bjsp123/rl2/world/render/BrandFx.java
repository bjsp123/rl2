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
        if (brand == null || white == null) return;
        int hex = brand.colorHex;
        float cr = ((hex >> 16) & 0xFF) / 255f;
        float cg = ((hex >>  8) & 0xFF) / 255f;
        float cb = ( hex        & 0xFF) / 255f;
        float time = (float) (System.currentTimeMillis() / 1000.0);
        float pSize = Math.max(2f, rw * 0.12f);
        batch.setBlendFunction(GL20.GL_SRC_ALPHA, GL20.GL_ONE);
        for (int i = 0; i < 4; i++) {
            float slot  = i * 0.25f;
            float speed = 0.8f + i * 0.15f;
            float t     = (time * speed + phaseOffset + slot * 7.3f) % 1.0f;
            if (t > 0.55f) continue;
            float alpha = t < 0.15f ? t / 0.15f : (0.55f - t) / 0.40f;
            alpha *= 0.7f;
            float px = rx + rw * (0.2f + slot * 0.6f) - pSize * 0.5f;
            float py = ry + rh * (0.05f + t * 1.1f);
            batch.setColor(cr, cg, cb, alpha);
            batch.draw(white, px, py, pSize, pSize);
        }
        batch.setBlendFunction(GL20.GL_SRC_ALPHA, GL20.GL_ONE_MINUS_SRC_ALPHA);
        batch.setColor(Color.WHITE);
    }

    /** Stable per-item phase offset so nearby branded items animate independently.
     *  Derived from the item type string (consistent across frames). */
    public static float phaseFor(Item item) {
        return (item.type != null ? item.type.hashCode() & 0xFF : 0) * 0.1f;
    }
}
