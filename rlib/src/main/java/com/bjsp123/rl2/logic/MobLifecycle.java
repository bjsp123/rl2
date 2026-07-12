package com.bjsp123.rl2.logic;

import com.bjsp123.rl2.logic.MobSystem.AttackType;
import com.bjsp123.rl2.logic.MobSystem.Attitude;
import com.bjsp123.rl2.logic.MobSystem.DamageCause;
import com.bjsp123.rl2.logic.MobSystem.DamageElement;
import com.bjsp123.rl2.model.Item;
import com.bjsp123.rl2.model.Level;
import com.bjsp123.rl2.model.Level.Surface;
import com.bjsp123.rl2.model.Mob;
import com.bjsp123.rl2.model.Mob.Material;
import com.bjsp123.rl2.model.Point;
import com.bjsp123.rl2.model.Tile;

import java.util.ArrayList;
import java.util.List;

/**
 * Mob lifecycle events extracted from {@link MobSystem}: the {@link #killMob}
 * single point of truth for death (loot scatter, blood, XP, revive charms),
 * knockback resolution with wall-slam damage, ghostly-buff expiry, chasm falls
 * and cross-level mob/item transfer, and the final-boss / level-spawner
 * machinery.
 */
public final class MobLifecycle {

    private MobLifecycle() {}

    /**
     * Single point of truth for mob death. Handles everything that happens when a mob dies:
     * <ol>
     *   <li>Scatters bagged + equipped items onto nearby floor tiles.</li>
     *   <li>Splashes blood if the mob is flesh.</li>
     *   <li>Awards XP (and score) to {@code killer} - level-ups cascade automatically via
     *       {@link MobProgression#awardXp}. Pass {@code null} for environmental deaths where
     *       no mob should be credited.</li>
     *   <li>Removes the mob from the level.</li>
     * </ol>
     * Callers must not perform any of these steps themselves - they all live here.
     */
    public static void killMob(Level level, Mob mob, Mob killer) {
        // Jade Peach revive (difficulty levels): if the player is about to die
        // while carrying a revive charm, consume one and revive in place instead
        // of dying - full heal, clear lethal DoTs, and a shockwave that damages
        // every hostile on the level. killMob is the single death funnel, so this
        // covers every damage source. Skips all kill bookkeeping below.
        if (mob.isPlayer && level != null) {
            Item charm = findReviveCharm(mob);
            if (charm != null) {
                InventorySystem.removeOneFromBag(mob.inventory, charm);
                mob.statsDirty = true;
                int maxHp = (int) Math.round(mob.effectiveStats().maxHp);
                mob.hp = Math.max(1, (int) Math.round(maxHp * GameBalance.REVIVE_HP_RESTORE_FRAC));
                BuffSystem.removeBuff(mob, com.bjsp123.rl2.model.Buff.BuffType.ON_FIRE);
                BuffSystem.removeBuff(mob, com.bjsp123.rl2.model.Buff.BuffType.POISONED);
                BuffSystem.removeBuff(mob, com.bjsp123.rl2.model.Buff.BuffType.BLEEDING);
                rechargeAllItems(mob);
                reviveShockwave(level, mob);
                if (level.events != null && mob.position != null) {
                    level.events.add(new com.bjsp123.rl2.event.GameEvent.PlayerRevived(mob.position));
                }
                EventLog.add(Messages.playerRevived(MobSystem.nameForLog(level, mob)));
                return;
            }
        }
        // Drain inventory + equipment, roll drop-quality loot, scatter on adjacent
        // tiles, and post LootDropped events so rgame's Animator can play the
        // corpse-to-landing arc. Replaces the inline scatter that used to live here.
        LootSystem.dropLootOnDeath(level, mob);

        if (mob.material == Material.FLESH) {
            SurfaceSystem.addSurface(level, mob.position, Surface.BLOOD);
        }

        // Death-explosion hook. Mobs with fireExplosionRadiusOnDeath > 0 (e.g. blazing
        // firemouse) release a ball of fire centred on their corpse. Done BEFORE the
        // kill-hooks below so the radial ignite hits any per-mob hooks layered on top
        // and doesn't get confused by the corpse cleanup.
        if (mob.effectiveStats().fireExplosionRadiusOnDeath > 0 && mob.position != null && level != null) {
            int r = mob.effectiveStats().fireExplosionRadiusOnDeath;
            MobSystem.igniteDisc(level, mob.position.tileX(), mob.position.tileY(), r);
            if (level.events != null) {
                level.events.add(new com.bjsp123.rl2.event.GameEvent.ExplosionEffect(mob.position, r));
            }
        }

        // Per-event hooks dispatch on flag fields on the killer / victim. The kissyblob's
        // bud-on-eat behaviour lives entirely in MobHooks.onKill, driven by the
        // killer.eatSpawnChance / killer.eatSpawnType flags set in MobFactory.kissyblob.
        MobHooks.onKill(level, mob, killer);
        MobHooks.onDie (level, mob, killer);

        if (killer != null) {
            int reward = (int) Math.round(mob.effectiveStats().maxHp);
            //mobs currently give no XP at all, it's all earned through powerups.
            reward = 0;
            killer.score += reward;
            MobProgression.awardXp(level, killer, reward);
            // KILLER perk: every kill stacks the KILLER buff on the killer.
            // Each kill adds (2 + perkLvl) KILLER stacks (RL-43). BuffSystem.apply
            // has a stacking carve-out for KILLER that ADDS the incoming stacks onto
            // the existing count (capped at stackCap(KILLER) = 30); the speed effect
            // saturates at KILLER_EFFECT_CAP. Stacks also serve as the lifetime, so a
            // kill streak keeps the buff refreshed.
            if (killer.perks != null) {
                int perkLvl = killer.perks.getOrDefault(com.bjsp123.rl2.model.Perk.KILLER, 0);
                if (perkLvl > 0) {
                    BuffSystem.apply(level, killer,
                            com.bjsp123.rl2.model.Buff.BuffType.KILLER,
                            2 + perkLvl, killer);
                }
            }
            if (killer.history != null) {
                String victimName = mob.name != null ? mob.name : "?";
                killer.history.add(com.bjsp123.rl2.model.HistoricalRecord.kill(
                        level.currentTurn, level.depth, victimName));
            }
            // Final-boss roster (RL-19): the player records every individual it
            // kills so the boss floor can reanimate them as revenants. Exclude
            // players/clones, the boss itself, and already-reanimated revenants
            // so the fight can't feed itself.
            if (killer.isPlayer && mob.mobType != null && !mob.isPlayer && !mob.isClone
                    && !"GREAT_WRAITH".equals(mob.mobType)
                    && !BuffSystem.hasBuff(mob, com.bjsp123.rl2.model.Buff.BuffType.REVENANT)) {
                killer.killedRoster.add(mob.mobType);
            }
            // Run-stats kill tally: every enemy the player kills (boss + revenants
            // included), for the victory screen + score.
            if (killer.isPlayer && !mob.isPlayer && !mob.isClone) {
                killer.runStats.mobsKilled++;
            }
        }

        // Final-boss defeat (RL-19): killing the Great Wraith opens the descent
        // stairs at the arena centre (down to the exit-portal floor) and ends
        // revenant support, regardless of any adds still alive. The down-stairs
        // target was wired to the exit floor in WorldTopology.appendSpecialLevels.
        if (level.kind == Level.LevelKind.FINAL_BOSS && "GREAT_WRAITH".equals(mob.mobType)) {
            level.bossDefeated = true;
            level.spawner = null;
            if (killer != null && killer.isPlayer) killer.killedGreatWraith = true;
            if (level.lockedExit != null) {
                int ex = level.lockedExit.tileX(), ey = level.lockedExit.tileY();
                if (ex >= 0 && ey >= 0 && ex < level.width && ey < level.height) {
                    level.tiles[ex][ey] = Tile.STAIRS_DOWN;
                    level.stairsDown = new Point(ex, ey);
                }
            }
        }

        // Synchronously remove the mob from level.mobs. The visual flicker/fade plays
        // out against a snapshot held by rgame's Animator (the MobKilled event carries
        // position + visibility, and the Animator records a "ghost" entry from those).
        // Game logic (mobAt, pathfinding, targeting, attitude) sees a clean world
        // immediately because the mob is no longer in the list.
        boolean visible = MobSystem.isVisibleToPlayer(level, mob)
                || (killer != null && MobSystem.isVisibleToPlayer(level, killer));
        if (level.events != null && mob.position != null) {
            level.events.add(new com.bjsp123.rl2.event.GameEvent.MobKilled(
                    mob, killer, mob.position.tileX(), mob.position.tileY(), visible));
        }
        level.mobs.remove(mob);
    }

