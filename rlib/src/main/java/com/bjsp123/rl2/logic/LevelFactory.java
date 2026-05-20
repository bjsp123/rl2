package com.bjsp123.rl2.logic;

import com.bjsp123.rl2.model.Level;
import com.bjsp123.rl2.model.Level.LevelFlag;
import com.bjsp123.rl2.model.Level.Vegetation;
import com.bjsp123.rl2.model.Level.VisualTheme;
import com.bjsp123.rl2.model.Point;
import com.bjsp123.rl2.model.Tile;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

/**
 * Level generation orchestrator. The actual work - corridor routing, room shapes, surface
 * and mob population - is split across {@link LevelFactoryUtils}, {@link LevelFactoryRooms}
 * and {@link LevelFactoryPopulate}; this file glues the stages together.
 *
 * <p>Pipeline per level:
 * <ol>
 *   <li>Roll flags + theme + {@link Layout} from the seeded RNG.</li>
 *   <li>Layout-specific room generation: BSP partition or Poisson-disc scatter.</li>
 *   <li>Connect the resulting rooms with L-corridors via Prim's MST plus a few loop edges.</li>
 *   <li>Pick a {@link LevelFactoryRooms.RoomKind} per room and paint the interior.</li>
 *   <li>Wall in chasms touching floors, punch perimeter doors, prune orphan doors.</li>
 *   <li>Place stairs in the first and last rooms; populate water, vegetation, items, mobs.</li>
 * </ol>
 *
 * Every stage is driven by a single {@link Random} seeded once at entry - generating the
 * same level twice from the same seed is the contract callers should be able to rely on.
 */
public final class LevelFactory {

    /**
     * Macro-shape archetype rolled at level-generation time. Drives which placement algorithm
     * is used to seed room rectangles. Each layout produces a very different feel - a BSP
     * dungeon plays nothing like a poisson-disc scatter.
     *
     * <p>Layouts are about the SHAPE of the level - corridor and room placement strategy.
     * The contents of each room (regular, round, walkway, grass-farm, ...) are picked
     * separately per room and live in {@link LevelFactoryRooms.RoomKind}.
     */
    public enum Layout {
        /** Recursive binary partition of the level into room-sized rectangles, each carved
         *  as a room. Rooms are connected via a minimum-spanning-tree of their centres plus
         *  a few extra edges for loops. Looks like a classic Rogue dungeon - tight, every
         *  space accounted for, mostly orthogonal. */
        BSP,
        /** Poisson-disc scatter of room centres across the level, then each centre grows
         *  into a rectangle of random size, then a minimum-spanning-tree of L-corridors
         *  connects them. Looks more "organic" - rooms can be small islands far apart. */
        POISSON,
        /** SPD-style figure-eight: two loops of rooms sharing a single pivot room in the
         *  middle. Reads as a horizontal "8" - the player can pick either side of the cross
         *  and orbit it before threading through the pivot to the other side. */
        FIGURE_EIGHT,
        /** Village: one large irregular floor area with a handful of rectangular buildings
         *  carved into it. Each building is walled, with a single door - the rest of the
         *  level is open ground (treated as one big "outside" room for population). */
        VILLAGE,
        /** Two-sides: two small clusters of rooms sit at opposite halves of the level,
         *  joined by a single long FLOOR_WOOD walkway across the gap. */
        TWO_SIDES,
        /** Packed: rooms grow off each other to tile the level with as little dead space
         *  as possible, then doors / very short corridors join touching neighbours. A
         *  reachability sweep adds extra connections until every room is reachable from
         *  the stair-up room. Reads as a tightly-cellular layout - no long corridors,
         *  every tile inside a room. */
        PACKED
    }

    private LevelFactory() {}

    /** Top-level RNG for picking the per-level seed when none was supplied. */
    private static final Random ROOT_RNG = new Random();

    /** Per-flag roll at level build time. */
    private static final double LEVEL_FLAG_CHANCE = 0.2;

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    /** Generate a level with a freshly-rolled seed. The player is not placed by this method -
     *  the caller positions the player on the appropriate stair on entry. The
     *  {@code unique} tracker, when non-null, is consulted at themed-room generation time
     *  so unique rooms only appear once per game. {@code depth} is stamped on the new
     *  level <em>before</em> population so themed-room / unique-mob picks see the correct
     *  depth-fraction (passing it as a parameter rather than via post-mutation closes a
     *  bug where every level looked like depth 1 to the stamping code). */
    public static Level createDungeonLevel(int w, int h, int depth,
                                           boolean hasUp, boolean hasDown,
                                           com.bjsp123.rl2.model.UniqueTracker unique) {
        return createDungeonLevel(w, h, depth, hasUp, hasDown, unique, ROOT_RNG.nextLong());
    }

