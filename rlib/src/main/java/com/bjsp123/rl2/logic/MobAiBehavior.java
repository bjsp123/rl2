package com.bjsp123.rl2.logic;

import com.bjsp123.rl2.logic.MobSystem.AttackType;
import com.bjsp123.rl2.logic.MobSystem.Attitude;
import com.bjsp123.rl2.logic.MobSystem.DamageBreakdown;
import com.bjsp123.rl2.logic.MobSystem.DamageCause;
import com.bjsp123.rl2.logic.MobSystem.DamageElement;
import com.bjsp123.rl2.model.Level;
import com.bjsp123.rl2.model.Level.Vegetation;
import com.bjsp123.rl2.model.Mob;
import com.bjsp123.rl2.model.Mob.Behavior;
import com.bjsp123.rl2.model.Mob.StateOfMind;
import com.bjsp123.rl2.model.Point;

/**
 * Per-turn mob brains extracted from {@link MobSystem}: the {@link #processAiTurn}
 * dispatcher, innate-ability casting (including the wraith dodge), the dumb and
 * stand-off ranged-mob AIs with their animation-gated shot lifecycle, the default
 * melee/flee/follow behaviour tree, explore-hide and hunter AIs, stealth-notice
 * checks, and target/tile selection helpers.
 */
public final class MobAiBehavior {

    private MobAiBehavior() {}

    public static void processAiTurn(Mob mob, Level level) {
        if (mob.ticksTillMove > 0) return;
        if (BuffSystem.hasBuff(mob, com.bjsp123.rl2.model.Buff.BuffType.FROZEN)) {
            mob.intent = Mob.Intent.IDLE;
            TurnSystem.applyMoveCost(mob, mob.effectiveStats().moveCost);
            return;
        }
        // Player has no AI of its own - short-circuit so the wake gate below can't
        // accidentally bill the player's turn (which would freeze input).
        if (mob.behavior == Behavior.PLAYER) return;

        boolean inanimate = (mob.behavior == Behavior.INANIMATE);

        // Sleep gate - applied uniformly to every behaviour so mice / dogs / cats /
        // blobs / anthills are dormant until a relevant target wanders into their
        // wake radius AND line of sight (loud events still wake through walls via
        // wakeMobsNear). INANIMATE mobs use the inverse perspective (wake when a
        // hostile is incoming) since their own attackTypes is empty.
        if (mob.stateOfMind == StateOfMind.ASLEEP) {
            boolean wakeUp = inanimate
                    ? hasIncomingAttackerWithin(mob, level, mob.effectiveStats().wakeRadius)
                    : hasAttitudeTargetWithin(mob, level, mob.effectiveStats().wakeRadius);
            if (wakeUp) {
                MobSystem.wakeMob(level, mob, "sensing something nearby");
            } else {
                mob.intent = Mob.Intent.IDLE;
                // Only mobile mobs need a sleep cooldown - INANIMATE never has its
                // ticksTillMove decremented (TurnSystem.tick), so paying a move cost
                // would freeze them out of future wake checks forever.
                if (!inanimate) {
                    TurnSystem.applyMoveCost(mob, mob.effectiveStats().moveCost);
                }
                return;
            }
        }

        // INANIMATE: awake-state-aware (so the sleep-Z effect drops once a player
        // approaches), but never picks a target, never moves, never attacks.
        if (inanimate) {
            mob.intent = Mob.Intent.IDLE;
            return;
        }

        // Support-cast abilities (kobold general's haste/heal, etc.) - runs before
        // behaviour dispatch so any mob carrying an ability list casts before
        // defaulting to its normal AI step. Off-cooldown casts consume the turn.
        if (tryCastAbilities(mob, level)) {
            mob.intent = Mob.Intent.USING_ABILITY;
            return;
        }

        // Inventory item use (potions, magic-missile wand, dog wand, bombs).
        // Each usable item rolls 50% per turn - cheap heuristic that gives a
        // mob carrying a healing potion a steady chance to drink when low,
        // and a rogue carrying bombs a steady chance to lob one. Skips
        // anything that would harm self or an ally.
        if (MobAiItems.tryUseInventoryItem(mob, level)) {
            mob.intent = Mob.Intent.USING_ITEM;
            return;
        }

        if (mob.behavior == Behavior.SMART) {
            MobBrains.Brain brain = GameBalance.SMART_AI_ENABLED ? MobBrains.get(Behavior.SMART) : null;
            if (brain != null) {
                brain.run(mob, level);
            } else {
                processMobAi(mob, level);
            }
        } else if (mob.behavior == Behavior.MOB) {
            processMobAi(mob, level);
        } else if (mob.behavior == Behavior.EXPLORE_HIDE) {
            processExploreHideAi(mob, level);
        } else if (mob.behavior == Behavior.HUNTER) {
            processHunterAi(mob, level);
        } else if (mob.behavior == Behavior.RANGED_MOB_DUMB) {
            processRangedMobDumbAi(mob, level);
        } else if (mob.behavior == Behavior.RANGED_MOB_STANDOFF) {
            processRangedMobStandoffAi(mob, level);
        }
    }

