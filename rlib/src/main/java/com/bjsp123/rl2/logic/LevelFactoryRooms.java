package com.bjsp123.rl2.logic;

import com.bjsp123.rl2.model.Item;
import com.bjsp123.rl2.model.Level;
import com.bjsp123.rl2.model.Mob;
import com.bjsp123.rl2.model.Point;
import com.bjsp123.rl2.model.Tile;

import java.util.Random;

/**
 * Low-level room-shape + statue/lamp helpers used by themed-room painting.
 * Every variant of decoration is now expressed as data in
 * {@code assets/data/themedrooms.csv} and applied by {@link ThemedRoomPainter};
 * this class just provides the shape primitives (round-corner walls, walkway
 * chasm + plank corridors, random chasm patch, walled subroom) and the
 * statue / lamp drop helpers that the painter calls into.
 */
public final class LevelFactoryRooms {

    private LevelFactoryRooms() {}

    // -- Statue helpers -----------------------------------------------------

    static Tile randomSmallStatue(Random rng) {
        return rng.nextBoolean() ? Tile.STATUE_SMALL_L : Tile.STATUE_SMALL_R;
    }

    static Tile randomLargeStatue(Random rng) {
        return rng.nextBoolean() ? Tile.STATUE_LARGE_L : Tile.STATUE_LARGE_R;
    }

    /** Drop a statue at (x, y) only if the cell is plain FLOOR and not adjacent to a door
     *  - statues next to doorways read as obstacles blocking the passage. Guards against
     *  painting over chasm, walls, doors, lamps, or another statue we just placed.
     *  Package-private so {@code ThemedRoomPainter} can stamp statue patterns through
     *  the same safety check. */
    static void placeStatue(Level level, int x, int y, Tile statue) {
        if (!LevelFactoryUtils.inBounds(level, x, y)) return;
        if (!level.tiles[x][y].canHoldItem()) return;
        if (LevelFactoryUtils.adjacentToDoor(level, x, y)) return;
        level.tiles[x][y] = statue;
    }

    /** Drop a LAMP tile at (x, y) under the same rules as {@link #placeStatue} - FLOOR-only,
     *  and never adjacent to a doorway (so an arched door doesn't get a lamp post bolted
     *  onto its threshold). Package-private. */
    static void placeLamp(Level level, int x, int y) {
        if (!LevelFactoryUtils.inBounds(level, x, y)) return;
        if (!level.tiles[x][y].canHoldItem()) return;
        if (LevelFactoryUtils.adjacentToDoor(level, x, y)) return;
        level.tiles[x][y] = Tile.LAMP;
    }

    /** Drop a BEACON_INACTIVE tile at (x, y) under the same rules as
     *  {@link #placeStatue}. Beacons activate later, when the player steps
     *  adjacent. Also drops a TELEPORT_ORB on a nearby floor tile and seeds a
     *  beacon-room encounter (depth-appropriate WRAITH cluster + a
     *  mirror-match enemy of a random PLAYER class) so every beacon room
     *  ships fully populated. Package-private. */
    static void placeBeacon(Level level, int x, int y, java.util.Random rng) {
        if (!LevelFactoryUtils.inBounds(level, x, y)) return;
        if (!level.tiles[x][y].canHoldItem()) return;
        if (LevelFactoryUtils.adjacentToDoor(level, x, y)) return;
        level.tiles[x][y] = Tile.BEACON_INACTIVE;
        placeBeaconOrb(level, x, y);
        spawnBeaconEncounter(level, x, y, rng);
    }

