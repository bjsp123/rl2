package com.bjsp123.rl2.logic;

import com.bjsp123.rl2.model.Buff;
import com.bjsp123.rl2.model.Buff.BuffType;
import com.bjsp123.rl2.model.Level;
import com.bjsp123.rl2.model.Mob;

import java.util.Iterator;

/**
 * Central authority for {@link Buff}s. Handles apply / merge / expire and runs the
 * per-turn effects (regen / poison / fire damage / hasted move-cost scaling).
 *
 * <h3>Apply contract</h3>
 * Calling {@link #apply(Level, Mob, BuffType, int, int, Mob)} on a mob that already
 * carries a buff of the same type updates the existing instance to the <em>greater</em>
 * of the existing and incoming values for both level and duration - re-applying a buff
 * never makes it weaker. The mob also gets a floating "buff name" effect rising above
 * its head so the player gets visible feedback.
 *
 * <h3>Per-turn effects</h3>
 * {@link #tickPerTurn(Level)} runs once per game turn. It:
 * <ul>
 *   <li>decrements every buff's duration</li>
 *   <li>applies regen / poison / fire HP changes</li>
 *   <li>cancels {@link BuffType#FRIGHTENED} on mobs that gained {@link BuffType#HOPE}
 *       since the previous tick (hope grants terror immunity)</li>
 *   <li>removes expired buffs</li>
 * </ul>
 * Most call sites only care about the apply / expire / has paths and should ignore
 * the tick path.
 */
public final class BuffSystem {

    private BuffSystem() {}

    /** --- Queries ------------------------------------------------------------ */

    public static boolean hasBuff(Mob mob, BuffType type) {
        return get(mob, type) != null;
    }

    /** The mob's active {@link Buff} of {@code type}, or {@code null}. */
    public static Buff get(Mob mob, BuffType type) {
        if (mob == null || mob.buffs == null) return null;
        for (Buff b : mob.buffs) if (b.type == type) return b;
        return null;
    }

    /** Convenience - level of the buff if present, else 0. */
    public static int level(Mob mob, BuffType type) {
        Buff b = get(mob, type);
        return b == null ? 0 : b.level;
    }

    /** --- Apply / remove ----------------------------------------------------- */

    /**
     * Apply a buff to {@code target}. If a buff of {@code type} already exists, it's
     * upgraded to {@code max(existingLevel, level)} / {@code max(existingDuration,
     * durationTicks)}. Spawns a floating "buff name" effect above the target's head so
     * the player sees what landed.
     *
     * <p>{@code durationTicks} is the buff length in <b>game ticks</b>; convert from
     * standard turns at the call site via {@code N * TurnSystem.STANDARD_TURN_TICKS}.
     *
     * <p>Returns the resulting {@link Buff} (either the upgraded existing one or a
     * fresh one) so call sites can read final stats if needed.
     */
    public static Buff apply(Level level, Mob target, BuffType type,
                             int buffLevel, int durationTicks, Mob source) {
        if (target == null || type == null) return null;
        if (target.buffs == null) target.buffs = new java.util.ArrayList<>();
        // Hope grants immunity to FRIGHTENED - silently swallow incoming fear while
        // hope is active. Likewise fireImmune mobs don't take ON_FIRE.
        // Standing on a lit tile also blocks fear: courage in the light.
        if (type == BuffType.FRIGHTENED && (hasBuff(target, BuffType.HOPE) || !target.effectiveStats().terrifiable
                || isOnLitTile(level, target))) {
            return null;
        }
        if (type == BuffType.ON_FIRE && target.effectiveStats().fireImmune) return null;
        if (type == BuffType.POISONED && target.effectiveStats().poisonImmune) return null;
        if (type == BuffType.ON_FIRE) {
            removeBuff(target, BuffType.FROZEN);
            removeBuff(target, BuffType.CHILLED);
        }

        int newLevel    = Math.max(1, buffLevel);
        int newDuration = Math.max(1, durationTicks);
        Buff existing = get(target, type);
        if (existing != null) {
            // KILLER is a stacking buff: re-applying it on each kill ADDS the
            // incoming level to the existing stack count and RESETS the
            // duration (instead of the default max-merge). The Warrior's
            // perk feeds {@code ceil(perkLvl/2)} as the incoming level so
            // higher-rank KILLER perk means each kill bumps the stack
            // faster.
            if (type == BuffType.KILLER) {
                existing.level         = existing.level + newLevel;
                existing.durationTicks = newDuration;
            } else {
                existing.level         = Math.max(existing.level,         newLevel);
                existing.durationTicks = Math.max(existing.durationTicks, newDuration);
            }
            if (source != null) existing.source = source;
            target.statsDirty = true;
            maybeFreeze(level, target, type, source);
            return existing;
        }
        Buff buff = new Buff(type, newLevel, newDuration, source);
        target.buffs.add(buff);
        target.statsDirty = true;
        spawnApplyVfx(level, target, type);
        // Log the apply for natural buff types - cooldown buffs are
        // internal accounting (TELEPORT_COOLDOWN etc.) and would spam
        // the log without telling the player anything useful.
        if (!isCooldownBuff(type)) {
            String name = MobSystem.nameForLog(level, target);
            EventLog.add(Messages.buffApplied(name, displayName(type),
                    target.behavior == Mob.Behavior.PLAYER));
        }
        // Hope wipes any active fear - adding hope after a fright should free the mob.
        if (type == BuffType.HOPE) {
            removeBuff(target, BuffType.FRIGHTENED);
        }
        maybeFreeze(level, target, type, source);
        return buff;
    }

