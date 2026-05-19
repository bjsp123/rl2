package com.bjsp123.rl2.logic;

import com.bjsp123.rl2.model.Item;
import com.bjsp123.rl2.model.Buff;
import com.bjsp123.rl2.model.Inventory;
import com.bjsp123.rl2.model.Level;
import com.bjsp123.rl2.model.MinMax;
import com.bjsp123.rl2.model.Mob.Material;
import com.bjsp123.rl2.model.Mob;
import com.bjsp123.rl2.model.Mob.Behavior;
import com.bjsp123.rl2.model.Point;
import com.bjsp123.rl2.model.Mob.StateOfMind;
import com.bjsp123.rl2.model.Level.Surface;
import com.bjsp123.rl2.model.Item.ItemEffect;
import com.bjsp123.rl2.model.Tile;
import com.bjsp123.rl2.model.Level.Vegetation;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;
import java.util.Random;

public class MobSystem {

    /** What mob A wants to do with mob B when they meet. Drives target selection (flee
     *  targets are preferred over attack targets) and collision behaviour (ATTACK -> strike,
     *  FLEE / NOTHING / ALLY -> swap positions). {@code ALLY} is the "we're on the same
     *  side, don't fight, but we're not strangers" answer - used by collision-swap rules
     *  and by code that wants a quick "is this mob friendly?" check. */
    public enum Attitude {
        NOTHING, FLEE, ATTACK, ALLY
    }

    /**
     * Cardinal facing for a mob. Used by renderers that pick a directional sprite per
     * frame; non-directional sprites ignore it. A mob updates its facing when it takes a
     * step that has a non-zero delta in x or y (diagonals pick the axis with the larger
     * delta - see {@link #fromDelta}).
     */
    public enum Direction {
        NORTH, SOUTH, EAST, WEST;

        /** Choose a facing from a step delta. (0, 0) leaves the caller to keep its current
         *  facing. */
        public static Direction fromDelta(int dx, int dy, Direction fallback) {
            if (dx == 0 && dy == 0) return fallback;
            if (Math.abs(dx) >= Math.abs(dy)) return dx > 0 ? EAST : WEST;
            return dy > 0 ? NORTH : SOUTH;
        }
    }

    /** Mechanism through which damage reached a mob. Passed to {@link #processAttack}. */
    public enum AttackType {
        /** Hand-to-hand / weapon strike at adjacent range. */
        MELEE,
        /** Innate single-target projectile attack. */
        RANGED,
        /** Item hurled at a target tile. */
        THROWN,
        /** Ranged magical effect (e.g. magic missile). */
        MAGIC,
        /** No attacker - pit trap, starvation, drowning, etc. */
        ENVIRONMENTAL
    }

    /** Elemental class of damage. Routes mitigation: {@link Buff.BuffType#PROTECTION}
     *  resists {@link #PHYSICAL}; {@link Buff.BuffType#ANTI_MAGIC} resists
     *  {@link #MAGIC} and {@link #FIRE}; {@link #POISON}, {@link #SHOCK}, and
     *  {@link #STARVATION} are unmitigated by buffs. Independent of {@link AttackType}
     *  (mechanism) - a fire bomb's impact damage is THROWN/PHYSICAL while its DOT is
     *  ENVIRONMENTAL/FIRE. */
    public enum DamageElement {
        PHYSICAL, MAGIC, POISON, FIRE, SHOCK, STARVATION
    }

    /** Mutable accumulator for the per-attack tuning log line. Callers populate it
     *  with their stat-based mitigations (armor, magicResist) before calling
     *  {@link #processAttack}; processAttack appends buff mitigations
     *  (PROTECTION / ANTI_MAGIC) and emits one LOW-priority log entry. */
    public static final class DamageBreakdown {
        public final DamageElement element;
        /** The dice-rolled damage before <em>any</em> mitigation. */
        public final int rolled;
        /** Ordered list of "label: -N" deductions, in the order they were applied. */
        public final java.util.List<String> mitigations = new java.util.ArrayList<>();

        public DamageBreakdown(DamageElement element, int rolled) {
            this.element = element;
            this.rolled  = rolled;
        }

        /** Append a mitigation entry. Zero/negative amounts are dropped. */
        public DamageBreakdown add(String label, int amount) {
            if (amount > 0) mitigations.add(label + " -" + amount);
            return this;
        }
    }

    private static final Random RANDOM = new Random();

    /** Default duration in turns that the {@link com.bjsp123.rl2.model.Buff.BuffType#OILY}
     *  buff lasts when a mob steps onto an OIL surface. The buff system tracks the
     *  countdown - see {@link BuffSystem#tickPerTurn}. */
    public static final int OIL_STEP_BUFF_TURNS = 3;

