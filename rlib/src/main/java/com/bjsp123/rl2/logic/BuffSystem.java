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

    /** Convenience - stack count of the buff if present, else 0. */
    public static int stacks(Mob mob, BuffType type) {
        Buff b = get(mob, type);
        return b == null ? 0 : b.stacks;
    }

    /** Maximum stacks a buff of {@code type} can hold. Default 10; per-type overrides
     *  per RL-43. Re-applying a buff never pushes it past this cap. */
    public static int stackCap(BuffType type) {
        return switch (type) {
            case FRIGHTENED, WET -> 2;
            case OILY -> 3;
            case FROZEN -> 5;
            case ON_FIRE -> 8;
            case INVISIBLE, GHOSTLY, LEVITATING, PHASE -> 20;
            case KILLER -> 30;            // stacks cap 30; speed effect capped at 10
            default -> 10;                // ESP, INSIGHT, HIDING, SHIELDED, REGENERATION,
                                          // POISONED, HASTED, HOPE, CHILLED, PROTECTION,
                                          // ANTI_MAGIC, SORCERY, BLEEDING, cooldowns
        };
    }

    /** --- Apply / remove ----------------------------------------------------- */

    /**
     * Apply a buff to {@code target}. {@code stacks} is the buff's combined
     * strength/lifetime in turns (it counts down 1/turn and the buff drops at 0). If a
     * buff of {@code type} already exists it's upgraded to {@code max(existing, stacks)};
     * {@link BuffType#KILLER} instead ADDS the incoming stacks. Either way the result is
     * clamped to {@link #stackCap(BuffType)}. Spawns a floating "buff name" effect above
     * the target's head so the player sees what landed.
     *
     * <p>Returns the resulting {@link Buff} (either the upgraded existing one or a
     * fresh one) so call sites can read final stacks if needed.
     */
    public static Buff apply(Level level, Mob target, BuffType type,
                             int stacks, Mob source) {
        return apply(level, target, type, stacks, source, null);
    }

    /** Full apply overload that records the originating {@link Item} on the
     *  buff (the wand of fire, bomb, potion, etc.). The item is preserved
     *  for downstream attribution: fire / poison DOT ticks build a
     *  {@code DamageCause} from {@code source} + {@code sourceItem} so the
     *  death screen + log messages can name the root cause ("burned to death
     *  in a fire caused by Kobold's fire wand"). Pass {@code null} for
     *  {@code sourceItem} when the buff origin item isn't meaningful. */
    public static Buff apply(Level level, Mob target, BuffType type,
                             int stacks, Mob source,
                             com.bjsp123.rl2.model.Item sourceItem) {
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

        int cap = stackCap(type);
        int newStacks = Math.max(1, Math.min(cap, stacks));
        Buff existing = get(target, type);
        if (existing != null) {
            // KILLER is a stacking buff: re-applying it on each kill ADDS the incoming
            // stacks onto the existing count (capped). Every other buff max-merges so
            // re-applying never shortens or weakens it.
            if (type == BuffType.KILLER) {
                existing.stacks = Math.min(cap, existing.stacks + newStacks);
            } else {
                existing.stacks = Math.max(existing.stacks, newStacks);
            }
            if (source != null) existing.source = source;
            if (sourceItem != null) existing.sourceItem = sourceItem;
            target.statsDirty = true;
            maybeFreeze(level, target, type, source);
            return existing;
        }
        Buff buff = new Buff(type, newStacks, source, sourceItem);
        target.buffs.add(buff);
        target.statsDirty = true;
        spawnApplyVfx(level, target, type);
        // Log the apply for natural buff types - cooldown buffs are
        // internal accounting (TELEPORT_COOLDOWN etc.) and would spam
        // the log without telling the player anything useful.
        if (!isCooldownBuff(type)) {
            String name = MobSystem.nameForLog(level, target);
            EventLog.add(Messages.buffApplied(name, displayName(type), newStacks,
                    target.isPlayer));
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
        if (chilled == null) return;
        // "Wet" = the WET buff OR standing on a water/ice tile (RL-32), so chilling
        // a mob standing in water freezes it even without the buff.
        if (!MobSystem.isWet(level, target)) return;
        Buff wet = get(target, BuffType.WET);
        int frozenStacks = Math.max(chilled.stacks, wet != null ? wet.stacks : 1);
        Mob src = source != null ? source
                : (chilled.source != null ? chilled.source : (wet != null ? wet.source : null));
        // Cold-shock burst (RL-31): on the transition INTO chilled+wet (i.e. not
        // already frozen), deal a one-time COLD hit - quadrupled by wetness in
        // processAttack. Dealt BEFORE freezing so it doesn't shorten the fresh
        // FROZEN via shortenFrozenOnDamage. Burst base 5*stacks -> ~20*stacks felt.
        if (get(target, BuffType.FROZEN) == null && target.hp > 0) {
            MobSystem.processAttack(level, src, target, 5 * Math.max(1, frozenStacks),
                    MobSystem.AttackType.MAGIC, MobSystem.DamageElement.COLD, null,
                    new MobSystem.DamageCause(src, null, "cold-shock"));
        }
        if (target.hp > 0) apply(level, target, BuffType.FROZEN, frozenStacks, src);
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
        frozen.stacks -= 2;
        if (frozen.stacks <= 0) removeBuff(mob, BuffType.FROZEN);
    }

    /** --- Per-turn driver --------------------------------------------------- */

    /** FRIGHTENED-aura stacks applied to a terrifiable mob adjacent to a terrifying one.
     *  Two stacks (= two turns) lets the fear persist for one full AI round after the mob
     *  has walked away from the terrifier; matches the FRIGHTENED stack cap. */
    public static final int FEAR_AURA_STACKS = 2;

    /**
     * Per-standard-turn pass. Drives terrifying-aura propagation, FRIGHTENED
     * light-purge, damage-over-time application (ON_FIRE, POISONED, BLEEDING,
     * REGENERATION), and finally the per-turn stack countdown + expiry
     * (see {@link #decrementStacks}).
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
                    apply(level, other, BuffType.FRIGHTENED, FEAR_AURA_STACKS, src);
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
            // Stacks count down once per turn; drop any buff that hits zero. Runs after
            // the effect pass so a buff still fires on the turn it expires.
            if (m.hp > 0) decrementStacks(level, m);
        }
    }

    /**
     * Count every active buff's stacks down by 1 and remove any that reach zero. Called
     * once per standard turn from {@link #tickPerTurn} - stacks are turn-granular now, so
     * there is no per-game-tick duration path any more.
     */
    private static void decrementStacks(Level level, Mob m) {
        if (m == null || m.buffs == null || m.buffs.isEmpty()) return;
        Iterator<Buff> it = m.buffs.iterator();
        boolean changed = false;
        while (it.hasNext()) {
            Buff b = it.next();
            // WET / OILY don't decay while the mob is still standing in the water / oil that
            // sustains them - wading keeps you soaked. They decay normally once off it.
            if (sustainedBySurface(level, m, b.type)) continue;
            b.stacks -= 1;
            if (b.stacks <= 0) {
                it.remove();
                changed = true;
                // Natural expiry log - same gate as apply (cooldown buffs are silent so
                // the rolling log doesn't fill up with "no longer on teleport cooldown").
                if (!isCooldownBuff(b.type)) {
                    String name = MobSystem.nameForLog(level, m);
                    EventLog.add(Messages.buffExpired(name, displayName(b.type),
                            m.isPlayer));
                    // Emit a BuffRemoved event so the renderer can show a brief
                    // "buff faded" floater above the mob.
                    if (level != null && level.events != null) {
                        level.events.add(new com.bjsp123.rl2.event.GameEvent.BuffRemoved(
                                m, b.type, displayName(b.type)));
                    }
                }
            }
        }
        if (changed) m.statsDirty = true;
    }

    /** True when {@code type} is a buff whose source surface the mob is currently standing
     *  on (WET on WATER, OILY on OIL) - such buffs hold steady instead of decaying so a mob
     *  wading through liquid stays soaked / slick. */
    private static boolean sustainedBySurface(Level level, Mob m, Buff.BuffType type) {
        if (level == null || level.surface == null || m == null || m.position == null) return false;
        int x = m.position.tileX(), y = m.position.tileY();
        if (x < 0 || y < 0 || x >= level.width || y >= level.height) return false;
        com.bjsp123.rl2.model.Level.Surface s = level.surface[x][y];
        return (type == Buff.BuffType.WET  && s == com.bjsp123.rl2.model.Level.Surface.WATER)
            || (type == Buff.BuffType.OILY && s == com.bjsp123.rl2.model.Level.Surface.OIL);
    }

    /** True for buffs that exist purely as internal time-gates (the four
     *  *_COOLDOWN buffs). Their apply / expire events would spam the log
     *  with no player-meaningful information, so they're filtered out at
     *  the {@link Messages#buffApplied} / {@link Messages#buffExpired}
     *  emit sites. */
    public static boolean isCooldownBuff(Buff.BuffType type) {
        return type == Buff.BuffType.TELEPORT_COOLDOWN
            || type == Buff.BuffType.RANGED_COOLDOWN
            || type == Buff.BuffType.HASTE_COOLDOWN
            || type == Buff.BuffType.HEAL_COOLDOWN
            || type == Buff.BuffType.PHASE_DODGE_COOLDOWN;
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
                    MobSystem.DamageCause cause = new MobSystem.DamageCause(
                            b.source, b.sourceItem, "fire-dot");
                    boolean killed = MobSystem.processAttack(level, b.source, m, dmg,
                            MobSystem.AttackType.ENVIRONMENTAL, MobSystem.DamageElement.FIRE, bk, cause);
                    if (killed) logDotDeath(m, TextCatalog.get("buff.dotdeath.ON_FIRE"));
                }
            }
            case POISONED -> {
                // Damage from current stacks; poison weakens as the stacks count down.
                int dmg = 2 + (3 * b.stacks) / 2;
                emitPeriodicDamage(level, m, BuffType.POISONED, dmg);
                MobSystem.DamageCause cause = new MobSystem.DamageCause(
                        b.source, b.sourceItem, "poison-dot");
                boolean killed = MobSystem.processAttack(level, b.source, m, dmg,
                        MobSystem.AttackType.ENVIRONMENTAL, MobSystem.DamageElement.POISON, null, cause);
                if (killed) logDotDeath(m, TextCatalog.get("buff.dotdeath.POISONED"));
            }
            case BLEEDING -> {
                // Damage straight from current stacks - strong at first, tapering as the
                // stacks count down each turn (RL-43): dmg = max(1, 3*stacks/4).
                int dmg = Math.max(1, (3 * b.stacks) / 4);
                emitPeriodicDamage(level, m, BuffType.BLEEDING, dmg);
                MobSystem.DamageCause cause = new MobSystem.DamageCause(
                        b.source, b.sourceItem, "bleed");
                boolean killed = MobSystem.processAttack(level, b.source, m, dmg,
                        MobSystem.AttackType.ENVIRONMENTAL, MobSystem.DamageElement.PHYSICAL, null, cause);
                if (killed) logDotDeath(m, TextCatalog.get("buff.dotdeath.BLEEDING"));
            }
            case REGENERATION -> {
                // Heal from current stacks; regen tapers as the stacks count down.
                int heal = 2 + (3 * b.stacks) / 2;
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
        int s = stacks(target, buffType);
        if (s <= 0 || rawDmg <= 0) return false;
        return rawDmg >> Math.min(s, 30) != rawDmg;
    }

    private static int mitigate(Mob target, int dmg, BuffType type) {
        if (dmg <= 0) return dmg;
        int s = stacks(target, type);
        if (s <= 0) return dmg;
        // Integer divide by 2^stacks - at 1 stack halves, 2 stacks quarters, etc.
        int mitigated = dmg >> Math.min(s, 30);
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
        double killerMult = 1.0; // KILLER kept separate so its reduction floors at KILLER_MIN_COST
        for (Buff b : mob.buffs) {
            if (b == null || b.type == null) continue;
            switch (b.type) {
                case HOPE -> {
                    dst.accuracy += b.stacks * 5;
                    dst.evasion  += b.stacks * 5;
                }
                case INVISIBLE -> dst.evasion += 40;
                case GHOSTLY   -> dst.evasion += 20;
                // Levitation grants flight: the mob floats over chasms (and
                // other ground hazards gated on flying) for the buff's duration.
                case LEVITATING -> dst.flying = true;
                case PHASE -> {
                    dst.evasion += 40;
                    moveMultiplier *= 0.3;
                }
                case HASTED -> moveMultiplier *= Math.pow(0.8, b.stacks);
                case KILLER -> {
                    // Killer-streak haste: multiplies BOTH move and action
                    // (attack/ranged) cost by 0.9 per stack, compounding. Tracked
                    // separately from the other multipliers so its benefit floors
                    // at KILLER_MIN_COST (see below) instead of collapsing cost
                    // toward 1. Effect is capped at 10 stacks even though the buff
                    // itself can stack to 30.
                    killerMult *= Math.pow(0.9, Math.min(KILLER_EFFECT_CAP, Math.max(1, b.stacks)));
                }
                case CHILLED -> chilledPenalty = Math.max(chilledPenalty, 80 + b.stacks * 15);
                default -> { /* other buff types don't yet contribute to the stat block */ }
            }
        }
        dst.moveCost   = killerFloor(scaledCost(dst.moveCost   + chilledPenalty, moveMultiplier),   killerMult);
        dst.attackCost = killerFloor(scaledCost(dst.attackCost + chilledPenalty, actionMultiplier), killerMult);
        if (dst.rangedCost > 0) {
            dst.rangedCost = killerFloor(scaledCost(dst.rangedCost + chilledPenalty, actionMultiplier), killerMult);
        }
    }

    /** Lowest move/action cost the KILLER streak buff will reduce a stat to.
     *  KILLER still stacks (duration + count), but its speed benefit bottoms out
     *  here rather than compounding toward 1, so a maxed perk on a kill streak
     *  caps at this cost instead of becoming near-instant. */
    public static final int KILLER_MIN_COST = 40;

    /** KILLER stacks past this don't speed the mob up any further. The buff itself can
     *  stack to {@code stackCap(KILLER)} (30) so a streak keeps refreshing its lifetime,
     *  but the speed multiplier saturates here (and is further floored by
     *  {@link #KILLER_MIN_COST}). */
    public static final int KILLER_EFFECT_CAP = 10;

    /** Apply the KILLER speed multiplier to an already-(other-buff-)scaled cost,
     *  but never below {@link #KILLER_MIN_COST} - and never RAISE a cost that
     *  other buffs already pushed below the floor. No-op when no KILLER is active. */
    private static int killerFloor(int cost, double killerMult) {
        if (killerMult >= 1.0) return cost;
        int reduced = (int) Math.round(cost * killerMult);
        int floor = Math.min(cost, KILLER_MIN_COST);
        return Math.max(floor, reduced);
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
        return stacks(user, BuffType.SORCERY);
    }

    /** --- Visuals --------------------------------------------------------- */

    /** Log a "X burns to a cinder" / "X succumbs to poison" message when a damage-over-
     *  time buff is the killing blow. Player-target gets the second-person "you ..."
     *  framing routed through the existing event-log helpers. */
    private static void logDotDeath(Mob deadMob, String verbPhrase) {
        if (deadMob == null) return;
        boolean player = deadMob.isPlayer;
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

    /**
     * Short, stacks-resolved descriptor of what the buff *does right now*
     * ("moves 49% faster", "physical damage divided by 4", "+5 HP/turn"). The
     * wording lives in {@code strings.csv} under {@code buff.<TYPE>.effect}; only
     * the numbers (which depend on the current stack count) are computed here and
     * passed in as template vars. Empty string for buffs with no per-stack effect
     * line. Used by {@code V2BuffInfo}.
     */
    public static String describeEffectForStacks(BuffType type, int stacks) {
        if (type == null || stacks <= 0) return "";
        switch (type) {
            case HASTED: {
                int faster = (int) Math.round((1.0 - Math.pow(0.8, stacks)) * 100.0);
                return TextCatalog.format("buff.HASTED.effect", TextCatalog.vars("pct", faster));
            }
            case CHILLED:
                return TextCatalog.format("buff.CHILLED.effect", TextCatalog.vars("n", 80 + 15 * stacks));
            case PROTECTION:
                return TextCatalog.format("buff.PROTECTION.effect",
                        TextCatalog.vars("n", 1 << Math.min(stacks, 30)));
            case ANTI_MAGIC:
                return TextCatalog.format("buff.ANTI_MAGIC.effect",
                        TextCatalog.vars("n", 1 << Math.min(stacks, 30)));
            case REGENERATION:
                return TextCatalog.format("buff.REGENERATION.effect",
                        TextCatalog.vars("n", 2 + (3 * stacks) / 2));
            case HOPE:
                return TextCatalog.format("buff.HOPE.effect", TextCatalog.vars("n", stacks * 5));
            case KILLER: {
                // 0.9^stacks (capped at KILLER_EFFECT_CAP) on move/attack cost, floored
                // at KILLER_MIN_COST. Compute against the standard cost as a representative
                // base, applying the same cap + floor the stat pipeline does.
                int base = TurnSystem.STANDARD_TURN_TICKS;
                int eff  = Math.max(KILLER_MIN_COST,
                        (int) Math.round(base * Math.pow(0.9, Math.min(KILLER_EFFECT_CAP, stacks))));
                int killerFaster = (int) Math.round((1.0 - eff / (double) base) * 100.0);
                return TextCatalog.format("buff.KILLER.effect", TextCatalog.vars("pct", killerFaster));
            }
            case BLEEDING:   return TextCatalog.getOrDefault("buff.BLEEDING.effect", "");
            case POISONED:   return TextCatalog.getOrDefault("buff.POISONED.effect", "");
            case ON_FIRE:    return TextCatalog.getOrDefault("buff.ON_FIRE.effect", "");
            case FROZEN:     return TextCatalog.getOrDefault("buff.FROZEN.effect", "");
            case INVISIBLE:  return TextCatalog.getOrDefault("buff.INVISIBLE.effect", "");
            case SHIELDED:   return TextCatalog.getOrDefault("buff.SHIELDED.effect", "");
            case WET:        return TextCatalog.getOrDefault("buff.WET.effect", "");
            case OILY:       return TextCatalog.getOrDefault("buff.OILY.effect", "");
            case FRIGHTENED: return TextCatalog.getOrDefault("buff.FRIGHTENED.effect", "");
            case LEVITATING: return TextCatalog.getOrDefault("buff.LEVITATING.effect", "");
            case PHASE:      return TextCatalog.getOrDefault("buff.PHASE.effect", "");
            case GHOSTLY:    return TextCatalog.getOrDefault("buff.GHOSTLY.effect", "");
            case TELEPORT_COOLDOWN:
            case RANGED_COOLDOWN:
            case HIDING:     return TextCatalog.getOrDefault("buff.recharging.effect", "");
            default:         return "";
        }
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
            sb.append(" x").append(b.stacks);
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