    /** Refill every charge-bearing item the mob carries (bag + equipped) to its
     *  max - the Jade Peach's revive tops up wands, blink tools, jade tools, etc. */
    private static void rechargeAllItems(Mob mob) {
        if (mob == null || mob.inventory == null) return;
        if (mob.inventory.bag != null) {
            for (Item it : mob.inventory.bag) {
                if (it != null && it.baseChargeMax > 0) it.charge = it.maxCharge();
            }
        }
        for (Item it : mob.inventory.allEquipped()) {
            if (it != null && it.baseChargeMax > 0) it.charge = it.maxCharge();
        }
    }

    /** First carried Jade Peach (revive charm) in the bag, or null. Classified by
     *  the {@link Item#revivesOnDeath} flag, never by item type. */
    private static Item findReviveCharm(Mob mob) {
        if (mob == null || mob.inventory == null || mob.inventory.bag == null) return null;
        for (Item it : mob.inventory.bag) {
            if (it != null && it.revivesOnDeath && it.count > 0) return it;
        }
        return null;
    }

    /** Jade Peach revive shockwave: deal {@code REVIVE_AOE_MAXHP_FRAC} of each
     *  hostile's max HP to every enemy on the level. Snapshots the target list
     *  first because killing weak enemies mutates {@code level.mobs}. */
    private static void reviveShockwave(Level level, Mob player) {
        if (level == null || level.mobs == null) return;
        java.util.List<Mob> hostiles = new java.util.ArrayList<>();
        for (Mob m : level.mobs) {
            if (m == null || m == player || m.hp <= 0 || m.position == null) continue;
            if (MobSystem.getAttitudeToMob(player, m) != Attitude.ATTACK) continue;
            hostiles.add(m);
        }
        for (Mob m : hostiles) {
            if (m.hp <= 0) continue;
            int dmg = (int) Math.round(m.effectiveStats().maxHp * GameBalance.REVIVE_AOE_MAXHP_FRAC);
            if (dmg <= 0) continue;
            m.hp -= dmg;
            if (m.hp <= 0) killMob(level, m, player);
        }
    }

    /**
     * Knock {@code mob} away from {@code from} by up to {@code numSquares} tiles.
     *
     * <p>Outcomes per tile stepped:
     * <ul>
     *   <li>Free floor - mob moves, continues.</li>
     *   <li>CHASM (non-flying mob) - mob dies; its items emit {@code ItemFallingIntoChasm}
     *       events instead of normal loot scatter.</li>
     *   <li>Wall / blocking terrain - mob takes {@code remaining * 4} impact damage, stops.</li>
     *   <li>Another mob - both take {@code remaining * 4} damage; the collided mob is
     *       knocked back by {@code remaining} squares (cascade capped at depth 3).</li>
     * </ul>
     */
    public static void knockBack(Level level, Mob mob, int numSquares, Point from) {
        knockBackInternal(level, mob, numSquares, from, 0, 0, null);
    }

    /** Knockback overload with a wall-slam damage bonus. The bonus is added to
     *  the impact damage when the slide is short-circuited by a wall / chasm /
     *  mob - i.e. the target is "pinned". Used by KNOCKBACK perk levels 6-10
     *  (each level beyond 5 contributes +1 to {@code wallSlamBonus}). Zero is
     *  the historical default; callers that don't pass it via the simple
     *  overload behave identically to before. */
    public static void knockBack(Level level, Mob mob, int numSquares, Point from,
                                 int wallSlamBonus) {
        knockBackInternal(level, mob, numSquares, from, 0, wallSlamBonus, null);
    }

