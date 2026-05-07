package com.bjsp123.rl2.model;

import com.bjsp123.rl2.logic.GameBalance;
import com.bjsp123.rl2.logic.LevelFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Procedural world generator. Reads {@link GameBalance#DUNGEON_DEPTH},
 * {@link GameBalance#SIDE_BRANCH_PROBABILITY}, and
 * {@link GameBalance#CROSSLINK_PROBABILITY} to build a layered DAG of
 * {@link Level}s and wires the per-level {@code stairs(Up|Down)(Alt)Target}
 * indices that drive both inter-level transitions and {@code MapScreen}.
 *
 * <p>Shape: depth 1 and {@code DUNGEON_DEPTH} are single CENTER levels. Every
 * intermediate depth carries one WEST + one EAST level. From each E/W level at
 * depth {@code d}:
 * <ul>
 *   <li>The default downstair always reaches the same-side level at {@code d+1}
 *       (or the bottom CENTER if {@code d+1 == DUNGEON_DEPTH}).</li>
 *   <li>With probability {@code CROSSLINK_PROBABILITY}, a second downstair
 *       reaches the opposite-side level at {@code d+1} — only relevant when
 *       both sides exist there ({@code d+1 < DUNGEON_DEPTH}).</li>
 *   <li>With probability {@code SIDE_BRANCH_PROBABILITY}, a second downstair
 *       reaches a fresh dead-end "side branch" level at the same depth, column
 *       ±2 of the parent. Mutually exclusive with the crosslink — a level can
 *       grow at most one extra downstair.</li>
 * </ul>
 *
 * <p>Top + bottom CENTER levels reach all surviving {@code d=2} / {@code d=N-1}
 * E/W rows via their primary + alt stair pair. {@code DUNGEON_DEPTH = 2}
 * collapses to a CENTER-CENTER pair with a single edge between them.
 */
public final class WorldTopology {

    private WorldTopology() {}

    /** Build a fresh world. {@code rng} drives every probability roll; pass a
     *  reproducible seed to get a deterministic dungeon. The {@code unique}
     *  tracker is threaded through to {@link LevelFactory#createDungeonLevel} so
     *  unique themed rooms only spawn once across the whole world. */
    public static Level[] build(int width, int height, Random rng, UniqueTracker unique) {
        int depth = Math.max(2, GameBalance.DUNGEON_DEPTH);
        double pBranch = clamp01(GameBalance.SIDE_BRANCH_PROBABILITY);
        double pCross  = clamp01(GameBalance.CROSSLINK_PROBABILITY);

        List<Level> out = new ArrayList<>();

        // 1. Allocate the main spine. mainW[d] / mainE[d] hold the level index
        //    at depth d on the WEST / EAST side; -1 where the slot doesn't exist.
        int[] mainW = new int[depth + 1];   // index by depth
        int[] mainE = new int[depth + 1];
        int[] mainC = new int[depth + 1];   // for top + bottom CENTER
        for (int d = 0; d <= depth; d++) { mainW[d] = mainE[d] = mainC[d] = -1; }

        // Top CENTER (depth 1).
        mainC[1] = addLevel(out, width, height, /*hasUp=*/false, /*hasDown=*/true,
                /*depth=*/1, Level.Side.CENTER, /*column=*/0f, unique, rng);

        // Intermediate depths: one W + one E each.
        for (int d = 2; d < depth; d++) {
            mainW[d] = addLevel(out, width, height, true, true, d, Level.Side.WEST, -1f, unique, rng);
            mainE[d] = addLevel(out, width, height, true, true, d, Level.Side.EAST, +1f, unique, rng);
        }

        // Bottom CENTER (depth = DUNGEON_DEPTH).
        mainC[depth] = addLevel(out, width, height, true, false, depth, Level.Side.CENTER, 0f, unique, rng);

        // 2. Wire the spine — same-side downstairs at every depth d ∈ [2, N-1].
        //    Top CENTER feeds 2W + 2E; bottom CENTER receives from (N-1)W + (N-1)E.
        if (depth == 2) {
            // Degenerate world: top CENTER → bottom CENTER, single edge.
            connectDown(out, mainC[1], mainC[2], /*alt=*/false);
        } else {
            connectDown(out, mainC[1], mainW[2], false);
            // Top CENTER's alt-down points at 2E.
            LevelFactory.addAltStairsDown(out.get(mainC[1]));
            connectDown(out, mainC[1], mainE[2], true);

            for (int d = 2; d < depth - 1; d++) {
                connectDown(out, mainW[d], mainW[d + 1], false);
                connectDown(out, mainE[d], mainE[d + 1], false);
            }
            // (N-1)W and (N-1)E both feed the bottom CENTER, which uses up + upAlt.
            connectDown(out, mainW[depth - 1], mainC[depth], false);
            LevelFactory.addAltStairsUp(out.get(mainC[depth]));
            connectDown(out, mainE[depth - 1], mainC[depth], true);
        }

        // 3. Per-E/W roll: at most one extra downstair (crosslink OR side branch).
        for (int d = 2; d < depth; d++) {
            for (int idx : new int[]{mainW[d], mainE[d]}) {
                if (idx < 0) continue;
                Level lvl = out.get(idx);
                boolean amWest = lvl.side == Level.Side.WEST;

                // Crosslink: requires both sides to exist at depth d+1.
                boolean canCross = (d + 1 < depth);
                int crossTarget  = canCross ? (amWest ? mainE[d + 1] : mainW[d + 1]) : -1;
                if (canCross && crossTarget >= 0 && rng.nextDouble() < pCross) {
                    LevelFactory.addAltStairsDown(lvl);
                    connectDown(out, idx, crossTarget, true);
                    LevelFactory.addAltStairsUp(out.get(crossTarget));
                    Level dst = out.get(crossTarget);
                    if (dst.stairsUpTarget == idx) {
                        // Already wired as primary; skip alt wiring.
                    } else {
                        dst.stairsUpAltTarget = idx;
                    }
                    continue;
                }

                // Side branch: dead-end at parent depth, column ±2.
                if (rng.nextDouble() < pBranch) {
                    int sideIdx = addLevel(out, width, height, true, false,
                            d, lvl.side, amWest ? -2f : +2f, unique, rng);
                    LevelFactory.addAltStairsDown(lvl);
                    connectDown(out, idx, sideIdx, true);
                }
            }
        }

        return out.toArray(new Level[0]);
    }

    /** Allocate one fresh level, append it to {@code out}, and stamp the supplied
     *  metadata. Returns its index. The per-level seed is drawn from the world
     *  rng so the same world seed regenerates the same dungeon every time —
     *  using {@code LevelFactory}'s built-in {@code ROOT_RNG} would leak fresh
     *  randomness into every run and break determinism. */
    private static int addLevel(List<Level> out, int width, int height,
                                boolean hasUp, boolean hasDown,
                                int depth, Level.Side side, float mapColumn,
                                UniqueTracker unique, Random rng) {
        // Pass depth into createDungeonLevel up front — population reads
        // {@code level.depth} for power-level / unique-mob eligibility, so
        // setting it after construction would be too late and every level
        // would generate as if it were depth 1.
        long levelSeed = rng.nextLong();
        Level lvl = LevelFactory.createDungeonLevel(width, height, depth, hasUp, hasDown,
                                                    unique, levelSeed);
        lvl.side      = side;
        lvl.mapColumn = mapColumn;
        out.add(lvl);
        return out.size() - 1;
    }

    /** Wire a downward edge from {@code srcIdx} to {@code dstIdx}. {@code alt = true}
     *  uses the source's alt downstair (must already exist via
     *  {@link LevelFactory#addAltStairsDown}); the destination side picks its
     *  primary upstair if free, else its alt upstair. */
    private static void connectDown(List<Level> out, int srcIdx, int dstIdx, boolean alt) {
        Level src = out.get(srcIdx);
        Level dst = out.get(dstIdx);
        if (alt) src.stairsDownAltTarget = dstIdx;
        else      src.stairsDownTarget    = dstIdx;
        if (dst.stairsUpTarget == -1)         dst.stairsUpTarget    = srcIdx;
        else if (dst.stairsUpAltTarget == -1) dst.stairsUpAltTarget = srcIdx;
    }

    private static double clamp01(double v) {
        if (v < 0) return 0;
        if (v > 1) return 1;
        return v;
    }

    /**
     * Where the player lands on {@code dst} when arriving from {@code srcIdx} via the
     * stair indicated by {@code descended}. The arrival point is the stair on {@code dst}
     * whose target points back at {@code srcIdx}. Returns {@code null} if no such stair
     * exists (broken topology).
     */
    public static Point arrivalPointFrom(Level dst, int srcIdx, boolean descended) {
        if (descended) {
            if (dst.stairsUpTarget    == srcIdx) return dst.stairsUp;
            if (dst.stairsUpAltTarget == srcIdx) return dst.stairsUpAlt;
        } else {
            if (dst.stairsDownTarget    == srcIdx) return dst.stairsDown;
            if (dst.stairsDownAltTarget == srcIdx) return dst.stairsDownAlt;
        }
        return null;
    }
}