    /** Default duration in turns that the {@link com.bjsp123.rl2.model.Buff.BuffType#WET}
     *  buff lasts when a mob steps onto a WATER surface. */
    public static final int WATER_STEP_BUFF_TURNS = 3;

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
        if (mob.behavior == Behavior.PLAYER
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
        if (mob.behavior == Behavior.PLAYER
                && BuffSystem.hasBuff(mob, com.bjsp123.rl2.model.Buff.BuffType.FRIGHTENED)
                && stepWouldApproachTerror(mob, level, cx, cy, nx, ny)) {
            mob.targetPosition = null;
            EventLog.add(new com.bjsp123.rl2.model.LogEvent(
                    "You are too frightened to approach.",
                    com.bjsp123.rl2.model.LogEvent.EventPriority.HIGH, true));
            return;
        }
        int stepDx = nx - cx, stepDy = ny - cy;
        if (stepDx != 0) mob.facingEast = stepDx > 0;
        Mob occupant = MobQueries.mobAt(level, next);
        if (occupant != null) {
            if (getAttitudeToMob(mob, occupant) == Attitude.ATTACK) {
                attack(level, mob, occupant);
                mob.targetPosition = null;
                TurnSystem.applyActionCost(mob, mob.effectiveStats().attackCost);
                return;
            }
            // The player is impassable to any non-hostile mob: even a FLEE / NOTHING swap
            // is blocked, so friendly critters can never end up shoving the player into a
            // wall by accident. The mover gives up its step and pays a regular move tick.
            if (occupant.behavior == Behavior.PLAYER) {
                mob.targetPosition = null;
                TurnSystem.applyMoveCost(mob, mob.effectiveStats().moveCost);
                return;
            }
            // Non-player movers only displace strictly smaller non-hostile mobs (matches
            // Pathfinder's canEnter gate). Same-size or larger blocks the step entirely
            // - the mover idles for one tick. The PLAYER ignores the size gate so a
            // non-hostile critter never blocks the player's intended move.
            if (mob.behavior != Behavior.PLAYER && mob.effectiveStats().size <= occupant.effectiveStats().size) {
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
            if (mob.behavior != Behavior.PLAYER) pickupAtFeet(level, mob);
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
            if (mob.behavior != Behavior.PLAYER) pickupAtFeet(level, mob);
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
        if (t == Tile.DOOR_OPEN){
            if (mob.doorClosing == Mob.DoorClosingBehavior.ALWAYS
                    || (mob.doorClosing == Mob.DoorClosingBehavior.ONLY_IF_WAS_CLOSED
                        && mob.lastDoorWasClosed)) {
                level.tiles[oldX][oldY] = Tile.DOOR;
                if (level.events != null) level.events.add(
                        new com.bjsp123.rl2.event.GameEvent.DoorClosed(new com.bjsp123.rl2.model.Point(oldX, oldY)));
            }
        }

        if (t == Tile.CRYSTAL_DOOR_OPEN){
            if (mob.doorClosing == Mob.DoorClosingBehavior.ALWAYS
                    || (mob.doorClosing == Mob.DoorClosingBehavior.ONLY_IF_WAS_CLOSED
                        && mob.lastDoorWasClosed)) {
                level.tiles[oldX][oldY] = Tile.CRYSTAL_DOOR;
                if (level.events != null) level.events.add(
                        new com.bjsp123.rl2.event.GameEvent.DoorClosed(new com.bjsp123.rl2.model.Point(oldX, oldY)));
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
        if (t == Tile.DOOR) {
            mob.lastDoorWasClosed = true;
            level.tiles[nx][ny] = Tile.DOOR_OPEN;
            if (level.events != null) level.events.add(
                    new com.bjsp123.rl2.event.GameEvent.DoorOpened(new com.bjsp123.rl2.model.Point(nx, ny)));
        } else if (t == Tile.DOOR_OPEN) {
            mob.lastDoorWasClosed = false;
        } else if (t == Tile.CRYSTAL_DOOR) {
            mob.lastDoorWasClosed = true;
            level.tiles[nx][ny] = Tile.CRYSTAL_DOOR_OPEN;
            if (level.events != null) level.events.add(
                    new com.bjsp123.rl2.event.GameEvent.DoorOpened(new com.bjsp123.rl2.model.Point(nx, ny)));
        } else if (t == Tile.CRYSTAL_DOOR_OPEN) {
            mob.lastDoorWasClosed = false;
        } else if (t == Tile.ONETIME_DOOR) {
            level.tiles[nx][ny] = Tile.FLOOR;
            if (level.events != null) level.events.add(
                    new com.bjsp123.rl2.event.GameEvent.OnetimeDoorBroken(new com.bjsp123.rl2.model.Point(nx, ny)));
        }
        // Oil pickup: stepping onto an OIL surface applies the OILY buff for
        // OIL_STEP_BUFF_TURNS turns. Re-applies refresh the buff (max-merge) so wading
        // deeper resets the clock.
        if (level.surface[nx][ny] == Surface.OIL) {
            BuffSystem.apply(level, mob, com.bjsp123.rl2.model.Buff.BuffType.OILY,
                    1, OIL_STEP_BUFF_TURNS, null);
        }
        // Water pickup: stepping into / over a WATER surface soaks the mob
        // for WATER_STEP_BUFF_TURNS turns (so they take double damage from
        // lightning until they dry off). Refreshes via the standard
        // max-merge so a long wade keeps resetting the clock.
        if (level.surface[nx][ny] == Surface.WATER) {
            BuffSystem.apply(level, mob, com.bjsp123.rl2.model.Buff.BuffType.WET,
                    1, WATER_STEP_BUFF_TURNS, null);
        }
        // Oil drip: an oily medium-or-larger mob drags its slick onto the new tile a
        // fraction of the time (50% per the OILY-buff spec). Tiny mobs (size <
        // BIG_ENOUGH_TO_DRIP_OIL) are too light to leave a residue, and flying mobs
        // hover over the floor. SurfaceSystem.addSurface handles the "tile is already
        // oily" case (it spreads to a neighbour instead).
        if (mob.effectiveStats().size >= Mob.BIG_ENOUGH_TO_DRIP_OIL && !mob.effectiveStats().flying
                && BuffSystem.hasBuff(mob, com.bjsp123.rl2.model.Buff.BuffType.OILY)
                && RANDOM.nextDouble() < 0.5) {
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
                && RANDOM.nextDouble() < (1.0 / 3.0)) {
            SurfaceSystem.addSurface(level, new Point(nx, ny), Surface.WATER);
        }
        // POWERUP pickup-trigger - stepping onto a tile with a POWERUP
        // item destroys the item and applies its wandEffect to the
        // stepper. Player-only by spec; other mobs walk over them.
        if (mob.behavior == Behavior.PLAYER) {
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
            ItemSystem.applyPowerup(level, picker, item);
            it.remove();
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

    /**
     * What mob {@code a} wants to do about mob {@code b}: ATTACK (chase & hit), FLEE (move
     * away), ALLY (won't fight, can swap places), or NOTHING (ignore). Purely one-sided -
     * a dog's attitude toward a cat can be ATTACK while the cat's attitude toward the dog
     * is FLEE. Callers must evaluate both directions when deciding collision behaviour.
     *
     * <p>Resolution order:
     * <ol>
     *   <li>self / null -> NOTHING</li>
     *   <li>owner-based ALLY - pet <-> master, two pets sharing a master</li>
     *   <li>FRIGHTENED buff -> FLEE everything (player exempt)</li>
     *   <li>shared {@link Mob#faction} tag -> ALLY</li>
     *   <li>explicit {@link Mob#fleeTypes} membership -> FLEE (priority over ATTACK)</li>
     *   <li>explicit {@link Mob#attackTypes} membership -> ATTACK</li>
     *   <li>owner-inherited hostility - pet attacks what its master attacks</li>
     *   <li>ally-defense transitivity -> ATTACK anyone hostile to {@code a} or any
     *       mob in {@code a}'s faction ({@link #defendsAlly})</li>
     *   <li>otherwise NOTHING</li>
     * </ol>
     *
     * <p>Same-{@link Behavior} no longer implies ALLY - alliance is purely the
     * shared-faction-tag relationship, with lone-wolf species carrying a null
     * faction.
     */
    public static Attitude getAttitudeToMob(Mob a, Mob b) {
        if (a == null || b == null || a == b) return Attitude.NOTHING;
        // Owner / owned-pet shortcuts. A tame dog should never be treated as
        // hostile by its master, and two pets sharing a master are allies.
        if (a.owner == b || b.owner == a)            return Attitude.ALLY;
        if (a.owner != null && a.owner == b.owner)   return Attitude.ALLY;
        // Frightened mobs flee everything. Player exempt: their intent comes from
        // user input, not AI; the buff is cosmetic on the player side.
        if (a.behavior != Behavior.PLAYER
                && BuffSystem.hasBuff(a, com.bjsp123.rl2.model.Buff.BuffType.FRIGHTENED)) {
            return Attitude.FLEE;
        }
        // Shared faction wins over attack/flee lists, so a kobold general healing
        // a kobold he's previously fought (combat-memoried into his attackTypes
        // by some weird chain) still reads as an ally.
        if (a.faction != null && a.faction.equals(b.faction)) return Attitude.ALLY;
        // Symmetric rule: declaring b's faction as an enemy resolves to ATTACK
        // without needing every member of that faction in attackTypes. Powers
        // the player-hostility relationship via the PLAYER faction.
        if (b.faction != null && a.enemyFactions.contains(b.faction)) return Attitude.ATTACK;
        if (a.fleeTypes   != null && b.mobType != null && a.fleeTypes  .contains(b.mobType)) return Attitude.FLEE;
        if (a.attackTypes != null && b.mobType != null && a.attackTypes.contains(b.mobType)) return Attitude.ATTACK;
        // Loyalty - a tame mob inherits its owner's hostilities. If the owner wants to
        // attack b's species (either by spec or via combat memory) or b's faction, the pet does too.
        if (a.owner != null && a.owner != b
                && b.mobType != null && a.owner.attackTypes != null
                && a.owner.attackTypes.contains(b.mobType)) {
            return Attitude.ATTACK;
        }
        if (a.owner != null && a.owner != b
                && b.faction != null && a.owner.enemyFactions.contains(b.faction)) {
            return Attitude.ATTACK;
        }
        // Ally-defense transitivity. {@code a} is hostile to anyone whose
        // attackTypes lists a's own species, any species sharing a's faction,
        // or anyone who has declared a's faction an enemy.
        if (defendsAlly(a, b)) return Attitude.ATTACK;
        return Attitude.NOTHING;
    }

    /** True iff {@code b} would treat {@code a} as a hostile target - covers
     *  {@code b.attackTypes} containing {@code a}'s species or any species
     *  sharing {@code a}'s {@link Mob#faction}, and {@code b.enemyFactions}
     *  including {@code a}'s faction. Powers the ally-defense rule in
     *  {@link #getAttitudeToMob}. */
    private static boolean defendsAlly(Mob a, Mob b) {
        if (b == null) return false;
        if (a.faction != null && b.enemyFactions.contains(a.faction)) return true;
        if (b.attackTypes == null || b.attackTypes.isEmpty()) return false;
        if (a.mobType != null && b.attackTypes.contains(a.mobType)) return true;
        if (a.faction != null) {
            for (String t : Registries.mobsInFaction(a.faction)) {
                if (b.attackTypes.contains(t)) return true;
            }
        }
        return false;
    }

    /**
     * Record that {@code a} and {@code b} have been in combat - bidirectional. Each side
     * learns to attack the other's species on sight, and drops any fear of it (combat
     * overrides flee). Attack/flee identity is keyed on {@link com.bjsp123.rl2.model.Mob.MobType}
     * so behavior code never has to consult the glyph.
     */
    public static void recordCombatMemory(Level level, Mob a, Mob b, String reason) {
        if (a == null || b == null || a == b) return;
        if (a.mobType == null || b.mobType == null) return;
        if (a.attackTypes == null) a.attackTypes = new java.util.HashSet<>();
        if (a.fleeTypes   == null) a.fleeTypes   = new java.util.HashSet<>();
        if (b.attackTypes == null) b.attackTypes = new java.util.HashSet<>();
        if (b.fleeTypes   == null) b.fleeTypes   = new java.util.HashSet<>();
        boolean aLearned = a.attackTypes.add(b.mobType);
        a.fleeTypes.remove(b.mobType);
        boolean bLearned = b.attackTypes.add(a.mobType);
        b.fleeTypes.remove(a.mobType);

        if (!aLearned && !bLearned) return;
        boolean aPlayer = a.behavior == Behavior.PLAYER;
        boolean bPlayer = b.behavior == Behavior.PLAYER;
        String aName = nameForLog(level, a);
        String bName = nameForLog(level, b);
        if (!aPlayer && bPlayer && aLearned) {
            EventLog.add(Messages.attitudeTurnsOnPlayer(aName, reason));
        } else if (!bPlayer && aPlayer && bLearned) {
            EventLog.add(Messages.attitudeTurnsOnPlayer(bName, reason));
        } else if (!aPlayer && !bPlayer) {
            if (aLearned) EventLog.add(Messages.attitudeMobOnMob(aName, bName, reason));
            else          EventLog.add(Messages.attitudeMobOnMob(bName, aName, reason));
        }
    }

    /** Walk the projectile path from {@code from} to {@code to} and return
     *  the position of the first obstacle in the way: a mob (other than
     *  {@code shooter}), or a movement-blocking tile (wall, statue,
     *  altar, throne, lamp). The shooter's own tile is always skipped so
     *  a caster doesn't block their own missile. Used by both wand fire
     *  and item throw so a projectile aimed beyond an obstacle clips to
     *  that obstacle and resolves its effect there - bombs detonate
     *  against walls / statues, magic missiles strike the first body in
     *  the line, etc. Returns {@code to} when the trajectory is clear. */
    public static Point firstMobBlocking(Level level, Point from, Point to, Mob shooter) {
        return MobVisibility.firstMobBlocking(level, from, to, shooter);
    }

    /** Bresenham-walk every tile along the segment from {@code from} to {@code to} and
     *  return true the moment one of them is in {@code level.visible}. Used to decide
     *  whether an in-flight projectile is observable; a missile streaking entirely
     *  through unseen rooms shouldn't pause the world. Both endpoints null-tolerant. */
    public static boolean trajectoryTouchesVisible(Level level, Point from, Point to) {
        return MobVisibility.trajectoryTouchesVisible(level, from, to);
    }

    /** True iff {@code mob} is on a tile the player currently sees. The player counts as
     *  visible to themselves so messages and animations involving the player aren't
     *  redacted to "something". */
    public static boolean isVisibleToPlayer(Level level, Mob mob) {
        return MobVisibility.isVisibleToPlayer(level, mob);
    }

    /** Display name for a mob in the event log. The player is always shown by name; any
     *  other mob the player can't currently see is rendered as "something". */
    public static String nameForLog(Level level, Mob mob) {
        return MobVisibility.nameForLog(level, mob);
    }

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
            Attitude fwd = getAttitudeToMob(viewer, other);
            if (fwd == Attitude.ATTACK || fwd == Attitude.FLEE) { needsFov = true; break; }
            if (getAttitudeToMob(other, viewer) == Attitude.ATTACK) { needsFov = true; break; }
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
        return Math.max(Math.abs(sx - vx), Math.abs(sy - vy)) <= radius;
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
        boolean playerInvolved = (attacker != null && attacker.behavior == Behavior.PLAYER)
                || (target != null && target.behavior == Behavior.PLAYER);
        EventLog.add(Messages.surpriseAttack(nameForLog(level, attacker),
                nameForLog(level, target), playerInvolved));
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
        int hitDenom = atkAcc + tgtEva;
        return hitDenom > 0 && RANDOM.nextInt(hitDenom) < atkAcc;
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

    /** Roll a uniform integer in {@code [range.min, range.max]} using the shared combat
     *  RNG. Public so out-of-package call sites (PlayScreen magic-missile resolution,
     *  FireSystem fire damage) can apply magic resist without having to maintain their
     *  own RNG. */
    public static int rollRange(MinMax range) {
        return MobStats.rollRange(range);
    }

    /**
     * Single rollup for a mob's effective stats. Copies the intrinsic block, then folds
     * in every contributor in declaration order: character-level bonus, equipped items,
     * active buffs. Writes into {@code dst} in place - no allocation beyond the MinMax
     * record per per-stat plus.
     *
     * <p>Called from {@link Mob#effectiveStats()} when {@link Mob#statsDirty} is set.
     * Don't invoke directly unless you want to bypass the cache.
     */
    public static void writeEffectiveStats(Mob mob, com.bjsp123.rl2.model.StatBlock dst) {
        MobStats.writeEffectiveStats(mob, dst);
    }

    /** Character-level contribution. Reads {@link Mob#characterLevel} and adds the
     *  level-up bonuses owned by {@link MobProgression}; v1 leaves this as a no-op until
     *  the per-level bonus table is moved here. The contributor is wired now so the
     *  rollup shape is final and no future signature changes are needed. */
    private static void characterLevelBonusInto(com.bjsp123.rl2.model.StatBlock dst, Mob mob) {
        // Intentionally empty - placeholder for future MobProgression migration.
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
        int hitDenom = atkAcc + tgtEva;
        boolean hit  = surprise || (hitDenom > 0 && RANDOM.nextInt(hitDenom) < atkAcc);

        if (!hit) {
            if (level.events != null) {
                level.events.add(new com.bjsp123.rl2.event.GameEvent.MobMeleeAttacked(
                        attacker, target, /*hit=*/false, /*dealt=*/0));
            }
            logAttackOutcome(level, attacker, target, 0, /*miss*/ true, /*killed*/ false);
            // Tuning log for the missed swing - rolled=0 collapses the body to "miss".
            emitDamageRollLog(level, attacker, target,
                    new DamageBreakdown(DamageElement.PHYSICAL, 0), 0);
            return;
        }

        // Regular melee damage is reduced by armour (floored at 0); AP damage is added on
        // top with no armour reduction. Both are rolled independently so a swing that
        // bounces off scale mail still lands its full AP component. PROTECTION mitigation
        // is now applied centrally inside processAttack (gated on PHYSICAL element).
        int rawAtk = rollRange(rawDamageRange(attacker));
        int armor  = rollRange(resistRange(target));
        int regular = Math.max(0, rawAtk - armor);
        int ap = rollRange(apDamageRange(attacker));
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
        // Knockback fires BEFORE the melee damage so even a killing blow
        // shows the shove - the slide animation queues sequentially and
        // the death-fade plays at the destination tile. A knockback
        // collision can itself kill the target via environmental damage;
        // we guard the subsequent melee damage so we don't double-kill.
        // Total knockback = stat-based (intrinsic + equipped weapon) plus
        // one square per level of the {@link com.bjsp123.rl2.model.Perk#KNOCKBACK}
        // perk, so a Warrior with KNOCKBACK 1 gets 1 sq even from a
        // weapon that itself has none.
        if (attacker.position != null) {
            int kb = attacker.effectiveStats().knockbackSquares;
            if (attacker.perks != null) {
                kb += attacker.perks.getOrDefault(
                        com.bjsp123.rl2.model.Perk.KNOCKBACK, 0);
            }
            if (kb > 0) knockBack(level, target, kb, attacker.position);
        }
        boolean killed;
        int dealt;
        if (target.hp <= 0) {
            killed = true;
            dealt  = 0;
        } else {
            double hpBefore = target.hp;
            killed = processAttack(level, attacker, target, rawDealt,
                    AttackType.MELEE, DamageElement.PHYSICAL, bk);
            dealt = Math.max(0, (int) Math.round(hpBefore - target.hp));
        }
        logAttackOutcome(level, attacker, target, dealt, /*miss*/ false, killed);
    }

    /**
     * Single entry point for any damage done to a mob. Every path - melee, thrown item, magic
     * missile, starvation or other environmental harm - routes through here so HP reduction,
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
     *                 like starvation; no XP or history is recorded in that case.
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
        return processAttack(level, attacker, target, rawDealt, type, element, null);
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
        if (target == null) return false;
        if (rawDealt < 0) rawDealt = 0;
        if (rawDealt > 0 && BuffSystem.hasBuff(target, com.bjsp123.rl2.model.Buff.BuffType.SHIELDED)) return false;
        // Buff-based mitigation. PROTECTION blunts physical, ANTI_MAGIC blunts magic
        // and fire. Other elements (POISON, SHOCK, STARVATION) ignore both buffs.
        int dealt = switch (element) {
            case PHYSICAL          -> BuffSystem.mitigatePhysicalDamage(target, rawDealt);
            case MAGIC, FIRE       -> BuffSystem.mitigateMagicDamage(target, rawDealt);
            case POISON, SHOCK, STARVATION -> rawDealt;
        };
        // BLUNT floater - emitted ahead of the HIT/MISS floater whenever a defensive
        // buff ate part of the hit, so the renderer plays the dim "blunt" first.
        if (dealt < rawDealt && rawDealt > 0 && level != null && level.events != null) {
            level.events.add(new com.bjsp123.rl2.event.GameEvent.DamageDealt(
                    target, dealt, com.bjsp123.rl2.event.GameEvent.DamageMessage.BLUNT, attacker));
        }
        // Damage-roll tuning log. Falls back to a default breakdown when the caller
        // didn't pre-populate one, so every processAttack call still produces a line.
        DamageBreakdown bk = breakdown != null ? breakdown : new DamageBreakdown(element, rawDealt);
        if (dealt < rawDealt && rawDealt > 0) {
            bk.add(element == DamageElement.PHYSICAL ? "PROTECTION" : "ANTI_MAGIC",
                    rawDealt - dealt);
        }
        emitDamageRollLog(level, attacker, target, bk, dealt);
        target.hp -= dealt;
        // PHASE ends the moment the mob takes or deals damage.
        if (dealt > 0) {
            BuffSystem.removeBuff(target,   com.bjsp123.rl2.model.Buff.BuffType.PHASE);
            BuffSystem.shortenFrozenOnDamage(target);
            if (attacker != null)
                BuffSystem.removeBuff(attacker, com.bjsp123.rl2.model.Buff.BuffType.PHASE);
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
            target.stateOfMind = Mob.StateOfMind.AWAKE;
            BuffSystem.removeBuff(target, com.bjsp123.rl2.model.Buff.BuffType.HIDING);
            if (target.behavior != Behavior.PLAYER && isVisibleToPlayer(level, target)) {
                EventLog.add(Messages.mobWakesUp(nameForLog(level, target),
                        "damaged by " + element.name().toLowerCase()));
            }
        }
        // Hostility from damage: a mob that takes real damage from an attacker promotes
        // that attacker into its attackTypes (and recordCombatMemory's reciprocal does the
        // same the other way). A miss leaves attitudes unchanged - only damaging blows
        // count, so a sparring kitten that scratches without breaking skin doesn't turn
        // the household feral. Environmental damage has no attacker and is skipped.
        if (dealt > 0 && attacker != null) {
            recordCombatMemory(level, attacker, target, "attacked");
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
                    attacker));
        }
        // Only flinch when real damage lands - a 0-damage blow doesn't visually stagger the
        // target. Environmental damage has no attacker and therefore no direction to recoil
        // from. Off-screen flinches are suppressed for the same reason as off-screen lunges:
        // no observer means no animation.
        if (dealt > 0 && attacker != null
                && (isVisibleToPlayer(level, attacker) || isVisibleToPlayer(level, target))) {
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
                && element == DamageElement.PHYSICAL) {
            int lvl = Math.max(1, attacker.characterLevel);
            BuffSystem.apply(level, target, com.bjsp123.rl2.model.Buff.BuffType.POISONED,
                    lvl, lvl * 3, attacker);
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
        // Brand on-hit elemental effect - fired when a melee blow lands, using
        // the attacker's equipped weapon brand (if any).
        if (dealt > 0 && attacker != null && attacker.inventory != null) {
            com.bjsp123.rl2.model.Item weapon = attacker.inventory.weapon;
            if (weapon != null && weapon.brand != null) {
                BrandSystem.applyBrandOnHit(level, attacker, target, weapon.brand);
            }
        }
        if (target.hp <= 0) {
            killMob(level, target, attacker);
            return true;
        }
        return false;
    }

    /** True if stepping from ({@code cx},{@code cy}) to ({@code nx},{@code ny}) brings
     *  the mover strictly closer to any visible terrifying mob. */
    private static boolean stepWouldApproachTerror(Mob mover, Level level,
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

    /** Legacy shim - kept callable so {@code MobSystem.attack} doesn't need a same-PR
     *  rewrite. The actual lunge animation is scheduled by rgame's {@code Animator}
     *  when it consumes the {@code MobMeleeAttacked} event. */
    public static void startMeleeLunge(Level level, Mob attacker, Mob target) {
        // Intentionally empty - Animator drives the visual.
    }

    /** Legacy shim - see {@link #startMeleeLunge}. {@code MobHitFlinched} is the event
     *  that triggers rgame's flinch animation. */
    public static void startHitFlinch(Level level, Mob target, Mob hitSource) {
        if (target == null || hitSource == null
                || target.position == null || hitSource.position == null) return;
        if (level != null && level.events != null) {
            level.events.add(new com.bjsp123.rl2.event.GameEvent.MobHitFlinched(target, hitSource));
        }
    }

    /**
     * Translate the raw attack result into the correct {@link Messages} template and push
     * it to {@link EventLog}. Three axes: which side is the player, hit/miss, killed-or-not.
     */
    private static void logAttackOutcome(Level level, Mob attacker, Mob target, int dmg, boolean miss, boolean killed) {
        boolean attackerIsPlayer = attacker.behavior == Behavior.PLAYER;
        boolean targetIsPlayer   = target.behavior   == Behavior.PLAYER;
        String atkName = nameForLog(level, attacker);
        String tgtName = nameForLog(level, target);

        if (attackerIsPlayer) {
            if (killed)      EventLog.add(Messages.playerKill(atkName, tgtName));
            else if (miss)   EventLog.add(Messages.playerMiss(atkName, tgtName));
            else             EventLog.add(Messages.playerHit (atkName, tgtName, dmg));
        } else if (targetIsPlayer) {
            if (killed)      EventLog.add(Messages.enemyKill(atkName, tgtName));
            else if (miss)   EventLog.add(Messages.enemyMiss(atkName, tgtName));
            else             EventLog.add(Messages.enemyHit (atkName, tgtName, dmg));
        } else {
            if (killed)      EventLog.add(Messages.mobKill(atkName, tgtName));
            else if (miss)   EventLog.add(Messages.mobMiss(atkName, tgtName));
            else             EventLog.add(Messages.mobHit (atkName, tgtName, dmg));
        }
    }

    /** Push the per-attack {@link DamageBreakdown} to the LOW-priority event log.
     *  Called from {@link #processAttack} after final {@code dealt} is known and from
     *  the melee-miss path before it returns. */
    static void emitDamageRollLog(Level level, Mob attacker, Mob target,
                                  DamageBreakdown bk, int dealt) {
        if (bk == null) return;
        String atk = attacker == null ? "(env)" : nameForLog(level, attacker);
        String tgt = target   == null ? "?"     : nameForLog(level, target);
        boolean playerInv = (attacker != null && attacker.behavior == Behavior.PLAYER)
                          || (target   != null && target  .behavior == Behavior.PLAYER);
        EventLog.add(Messages.damageRoll(atk, tgt, bk.element.name(),
                bk.rolled, dealt, bk.mitigations, playerInv));
    }

    /** Move every item under the mob's feet from the ground into its bag (until the bag is full).
     *  Returns the number of items actually picked up - callers use this to decide whether
     *  to charge a move tick. */
    public static int pickupAtFeet(Level level, Mob mob) {
        int x = mob.position.tileX(), y = mob.position.tileY();
        boolean isPlayer = mob.behavior == Behavior.PLAYER;
        String pickerName = mob.name != null ? mob.name : "?";
        int picked = 0;
        Iterator<Item> it = level.items.iterator();
        while (it.hasNext()) {
            Item item = it.next();
            if (item.location == null) continue;
            if (item.location.tileX() != x || item.location.tileY() != y) continue;
            // POWERUP items are player-only - they're consumed on touch
            // by the player's own onMobEnteredTile path, never picked up
            // into anyone's bag. Non-player mobs leave them untouched.
            if (item.useBehavior == com.bjsp123.rl2.model.Item.UseBehavior.POWERUP) {
                continue;
            }
            // Snapshot the floor position BEFORE addToBag clears it, so the
            // ItemPickedUp event below can carry the source tile for the
            // arc-toward-bottom-right animation.
            Point fromTile = item.location;
            if (!InventorySystem.addToBag(mob.inventory, item)) break;
            item.location = null;
            it.remove();
            picked++;
            if (isPlayer) {
                EventLog.add(Messages.pickupItem(pickerName, item.name));
            } else if (item.name != null && isVisibleToPlayer(level, mob)) {
                EventLog.add(Messages.mobPicksUpItem(nameForLog(level, mob), item.name));
            }
            if (mob.history != null) {
                mob.history.add(com.bjsp123.rl2.model.HistoricalRecord.itemFound(
                        level.currentTurn, level.depth, item.name));
            }
            if (level.events != null) {
                level.events.add(
                        new com.bjsp123.rl2.event.GameEvent.ItemPickedUp(mob, item, fromTile));
            }
        }
        return picked;
    }

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
            igniteDisc(level, mob.position.tileX(), mob.position.tileY(), r);
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
            // KILLER perk: every kill grants the killer the KILLER buff (-20% attack /
            // move cost) for 10 standard turns.
            if (killer.perks != null
                    && killer.perks.getOrDefault(com.bjsp123.rl2.model.Perk.KILLER, 0) > 0) {
                BuffSystem.apply(level, killer,
                        com.bjsp123.rl2.model.Buff.BuffType.KILLER, 1, 10, killer);
            }
            if (killer.history != null) {
                String victimName = mob.name != null ? mob.name : "?";
                killer.history.add(com.bjsp123.rl2.model.HistoricalRecord.kill(
                        level.currentTurn, level.depth, victimName));
            }
        }

        // Synchronously remove the mob from level.mobs. The visual flicker/fade plays
        // out against a snapshot held by rgame's Animator (the MobKilled event carries
        // position + visibility, and the Animator records a "ghost" entry from those).
        // Game logic (mobAt, pathfinding, targeting, attitude) sees a clean world
        // immediately because the mob is no longer in the list.
        boolean visible = isVisibleToPlayer(level, mob)
                || (killer != null && isVisibleToPlayer(level, killer));
        if (level.events != null && mob.position != null) {
            level.events.add(new com.bjsp123.rl2.event.GameEvent.MobKilled(
                    mob, killer, mob.position.tileX(), mob.position.tileY(), visible));
        }
        level.mobs.remove(mob);
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
        knockBackInternal(level, mob, numSquares, from, 0);
    }

    private static void knockBackInternal(Level level, Mob mob, int numSquares,
                                          Point from, int depth) {
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
                processAttack(level, null, mob, remaining * 4,
                        AttackType.ENVIRONMENTAL, DamageElement.PHYSICAL);
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
                processAttack(level, null, mob, remaining * 4,
                        AttackType.ENVIRONMENTAL, DamageElement.PHYSICAL);
                return;
            }

            Mob collided = MobQueries.mobAt(level, new Point(nx, ny));
            if (collided != null) {
                mob.position = new Point(cx, cy);
                emitKnockBack(level, mob, start, true);
                processAttack(level, null, mob, remaining * 4,
                        AttackType.ENVIRONMENTAL, DamageElement.PHYSICAL);
                if (collided.hp > 0) {
                    processAttack(level, null, collided, remaining * 4,
                            AttackType.ENVIRONMENTAL, DamageElement.PHYSICAL);
                    if (collided.hp > 0) {
                        knockBackInternal(level, collided, remaining, mob.position, depth + 1);
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
    public static void fallToNextLevel(Level level, Mob mob) {
        if (level == null || mob == null) return;
        com.bjsp123.rl2.model.World world = level.world;
        int srcIdx = -1;
        Level next = null;
        Point arrival = null;
        if (world != null && world.levels != null) {
            for (int i = 0; i < world.levels.length; i++) {
                if (world.levels[i] == level) { srcIdx = i; break; }
            }
            int target = level.stairsDownTarget;
            if (target >= 0 && target < world.levels.length) {
                next = world.levels[target];
                if (next != null && srcIdx >= 0) {
                    arrival = com.bjsp123.rl2.model.WorldTopology
                            .arrivalPointFrom(next, srcIdx, true);
                }
            }
        }

        Point fromPos = mob.position;
        int dmg = Math.max(1, (int) Math.round(mob.effectiveStats().maxHp * 0.5));
        boolean wouldKill = next == null || arrival == null
                || mob.hp - dmg <= 0;

        // Revolve-shrink-fade visual at the source tile - same shape as
        // a falling item, but driven by the mob's sprite.
        if (level.events != null) {
            level.events.add(new com.bjsp123.rl2.event.GameEvent.MobFellThroughChasm(
                    mob, fromPos));
        }

        if (wouldKill) {
            // Items into the chasm, mob dies on impact.
            emitFallingItems(level, mob);
            processAttack(level, null, mob, dmg,
                    AttackType.ENVIRONMENTAL, DamageElement.PHYSICAL);
            return;
        }

        // Survivor - relocate to the next level + apply impact damage there.
        level.mobs.remove(mob);
        mob.position = arrival;
        mob.targetPosition = null;
        next.mobs.add(mob);
        if (mob.behavior == Mob.Behavior.PLAYER) {
            world.currentLevelIndex = srcIdx >= 0
                    ? indexOf(world, next) : world.currentLevelIndex;
            next.visited = true;
        }
        processAttack(next, null, mob, dmg,
                AttackType.ENVIRONMENTAL, DamageElement.PHYSICAL);
    }

    /** Index of {@code lvl} in {@code world.levels}, or -1 if not found. */
    private static int indexOf(com.bjsp123.rl2.model.World world, Level lvl) {
        if (world == null || world.levels == null) return -1;
        for (int i = 0; i < world.levels.length; i++) {
            if (world.levels[i] == lvl) return i;
        }
        return -1;
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
        for (Item item : falling) {
            level.events.add(new com.bjsp123.rl2.event.GameEvent.ItemFallingIntoChasm(
                    item, mob.position));
        }
    }

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
        // wake radius. INANIMATE mobs use the inverse perspective (wake when a
        // hostile is incoming) since their own attackTypes is empty.
        if (mob.stateOfMind == StateOfMind.ASLEEP) {
            boolean wakeUp = inanimate
                    ? hasIncomingAttackerWithin(mob, level, mob.effectiveStats().wakeRadius)
                    : hasAttitudeTargetWithin(mob, level, mob.effectiveStats().wakeRadius);
            if (wakeUp) {
                mob.stateOfMind = StateOfMind.AWAKE;
                if (isVisibleToPlayer(level, mob)) {
                    EventLog.add(Messages.mobWakesUp(nameForLog(level, mob),
                            "sensing something nearby"));
                }
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
        if (tryUseInventoryItem(mob, level)) {
            mob.intent = Mob.Intent.USING_ITEM;
            return;
        }

        if (mob.behavior == Behavior.MOB) {
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
    private static boolean tryCastAbilities(Mob caster, Level level) {
        if (caster == null || caster.abilities == null || caster.abilities.isEmpty()) return false;
        double vision = caster.effectiveStats().visionRadius;
        for (Mob.MobAbility ab : caster.abilities) {
            if (ab == null) continue;
            if (ab.cooldownTracker != null
                    && BuffSystem.hasBuff(caster, ab.cooldownTracker)) continue;
            Mob target = pickAbilityTarget(caster, level, ab, vision);
            if (target == null) continue;
            if (isVisibleToPlayer(level, caster) || isVisibleToPlayer(level, target)) {
                String abilityDesc = ab.kind == Mob.MobAbility.AbilityKind.HEAL
                        ? "a healing ability"
                        : ab.kind == Mob.MobAbility.AbilityKind.TELEPORT
                        ? "a teleport ability"
                        : (ab.applies != null
                                ? "a " + ab.applies.name().toLowerCase() + " ability"
                                : "an ability");
                boolean inv = caster.behavior == Behavior.PLAYER
                        || target.behavior == Behavior.PLAYER;
                EventLog.add(Messages.mobUsesAbility(nameForLog(level, caster),
                        abilityDesc, nameForLog(level, target), inv));
            }
            switch (ab.kind) {
                case BUFF -> {
                    if (level.events != null) {
                        level.events.add(new com.bjsp123.rl2.event.GameEvent.MobAbilityUsed(
                                caster, target, caster.position, target.position));
                    }
                    BuffSystem.apply(level, target, ab.applies,
                            Math.max(1, ab.appliedLevel),
                            Math.max(1, ab.appliedDuration), caster);
                }
                case HEAL -> {
                    if (level.events != null) {
                        level.events.add(new com.bjsp123.rl2.event.GameEvent.MobAbilityUsed(
                                caster, target, caster.position, target.position));
                    }
                    heal(level, target, ab.healAmount);
                }
                case TELEPORT -> {
                    // tryTeleportToTarget handles LOS, free-tile, and the
                    // visual event; if it bails the ability is wasted, but
                    // still costs the turn so the caster doesn't loop forever.
                    tryTeleportToTarget(level, caster, target);
                }
            }
            if (ab.cooldownTracker != null && ab.cooldownTurns > 0) {
                BuffSystem.apply(level, caster, ab.cooldownTracker, 1,
                        ab.cooldownTurns, caster);
            }
            TurnSystem.applyActionCost(caster, caster.effectiveStats().attackCost);
            return true;
        }
        return false;
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
                    if (getAttitudeToMob(caster, m) != Attitude.ALLY) continue;
                    if (BuffSystem.hasBuff(m, ab.applies)) continue;
                }
                case HEAL -> {
                    if (getAttitudeToMob(caster, m) != Attitude.ALLY) continue;
                    if (m.hp >= m.effectiveStats().maxHp) continue;
                }
                case TELEPORT -> {
                    if (getAttitudeToMob(caster, m) != Attitude.ATTACK) continue;
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

    // ========================================================================
    // AI ITEM USE - wands, potions, bombs in a mob's inventory
    // ========================================================================

    /** Per-item probability a mob's AI rolls each turn that it'll actually use
     *  the item (after the safety / utility gate). 50% gives the rogue a
     *  steady drumbeat of bomb throws while letting them mix in melee. */
    private static final double AI_USE_ITEM_CHANCE = 0.5;
    /** Max Chebyshev distance for an AI bomb throw. Mirrors the rough range
     *  the player uses in look mode for thrown items. */
    private static final int AI_BOMB_THROW_RANGE = 6;

    /**
     * Run the AI item-use heuristic for {@code mob}: walk the bag, find the
     * first item that's both usable and won't harm self/allies, roll
     * {@link #AI_USE_ITEM_CHANCE}, and on success apply it. The use consumes
     * the mob's turn - caller short-circuits.
     */
    private static boolean tryUseInventoryItem(Mob mob, Level level) {
        if (mob == null || mob.inventory == null) return false;
        if (mob.inventory.bag == null || mob.inventory.bag.isEmpty()) return false;
        // Ranged-behavior mobs always use a usable item if the safety gate clears -
        // otherwise a kobold mage with a wand of magic missile would idle half its
        // turns next to a melee enemy. Other mobs roll AI_USE_ITEM_CHANCE.
        boolean alwaysUse = mob.behavior == Behavior.RANGED_MOB_DUMB
                         || mob.behavior == Behavior.RANGED_MOB_STANDOFF;
        // Snapshot the bag so applyAiItemUse can mutate it (heal potions /
        // bombs are removed on use).
        java.util.List<com.bjsp123.rl2.model.Item> snapshot =
                new java.util.ArrayList<>(mob.inventory.bag);
        for (com.bjsp123.rl2.model.Item item : snapshot) {
            if (!isUsableByAi(mob, item, level)) continue;
            if (!alwaysUse && RANDOM.nextDouble() >= AI_USE_ITEM_CHANCE) continue;
            if (applyAiItemUse(mob, item, level)) return true;
        }
        return false;
    }

    private static boolean isUsableByAi(Mob mob, com.bjsp123.rl2.model.Item item, Level level) {
        if (item == null) return false;
        if (item.inventoryCategory == com.bjsp123.rl2.model.Item.InventoryCategory.BOMB) {
            return canThrowBombAtSomeone(mob, item, level);
        }
        if (item.useBehavior == null
                || item.useBehavior == com.bjsp123.rl2.model.Item.UseBehavior.NONE) return false;
        return switch (item.useBehavior) {
            case DRINK, APPLYBUFF -> wouldDrinkHelp(mob, item);
            case WAND    -> isWandUsableByAi(mob, item, level);
            case GRAPPLE -> isGrappleUsableByAi(mob, item, level);
            case JUMP    -> isJumpUsableByAi(mob, item, level);
            case EAT, GRANT_PERK, POWERUP, NONE -> false;
        };
    }

    /** Heuristic for {@code DRINK} potions - only quaff if it'll actually help.
     *  Damaging potions (non-zero {@code item.damage}, e.g. potion of poison)
     *  are never drunk; buff potions are useful when the buff isn't already
     *  up; the REGENERATION buff additionally requires the mob to actually
     *  be wounded (drinking a healing potion at full HP wastes the potion). */
    private static boolean wouldDrinkHelp(Mob mob, com.bjsp123.rl2.model.Item item) {
        if (item == null) return false;
        if (item.damage.max() > 0) return false;
        com.bjsp123.rl2.model.Buff.BuffType primary = item.primaryBuff();
        if (primary == null) return false;
        if (BuffSystem.hasBuff(mob, primary)) return false;
        if (primary == com.bjsp123.rl2.model.Buff.BuffType.REGENERATION) {
            return mob.hp < mob.effectiveStats().maxHp;
        }
        return true;
    }

    private static boolean isGrappleUsableByAi(Mob mob, com.bjsp123.rl2.model.Item item, Level level) {
        if (mob.position == null) return false;
        Mob target = nearestAttackTarget(mob, level);
        if (target == null || target.position == null) return false;
        // No point grappling when already adjacent
        if (LevelFactoryUtils.chebyshev(mob.position, target.position) <= 1) return false;
        int maxSize = Math.max(0, (int) item.abilityPower);
        return target.effectiveStats().size <= maxSize;
    }

    private static boolean isJumpUsableByAi(Mob mob, com.bjsp123.rl2.model.Item item, Level level) {
        if (mob.position == null) return false;
        Mob threat = nearestAttackTarget(mob, level);
        if (threat == null || threat.position == null) return false;
        return pickBestJumpTile(mob, item, level, threat) != null;
    }

    /** Element-wand AI gate. Only single-target / ally-creating elements pass
     *  in this first cut - element wands with AOE / friendly-fire risk
     *  (water, oil, grass, fungus, fire, detonation, banishment) are deferred. */
    private static boolean isWandUsableByAi(Mob mob, com.bjsp123.rl2.model.Item wand, Level level) {
        if (wand.wandEffect == com.bjsp123.rl2.model.Item.ItemEffect.MISSILE) {
            Mob target = nearestAttackTarget(mob, level);
            return target != null;
        }
        if (wand.wandEffect == com.bjsp123.rl2.model.Item.ItemEffect.TELEPORT) {
            return nearestAttackTarget(mob, level) != null
                    && pickTeleportDestination(mob, level) != null;
        }
        if (wand.summonsWhenUsed != null) {
            return MobQueries.levelHasRoomForSpawn(level)
                    && MobHooks.freeAdjacentFloor(level, mob.position) != null;
        }
        return false;
    }

    /** True iff the mob has at least one hostile in throw range and no ally
     *  inside the bomb's AOE around that target. */
    private static boolean canThrowBombAtSomeone(Mob thrower, com.bjsp123.rl2.model.Item bomb, Level level) {
        Mob target = nearestAttackTarget(thrower, level);
        if (target == null || target.position == null) return false;
        int d = Math.max(Math.abs(target.position.tileX() - thrower.position.tileX()),
                         Math.abs(target.position.tileY() - thrower.position.tileY()));
        if (d > AI_BOMB_THROW_RANGE) return false;
        return !allyInBombAoe(thrower, target.position, bomb, level);
    }

    /** Walk the bomb's effect disc around {@code centre}; return true the
     *  moment any tile holds a mob the thrower considers ALLY. */
    private static boolean allyInBombAoe(Mob thrower, com.bjsp123.rl2.model.Point centre,
                                         com.bjsp123.rl2.model.Item bomb, Level level) {
        int radius = ItemStats.effectiveTilesAffected(bomb);
        int r2 = radius * radius;
        int cx = centre.tileX(), cy = centre.tileY();
        for (Mob m : level.mobs) {
            if (m == thrower || m.hp <= 0 || m.position == null) continue;
            int dx = m.position.tileX() - cx;
            int dy = m.position.tileY() - cy;
            if (dx * dx + dy * dy > r2) continue;
            if (getAttitudeToMob(thrower, m) == Attitude.ALLY) return true;
        }
        return false;
    }

    /** Apply the chosen AI item-use. Returns true only if the use actually
     *  charged time; depleted or invalid items fall through to later AI choices. */
    private static boolean applyAiItemUse(Mob mob, com.bjsp123.rl2.model.Item item, Level level) {
        int before = mob.ticksTillMove;
        if (item.inventoryCategory == com.bjsp123.rl2.model.Item.InventoryCategory.BOMB) {
            Mob target = nearestAttackTarget(mob, level);
            if (target != null) {
                throwItem(level, mob, item, target.position);
            }
            return mob.ticksTillMove != before;
        }
        switch (item.useBehavior) {
            case DRINK, APPLYBUFF -> ItemSystem.useItem(level, mob, item);
            case WAND    -> { if (!aiCastWand(mob, item, level)) return false; }
            case GRAPPLE -> { if (!aiCastGrapple(mob, item, level)) return false; }
            case JUMP    -> { if (!aiCastJump(mob, item, level)) return false; }
            default      -> { /* unreachable per the gate */ }
        }
        return mob.ticksTillMove != before;
    }

    /** AI wand cast. Picks a target (no targeting UI) and delegates to the shared
     *  {@link ItemSystem#fireWand} entry point so trajectory clipping, event
     *  emission, and move cost stay identical between player and AI. Summon
     *  wands ignore target. Tile-targeting element wands (water/oil/fire/etc.)
     *  are deferred until friendly-fire AOE checks land. */
    private static boolean aiCastWand(Mob caster, com.bjsp123.rl2.model.Item wand, Level level) {
        int before = caster.ticksTillMove;
        if (wand.summonsWhenUsed != null) {
            ItemSystem.fireWand(level, caster, wand, null);
            return caster.ticksTillMove != before;
        }
        if (wand.wandEffect == com.bjsp123.rl2.model.Item.ItemEffect.MISSILE) {
            Mob target = nearestAttackTarget(caster, level);
            if (target == null) return false;
            ItemSystem.fireWand(level, caster, wand, target.position);
            return caster.ticksTillMove != before;
        }
        if (wand.wandEffect == com.bjsp123.rl2.model.Item.ItemEffect.TELEPORT) {
            com.bjsp123.rl2.model.Point dest = pickTeleportDestination(caster, level);
            if (dest != null) ItemSystem.fireWand(level, caster, wand, dest);
        }
        return caster.ticksTillMove != before;
    }

    private static boolean aiCastGrapple(Mob mob, com.bjsp123.rl2.model.Item item, Level level) {
        int before = mob.ticksTillMove;
        Mob target = nearestAttackTarget(mob, level);
        if (target == null || target.position == null) return false;
        // Grapple is only useful against the player or targets with a ranged attack.
        if (target.behavior != Mob.Behavior.PLAYER
                && target.effectiveStats().rangedDamage.max() <= 0) return false;
        ItemSystem.castGrapple(level, mob, item, target.position);
        return mob.ticksTillMove != before;
    }

    private static boolean aiCastJump(Mob mob, com.bjsp123.rl2.model.Item item, Level level) {
        int before = mob.ticksTillMove;
        Mob threat = nearestAttackTarget(mob, level);
        if (threat == null) return false;
        com.bjsp123.rl2.model.Point dest = pickBestJumpTile(mob, item, level, threat);
        if (dest != null) ItemSystem.castJump(level, mob, item, dest);
        return mob.ticksTillMove != before;
    }

    /** Find the tile in JUMP radius that maximises Chebyshev distance from {@code threat}.
     *  Returns null when no reachable tile improves on the mob's current distance. */
    private static com.bjsp123.rl2.model.Point pickBestJumpTile(
            Mob mob, com.bjsp123.rl2.model.Item item, Level level, Mob threat) {
        if (mob.position == null || threat.position == null) return null;
        int radius = Math.max(0, (int) item.abilityPower);
        int mx = mob.position.tileX(), my = mob.position.tileY();
        int tx = threat.position.tileX(), ty = threat.position.tileY();
        int currentDist = LevelFactoryUtils.chebyshev(mob.position, threat.position);
        com.bjsp123.rl2.model.Point best = null;
        int bestDist = currentDist;
        for (int dy = -radius; dy <= radius; dy++) {
            for (int dx = -radius; dx <= radius; dx++) {
                if (dx == 0 && dy == 0) continue;
                int nx = mx + dx, ny = my + dy;
                if (nx < 0 || ny < 0 || nx >= level.width || ny >= level.height) continue;
                if (level.tiles[nx][ny].blocksMovement()) continue;
                com.bjsp123.rl2.model.Point p = new com.bjsp123.rl2.model.Point(nx, ny);
                if (MobQueries.mobAt(level, p) != null) continue;
                int d = Math.max(Math.abs(nx - tx), Math.abs(ny - ty));
                if (d > bestDist) { bestDist = d; best = p; }
            }
        }
        return best;
    }

    /** Pick a random free floor tile farther from the nearest threat than the mob's
     *  current position. Returns null when no improvement is possible. */
    private static com.bjsp123.rl2.model.Point pickTeleportDestination(Mob mob, Level level) {
        if (mob.position == null) return null;
        Mob threat = nearestAttackTarget(mob, level);
        int currentDist = threat != null
                ? LevelFactoryUtils.chebyshev(mob.position, threat.position) : 0;
        java.util.List<com.bjsp123.rl2.model.Point> candidates = new java.util.ArrayList<>();
        for (int y = 0; y < level.height; y++) {
            for (int x = 0; x < level.width; x++) {
                if (level.tiles[x][y].blocksMovement()) continue;
                com.bjsp123.rl2.model.Point p = new com.bjsp123.rl2.model.Point(x, y);
                if (MobQueries.mobAt(level, p) != null) continue;
                if (threat != null
                        && LevelFactoryUtils.chebyshev(p, threat.position) <= currentDist) continue;
                candidates.add(p);
            }
        }
        if (candidates.isEmpty()) return null;
        return candidates.get(RANDOM.nextInt(candidates.size()));
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
        if (mob.stateOfMind == StateOfMind.ASLEEP) {
            if (hasAttitudeTargetWithin(mob, level, mob.effectiveStats().wakeRadius)) {
                mob.stateOfMind = StateOfMind.AWAKE;
            } else {
                mob.intent = Mob.Intent.IDLE;
                TurnSystem.applyMoveCost(mob, mob.effectiveStats().moveCost);
                return;
            }
        }
        Mob.Intent prevIntent = mob.intent;
        Point fleeAway = fleeTargetFor(mob, level);
        if (fleeAway != null) {
            mob.intent = Mob.Intent.FLEEING;
            mob.targetPosition = fleeAway;
            stepOrIdle(mob, level);
            return;
        }
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
        if (isChaseCarryover(prevIntent, mob)) {
            mob.intent = Mob.Intent.CHASING_LAST_KNOWN;
            stepOrIdle(mob, level);
            return;
        }
        if (tryFollowLeader(mob, level)) {
            mob.intent = Mob.Intent.FOLLOWING_LEADER;
            return;
        }
        if (mob.targetPosition == null) {
            mob.targetPosition = randomFloorPoint(level);
        }
        mob.intent = Mob.Intent.WANDERING;
        stepOrIdle(mob, level);
    }

    /**
     * RANGED_MOB_STANDOFF AI - kite the target. If the mob is within
     * {@link #STANDOFF_BUBBLE_TILES} tiles of an attack target it tries to back away
     * (the AI sets a target tile farther from the enemy and steps toward it). Otherwise
     * it tries the same ranged-shot path as {@link #processRangedMobDumbAi}; failing
     * that, it closes to range like the dumb variant.
     */
    private static void processRangedMobStandoffAi(Mob mob, Level level) {
        if (mob.stateOfMind == StateOfMind.ASLEEP) {
            if (hasAttitudeTargetWithin(mob, level, mob.effectiveStats().wakeRadius)) {
                mob.stateOfMind = StateOfMind.AWAKE;
            } else {
                mob.intent = Mob.Intent.IDLE;
                TurnSystem.applyMoveCost(mob, mob.effectiveStats().moveCost);
                return;
            }
        }
        Mob.Intent prevIntent = mob.intent;
        Point fleeAway = fleeTargetFor(mob, level);
        if (fleeAway != null) {
            mob.intent = Mob.Intent.FLEEING;
            mob.targetPosition = fleeAway;
            stepOrIdle(mob, level);
            return;
        }
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
        if (isChaseCarryover(prevIntent, mob)) {
            mob.intent = Mob.Intent.CHASING_LAST_KNOWN;
            stepOrIdle(mob, level);
            return;
        }
        if (tryFollowLeader(mob, level)) {
            mob.intent = Mob.Intent.FOLLOWING_LEADER;
            return;
        }
        if (mob.targetPosition == null) {
            mob.targetPosition = randomFloorPoint(level);
        }
        mob.intent = Mob.Intent.WANDERING;
        stepOrIdle(mob, level);
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
        // Cooldown gate - present-RANGED_COOLDOWN-buff means "still recharging".
        if (BuffSystem.hasBuff(shooter, com.bjsp123.rl2.model.Buff.BuffType.RANGED_COOLDOWN)) {
            return false;
        }
        // Prefer melee when adjacent — only ranged-only mobs fire point-blank.
        if (cheb == 1 && ss.damage.max() > 0) return false;
        int dmg = rollRange(ss.rangedDamage);
        com.bjsp123.rl2.model.Mob.RangedDamageType rdt = shooter.rangedDamageType != null
                ? shooter.rangedDamageType
                : com.bjsp123.rl2.model.Mob.RangedDamageType.MAGIC;
        DamageElement rangedElement = rdt == com.bjsp123.rl2.model.Mob.RangedDamageType.PHYSICAL
                ? DamageElement.PHYSICAL : DamageElement.MAGIC;
        if (level.events != null) {
            // Clip to the first mob in the way so the missile resolves on whoever
            // it actually hits visually rather than passing through them.
            Point impact = firstMobBlocking(level, shooter.position, target.position, shooter);
            boolean trajectoryVisible = trajectoryTouchesVisible(level, shooter.position, impact);
            level.events.add(new com.bjsp123.rl2.event.GameEvent.MagicMissileFired(
                    shooter, shooter.position, impact, dmg, trajectoryVisible));
        }
        int cooldownTurns = Math.max(0, ss.rangedRateOfFire - 1);
        if (cooldownTurns > 0) {
            BuffSystem.apply(level, shooter,
                    com.bjsp123.rl2.model.Buff.BuffType.RANGED_COOLDOWN, /*level=*/1,
                    cooldownTurns, shooter);
        }
        TurnSystem.applyActionCost(shooter, ss.rangedCost > 0 ? ss.rangedCost : ss.attackCost);
        // Combat memory is recorded on impact (in processAttack) when the missile actually
        // damages the target, not at the moment of firing.
        return true;
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
        // Wake gate: ASLEEP mobs only stir when anyone they care about (attack or flee)
        // enters their wake radius. Before, we only checked the player; now any mob this
        // one has an attitude toward counts, so dogs wake the cat sleeping in the next room.
        if (mob.stateOfMind == StateOfMind.ASLEEP) {
            if (hasAttitudeTargetWithin(mob, level, mob.effectiveStats().wakeRadius)) {
                mob.stateOfMind = StateOfMind.AWAKE;
            } else {
                mob.intent = Mob.Intent.IDLE;
                TurnSystem.applyMoveCost(mob, mob.effectiveStats().moveCost);
                return;
            }
        }

        Mob.Intent prevIntent = mob.intent;

        Point fleeAway = fleeTargetFor(mob, level);
        if (fleeAway != null) {
            mob.intent = Mob.Intent.FLEEING;
            mob.targetPosition = fleeAway;
            stepOrIdle(mob, level);
            return;
        }
        Mob attackTarget = nearestAttackTarget(mob, level);
        if (attackTarget != null) {
            mob.intent = Mob.Intent.PURSUING;
            mob.targetPosition = attackTarget.position;
            stepOrIdle(mob, level);
            return;
        }
        // Promotion: was pursuing last tick, target is no longer visible, but the
        // remembered tile is still ahead. Keep walking toward it; we'll drop to
        // wander once we arrive (or back to PURSUING if the target reappears).
        if (isChaseCarryover(prevIntent, mob)) {
            mob.intent = Mob.Intent.CHASING_LAST_KNOWN;
            stepOrIdle(mob, level);
            return;
        }
        if (tryFollowLeader(mob, level)) {
            mob.intent = Mob.Intent.FOLLOWING_LEADER;
            return;
        }
        if (mob.targetPosition == null) {
            mob.targetPosition = randomFloorPoint(level);
        }
        mob.intent = Mob.Intent.WANDERING;
        stepOrIdle(mob, level);
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

    /** How many turns a just-hidden {@link Behavior#EXPLORE_HIDE} mob stays put. */
    private static final int HIDING_TURN_COUNT = 5;

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
                    /*level=*/1, HIDING_TURN_COUNT, mob);
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
        if (isChaseCarryover(prevIntent, mob)) {
            mob.intent = Mob.Intent.CHASING_LAST_KNOWN;
            stepAndApplyPostMoveEffects(mob, level);
            return;
        }
        if (tryFollowLeader(mob, level)) {
            mob.intent = Mob.Intent.FOLLOWING_LEADER;
            return;
        }
        if (mob.targetPosition == null) {
            mob.targetPosition = randomFloorPoint(level);
        }
        mob.intent = Mob.Intent.WANDERING;
        stepAndApplyPostMoveEffects(mob, level);
    }

    /**
     * AI for {@link Behavior#HUNTER} mobs. Same attitude-driven target selection as
     * {@link #processMobAi} (FLEE -> run away, else ATTACK -> chase, else wander) minus the
     * wake-on-sight gate - predators are active by default.
     */
    private static void processHunterAi(Mob mob, Level level) {
        Mob.Intent prevIntent = mob.intent;
        Point fleeAway = fleeTargetFor(mob, level);
        if (fleeAway != null) {
            mob.intent = Mob.Intent.FLEEING;
            mob.targetPosition = fleeAway;
            stepOrIdle(mob, level);
            return;
        }
        Mob target = nearestAttackTarget(mob, level);
        if (target != null) {
            mob.intent = Mob.Intent.PURSUING;
            mob.targetPosition = target.position;
            stepOrIdle(mob, level);
            return;
        }
        if (isChaseCarryover(prevIntent, mob)) {
            mob.intent = Mob.Intent.CHASING_LAST_KNOWN;
            stepOrIdle(mob, level);
            return;
        }
        if (tryFollowLeader(mob, level)) {
            mob.intent = Mob.Intent.FOLLOWING_LEADER;
            return;
        }
        if (mob.targetPosition == null) {
            mob.targetPosition = randomFloorPoint(level);
        }
        mob.intent = Mob.Intent.WANDERING;
        stepOrIdle(mob, level);
    }

    /**
     * True when any mob this one has an attitude toward (attack or flee) sits within
     * {@code radius} (Chebyshev). Used as the wake-up gate for ASLEEP mobs.
     */
    private static boolean hasAttitudeTargetWithin(Mob mob, Level level, double radius) {
        int mx = mob.position.tileX(), my = mob.position.tileY();
        for (Mob m : level.mobs) {
            if (m == mob) continue;
            if (getAttitudeToMob(mob, m) == Attitude.NOTHING) continue;
            if (BuffSystem.hasBuff(m, com.bjsp123.rl2.model.Buff.BuffType.INVISIBLE)
                    || BuffSystem.hasBuff(m, com.bjsp123.rl2.model.Buff.BuffType.PHASE)) continue;
            int d = Math.max(Math.abs(m.position.tileX() - mx),
                             Math.abs(m.position.tileY() - my));
            // STEALTH perk halves enemy wake / vision radius when checking against the
            // perked player. Round down so a 4-radius wake becomes 2.
            double effRadius = radius;
            if (m.perks != null
                    && m.perks.getOrDefault(com.bjsp123.rl2.model.Perk.STEALTH, 0) > 0) {
                effRadius = radius / 2.0;
            }
            if (d <= effRadius) return true;
        }
        return false;
    }

    /** True iff any other mob within {@code radius} (Chebyshev) has ATTACK
     *  attitude toward {@code target}. Used as the wake gate for INANIMATE mobs
     *  (anthills) - their own attackTypes is empty, so the regular wake gate
     *  never fires; this checks "is something coming for me" instead. STEALTH
     *  perk applies the same halved-radius rule as the regular wake gate. */
    private static boolean hasIncomingAttackerWithin(Mob target, Level level, double radius) {
        int tx = target.position.tileX(), ty = target.position.tileY();
        for (Mob m : level.mobs) {
            if (m == target) continue;
            if (getAttitudeToMob(m, target) != Attitude.ATTACK) continue;
            if (BuffSystem.hasBuff(m, com.bjsp123.rl2.model.Buff.BuffType.INVISIBLE)
                    || BuffSystem.hasBuff(m, com.bjsp123.rl2.model.Buff.BuffType.PHASE)) continue;
            int d = Math.max(Math.abs(m.position.tileX() - tx),
                             Math.abs(m.position.tileY() - ty));
            double effRadius = radius;
            if (m.perks != null
                    && m.perks.getOrDefault(com.bjsp123.rl2.model.Perk.STEALTH, 0) > 0) {
                effRadius = radius / 2.0;
            }
            if (d <= effRadius) return true;
        }
        return false;
    }

    /** Nearest mob this one wants to ATTACK that is within vision radius (Chebyshev) and,
     *  when {@link Mob#targetRequiresSight} is true, was visible at the start of the turn. */
    private static Mob nearestAttackTarget(Mob self, Level level) {
        int sx = self.position.tileX(), sy = self.position.tileY();
        Mob best = null;
        int bestD = Integer.MAX_VALUE;
        double baseVision = self.effectiveStats().visionRadius;

        boolean useLos = self.targetRequiresSight && self.visibleMobsAtTurnStart != null;
        if (useLos) {
            // Fast path: only examine mobs already known to be in LOS
            for (Mob m : self.visibleMobsAtTurnStart) {
                if (m == self || m.hp <= 0) continue;
                if (getAttitudeToMob(self, m) != Attitude.ATTACK) continue;
                int d = Math.max(Math.abs(m.position.tileX() - sx),
                                 Math.abs(m.position.tileY() - sy));
                double vision = (m.perks != null
                        && m.perks.getOrDefault(com.bjsp123.rl2.model.Perk.STEALTH, 0) > 0)
                        ? baseVision / 2.0 : baseVision;
                if (d > vision) continue;
                if (d < bestD) { bestD = d; best = m; }
            }
            return best;
        }

        // Fallback: Chebyshev-only scan (targetRequiresSight=false or no snapshot yet)
        for (Mob m : level.mobs) {
            if (m == self) continue;
            if (getAttitudeToMob(self, m) != Attitude.ATTACK) continue;
            int d = Math.max(Math.abs(m.position.tileX() - sx),
                             Math.abs(m.position.tileY() - sy));
            double vision = (m.perks != null
                    && m.perks.getOrDefault(com.bjsp123.rl2.model.Perk.STEALTH, 0) > 0)
                    ? baseVision / 2.0 : baseVision;
            if (d > vision) continue;
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
            if (getAttitudeToMob(self, m) != Attitude.FLEE) continue;
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
        stepTowardTarget(mob, level);
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
                && RANDOM.nextDouble() < mob.effectiveStats().mushroomEatSpawnChance) {
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

    /**
     * Thrower hurls {@code it} toward {@code dst}. Removes the item from the thrower's
     * inventory, applies damage if the item has {@link ItemEffect#DAMAGE} and a hostile
     * mob occupies the target tile, drops the item on the target tile (unless it's chasm),
     * spawns the flying-item visual, and charges the thrower an attack's worth of time.
     */
    /** Player / AI throw entry point. Removes the item from inventory and
     *  emits the {@link com.bjsp123.rl2.event.GameEvent.ItemThrown}
     *  projectile event immediately so the inventory + the move-cost gate
     *  reflect the throw, but DEFERS the actual impact (damage, knockback,
     *  ignition, surface paint, etc.) to the moment the projectile
     *  visually arrives - see {@link #applyThrowImpact}. The Animator
     *  wires a {@code PendingImpact} from the {@code ItemThrown} event
     *  back to {@code applyThrowImpact} so the world only mutates after
     *  the visual lands. */
    public static void throwItem(Level level, Mob thrower, Item it, Point dst) {
        if (thrower == null || it == null || dst == null) return;
        removeFromInventory(thrower, it);
        // Clip the trajectory at the first mob / wall / statue between
        // thrower and the user-picked tile. Bombs detonate against the
        // obstacle rather than ghosting through it.
        Point impact = firstMobBlocking(level, thrower.position, dst, thrower);
        if (level.events != null) {
            boolean trajectoryVisible =
                    trajectoryTouchesVisible(level, thrower.position, impact);
            level.events.add(new com.bjsp123.rl2.event.GameEvent.ItemThrown(
                    thrower, it, thrower.position, impact, trajectoryVisible));
        }
        int throwCost = thrower.effectiveStats().attackCost;
        if (thrower.perks != null) {
            int hurlerLvl = thrower.perks.getOrDefault(com.bjsp123.rl2.model.Perk.HURLER, 0);
            for (int i = 0; i < hurlerLvl; i++) throwCost = (int) Math.ceil(throwCost * 0.75);
        }
        TurnSystem.applyActionCost(thrower, throwCost);
    }

    /**
     * Apply the world-state mutations of a throw - door open, damage, bomb /
     * potion / cloud effects, tame-on-throw, knockback, and the
     * {@link Item.ThrowResult} fate (consume / return / drop). Called from
     * the Animator's {@code PendingImpact} callback when the projectile
     * arc finishes so the visual sequence reads cleanly: arc flies ->
     * impact resolves -> subsequent visual events (ignite tiles, knock
     * back survivors, drop the item) all play AFTER the projectile lands.
     */
    public static void applyThrowImpact(Level level, Mob thrower, Item it, Point dst) {
        if (thrower == null || it == null || dst == null) return;

        int tx = dst.tileX(), ty = dst.tileY();
        ItemEffect te = it.throwEffect;
        boolean inBounds = tx >= 0 && ty >= 0 && tx < level.width && ty < level.height;

        // A thrown item that lands on a closed door pops it open - works for any throw kind.
        if (inBounds && level.tiles[tx][ty] == Tile.DOOR) {
            level.tiles[tx][ty] = Tile.DOOR_OPEN;
            if (level.events != null) level.events.add(
                    new com.bjsp123.rl2.event.GameEvent.DoorOpened(new com.bjsp123.rl2.model.Point(tx, ty)));
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

        if (te == ItemEffect.DAMAGE && it.damage.max() > 0) {
            Mob target = MobQueries.mobAt(level, dst);
            if (target != null && getAttitudeToMob(target, thrower) != Attitude.ALLY) {
                // processAttack records combat memory and floating-text centrally when
                // damage actually lands. Damage range comes from ItemSystem so the
                // weapon's level increment lands on thrown impact too.
                int dmg = rollRange(ItemStats.effectiveDamageRange(it));
                dmg = applySurpriseIfNeeded(level, thrower, target, dmg,
                        AttackType.THROWN, DamageElement.PHYSICAL);
                processAttack(level, thrower, target, dmg, AttackType.THROWN, DamageElement.PHYSICAL);
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
                EventLog.add(Messages.mobTamed(nameForLog(level, thrower), nameForLog(level, target)));
                consumedByTame = true;
            }
        }
        int lvl = ItemStats.effectiveLevel(it, thrower);
        // Bomb damage and AoE come from ItemSystem so every bomb-class throw shares
        // the same level-scaling formula with the rest of the item-stat math.
        int bombDamage = rollRange(ItemStats.effectiveDamageRange(it, thrower));
        int bombTiles  = ItemStats.effectiveTilesAffected(it, thrower);
        int bombRadius = ItemSystem.radiusForTileCount(bombTiles);
        int r2 = bombRadius * bombRadius;
        if (te == ItemEffect.FIRE && inBounds) {
            // Fire bomb: bomb damage at impact, ignite a Euclidean disc covering
            // tilesAffected + level * tilesAffectedPerLevel tiles around the impact tile.
            Mob target = MobQueries.mobAt(level, dst);
            if (target != null) {
                processAttack(level, thrower, target, bombDamage, AttackType.THROWN, DamageElement.PHYSICAL);
                BuffSystem.apply(level, target, com.bjsp123.rl2.model.Buff.BuffType.ON_FIRE,
                        Math.max(1, lvl), Math.max(1, 3 + lvl), thrower);
            }
            for (int dx = -bombRadius; dx <= bombRadius; dx++) {
                for (int dy = -bombRadius; dy <= bombRadius; dy++) {
                    if (dx * dx + dy * dy > r2) continue;
                    FireSystem.ignite(level, tx + dx, ty + dy);
                }
            }
        } else if (te == ItemEffect.WATER && inBounds) {
            // Water bomb: splashes a larger water disc than ordinary bombs.
            // Affected mobs are soaked and pushed back by one square per effective level.
            java.util.List<Mob> soaked = new ArrayList<>();
            for (int dx = -bombRadius; dx <= bombRadius; dx++) {
                for (int dy = -bombRadius; dy <= bombRadius; dy++) {
                    if (dx * dx + dy * dy > r2) continue;
                    int x = tx + dx, y = ty + dy;
                    if (x < 0 || y < 0 || x >= level.width || y >= level.height) continue;
                    Point p = new Point(x, y);
                    SurfaceSystem.addSurface(level, p, Surface.WATER);
                    Mob m = MobQueries.mobAt(level, p);
                    if (m != null && m != thrower) {
                        BuffSystem.apply(level, m, com.bjsp123.rl2.model.Buff.BuffType.WET,
                                Math.max(1, lvl), Math.max(1, WATER_STEP_BUFF_TURNS + lvl), thrower);
                        soaked.add(m);
                    }
                }
            }
            int kb = Math.max(0, lvl * it.knockbackSquares);
            if (kb > 0) {
                for (Mob m : soaked) if (m.hp > 0) knockBack(level, m, kb, dst);
            }
        } else if (te == ItemEffect.OIL && inBounds) {
            // Oil bomb: deals no damage but lays down an oil disc using the bomb area.
            for (int dx = -bombRadius; dx <= bombRadius; dx++) {
                for (int dy = -bombRadius; dy <= bombRadius; dy++) {
                    if (dx * dx + dy * dy > r2) continue;
                    Point p = new Point(tx + dx, ty + dy);
                    if (p.tileX() < 0 || p.tileY() < 0
                            || p.tileX() >= level.width || p.tileY() >= level.height) continue;
                    for (int i = 0; i < 2; i++) SurfaceSystem.addSurface(level, p, Surface.OIL);
                }
            }
            for (Mob m : level.mobs) {
                int mx = m.position.tileX(), my = m.position.tileY();
                int dx = mx - tx, dy = my - ty;
                if (dx * dx + dy * dy <= r2) {
                    BuffSystem.apply(level, m, com.bjsp123.rl2.model.Buff.BuffType.OILY,
                            Math.max(1, lvl), Math.max(1, 5 + lvl), thrower);
                }
            }
        } else if (te == ItemEffect.BLAST && inBounds) {
            // Blast bomb: doubles the bomb-damage constant against everyone in the
            // blast disc, plus a "blast" particle effect on every affected tile,
            // plus a 0..3-duration smoke cloud on each tile so the blast leaves
            // a visible aftermath (also gives smoke blocking a real role in the
            // post-blast frame or two).
            int dmg = bombDamage * 2;
            List<Mob> blastSurvivors = it.knockbackSquares > 0 ? new ArrayList<>() : null;
            for (int dx = -bombRadius; dx <= bombRadius; dx++) {
                for (int dy = -bombRadius; dy <= bombRadius; dy++) {
                    if (dx * dx + dy * dy > r2) continue;
                    int x = tx + dx, y = ty + dy;
                    if (x < 0 || y < 0 || x >= level.width || y >= level.height) continue;
                    Point p = new Point(x, y);
                    if (level.events != null) {
                        level.events.add(new com.bjsp123.rl2.event.GameEvent.BlastEffect(p));
                    }
                    // 0..3-duration smoke per tile - when the roll lands on
                    // 0 the tile gets no cloud at all, so a blast on open
                    // floor leaves an irregular soot pattern rather than a
                    // perfect smoky disc.
                    int smokeDur = RANDOM.nextInt(4);
                    if (smokeDur > 0) {
                        CloudSystem.addCloud(level, x, y,
                                com.bjsp123.rl2.model.Level.Cloud.SMOKE, smokeDur);
                    }
                    Mob m = MobQueries.mobAt(level, p);
                    if (m != null && m != thrower) {
                        processAttack(level, thrower, m, dmg, AttackType.THROWN, DamageElement.PHYSICAL);
                        if (blastSurvivors != null && m.hp > 0) blastSurvivors.add(m);
                    }
                }
            }
            if (blastSurvivors != null) {
                for (Mob m : blastSurvivors) knockBack(level, m, it.knockbackSquares, dst);
            }
        } else if (te == ItemEffect.APPLYBUFFS && inBounds
                && it.appliesBuff != null && !it.appliesBuff.isEmpty()) {
            // APPLYBUFFS - every buff in the item's pipe-list is applied
            // to every mob in a Chebyshev radius 1 disc around the impact
            // tile. Item level + abilityPower scale via the standard
            // ItemSystem helpers.
            int buffLvl = ItemStats.effectiveBuffLevel(it);
            int buffDur = ItemStats.effectiveBuffDuration(it);
            for (Mob m : level.mobs) {
                if (m == null || m.position == null || m.hp <= 0) continue;
                int d = Math.max(Math.abs(m.position.tileX() - tx),
                                 Math.abs(m.position.tileY() - ty));
                if (d > 1) continue;
                for (com.bjsp123.rl2.model.Buff.BuffType b : it.appliesBuff) {
                    BuffSystem.apply(level, m, b, buffLvl, buffDur, thrower);
                }
            }
        } else if (te == ItemEffect.POISONCLOUD && inBounds) {
            // POISONCLOUD - drop a persistent poison cloud over the disc.
            // The cloud layer (see {@link CloudSystem}) re-applies POISONED
            // to mobs standing in it on each per-turn pass, so a longer
            // cloud lifetime keeps poisoning whoever lingers in it. The
            // cloud's duration is the item's scaled abilityPower.
            int dur = ItemStats.effectiveBuffDuration(it);
            for (int dx = -bombRadius; dx <= bombRadius; dx++) {
                for (int dy = -bombRadius; dy <= bombRadius; dy++) {
                    if (dx * dx + dy * dy > r2) continue;
                    int x = tx + dx, y = ty + dy;
                    if (x < 0 || y < 0 || x >= level.width || y >= level.height) continue;
                    if (level.events != null) {
                        level.events.add(new com.bjsp123.rl2.event.GameEvent.BlastEffect(
                                new Point(x, y)));
                    }
                    if (dur > 0) {
                        CloudSystem.addCloud(level, x, y,
                                com.bjsp123.rl2.model.Level.Cloud.POISON, dur);
                    }
                }
            }
        } else if (te == ItemEffect.SMOKE && inBounds) {
            // SMOKE - drop an opaque cloud over the disc. Smoke blocks sight and
            // light but not projectiles; duration scales through the same
            // abilityPower path used by poison-cloud items.
            int dur = ItemStats.effectiveBuffDuration(it);
            for (int dx = -bombRadius; dx <= bombRadius; dx++) {
                for (int dy = -bombRadius; dy <= bombRadius; dy++) {
                    if (dx * dx + dy * dy > r2) continue;
                    int x = tx + dx, y = ty + dy;
                    if (x < 0 || y < 0 || x >= level.width || y >= level.height) continue;
                    if (level.events != null) {
                        level.events.add(new com.bjsp123.rl2.event.GameEvent.BlastEffect(
                                new Point(x, y)));
                    }
                    if (dur > 0) {
                        CloudSystem.addCloud(level, x, y,
                                com.bjsp123.rl2.model.Level.Cloud.SMOKE, dur);
                    }
                }
            }
        } else if (te == ItemEffect.FREEZE && inBounds) {
            // Freeze bomb: bomb damage to the target, CHILLED applied to every mob in
            // the freeze disc. Removes fire vegetation in the disc. Water-to-ice
            // conversion is blocked on a new ICE surface type - TODO.
            Mob target = MobQueries.mobAt(level, dst);
            if (target != null) {
                processAttack(level, thrower, target, bombDamage, AttackType.THROWN, DamageElement.PHYSICAL);
            }
            for (int dx = -bombRadius; dx <= bombRadius; dx++) {
                for (int dy = -bombRadius; dy <= bombRadius; dy++) {
                    if (dx * dx + dy * dy > r2) continue;
                    int x = tx + dx, y = ty + dy;
                    if (x < 0 || y < 0 || x >= level.width || y >= level.height) continue;
                    if (level.vegetation[x][y] == com.bjsp123.rl2.model.Level.Vegetation.FIRE) {
                        level.vegetation[x][y] = null;
                        if (level.fireRemaining     != null) level.fireRemaining[x][y]     = 0;
                        if (level.fireEmitCountdown != null) level.fireEmitCountdown[x][y] = 0;
                    }
                }
            }
            for (Mob m : level.mobs) {
                int mx = m.position.tileX(), my = m.position.tileY();
                int dx = mx - tx, dy = my - ty;
                if (dx * dx + dy * dy <= r2) {
                    BuffSystem.apply(level, m, com.bjsp123.rl2.model.Buff.BuffType.CHILLED,
                            Math.max(1, lvl), Math.max(1, 6 + lvl), thrower);
                    if (m != thrower) {
                        m.ticksTillMove += TurnSystem.STANDARD_TURN_TICKS;
                    }
                }
            }
        } else if (te == ItemEffect.VOID && inBounds) {
            ItemSystem.applyVoidImpact(level, dst, lvl);
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

    // eat / drinkPotion moved to ItemSystem - they're item-effect dispatchers, not
    // mob-system primitives. ItemSystem.eat / ItemSystem.drinkPotion are the live
    // entry points; the helpers they need (removeFromInventory, processAttack,
    // applyWandImpact, igniteDisc, paintMixedFloraDisc, paintVegetationDisc,
    // radiusForTileCount) are package-private here.

    /**
     * Apply healing to a mob: bumps {@code mob.hp} up to {@code mob.effectiveStats().maxHp} (capped at
     * {@code amount}) and spawns a green {@code "+N"} floating-text effect at the mob's
     * tile so the player gets visible feedback. No-op for already-full HP. Called from
     * the inventory potion path; new heal sources should funnel through here so the
     * green-text contract stays in one place.
     */
    public static void heal(Level level, Mob mob, int amount) {
        if (mob == null || amount <= 0) return;
        if (mob.hp >= mob.effectiveStats().maxHp) return;
        int actual = (int) Math.min(amount, mob.effectiveStats().maxHp - mob.hp);
        mob.hp = Math.min(mob.effectiveStats().maxHp, mob.hp + actual);
        if (level != null && level.events != null && actual > 0) {
            level.events.add(new com.bjsp123.rl2.event.GameEvent.HealApplied(mob, actual));
        }
    }

    // applyWandImpact and radiusForTileCount moved to ItemSystem.

    /** Ignite every tile in the Euclidean disc of {@code radius} centred on
     *  {@code (cx, cy)}. Cells outside bounds or non-flammable (water/blood/walls/chasm)
     *  shrug off the ignite call inside {@link FireSystem#ignite}. Package-private so
     *  {@link ItemSystem}'s wand / bomb impact paths can reach it. */
    static void igniteDisc(Level level, int cx, int cy, int radius) {
        int r2 = radius * radius;
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dy = -radius; dy <= radius; dy++) {
                if (dx * dx + dy * dy > r2) continue;
                FireSystem.ignite(level, cx + dx, cy + dy);
            }
        }
    }

    /** Like {@link #igniteDisc} but only sets fire to tiles that are
     *  intrinsically flammable - oil-coated or grass / mushroom / tree
     *  vegetation. Bare floor and stone don't catch. Used by the
     *  wand-of-blast / DETONATION path so a concussive blast only
     *  spreads fire where there's actually something to burn. */
    static void igniteFlammableDisc(Level level, int cx, int cy, int radius) {
        int r2 = radius * radius;
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dy = -radius; dy <= radius; dy++) {
                if (dx * dx + dy * dy > r2) continue;
                int x = cx + dx, y = cy + dy;
                if (x < 0 || y < 0 || x >= level.width || y >= level.height) continue;
                Level.Surface s = level.surface[x][y];
                Level.Vegetation v = level.vegetation[x][y];
                boolean flammable = (s == Level.Surface.OIL)
                        || (v == Level.Vegetation.GRASS
                                || v == Level.Vegetation.MUSHROOMS
                                || v == Level.Vegetation.TREES);
                if (flammable) FireSystem.ignite(level, x, y);
            }
        }
    }

    /** Paint {@code v} onto every floor-like, surface-free cell in the Euclidean disc of
     *  {@code radius} centred on {@code (cx, cy)}. The wand-of-fungus path uses this
     *  directly with {@link Level.Vegetation#MUSHROOMS}. */
    static void paintVegetationDisc(Level level, int cx, int cy, int radius,
                                    Level.Vegetation v) {
        int r2 = radius * radius;
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dy = -radius; dy <= radius; dy++) {
                if (dx * dx + dy * dy > r2) continue;
                int x = cx + dx, y = cy + dy;
                if (x < 0 || y < 0 || x >= level.width || y >= level.height) continue;
                if (!level.tiles[x][y].isFloorLike()) continue;
                if (level.surface[x][y] != null) continue;
                Level.Vegetation old = level.vegetation[x][y];
                level.vegetation[x][y] = v;
                if (old != v) VegetationSystem.emitVegetationChanged(level, x, y, v);
            }
        }
    }

    /** Wand-of-vegetation paint: each cell of the disc rolls grass-or-tree independently
     *  so the result reads as a small thicket - usually a tree at the centre with grass
     *  around it, but occasionally the other way around. The centre cell skews tree-heavy;
     *  cells further out skew grass-heavy so the player isn't drowning in trunks. */
    static void paintMixedFloraDisc(Level level, int cx, int cy, int radius) {
        int r2 = radius * radius;
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dy = -radius; dy <= radius; dy++) {
                int distSq = dx * dx + dy * dy;
                if (distSq > r2) continue;
                int x = cx + dx, y = cy + dy;
                if (x < 0 || y < 0 || x >= level.width || y >= level.height) continue;
                if (!level.tiles[x][y].isFloorLike()) continue;
                if (level.surface[x][y] != null) continue;
                // Centre + immediate neighbours skew tree (<=1 tile away); rest skew grass.
                double treeChance = distSq <= 1 ? 0.6 : 0.25;
                Level.Vegetation v = RANDOM.nextDouble() < treeChance
                        ? Level.Vegetation.TREES
                        : Level.Vegetation.GRASS;
                Level.Vegetation old = level.vegetation[x][y];
                level.vegetation[x][y] = v;
                if (old != v) VegetationSystem.emitVegetationChanged(level, x, y, v);
            }
        }
    }

    /** Public re-export of the package-private inventory remove for the wand/potion use
     *  paths that live in PlayScreen. */
    public static void removeFromInventoryPublic(Mob mob, Item it) {
        removeFromInventory(mob, it);
    }

    /** Consume one unit of {@code it} - decrements the bag stack (drops the entry if
     *  this was the last one) or unequips it from any slot it occupies. The "consume
     *  one" semantics apply to throw / eat / drink / use callers; {@link Inventory#bag}
     *  entries with {@code count > 1} represent stacks, so we do NOT remove the whole
     *  stack on a single use. */
    static void removeFromInventory(Mob mob, Item it) {
        if (mob == null || it == null) return;
        Inventory inv = mob.inventory;
        boolean wasEquipped = false;
        if (inv.weapon  == it) { inv.weapon  = null; wasEquipped = true; }
        if (inv.offhand == it) { inv.offhand = null; wasEquipped = true; }
        if (inv.armor   == it) { inv.armor   = null; wasEquipped = true; }
        for (int i = 0; i < inv.amulets.length; i++) {
            if (inv.amulets[i] == it) { inv.amulets[i] = null; wasEquipped = true; }
        }
        for (int i = 0; i < inv.gems.length; i++) {
            if (inv.gems[i] == it) { inv.gems[i] = null; wasEquipped = true; }
        }
        if (wasEquipped) return;
        InventorySystem.removeOneFromBag(mob.inventory, it);
    }

    private static Point randomFloorPoint(Level level) {
        List<Point> floors = new ArrayList<>();
        for (int x = 0; x < level.width; x++)
            for (int y = 0; y < level.height; y++)
                if (level.tiles[x][y] == Tile.FLOOR) floors.add(new Point(x, y));
        if (floors.isEmpty()) return null;
        return floors.get(RANDOM.nextInt(floors.size()));
    }
}