    /** Full knockback variant carrying a {@link DamageCause} for attribution
     *  on the wall-slam / collision damage event. The cause's medium is
     *  overridden to {@code "wall-slam"} or {@code "fall"} inside; callers
     *  pass the originating attacker + item (e.g. the bomb thrower + bomb)
     *  so the death-screen + log messages can name the root cause. */
    public static void knockBack(Level level, Mob mob, int numSquares, Point from,
                                 int wallSlamBonus, DamageCause cause) {
        knockBackInternal(level, mob, numSquares, from, 0, wallSlamBonus, cause);
    }

    private static void knockBackInternal(Level level, Mob mob, int numSquares,
                                          Point from, int depth, int wallSlamBonus,
                                          DamageCause cause) {
        DamageCause slamCause = cause != null
                ? new DamageCause(cause.origin(), cause.originItem(), "wall-slam")
                : null;
        if (depth >= 3 || mob == null || mob.position == null) return;
        int dx = Integer.signum(mob.position.tileX() - from.tileX());
        int dy = Integer.signum(mob.position.tileY() - from.tileY());
        if (dx == 0 && dy == 0) return;

        Point start = mob.position;
        int cx = start.tileX(), cy = start.tileY();

        for (int i = 0; i < numSquares; i++) {
            int remaining = numSquares - i;
            int nx = cx + dx, ny = cy + dy;

            if (nx < 0 || ny < 0 || nx >= level.width || ny >= level.height) {
                mob.position = new Point(cx, cy);
                emitKnockBack(level, mob, start, true);
                MobCombat.processAttack(level, null, mob, remaining * 4 + wallSlamBonus,
                        AttackType.ENVIRONMENTAL, DamageElement.PHYSICAL, null, slamCause);
                return;
            }

            Tile tile = level.tiles[nx][ny];

            if (tile == Tile.CHASM && !mob.effectiveStats().flying) {
                mob.position = new Point(nx, ny);
                emitKnockBack(level, mob, start, false);
                fallToNextLevel(level, mob);
                return;
            }

            if (tile.blocksMovement()) {
                mob.position = new Point(cx, cy);
                emitKnockBack(level, mob, start, true);
                int slamDmg = remaining * 4 + wallSlamBonus;
                MobCombat.processAttack(level, null, mob, slamDmg,
                        AttackType.ENVIRONMENTAL, DamageElement.PHYSICAL, null, slamCause);
                if (mob.isPlayer || MobSystem.isVisibleToPlayer(level, mob)) {
                    // No {@code intoName} - picks the "wall" variant.
                    // Origin only shows on attributable (non-melee) chains
                    // since the melee chain's preceding hit line already
                    // attributes the push.
                    EventLog.add(Messages.knockbackSlam(MobSystem.nameForLog(level, mob), slamDmg,
                            Messages.formatCauseOrigin(level, slamCause), null, 0,
                            mob.isPlayer));
                }
                return;
            }

            Mob collided = MobQueries.mobAt(level, new Point(nx, ny));
            if (collided != null) {
                mob.position = new Point(cx, cy);
                emitKnockBack(level, mob, start, true);
                int slamDmg = remaining * 4 + wallSlamBonus;
                MobCombat.processAttack(level, null, mob, slamDmg,
                        AttackType.ENVIRONMENTAL, DamageElement.PHYSICAL, null, slamCause);
                if (mob.isPlayer || MobSystem.isVisibleToPlayer(level, mob)) {
                    // Cascade kb = the {@code remaining} push that the
                    // collided mob will inherit. Reads as "...knocking the
                    // rat back 2" in the slam log.
                    int cascadeKb = (collided.hp > 0) ? remaining : 0;
                    EventLog.add(Messages.knockbackSlam(MobSystem.nameForLog(level, mob), slamDmg,
                            null, MobSystem.nameForLog(level, collided), cascadeKb,
                            mob.isPlayer));
                }
                if (collided.hp > 0) {
                    MobCombat.processAttack(level, null, collided, remaining * 4,
                            AttackType.ENVIRONMENTAL, DamageElement.PHYSICAL, null, slamCause);
                    if (collided.hp > 0) {
                        knockBackInternal(level, collided, remaining, mob.position, depth + 1, 0, cause);
                    }
                }
                return;
            }

            cx = nx;
            cy = ny;
        }

        mob.position = new Point(cx, cy);
        emitKnockBack(level, mob, start, false);
    }

    private static void emitKnockBack(Level level, Mob mob, Point start, boolean blocked) {
        if (level == null || level.events == null || mob == null) return;
        if (!mob.position.equals(start)) {
            level.events.add(new com.bjsp123.rl2.event.GameEvent.MobKnockedBack(
                    mob, start, mob.position, blocked));
        }
    }

