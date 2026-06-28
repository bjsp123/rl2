package com.bjsp123.rl2.logic;

import com.bjsp123.rl2.model.Item;
import com.bjsp123.rl2.model.Level;
import com.bjsp123.rl2.model.Mob;
import com.bjsp123.rl2.model.Point;
import com.bjsp123.rl2.model.Tile;

import java.util.Random;

/**
 * Stamps a {@link ThemedRoomDefinition}'s decoration onto an already-carved
 * room rectangle. Four orthogonal layers, applied in order so each respects
 * what the previous laid down:
 * <ol>
 *   <li>{@link ThemedRoomDefinition.RoomShape}  - overall floor geometry.</li>
 *   <li>{@link ThemedRoomDefinition.ChasmShape} - chasm overlay.</li>
 *   <li>{@link ThemedRoomDefinition.Vegetation} - only paints over surviving FLOOR.</li>
 *   <li>{@link ThemedRoomDefinition.Decoration} list - statues / lamps via
 *       {@link #placeStatue} / {@link #placeLamp}, which already require
 *       FLOOR + not-near-door.</li>
 * </ol>
 *
 * <p>The low-level shape and statue/lamp/beacon primitives the decoration layer
 * stamps with (round-corner walls, walkway plank corridors, random chasm patch,
 * walled subroom, statue / lamp / beacon drops) live in the "primitives"
 * section at the bottom of this class.
 */
final class ThemedRoomPainter {

    private ThemedRoomPainter() {}

    static void paint(Level level, ThemedRoomDefinition def,
                      int x, int y, int w, int h, Random rng) {
        paintRoomShape   (level, def.roomShape,    x, y, w, h, rng);
        paintChasm       (level, def.chasmShape,   x, y, w, h, rng);
        paintSurface     (level, def.surface,      x, y, w, h);
        paintVegetation  (level, def.vegetation,   x, y, w, h, rng);
        paintSpecialFloor(level, def.specialFloor, x, y, w, h);
        for (ThemedRoomDefinition.Decoration d : def.decorations) {
            paintDecoration(level, d, x, y, w, h, def.placement, rng);
        }
    }

    // -- Room shape ----------------------------------------------------------

    private static void paintRoomShape(Level level,
                                       ThemedRoomDefinition.RoomShape s,
                                       int x, int y, int w, int h, Random rng) {
        switch (s) {
            case RECTANGLE -> { /* default carved rectangle */ }
            case ROUND     -> paintRound   (level, x, y, w, h);
            case WALKWAY   -> paintWalkway (level, x, y, w, h);
            case SUBROOM   -> paintSubroom (level, x, y, w, h, rng);
        }
    }

    // -- Surface -------------------------------------------------------------

    private static void paintSurface(Level level,
                                     ThemedRoomDefinition.Surface s,
                                     int x, int y, int w, int h) {
        switch (s) {
            case NONE              -> { }
            case BLOOD_POOL_CENTER -> paintBloodPoolCenter(level, x, y, w, h);
        }
    }

    /** Roughly 40% of the interior covered by a centred blood patch - matches
     *  the previous SHROOM_FARM blood-pool layout. The patch is inset by 2 cells
     *  from each edge so the door fringe stays dry. */
    private static void paintBloodPoolCenter(Level level, int x, int y, int w, int h) {
        if (w < 3 || h < 3) return;
        int bw = Math.max(1, w - 4);
        int bh = Math.max(1, h - 4);
        int bx = x + (w - bw) / 2;
        int by = y + (h - bh) / 2;
        for (int i = bx; i < bx + bw; i++) {
            for (int j = by; j < by + bh; j++) {
                if (!LevelFactoryUtils.inBounds(level, i, j)) continue;
                if (level.tiles[i][j] != Tile.FLOOR) continue;
                level.surface[i][j] = Level.Surface.BLOOD;
            }
        }
    }

    // -- Chasm overlay -------------------------------------------------------

    private static void paintChasm(Level level,
                                   ThemedRoomDefinition.ChasmShape c,
                                   int x, int y, int w, int h, Random rng) {
        switch (c) {
            case NONE          -> { }
            case CROSS         -> paintCrossChasm(level, x, y, w, h);
            case CENTER_SQUARE -> paintCenterSquareChasm(level, x, y, w, h);
            case RANDOM_PATCH  -> paintChasm(level, x, y, w, h, rng);
        }
    }