    /** True when {@code mob} stands on a tile currently illuminated by the
     *  level's lighting pass. Used to gate FRIGHTENED both ways: lit tiles
     *  block application and strip the buff on per-turn tick. Defensive
     *  against null {@code level.lit} (transient field reset on load). */
    private static boolean isOnLitTile(Level level, Mob mob) {
        if (level == null || mob == null || mob.position == null) return false;
        if (level.lit == null) return false;
        int x = mob.position.tileX(), y = mob.position.tileY();
        if (x < 0 || y < 0 || x >= level.width || y >= level.height) return false;
        return level.lit[x][y];
    }

    private static void maybeFreeze(Level level, Mob target, BuffType applied, Mob source) {
        if (applied != BuffType.CHILLED && applied != BuffType.WET) return;
        Buff chilled = get(target, BuffType.CHILLED);
        Buff wet = get(target, BuffType.WET);
        if (chilled == null || wet == null) return;
        int lvl = Math.max(chilled.level, wet.level);
        int dur = Math.max(chilled.durationTicks, wet.durationTicks);
        Mob src = source != null ? source : (chilled.source != null ? chilled.source : wet.source);
        apply(level, target, BuffType.FROZEN, lvl, dur, src);
    }

    /** Strip a buff if present. No-op otherwise. Used by potion effects that should
     *  cancel on attack ({@link BuffType#INVISIBLE}) and by the hope/frightened
     *  interaction in {@link #apply}. */
    public static boolean removeBuff(Mob mob, BuffType type) {
        if (mob == null || mob.buffs == null) return false;
        Iterator<Buff> it = mob.buffs.iterator();
        while (it.hasNext()) {
            if (it.next().type == type) { it.remove(); mob.statsDirty = true; return true; }
        }
        return false;
    }

    public static void shortenFrozenOnDamage(Mob mob) {
        Buff frozen = get(mob, BuffType.FROZEN);
        if (frozen == null) return;
        frozen.durationTicks -= 2 * TurnSystem.STANDARD_TURN_TICKS;
        if (frozen.durationTicks <= 0) removeBuff(mob, BuffType.FROZEN);
    }

    /** --- Per-turn driver --------------------------------------------------- */

    /** FRIGHTENED-aura duration applied to a terrifiable mob adjacent to a
     *  terrifying one, in <b>game ticks</b>. Two standard turns lets the fear
     *  persist for one full AI round after the mob has walked away from the
     *  terrifier. */
    public static final int FEAR_AURA_DURATION_TICKS = 2 * TurnSystem.STANDARD_TURN_TICKS;