    /** Seeded variant - same seed -> same level, modulo class-loaded factory state. */
    public static Level createDungeonLevel(int w, int h, int depth,
                                           boolean hasUp, boolean hasDown,
                                           com.bjsp123.rl2.model.UniqueTracker unique,
                                           long seed) {
        Random rng = new Random(seed);

        // Roll flags FIRST - BIGLEVEL has to be known before the tile grid is allocated,
        // since it scales the level's dimensions up.
        Set<LevelFlag> flags = rollFlags(rng);
        if (flags.contains(LevelFlag.BIGLEVEL)) {
            w = (int) Math.round(w * 1.5);
            h = (int) Math.round(h * 1.5);
        }
        Level level = new Level(w, h);
        level.depth = depth;        // before population - populate reads depth-fraction.
        level.flags.addAll(flags);

        level.layout = Layout.values()[rng.nextInt(Layout.values().length)];
        // Equal 1/3 chance per registered theme - VisualTheme.values() drives the
        // distribution so adding a fourth theme will rebalance automatically.
        VisualTheme[] themes = VisualTheme.values();
        level.theme = themes[rng.nextInt(themes.length)];

        List<int[]> rooms = switch (level.layout) {
            case BSP          -> buildBsp(level, rng);
            case POISSON      -> buildPoisson(level, rng);
            case FIGURE_EIGHT -> buildFigureEight(level, rng);
            case VILLAGE      -> buildVillage(level, rng);
            case TWO_SIDES    -> buildTwoSides(level, rng);
            case PACKED       -> buildPacked(level, rng);
        };
        System.out.println("[rl2] generated level seed=" + seed
                + " size=" + w + "x" + h
                + " theme=" + level.theme + " layout=" + level.layout
                + " rooms=" + rooms.size()
                + " flags=" + level.flags);
        if (rooms.isEmpty()) {
            // Degenerate fallback - every layout had a bad roll. Carve the whole interior.
            LevelFactoryUtils.carveRect(level, 1, 1, w - 2, h - 2);
            rooms.add(new int[]{1, 1, w - 2, h - 2});
        }

        // On WALKWAY_LEVEL, connectTwo paints corridors as FLOOR_WOOD; the L-shape passes
        // through the source/dest room interiors on the way out, leaving plank cells inside
        // rooms. Revert those to FLOOR so only the chasm-spanning portion of each corridor
        // reads as a plank bridge - rooms themselves stay normal floor.
        if (level.flags.contains(LevelFlag.WALKWAY_LEVEL)) {
            for (int[] r : rooms) {
                for (int x = r[0]; x < r[0] + r[2]; x++) {
                    for (int y = r[1]; y < r[1] + r[3]; y++) {
                        if (level.tiles[x][y] == Tile.FLOOR_WOOD) level.tiles[x][y] = Tile.FLOOR;
                    }
                }
            }
        }

        // Walls and doors first - interiors paint AFTER, so decorative chasms (CHASM,
        // WALKWAY) aren't eaten by wallInFloors and walkway plank-painting can rely on the
        // doors already being placed. Every decorative variant - round rooms, walkways,
        // chasm/light/imp temples, galleries, etc. - is now expressed as a non-unique
        // themed room in {@code assets/data/themedrooms.csv}; the orchestrator below picks
        // and stamps them. VILLAGE-layout levels finalise their own buildings inside the
        // builder, so themed-room stamping is skipped on VILLAGE inside the orchestrator.
        LevelFactoryUtils.wallInFloors(level);
        placeDoors(level, rooms);
        // Snapshot the room rectangles for diagnostic dumps (WorldYamlDump etc.) -
        // stamping below mutates the working list (claimed unique rooms get
        // removed), but the snapshot preserves the full layout.
        for (int[] r : rooms) level.rooms.add(new Level.RoomSnapshot(r[0], r[1], r[2], r[3]));
        int reservedBefore = level.reservedRects.size();
        LevelFactoryThemedRooms.stampUniqueRoom(level, rooms, unique, rng);
        for (int i = reservedBefore; i < level.reservedRects.size(); i++) {
            changeRoomDoors(level, level.reservedRects.get(i), Tile.DOOR, Tile.ONETIME_DOOR);
        }
        LevelFactoryThemedRooms.stampRegularThemedRooms(level, rooms, rng);
        LevelFactoryUtils.pruneOrphanDoors(level);

        placeStairs(level, rooms, hasUp, hasDown, rng);
        if (hasUp && !rooms.isEmpty()) {
            changeRoomDoors(level, rooms.get(0), Tile.DOOR, Tile.CRYSTAL_DOOR);
        }

        // Depth-1 spawn room: onetime doors so mobs can't cross into the room
        // from outside until the player has stepped through (the crossed tile
        // turns to FLOOR at that point). Runs before the all-crystal sweep so
        // the ONETIME_DOOR tiles stick (the sweep only swaps DOOR -> CRYSTAL_DOOR).
        if (depth == 1 && !rooms.isEmpty()) {
            changeRoomDoors(level, rooms.get(0), Tile.DOOR, Tile.ONETIME_DOOR);
        }

        //bp make all doors crystal
        for (int i = 0; i<rooms.size(); i++) {
            changeRoomDoors(level, rooms.get(i), Tile.DOOR, Tile.CRYSTAL_DOOR);
        }

        LevelFactoryPopulate.placeWaterPools(level, rng);
        LevelFactoryPopulate.placeVegetation(level, rng);
        LevelFactoryPopulate.placeItems(level, rng);
        LevelFactoryPopulate.placeGems(level, rng);
        LevelFactoryPopulate.placeMobs(level, rng, unique);
        return level;
    }

    // -------------------------------------------------------------------------
    // Flag roll
    // -------------------------------------------------------------------------

    private static Set<LevelFlag> rollFlags(Random rng) {
        Set<LevelFlag> out = new HashSet<>();
        for (LevelFlag flag : LevelFlag.values()) {
            if (rng.nextDouble() < LEVEL_FLAG_CHANCE) out.add(flag);
        }
        return out;
    }

    // -------------------------------------------------------------------------
    // Layouts
    // -------------------------------------------------------------------------

    /**
     * BSP layout: split the level into a tree of leaf rectangles, then place a randomly
     * sized FLOOR room inside each leaf with a 1-tile margin from the leaf's bounds.
     * Connects with an MST plus a few extra random edges for loops. Produces dense,
     * mostly-orthogonal dungeons where every leaf hosts a chamber.
     */
    /** Per-level room target. Picks a value in {@code [TARGET-1, TARGET+1]} so
     *  no two adjacent levels feel mechanically identical, but every level stays
     *  within +/-1 of the configured target. Used as the {@code targetCount} arg
     *  to {@link #topUpPackedRooms} and as the placement cap in
     *  {@link #buildPoisson}. */
    private static int perLevelRoomTarget(Random rng) {
        return Math.max(2, GameBalance.LEVEL_TARGET_ROOMS + rng.nextInt(3) - 1);
    }

    private static List<int[]> buildBsp(Level level, Random rng) {
        int w = level.width, h = level.height;
        // Room size driven by the global ROOM_MIN/MAX_SIZE pair; leaf size is the
        // larger of (a) one room + 1-tile margin all round, (b) a value derived
        // from area / target so the partition splits roughly LEVEL_TARGET_ROOMS
        // ways regardless of level size.
        int minRoom = GameBalance.ROOM_MIN_SIZE;
        int maxRoom = GameBalance.ROOM_MAX_SIZE;
        int target = perLevelRoomTarget(rng);
        int minLeaf = leafSizeForTarget(w, h, maxRoom, target);
        List<int[]> leaves = LevelFactoryUtils.bspPartition(1, 1, w - 2, h - 2, minLeaf, rng);
        List<int[]> rooms = new ArrayList<>();
        for (int[] leaf : leaves) {
            int margin = 1;
            int maxRW = Math.min(maxRoom, leaf[2] - 2 * margin);
            int maxRH = Math.min(maxRoom, leaf[3] - 2 * margin);
            if (maxRW < minRoom || maxRH < minRoom) continue;
            int rw = minRoom + rng.nextInt(maxRW - minRoom + 1);
            int rh = minRoom + rng.nextInt(maxRH - minRoom + 1);
            int rx = leaf[0] + margin + rng.nextInt(leaf[2] - 2 * margin - rw + 1);
            int ry = leaf[1] + margin + rng.nextInt(leaf[3] - 2 * margin - rh + 1);
            LevelFactoryUtils.carveRect(level, rx, ry, rw, rh);
            rooms.add(new int[]{rx, ry, rw, rh});
        }
        connectMst(level, rooms, rng);
        // BSP's leaf-size floor caps room count well below the target on smaller
        // levels; top up with packed rooms in the gaps so every level lands at
        // the per-level target.
        topUpPackedRooms(level, rooms, rng, target);
        return rooms;
    }

