package com.bjsp123.rl2.model;

import com.bjsp123.rl2.logic.GameBalance;
import com.bjsp123.rl2.logic.LevelFactory;
import com.bjsp123.rl2.logic.LevelFactorySpecial;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Random;

/**
 * Procedural world generator. Reads {@link GameBalance#DUNGEON_DEPTH},
 * {@link GameBalance#TWO_LEVEL_PROBABILITY}, and
 * {@link GameBalance#DIAGONAL_STAIR_PROBABILITY} to build a layered DAG of
 * {@link Level}s and wires the per-level {@code stairs(Up|Down)(Alt)Target}
 * indices that drive both inter-level transitions and {@code MapScreen}.
 *
 * <p>Shape: the world reads as three parallel themed columns -
 * {@link Level.VisualTheme#CONCRETE} at column 0 (WEST),
 * {@link Level.VisualTheme#CRYSTAL} at column 1 (CENTER),
 * {@link Level.VisualTheme#GOTHIC} at column 2 (EAST). Depth 1 and depth N are
 * single {@link Level.VisualTheme#SHINY} CENTER levels. Each intermediate depth
 * carries 1 level (always CRYSTAL) or 2 levels (any 2 of 3 columns), rolled per
 * depth via {@code TWO_LEVEL_PROBABILITY}.
 *
 * <p>Stair wiring per source level at depth {@code d}:
 * <ul>
 *   <li>Primary downstair: prefer the same-column level at {@code d+1}, else
 *       the nearest occupied column (preferring uncovered destinations).</li>
 *   <li>Every destination at {@code d+1} is guaranteed at least one incoming
 *       stair - if a roll leaves a destination uncovered after primary
 *       assignment, an alt stair is added to cover it.</li>
 *   <li>Beyond mandatory coverage, an additional "diagonal" alt stair is rolled
 *       per source via {@code DIAGONAL_STAIR_PROBABILITY}, pointing at a
 *       different-column destination than the primary.</li>
 * </ul>
 *
 * <p>{@code DUNGEON_DEPTH = 2} collapses to top-SHINY -> bottom-SHINY with a
 * single edge.
 */
public final class WorldTopology {

    private WorldTopology() {}

    /** Build a fresh world. {@code rng} drives every probability roll; pass a
     *  reproducible seed to get a deterministic dungeon. The {@code unique}
     *  tracker is threaded through to {@link LevelFactory#createDungeonLevel} so
     *  unique themed rooms only spawn once across the whole world. */
    public static Level[] build(int width, int height, Random rng, UniqueTracker unique) {
        int depth = Math.max(2, GameBalance.DUNGEON_DEPTH);
        double pTwo  = clamp01(GameBalance.TWO_LEVEL_PROBABILITY);
        double pDiag = clamp01(GameBalance.DIAGONAL_STAIR_PROBABILITY);

        List<Level> out = new ArrayList<>();

        // slot[col][d] = level index in `out`, -1 if absent. Columns: 0=W, 1=C, 2=E.
        int[][] slot = new int[3][depth + 1];
        for (int c = 0; c < 3; c++) Arrays.fill(slot[c], -1);

        // Depth 1: single SHINY CENTER level.
        slot[1][1] = addLevel(out, width, height, /*hasUp=*/false, /*hasDown=*/true,
                /*depth=*/1, Level.Side.CENTER, /*mapColumn=*/0f,
                Level.VisualTheme.SHINY, unique, rng);

        // Intermediate depths: roll 1 level (always CRYSTAL) or 2 levels
        // (any 2 of 3 columns picked uniformly).
        for (int d = 2; d < depth; d++) {
            int[] cols;
            if (rng.nextDouble() < pTwo) {
                int pair = rng.nextInt(3);
                cols = switch (pair) {
                    case 0  -> new int[]{0, 1};   // CONCRETE + CRYSTAL
                    case 1  -> new int[]{0, 2};   // CONCRETE + GOTHIC
                    default -> new int[]{1, 2};   // CRYSTAL  + GOTHIC
                };
            } else {
                cols = new int[]{1};               // CRYSTAL only
            }
            for (int c : cols) {
                slot[c][d] = addLevel(out, width, height, true, true, d,
                        sideForCol(c), mapColumnForCol(c),
                        themeForCol(c), unique, rng);
            }
        }

        // Depth N: single SHINY CENTER level. Carries a downstair into the
        // special antechamber (Landing) - hasDown=true so LevelFactory
        // places a real STAIRS_DOWN tile we can wire below.
        slot[1][depth] = addLevel(out, width, height, true, true,
                depth, Level.Side.CENTER, 0f,
                Level.VisualTheme.SHINY, unique, rng);

        // Wire downstairs depth-by-depth so every destination ends up reachable.
        for (int d = 1; d < depth; d++) {
            wireDepth(out, slot, d, pDiag, rng);
        }

        // Special levels: append Landing then the three unique floors in
        // random order. All single-column (CENTER, mapColumn=0) so the map
        // screen draws them as a vertical chain hanging off the bottom of the
        // diamond. Stairs wire as: depth N -> Landing -> shuffled[0] ->
        // shuffled[1] -> shuffled[2] (no further descent).
        int lastRegularIdx = slot[1][depth];
        appendSpecialLevels(out, depth, lastRegularIdx, rng);

        return out.toArray(new Level[0]);
    }

