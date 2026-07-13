package com.bjsp123.rl2.logic;

import com.bjsp123.rl2.logic.MobSystem.AttackType;
import com.bjsp123.rl2.logic.MobSystem.Attitude;
import com.bjsp123.rl2.logic.MobSystem.DamageCause;
import com.bjsp123.rl2.logic.MobSystem.DamageElement;
import com.bjsp123.rl2.model.Item;
import com.bjsp123.rl2.model.Item.ItemEffect;
import com.bjsp123.rl2.model.Level;
import com.bjsp123.rl2.model.Level.Surface;
import com.bjsp123.rl2.model.Mob;
import com.bjsp123.rl2.model.Mob.Behavior;
import com.bjsp123.rl2.model.Point;
import com.bjsp123.rl2.model.Tile;

import java.util.ArrayList;
import java.util.List;

/**
 * Thrown-item mechanics extracted from {@link MobSystem}: throw range and
 * trajectory clamping, the animation-gated {@link #throwItem} /
 * {@link #applyThrowImpact} lifecycle (fire-time-locked impact, deferred
 * world-state mutation), per-effect bomb resolution, BOMB_DODGER scaling,
 * and captured-mob release placement.
 */
public final class MobThrowing {

    private MobThrowing() {}

    /** Max EUCLIDEAN tile distance a mob can throw: the base range plus its
     *  Hurler perk bonus. Single source of truth shared by the targeting
     *  overlay (which tiles to highlight) and {@link #throwItem} (clamping the
     *  actual flight) - a throw reaches the same true distance in every
     *  direction, so diagonals aren't ~40% longer than straight throws. */
    public static int throwRangeFor(Mob thrower) {
        int range = GameBalance.DEFAULT_THROW_RANGE;
        if (thrower != null && thrower.perks != null) {
            range += thrower.perks.getOrDefault(com.bjsp123.rl2.model.Perk.HURLER, 0)
                    * GameBalance.HURLER_RANGE_PER_LEVEL;
        }
        return range;
    }

    /** Clamp {@code dst} to within Euclidean {@code range} of {@code origin},
     *  sliding it back along the aim line when it's too far. Returns {@code dst}
     *  unchanged when already in range (or inputs are degenerate). */
    private static Point clampToRange(Point origin, Point dst, int range) {
        if (origin == null || dst == null || range <= 0) return dst;
        int dx = dst.tileX() - origin.tileX();
        int dy = dst.tileY() - origin.tileY();
        double dist = Math.sqrt((double) dx * dx + (double) dy * dy);
        if (dist <= range) return dst;
        double k = range / dist;
        return new Point(origin.tileX() + (int) Math.round(dx * k),
                         origin.tileY() + (int) Math.round(dy * k));
    }

