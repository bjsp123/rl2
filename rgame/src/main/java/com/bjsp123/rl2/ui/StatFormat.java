package com.bjsp123.rl2.ui;

import com.bjsp123.rl2.model.MinMax;

/**
 * Shared player-facing formatting for combat stats.
 *
 * <p>Two rules the UI kept getting wrong:
 * <ul>
 *   <li>Damage is <em>always</em> shown with its armour-piercing component (a
 *       {@code " +Nap"} suffix), so a weapon's real output against armoured
 *       targets is legible - the plain {@code damage} range hides AP entirely.</li>
 *   <li>Action costs (move / attack) render as an intuitive <em>speed</em>
 *       percentage where higher = faster, so a haste buff (which lowers the tick
 *       cost) makes the number go up, not down.</li>
 * </ul>
 */
public final class StatFormat {

    private StatFormat() {}

    /** The standard action-cost tick that maps to 100% speed. A hasted cost of
     *  50 reads as 200%; a slowed cost of 200 reads as 50%. */
    public static final int BASE_ACTION_COST = 100;

    /** {@code "min-max"} damage with a trailing {@code " +Nap"} (or
     *  {@code " +lo-hiap"}) whenever the attacker deals armour-piercing damage. */
    public static String damage(MinMax dmg, MinMax ap) {
        String base = dmg.min() + "-" + dmg.max();
        if (ap == null || ap.isZero()) return base;
        String apStr = ap.min() == ap.max()
                ? ap.min() + "ap"
                : ap.min() + "-" + ap.max() + "ap";
        return base + " +" + apStr;
    }

    /** A cost in game ticks rendered as a speed percentage (higher = faster).
     *  {@code BASE_ACTION_COST} ticks = 100%. */
    public static String speed(int cost) {
        if (cost <= 0) return "-";
        return Math.round(100.0 * BASE_ACTION_COST / cost) + "%";
    }
}