    /** Resolve a non-flying mob falling into a chasm. If the level has a
     *  down-stairs link AND the mob's half-max-HP impact damage doesn't
     *  kill it, the mob is moved to the next dungeon level (the
     *  staircase target) - losing half of its max HP from the fall. If
     *  the fall would kill (or there's no next level / no arrival tile),
     *  the mob's items revolve-shrink-fade into the chasm and the mob
     *  dies on impact at the source level. The visual revolve-fade of
     *  the mob itself is emitted as a {@code MobFellThroughChasm}
     *  event so the renderer plays the same spinning-fade as a falling
     *  item. The PLAYER falling carries through to
     *  {@link com.bjsp123.rl2.model.World#currentLevelIndex}. */
    /**
     * GHOSTLY just ended: a mob that was drifting through the world may now be
     * somewhere solid. Over a chasm (and not otherwise flying) it falls; inside
     * a wall / statue / closed door / another mob it is repositioned to the
     * nearest tile it can legally occupy. Called by BuffSystem when the buff
     * expires, AFTER the buff has been removed from {@code mob.buffs}.
     */
    public static void resolveGhostlyEnd(Level level, Mob mob) {
        if (level == null || mob == null || mob.position == null || mob.hp <= 0) return;
        mob.statsDirty = true;   // drop ghostly's flying/evasion contribution first
        int x = mob.position.tileX(), y = mob.position.tileY();
        if (x < 0 || y < 0 || x >= level.width || y >= level.height) return;
        if (level.tiles[x][y] == Tile.CHASM && !mob.effectiveStats().flying) {
            fallToNextLevel(level, mob);
            return;
        }
        boolean tileBlocked = com.bjsp123.rl2.model.TileQuery
                .blocksMovementAt(level, x, y, mob);
        boolean occupied = false;
        for (Mob m : level.mobs) {
            if (m == mob || m == null || m.hp <= 0 || m.position == null) continue;
            if (m.position.tileX() == x && m.position.tileY() == y) { occupied = true; break; }
        }
        if (!tileBlocked && !occupied) return;   // re-solidified somewhere legal
        Point dest = nearestFreeTile(level, mob, x, y);
        if (dest == null) return;                // pathological: no free tile anywhere
        Point from = mob.position;
        mob.position = dest;
        mob.targetPosition = null;
        if (level.events != null) {
            level.events.add(new com.bjsp123.rl2.event.GameEvent.MobTeleported(
                    mob, from.tileX(), from.tileY(), dest.tileX(), dest.tileY()));
        }
    }

    /** Nearest tile (expanding Chebyshev rings) that {@code mob} can legally
     *  occupy: in bounds, doesn't block its movement, and holds no live mob.
     *  Returns {@code null} when the whole level is somehow full. */
    private static Point nearestFreeTile(Level level, Mob mob, int cx, int cy) {
        int maxR = Math.max(level.width, level.height);
        for (int r = 1; r <= maxR; r++) {
            for (int dy = -r; dy <= r; dy++) {
                for (int dx = -r; dx <= r; dx++) {
                    if (Math.max(Math.abs(dx), Math.abs(dy)) != r) continue;  // ring only
                    int nx = cx + dx, ny = cy + dy;
                    if (nx < 0 || ny < 0 || nx >= level.width || ny >= level.height) continue;
                    Point cand = new Point(nx, ny);
                    if (MobQueries.blocksMovement(level, mob, cand)) continue;
                    return cand;
                }
            }
        }
        return null;
    }

    public static void fallToNextLevel(Level level, Mob mob) {
        if (level == null || mob == null) return;
        com.bjsp123.rl2.model.World world = level.world;
        Level next = null;
        Point arrival = null;
        if (world != null && world.levels != null) {
            int target = level.stairsDownTarget;
            if (target >= 0 && target < world.levels.length) next = world.levels[target];
            // No down-stairs target (deepest level / topology hole) - loop to depth 1 so a
            // fall with nowhere lower wraps to the top instead of annihilating.
            if (next == null || next == level) {
                Level depth1 = findDepth1Level(world);
                if (depth1 != null && depth1 != level) next = depth1;
            }
            // Reappear at a RANDOM floor tile on the level below.
            if (next != null) arrival = MobSystem.randomFreeFloor(next);
        }

        Point fromPos = mob.position;
        int dmg = Math.max(1, (int) Math.round(mob.effectiveStats().maxHp * 0.5));
        boolean canRelocate = next != null && arrival != null;

        // Revolve-shrink-fade visual at the source tile + log the plunge when seen.
        if (level.events != null) {
            level.events.add(new com.bjsp123.rl2.event.GameEvent.MobFellThroughChasm(
                    mob, fromPos));
        }
        if (mob.isPlayer || MobSystem.isVisibleToPlayer(level, mob)) {
            EventLog.add(Messages.mobFellInChasm(MobSystem.nameForLog(level, mob),
                    mob.isPlayer));
        }

        if (!canRelocate) {
            // Nowhere below to land (single-level / unlinked world) - destroyed by the void.
            emitFallingItems(level, mob);
            MobCombat.processAttack(level, null, mob, dmg,
                    AttackType.ENVIRONMENTAL, DamageElement.PHYSICAL);
            return;
        }

        // Reappear on the level below at the random floor tile, then take the fall damage
        // there. The PLAYER is capped to survive at 1 HP so a void-knockback never silently
        // strips their inventory; NPCs take the full hit and may die on arrival (their loot
        // then drops on the level below via the normal death path).
        int applied = mob.isPlayer
                ? Math.max(0, (int) Math.floor(mob.hp - 1))
                : dmg;
        transferMobToLevel(level, mob, next, arrival);
        MobCombat.processAttack(next, null, mob, applied,
                AttackType.ENVIRONMENTAL, DamageElement.PHYSICAL);
    }

    /** Relocate an item lying on a freshly-chasmed tile to the level it
     *  would fall to (stairs-down target, or the depth-1 level as a
     *  fallback per the world spec). Emits the existing item-fall visual
     *  at the source tile and re-anchors the item at the destination's
     *  spawn point. If no fall destination exists at all, the item is
     *  consumed by the chasm (existing visual, removed from the world). */
    public static void fallItemThroughChasm(Level srcLevel, com.bjsp123.rl2.model.Item it) {
        if (srcLevel == null || it == null || it.location == null) return;
        Point fromPos = it.location;
        if (srcLevel.events != null) {
            srcLevel.events.add(new com.bjsp123.rl2.event.GameEvent.ItemFallingIntoChasm(
                    it, fromPos));
        }
        // Log when the source tile is in the player's FOV - items vanishing
        // off-screen don't need spam, but anything the player saw on the
        // floor warrants a "the X falls into the chasm." line.
        if (MobSystem.tileVisibleToPlayer(srcLevel, fromPos)) {
            EventLog.add(Messages.itemFellInChasm(it.name, true));
        }
        srcLevel.items.remove(it);

        Level dst = findFallDestination(srcLevel);
        if (dst == null || dst == srcLevel) {
            // No destination - item is destroyed by the fall.
            it.location = null;
            return;
        }
        // Items reappear at a random floor tile on the level below.
        Point arrival = MobSystem.randomFreeFloor(dst);
        if (arrival == null) {
            // Destination exists but no walkable landing - destroy the item.
            it.location = null;
            return;
        }
        it.location = arrival;
        dst.items.add(it);
    }

