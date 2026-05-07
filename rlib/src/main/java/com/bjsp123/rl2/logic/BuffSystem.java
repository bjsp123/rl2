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
 * of the existing and incoming values for both level and duration — re-applying a buff
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

    /** ─── Queries ──────────────────────────────────────────────────────────── */

    public static boolean hasBuff(Mob mob, BuffType type) {
        return get(mob, type) != null;
    }

    /** The mob's active {@link Buff} of {@code type}, or {@code null}. */
    public static Buff get(Mob mob, BuffType type) {
        if (mob == null || mob.buffs == null) return null;
        for (Buff b : mob.buffs) if (b.type == type) return b;
        return null;
    }

    /** Convenience — level of the buff if present, else 0. */
    public static int level(Mob mob, BuffType type) {
        Buff b = get(mob, type);
        return b == null ? 0 : b.level;
    }

    /** ─── Apply / remove ───────────────────────────────────────────────────── */

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
        // Hope grants immunity to FRIGHTENED — silently swallow incoming fear while
        // hope is active. Likewise fireImmune mobs don't take ON_FIRE.
        if (type == BuffType.FRIGHTENED && (hasBuff(target, BuffType.HOPE) || !target.effectiveStats().terrifiable)) {
            return null;
        }
        if (type == BuffType.ON_FIRE && target.effectiveStats().fireImmune) return null;

        int newLevel    = Math.max(1, buffLevel);
        int newDuration = Math.max(1, durationTurns);
        Buff existing = get(target, type);
        if (existing != null) {
            existing.level         = Math.max(existing.level,         newLevel);
            existing.durationTurns = Math.max(existing.durationTurns, newDuration);
            if (source != null) existing.source = source;
            return existing;
        }
        Buff buff = new Buff(type, newLevel, newDuration, source);
        target.buffs.add(buff);
        spawnApplyVfx(level, target, type);
        // Hope wipes any active fear — adding hope after a fright should free the mob.
        if (type == BuffType.HOPE) {
            removeBuff(target, BuffType.FRIGHTENED);
        }
        return buff;
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

    /** ─── Per-turn driver ─────────────────────────────────────────────────── */

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
        // MobSystem.processAttack → killMob, which removes the dying mob from
        // level.mobs synchronously. Without the snapshot the live iterator throws
        // ConcurrentModificationException as soon as a fire DOT lands a kill.
        java.util.List<Mob> snapshot = new java.util.ArrayList<>(level.mobs);
        // First pass — terrifying mobs apply FRIGHTENED to terrifiable neighbours
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
            // Snapshot — applyEffect can mutate buffs (e.g. invisibility cancel via
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
            case REGENERATION -> {
                if (!hasBuff(m, BuffType.POISONED)) {
                    int heal = 1 + b.level / 2;
                    MobSystem.heal(level, m, heal);
                }
            }
            default -> {
                /* Passive buffs — read by other systems (movement, vision, damage
                 * resolution, AI). No per-turn HP delta on this side. */
            }
        }
    }

    /** ─── Damage mitigation ──────────────────────────────────────────────── */

    /** Anti-magic divides incoming damage by {@code 2^level}, floored at 1 if the
     *  original was positive. Routed via {@link MobSystem#processAttack} for damage
     *  with {@link MobSystem.DamageElement#MAGIC} or {@link MobSystem.DamageElement#FIRE}.
     *  Don't call directly from new sites — pass the right element to processAttack. */
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
        // Integer divide by 2^level — at level 1 halves, level 2 quarters, etc.
        int mitigated = dmg >> Math.min(lvl, 30);
        // If the original damage was positive, leave at least 1 so the hit still
        // registers (otherwise high-level resists silently swallow the entire attack).
        return Math.max(mitigated, dmg > 0 ? 1 : 0);
    }

    /** ─── Stat modifiers ─────────────────────────────────────────────────── */

    /**
     * Single contributor entry-point for the StatBlock pipeline. Adds every active
     * buff's stat contribution into {@code dst} in place. Replaces the historical
     * per-stat helpers — accuracy/evasion bonuses now flow through here. CHILLED is the
     * one exception: its move/attack-cost penalty stays as an action-time modifier in
     * {@link #chilledCostPenalty} so the ranged-cost path (which doesn't go through the
     * StatBlock) gets the same treatment as melee.
     */
    public static void contributeInto(com.bjsp123.rl2.model.StatBlock dst, Mob mob) {
        if (mob == null || dst == null || mob.buffs == null) return;
        for (Buff b : mob.buffs) {
            if (b == null || b.type == null) continue;
            switch (b.type) {
                case HOPE -> {
                    dst.accuracy += b.level;
                    dst.evasion  += b.level;
                }
                case INVISIBLE -> dst.evasion += 20;
                case GHOSTLY   -> dst.evasion += 20;
                // CHILLED and HASTED are action-time modifiers (applied inside
                // applyMoveCost / its multiplicative sibling), not stat baselines, so they
                // intentionally don't contribute to the StatBlock. Keeps the StatBlock
                // representing "stats before action resolution" rather than "exact cost
                // of the next tick", and avoids double-applying the penalty when the
                // ranged code path adds its own cost outside the move/attack split.
                default -> { /* other buff types don't yet contribute to the stat block */ }
            }
        }
    }

    // effectiveAccuracy / effectiveEvasion deleted: their contributions now flow
    // through StatBlock via {@link #contributeInto}.

    /** Combined move/attack cost multiplier from {@link BuffType#HASTED} (-20% per
     *  level) and {@link BuffType#KILLER} (flat -20% while active). Returns {@code 1.0}
     *  when neither buff is present, so callers can multiply their base cost
     *  unconditionally. Used by {@link TurnSystem#applyMoveCost}. */
    public static double actionCostMultiplier(Mob mob) {
        double m = 1.0;
        Buff hasted = get(mob, BuffType.HASTED);
        if (hasted != null) m *= Math.pow(0.8, hasted.level);
        if (hasBuff(mob, BuffType.KILLER)) m *= 0.8;
        return m;
    }

    /** Additive penalty applied to {@code moveCost} and {@code attackCost} from
     *  {@link BuffType#CHILLED}: {@code 50 + level * 10}. Returns 0 when the buff is
     *  absent. Callers should add this to their base cost. */
    public static int chilledCostPenalty(Mob mob) {
        Buff c = get(mob, BuffType.CHILLED);
        return c == null ? 0 : 50 + c.level * 10;
    }

    /** True if the mob takes double damage from fire — i.e. carries the
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

    /** ─── Visuals ───────────────────────────────────────────────────────── */

    /** Log a "X burns to a cinder" / "X succumbs to poison" message when a damage-over-
     *  time buff is the killing blow. Player-target gets the second-person "you …"
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

    /** Per-turn DOT damage event — the rgame Animator picks the floating-text tint
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
        return switch (type) {
            case SORCERY      -> "Sorcery";
            case ON_FIRE      -> "On Fire";
            case FRIGHTENED   -> "Frightened";
            case INVISIBLE    -> "Invisible";
            case GHOSTLY      -> "Ghostly";
            case LEVITATING   -> "Levitating";
            case ANTI_MAGIC   -> "Anti-magic";
            case PROTECTION   -> "Protection";
            case REGENERATION -> "Regeneration";
            case POISONED     -> "Poisoned";
            case HASTED       -> "Hasted";
            case HOPE         -> "Blessed";
            case ESP          -> "ESP";
            case CHILLED      -> "Chilled";
            case OILY         -> "Oily";
            case WET          -> "Wet";
            case STARVING     -> "Starving";
            case TELEPORT_COOLDOWN -> "Recharging";
            case RANGED_COOLDOWN   -> "Recharging";
            case HASTE_COOLDOWN    -> "Recharging";
            case HEAL_COOLDOWN     -> "Recharging";
            case HIDING            -> "Hiding";
            case KILLER            -> "Killer";
        };
    }

    public static String description(BuffType type) {
        return switch (type) {
            case SORCERY -> "Your next spell or potion is empowered.";
            case ON_FIRE -> "Losing HP each turn until it burns out.";
            case FRIGHTENED -> "Too scared to act effectively.";
            case INVISIBLE -> "Harder to hit, but attacking cancels the effect.";
            case GHOSTLY -> "Harder to hit, but attacking cancels the effect.";
            case LEVITATING -> "Unaffected by ground hazards and pits.";
            case ANTI_MAGIC -> "Incoming magic and fire damage is reduced.";
            case PROTECTION -> "Incoming physical damage is reduced.";
            case REGENERATION -> "Regenerating HP each turn.";
            case POISONED -> "Losing HP each turn until the poison wears off.";
            case HASTED -> "Acting faster than normal.";
            case HOPE -> "Feeling hopeful and courageous.";
            case ESP -> "Can see mobs through walls, but not their HP bars or names.";
            case CHILLED -> "Acting slower than normal.";
            case OILY -> "Takes extra damage from fire.";
            case WET -> "Takes extra damage from lightning.";
            case STARVING -> "Needs to eat soon or start taking damage.";
            case TELEPORT_COOLDOWN -> "Can't teleport again until this wears off.";
            case RANGED_COOLDOWN   -> "Can't use ranged attacks until this wears off.";
            case HASTE_COOLDOWN    -> "Can't cast haste again until this wears off.";
            case HEAL_COOLDOWN     -> "Can't cast heal again until this wears off.";
            case HIDING            -> "Hidden from enemies. Moving or attacking will cancel this.";
            case KILLER            -> "Striking with deadly precision.";
        };
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

    /** ─── Death message helper ───────────────────────────────────────────── */

    /** Returns a short cause-of-death string when the mob's killing blow came from a
     *  damage-over-time buff that's still on it (fire / poison). Empty when the mob
     *  carries neither, so callers can default to their normal "X killed Y" message. */
    public static String deathMessageSuffix(Mob deadMob) {
        if (deadMob == null || deadMob.buffs == null) return "";
        if (hasBuff(deadMob, BuffType.ON_FIRE))   return " burns to a cinder";
        if (hasBuff(deadMob, BuffType.POISONED))  return " succumbs to poison";
        return "";
    }
}