    /**
     * Per-standard-turn pass. Drives terrifying-aura propagation, FRIGHTENED
     * light-purge, and damage-over-time application (ON_FIRE, POISONED,
     * BLEEDING, REGENERATION). Duration decrement is NOT done here - that
     * runs every game tick via {@link #tickEveryGameTick}.
     */
    public static void tickPerTurn(Level level) {
        if (level == null || level.mobs == null) return;
        // Snapshot the mob list once for both passes: applyPerTurnEffect can call
        // MobSystem.processAttack -> killMob, which removes the dying mob from
        // level.mobs synchronously. Without the snapshot the live iterator throws
        // ConcurrentModificationException as soon as a fire DOT lands a kill.
        java.util.List<Mob> snapshot = new java.util.ArrayList<>(level.mobs);
        // First pass - terrifying mobs apply FRIGHTENED to terrifiable neighbours
        // within Chebyshev range 1. apply() respects HOPE immunity and the
        // terrifiable=false short-circuit, so this just enumerates and applies.
        for (Mob src : snapshot) {
            if (src == null || !src.effectiveStats().terrifying || src.hp <= 0) continue;
            int sx = src.position.tileX(), sy = src.position.tileY();
            for (Mob other : snapshot) {
                if (other == src) continue;
                if (other.hp <= 0) continue;
                int dx = Math.abs(other.position.tileX() - sx);
                int dy = Math.abs(other.position.tileY() - sy);
                if (Math.max(dx, dy) <= 1) {
                    apply(level, other, BuffType.FRIGHTENED, 1, FEAR_AURA_DURATION_TICKS, src);
                }
            }
        }
        // Strip FRIGHTENED off any mob that stands on a lit tile - courage in
        // the light. Run before the per-buff effect pass so a mob with fear
        // who just stepped into light doesn't take one extra frightened
        // turn before it clears.
        for (Mob m : snapshot) {
            if (m == null || m.hp <= 0 || m.buffs == null || m.buffs.isEmpty()) continue;
            if (hasBuff(m, BuffType.FRIGHTENED) && isOnLitTile(level, m)) {
                removeBuff(m, BuffType.FRIGHTENED);
            }
        }
        for (Mob m : snapshot) {
            if (m == null || m.buffs == null || m.buffs.isEmpty()) continue;
            if (m.hp <= 0) {
                m.buffs.clear();
                continue;
            }
            // Snapshot - applyEffect can mutate buffs (e.g. invisibility cancel via
            // attack) but the per-turn tick itself just reads. Bail out as soon
            // as the mob dies inside the loop so a later REGEN tick can't heal
            // a corpse back to positive HP (which leaves the mob dead-in-mobs
            // but with hp > 0 - a "zombie" state that breaks any caller doing
            // `mob.hp <= 0` to detect death).
            for (Buff b : new java.util.ArrayList<>(m.buffs)) {
                if (m.hp <= 0) break;
                applyPerTurnEffect(level, m, b);
            }
        }
    }

    /**
     * Per-game-tick pass. Decrements every active buff's {@code durationTicks}
     * by {@code dtTicks} and removes any that hit zero. Called from
     * {@link TurnSystem#tick} every game tick (and from
     * {@code TurnSystem.advanceToNextEvent} during catch-up) so buff expiry
     * lands on the exact tick the budget runs out - not snapped to the next
     * standard-turn boundary.
     */
    public static void tickEveryGameTick(Level level, int dtTicks) {
        if (level == null || level.mobs == null || dtTicks <= 0) return;
        for (Mob m : level.mobs) {
            if (m == null || m.buffs == null || m.buffs.isEmpty()) continue;
            if (m.hp <= 0) { m.buffs.clear(); continue; }
            Iterator<Buff> it = m.buffs.iterator();
            boolean changed = false;
            while (it.hasNext()) {
                Buff b = it.next();
                b.durationTicks -= dtTicks;
                if (b.durationTicks <= 0) {
                    it.remove();
                    changed = true;
                    // Natural expiry log - same gate as apply (cooldown
                    // buffs are silent so the rolling log doesn't fill up
                    // with "no longer on teleport cooldown" lines).
                    if (!isCooldownBuff(b.type)) {
                        String name = MobSystem.nameForLog(level, m);
                        EventLog.add(Messages.buffExpired(name, displayName(b.type),
                                m.behavior == Mob.Behavior.PLAYER));
                    }
                }
            }
            if (changed) m.statsDirty = true;
        }
    }

