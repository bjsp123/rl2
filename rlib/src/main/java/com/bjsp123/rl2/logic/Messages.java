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
        return playerHit(playerName, target, dmg, 0);
    }

    /** Player-attacker hit with optional knockback annotation. When
     *  {@code kbSquares > 0} the message ends with ", knocking the {target}
     *  back N." so the chained slam log lines that follow read as a
     *  natural narrative continuation. */
    public static LogEvent playerHit(String playerName, String target, int dmg, int kbSquares) {
        String key = kbSquares > 0
                ? "eventlog.combat.player.hit.knockback"
                : "eventlog.combat.player.hit";
        return new LogEvent(TextCatalog.format(key,
                                    TextCatalog.vars("player", playerName, "target", target,
                                            "damage", dmg, "kb", kbSquares)),
                            EventPriority.LOW, true);
    }

    /** "{target} takes N {element} damage from {origin}." (or "..." with no
     *  attribution if {@code originName} is null/empty). Emitted by
     *  {@link MobSystem#processAttack} for every non-PHYSICAL damage element
     *  (MAGIC / FIRE / POISON / SHOCK / STARVATION). The element name is
     *  resolved via {@code eventlog.damageElement.<name>} so locales can map
     *  enum names ("POISON") to display strings ("poison" / "venom").
     *  {@code originName} is the formatted attribution string (e.g.
     *  {@code "the Kobold's fire wand"}); when present a string key with the
     *  "From" suffix is selected. */
    public static LogEvent elementalDamage(String targetName,
                                           MobSystem.DamageElement element,
                                           int dmg, String originName,
                                           boolean playerInvolved) {
        String elementText = TextCatalog.getOrDefault(
                "eventlog.damageElement." + element.name().toLowerCase(),
                element.name().toLowerCase());
        String key = (originName != null && !originName.isEmpty())
                ? "eventlog.combat.elementalDamageFrom"
                : "eventlog.combat.elementalDamage";
        return new LogEvent(TextCatalog.format(key,
                                    TextCatalog.vars("target", targetName == null ? "?" : targetName,
                                            "damage", dmg, "element", elementText,
                                            "origin", originName == null ? "" : originName)),
                            EventPriority.HIGH, playerInvolved);
    }

    /** Render a {@link MobSystem.DamageCause} as a human-readable attribution
     *  suffix - {@code "the Kobold's fire wand"} (with item), {@code "the
     *  Kobold"} (no item), or {@code null} when the cause has no origin. The
     *  level argument lets the formatter use the same display-name rules
     *  ("the Kobold" vs "Kobold") as the combat log. */
    public static String formatCauseOrigin(com.bjsp123.rl2.model.Level level,
                                           MobSystem.DamageCause cause) {
        if (cause == null || cause.origin() == null) return null;
        String mobName = MobSystem.nameForLog(level, cause.origin());
        if (cause.originItem() != null && cause.originItem().name != null) {
            return mobName + "'s " + cause.originItem().name;
        }
        return mobName;
    }

    /** Compose the death-screen headline from the last fatal cause + element.
     *  Examples:
     *  <ul>
     *    <li>"Rogue burned to death in a fire caused by Kobold's fire wand."</li>
     *    <li>"Warrior was shoved into a wall by Kobold's blast bomb."</li>
     *    <li>"Mage starved to death."</li>
     *    <li>"Mage was poisoned to death by Spider."</li>
     *    <li>"Warrior fell into a chasm."</li>
     *  </ul>
     *  The verb phrase comes from the cause's {@code medium}; the origin
     *  attribution comes from {@link #formatCauseOrigin}. Falls back to a
     *  generic "{class} was killed." when no cause data is available
     *  (legacy saves, etc.). */
    public static String deathHeadline(com.bjsp123.rl2.model.Level level,
                                       String className,
                                       MobSystem.DamageCause cause,
                                       MobSystem.DamageElement element) {
        String cls = className == null || className.isEmpty()
                ? TextCatalog.getOrDefault("eventlog.fallback.adventurer", "Adventurer")
                : className;
        String origin = formatCauseOrigin(level, cause);
        String medium = cause != null ? cause.medium() : null;
        // Phrasing first, then attribution suffix.
        String verb;
        boolean attributable = true;
        if (medium == null) {
            verb = "was killed";
            attributable = false;
        } else switch (medium) {
            case "fire-dot"   -> verb = "burned to death in a fire";
            case "poison-dot" -> verb = "was poisoned to death";
            case "bleed"      -> verb = "bled to death";
            case "wall-slam"  -> verb = "was shoved into a wall";
            case "fall"       -> { verb = "fell into a chasm"; attributable = false; }
            case "lightning"  -> verb = "was electrocuted";
            case "blast"      -> verb = "was blasted apart";
            case "missile"    -> verb = "was struck down";
            case "magic"      -> verb = "was killed by magic";
            case "throw"      -> verb = "was struck down";
            case "blow"       -> {
                if (element == MobSystem.DamageElement.STARVATION) {
                    verb = "starved to death";
                    attributable = false;
                } else {
                    verb = "was killed";
                }
            }
            default -> verb = "was killed";
        }
        if (origin != null && !origin.isEmpty() && attributable) {
            String connector = medium != null && medium.equals("fire-dot")
                    ? " caused by " : " by ";
            return cls + " " + verb + connector + origin + ".";
        }
        return cls + " " + verb + ".";
    }

    /** Human-readable LOW-priority hit / miss line emitted for every
     *  {@link MobSystem#processAttack} call. Format:
     *  <ul>
     *    <li>"Roach hits Warrior for 3 damage." (PHYSICAL, no mitigation)</li>
     *    <li>"Wraith hits Warrior for 5 fire damage." (non-physical)</li>
     *    <li>"Roach hits Warrior for 3 damage (rolled 6, armor -2, PROTECTION -1)." (with mitigation)</li>
     *    <li>"Warrior misses the roach." (miss)</li>
     *  </ul>
     *  When mitigation is present, the math is appended in a trailing
     *  parenthetical so the player can audit damage rolls without the line
     *  becoming a tuning-log dump. Element is omitted for PHYSICAL since
     *  the bare verb is unambiguous. */
    public static LogEvent damageRoll(String attackerName, String targetName,
                                      String element, int rolled, int dealt,
                                      java.util.List<String> mitigations,
                                      boolean playerInvolved) {
        return damageRoll(attackerName, false, targetName, false,
                element, rolled, dealt, mitigations, 0, playerInvolved);
    }

    /** Full damageRoll variant carrying actor player-ness and optional
     *  knockback annotation. {@code attackerIsPlayer} / {@code targetIsPlayer}
     *  drive the "The X" vs "X" article prefix (player names get no article;
     *  mob names are formatted "The black ant" at sentence start, "the
     *  black ant" mid-sentence). {@code kbSquares > 0} appends a "knocking
     *  the {target} back N" clause inside the main sentence. Priority is
     *  HIGH when the player is involved so this line becomes the canonical
     *  combat message (replacing the previous separate playerHit / enemyHit
     *  / mobHit lines, which were duplicates of this one without mitigation
     *  info). */
    public static LogEvent damageRoll(String attackerName, boolean attackerIsPlayer,
                                      String targetName,   boolean targetIsPlayer,
                                      String element, int rolled, int dealt,
                                      java.util.List<String> mitigations,
                                      int kbSquares,
                                      boolean playerInvolved) {
        return damageRoll(attackerName, attackerIsPlayer, targetName, targetIsPlayer,
                MobSystem.AttackType.MELEE, null, element, rolled, dealt,
                mitigations, kbSquares, playerInvolved);
    }

    /** Compose the canonical damage-roll log line, switching voice based on
     *  attack mechanism and attacker presence:
     *  <ul>
     *    <li>{@code attackerName == null} (environmental) → passive
     *        "The {target} takes N {element} damage." No "hit" verb.</li>
     *    <li>{@link MobSystem.AttackType#MELEE} →
     *        "{attacker} hits the {target} for N damage." Reserves the
     *        "hit" verb for actual melee swings.</li>
     *    <li>Other (RANGED / THROWN / MAGIC) →
     *        "{attacker}'s {item} does N damage to the {target}."
     *        Reads naturally for thrown bombs and wand zaps; the item name
     *        comes from the cause's originItem. Falls back to a no-item
     *        variant when {@code itemName} is null.</li>
     *  </ul>
     *  Knockback suffix and mitigation parenthetical work the same for all
     *  three voices. */
    public static LogEvent damageRoll(String attackerName, boolean attackerIsPlayer,
                                      String targetName,   boolean targetIsPlayer,
                                      MobSystem.AttackType type, String itemName,
                                      String element, int rolled, int dealt,
                                      java.util.List<String> mitigations,
                                      int kbSquares,
                                      boolean playerInvolved) {
        String atk = articled(attackerName, attackerIsPlayer, /*sentenceStart=*/true);
        String tgt = articled(targetName,   targetIsPlayer,   /*sentenceStart=*/false);
        boolean isPhysical = element == null || "PHYSICAL".equalsIgnoreCase(element);
        boolean hasAttacker = attackerName != null && !attackerName.isEmpty();
        EventPriority pri = playerInvolved ? EventPriority.HIGH : EventPriority.LOW;
        // Miss path keeps the existing "X misses Y" wording; environmental
        // damage doesn't miss, so a null attacker on a miss is treated as a
        // degenerate case (shouldn't happen in practice).
        if (rolled <= 0 && (mitigations == null || mitigations.isEmpty())) {
            String missAtk = hasAttacker ? atk : "Something";
            return new LogEvent(TextCatalog.format("eventlog.combat.roll.miss",
                    TextCatalog.vars("attacker", missAtk, "target", tgt)),
                    pri, playerInvolved);
        }
        String elementText = isPhysical ? "" : TextCatalog.getOrDefault(
                "eventlog.damageElement." + element.toLowerCase(), element.toLowerCase()) + " ";
        String body;
        if (!hasAttacker) {
            // Environmental / passive voice. Target sits at sentence start
            // here (unlike all other templates where it's mid-sentence), so
            // re-format with the sentenceStart capitalisation.
            String tgtStart = articled(targetName, targetIsPlayer, /*sentenceStart=*/true);
            body = TextCatalog.format(isPhysical
                            ? "eventlog.combat.roll.env"
                            : "eventlog.combat.roll.env.element",
                    TextCatalog.vars("target", tgtStart, "dealt", dealt,
                            "element", elementText.trim()));
        } else if (type == MobSystem.AttackType.MELEE || type == null) {
            body = TextCatalog.format(isPhysical
                            ? "eventlog.combat.roll.hit"
                            : "eventlog.combat.roll.hit.element",
                    TextCatalog.vars("attacker", atk, "target", tgt,
                            "dealt", dealt, "element", elementText.trim()));
        } else if (type == MobSystem.AttackType.ENVIRONMENTAL) {
            // Environmental damage with an originator (knockback slam,
            // bystander-of-slam, fall-through-chasm) - drop the attacker +
            // item attribution and use passive voice. The preceding
            // narrative log (slamInto / shoved-into-wall / fell) already
            // names who caused it; phrasing the same hit as "attacker's
            // weapon does N damage" reads as a double-count and wrongly
            // attributes the slam to the weapon.
            String tgtStart = articled(targetName, targetIsPlayer, /*sentenceStart=*/true);
            body = TextCatalog.format(isPhysical
                            ? "eventlog.combat.roll.env"
                            : "eventlog.combat.roll.env.element",
                    TextCatalog.vars("target", tgtStart, "dealt", dealt,
                            "element", elementText.trim()));
        } else {
            // Ranged / thrown / magic - "X's <item> does N damage to Y".
            boolean hasItem = itemName != null && !itemName.isEmpty();
            String key = hasItem
                    ? (isPhysical ? "eventlog.combat.roll.ranged"
                                  : "eventlog.combat.roll.ranged.element")
                    : (isPhysical ? "eventlog.combat.roll.rangedNoItem"
                                  : "eventlog.combat.roll.rangedNoItem.element");
            body = TextCatalog.format(key,
                    TextCatalog.vars("attacker", atk, "target", tgt,
                            "dealt", dealt, "element", elementText.trim(),
                            "item", hasItem ? itemName : ""));
        }
        if (kbSquares > 0) {
            body = body + TextCatalog.format("eventlog.combat.roll.knockbackSuffix",
                    TextCatalog.vars("target", tgt, "kb", kbSquares));
        }
        if (mitigations != null && !mitigations.isEmpty()) {
            StringBuilder parts = new StringBuilder();
            for (int i = 0; i < mitigations.size(); i++) {
                if (i > 0) parts.append(", ");
                parts.append(mitigations.get(i));
            }
            body = body + TextCatalog.format("eventlog.combat.roll.mitigations",
                    TextCatalog.vars("rolled", rolled, "mitigations", parts));
        }
        body = body + ".";
        return new LogEvent(body, pri, playerInvolved);
    }

    /** Prepend "The "/"the " to a non-player name. Players are addressed by
     *  name without an article ("Rogue", not "The Rogue"). Sentence-start
     *  capitalises the article. */
    private static String articled(String name, boolean isPlayer, boolean sentenceStart) {
        if (name == null) return "?";
        if (isPlayer) return name;
        return (sentenceStart ? "The " : "the ") + name;
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
        return mobHit(attacker, target, dmg, 0);
    }

    public static LogEvent mobHit(String attacker, String target, int dmg, int kbSquares) {
        String key = kbSquares > 0
                ? "eventlog.combat.mob.hit.knockback"
                : "eventlog.combat.mob.hit";
        return new LogEvent(TextCatalog.format(key,
                                    TextCatalog.vars("attacker", attacker, "target", target,
                                            "damage", dmg, "kb", kbSquares)),
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
        return enemyHit(attacker, playerName, dmg, 0);
    }

    public static LogEvent enemyHit(String attacker, String playerName, int dmg, int kbSquares) {
        String key = kbSquares > 0
                ? "eventlog.combat.enemy.hit.knockback"
                : "eventlog.combat.enemy.hit";
        return new LogEvent(TextCatalog.format(key,
                                    TextCatalog.vars("attacker", attacker, "player", playerName,
                                            "damage", dmg, "kb", kbSquares)),
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

    /** "Warrior charges at the rat!" - dedicated lead-in for the CHARGE tool
     *  (jade bull). Replaces the generic "Warrior uses the jade bull" line
     *  so the narrative reads as the action ("charges at X") followed by
     *  the impact roll ("hits X for N damage"). HIGH priority since it
     *  flags a deliberate player commitment. */
    public static LogEvent playerCharges(String playerName, String targetName, boolean targetIsPlayer) {
        String tgt = articled(targetName, targetIsPlayer, /*sentenceStart=*/false);
        return new LogEvent(TextCatalog.format("eventlog.player.charges",
                                    TextCatalog.vars("player", playerName, "target", tgt)),
                            EventPriority.HIGH, true);
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

    /** "Adventurer snatches the fire bomb out of the air!" - BOMB_DODGER catch (RL-34). */
    public static LogEvent bombCaught(String playerName, String itemName) {
        return new LogEvent(TextCatalog.format("eventlog.bomb.caught",
                                    TextCatalog.vars("player", playerName, "item", itemName)),
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

    public static LogEvent itemFellInChasm(String itemName, boolean involvesPlayer) {
        return new LogEvent(TextCatalog.format("eventlog.item.fellInChasm",
                                    TextCatalog.vars("item",
                                            itemName == null || itemName.isEmpty()
                                                    ? TextCatalog.getOrDefault(
                                                            "eventlog.item.itemFallback", "item")
                                                    : itemName)),
                            involvesPlayer ? EventPriority.HIGH : EventPriority.LOW,
                            involvesPlayer);
    }

    public static LogEvent knockbackSlam(String mobName, int damage, boolean involvesPlayer) {
        return knockbackSlam(mobName, damage, null, null, 0, involvesPlayer);
    }

    /** Knockback impact log with optional "into what" target + cascade
     *  knockback distance. Selects one of four string keys:
     *  <ul>
     *    <li>{@code intoName} != null + {@code cascadeKb > 0} →
     *        "The {mob} slams into the {into}, taking {damage} damage and knocking the {into} back {kb}."</li>
     *    <li>{@code intoName} != null only →
     *        "The {mob} slams into the {into}, taking {damage} damage."</li>
     *    <li>{@code originName} != null only (e.g. bomb-into-wall) →
     *        "The {mob} is shoved into a wall for {damage} damage by {origin}."</li>
     *    <li>neither →
     *        "The {mob} slams into a wall, taking {damage} damage."</li>
     *  </ul>
     *  The wall-slam path uses the "slamWall" key for the bare narrative
     *  variant and keeps "slamFrom" for the attributed bomb-knock case.
     *  Cascade and origin are mutually exclusive in practice: a melee
     *  knockback chain reads as a narrative with cascade distances, while a
     *  bomb knock reads with attribution. */
    public static LogEvent knockbackSlam(String mobName, int damage, String originName,
                                         String intoName, int cascadeKb,
                                         boolean involvesPlayer) {
        boolean hasInto    = intoName   != null && !intoName.isEmpty();
        boolean hasOrigin  = originName != null && !originName.isEmpty();
        boolean hasCascade = cascadeKb  > 0;
        String key;
        if (hasInto && hasCascade)     key = "eventlog.knockback.slamIntoCascade";
        else if (hasInto)              key = "eventlog.knockback.slamInto";
        else if (hasOrigin)            key = "eventlog.knockback.slamFrom";
        else                           key = "eventlog.knockback.slamWall";
        return new LogEvent(TextCatalog.format(key,
                                    TextCatalog.vars("mob", mobName, "damage", damage,
                                            "origin", originName == null ? "" : originName,
                                            "into",   intoName   == null ? "" : intoName,
                                            "kb",     cascadeKb)),
                            involvesPlayer ? EventPriority.HIGH : EventPriority.LOW,
                            involvesPlayer);
    }

    public static LogEvent beaconActivated(String playerName) {
        return new LogEvent(TextCatalog.format("eventlog.beacon.activated",
                                    TextCatalog.vars("player", playerName)),
                            EventPriority.HIGH, true);
    }
}