    /** Plus-shaped chasm centred on the room: a 3-cell horizontal arm crossed
     *  with a 3-cell vertical arm. Only overwrites FLOOR. */
    private static void paintCrossChasm(Level level, int x, int y, int w, int h) {
        int cx = x + w / 2, cy = y + h / 2;
        // Horizontal arm.
        for (int i = -1; i <= 1; i++) {
            setIfFloor(level, cx + i, cy, Tile.CHASM);
        }
        // Vertical arm.
        for (int j = -1; j <= 1; j++) {
            setIfFloor(level, cx, cy + j, Tile.CHASM);
        }
    }

    /** A small square of chasm at the room's centre - like the existing
     *  CHASM_TEMPLE painter, but without the corner statues (decorations layer
     *  handles those if requested). */
    private static void paintCenterSquareChasm(Level level, int x, int y, int w, int h) {
        // 3x3 if the room is big enough, 2x2 otherwise.
        int side = (w >= 7 && h >= 7) ? 3 : 2;
        int sx = x + (w - side) / 2;
        int sy = y + (h - side) / 2;
        for (int i = sx; i < sx + side; i++) {
            for (int j = sy; j < sy + side; j++) {
                setIfFloor(level, i, j, Tile.CHASM);
            }
        }
    }

    private static void setIfFloor(Level level, int x, int y, Tile t) {
        if (!LevelFactoryUtils.inBounds(level, x, y)) return;
        if (level.tiles[x][y] == Tile.FLOOR) level.tiles[x][y] = t;
    }

    // -- Vegetation ----------------------------------------------------------

    // -- Special floor -----------------------------------------------------

    private static void paintSpecialFloor(Level level,
                                          ThemedRoomDefinition.SpecialFloor s,
                                          int x, int y, int w, int h) {
        switch (s) {
            case NONE             -> { }
            case CENTER_4X4       -> paintSpecialFloorCenter4x4(level, x, y, w, h);
            case CENTER_3X3       -> paintSpecialFloorCenter3x3(level, x, y, w, h);
            case INSET_RECTANGLE  -> paintSpecialFloorInsetRect(level, x, y, w, h);
            case CHECKERBOARD     -> paintSpecialFloorCheckerboard(level, x, y, w, h);
        }
    }

    /** Drop a 3x3 patch of FLOOR_SPECIAL centred on the room. Used by the
     *  round beacon room - the activation pad in the middle of the floor. */
    private static void paintSpecialFloorCenter3x3(Level level, int x, int y, int w, int h) {
        if (w < 3 || h < 3) return;
        int patchX0 = x + (w - 3) / 2;
        int patchY0 = y + (h - 3) / 2;
        for (int i = patchX0; i < patchX0 + 3 && i < x + w - 1; i++) {
            for (int j = patchY0; j < patchY0 + 3 && j < y + h - 1; j++) {
                setFloorSpecialIfFloor(level, i, j);
            }
        }
    }

    /** Drop a 4x4 patch of FLOOR_SPECIAL in the lower-middle of the room - used
     *  by the chapel layout so the altar/lamps at the top of the room sit on
     *  regular floor while the centre-south reads as a styled "pew" area. */
    private static void paintSpecialFloorCenter4x4(Level level, int x, int y, int w, int h) {
        if (w < 4 || h < 4) return;
        int patchX0 = x + (w - 4) / 2;
        int patchY0 = y + 1;     // 1 cell above the south wall
        for (int i = patchX0; i < patchX0 + 4 && i < x + w - 1; i++) {
            for (int j = patchY0; j < patchY0 + 4 && j < y + h - 1; j++) {
                setFloorSpecialIfFloor(level, i, j);
            }
        }
    }

    /** Fill every interior FLOOR cell with FLOOR_SPECIAL except for the 1-tile
     *  strip ringing the outer walls - produces the "pedestal" look. */
    private static void paintSpecialFloorInsetRect(Level level, int x, int y, int w, int h) {
        if (w < 4 || h < 4) return;
        for (int i = x + 2; i < x + w - 2; i++) {
            for (int j = y + 2; j < y + h - 2; j++) {
                setFloorSpecialIfFloor(level, i, j);
            }
        }
    }

