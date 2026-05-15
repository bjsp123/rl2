package com.bjsp123.rl2.world.render;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.graphics.g2d.TextureRegion;

import java.util.IdentityHashMap;
import java.util.HashSet;
import java.util.Set;

/** Maps normal sprite-sheet textures to pre-generated white-alpha outline sheets. */
final class OutlineSprites {
    private static final IdentityHashMap<Texture, Texture> outlines = new IdentityHashMap<>();
    private static final Set<Texture> owned = new HashSet<>();

    private OutlineSprites() {}

    static void register(Texture source, String outlinePath) {
        if (source == null || outlinePath == null || outlines.containsKey(source)) return;
        if (!Gdx.files.internal(outlinePath).exists()) return;
        try {
            Texture outline = new Texture(Gdx.files.internal(outlinePath));
            outline.setFilter(Texture.TextureFilter.Nearest, Texture.TextureFilter.Nearest);
            outlines.put(source, outline);
            owned.add(outline);
        } catch (Exception ignored) {
            // Missing or broken outline sheets fall back to the legacy multi-tap outline.
        }
    }

    static void register(Texture source, Texture outline) {
        if (source == null || outline == null) return;
        outline.setFilter(Texture.TextureFilter.Nearest, Texture.TextureFilter.Nearest);
        outlines.put(source, outline);
        owned.add(outline);
    }

    static boolean configure(TextureRegion source, TextureRegion target) {
        if (source == null || target == null) return false;
        Texture outline = outlines.get(source.getTexture());
        if (outline == null) return false;
        target.setTexture(outline);
        target.setRegion(source.getRegionX(), source.getRegionY(),
                source.getRegionWidth(), source.getRegionHeight());
        if (source.isFlipX() || source.isFlipY()) {
            target.flip(source.isFlipX(), source.isFlipY());
        }
        return true;
    }

    static boolean configure(Texture source, TextureRegion target) {
        if (source == null || target == null) return false;
        Texture outline = outlines.get(source);
        if (outline == null) return false;
        target.setTexture(outline);
        target.setRegion(0, 0, outline.getWidth(), outline.getHeight());
        return true;
    }

    static boolean configure(Texture source, TextureRegion target,
                             int srcX, int srcY, int srcW, int srcH) {
        if (source == null || target == null) return false;
        Texture outline = outlines.get(source);
        if (outline == null) return false;
        target.setTexture(outline);
        target.setRegion(srcX, srcY, srcW, srcH);
        return true;
    }

    static void disposeShared() {
        for (Texture t : owned) {
            if (t != null) t.dispose();
        }
        owned.clear();
        outlines.clear();
    }
}