    /** Build and wire the four special floors after the regular dungeon.
     *  Order: Landing always at depth N+1; then the three unique floors
     *  (Mirrormatch, Horde, Walkway) shuffled across depths N+2..N+4. */
    private static void appendSpecialLevels(List<Level> out, int regularDepth,
                                       int lastRegularIdx, Random rng) {
        // Landing - the antechamber. Always sits immediately below the
        // last regular floor.
        int landingDepth = regularDepth + 1;
        Level landing = LevelFactorySpecial.buildLanding(landingDepth, rng.nextLong());
        out.add(landing);
        int landingIdx = out.size() - 1;

        // Wire the last regular floor's downstair to Landing, and
        // Landing's upstair back to the last regular floor.
        Level lastRegular = out.get(lastRegularIdx);
        lastRegular.stairsDownTarget = landingIdx;
        landing.stairsUpTarget = lastRegularIdx;

        // Shuffle the three unique floors so each run gets a different
        // endgame order. Each shuffled floor sits at landingDepth+1+i.
        List<Level.LevelKind> order = new ArrayList<>();
        order.add(Level.LevelKind.MIRRORMATCH);
        order.add(Level.LevelKind.HORDE);
        order.add(Level.LevelKind.WALKWAY);
        Collections.shuffle(order, rng);

        int previousIdx = landingIdx;
        for (int i = 0; i < order.size(); i++) {
            int floorDepth = landingDepth + 1 + i;
            long seed = rng.nextLong();
            Level next = switch (order.get(i)) {
                case MIRRORMATCH -> LevelFactorySpecial.buildMirrormatch(floorDepth, seed);
                case HORDE       -> LevelFactorySpecial.buildHorde(floorDepth, seed);
                case WALKWAY     -> LevelFactorySpecial.buildWalkway(floorDepth, seed);
                default          -> throw new IllegalStateException("Unexpected: " + order.get(i));
            };
            out.add(next);
            int nextIdx = out.size() - 1;
            // Wire the previous floor down to this one and this one up to
            // the previous. The last floor in the chain has no downstair
            // (its stairsDownTarget stays -1) - that's the end of the run.
            Level prev = out.get(previousIdx);
            prev.stairsDownTarget = nextIdx;
            next.stairsUpTarget = previousIdx;
            previousIdx = nextIdx;
        }
    }

    /** Allocate downstairs from depth {@code d} into depth {@code d+1}. Phase 1
     *  picks a primary for every source (same-column preferred, then nearest
     *  uncovered, then nearest at all). Phase 2 covers any destination still
     *  missing an incoming edge with a mandatory alt stair. Phase 3 rolls
     *  optional diagonal alt stairs for sources that still have a free alt
     *  slot. */
    private static void wireDepth(List<Level> out, int[][] slot, int d,
                                  double pDiag, Random rng) {
        int dn = d + 1;
        int[] srcs = new int[]{slot[0][d],  slot[1][d],  slot[2][d]};
        int[] dsts = new int[]{slot[0][dn], slot[1][dn], slot[2][dn]};

        int[] primaryTargetCol = new int[]{-1, -1, -1};
        boolean[] dstCovered   = new boolean[]{false, false, false};

        // Phase 1: primary stair per source.
        for (int c = 0; c < 3; c++) {
            if (srcs[c] < 0) continue;
            int tgt = pickPrimaryCol(c, dsts, dstCovered);
            if (tgt < 0) continue;
            primaryTargetCol[c] = tgt;
            dstCovered[tgt] = true;
            wire(out, srcs[c], dsts[tgt], /*altStair=*/false);
        }

        // Phase 2: mandatory coverage for uncovered destinations.
        for (int c = 0; c < 3; c++) {
            if (dsts[c] < 0 || dstCovered[c]) continue;
            int srcCol = pickFreeAltSrc(out, srcs, primaryTargetCol, c);
            if (srcCol < 0) continue;
            wire(out, srcs[srcCol], dsts[c], /*altStair=*/true);
            dstCovered[c] = true;
        }

        // Phase 3: optional diagonal alt stairs.
        for (int c = 0; c < 3; c++) {
            if (srcs[c] < 0) continue;
            Level src = out.get(srcs[c]);
            if (src.stairsDownAltTarget != -1) continue;       // alt already used
            if (rng.nextDouble() >= pDiag) continue;
            int altCol = pickDiagonalCol(dsts, primaryTargetCol[c]);
            if (altCol < 0) continue;
            wire(out, srcs[c], dsts[altCol], /*altStair=*/true);
        }
    }

