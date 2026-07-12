package com.bjsp123.rl2.logic;

import com.bjsp123.rl2.logic.MobSystem.Attitude;
import com.bjsp123.rl2.model.Item;
import com.bjsp123.rl2.model.Level;
import com.bjsp123.rl2.model.Level.Surface;
import com.bjsp123.rl2.model.Mob;
import com.bjsp123.rl2.model.Mob.Behavior;
import com.bjsp123.rl2.model.Point;
import com.bjsp123.rl2.model.Tile;

/**
 * Tile-to-tile mob movement extracted from {@link MobSystem}: A* stepping
 * toward a target (including ally position swaps), the leave/enter-tile hooks
 * (doors, surfaces, chasms, powerups), step animation emission, surface-aware
 * move costs, and the teleport entry points with their fade-timing constants.
 */
public final class MobMovement {

    private MobMovement() {}

    /**
     * Use A* to move mob one tile toward its targetPosition, then apply move cost.
     * Clears targetPosition on arrival or if no path exists.
     */
    public static void stepTowardTarget(Mob mob, Level level) {
        if (mob.targetPosition == null) return;
        if (BuffSystem.hasBuff(mob, com.bjsp123.rl2.model.Buff.BuffType.FROZEN)) {
            mob.targetPosition = null;
            TurnSystem.applyMoveCost(mob, mob.effectiveStats().moveCost);
            return;
        }

        int tx = mob.targetPosition.tileX(), ty = mob.targetPosition.tileY();
        int cx = mob.position.tileX(),       cy = mob.position.tileY();

        if (cx == tx && cy == ty) {
            mob.targetPosition = null;
            return;
        }

        // Jump perk - player can leap directly to any tile within Chebyshev radius 2
        // for one move tick, ignoring intervening obstacles. Falls through to normal
        // pathing if the destination tile itself is blocked or occupied.
        if (mob.isPlayer
                && mob.perks != null
                && mob.perks.getOrDefault(com.bjsp123.rl2.model.Perk.JUMP, 0) > 0) {
            int jdx = tx - cx, jdy = ty - cy;
            if (Math.max(Math.abs(jdx), Math.abs(jdy)) == 2) {
                Point dest = new Point(tx, ty);
                if (!MobQueries.blocksMovement(level, mob, dest) && MobQueries.mobAt(level, dest) == null) {
                    int oldX = cx, oldY = cy;
                    if (jdx != 0) mob.facingEast = jdx > 0;
                    mob.position = dest;
                    onMobLeftTile(level, mob, oldX, oldY);
                    onMobEnteredTile(level, mob, tx, ty);
                    startStepAnimation(level, mob, oldX, oldY, tx, ty);
                    mob.targetPosition = null;
                    TurnSystem.applyMoveCost(mob, moveCostOnto(mob, level, tx, ty));
                    return;
                }
            }
        }

        Point next = Pathfinder.nextStep(level, mob, mob.targetPosition);
        if (next == null) {
            mob.targetPosition = null;
            TurnSystem.applyMoveCost(mob, mob.effectiveStats().moveCost);
            return;
        }

        int nx = next.tileX(), ny = next.tileY();
        // FRIGHTENED gate - if the mover is the player and frightened, refuse to step
        // toward any visible terrifying mob. Hands control back to the player so they
        // can pick a different escape route. Uses Chebyshev distance: if the proposed
        // tile is strictly closer to a terrifying source than the current tile, abort.
        if (mob.isPlayer
                && BuffSystem.hasBuff(mob, com.bjsp123.rl2.model.Buff.BuffType.FRIGHTENED)
                && MobCombat.stepWouldApproachTerror(mob, level, cx, cy, nx, ny)) {
            mob.targetPosition = null;
            EventLog.add(new com.bjsp123.rl2.model.LogEvent(
                    "You are too frightened to approach.",
                    com.bjsp123.rl2.model.LogEvent.EventPriority.HIGH, true));
            return;
        }
        int stepDx = nx - cx, stepDy = ny - cy;
        if (stepDx != 0) mob.facingEast = stepDx > 0;
        Mob occupant = MobQueries.mobAt(level, next);
        if (occupant != null && mob.isGhostly()
                && MobSystem.getAttitudeToMob(mob, occupant) != Attitude.ATTACK) {
            // GHOSTLY: drift straight through a non-hostile occupant - ghosts
            // share the tile. Hostiles still get the bump-attack below.
            occupant = null;
        }
        if (occupant != null) {
            if (MobSystem.getAttitudeToMob(mob, occupant) == Attitude.ATTACK) {
                MobCombat.attack(level, mob, occupant);
                mob.targetPosition = null;
                TurnSystem.applyActionCost(mob, mob.effectiveStats().attackCost);
                return;
            }
            // The player (and the SMART autoplay driver standing in for the player) is
            // impassable to any non-hostile mob: even a FLEE / NOTHING swap is blocked,
            // so friendly critters can never end up shoving the player into a wall by
            // accident. The mover gives up its step and pays a regular move tick.
            if (occupant.isPlayer) {
                mob.targetPosition = null;
                TurnSystem.applyMoveCost(mob, mob.effectiveStats().moveCost);
                return;
            }
            // Non-player movers only displace strictly smaller non-hostile mobs (matches
            // Pathfinder's canEnter gate). Same-size or larger blocks the step entirely
            // - the mover idles for one tick. PLAYER and SMART (the autoplay/AI driver
            // for a player-class character) ignore the size gate so a non-hostile
            // critter never blocks a player-style mover's intended move.
            if (!mob.isPlayer
                    && mob.effectiveStats().size <= occupant.effectiveStats().size) {
                mob.targetPosition = null;
                TurnSystem.applyMoveCost(mob, mob.effectiveStats().moveCost);
                return;
            }
            // FLEE or NOTHING against a non-player - swap positions. Mover pays a regular
            // move tick; the other mob gets a free ride so we don't chain time costs per swap.
            // Both mobs are visibly travelling a tile, so both kick a step animation.
            Point myOld = mob.position;
            mob.position = occupant.position;
            occupant.position = myOld;
            startStepAnimation(level, mob,      myOld.tileX(),         myOld.tileY(),
                                                mob.position.tileX(),  mob.position.tileY());
            startStepAnimation(level, occupant, mob.position.tileX(),  mob.position.tileY(),
                                                occupant.position.tileX(), occupant.position.tileY());
            // NPCs grab whatever they step on; the player picks things up manually via
            // tryInteract (space / tap-on-self) so they don't get railroaded into
            // collecting every loot pile they walk through.
            // The SMART agent (isPlayer) drops its least valuable item to make
            // room when full; ordinary enemies just skip a full bag.
            if (mob.behavior != Behavior.PLAYER) MobSystem.pickupAtFeet(level, mob, mob.isPlayer);
            // Both swappers visibly stepped onto a new tile - apply door-leave / door-enter
            // bookkeeping symmetrically so a mouse-vs-cat shuffle through a doorway behaves
            // the same as a regular step.
            onMobLeftTile(level, mob,      myOld.tileX(),       myOld.tileY());
            onMobEnteredTile(level, mob,   mob.position.tileX(),  mob.position.tileY());
            onMobLeftTile(level, occupant, mob.position.tileX(),  mob.position.tileY());
            onMobEnteredTile(level, occupant, occupant.position.tileX(), occupant.position.tileY());
            TurnSystem.applyMoveCost(mob, moveCostOnto(mob, level, nx, ny));
            return;
        }

        int cost = mob.effectiveStats().moveCost;
        if (!MobQueries.blocksMovement(level, mob, next)) {
            int oldX = cx, oldY = cy;
            mob.position = next;
            if (mob.behavior != Behavior.PLAYER) MobSystem.pickupAtFeet(level, mob, mob.isPlayer);
            onMobLeftTile(level, mob, oldX, oldY);
            onMobEnteredTile(level, mob, nx, ny);
            cost = moveCostOnto(mob, level, nx, ny);
            startStepAnimation(level, mob, oldX, oldY, nx, ny);
        }
        TurnSystem.applyMoveCost(mob, cost);
    }