    /**
     * Player / AI throw entry point. Synchronously: validates eligibility,
     * removes the item from the thrower's inventory, clips the trajectory to
     * the first blocker (locking the impact tile at fire time), emits the
     * {@link com.bjsp123.rl2.event.GameEvent.ItemThrown} projectile event,
     * and charges the throw's action cost. The world-state mutation (damage,
     * knockback, ignition, item fate) is packaged as a deferred call to
     * {@link #applyThrowImpact} via {@link MobSystem#queuePendingImpact} - the
     * ANIMATION-GATED LIFECYCLE.
     *
     * <p><b>The core invariant for ALL ranged attacks:</b> the defender must
     * not get a chance to act between fire and impact. That is enforced NOT
     * by mutating synchronously, but by the pending-impact freeze: while the
     * queue is non-empty, no game tick runs on-screen (PlayScreen gates on
     * {@code Animator.hasPendingImpacts()}) and headless drains the queue
     * before the next mob brain ({@code MobAi.processAllAiTurns}). Combined
     * with the fire-time-locked impact tile, deferred resolution is
     * gameplay-equivalent to synchronous - while letting the on-screen
     * mutation land exactly when the visual arc does, so a killed mob dies
     * when the bomb arrives, not before.
     *
     * <p><b>Regression guard:</b> if the impact ever resolves WITHOUT the
     * freeze holding (e.g. a new caller emits {@code ItemThrown} without
     * queueing, or a tick path ignores the pending-impact gate), defenders
     * get free ticks to step out of the AoE and throws silently miss - the
     * historical warrior-bomb-dud bug. Queue and gate must move together.
     */
    public static void throwItem(Level level, Mob thrower, Item it, Point dst) {
        if (thrower == null || it == null || dst == null) return;
        // Single authoritative throw-eligibility gate: only THROWN weapons and
        // consumable throwables (bombs/potions/orbs/tools) can be thrown -
        // wielded gear and generic items can't, even if their data still
        // carries a throwEffect.
        if (!it.isThrowable()) return;
        // Teleport-suppressing arenas (mirror match, final boss): a thrown
        // teleport orb would scatter the rival players / boss out of the
        // sealed fight. Refuse the throw outright - orb kept, no turn spent -
        // so the player isn't punished for the attempt.
        if (level != null && level.suppressTeleport
                && it.throwEffect == com.bjsp123.rl2.model.Item.ItemEffect.TELEPORT) {
            if (thrower.isPlayer) EventLog.add(Messages.teleportSuppressed(it.name != null ? it.name : it.type));
            return;
        }
        if (it.inventoryCategory == Item.InventoryCategory.BOMB) {
            com.bjsp123.rl2.util.ActionTracker.bumpBomb(thrower);
            if (thrower.isPlayer) thrower.runStats.recordBombUse(it.type);
        } else {
            com.bjsp123.rl2.util.ActionTracker.bumpThrow(thrower);
        }
        // "Adventurer throws the fire bomb." Bombs are notable, so log EVERY bomb
        // throw visibly - including mob throws, which used to be LOW-priority and
        // hidden (RL-35). Other thrown items remain player-only in the log.
        EventLog.add(Messages.itemThrown(MobSystem.nameForLog(level, thrower),
                it.name != null ? it.name : it.type,
                thrower.isPlayer
                        || it.inventoryCategory == Item.InventoryCategory.BOMB));
        // Thrown items are always consumed from the thrower's bag. (The old
        // BOMB_DODGER "bomb survives the throw" regeneration is gone - RL-34
        // reworks the perk into catching ENEMY bombs instead; see applyThrowImpact.)
        MobSystem.removeFromInventory(thrower, it);
        // Range cap: a throw can't travel beyond the thrower's Chebyshev range
        // (base + Hurler). The player's targeting overlay already refuses to aim
        // past this, but clamp here too so AI throws - and any other caller -
        // obey the same limit rather than lobbing a bomb clear across the level.
        dst = clampToRange(thrower.position, dst, throwRangeFor(thrower));
        // Clip the trajectory at the first mob / wall / statue between
        // thrower and the user-picked tile. Bombs detonate against the
        // obstacle rather than ghosting through it.
        Point impact = MobSystem.firstMobBlocking(level, thrower.position, dst, thrower);
        if (level.events != null) {
            boolean trajectoryVisible =
                    MobSystem.trajectoryTouchesVisible(level, thrower.position, impact);
            level.events.add(new com.bjsp123.rl2.event.GameEvent.ItemThrown(
                    thrower, it, thrower.position, impact, trajectoryVisible));
        }
        int throwCost = thrower.effectiveStats().attackCost;
        if (thrower.perks != null) {
            int hurlerLvl = thrower.perks.getOrDefault(com.bjsp123.rl2.model.Perk.HURLER, 0);
            if (hurlerLvl > 0) {
                throwCost = (int) Math.max(1, Math.round(throwCost * Math.pow(0.85, hurlerLvl)));
            }
        }
        TurnSystem.applyActionCost(thrower, throwCost);
        // ANIMATION-GATED LIFECYCLE: defer the world-state mutation (damage,
        // knockback, ignition, surface paint, item-fate) to step 4 of the
        // lifecycle - which fires when the projectile arc visually lands.
        // On-screen the rgame Animator pops this Runnable in onItemThrown
        // and runs it at arc-completion; headless drains the queue between
        // mob brains via MobAi.processAllAiTurns. The pending-impact gate
        // (level.pendingImpactCount > 0) prevents any other mob from acting
        // before this resolve fires - that's how step 1 "ticking stops" is
        // enforced.
        final Item itFinal = it;
        final Point impactFinal = impact;
        final Mob throwerFinal = thrower;
        MobSystem.queuePendingImpact(level, () -> applyThrowImpact(level, throwerFinal, itFinal, impactFinal));
    }

