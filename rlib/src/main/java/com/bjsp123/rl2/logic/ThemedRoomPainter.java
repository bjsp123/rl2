package com.bjsp123.rl2.logic;

import com.bjsp123.rl2.model.Level;
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
 *       {@link LevelFactoryRooms#placeStatue} / {@link LevelFactoryRooms#placeLamp},
 *       which already require FLOOR + not-near-door.</li>
 * </ol>
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
            case ROUND     -> LevelFactoryRooms.paintRound   (level, x, y, w, h);
            case WALKWAY   -> LevelFactoryRooms.paintWalkway (level, x, y, w, h);
            case SUBROOM   -> LevelFactoryRooms.paintSubroom (level, x, y, w, h, rng);
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
            case RANDOM_PATCH  -> LevelFactoryRooms.paintChasm(level, x, y, w, h, rng);
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
            case STATUE_CENTER_LARGE    -> LevelFactoryRooms.placeStatue(level,
                                                  x + w / 2, y + h / 2,
                                                  LevelFactoryRooms.randomLargeStatue(rng));
            case LAMPS_CORNERS          -> placeLampsAtCorners(level, x, y, w, h);
            case LAMPS_CARDINAL         -> placeLampsAtCardinals(level, x, y, w, h);
            case LAMP_CENTER            -> LevelFactoryRooms.placeLamp(level,
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
        LevelFactoryRooms.placeBeacon(level, cx, ay, rng);
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
        LevelFactoryRooms.placeBeacon(level, cx - 2, ay, rng);
        // Flanking statues at (cx-3, ay) and (cx+2, ay) - need w >= 6.
        if (w >= 6) {
            LevelFactoryRooms.placeStatue(level, cx - 3, ay,
                    LevelFactoryRooms.randomLargeStatue(rng));
            LevelFactoryRooms.placeStatue(level, cx + 2, ay,
                    LevelFactoryRooms.randomLargeStatue(rng));
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
        LevelFactoryRooms.placeBeacon(level, cx - 1, ay, rng);
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
            LevelFactoryRooms.placeStatue(level, sx, sy,
                    LevelFactoryRooms.randomSmallStatue(rng));
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
        LevelFactoryRooms.placeLamp(level, cx - 1, lampY);
        LevelFactoryRooms.placeLamp(level, cx + 1, lampY);
    }

    private static void placeStatuesAtCorners(Level level, int x, int y, int w, int h,
                                              Random rng, boolean small) {
        int x0 = x + 1, x1 = x + w - 2;
        int y0 = y + 1, y1 = y + h - 2;
        for (int[] xy : new int[][]{{x0, y0}, {x1, y0}, {x0, y1}, {x1, y1}}) {
            Tile s = small ? LevelFactoryRooms.randomSmallStatue(rng)
                           : LevelFactoryRooms.randomLargeStatue(rng);
            LevelFactoryRooms.placeStatue(level, xy[0], xy[1], s);
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
            LevelFactoryRooms.placeStatue(level, xy[0], xy[1],
                    LevelFactoryRooms.randomLargeStatue(rng));
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
                Tile a = small ? LevelFactoryRooms.randomSmallStatue(rng)
                               : LevelFactoryRooms.randomLargeStatue(rng);
                Tile b = small ? LevelFactoryRooms.randomSmallStatue(rng)
                               : LevelFactoryRooms.randomLargeStatue(rng);
                LevelFactoryRooms.placeStatue(level, i, rowA, a);
                LevelFactoryRooms.placeStatue(level, i, rowB, b);
            }
        } else {
            int colA = x + 1;
            int colB = x + w - 2;
            for (int j = y + 2; j <= y + h - 3; j += 2) {
                Tile a = small ? LevelFactoryRooms.randomSmallStatue(rng)
                               : LevelFactoryRooms.randomLargeStatue(rng);
                Tile b = small ? LevelFactoryRooms.randomSmallStatue(rng)
                               : LevelFactoryRooms.randomLargeStatue(rng);
                LevelFactoryRooms.placeStatue(level, colA, j, a);
                LevelFactoryRooms.placeStatue(level, colB, j, b);
            }
        }
    }

    private static void placeLampsAtCorners(Level level, int x, int y, int w, int h) {
        int x0 = x + 1, x1 = x + w - 2;
        int y0 = y + 1, y1 = y + h - 2;
        LevelFactoryRooms.placeLamp(level, x0, y0);
        LevelFactoryRooms.placeLamp(level, x1, y0);
        LevelFactoryRooms.placeLamp(level, x0, y1);
        LevelFactoryRooms.placeLamp(level, x1, y1);
    }

    private static void placeLampsAtCardinals(Level level, int x, int y, int w, int h) {
        int cx = x + w / 2, cy = y + h / 2;
        int dx = Math.max(2, w / 3);
        int dy = Math.max(2, h / 3);
        LevelFactoryRooms.placeLamp(level, cx - dx, cy);
        LevelFactoryRooms.placeLamp(level, cx + dx, cy);
        LevelFactoryRooms.placeLamp(level, cx, cy - dy);
        LevelFactoryRooms.placeLamp(level, cx, cy + dy);
    }
}
