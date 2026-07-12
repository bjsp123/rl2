package com.bjsp123.rl2.logic;

import com.bjsp123.rl2.logic.MobSystem.AttackType;
import com.bjsp123.rl2.logic.MobSystem.Attitude;
import com.bjsp123.rl2.logic.MobSystem.DamageBreakdown;
import com.bjsp123.rl2.logic.MobSystem.DamageCause;
import com.bjsp123.rl2.logic.MobSystem.DamageElement;
import com.bjsp123.rl2.model.Level;
import com.bjsp123.rl2.model.MinMax;
import com.bjsp123.rl2.model.Mob;
import com.bjsp123.rl2.model.Mob.StateOfMind;
import com.bjsp123.rl2.model.Point;

import java.util.Arrays;

/**
 * Combat resolution extracted from {@link MobSystem}: turn-start visibility
 * snapshots and the surprise-attack rules built on them, to-hit and damage-range
 * math, the melee {@link #attack} entry point, the central {@link #processAttack}
 * damage pipeline (mitigation, breakdown logging, kill handling), and the
 * flinch / kill-log / damage-roll-log emitters.
 */
public final class MobCombat {

    private MobCombat() {}

    public static void snapshotVisibleMobsAtTurnStart(Level level, Mob viewer) {
        if (level == null || viewer == null || level.mobs == null) return;
        // Reuse existing set to avoid per-turn allocation
        java.util.Set<Mob> seen = viewer.visibleMobsAtTurnStart;
        if (seen == null) seen = new java.util.HashSet<>();
        else seen.clear();
        if (viewer.position == null || viewer.stateOfMind == StateOfMind.ASLEEP) {
            viewer.visibleMobsAtTurnStart = seen;
            return;
        }
        int w = level.width, h = level.height;
        int vx = viewer.position.tileX(), vy = viewer.position.tileY();
        if (vx < 0 || vy < 0 || vx >= w || vy >= h) {
            viewer.visibleMobsAtTurnStart = seen;
            return;
        }
        int radius = (int) Math.ceil(viewer.effectiveStats().visionRadius);
        // Pre-check: skip the expensive FOV if no mob in the radius box is a
        // mutual threat (viewer attacks/flees other, OR other attacks viewer).
        // Checks both directions so non-attacking targets (e.g. a mouse) still
        // get a snapshot when a predator is nearby.
        boolean needsFov = false;
        for (Mob other : level.mobs) {
            if (other == viewer || other.hp <= 0 || other.position == null) continue;
            int ox = other.position.tileX(), oy = other.position.tileY();
            if (Math.max(Math.abs(ox - vx), Math.abs(oy - vy)) > radius) continue;
            Attitude fwd = MobSystem.getAttitudeToMob(viewer, other);
            if (fwd == Attitude.ATTACK || fwd == Attitude.FLEE) { needsFov = true; break; }
            if (MobSystem.getAttitudeToMob(other, viewer) == Attitude.ATTACK) { needsFov = true; break; }
        }
        if (!needsFov) {
            viewer.visibleMobsAtTurnStart = seen;
            return;
        }
        // Use bounded variant: O(r²+M) instead of O(W×H+M)
        boolean[] blocking = LevelSystem.buildBlockingLocal(level, vx, vy, radius);
        level.initVisibilityScratch();
        boolean[] fov = level.visibilityTempScratch;
        Arrays.fill(fov, 0, w * h, false);
        ShadowCaster.castShadow(vx, vy, w, fov, blocking, radius);
        for (Mob other : level.mobs) {
            if (!canSeeForSurprisePrefilter(level, viewer, other, radius)) continue;
            int ox = other.position.tileX(), oy = other.position.tileY();
            if (fov[oy * w + ox]) seen.add(other);
        }
        viewer.visibleMobsAtTurnStart = seen;
    }

    private static boolean canSeeForSurprise(Level level, Mob viewer, Mob subject) {
        if (level == null || viewer == null || subject == null) return false;
        if (!canSeeForSurprisePrefilter(level, viewer, subject,
                (int) Math.ceil(viewer.effectiveStats().visionRadius))) return false;
        return LevelUtilities.getLineOfSight(level, viewer, subject.position);
    }