    /** Alternate FLOOR / FLOOR_SPECIAL in a checkerboard pattern over the room's
     *  interior cells. Parity is {@code (i + j) % 2 == 0 -> FLOOR_SPECIAL}. */
    private static void paintSpecialFloorCheckerboard(Level level, int x, int y, int w, int h) {
        for (int i = x + 1; i < x + w - 1; i++) {
            for (int j = y + 1; j < y + h - 1; j++) {
                if (((i + j) & 1) != 0) continue;
                setFloorSpecialIfFloor(level, i, j);
            }
        }
    }

    private static void setFloorSpecialIfFloor(Level level, int x, int y) {
        if (!LevelFactoryUtils.inBounds(level, x, y)) return;
        if (level.tiles[x][y] == Tile.FLOOR) level.tiles[x][y] = Tile.FLOOR_SPECIAL;
    }

    private static void paintVegetation(Level level,
                                        ThemedRoomDefinition.Vegetation v,
                                        int x, int y, int w, int h, Random rng) {
        switch (v) {
            case NONE                -> { }
            case GRASS_FILL          -> fillGrass(level, x, y, w, h);
            case MUSHROOM_PATCH      -> growMushroomPatch(level, x, y, w, h, rng);
            case MUSHROOMS_DRY_FILL  -> fillMushroomsDry(level, x, y, w, h);
        }
    }

    /** Fill mushrooms on every dry interior FLOOR cell (cells whose surface array
     *  is null - so e.g. blood-pool tiles are skipped). Used by the shroom-farm
     *  layout where a central blood pool sits inside a mushroom carpet. */
    private static void fillMushroomsDry(Level level, int x, int y, int w, int h) {
        for (int i = x; i < x + w; i++) {
            for (int j = y; j < y + h; j++) {
                if (!LevelFactoryUtils.inBounds(level, i, j)) continue;
                if (level.tiles[i][j] != Tile.FLOOR) continue;
                if (level.surface != null && level.surface[i][j] != null) continue;
                level.vegetation[i][j] = Level.Vegetation.MUSHROOMS;
            }
        }
    }

    private static void fillGrass(Level level, int x, int y, int w, int h) {
        for (int i = x; i < x + w; i++) {
            for (int j = y; j < y + h; j++) {
                if (!LevelFactoryUtils.inBounds(level, i, j)) continue;
                if (level.tiles[i][j] != Tile.FLOOR) continue;
                if (level.surface != null && level.surface[i][j] != null) continue;
                level.vegetation[i][j] = Level.Vegetation.GRASS;
            }
        }
    }

    /** Drop a single 3-6 tile mushroom patch somewhere inside the room. The
     *  painter doesn't need the elaborate spread-chance flood-fill the level-
     *  wide vegetation pass uses - a small contiguous blob reads fine for a
     *  themed room. */
    private static void growMushroomPatch(Level level, int x, int y, int w, int h, Random rng) {
        int cx = x + 1 + rng.nextInt(Math.max(1, w - 2));
        int cy = y + 1 + rng.nextInt(Math.max(1, h - 2));
        int radius = 1 + rng.nextInt(2);   // 1 or 2
        for (int i = cx - radius; i <= cx + radius; i++) {
            for (int j = cy - radius; j <= cy + radius; j++) {
                if (!LevelFactoryUtils.inBounds(level, i, j)) continue;
                if (level.tiles[i][j] != Tile.FLOOR) continue;
                if (level.surface != null && level.surface[i][j] != null) continue;
                level.vegetation[i][j] = Level.Vegetation.MUSHROOMS;
            }
        }
    }

    // -- Statue / lamp decorations ------------------------------------------

    private static void paintDecoration(Level level,
                                        ThemedRoomDefinition.Decoration d,
                                        int x, int y, int w, int h,
                                        ThemedRoomDefinition.Placement placement,
                                        Random rng) {
        switch (d) {
            case STATUES_SMALL_CORNERS  -> placeStatuesAtCorners(level, x, y, w, h, rng, true);
            case STATUES_LARGE_CORNERS  -> placeStatuesAtCorners(level, x, y, w, h, rng, false);
            case STATUES_LARGE_CARDINAL -> placeStatuesAtCardinals(level, x, y, w, h, rng);
            case STATUE_AVENUE_SMALL    -> paintStatueAvenue(level, x, y, w, h, rng, true);
            case STATUE_AVENUE_LARGE    -> paintStatueAvenue(level, x, y, w, h, rng, false);
            case STATUE_CENTER_LARGE    -> placeStatue(level,
                                                  x + w / 2, y + h / 2,
                                                  randomLargeStatue(rng));
            case LAMPS_CORNERS          -> placeLampsAtCorners(level, x, y, w, h);
            case LAMPS_CARDINAL         -> placeLampsAtCardinals(level, x, y, w, h);
            case LAMP_CENTER            -> placeLamp(level,
                                                  x + w / 2, y + h / 2);
            case SMALL_STATUES_SCATTERED -> paintSmallStatuesScattered(level, x, y, w, h, rng);
            case CHAPEL_SHRINE          -> paintChapelShrine(level, x, y, w, h);
            case BEACON                 -> paintBeacon(level, x, y, w, h, placement, rng);
            case CHAPEL_BEACON          -> paintChapelBeacon(level, x, y, w, h, placement, rng);
            case THRONE_BEACON          -> paintThroneBeacon(level, x, y, w, h, placement, rng);
        }
    }

