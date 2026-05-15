package com.bjsp123.rl2.world.render;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.graphics.Pixmap;
import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.graphics.g2d.TextureRegion;
import com.bjsp123.rl2.model.Level.Surface;
import com.bjsp123.rl2.model.Level.Vegetation;

import java.util.EnumMap;
import java.util.Map;

/**
 * Single owner of every Texture derived from {@code sprites/surfaces.png} plus the
 * two animated fire sheets ({@code sprites/fire 1.png}, {@code sprites/fire 2.png}).
 * Loaded once on first call; {@link #disposeShared()} releases everything.
 *
 * <p>Three flavours of access:
 * <ul>
 *   <li><b>UI regions</b> - {@link #regionFor(Surface)} / {@link #regionFor(Vegetation)}
 *       return single-tile {@link TextureRegion}s for UI panels (look popup, encyclopaedia).
 *       The encyclopaedia-only ICE / FIRE entries are backed by procedural / first-frame
 *       fallbacks respectively.</li>
 *   <li><b>In-world liquid + vegetation Textures</b> - {@link #liquidTexture(Surface)},
 *       {@link #vegetationTextureA(Vegetation)} / {@link #vegetationTextureB(Vegetation)}
 *       return per-tile {@link Texture} instances with the right filter/wrap settings the
 *       in-world renderer needs (Linear+Repeat for liquids' scrolling shader; Nearest for
 *       crisp vegetation pixels).</li>
 *   <li><b>Shore-mask tiles</b> - {@link #maskTexture(int)} returns one of 16 alpha
 *       masks indexed by the 4-bit N/E/S/W neighbour bitfield.</li>
 *   <li><b>Fire frames</b> - {@link #fire1Texture()} / {@link #fire2Texture()} return
 *       the two 8-frame fire animation sheets used by FxRenderer.</li>
 * </ul>
 */
public final class SurfaceSprites {

    private static final String SURFACES_PATH = "sprites/surfaces.png";
    private static final String FIRE1_PATH    = "sprites/fire 1.png";
    private static final String FIRE2_PATH    = "sprites/fire 2.png";

    /** Liquid tile pitch - matches {@code DefaultLevelRenderer.SURFACE_TILE}. */
    public static final int LIQUID_TILE = 64;
    /** Vegetation cell pitch - matches {@code DefaultLevelRenderer.VEG_SPRITE_PX}. */
    public static final int VEG_CELL    = 32;
    /** Shore-mask tile pitch. */
    public static final int MASK_TILE   = 32;
    /** y-offset of the mask strip - row 3 of the 64-px liquid grid. */
    private static final int MASK_STRIP_Y = 3 * LIQUID_TILE;

    /** Number of mask variants - 4-bit N/E/S/W bitfield. */
    public static final int MASK_VARIANTS = 16;

    /** Frame size of one cell in fire 1/2.png - matches {@code FxRenderer.FIRE_SHEET_FRAME_*}. */
    private static final int FIRE_FRAME_W = 32;
    private static final int FIRE_FRAME_H = 48;

    // Raw atlas sheet - kept for UI region access.
    private static Texture sheet;
    // In-world per-liquid textures (Linear+Repeat for the scroll shader).
    private static Map<Surface, Texture> liquidTextures;
    // In-world vegetation variant textures (Nearest filter).
    private static Map<Vegetation, Texture> vegA;
    private static Map<Vegetation, Texture> vegB;
    // Shore-mask variants 0..15.
    private static Texture[] maskTextures;
    // Procedural placeholder for ICE - atlas doesn't ship one.
    private static Texture iceTex;
    // Fire animation sheets.
    private static Texture fire1Tex, fire2Tex;

    // UI region maps (kept separately because UI prefers Nearest filter on the raw
    // atlas Texture; in-world tiles use the per-tile Linear-filtered Textures above).
    private static Map<Surface, TextureRegion>    surfaceRegions;
    private static Map<Vegetation, TextureRegion> vegRegions;

    private SurfaceSprites() {}

    // -- UI region accessors -------------------------------------------------

    public static TextureRegion regionFor(Surface s) {
        if (surfaceRegions == null) load();
        return surfaceRegions == null ? null : surfaceRegions.get(s);
    }

