package com.bjsp123.rl2.world.render;

import com.badlogic.gdx.graphics.Pixmap;
import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.graphics.TextureData;
import com.badlogic.gdx.graphics.g2d.TextureRegion;
import com.bjsp123.rl2.ui.skin.Settings;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/** Runtime-generated atlas of padded white-alpha sprite outlines. */
final class OutlineAtlas {
    private static final int MAX_ATLAS_W = 2048;

    static final class Frame {
        final TextureRegion region;
        final int sourceW, sourceH;
        final int padX, padY;

        Frame(TextureRegion region, int sourceW, int sourceH, int padX, int padY) {
            this.region = region;
            this.sourceW = sourceW;
            this.sourceH = sourceH;
            this.padX = padX;
            this.padY = padY;
        }
    }

    private static final class Key {
        final int x, y, w, h;

        Key(int x, int y, int w, int h) {
            this.x = x;
            this.y = y;
            this.w = Math.abs(w);
            this.h = Math.abs(h);
        }

        @Override public boolean equals(Object o) {
            if (!(o instanceof Key k)) return false;
            return x == k.x && y == k.y && w == k.w && h == k.h;
        }

        @Override public int hashCode() {
            int r = x;
            r = 31 * r + y;
            r = 31 * r + w;
            r = 31 * r + h;
            return r;
        }
    }

    private static final class Request {
        final Texture texture;
        final Key key;

        Request(Texture texture, Key key) {
            this.texture = texture;
            this.key = key;
        }
    }

    private record Placement(int x, int y, int w, int h) {}

    private static final class SourcePixmap {
        final Pixmap pixmap;
        final boolean dispose;

        SourcePixmap(Pixmap pixmap, boolean dispose) {
            this.pixmap = pixmap;
            this.dispose = dispose;
        }
    }

    private final List<Request> requests = new ArrayList<>();
    private final IdentityHashMap<Texture, Map<Key, Frame>> frames = new IdentityHashMap<>();
    private final IdentityHashMap<Texture, SourcePixmap> sourcePixmaps = new IdentityHashMap<>();

    private Texture atlas;
    private float generatedWidth = Float.NaN;
    private boolean generatedSmooth;

    void register(TextureRegion region) {
        if (region == null || region.getTexture() == null) return;
        register(region.getTexture(), region.getRegionX(), region.getRegionY(),
                region.getRegionWidth(), region.getRegionHeight());
    }

    void register(Texture texture) {
        if (texture == null) return;
        register(texture, 0, 0, texture.getWidth(), texture.getHeight());
    }

    void register(Texture texture, int srcX, int srcY, int srcW, int srcH) {
        if (texture == null || srcW == 0 || srcH == 0) return;
        Key key = new Key(srcX, srcY, srcW, srcH);
        Map<Key, Frame> byKey = frames.computeIfAbsent(texture, t -> new HashMap<>());
        if (byKey.containsKey(key)) return;
        byKey.put(key, null);
        requests.add(new Request(texture, key));
    }

    Frame frame(TextureRegion region) {
        if (region == null) return null;
        return frame(region.getTexture(), region.getRegionX(), region.getRegionY(),
                region.getRegionWidth(), region.getRegionHeight());
    }

    Frame frame(Texture texture) {
        if (texture == null) return null;
        return frame(texture, 0, 0, texture.getWidth(), texture.getHeight());
    }

    Frame frame(Texture texture, int srcX, int srcY, int srcW, int srcH) {
        Map<Key, Frame> byKey = frames.get(texture);
        return byKey == null ? null : byKey.get(new Key(srcX, srcY, srcW, srcH));
    }

    void ensureCurrent() {
        float width = Settings.mobOutlineWidth();
        boolean smooth = Settings.mobOutlineSmooth();
        if (atlas != null
                && Math.abs(width - generatedWidth) < 0.001f
                && smooth == generatedSmooth) {
            return;
        }
        rebuild();
    }