    /** True for buffs that exist purely as internal time-gates (the four
     *  *_COOLDOWN buffs). Their apply / expire events would spam the log
     *  with no player-meaningful information, so they're filtered out at
     *  the {@link Messages#buffApplied} / {@link Messages#buffExpired}
     *  emit sites. */
    private static boolean isCooldownBuff(Buff.BuffType type) {
        return type == Buff.BuffType.TELEPORT_COOLDOWN
            || type == Buff.BuffType.RANGED_COOLDOWN
            || type == Buff.BuffType.HASTE_COOLDOWN
            || type == Buff.BuffType.HEAL_COOLDOWN;
    }

    /** HUD-friendly turn count for a buff's remaining duration. Uses ceiling
     *  division so a still-active sub-turn buff renders as {@code (1t)} rather
     *  than {@code (0t)}. */
    public static int displayTurns(int durationTicks) {
        if (durationTicks <= 0) return 0;
        return (durationTicks + TurnSystem.STANDARD_TURN_TICKS - 1)
                / TurnSystem.STANDARD_TURN_TICKS;
    }

    /** Per-turn effect for one buff. Damage / heal happens via
     *  {@link MobSystem#processAttack} so kill bookkeeping stays consistent. */
    private static void applyPerTurnEffect(Level level, Mob m, Buff b) {
        switch (b.type) {
            case ON_FIRE -> {
                int raw = takesDoubleFireDamage(m) ? GameBalance.FIRE_DAMAGE_PER_TURN * 2
                                                   : GameBalance.FIRE_DAMAGE_PER_TURN;
                int magicResist = MobSystem.rollRange(MobSystem.magicResistRange(m));
                int dmg = Math.max(0, raw - magicResist);
                if (dmg > 0) {
                    emitPeriodicDamage(level, m, BuffType.ON_FIRE, dmg);
                    MobSystem.DamageBreakdown bk =
                            new MobSystem.DamageBreakdown(MobSystem.DamageElement.FIRE, raw)
                                    .add("magicResist", Math.min(magicResist, raw));
                    boolean killed = MobSystem.processAttack(level, b.source, m, dmg,
                            MobSystem.AttackType.ENVIRONMENTAL, MobSystem.DamageElement.FIRE, bk);
                    if (killed) logDotDeath(m, "burns to a cinder");
                }
            }
            case POISONED -> {
                // +50% rebalance: was 1 + level. Now 2 + (3*level)/2 (integer).
                int dmg = 2 + (3 * b.level) / 2;
                emitPeriodicDamage(level, m, BuffType.POISONED, dmg);
                boolean killed = MobSystem.processAttack(level, b.source, m, dmg,
                        MobSystem.AttackType.ENVIRONMENTAL, MobSystem.DamageElement.POISON);
                if (killed) logDotDeath(m, "succumbs to poison");
            }
            case BLEEDING -> {
                // (level x standardTurnsRemaining) / 2 HP per standard turn -
                // strong at first then tapers as the duration counts down.
                // Convert ticks to standard turns for game-feel parity with the
                // pre-tick implementation: a buff applied for 6 standard turns
                // (600 ticks) at level 4 still ticks for 12 / 10 / 8 / 6 / 4 /
                // 2 over its life.
                int turnsLeft = displayTurns(b.durationTicks);
                // +50% rebalance: was (level * turnsLeft) / 2. Now ×3/4.
                int dmg = Math.max(1, (3 * b.level * turnsLeft) / 4);
                emitPeriodicDamage(level, m, BuffType.BLEEDING, dmg);
                boolean killed = MobSystem.processAttack(level, b.source, m, dmg,
                        MobSystem.AttackType.ENVIRONMENTAL, MobSystem.DamageElement.PHYSICAL);
                if (killed) logDotDeath(m, "bleeds out");
            }
            case REGENERATION -> {
                // +50% rebalance: was 1 + level. Now 2 + (3*level)/2 (integer).
                int heal = 2 + (3 * b.level) / 2;
                MobSystem.heal(level, m, heal);
                
                if (hasBuff(m, BuffType.POISONED)) {
                    // remove poison
                    removeBuff(m, BuffType.POISONED);
                }
            }
            default -> {
                /* Passive buffs - read by other systems (movement, vision, damage
                 * resolution, AI). No per-turn HP delta on this side. */
            }
        }
    }

