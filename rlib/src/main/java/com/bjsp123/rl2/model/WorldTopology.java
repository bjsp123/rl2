package com.bjsp123.rl2.model;

import com.bjsp123.rl2.logic.LevelFactory;

/**
 * Builders for pre-baked dungeon graph shapes. The runtime no longer has a single
 * "the topology is this" table — every level instead carries its own
 * {@code stairs(Up|Down)(Alt)Target} indices plus a {@link Level#mapColumn}, and the
 * stair-transition + map-rendering code reads those fields directly. So the world graph
 * can be any layered DAG (any number of levels per depth, any branching shape) as long
 * as the generator wires the per-level edge fields consistently.
 *
 * <p>This class' role is reduced to: pick a shape, generate the levels, and wire the
 * fields. {@link #buildDiamond} is the only shape we ship today.
 */
public final class WorldTopology {

    private WorldTopology() {}

    /**
     * Generate the default 10-level world: a base diamond with two "cross" connections
     * weaving the two branches together, plus two dead-end side levels.
     *
     * <pre>
     *                        [0]                    depth 1, CENTER
     *                       /   \
     *                    [1]     [2]                depth 2, WEST / EAST
     *                   / | \     |
     *                  /  |  \    |
     *               [3] [side8] [4]                 depth 3 (and side W at column −2)
     *                |    ↑    /|\
     *                |    └──[3] (side hangs off 3W)
     *                |       / | \
     *               [5]    [6] |  \
     *                | \   /|  |   \
     *                |  \ / |  |    \
     *               [7]    [side9] (side hangs off 4E)
     * </pre>
     *
     * <p>The cross connections: 2W (idx 1) reaches both 3W <em>and</em> 3E; 3E (idx 4)
     * reaches both 4W <em>and</em> 4E. The side levels (idx 8, 9) are dead-end branches
     * accessible only from their parent main-line level; the player can wander into one
     * and must come back the way they came.
     *
     * <p>Per-level wiring:
     * <table>
     *   <tr><th>idx</th><th>depth</th><th>side</th><th>mapColumn</th>
     *       <th>stairsUp / Alt → ?</th><th>stairsDown / Alt → ?</th></tr>
     *   <tr><td>0</td><td>1</td><td>CENTER</td><td>0</td>     <td>—</td>          <td>1 / 2</td></tr>
     *   <tr><td>1</td><td>2</td><td>WEST</td>  <td>−1</td>    <td>0 / —</td>      <td>3 / 4</td></tr>
     *   <tr><td>2</td><td>2</td><td>EAST</td>  <td>+1</td>    <td>0 / —</td>      <td>4 / —</td></tr>
     *   <tr><td>3</td><td>3</td><td>WEST</td>  <td>−1</td>    <td>1 / —</td>      <td>5 / 8</td></tr>
     *   <tr><td>4</td><td>3</td><td>EAST</td>  <td>+1</td>    <td>2 / 1</td>      <td>6 / 5</td></tr>
     *   <tr><td>5</td><td>4</td><td>WEST</td>  <td>−1</td>    <td>3 / 4</td>      <td>7 / —</td></tr>
     *   <tr><td>6</td><td>4</td><td>EAST</td>  <td>+1</td>    <td>4 / —</td>      <td>7 / 9</td></tr>
     *   <tr><td>7</td><td>5</td><td>CENTER</td><td>0</td>     <td>5 / 6</td>      <td>—</td></tr>
     *   <tr><td>8</td><td>4</td><td>WEST</td>  <td>−2</td>    <td>3 / —</td>      <td>—</td></tr>
     *   <tr><td>9</td><td>5</td><td>EAST</td>  <td>+2</td>    <td>6 / —</td>      <td>—</td></tr>
     * </table>
     */
    public static Level[] buildDiamond(int width, int height) {
        Level[] lv = new Level[10];

        // 1. Generate ten raw levels. Each main-line level (0..7) follows the diamond depth
        //    convention; the two side levels (8, 9) are generated with hasUp=true / hasDown=false
        //    so they end up as dead-end branches.
        int[] depths      = {1, 2, 2, 3, 3, 4, 4, 5, 4, 5};
        Level.Side[] sides = {
            Level.Side.CENTER, Level.Side.WEST, Level.Side.EAST,
            Level.Side.WEST,   Level.Side.EAST,
            Level.Side.WEST,   Level.Side.EAST,
            Level.Side.CENTER,
            Level.Side.WEST,   Level.Side.EAST     // side W, side E
        };
        float[] columns   = {0f, -1f, +1f, -1f, +1f, -1f, +1f, 0f, -2f, +2f};
        boolean[] hasUp   = {false, true,  true,  true,  true,  true,  true,  true,  true,  true};
        boolean[] hasDown = {true,  true,  true,  true,  true,  true,  true,  false, false, false};

        for (int i = 0; i < lv.length; i++) {
            lv[i] = LevelFactory.createDungeonLevel(width, height, hasUp[i], hasDown[i]);
            lv[i].depth     = depths[i];
            lv[i].side      = sides[i];
            lv[i].mapColumn = columns[i];
        }

        // 2. Graft extra staircases onto every main-line level that needs more than one stair
        //    in some direction. The W/E orient swap on the boundary levels (idx 0 and 7) is the
        //    only place we still try to align a stair's room position with its destination —
        //    elsewhere the labels render the destination so the position doesn't matter.
        LevelFactory.addAltStairsDown(lv[0]);  orientWestEastDownStairs(lv[0]);
        LevelFactory.addAltStairsDown(lv[1]);  // 2W needs a 2nd downstair for the cross
        LevelFactory.addAltStairsDown(lv[3]);  // 3W needs a 2nd downstair for side level 8
        LevelFactory.addAltStairsUp  (lv[4]);  // 3E receives a cross from 2W
        LevelFactory.addAltStairsDown(lv[4]);  // 3E sends a cross to 4W
        LevelFactory.addAltStairsUp  (lv[5]);  // 4W receives a cross from 3E
        LevelFactory.addAltStairsDown(lv[6]);  // 4E needs a 2nd downstair for side level 9
        LevelFactory.addAltStairsUp  (lv[7]);  orientWestEastUpStairs  (lv[7]);

        // 3. Wire per-level edge targets. Each pair is symmetric — A.stairsDownTarget = B
        //    iff exactly one of B.stairsUpTarget / B.stairsUpAltTarget is A. The arrival-
        //    point lookup at transition time scans these fields, so this wiring IS the graph.
        // depth 1 → depth 2
        lv[0].stairsDownTarget    = 1;
        lv[0].stairsDownAltTarget = 2;
        lv[1].stairsUpTarget      = 0;
        lv[2].stairsUpTarget      = 0;
        // depth 2 → depth 3 (with cross from 2W reaching both 3W and 3E)
        lv[1].stairsDownTarget    = 3;   lv[3].stairsUpTarget    = 1;
        lv[1].stairsDownAltTarget = 4;   lv[4].stairsUpAltTarget = 1;   // cross
        lv[2].stairsDownTarget    = 4;   lv[4].stairsUpTarget    = 2;
        // depth 3 → depth 4 (with cross from 3E reaching both 4W and 4E)
        lv[3].stairsDownTarget    = 5;   lv[5].stairsUpTarget    = 3;
        lv[4].stairsDownTarget    = 6;   lv[6].stairsUpTarget    = 4;
        lv[4].stairsDownAltTarget = 5;   lv[5].stairsUpAltTarget = 4;   // cross
        // depth 3 → side W (dead-end)
        lv[3].stairsDownAltTarget = 8;   lv[8].stairsUpTarget    = 3;
        // depth 4 → depth 5
        lv[5].stairsDownTarget    = 7;
        lv[6].stairsDownTarget    = 7;
        lv[7].stairsUpTarget      = 5;
        lv[7].stairsUpAltTarget   = 6;
        // depth 4 → side E (dead-end)
        lv[6].stairsDownAltTarget = 9;   lv[9].stairsUpTarget    = 6;

        return lv;
    }

    /** Swap the two down-stairs on a level so that the smaller-X tile lives in
     *  {@code stairsDown} (the "primary" / west-pointing stair). Used only on the
     *  boundary levels (idx 0 of the diamond) where W/E semantics are unambiguous;
     *  for cross-connection levels the stair labels disambiguate destinations. */
    private static void orientWestEastDownStairs(Level lvl) {
        if (lvl.stairsDown == null || lvl.stairsDownAlt == null) return;
        if (lvl.stairsDown.tileX() <= lvl.stairsDownAlt.tileX()) return;
        Point tmp = lvl.stairsDown;
        lvl.stairsDown    = lvl.stairsDownAlt;
        lvl.stairsDownAlt = tmp;
    }

    /** Sibling of {@link #orientWestEastDownStairs} for the deepest level's two upstairs. */
    private static void orientWestEastUpStairs(Level lvl) {
        if (lvl.stairsUp == null || lvl.stairsUpAlt == null) return;
        if (lvl.stairsUp.tileX() <= lvl.stairsUpAlt.tileX()) return;
        Point tmp = lvl.stairsUp;
        lvl.stairsUp    = lvl.stairsUpAlt;
        lvl.stairsUpAlt = tmp;
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
