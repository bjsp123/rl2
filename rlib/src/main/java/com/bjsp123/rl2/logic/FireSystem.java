package com.bjsp123.rl2.logic;

import com.bjsp123.rl2.model.Level;
import com.bjsp123.rl2.model.Mob;
import com.bjsp123.rl2.model.Level.Surface;
import com.bjsp123.rl2.model.Level.Vegetation;

import java.util.Random;

/**
 * Fire propagation, burnout, particle emission, and on-fire mob bookkeeping.
 *
 * <p>Three time-domain cadences are kept strictly separate. Every {@code tick*} method in
 * this class belongs to exactly one of them, and each name encodes which:
 * <ul>
 *   <li><b>Game ticks</b> - {@link #tickGameTicks}. Called from {@link TurnSystem#tick}'s
 *       standard-turn pass with the size of the standard-turn beat in game ticks (same
 *       units as {@link Mob#moveCost} and {@link TurnSystem#STANDARD_TURN_TICKS}). Drains
 *       fire lifetimes and fire-damage counters in lockstep with the rest of the
 *       time-driven systems (e.g. heal).</li>
 *   <li><b>Game turns</b> - {@link #tickPerTurn}. Called once per "game advancement" from
 *       {@code PlayScreen.tick}, alongside {@link VegetationSystem#tickPerTurn}. Runs the
 *       per-turn random rolls (spread, smoke, mob-vs-tile propagation) at the designed
 *       compounded rate. A single turn may cover many game ticks.</li>
 *   <li><b>Real time</b> - {@link #tickRealTime}. Called once per render frame with the
 *       wall-clock delta in milliseconds. Drives visuals that should keep moving while the
 *       game is paused on input - currently fire-particle emission. Anything in this domain
 *       should be safe to skip on a frame without affecting gameplay state.</li>
 * </ul>
 *
 * <p>Constants follow the same convention: tunables that count in game ticks end with
 * {@code _TICKS}, real-time tunables end with {@code _MS}, anything else has no suffix.
 *
 * <p>Balance tunables now live on {@link GameBalance} (and {@code assets/data/config.csv})
 * so they can be overridden without recompiling.
 */
public final class FireSystem {