    /** --- Damage mitigation ------------------------------------------------ */

    /** Anti-magic divides incoming damage by {@code 2^level}, floored at 1 if the
     *  original was positive. Routed via {@link MobSystem#processAttack} for damage
     *  with {@link MobSystem.DamageElement#MAGIC} or {@link MobSystem.DamageElement#FIRE}.
     *  Don't call directly from new sites - pass the right element to processAttack. */
    public static int mitigateMagicDamage(Mob target, int dmg) {
        return mitigate(target, dmg, BuffType.ANTI_MAGIC);
    }

    /** Protection divides incoming damage by {@code 2^level}. Routed via
     *  {@link MobSystem#processAttack} for {@link MobSystem.DamageElement#PHYSICAL}
     *  damage. Don't call directly from new sites. */
    public static int mitigatePhysicalDamage(Mob target, int dmg) {
        return mitigate(target, dmg, BuffType.PROTECTION);
    }

    /** True if {@code target}'s incoming {@code dmg} would be mitigated below the raw
     *  value by the named protective buff. The renderer uses this to dim damage text. */
    public static boolean wasMitigated(Mob target, int rawDmg, BuffType buffType) {
        int level = level(target, buffType);
        if (level <= 0 || rawDmg <= 0) return false;
        return rawDmg >> level != rawDmg;
    }

    private static int mitigate(Mob target, int dmg, BuffType type) {
        if (dmg <= 0) return dmg;
        int lvl = level(target, type);
        if (lvl <= 0) return dmg;
        // Integer divide by 2^level - at level 1 halves, level 2 quarters, etc.
        int mitigated = dmg >> Math.min(lvl, 30);
        // If the original damage was positive, leave at least 1 so the hit still
        // registers (otherwise high-level resists silently swallow the entire attack).
        return Math.max(mitigated, dmg > 0 ? 1 : 0);
    }

    /** --- Stat modifiers --------------------------------------------------- */

    /**
     * Single contributor entry-point for the StatBlock pipeline. Adds every active
     * buff's stat contribution into {@code dst} in place. Accuracy/evasion bonuses and
     * speed/cost changes all flow through here, so {@link Mob#effectiveStats()} is the
     * single source for current mob stats.
     */
    public static void contributeInto(com.bjsp123.rl2.model.StatBlock dst, Mob mob) {
        if (mob == null || dst == null || mob.buffs == null) return;
        int chilledPenalty = 0;
        double moveMultiplier = 1.0;
        double actionMultiplier = 1.0;
        for (Buff b : mob.buffs) {
            if (b == null || b.type == null) continue;
            switch (b.type) {
                case HOPE -> {
                    dst.accuracy += b.level;
                    dst.evasion  += b.level;
                }
                case INVISIBLE -> dst.evasion += 40;
                case GHOSTLY   -> dst.evasion += 20;
                case PHASE -> {
                    dst.evasion += 40;
                    moveMultiplier *= 0.3;
                }
                case HASTED -> moveMultiplier *= Math.pow(0.8, b.level);
                case KILLER -> {
                    // KILLER stacks via the apply() carve-out above. Each
                    // stack multiplies action + move cost by 0.9 - softer
                    // than the previous 0.85 / 0.8 multipliers so a
                    // warrior on a kill streak speeds up gradually rather
                    // than collapsing turn cost to 1.
                    double k = Math.pow(0.9, Math.max(1, b.level));
                    moveMultiplier   *= k;
                    actionMultiplier *= k;
                }
                case CHILLED -> chilledPenalty = Math.max(chilledPenalty, 80 + b.level * 15);
                default -> { /* other buff types don't yet contribute to the stat block */ }
            }
        }
        dst.moveCost = scaledCost(dst.moveCost + chilledPenalty, moveMultiplier);
        dst.attackCost = scaledCost(dst.attackCost + chilledPenalty, actionMultiplier);
        if (dst.rangedCost > 0) {
            dst.rangedCost = scaledCost(dst.rangedCost + chilledPenalty, actionMultiplier);
        }
    }

    // effectiveAccuracy / effectiveEvasion deleted: their contributions now flow
    // through StatBlock via {@link #contributeInto}.

