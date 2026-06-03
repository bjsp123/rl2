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
        PHYSICAL, MAGIC, POISON, FIRE, SHOCK, STARVATION, COLD
    }

    /** True if {@code m} is "wet" - carries the WET buff or stands on a water /
     *  ice tile. Wetness conducts lightning (x2) and aggravates cold (x4, RL-31),
     *  applied centrally in {@link #processAttack}; also gates the chilled+wet
     *  freeze in {@code BuffSystem.maybeFreeze}. */
    public static boolean isWet(Level level, Mob m) {
        if (level == null || m == null || m.position == null) return false;
        if (BuffSystem.hasBuff(m, com.bjsp123.rl2.model.Buff.BuffType.WET)) return true;
        int x = m.position.tileX(), y = m.position.tileY();
        if (x < 0 || y < 0 || x >= level.width || y >= level.height) return false;
        Level.Surface s = level.surface[x][y];
        return s == Level.Surface.WATER || s == Level.Surface.ICE;
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
        /** Knockback distance applied alongside this damage roll. Drives the
         *  ", knocking the {target} back N" suffix on the damageRoll log line.
         *  Zero = no annotation. Set by melee callers in {@link #attack}
         *  before {@link #processAttack} runs. */
        public int kbSquares = 0;
        /** Mechanism of the attack. Drives the damageRoll log line's voice:
         *  MELEE → "X hits Y for N damage"; RANGED/THROWN/MAGIC → "X's <item>
         *  does N damage to Y"; ENVIRONMENTAL (no attacker) → "Y takes N
         *  damage" (passive). Defaults to null; {@link #processAttack} fills
         *  it from its own {@code type} parameter so non-melee callers don't
         *  have to remember to set it. */
        public AttackType type;
        /** Causal chain, used to pull the originating item name for the
         *  "X's <item> does N damage" form on ranged/thrown/magic hits.
         *  Defaults to null; {@link #processAttack} fills it from the cause
         *  it computed (synthesised or caller-supplied). */
        public DamageCause cause;

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

    /** Causal chain for a single damage event. {@code origin} is the root
     *  attacker (the mob whose action ultimately caused this damage even if
     *  the damage is being applied indirectly via a fire DOT, a wall-slam,
     *  etc.). {@code originItem} is the wand / bomb / weapon that *originated*
     *  the chain (the fire wand that lit the fire, the blast bomb that
     *  knocked the victim into a wall). {@code medium} names the indirect
     *  mechanism: {@code "blow"}, {@code "wall-slam"}, {@code "fall"},
     *  {@code "fire-dot"}, {@code "poison-dot"}, {@code "burst"}, etc.
     *
     *  <p>For direct hits the cause is just {@code (attacker,
     *  attacker.equippedWeapon, "blow")} — equivalent to passing null, since
     *  {@link #processAttack(Level, Mob, Mob, int, AttackType, DamageElement,
     *  DamageBreakdown, DamageCause)} fills that default when {@code cause}
     *  is null. Indirect damage paths construct a {@code DamageCause} at
     *  the originating site (fire-tile damage, knockback wall-slam, chasm
     *  fall) so the death screen + log messages can name the root cause. */
    public record DamageCause(Mob origin, Item originItem, String medium) {
        /** Cause for an environmental hit with no attribution
         *  (e.g. chasm fall, ambient fire that's been burning since level
         *  gen). */
        public static final DamageCause NONE = new DamageCause(null, null, null);
    }

    /** Most recent {@link DamageCause} that landed a damaging blow on a
     *  PLAYER-behaviour mob. Updated inside {@link #processAttack} whenever
     *  {@code target.behavior == PLAYER && dealt > 0}. Read by the
     *  death-screen path on PlayerScreen to surface the cause as the death
     *  headline. {@code element} and {@code dealt} are stashed alongside so
     *  the headline can phrase the verb ("burned", "shoved", "bled out")
     *  without re-walking the log. Cleared by {@link #resetLastPlayerHit}
     *  when a new run begins. */
    private static volatile DamageCause lastPlayerCause;
    private static volatile DamageElement lastPlayerElement;
    private static volatile int lastPlayerHitDealt;

    public static DamageCause lastPlayerCause()        { return lastPlayerCause; }
    public static DamageElement lastPlayerElement()    { return lastPlayerElement; }
    public static int lastPlayerHitDealt()             { return lastPlayerHitDealt; }
    public static void resetLastPlayerHit() {
        lastPlayerCause = null;
        lastPlayerElement = null;
        lastPlayerHitDealt = 0;
    }

    private static final Random RANDOM =
            com.bjsp123.rl2.util.SimRng.register("MobSystem", new Random());

    /** Default duration in game ticks (3 standard turns) that the
     *  {@link com.bjsp123.rl2.model.Buff.BuffType#OILY} buff lasts when a mob
     *  steps onto an OIL surface. The buff system decrements per game tick - see
     *  {@link BuffSystem#tickEveryGameTick}. */
    public static final int OIL_STEP_BUFF_TICKS = 3 * TurnSystem.STANDARD_TURN_TICKS;

    /** Default duration in game ticks (3 standard turns) that the
     *  {@link com.bjsp123.rl2.model.Buff.BuffType#WET} buff lasts when a mob
     *  steps onto a WATER surface. */
    public static final int WATER_STEP_BUFF_TICKS = 3 * TurnSystem.STANDARD_TURN_TICKS;

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
            // The player (and the SMART autoplay driver standing in for the player) is
            // impassable to any non-hostile mob: even a FLEE / NOTHING swap is blocked,
            // so friendly critters can never end up shoving the player into a wall by
            // accident. The mover gives up its step and pays a regular move tick.
            if (occupant.behavior == Behavior.PLAYER || occupant.behavior == Behavior.SMART) {
                mob.targetPosition = null;
                TurnSystem.applyMoveCost(mob, mob.effectiveStats().moveCost);
                return;
            }
            // Non-player movers only displace strictly smaller non-hostile mobs (matches
            // Pathfinder's canEnter gate). Same-size or larger blocks the step entirely
            // - the mover idles for one tick. PLAYER and SMART (the autoplay/AI driver
            // for a player-class character) ignore the size gate so a non-hostile
            // critter never blocks a player-style mover's intended move.
            if (mob.behavior != Behavior.PLAYER && mob.behavior != Behavior.SMART
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
        boolean isPlayer = mob.behavior == Behavior.PLAYER;
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
                EventLog.add(Messages.doorClosed(nameForLog(level, mob), isPlayer));
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
        boolean isPlayer = mob.behavior == Behavior.PLAYER;
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
                        EventLog.add(Messages.doorOpened(nameForLog(level, mob), isPlayer));
                    }
                    case BREAKS -> {
                        level.tiles[nx][ny] = db.brokenVariant();
                        if (level.events != null) level.events.add(
                                new com.bjsp123.rl2.event.GameEvent.OnetimeDoorBroken(new com.bjsp123.rl2.model.Point(nx, ny)));
                        EventLog.add(Messages.doorBroken(nameForLog(level, mob), isPlayer));
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
        if (mob.behavior == Mob.Behavior.PLAYER) {
            for (int dx = -1; dx <= 1; dx++) {
                for (int dy = -1; dy <= 1; dy++) {
                    if (dx == 0 && dy == 0) continue;
                    int bx = nx + dx, by = ny + dy;
                    if (bx < 0 || by < 0 || bx >= level.width || by >= level.height) continue;
                    if (level.tiles[bx][by] != Tile.BEACON_INACTIVE) continue;
                    level.tiles[bx][by] = Tile.BEACON_ACTIVE;
                    if (level.events != null) level.events.add(
                            new com.bjsp123.rl2.event.GameEvent.BeaconActivated(new Point(bx, by)));
                    EventLog.add(Messages.beaconActivated(nameForLog(level, mob)));
                }
            }
        }
        // Oil pickup: stepping onto an OIL surface applies the OILY buff for
        // OIL_STEP_BUFF_TURNS turns. Re-applies refresh the buff (max-merge) so wading
        // deeper resets the clock.
        // BOMB_DODGER gates the surface step-buff so a Rogue with the perk
        // stays dry crossing her own water / oil bombs.
        boolean bombDodger = mob.perks != null
                && mob.perks.getOrDefault(com.bjsp123.rl2.model.Perk.BOMB_DODGER, 0) >= 1;
        if (level.surface[nx][ny] == Surface.OIL && !bombDodger) {
            BuffSystem.apply(level, mob, com.bjsp123.rl2.model.Buff.BuffType.OILY,
                    1, OIL_STEP_BUFF_TICKS, null);
        }
        // Water pickup: stepping into / over a WATER surface soaks the mob
        // for WATER_STEP_BUFF_TICKS ticks (so they take double damage from
        // lightning until they dry off). Refreshes via the standard
        // max-merge so a long wade keeps resetting the clock.
        if (level.surface[nx][ny] == Surface.WATER && !bombDodger) {
            BuffSystem.apply(level, mob, com.bjsp123.rl2.model.Buff.BuffType.WET,
                    1, WATER_STEP_BUFF_TICKS, null);
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
        // stepper. PLAYER and SMART (autoplay driver standing in for the
        // player) both trigger; other mobs walk over them.
        if (mob.behavior == Behavior.PLAYER || mob.behavior == Behavior.SMART) {
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
    /** Shorthand for {@code getAttitudeToMob(a, b) == Attitude.ALLY}. Use
     *  this for the very common "is this friendly fire?" check so call sites
     *  don't re-import the Attitude enum or hand-write the same comparison. */
    public static boolean isAlly(Mob a, Mob b) {
        return getAttitudeToMob(a, b) == Attitude.ALLY;
    }

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
        // Capture the prior attitude BEFORE we mutate attackTypes so the
        // "becomes hostile" log only fires when the relationship actually
        // changes. A roach whose CSV enemyFactions includes PLAYER was
        // already going to attack; adding ROACH -> WARRIOR to the player's
        // attackTypes set is bookkeeping, not news.
        Attitude aPriorAttitude = getAttitudeToMob(a, b);
        Attitude bPriorAttitude = getAttitudeToMob(b, a);
        boolean aLearned = a.attackTypes.add(b.mobType);
        a.fleeTypes.remove(b.mobType);
        boolean bLearned = b.attackTypes.add(a.mobType);
        b.fleeTypes.remove(a.mobType);

        boolean aBecameHostile = aLearned && aPriorAttitude != Attitude.ATTACK;
        boolean bBecameHostile = bLearned && bPriorAttitude != Attitude.ATTACK;
        if (!aBecameHostile && !bBecameHostile) return;
        boolean aPlayer = a.behavior == Behavior.PLAYER;
        boolean bPlayer = b.behavior == Behavior.PLAYER;
        String aName = nameForLog(level, a);
        String bName = nameForLog(level, b);
        if (!aPlayer && bPlayer && aBecameHostile) {
            EventLog.add(Messages.attitudeTurnsOnPlayer(aName, reason));
        } else if (!bPlayer && aPlayer && bBecameHostile) {
            EventLog.add(Messages.attitudeTurnsOnPlayer(bName, reason));
        } else if (!aPlayer && !bPlayer) {
            if (aBecameHostile) EventLog.add(Messages.attitudeMobOnMob(aName, bName, reason));
            else                EventLog.add(Messages.attitudeMobOnMob(bName, aName, reason));
        }

        // Kin propagation: same-species mobs within their own wake radius of
        // the attacked one also learn to attack the player. A pack of mice
        // turns en masse when one of them is hit, even though they're all
        // factionless individuals. Only fires when the attacker is the
        // player and the victim genuinely became hostile (so we don't
        // re-log for mobs that were already attacking via faction rules).
        if (aPlayer && bBecameHostile) propagateHostilityToKin(level, a, b, reason);
        if (bPlayer && aBecameHostile) propagateHostilityToKin(level, b, a, reason);
    }

    /** Spread the {@code attacker -> attackTypes} entry to every same-species
     *  mob within its own {@code wakeRadius} of the original {@code victim}.
     *  Each newly-hostile kin emits a "becomes hostile" log line, gated by
     *  the same prior-attitude check as {@link #recordCombatMemory} so kin
     *  that were already attacking (via faction or earlier combat memory)
     *  don't re-log. The flee set is also cleared on each affected mob so
     *  a "fled in fright" entry doesn't override the new hostility. */
    private static void propagateHostilityToKin(Level level, Mob attacker, Mob victim, String reason) {
        if (level == null || level.mobs == null) return;
        if (attacker == null || victim == null) return;
        if (attacker.mobType == null || victim.mobType == null) return;
        if (victim.position == null) return;
        int ox = victim.position.tileX(), oy = victim.position.tileY();
        for (Mob m : level.mobs) {
            if (m == attacker || m == victim) continue;
            if (m.mobType == null || !m.mobType.equals(victim.mobType)) continue;
            if (m.position == null || m.hp <= 0) continue;
            int d = Math.max(Math.abs(m.position.tileX() - ox),
                             Math.abs(m.position.tileY() - oy));
            int range = (int) Math.max(1.0, m.effectiveStats().wakeRadius);
            if (d > range) continue;
            if (m.attackTypes == null) m.attackTypes = new java.util.HashSet<>();
            if (m.fleeTypes   == null) m.fleeTypes   = new java.util.HashSet<>();
            Attitude prior = getAttitudeToMob(m, attacker);
            boolean learned = m.attackTypes.add(attacker.mobType);
            m.fleeTypes.remove(attacker.mobType);
            if (learned && prior != Attitude.ATTACK) {
                EventLog.add(Messages.attitudeTurnsOnPlayer(
                        nameForLog(level, m), reason));
            }
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

    /** True iff a projectile from {@code shooter} aimed at {@code to} actually
     *  lands on {@code to} - i.e. no wall, closed door (incl. CRYSTAL_DOOR),
     *  statue, or intervening mob clips the line first. Used by the basic
     *  ranged-attack AI to gate firing; the SMART-AI throw / wand paths use
     *  this indirectly via their {@code *WillLandUsefully} eval calls. */
    public static boolean projectileLineReaches(Level level, Point from, Point to, Mob shooter) {
        return MobVisibility.projectileLineReaches(level, from, to, shooter);
    }

    /** Queue a deferred impact resolution onto the level. The {@code resolve}
     *  Runnable should invoke the matching {@code apply*Impact} method (which
     *  in turn decrements {@link Level#pendingImpactCount}). Used by every
     *  animation-gated action (throws, wand fires, knockback chains, animated
     *  tool uses) to defer step 4 of the lifecycle until the visual lands.
     *
     *  <p>On-screen: the rgame Animator pops these into its PendingImpactQueue
     *  at consume() time and fires them when the matching projectile arc
     *  completes. Headless: {@link #drainPendingImpactsImmediate} is called
     *  by the AI loop between mob brains and fires the queued resolves
     *  synchronously. */
    public static void queuePendingImpact(Level level, Runnable resolve) {
        if (level == null || resolve == null) return;
        if (level.pendingImpacts == null) {
            level.pendingImpacts = new java.util.ArrayDeque<>();
        }
        level.pendingImpacts.add(resolve);
        level.pendingImpactCount++;
    }

    /** Fire all queued pending-impact resolves synchronously, in FIFO order.
     *  Called between mob brains in headless mode (and by the rgame Animator
     *  if it ever wants to force-resolve everything immediately, e.g. on
     *  level transition). On-screen, the Animator normally pulls resolves
     *  out of the queue one at a time at arc-completion via
     *  {@link #popNextPendingImpact} so the queue is empty when this is
     *  called. */
    public static void drainPendingImpactsImmediate(Level level) {
        if (level == null || level.pendingImpacts == null) return;
        while (!level.pendingImpacts.isEmpty()) {
            Runnable r = level.pendingImpacts.pollFirst();
            if (r != null) r.run();
        }
    }

    /** Pop the next queued pending-impact resolve (FIFO). The rgame Animator
     *  uses this in {@code onItemThrown}/{@code onWandMissileFired}/etc. to
     *  fetch the resolve callback whose visual arc it's about to schedule.
     *  Returns null if the queue is empty (defensive — shouldn't normally
     *  happen since throwItem queues before emitting the ItemThrown event). */
    public static Runnable popNextPendingImpact(Level level) {
        if (level == null || level.pendingImpacts == null) return null;
        return level.pendingImpacts.pollFirst();
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

    /** Transition {@code mob} to AWAKE and emit a "wakes up" log entry when
     *  the tile is visible to the player. Centralises the
     *  {@code ASLEEP -> AWAKE} log so every AI dispatcher that checks the
     *  wake gate emits consistent messaging. No-op when {@code mob} is null
     *  or already AWAKE; player and off-screen wakes are silent. {@code
     *  reason} is the parenthetical (e.g. "sensing something nearby",
     *  "damaged by fire") - same wording as the existing Messages helper. */
    public static void wakeMob(Level level, Mob mob, String reason) {
        if (mob == null) return;
        StateOfMind prev = mob.stateOfMind;
        if (prev == StateOfMind.AWAKE) return;
        mob.stateOfMind = StateOfMind.AWAKE;
        if (mob.behavior == Behavior.PLAYER) return;
        if (prev != StateOfMind.ASLEEP) return;
        if (!isVisibleToPlayer(level, mob)) return;
        EventLog.add(Messages.mobWakesUp(nameForLog(level, mob), reason));
    }

    /** True if the tile at {@code pos} is currently lit + in the player's
     *  FOV. Defensive against null {@code level.visible} (transient field
     *  reset on load) and out-of-bounds positions. */
    public static boolean tileVisibleToPlayer(Level level, Point pos) {
        if (level == null || pos == null || level.visible == null) return false;
        int x = pos.tileX(), y = pos.tileY();
        if (x < 0 || y < 0 || x >= level.width || y >= level.height) return false;
        return level.visible[x][y];
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
            knockBack(level, target, kb, attacker.position, wallSlam, kbCause);
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
        // Stat-based per-element immunity: poisonImmune zeroes incoming POISON
        // before mitigation. The HIT event still fires below with dealt=0 so the
        // player sees "-0 poison" floating, signalling the immunity.
        if (element == DamageElement.POISON && target.effectiveStats().poisonImmune) {
            rawDealt = 0;
        }
        // Buff-based mitigation. PROTECTION blunts physical, ANTI_MAGIC blunts magic
        // and fire. Other elements (POISON, SHOCK, STARVATION, COLD) ignore both buffs.
        int dealt = switch (element) {
            case PHYSICAL          -> BuffSystem.mitigatePhysicalDamage(target, rawDealt);
            case MAGIC, FIRE       -> BuffSystem.mitigateMagicDamage(target, rawDealt);
            case POISON, SHOCK, STARVATION, COLD -> rawDealt;
        };
        // Wet vulnerability (RL-31): water conducts lightning (x2) and aggravates
        // cold (x4). Applied after mitigation so it scales the real hit.
        if (dealt > 0 && isWet(level, target)) {
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
        if (dealt > 0 && target.behavior == Behavior.PLAYER) {
            lastPlayerCause     = effectiveCause;
            lastPlayerElement   = element;
            lastPlayerHitDealt  = dealt;
        }
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
            // wakeMob only logs the ASLEEP -> AWAKE case (the user-visible
            // "wakes up" beat); HIDING / SEEKING_HIDING transitions still
            // flip silently to AWAKE here.
            wakeMob(level, target, "damaged by " + element.name().toLowerCase());
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
                    attacker, element, effectiveCause));
        }
        // HIGH-priority element-tagged log line for non-PHYSICAL damage. The
        // PHYSICAL melee path emits its own playerHit/enemyHit/mobHit line via
        // logAttackOutcome; PHYSICAL throws/wall-slams stay on the LOW-pri
        // damageRoll line as before. This line is the player-visible "X takes 3
        // poison damage" feedback for DOT ticks, wand zaps, and starvation.
        if (dealt > 0 && element != DamageElement.PHYSICAL) {
            boolean playerInvolved = (attacker != null && attacker.behavior == Behavior.PLAYER)
                                  || target.behavior == Behavior.PLAYER;
            String originName = Messages.formatCauseOrigin(level, effectiveCause);
            EventLog.add(Messages.elementalDamage(
                    nameForLog(level, target), element, dealt, originName, playerInvolved));
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
                && element == DamageElement.PHYSICAL
                && !target.effectiveStats().poisonImmune) {
            int lvl = Math.max(1, attacker.characterLevel);
            BuffSystem.apply(level, target, com.bjsp123.rl2.model.Buff.BuffType.POISONED,
                    lvl, lvl * 3 * TurnSystem.STANDARD_TURN_TICKS, attacker);
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

    /** Emit the kill line for a fatal blow. Called from {@link #attack}
     *  after the full melee chain resolves (damage + knockback chain).
     *  Selects the right Messages factory by player-involvement. */
    private static void emitKillLog(Level level, Mob attacker, Mob target) {
        String atkName = nameForLog(level, attacker);
        String tgtName = nameForLog(level, target);
        if (attacker.behavior == Behavior.PLAYER) {
            EventLog.add(Messages.playerKill(atkName, tgtName));
        } else if (target.behavior == Behavior.PLAYER) {
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
        String atk = effectiveAttacker == null ? null : nameForLog(level, effectiveAttacker);
        String tgt = target == null ? "?" : nameForLog(level, target);
        boolean attackerIsPlayer = effectiveAttacker != null
                && effectiveAttacker.behavior == Behavior.PLAYER;
        boolean targetIsPlayer = target != null && target.behavior == Behavior.PLAYER;
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

    /** Move every item under the mob's feet from the ground into its bag (until the bag is full).
     *  Returns the number of items actually picked up - callers use this to decide whether
     *  to charge a move tick. */
    public static int pickupAtFeet(Level level, Mob mob) {
        if (!mob.effectiveStats().canPickUp) return 0;
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
            // RL-36: mobs must not pick up the teleport ORB (throwEffect TELEPORT) -
            // thrown, it scatters everyone in its blast to a random level, so a mob
            // grabbing one could fling the player away. Leave it on the floor.
            if (item.throwEffect == com.bjsp123.rl2.model.Item.ItemEffect.TELEPORT) {
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
            // KILLER perk: every kill stacks the KILLER buff on the killer.
            // Per perk-level math (see Perk.KILLER javadoc): stacks-per-kill
            // is ceil(perkLvl/2), duration refresh is 8 + 2*ceil(perkLvl/2)
            // turns. BuffSystem.apply contains a stacking carve-out for
            // KILLER that adds the incoming level to existing.level and
            // resets duration rather than max-merging.
            if (killer.perks != null) {
                int perkLvl = killer.perks.getOrDefault(com.bjsp123.rl2.model.Perk.KILLER, 0);
                if (perkLvl > 0) {
                    int stacks   = (perkLvl + 1) / 2;
                    int durationTicks = (8 + 2 * stacks) * TurnSystem.STANDARD_TURN_TICKS;
                    BuffSystem.apply(level, killer,
                            com.bjsp123.rl2.model.Buff.BuffType.KILLER,
                            stacks, durationTicks, killer);
                }
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
                processAttack(level, null, mob, remaining * 4 + wallSlamBonus,
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
                processAttack(level, null, mob, slamDmg,
                        AttackType.ENVIRONMENTAL, DamageElement.PHYSICAL, null, slamCause);
                if (mob.behavior == Behavior.PLAYER || isVisibleToPlayer(level, mob)) {
                    // No {@code intoName} - picks the "wall" variant.
                    // Origin only shows on attributable (non-melee) chains
                    // since the melee chain's preceding hit line already
                    // attributes the push.
                    EventLog.add(Messages.knockbackSlam(nameForLog(level, mob), slamDmg,
                            Messages.formatCauseOrigin(level, slamCause), null, 0,
                            mob.behavior == Behavior.PLAYER));
                }
                return;
            }

            Mob collided = MobQueries.mobAt(level, new Point(nx, ny));
            if (collided != null) {
                mob.position = new Point(cx, cy);
                emitKnockBack(level, mob, start, true);
                int slamDmg = remaining * 4 + wallSlamBonus;
                processAttack(level, null, mob, slamDmg,
                        AttackType.ENVIRONMENTAL, DamageElement.PHYSICAL, null, slamCause);
                if (mob.behavior == Behavior.PLAYER || isVisibleToPlayer(level, mob)) {
                    // Cascade kb = the {@code remaining} push that the
                    // collided mob will inherit. Reads as "...knocking the
                    // rat back 2" in the slam log.
                    int cascadeKb = (collided.hp > 0) ? remaining : 0;
                    EventLog.add(Messages.knockbackSlam(nameForLog(level, mob), slamDmg,
                            null, nameForLog(level, collided), cascadeKb,
                            mob.behavior == Behavior.PLAYER));
                }
                if (collided.hp > 0) {
                    processAttack(level, null, collided, remaining * 4,
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
            // No down-stairs (or no arrival tile) - fall back to depth 1.
            // Anything destroyed at the very bottom loops to the top of
            // the dungeon rather than being annihilated.
            if (next == null || arrival == null) {
                Level depth1 = findDepth1Level(world);
                if (depth1 != null && depth1 != level) {
                    next = depth1;
                    arrival = freeFloorNear(depth1, depth1.spawnPoint);
                }
            }
        }

        Point fromPos = mob.position;
        int dmg = Math.max(1, (int) Math.round(mob.effectiveStats().maxHp * 0.5));
        boolean canRelocate = next != null && arrival != null;
        boolean wouldKill = !canRelocate || mob.hp - dmg <= 0;

        // Revolve-shrink-fade visual at the source tile - same shape as
        // a falling item, but driven by the mob's sprite.
        if (level.events != null) {
            level.events.add(new com.bjsp123.rl2.event.GameEvent.MobFellThroughChasm(
                    mob, fromPos));
        }
        // Log the chasm plunge whenever it's visible to the player (or it's
        // the player themselves).
        if (mob.behavior == Behavior.PLAYER || isVisibleToPlayer(level, mob)) {
            EventLog.add(Messages.mobFellInChasm(nameForLog(level, mob),
                    mob.behavior == Behavior.PLAYER));
        }

        // Player safety net: as long as a relocate destination exists, the
        // PLAYER always survives the fall - capped at 1 HP - rather than
        // dying-and-dumping-inventory. Without this a knockback into the
        // void at <50% HP would silently strip the player's bag, weapon,
        // armour, amulets, and gems before the death animation. NPCs keep
        // the original kill-and-dump behaviour.
        if (wouldKill && canRelocate && mob.behavior == Behavior.PLAYER) {
            int safeDmg = Math.max(0, (int) Math.floor(mob.hp - 1));
            transferMobToLevel(level, mob, next, arrival);
            processAttack(next, null, mob, safeDmg,
                    AttackType.ENVIRONMENTAL, DamageElement.PHYSICAL);
            return;
        }

        if (wouldKill) {
            // Items into the chasm, mob dies on impact.
            emitFallingItems(level, mob);
            processAttack(level, null, mob, dmg,
                    AttackType.ENVIRONMENTAL, DamageElement.PHYSICAL);
            return;
        }

        // Survivor - relocate to the next level + apply impact damage there.
        transferMobToLevel(level, mob, next, arrival);
        processAttack(next, null, mob, dmg,
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
        if (tileVisibleToPlayer(srcLevel, fromPos)) {
            EventLog.add(Messages.itemFellInChasm(it.name, true));
        }
        srcLevel.items.remove(it);

        Level dst = findFallDestination(srcLevel);
        if (dst == null || dst == srcLevel) {
            // No destination - item is destroyed by the fall.
            it.location = null;
            return;
        }
        Point arrival = freeFloorNear(dst, dst.spawnPoint);
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
    private static Point freeFloorNear(Level lvl, Point preferred) {
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

    private static boolean isFreeFloor(Level lvl, int x, int y) {
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
        if (mob.behavior == Mob.Behavior.PLAYER || mob.behavior == Mob.Behavior.SMART) {
            com.bjsp123.rl2.model.World world = srcLevel.world;
            if (world != null) {
                int idx = indexOf(world, dstLevel);
                if (idx >= 0) {
                    world.currentLevelIndex = idx;
                    dstLevel.visited = true;
                }
            }
        }
        // Levels that seal behind the player vanish their stairs-up the first
        // time the player sets foot on them. Generic + idempotent (once sealed
        // stairsUp is null), so no per-level "entered" bookkeeping needed.
        if (mob.behavior == Mob.Behavior.PLAYER && dstLevel.sealOnEntry
                && dstLevel.stairsUp != null) {
            LevelSystem.sealStairsUp(dstLevel);
        }
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
        if (sp == null || sp.chancePerTurn <= 0
                || sp.speciesPool == null || sp.speciesPool.isEmpty()) return;
        if (RANDOM.nextDouble() >= sp.chancePerTurn) return;
        // Live-mob cap, summed across the pooled species, plus the global cap.
        int alive = 0;
        for (String type : sp.speciesPool) alive += MobQueries.countMobsOfType(level, type);
        if (alive >= sp.maxAlive) return;
        if (!MobQueries.levelHasRoomForSpawn(level)) return;
        Point spawnPos = spawnerTile(level, sp);
        if (spawnPos == null) return;
        String species = sp.speciesPool.get(RANDOM.nextInt(sp.speciesPool.size()));
        Mob bud = MobFactory.spawn(species, spawnPos);
        if (bud == null) return;
        // Escalation: spawn level ramps with the player's time on the level.
        if (sp.levelRampPer10Turns > 0) {
            int lvl = Math.min(GameBalance.MAX_CHARACTER_LEVEL,
                    1 + sp.levelRampPer10Turns * (level.turnsOnLevel / 10));
            MobProgression.setSpawnLevel(bud, lvl);
        }
        if (sp.spawnAwake) bud.stateOfMind = Mob.StateOfMind.AWAKE;
        level.mobs.add(bud);
        MobHooks.onSpawn(level, bud);
        if (level.events != null) {
            level.events.add(new com.bjsp123.rl2.event.GameEvent.MobSpawned(bud, spawnPos));
        }
    }

    /** Pick a spawn tile per the spawner's placement strategy. */
    private static Point spawnerTile(Level level, Level.Spawner sp) {
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
        int mx = (player.position.tileX() + exit.tileX()) / 2 + RANDOM.nextInt(5) - 2;
        int my = (player.position.tileY() + exit.tileY()) / 2 + RANDOM.nextInt(5) - 2;
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
        boolean tileVisible = tileVisibleToPlayer(level, mob.position);
        boolean involvesPlayer = mob.behavior == Behavior.PLAYER;
        for (Item item : falling) {
            level.events.add(new com.bjsp123.rl2.event.GameEvent.ItemFallingIntoChasm(
                    item, mob.position));
            if (tileVisible) {
                EventLog.add(Messages.itemFellInChasm(item.name, involvesPlayer));
            }
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
                wakeMob(level, mob, "sensing something nearby");
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

        if (mob.behavior == Behavior.SMART) {
            MobBrain brain = GameBalance.SMART_AI_ENABLED ? MobBrains.get(Behavior.SMART) : null;
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
                            Math.max(1, ab.appliedDuration) * TurnSystem.STANDARD_TURN_TICKS,
                            caster);
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
                        ab.cooldownTurns * TurnSystem.STANDARD_TURN_TICKS, caster);
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
                    if (!isAlly(caster, m)) continue;
                    if (BuffSystem.hasBuff(m, ab.applies)) continue;
                }
                case HEAL -> {
                    if (!isAlly(caster, m)) continue;
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
        // bombs are removed on use). Sort by Item.getValue() desc so within
        // each kind of item the AI prefers the highest-value instance;
        // since usability is checked per-item the "kind of item to use"
        // choice is still driven by isUsableByAi, just biased to good rolls.
        java.util.List<com.bjsp123.rl2.model.Item> snapshot =
                new java.util.ArrayList<>(mob.inventory.bag);
        snapshot.sort((a, b) -> Double.compare(
                b == null ? Double.NEGATIVE_INFINITY : b.getValue(),
                a == null ? Double.NEGATIVE_INFINITY : a.getValue()));
        for (com.bjsp123.rl2.model.Item item : snapshot) {
            if (!isUsableByAi(mob, item, level)) continue;
            // Wands that pass isWandUsableByAi already represent "best damage
            // option vs. safe to fire" - skip the chance roll so a mage holding
            // a viable wand doesn't idle every other turn. Mirrors the always-
            // use treatment ranged-behavior mobs get above.
            boolean isUsableWand =
                    item.useBehavior == com.bjsp123.rl2.model.Item.UseBehavior.WAND;
            if (!alwaysUse && !isUsableWand
                    && RANDOM.nextDouble() >= AI_USE_ITEM_CHANCE) continue;
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
            case CHARGE  -> isChargeUsableByAi(mob, item, level);
            case EAT, GRANT_PERK, POWERUP, TELEPORT, NONE -> false;
        };
    }

    /** AI CHARGE gate. Requires (a) a hostile attack-target within the item's
     *  {@link ItemStats#effectiveRange} Chebyshev radius, (b) the target is
     *  NOT adjacent (Chebyshev > 1 - melee is cheaper than burning a charge),
     *  (c) line-of-sight from caster to target, and (d) at least one
     *  walkable + unoccupied 8-neighbour of the target for the dash to land
     *  on. Mirrors the player-side {@code chargeGrid} in {@code PlayController}. */
    private static boolean isChargeUsableByAi(Mob mob, com.bjsp123.rl2.model.Item item, Level level) {
        if (mob == null || mob.position == null) return false;
        if (item.baseChargeMax > 0 && item.charge < 1f) return false;
        Mob target = nearestAttackTarget(mob, level);
        if (target == null || target.position == null || target.hp <= 0) return false;
        int dx = Math.abs(target.position.tileX() - mob.position.tileX());
        int dy = Math.abs(target.position.tileY() - mob.position.tileY());
        int cheb = Math.max(dx, dy);
        if (cheb <= 1) return false; // already adjacent - just melee
        int effLvl = ItemStats.effectiveLevel(item, mob);
        int dashRange = Math.max(1, ItemStats.effectiveRange(item, effLvl));
        if (cheb > dashRange) return false;
        if (!LevelUtilities.getLineOfSight(level, mob, target.position)) return false;
        int tx = target.position.tileX(), ty = target.position.tileY();
        for (int ndy = -1; ndy <= 1; ndy++) {
            for (int ndx = -1; ndx <= 1; ndx++) {
                if (ndx == 0 && ndy == 0) continue;
                int nx = tx + ndx, ny = ty + ndy;
                if (nx < 0 || ny < 0 || nx >= level.width || ny >= level.height) continue;
                if (level.tiles[nx][ny].blocksMovement()) continue;
                if (MobQueries.mobAt(level, new Point(nx, ny)) != null) continue;
                return true;
            }
        }
        return false;
    }

    /** Heuristic for {@code DRINK} potions - only quaff if it'll actually help.
     *  Damaging potions (non-zero {@code item.damage}, e.g. potion of poison)
     *  are never drunk; buff potions are useful when the buff isn't already
     *  up; the REGENERATION buff additionally requires the mob to actually
     *  be wounded (drinking a healing potion at full HP wastes the potion).
     *  Override: a POISONED mob will drink any potion that would cure the
     *  poison (currently any REGENERATION-applying item), even at full HP. */
    private static boolean wouldDrinkHelp(Mob mob, com.bjsp123.rl2.model.Item item) {
        if (item == null) return false;
        if (item.damage > 0) return false;
        // Depleted charged tools (JADE_CRAB, JADE_FISH, FROG, etc.) can't fire;
        // ItemSystem.useChargedBuffTool would short-circuit anyway, but gating
        // here means the AI doesn't burn a per-item roll on an unusable choice.
        if (item.baseChargeMax > 0 && item.charge < 1f) return false;
        com.bjsp123.rl2.model.Buff.BuffType primary = item.primaryBuff();
        if (primary == null) return false;
        // Cure-poison override: a poisoned mob holding a REGEN-applying item
        // drinks it to strip POISONED, regardless of HP / existing buffs.
        if (BuffSystem.hasBuff(mob, com.bjsp123.rl2.model.Buff.BuffType.POISONED)
                && removesPoison(item)) {
            return true;
        }
        if (BuffSystem.hasBuff(mob, primary)) return false;
        if (primary == com.bjsp123.rl2.model.Buff.BuffType.REGENERATION) {
            return mob.hp < mob.effectiveStats().maxHp;
        }
        return true;
    }

    /** True if using this item applies a buff that strips POISONED. Today
     *  that's any item whose {@code appliesBuff} list contains REGENERATION
     *  (per {@link BuffSystem}'s per-turn REGEN dispatch, which removes a
     *  POISONED stack). Add other cure mechanisms here as they appear. */
    private static boolean removesPoison(com.bjsp123.rl2.model.Item item) {
        if (item == null || item.appliesBuff == null) return false;
        return item.appliesBuff.contains(
                com.bjsp123.rl2.model.Buff.BuffType.REGENERATION);
    }

    private static boolean isGrappleUsableByAi(Mob mob, com.bjsp123.rl2.model.Item item, Level level) {
        if (mob.position == null) return false;
        Mob target = nearestAttackTarget(mob, level);
        if (target == null || target.position == null) return false;
        // No point grappling when already adjacent
        if (LevelFactoryUtils.chebyshev(mob.position, target.position) <= 1) return false;
        int maxSize = Math.max(0, (int) ItemStats.effectivePower(item));
        return target.effectiveStats().size <= maxSize;
    }

    private static boolean isJumpUsableByAi(Mob mob, com.bjsp123.rl2.model.Item item, Level level) {
        if (mob.position == null) return false;
        Mob threat = nearestAttackTarget(mob, level);
        if (threat == null || threat.position == null) return false;
        return pickBestJumpTile(mob, item, level, threat) != null;
    }

    /** Element-wand AI gate. MISSILE / TELEPORT / summon wands always pass
     *  the gate when their preconditions hold. Direct-damage AoE wands
     *  (BLAST, DETONATION, FIRE) pass when there's a hostile target AND no
     *  ally would catch the disc AND the caster's own tile isn't inside the
     *  disc footprint. Utility AoE wands (WATER / OIL / GRASS / FUNGUS) and
     *  BANISHMENT remain deferred - the AI has no heuristic for when those
     *  are useful.
     *
     *  <p>Damage-class wands additionally have to beat the caster's melee
     *  output - "fire a wand only if it's the best damage option this turn"
     *  per the AI rule. A wand that's strictly weaker than what the caster
     *  could land in melee is left in the bag so the caster steps into
     *  melee range and swings instead. */
    private static boolean isWandUsableByAi(Mob mob, com.bjsp123.rl2.model.Item wand, Level level) {
        // Depleted wands can't fire. Without this gate they pass here and
        // then ItemSystem.fireWand silently no-ops at its own charge check,
        // wasting the AI's per-item roll.
        if (wand.baseChargeMax > 0 && wand.charge < 1f) return false;
        if (wand.wandEffect == com.bjsp123.rl2.model.Item.ItemEffect.MISSILE) {
            Mob target = nearestAttackTarget(mob, level);
            if (target == null) return false;
            if (!hasLineOfFire(mob, target, level)) return false;
            if (expectedWandDamage(wand, mob, target) <= 0) return false;
            return wandBeatsMelee(mob, wand, target);
        }
        if (wand.wandEffect == com.bjsp123.rl2.model.Item.ItemEffect.TELEPORT) {
            return nearestAttackTarget(mob, level) != null
                    && pickTeleportDestination(mob, level) != null;
        }
        if (wand.summonsWhenUsed != null) {
            return MobQueries.levelHasRoomForSpawn(level)
                    && MobHooks.freeAdjacentFloor(level, mob.position) != null;
        }
        if (wand.wandEffect == com.bjsp123.rl2.model.Item.ItemEffect.BLAST
                || wand.wandEffect == com.bjsp123.rl2.model.Item.ItemEffect.DETONATION
                || wand.wandEffect == com.bjsp123.rl2.model.Item.ItemEffect.FIRE) {
            Mob target = nearestAttackTarget(mob, level);
            if (target == null || target.position == null) return false;
            if (!hasLineOfFire(mob, target, level)) return false;
            int effLvl = ItemStats.effectiveLevel(wand, mob);
            int effectSize = ItemStats.effectiveSize(wand, effLvl);
            if (effectSize <= 0) return false;
            if (hasAllyInDisc(mob, level, target.position, effectSize)) return false;
            if (casterInDisc(mob, target.position, effectSize)) return false;
            if (expectedWandDamage(wand, mob, target) <= 0) return false;
            return wandBeatsMelee(mob, wand, target);
        }
        return false;
    }

    /** Heuristic for "would firing this wand at this target plausibly deal
     *  damage?". Returns 0 when the target is fundamentally immune to the
     *  effect (flying / fireImmune vs FIRE, non-banishable vs BANISHMENT)
     *  and otherwise nets the wand's stat-based damage against the target's
     *  magic-resist range. AI uses the zero return as an "don't bother
     *  firing" gate; comparators use the magnitude for melee comparison.
     *
     *  <p>Surface contributions (the fire trail WAND_FIRE leaves) are
     *  approximated as a flat bonus tied to {@code effectSize} when the
     *  target can actually be ignited - mobs that ignore the surface get
     *  nothing for it. */
    private static double expectedWandDamage(com.bjsp123.rl2.model.Item wand,
                                             Mob caster, Mob target) {
        if (wand == null || target == null) return 0;
        com.bjsp123.rl2.model.StatBlock ts = target.effectiveStats();
        com.bjsp123.rl2.model.Item.ItemEffect eff = wand.wandEffect;
        if (eff == com.bjsp123.rl2.model.Item.ItemEffect.BANISHMENT) {
            // Treat as a one-shot kill when applicable; otherwise no effect.
            return target.banishable ? 999.0 : 0.0;
        }
        // FIRE wand vs flying or fireImmune target: the projectile's small
        // direct damage is far less useful than the lost surface contribution,
        // and a 3-damage hit isn't enough to chip down anything. Treat the
        // matchup as "wrong wand for this target" and reject outright.
        if (eff == com.bjsp123.rl2.model.Item.ItemEffect.FIRE
                && (ts.flying || ts.fireImmune)) {
            return 0;
        }
        int effLvl = ItemStats.effectiveLevel(wand, caster);
        double base = wand.damage > 0
                ? ItemStats.effectiveDamageRange(wand, effLvl).average()
                : 0;
        double mr = ts.magicResist.average();
        double net = Math.max(0, base - mr);
        if (eff == com.bjsp123.rl2.model.Item.ItemEffect.FIRE) {
            // Surface bonus: fire wands leave a burning patch worth roughly
            // {@code effectSize} ticks of fire damage to a stationary target.
            int effectSize = ItemStats.effectiveSize(wand, effLvl);
            net += 2.0 * Math.max(1, effectSize);
        }
        return net;
    }

    /** Geometric: would the caster's own tile fall inside the prospective
     *  AoE disc centred on {@code target}? Uses the same approximate
     *  Chebyshev radius ({@code ceil(sqrt(effectSize))}) as
     *  {@link #hasAllyInDisc}, keeping the predictor and the resolver
     *  consistent. Returning true means "AI would singe itself - skip". */
    private static boolean casterInDisc(Mob caster, com.bjsp123.rl2.model.Point target,
                                        int effectSize) {
        if (caster == null || caster.position == null || target == null
                || effectSize <= 0) return false;
        int approxRadius = (int) Math.ceil(Math.sqrt(effectSize));
        int dx = Math.abs(caster.position.tileX() - target.tileX());
        int dy = Math.abs(caster.position.tileY() - target.tileY());
        return Math.max(dx, dy) <= approxRadius;
    }

    /** True if firing {@code wand} at {@code target} would land at least as
     *  much damage as the caster's straight-up melee swing. Out-of-melee
     *  targets (Chebyshev > 1) auto-pass since melee output is 0 from there.
     *  Uses {@link #expectedWandDamage} so resistances / immunities feed
     *  into the comparison too: a fire wand against a flying ghost reports
     *  zero expected damage and loses to even a 1-damage dagger. */
    private static boolean wandBeatsMelee(Mob caster, com.bjsp123.rl2.model.Item wand,
                                          Mob target) {
        if (caster == null || target == null || target.position == null
                || caster.position == null) return true;
        int dx = Math.abs(caster.position.tileX() - target.position.tileX());
        int dy = Math.abs(caster.position.tileY() - target.position.tileY());
        if (Math.max(dx, dy) > 1) return true;
        double wandMean = expectedWandDamage(wand, caster, target);
        double meleeMean = caster.effectiveStats().damage.average();
        return wandMean >= meleeMean;
    }

    /** True if any live mob with ALLY attitude to {@code caster} stands
     *  within the prospective effect disc of an AoE wand centred on
     *  {@code target}. Used by the AI to skip casting an AoE wand into a
     *  pack of allies. Disc radius is approximated as
     *  {@code ceil(sqrt(effectSize))} (Chebyshev) - over-cautious so the
     *  AI may skip borderline-safe casts, never friendly-fire. */
    private static boolean hasAllyInDisc(Mob caster, Level level,
                                         com.bjsp123.rl2.model.Point target,
                                         int effectSize) {
        if (level == null || target == null || effectSize <= 0) return false;
        int approxRadius = (int) Math.ceil(Math.sqrt(effectSize));
        int tx = target.tileX(), ty = target.tileY();
        for (Mob m : level.mobs) {
            if (m == null || m == caster || m.hp <= 0 || m.position == null) continue;
            int dx = Math.abs(m.position.tileX() - tx);
            int dy = Math.abs(m.position.tileY() - ty);
            if (Math.max(dx, dy) > approxRadius) continue;
            if (isAlly(caster, m)) return true;
        }
        return false;
    }

    /** True iff {@code shooter} both sees {@code target} AND has an unobstructed
     *  projectile path to it. CRYSTAL_DOOR is sight-transparent but blocks
     *  projectiles, so without the second check a mob lobs bombs / fires wands at
     *  a target it can't actually hit (e.g. the player sealed outside a beacon
     *  room); the shot clips short and splashes its own allies (RL-16). Mirrors
     *  the gate in {@link #tryRangedShot}. */
    private static boolean hasLineOfFire(Mob shooter, Mob target, Level level) {
        if (target == null || target.position == null) return false;
        return LevelUtilities.getLineOfSight(level, shooter, target.position)
                && projectileLineReaches(level, shooter.position, target.position, shooter);
    }

    /** True iff the mob has at least one hostile in throw range, a clear line of
     *  fire to it, and no ally inside the bomb's AOE around that target. */
    private static boolean canThrowBombAtSomeone(Mob thrower, com.bjsp123.rl2.model.Item bomb, Level level) {
        Mob target = nearestAttackTarget(thrower, level);
        if (target == null || target.position == null) return false;
        int d = Math.max(Math.abs(target.position.tileX() - thrower.position.tileX()),
                         Math.abs(target.position.tileY() - thrower.position.tileY()));
        if (d > AI_BOMB_THROW_RANGE) return false;
        if (!hasLineOfFire(thrower, target, level)) return false;
        return !allyInBombAoe(thrower, target.position, bomb, level);
    }

    /** Walk the bomb's effect disc around {@code centre}; return true the
     *  moment any tile holds a mob the thrower considers ALLY. */
    private static boolean allyInBombAoe(Mob thrower, com.bjsp123.rl2.model.Point centre,
                                         com.bjsp123.rl2.model.Item bomb, Level level) {
        int radius = ItemStats.effectiveSize(bomb);
        int r2 = radius * radius;
        int cx = centre.tileX(), cy = centre.tileY();
        for (Mob m : level.mobs) {
            if (m == thrower || m.hp <= 0 || m.position == null) continue;
            int dx = m.position.tileX() - cx;
            int dy = m.position.tileY() - cy;
            if (dx * dx + dy * dy > r2) continue;
            if (isAlly(thrower, m)) return true;
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
            case GRAPPLE -> {
                if (!aiCastGrapple(mob, item, level)) return false;
                com.bjsp123.rl2.util.ActionTracker.bumpTool(mob);
            }
            case JUMP    -> {
                if (!aiCastJump(mob, item, level)) return false;
                com.bjsp123.rl2.util.ActionTracker.bumpTool(mob);
            }
            case CHARGE  -> {
                if (!aiCastCharge(mob, item, level)) return false;
                com.bjsp123.rl2.util.ActionTracker.bumpTool(mob);
            }
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
            return caster.ticksTillMove != before;
        }
        // AoE damage wands: fire at the nearest hostile (gate already verified
        // no allies in the disc via isWandUsableByAi).
        if (wand.wandEffect == com.bjsp123.rl2.model.Item.ItemEffect.BLAST
                || wand.wandEffect == com.bjsp123.rl2.model.Item.ItemEffect.DETONATION
                || wand.wandEffect == com.bjsp123.rl2.model.Item.ItemEffect.FIRE) {
            Mob target = nearestAttackTarget(caster, level);
            if (target == null || target.position == null) return false;
            ItemSystem.fireWand(level, caster, wand, target.position);
            return caster.ticksTillMove != before;
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

    /** AI CHARGE cast - dash to the nearest attack-target's tile. The CHARGE
     *  use itself picks the arrival tile and applies the swing + knockback via
     *  {@link ItemSystem#castCharge}; this helper just provides the target
     *  position. Gate ({@link #isChargeUsableByAi}) already verified range +
     *  LoS + free arrival tile. */
    private static boolean aiCastCharge(Mob mob, com.bjsp123.rl2.model.Item item, Level level) {
        int before = mob.ticksTillMove;
        Mob target = nearestAttackTarget(mob, level);
        if (target == null || target.position == null) return false;
        ItemSystem.castCharge(level, mob, item, target.position);
        return mob.ticksTillMove != before;
    }

    /** Find the tile in JUMP radius that maximises Chebyshev distance from {@code threat}.
     *  Returns null when no reachable tile improves on the mob's current distance. */
    private static com.bjsp123.rl2.model.Point pickBestJumpTile(
            Mob mob, com.bjsp123.rl2.model.Item item, Level level, Mob threat) {
        if (mob.position == null || threat.position == null) return null;
        int effLvl = ItemStats.effectiveLevel(item, mob);
        int radius = Math.max(0, ItemStats.effectiveRange(item, effLvl));
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
                wakeMob(level, mob, "sensing something nearby");
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
                wakeMob(level, mob, "sensing something nearby");
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
        // Sight isn't enough on its own: CRYSTAL_DOOR is transparent to sight
        // but blocks projectiles, so without this gate a crossbowman would
        // happily plink arrows into the door all day. projectileLineReaches
        // also rejects shots that would hit an intervening mob (incl. ally).
        if (!projectileLineReaches(level, shooter.position, target.position, shooter)) return false;
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
                    cooldownTurns * TurnSystem.STANDARD_TURN_TICKS, shooter);
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
                wakeMob(level, mob, "sensing something nearby");
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
    private static final int HIDING_DURATION_TICKS = 5 * TurnSystem.STANDARD_TURN_TICKS;

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
                    /*level=*/1, HIDING_DURATION_TICKS, mob);
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
            if (d <= stealthScaledRadius(m, radius)) {
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
        return RANDOM.nextDouble() >= noticeProb;            // true = failed to notice
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
            if (d <= stealthScaledRadius(m, radius)) return true;
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
                if (d > stealthScaledRadius(m, baseVision)) continue;
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
    /** Player / AI throw entry point. Removes the item from inventory,
     *  emits the {@link com.bjsp123.rl2.event.GameEvent.ItemThrown}
     *  projectile event, AND applies the impact synchronously.
     *
     *  <p><b>DO NOT DEFER THE IMPACT.</b> This function MUST call
     *  {@link #applyThrowImpact} before returning. If you let the Animator's
     *  PendingImpact / arc-completion callback drive impact instead, the
     *  defender gets one or more game ticks to step out of the AoE before
     *  damage lands, and the throw silently misses. This regression has been
     *  introduced and re-fixed multiple times; if you find this function
     *  fires {@code ItemThrown} but does NOT call {@code applyThrowImpact},
     *  you are looking at the regression - re-apply the synchronous call.
     *
     *  <p>The core invariant for ALL ranged attacks in this game:
     *  <b>the attacker must complete the attack and deal damage before the
     *  defender gets to move.</b> Throws, wand projectiles, charges, and
     *  every other ranged effect must obey this. The on-screen visual arc
     *  is cosmetic ONLY; it never drives world-state mutation.
     *
     *  <p>Visual presentation: the rgame Animator may schedule a delay on
     *  damage popups / death fades / knockback frames / ignition flashes so
     *  they read as "the bomb arc lands, then the target reacts" - but
     *  that's a renderer-side concern and does NOT change the fact that the
     *  rlib world state is fully mutated here, synchronously, at throw
     *  time. */
    public static void throwItem(Level level, Mob thrower, Item it, Point dst) {
        if (thrower == null || it == null || dst == null) return;
        // Single authoritative throw-eligibility gate: only THROWN weapons and
        // consumable throwables (bombs/potions/orbs/tools) can be thrown -
        // wielded gear and generic items can't, even if their data still
        // carries a throwEffect.
        if (!it.isThrowable()) return;
        if (it.inventoryCategory == Item.InventoryCategory.BOMB) {
            com.bjsp123.rl2.util.ActionTracker.bumpBomb(thrower);
        } else {
            com.bjsp123.rl2.util.ActionTracker.bumpThrow(thrower);
        }
        // "Adventurer throws the fire bomb." Bombs are notable, so log EVERY bomb
        // throw visibly - including mob throws, which used to be LOW-priority and
        // hidden (RL-35). Other thrown items remain player-only in the log.
        EventLog.add(Messages.itemThrown(nameForLog(level, thrower),
                it.name != null ? it.name : it.type,
                thrower.behavior == Behavior.PLAYER
                        || it.inventoryCategory == Item.InventoryCategory.BOMB));
        // Thrown items are always consumed from the thrower's bag. (The old
        // BOMB_DODGER "bomb survives the throw" regeneration is gone - RL-34
        // reworks the perk into catching ENEMY bombs instead; see applyThrowImpact.)
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
        queuePendingImpact(level, () -> applyThrowImpact(level, throwerFinal, itFinal, impactFinal));
    }

    /**
     * Apply the world-state mutations of a throw - door open, damage, bomb /
     * potion / cloud effects, tame-on-throw, knockback, and the
     * {@link Item.ThrowResult} fate (consume / return / drop).
     *
     * <p><b>Must run synchronously from {@link #throwItem}, not from an
     * Animator PendingImpact / arc-completion callback.</b> If you wire
     * this through the Animator instead, every defender gets free ticks to
     * step out of the AoE before damage lands — the warrior-bomb-dud
     * regression. See the throwItem javadoc for the invariant: ranged
     * attackers must complete the attack and deal damage before the
     * defender can move.
     *
     * <p>The on-screen visual sequence (arc flies → impact flash → damage
     * popup) is preserved by the rgame Animator scheduling visual-FX
     * playback against the arc duration. World-state mutation is decoupled
     * from that visual delay and happens here.
     */
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
                    && getAttitudeToMob(thrower, player) == Attitude.ATTACK) {
                int pLvl = player.perks == null ? 0
                        : player.perks.getOrDefault(com.bjsp123.rl2.model.Perk.BOMB_DODGER, 0);
                int dist = Math.max(Math.abs(tx - player.position.tileX()),
                                    Math.abs(ty - player.position.tileY()));
                if (pLvl > 0 && dist <= 3 && RANDOM.nextDouble() < 0.25 + 0.05 * pLvl) {
                    it.location = null;
                    if (InventorySystem.addToBag(player.inventory, it)) {
                        EventLog.add(Messages.bombCaught(nameForLog(level, player),
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
            if (target != null && !isAlly(target, thrower)) {
                // Single-target throws (throwing knives, javelins) roll-to-hit using
                // the same accuracy-vs-evasion math as melee. AOE bombs and wands
                // never roll - they always land at the target tile. A miss emits a
                // log line + yellow "miss" floater via the standard DamageDealt(MISS)
                // animator path; the item still lands on the tile (handled outside
                // this branch).
                if (!rollRangedHit(thrower, target, 0)) {
                    String cn = thrower != null && thrower.name != null
                            ? thrower.name
                            : com.bjsp123.rl2.logic.TextCatalog.get("eventlog.fallback.adventurer");
                    String vn = nameForLog(level, target);
                    boolean attackerIsPlayer = thrower != null
                            && thrower.behavior == Behavior.PLAYER;
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
                    int dmg = rollRange(ItemStats.effectiveDamageRange(it));
                    dmg = applySurpriseIfNeeded(level, thrower, target, dmg,
                            AttackType.THROWN, DamageElement.PHYSICAL);
                    processAttack(level, thrower, target, dmg, AttackType.THROWN, DamageElement.PHYSICAL,
                            null, new DamageCause(thrower, it, "throw"));
                    BrandSystem.applyBrandOnHit(level, thrower, target, it);
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
                EventLog.add(Messages.mobTamed(nameForLog(level, thrower), nameForLog(level, target)));
                consumedByTame = true;
            }
        }
        int lvl = ItemStats.effectiveLevel(it, thrower);
        // Bomb damage and AoE come from ItemSystem so every bomb-class throw shares
        // the same level-scaling formula with the rest of the item-stat math.
        int bombDamage = rollRange(ItemStats.effectiveDamageRange(it, lvl));
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
                processAttack(level, thrower, target,
                        scaledBombDamage(target, it, bombDamage),
                        AttackType.THROWN, DamageElement.PHYSICAL,
                        null, new DamageCause(thrower, it, "throw"));
                BrandSystem.applyBrandOnHit(level, thrower, target, it);
                if (!bombBuffsIgnored(target, it)) {
                    BuffSystem.apply(level, target, com.bjsp123.rl2.model.Buff.BuffType.ON_FIRE,
                            Math.max(1, lvl),
                            Math.max(TurnSystem.STANDARD_TURN_TICKS,
                                    (3 + lvl) * TurnSystem.STANDARD_TURN_TICKS),
                            thrower, it);
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
                                Math.max(1, lvl),
                                Math.max(TurnSystem.STANDARD_TURN_TICKS,
                                        WATER_STEP_BUFF_TICKS + lvl * TurnSystem.STANDARD_TURN_TICKS),
                                thrower, it);
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
                    knockBack(level, m, kb, dst, 0, kbCause);
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
                        Math.max(1, lvl),
                        Math.max(TurnSystem.STANDARD_TURN_TICKS,
                                (5 + lvl) * TurnSystem.STANDARD_TURN_TICKS),
                        thrower, it);
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
                int smokeDur = RANDOM.nextInt(4);
                if (smokeDur > 0) {
                    CloudSystem.addCloud(level, x, y,
                            com.bjsp123.rl2.model.Level.Cloud.SMOKE, smokeDur);
                }
                Mob m = MobQueries.mobAt(level, p);
                if (m != null && m != thrower) {
                    processAttack(level, thrower, m,
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
                    knockBack(level, m, it.knockbackSquares, dst, 0, kbCause);
                }
            }
        } else if (te == ItemEffect.APPLYBUFFS && inBounds
                && it.appliesBuff != null && !it.appliesBuff.isEmpty()) {
            // APPLYBUFFS - every buff in the item's pipe-list is applied to
            // every mob in the disc.
            int buffLvl = ItemStats.effectiveBuffLevel(it);
            int buffDur = ItemStats.effectiveBuffDuration(it);
            for (Point p : disc) {
                Mob m = MobQueries.mobAt(level, p);
                if (m == null || m.hp <= 0) continue;
                if (bombBuffsIgnored(m, it)) continue;
                for (com.bjsp123.rl2.model.Buff.BuffType b : it.appliesBuff) {
                    BuffSystem.apply(level, m, b, buffLvl, buffDur, thrower, it);
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
                processAttack(level, thrower, target,
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
                        Math.max(1, lvl),
                        Math.max(TurnSystem.STANDARD_TURN_TICKS,
                                (6 + lvl) * TurnSystem.STANDARD_TURN_TICKS),
                        thrower, it);
                if (m != thrower) {
                    m.ticksTillMove += TurnSystem.STANDARD_TURN_TICKS;
                }
            }
        } else if (te == ItemEffect.VOID && inBounds) {
            ItemSystem.applyVoidImpact(level, dst, lvl);
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
            for (Mob m : toScatter) scatterMobAcrossWorld(level, m);
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

    /** Teleport {@code mob} to a random walkable, unoccupied tile on a
     *  random level of {@code srcLevel.world}. The destination level may
     *  be the same as the source. Emits a {@link com.bjsp123.rl2.event.GameEvent.MobTeleported}
     *  on the level where the mob ends up so the existing teleport-fade
     *  visual plays. Falls back gracefully (no-op) if no walkable tile can
     *  be found after a bounded number of tries. */
    private static void scatterMobAcrossWorld(Level srcLevel, Mob mob) {
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
            com.bjsp123.rl2.model.Level cand = viable.get(RANDOM.nextInt(viable.size()));
            int x = RANDOM.nextInt(cand.width);
            int y = RANDOM.nextInt(cand.height);
            if (!cand.tiles[x][y].isFloorLike()) continue;
            if (MobQueries.mobAt(cand, new Point(x, y)) != null) continue;
            dst = cand;
            dx  = x;
            dy  = y;
            break;
        }
        if (dst == null) return;

        Point fromPoint = mob.position;
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

    /** Ignite a single tile when it carries something flammable - an oil
     *  surface, or grass / mushroom / tree vegetation. Bare floor and stone
     *  don't catch. Used by the wand-of-blast / DETONATION path so a
     *  concussive blast only spreads fire where there's actually something
     *  to burn. */
    public static void igniteIfFlammable(Level level, int x, int y) {
        if (x < 0 || y < 0 || x >= level.width || y >= level.height) return;
        Level.Surface s = level.surface[x][y];
        Level.Vegetation v = level.vegetation[x][y];
        boolean flammable = (s == Level.Surface.OIL)
                || (v == Level.Vegetation.GRASS
                        || v == Level.Vegetation.MUSHROOMS
                        || v == Level.Vegetation.TREES);
        if (flammable) FireSystem.ignite(level, x, y);
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
