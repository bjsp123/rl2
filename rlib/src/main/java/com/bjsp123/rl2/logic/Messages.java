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

    // -- Level / game life-cycle ---------------------------------------------

    public static LogEvent beginGame(String playerName) {
        return new LogEvent(TextCatalog.format("eventlog.game.begin",
                                    TextCatalog.vars("player", playerName)),
                            EventPriority.HIGH, true);
    }

    /** "Achievement unlocked: First Blood." */
    public static LogEvent achievementUnlocked(String displayName) {
        return new LogEvent(TextCatalog.format("eventlog.achievement.unlocked",
                                    TextCatalog.vars("achievement", displayName)),
                            EventPriority.HIGH, true);
    }

    /** "Rogue enters level 3 (water, big rooms)." */
    public static LogEvent enterLevel(String playerName, int depth, Set<LevelFlag> flags) {
        String flagsText = "";
        if (flags != null && !flags.isEmpty()) {
            StringBuilder flagNames = new StringBuilder();
            boolean first = true;
            for (LevelFlag f : flags) {
                if (!first) flagNames.append(", ");
                flagNames.append(flagName(f));
                first = false;
            }
            flagsText = TextCatalog.format("eventlog.level.flags",
                    TextCatalog.vars("flags", flagNames));
        }
        return new LogEvent(TextCatalog.format("eventlog.level.enter",
                TextCatalog.vars("player", playerName, "depth", depth, "flags", flagsText)),
                EventPriority.HIGH, true);
    }

    private static String flagName(LevelFlag f) {
        return TextCatalog.getOrDefault("eventlog.level.flag." + f.name(),
                f.name().toLowerCase());
    }

    // -- Player combat -------------------------------------------------------

    public static LogEvent playerHit(String playerName, String target, int dmg) {
        return new LogEvent(TextCatalog.format("eventlog.combat.player.hit",
                                    TextCatalog.vars("player", playerName, "target", target, "damage", dmg)),
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
            return new LogEvent(TextCatalog.format("eventlog.combat.roll.miss",
                    TextCatalog.vars("attacker", attackerName == null ? "?" : attackerName,
                            "target", targetName == null ? "?" : targetName,
                            "element", element)), EventPriority.LOW, playerInvolved);
        } else {
            String mitigationText = "";
            if (mitigations != null && !mitigations.isEmpty()) {
                StringBuilder parts = new StringBuilder();
                for (int i = 0; i < mitigations.size(); i++) {
                    if (i > 0) parts.append(", ");
                    parts.append(mitigations.get(i));
                }
                mitigationText = TextCatalog.format("eventlog.combat.roll.mitigations",
                        TextCatalog.vars("mitigations", parts));
            }
            return new LogEvent(TextCatalog.format("eventlog.combat.roll.damage",
                    TextCatalog.vars("attacker", attackerName == null ? "?" : attackerName,
                            "target", targetName == null ? "?" : targetName,
                            "element", element, "rolled", rolled,
                            "mitigations", mitigationText, "dealt", dealt)),
                    EventPriority.LOW, playerInvolved);
        }
    }

    public static LogEvent playerMiss(String playerName, String target) {
        return new LogEvent(TextCatalog.format("eventlog.combat.player.miss",
                                    TextCatalog.vars("player", playerName, "target", target)),
                            EventPriority.LOW, true);
    }

    public static LogEvent playerKill(String playerName, String target) {
        return new LogEvent(TextCatalog.format("eventlog.combat.player.kill",
                                    TextCatalog.vars("player", playerName, "target", target)),
                            EventPriority.HIGH, true);
    }

    // -- Mob combat (no player) ----------------------------------------------

    public static LogEvent mobHit(String attacker, String target, int dmg) {
        return new LogEvent(TextCatalog.format("eventlog.combat.mob.hit",
                                    TextCatalog.vars("attacker", attacker, "target", target, "damage", dmg)),
                            EventPriority.LOW, false);
    }

    public static LogEvent mobMiss(String attacker, String target) {
        return new LogEvent(TextCatalog.format("eventlog.combat.mob.miss",
                                    TextCatalog.vars("attacker", attacker, "target", target)),
                            EventPriority.LOW, false);
    }

    public static LogEvent mobKill(String attacker, String target) {
        return new LogEvent(TextCatalog.format("eventlog.combat.mob.kill",
                                    TextCatalog.vars("attacker", attacker, "target", target)),
                            EventPriority.HIGH, false);
    }

    // -- Mob-on-player combat (involves player) ------------------------------

    public static LogEvent enemyHit(String attacker, String playerName, int dmg) {
        return new LogEvent(TextCatalog.format("eventlog.combat.enemy.hit",
                                    TextCatalog.vars("attacker", attacker, "player", playerName, "damage", dmg)),
                            EventPriority.LOW, true);
    }

    public static LogEvent enemyMiss(String attacker, String playerName) {
        return new LogEvent(TextCatalog.format("eventlog.combat.enemy.miss",
                                    TextCatalog.vars("attacker", attacker, "player", playerName)),
                            EventPriority.LOW, true);
    }

    public static LogEvent enemyKill(String attacker, String playerName) {
        return new LogEvent(TextCatalog.format("eventlog.combat.enemy.kill",
                                    TextCatalog.vars("attacker", attacker, "player", playerName)),
                            EventPriority.HIGH, true);
    }

    // -- Other --------------------------------------------------------------

    public static LogEvent pickupItem(String playerName, String itemName) {
        return new LogEvent(TextCatalog.format("eventlog.player.pickup",
                                    TextCatalog.vars("player", playerName, "item", itemName)),
                            EventPriority.HIGH, true);
    }

    /** "The Kobold picks up a healing potion." */
    public static LogEvent mobPicksUpItem(String mobName, String itemName) {
        return new LogEvent(TextCatalog.format("eventlog.mobaction.pickup",
                                    TextCatalog.vars("mob", mobName, "item", itemName)),
                            EventPriority.LOW, true);
    }

    public static LogEvent mobSpawn(String name) {
        return new LogEvent(TextCatalog.format("eventlog.mobaction.spawn",
                                    TextCatalog.vars("mob", name)),
                            EventPriority.HIGH, false);
    }

    public static LogEvent attitudeTurnsOnPlayer(String name, String reason) {
        return new LogEvent(TextCatalog.format("eventlog.mobaction.hostileToPlayer",
                TextCatalog.vars("mob", name, "reason", reason(reason))), EventPriority.HIGH, true);
    }

    public static LogEvent attitudeMobOnMob(String a, String b, String reason) {
        return new LogEvent(TextCatalog.format("eventlog.mobaction.turnsOnMob",
                TextCatalog.vars("mob", a, "target", b, "reason", reason(reason))), EventPriority.HIGH, false);
    }

    /** "The Black Ant wakes up (damaged by fire)." */
    public static LogEvent mobWakesUp(String name, String reason) {
        return new LogEvent(TextCatalog.format("eventlog.mobaction.wakes",
                TextCatalog.vars("mob", name, "reason", reason(reason))), EventPriority.LOW, false);
    }

    /** "The Kobold General uses a haste ability on the Kobold Warrior." */
    public static LogEvent mobUsesAbility(String caster, String abilityDesc, String target,
                                          boolean involvesPlayer) {
        String targetText = involvesPlayer ? target : TextCatalog.format("eventlog.mobaction.usesAbility.nonplayerTarget",
                TextCatalog.vars("target", target));
        return new LogEvent(TextCatalog.format("eventlog.mobaction.usesAbility",
                TextCatalog.vars("mob", caster, "ability", abilityDesc, "target", targetText)),
                EventPriority.LOW, involvesPlayer);
    }

    /** "The Goblin uses a healing potion." */
    public static LogEvent mobUsesItem(String mobName, String itemName, boolean involvesPlayer) {
        return new LogEvent(TextCatalog.format("eventlog.mobaction.usesItem",
                TextCatalog.vars("mob", mobName, "item", itemName)),
                EventPriority.LOW, involvesPlayer);
    }

    public static LogEvent vegetationEaten(String name, String vegetation) {
        // HIGH priority so the message survives the default log filter (LOW would be
        // hidden unless the player toggles "!"). Mushroom eating drives mouse-spawning
        // bookkeeping that the player otherwise has no way to observe - making the event
        // visible avoids "the system feels broken because nothing logs".
        return new LogEvent(TextCatalog.format("eventlog.mobaction.eatsVegetation",
                                    TextCatalog.vars("mob", name, "vegetation", vegetation)),
                            EventPriority.HIGH, false);
    }

    public static LogEvent playerUses(String playerName, String verb, String itemName) {
        String v = (verb == null || verb.isEmpty()) ? "uses" : verb + "s";
        return new LogEvent(TextCatalog.format("eventlog.player.uses",
                                    TextCatalog.vars("player", playerName, "verb", v, "item", itemName)),
                            EventPriority.LOW, true);
    }

    /** "The rope can't pull the kobold." */
    public static LogEvent grappleBlocked(String targetName) {
        return new LogEvent(TextCatalog.format("eventlog.player.grappleBlocked",
                                    TextCatalog.vars("target", targetName)),
                            EventPriority.HIGH, true);
    }

    public static LogEvent playerStarves(String playerName) {
        return new LogEvent(TextCatalog.format("eventlog.player.starves",
                                    TextCatalog.vars("player", playerName)),
                            EventPriority.HIGH, true);
    }

    /** "Adventurer eats the apple." - HIGH-priority so the player sees it
     *  in the default log filter; food is a meaningful resource event. */
    public static LogEvent playerEats(String playerName, String itemName) {
        return new LogEvent(TextCatalog.format("eventlog.player.eats",
                                    TextCatalog.vars("player", playerName, "item", itemName)),
                            EventPriority.HIGH, true);
    }

    private static String reason(String reason) {
        return reason != null && !reason.isEmpty()
                ? TextCatalog.format("eventlog.reason.parenthesized",
                        TextCatalog.vars("reason", reason))
                : "";
    }
}
