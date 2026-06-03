package com.bjsp123.rl2.logic;

import com.bjsp123.rl2.model.Buff;
import com.bjsp123.rl2.model.Level;
import com.bjsp123.rl2.model.Level.Cloud;
import com.bjsp123.rl2.model.Level.Surface;
import com.bjsp123.rl2.model.Level.Vegetation;
import com.bjsp123.rl2.model.Mob;

import java.util.Random;

/**
 * Per-turn evolution of the {@link Level#cloud} layer - the gaseous overlay that
 * sits on top of vegetation / surfaces and drifts around the level.
 *
 * <p>Each cell in {@code level.cloud} packs a {@link Cloud} type and a duration
 * (turns until the cloud dissipates) into a single int - see
 * {@link #pack(Cloud, int)}, {@link #typeAt(Level, int, int)},
 * {@link #durationAt(Level, int, int)}. Reads / writes go through this class so
 * the encoding stays contained.
 *
 * <p>Per-turn flow (driven by {@link TurnSystem#tick} via the standard-turn pass):
 * <ol>
 *   <li>Apply effects - every mob standing on a {@link Cloud#POISON} cell gets
 *       a fresh {@link Buff.BuffType#POISONED} buff.</li>
 *   <li>Spread - every cell with {@code duration >= 2} has a 30% chance to
 *       transfer half its duration (rounded up) to a random adjacent cell;
 *       the source's duration is also halved (rounded up).</li>
 *   <li>Decay - every non-empty cell loses 1 duration; cells that hit 0 clear
 *       back to {@code 0}.</li>
 *   <li>Emission - fire vegetation rolls 25% to spawn smoke (duration 5);
 *       water surfaces adjacent to fire roll 50% to spawn steam (duration 3).</li>
 * </ol>
 *
 * <p>Smoke blocks sight + light (consulted by {@code LevelSystem.buildBlocking});
 * projectile traces ignore clouds entirely.
 */
public final class CloudSystem {

    private CloudSystem() {}

    /** Maximum cloud duration in standard turns. {@link #addCloud} clamps to
     *  this; saturation behaviour matches the engine's expectation that no
     *  cloud lingers beyond a dozen turns regardless of stacking. */
    public static final int MAX_DURATION = 12;

    /** Per-tile per-turn chance a cloud with duration >= 2 spreads to a
     *  neighbour. */
    public static final double SPREAD_CHANCE = 0.30;
    /** Per-fire-tile per-turn chance to emit a smoke cloud (duration 5). */
    public static final double SMOKE_EMIT_CHANCE = 0.25;
    /** Smoke duration emitted by a fire tile. */
    public static final int SMOKE_EMIT_DURATION = 5;
    /** Per-water-tile per-turn chance to emit a steam cloud (duration 3),
     *  conditional on adjacent fire. */
    public static final double STEAM_EMIT_CHANCE = 0.50;
    /** Steam duration emitted by a water tile next to fire. */
    public static final int STEAM_EMIT_DURATION = 3;

    /** Buff strength applied by a poison cloud each turn. */
    private static final int POISON_BUFF_LEVEL = 1;
    /** Buff duration (turns) applied per poison-cloud tick. Short - the cloud
     *  re-applies it next turn if the mob is still standing in it. */
    private static final int POISON_BUFF_DURATION_TICKS = 2 * TurnSystem.STANDARD_TURN_TICKS;

    private static final Random RNG =
            com.bjsp123.rl2.util.SimRng.register("CloudSystem", new Random());

    // -- Encoding helpers ----------------------------------------------------

    /** Pack {@code type} + {@code duration} into the single int form stored in
     *  {@link Level#cloud}. {@code duration <= 0} or {@code type == null}
     *  collapses to the empty-cell value 0. */
    public static int pack(Cloud type, int duration) {
        if (type == null || duration <= 0) return 0;
        int d = Math.min(MAX_DURATION, duration);
        return ((type.ordinal() + 1) << 4) | d;
    }