    /** Apply chasm-fall consequences to everything currently on tile
     *  ({@code x}, {@code y}) of {@code level}. Non-flying mobs fall via
     *  {@link #fallToNextLevel}; items relocate via
     *  {@link #fallItemThroughChasm}. Flying mobs are unaffected. Snapshot
     *  the lists before iterating because the fall routines mutate both. */
    public static void applyChasmFallToTile(Level level, int x, int y) {
        if (level == null || level.mobs == null) return;
        java.util.List<Mob> mobSnap = new java.util.ArrayList<>(level.mobs);
        for (Mob m : mobSnap) {
            if (m == null || m.position == null || m.hp <= 0) continue;
            if (m.position.tileX() != x || m.position.tileY() != y) continue;
            if (m.effectiveStats().flying) continue;
            fallToNextLevel(level, m);
        }
        if (level.items != null) {
            java.util.List<com.bjsp123.rl2.model.Item> itemSnap =
                    new java.util.ArrayList<>(level.items);
            for (com.bjsp123.rl2.model.Item it : itemSnap) {
                if (it == null || it.location == null) continue;
                if (it.location.tileX() != x || it.location.tileY() != y) continue;
                fallItemThroughChasm(level, it);
            }
        }
    }

    /** Pick the level that things falling out of {@code srcLevel} should
     *  land on. First choice is the {@code stairsDownTarget} (one depth
     *  below); if that's absent (deepest level, or topology hole), fall
     *  back to depth 1 - per the design, a fall with nowhere lower loops
     *  to the top of the dungeon. Returns {@code null} when even that's
     *  not available (single-level world / un-linked world). */
    private static Level findFallDestination(Level srcLevel) {
        if (srcLevel == null || srcLevel.world == null) return null;
        com.bjsp123.rl2.model.World world = srcLevel.world;
        if (world.levels == null) return null;
        int target = srcLevel.stairsDownTarget;
        if (target >= 0 && target < world.levels.length) {
            Level next = world.levels[target];
            if (next != null) return next;
        }
        Level depth1 = findDepth1Level(world);
        return depth1 == srcLevel ? null : depth1;
    }

    /** Locate the depth-1 level in {@code world}, or null if not present. */
    private static Level findDepth1Level(com.bjsp123.rl2.model.World world) {
        if (world == null || world.levels == null) return null;
        for (Level l : world.levels) {
            if (l != null && l.depth == 1) return l;
        }
        return null;
    }

    /** Find a walkable, unoccupied tile on {@code lvl} near {@code preferred}.
     *  Falls back to a small spiral search if the preferred tile is blocked,
     *  then to ANY walkable tile on the level. Used as the landing spot for
     *  things falling out of nowhere (depth-1 fallback). */
    static Point freeFloorNear(Level lvl, Point preferred) {
        if (lvl == null || lvl.tiles == null) return null;
        if (preferred != null) {
            int px = preferred.tileX(), py = preferred.tileY();
            if (isFreeFloor(lvl, px, py)) return preferred;
            // Spiral out up to radius 6.
            for (int r = 1; r <= 6; r++) {
                for (int dy = -r; dy <= r; dy++) {
                    for (int dx = -r; dx <= r; dx++) {
                        if (Math.max(Math.abs(dx), Math.abs(dy)) != r) continue;
                        if (isFreeFloor(lvl, px + dx, py + dy)) {
                            return new Point(px + dx, py + dy);
                        }
                    }
                }
            }
        }
        // Last-ditch scan.
        for (int y = 0; y < lvl.height; y++) {
            for (int x = 0; x < lvl.width; x++) {
                if (isFreeFloor(lvl, x, y)) return new Point(x, y);
            }
        }
        return null;
    }

    static boolean isFreeFloor(Level lvl, int x, int y) {
        if (x < 0 || y < 0 || x >= lvl.width || y >= lvl.height) return false;
        com.bjsp123.rl2.model.Tile t = lvl.tiles[x][y];
        if (t == null || !t.isFloorLike()) return false;
        for (Mob m : lvl.mobs) {
            if (m != null && m.position != null
                    && m.position.tileX() == x && m.position.tileY() == y) return false;
        }
        return true;
    }

    /** Index of {@code lvl} in {@code world.levels}, or -1 if not found. */
    private static int indexOf(com.bjsp123.rl2.model.World world, Level lvl) {
        if (world == null || world.levels == null) return -1;
        for (int i = 0; i < world.levels.length; i++) {
            if (world.levels[i] == lvl) return i;
        }
        return -1;
    }

    /** Move {@code mob} from {@code srcLevel} to {@code dstLevel} at
     *  {@code arrivalPos}. For PLAYER and SMART mobs, the world's
     *  {@code currentLevelIndex} follows the mob so {@code World.currentLevel()}
     *  stays consistent with where the agent actually lives. Same-level moves
     *  (chasm/teleport landing on the source level) just update position. */
    public static void transferMobToLevel(Level srcLevel, Mob mob,
                                          Level dstLevel, Point arrivalPos) {
        if (srcLevel == null || mob == null || dstLevel == null || arrivalPos == null) return;
        if (dstLevel != srcLevel) {
            srcLevel.mobs.remove(mob);
            dstLevel.mobs.add(mob);
        }
        mob.position = arrivalPos;
        mob.targetPosition = null;
        if (mob.isPlayer) {
            com.bjsp123.rl2.model.World world = srcLevel.world;
            if (world != null) {
                int idx = indexOf(world, dstLevel);
                if (idx >= 0) {
                    world.currentLevelIndex = idx;
                    dstLevel.visited = true;
                }
            }
        }
        applyLevelEntryEffects(dstLevel, mob);
    }

