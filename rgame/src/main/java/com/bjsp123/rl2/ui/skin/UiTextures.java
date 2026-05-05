package com.bjsp123.rl2.ui.skin;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.graphics.g2d.TextureRegion;
import com.badlogic.gdx.scenes.scene2d.utils.Drawable;
import com.badlogic.gdx.scenes.scene2d.utils.TextureRegionDrawable;

/**
 * Lazy-loaded UI atlas at {@code ui/textures.png}. Currently exposes a single
 * 512×512 {@link #windowBackground()} region used as the surrounding-window
 * backdrop on full-screen menu screens (see {@link com.bjsp123.rl2.screen.MenuScreen}).
 *
 * <p>The atlas is 1024×1024 with the window-background sprite anchored at the
 * top-left; remaining quadrants are reserved for future UI chrome and can be
 * exposed via additional accessors as needed.
 *
 * <p>Singleton lazy-loader following the same pattern as the other sprite-source
 * helpers in {@code world.render} — file IO happens once, the cached texture
 * lives for the JVM lifetime, and {@link #disposeShared()} releases it.
 */
public final class UiTextures {

    private static final String PATH = "ui/textures.png";
    /** Side length in source pixels of the surrounding-window background sprite. */
    public static final int WINDOW_BG_SIZE = 512;

    private static Texture sheet;
    private static TextureRegion windowBg;
    private static Drawable      windowBgDrawable;

    private UiTextures() {}

    /** {@link TextureRegion} of the surrounding-window background — top-left 512×512
     *  quadrant of the atlas. {@code null} if the file failed to load. */
    public static TextureRegion windowBackground() {
        if (sheet == null) load();
        return windowBg;
    }

    /** Drawable form of {@link #windowBackground()} — suitable for use as a Table /
     *  Container background. The drawable stretches to whatever size the parent
     *  cell allocates, so callers don't have to care about the source 512×512 size.
     *  {@code null} if the file failed to load. */
    public static Drawable windowBackgroundDrawable() {
        if (sheet == null) load();
        if (windowBgDrawable == null && windowBg != null) {
            windowBgDrawable = new TextureRegionDrawable(windowBg);
        }
        return windowBgDrawable;
    }

    /** {@link #windowBackgroundDrawable()} or {@code fallback} when the atlas didn't
     *  load — convenience for popup wiring so each call site stays a single line.
     *  Typical use: {@code UiTextures.windowBackgroundOr(skin.getDrawable("panel"))}. */
    public static Drawable windowBackgroundOr(Drawable fallback) {
        return fallback;
        //Drawable d = windowBackgroundDrawable();
       // return d != null ? d : fallback;
    }

    private static void load() {
        try {
            sheet = new Texture(Gdx.files.internal(PATH));
            sheet.setFilter(Texture.TextureFilter.Linear, Texture.TextureFilter.Linear);
        } catch (Exception ignored) {
            return;
        }
        int sw = sheet.getWidth(), sh = sheet.getHeight();
        if (sw >= WINDOW_BG_SIZE && sh >= WINDOW_BG_SIZE) {
            windowBg = new TextureRegion(sheet, 0,WINDOW_BG_SIZE, WINDOW_BG_SIZE, WINDOW_BG_SIZE);
        }
    }

    /** Release the cached atlas. Subsequent accessors reload on demand. */
    public static void disposeShared() {
        if (sheet != null) { sheet.dispose(); sheet = null; }
        windowBg = null;
        windowBgDrawable = null;
    }
}