    public static TextureRegion regionFor(Vegetation v) {
        if (vegRegions == null) load();
        return vegRegions == null ? null : vegRegions.get(v);
    }

    // -- In-world Texture accessors ------------------------------------------

    public static Texture liquidTexture(Surface s) {
        if (liquidTextures == null) load();
        return liquidTextures == null ? null : liquidTextures.get(s);
    }

    public static Texture vegetationTextureA(Vegetation v) {
        if (vegA == null) load();
        return vegA == null ? null : vegA.get(v);
    }

    public static Texture vegetationTextureB(Vegetation v) {
        if (vegB == null) load();
        return vegB == null ? null : vegB.get(v);
    }

    /** Shore-mask tile for a 4-bit N/E/S/W neighbour bitfield (0..15). Variant 0
     *  is synthesised as fully transparent regardless of what's in the atlas at
     *  that cell. */
    public static Texture maskTexture(int variant) {
        if (maskTextures == null) load();
        if (maskTextures == null || variant < 0 || variant >= maskTextures.length) return null;
        return maskTextures[variant];
    }

    public static Texture fire1Texture() {
        if (fire1Tex == null && surfaceRegions == null) load();
        return fire1Tex;
    }

    public static Texture fire2Texture() {
        if (fire2Tex == null && surfaceRegions == null) load();
        return fire2Tex;
    }

    // -- Loading -------------------------------------------------------------

    private static void load() {
        Pixmap sheetPm = null;
        Pixmap outlinePm = null;
        try {
            sheetPm = new Pixmap(Gdx.files.internal(SURFACES_PATH));
            if (Gdx.files.internal("sprites/surfaces_outline.png").exists()) {
                outlinePm = new Pixmap(Gdx.files.internal("sprites/surfaces_outline.png"));
            }
            // Raw atlas Texture for UI regions - Nearest filter so the UI preview
            // stays crisp at native size.
            sheet = new Texture(sheetPm);
            sheet.setFilter(Texture.TextureFilter.Nearest, Texture.TextureFilter.Nearest);
            OutlineSprites.register(sheet, "sprites/surfaces_outline.png");
            int sw = sheetPm.getWidth(), sh = sheetPm.getHeight();

            liquidTextures = new EnumMap<>(Surface.class);
            putLiquid(sheetPm, Surface.WATER, 0, sw, sh);
            putLiquid(sheetPm, Surface.BLOOD, 1, sw, sh);
            putLiquid(sheetPm, Surface.OIL,   2, sw, sh);
            // ICE - atlas doesn't carry one (in-world reads off the end of the sheet).
            // Synthesise a 32x32 procedural placeholder; same Texture serves both UI
            // and in-world rendering.
            iceTex = buildIceTexture();
            if (iceTex != null) liquidTextures.put(Surface.ICE, iceTex);

            vegA = new EnumMap<>(Vegetation.class);
            vegB = new EnumMap<>(Vegetation.class);
            putVegPair(sheetPm, outlinePm, Vegetation.GRASS,     4, 0, 1, sw, sh);
            putVegPair(sheetPm, outlinePm, Vegetation.MUSHROOMS, 4, 1, 1, sw, sh);
            // Trees - 32x64 (col 6/7, rows 0..1).
            putVegPair(sheetPm, outlinePm, Vegetation.TREES,     6, 0, 2, sw, sh);

            // Shore-mask tiles - row 3 of the 64-px grid, sliced into 16 32x32 variants.
            maskTextures = new Texture[MASK_VARIANTS];
            maskTextures[0] = buildAllWaterMask();
            for (int i = 1; i < MASK_VARIANTS; i++) {
                int x = i * MASK_TILE;
                if (x + MASK_TILE > sw || MASK_STRIP_Y + MASK_TILE > sh) {
                    maskTextures[i] = buildAllWaterMask();
                } else {
                    Pixmap m = new Pixmap(MASK_TILE, MASK_TILE, Pixmap.Format.RGBA8888);
                    m.setBlending(Pixmap.Blending.None);
                    m.drawPixmap(sheetPm, 0, 0, x, MASK_STRIP_Y, MASK_TILE, MASK_TILE);
                    Texture t = new Texture(m);
                    t.setFilter(Texture.TextureFilter.Nearest, Texture.TextureFilter.Nearest);
                    m.dispose();
                    maskTextures[i] = t;
                }
            }

            // UI regions - surface previews are 64x64 single-tile samples; vegetation
            // previews use the 32-px cell pitch.
            surfaceRegions = new EnumMap<>(Surface.class);
            putRegion(surfaceRegions, Surface.WATER, 0, 0, LIQUID_TILE, LIQUID_TILE, sw, sh);
            putRegion(surfaceRegions, Surface.BLOOD, 0, LIQUID_TILE, LIQUID_TILE, LIQUID_TILE, sw, sh);
            putRegion(surfaceRegions, Surface.OIL,   0, 2 * LIQUID_TILE, LIQUID_TILE, LIQUID_TILE, sw, sh);
            // ICE - point UI at the procedural placeholder.
            if (iceTex != null) surfaceRegions.put(Surface.ICE, new TextureRegion(iceTex));

            vegRegions = new EnumMap<>(Vegetation.class);
            putRegion(vegRegions, Vegetation.GRASS,     4 * VEG_CELL, 0,           VEG_CELL, VEG_CELL, sw, sh);
            putRegion(vegRegions, Vegetation.MUSHROOMS, 4 * VEG_CELL, VEG_CELL,    VEG_CELL, VEG_CELL, sw, sh);
            // Trees: 32x64 - let UI scale to fit.
            putRegion(vegRegions, Vegetation.TREES,     6 * VEG_CELL, 0,           VEG_CELL, 2 * VEG_CELL, sw, sh);
        } catch (Exception ignored) {
            // Atlas missing - fields stay null; accessors return null.
        } finally {
            if (sheetPm != null) sheetPm.dispose();
            if (outlinePm != null) outlinePm.dispose();
        }

        // Fire sheets - separate files, both optional.
        fire1Tex = loadIfPresent(FIRE1_PATH);
        fire2Tex = loadIfPresent(FIRE2_PATH);
        // Encyclopaedia FIRE preview rides on fire1's first frame.
        if (fire1Tex != null && vegRegions != null
                && fire1Tex.getWidth() >= FIRE_FRAME_W && fire1Tex.getHeight() >= FIRE_FRAME_H) {
            vegRegions.put(Vegetation.FIRE,
                    new TextureRegion(fire1Tex, 0, 0, FIRE_FRAME_W, FIRE_FRAME_H));
        }
    }