    /**
     * Door close-behind hook. When a mob steps off a tile, if that tile was a door (open or
     * closed) the mob's {@link Mob#doorClosing} setting decides whether the door should now
     * close: {@link Mob.DoorClosingBehavior#ALWAYS} (the player) closes any open door it
     * leaves; {@link Mob.DoorClosingBehavior#ONLY_IF_WAS_CLOSED} (mice / cats / ghosts) only
     * closes the door when they entered a closed door (recorded in {@link Mob#lastDoorWasClosed}),
     * so they leave the door in the state they found it.
     */
    private static void onMobLeftTile(Level level, Mob mob, int oldX, int oldY) {
        if (oldX < 0 || oldY < 0 || oldX >= level.width || oldY >= level.height) return;
        Tile t = level.tiles[oldX][oldY];
        boolean isPlayer = mob.isPlayer;
        // Auto-close any open-door variant that has a defined closed state.
        // ONETIME doors don't have a closedVariant so they're naturally
        // skipped (they don't auto-close - they're already broken to FLOOR).
        com.bjsp123.rl2.model.DoorBehavior db = t.doorBehavior();
        if (db != null && db.closedVariant() != null && !t.isClosedDoor()) {
            if (mob.doorClosing == Mob.DoorClosingBehavior.ALWAYS
                    || (mob.doorClosing == Mob.DoorClosingBehavior.ONLY_IF_WAS_CLOSED
                        && mob.lastDoorWasClosed)) {
                level.tiles[oldX][oldY] = db.closedVariant();
                if (level.events != null) level.events.add(
                        new com.bjsp123.rl2.event.GameEvent.DoorClosed(new com.bjsp123.rl2.model.Point(oldX, oldY)));
                EventLog.add(Messages.doorClosed(MobSystem.nameForLog(level, mob), isPlayer));
            }
        }
    }