    /** Side effects of the player setting foot on {@code dstLevel}: seal-on-entry
     *  floors vanish their up-stairs, and the final-boss floor seeds the revenant
     *  roster + spawns / scales the Great Wraith (RL-19). Idempotent (sealed
     *  stairs go null; the boss spawns only once). Invoked both by stair
     *  transfers and by initial placement (the "start at level" option), so
     *  starting directly on a special floor behaves like descending into it. */
    public static void applyLevelEntryEffects(Level dstLevel, Mob mob) {
        if (dstLevel == null || mob == null || !mob.isPlayer) return;
        if (dstLevel.sealOnEntry && dstLevel.stairsUp != null) {
            LevelSystem.sealStairsUp(dstLevel);
        }
        if (dstLevel.kind == Level.LevelKind.FINAL_BOSS
                && !dstLevel.bossDefeated && findFinalBoss(dstLevel) == null) {
            spawnFinalBoss(dstLevel, mob);
        }
    }

    /** Spawn the Great Wraith at the arena centre and scale it by the arriving
     *  player's beacons lit; seed the depleting revenant roster from the
     *  player's kills (RL-19). */
    private static void spawnFinalBoss(Level level, Mob player) {
        java.util.List<String> roster = new java.util.ArrayList<>(player.killedRoster);
        if (GameBalance.BOSS_ADD_TOTAL_CAP > 0 && roster.size() > GameBalance.BOSS_ADD_TOTAL_CAP) {
            java.util.Collections.shuffle(roster, MobSystem.RANDOM);
            roster = new java.util.ArrayList<>(roster.subList(0, GameBalance.BOSS_ADD_TOTAL_CAP));
        }
        level.remainingRoster = roster;

        // Boss-floor hazard is set from the player's total kills on arrival - a
        // heavier body count makes the floor more dangerous, which drives the
        // revenant spawn rate (see runLevelSpawner). Frozen here: TurnSystem
        // skips the time-based hazard climb on the boss floor. Hazard is 0 up to
        // the kill floor, then +1 per BOSS_HAZARD_KILLS_PER_POINT, capped at
        // BOSS_HAZARD_MAX (0..7, wider than the normal HAZARD_MAX).
        int kills = player.killedRoster.size();
        level.hazardLevel = kills < GameBalance.BOSS_HAZARD_KILL_FLOOR ? 0
                : Math.min(GameBalance.BOSS_HAZARD_MAX,
                        1 + (kills - GameBalance.BOSS_HAZARD_KILL_FLOOR)
                                / Math.max(1, GameBalance.BOSS_HAZARD_KILLS_PER_POINT));

        Point at = level.lockedExit != null ? level.lockedExit
                : new Point(level.width / 2, level.height / 2);
        Mob boss = MobFactory.spawn("GREAT_WRAITH", at);
        if (boss == null) return;
        // The boss's beacon power is carried by its beacon spirits - one per
        // beacon lit on arrival. Its level + buffs derive from that count; the
        // player can destroy spirits (50% per landed hit) to weaken it.
        boss.beaconSpirits = Math.max(0, player.beaconsLit);
        applyBeaconSpiritPower(level, boss, /*seatFullHp=*/true);
        boss.stateOfMind = Mob.StateOfMind.AWAKE;
        level.mobs.add(boss);
        MobHooks.onSpawn(level, boss);
        if (level.events != null) {
            level.events.add(new com.bjsp123.rl2.event.GameEvent.MobSpawned(boss, at));
        }
    }

    /** (Re)derive the Great Wraith's power from its living beacon-spirit count:
     *  spawn level + PROTECTION / REGENERATION stacks + a haste milestone, all
     *  scaling 1:1 with spirits (same numbers the per-beacon scaling used).
     *  {@code seatFullHp} is true on spawn (full HP at the scaled level); false
     *  on a spirit loss - the level drops and current HP is clamped down (so
     *  destroying a spirit also chips the boss), never healed. */
    static void applyBeaconSpiritPower(Level level, Mob boss, boolean seatFullHp) {
        int n = Math.max(0, boss.beaconSpirits);
        int lvl = Math.min(GameBalance.MAX_CHARACTER_LEVEL,
                GameBalance.BOSS_BASE_LEVEL + n * GameBalance.BOSS_LEVEL_PER_BEACON);
        if (seatFullHp) {
            MobProgression.setSpawnLevel(boss, lvl);
        } else {
            boss.characterLevel = lvl;
            boss.statsDirty = true;
            int maxHp = (int) Math.round(boss.effectiveStats().maxHp);
            if (boss.hp > maxHp) boss.hp = maxHp;
        }
        setBeaconBuff(level, boss, com.bjsp123.rl2.model.Buff.BuffType.PROTECTION,   Math.min(10, n));
        setBeaconBuff(level, boss, com.bjsp123.rl2.model.Buff.BuffType.REGENERATION, Math.min(10, n));
        int haste = (GameBalance.BOSS_ABILITY_PER_BEACONS > 0
                && n >= GameBalance.BOSS_ABILITY_PER_BEACONS)
                ? Math.min(10, n / GameBalance.BOSS_ABILITY_PER_BEACONS) : 0;
        setBeaconBuff(level, boss, com.bjsp123.rl2.model.Buff.BuffType.HASTED, haste);
    }

    /** Set a beacon-derived buff to exactly {@code stacks}: apply it when absent
     *  (spawn - one log line), otherwise mutate the live stack count silently
     *  (recompute on spirit loss), or remove it at zero. */
    private static void setBeaconBuff(Level level, Mob boss,
                                      com.bjsp123.rl2.model.Buff.BuffType t, int stacks) {
        if (stacks <= 0) { BuffSystem.removeBuff(boss, t); return; }
        com.bjsp123.rl2.model.Buff existing = BuffSystem.get(boss, t);
        if (existing == null) {
            BuffSystem.apply(level, boss, t, stacks, boss);
        } else {
            existing.stacks = Math.min(stacks, BuffSystem.stackCap(t));
            boss.statsDirty = true;
        }
    }