    /**
     * Try to cast one of {@code caster.abilities} on a friendly target. Each
     * ability is checked in order; the first one that's both off-cooldown and has
     * a valid friendly target fires, applies its cooldown buff, consumes the
     * caster's attack-cost turn, and returns {@code true}. Returns {@code false}
     * if no ability is castable, in which case the caller continues to normal AI.
     *
     * <p>For buff abilities the target must lack {@code applies}; for heal
     * abilities the target must be below max HP. Targets are picked nearest-first
     * within the caster's vision radius, friend/foe via {@link Attitude#ALLY}.
     */
    public static boolean tryCastAbilities(Mob caster, Level level) {
        if (caster == null || caster.abilities == null || caster.abilities.isEmpty()) return false;
        double vision = caster.effectiveStats().visionRadius;
        for (Mob.MobAbility ab : caster.abilities) {
            if (ab == null) continue;
            // WRAITH_DODGE is reactive (handled in processAttack), never cast proactively.
            if (ab.kind == Mob.MobAbility.AbilityKind.WRAITH_DODGE) continue;
            if (ab.cooldownTracker != null
                    && BuffSystem.hasBuff(caster, ab.cooldownTracker)) continue;
            Mob target = pickAbilityTarget(caster, level, ab, vision);
            if (target == null) continue;
            if (MobSystem.isVisibleToPlayer(level, caster) || MobSystem.isVisibleToPlayer(level, target)) {
                String abilityDesc = ab.kind == Mob.MobAbility.AbilityKind.HEAL
                        ? "a healing ability"
                        : ab.kind == Mob.MobAbility.AbilityKind.TELEPORT
                        ? "a teleport ability"
                        : (ab.applies != null
                                ? "a " + ab.applies.name().toLowerCase() + " ability"
                                : "an ability");
                boolean inv = caster.isPlayer
                        || target.isPlayer;
                EventLog.add(Messages.mobUsesAbility(MobSystem.nameForLog(level, caster),
                        abilityDesc, MobSystem.nameForLog(level, target), inv));
            }
            switch (ab.kind) {
                case BUFF -> {
                    if (level.events != null) {
                        level.events.add(new com.bjsp123.rl2.event.GameEvent.MobAbilityUsed(
                                caster, target, caster.position, target.position));
                    }
                    BuffSystem.apply(level, target, ab.applies,
                            Math.max(1, ab.appliedDuration), caster);
                }
                case HEAL -> {
                    if (level.events != null) {
                        level.events.add(new com.bjsp123.rl2.event.GameEvent.MobAbilityUsed(
                                caster, target, caster.position, target.position));
                    }
                    MobSystem.heal(level, target, ab.healAmount);
                }
                case TELEPORT -> {
                    // tryTeleportToTarget handles LOS, free-tile, and the
                    // visual event; if it bails the ability is wasted, but
                    // still costs the turn so the caster doesn't loop forever.
                    MobMovement.tryTeleportToTarget(level, caster, target);
                }
            }
            if (ab.cooldownTracker != null && ab.cooldownTurns > 0) {
                BuffSystem.apply(level, caster, ab.cooldownTracker,
                        ab.cooldownTurns, caster);
            }
            TurnSystem.applyActionCost(caster, caster.effectiveStats().attackCost);
            return true;
        }
        return false;
    }

    /** WRAITH_DODGE cooldown length (turns) for {@code m}, or {@code -1} if it can't dodge.
     *  The player gets it from the DODGE perk ({@code 11 - level}); other mobs from a
     *  WRAITH_DODGE ability's configured cooldown. */
    private static int wraithDodgeCooldownTurns(Mob m) {
        if (m == null) return -1;
        if (m.perks != null) {
            int lvl = m.perks.getOrDefault(com.bjsp123.rl2.model.Perk.DODGE, 0);
            if (lvl > 0) return Math.max(1, 11 - lvl);
        }
        if (m.abilities != null) {
            for (Mob.MobAbility ab : m.abilities) {
                if (ab != null && ab.kind == Mob.MobAbility.AbilityKind.WRAITH_DODGE) {
                    return Math.max(1, ab.cooldownTurns);
                }
            }
        }
        return -1;
    }

    /** Reactive wraith-dodge: if {@code self} can dodge (ability/perk), is off cooldown, and a
     *  free adjacent square exists, slide there (avoiding the incoming hit), start the cooldown,
     *  and return true. Otherwise false (the attack resolves normally). */
    static boolean tryWraithDodge(Level level, Mob self, Mob attacker) {
        if (level == null || self == null || self.position == null || self.hp <= 0) return false;
        int cooldownTurns = wraithDodgeCooldownTurns(self);
        if (cooldownTurns < 0) return false;
        if (BuffSystem.hasBuff(self, com.bjsp123.rl2.model.Buff.BuffType.WRAITH_DODGE_COOLDOWN)) {
            return false;
        }
        Point spot = pickDodgeTile(level, self, attacker);
        if (spot == null) return false;   // no suitable square - can't dodge
        Point from = self.position;
        self.position = spot;
        self.targetPosition = null;
        if (level.events != null) {
            level.events.add(new com.bjsp123.rl2.event.GameEvent.MobPhaseDodged(self, from, spot));
        }
        BuffSystem.apply(level, self, com.bjsp123.rl2.model.Buff.BuffType.WRAITH_DODGE_COOLDOWN,
                cooldownTurns, self);
        // Log whenever the player could witness the dodge - either the tile it
        // left or the tile it blinked to was in view. (Checking only the new
        // tile missed dodges that ended just outside the player's FOV.)
        if (self.isPlayer
                || MobSystem.tileVisibleToPlayer(level, from)
                || MobSystem.tileVisibleToPlayer(level, spot)) {
            EventLog.add(Messages.wraithDodged(MobSystem.nameForLog(level, self),
                    self.isPlayer));
        }
        return true;
    }

    /** Pick a free 8-neighbour floor tile for {@code self} to dodge to - floor-like, not
     *  movement-blocking, unoccupied - preferring the tile FARTHEST from {@code attacker}
     *  (dodge away). Returns null when no neighbour qualifies. */
    private static Point pickDodgeTile(Level level, Mob self, Mob attacker) {
        int sx = self.position.tileX(), sy = self.position.tileY();
        int ax = attacker != null && attacker.position != null ? attacker.position.tileX() : sx;
        int ay = attacker != null && attacker.position != null ? attacker.position.tileY() : sy;
        Point best = null;
        int bestDist = -1;
        for (int dy = -1; dy <= 1; dy++) {
            for (int dx = -1; dx <= 1; dx++) {
                if (dx == 0 && dy == 0) continue;
                int nx = sx + dx, ny = sy + dy;
                if (!MobLifecycle.isFreeFloor(level, nx, ny)) continue;
                int d = Math.max(Math.abs(nx - ax), Math.abs(ny - ay));
                if (d > bestDist) { bestDist = d; best = new Point(nx, ny); }
            }
        }
        return best;
    }

