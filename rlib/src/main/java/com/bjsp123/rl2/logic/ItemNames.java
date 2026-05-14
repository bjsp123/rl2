package com.bjsp123.rl2.logic;

import com.bjsp123.rl2.model.Item;
import com.bjsp123.rl2.model.Mob;

/** Naming helpers for player-facing item labels. */
public final class ItemNames {
    private ItemNames() {}

    public static String displayName(Item item, Mob holder) {
        if (item == null) return "";
        if (item.isGem()) return gemDisplayName(item);
        String name = item.name == null ? "" : item.name;
        int lvl = ItemStats.effectiveLevel(item, holder);
        if (lvl > 0) name = name + " +" + lvl;
        if (item.brand != null && item.brand.name != null && !item.brand.name.isEmpty()) {
            name = name + " of " + item.brand.name;
        }
        return name;
    }

    public static String displayName(Item item) {
        return displayName(item, null);
    }

    public static String gemSizePrefix(int size) {
        return switch (Math.max(1, Math.min(9, size))) {
            case 1 -> "tiny";
            case 2 -> "small";
            case 3 -> "medium";
            case 4 -> "large";
            case 5 -> "fine";
            case 6 -> "impressive";
            case 7 -> "mighty";
            case 8 -> "sublime";
            default -> "exquisite";
        };
    }

    public static String gemDisplayName(Item item) {
        if (item == null || item.gemSpecies == null) return "gem";
        return gemSizePrefix(item.gemSize) + " "
                + item.gemSpecies.name().toLowerCase();
    }
}
