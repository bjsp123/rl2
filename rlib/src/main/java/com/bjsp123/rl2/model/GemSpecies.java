package com.bjsp123.rl2.model;

import com.bjsp123.rl2.model.Level.VisualTheme;

/**
 * Gem species (RL-47 roster). Each gem has:
 * <ul>
 *   <li>an <b>affinity</b> ({@link VisualTheme}) - a soft spawn bias toward the matching
 *       dungeon theme (matching levels roll it more often), reusing the same theme-weighting
 *       items use. It is NOT a hard gate, so a metal gem with no shiny variant still appears
 *       (less often) on shiny levels.</li>
 *   <li>a <b>rarity class</b> ({@link GemClass}): BASIC (common), METAL (rare), EXOTIC
 *       (very rare).</li>
 *   <li>a packed RGBA8888 colour for any procedural / tint fallback.</li>
 * </ul>
 * Sprites come from {@code gems2.png}; the per-species cell lives renderer-side in
 * {@code GemSprites}. There is no gem size system.
 */
public enum GemSpecies {
    // Basic - one per affinity.
    LETTUSTONE  (VisualTheme.CONCRETE, GemClass.BASIC,  0x77bb77ff),
    HAMETHYST   (VisualTheme.CRYSTAL,  GemClass.BASIC,  0xaa22aaff),
    SALAMITE    (VisualTheme.GOTHIC,   GemClass.BASIC,  0xdd5533ff),
    ICELANDSPAR (VisualTheme.SHINY,    GemClass.BASIC,  0xcceeffff),

    // Metal - rare. No shiny variant.
    SILVER      (VisualTheme.CONCRETE, GemClass.METAL,  0xccccd0ff),
    COPPER      (VisualTheme.CRYSTAL,  GemClass.METAL,  0xcc7744ff),
    GOLD        (VisualTheme.GOTHIC,   GemClass.METAL,  0xeecc33ff),

    // Exotic - very rare. One per affinity.
    BLOODHIVE   (VisualTheme.GOTHIC,   GemClass.EXOTIC, 0xaa1133ff),
    BLACKGLASS  (VisualTheme.SHINY,    GemClass.EXOTIC, 0x222233ff),
    MALACHOR    (VisualTheme.CONCRETE, GemClass.EXOTIC, 0x33aa66ff),
    FLUORON     (VisualTheme.CRYSTAL,  GemClass.EXOTIC, 0x22ddeeff);

    /** Rarity class - controls per-level spawn counts and special-drop rolls. */
    public enum GemClass { BASIC, METAL, EXOTIC }

    /** Affinity: the dungeon theme this gem is biased to spawn on. */
    public final VisualTheme theme;
    /** Rarity class. */
    public final GemClass gemClass;
    /** Packed RGBA8888 colour for any procedural / tint fallback. */
    public final int rgba;

    GemSpecies(VisualTheme theme, GemClass gemClass, int rgba) {
        this.theme    = theme;
        this.gemClass = gemClass;
        this.rgba     = rgba;
    }

    /** Pretty species name for display ("Lettustone"). */
    public String pretty() {
        String s = name().toLowerCase();
        return Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }
}