    /**
     * Poisson layout: scatter room centres with a minimum spacing, then grow each into a
     * rectangle of random size. Centres that don't have room for a rect (or whose rect
     * overlaps an existing room) are discarded - final room count is data-driven. Connects
     * with an MST plus a few extra edges for loops.
     */
    private static List<int[]> buildPoisson(Level level, Random rng) {
        int w = level.width, h = level.height;
        // minDist is the Poisson-disc spacing between room centres - directly determines
        // how far apart rooms sit, and therefore how long the MST corridors between them
        // are. Derived from area / target (with a 2.0 fudge factor for centres-per-room
        // empirically - many candidate centres fail the overlap check) so room count
        // stays near the target regardless of level dimensions.
        int target = perLevelRoomTarget(rng);
        double minDist = poissonSpacingForTarget(w, h, target);
        int minSize = GameBalance.ROOM_MIN_SIZE;
        int maxSize = GameBalance.ROOM_MAX_SIZE;
        List<Point> centres = LevelFactoryUtils.poissonDiskPoints(w, h, minDist, rng);
        List<int[]> rooms = new ArrayList<>();
        for (Point c : centres) {
            // Cap placement at the per-level target - Poisson without this cap
            // happily over-shoots when the disc-sampler is generous, and the
            // user wants every level inside the target +/- 1 band.
            if (rooms.size() >= target) break;
            int rw = minSize + rng.nextInt(maxSize - minSize + 1);
            int rh = minSize + rng.nextInt(maxSize - minSize + 1);
            int rx = c.tileX() - rw / 2;
            int ry = c.tileY() - rh / 2;
            if (rx < 1 || ry < 1 || rx + rw >= w - 1 || ry + rh >= h - 1) continue;
            boolean overlap = false;
            for (int[] r : rooms) {
                if (rectsOverlap(r, rx, ry, rw, rh, 1)) { overlap = true; break; }
            }
            if (overlap) continue;
            LevelFactoryUtils.carveRect(level, rx, ry, rw, rh);
            rooms.add(new int[]{rx, ry, rw, rh});
        }
        connectMst(level, rooms, rng);
        // Bottom side too - Poisson sometimes under-shoots if the disc sampler
        // happens to place few centres; top up so every level lands at target.
        topUpPackedRooms(level, rooms, rng, target);
        return rooms;
    }

    /**
     * Figure-eight layout: a pivot room in the middle of the level plus two cycles of rooms
     * arranged on its left and right, each cycle including the pivot. Walking either cycle
     * brings you back to the pivot, from which you can cross to the opposite side - the
     * level reads as a horizontal "8".
     */
    private static List<int[]> buildFigureEight(Level level, Random rng) {
        int w = level.width, h = level.height;
        int cy = h / 2;
        int aCx = w / 4 + 1, bCx = 3 * w / 4 - 1;
        int minSize = GameBalance.ROOM_MIN_SIZE;
        int maxSize = GameBalance.ROOM_MAX_SIZE;
        // Tighter than before (-5/-7) so the two lobes of the figure-eight don't sprawl
        // out to the level edges. Each lobe sits closer to the central pivot, halving the
        // arc length between adjacent rooms.
        int radius  = Math.min((bCx - aCx) / 2 - 2, h / 2 - 9);
        if (radius < 4) radius = Math.max(3, h / 2 - 3);

        List<int[]> rooms = new ArrayList<>();

        // Pivot room sits centred between the two loop centres.
        int prw = minSize + rng.nextInt(maxSize - minSize + 1);
        int prh = minSize + rng.nextInt(maxSize - minSize + 1);
        int prx = (aCx + bCx) / 2 - prw / 2, pry = cy - prh / 2;
        prx = Math.max(1, Math.min(w - prw - 1, prx));
        pry = Math.max(1, Math.min(h - prh - 1, pry));
        LevelFactoryUtils.carveRect(level, prx, pry, prw, prh);
        rooms.add(new int[]{prx, pry, prw, prh});
        final int pivot = 0;

        // Left and right loops - each places n rooms on a half-circle, then connects in a
        // cycle that goes pivot -> first -> ... -> last -> pivot. The arc radius is small (~9
        // cells on a 48x48 level), so cramming 6+ rooms per arc forces them to overlap
        // and fail placement. Cap n at 4 - total rooms = 2*4 + 1 = 9 - which is what
        // 9-radius arcs of 5x5..10x10 rooms can host without collisions. Lower target
        // counts shrink linearly with a floor of 2.
        int n = Math.max(2, Math.min(4, (GameBalance.LEVEL_TARGET_ROOMS - 1) / 2));
        List<Integer> leftIdx  = placeArc(level, rooms, rng, aCx, cy, radius,
                                          Math.PI / 2, 3 * Math.PI / 2, n,
                                          minSize, maxSize);
        List<Integer> rightIdx = placeArc(level, rooms, rng, bCx, cy, radius,
                                          -Math.PI / 2, Math.PI / 2, n,
                                          minSize, maxSize);

        // Stitch each arc into a cycle through the pivot.
        connectArcCycle(level, rooms, leftIdx,  pivot, rng);
        connectArcCycle(level, rooms, rightIdx, pivot, rng);
        // The arc radius is small (~9 cells on 48x48), capping each lobe at 4
        // rooms before they start overlapping. Top up with packed rooms in the
        // remaining empty space (mostly along the level's top + bottom edges)
        // so the per-level target is reliably reached.
        topUpPackedRooms(level, rooms, rng, perLevelRoomTarget(rng));
        return rooms;
    }