    /** Returns the anchor row for the given placement. TOP = 1 cell south of
     *  the north wall (leaving 1 floor row clear); other placements fall back
     *  to vertical centre. */
    private static int anchorY(int y, int h, ThemedRoomDefinition.Placement p) {
        return p == ThemedRoomDefinition.Placement.TOP ? y + h - 2 : y + h / 2;
    }

    /** Single beacon at the room's horizontal centre, on the placement-anchor
     *  row. */
    private static void paintBeacon(Level level, int x, int y, int w, int h,
                                    ThemedRoomDefinition.Placement placement,
                                    Random rng) {
        int cx = x + w / 2;
        int ay = anchorY(y, h, placement);
        placeBeacon(level, cx, ay, rng);
    }

    /** Chapel-style assembly: a centred altar (3-wide), a beacon 1 cell west
     *  of the altar's leftmost cell, and two large statues flanking the whole
     *  arrangement (dropped silently if the room is too narrow). All elements
     *  sit on the placement-anchor row. */
    private static void paintChapelBeacon(Level level, int x, int y, int w, int h,
                                          ThemedRoomDefinition.Placement placement,
                                          Random rng) {
        if (w < 4) return;
        int cx = x + w / 2;
        int ay = anchorY(y, h, placement);
        // Altar anchor at (cx, ay); the renderer extends one cell east + west.
        if (LevelFactoryUtils.inBounds(level, cx, ay)
                && level.tiles[cx][ay] == Tile.FLOOR) {
            level.tiles[cx][ay] = Tile.ALTAR;
        }
        // Beacon: 1 cell west of the altar's leftmost cell (cx-1) -> (cx-2, ay).
        placeBeacon(level, cx - 2, ay, rng);
        // Flanking statues at (cx-3, ay) and (cx+2, ay) - need w >= 6.
        if (w >= 6) {
            placeStatue(level, cx - 3, ay,
                    randomLargeStatue(rng));
            placeStatue(level, cx + 2, ay,
                    randomLargeStatue(rng));
        }
    }

    /** Throne + beacon side-by-side at the placement-anchor row, centred
     *  horizontally. Throne anchor at the centre cell, beacon immediately
     *  west of it. */
    private static void paintThroneBeacon(Level level, int x, int y, int w, int h,
                                          ThemedRoomDefinition.Placement placement,
                                          Random rng) {
        if (w < 3) return;
        int cx = x + w / 2;
        int ay = anchorY(y, h, placement);
        Tile throne = rng.nextBoolean() ? Tile.THRONE_L : Tile.THRONE_R;
        if (LevelFactoryUtils.inBounds(level, cx, ay)
                && level.tiles[cx][ay] == Tile.FLOOR) {
            level.tiles[cx][ay] = throne;
        }
        placeBeacon(level, cx - 1, ay, rng);
    }

    /** Drop 2-6 small statues at random interior positions, leaving a 1-cell
     *  margin from the outer wall. Count scales with room area so a 4x4 closet
     *  gets 2 and a 10x6 hall gets ~4. Mirrors the pre-existing SMALL_STATUE_ROOM. */
    private static void paintSmallStatuesScattered(Level level, int x, int y,
                                                   int w, int h, Random rng) {
        if (w < 4 || h < 4) return;
        int interior = Math.max(1, (w - 2) * (h - 2));
        int count = Math.max(2, Math.min(6, 1 + interior / 8));
        for (int i = 0; i < count; i++) {
            int sx = x + 1 + rng.nextInt(w - 2);
            int sy = y + 1 + rng.nextInt(h - 2);
            placeStatue(level, sx, sy,
                    randomSmallStatue(rng));
        }
    }