    /** Nearest valid target within {@code vision} (Chebyshev) for {@code ab}.
     *  {@code BUFF} / {@code HEAL} pick allies (buffs skip already-buffed
     *  targets, heals skip full-HP targets); {@code TELEPORT} picks the nearest
     *  enemy in line of sight with a free adjacent tile to land on. Self never
     *  qualifies. */
    private static Mob pickAbilityTarget(Mob caster, Level level,
                                         Mob.MobAbility ab, double vision) {
        int cx = caster.position.tileX(), cy = caster.position.tileY();
        Mob best = null;
        int bestD = Integer.MAX_VALUE;
        for (Mob m : level.mobs) {
            if (m == caster || m.hp <= 0 || m.position == null) continue;
            switch (ab.kind) {
                case BUFF -> {
                    if (!MobSystem.isAlly(caster, m)) continue;
                    if (BuffSystem.hasBuff(m, ab.applies)) continue;
                }
                case HEAL -> {
                    if (!MobSystem.isAlly(caster, m)) continue;
                    if (m.hp >= m.effectiveStats().maxHp) continue;
                }
                case TELEPORT -> {
                    if (MobSystem.getAttitudeToMob(caster, m) != Attitude.ATTACK) continue;
                    if (!LevelUtilities.getLineOfSight(level, caster, m.position)) continue;
                    if (MobHooks.freeAdjacentFloor(level, m.position) == null) continue;
                }
            }
            int d = Math.max(Math.abs(m.position.tileX() - cx),
                             Math.abs(m.position.tileY() - cy));
            if (d > vision) continue;
            if (d < bestD) { bestD = d; best = m; }
        }
        return best;
    }

    /** Standard turns away from the leader's tile that a stand-off ranged mob will retreat
     *  back to range from. Inside this radius they kite even if their target is reachable. */
    private static final int STANDOFF_BUBBLE_TILES = 2;

    /**
     * RANGED_MOB_DUMB AI - same wake / flee / attack-target / follow / wander structure as
     * {@link #processMobAi}, but if the mob has a ranged attack ready and the chosen
     * attack target is in range + LOS but not adjacent, the mob fires a projectile
     * instead of stepping.
     */
    private static void processRangedMobDumbAi(Mob mob, Level level) {
        // ASLEEP gating happens in processAiTurn before dispatch (see processMobAi).
        Mob.Intent prevIntent = mob.intent;
        if (tryFleeStep(mob, level, false)) return;
        Mob attackTarget = nearestAttackTarget(mob, level);
        if (attackTarget != null) {
            // Per-spec: DUMB shoots only when not adjacent - at adjacent it prefers
            // melee (the closing-step path swings on contact via the mob-occupant
            // resolution in stepTowardTarget).
            int cheb = LevelFactoryUtils.chebyshev(mob.position, attackTarget.position);
            if (cheb > 1 && tryRangedShot(mob, attackTarget, level)) {
                mob.intent = Mob.Intent.SHOOTING;
                return;
            }
            mob.intent = (cheb > 1 && BuffSystem.hasBuff(mob,
                    com.bjsp123.rl2.model.Buff.BuffType.RANGED_COOLDOWN))
                    ? Mob.Intent.RELOADING : Mob.Intent.PURSUING;
            mob.targetPosition = attackTarget.position;
            stepOrIdle(mob, level);
            return;
        }
        defaultAiTail(mob, level, prevIntent, false);
    }

    /**
     * RANGED_MOB_STANDOFF AI - kite the target. If the mob is within
     * {@link #STANDOFF_BUBBLE_TILES} tiles of an attack target it tries to back away
     * (the AI sets a target tile farther from the enemy and steps toward it). Otherwise
     * it tries the same ranged-shot path as {@link #processRangedMobDumbAi}; failing
     * that, it closes to range like the dumb variant.
     */
    private static void processRangedMobStandoffAi(Mob mob, Level level) {
        // ASLEEP gating happens in processAiTurn before dispatch (see processMobAi).
        Mob.Intent prevIntent = mob.intent;
        if (tryFleeStep(mob, level, false)) return;
        Mob attackTarget = nearestAttackTarget(mob, level);
        if (attackTarget != null) {
            int cheb = LevelFactoryUtils.chebyshev(mob.position, attackTarget.position);
            // Adjacent target - never retreat. Try a point-blank ranged shot if the cooldown
            // is up; otherwise melee on bump. Without this branch the imp kites every turn
            // the cooldown is on, and the player can never close the gap to fight back.
            if (cheb <= 1) {
                if (tryRangedShot(mob, attackTarget, level)) {
                    mob.intent = Mob.Intent.SHOOTING;
                    return;
                }
                mob.intent = Mob.Intent.PURSUING;
                mob.targetPosition = attackTarget.position;
                stepOrIdle(mob, level);
                return;
            }
            // Shoot first - a stand-off ranged mob prefers to fire over moving when the
            // shot is available. Only on cooldown does the standoff/retreat/close logic
            // run, and even then only outside melee range.
            if (tryRangedShot(mob, attackTarget, level)) {
                mob.intent = Mob.Intent.SHOOTING;
                return;
            }
            boolean onCooldown = BuffSystem.hasBuff(mob,
                    com.bjsp123.rl2.model.Buff.BuffType.RANGED_COOLDOWN);
            if (cheb <= STANDOFF_BUBBLE_TILES) {
                Point retreat = findRetreatTile(mob, attackTarget, level);
                if (retreat != null) {
                    mob.intent = onCooldown ? Mob.Intent.RELOADING : Mob.Intent.KITING;
                    mob.targetPosition = retreat;
                    stepOrIdle(mob, level);
                    return;
                }
                // Cornered - fall through to closing distance + melee.
            }
            mob.intent = onCooldown ? Mob.Intent.RELOADING : Mob.Intent.PURSUING;
            mob.targetPosition = attackTarget.position;
            stepOrIdle(mob, level);
            return;
        }
        defaultAiTail(mob, level, prevIntent, false);
    }