    /** A landed player attack on the Great Wraith may shatter one beacon spirit
     *  (chance {@link GameBalance#BOSS_SPIRIT_DESTROY_CHANCE}); the boss's power
     *  is then recomputed from the reduced count. Only deliberate hits count -
     *  environmental DOT (fire/poison ticks, falls) is excluded. No-op once the
     *  spirits are gone. */
    static void maybeShatterBeaconSpirit(Level level, Mob attacker, Mob target,
                                                 AttackType type) {
        if (target == null || target.beaconSpirits <= 0) return;
        if (type == AttackType.ENVIRONMENTAL) return;
        if (attacker == null || !attacker.isPlayer) return;
        if (!"GREAT_WRAITH".equals(target.mobType)) return;
        if (MobSystem.RANDOM.nextDouble() >= GameBalance.BOSS_SPIRIT_DESTROY_CHANCE) return;
        target.beaconSpirits--;
        applyBeaconSpiritPower(level, target, /*seatFullHp=*/false);
        EventLog.add(Messages.beaconSpiritDestroyed(target.beaconSpirits));
    }

    /** Data-driven per-turn mob spawner. Reads {@link Level#spawner} (null on
     *  levels that don't spawn) and, on a successful chance roll, spawns one
     *  random species from the pool - optionally awake, with a spawn level
     *  that escalates the longer the player lingers. No-op when there's no
     *  spawner, the roll fails, the live-mob cap is hit, or no free tile is
     *  found. Mirrors the per-mob anthill spawner in {@link TurnSystem} but
     *  scoped to the whole level. */
    public static void runLevelSpawner(Level level) {
        if (level == null) return;
        Level.Spawner sp = level.spawner;
        if (sp == null) return;
        boolean bossPool = level.remainingRoster != null;   // final-boss revenants

        // Cadence. The boss-floor revenant pool derives its cadence from the
        // floor's hazard level (set from the player's kills on arrival); every
        // other spawner uses its fixed everyNTurns / chancePerTurn.
        if (bossPool) {
            int[] table = GameBalance.BOSS_ADD_CADENCE_BY_HAZARD;
            int hz = Math.max(0, Math.min(table.length - 1, level.hazardLevel));
            int cad = Math.max(GameBalance.BOSS_ADD_CADENCE_MIN, table[hz]);
            if (level.turnsOnLevel <= 0 || level.turnsOnLevel % cad != 0) return;
        } else if (sp.everyNTurns > 0) {
            if (level.turnsOnLevel <= 0 || level.turnsOnLevel % sp.everyNTurns != 0) return;
        } else {
            if (sp.chancePerTurn <= 0) return;
            if (MobSystem.RANDOM.nextDouble() >= sp.chancePerTurn) return;
        }

        // Pick the species + enforce the live cap.
        int rosterIdx = -1;
        String species;
        if (bossPool) {
            if (level.remainingRoster.isEmpty()) return;     // support exhausted
            int aliveRev = 0;
            for (Mob m : level.mobs) {
                if (BuffSystem.hasBuff(m, com.bjsp123.rl2.model.Buff.BuffType.REVENANT)) aliveRev++;
            }
            if (aliveRev >= sp.maxAlive) return;
            rosterIdx = MobSystem.RANDOM.nextInt(level.remainingRoster.size());
            species = level.remainingRoster.get(rosterIdx);
        } else {
            if (sp.speciesPool == null || sp.speciesPool.isEmpty()) return;
            int alive = 0;
            for (String type : sp.speciesPool) alive += MobQueries.countMobsOfType(level, type);
            if (alive >= sp.maxAlive) return;
            species = sp.speciesPool.get(MobSystem.RANDOM.nextInt(sp.speciesPool.size()));
        }
        if (!MobQueries.levelHasRoomForSpawn(level)) return;
        Point spawnPos = spawnerTile(level, sp);
        if (spawnPos == null) return;
        Mob bud = MobFactory.spawn(species, spawnPos);
        if (bud == null) return;

        if (bossPool) {
            // Reanimated kill: per-mob depth-adjusted (the boss floor is at
            // max depth, so each revenant lands at the top of its band) +
            // REVENANT mark + the boss's faction so it fights the player.
            MobProgression.setSpawnLevel(bud,
                    MobProgression.depthAdjustedSpawnLevel(level, Registries.mob(species)));
            BuffSystem.apply(level, bud,
                    com.bjsp123.rl2.model.Buff.BuffType.REVENANT, 9999, bud);
            Mob boss = findFinalBoss(level);
            if (boss != null) {
                bud.faction = boss.faction;
                bud.enemyFactions = boss.enemyFactions != null
                        ? new java.util.HashSet<>(boss.enemyFactions) : new java.util.HashSet<>();
            }
            level.remainingRoster.remove(rosterIdx);   // this individual is spent
        } else {
            // Per-mob depth-adjusted base level, with the spawner's linger
            // ramp added on top so a stalled level still escalates.
            int base = MobProgression.depthAdjustedSpawnLevel(
                    level, Registries.mob(species));
            int ramp = sp.levelRampPer10Turns * (level.turnsOnLevel / 10);
            int cap  = sp.maxSpawnLevel > 0
                    ? Math.min(GameBalance.MAX_CHARACTER_LEVEL, sp.maxSpawnLevel)
                    : GameBalance.MAX_CHARACTER_LEVEL;
            int lvl  = Math.min(cap, base + ramp);
            MobProgression.setSpawnLevel(bud, lvl);
        }
        if (sp.spawnAwake) bud.stateOfMind = Mob.StateOfMind.AWAKE;
        level.mobs.add(bud);
        MobHooks.onSpawn(level, bud);
        if (level.events != null) {
            level.events.add(new com.bjsp123.rl2.event.GameEvent.MobSpawned(bud, spawnPos));
        }
    }

    /** The living Great Wraith on a final-boss floor, or null. */
    private static Mob findFinalBoss(Level level) {
        if (level.mobs == null) return null;
        for (Mob m : level.mobs) if ("GREAT_WRAITH".equals(m.mobType)) return m;
        return null;
    }