    /** Chapel furnishing: altar 1 cell south of the top wall, centred horizontally;
     *  two lamps one cell south of the altar, flanking its centre. The altar tile
     *  is the single anchor - the renderer paints the 3-wide sprite extending one
     *  cell west and one cell east from there. {@code y+h-1} is the top wall in
     *  this engine's y-up world, so "1 cell south of top wall" lives at {@code y+h-2}. */
    private static void paintChapelShrine(Level level, int x, int y, int w, int h) {
        if (w < 5 || h < 4) return;
        int cx = x + w / 2;
        int altarY = y + h - 2;       // 1 south of the top wall
        if (LevelFactoryUtils.inBounds(level, cx, altarY)
                && level.tiles[cx][altarY] == Tile.FLOOR) {
            level.tiles[cx][altarY] = Tile.ALTAR;
        }
        int lampY = altarY - 1;       // 1 south of the altar
        placeLamp(level, cx - 1, lampY);
        placeLamp(level, cx + 1, lampY);
    }

    private static void placeStatuesAtCorners(Level level, int x, int y, int w, int h,
                                              Random rng, boolean small) {
        int x0 = x + 1, x1 = x + w - 2;
        int y0 = y + 1, y1 = y + h - 2;
        for (int[] xy : new int[][]{{x0, y0}, {x1, y0}, {x0, y1}, {x1, y1}}) {
            Tile s = small ? randomSmallStatue(rng)
                           : randomLargeStatue(rng);
            placeStatue(level, xy[0], xy[1], s);
        }
    }

    /** Four large statues two cells out from the centre on the cardinals.
     *  Mirrors the existing LIGHT_TEMPLE painter's shape, minus the centre lamp. */
    private static void placeStatuesAtCardinals(Level level, int x, int y, int w, int h,
                                                Random rng) {
        int cx = x + w / 2, cy = y + h / 2;
        int dx = Math.max(2, w / 3);
        int dy = Math.max(2, h / 3);
        for (int[] xy : new int[][]{{cx - dx, cy}, {cx + dx, cy},
                                    {cx, cy - dy}, {cx, cy + dy}}) {
            placeStatue(level, xy[0], xy[1],
                    randomLargeStatue(rng));
        }
    }

    /** Two parallel rows of statues running along the long axis, one cell in
     *  from each long wall. Used by the imp-temple rooms. */
    private static void paintStatueAvenue(Level level, int x, int y, int w, int h,
                                          Random rng, boolean small) {
        boolean horizontal = w >= h;
        if (horizontal) {
            int rowA = y + 1;
            int rowB = y + h - 2;
            for (int i = x + 2; i <= x + w - 3; i += 2) {
                Tile a = small ? randomSmallStatue(rng)
                               : randomLargeStatue(rng);
                Tile b = small ? randomSmallStatue(rng)
                               : randomLargeStatue(rng);
                placeStatue(level, i, rowA, a);
                placeStatue(level, i, rowB, b);
            }
        } else {
            int colA = x + 1;
            int colB = x + w - 2;
            for (int j = y + 2; j <= y + h - 3; j += 2) {
                Tile a = small ? randomSmallStatue(rng)
                               : randomLargeStatue(rng);
                Tile b = small ? randomSmallStatue(rng)
                               : randomLargeStatue(rng);
                placeStatue(level, colA, j, a);
                placeStatue(level, colB, j, b);
            }
        }
    }

    private static void placeLampsAtCorners(Level level, int x, int y, int w, int h) {
        int x0 = x + 1, x1 = x + w - 2;
        int y0 = y + 1, y1 = y + h - 2;
        placeLamp(level, x0, y0);
        placeLamp(level, x1, y0);
        placeLamp(level, x0, y1);
        placeLamp(level, x1, y1);
    }

    private static void placeLampsAtCardinals(Level level, int x, int y, int w, int h) {
        int cx = x + w / 2, cy = y + h / 2;
        int dx = Math.max(2, w / 3);
        int dy = Math.max(2, h / 3);
        placeLamp(level, cx - dx, cy);
        placeLamp(level, cx + dx, cy);
        placeLamp(level, cx, cy - dy);
        placeLamp(level, cx, cy + dy);
    }