    /**
     * If the mob's ranged attack is armed and the target is in range + LOS but not
     * adjacent, fire a projectile and burn {@link Mob#rangedCost} ticks. Returns
     * {@code true} when a shot fired (the AI should short-circuit). Cooldown is decremented
     * here when no shot fires, so a turn that didn't shoot still ticks toward readiness.
     */
    private static boolean tryRangedShot(Mob shooter, Mob target, Level level) {
        com.bjsp123.rl2.model.StatBlock ss = shooter.effectiveStats();
        if (ss.rangedDamage.max() <= 0) return false;
        int cheb = LevelFactoryUtils.chebyshev(shooter.position, target.position);
        // No point-blank gate here - the standoff imp needs to fire while the player is
        // adjacent (otherwise the player chases and the imp kites forever, never
        // shooting). Per-behaviour callers that prefer melee at adjacent
        // (RANGED_MOB_DUMB) gate on adjacency themselves before invoking tryRangedShot.
        if (ss.rangedDistance > 0 && cheb > ss.rangedDistance) return false;
        if (!LevelUtilities.getLineOfSight(level, shooter, target.position)) return false;
        // Sight isn't enough on its own: CRYSTAL_DOOR is transparent to sight
        // but blocks projectiles, so without this gate a crossbowman would
        // happily plink arrows into the door all day. projectileLineReaches
        // also rejects shots that would hit an intervening mob (incl. ally).
        if (!MobSystem.projectileLineReaches(level, shooter.position, target.position, shooter)) return false;
        // Cooldown gate - present-RANGED_COOLDOWN-buff means "still recharging".
        if (BuffSystem.hasBuff(shooter, com.bjsp123.rl2.model.Buff.BuffType.RANGED_COOLDOWN)) {
            return false;
        }
        // Prefer melee when adjacent — only ranged-only mobs fire point-blank.
        if (cheb == 1 && ss.damage.max() > 0) return false;
        int dmg = MobSystem.rollRange(ss.rangedDamage);
        // Clip to the first mob in the way so the missile resolves on whoever
        // it actually hits visually rather than passing through them. The
        // impact tile is locked here, at fire time - the pending-impact
        // freeze means nobody can move out of (or into) it before resolve.
        Point impact = MobSystem.firstMobBlocking(level, shooter.position, target.position, shooter);
        if (level.events != null) {
            boolean trajectoryVisible = MobSystem.trajectoryTouchesVisible(level, shooter.position, impact);
            level.events.add(new com.bjsp123.rl2.event.GameEvent.MagicMissileFired(
                    shooter, shooter.position, impact, dmg, trajectoryVisible));
        }
        int cooldownTurns = Math.max(0, ss.rangedRateOfFire - 1);
        if (cooldownTurns > 0) {
            BuffSystem.apply(level, shooter,
                    com.bjsp123.rl2.model.Buff.BuffType.RANGED_COOLDOWN,
                    cooldownTurns, shooter);
        }
        TurnSystem.applyActionCost(shooter, ss.rangedCost > 0 ? ss.rangedCost : ss.attackCost);
        // ANIMATION-GATED LIFECYCLE: defer the hit/resist/damage resolution to
        // step 4 - fired by the rgame Animator when the missile arc lands, or
        // drained between mob brains headless. Combat memory is recorded on
        // impact (in processAttack), not at the moment of firing.
        final Mob shooterFinal = shooter;
        final Point impactFinal = impact;
        final int dmgFinal = dmg;
        MobSystem.queuePendingImpact(level,
                () -> applyRangedShotImpact(level, shooterFinal, impactFinal, dmgFinal));
        return true;
    }

    /**
     * Resolve an innate ranged shot (crossbow bolt, imp spark) at its impact
     * tile - step 4 of the animation-gated lifecycle. Rolls accuracy vs
     * evasion (with {@link GameBalance#POINT_BLANK_ACCURACY_MOD} when the
     * shooter is adjacent to the impact tile), then armor / magic-resist and
     * surprise, then {@link #processAttack} for the actual damage. Queued by
     * {@link #tryRangedShot} via {@link #queuePendingImpact}; the rgame
     * Animator runs it at arc completion, headless drains run it between mob
     * brains.
     */
    public static void applyRangedShotImpact(Level level, Mob caster, Point target, int damage) {
        // Step 4 complete once this resolve runs - clear the pending-impact gate.
        if (level.pendingImpactCount > 0) level.pendingImpactCount--;
        Mob victim = MobQueries.mobAt(level, target);
        if (victim == null) return;
        boolean shotPhysical = caster != null
                && caster.rangedDamageType == com.bjsp123.rl2.model.Mob.RangedDamageType.PHYSICAL;
        DamageElement shotElement = shotPhysical ? DamageElement.PHYSICAL : DamageElement.MAGIC;
        AttackType shotType = shotPhysical ? AttackType.RANGED : AttackType.MAGIC;
        // Surprised targets are always hit - same rule as melee - so the hit
        // roll only runs on an aware defender.
        boolean shotSurprise = MobCombat.isSurpriseAttack(level, caster, victim, shotType, shotElement);
        // Hit-roll for ranged attacks. Adjacent shots suffer the point-blank
        // accuracy penalty; normal range uses the straight accuracy-vs-evasion
        // roll, same denominator as melee.
        if (!shotSurprise && caster != null && caster.position != null) {
            int cheb = LevelFactoryUtils.chebyshev(caster.position, target);
            int accuracyMod = (cheb == 1) ? GameBalance.POINT_BLANK_ACCURACY_MOD : 0;
            if (!MobCombat.rollRangedHit(caster, victim, accuracyMod)) {
                String cn = caster.name != null ? caster.name
                        : TextCatalog.get("eventlog.fallback.adventurer");
                String vn = MobSystem.nameForLog(level, victim);
                EventLog.add(caster.isPlayer
                        ? Messages.playerMiss(cn, vn)
                        : (victim.isPlayer
                           ? Messages.enemyMiss(cn, vn)
                           : Messages.mobMiss(cn, vn)));
                // Yellow "miss" floater + miss sfx via the standard MISS path.
                if (level.events != null) {
                    boolean missPhysical = caster.rangedDamageType
                            == com.bjsp123.rl2.model.Mob.RangedDamageType.PHYSICAL;
                    level.events.add(new com.bjsp123.rl2.event.GameEvent.DamageDealt(
                            victim, 0,
                            com.bjsp123.rl2.event.GameEvent.DamageMessage.MISS,
                            caster,
                            missPhysical ? DamageElement.PHYSICAL : DamageElement.MAGIC,
                            null));
                }
                return;
            }
        }
        int resist = shotPhysical
                ? MobSystem.rollRange(MobCombat.resistRange(victim))
                : MobSystem.rollRange(MobCombat.magicResistRange(victim));
        int afterResist = Math.max(0, damage - resist);
        String resistKey = shotPhysical ? "armor" : "magicResist";
        DamageBreakdown bk = new DamageBreakdown(shotElement, damage)
                .add(resistKey, Math.min(resist, damage));
        Mob speaker = caster != null ? caster : TurnSystem.findPlayer(level);
        afterResist = MobCombat.applySurpriseIfNeeded(level, speaker, victim,
                afterResist, shotType, shotElement);
        String casterName = speaker != null && speaker.name != null
                ? speaker.name
                : TextCatalog.get("eventlog.fallback.adventurer");
        String victimName = MobSystem.nameForLog(level, victim);
        double hpBefore = victim.hp;
        MobCombat.processAttack(level, speaker, victim, afterResist, shotType, shotElement, bk);
        int dealt = Math.max(0, (int) Math.round(hpBefore - victim.hp));
        EventLog.add(Messages.playerHit(casterName, victimName, dealt));
    }