    void rebuild() {
        disposeAtlas();
        generatedWidth = Settings.mobOutlineWidth();
        generatedSmooth = Settings.mobOutlineSmooth();
        clearFramesOnly();
        if (generatedWidth <= 0f || requests.isEmpty()) return;

        int radius = Math.max(1, (int) Math.ceil(generatedWidth * 2f));
        int cursorX = 0;
        int cursorY = 0;
        int rowH = 0;
        int atlasW = 0;
        int atlasH;

        List<Placement> generated = new ArrayList<>(requests.size());
        for (Request req : requests) {
            int frameW = req.key.w + radius * 2;
            int frameH = req.key.h + radius * 2;
            if (cursorX > 0 && cursorX + frameW > MAX_ATLAS_W) {
                cursorX = 0;
                cursorY += rowH;
                rowH = 0;
            }
            generated.add(new Placement(cursorX, cursorY, frameW, frameH));
            cursorX += frameW;
            rowH = Math.max(rowH, frameH);
            atlasW = Math.max(atlasW, cursorX);
        }
        atlasH = cursorY + rowH;
        if (atlasW <= 0 || atlasH <= 0) return;

        Pixmap atlasPm = new Pixmap(nextPowerOfTwo(atlasW), nextPowerOfTwo(atlasH), Pixmap.Format.RGBA8888);
        atlasPm.setBlending(Pixmap.Blending.None);
        atlasPm.setColor(0, 0, 0, 0);
        atlasPm.fill();

        boolean[] built = new boolean[requests.size()];
        for (int i = 0; i < requests.size(); i++) {
            Request req = requests.get(i);
            Placement placement = generated.get(i);
            SourcePixmap src = sourcePixmaps.computeIfAbsent(req.texture, OutlineAtlas::readTexturePixmap);
            if (src == null || src.pixmap == null) continue;
            drawOutline(atlasPm, src.pixmap, req.key,
                    placement.x(), placement.y(), radius);
            built[i] = true;
        }

        atlas = new Texture(atlasPm);
        atlas.setFilter(generatedSmooth ? Texture.TextureFilter.Linear : Texture.TextureFilter.Nearest,
                generatedSmooth ? Texture.TextureFilter.Linear : Texture.TextureFilter.Nearest);
        atlasPm.dispose();

        for (int i = 0; i < requests.size(); i++) {
            if (!built[i]) continue;
            Request req = requests.get(i);
            Placement placement = generated.get(i);
            TextureRegion region = new TextureRegion(atlas,
                    placement.x(), placement.y(), placement.w(), placement.h());
            frames.get(req.texture).put(req.key,
                    new Frame(region, req.key.w, req.key.h, radius, radius));
        }
    }

    void dispose() {
        disposeAtlas();
        for (SourcePixmap src : sourcePixmaps.values()) {
            if (src != null && src.dispose && src.pixmap != null) src.pixmap.dispose();
        }
        sourcePixmaps.clear();
        requests.clear();
        frames.clear();
    }

    private void clearFramesOnly() {
        for (Map<Key, Frame> byKey : frames.values()) {
            for (Key key : new ArrayList<>(byKey.keySet())) {
                byKey.put(key, null);
            }
        }
    }

    private void disposeAtlas() {
        if (atlas != null) {
            atlas.dispose();
            atlas = null;
        }
    }

    private static SourcePixmap readTexturePixmap(Texture texture) {
        try {
            TextureData data = texture.getTextureData();
            if (data.getClass().getName().contains("PixmapTextureData")) return null;
            if (!data.isPrepared()) data.prepare();
            return new SourcePixmap(data.consumePixmap(), data.disposePixmap());
        } catch (Exception ignored) {
            return null;
        }
    }

    private static void drawOutline(Pixmap dst, Pixmap src, Key key, int dstX, int dstY, int radius) {
        int[] alpha = new int[key.w * key.h];
        for (int y = 0; y < key.h; y++) {
            int sy = key.y + y;
            if (sy < 0 || sy >= src.getHeight()) continue;
            for (int x = 0; x < key.w; x++) {
                int sx = key.x + x;
                if (sx < 0 || sx >= src.getWidth()) continue;
                alpha[y * key.w + x] = src.getPixel(sx, sy) & 0xff;
            }
        }

        int outW = key.w + radius * 2;
        int outH = key.h + radius * 2;
        for (int y = 0; y < outH; y++) {
            for (int x = 0; x < outW; x++) {
                int sx = x - radius;
                int sy = y - radius;
                int original = alphaAt(alpha, key.w, key.h, sx, sy);
                if (original > 0) continue;

                int max = 0;
                for (int oy = -radius; oy <= radius; oy++) {
                    for (int ox = -radius; ox <= radius; ox++) {
                        if (ox * ox + oy * oy > radius * radius) continue;
                        max = Math.max(max, alphaAt(alpha, key.w, key.h, sx + ox, sy + oy));
                    }
                }
                if (max > 0) {
                    dst.drawPixel(dstX + x, dstY + y, 0xffffff00 | max);
                }
            }
        }
    }

    private static int alphaAt(int[] alpha, int w, int h, int x, int y) {
        if (x < 0 || y < 0 || x >= w || y >= h) return 0;
        return alpha[y * w + x];
    }

    private static int nextPowerOfTwo(int v) {
        int p = 1;
        while (p < v) p <<= 1;
        return p;
    }
}