    private static boolean canSeeForSurprisePrefilter(Level level, Mob viewer, Mob subject,
                                                      int radius) {
        if (level == null || viewer == null || subject == null) return false;
        if (viewer.position == null || subject.position == null) return false;
        if (subject == viewer || subject.hp <= 0) return false;
        if (viewer.stateOfMind == StateOfMind.ASLEEP) return false;
        if (BuffSystem.hasBuff(subject, com.bjsp123.rl2.model.Buff.BuffType.INVISIBLE)) return false;
        int vx = viewer.position.tileX(), vy = viewer.position.tileY();
        int sx = subject.position.tileX(), sy = subject.position.tileY();
        if (vx < 0 || vy < 0 || vx >= level.width || vy >= level.height) return false;
        if (sx < 0 || sy < 0 || sx >= level.width || sy >= level.height) return false;
        if (Math.max(Math.abs(sx - vx), Math.abs(sy - vy)) > radius) return false;
        // Smoke blinds a fight: a subject is hidden for surprise purposes when
        // smoke sits on the subject's tile (cloaked in the plume) OR on the
        // viewer's own tile (blinded by the smoke around them) - unless the
        // viewer can peer through with KEEN_SIGHT (Chebyshev range = perk level,
        // the same relaxation the player's FOV uses). This is what lets a
        // keen-sighted attacker surprise foes fighting blind in smoke - whether
        // the smoke landed on the foe or on the attacker - and symmetrically
        // lets smoke-cloaked foes surprise a player who lacks the perk.
        if (GameBalance.RULES_SURPRISE_SMOKE_CONCEALS
                && (CloudSystem.smokeAt(level, sx, sy) || CloudSystem.smokeAt(level, vx, vy))
                && !keenSeesThroughSmoke(viewer, sx, sy)) {
            return false;
        }
        return true;
    }

    /** True iff {@code viewer}'s KEEN_SIGHT perk reaches tile ({@code tx},{@code ty}) -
     *  i.e. the tile is within Chebyshev range = perk level, the same relaxation
     *  {@link LevelSystem} applies to the player's FOV so keen eyes see into smoke. */
    private static boolean keenSeesThroughSmoke(Mob viewer, int tx, int ty) {
        if (viewer == null || viewer.position == null || viewer.perks == null) return false;
        int keen = viewer.perks.getOrDefault(com.bjsp123.rl2.model.Perk.KEEN_SIGHT, 0);
        if (keen <= 0) return false;
        int d = Math.max(Math.abs(tx - viewer.position.tileX()),
                         Math.abs(ty - viewer.position.tileY()));
        return d <= keen;
    }

    public static boolean isSurpriseAttack(Level level, Mob attacker, Mob target,
                                           AttackType type, DamageElement element) {
        if (attacker == null || target == null || attacker == target) return false;
        if (!surpriseEligible(type, element)) return false;
        if (GameBalance.RULES_SURPRISE_SURPRISE_IF_NO_LOS_LAST_TURN) {
            java.util.Set<Mob> seen = target.visibleMobsAtTurnStart;
            boolean sawAtTurnStart = seen == null
                    ? canSeeForSurprise(level, target, attacker)
                    : seen.contains(attacker);
            if (!sawAtTurnStart) return true;
        }
        if (GameBalance.RULES_SURPRISE_SURPRISE_IF_NO_LOS_NOW
                && !canSeeForSurprise(level, target, attacker)) {
            return true;
        }
        return false;
    }

    private static boolean surpriseEligible(AttackType type, DamageElement element) {
        if (type == null || type == AttackType.ENVIRONMENTAL) return false;
        if (GameBalance.RULES_SURPRISE_ALLOW_ALL_TARGETED_ATTACK_TYPES) return true;
        if (element != DamageElement.PHYSICAL) return false;
        return type == AttackType.MELEE
                || type == AttackType.RANGED
                || (type == AttackType.THROWN && GameBalance.RULES_SURPRISE_ALLOW_THROW);
    }

    public static int applySurpriseIfNeeded(Level level, Mob attacker, Mob target,
                                            int damage, AttackType type, DamageElement element) {
        if (!isSurpriseAttack(level, attacker, target, type, element)) return damage;
        emitSurpriseAttack(level, attacker, target);
        return (int) Math.round(damage * GameBalance.RULES_SURPRISE_DAMAGE_MULT);
    }

    private static void emitSurpriseAttack(Level level, Mob attacker, Mob target) {
        boolean playerInvolved = (attacker != null && attacker.isPlayer)
                || (target != null && target.isPlayer);
        EventLog.add(Messages.surpriseAttack(MobSystem.nameForLog(level, attacker),
                MobSystem.nameForLog(level, target), playerInvolved));
        if (level != null && level.events != null && target != null) {
            level.events.add(new com.bjsp123.rl2.event.GameEvent.SurpriseAttack(target));
        }
    }

    /** Probability that {@code attacker} lands a hit on {@code target}. Reads the
     *  fully-rolled-up effective accuracy and evasion from each side's StatBlock - so
     *  HOPE / INVISIBLE / GHOSTLY buffs and any future accuracy-bonus items automatically
     *  flow through. */
    public static double hitChance(Mob attacker, Mob target) {
        return MobStats.hitChance(attacker, target);
    }

    /** Roll a ranged hit check with an accuracy modifier (negative = penalty).
     *  Returns true if the shot lands. */
    public static boolean rollRangedHit(Mob caster, Mob target, int accuracyMod) {
        int atkAcc = Math.max(0, caster.effectiveStats().accuracy + accuracyMod);
        int tgtEva = target.effectiveStats().evasion;
        return rollToHit(atkAcc, tgtEva);
    }

