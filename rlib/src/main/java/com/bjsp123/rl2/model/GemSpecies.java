package com.bjsp123.rl2.model;

import com.bjsp123.rl2.model.Level.VisualTheme;

/**
 * Gem species. Each species has a fixed dungeon-theme home (so a CRYSTAL level only spawns
 * crystal-theme gems and likewise for CONCRETE), a rarity tier within that theme that
 * controls how often it spawns at the per-level draw, and a packed RGBA8888 colour used by
 * the procedural icon renderer.
 *
 * <p>Shape comes from the theme: crystal gems render as triangles, concrete gems as squares.
 * Colour is per-species; size grows with the gem's prefix tier (tiny ... exquisite).
 */
public enum GemSpecies {
    BLAZINGSTAR (VisualTheme.CRYSTAL,  1, 0xff5522ff),  // hot orange-red
    AZURITE     (VisualTheme.CRYSTAL,  1, 0x2266ffff),  // azure blue
    AMBERGLEAM  (VisualTheme.CRYSTAL,  1, 0xffcc00ff),  // amber yellow
    SCINTILLIUM (VisualTheme.CRYSTAL,  2, 0xff44ddff),  // magenta
    GLITTERSHARD(VisualTheme.CRYSTAL,  3, 0x66ffeeff),  // cyan

    CUPRIUM    (VisualTheme.CONCRETE, 1, 0x888888ff),  // iron grey
    ARGENTEL     (VisualTheme.CONCRETE, 1, 0x4455aaff),  // dull blue-grey
    AURELIUM  (VisualTheme.CONCRETE, 1, 0x333333ff),  // smoked black
    PETRICHOR   (VisualTheme.CONCRETE, 2, 0x88aa55ff),  // mossy green
    STEELROCK   (VisualTheme.CONCRETE, 3, 0xaaccffff),  // steel sheen

    BLOODGLASS    (VisualTheme.STRAIGHTFORWARD, 1, 0xaa5588ff), // blood red  
    SLIPGLASS     (VisualTheme.STRAIGHTFORWARD, 1, 0xaa9966ff), // sickly yellow-green
    JADEGLASS  (VisualTheme.STRAIGHTFORWARD, 1, 0x33bb66ff),  // jade green
    MILKSPAR   (VisualTheme.STRAIGHTFORWARD, 2, 0xddddddff),  // milky 
    MALACHOR   (VisualTheme.STRAIGHTFORWARD, 3, 0x66ee88ff); // malachite green

    /** Dungeon theme this gem appears in. */
    public final VisualTheme theme;
    /** Rarity tier 1-3. Tier 1 species are common, tier 3 species are rare. The same-kind
     *  size-up recipe never crosses species, so tier doesn't affect the recipe graph. */
    public final int tier;
    /** Packed RGBA8888 colour for the procedural gem icon. */
    public final int rgba;

    GemSpecies(VisualTheme theme, int tier, int rgba) {
        this.theme = theme;
        this.tier  = tier;
        this.rgba  = rgba;
    }

    /** Pretty species name for display ("Blazingstar"). Used by gem display-name composition
     *  alongside the size prefix. */
    public String pretty() {
        String s = name().toLowerCase();
        return Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }
}