    /** Pick a free floor tile in the 8-neighbourhood of {@code mob} that maximises
     *  Chebyshev distance to {@code threat}. Returns null if no neighbour is walkable. */
    private static Point findRetreatTile(Mob mob, Mob threat, Level level) {
        int sx = mob.position.tileX(), sy = mob.position.tileY();
        int tx = threat.position.tileX(), ty = threat.position.tileY();
        Point best = null;
        int bestDist = LevelFactoryUtils.chebyshev(mob.position, threat.position);
        for (int dy = -1; dy <= 1; dy++) {
            for (int dx = -1; dx <= 1; dx++) {
                if (dx == 0 && dy == 0) continue;
                int nx = sx + dx, ny = sy + dy;
                Point cand = new Point(nx, ny);
                if (MobQueries.blocksMovement(level, mob, cand)) continue;
                int d = Math.max(Math.abs(nx - tx), Math.abs(ny - ty));
                if (d > bestDist) { bestDist = d; best = cand; }
            }
        }
        return best;
    }

    private static void processMobAi(Mob mob, Level level) {
        // ASLEEP mobs are already gated in processAiTurn (the uniform wake check runs
        // before behaviour dispatch), so by here the mob is awake.
        Mob.Intent prevIntent = mob.intent;
        if (tryFleeStep(mob, level, false)) return;
        Mob attackTarget = nearestAttackTarget(mob, level);
        if (attackTarget != null) {
            mob.intent = Mob.Intent.PURSUING;
            mob.targetPosition = attackTarget.position;
            stepOrIdle(mob, level);
            return;
        }
        defaultAiTail(mob, level, prevIntent, false);
    }

    /** True iff this turn should continue an in-progress chase: the previous tick
     *  was {@link Mob.Intent#PURSUING} or {@link Mob.Intent#CHASING_LAST_KNOWN},
     *  and {@code mob.targetPosition} still holds an unreached tile. Used by every
     *  AI dispatcher's no-visible-target branch to keep heading toward where the
     *  enemy was last seen instead of immediately falling back to wander. */
    private static boolean isChaseCarryover(Mob.Intent prev, Mob mob) {
        if (prev != Mob.Intent.PURSUING && prev != Mob.Intent.CHASING_LAST_KNOWN) return false;
        if (mob.targetPosition == null || mob.position == null) return false;
        return mob.targetPosition.tileX() != mob.position.tileX()
            || mob.targetPosition.tileY() != mob.position.tileY();
    }

    /** Apply a behaviour's post-decision move. EXPLORE_HIDE mobs run the post-move-effects
     *  path ({@link #stepAndApplyPostMoveEffects}); every other behaviour uses
     *  {@link #stepOrIdle}. Selected per dispatcher via the {@code postMoveEffects} flag. */
    private static void stepForBehavior(Mob mob, Level level, boolean postMoveEffects) {
        if (postMoveEffects) stepAndApplyPostMoveEffects(mob, level);
        else stepOrIdle(mob, level);
    }

    /** Shared FLEE check for the simple dispatchers (every behaviour except EXPLORE_HIDE,
     *  which has its own hide-aware flee). Sets FLEEING toward the retreat tile and steps.
     *  Returns {@code true} when the turn was spent fleeing. */
    private static boolean tryFleeStep(Mob mob, Level level, boolean postMoveEffects) {
        Point fleeAway = fleeTargetFor(mob, level);
        if (fleeAway == null) return false;
        mob.intent = Mob.Intent.FLEEING;
        mob.targetPosition = fleeAway;
        stepForBehavior(mob, level, postMoveEffects);
        return true;
    }

    /** Shared fallback tail for every per-behaviour AI dispatcher once no flee/attack action
     *  applied: continue an in-progress chase toward the last-known tile (so the mob keeps
     *  heading where the enemy was last seen rather than immediately giving up), else follow
     *  a leader, else wander to a random floor tile. */
    private static void defaultAiTail(Mob mob, Level level, Mob.Intent prevIntent,
                                      boolean postMoveEffects) {
        if (isChaseCarryover(prevIntent, mob)) {
            mob.intent = Mob.Intent.CHASING_LAST_KNOWN;
            stepForBehavior(mob, level, postMoveEffects);
            return;
        }
        if (tryFollowLeader(mob, level)) {
            mob.intent = Mob.Intent.FOLLOWING_LEADER;
            return;
        }
        if (mob.targetPosition == null) {
            mob.targetPosition = MobSystem.randomFloorPoint(level);
        }
        mob.intent = Mob.Intent.WANDERING;
        stepForBehavior(mob, level, postMoveEffects);
    }

    /**
     * Mob this one should treat as its non-combat leader. Returns the mob's
     * {@link Mob#owner} if it has one and the owner is still on the level - covers
     * both tame mobs (owner = player) and kittens (owner = parent cat). Returns
     * null otherwise. Self-heals a stale owner reference (e.g. after the owner
     * died) by clearing it and stepping any FOLLOWING state back to AWAKE.
     */
    private static Mob leaderToFollow(Mob self, Level level) {
        if (self.owner == null) return null;
        Mob own = self.owner;
        if (level.mobs.contains(own)) return own;
        // Owner died or left the level - drop loyalty so the mob doesn't path toward
        // a corpse forever, and exit the FOLLOWING state so the regular AI takes over.
        self.owner = null;
        if (self.stateOfMind == StateOfMind.FOLLOWING) {
            self.stateOfMind = StateOfMind.AWAKE;
        }
        return null;
    }