    /** Total real-time duration of the teleport fade, ms. Split evenly between fade-out
     *  at origin (first half) and fade-in at destination (second half). */
    public static final int TELEPORT_FADE_TOTAL_MS = 1000;
    /** Half of {@link #TELEPORT_FADE_TOTAL_MS} - phase transition threshold. */
    public static final int TELEPORT_FADE_HALF_MS  = TELEPORT_FADE_TOTAL_MS / 2;

    /**
     * Jump {@code mob} to a free tile adjacent to {@code target}. Caller is
     * responsible for the LOS / free-tile pre-checks (the teleport ability
     * picker does this in {@code pickAbilityTarget}); we still re-check the
     * free-tile clause here defensively in case state shifted between the
     * picker and this call. Runs the same per-step hooks a normal move would
     * (door leave, oil pickup, ...).
     */
    public static boolean tryTeleportToTarget(Level level, Mob mob, Mob target) {
        if (mob == null || level == null || target == null) return false;
        if (target.position == null) return false;
        Point dest = MobHooks.freeAdjacentFloor(level, target.position);
        if (dest == null) return false;
        Point origin = mob.position;
        int oldX = origin.tileX(), oldY = origin.tileY();
        int newX = dest.tileX(),   newY = dest.tileY();
        // Instant relocate - no step interpolation, since this is a teleport, not a slide.
        mob.position = dest;
        // Re-run the same per-step bookkeeping so a horror that lands on a closed door,
        // an oil pool, etc. picks up the correct state.
        onMobLeftTile(level, mob, oldX, oldY);
        onMobEnteredTile(level, mob, newX, newY);
        // Visual feedback - green vertical streaks rising from the departure tile, the
        // mob fading out at origin over 250 ms, then green streaks raining onto the
        // destination tile and the mob fading in over another 250 ms. The arrival
        // streaks are deferred to the phase transition by tickTeleportFadesRealTime so
        // they don't visibly fall on an empty tile.
        if (level.events != null) {
            level.events.add(new com.bjsp123.rl2.event.GameEvent.MobTeleported(
                    mob, oldX, oldY, newX, newY));
        }
        // Move cost is the caller's concern - {@code tryCastAbilities} already
        // bills attackCost after a successful cast, so we don't double-charge.
        return true;
    }