    /** Inverse of {@link #pack} - null when the cell is empty. */
    public static Cloud type(int packed) {
        int t = (packed >> 4) & 0xF;
        if (t <= 0 || t > Cloud.values().length) return null;
        return Cloud.values()[t - 1];
    }

    public static int duration(int packed) {
        return packed & 0xF;
    }

    public static Cloud typeAt(Level level, int x, int y) {
        if (!inBounds(level, x, y)) return null;
        return type(level.cloud[x][y]);
    }

    public static int durationAt(Level level, int x, int y) {
        if (!inBounds(level, x, y)) return 0;
        return duration(level.cloud[x][y]);
    }

    /** True when {@code (x, y)} carries a SMOKE cloud - used by
     *  {@code LevelSystem.buildBlocking} to make smoke opaque to sight + light
     *  without changing the underlying tile. */
    public static boolean smokeAt(Level level, int x, int y) {
        return typeAt(level, x, y) == Cloud.SMOKE;
    }

    // -- Mutation ------------------------------------------------------------

    /** Add (or replace) a cloud at {@code (x, y)}. The merge rule:
     *  <ul>
     *    <li>Empty cell -> set to ({@code type}, {@code duration}).</li>
     *    <li>Same type -> durations add (clamped to {@link #MAX_DURATION}).</li>
     *    <li>Different type -> the incoming type wins, duration is the larger
     *        of the two so a fresh poison plume doesn't get instantly wiped
     *        by leftover smoke.</li>
     *  </ul>
     *  No-op for null / non-positive duration / out-of-bounds coords. */
    public static void addCloud(Level level, int x, int y, Cloud type, int duration) {
        if (!inBounds(level, x, y) || type == null || duration <= 0) return;
        int existing = level.cloud[x][y];
        Cloud existingType = type(existing);
        int existingDur    = duration(existing);
        int newDur;
        Cloud newType;
        if (existingType == null) {
            newType = type;
            newDur  = duration;
        } else if (existingType == type) {
            newType = type;
            newDur  = Math.min(MAX_DURATION, existingDur + duration);
        } else {
            newType = type;
            newDur  = Math.max(existingDur, duration);
        }
        level.cloud[x][y] = pack(newType, newDur);
    }

    // -- Per-turn tick -------------------------------------------------------

    /** One pass of the cloud layer's per-turn behaviour. See class javadoc for
     *  ordering: poison effect -> spread -> decay -> emission. */
    public static void tickPerTurn(Level level) {
        if (level == null || level.cloud == null) return;
        int w = level.width, h = level.height;

        // (1) Apply poison-cloud effects to mobs standing in poison cells.
        applyPoisonEffects(level);

        // (2) Spread - work from a snapshot so clouds don't cascade through
        //     freshly-spread tiles in a single turn.
        int[][] next = new int[w][h];
        for (int x = 0; x < w; x++) System.arraycopy(level.cloud[x], 0, next[x], 0, h);
        for (int x = 0; x < w; x++) {
            for (int y = 0; y < h; y++) {
                int packed = level.cloud[x][y];
                int dur = duration(packed);
                if (dur < 2) continue;
                if (RNG.nextDouble() >= SPREAD_CHANCE) continue;
                Cloud t = type(packed);
                if (t == null) continue;
                int[] nb = pickAdjacentInBounds(level, x, y);
                if (nb == null) continue;
                int transfer = (dur + 1) / 2; // half rounded up
                int srcRem   = (dur + 1) / 2; // halved (rounded up) - spec
                next[x][y] = pack(t, srcRem);
                int destPacked = next[nb[0]][nb[1]];
                Cloud destType = type(destPacked);
                int destDur = duration(destPacked);
                int merged;
                if (destType == null || destType == t) {
                    merged = Math.min(MAX_DURATION, destDur + transfer);
                } else {
                    // Mixed types - incoming wins, duration takes the larger
                    // of the two so we don't reset the cloud's lifetime.
                    merged = Math.min(MAX_DURATION, Math.max(destDur, transfer));
                }
                next[nb[0]][nb[1]] = pack(t, merged);
            }
        }
        for (int x = 0; x < w; x++) System.arraycopy(next[x], 0, level.cloud[x], 0, h);

        // (3) Decay - every non-empty cell drops by 1.
        for (int x = 0; x < w; x++) {
            for (int y = 0; y < h; y++) {
                int packed = level.cloud[x][y];
                int dur = duration(packed);
                if (dur <= 0) continue;
                int newDur = dur - 1;
                if (newDur <= 0) {
                    level.cloud[x][y] = 0;
                } else {
                    Cloud t = type(packed);
                    level.cloud[x][y] = pack(t, newDur);
                }
            }
        }

        // (4) Emission - fire makes smoke; water adjacent to fire makes steam.
        emitFromFireAndWater(level);
    }