    /**
     * Pick a tile adjacent to {@code leader} as the path destination - the leader's own
     * tile is impassable to the follower (non-hostile mobs can't path through the player
     * or any leader, see {@link Pathfinder#canEnter}). Picks the leader-adjacent tile
     * with the smallest Chebyshev distance to {@code self} so the follower naturally
     * trails on the side closest to its current position. Out-of-bounds neighbours are
     * filtered out; the pathfinder handles whether the picked tile is actually reachable.
     */
    private static Point leaderApproachTile(Mob self, Mob leader, Level level) {
        int sx = self.position.tileX(), sy = self.position.tileY();
        int lx = leader.position.tileX(), ly = leader.position.tileY();
        Point best = null;
        int bestD = Integer.MAX_VALUE;
        for (int dy = -1; dy <= 1; dy++) {
            for (int dx = -1; dx <= 1; dx++) {
                if (dx == 0 && dy == 0) continue;
                int nx = lx + dx, ny = ly + dy;
                if (nx < 0 || ny < 0 || nx >= level.width || ny >= level.height) continue;
                int d = Math.max(Math.abs(nx - sx), Math.abs(ny - sy));
                if (d < bestD) { bestD = d; best = new Point(nx, ny); }
            }
        }
        return best;
    }

    /**
     * Apply the "follow your leader" fallback to {@code mob} when no flee/attack target
     * applied. Returns {@code true} iff this method handled the turn (set up movement,
     * idled adjacent, or burned the move cost) so the AI can short-circuit and skip its
     * own random-wander branch. When already adjacent the mob stands still and pays a
     * regular move-cost tick so the world clock advances.
     */
    private static boolean tryFollowLeader(Mob mob, Level level) {
        Mob leader = leaderToFollow(mob, level);
        if (leader == null) return false;
        int sx = mob.position.tileX(), sy = mob.position.tileY();
        int lx = leader.position.tileX(), ly = leader.position.tileY();
        int cheb = Math.max(Math.abs(lx - sx), Math.abs(ly - sy));
        if (cheb <= 1) {
            // Already next to the leader - stay put, but advance the clock so we don't
            // re-enter the AI in a tight loop.
            mob.targetPosition = null;
            TurnSystem.applyMoveCost(mob, mob.effectiveStats().moveCost);
            return true;
        }
        Point dest = leaderApproachTile(mob, leader, level);
        if (dest == null) return false;
        // Step toward the leader's approach tile here so callers can treat a true
        // return as "fully handled, intent = FOLLOWING_LEADER" without needing to
        // fall through to their own wander/step path. Previously this returned
        // false after setting targetPosition, which left the caller to step but
        // record the intent as WANDERING - making the look screen show a pet that
        // was actually following its master as "Awake (Wandering)".
        mob.targetPosition = dest;
        stepOrIdle(mob, level);
        return true;
    }

    /** How many turns (= HIDING stacks) a just-hidden {@link Behavior#EXPLORE_HIDE} mob
     *  stays put. */
    private static final int HIDING_DURATION_TURNS = 5;

    /**
     * AI for {@link Behavior#EXPLORE_HIDE} mobs. The flee flavour of the mouse:
     * <ul>
     *   <li>Mob this one FLEEs within vision -> run to the nearest tile the feared mob can't
     *       see; once there, enter {@link StateOfMind#HIDING} for a few turns.</li>
     *   <li>Mob this one wants to ATTACK within vision -> target it (mouse that's learned to
     *       hate a mob via combat memory).</li>
     *   <li>Otherwise -> random wander.</li>
     * </ul>
     */
    private static void processExploreHideAi(Mob mob, Level level) {
        Mob.Intent prevIntent = mob.intent;
        // Resolve FLEE before ATTACK: the spec says fleeing wins when selecting a target.
        Mob fearedMob = nearestFleeTarget(mob, level);
        if (fearedMob != null) {
            Point hide = findHiddenTileFrom(level, mob, fearedMob);
            if (hide != null) {
                mob.targetPosition = hide;
                mob.stateOfMind = StateOfMind.SEEKING_HIDING;
            } else {
                // No tile is out of the threat's LOS (open room, arena floor, ...).
                // Fall back to a plain retreat - same straight-away-from-threat
                // pick that HUNTER mobs use, with a side-step fallback when the
                // direct line is blocked. Without this the mouse's targetPosition
                // would stay stale and "fleeing" would be cosmetic.
                Point retreat = fleeTargetFor(mob, level);
                if (retreat != null) mob.targetPosition = retreat;
                mob.stateOfMind = StateOfMind.AWAKE;
            }
            BuffSystem.removeBuff(mob, com.bjsp123.rl2.model.Buff.BuffType.HIDING);
            mob.intent = Mob.Intent.FLEEING;
            stepAndApplyPostMoveEffects(mob, level);
            return;
        }

        // No one to flee: if we were fleeing and are now in cover, hunker down a few
        // turns by applying the HIDING buff. The buff's natural duration drain in
        // BuffSystem.tickPerTurn handles the countdown; we just look for its presence.
        if (mob.stateOfMind == StateOfMind.SEEKING_HIDING) {
            mob.targetPosition = null;
            mob.stateOfMind = StateOfMind.HIDING;
            BuffSystem.apply(level, mob, com.bjsp123.rl2.model.Buff.BuffType.HIDING,
                    HIDING_DURATION_TURNS, mob);
            mob.intent = Mob.Intent.IDLE;
            TurnSystem.applyMoveCost(mob, mob.effectiveStats().moveCost);
            return;
        }
        if (mob.stateOfMind == StateOfMind.HIDING) {
            if (!BuffSystem.hasBuff(mob, com.bjsp123.rl2.model.Buff.BuffType.HIDING)) {
                mob.stateOfMind = StateOfMind.AWAKE;
                mob.targetPosition = null;
            }
            mob.intent = Mob.Intent.IDLE;
            TurnSystem.applyMoveCost(mob, mob.effectiveStats().moveCost);
            return;
        }

        Mob hated = nearestAttackTarget(mob, level);
        if (hated != null) {
            mob.intent = Mob.Intent.PURSUING;
            mob.targetPosition = hated.position;
            stepAndApplyPostMoveEffects(mob, level);
            return;
        }
        defaultAiTail(mob, level, prevIntent, true);
    }

    /**
     * AI for {@link Behavior#HUNTER} mobs. Same attitude-driven target selection as
     * {@link #processMobAi} (FLEE -> run away, else ATTACK -> chase, else wander) minus the
     * wake-on-sight gate - predators are active by default.
     */
    private static void processHunterAi(Mob mob, Level level) {
        Mob.Intent prevIntent = mob.intent;
        if (tryFleeStep(mob, level, false)) return;
        Mob target = nearestAttackTarget(mob, level);
        if (target != null) {
            mob.intent = Mob.Intent.PURSUING;
            mob.targetPosition = target.position;
            stepOrIdle(mob, level);
            return;
        }
        defaultAiTail(mob, level, prevIntent, false);
    }