    /** Record the door's prior state and pop it open as the mob steps through, then run
     *  any oil pickup / drip rolls for the new cell. Door recording happens BEFORE the open
     *  so {@link Mob.DoorClosingBehavior#ONLY_IF_WAS_CLOSED} mobs (mice / cats / ghosts) know
     *  whether to re-close on the way out. Non-closing mobs (orcs, etc.) auto-open closed
     *  doors as they pass through and leave them open behind. */
    private static void onMobEnteredTile(Level level, Mob mob, int nx, int ny) {
        if (nx < 0 || ny < 0 || nx >= level.width || ny >= level.height) return;
        Tile t = level.tiles[nx][ny];
        boolean isPlayer = mob.isPlayer;
        // Door entry - delegate to DoorBehavior. The TileQuery.blocksMovementAt
        // gate has already verified the mob is allowed to cross (passRule),
        // so here we just apply the on-cross effect: open, break, or nothing.
        com.bjsp123.rl2.model.DoorBehavior db = t.doorBehavior();
        if (db != null) {
            if (t.isClosedDoor()) {
                mob.lastDoorWasClosed = true;
                switch (db.onCross()) {
                    case OPENS -> {
                        level.tiles[nx][ny] = db.openVariant();
                        if (level.events != null) level.events.add(
                                new com.bjsp123.rl2.event.GameEvent.DoorOpened(new com.bjsp123.rl2.model.Point(nx, ny)));
                        EventLog.add(Messages.doorOpened(MobSystem.nameForLog(level, mob), isPlayer));
                    }
                    case BREAKS -> {
                        level.tiles[nx][ny] = db.brokenVariant();
                        if (level.events != null) level.events.add(
                                new com.bjsp123.rl2.event.GameEvent.OnetimeDoorBroken(new com.bjsp123.rl2.model.Point(nx, ny)));
                        EventLog.add(Messages.doorBroken(MobSystem.nameForLog(level, mob), isPlayer));
                    }
                    case NONE -> { /* no-op; locked door handled elsewhere */ }
                }
            } else {
                // Walked through an already-open door variant - record so
                // doorClosing=ONLY_IF_WAS_CLOSED skips re-closing.
                mob.lastDoorWasClosed = false;
            }
        }
        // Beacon activation: the player stepping into any 8-neighbour of an
        // inactive beacon flips it to active and emits a one-shot activation
        // effect. Beacons themselves block movement, so the player never
        // stands ON one - only adjacent.
        if (mob.isPlayer) {
            for (int dx = -1; dx <= 1; dx++) {
                for (int dy = -1; dy <= 1; dy++) {
                    if (dx == 0 && dy == 0) continue;
                    int bx = nx + dx, by = ny + dy;
                    if (bx < 0 || by < 0 || bx >= level.width || by >= level.height) continue;
                    if (level.tiles[bx][by] != Tile.BEACON_INACTIVE) continue;
                    level.tiles[bx][by] = Tile.BEACON_ACTIVE;
                    level.beaconLit = true;   // RL-54: lighting the beacon raises hazard
                    mob.beaconsLit++;          // RL-19: scales the final boss + score
                    if (level.events != null) level.events.add(
                            new com.bjsp123.rl2.event.GameEvent.BeaconActivated(new Point(bx, by)));
                    EventLog.add(Messages.beaconActivated(MobSystem.nameForLog(level, mob)));
                }
            }
        }
        // Oil pickup: stepping onto an OIL surface applies the OILY buff for
        // MobSystem.OIL_STEP_BUFF_TURNS turns. Re-applies refresh the buff (max-merge) so wading
        // deeper resets the clock.
        // BOMB_DODGER gates the surface step-buff so a Rogue with the perk
        // stays dry crossing her own water / oil bombs.
        boolean bombDodger = mob.perks != null
                && mob.perks.getOrDefault(com.bjsp123.rl2.model.Perk.BOMB_DODGER, 0) >= 1;
        if (level.surface[nx][ny] == Surface.OIL && !bombDodger) {
            BuffSystem.apply(level, mob, com.bjsp123.rl2.model.Buff.BuffType.OILY,
                    MobSystem.OIL_STEP_BUFF_TURNS, null);
        }
        // Water pickup: stepping into / over a WATER surface soaks the mob
        // for MobSystem.WATER_STEP_BUFF_TURNS turns (so they take double damage from
        // lightning until they dry off). Refreshes via the standard
        // max-merge so a long wade keeps resetting the clock.
        if (level.surface[nx][ny] == Surface.WATER && !bombDodger) {
            BuffSystem.apply(level, mob, com.bjsp123.rl2.model.Buff.BuffType.WET,
                    MobSystem.WATER_STEP_BUFF_TURNS, null);
        }
        // Oil drip: an oily medium-or-larger mob drags its slick onto the new tile a
        // fraction of the time (12.5% per step). Tiny mobs (size <
        // BIG_ENOUGH_TO_DRIP_OIL) are too light to leave a residue, and flying mobs
        // hover over the floor. SurfaceSystem.addSurface handles the "tile is already
        // oily" case (it spreads to a neighbour instead).
        if (mob.effectiveStats().size >= Mob.BIG_ENOUGH_TO_DRIP_OIL && !mob.effectiveStats().flying
                && BuffSystem.hasBuff(mob, com.bjsp123.rl2.model.Buff.BuffType.OILY)
                && MobSystem.RANDOM.nextDouble() < 0.125) {
            SurfaceSystem.addSurface(level, new Point(nx, ny), Surface.OIL);
        }
        // Water drip: a sufficiently big WET non-flying mob (size > 4) on
        // a bare-floor tile (no surface) leaves a small puddle 1/3 of the
        // time. Lets a soaked dragon track water onto adjacent tiles
        // until it dries off - a free conduit for follow-up lightning
        // strikes.
        if (mob.effectiveStats().size > 4 && !mob.effectiveStats().flying
                && BuffSystem.hasBuff(mob, com.bjsp123.rl2.model.Buff.BuffType.WET)
                && level.surface[nx][ny] == null
                && MobSystem.RANDOM.nextDouble() < (1.0 / 3.0)) {
            SurfaceSystem.addSurface(level, new Point(nx, ny), Surface.WATER);
        }
        // Tree trample: a very large mob (size >= BIG_ENOUGH_TO_FLATTEN_TREES)
        // crashing through trees flattens them to grass. Flying mobs clear the
        // canopy.
        if (mob.effectiveStats().size >= Mob.BIG_ENOUGH_TO_FLATTEN_TREES
                && !mob.effectiveStats().flying
                && level.vegetation != null
                && level.vegetation[nx][ny] == com.bjsp123.rl2.model.Level.Vegetation.TREES) {
            level.vegetation[nx][ny] = com.bjsp123.rl2.model.Level.Vegetation.GRASS;
            VegetationSystem.emitVegetationChanged(level, nx, ny,
                    com.bjsp123.rl2.model.Level.Vegetation.GRASS);
        }
        // POWERUP pickup-trigger - stepping onto a tile with a POWERUP
        // item destroys the item and applies its wandEffect to the
        // stepper. PLAYER and SMART (autoplay driver standing in for the
        // player) both trigger; other mobs walk over them.
        if (mob.isPlayer) {
            applyPowerupsAt(level, mob, nx, ny);
        }
    }