    /** Drop a TELEPORT_ORB on a floor tile near the beacon at {@code (bx, by)}.
     *  Scans an expanding ring (radius 1..3); the first FLOOR tile that can
     *  hold an item and isn't adjacent to a door wins. Silently bails when no
     *  candidate exists - a beacon crammed into a 1-tile alcove will simply
     *  ship without an orb. */
    private static void placeBeaconOrb(Level level, int bx, int by) {
        Item orb = ItemFactory.build("TELEPORT_ORB");
        if (orb == null) return;
        for (int r = 1; r <= 3; r++) {
            for (int dy = -r; dy <= r; dy++) {
                for (int dx = -r; dx <= r; dx++) {
                    if (Math.max(Math.abs(dx), Math.abs(dy)) != r) continue;
                    int x = bx + dx, y = by + dy;
                    if (!LevelFactoryUtils.inBounds(level, x, y)) continue;
                    if (!level.tiles[x][y].canHoldItem()) continue;
                    if (LevelFactoryUtils.adjacentToDoor(level, x, y)) continue;
                    orb.location = new Point(x, y);
                    level.items.add(orb);
                    return;
                }
            }
        }
    }

    /** Populate the room around the beacon at {@code (bx, by)} with the boss
     *  encounter: a cluster of depth-appropriate wraiths plus a single
     *  mirror-match enemy of a random {@link Mob.CharacterClass}. Wraith
     *  variant is chosen by depth-fraction band (matching the rows'
     *  {@code minPowerLevel}/{@code maxPowerLevel} in mobs.csv); the
     *  cluster size is fixed per variant. The encounter scales with depth
     *  via the standard {@code 1 + level.depth} spawn-level rule.
     *
     *  <p>Spots are picked off the surrounding rings via a BFS-style scan;
     *  the mirror takes the first valid floor tile (closest to the beacon),
     *  the wraiths fill the remaining ring positions. Tiles that already
     *  carry a mob or that fall on a reserved themed-room rect are skipped. */
    private static void spawnBeaconEncounter(Level level, int bx, int by,
                                             java.util.Random rng) {
        double frac = LevelFactoryPopulate.depthFraction(level);
        String wraithType;
        int wraithCount;
        if      (frac < 0.5) { wraithType = "WRAITH";       wraithCount = 4; }
        else if (frac < 0.8) { wraithType = "LARGE_WRAITH"; wraithCount = 3; }
        else                 { wraithType = "AWFUL_WRAITH"; wraithCount = 2; }
        int spawnLevel = 1 + level.depth;

        Mob.CharacterClass[] classes = Mob.CharacterClass.values();
        // Beacon encounter boss: use the data-driven ENEMY_PLAYER_* mob row
        // (mobs.csv) rather than re-using the actual PLAYER_* kit. The
        // ENEMY_PLAYER row carries the right faction, drop suppression
        // (NOTHING_AT_ALL), and a LEVEL_APPROPRIATE inventory keyword that
        // generates depth-appropriate gear at spawn time.
        String mirrorType = "ENEMY_PLAYER_" + classes[rng.nextInt(classes.length)].name();

        // Gather candidate floor tiles in expanding rings around the beacon.
        java.util.List<Point> spots = new java.util.ArrayList<>();
        for (int r = 1; r <= 4 && spots.size() < wraithCount + 1; r++) {
            for (int dy = -r; dy <= r; dy++) {
                for (int dx = -r; dx <= r; dx++) {
                    if (Math.max(Math.abs(dx), Math.abs(dy)) != r) continue;
                    int x = bx + dx, y = by + dy;
                    if (!LevelFactoryUtils.inBounds(level, x, y)) continue;
                    if (level.tiles[x][y] != Tile.FLOOR) continue;
                    boolean occupied = false;
                    for (Mob existing : level.mobs) {
                        if (existing.position == null) continue;
                        if (existing.position.tileX() == x
                                && existing.position.tileY() == y) {
                            occupied = true;
                            break;
                        }
                    }
                    if (occupied) continue;
                    spots.add(new Point(x, y));
                }
            }
        }
        if (spots.isEmpty()) return;

        int idx = 0;
        // Boss spawn flows through the standard spawnMobAt path so the
        // LEVEL_APPROPRIATE hook in MobDefinition fires - the mob walks out
        // with a depth-tier weapon + armor + amulet + damage wand + damage
        // bombs plus its class jade. Spawn level is the dungeon depth (one
        // higher than the wraith pack at 1+depth) so it reads as the boss.
        LevelFactoryPopulate.spawnMobAt(level, mirrorType, spots.get(idx++),
                level.depth, rng, /*withRetainers=*/ false);
        for (int i = 0; i < wraithCount && idx < spots.size(); i++) {
            Mob w = MobFactory.spawn(wraithType, spots.get(idx++));
            if (w == null) continue;
            MobProgression.setSpawnLevel(w, spawnLevel);
            level.mobs.add(w);
        }
    }