    /**
     * True when any mob this one has an attitude toward (attack or flee) sits within
     * {@code radius} (Chebyshev) AND in line of sight. Used as the wake-up gate for
     * ASLEEP mobs - a sleeper can't sense a target through solid walls; waking to
     * unseen threats is the job of loud-event wakes ({@link #wakeMobsNear}).
     */
    private static boolean hasAttitudeTargetWithin(Mob mob, Level level, double radius) {
        int mx = mob.position.tileX(), my = mob.position.tileY();
        for (Mob m : level.mobs) {
            if (m == mob) continue;
            if (MobSystem.getAttitudeToMob(mob, m) == Attitude.NOTHING) continue;
            if (BuffSystem.hasBuff(m, com.bjsp123.rl2.model.Buff.BuffType.INVISIBLE)
                    || BuffSystem.hasBuff(m, com.bjsp123.rl2.model.Buff.BuffType.PHASE)) continue;
            int d = Math.max(Math.abs(m.position.tileX() - mx),
                             Math.abs(m.position.tileY() - my));
            if (d <= stealthScaledRadius(m, radius)
                    && LevelUtilities.getLineOfSight(level, mob, m.position)) {
                // RL-33: a STEALTHy target a SLEEPING observer can see may slip
                // notice this turn; awake mobs have already noticed and track
                // normally, so the dodge only gates the initial wake.
                if (mob.stateOfMind == StateOfMind.ASLEEP
                        && stealthDodgesNotice(level, mob, m)) continue;
                return true;
            }
        }
        return false;
    }

    /** RL-33: per-turn stealth dodge. Returns true when {@code observer} FAILS to
     *  notice a STEALTHy {@code target} it can see this turn. Notice chance =
     *  {@code 1 - 0.05*stealthLvl} (L0 always notices, L10 = 50%/turn). LoS-gated:
     *  stealth only helps against a foe that can actually see you. Non-stealthy
     *  targets never dodge. */
    private static boolean stealthDodgesNotice(Level level, Mob observer, Mob target) {
        if (target == null || target.perks == null) return false;
        int lvl = target.perks.getOrDefault(com.bjsp123.rl2.model.Perk.STEALTH, 0);
        if (lvl <= 0) return false;
        if (!LevelUtilities.getLineOfSight(level, observer, target.position)) return false;
        double noticeProb = Math.max(0.0, 1.0 - 0.05 * lvl); // L10 -> 0.50
        return MobSystem.RANDOM.nextDouble() >= noticeProb;            // true = failed to notice
    }

    /** Apply the STEALTH perk to {@code observer}'s detection {@code radius}.
     *  Returns {@code radius / (perkLvl + 1)} when {@code observer} carries
     *  STEALTH (so L1 halves, L2 thirds, L10 elevenths the radius); returns
     *  {@code radius} unchanged otherwise. Used by the four wake / vision
     *  call sites in this class. */
    private static double stealthScaledRadius(Mob observer, double radius) {
        if (observer == null || observer.perks == null) return radius;
        int lvl = observer.perks.getOrDefault(com.bjsp123.rl2.model.Perk.STEALTH, 0);
        if (lvl <= 0) return radius;
        return radius / (lvl + 1.0);
    }

    /** True iff any other mob within {@code radius} (Chebyshev) and in line of
     *  sight has ATTACK attitude toward {@code target}. Used as the wake gate for
     *  INANIMATE mobs (anthills) - their own attackTypes is empty, so the regular
     *  wake gate never fires; this checks "is something coming for me" instead.
     *  STEALTH perk applies the same halved-radius rule as the regular wake gate. */
    private static boolean hasIncomingAttackerWithin(Mob target, Level level, double radius) {
        int tx = target.position.tileX(), ty = target.position.tileY();
        for (Mob m : level.mobs) {
            if (m == target) continue;
            if (MobSystem.getAttitudeToMob(m, target) != Attitude.ATTACK) continue;
            if (BuffSystem.hasBuff(m, com.bjsp123.rl2.model.Buff.BuffType.INVISIBLE)
                    || BuffSystem.hasBuff(m, com.bjsp123.rl2.model.Buff.BuffType.PHASE)) continue;
            int d = Math.max(Math.abs(m.position.tileX() - tx),
                             Math.abs(m.position.tileY() - ty));
            if (d <= stealthScaledRadius(m, radius)
                    && LevelUtilities.getLineOfSight(level, target, m.position)) return true;
        }
        return false;
    }

    /** Nearest mob this one wants to ATTACK that is within vision radius (Chebyshev) and,
     *  when {@link Mob#targetRequiresSight} is true, was visible at the start of the turn. */
    static Mob nearestAttackTarget(Mob self, Level level) {
        int sx = self.position.tileX(), sy = self.position.tileY();
        Mob best = null;
        int bestD = Integer.MAX_VALUE;
        double baseVision = self.effectiveStats().visionRadius;

        boolean useLos = self.targetRequiresSight && self.visibleMobsAtTurnStart != null;
        if (useLos) {
            // Fast path: only examine mobs already known to be in LOS
            for (Mob m : self.visibleMobsAtTurnStart) {
                if (m == self || m.hp <= 0) continue;
                if (MobSystem.getAttitudeToMob(self, m) != Attitude.ATTACK) continue;
                int d = Math.max(Math.abs(m.position.tileX() - sx),
                                 Math.abs(m.position.tileY() - sy));
                if (d > stealthScaledRadius(m, baseVision)) continue;
                if (d < bestD) { bestD = d; best = m; }
            }
            return best;
        }

        // Fallback: Chebyshev-only scan (targetRequiresSight=false or no snapshot yet)
        for (Mob m : level.mobs) {
            if (m == self) continue;
            if (MobSystem.getAttitudeToMob(self, m) != Attitude.ATTACK) continue;
            int d = Math.max(Math.abs(m.position.tileX() - sx),
                             Math.abs(m.position.tileY() - sy));
            if (d > stealthScaledRadius(m, baseVision)) continue;
            if (d < bestD) { bestD = d; best = m; }
        }
        return best;
    }

