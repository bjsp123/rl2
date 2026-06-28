package com.bjsp123.rl2.model;

/**
 * Gem species (RL-47 roster). The enum carries only identity + rarity
 * {@link GemClass} (structural - recipes match on it). The per-species
 * <b>affinity</b> and <b>sprite cell</b> are data-driven: see
 * {@code assets/data/gems.csv} / {@link com.bjsp123.rl2.logic.GemDefinition}
 * (looked up via {@code Registries.gem(species)}). Display names + descriptions
 * live in strings.csv ({@code gem.<SPECIES>.name} / {@code .description}).
 */
public enum GemSpecies {
    // Basic - common.
    LETTUSTONE  (GemClass.BASIC),
    HAMETHYST   (GemClass.BASIC),
    SALAMITE    (GemClass.BASIC),
    ICELANDSPAR (GemClass.BASIC),

    // Metal - rare. No shiny variant.
    SILVER      (GemClass.METAL),
    COPPER      (GemClass.METAL),
    GOLD        (GemClass.METAL),

    // Exotic - very rare.
    BLOODHIVE   (GemClass.EXOTIC),
    BLACKGLASS  (GemClass.EXOTIC),
    MALACHOR    (GemClass.EXOTIC),
    FLUORON     (GemClass.EXOTIC);

    /** Rarity class - controls per-level spawn counts and special-drop rolls. */
    public enum GemClass { BASIC, METAL, EXOTIC }

    /** Rarity class. */
    public final GemClass gemClass;

    GemSpecies(GemClass gemClass) {
        this.gemClass = gemClass;
    }

    /** Pretty species name for display ("Lettustone"). */
    public String pretty() {
        String s = name().toLowerCase();
        return Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }
}