    /** Roll a to-hit against an accuracy/evasion pair, applying the global
     *  {@link GameBalance#RULES_HIT_CHANCE_FLOOR} so a high-evasion target can
     *  never drop the chance below the floor. Shared by melee, ranged, and
     *  thrown so the 10% floor holds everywhere. */
    public static boolean rollToHit(int acc, int eva) {
        return MobSystem.RANDOM.nextDouble() < hitChanceFloored(acc, eva);
    }

    /** The floored to-hit probability: {@code max(FLOOR, acc/(acc+eva))}. */
    public static double hitChanceFloored(int acc, int eva) {
        int denom = acc + eva;
        double base = denom <= 0 ? 0.0 : (double) acc / denom;
        return Math.max(GameBalance.RULES_HIT_CHANCE_FLOOR, base);
    }

    /** Min and max damage the attacker outputs before resistance - pulled directly from
     *  the StatBlock pipeline. Per-item level scaling, equipped-slot summation, and any
     *  future buff contributions are folded in by {@link MobSystem#writeEffectiveStats}. */
    public static MinMax rawDamageRange(Mob attacker) {
        return MobStats.rawDamageRange(attacker);
    }

    /** Min and max damage the target resists. */
    public static MinMax resistRange(Mob target) {
        return MobStats.resistRange(target);
    }

    /** Min and max bonus damage the attacker lands ignoring armour. */
    public static MinMax apDamageRange(Mob attacker) {
        return MobStats.apDamageRange(attacker);
    }

    /** Min and max magic resistance the target rolls per non-physical hit. */
    public static MinMax magicResistRange(Mob target) {
        return MobStats.magicResistRange(target);
    }

    /** Min and max damage attacker can land on target after resistance, floored at 0,
     *  plus the AP bonus. */
    public static MinMax netDamageRange(Mob attacker, Mob target) {
        return MobStats.netDamageRange(attacker, target);
    }

    /**
     * Single rollup for a mob's effective stats: copies the intrinsic block, then folds
     * in level scaling, equipped items, and active buffs. Writes into {@code dst} in
     * place. See {@link MobStats#writeEffectiveStats} for the full contributor order.
     *
     * <p>Called from {@link Mob#effectiveStats()} when {@link Mob#statsDirty} is set.
     * Don't invoke directly unless you want to bypass the cache.
     */
    public static void writeEffectiveStats(Mob mob, com.bjsp123.rl2.model.StatBlock dst) {
        MobStats.writeEffectiveStats(mob, dst);
    }