    private static void putLiquid(Pixmap sheet, Surface s, int row, int sw, int sh) {
        int y = row * LIQUID_TILE;
        if (y + LIQUID_TILE > sh || LIQUID_TILE > sw) return;
        Pixmap p = new Pixmap(LIQUID_TILE, LIQUID_TILE, sheet.getFormat());
        p.setBlending(Pixmap.Blending.None);
        p.drawPixmap(sheet, 0, 0, 0, y, LIQUID_TILE, LIQUID_TILE);
        Texture t = new Texture(p);
        p.dispose();
        // Linear+Repeat - the in-world surface shader scrolls UVs across the tile.
        t.setFilter(Texture.TextureFilter.Linear, Texture.TextureFilter.Linear);
        t.setWrap(Texture.TextureWrap.Repeat, Texture.TextureWrap.Repeat);
        liquidTextures.put(s, t);
    }

    private static void putVegPair(Pixmap sheet, Pixmap outlineSheet,
                                   Vegetation v, int col, int row,
                                   int hCells, int sw, int sh) {
        Texture a = extractVeg(sheet, outlineSheet, col,     row, hCells, sw, sh);
        Texture b = extractVeg(sheet, outlineSheet, col + 1, row, hCells, sw, sh);
        if (a != null) vegA.put(v, a);
        if (b != null) vegB.put(v, b);
    }

    private static Texture extractVeg(Pixmap sheet, Pixmap outlineSheet,
                                      int col, int row, int hCells,
                                      int sw, int sh) {
        int x = col * VEG_CELL;
        int y = row * VEG_CELL;
        int h = hCells * VEG_CELL;
        if (x + VEG_CELL > sw || y + h > sh) return null;
        Pixmap p = new Pixmap(VEG_CELL, h, sheet.getFormat());
        p.setBlending(Pixmap.Blending.None);
        p.drawPixmap(sheet, 0, 0, x, y, VEG_CELL, h);
        Texture t = new Texture(p);
        p.dispose();
        t.setFilter(Texture.TextureFilter.Nearest, Texture.TextureFilter.Nearest);
        Texture outline = extractOutlineVeg(outlineSheet, x, y, VEG_CELL, h);
        if (outline != null) OutlineSprites.register(t, outline);
        return t;
    }

