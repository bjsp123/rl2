package com.bjsp123.rl2.logic;

import com.bjsp123.rl2.model.Level;
import com.bjsp123.rl2.model.Level.Surface;
import com.bjsp123.rl2.model.Tile;
import com.bjsp123.rl2.model.Level.Vegetation;

import java.util.Random;

/**
 * Per-room interior painters. Each builder receives a rectangle that is already carved as
 * solid {@link Tile#FLOOR} and is responsible for tweaking the interior (chasms, walkways,
 * walls, lamps, vegetation, surfaces). Outer walls are filled later by
 * {@link LevelFactoryUtils#wallInFloors}, and doors are punched separately by the
 * orchestrator after corridors are routed.
 *
 * <p>Builders are conservative: if the room is too small for the variant's geometry, they
 * silently degrade to a plain rectangle rather than producing degenerate output.
 */
public final class LevelFactoryRooms {

    private LevelFactoryRooms() {}

    /** What flavour of room to paint. {@link #REGULAR} leaves the room alone — small
     *  statues only spawn in dedicated statue rooms now. */
    public enum RoomKind {
        REGULAR, ROUND, WALKWAY, GRASS_FARM, SHROOM_FARM, CHASM, SUBROOM,
        /** Square of chasm in the middle with large statues at the corners of the floor
         *  ring around it; statues sit one floor cell away from both the chasm and the
         *  outer wall. Needs at least 9×9 to fit the buffers. */
        CHASM_TEMPLE,
        /** Lamp at the centre with four large statues two cells out on the cardinals. */
        LIGHT_TEMPLE,
        /** Four small statues, one in each interior corner. */
        GALLERY,
        /** Colonnade: two parallel rows of small statues running along the long axis with a
         *  one-cell gap and a single large statue at the far end. */
        AVENUE,
        /** Room dedicated to a few randomly-placed small statues — the only place the
         *  small-statue tile spawns outside of the corner-set rooms (GALLERY, AVENUE). */
        SMALL_STATUE_ROOM
    }

    /** Single dispatch: paint the {@code kind}'s interior over the (already-carved-FLOOR) rect. */
    public static void paint(Level level, RoomKind kind, int x, int y, int w, int h, Random rng) {
        switch (kind) {
            case REGULAR           -> { /* plain floor, no decoration */ }
            case ROUND             -> paintRound(level, x, y, w, h);
            case WALKWAY           -> paintWalkway(level, x, y, w, h);
            case GRASS_FARM        -> paintGrassFarm(level, x, y, w, h);
            case SHROOM_FARM       -> paintShroomFarm(level, x, y, w, h);
            case CHASM             -> paintChasm(level, x, y, w, h, rng);
            case SUBROOM           -> paintSubroom(level, x, y, w, h, rng);
            case CHASM_TEMPLE      -> paintChasmTemple(level, x, y, w, h, rng);
            case LIGHT_TEMPLE      -> paintLightTemple(level, x, y, w, h, rng);
            case GALLERY           -> paintGallery(level, x, y, w, h, rng);
            case AVENUE            -> paintAvenue(level, x, y, w, h, rng);
            case SMALL_STATUE_ROOM -> paintSmallStatueRoom(level, x, y, w, h, rng);
        }
    }

    // ── Statue helpers ─────────────────────────────────────────────────────

    private static Tile randomSmallStatue(Random rng) {
        return rng.nextBoolean() ? Tile.STATUE_SMALL_L : Tile.STATUE_SMALL_R;
    }

    private static Tile randomLargeStatue(Random rng) {
        return rng.nextBoolean() ? Tile.STATUE_LARGE_L : Tile.STATUE_LARGE_R;
    }

    /** Drop a statue at (x, y) only if the cell is plain FLOOR and not adjacent to a door
     *  — statues next to doorways read as obstacles blocking the passage. Guards against
     *  painting over chasm, walls, doors, lamps, or another statue we just placed. */
    private static void placeStatue(Level level, int x, int y, Tile statue) {
        if (!LevelFactoryUtils.inBounds(level, x, y)) return;
        if (level.tiles[x][y] != Tile.FLOOR) return;
        if (LevelFactoryUtils.adjacentToDoor(level, x, y)) return;
        level.tiles[x][y] = statue;
    }

    /** Drop a LAMP tile at (x, y) under the same rules as {@link #placeStatue} — FLOOR-only,
     *  and never adjacent to a doorway (so an arched door doesn't get a lamp post bolted
     *  onto its threshold). */
    private static void placeLamp(Level level, int x, int y) {
        if (!LevelFactoryUtils.inBounds(level, x, y)) return;
        if (level.tiles[x][y] != Tile.FLOOR) return;
        if (LevelFactoryUtils.adjacentToDoor(level, x, y)) return;
        level.tiles[x][y] = Tile.LAMP;
    }

