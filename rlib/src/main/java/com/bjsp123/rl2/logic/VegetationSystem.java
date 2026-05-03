package com.bjsp123.rl2.logic;

import com.bjsp123.rl2.model.Level;
import com.bjsp123.rl2.model.Level.Surface;
import com.bjsp123.rl2.model.Level.Vegetation;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Per-turn vegetation spread. Every <b>game turn</b> (see {@link #tickPerTurn}), each cell
 * that already holds vegetation gets a single roll to grow the same species onto a random
 * cardinally-adjacent empty floor. The chance is computed from the <em>target</em> cell's
 * conditions — not the source — so spread tracks lighting and surface geography rather
 * than how the source patch started.
 *
 * <h3>Rates</h3>
 * <ul>
 *   <li>{@link Vegetation#GRASS}: 0% base. +0.2% if lit, +0.2% if cardinally adjacent to a
 *       water tile, plus a +0.6% synergy bonus when both conditions hold (a sunlit
 *       streamside cell creeps roughly 5× faster than either factor alone).</li>
 *   <li>{@link Vegetation#MUSHROOMS}: 0.125% base in the dark. Within Chebyshev range 2 of a
 *       blood surface jumps to 2%, and an unlit blood-adjacent cell doubles again to 4% —
 *       so dark fleshy corners seed the fastest.</li>
 *   <li>Existing grass / mushroom tiles that pick up an {@link Surface#OIL} surface
 *       (typically from an oil splash thrown over them) wither at
 *       {@value #OIL_DECAY_CHANCE_PCT}% per turn until cleared.</li>
 * </ul>
 */
public final class VegetationSystem {

    private static final Random RANDOM = new Random();
    private static final int[][] DIRS = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};

    private VegetationSystem() {}

    /**
     * Run one spread roll across {@code level}. Called once <b>per game turn</b> from
     * {@code PlayScreen.tick}, alongside {@link FireSystem#tickPerTurn}, so the random
     * per-cell chances compound at the designed rate. Uses a snapshot of the vegetation
     * grid so a patch that grows this turn doesn't immediately seed another in the same
     * turn.
     */
    /** Per-turn chance that a lit mushroom tile withers away and becomes bare floor. */
    private static final double MUSHROOM_LIT_DECAY_CHANCE = 0.01;
    /** Per-turn chance that a grass/mushroom tile sitting on an OIL surface withers.
     *  Oil-doused vegetation rots fast — moderate per-tile chance, so a thrown
     *  oil-splash bomb visibly clears flora over the next handful of turns. */
    private static final double OIL_DECAY_CHANCE = 0.05;
    /** Display copy of {@link #OIL_DECAY_CHANCE} for the class Javadoc. */
    private static final int OIL_DECAY_CHANCE_PCT = 5;

    public static void tickPerTurn(Level level) {
        int w = level.width, h = level.height;
        Vegetation[][] snap = new Vegetation[w][h];
        for (int x = 0; x < w; x++) {
            System.arraycopy(level.vegetation[x], 0, snap[x], 0, h);
        }
        for (int x = 0; x < w; x++) {
            for (int y = 0; y < h; y++) {
                Vegetation v = snap[x][y];
                if (v == null) continue;
                // Oil rot — applies to grass and mushrooms alike. Trees and fire
                // vegetation are immune. Rolled before the lit-decay/spread paths so a
                // dying tile doesn't get one last seeding roll on the way out.
                if ((v == Vegetation.GRASS || v == Vegetation.MUSHROOMS)
                        && level.surface[x][y] == Surface.OIL
                        && RANDOM.nextDouble() < OIL_DECAY_CHANCE) {
                    level.vegetation[x][y] = null;
                    continue;
                }
                // Mushrooms wither in light — rolled per-tile, per-turn, using the snapshot
                // so a mushroom seeded this same tick gets its first decay roll next tick.
                if (v == Vegetation.MUSHROOMS
                        && level.lit != null && level.lit[x][y]
                        && RANDOM.nextDouble() < MUSHROOM_LIT_DECAY_CHANCE) {
                    level.vegetation[x][y] = null;
                    continue;  // no spread from a tile that just died this tick
                }
                trySpread(level, x, y, v, snap);
            }
        }
    }

    private static void trySpread(Level level, int x, int y, Vegetation v, Vegetation[][] snap) {
        List<int[]> candidates = new ArrayList<>(4);
        for (int[] d : DIRS) {
            int nx = x + d[0], ny = y + d[1];
            if (nx < 0 || ny < 0 || nx >= level.width || ny >= level.height) continue;
            if (!level.tiles[nx][ny].isFloorLike()) continue;
            if (snap[nx][ny] != null) continue;
            // Liquids drown flora — grass and mushrooms can't take root on a water/blood/oil
            // tile. Pre-seeded vegetation placed by LevelFactory is untouched by this rule.
            if (level.surface[nx][ny] != null) continue;
            candidates.add(new int[]{nx, ny});
        }
        if (candidates.isEmpty()) return;
        int[] target = candidates.get(RANDOM.nextInt(candidates.size()));
        double chance = spreadChance(level, target[0], target[1], v);
        if (chance <= 0) return;
        if (RANDOM.nextDouble() < chance) {
            level.vegetation[target[0]][target[1]] = v;
        }
    }

    /** Chance for {@code v} to take root at the target cell, given the target's conditions. */
    private static double spreadChance(Level level, int x, int y, Vegetation v) {
        boolean lit = level.lit != null && level.lit[x][y];
        switch (v) {
            case GRASS: {
                // Lit and water-adjacent each contribute a small base chance, plus a
                // synergy bonus when both hold so a sunlit streamside cell creeps
                // visibly faster than either dry-and-lit or shaded-near-water alone.
                double c = 0.0;
                boolean nearWater = adjacentTo(level, x, y, Surface.WATER);
                if (lit) c += 0.002;
                if (nearWater) c += 0.002;
                if (lit && nearWater) c += 0.006;
                return c;
            }
            case MUSHROOMS: {
                // Base rate is 1/8 of the previous flat 0.01 — fungi creep slowly when
                // out in the open. Within Chebyshev distance 2 of a blood surface the
                // rate jumps to 2% (lit blood-adjacent corners) and 4% in the dark, so a
                // shaded corpse pile is the densest seeding ground in the dungeon.
                // Blood proximity dominates lit-suppression: a corpse pile behind a
                // torch still feeds mushrooms, just at half rate.
                boolean nearBlood = withinNTiles(level, x, y, Surface.BLOOD, 2);
                if (nearBlood) return lit ? 0.02 : 0.04;
                if (lit) return 0.0;   // light is a dealbreaker outside blood range
                return 0.00125;
            }
            default: return 0.0;
        }
    }

    /** True if (x, y) is cardinally adjacent to a tile holding {@code s}, but not on one. */
    private static boolean adjacentTo(Level level, int x, int y, Surface s) {
        if (level.surface[x][y] == s) return false;
        for (int[] d : DIRS) {
            int nx = x + d[0], ny = y + d[1];
            if (nx < 0 || ny < 0 || nx >= level.width || ny >= level.height) continue;
            if (level.surface[nx][ny] == s) return true;
        }
        return false;
    }

    /** True if any tile within Chebyshev distance {@code n} of (x, y) carries surface {@code s}.
     *  The source cell itself counts. */
    private static boolean withinNTiles(Level level, int x, int y, Surface s, int n) {
        int x0 = Math.max(0, x - n), x1 = Math.min(level.width  - 1, x + n);
        int y0 = Math.max(0, y - n), y1 = Math.min(level.height - 1, y + n);
        for (int yy = y0; yy <= y1; yy++)
            for (int xx = x0; xx <= x1; xx++)
                if (level.surface[xx][yy] == s) return true;
        return false;
    }
}