    /** Roll to-hit, compute damage, apply, spawn floating text. Kills target if HP drops to 0.
     *  Visual side-effects (lunge animation, floating-text, particle bursts) are suppressed
     *  when neither participant is in the player's current FOV - there's no point flickering
     *  damage numbers off-screen. */
    public static void attack(Level level, Mob attacker, Mob target) {
        if (BuffSystem.hasBuff(attacker, com.bjsp123.rl2.model.Buff.BuffType.FROZEN)) return;
        boolean surprise = isSurpriseAttack(level, attacker, target,
                AttackType.MELEE, DamageElement.PHYSICAL);
        // Attacking ends INVISIBLE. Surprise is checked first so a currently invisible
        // attacker still gets the off-guard strike before the buff drops.
        BuffSystem.removeBuff(attacker, com.bjsp123.rl2.model.Buff.BuffType.INVISIBLE);
        // Combat memory is now seeded inside processAttack (gated on actual damage),
        // so a swing that misses leaves attitudes intact.
        int atkAcc = attacker.effectiveStats().accuracy;
        int tgtEva = target.effectiveStats().evasion;
        boolean hit  = surprise || rollToHit(atkAcc, tgtEva);

        if (!hit) {
            if (level.events != null) {
                level.events.add(new com.bjsp123.rl2.event.GameEvent.MobMeleeAttacked(
                        attacker, target, /*hit=*/false, /*dealt=*/0));
                // Yellow "miss" floater + miss sfx are bound to the DamageDealt
                // MISS branch in the Animator; emit one here too so a melee
                // miss has the same visual/audio cue as a ranged miss.
                level.events.add(new com.bjsp123.rl2.event.GameEvent.DamageDealt(
                        target, 0,
                        com.bjsp123.rl2.event.GameEvent.DamageMessage.MISS,
                        attacker, DamageElement.PHYSICAL, null));
            }
            // Single miss line via the damageRoll path; the older
            // logAttackOutcome miss emission was a duplicate.
            emitDamageRollLog(level, attacker, target,
                    new DamageBreakdown(DamageElement.PHYSICAL, 0), 0);
            return;
        }

        // Regular melee damage is reduced by armour (floored at 0); AP damage is added on
        // top with no armour reduction. Both are rolled independently so a swing that
        // bounces off scale mail still lands its full AP component. PROTECTION mitigation
        // is now applied centrally inside processAttack (gated on PHYSICAL element).
        int rawAtk = MobSystem.rollRange(rawDamageRange(attacker));
        int armor  = MobSystem.rollRange(resistRange(target));
        int regular = Math.max(0, rawAtk - armor);
        int ap = MobSystem.rollRange(apDamageRange(attacker));
        int rawDealt = regular + ap;
        DamageBreakdown bk = new DamageBreakdown(DamageElement.PHYSICAL, rawAtk + ap);
        bk.add("armor", Math.min(armor, rawAtk));
        if (surprise) {
            emitSurpriseAttack(level, attacker, target);
            rawDealt = (int) Math.round(rawDealt * GameBalance.RULES_SURPRISE_DAMAGE_MULT);
        }
        if (level.events != null) {
            level.events.add(new com.bjsp123.rl2.event.GameEvent.MobMeleeAttacked(
                    attacker, target, /*hit=*/true, rawDealt));
        }
        // Compute knockback up front so the hit line can be annotated with
        // the push distance ("knocking the roach back 3") before the slam
        // log lines start. Knockback then runs - emits its slam logs in
        // narrative order - then the melee damage applies. The kill log
        // line is held back until the end so the death message reads as a
        // consequence of the full chain rather than the only signal.
        // Total knockback = stat-based (intrinsic + equipped weapon) plus the
        // KNOCKBACK perk contribution. The perk's tile contribution caps at
        // GameBalance.KNOCKBACK_TILE_CAP (perkLvl 1..cap = +1 tile each); levels
        // above the cap instead each add +1 to the wall-slam damage bonus dealt
        // when the knockback's flight is blocked by a wall / chasm / mob.
        int kb = 0;
        int wallSlam = 0;
        if (attacker.position != null) {
            kb = attacker.effectiveStats().knockbackSquares;
            if (attacker.perks != null) {
                int p = attacker.perks.getOrDefault(
                        com.bjsp123.rl2.model.Perk.KNOCKBACK, 0);
                int cap = GameBalance.KNOCKBACK_TILE_CAP;
                kb       += Math.min(cap, p);
                wallSlam += Math.max(0, p - cap);
            }
        }
        // Pre-mitigate so we can emit the damageRoll log line FIRST (with kb
        // annotation) before knockback's slam logs follow. The mitigation
        // math is duplicated between here and processAttack, but processAttack
        // is invoked with suppressLog=true so the damageRoll only fires once.
        // SHIELDED short-circuits both: dealt=0 and no damage applied below.
        boolean shielded = BuffSystem.hasBuff(target, com.bjsp123.rl2.model.Buff.BuffType.SHIELDED);
        int previewDealt;
        if (shielded) {
            previewDealt = 0;
        } else {
            previewDealt = BuffSystem.mitigatePhysicalDamage(target, rawDealt);
        }
        if (previewDealt < rawDealt && rawDealt > 0) {
            bk.add("PROTECTION", rawDealt - previewDealt);
        }
        bk.kbSquares = kb;
        bk.type      = AttackType.MELEE;
        bk.cause     = new DamageCause(attacker,
                attacker.inventory != null ? attacker.inventory.weapon : null, "blow");
        emitDamageRollLog(level, attacker, target, bk, previewDealt);

        if (attacker.position != null && kb > 0) {
            DamageCause kbCause = new DamageCause(attacker,
                    attacker.inventory != null ? attacker.inventory.weapon : null,
                    "wall-slam");
            MobLifecycle.knockBack(level, target, kb, attacker.position, wallSlam, kbCause);
        }
        boolean killed;
        if (target.hp <= 0) {
            killed = true;
        } else if (shielded) {
            // SHIELDED still consumed the swing; no damage to apply but the
            // attacker's combat memory shouldn't fire (no damage = no
            // memory). Skip processAttack entirely.
            killed = false;
        } else {
            killed = processAttack(level, attacker, target, rawDealt,
                    AttackType.MELEE, DamageElement.PHYSICAL, bk, null, true /*suppressLog*/);
        }
        if (killed) emitKillLog(level, attacker, target);
    }

    /**
     * Single entry point for any damage done to a mob. Every path - melee, thrown item, magic
     * missile, or environmental harm - routes through here so HP reduction,
     * kill resolution, death-history bookkeeping, and flinch animation stay in lockstep.
     *
     * <p>Does NOT spawn floating text or particle bursts. Those vary per source (melee wants
     * blood, thrown wants the thrown-item sprite continuing its arc, magic missile has its
     * own trail already) and should be added by the caller before invoking this method.
     *
     * <p>When the blow lands a killing hit the {@code attacker}'s {@code history} gets a
     * {@link com.bjsp123.rl2.model.HistoricalRecord#kill KILL} entry via {@link #killMob};
     * that is the single authoritative place combat history is written.
     *
     * @param attacker the mob credited with the blow. {@code null} for environmental damage
     *                 (e.g. a chasm fall); no XP or history is recorded in that case.
     * @param target   the mob receiving the blow.
     * @param rawDealt non-negative pre-buff-mitigation damage. Stat-based resists (armor
     *                 for physical, magicResist for magic) are expected to have been
     *                 subtracted by the caller; {@link Buff.BuffType#PROTECTION} and
     *                 {@link Buff.BuffType#ANTI_MAGIC} are applied here based on
     *                 {@code element}.
     * @param type     mechanism of the attack ({@link AttackType}).
     * @param element  elemental class of the damage ({@link DamageElement}). Selects
     *                 which buff (if any) mitigates the blow.
     * @return {@code true} iff the blow killed {@code target}.
     */
    public static boolean processAttack(Level level, Mob attacker, Mob target,
                                        int rawDealt, AttackType type, DamageElement element) {
        return processAttack(level, attacker, target, rawDealt, type, element, null, null);
    }