    /** Clamp resolved action costs so even extreme speed stacks still consume time. */
    private static int scaledCost(int cost, double multiplier) {
        return Math.max(1, (int) Math.round(cost * multiplier));
    }

    /** True if the mob takes double damage from fire - i.e. carries the
     *  {@link BuffType#OILY} buff. Read by {@link #applyPerTurnEffect} and any caller
     *  resolving fire damage. */
    public static boolean takesDoubleFireDamage(Mob mob) {
        return hasBuff(mob, BuffType.OILY);
    }

    /** Item-level boost from the {@link BuffType#SORCERY} buff. Wand impact and potion
     *  factories add this onto {@link com.bjsp123.rl2.model.Item#level} when the user
     *  is sorcery-buffed. */
    public static int sorceryBonus(Mob user) {
        return level(user, BuffType.SORCERY);
    }

    /** --- Visuals --------------------------------------------------------- */

    /** Log a "X burns to a cinder" / "X succumbs to poison" message when a damage-over-
     *  time buff is the killing blow. Player-target gets the second-person "you ..."
     *  framing routed through the existing event-log helpers. */
    private static void logDotDeath(Mob deadMob, String verbPhrase) {
        if (deadMob == null) return;
        boolean player = deadMob.behavior == Mob.Behavior.PLAYER;
        String name = deadMob.name != null ? deadMob.name
                    : (player ? "Adventurer" : "the creature");
        EventLog.add(new com.bjsp123.rl2.model.LogEvent(
                name + " " + verbPhrase + ".",
                com.bjsp123.rl2.model.LogEvent.EventPriority.HIGH,
                player));
    }

    /** Per-turn DOT damage event - the rgame Animator picks the floating-text tint
     *  from the buff type (orange for fire, green for poison). */
    private static void emitPeriodicDamage(Level level, Mob m, BuffType buff, int amount) {
        if (level == null || level.events == null) return;
        level.events.add(new com.bjsp123.rl2.event.GameEvent.PeriodicBuffDamage(m, buff, amount));
    }

    /** Emits a {@code BuffApplied} event for {@code target}; the rgame Animator
     *  decides whether to render an icon or text floater based on its own user-pref
     *  supplier. {@code rlib} no longer carries any rendering toggle. */
    private static void spawnApplyVfx(Level level, Mob target, BuffType type) {
        if (level == null || level.events == null || target.position == null) return;
        level.events.add(new com.bjsp123.rl2.event.GameEvent.BuffApplied(
                target, type, displayName(type)));
    }

    /** Player-facing label for a buff type. Used by the apply VFX and by HUD / look
     *  rendering code that lists buffs on a mob. */
    public static String displayName(BuffType type) {
        return TextCatalog.getOrDefault("buff." + type + ".name", type.name());
    }

    public static String description(BuffType type) {
        return TextCatalog.getOrDefault("buff." + type + ".description", "");
    }

    /** Pretty multi-line summary of every buff on a mob, one line per buff. Used by
     *  the HUD's portrait sidebar and the look-mode panel. Empty string when the mob
     *  has no buffs. */
    public static String summary(Mob mob) {
        if (mob == null || mob.buffs == null || mob.buffs.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        for (Buff b : mob.buffs) {
            if (sb.length() > 0) sb.append('\n');
            sb.append(displayName(b.type));
            if (b.level > 1) sb.append(" +").append(b.level - 1);
            sb.append(" (").append(displayTurns(b.durationTicks)).append("t)");
        }
        return sb.toString();
    }

    /** --- Death message helper --------------------------------------------- */

    /** Returns a short cause-of-death string when the mob's killing blow came from a
     *  damage-over-time buff that's still on it (fire / poison). Empty when the mob
     *  carries neither, so callers can default to their normal "X killed Y" message. */
    public static String deathMessageSuffix(Mob deadMob) {
        if (deadMob == null || deadMob.buffs == null) return "";
        if (hasBuff(deadMob, BuffType.ON_FIRE))   return TextCatalog.get("buff.death.ON_FIRE");
        if (hasBuff(deadMob, BuffType.POISONED))  return TextCatalog.get("buff.death.POISONED");
        return "";
    }
}