    // ====================================================================
    // Low-level shape + statue/lamp/beacon primitives.
    //
    // Every variant of decoration is expressed as data in
    // assets/data/themedrooms.csv; the dispatch layers above call into these
    // primitives, which provide the shape stamps (round-corner walls, walkway
    // plank corridors, random chasm patch, walled subroom) and the
    // statue / lamp / beacon drop helpers (FLOOR-only, never adjacent to a door).
    // ====================================================================

    // -- Statue helpers -----------------------------------------------------

    private static Tile randomSmallStatue(Random rng) {
        return rng.nextBoolean() ? Tile.STATUE_SMALL_L : Tile.STATUE_SMALL_R;
    }

    private static Tile randomLargeStatue(Random rng) {
        return rng.nextBoolean() ? Tile.STATUE_LARGE_L : Tile.STATUE_LARGE_R;
    }

    /** Drop a statue at (x, y) only if the cell is plain FLOOR and not adjacent to a door
     *  - statues next to doorways read as obstacles blocking the passage. Guards against
     *  painting over chasm, walls, doors, lamps, or another statue we just placed. */
    private static void placeStatue(Level level, int x, int y, Tile statue) {
        if (!LevelFactoryUtils.inBounds(level, x, y)) return;
        if (!level.tiles[x][y].canHoldItem()) return;
        if (LevelFactoryUtils.adjacentToDoor(level, x, y)) return;
        level.tiles[x][y] = statue;
    }

