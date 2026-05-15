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

    /** Per-turn HP loss for the {@link BuffType#ON_FIRE} buff at {@code level}. */
    public static final int FIRE_DAMAGE_PER_TURN = 5;

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
     * durationTurns)}. Spawns a floating "buff name" effect above the target's head so
     * the player sees what landed.
     *
     * <p>Returns the resulting {@link Buff} (either the upgraded existing one or a
     * fresh one) so call sites can read final stats if needed.
     */
    public static Buff apply(Level level, Mob target, BuffType type,
                             int buffLevel, int durationTurns, Mob source) {
        if (target == null || type == null) return null;
        if (target.buffs == null) target.buffs = new java.util.ArrayList<>();
        // Hope grants immunity to FRIGHTENED - silently swallow incoming fear while
        // hope is active. Likewise fireImmune mobs don't take ON_FIRE.
        if (type == BuffType.FRIGHTENED && (hasBuff(target, BuffType.HOPE) || !target.effectiveStats().terrifiable)) {
            return null;
        }
        if (type == BuffType.ON_FIRE && target.effectiveStats().fireImmune) return null;
        if (type == BuffType.ON_FIRE) {
            removeBuff(target, BuffType.FROZEN);
            removeBuff(target, BuffType.CHILLED);
        }

        int newLevel    = Math.max(1, buffLevel);
        int newDuration = Math.max(1, durationTurns);
        Buff existing = get(target, type);
        if (existing != null) {
            existing.level         = Math.max(existing.level,         newLevel);
            existing.durationTurns = Math.max(existing.durationTurns, newDuration);
            if (source != null) existing.source = source;
            maybeFreeze(level, target, type, source);
            return existing;
        }
        Buff buff = new Buff(type, newLevel, newDuration, source);
        target.buffs.add(buff);
        spawnApplyVfx(level, target, type);
        // Hope wipes any active fear - adding hope after a fright should free the mob.
        if (type == BuffType.HOPE) {
            removeBuff(target, BuffType.FRIGHTENED);
        }
        maybeFreeze(level, target, type, source);
        return buff;
    }

    private static void maybeFreeze(Level level, Mob target, BuffType applied, Mob source) {
        if (applied != BuffType.CHILLED && applied != BuffType.WET) return;
        Buff chilled = get(target, BuffType.CHILLED);
        Buff wet = get(target, BuffType.WET);
        if (chilled == null || wet == null) return;
        int lvl = Math.max(chilled.level, wet.level);
        int dur = Math.max(chilled.durationTurns, wet.durationTurns);
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
            if (it.next().type == type) { it.remove(); return true; }
        }
        return false;
    }

    public static void shortenFrozenOnDamage(Mob mob) {
        Buff frozen = get(mob, BuffType.FROZEN);
        if (frozen == null) return;
        frozen.durationTurns -= 2;
        if (frozen.durationTurns <= 0) removeBuff(mob, BuffType.FROZEN);
    }

    /** --- Per-turn driver --------------------------------------------------- */

    /**
     * Run all per-turn buff effects on every mob in the level, then decrement durations
     * and remove expired buffs. Called once per standard turn from {@link TurnSystem#tick}'s
     * standard-turn pass.
     */
    /** Duration (in game turns) of the FRIGHTENED buff applied when a terrifiable mob
     *  is adjacent to a terrifying one. Two turns lets the fear persist for one full
     *  AI round after the mob has already walked away from the terrifier. */
    public static final int FEAR_AURA_DURATION = 2;

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
                    apply(level, other, BuffType.FRIGHTENED, 1, FEAR_AURA_DURATION, src);
                }
            }
        }
        for (Mob m : snapshot) {
            if (m == null || m.buffs == null || m.buffs.isEmpty()) continue;
            if (m.hp <= 0) {
                m.buffs.clear();
                continue;
            }
            // Snapshot - applyEffect can mutate buffs (e.g. invisibility cancel via
            // attack) but the per-turn tick itself just reads.
            for (Buff b : new java.util.ArrayList<>(m.buffs)) {
                applyPerTurnEffect(level, m, b);
            }
            // Decrement durations + drop expired.
            Iterator<Buff> it = m.buffs.iterator();
            while (it.hasNext()) {
                Buff b = it.next();
                b.durationTurns--;
                if (b.durationTurns <= 0) it.remove();
            }
        }
    }

    /** Per-turn effect for one buff. Damage / heal happens via
     *  {@link MobSystem#processAttack} so kill bookkeeping stays consistent. */
    private static void applyPerTurnEffect(Level level, Mob m, Buff b) {
        switch (b.type) {
            case ON_FIRE -> {
                int raw = takesDoubleFireDamage(m) ? FIRE_DAMAGE_PER_TURN * 2
                                                   : FIRE_DAMAGE_PER_TURN;
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
                int dmg = 1 + b.level / 2;
                emitPeriodicDamage(level, m, BuffType.POISONED, dmg);
                boolean killed = MobSystem.processAttack(level, b.source, m, dmg,
                        MobSystem.AttackType.ENVIRONMENTAL, MobSystem.DamageElement.POISON);
                if (killed) logDotDeath(m, "succumbs to poison");
            }
            case BLEEDING -> {
                // (level x duration) / 2 HP / turn - strong at first then
                // tapers as the duration counts down. Read durationTurns
                // BEFORE the post-effect decrement so the first tick uses
                // the full duration the user-source applied.
                int dmg = Math.max(1, (b.level * b.durationTurns) / 2);
                emitPeriodicDamage(level, m, BuffType.BLEEDING, dmg);
                boolean killed = MobSystem.processAttack(level, b.source, m, dmg,
                        MobSystem.AttackType.ENVIRONMENTAL, MobSystem.DamageElement.PHYSICAL);
                if (killed) logDotDeath(m, "bleeds out");
            }
            case REGENERATION -> {
                if (!hasBuff(m, BuffType.POISONED)) {
                    int heal = 1 + b.level / 2;
                    MobSystem.heal(level, m, heal);
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
                    moveMultiplier *= 0.8;
                    actionMultiplier *= 0.8;
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
            sb.append(" (").append(b.durationTurns).append("t)");
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