    private static void applyPoisonEffects(Level level) {
        if (level.mobs == null) return;
        for (Mob m : level.mobs) {
            if (m == null || m.position == null || m.hp <= 0) continue;
            int x = m.position.tileX(), y = m.position.tileY();
            if (typeAt(level, x, y) != Cloud.POISON) continue;
            BuffSystem.apply(level, m, Buff.BuffType.POISONED,
                    POISON_BUFF_LEVEL, POISON_BUFF_DURATION_TICKS, null);
        }
    }

    private static void emitFromFireAndWater(Level level) {
        if (level.vegetation == null && level.surface == null) return;
        int w = level.width, h = level.height;
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                if (level.vegetation != null
                        && level.vegetation[x][y] == Vegetation.FIRE
                        && RNG.nextDouble() < SMOKE_EMIT_CHANCE) {
                    addCloud(level, x, y, Cloud.SMOKE, SMOKE_EMIT_DURATION);
                }
                if (level.surface != null
                        && level.surface[x][y] == Surface.WATER
                        && hasFireNeighbour(level, x, y)
                        && RNG.nextDouble() < STEAM_EMIT_CHANCE) {
                    addCloud(level, x, y, Cloud.STEAM, STEAM_EMIT_DURATION);
                }
            }
        }
    }

    private static boolean hasFireNeighbour(Level level, int x, int y) {
        if (level.vegetation == null) return false;
        for (int dy = -1; dy <= 1; dy++) {
            for (int dx = -1; dx <= 1; dx++) {
                if (dx == 0 && dy == 0) continue;
                int nx = x + dx, ny = y + dy;
                if (!inBounds(level, nx, ny)) continue;
                if (level.vegetation[nx][ny] == Vegetation.FIRE) return true;
            }
        }
        return false;
    }

    /** 8 neighbour offsets - same indexing as {@link #pickAdjacentInBounds}. */
    private static final int[] NB_DX = { -1,  0,  1, -1, 1, -1, 0, 1 };
    private static final int[] NB_DY = { -1, -1, -1,  0, 0,  1, 1, 1 };

    /** Pick a uniformly-random in-bounds 8-neighbour of {@code (x, y)}, or
     *  null when none exist. */
    private static int[] pickAdjacentInBounds(Level level, int x, int y) {
        int[] order = { 0, 1, 2, 3, 4, 5, 6, 7 };
        for (int i = order.length - 1; i > 0; i--) {
            int j = RNG.nextInt(i + 1);
            int t = order[i]; order[i] = order[j]; order[j] = t;
        }
        for (int idx : order) {
            int nx = x + NB_DX[idx], ny = y + NB_DY[idx];
            if (inBounds(level, nx, ny)) return new int[] { nx, ny };
        }
        return null;
    }

    private static boolean inBounds(Level level, int x, int y) {
        return level != null && x >= 0 && y >= 0 && x < level.width && y < level.height;
    }
}