    /** Drop a LAMP tile at (x, y) under the same rules as {@link #placeStatue} - FLOOR-only,
     *  and never adjacent to a doorway (so an arched door doesn't get a lamp post bolted
     *  onto its threshold). */
    private static void placeLamp(Level level, int x, int y) {
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
     *  ships fully populated. */
    private static void placeBeacon(Level level, int x, int y, Random rng) {
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
                                             Random rng) {
        double frac = LevelFactoryPopulate.depthFraction(level);
        // Wraith species still escalates by depth band, but the count is
        // fixed at 2 per beacon room — earlier 4/3/2 ramp put too many
        // wraith bodies on the player at once.
        String wraithType;
        if      (frac < 0.5) wraithType = "WRAITH";
        else if (frac < 0.8) wraithType = "LARGE_WRAITH";
        else                 wraithType = "AWFUL_WRAITH";
        int wraithCount = 2;
        int wraithSpawnLevel = MobProgression.depthAdjustedSpawnLevel(
                level, Registries.mob(wraithType));
        // Mirror boss uses the per-mob rule too - it reads as the encounter
        // boss because the band-bonus of ENEMY_PLAYER_* lands a level above
        // the wraith pack at the same floor.
        int mirrorSpawnLevel = -1;

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
                mirrorSpawnLevel, rng, /*withRetainers=*/ false);
        for (int i = 0; i < wraithCount && idx < spots.size(); i++) {
            Mob w = MobFactory.spawn(wraithType, spots.get(idx++));
            if (w == null) continue;
            MobProgression.setSpawnLevel(w, wraithSpawnLevel);
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
    private static void paintRound(Level level, int x, int y, int w, int h) {
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
        // A preserved door alcove can end up touching the round interior only
        // diagonally (the cells orthogonally inward got walled by the ellipse).
        // A diagonal link isn't walkable, so carve a bridging FLOOR cell to give
        // every door a full orthogonal connection into the room.
        connectDoorAlcovesOrthogonally(level, x, y, w, h);
    }

    /** True if any of {@code (i, j)}'s four cardinal neighbours is a door (any
     *  type). Used by {@link #paintRound} to keep a 1-tile floor alcove alive for
     *  doors near corner positions. Recognises ONETIME_DOOR / CRYSTAL_DOOR too,
     *  since a round room can share a wall with an already-converted unique room. */
    private static boolean hasAdjacentDoor(Level level, int i, int j) {
        int[][] dirs = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
        for (int[] d : dirs) {
            int ni = i + d[0], nj = j + d[1];
            if (!LevelFactoryUtils.inBounds(level, ni, nj)) continue;
            if (level.tiles[ni][nj].isDoor()) return true;
        }
        return false;
    }

    /** For every door bordering the round room rect, ensure its inward FLOOR
     *  alcove links orthogonally to the room body. If the alcove only touches
     *  the interior diagonally, carve the single shared orthogonal cell to FLOOR
     *  so the door is genuinely reachable. */
    private static void connectDoorAlcovesOrthogonally(Level level, int x, int y, int w, int h) {
        int[][] dirs = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
        // Doors live on the rect's outer ring, so scan one cell beyond it.
        for (int i = x - 1; i <= x + w; i++) {
            for (int j = y - 1; j <= y + h; j++) {
                if (!LevelFactoryUtils.inBounds(level, i, j)) continue;
                if (!level.tiles[i][j].isDoor()) continue;
                // The alcove is the door's inward neighbour that sits inside the
                // rect and survived as FLOOR.
                for (int[] d : dirs) {
                    int ai = i + d[0], aj = j + d[1];
                    if (ai < x || ai >= x + w || aj < y || aj >= y + h) continue;
                    if (!LevelFactoryUtils.inBounds(level, ai, aj)) continue;
                    if (level.tiles[ai][aj] != Tile.FLOOR) continue;
                    bridgeDiagonalAlcove(level, ai, aj, i, j);
                }
            }
        }
    }

    /** {@code (ai, aj)} is a door alcove (FLOOR); {@code (di, dj)} is the door it
     *  serves. If the alcove already has an orthogonal FLOOR neighbour other than
     *  the door it's connected - return. Otherwise find a diagonal FLOOR neighbour
     *  (the room body reaching it diagonally) and carve the shared orthogonal cell
     *  between them to FLOOR. */
    private static void bridgeDiagonalAlcove(Level level, int ai, int aj, int di, int dj) {
        int[][] dirs = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
        for (int[] d : dirs) {
            int ni = ai + d[0], nj = aj + d[1];
            if (ni == di && nj == dj) continue;       // back toward the door
            if (!LevelFactoryUtils.inBounds(level, ni, nj)) continue;
            if (level.tiles[ni][nj] == Tile.FLOOR) return;   // already linked
        }
        int[][] diag = {{1, 1}, {1, -1}, {-1, 1}, {-1, -1}};
        for (int[] dd : diag) {
            int ni = ai + dd[0], nj = aj + dd[1];
            if (!LevelFactoryUtils.inBounds(level, ni, nj)) continue;
            if (level.tiles[ni][nj] != Tile.FLOOR) continue;
            // Carve whichever of the two shared orthogonal cells is a wall.
            int hx = ai + dd[0], hy = aj;             // horizontal step
            int vx = ai,         vy = aj + dd[1];     // vertical step
            if (LevelFactoryUtils.inBounds(level, hx, hy)
                    && level.tiles[hx][hy] == Tile.WALL) {
                level.tiles[hx][hy] = Tile.FLOOR;
            } else if (LevelFactoryUtils.inBounds(level, vx, vy)
                    && level.tiles[vx][vy] == Tile.WALL) {
                level.tiles[vx][vy] = Tile.FLOOR;
            }
            return;
        }
    }

    // -- WALKWAY ------------------------------------------------------------

    /**
     * Walkway room: interior becomes CHASM, then a FLOOR_WOOD L-corridor is carved from the
     * inside neighbour of every door on the room's outer wall to a central hub. Doors are
     * already placed by the orchestrator before painters run, so we can address each door
     * directly. Rooms with no doors end up as a sealed chasm pit.
     */
    private static void paintWalkway(Level level, int x, int y, int w, int h) {
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
        // Any door type, not just plain DOOR: a walkway room can share a wall
        // with an already-stamped unique room whose doors were converted to
        // ONETIME_DOOR (or the all-crystal sweep), and those still need a plank
        // leading to them or the door opens onto an empty chasm pit.
        if (!level.tiles[dx][dy].isDoor()) return;
        int sx = dx + ix, sy = dy + iy;
        for (int xx = Math.min(sx, cx); xx <= Math.max(sx, cx); xx++) plankCell(level, xx, sy);
        for (int yy = Math.min(sy, cy); yy <= Math.max(sy, cy); yy++) plankCell(level, cx, yy);
    }

    private static void plankCell(Level level, int x, int y) {
        if (LevelFactoryUtils.inBounds(level, x, y)) level.tiles[x][y] = Tile.FLOOR_WOOD;
    }

    // -- CHASM --------------------------------------------------------------

    /** A chasm patch in the middle of the room that does not reach the edges. */
    private static void paintChasm(Level level, int x, int y, int w, int h, Random rng) {
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
    private static void paintSubroom(Level level, int x, int y, int w, int h, Random rng) {
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