    // -- ROUND --------------------------------------------------------------

    /** Round the corners of the room: any FLOOR cell outside the inscribed ellipse becomes
     *  WALL. Walls and doors are already in place by the time this runs, so painting WALL
     *  directly (rather than CHASM + relying on a later wall pass) keeps the geometry crisp.
     *
     *  <p>One subtlety: doors live on the rectangle's outer perimeter. The cell directly
     *  inside a door (one step into the rect) is on a corner of the rectangle for a
     *  door near a corner, and that corner cell sits OUTSIDE the inscribed ellipse -
     *  walling it would orphan the door, leaving a doorframe that opens onto solid wall.
     *  We detect that case and keep the cell as FLOOR so the door has a 1-tile alcove
     *  reaching into the round room interior. */
    static void paintRound(Level level, int x, int y, int w, int h) {
        if (w < 3 || h < 3) return;
        double cx = x + (w - 1) / 2.0;
        double cy = y + (h - 1) / 2.0;
        double rx = w / 2.0;
        double ry = h / 2.0;
        for (int i = x; i < x + w; i++) {
            for (int j = y; j < y + h; j++) {
                if (!LevelFactoryUtils.inBounds(level, i, j)) continue;
                if (!level.tiles[i][j].canHoldItem()) continue;
                double dx = (i - cx) / rx;
                double dy = (j - cy) / ry;
                if (dx * dx + dy * dy <= 1.0) continue;
                // Outside the ellipse - would normally wall in. Skip if a door sits on
                // any cardinal neighbour (i.e. on the rectangle's outer ring), so the
                // door retains a FLOOR alcove leading into the room.
                if (hasAdjacentDoor(level, i, j)) continue;
                level.tiles[i][j] = Tile.WALL;
            }
        }
    }

    /** True if any of {@code (i, j)}'s four cardinal neighbours is a {@link Tile#DOOR} or
     *  {@link Tile#DOOR_OPEN}. Used by {@link #paintRound} to keep a 1-tile floor alcove
     *  alive for doors near corner positions. */
    private static boolean hasAdjacentDoor(Level level, int i, int j) {
        int[][] dirs = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
        for (int[] d : dirs) {
            int ni = i + d[0], nj = j + d[1];
            if (!LevelFactoryUtils.inBounds(level, ni, nj)) continue;
            Tile t = level.tiles[ni][nj];
            if (t == Tile.DOOR || t == Tile.DOOR_OPEN) return true;
        }
        return false;
    }

    // -- WALKWAY ------------------------------------------------------------

    /**
     * Walkway room: interior becomes CHASM, then a FLOOR_WOOD L-corridor is carved from the
     * inside neighbour of every door on the room's outer wall to a central hub. Doors are
     * already placed by the orchestrator before painters run, so we can address each door
     * directly. Rooms with no doors end up as a sealed chasm pit.
     */
    static void paintWalkway(Level level, int x, int y, int w, int h) {
        for (int i = x; i < x + w; i++)
            for (int j = y; j < y + h; j++)
                if (LevelFactoryUtils.inBounds(level, i, j))
                    level.tiles[i][j] = Tile.CHASM;

        int cx = x + w / 2;
        int cy = y + h / 2;
        plankCell(level, cx, cy);

        // Walk the four perimeter edges and carve a plank L from each door's inside cell to
        // the hub. The (ix, iy) offset points into the room from the door tile.
        for (int i = x - 1; i <= x + w; i++) {
            carveWalkwayFromDoor(level, i, y - 1,    0,  1, cx, cy);  // top wall
            carveWalkwayFromDoor(level, i, y + h,    0, -1, cx, cy);  // bottom wall
        }
        for (int j = y - 1; j <= y + h; j++) {
            carveWalkwayFromDoor(level, x - 1,    j,  1,  0, cx, cy);  // left wall
            carveWalkwayFromDoor(level, x + w,    j, -1,  0, cx, cy);  // right wall
        }
    }