    /** Drain any POWERUP items off tile ({@code nx},{@code ny}), applying
     *  each one's {@link Item.ItemEffect} to the stepper before removing
     *  it from {@link Level#items}. */
    private static void applyPowerupsAt(Level level, Mob picker, int nx, int ny) {
        if (level.items == null || level.items.isEmpty()) return;
        java.util.Iterator<com.bjsp123.rl2.model.Item> it = level.items.iterator();
        while (it.hasNext()) {
            com.bjsp123.rl2.model.Item item = it.next();
            if (item == null || item.location == null) continue;
            if (item.useBehavior != com.bjsp123.rl2.model.Item.UseBehavior.POWERUP) continue;
            if ((int) Math.floor(item.location.x()) != nx
                    || (int) Math.floor(item.location.y()) != ny) continue;
            // Only consume the pill if it actually did something. A HP pill
            // stepped on at full health, or a charge pill with no wand to
            // refill, is left on the floor to be collected when it's useful.
            if (ItemSystem.applyPowerup(level, picker, item)) {
                it.remove();
            }
        }
    }

    /** Step-animation length for a mob with the default move cost
     *  ({@link GameBalance#PLAYER_MOVE_COST}). 5 ~ 83 ms at 60 fps - user-spec is "the
     *  player with 100 move cost takes 5 frames per tile". Mobs with lower move cost
     *  step faster proportionally; mobs with higher move cost are CAPPED at
     *  {@link #STEP_ANIMATION_FRAMES_MAX} so a sluggish creature visibly drags between
     *  tiles by one frame more than the player but no further. */
    public static final int STEP_ANIMATION_FRAMES_DEFAULT = 5;
    /** Hard cap on step animation length, in render frames. ~100 ms at 60 fps. Slightly
     *  longer than the default so slow mobs read as a touch laggier than the player. */
    public static final int STEP_ANIMATION_FRAMES_MAX     = 6;

    /** Kick the step-interpolation animation on {@code mob}: it has just been logically
     *  teleported to {@code (toX, toY)} but the renderer should draw it sliding from
     *  {@code (fromX, fromY)} over a number of frames proportional to its
     *  {@link com.bjsp123.rl2.model.StatBlock#moveCost}. Schedules concurrently - multiple
     *  mobs that step on the same tick all start sliding on the same render frame, and
     *  the freeze gate stretches to cover the longest of them. */
    public static void startStepAnimation(Level level, Mob mob,
                                          int fromX, int fromY, int toX, int toY) {
        if (mob == null) return;
        if (level != null && level.events != null) {
            level.events.add(new com.bjsp123.rl2.event.GameEvent.MobMoved(
                    mob, fromX, fromY, toX, toY));
        }
    }

