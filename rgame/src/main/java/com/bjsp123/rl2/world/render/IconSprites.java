package com.bjsp123.rl2.world.render;

import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.graphics.g2d.TextureRegion;

/**
 * UI icon sheet sourced from {@code assets/ui/icons.png}. The atlas is a single
 * row of 32x32 cells; {@link Icon} enumerates each slot by name so callers can
 * just say {@code IconSprites.regionFor(Icon.BOOK)} without sweeping the
 * column index through their own code. Lazy-loaded the same way every other
 * sprite sheet in the game is - first {@link #regionFor} call pulls the PNG
 * off disk and caches the per-cell {@link TextureRegion}s.
 *
 * <p>Drop the new icon you want into the next free column on the right, add
 * its enum constant to {@link Icon} in the same order, and that's it; the
 * cache and dispose paths sweep all enum values automatically.
 */
public final class IconSprites {

    /** Cell size on {@code ui/icons.png} - every icon is the same square. */
    private static final int CELL = 32;

    /** Path under the libGDX internal-files root. */
    private static final String SHEET_PATH = "ui/icons.png";

    /** One enum constant per cell, in left-to-right order on the sheet.
     *  {@link #ordinal()} is the column index, so adding a new icon is a
     *  one-line append. */
    public enum Icon {
        BOOK,
        INFO,
        EQUIPMENT,
        ITEMS,
        GEMS,
        MOBS,
        PERKS,
        TERRAIN,
        LOOK,
        MAP,
        CHARACTER,
        GAME,
        BACK,
        VIDEO,
        SOUND,
        FOOD,
        INV,
        CANCEL,
        SETTINGS,
        COMPASS
    }

    private static Texture sheet;
    private static final TextureRegion[] regions = new TextureRegion[Icon.values().length];

    private IconSprites() {}

    /** Lazy-loaded region for the given icon. Returns {@code null} if the
     *  sheet failed to load (missing asset, headless boot, etc.) so callers
     *  can fall back to a text label without a crash. */
    public static TextureRegion regionFor(Icon icon) {
        if (icon == null) return null;
        loadSheet();
        if (sheet == null) return null;
        int i = icon.ordinal();
        if (regions[i] == null) {
            regions[i] = new TextureRegion(sheet, i * CELL, 0, CELL, CELL);
        }
        return regions[i];
    }

    private static void loadSheet() {
        if (sheet != null) return;
        try {
            sheet = new Texture(com.badlogic.gdx.Gdx.files.internal(SHEET_PATH));
            sheet.setFilter(Texture.TextureFilter.Nearest, Texture.TextureFilter.Nearest);
        } catch (Exception ignored) {
            sheet = null;
        }
    }

    /** Release the icon sheet. Call on shutdown if you care about clean
     *  tear-down; the next {@link #regionFor} call reloads it. */
    public static void disposeShared() {
        if (sheet != null) {
            sheet.dispose();
            sheet = null;
        }
        for (int i = 0; i < regions.length; i++) regions[i] = null;
    }
}