    private static final Random RANDOM =
            com.bjsp123.rl2.util.SimRng.register("FireSystem", new Random());
    private static final int[][] DIRS = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};

    private FireSystem() {}

    // ------------------------------------------------------------------------
    // Public ignition entry point
    // ------------------------------------------------------------------------

    /**
     * Try to set fire to ({@code x}, {@code y}). No-op if the cell isn't flammable -
     * specifically: out of bounds, not floor-like, or carrying a water/blood surface. Oil
     * is consumed (cleared) when ignited. Existing vegetation is replaced with FIRE.
     *
     * @return {@code true} if the cell is now on fire (newly ignited or already burning).
     */
    public static boolean ignite(Level level, int x, int y) {
        if (!isFlammable(level, x, y)) return false;
        // Trees burn much longer than other fuel, so check what was on the cell BEFORE we
        // overwrite vegetation with FIRE.
        boolean wasTree = level.vegetation[x][y] == Vegetation.TREES;
        boolean wasFire = level.vegetation[x][y] == Vegetation.FIRE;
        // Oil is consumed by the flame.
        if (level.surface[x][y] == Surface.OIL) level.surface[x][y] = null;
        level.vegetation[x][y]  = Vegetation.FIRE;
        // Only fountain particles on a fresh ignition - refueling an already-
        // burning tile shouldn't double-stamp the visual.
        if (!wasFire) VegetationSystem.emitVegetationChanged(level, x, y, Vegetation.FIRE);
        // Re-roll lifetime even if the tile was already on fire - refueling.
        int durMin = wasTree ? GameBalance.FIRE_DURATION_TREE_MIN_TICKS : GameBalance.FIRE_DURATION_MIN_TICKS;
        int durMax = wasTree ? GameBalance.FIRE_DURATION_TREE_MAX_TICKS : GameBalance.FIRE_DURATION_MAX_TICKS;
        int dur = durMin + RANDOM.nextInt(durMax - durMin + 1);
        level.fireRemaining[x][y]     = dur;
        level.fireEmitCountdown[x][y] = 1;   // emit on the next tick so the player gets feedback
        return true;
    }

    /** A tile is flammable if it's a floor-like cell that isn't covered by water or blood. */
    public static boolean isFlammable(Level level, int x, int y) {
        if (x < 0 || y < 0 || x >= level.width || y >= level.height) return false;
        if (!level.tiles[x][y].isFloorLike()) return false;
        Surface s = level.surface[x][y];
        return s != Surface.WATER && s != Surface.BLOOD;
    }

    // ------------------------------------------------------------------------
    // Game-tick driven updates (lifetimes + on-fire mob damage)
    // ------------------------------------------------------------------------

    /**
     * Drain fire lifetimes and on-fire-mob damage timers by {@code gameTicks}. The unit is
     * <b>game ticks</b> - the same currency as {@link Mob#moveCost}. Called from
     * {@link TurnSystem#tickStandardTurn} once per standard turn (i.e. always with
     * {@link TurnSystem#STANDARD_TURN_TICKS}); the parameter is kept for documentation +
     * test reuse rather than because the cadence still varies.
     *
     * <p>Particle emission is NOT in this method; it lives in {@link #tickRealTime} so
     * embers keep flowing while the player is sitting still.
     */
    public static void tickGameTicks(Level level, int gameTicks) {
        if (gameTicks <= 0) return;
        if (level.fireRemaining == null) return;

        // Per-tile lifetime decay. Per-mob fire damage now lives in
        // {@link BuffSystem#tickPerTurn} via the ON_FIRE buff; this method only handles
        // the environment-side fire countdown.
        for (int x = 0; x < level.width; x++) {
            for (int y = 0; y < level.height; y++) {
                if (level.vegetation[x][y] != Vegetation.FIRE) continue;
                int rem = level.fireRemaining[x][y] - gameTicks;
                if (rem <= 0) {
                    extinguishTile(level, x, y);
                    continue;
                }
                level.fireRemaining[x][y] = rem;
            }
        }
    }

    // ------------------------------------------------------------------------
    // Real-time driven updates (purely visual: particle emission)
    // ------------------------------------------------------------------------

    /**
     * Real-time fire-particle path moved entirely to {@code rgame.anim.Animator}.
     * It polls {@code level.vegetation} for FIRE tiles and {@code BuffSystem.hasBuff}
     * for burning mobs each render frame, drains the per-tile countdown stored in
     * {@code Level.fireEmitCountdown}, and spawns {@code Effect.fireParticle} into
     * its own {@code EffectStage}. Kept as a no-op stub so existing per-frame callers
     * still compile.
     */
    public static void tickRealTime(Level level, int dtMs) {
        // No-op - see Javadoc.
    }

    // ------------------------------------------------------------------------
    // Per-turn updates (spread, smoke, mob-vs-tile propagation)
    // ------------------------------------------------------------------------

    /**
     * Run one fire-spread / smoke roll across the level, plus mob-tile fire propagation.
     * Called once <b>per game turn</b> from {@code PlayScreen.tick}, alongside
     * {@link VegetationSystem#tickPerTurn}, so the random spread chances compound at the
     * designed rate.
     */
    public static void tickPerTurn(Level level) {
        if (level.fireRemaining == null) return;

        // Snapshot the fire grid so a tile lit this turn doesn't seed its own neighbour
        // again immediately.
        int w = level.width, h = level.height;
        boolean[][] wasFire = new boolean[w][h];
        for (int x = 0; x < w; x++)
            for (int y = 0; y < h; y++)
                wasFire[x][y] = level.vegetation[x][y] == Vegetation.FIRE;

        // Smoke emission from fire tiles now lives in {@link CloudSystem#tickPerTurn}
        // - the cloud layer owns its own per-turn drift / spread / spawn logic, so
        // FireSystem just drives fire spread here.
        for (int x = 0; x < w; x++) {
            for (int y = 0; y < h; y++) {
                if (!wasFire[x][y]) continue;
                // Independent spread roll for each of the four cardinal neighbours so a fire
                // surrounded by oil or grass races outward at the per-cell SPREAD_CHANCE_*
                // rate, rather than the old one-direction-per-turn lottery (which capped any
                // tile's effective reach at ~25% even with chance=1.0).
                for (int[] d : DIRS) {
                    trySpreadInto(level, x + d[0], y + d[1], wasFire);
                }
            }
        }

        // Mob <-> tile propagation:
        //   - on water/blood -> extinguish the mob
        //   - on fire -> ignite the mob (unless the same tile is also water/blood: handled by
        //     the order above, since FIRE can't coexist with water/blood - ignite clears oil
        //     and refuses water/blood)
        //   - on-fire mob standing on flammable vegetation/oil -> ignite the tile
        for (Mob m : level.mobs) {
            if (m.hp <= 0) continue;
            int mx = m.position.tileX(), my = m.position.tileY();
            if (mx < 0 || my < 0 || mx >= w || my >= h) continue;
            Surface surf = level.surface[mx][my];
            boolean burning = com.bjsp123.rl2.logic.BuffSystem.hasBuff(m,
                    com.bjsp123.rl2.model.Buff.BuffType.ON_FIRE);
            if (surf == Surface.WATER || surf == Surface.BLOOD) {
                if (burning) {
                    com.bjsp123.rl2.logic.BuffSystem.removeBuff(m,
                            com.bjsp123.rl2.model.Buff.BuffType.ON_FIRE);
                }
                continue;
            }
            if (level.vegetation[mx][my] == Vegetation.FIRE && !burning
                    && !m.effectiveStats().fireImmune) {
                // Fire on the floor sets the mob alight for ~5 turns. Re-applies extend
                // the duration via BuffSystem's max-merge contract.
                com.bjsp123.rl2.logic.BuffSystem.apply(level, m,
                        com.bjsp123.rl2.model.Buff.BuffType.ON_FIRE, 5, null);
                burning = true;
            }
            if (burning) {
                Vegetation v = level.vegetation[mx][my];
                if (v == Vegetation.GRASS || v == Vegetation.MUSHROOMS || v == Vegetation.TREES
                        || surf == Surface.OIL) {
                    ignite(level, mx, my);
                }
            }
        }
    }

    private static void trySpreadInto(Level level, int nx, int ny, boolean[][] wasFire) {
        if (nx < 0 || ny < 0 || nx >= level.width || ny >= level.height) return;
        if (!isFlammable(level, nx, ny)) return;
        if (wasFire[nx][ny])             return;   // already burning at the start of this turn
        double chance;
        if (level.surface[nx][ny] == Surface.OIL) chance = GameBalance.SPREAD_CHANCE_OIL;
        else if (level.vegetation[nx][ny] == Vegetation.GRASS) chance = GameBalance.SPREAD_CHANCE_GRASS;
        else if (level.vegetation[nx][ny] != null) chance = GameBalance.SPREAD_CHANCE_VEGETATION;
        else chance = GameBalance.SPREAD_CHANCE_BARE;
        if (RANDOM.nextDouble() < chance) ignite(level, nx, ny);
    }

    private static void extinguishTile(Level level, int x, int y) {
        if (level.vegetation[x][y] == Vegetation.FIRE) level.vegetation[x][y] = null;
        level.fireRemaining[x][y]     = 0;
        level.fireEmitCountdown[x][y] = 0;
    }
}