    /** Variant that accepts a {@link DamageBreakdown} pre-populated with the caller's
     *  stat-based mitigations (armor, magicResist). The breakdown is augmented with the
     *  PROTECTION / ANTI_MAGIC entries applied here, then a LOW-priority tuning log
     *  line is emitted. Pass {@code null} for {@code breakdown} to skip log enrichment;
     *  a default breakdown with {@code rolled = rawDealt} and no pre-mitigations will
     *  still be logged. */
    public static boolean processAttack(Level level, Mob attacker, Mob target,
                                        int rawDealt, AttackType type, DamageElement element,
                                        DamageBreakdown breakdown) {
        return processAttack(level, attacker, target, rawDealt, type, element, breakdown, null);
    }

    /** Full variant carrying an explicit {@link DamageCause} chain for causal
     *  attribution in the death screen + log messages. Indirect damage paths
     *  (fire DOT, wall-slam, chasm fall) build a cause at the originating
     *  site and pass it here; direct hits can leave {@code cause = null},
     *  in which case the method synthesises a "blow" cause from the
     *  attacker + their equipped weapon. */
    public static boolean processAttack(Level level, Mob attacker, Mob target,
                                        int rawDealt, AttackType type, DamageElement element,
                                        DamageBreakdown breakdown, DamageCause cause) {
        return processAttack(level, attacker, target, rawDealt, type, element, breakdown, cause, false);
    }