    /** REGULAR-room scatter: 50% chance of dropping 1–3 small statues on random interior
     *  floor tiles, leaving a 1-cell margin from the outer wall so the silhouettes aren't
     *  pressed up against the room edge. Each statue rolls its facing independently. */
    /** Paint a {@link RoomKind#SMALL_STATUE_ROOM} interior — a few randomly-placed small
     *  statues at jittered positions. The number scales with room area so a 4×4 closet
     *  gets one statue while a 10×6 hall gets four. The default REGULAR painter no
     *  longer scatters statues so this room is the only natural source of the small
     *  statue tile (alongside the corner-set GALLERY / AVENUE rooms). */
    private static void paintSmallStatueRoom(Level level, int x, int y, int w, int h, Random rng) {
        if (w < 4 || h < 4) return;
        int interior = Math.max(1, (w - 2) * (h - 2));
        int count = Math.max(2, Math.min(6, 1 + interior / 8));
        for (int i = 0; i < count; i++) {
            int sx = x + 1 + rng.nextInt(w - 2);
            int sy = y + 1 + rng.nextInt(h - 2);
            placeStatue(level, sx, sy, randomSmallStatue(rng));
        }
    }

    // ── ROUND ──────────────────────────────────────────────────────────────

    /** Round the corners of the room: any FLOOR cell outside the inscribed ellipse becomes
     *  WALL. Walls and doors are already in place by the time this runs, so painting WALL
     *  directly (rather than CHASM + relying on a later wall pass) keeps the geometry crisp.
     *
     *  <p>One subtlety: doors live on the rectangle's outer perimeter. The cell directly
     *  inside a door (one step into the rect) is on a corner of the rectangle for a
     *  door near a corner, and that corner cell sits OUTSIDE the inscribed ellipse —
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
                if (level.tiles[i][j] != Tile.FLOOR) continue;
                double dx = (i - cx) / rx;
                double dy = (j - cy) / ry;
                if (dx * dx + dy * dy <= 1.0) continue;
                // Outside the ellipse — would normally wall in. Skip if a door sits on
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

    // ── WALKWAY ────────────────────────────────────────────────────────────

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
     *  {@code (dx + ix, dy + iy)} to {@code (cx, cy)}. Idempotent — overlapping carves are
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

    // ── GRASS FARM ─────────────────────────────────────────────────────────

    /** Grass everywhere on the interior, plus a single LAMP on the centre tile. */
    private static void paintGrassFarm(Level level, int x, int y, int w, int h) {
        int cx = x + w / 2;
        int cy = y + h / 2;
        for (int i = x; i < x + w; i++) {
            for (int j = y; j < y + h; j++) {
                if (!LevelFactoryUtils.inBounds(level, i, j)) continue;
                if (level.tiles[i][j] != Tile.FLOOR) continue;
                if (i == cx && j == cy) continue;
                level.vegetation[i][j] = Vegetation.GRASS;
            }
        }
        placeLamp(level, cx, cy);
    }

    // ── SHROOM FARM ────────────────────────────────────────────────────────

    /**
     * Shroom farm: a small blood pool sits in the middle, mushrooms scattered around it on
     * the dry tiles. The blood patch is roughly 40% of the interior area, capped to leave
     * a 1-tile dry ring around the doors.
     */
    private static void paintShroomFarm(Level level, int x, int y, int w, int h) {
        if (w < 3 || h < 3) return;
        int bw = Math.max(1, w - 4);
        int bh = Math.max(1, h - 4);
        int bx = x + (w - bw) / 2;
        int by = y + (h - bh) / 2;
        for (int i = bx; i < bx + bw; i++) {
            for (int j = by; j < by + bh; j++) {
                if (!LevelFactoryUtils.inBounds(level, i, j)) continue;
                if (level.tiles[i][j] != Tile.FLOOR) continue;
                level.surface[i][j] = Surface.BLOOD;
            }
        }
        for (int i = x; i < x + w; i++) {
            for (int j = y; j < y + h; j++) {
                if (!LevelFactoryUtils.inBounds(level, i, j)) continue;
                if (level.tiles[i][j] != Tile.FLOOR) continue;
                if (level.surface[i][j] != null) continue;
                level.vegetation[i][j] = Vegetation.MUSHROOMS;
            }
        }
    }

    // ── CHASM ──────────────────────────────────────────────────────────────

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

    // ── SUBROOM ────────────────────────────────────────────────────────────

