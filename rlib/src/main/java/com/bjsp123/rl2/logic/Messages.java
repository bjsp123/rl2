package com.bjsp123.rl2.logic;

import com.bjsp123.rl2.model.LogEvent.EventPriority;
import com.bjsp123.rl2.model.Level.LevelFlag;
import com.bjsp123.rl2.model.LogEvent;

import java.util.Set;

/**
 * Central template store for user-facing text. All strings that might one day need
 * localisation live here as factory methods returning {@link LogEvent}s. Callers never
 * build text inline; they ask this class for the appropriate sentence.
 *
 * <p>When we add i18n we'll swap the hardcoded strings for a lookup against a message
 * bundle keyed on the method name.
 */
public final class Messages {

    private Messages() {}

    // ── Level / game life-cycle ─────────────────────────────────────────────

    public static LogEvent beginGame(String playerName) {
        return new LogEvent(playerName + " begins a new adventure.",
                            EventPriority.HIGH, true);
    }

    /** "Rogue enters level 3 (water, big rooms)." */
    public static LogEvent enterLevel(String playerName, int depth, Set<LevelFlag> flags) {
        StringBuilder sb = new StringBuilder(playerName);
        sb.append(" enters level ").append(depth);
        if (flags != null && !flags.isEmpty()) {
            sb.append(" (");
            boolean first = true;
            for (LevelFlag f : flags) {
                if (!first) sb.append(", ");
                sb.append(flagName(f));
                first = false;
            }
            sb.append(')');
        }
        sb.append('.');
        return new LogEvent(sb.toString(), EventPriority.HIGH, true);
    }

    private static String flagName(LevelFlag f) {
        switch (f) {
            case WATER:         return "water";
            case WALKWAY_LEVEL: return "plank corridors";
            case PLANTS:        return "plants";
            case BIGLEVEL:      return "big level";
            case ROUGH:         return "rough";
            default:            return f.name().toLowerCase();
        }
    }

    // ── Player combat ───────────────────────────────────────────────────────

    public static LogEvent playerHit(String playerName, String target, int dmg) {
        return new LogEvent(playerName + " hits the " + target + " for " + dmg + ".",
                            EventPriority.LOW, true);
    }

    /** Verbose per-attack tuning line. Format:
     *  {@code "Goblin -> Player: PHYSICAL 8 [armor -3, PROTECTION -1] -> 4"}.
     *  When {@code rolled == 0} (a miss), the body collapses to {@code "miss"}. */
    public static LogEvent damageRoll(String attackerName, String targetName,
                                      String element, int rolled, int dealt,
                                      java.util.List<String> mitigations,
                                      boolean playerInvolved) {
        StringBuilder sb = new StringBuilder();
        sb.append(attackerName == null ? "?" : attackerName).append(" -> ");
        sb.append(targetName   == null ? "?" : targetName);
        sb.append(": ").append(element);
        if (rolled <= 0 && (mitigations == null || mitigations.isEmpty())) {
            sb.append(" miss");
        } else {
            sb.append(' ').append(rolled);
            if (mitigations != null && !mitigations.isEmpty()) {
                sb.append(" [");
                for (int i = 0; i < mitigations.size(); i++) {
                    if (i > 0) sb.append(", ");
                    sb.append(mitigations.get(i));
                }
                sb.append(']');
            }
            sb.append(" -> ").append(dealt);
        }
        return new LogEvent(sb.toString(), EventPriority.LOW, playerInvolved);
    }

    public static LogEvent playerMiss(String playerName, String target) {
        return new LogEvent(playerName + " misses the " + target + ".",
                            EventPriority.LOW, true);
    }

    public static LogEvent playerKill(String playerName, String target) {
        return new LogEvent(playerName + " kills the " + target + "!",
                            EventPriority.HIGH, true);
    }

    // ── Mob combat (no player) ──────────────────────────────────────────────

    public static LogEvent mobHit(String attacker, String target, int dmg) {
        return new LogEvent("The " + attacker + " hits the " + target + " for " + dmg + ".",
                            EventPriority.LOW, false);
    }

    public static LogEvent mobMiss(String attacker, String target) {
        return new LogEvent("The " + attacker + " misses the " + target + ".",
                            EventPriority.LOW, false);
    }

    public static LogEvent mobKill(String attacker, String target) {
        return new LogEvent("The " + attacker + " kills the " + target + "!",
                            EventPriority.HIGH, false);
    }

    // ── Mob-on-player combat (involves player) ──────────────────────────────

    public static LogEvent enemyHit(String attacker, String playerName, int dmg) {
        return new LogEvent("The " + attacker + " hits " + playerName + " for " + dmg + ".",
                            EventPriority.LOW, true);
    }

    public static LogEvent enemyMiss(String attacker, String playerName) {
        return new LogEvent("The " + attacker + " misses " + playerName + ".",
                            EventPriority.LOW, true);
    }

    public static LogEvent enemyKill(String attacker, String playerName) {
        return new LogEvent("The " + attacker + " kills " + playerName + "!",
                            EventPriority.HIGH, true);
    }

    // ── Other ──────────────────────────────────────────────────────────────

    public static LogEvent pickupItem(String playerName, String itemName) {
        return new LogEvent(playerName + " picks up a " + itemName + ".",
                            EventPriority.HIGH, true);
    }

    public static LogEvent mobSpawn(String name) {
        return new LogEvent("A " + name + " appears.",
                            EventPriority.HIGH, false);
    }

    public static LogEvent attitudeTurnsOnPlayer(String name, String playerName) {
        return new LogEvent("The " + name + " turns on " + playerName + "!",
                            EventPriority.HIGH, true);
    }

    public static LogEvent attitudeMobOnMob(String a, String b) {
        return new LogEvent("The " + a + " turns on the " + b + ".",
                            EventPriority.HIGH, false);
    }

    public static LogEvent vegetationEaten(String name, String vegetation) {
        // HIGH priority so the message survives the default log filter (LOW would be
        // hidden unless the player toggles "!"). Mushroom eating drives mouse-spawning
        // bookkeeping that the player otherwise has no way to observe — making the event
        // visible avoids "the system feels broken because nothing logs".
        return new LogEvent("The " + name + " eats the " + vegetation + ".",
                            EventPriority.HIGH, false);
    }

    public static LogEvent playerUses(String playerName, String verb, String itemName) {
        String v = (verb == null || verb.isEmpty()) ? "uses" : verb + "s";
        return new LogEvent(playerName + " " + v + " the " + itemName + ".",
                            EventPriority.LOW, true);
    }

    public static LogEvent playerStarves(String playerName) {
        return new LogEvent(playerName + " is starving!",
                            EventPriority.HIGH, true);
    }
}