    /** Full variant with a {@code suppressLog} flag. When true, the canonical
     *  damageRoll log line is NOT emitted from inside this method - the
     *  caller is responsible for emitting it (e.g. {@link #attack} pre-emits
     *  it with the knockback annotation BEFORE the knockback slam logs
     *  follow, so the narrative reads top-to-bottom). Everything else (HP
     *  application, floaters, combat memory, flinch, brand-on-hit) still
     *  fires as before. */
    public static boolean processAttack(Level level, Mob attacker, Mob target,
                                        int rawDealt, AttackType type, DamageElement element,
                                        DamageBreakdown breakdown, DamageCause cause,
                                        boolean suppressLog) {
        if (target == null) return false;
        // Synthesise a default cause for direct hits from {@code attacker}.
        // The attacker's currently-equipped weapon is the most common
        // originating item; melee callers and ranged-weapon callers rely on
        // this default. Indirect damage paths (fire DOT, knockback wall-slam,
        // chasm fall) pass an explicit DamageCause and don't hit this branch.
        final DamageCause effectiveCause = cause != null ? cause : new DamageCause(
                attacker,
                attacker != null && attacker.inventory != null ? attacker.inventory.weapon : null,
                "blow");
        if (type == AttackType.MELEE) com.bjsp123.rl2.util.ActionTracker.bumpMelee(attacker);
        if (rawDealt < 0) rawDealt = 0;
        if (rawDealt > 0 && BuffSystem.hasBuff(target, com.bjsp123.rl2.model.Buff.BuffType.SHIELDED)) return false;
        // WRAITH_DODGE: a mob with the dodge ability (wraiths) or the DODGE perk (player) slides
        // to a free adjacent square and avoids the whole hit, then goes on cooldown. Only fires
        // off-cooldown and when there's a suitable square to dash to.
        if (rawDealt > 0 && MobAiBehavior.tryWraithDodge(level, target, attacker)) return false;
        // Stat-based per-element immunity: poisonImmune zeroes incoming POISON
        // before mitigation. The HIT event still fires below with dealt=0 so the
        // player sees "-0 poison" floating, signalling the immunity.
        if (element == DamageElement.POISON && target.effectiveStats().poisonImmune) {
            rawDealt = 0;
        }
        // Buff-based mitigation. PROTECTION blunts physical, ANTI_MAGIC blunts magic
        // and fire. Other elements (POISON, SHOCK, COLD) ignore both buffs.
        int dealt = switch (element) {
            case PHYSICAL          -> BuffSystem.mitigatePhysicalDamage(target, rawDealt);
            case MAGIC, FIRE       -> BuffSystem.mitigateMagicDamage(target, rawDealt);
            case POISON, SHOCK, COLD -> rawDealt;
        };
        // Wet vulnerability (RL-31): water conducts lightning (x2) and aggravates
        // cold (x4). Applied after mitigation so it scales the real hit.
        if (dealt > 0 && MobSystem.isWet(level, target)) {
            if (element == DamageElement.COLD)       dealt *= 4;
            else if (element == DamageElement.SHOCK) dealt *= 2;
        }
        // BLUNT floater - emitted ahead of the HIT/MISS floater whenever a defensive
        // buff ate part of the hit, so the renderer plays the dim "blunt" first.
        if (dealt < rawDealt && rawDealt > 0 && level != null && level.events != null) {
            level.events.add(new com.bjsp123.rl2.event.GameEvent.DamageDealt(
                    target, dealt, com.bjsp123.rl2.event.GameEvent.DamageMessage.BLUNT,
                    attacker, element, effectiveCause));
        }
        // Damage-roll tuning log. Falls back to a default breakdown when the caller
        // didn't pre-populate one, so every processAttack call still produces a line.
        DamageBreakdown bk = breakdown != null ? breakdown : new DamageBreakdown(element, rawDealt);
        if (bk.type  == null) bk.type  = type;
        if (bk.cause == null) bk.cause = effectiveCause;
        // Only add PROTECTION when the breakdown doesn't already carry one
        // (the pre-mitigation path in {@link #attack} adds PROTECTION
        // itself before suppressLog=true, so re-adding would duplicate
        // the entry in the parenthetical).
        if (dealt < rawDealt && rawDealt > 0
                && !breakdownAlreadyHas(bk, "PROTECTION")
                && !breakdownAlreadyHas(bk, "ANTI_MAGIC")) {
            bk.add(element == DamageElement.PHYSICAL ? "PROTECTION" : "ANTI_MAGIC",
                    rawDealt - dealt);
        }
        if (!suppressLog) emitDamageRollLog(level, attacker, target, bk, dealt);
        target.hp -= dealt;
        // Capture the most recent damaging hit on the player for the death
        // screen headline (E1). Cleared via {@link #resetLastPlayerHit} on
        // new run.
        if (dealt > 0 && target.isPlayer) {
            MobSystem.lastPlayerCause     = effectiveCause;
            MobSystem.lastPlayerElement   = element;
            MobSystem.lastPlayerHitDealt  = dealt;
        }
        // PHASE persists through both dealing and taking damage - the holder
        // gets the full duration as a real combat window, not just a panic
        // button. Earlier versions dropped on damage events, which made the
        // rogue's jade fish purely escapist.
        if (dealt > 0) {
            BuffSystem.shortenFrozenOnDamage(target);
        }
        // God-mode clamp: damage applies normally but hp is floored at 1
        // so a god-mode target never dies. Set on the player Mob from
        // the character-select pre-game options popup.
        if (target.godMode && target.hp < 1) target.hp = 1;
        // A blow always wakes the target - anything from sleeping through hiding snaps
        // to AWAKE so the AI can react this turn instead of staying ASLEEP / HIDING /
        // SEEKING_HIDING through the hit. Zero-damage blows still wake; the mob noticed
        // the swing. AWAKE / FOLLOWING mobs don't need transitioning.
        if (target.stateOfMind == Mob.StateOfMind.ASLEEP
                || target.stateOfMind == Mob.StateOfMind.HIDING
                || target.stateOfMind == Mob.StateOfMind.SEEKING_HIDING) {
            // wakeMob only logs the ASLEEP -> AWAKE case (the user-visible
            // "wakes up" beat); HIDING / SEEKING_HIDING transitions still
            // flip silently to AWAKE here.
            MobSystem.wakeMob(level, target, "damaged by " + element.name().toLowerCase());
            if (target.stateOfMind != Mob.StateOfMind.AWAKE) {
                target.stateOfMind = Mob.StateOfMind.AWAKE;
            }
            BuffSystem.removeBuff(target, com.bjsp123.rl2.model.Buff.BuffType.HIDING);
        }
        // Hostility from damage: a mob that takes real damage from an attacker promotes
        // that attacker into its attackTypes (and recordCombatMemory's reciprocal does the
        // same the other way). A miss leaves attitudes unchanged - only damaging blows
        // count, so a sparring kitten that scratches without breaking skin doesn't turn
        // the household feral. Environmental damage has no attacker and is skipped.
        if (dealt > 0 && attacker != null) {
            MobSystem.recordCombatMemory(level, attacker, target, "attacked");
        }
        // Floating combat text - every visible blow produces a number ("-N" red for a
        // hit, "miss" yellow for a glancing/zero-damage strike). Heal text is added by
        // the heal helper. Centralised here so every damage source (melee, throw, ranged
        // missile, environmental) lights up the same indicator without each call site
        // having to remember to spawn the effect.
        if (level != null && level.events != null) {
            level.events.add(new com.bjsp123.rl2.event.GameEvent.DamageDealt(
                    target, dealt,
                    dealt > 0
                            ? com.bjsp123.rl2.event.GameEvent.DamageMessage.HIT
                            : com.bjsp123.rl2.event.GameEvent.DamageMessage.MISS,
                    attacker, element, effectiveCause));
        }
        // HIGH-priority element-tagged log line for non-PHYSICAL damage. The
        // PHYSICAL melee path emits its own damageRoll line via
        // logAttackOutcome; PHYSICAL throws/wall-slams stay on the LOW-pri
        // damageRoll line as before. This line is the player-visible "X takes 3
        // poison damage" feedback for DOT ticks and wand zaps.
        if (dealt > 0 && element != DamageElement.PHYSICAL) {
            boolean playerInvolved = (attacker != null && attacker.isPlayer)
                                  || target.isPlayer;
            String originName = Messages.formatCauseOrigin(level, effectiveCause);
            EventLog.add(Messages.elementalDamage(
                    MobSystem.nameForLog(level, target), element, dealt, originName, playerInvolved));
        }
        // Only flinch when real damage lands - a 0-damage blow doesn't visually stagger the
        // target. Environmental damage has no attacker and therefore no direction to recoil
        // from. Off-screen flinches are suppressed for the same reason as off-screen lunges:
        // no observer means no animation.
        if (dealt > 0 && attacker != null
                && (MobSystem.isVisibleToPlayer(level, attacker) || MobSystem.isVisibleToPlayer(level, target))) {
            startHitFlinch(level, target, attacker);
        }
        // Poison-on-hit. Spiders carry {@code intrinsic.poisonsOnAttack} so any blow
        // they land applies POISONED at level = attacker character level, duration
        // = level x 3 turns. Fires on any landed hit even if armour absorbed it -
        // a 1-damage spider bite vs scale mail still injects venom.
        //
        // Gated on PHYSICAL damage so the per-turn POISON DOT (which routes
        // back through {@link #processAttack} with element = POISON, attacker =
        // the buff's source) doesn't re-credit the spider and refresh the
        // POISONED duration on every tick - that turned the poison buff
        // permanent until the spider died.
        if (attacker != null
                && attacker.effectiveStats().poisonsOnAttack
                && target.hp > 0
                && element == DamageElement.PHYSICAL
                && !target.effectiveStats().poisonImmune) {
            int lvl = Math.max(1, attacker.characterLevel);
            // The unique spider injects full-strength venom (~1.5 stacks/level);
            // ordinary spiders inflict 2/3 of that (~1 stack/level).
            int stacks = attacker.unique
                    ? Math.max(1, lvl * 3 / 2)
                    : Math.max(1, lvl);
            BuffSystem.apply(level, target, com.bjsp123.rl2.model.Buff.BuffType.POISONED,
                    stacks, attacker);
        }
        // Reactive fire burst. Mobs with {@link Mob#fireSpreadOnAttack} (e.g. blazing
        // firemouse) ignite their own tile + the four cardinal neighbours when they take
        // a damaging blow. The mob's own fireImmune flag keeps it from cooking itself.
        // Triggered after damage but before kill resolution so the burst fires even when
        // the blow is fatal.
        if (dealt > 0 && target.effectiveStats().fireSpreadOnAttack && level != null && target.position != null) {
            int tx = target.position.tileX(), ty = target.position.tileY();
            FireSystem.ignite(level, tx,     ty);
            FireSystem.ignite(level, tx + 1, ty);
            FireSystem.ignite(level, tx - 1, ty);
            FireSystem.ignite(level, tx,     ty + 1);
            FireSystem.ignite(level, tx,     ty - 1);
        }
        // Brand on-hit elemental effect - fired ONLY when a melee blow lands,
        // using the attacker's equipped weapon brand (if any). Gating on
        // MELEE is essential: the LIGHTNING brand's chain re-enters
        // processAttack with AttackType.MAGIC for each chain victim, and
        // without this gate every chain link would re-trigger the brand and
        // recurse infinitely (StackOverflowError). Thrown / wand / ranged
        // hits also bypass the brand for the same documented reason - the
        // brand is on the equipped weapon swung in melee.
        if (dealt > 0 && type == AttackType.MELEE
                && attacker != null && attacker.inventory != null) {
            com.bjsp123.rl2.model.Item weapon = attacker.inventory.weapon;
            if (weapon != null && weapon.brand != null) {
                BrandSystem.applyBrandOnHit(level, attacker, target, weapon);
            }
        }
        // Beacon spirits: a landed player blow on the Great Wraith may shatter
        // one orbiting spirit (50%), recomputing the boss's power from the
        // reduced count. Reaching this point means the hit landed (past the
        // SHIELDED / WRAITH_DODGE early-returns), so even a fully-mitigated
        // 0-damage swing counts. Only fires while the boss survives.
        if (target.hp > 0) {
            MobLifecycle.maybeShatterBeaconSpirit(level, attacker, target, type);
        }
        if (target.hp <= 0) {
            MobLifecycle.killMob(level, target, attacker);
            return true;
        }
        return false;
    }