    /** Pick a spawn tile per the spawner's placement strategy. */
    private static Point spawnerTile(Level level, Level.Spawner sp) {
        if (sp.placement == Level.Spawner.Placement.SOUL_SPAWNERS) {
            if (level.spawnerTiles == null || level.spawnerTiles.isEmpty()) return null;
            Point anchor = level.spawnerTiles.get(MobSystem.RANDOM.nextInt(level.spawnerTiles.size()));
            return MobHooks.freeAdjacentFloor(level, anchor);
        }
        if (sp.placement == Level.Spawner.Placement.MIDPOINT_TO_EXIT) {
            return midpointToExitTile(level);
        }
        // ADJACENT: near the player (the level-scoped spawner has no anchor mob).
        Mob player = TurnSystem.findPlayer(level);
        return player != null ? MobHooks.freeAdjacentFloor(level, player.position) : null;
    }

    /** A free floor tile roughly halfway between the player and the exit, with
     *  a small jitter so repeated spawns average around the midpoint. Falls
     *  back to the nearest free floor. Null if there's no player or exit yet. */
    private static Point midpointToExitTile(Level level) {
        Mob player = TurnSystem.findPlayer(level);
        Point exit = level.stairsDown != null ? level.stairsDown : level.lockedExit;
        if (player == null || player.position == null || exit == null) return null;
        int mx = (player.position.tileX() + exit.tileX()) / 2 + MobSystem.RANDOM.nextInt(5) - 2;
        int my = (player.position.tileY() + exit.tileY()) / 2 + MobSystem.RANDOM.nextInt(5) - 2;
        return nearestFreeFloor(level, mx, my);
    }

    /** Nearest unoccupied floor-like tile to {@code (tx,ty)} by expanding-ring
     *  search; null if the whole level is blocked. */
    private static Point nearestFreeFloor(Level level, int tx, int ty) {
        int maxR = Math.max(level.width, level.height);
        for (int r = 0; r <= maxR; r++) {
            for (int dy = -r; dy <= r; dy++) {
                for (int dx = -r; dx <= r; dx++) {
                    if (Math.max(Math.abs(dx), Math.abs(dy)) != r) continue; // ring only
                    int x = tx + dx, y = ty + dy;
                    if (x < 0 || y < 0 || x >= level.width || y >= level.height) continue;
                    if (!level.tiles[x][y].isFloorLike()) continue;
                    boolean occupied = false;
                    for (Mob m : level.mobs) {
                        if (m.position != null
                                && m.position.tileX() == x && m.position.tileY() == y) {
                            occupied = true; break;
                        }
                    }
                    if (!occupied) return new Point(x, y);
                }
            }
        }
        return null;
    }

    private static void emitFallingItems(Level level, Mob mob) {
        if (mob.inventory == null || level == null || level.events == null) return;
        List<Item> falling = new ArrayList<>();
        if (mob.inventory.bag != null) {
            falling.addAll(mob.inventory.bag);
            mob.inventory.bag.clear();
        }
        falling.addAll(mob.inventory.allEquipped());
        mob.inventory.weapon  = null;
        mob.inventory.offhand = null;
        mob.inventory.armor   = null;
        java.util.Arrays.fill(mob.inventory.amulets, null);
        java.util.Arrays.fill(mob.inventory.gems,    null);
        boolean tileVisible = MobSystem.tileVisibleToPlayer(level, mob.position);
        boolean involvesPlayer = mob.isPlayer;
        for (Item item : falling) {
            level.events.add(new com.bjsp123.rl2.event.GameEvent.ItemFallingIntoChasm(
                    item, mob.position));
            if (tileVisible) {
                EventLog.add(Messages.itemFellInChasm(item.name, involvesPlayer));
            }
        }
    }

    static void scatterMobAcrossWorld(Level srcLevel, Mob mob) {
        if (srcLevel == null || srcLevel.world == null || mob == null) return;
        com.bjsp123.rl2.model.Level[] levels = srcLevel.world.levels;
        if (levels == null || levels.length == 0) return;
        // Build the list of viable destination levels (non-null only) so the
        // uniform pick can't roll a hole and bail.
        java.util.List<com.bjsp123.rl2.model.Level> viable = new ArrayList<>();
        for (com.bjsp123.rl2.model.Level lvl : levels) {
            if (lvl != null && lvl.tiles != null) viable.add(lvl);
        }
        if (viable.isEmpty()) return;

        com.bjsp123.rl2.model.Level dst = null;
        int dx = -1, dy = -1;
        for (int attempt = 0; attempt < 40; attempt++) {
            com.bjsp123.rl2.model.Level cand = viable.get(MobSystem.RANDOM.nextInt(viable.size()));
            int x = MobSystem.RANDOM.nextInt(cand.width);
            int y = MobSystem.RANDOM.nextInt(cand.height);
            if (!cand.tiles[x][y].isFloorLike()) continue;
            if (MobQueries.mobAt(cand, new Point(x, y)) != null) continue;
            dst = cand;
            dx  = x;
            dy  = y;
            break;
        }
        if (dst == null) return;

        Point fromPoint = mob.position;
        // Departure visual on the SOURCE level - emitted BEFORE the transfer so
        // the player (who usually stays put when scattering an enemy with a
        // teleport orb) actually sees the enemy vanish in a streak burst. The
        // arrival visual below fires on the destination level for the case where
        // the player follows (e.g. SMART agent scattered with the camera).
        if (srcLevel.events != null && fromPoint != null) {
            srcLevel.events.add(new com.bjsp123.rl2.event.GameEvent.MobTeleported(
                    mob, fromPoint.tileX(), fromPoint.tileY(),
                    fromPoint.tileX(), fromPoint.tileY()));
        }
        // Cross-level scatter goes through the shared mob/level transfer
        // helper so the World.currentLevelIndex follows PLAYER / SMART agents
        // and doesn't leave the autoplay looking at an empty source level.
        transferMobToLevel(srcLevel, mob, dst, new Point(dx, dy));
        if (dst.events != null) {
            dst.events.add(new com.bjsp123.rl2.event.GameEvent.MobTeleported(
                    mob,
                    fromPoint != null ? fromPoint.tileX() : dx,
                    fromPoint != null ? fromPoint.tileY() : dy,
                    dx, dy));
        }
    }
}