    /** BOMB_DODGER damage multiplier - when {@code victim} carries the perk
     *  AND {@code thrown} is a BOMB, incoming bomb damage is scaled by
     *  {@code GameBalance.BOMB_DODGER_DAMAGE_BASE^perkLvl} (asymptotic -
     *  never zero). Returns 1.0 otherwise. */
    static double bombDamageScale(Mob victim, Item thrown) {
        if (victim == null || victim.perks == null || thrown == null) return 1.0;
        if (thrown.inventoryCategory != Item.InventoryCategory.BOMB) return 1.0;
        int lvl = victim.perks.getOrDefault(com.bjsp123.rl2.model.Perk.BOMB_DODGER, 0);
        if (lvl <= 0) return 1.0;
        return Math.pow(GameBalance.BOMB_DODGER_DAMAGE_BASE, lvl);
    }

    /** BOMB_DODGER buff / knockback gate - true means skip the buff or
     *  knockback application when the source is a BOMB and the victim has the
     *  perk at any level. Binary on/off (no per-level scaling). */
    static boolean bombBuffsIgnored(Mob victim, Item thrown) {
        if (victim == null || victim.perks == null || thrown == null) return false;
        if (thrown.inventoryCategory != Item.InventoryCategory.BOMB) return false;
        return victim.perks.getOrDefault(com.bjsp123.rl2.model.Perk.BOMB_DODGER, 0) >= 1;
    }

    /** Apply BOMB_DODGER's damage scaling to a rolled bomb-damage amount
     *  destined for {@code victim}. Returns {@code dmg} unchanged when no
     *  scaling applies; otherwise multiplies and rounds. Never returns
     *  negative; rounds away from zero so very low scaled damage still
     *  registers as 1 when the input was positive. */
    private static int scaledBombDamage(Mob victim, Item thrown, int dmg) {
        if (dmg <= 0) return dmg;
        double mult = bombDamageScale(victim, thrown);
        if (mult >= 0.9999) return dmg;
        int scaled = (int) Math.round(dmg * mult);
        return Math.max(scaled, 1);
    }

