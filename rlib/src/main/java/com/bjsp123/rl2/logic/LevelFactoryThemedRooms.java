package com.bjsp123.rl2.logic;

import com.bjsp123.rl2.model.Level;
import com.bjsp123.rl2.model.UniqueTracker;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Stamping orchestrator: walks the just-carved room rectangles and applies
 * themed-room treatments. All decoration — round rooms, walkways, chasm/light/
 * imp temples, galleries, the new chapel/pedestal/checkerboard rooms, etc. —
 * is data-driven from {@code assets/data/themedrooms.csv}; this class just
 * picks rows that fit and stamps them.
 *
 * <p>Two passes:
 * <ol>
 *   <li>{@link #stampUniqueRoom} — at most one unique row per level. Eligible
 *       candidates are rows flagged {@code unique=true}, not already in
 *       {@link UniqueTracker#rooms}, and whose {@code [powerMin, powerMax]}
 *       band covers the level's depth-fraction. The first room whose dimensions
 *       fit is claimed: the room is removed from the rooms list (so stairs /
 *       regular paint won't touch it), the rectangle is added to
 *       {@link Level#reservedRects} (so random scatter skips it), and the type
 *       is added to {@code unique.rooms}.</li>
 *   <li>{@link #stampRegularThemedRooms} — every non-stair room is decorated
 *       with a uniform pick from the {@code unique=false} rows whose dimensions
 *       and powerLevel fit. Doesn't reserve; regulars coexist with random
 *       scatter the same way the decorative {@code RoomKind} variants used
 *       to.</li>
 * </ol>
 *
 * <p>VILLAGE-layout levels finalise their own buildings inside the layout
 * builder, so themed-room stamping is skipped on those entirely. WALKWAY_LEVEL
 * already paints corridors as planks over chasm — stamping additional
 * {@code roomShape=WALKWAY} rooms on top would be redundant, so those rows are
 * filtered out on those levels.
 */
public final class LevelFactoryThemedRooms {

    private LevelFactoryThemedRooms() {}

    public static void stampUniqueRoom(Level level, List<int[]> rooms,
                                       UniqueTracker unique, Random rng) {
        if (unique == null) return;
        if (level.layout == LevelFactory.Layout.VILLAGE) return;
        double f = depthFraction(level);
        List<ThemedRoomDefinition> candidates = new ArrayList<>();
        for (String type : ThemedRoomRegistry.knownTypes()) {
            ThemedRoomDefinition d = ThemedRoomRegistry.get(type);
            if (d == null || !d.unique) continue;
            if (unique.rooms.contains(d.type)) continue;
            if (f < d.powerMin || f > d.powerMax) continue;
            candidates.add(d);
        }
        if (candidates.isEmpty()) return;

        // Walk rooms (skipping the first / last so stairs always have a clean room
        // to land on) and pick the first candidate whose constraints fit.
        int last = rooms.size() - 1;
        for (int ri = 1; ri < last; ri++) {
            int[] r = rooms.get(ri);
            for (ThemedRoomDefinition d : candidates) {
                if (!fits(d, r)) continue;
                int spawnLevel = 1 + level.depth;
                ThemedRoomPainter.paint(level, d, r[0], r[1], r[2], r[3], rng);
                ThemedRoomPopulator.populate(level, d, r[0], r[1], r[2], r[3],
                                             spawnLevel, rng);
                level.reservedRects.add(new int[]{r[0], r[1], r[2], r[3]});
                unique.rooms.add(d.type);
                tagSnapshotKind(level, r, d.type);
                rooms.remove(ri);
                return;
            }
        }
    }

    public static void stampRegularThemedRooms(Level level, List<int[]> rooms, Random rng) {
        if (level.layout == LevelFactory.Layout.VILLAGE) return;
        boolean walkwayLevel = level.flags.contains(Level.LevelFlag.WALKWAY_LEVEL);

        List<ThemedRoomDefinition> regulars = new ArrayList<>();
        for (String type : ThemedRoomRegistry.knownTypes()) {
            ThemedRoomDefinition d = ThemedRoomRegistry.get(type);
            if (d == null || d.unique) continue;
            // WALKWAY_LEVEL paints corridors as planks over chasm — stamping a
            // walkway-shaped room on top would be redundant.
            if (walkwayLevel && d.roomShape == ThemedRoomDefinition.RoomShape.WALKWAY) continue;
            regulars.add(d);
        }
        if (regulars.isEmpty()) return;
        double f = depthFraction(level);

        // Walk in reverse so removing claimed rooms doesn't shift indices for the
        // next iteration. Skip indices 0 and last so stair rooms stay clean for
        // {@link LevelFactory#placeStairs} to drop the up / down ladders.
        int last = rooms.size() - 1;
        for (int ri = last - 1; ri >= 1; ri--) {
            int[] r = rooms.get(ri);
            // Filter to regulars that fit this room AND whose powerLevel band
            // covers this level's depth-fraction.
            List<ThemedRoomDefinition> fitting = new ArrayList<>();
            for (ThemedRoomDefinition d : regulars) {
                if (!fits(d, r)) continue;
                if (f < d.powerMin || f > d.powerMax) continue;
                fitting.add(d);
            }
            if (fitting.isEmpty()) continue;
            ThemedRoomDefinition d = fitting.get(rng.nextInt(fitting.size()));
            int spawnLevel = 1 + level.depth;
            ThemedRoomPainter.paint(level, d, r[0], r[1], r[2], r[3], rng);
            ThemedRoomPopulator.populate(level, d, r[0], r[1], r[2], r[3],
                                         spawnLevel, rng);
            // Regular themed rooms don't reserve — they coexist with random scatter
            // the same way the pre-existing decorative variants did.
            tagSnapshotKind(level, r, d.type);
            rooms.remove(ri);
        }
    }

    /** Find the room snapshot in {@code level.rooms} matching the rectangle
     *  {@code r} and stamp it with {@code kind}. The captured snapshot list
     *  is parallel to (but separate from) the working {@code rooms} list the
     *  stampers mutate, so look-up is by geometry. */
    private static void tagSnapshotKind(Level level, int[] r, String kind) {
        for (Level.RoomSnapshot s : level.rooms) {
            if (s.x == r[0] && s.y == r[1] && s.w == r[2] && s.h == r[3]) {
                s.kind = kind;
                return;
            }
        }
    }

    private static boolean fits(ThemedRoomDefinition d, int[] r) {
        int w = r[2], h = r[3];
        if (w < d.minWidth || h < d.minHeight) return false;
        if (w > d.maxWidth || h > d.maxHeight) return false;
        if (d.requireLong) {
            int longSide  = Math.max(w, h);
            int shortSide = Math.min(w, h);
            if (longSide < shortSide * 3 / 2) return false;   // 1.5×
        }
        return true;
    }

    /** Depth-fraction of {@code level.depth} within the configured dungeon size:
     *  depth 1 → 0.0, depth {@code DUNGEON_DEPTH} → 1.0. Mirrors
     *  {@link LevelFactoryPopulate}'s helper but inlined here so the orchestrator
     *  doesn't reach into the populator's internals. */
    private static double depthFraction(Level level) {
        int total = Math.max(2, GameBalance.DUNGEON_DEPTH);
        double f = (level.depth - 1) / (double) (total - 1);
        if (f < 0) return 0;
        if (f > 1) return 1;
        return f;
    }
}