    /**
     * Place rooms at evenly spaced angles along an arc from {@code angleFrom} to
     * {@code angleTo} around {@code (cx, cy)} at the given {@code radius}. Returns the
     * indices into {@code rooms} of the successfully placed rooms, in arc order.
     */
    private static List<Integer> placeArc(Level level, List<int[]> rooms, Random rng,
                                          int cx, int cy, int radius,
                                          double angleFrom, double angleTo, int n,
                                          int minSize, int maxSize) {
        int w = level.width, h = level.height;
        List<Integer> idx = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            double a = angleFrom + (angleTo - angleFrom) * (i + 1) / (n + 1);
            int rcx = cx + (int) Math.round(Math.cos(a) * radius);
            int rcy = cy + (int) Math.round(Math.sin(a) * radius);
            int rw = minSize + rng.nextInt(maxSize - minSize + 1);
            int rh = minSize + rng.nextInt(maxSize - minSize + 1);
            int rx = rcx - rw / 2, ry = rcy - rh / 2;
            if (rx < 1 || ry < 1 || rx + rw >= w - 1 || ry + rh >= h - 1) continue;
            boolean overlap = false;
            for (int[] r : rooms) {
                if (rectsOverlap(r, rx, ry, rw, rh, 1)) { overlap = true; break; }
            }
            if (overlap) continue;
            LevelFactoryUtils.carveRect(level, rx, ry, rw, rh);
            idx.add(rooms.size());
            rooms.add(new int[]{rx, ry, rw, rh});
        }
        return idx;
    }

    /** Carve a cycle: pivot -> arc[0] -> arc[1] -> ... -> arc[k-1] -> pivot. */
    private static void connectArcCycle(Level level, List<int[]> rooms, List<Integer> arc,
                                        int pivot, Random rng) {
        if (arc.isEmpty()) return;
        connectTwo(level, rooms.get(pivot), rooms.get(arc.get(0)), rng);
        for (int i = 0; i < arc.size() - 1; i++) {
            connectTwo(level, rooms.get(arc.get(i)), rooms.get(arc.get(i + 1)), rng);
        }
        connectTwo(level, rooms.get(arc.get(arc.size() - 1)), rooms.get(pivot), rng);
    }

    /**
     * Village layout: most of the level interior is one big floor "green" with a jaggy
     * organic outline, and a handful of walled rectangular buildings sit inside it. Each
     * building has exactly one door on a non-corner wall cell. Rooms list is
     * {@code [green, building0, building1, ...]} so stair placement uses the green for up and
     * a building for down.
     */
    private static List<int[]> buildVillage(Level level, Random rng) {
        int w = level.width, h = level.height;
        int gx0 = 2, gy0 = 2, gx1 = w - 3, gy1 = h - 3;
        // Carve a jaggy ellipse covering most of the interior. Per-cell noise breaks the
        // ellipse boundary so the outline reads as organic rather than geometric.
        double cx = (gx0 + gx1) / 2.0, cy = (gy0 + gy1) / 2.0;
        double rx = (gx1 - gx0) / 2.0, ry = (gy1 - gy0) / 2.0;
        for (int x = gx0; x <= gx1; x++) {
            for (int y = gy0; y <= gy1; y++) {
                double nx = (x - cx) / rx, ny = (y - cy) / ry;
                double d = nx * nx + ny * ny;
                d += (rng.nextDouble() - 0.5) * 0.18;
                if (d <= 1.0) level.tiles[x][y] = Tile.FLOOR;
            }
        }

        List<int[]> rooms = new ArrayList<>();
        // Room 0 = village green (bounding box). Used by stair placement; the outer perimeter
        // pass is harmless because the level's outer wall already absorbs anything it would
        // try to door, and pruneOrphanDoors cleans whatever leaks through.
        rooms.add(new int[]{gx0, gy0, gx1 - gx0 + 1, gy1 - gy0 + 1});

        // Place LEVEL_TARGET_ROOMS - 1 buildings (one room is the central green
        // itself), with +/-1 jitter, retrying overlaps. Each is a fully-walled
        // rectangle that sits entirely on FLOOR (so no building wall floats over
        // the irregular boundary), with one door on a non-corner perimeter cell.
        // Building sizes stay at 4..6 - VILLAGE houses are intentionally smaller
        // than the global ROOM_MAX_SIZE.
        int target = Math.max(2, GameBalance.LEVEL_TARGET_ROOMS - 1 + rng.nextInt(3) - 1);
        int tries  = target * 8;
        while (tries-- > 0 && rooms.size() < target + 1) {
            int bw = 4 + rng.nextInt(3);   // 4..6
            int bh = 4 + rng.nextInt(3);
            int bx = gx0 + 2 + rng.nextInt(Math.max(1, gx1 - gx0 - bw - 3));
            int by = gy0 + 2 + rng.nextInt(Math.max(1, gy1 - gy0 - bh - 3));
            // Need a 1-tile floor margin all round the building so the green wraps it.
            boolean canPlace = true;
            for (int x = bx - 1; x <= bx + bw && canPlace; x++) {
                for (int y = by - 1; y <= by + bh; y++) {
                    if (level.tiles[x][y] != Tile.FLOOR) { canPlace = false; break; }
                }
            }
            if (!canPlace) continue;
            // No overlap with another building.
            boolean overlap = false;
            for (int i = 1; i < rooms.size(); i++) {
                if (rectsOverlap(rooms.get(i), bx, by, bw, bh, 2)) { overlap = true; break; }
            }
            if (overlap) continue;

            // Wall the perimeter, leave the interior FLOOR.
            for (int x = bx; x < bx + bw; x++) {
                level.tiles[x][by]          = Tile.WALL;
                level.tiles[x][by + bh - 1] = Tile.WALL;
            }
            for (int y = by; y < by + bh; y++) {
                level.tiles[bx][y]          = Tile.WALL;
                level.tiles[bx + bw - 1][y] = Tile.WALL;
            }
            // One door on a random non-corner perimeter cell.
            int side = rng.nextInt(4);
            int dx, dy;
            switch (side) {
                case 0  -> { dx = bx + 1 + rng.nextInt(bw - 2); dy = by; }
                case 1  -> { dx = bx + 1 + rng.nextInt(bw - 2); dy = by + bh - 1; }
                case 2  -> { dx = bx;                            dy = by + 1 + rng.nextInt(bh - 2); }
                default -> { dx = bx + bw - 1;                   dy = by + 1 + rng.nextInt(bh - 2); }
            }
            level.tiles[dx][dy] = Tile.DOOR;
            rooms.add(new int[]{bx, by, bw, bh});
        }

        // Scatter a few trees on the green for ambience - they block sight, are flammable,
        // and don't grow on their own. Skipped on cells touching a building wall so the
        // door is always visually approachable.
        int trees = 6 + rng.nextInt(8);
        for (int i = 0; i < trees; i++) {
            int tx = gx0 + 1 + rng.nextInt(gx1 - gx0 - 1);
            int ty = gy0 + 1 + rng.nextInt(gy1 - gy0 - 1);
            if (level.tiles[tx][ty] != Tile.FLOOR) continue;
            if (level.vegetation[tx][ty] != null) continue;
            // Skip any tile adjacent to a wall - keeps a clean approach path to building doors.
            boolean nearWall = false;
            for (int dx = -1; dx <= 1 && !nearWall; dx++) {
                for (int dy = -1; dy <= 1; dy++) {
                    int nx = tx + dx, ny = ty + dy;
                    if (nx < 0 || ny < 0 || nx >= w || ny >= h) continue;
                    if (level.tiles[nx][ny] == Tile.WALL || level.tiles[nx][ny] == Tile.DOOR) {
                        nearWall = true; break;
                    }
                }
            }
            if (nearWall) continue;
            level.vegetation[tx][ty] = Vegetation.TREES;
        }
        return rooms;
    }

    /**
     * Two-sides layout: BSP-place a small cluster of rooms in the left third of the level,
     * another cluster in the right third, connect each cluster internally with an MST, and
     * join the two clusters with a single FLOOR_WOOD walkway across the chasm gap. Reads as
     * "two small dungeons stitched together by a bridge".
     */
    private static List<int[]> buildTwoSides(Level level, Random rng) {
        int w = level.width, h = level.height;
        int gapL = w / 3, gapR = 2 * w / 3;

        List<int[]> rooms = new ArrayList<>();
        // Each end of the central walkway gets a tightly-packed cluster - the same
        // greedy growth as {@link Layout#PACKED}, just confined to one half of the
        // level. The packed connector inside each cluster keeps corridors short
        // (rooms share walls; connectPacked punches doors through the shared wall
        // first, falling back to a short L-corridor only for orphans). Per-side
        // target is half the level's room target, with a floor of 3 so even very
        // small target counts produce a recognisable cluster on each end.
        int sideTarget = Math.max(3, GameBalance.LEVEL_TARGET_ROOMS / 2);
        int leftStart  = rooms.size();
        growPackedRooms(level, rooms, rng,
                /*x0=*/1, /*y0=*/1,
                /*regionW=*/gapL - 1, /*regionH=*/h - 2, sideTarget);
        int leftEnd    = rooms.size();
        int rightStart = rooms.size();
        growPackedRooms(level, rooms, rng,
                /*x0=*/gapR + 1, /*y0=*/1,
                /*regionW=*/w - gapR - 2, /*regionH=*/h - 2, sideTarget);
        int rightEnd   = rooms.size();

        // Packed-style connector inside each cluster (door-through-wall first, short
        // L-corridor only as a fallback for orphan groups).
        connectPacked(level, rooms.subList(leftStart,  leftEnd),  rng);
        connectPacked(level, rooms.subList(rightStart, rightEnd), rng);

        // Bridge: pick the rightmost left-room and the leftmost right-room, carve a single
        // FLOOR_WOOD line straight across at a y near the level centre. Plank corridors
        // don't trigger wallInFloors, so the bridge sits over chasm with no walls hugging it.
        if (leftStart < leftEnd && rightStart < rightEnd) {
            int[] bridgeL = rooms.get(leftStart);
            for (int i = leftStart + 1; i < leftEnd; i++) {
                if (rooms.get(i)[0] + rooms.get(i)[2] > bridgeL[0] + bridgeL[2]) bridgeL = rooms.get(i);
            }
            int[] bridgeR = rooms.get(rightStart);
            for (int i = rightStart + 1; i < rightEnd; i++) {
                if (rooms.get(i)[0] < bridgeR[0]) bridgeR = rooms.get(i);
            }
            int lx = bridgeL[0] + bridgeL[2] / 2, ly = bridgeL[1] + bridgeL[3] / 2;
            int rx = bridgeR[0] + bridgeR[2] / 2, ry = bridgeR[1] + bridgeR[3] / 2;
            LevelFactoryUtils.carveCorridorL(level, new Point(lx, ly), new Point(rx, ry),
                    Tile.FLOOR_WOOD, rng);
        }
        return rooms;
    }

    private static boolean rectsOverlap(int[] r, int x, int y, int w, int h, int pad) {
        return r[0] - pad < x + w && r[0] + r[2] + pad > x &&
               r[1] - pad < y + h && r[1] + r[3] + pad > y;
    }

    /** BSP leaf side length that yields roughly {@code target} leaves in a
     *  {@code (w-2) x (h-2)} interior. Floored at {@code maxRoom + 2} so a max-
     *  sized room plus its 1-tile margin always fits in any leaf the partition
     *  produces. Larger levels get larger leaves, keeping room count near target. */
    private static int leafSizeForTarget(int w, int h, int maxRoom, int target) {
        if (target <= 0) target = 1;
        int derived = (int) Math.ceil(Math.sqrt((double)(w - 2) * (h - 2) / target));
        return Math.max(maxRoom + 2, derived);
    }

    /** Poisson-disc minimum spacing that yields roughly {@code target} accepted
     *  rooms on a {@code (w-2) x (h-2)} interior. The 2.0 fudge factor accounts
     *  for centres that fail the post-placement overlap / bounds checks
     *  (empirically ~half of candidate centres survive into a placed room).
     *  Floored at {@code ROOM_MAX_SIZE} so adjacent rooms never overlap. */
    private static double poissonSpacingForTarget(int w, int h, int target) {
        if (target <= 0) target = 1;
        double derived = Math.sqrt((double)(w - 2) * (h - 2) / (target * 2.0));
        return Math.max(GameBalance.ROOM_MAX_SIZE, derived);
    }

    /**
     * PACKED layout: rooms grow off each other with exactly one wall tile between them
     * so the level tiles up like a brick wall. After greedy growth saturates, every pair
     * of wall-adjacent rooms gets a door punched through, then a reachability sweep adds
     * doors / short corridors until every room is reachable from the stair-up room.
     * Reads as a tightly cellular floorplan - no long corridors, almost every interior
     * tile inside a room.
     */
    private static List<int[]> buildPacked(Level level, Random rng) {
        List<int[]> rooms = new ArrayList<>();
        growPackedRooms(level, rooms, rng,
                /*x0=*/0, /*y0=*/0,
                /*regionW=*/level.width, /*regionH=*/level.height,
                /*targetCount=*/GameBalance.LEVEL_TARGET_ROOMS
                        + GameBalance.LEVEL_ROOM_TOLERANCE);
        connectPacked(level, rooms, rng);
        return rooms;
    }

    /**
     * Top up an already-laid-out room list with extra packed rooms until
     * {@code targetCount} is reached. Anchors off any existing room (no seed
     * needed), respects level bounds, and connects each new room to its
     * nearest existing neighbour with a short L-corridor (or a wall-door
     * when they happen to share a wall).
     *
     * <p>Used as a post-pass on {@link #buildBsp}, {@link #buildPoisson}, and
     * {@link #buildFigureEight} - those builders place rooms with their own
     * geometric constraints (BSP partition, Poisson spacing, half-circle arcs)
     * and frequently produce fewer rooms than {@link GameBalance#LEVEL_TARGET_ROOMS}.
     * This helper closes the gap without requiring a redesign of those layouts.
     */
    private static void topUpPackedRooms(Level level, List<int[]> rooms,
                                         Random rng, int targetCount) {
        if (rooms.size() >= targetCount) return;
        int minSize = GameBalance.ROOM_MIN_SIZE;
        int maxSize = GameBalance.ROOM_MAX_SIZE;
        int xMin = 1, yMin = 1;
        int xMax = level.width  - 1;
        int yMax = level.height - 1;
        if (xMax - xMin < minSize + 2 || yMax - yMin < minSize + 2) return;
        if (rooms.isEmpty()) return;   // nothing to anchor off

        int failuresInARow = 0;
        final int MAX_FAILURES = 200;
        while (failuresInARow < MAX_FAILURES && rooms.size() < targetCount) {
            int[] anchor = rooms.get(rng.nextInt(rooms.size()));
            int side = rng.nextInt(4); // 0=N, 1=E, 2=S, 3=W
            int newW = minSize + rng.nextInt(maxSize - minSize + 1);
            int newH = minSize + rng.nextInt(maxSize - minSize + 1);
            int nx, ny;
            switch (side) {
                case 0 -> {
                    ny = anchor[1] + anchor[3] + 1;
                    int slack = anchor[2] + newW - 2;
                    nx = anchor[0] - newW + 1 + (slack > 0 ? rng.nextInt(slack) : 0);
                }
                case 1 -> {
                    nx = anchor[0] + anchor[2] + 1;
                    int slack = anchor[3] + newH - 2;
                    ny = anchor[1] - newH + 1 + (slack > 0 ? rng.nextInt(slack) : 0);
                }
                case 2 -> {
                    ny = anchor[1] - newH - 1;
                    int slack = anchor[2] + newW - 2;
                    nx = anchor[0] - newW + 1 + (slack > 0 ? rng.nextInt(slack) : 0);
                }
                default -> {
                    nx = anchor[0] - newW - 1;
                    int slack = anchor[3] + newH - 2;
                    ny = anchor[1] - newH + 1 + (slack > 0 ? rng.nextInt(slack) : 0);
                }
            }
            if (nx < xMin || ny < yMin || nx + newW > xMax || ny + newH > yMax) {
                failuresInARow++;
                continue;
            }
            boolean overlap = false;
            for (int[] r : rooms) {
                if (rectsOverlap(r, nx, ny, newW, newH, 1)) { overlap = true; break; }
            }
            if (overlap) {
                failuresInARow++;
                continue;
            }
            LevelFactoryUtils.carveRect(level, nx, ny, newW, newH);
            int[] newRoom = new int[]{nx, ny, newW, newH};
            // Connect the new room to its nearest existing neighbour: a wall-door
            // if they happen to share a wall (carveDoorBetween succeeds), otherwise
            // a single L-corridor through whatever's between them. The closest
            // neighbour is usually the anchor itself but isn't always - picking
            // the truly-nearest avoids weird zig-zags when the anchor sits on the
            // far side of a room.
            int best = -1;
            long bestD = Long.MAX_VALUE;
            for (int[] other : rooms) {
                long d = roomCenterDistSq(newRoom, other);
                if (d < bestD) { bestD = d; best = rooms.indexOf(other); }
            }
            int[] near = rooms.get(best);
            if (areWallAdjacent(newRoom, near)) {
                carveDoorBetween(level, newRoom, near, rng);
            } else {
                connectTwo(level, newRoom, near, rng);
            }
            rooms.add(newRoom);
            failuresInARow = 0;
        }
    }

    /**
     * Greedy packed-room growth confined to {@code [x0, x0+regionW) x [y0, y0+regionH)}
     * of the level. Drops a randomly-sized seed near the region's centre, then keeps
     * extending random anchors with a 1-tile gap until {@code targetCount} rooms have
     * landed or the failure budget is exhausted. Used by {@link #buildPacked} for
     * whole-level packing and by {@link #buildTwoSides} to pack each end of the central
     * walkway.
     */
    private static void growPackedRooms(Level level, List<int[]> rooms, Random rng,
                                        int x0, int y0, int regionW, int regionH,
                                        int targetCount) {
        int minSize = GameBalance.ROOM_MIN_SIZE;
        int maxSize = GameBalance.ROOM_MAX_SIZE;
        int xEnd = x0 + regionW;        // exclusive right edge
        int yEnd = y0 + regionH;        // exclusive top edge (y-up)
        int xMin = Math.max(1, x0);
        int yMin = Math.max(1, y0);
        int xMax = Math.min(level.width  - 1, xEnd);
        int yMax = Math.min(level.height - 1, yEnd);
        if (xMax - xMin < minSize + 2 || yMax - yMin < minSize + 2) return;

        int countBefore = rooms.size();

        // Seed room - randomly sized, planted near the region's centre so growth has
        // space to expand in every direction.
        int seedW = minSize + rng.nextInt(maxSize - minSize + 1);
        int seedH = minSize + rng.nextInt(maxSize - minSize + 1);
        if (seedW > xMax - xMin - 2 || seedH > yMax - yMin - 2) return;
        int seedX = xMin + ((xMax - xMin) - seedW) / 2;
        int seedY = yMin + ((yMax - yMin) - seedH) / 2;
        // If a previous call placed rooms that overlap our seed, skip seeding.
        for (int[] r : rooms) {
            if (rectsOverlap(r, seedX, seedY, seedW, seedH, 1)) return;
        }
        LevelFactoryUtils.carveRect(level, seedX, seedY, seedW, seedH);
        rooms.add(new int[]{seedX, seedY, seedW, seedH});

        // Greedy growth: pick a random anchor (only from rooms we placed in THIS region -
        // anchors from other regions would walk the candidate out of bounds), pick a
        // side, drop a candidate alongside with a 1-tile gap, accept if it stays in
        // the region and doesn't collide. The failure budget governs density.
        int failuresInARow = 0;
        final int MAX_FAILURES = 150;
        while (failuresInARow < MAX_FAILURES && rooms.size() < countBefore + targetCount) {
            int[] anchor = rooms.get(countBefore + rng.nextInt(rooms.size() - countBefore));
            int side = rng.nextInt(4); // 0=N, 1=E, 2=S, 3=W
            int newW = minSize + rng.nextInt(maxSize - minSize + 1);
            int newH = minSize + rng.nextInt(maxSize - minSize + 1);
            int nx, ny;
            switch (side) {
                case 0 -> { // North (y-up: north = larger y)
                    ny = anchor[1] + anchor[3] + 1;
                    int slack = anchor[2] + newW - 2;
                    nx = anchor[0] - newW + 1 + (slack > 0 ? rng.nextInt(slack) : 0);
                }
                case 1 -> { // East
                    nx = anchor[0] + anchor[2] + 1;
                    int slack = anchor[3] + newH - 2;
                    ny = anchor[1] - newH + 1 + (slack > 0 ? rng.nextInt(slack) : 0);
                }
                case 2 -> { // South
                    ny = anchor[1] - newH - 1;
                    int slack = anchor[2] + newW - 2;
                    nx = anchor[0] - newW + 1 + (slack > 0 ? rng.nextInt(slack) : 0);
                }
                default -> { // West
                    nx = anchor[0] - newW - 1;
                    int slack = anchor[3] + newH - 2;
                    ny = anchor[1] - newH + 1 + (slack > 0 ? rng.nextInt(slack) : 0);
                }
            }
            if (nx < xMin || ny < yMin || nx + newW > xMax || ny + newH > yMax) {
                failuresInARow++;
                continue;
            }
            boolean overlap = false;
            for (int[] r : rooms) {
                if (rectsOverlap(r, nx, ny, newW, newH, 1)) { overlap = true; break; }
            }
            if (overlap) {
                failuresInARow++;
                continue;
            }
            LevelFactoryUtils.carveRect(level, nx, ny, newW, newH);
            rooms.add(new int[]{nx, ny, newW, newH});
            failuresInARow = 0;
        }
    }

    /**
     * Connect packed rooms. First pass punches a door through every wall-adjacent pair
     * (rooms separated by exactly one wall column or row, with non-empty perpendicular
     * overlap). Second pass BFSes from room 0 - any orphan group is wired in via the
     * shortest available adjacency, falling back to a short L-corridor when the orphan
     * doesn't actually touch the connected component.
     */
    private static void connectPacked(Level level, List<int[]> rooms, Random rng) {
        int n = rooms.size();
        if (n < 2) return;

        boolean[][] adjacent = new boolean[n][n];
        for (int i = 0; i < n; i++) {
            for (int j = i + 1; j < n; j++) {
                if (areWallAdjacent(rooms.get(i), rooms.get(j))) {
                    adjacent[i][j] = true;
                    adjacent[j][i] = true;
                }
            }
        }

        // First pass - wall-punch every adjacent pair. carveDoorBetween writes a single
        // FLOOR tile into the wall column/row; placeDoors() promotes it to DOOR later.
        boolean[] connectedToZero = new boolean[n];
        for (int i = 0; i < n; i++) {
            for (int j = i + 1; j < n; j++) {
                if (adjacent[i][j]) carveDoorBetween(level, rooms.get(i), rooms.get(j), rng);
            }
        }

        // BFS from room 0 over the adjacency graph (any wall-adjacent pair is now joined
        // by a door, so adjacency == reachability after the first pass).
        java.util.ArrayDeque<Integer> q = new java.util.ArrayDeque<>();
        connectedToZero[0] = true;
        q.add(0);
        while (!q.isEmpty()) {
            int u = q.poll();
            for (int v = 0; v < n; v++) {
                if (!adjacent[u][v] || connectedToZero[v]) continue;
                connectedToZero[v] = true;
                q.add(v);
            }
        }

        // Patch orphans: for any unreached room, find the closest reached room. If they
        // happen to be adjacent (we missed some edge above) punch a door; otherwise carve
        // a short L-corridor through the chasm gap.
        for (int i = 0; i < n; i++) {
            if (connectedToZero[i]) continue;
            int best = -1;
            long bestD = Long.MAX_VALUE;
            for (int j = 0; j < n; j++) {
                if (!connectedToZero[j]) continue;
                long d = roomCenterDistSq(rooms.get(i), rooms.get(j));
                if (d < bestD) { bestD = d; best = j; }
            }
            if (best < 0) continue;
            if (adjacent[i][best]) {
                carveDoorBetween(level, rooms.get(i), rooms.get(best), rng);
            } else {
                connectTwo(level, rooms.get(i), rooms.get(best), rng);
            }
            // Re-flood from i so its own wall-neighbours pick up reachability transitively.
            connectedToZero[i] = true;
            q.add(i);
            while (!q.isEmpty()) {
                int u = q.poll();
                for (int v = 0; v < n; v++) {
                    if (!adjacent[u][v] || connectedToZero[v]) continue;
                    connectedToZero[v] = true;
                    q.add(v);
                }
            }
        }
    }

    /** True iff {@code a} and {@code b} are separated by exactly one wall column or row
     *  (1-tile gap) AND overlap on the perpendicular axis. Mirrors the placement rule
     *  enforced by {@link #rectsOverlap} with pad = 1. */
    private static boolean areWallAdjacent(int[] a, int[] b) {
        // Horizontal neighbour - wall column between them
        if (b[0] == a[0] + a[2] + 1 || a[0] == b[0] + b[2] + 1) {
            int yLo = Math.max(a[1], b[1]);
            int yHi = Math.min(a[1] + a[3] - 1, b[1] + b[3] - 1);
            return yHi >= yLo;
        }
        // Vertical neighbour - wall row between them
        if (b[1] == a[1] + a[3] + 1 || a[1] == b[1] + b[3] + 1) {
            int xLo = Math.max(a[0], b[0]);
            int xHi = Math.min(a[0] + a[2] - 1, b[0] + b[2] - 1);
            return xHi >= xLo;
        }
        return false;
    }

    /** Punch a single FLOOR tile in the wall column / row separating {@code a} from
     *  {@code b}. {@link #placeDoors} promotes that floor cell to DOOR in its later sweep.
     *  No-op for non-adjacent pairs. */
    private static boolean carveDoorBetween(Level level, int[] a, int[] b, Random rng) {
        // Normalise so a is the rectangle further west / further south of the pair.
        if (a[0] == b[0] + b[2] + 1) { int[] t = a; a = b; b = t; }
        if (a[1] == b[1] + b[3] + 1) { int[] t = a; a = b; b = t; }
        if (b[0] == a[0] + a[2] + 1) {
            int wallX = a[0] + a[2];
            int yLo = Math.max(a[1], b[1]);
            int yHi = Math.min(a[1] + a[3] - 1, b[1] + b[3] - 1);
            if (yHi < yLo) return false;
            int yPick = yLo + rng.nextInt(yHi - yLo + 1);
            level.tiles[wallX][yPick] = Tile.FLOOR;
            return true;
        }
        if (b[1] == a[1] + a[3] + 1) {
            int wallY = a[1] + a[3];
            int xLo = Math.max(a[0], b[0]);
            int xHi = Math.min(a[0] + a[2] - 1, b[0] + b[2] - 1);
            if (xHi < xLo) return false;
            int xPick = xLo + rng.nextInt(xHi - xLo + 1);
            level.tiles[xPick][wallY] = Tile.FLOOR;
            return true;
        }
        return false;
    }

    private static long roomCenterDistSq(int[] a, int[] b) {
        long ax = a[0] + a[2] / 2L, ay = a[1] + a[3] / 2L;
        long bx = b[0] + b[2] / 2L, by = b[1] + b[3] / 2L;
        long dx = ax - bx, dy = ay - by;
        return dx * dx + dy * dy;
    }

    // -------------------------------------------------------------------------
    // Corridors
    // -------------------------------------------------------------------------

    /**
     * Connect every pair of rooms in the MST of their centres (Prim's), then sprinkle
     * {@code n/4} extra random edges to introduce loops. Each edge is carved as an
     * L-shape via {@link LevelFactoryUtils#carveCorridorL}.
     */
    private static void connectMst(Level level, List<int[]> rooms, Random rng) {
        int n = rooms.size();
        if (n < 2) return;

        boolean[] inTree = new boolean[n];
        int[] parent = new int[n];
        long[] best = new long[n];
        Arrays.fill(parent, -1);
        Arrays.fill(best, Long.MAX_VALUE);
        best[0] = 0;

        for (int step = 0; step < n; step++) {
            int u = -1;
            for (int v = 0; v < n; v++) {
                if (!inTree[v] && (u == -1 || best[v] < best[u])) u = v;
            }
            if (u == -1 || best[u] == Long.MAX_VALUE && step > 0) break;
            inTree[u] = true;
            if (parent[u] != -1) connectTwo(level, rooms.get(u), rooms.get(parent[u]), rng);

            int[] ru = rooms.get(u);
            long ux = ru[0] + ru[2] / 2, uy = ru[1] + ru[3] / 2;
            for (int v = 0; v < n; v++) {
                if (inTree[v]) continue;
                int[] rv = rooms.get(v);
                long vx = rv[0] + rv[2] / 2, vy = rv[1] + rv[3] / 2;
                long d = (ux - vx) * (ux - vx) + (uy - vy) * (uy - vy);
                if (d < best[v]) { best[v] = d; parent[v] = u; }
            }
        }

        int extras = Math.max(1, n / 4);
        for (int e = 0; e < extras; e++) {
            int a = rng.nextInt(n), b = rng.nextInt(n);
            if (a == b) continue;
            connectTwo(level, rooms.get(a), rooms.get(b), rng);
        }
    }

    private static void connectTwo(Level level, int[] a, int[] b, Random rng) {
        Tile corridor = level.flags.contains(LevelFlag.WALKWAY_LEVEL)
                ? Tile.FLOOR_WOOD : Tile.FLOOR;
        int ax = a[0] + a[2] / 2, ay = a[1] + a[3] / 2;
        int bx = b[0] + b[2] / 2, by = b[1] + b[3] / 2;
        Point from = new Point(ax, ay), to = new Point(bx, by);
        if (level.flags.contains(LevelFlag.ROUGH)) {
            LevelFactoryUtils.carveCorridorRough(level, from, to, corridor, rng);
        } else {
            LevelFactoryUtils.carveCorridorL(level, from, to, corridor, rng);
        }
    }

    // -------------------------------------------------------------------------
    // Room kinds + interiors
    // -------------------------------------------------------------------------

    // -------------------------------------------------------------------------
    // Doors
    // -------------------------------------------------------------------------

    /**
     * For every room, any FLOOR or FLOOR_WOOD tile on the 1-tile perimeter ring around the
     * room becomes a DOOR. The ring is the cells just outside the room's rect, where
     * corridors land after threading through chasm. Run after {@link LevelFactoryUtils#wallInFloors}.
     */
    private static void placeDoors(Level level, List<int[]> rooms) {
        for (int[] r : rooms) {
            int x0 = r[0], y0 = r[1], w = r[2], h = r[3];
            for (int x = x0 - 1; x <= x0 + w; x++) {
                tryDoor(level, x, y0 - 1);
                tryDoor(level, x, y0 + h);
            }
            for (int y = y0 - 1; y <= y0 + h; y++) {
                tryDoor(level, x0 - 1, y);
                tryDoor(level, x0 + w, y);
            }
        }
    }

    private static void tryDoor(Level level, int x, int y) {
        if (x <= 0 || y <= 0 || x >= level.width - 1 || y >= level.height - 1) return;
        Tile t = level.tiles[x][y];
        if (t == Tile.FLOOR || t == Tile.FLOOR_WOOD) level.tiles[x][y] = Tile.DOOR;
    }

    private static void changeRoomDoors(Level level, int[] room, Tile from, Tile to) {
        int x0 = room[0], y0 = room[1], w = room[2], h = room[3];
        for (int x = x0 - 1; x <= x0 + w; x++) {
            swapDoor(level, x, y0 - 1, from, to);
            swapDoor(level, x, y0 + h, from, to);
        }
        for (int y = y0; y < y0 + h; y++) {
            swapDoor(level, x0 - 1, y, from, to);
            swapDoor(level, x0 + w, y, from, to);
        }
    }

    private static void swapDoor(Level level, int x, int y, Tile from, Tile to) {
        if (x <= 0 || y <= 0 || x >= level.width - 1 || y >= level.height - 1) return;
        if (level.tiles[x][y] == from) level.tiles[x][y] = to;
    }

    // -------------------------------------------------------------------------
    // Stairs
    // -------------------------------------------------------------------------

    private static void placeStairs(Level level, List<int[]> rooms, boolean hasUp, boolean hasDown,
                                    Random rng) {
        if (rooms.isEmpty()) return;
        int upIdx = 0, downIdx = rooms.size() - 1;
        if (hasUp) {
            Point p = randomFloorIn(level, rooms.get(upIdx), rng);
            if (p != null) {
                level.tiles[p.tileX()][p.tileY()] = Tile.STAIRS_UP;
                level.stairsUp   = p;
                level.spawnPoint = p;
            }
        }
        if (hasDown) {
            Point p = randomFloorIn(level, rooms.get(downIdx), rng);
            // Only one room -> up and down would collide; shuffle the down-stair onto a
            // different tile in the same room.
            if (p != null && p.equals(level.stairsUp)) {
                Point alt = randomFloorIn(level, rooms.get(downIdx), rng);
                if (alt != null && !alt.equals(level.stairsUp)) p = alt;
            }
            if (p != null) {
                level.tiles[p.tileX()][p.tileY()] = Tile.STAIRS_DOWN;
                level.stairsDown = p;
            }
        }
        if (level.spawnPoint == null) {
            level.spawnPoint = randomFloorIn(level, rooms.get(upIdx), rng);
        }
    }

    /**
     * Place a second downstair on a floor cell as far as possible from the existing
     * {@code stairsDown}, recording the position in {@code stairsDownAlt}. Used by the
     * topmost level in the diamond topology, which has two down-staircases (one to the
     * west depth-2 level, one to the east). No-op if the level has no {@code stairsDown}
     * yet, no spare floor tile, or the alt has already been placed.
     *
     * <p>The W/E assignment is implicit: whichever of {@code stairsDown} / {@code stairsDownAlt}
     * has the smaller X coordinate is treated as the "west" stair by callers, but the
     * picker here just maximises distance - the caller can swap the two fields if it
     * wants a deterministic mapping.
     */
    public static void addAltStairsDown(Level level) {
        if (level == null || level.stairsDown == null) return;
        if (level.stairsDownAlt != null) return;
        Point p = farthestFreeFloor(level, level.stairsDown);
        if (p == null) return;
        level.tiles[p.tileX()][p.tileY()] = Tile.STAIRS_DOWN;
        level.stairsDownAlt = p;
    }

    /** Sibling of {@link #addAltStairsDown} for the deepest level: place a second up-stair
     *  far from the existing {@code stairsUp}. */
    public static void addAltStairsUp(Level level) {
        if (level == null || level.stairsUp == null) return;
        if (level.stairsUpAlt != null) return;
        Point p = farthestFreeFloor(level, level.stairsUp);
        if (p == null) return;
        level.tiles[p.tileX()][p.tileY()] = Tile.STAIRS_UP;
        level.stairsUpAlt = p;
    }

    /** Find a FLOOR or FLOOR_WOOD tile maximising squared distance from {@code from}, that
     *  isn't already a stair. Falls back to any free floor cell if none qualify. */
    private static Point farthestFreeFloor(Level level, Point from) {
        int fx = from.tileX(), fy = from.tileY();
        Point best = null;
        long bestD = -1;
        for (int x = 1; x < level.width - 1; x++) {
            for (int y = 1; y < level.height - 1; y++) {
                Tile t = level.tiles[x][y];
                if (t != Tile.FLOOR && t != Tile.FLOOR_WOOD) continue;
                long dx = x - fx, dy = y - fy;
                long d = dx * dx + dy * dy;
                if (d > bestD) { bestD = d; best = new Point(x, y); }
            }
        }
        return best;
    }

    private static Point randomFloorIn(Level level, int[] r, Random rng) {
        // Strict pass - interior cells (skip the 1-cell perimeter ring) that aren't
        // cardinally adjacent to a door. This is the preferred slot for a stair so the
        // glyph isn't pressed against a wall or jammed in a doorway.
        List<Point> strict   = new ArrayList<>();
        // Relaxed pass - any interior FLOOR cell, including door-adjacent. Used only as
        // a fallback when the strict pass found nothing (small or door-heavy rooms).
        List<Point> relaxed  = new ArrayList<>();
        // Loose pass - any FLOOR cell at all, edge or otherwise. Last-ditch so a stair
        // never silently fails to place.
        List<Point> anywhere = new ArrayList<>();
        int x0 = r[0], y0 = r[1], w = r[2], h = r[3];
        for (int x = x0; x < x0 + w; x++) {
            for (int y = y0; y < y0 + h; y++) {
                if (level.tiles[x][y] != Tile.FLOOR) continue;
                anywhere.add(new Point(x, y));
                boolean onEdge = x == x0 || y == y0 || x == x0 + w - 1 || y == y0 + h - 1;
                if (onEdge) continue;
                relaxed.add(new Point(x, y));
                if (LevelFactoryUtils.adjacentToDoor(level, x, y)) continue;
                strict.add(new Point(x, y));
            }
        }
        if (!strict.isEmpty())   return strict  .get(rng.nextInt(strict.size()));
        if (!relaxed.isEmpty())  return relaxed .get(rng.nextInt(relaxed.size()));
        if (!anywhere.isEmpty()) return anywhere.get(rng.nextInt(anywhere.size()));
        return null;
    }
}