    /**
     * Apply the world-state mutations of a throw - door open, damage, bomb /
     * potion / cloud effects, tame-on-throw, knockback, and the
     * {@link Item.ThrowResult} fate (consume / return / drop). Step 4 of the
     * animation-gated lifecycle: queued by {@link #throwItem}, run by the
     * rgame Animator at arc completion (on-screen) or by the headless drain
     * between mob brains. The pending-impact freeze guarantees no mob acted
     * since the throw fired, so resolving here is gameplay-equivalent to
     * resolving at throw time - see the {@link #throwItem} javadoc.
     */
    public static void applyThrowImpact(Level level, Mob thrower, Item it, Point dst) {
        if (thrower == null || it == null || dst == null) return;

        int tx = dst.tileX(), ty = dst.tileY();
        ItemEffect te = it.throwEffect;
        boolean inBounds = tx >= 0 && ty >= 0 && tx < level.width && ty < level.height;

        // RL-34: BOMB_DODGER catch. An ENEMY bomb about to land within 3 tiles of
        // a player who has the perk has a (25 + 5*lvl)% chance to be snatched into
        // the player's bag instead of detonating.
        if (it.inventoryCategory == Item.InventoryCategory.BOMB && inBounds && thrower != null) {
            Mob player = TurnSystem.findPlayer(level);
            if (player != null && player != thrower && player.position != null
                    && player.inventory != null
                    && MobSystem.getAttitudeToMob(thrower, player) == Attitude.ATTACK) {
                int pLvl = player.perks == null ? 0
                        : player.perks.getOrDefault(com.bjsp123.rl2.model.Perk.BOMB_DODGER, 0);
                int dist = Math.max(Math.abs(tx - player.position.tileX()),
                                    Math.abs(ty - player.position.tileY()));
                if (pLvl > 0 && dist <= 3 && MobSystem.RANDOM.nextDouble() < 0.25 + 0.05 * pLvl) {
                    it.location = null;
                    if (InventorySystem.addToBag(player.inventory, it)) {
                        EventLog.add(Messages.bombCaught(MobSystem.nameForLog(level, player),
                                it.name != null ? it.name : it.type));
                        if (level.events != null) {
                            level.events.add(new com.bjsp123.rl2.event.GameEvent.ItemPickedUp(
                                    player, it, dst));
                        }
                        return; // caught -> no detonation
                    }
                }
            }
        }

        // Bomb detonation log - HIGH priority for any bomb that does damage
        // or applies a cloud. Non-damaging utility throws (CAPTURE / TAME /
        // RETURN-only items) skip this so the rolling log isn't spammed
        // with "the empty potion detonates" lines.
        if (it.inventoryCategory == Item.InventoryCategory.BOMB
                && te != ItemEffect.CAPTURE && te != ItemEffect.TELEPORT) {
            EventLog.add(Messages.bombDetonates(it.name != null ? it.name : it.type));
            // A blast is loud - always wake nearby sleepers within their wake radius.
            MobSystem.wakeMobsNear(level, dst, "a nearby explosion");
        }

        // A thrown item landing on a closed door pops it open IF the door
        // accepts projectile impacts as a cross (DoorBehavior.passRule ==
        // ANYONE and onCross == OPENS - i.e. today's wooden DOOR). Crystal
        // and one-time doors stay closed - bombs don't power-open
        // player-only barriers.
        if (inBounds) {
            Tile doorTile = level.tiles[tx][ty];
            com.bjsp123.rl2.model.DoorBehavior doorDb = doorTile.doorBehavior();
            if (doorDb != null && doorTile.isClosedDoor()
                    && doorDb.passRule() == com.bjsp123.rl2.model.DoorBehavior.PassRule.ANYONE
                    && doorDb.onCross() == com.bjsp123.rl2.model.DoorBehavior.OnCross.OPENS
                    && doorDb.openVariant() != null) {
                level.tiles[tx][ty] = doorDb.openVariant();
                if (level.events != null) level.events.add(
                        new com.bjsp123.rl2.event.GameEvent.DoorOpened(new com.bjsp123.rl2.model.Point(tx, ty)));
            }
        }

        if (te == ItemEffect.CAPTURE && inBounds) {
            if (it.capturedMob != null) {
                Point release = releasePointForCapturedMob(level, dst, thrower);
                if (release != null) {
                    Mob released = it.capturedMob;
                    it.capturedMob = null;
                    released.position = release;
                    if (!level.mobs.contains(released)) level.mobs.add(released);
                    if (level.events != null) {
                        level.events.add(new com.bjsp123.rl2.event.GameEvent.MobSpawned(released, release));
                    }
                    return; // full catcherball opens and disappears.
                }
            } else {
                Mob target = MobQueries.mobAt(level, dst);
                if (target != null && target != thrower && target.hp > 0) {
                    level.mobs.remove(target);
                    target.position = null;
                    it.capturedMob = target;
                    if (level.events != null) {
                        level.events.add(new com.bjsp123.rl2.event.GameEvent.BlastEffect(dst));
                    }
                }
            }
        }

        // Potion preflight: drink and throw apply the same per-mob effect (buff
        // + damage). Throwing splashes that effect onto every mob within
        // Chebyshev range 1 of the impact tile, then short-circuits the
        // standard DAMAGE / bomb branches so a thrown POTION_POISON applies
        // POISONED + damage in a 3x3 disc rather than just on the centre tile.
        if (it.useBehavior == com.bjsp123.rl2.model.Item.UseBehavior.DRINK) {
            if (inBounds) ItemSystem.applyPotionImpact(level, dst, it, thrower);
            return;
        }

        if (te == ItemEffect.DAMAGE && it.damage > 0) {
            Mob target = MobQueries.mobAt(level, dst);
            if (target != null && !MobSystem.isAlly(target, thrower)) {
                // Single-target throws (throwing knives, javelins) roll-to-hit using
                // the same accuracy-vs-evasion math as melee. AOE bombs and wands
                // never roll - they always land at the target tile. A surprise
                // throw skips the roll entirely - surprised targets are always
                // hit, matching melee. A miss emits a log line + yellow "miss"
                // floater via the standard DamageDealt(MISS) animator path; the
                // item still lands on the tile (handled outside this branch).
                boolean throwSurprise = MobCombat.isSurpriseAttack(level, thrower, target,
                        AttackType.THROWN, DamageElement.PHYSICAL);
                if (!throwSurprise && !MobCombat.rollRangedHit(thrower, target, 0)) {
                    String cn = thrower != null && thrower.name != null
                            ? thrower.name
                            : com.bjsp123.rl2.logic.TextCatalog.get("eventlog.fallback.adventurer");
                    String vn = MobSystem.nameForLog(level, target);
                    boolean attackerIsPlayer = thrower != null
                            && thrower.isPlayer;
                    boolean victimIsPlayer   = target.behavior  == Behavior.PLAYER;
                    EventLog.add(attackerIsPlayer
                            ? Messages.playerMiss(cn, vn)
                            : (victimIsPlayer
                               ? Messages.enemyMiss(cn, vn)
                               : Messages.mobMiss(cn, vn)));
                    if (level.events != null) {
                        level.events.add(new com.bjsp123.rl2.event.GameEvent.DamageDealt(
                                target, 0,
                                com.bjsp123.rl2.event.GameEvent.DamageMessage.MISS,
                                thrower, DamageElement.PHYSICAL,
                                new DamageCause(thrower, it, "throw")));
                    }
                } else {
                    // processAttack records combat memory and floating-text centrally when
                    // damage actually lands. Damage range comes from ItemSystem so the
                    // weapon's level increment lands on thrown impact too.
                    int dmg = MobSystem.rollRange(ItemStats.effectiveDamageRange(it));
                    dmg = MobCombat.applySurpriseIfNeeded(level, thrower, target, dmg,
                            AttackType.THROWN, DamageElement.PHYSICAL);
                    MobCombat.processAttack(level, thrower, target, dmg, AttackType.THROWN, DamageElement.PHYSICAL,
                            null, new DamageCause(thrower, it, "throw"));
                    BrandSystem.applyBrandOnHit(level, thrower, target, it);
                    // On-hit buffs for damage throwables (e.g. the ice dagger's CHILLED).
                    // The struck target gets each of the item's appliesBuff entries, with
                    // stacks = the item's effect duration (in turns).
                    if (it.appliesBuff != null && !it.appliesBuff.isEmpty() && target.hp > 0) {
                        int stacks = Math.max(1, ItemStats.effectiveDuration(it));
                        for (com.bjsp123.rl2.model.Buff.BuffType b : it.appliesBuff) {
                            BuffSystem.apply(level, target, b, stacks, thrower, it);
                        }
                    }
                }
            }
        }
        // Tame-on-throw - items list the mob types they tame; throwing one at a
        // matching mob converts it to a tame ally of the thrower. Done as a
        // separate branch (not gated on ThrownBehavior) so the same item can
        // carry an additional behaviour like NOTHING (drops on the ground)
        // without the two paths interfering. A successful tame consumes the
        // item - the mob "eats" the bait, so the food shouldn't also land on
        // the ground.
        boolean consumedByTame = false;
        if (!it.tameOnThrow.isEmpty() && inBounds) {
            Mob target = MobQueries.mobAt(level, dst);
            if (target != null && it.tameOnThrow.contains(target.mobType)) {
                target.owner = thrower;
                if (thrower != null) thrower.beastsTamed++;
                target.attackTypes.remove(thrower.mobType);
                target.fleeTypes.remove(thrower.mobType);
                if (level.events != null) {
                    level.events.add(new com.bjsp123.rl2.event.GameEvent.MobTamed(target));
                }
                EventLog.add(Messages.mobTamed(MobSystem.nameForLog(level, thrower), MobSystem.nameForLog(level, target)));
                consumedByTame = true;
            }
        }
        int lvl = ItemStats.effectiveLevel(it, thrower);
        // Bomb damage and AoE come from ItemSystem so every bomb-class throw shares
        // the same level-scaling formula with the rest of the item-stat math.
        int bombDamage = MobSystem.rollRange(ItemStats.effectiveDamageRange(it, lvl));
        int bombTiles  = ItemStats.effectiveSize(it, lvl);
        // Effect disc: literal effectSize tiles packed closest-first around
        // the impact point, filtered for projectile-LoS reachability so
        // walls block coverage but corners spill around freely.
        java.util.List<Point> disc = inBounds
                ? ItemSystem.packTilesAround(level, dst, bombTiles)
                : java.util.Collections.emptyList();
        if (te == ItemEffect.FIRE && inBounds) {
            // Fire bomb: bomb damage at impact, ignite every disc tile.
            Mob target = MobQueries.mobAt(level, dst);
            if (target != null) {
                MobCombat.processAttack(level, thrower, target,
                        scaledBombDamage(target, it, bombDamage),
                        AttackType.THROWN, DamageElement.PHYSICAL,
                        null, new DamageCause(thrower, it, "throw"));
                BrandSystem.applyBrandOnHit(level, thrower, target, it);
                if (!bombBuffsIgnored(target, it)) {
                    BuffSystem.apply(level, target, com.bjsp123.rl2.model.Buff.BuffType.ON_FIRE,
                            3 + lvl, thrower, it);
                }
            }
            for (Point p : disc) FireSystem.ignite(level, p.tileX(), p.tileY());
        } else if (te == ItemEffect.WATER && inBounds) {
            // Water bomb: WATER surface placed on every disc tile; mobs in
            // the disc get soaked and shoved back by one square per level.
            java.util.List<Mob> soaked = new ArrayList<>();
            for (Point p : disc) {
                SurfaceSystem.addSurface(level, p, Surface.WATER);
                Mob m = MobQueries.mobAt(level, p);
                if (m != null && m != thrower) {
                    if (!bombBuffsIgnored(m, it)) {
                        BuffSystem.apply(level, m, com.bjsp123.rl2.model.Buff.BuffType.WET,
                                MobSystem.WATER_STEP_BUFF_TURNS + lvl, thrower, it);
                    }
                    soaked.add(m);
                }
            }
            int kb = Math.max(0, lvl * it.knockbackSquares);
            if (kb > 0) {
                DamageCause kbCause = new DamageCause(thrower, it, "wall-slam");
                for (Mob m : soaked) {
                    if (m.hp <= 0) continue;
                    if (bombBuffsIgnored(m, it)) continue;
                    MobLifecycle.knockBack(level, m, kb, dst, 0, kbCause);
                }
            }
        } else if (te == ItemEffect.OIL && inBounds) {
            // Oil bomb: no damage, but lays down OIL on every disc tile
            // (twice so the surface is dense) and OILYs mobs on those tiles.
            for (Point p : disc) {
                for (int i = 0; i < 2; i++) SurfaceSystem.addSurface(level, p, Surface.OIL);
                Mob m = MobQueries.mobAt(level, p);
                if (m == null || bombBuffsIgnored(m, it)) continue;
                BuffSystem.apply(level, m, com.bjsp123.rl2.model.Buff.BuffType.OILY,
                        5 + lvl, thrower, it);
            }
        } else if (te == ItemEffect.BLAST && inBounds) {
            // Blast bomb: bomb damage to every mob in the blast disc, a
            // "blast" particle effect on every affected tile, plus a
            // 0..3-duration smoke cloud on each tile so the blast leaves
            // an irregular soot pattern.
            List<Mob> blastSurvivors = it.knockbackSquares > 0 ? new ArrayList<>() : null;
            for (Point p : disc) {
                int x = p.tileX(), y = p.tileY();
                if (level.events != null) {
                    level.events.add(new com.bjsp123.rl2.event.GameEvent.BlastEffect(p));
                }
                int smokeDur = MobSystem.RANDOM.nextInt(4);
                if (smokeDur > 0) {
                    CloudSystem.addCloud(level, x, y,
                            com.bjsp123.rl2.model.Level.Cloud.SMOKE, smokeDur);
                }
                // The blast levels any trees in the disc down to grass.
                if (level.vegetation != null
                        && level.vegetation[x][y] == com.bjsp123.rl2.model.Level.Vegetation.TREES) {
                    level.vegetation[x][y] = com.bjsp123.rl2.model.Level.Vegetation.GRASS;
                    VegetationSystem.emitVegetationChanged(level, x, y,
                            com.bjsp123.rl2.model.Level.Vegetation.GRASS);
                }
                Mob m = MobQueries.mobAt(level, p);
                if (m != null && m != thrower) {
                    MobCombat.processAttack(level, thrower, m,
                            scaledBombDamage(m, it, bombDamage),
                            AttackType.THROWN, DamageElement.PHYSICAL,
                            null, new DamageCause(thrower, it, "throw"));
                    BrandSystem.applyBrandOnHit(level, thrower, m, it);
                    if (blastSurvivors != null && m.hp > 0) blastSurvivors.add(m);
                }
            }
            if (blastSurvivors != null) {
                DamageCause kbCause = new DamageCause(thrower, it, "wall-slam");
                for (Mob m : blastSurvivors) {
                    if (bombBuffsIgnored(m, it)) continue;
                    MobLifecycle.knockBack(level, m, it.knockbackSquares, dst, 0, kbCause);
                }
            }
        } else if (te == ItemEffect.APPLYBUFFS && inBounds
                && it.appliesBuff != null && !it.appliesBuff.isEmpty()) {
            // APPLYBUFFS - every buff in the item's pipe-list is applied to
            // every mob in the disc. Stacks = the item's effect duration in turns.
            int buffStacks = ItemStats.effectiveDuration(it);
            for (Point p : disc) {
                Mob m = MobQueries.mobAt(level, p);
                if (m == null || m.hp <= 0) continue;
                if (bombBuffsIgnored(m, it)) continue;
                for (com.bjsp123.rl2.model.Buff.BuffType b : it.appliesBuff) {
                    BuffSystem.apply(level, m, b, buffStacks, thrower, it);
                }
            }
        } else if (te == ItemEffect.POISONCLOUD && inBounds) {
            // POISONCLOUD - drop a persistent poison cloud over the disc.
            // The cloud layer (see {@link CloudSystem}) re-applies POISONED
            // to mobs standing in it on each per-turn pass.
            int dur = ItemStats.effectiveBuffDuration(it);
            for (Point p : disc) {
                if (level.events != null) {
                    level.events.add(new com.bjsp123.rl2.event.GameEvent.BlastEffect(p));
                }
                if (dur > 0) {
                    CloudSystem.addCloud(level, p.tileX(), p.tileY(),
                            com.bjsp123.rl2.model.Level.Cloud.POISON, dur);
                }
            }
        } else if (te == ItemEffect.SMOKE && inBounds) {
            // SMOKE - drop an opaque cloud over the disc. Smoke blocks
            // sight and light but not projectiles.
            int dur = ItemStats.effectiveBuffDuration(it);
            for (Point p : disc) {
                if (level.events != null) {
                    level.events.add(new com.bjsp123.rl2.event.GameEvent.BlastEffect(p));
                }
                if (dur > 0) {
                    CloudSystem.addCloud(level, p.tileX(), p.tileY(),
                            com.bjsp123.rl2.model.Level.Cloud.SMOKE, dur);
                }
            }
        } else if (te == ItemEffect.FREEZE && inBounds) {
            // Freeze bomb: bomb damage to the target, CHILLED applied to
            // every mob in the disc, FIRE vegetation cleared on every disc
            // tile.
            Mob target = MobQueries.mobAt(level, dst);
            if (target != null) {
                MobCombat.processAttack(level, thrower, target,
                        scaledBombDamage(target, it, bombDamage),
                        AttackType.THROWN, DamageElement.COLD,
                        null, new DamageCause(thrower, it, "throw"));
                BrandSystem.applyBrandOnHit(level, thrower, target, it);
            }
            for (Point p : disc) {
                int x = p.tileX(), y = p.tileY();
                if (level.vegetation[x][y] == com.bjsp123.rl2.model.Level.Vegetation.FIRE) {
                    level.vegetation[x][y] = null;
                    if (level.fireRemaining     != null) level.fireRemaining[x][y]     = 0;
                    if (level.fireEmitCountdown != null) level.fireEmitCountdown[x][y] = 0;
                }
                Mob m = MobQueries.mobAt(level, p);
                if (m == null || bombBuffsIgnored(m, it)) continue;
                BuffSystem.apply(level, m, com.bjsp123.rl2.model.Buff.BuffType.CHILLED,
                        6 + lvl, thrower, it);
                if (m != thrower) {
                    m.ticksTillMove += TurnSystem.STANDARD_TURN_TICKS;
                }
            }
        } else if (te == ItemEffect.LIGHTNING && inBounds) {
            // Shock bomb: a SHOCK jolt to every mob in the disc. MAGIC attack
            // type so magic-resist applies; SHOCK element so processAttack's
            // wet/blood x2 kicks in. Each struck tile emits a lightning burst
            // so the renderer marks every hit. (Without this branch the bomb
            // resolved to a silent no-op - LIGHTNING was only handled on the
            // wand path, never the throw path.)
            for (Point p : disc) {
                Mob m = MobQueries.mobAt(level, p);
                if (m == null || m == thrower || m.hp <= 0) continue;
                MobCombat.processAttack(level, thrower, m,
                        scaledBombDamage(m, it, bombDamage),
                        AttackType.MAGIC, DamageElement.SHOCK,
                        null, new DamageCause(thrower, it, "lightning"));
                BrandSystem.applyBrandOnHit(level, thrower, m, it);
                if (level.events != null && m.position != null) {
                    level.events.add(new com.bjsp123.rl2.event.GameEvent.WandImpactBurst(
                            m.position, ItemEffect.LIGHTNING));
                }
            }
        } else if (te == ItemEffect.VOID && inBounds) {
            // Radius comes from the bomb's effectSize (like every other bomb),
            // not its enchant level - a level-0 void bomb was collapsing to a
            // single-tile hole that "did nothing but remove floor".
            ItemSystem.applyVoidImpact(level, dst, ItemSystem.radiusForTileCount(bombTiles));
        } else if (te == ItemEffect.TELEPORT && inBounds) {
            // Teleport orb: every non-thrower mob inside the disc is
            // scattered to a random walkable tile on a random level. The
            // thrower stays put.
            java.util.List<Mob> toScatter = new ArrayList<>();
            for (Point p : disc) {
                Mob m = MobQueries.mobAt(level, p);
                if (m == null || m == thrower || m.hp <= 0) continue;
                toScatter.add(m);
            }
            for (Mob m : toScatter) MobLifecycle.scatterMobAcrossWorld(level, m);
        }

        // The item's fate after impact is now driven entirely by the
        // {@link Item.ThrowResult} CSV column rather than category-specific
        // hard-coding:
        //   NOTHING - drop on the target tile (skipped over chasm so the
        //             item falls in instead of resting on air).
        //   CONSUME - the item ceases to exist (bombs, shatterers).
        //   RETURN  - the item bounces back to a free tile adjacent to
        //             the thrower so it can be picked up.
        com.bjsp123.rl2.model.Item.ThrowResult fate =
                consumedByTame
                        ? com.bjsp123.rl2.model.Item.ThrowResult.CONSUME
                        : (it.throwResult != null ? it.throwResult
                                : com.bjsp123.rl2.model.Item.ThrowResult.NOTHING);
        switch (fate) {
            case CONSUME -> { /* intentionally drop the item from the world */ }
            case RETURN -> {
                Point landing = MobHooks.freeAdjacentFloor(level, thrower.position);
                if (landing != null) {
                    it.location = landing;
                    level.items.add(it);
                }
                // No free adjacent tile -> item is lost (rare - only when
                // the thrower is fully boxed in by walls / mobs / chasms).
            }
            case NOTHING -> {
                if (inBounds && level.tiles[tx][ty] != Tile.CHASM) {
                    it.location = dst;
                    level.items.add(it);
                }
            }
        }
        // Move cost is charged in {@link #throwItem} immediately at throw
        // time, not here - the deferred-impact path keeps the player's
        // turn-cost model unchanged regardless of how long the visual
        // arc takes.

        // Step 4 of the animation-gated lifecycle is now complete: the world
        // state has fully mutated for this throw. Clear the pending-impact
        // gate so step 5 (ticking resumes) can begin.
        if (level.pendingImpactCount > 0) level.pendingImpactCount--;
    }

    private static Point releasePointForCapturedMob(Level level, Point preferred, Mob thrower) {
        if (canReleaseCapturedMobAt(level, preferred)) return preferred;
        Point nearImpact = MobHooks.freeAdjacentFloor(level, preferred);
        if (nearImpact != null) return nearImpact;
        return thrower == null || thrower.position == null
                ? null
                : MobHooks.freeAdjacentFloor(level, thrower.position);
    }

    private static boolean canReleaseCapturedMobAt(Level level, Point p) {
        if (level == null || p == null) return false;
        int x = p.tileX(), y = p.tileY();
        if (x < 0 || y < 0 || x >= level.width || y >= level.height) return false;
        if (level.tiles[x][y].blocksMovement()) return false;
        if (level.tiles[x][y] == Tile.CHASM) return false;
        return MobQueries.mobAt(level, p) == null;
    }
}