    /**
     * A smaller walled rectangle inside the main room with a single door on its perimeter.
     * The interior of the subroom stays FLOOR; only the perimeter (minus one door) becomes
     * WALL. Needs at least a 5×5 outer rect to fit a 3×3 inner with margin.
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

    // ── CHASM TEMPLE ───────────────────────────────────────────────────────

    /**
     * Square of chasm in the middle, large statues at the four inner corners of the floor
     * ring around it. Statues sit one floor cell away from both the chasm and the outer
     * wall — buffers on both sides so the silhouettes have breathing room. Below 9×9 the
     * room is too small to fit the buffers and the painter no-ops.
     */
    private static void paintChasmTemple(Level level, int x, int y, int w, int h, Random rng) {
        if (w < 9 || h < 9) return;
        // Chasm rect — inset by 4 cells on every side: 1 wall + 1 floor + 1 statue + 1 floor.
        int cx0 = x + 4, cx1 = x + w - 5;
        int cy0 = y + 4, cy1 = y + h - 5;
        for (int i = cx0; i <= cx1; i++) {
            for (int j = cy0; j <= cy1; j++) {
                if (LevelFactoryUtils.inBounds(level, i, j) && level.tiles[i][j] == Tile.FLOOR)
                    level.tiles[i][j] = Tile.CHASM;
            }
        }
        // Four large statues at the inner corners of the surrounding floor ring.
        placeStatue(level, x + 2,         y + 2,         randomLargeStatue(rng));
        placeStatue(level, x + w - 3,     y + 2,         randomLargeStatue(rng));
        placeStatue(level, x + 2,         y + h - 3,     randomLargeStatue(rng));
        placeStatue(level, x + w - 3,     y + h - 3,     randomLargeStatue(rng));
    }

    // ── LIGHT TEMPLE ───────────────────────────────────────────────────────

    /**
     * Lamp at the room's centre with four large statues two cells out on the cardinals.
     * Needs at least 5×5 so the cardinal statues land on interior floor instead of the
     * outer wall.
     */
    private static void paintLightTemple(Level level, int x, int y, int w, int h, Random rng) {
        if (w < 5 || h < 5) return;
        int cx = x + w / 2, cy = y + h / 2;
        placeLamp(level, cx, cy);
        placeStatue(level, cx - 2, cy,     randomLargeStatue(rng));
        placeStatue(level, cx + 2, cy,     randomLargeStatue(rng));
        placeStatue(level, cx,     cy - 2, randomLargeStatue(rng));
        placeStatue(level, cx,     cy + 2, randomLargeStatue(rng));
    }

    // ── GALLERY ────────────────────────────────────────────────────────────

    /** Four small statues, one in each interior corner of the room. Statues face outward
     *  (toward the nearest wall) so the room reads as a curated exhibit rather than a
     *  random scatter. Needs at least 3×3. */
    private static void paintGallery(Level level, int x, int y, int w, int h, Random rng) {
        if (w < 3 || h < 3) return;
        // Outward-facing pairs: the two left-column statues face right (away from the W wall),
        // the two right-column statues face left.
        placeStatue(level, x + 1,         y + 1,         Tile.STATUE_SMALL_R);
        placeStatue(level, x + w - 2,     y + 1,         Tile.STATUE_SMALL_L);
        placeStatue(level, x + 1,         y + h - 2,     Tile.STATUE_SMALL_R);
        placeStatue(level, x + w - 2,     y + h - 2,     Tile.STATUE_SMALL_L);
    }

    // ── AVENUE ─────────────────────────────────────────────────────────────

    /**
     * Colonnade: two parallel rows of small statues running along the room's longer axis,
     * with a one-cell gap and a single large statue at the far end. Statues in each row
     * face inward toward the avenue centre. Orientation is chosen automatically — wider
     * rooms get a horizontal avenue, taller rooms a vertical one. Skipped if the room is
     * too cramped to fit the colonnade plus the gap and the terminal large statue.
     */
    private static void paintAvenue(Level level, int x, int y, int w, int h, Random rng) {
        if (w >= h) {
            // Horizontal avenue — large statue at the east end, colonnade running east.
            if (w < 6 || h < 5) return;
            int cy = y + h / 2;
            int colStart = x + 1;
            int colEnd   = x + w - 4;     // last col with a small-statue pair
            int bigCol   = x + w - 2;     // large statue at the far end (x+w-3 is the gap)
            for (int col = colStart; col <= colEnd; col++) {
                // Small statues alternate facing per row to avoid a stamped colonnade.
                placeStatue(level, col, cy - 1, randomSmallStatue(rng));
                placeStatue(level, col, cy + 1, randomSmallStatue(rng));
            }
            placeStatue(level, bigCol, cy, randomLargeStatue(rng));
        } else {
            // Vertical avenue — large statue at the south end (y+h-2), colonnade running
            // south. y+1 .. y+h-4 = small statues; y+h-3 = gap; y+h-2 = large statue.
            if (h < 6 || w < 5) return;
            int cx = x + w / 2;
            int rowStart = y + 1;
            int rowEnd   = y + h - 4;
            int bigRow   = y + h - 2;
            for (int row = rowStart; row <= rowEnd; row++) {
                placeStatue(level, cx - 1, row, randomSmallStatue(rng));
                placeStatue(level, cx + 1, row, randomSmallStatue(rng));
            }
            placeStatue(level, cx, bigRow, randomLargeStatue(rng));
        }
    }
}