    /** True if stepping from ({@code cx},{@code cy}) to ({@code nx},{@code ny}) brings
     *  the mover strictly closer to any visible terrifying mob. */
    static boolean stepWouldApproachTerror(Mob mover, Level level,
                                                   int cx, int cy, int nx, int ny) {
        if (level == null || level.mobs == null || level.visible == null) return false;
        for (Mob m : level.mobs) {
            if (m == mover || m.position == null) continue;
            if (!m.effectiveStats().terrifying) continue;
            int mx = m.position.tileX(), my = m.position.tileY();
            if (mx < 0 || my < 0 || mx >= level.width || my >= level.height) continue;
            if (!level.visible[mx][my]) continue;
            int distNow  = Math.max(Math.abs(mx - cx), Math.abs(my - cy));
            int distNext = Math.max(Math.abs(mx - nx), Math.abs(my - ny));
            if (distNext < distNow) return true;
        }
        return false;
    }

    /** Emits {@code MobHitFlinched}, the event that triggers rgame's flinch animation.
     *  The flinch visual itself is scheduled by rgame's {@code Animator} when it
     *  consumes the event - rlib only records that the hit happened. */
    public static void startHitFlinch(Level level, Mob target, Mob hitSource) {
        if (target == null || hitSource == null
                || target.position == null || hitSource.position == null) return;
        if (level != null && level.events != null) {
            level.events.add(new com.bjsp123.rl2.event.GameEvent.MobHitFlinched(target, hitSource));
        }
    }