    /** Floor on step-animation length, in render frames. Fast mobs (mouse, kitten) would
     *  otherwise compute ~3 frames at 60 fps which looks closer to a teleport than a slide;
     *  clamping up to {@value} gives them a visibly travelled tile (~67 ms) - about 50%
     *  more frames than the unclamped scaling produced. */
    public static final int STEP_ANIMATION_FRAMES_MIN = 4;

    

    /**
     * Move cost a mob pays to step onto tile {@code (x,y)}. Doubles the base
     * {@link Mob#moveCost} when the destination carries an OIL surface and the mob isn't
     * flying - slick footing slows ground walkers but flyers ignore it. Other surfaces
     * (water, blood) currently don't modify cost; add a switch here when they should.
     */
    private static int moveCostOnto(Mob mob, Level level, int x, int y) {
        int base = mob.effectiveStats().moveCost;
        if (mob.effectiveStats().flying) return base;
        if (level.surface == null) return base;
        if (x < 0 || y < 0 || x >= level.width || y >= level.height) return base;
        if (level.surface[x][y] == Surface.OIL) return base * 2;
        return base;
    }

    /** Teleport {@code mob} to a random walkable, unoccupied tile on a
     *  random level of {@code srcLevel.world}. The destination level may
     *  be the same as the source. Emits a {@link com.bjsp123.rl2.event.GameEvent.MobTeleported}
     *  on the level where the mob ends up so the existing teleport-fade
     *  visual plays. Falls back gracefully (no-op) if no walkable tile can
     *  be found after a bounded number of tries. */
    /** Teleport {@code mob} to a random walkable tile on its CURRENT level,
     *  preferring a landing at least {@code minEnemyDist} Chebyshev tiles from
     *  every live hostile. After {@code maxTries} draws fail that distance test
     *  it settles for the first walkable tile it saw. Emits a
     *  {@link com.bjsp123.rl2.event.GameEvent.MobTeleported} for the visual.
     *  Returns {@code false} only when the level has no usable tile at all
     *  (in which case the mob hasn't moved). */
    public static boolean teleportRandomlyOnLevel(Level level, Mob mob,
                                                  int minEnemyDist, int maxTries) {
        if (level == null || mob == null || mob.position == null
                || level.tiles == null) return false;
        Point from = mob.position;
        Point chosen = null, fallback = null;
        for (int attempt = 0; attempt < Math.max(1, maxTries); attempt++) {
            int x = MobSystem.RANDOM.nextInt(level.width);
            int y = MobSystem.RANDOM.nextInt(level.height);
            if (!level.tiles[x][y].isFloorLike()) continue;
            if (MobQueries.mobAt(level, new Point(x, y)) != null) continue;
            if (fallback == null) fallback = new Point(x, y);
            if (farFromHostiles(level, mob, x, y, minEnemyDist)) {
                chosen = new Point(x, y);
                break;
            }
        }
        if (chosen == null) chosen = fallback;   // no enemy-safe tile in budget
        if (chosen == null) return false;        // level has nowhere to land
        mob.position = chosen;
        if (level.events != null) {
            level.events.add(new com.bjsp123.rl2.event.GameEvent.MobTeleported(
                    mob, from.tileX(), from.tileY(), chosen.tileX(), chosen.tileY()));
        }
        return true;
    }

    /** True when ({@code x},{@code y}) is at least {@code minDist} Chebyshev
     *  tiles from every live mob hostile to {@code mob}. */
    private static boolean farFromHostiles(Level level, Mob mob, int x, int y, int minDist) {
        if (minDist <= 0 || level.mobs == null) return true;
        for (Mob other : level.mobs) {
            if (other == null || other == mob || other.position == null || other.hp <= 0) continue;
            if (MobSystem.getAttitudeToMob(other, mob) != Attitude.ATTACK
                    && MobSystem.getAttitudeToMob(mob, other) != Attitude.ATTACK) continue;
            int d = Math.max(Math.abs(other.position.tileX() - x),
                             Math.abs(other.position.tileY() - y));
            if (d < minDist) return false;
        }
        return true;
    }
}
