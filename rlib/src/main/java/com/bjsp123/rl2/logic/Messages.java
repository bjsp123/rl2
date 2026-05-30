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

    /** "{target} takes N {element} damage." Emitted by
     *  {@link MobSystem#processAttack} for every non-PHYSICAL damage element
     *  (MAGIC / FIRE / POISON / SHOCK / STARVATION). The element name is
     *  resolved via {@code eventlog.damageElement.<name>} so locales can map
     *  enum names ("POISON") to display strings ("poison" / "venom"). */
    public static LogEvent elementalDamage(String targetName,
                                           MobSystem.DamageElement element,
                                           int dmg, boolean playerInvolved) {
        String elementText = TextCatalog.getOrDefault(
                "eventlog.damageElement." + element.name().toLowerCase(),
                element.name().toLowerCase());
        return new LogEvent(TextCatalog.format("eventlog.combat.elementalDamage",
                                    TextCatalog.vars("target", targetName == null ? "?" : targetName,
                                            "damage", dmg, "element", elementText)),
                            EventPriority.HIGH, playerInvolved);
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

    public static LogEvent surpriseAttack(String attacker, String target, boolean playerInvolved) {
        return new LogEvent(TextCatalog.format("eventlog.combat.surprise",
                                    TextCatalog.vars("attacker", attacker, "target", target)),
                            EventPriority.HIGH, playerInvolved);
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

    /** "{player} tames the {mob}!" - HIGH-priority; taming is a meaningful player action. */
    public static LogEvent mobTamed(String playerName, String mobName) {
        return new LogEvent(TextCatalog.format("eventlog.mob.tamed",
                                    TextCatalog.vars("player", playerName, "mob", mobName)),
                            EventPriority.HIGH, true);
    }

    private static String reason(String reason) {
        return reason != null && !reason.isEmpty()
                ? TextCatalog.format("eventlog.reason.parenthesized",
                        TextCatalog.vars("reason", reason))
                : "";
    }

    // -- Buff / status messages ----------------------------------------------

    /** "Adventurer is on fire." / "The kobold is poisoned." Fires when a fresh
     *  buff is applied to a mob (not on duration / level refresh). HIGH
     *  priority when the player is affected so the kill / death-screen log
     *  picks it up; LOW otherwise to keep the rolling log readable. */
    public static LogEvent buffApplied(String targetName, String buffName, boolean involvesPlayer) {
        return new LogEvent(TextCatalog.format("eventlog.buff.applied",
                                    TextCatalog.vars("target", targetName, "buff", buffName)),
                            involvesPlayer ? EventPriority.HIGH : EventPriority.LOW,
                            involvesPlayer);
    }

    /** "Adventurer is no longer on fire." Fires when a buff's duration drains
     *  to zero (natural expiry). Manual removes via removeBuff (chain effects
     *  like REGEN removing POISONED, HOPE removing FRIGHTENED) are silent
     *  by design. */
    public static LogEvent buffExpired(String targetName, String buffName, boolean involvesPlayer) {
        return new LogEvent(TextCatalog.format("eventlog.buff.expired",
                                    TextCatalog.vars("target", targetName, "buff", buffName)),
                            involvesPlayer ? EventPriority.HIGH : EventPriority.LOW,
                            involvesPlayer);
    }

    // -- Item / inventory messages -------------------------------------------

    /** "Adventurer throws a fire bomb." Fires when the projectile launches,
     *  not when it lands. */
    public static LogEvent itemThrown(String throwerName, String itemName, boolean involvesPlayer) {
        return new LogEvent(TextCatalog.format("eventlog.item.thrown",
                                    TextCatalog.vars("thrower", throwerName, "item", itemName)),
                            involvesPlayer ? EventPriority.HIGH : EventPriority.LOW,
                            involvesPlayer);
    }

    /** "The fire bomb detonates!" - fires at impact for damage-dealing bombs. */
    public static LogEvent bombDetonates(String itemName) {
        return new LogEvent(TextCatalog.format("eventlog.bomb.detonates",
                                    TextCatalog.vars("item", itemName)),
                            EventPriority.HIGH, true);
    }

    public static LogEvent itemEquipped(String playerName, String itemName) {
        return new LogEvent(TextCatalog.format("eventlog.item.equipped",
                                    TextCatalog.vars("player", playerName, "item", itemName)),
                            EventPriority.LOW, true);
    }

    public static LogEvent itemUnequipped(String playerName, String itemName) {
        return new LogEvent(TextCatalog.format("eventlog.item.unequipped",
                                    TextCatalog.vars("player", playerName, "item", itemName)),
                            EventPriority.LOW, true);
    }

    public static LogEvent powerupAbsorbed(String playerName, String itemName) {
        return new LogEvent(TextCatalog.format("eventlog.powerup.absorbed",
                                    TextCatalog.vars("player", playerName, "item", itemName)),
                            EventPriority.HIGH, true);
    }

    // -- World / movement messages -------------------------------------------

    public static LogEvent stairsDescended(String playerName, int newDepth) {
        return new LogEvent(TextCatalog.format("eventlog.stairs.descended",
                                    TextCatalog.vars("player", playerName, "depth", newDepth)),
                            EventPriority.HIGH, true);
    }

    public static LogEvent stairsAscended(String playerName, int newDepth) {
        return new LogEvent(TextCatalog.format("eventlog.stairs.ascended",
                                    TextCatalog.vars("player", playerName, "depth", newDepth)),
                            EventPriority.HIGH, true);
    }

    public static LogEvent doorOpened(String moverName, boolean involvesPlayer) {
        return new LogEvent(TextCatalog.format("eventlog.door.opened",
                                    TextCatalog.vars("mover", moverName)),
                            EventPriority.LOW, involvesPlayer);
    }

    public static LogEvent doorClosed(String moverName, boolean involvesPlayer) {
        return new LogEvent(TextCatalog.format("eventlog.door.closed",
                                    TextCatalog.vars("mover", moverName)),
                            EventPriority.LOW, involvesPlayer);
    }

    public static LogEvent doorBroken(String moverName, boolean involvesPlayer) {
        return new LogEvent(TextCatalog.format("eventlog.door.broken",
                                    TextCatalog.vars("mover", moverName)),
                            EventPriority.HIGH, involvesPlayer);
    }

    public static LogEvent mobFellInChasm(String mobName, boolean involvesPlayer) {
        return new LogEvent(TextCatalog.format("eventlog.mob.fellInChasm",
                                    TextCatalog.vars("mob", mobName)),
                            EventPriority.HIGH, involvesPlayer);
    }

    public static LogEvent knockbackSlam(String mobName, int damage, boolean involvesPlayer) {
        return new LogEvent(TextCatalog.format("eventlog.knockback.slam",
                                    TextCatalog.vars("mob", mobName, "damage", damage)),
                            involvesPlayer ? EventPriority.HIGH : EventPriority.LOW,
                            involvesPlayer);
    }

    public static LogEvent beaconActivated(String playerName) {
        return new LogEvent(TextCatalog.format("eventlog.beacon.activated",
                                    TextCatalog.vars("player", playerName)),
                            EventPriority.HIGH, true);
    }
}