    /** If {@code (dx, dy)} is a DOOR, carve a FLOOR_WOOD L-corridor from its inside neighbour
     *  {@code (dx + ix, dy + iy)} to {@code (cx, cy)}. Idempotent - overlapping carves are
     *  harmless. */
    private static void carveWalkwayFromDoor(Level level, int dx, int dy, int ix, int iy,
                                             int cx, int cy) {
        if (!LevelFactoryUtils.inBounds(level, dx, dy)) return;
        if (level.tiles[dx][dy] != Tile.DOOR) return;
        int sx = dx + ix, sy = dy + iy;
        for (int xx = Math.min(sx, cx); xx <= Math.max(sx, cx); xx++) plankCell(level, xx, sy);
        for (int yy = Math.min(sy, cy); yy <= Math.max(sy, cy); yy++) plankCell(level, cx, yy);
    }

    private static void plankCell(Level level, int x, int y) {
        if (LevelFactoryUtils.inBounds(level, x, y)) level.tiles[x][y] = Tile.FLOOR_WOOD;
    }

    // -- CHASM --------------------------------------------------------------

    /** A chasm patch in the middle of the room that does not reach the edges. */
    static void paintChasm(Level level, int x, int y, int w, int h, Random rng) {
        if (w < 4 || h < 4) return;
        int maxIW = w - 2, maxIH = h - 2;
        int cw = 1 + rng.nextInt(maxIW - 1);
        int ch = 1 + rng.nextInt(maxIH - 1);
        int cx = x + 1 + rng.nextInt(w - cw - 1);
        int cy = y + 1 + rng.nextInt(h - ch - 1);
        for (int i = cx; i < cx + cw; i++)
            for (int j = cy; j < cy + ch; j++)
                if (LevelFactoryUtils.inBounds(level, i, j) && level.tiles[i][j] == Tile.FLOOR)
                    level.tiles[i][j] = Tile.CHASM;
    }

    // -- SUBROOM ------------------------------------------------------------

    /**
     * A smaller walled rectangle inside the main room with a single door on its perimeter.
     * The interior of the subroom stays FLOOR; only the perimeter (minus one door) becomes
     * WALL. Needs at least a 5x5 outer rect to fit a 3x3 inner with margin.
     */
    static void paintSubroom(Level level, int x, int y, int w, int h, Random rng) {
        if (w < 5 || h < 5) return;
        int iw = 3 + rng.nextInt(Math.max(1, w - 4));
        int ih = 3 + rng.nextInt(Math.max(1, h - 4));
        int ix = x + 1 + rng.nextInt(w - iw - 1);
        int iy = y + 1 + rng.nextInt(h - ih - 1);

        for (int i = ix; i < ix + iw; i++) {
            wallCell(level, i, iy);
            wallCell(level, i, iy + ih - 1);
        }
        for (int j = iy; j < iy + ih; j++) {
            wallCell(level, ix, j);
            wallCell(level, ix + iw - 1, j);
        }

        // Door on a random non-corner perimeter cell.
        int side = rng.nextInt(4);
        int dx, dy;
        switch (side) {
            case 0  -> { dx = ix + 1 + rng.nextInt(iw - 2); dy = iy; }
            case 1  -> { dx = ix + 1 + rng.nextInt(iw - 2); dy = iy + ih - 1; }
            case 2  -> { dx = ix;                           dy = iy + 1 + rng.nextInt(ih - 2); }
            default -> { dx = ix + iw - 1;                  dy = iy + 1 + rng.nextInt(ih - 2); }
        }
        if (LevelFactoryUtils.inBounds(level, dx, dy)) level.tiles[dx][dy] = Tile.DOOR;
    }

    private static void wallCell(Level level, int x, int y) {
        if (LevelFactoryUtils.inBounds(level, x, y) && level.tiles[x][y] == Tile.FLOOR)
            level.tiles[x][y] = Tile.WALL;
    }

}