    /** Phase 1 helper: same-column first, then nearest uncovered, then nearest
     *  covered. Returns -1 if no destination exists at all (shouldn't happen
     *  because every intermediate depth has at least one level). */
    private static int pickPrimaryCol(int srcCol, int[] dsts, boolean[] dstCovered) {
        if (dsts[srcCol] >= 0) return srcCol;
        for (int dist = 1; dist <= 2; dist++) {
            int left  = srcCol - dist;
            int right = srcCol + dist;
            if (left  >= 0 && dsts[left]  >= 0 && !dstCovered[left])  return left;
            if (right <= 2 && dsts[right] >= 0 && !dstCovered[right]) return right;
        }
        for (int dist = 1; dist <= 2; dist++) {
            int left  = srcCol - dist;
            int right = srcCol + dist;
            if (left  >= 0 && dsts[left]  >= 0) return left;
            if (right <= 2 && dsts[right] >= 0) return right;
        }
        return -1;
    }

    /** Phase 2 helper: find a source that hasn't yet used its alt-stair slot
     *  and whose primary target isn't already the destination we're trying to
     *  cover. Returns -1 if no such source exists. */
    private static int pickFreeAltSrc(List<Level> out, int[] srcs,
                                      int[] primaryTargetCol, int dstCol) {
        for (int c = 0; c < 3; c++) {
            if (srcs[c] < 0) continue;
            if (out.get(srcs[c]).stairsDownAltTarget != -1) continue;
            if (primaryTargetCol[c] == dstCol) continue;
            return c;
        }
        return -1;
    }

    /** Phase 3 helper: pick any destination column other than the source's
     *  primary target. Returns -1 if no such destination exists. */
    private static int pickDiagonalCol(int[] dsts, int primaryCol) {
        for (int c = 0; c < 3; c++) {
            if (dsts[c] < 0) continue;
            if (c == primaryCol) continue;
            return c;
        }
        return -1;
    }

    /** Allocate one fresh level, append it to {@code out}, and stamp the supplied
     *  metadata. Returns its index. The per-level seed is drawn from the world
     *  rng so the same world seed regenerates the same dungeon every time -
     *  using {@code LevelFactory}'s built-in {@code ROOT_RNG} would leak fresh
     *  randomness into every run and break determinism. */
    private static int addLevel(List<Level> out, int width, int height,
                                boolean hasUp, boolean hasDown,
                                int depth, Level.Side side, float mapColumn,
                                Level.VisualTheme theme,
                                UniqueTracker unique, Random rng) {
        long levelSeed = rng.nextLong();
        Level lvl = LevelFactory.createDungeonLevel(width, height, depth, hasUp, hasDown,
                                                    theme, unique, levelSeed);
        lvl.side      = side;
        lvl.mapColumn = mapColumn;
        out.add(lvl);
        return out.size() - 1;
    }

    /** Wire a downward edge from {@code srcIdx} to {@code dstIdx}, allocating
     *  alt-stair tiles as needed so the connection has a real tile on both
     *  sides. {@code altStair=true} adds a second downstair to the source;
     *  the destination automatically uses its primary up-tile if free, else its
     *  alt up-tile (which is allocated here on demand). */
    private static void wire(List<Level> out, int srcIdx, int dstIdx, boolean altStair) {
        Level src = out.get(srcIdx);
        Level dst = out.get(dstIdx);
        if (altStair && src.stairsDownAlt == null) {
            LevelFactory.addAltStairsDown(src);
        }
        if (dst.stairsUpTarget != -1 && dst.stairsUpAlt == null) {
            LevelFactory.addAltStairsUp(dst);
        }
        connectDown(out, srcIdx, dstIdx, altStair);
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

    private static Level.Side sideForCol(int col) {
        return switch (col) {
            case 0  -> Level.Side.WEST;
            case 2  -> Level.Side.EAST;
            default -> Level.Side.CENTER;
        };
    }

    private static float mapColumnForCol(int col) {
        return col - 1f;
    }

    private static Level.VisualTheme themeForCol(int col) {
        return switch (col) {
            case 0  -> Level.VisualTheme.CONCRETE;
            case 2  -> Level.VisualTheme.GOTHIC;
            default -> Level.VisualTheme.CRYSTAL;
        };
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