    /** Nearest mob this one wants to FLEE within its vision radius (Chebyshev), or null. */
    private static Mob nearestFleeTarget(Mob self, Level level) {
        int sx = self.position.tileX(), sy = self.position.tileY();
        Mob best = null;
        int bestD = Integer.MAX_VALUE;
        for (Mob m : level.mobs) {
            if (m == self) continue;
            if (MobSystem.getAttitudeToMob(self, m) != Attitude.FLEE) continue;
            int d = Math.max(Math.abs(m.position.tileX() - sx),
                             Math.abs(m.position.tileY() - sy));
            if (d > self.effectiveStats().visionRadius) continue;
            if (d < bestD) { bestD = d; best = m; }
        }
        return best;
    }

    /**
     * If a mob {@code self} fears is in sight, return a floor tile it can path to that
     * moves it directly away - position reflected through self across the threat axis and
     * clamped to the level bounds and to walkable tiles. When the straight-line away path
     * is blocked, falls back to {@link #findRetreatTile} which picks any 8-neighbour tile
     * that increases distance from the threat - fleeing into a side-step beats freezing
     * up against a wall and reverting to wander. Null only if no escape direction
     * improves distance at all (genuinely cornered).
     */
    private static Point fleeTargetFor(Mob self, Level level) {
        Mob threat = nearestFleeTarget(self, level);
        if (threat == null) return null;
        int sx = self.position.tileX(), sy = self.position.tileY();
        int tx = threat.position.tileX(), ty = threat.position.tileY();
        int dx = sx - tx, dy = sy - ty;
        // Push ~6 tiles away from the threat; find a floor-like tile at the furthest valid
        // offset in that direction, then fall back to shorter offsets if the furthest isn't
        // a walkable cell.
        for (int step = 6; step >= 1; step--) {
            int nx = sx + Integer.signum(dx) * step;
            int ny = sy + Integer.signum(dy) * step;
            if (nx < 0 || ny < 0 || nx >= level.width || ny >= level.height) continue;
            if (!level.tiles[nx][ny].isFloorLike()) continue;
            return new Point(nx, ny);
        }
        // No straight-line escape works (wall / chasm in the away direction). Side-step
        // toward whichever neighbour maximises Chebyshev distance from the threat.
        return findRetreatTile(self, threat, level);
    }

    /**
     * Nearest floor-like tile that {@code threat} cannot currently see, measured from
     * {@code self}'s position. Uses the player's visibility grid as a proxy - "not visible"
     * is the closest thing we have to "out of the threat's line of sight" right now.
     */
    /** Closest floor tile (Manhattan from {@code self}) that {@code threat} cannot
     *  see. Used by {@link #processExploreHideAi} to pick a hiding spot when the
     *  mouse spots a cat. Returns {@code null} when every reachable tile is in
     *  the threat's LOS (e.g. a featureless arena), in which case the caller
     *  falls back to a straight retreat. */
    private static Point findHiddenTileFrom(Level level, Mob self, Mob threat) {
        if (threat == null) return null;
        int cx = self.position.tileX(), cy = self.position.tileY();
        int bestD = Integer.MAX_VALUE;
        Point best = null;
        for (int x = 0; x < level.width; x++) {
            for (int y = 0; y < level.height; y++) {
                if (!level.tiles[x][y].isFloorLike()) continue;
                Point candidate = new Point(x, y);
                // LOS from the *threat*, not the player's fog-of-war. The previous
                // implementation read level.visible, which only reflects what the
                // player can see - a tile in the cat's line of sight but outside
                // the player's FOV would falsely qualify as a hiding spot.
                if (LevelUtilities.getLineOfSight(level, threat, candidate)) continue;
                int d = Math.abs(x - cx) + Math.abs(y - cy);
                if (d < bestD) { bestD = d; best = candidate; }
            }
        }
        return best;
    }

    /**
     * AI-safe wrapper around {@link #stepTowardTarget}. Guarantees that the mob's clock
     * advances even in the degenerate cases where the real step function early-returns
     * without charging a tick (null target, already-at-target). Without this, an AI mob
     * that forgot to set a target would sit at {@code ticksTillMove == 0} forever - every
     * call to {@link TurnSystem#tick} would re-run its AI without making progress.
     */
    private static void stepOrIdle(Mob mob, Level level) {
        int before = mob.ticksTillMove;
        MobMovement.stepTowardTarget(mob, level);
        if (mob.ticksTillMove == before) {
            TurnSystem.applyMoveCost(mob, mob.effectiveStats().moveCost);
        }
    }

    /**
     * Step the mob one tile toward its target, then apply per-species post-move effects. For
     * the mouse (glyph {@code "m"}): a 10% roll when it lands on a mushroom tile - on success,
     * the mushroom is eaten (vegetation cleared) and a fresh mouse spawns at the tile it just
     * left (skipped if another mob already occupies that tile). Uses {@link #stepOrIdle} so
     * the clock advances even when no move actually happened.
     */
    private static void stepAndApplyPostMoveEffects(Mob mob, Level level) {
        Point before = mob.position;
        stepOrIdle(mob, level);
        Point after = mob.position;
        int px = after.tileX(), py = after.tileY();
        if (before.tileX() == px && before.tileY() == py) return;

        // Mushroom-eating + spawn-on-eat is flag-driven: any mob with a non-zero
        // mushroomEatSpawnChance walking onto a mushroom rolls the dice and (on success)
        // spawns a copy of mushroomEatSpawnType behind it. Currently used by the mouse;
        // a new species can opt in by setting the same two flags in MobFactory.
        if (mob.effectiveStats().mushroomEatSpawnChance > 0
                && inBounds(level, px, py)
                && level.vegetation[px][py] == Vegetation.MUSHROOMS
                && MobSystem.RANDOM.nextDouble() < mob.effectiveStats().mushroomEatSpawnChance) {
            level.vegetation[px][py] = null;
            EventLog.add(Messages.vegetationEaten(
                    mob.name != null ? mob.name : "?", "mushroom"));
            if (mob.mushroomEatSpawnType != null && MobQueries.mobAt(level, before) == null
                    && MobQueries.levelHasRoomForSpawn(level)) {
                Mob bud = MobFactory.spawn(mob.mushroomEatSpawnType, before);
                if (bud != null) {
                    level.mobs.add(bud);
                    MobHooks.onSpawn(level, bud);
                    if (level.events != null) {
                        level.events.add(new com.bjsp123.rl2.event.GameEvent.MobSpawned(bud, before));
                    }
                }
            }
        }
    }

    private static boolean inBounds(Level level, int x, int y) {
        return x >= 0 && y >= 0 && x < level.width && y < level.height;
    }
}