    /** Emit the kill line for a fatal blow. Called from {@link #attack}
     *  after the full melee chain resolves (damage + knockback chain).
     *  Selects the right Messages factory by player-involvement. */
    private static void emitKillLog(Level level, Mob attacker, Mob target) {
        String atkName = MobSystem.nameForLog(level, attacker);
        String tgtName = MobSystem.nameForLog(level, target);
        if (attacker.isPlayer) {
            EventLog.add(Messages.playerKill(atkName, tgtName));
        } else if (target.isPlayer) {
            EventLog.add(Messages.enemyKill(atkName, tgtName));
        } else {
            EventLog.add(Messages.mobKill(atkName, tgtName));
        }
    }

    /** Push the per-attack {@link DamageBreakdown} to the event log. This is
     *  the canonical "X hits Y for N damage" line - priority is HIGH when
     *  the player is involved (the previous separate playerHit / enemyHit /
     *  mobHit emissions are gone because they were duplicates of this line
     *  without the mitigation suffix). Called from {@link #processAttack}
     *  for non-melee paths, and from {@link #attack} for melee (which
     *  passes {@code kbSquares} for the knockback annotation and suppresses
     *  the processAttack emission to avoid a dupe). */
    /** True if {@code bk}'s mitigations list already carries an entry
     *  starting with {@code label} (e.g. "PROTECTION -3" matches "PROTECTION").
     *  Used by {@link #processAttack} to skip re-adding the same mitigation
     *  entry when {@link #attack} pre-mitigated and pre-emitted the
     *  damageRoll log line. */
    private static boolean breakdownAlreadyHas(DamageBreakdown bk, String label) {
        if (bk == null || bk.mitigations == null) return false;
        for (String e : bk.mitigations) {
            if (e != null && e.startsWith(label)) return true;
        }
        return false;
    }

    static void emitDamageRollLog(Level level, Mob attacker, Mob target,
                                  DamageBreakdown bk, int dealt) {
        if (bk == null) return;
        // Attacker may be the {@code cause.origin()} for indirect damage
        // (a fire DOT carries the wand's caster on the cause; the direct
        // {@code attacker} arg is null). Prefer the cause's origin when the
        // direct attacker is missing so the log line still attributes.
        Mob effectiveAttacker = attacker;
        if (effectiveAttacker == null && bk.cause != null) {
            effectiveAttacker = bk.cause.origin();
        }
        String atk = effectiveAttacker == null ? null : MobSystem.nameForLog(level, effectiveAttacker);
        String tgt = target == null ? "?" : MobSystem.nameForLog(level, target);
        boolean attackerIsPlayer = effectiveAttacker != null
                && effectiveAttacker.isPlayer;
        boolean targetIsPlayer = target != null && target.isPlayer;
        boolean playerInv = attackerIsPlayer || targetIsPlayer;
        String itemName = (bk.cause != null && bk.cause.originItem() != null
                && bk.cause.originItem().name != null)
                ? bk.cause.originItem().name : null;
        EventLog.add(Messages.damageRoll(atk, attackerIsPlayer,
                tgt, targetIsPlayer,
                bk.type, itemName,
                bk.element.name(),
                bk.rolled, dealt, bk.mitigations, bk.kbSquares, playerInv));
    }
}