    private static Texture extractOutlineVeg(Pixmap outlineSheet, int x, int y, int w, int h) {
        if (outlineSheet == null
                || x + w > outlineSheet.getWidth()
                || y + h > outlineSheet.getHeight()) {
            return null;
        }
        Pixmap p = new Pixmap(w, h, outlineSheet.getFormat());
        p.setBlending(Pixmap.Blending.None);
        p.drawPixmap(outlineSheet, 0, 0, x, y, w, h);
        Texture t = new Texture(p);
        p.dispose();
        t.setFilter(Texture.TextureFilter.Nearest, Texture.TextureFilter.Nearest);
        return t;
    }

    private static <K> void putRegion(Map<K, TextureRegion> into, K key,
                                      int x, int y, int w, int h, int sw, int sh) {
        if (sheet == null || x + w > sw || y + h > sh) return;
        into.put(key, new TextureRegion(sheet, x, y, w, h));
    }

    /** Procedural ice tile - soft cyan with a brighter centre highlight. 32x32 to
     *  match the vegetation cell pitch. */
    private static Texture buildIceTexture() {
        int size = 32;
        Pixmap p = new Pixmap(size, size, Pixmap.Format.RGBA8888);
        p.setBlending(Pixmap.Blending.None);
        p.setColor(0.72f, 0.86f, 0.98f, 1f);
        p.fill();
        p.setColor(0.86f, 0.94f, 1f, 1f);
        for (int y = 0; y < size; y++) {
            for (int x = 0; x < size; x++) {
                float dx = (x - size / 2f) / (size / 2f);
                float dy = (y - size / 2f) / (size / 2f);
                if (dx * dx + dy * dy < 0.35f) p.drawPixel(x, y);
            }
        }
        Texture t = new Texture(p);
        p.dispose();
        t.setFilter(Texture.TextureFilter.Nearest, Texture.TextureFilter.Nearest);
        return t;
    }

    /** Fully-transparent mask - under the inverted-alpha convention this means
     *  "every pixel is water", used as variant 0 (no shores). */
    private static Texture buildAllWaterMask() {
        Pixmap p = new Pixmap(MASK_TILE, MASK_TILE, Pixmap.Format.RGBA8888);
        p.setBlending(Pixmap.Blending.None);
        p.setColor(0, 0, 0, 0);
        p.fill();
        Texture t = new Texture(p);
        p.dispose();
        t.setFilter(Texture.TextureFilter.Nearest, Texture.TextureFilter.Nearest);
        return t;
    }

    private static Texture loadIfPresent(String path) {
        if (!Gdx.files.internal(path).exists()) return null;
        try {
            Texture t = new Texture(Gdx.files.internal(path));
            t.setFilter(Texture.TextureFilter.Nearest, Texture.TextureFilter.Nearest);
            return t;
        } catch (Exception ignored) {
            return null;
        }
    }

    /** Release every cached Texture. Subsequent accessors reload on demand. */
    public static void disposeShared() {
        if (sheet != null) { sheet.dispose(); sheet = null; }
        if (iceTex != null) { iceTex.dispose(); iceTex = null; }
        if (fire1Tex != null) { fire1Tex.dispose(); fire1Tex = null; }
        if (fire2Tex != null) { fire2Tex.dispose(); fire2Tex = null; }
        disposeAll(liquidTextures); liquidTextures = null;
        disposeAll(vegA);            vegA = null;
        disposeAll(vegB);            vegB = null;
        if (maskTextures != null) {
            for (Texture t : maskTextures) if (t != null) t.dispose();
            maskTextures = null;
        }
        surfaceRegions = null;
        vegRegions = null;
    }

    private static <K> void disposeAll(Map<K, Texture> m) {
        if (m == null) return;
        for (Texture t : m.values()) if (t != null) t.dispose();
    }
}
