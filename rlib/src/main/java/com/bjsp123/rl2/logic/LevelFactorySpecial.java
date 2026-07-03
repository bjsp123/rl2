package com.bjsp123.rl2.logic;

import com.bjsp123.rl2.model.Item;
import com.bjsp123.rl2.model.Level;
import com.bjsp123.rl2.model.Mob;
import com.bjsp123.rl2.model.Point;
import com.bjsp123.rl2.model.Tile;

import java.util.Random;

/**
 * Hand-built "special" floors that sit below the procedural dungeon. Each
 * builder produces a fully populated {@link Level} - geometry, stairs,
 * mobs, items, theme - and opts into generic, data-driven level rules
 * (seal-on-entry, exit-unlocks-on-clear, a per-turn spawner) by setting
 * plain fields on the {@link Level}. Nothing keys runtime behaviour off
 * {@link Level.LevelKind} - the kind is construction / map metadata only.
 *
 * <p>Coordinate convention is the project standard: x = column, y = row;
 * libGDX-style "y up" so {@code spawnPoint} matches the renderer's frame
 * of reference. All four floors are 48x48 (matches the procedural-floor
 * base dimensions) so the map screen lays them out at the same scale as
 * regular floors.
 */
public final class LevelFactorySpecial {

    private LevelFactorySpecial() {}

    /** Standard width/height for every special floor. Matches
     *  {@link GameBalance#LEVEL_BASE_W} / {@link GameBalance#LEVEL_BASE_H}
     *  so the map screen draws them at the same scale as regular floors. */
    private static final int W = 48;
    private static final int H = 48;
    /** Radius of the final-boss arena. */
    private static final int BOSS_ARENA_R = 8;

    // ========================================================================
    // Landing - the antechamber. Single small room with stairs up + stairs
    // down + a beacon (for teleport routing) + 10 hp pills + 10 mana pills.
    // No mobs. This is the player's safe staging ground between the regular
    // dungeon and the three unique floors.
    // ========================================================================
    public static Level buildLanding(int depth, long seed) {
        Random rng = new Random(seed ^ 0x1111111111111111L);
        Level level = blankFloor(depth, Level.VisualTheme.SHINY);
        level.kind = Level.LevelKind.LANDING;

        // Single rectangular room centred in the canvas.
        int rw = 16, rh = 12;
        int rx = (W - rw) / 2, ry = (H - rh) / 2;
        carveRoom(level, rx, ry, rw, rh);

        // Stairs up on the west wall (back to the regular dungeon's last
        // floor); stairs down on the east wall (forward to the first
        // randomized unique floor).
        Point stairsUp   = new Point(rx + 1,         ry + rh / 2);
        Point stairsDown = new Point(rx + rw - 2,    ry + rh / 2);
        level.tiles[stairsUp.tileX()  ][stairsUp.tileY()  ] = Tile.STAIRS_UP;
        level.tiles[stairsDown.tileX()][stairsDown.tileY()] = Tile.STAIRS_DOWN;
        level.stairsUp   = stairsUp;
        level.stairsDown = stairsDown;
        level.spawnPoint = stairsUp;

        // Beacon in the room's centre.
        int bx = rx + rw / 2, by = ry + rh / 2;
        level.tiles[bx][by] = Tile.BEACON_INACTIVE;

        // 10 health pills + 10 mana pills scattered on FLOOR tiles that
        // aren't a stair, beacon, or already-occupied. Two parallel rows
        // either side of the beacon read as a neat tribute - it's a
        // staging area, not a random scatter.
        scatterPills(level, rx, ry, rw, rh, rng);

        return level;
    }

    // ========================================================================
    // Exit portal - the ending floor, reached by the stairs that open at the
    // arena centre when the Great Wraith dies. A single small room, empty
    // except for one active beacon at its centre: the exit portal. Touching
    // (stepping adjacent to) it starts the victory end-sequence. lockedExit
    // marks the beacon tile so PlayScreen can detect the touch.
    // ========================================================================
    public static Level buildExitPortal(int depth, long seed) {
        Level level = blankFloor(depth, Level.VisualTheme.GOTHIC);
        level.kind = Level.LevelKind.EXIT_PORTAL;

        // Single rectangular room centred in the canvas.
        int rw = 12, rh = 10;
        int rx = (W - rw) / 2, ry = (H - rh) / 2;
        carveRoom(level, rx, ry, rw, rh);
        // Carved stone room over the void - no precipice around the exit.
        fillChasmWithWall(level);

        // Stairs up on the west wall (back up to the boss arena); the player
        // arrives here from the boss floor's down-stairs.
        Point stairsUp = new Point(rx + 1, ry + rh / 2);
        level.tiles[stairsUp.tileX()][stairsUp.tileY()] = Tile.STAIRS_UP;
        level.stairsUp   = stairsUp;
        level.spawnPoint = stairsUp;
        level.stairsDown = null;

        // The exit-portal beacon: an ACTIVE beacon at the room centre. Same
        // ornament as the boss-arena beacons, but lit with a normal glow.
        int bx = rx + rw / 2, by = ry + rh / 2;
        level.tiles[bx][by] = Tile.BEACON_ACTIVE;
        level.lockedExit = new Point(bx, by);   // touch-target for the end-sequence

        return level;
    }

    // ========================================================================
    // Mirrormatch - centre round room with three round side rooms (one per
    // class), each holding a high-level enemy player. Stairs up vanish on
    // entry; stairs down appear when all three enemy players are dead.
    // ========================================================================
    public static Level buildMirrormatch(int depth, long seed) {
        Level level = blankFloor(depth, Level.VisualTheme.CRYSTAL);
        level.kind = Level.LevelKind.MIRRORMATCH;
        // Seal behind the player on arrival, and withhold the exit until the
        // arena is cleared. lockedExit (set below) is where the down-stairs
        // get stamped once all foes are dead.
        level.sealOnEntry = true;
        level.exitUnlocksOnClear = true;
        level.suppressTeleport = true;   // can't banish the rival players away

        // Central hub room - round, 11x11. Stairs-up here (visible until
        // the player enters; sealOnEntry clears it on arrival so there's no
        // way out except finishing the match).
        int hubR = 5;
        int hubCx = W / 2, hubCy = H / 2;
        carveCircle(level, hubCx, hubCy, hubR);

        Point stairsUp = new Point(hubCx, hubCy);
        level.tiles[hubCx][hubCy] = Tile.STAIRS_UP;
        level.stairsUp   = stairsUp;
        level.spawnPoint = stairsUp;
        // The exit appears at the hub centre once the arena is cleared.
        level.lockedExit = new Point(hubCx, hubCy);

        // Three side rooms - one per class - to the north, southeast, and
        // southwest. Each 7-tile-radius-ish, connected by a 1-wide
        // corridor back to the hub.
        int sideR = 4;
        int armLen = hubR + 4;          // distance from hub centre to each side-room centre

        // Equally-spaced points around the hub at 60, 180, 300 degrees
        // (north slightly off-axis to leave the east side for stairs-down).
        double[][] dirs = {
                {  0.0, -1.0 },                                 // north
                { -Math.sqrt(3) / 2.0,  0.5 },                  // south-west
                {  Math.sqrt(3) / 2.0,  0.5 }                   // south-east
        };
        String[] enemyTypes = {
                "ENEMY_PLAYER_ROGUE",
                "ENEMY_PLAYER_WARRIOR",
                "ENEMY_PLAYER_MAGE"
        };

        for (int i = 0; i < 3; i++) {
            int sx = (int) Math.round(hubCx + dirs[i][0] * armLen);
            int sy = (int) Math.round(hubCy + dirs[i][1] * armLen);
            carveCircle(level, sx, sy, sideR);
            // Corridor connecting hub centre to side-room centre.
            carveStraightCorridor(level, hubCx, hubCy, sx, sy);
            // Enemy player at the side-room centre, levelled up.
            Mob foe = MobFactory.spawn(enemyTypes[i], new Point(sx, sy));
            if (foe != null) {
                MobProgression.setSpawnLevel(foe, GameBalance.MAX_CHARACTER_LEVEL);
                level.mobs.add(foe);
            }
        }

        // No down-stairs yet: LevelSystem.openExitIfCleared stamps them onto
        // lockedExit (the hub centre) once all three enemy players are dead.
        level.stairsDown = null;

        // Mirrormatch sits in a sealed crystal arena, not floating over
        // void - swap every remaining CHASM tile to WALL so the room
        // edges read as carved stone rather than a precipice.
        fillChasmWithWall(level);

        return level;
    }

    // ========================================================================
    // Final boss - the Great Wraith (RL-19). A central round arena, sealed on
    // entry (no stairs while the boss lives). Four cardinal soul spawners
    // reanimate the player's slain as revenants: N/S sit on the arena floor,
    // E/W on small platforms over the void reached by short plank walkways.
    // The boss is spawned + scaled (by beacons lit) on arrival in
    // MobSystem.transferMobToLevel; its death stamps the escape stairs at the
    // arena centre.
    // ========================================================================
    public static Level buildFinalBoss(int depth, long seed) {
        Random rng = new Random(seed ^ 0x5151515151515151L);
        Level level = blankFloor(depth, Level.VisualTheme.GOTHIC);
        level.kind = Level.LevelKind.FINAL_BOSS;
        level.sealOnEntry = true;   // the entry stairs vanish on arrival
        level.suppressTeleport = true;   // no teleport-orb cheese in the boss arena

        int cx = W / 2, cy = H / 2;
        int r  = BOSS_ARENA_R;
        carveCircle(level, cx, cy, r);

        // Boss + appearing-stairs tile at the arena centre.
        level.lockedExit = new Point(cx, cy);

        // Entry stairs-up at the south of the arena; sealOnEntry clears it on
        // arrival, so the floor then has no stairs until the boss dies.
        Point entry = new Point(cx, cy - (r - 1));
        level.tiles[entry.tileX()][entry.tileY()] = Tile.STAIRS_UP;
        level.stairsUp   = entry;
        level.spawnPoint = entry;
        level.stairsDown = null;

        // Four cardinal soul spawners. N/S on the arena floor; E/W on platforms
        // over the void reached by short plank walkways.
        placeSpawner(level, cx, cy + (r - 2));      // north (arena floor)
        placeSpawner(level, cx, cy - (r - 4));      // south (arena floor, north of entry)
        placeWalkwaySpawner(level, cx, cy, cx + (r + 5), cy, rng);   // east platform
        placeWalkwaySpawner(level, cx, cy, cx - (r + 5), cy, rng);   // west platform
        // The void around the E/W walkways is intentional - do NOT fill it.

        // Revenant add-spawner (roster seeded on entry by MobSystem). The real
        // per-turn cadence is hazard-driven in MobSystem.runLevelSpawner (the boss
        // pool branch reads BOSS_ADD_CADENCE_BY_HAZARD); everyNTurns is just a
        // nominal positive value so the spawner is the deterministic kind.
        Level.Spawner sp = new Level.Spawner();
        sp.everyNTurns = 1;
        sp.maxAlive    = GameBalance.BOSS_ADD_MAX_ALIVE;
        sp.placement   = Level.Spawner.Placement.SOUL_SPAWNERS;
        sp.spawnAwake  = true;
        level.spawner  = sp;

        return level;
    }

    /** Place a 2-wide soul-spawner prop (anchor at (x,y)) and register it as a
     *  spawn anchor. */
    private static void placeSpawner(Level level, int x, int y) {
        if (!inBounds(x, y)) return;
        level.tiles[x][y] = Tile.SOUL_SPAWNER_L;
        if (inBounds(x + 1, y)) level.tiles[x + 1][y] = Tile.SOUL_SPAWNER_R;
        level.spawnerTiles.add(new Point(x, y));
    }

    /** Carve a small 3x3 floor platform over the void at (px,py), bridge it to
     *  the arena with a plank walkway, and place a soul spawner on it. */
    private static void placeWalkwaySpawner(Level level, int arenaCx, int arenaCy,
                                            int px, int py, Random rng) {
        for (int dx = -1; dx <= 1; dx++) {
            for (int dy = -1; dy <= 1; dy++) {
                if (inBounds(px + dx, py + dy)) level.tiles[px + dx][py + dy] = Tile.FLOOR;
            }
        }
        int dirX = Integer.signum(px - arenaCx);
        carveLPlankPath(level, arenaCx + dirX * (BOSS_ARENA_R - 1), arenaCy,
                px - dirX, py, rng);
        placeSpawner(level, px, py);
    }

    // ========================================================================
    // Horde - 7-tile-wide corridor running east-west. Stairs up at the west
    // end (vanish on entry); stairs down at the east end. A data-driven
    // spawner drips awake insects in roughly halfway between the player and
    // the east exit, escalating their level the longer the player lingers.
    // ========================================================================
    public static Level buildHorde(int depth, long seed) {
        Level level = blankFloor(depth, Level.VisualTheme.CONCRETE);
        level.kind = Level.LevelKind.HORDE;
        level.sealOnEntry = true;

        // Data-driven horde spawner: ~1 insect every other turn on average,
        // appearing midway between the player and the exit, awake, with the
        // spawn level ramping +1 every 10 turns (reproduces the old curve).
        Level.Spawner spawner = new Level.Spawner();
        spawner.chancePerTurn = 0.5;
        // Mutable ArrayList, NOT List.of(...): the spawner is serialized into the
        // save, and libGDX Json can't reconstruct an immutable List on load
        // ("missing no-arg constructor: ImmutableCollections$ListN") - which made
        // any save with this spawner unloadable.
        spawner.speciesPool = new java.util.ArrayList<>(
                java.util.List.of("SPIDER", "BLACK_ANT"));
        spawner.placement = Level.Spawner.Placement.MIDPOINT_TO_EXIT;
        spawner.spawnAwake = true;
        spawner.levelRampPer10Turns = 1;
        // Keep the trickle survivable: the ramp never pushes a bug past level 8,
        // no matter how long the player lingers in the corridor.
        spawner.maxSpawnLevel = 8;
        level.spawner = spawner;

        // 7-tile-wide corridor running the full width. Vertical span: rows
        // ry .. ry+6.
        int ry = H / 2 - 3;
        int corridorH = 7;
        int corridorX0 = 2;
        int corridorX1 = W - 3;
        for (int x = corridorX0; x <= corridorX1; x++) {
            for (int dy = 0; dy < corridorH; dy++) {
                level.tiles[x][ry + dy] = Tile.FLOOR;
            }
        }
        wallBorder(level, corridorX0 - 1, ry - 1,
                corridorX1 - corridorX0 + 3, corridorH + 2);

        // Stairs - west end (in the middle row) and east end.
        Point stairsUp   = new Point(corridorX0,     ry + corridorH / 2);
        Point stairsDown = new Point(corridorX1,     ry + corridorH / 2);
        level.tiles[stairsUp.tileX()  ][stairsUp.tileY()  ] = Tile.STAIRS_UP;
        level.tiles[stairsDown.tileX()][stairsDown.tileY()] = Tile.STAIRS_DOWN;
        level.stairsUp   = stairsUp;
        level.stairsDown = stairsDown;
        level.spawnPoint = stairsUp;

        return level;
    }

    // ========================================================================
    // Walkway - canvas of CHASM with a twisting 1-tile-wide walkway running
    // from stairs-up (west) to stairs-down (east). A handful of orc + kobold
    // groups sit on the walkway at fixed waypoints. The walkway is
    // FLOOR_WOOD so it reads as a bridge.
    // ========================================================================
    public static Level buildWalkway(int depth, long seed) {
        Random rng = new Random(seed ^ 0x4444444444444444L);
        Level level = blankFloor(depth, Level.VisualTheme.GOTHIC);
        level.kind = Level.LevelKind.WALKWAY;
        // The whole canvas is already CHASM (Level ctor default), which
        // is exactly the void we want. Don't paint walls - the walkway is
        // exposed.

        // Waypoints across the canvas - alternating between the upper and
        // lower thirds so the path zig-zags. Shorter span + smaller delta
        // than the original sweep so each straight section is a couple of
        // tiles long, not a corridor in its own right.
        int yMid = H / 2;
        int yHi  = H / 2 + 4;
        int yLo  = H / 2 - 4;
        int[][] waypoints = {
                { 14, yMid },
                { 19, yHi  },
                { 24, yLo  },
                { 29, yHi  },
                { 34, yMid }
        };

        for (int i = 0; i < waypoints.length - 1; i++) {
            carveLPlankPath(level,
                    waypoints[i][0],     waypoints[i][1],
                    waypoints[i + 1][0], waypoints[i + 1][1],
                    rng);
        }

        Point stairsUp   = new Point(waypoints[0][0], waypoints[0][1]);
        Point stairsDown = new Point(waypoints[waypoints.length - 1][0],
                                     waypoints[waypoints.length - 1][1]);
        level.tiles[stairsUp.tileX()  ][stairsUp.tileY()  ] = Tile.STAIRS_UP;
        level.tiles[stairsDown.tileX()][stairsDown.tileY()] = Tile.STAIRS_DOWN;
        level.stairsUp   = stairsUp;
        level.stairsDown = stairsDown;
        level.spawnPoint = stairsUp;

        // Drop small orc / kobold encounter groups at the mid waypoints.
        // Each group is one leader + a couple of grunts on adjacent walkway
        // tiles. Skip waypoint 0 (player spawn) and the last one (escape
        // tile) - those stay clear.
        String[] leaders = { "ORC_HALBERDIER", "KOBOLD_GENERAL", "ORC_SNIPER" };
        String[] grunts  = { "KOBOLD_FIGHTER", "KOBOLD_SPEARMAN", "ORC_HALBERDIER" };
        for (int i = 1; i < waypoints.length - 1; i++) {
            int wx = waypoints[i][0], wy = waypoints[i][1];
            spawnEncounter(level, wx, wy, leaders[i - 1], grunts[i - 1]);
        }

        return level;
    }

    // ========================================================================
    // Helpers.
    // ========================================================================

    /** Fresh level with the canvas zeroed to CHASM (default), depth + theme
     *  stamped, and the standard derived bookkeeping (visited=false). */
    private static Level blankFloor(int depth, Level.VisualTheme theme) {
        Level level = new Level(W, H);
        level.depth = depth;
        level.theme = theme;
        LevelFactory.lastGeneratedTheme = theme;
        // Special floors are CENTER for now; the map screen lays them out
        // in the middle column below the diamond.
        level.side = Level.Side.CENTER;
        level.mapColumn = 0f;
        return level;
    }

    /** Paint a solid rectangular room: FLOOR inside, WALL ring around it.
     *  Coordinates are inclusive on the rect's outer wall edges. */
    private static void carveRoom(Level level, int x, int y, int w, int h) {
        for (int i = x; i < x + w; i++) {
            for (int j = y; j < y + h; j++) {
                if (!inBounds(i, j)) continue;
                boolean onEdge = (i == x || i == x + w - 1
                              || j == y || j == y + h - 1);
                level.tiles[i][j] = onEdge ? Tile.WALL : Tile.FLOOR;
            }
        }
    }

    /** Paint a wall ring along the perimeter of the given rectangle.
     *  Used to enclose a corridor or void section without overwriting
     *  its interior tiles. */
    private static void wallBorder(Level level, int x, int y, int w, int h) {
        for (int i = x; i < x + w; i++) {
            if (inBounds(i, y))         level.tiles[i][y]         = Tile.WALL;
            if (inBounds(i, y + h - 1)) level.tiles[i][y + h - 1] = Tile.WALL;
        }
        for (int j = y; j < y + h; j++) {
            if (inBounds(x, j))         level.tiles[x][j]         = Tile.WALL;
            if (inBounds(x + w - 1, j)) level.tiles[x + w - 1][j] = Tile.WALL;
        }
    }

    /** Carve a filled circle (radius r) of FLOOR around (cx,cy), ringed by
     *  WALL on the next tile out. Stones less inside the radius, walls just
     *  outside - matches the round-room aesthetic the procedural floors
     *  use without invoking the BSP / Poisson pipelines. */
    private static void carveCircle(Level level, int cx, int cy, int r) {
        int r2 = r * r;
        for (int dy = -r - 1; dy <= r + 1; dy++) {
            for (int dx = -r - 1; dx <= r + 1; dx++) {
                int x = cx + dx, y = cy + dy;
                if (!inBounds(x, y)) continue;
                int d2 = dx * dx + dy * dy;
                if (d2 <= r2)              level.tiles[x][y] = Tile.FLOOR;
                else if (d2 <= (r + 1) * (r + 1)
                        && level.tiles[x][y] == Tile.CHASM) level.tiles[x][y] = Tile.WALL;
            }
        }
    }

    /** Carve a straight FLOOR corridor (1-tile-wide) from (ax,ay) to
     *  (bx,by). Walks x first then y (L-shaped). Wall-ring the cardinal
     *  neighbours so the corridor reads as a tunnel through the void. */
    private static void carveStraightCorridor(Level level, int ax, int ay, int bx, int by) {
        int x = ax, y = ay;
        while (x != bx) {
            stampCorridor(level, x, y);
            x += Integer.signum(bx - x);
        }
        while (y != by) {
            stampCorridor(level, x, y);
            y += Integer.signum(by - y);
        }
        stampCorridor(level, x, y);
    }

    /** Carve a single corridor tile + wall ring its CHASM neighbours so it
     *  reads as a tunnel. Preserves anything that's already a FLOOR/WALL
     *  (room edges, junctions). */
    private static void stampCorridor(Level level, int x, int y) {
        if (!inBounds(x, y)) return;
        level.tiles[x][y] = Tile.FLOOR;
        for (int dy = -1; dy <= 1; dy++) {
            for (int dx = -1; dx <= 1; dx++) {
                int nx = x + dx, ny = y + dy;
                if (!inBounds(nx, ny)) continue;
                if (level.tiles[nx][ny] == Tile.CHASM) level.tiles[nx][ny] = Tile.WALL;
            }
        }
    }

    /** L-shaped FLOOR_WOOD plank path from (ax,ay) to (bx,by). Single-tile
     *  wide; no wall ring (the walkway level is intentionally exposed over
     *  chasm). Used by the WALKWAY floor. */
    private static void carveLPlankPath(Level level, int ax, int ay,
                                        int bx, int by, Random rng) {
        // Randomly pick whether to walk x-first or y-first so the path
        // weaves rather than always turning at the same corner.
        boolean xFirst = rng.nextBoolean();
        int x = ax, y = ay;
        if (xFirst) {
            while (x != bx) { stampPlank(level, x, y); x += Integer.signum(bx - x); }
            while (y != by) { stampPlank(level, x, y); y += Integer.signum(by - y); }
        } else {
            while (y != by) { stampPlank(level, x, y); y += Integer.signum(by - y); }
            while (x != bx) { stampPlank(level, x, y); x += Integer.signum(bx - x); }
        }
        stampPlank(level, x, y);
    }

    /** Stamp a single FLOOR_WOOD plank, leaving its neighbours as CHASM
     *  (the walkway is an exposed bridge - no walls). */
    private static void stampPlank(Level level, int x, int y) {
        if (!inBounds(x, y)) return;
        level.tiles[x][y] = Tile.FLOOR_WOOD;
    }

    /** Drop 10 HEALTHPILL + 10 CHARGEPILL on free FLOOR tiles inside the
     *  given room rect. Two parallel rows either side of the centre row -
     *  reads as a staged supply cache, not random scatter. */
    private static void scatterPills(Level level, int rx, int ry, int rw, int rh,
                                     Random rng) {
        int centerY = ry + rh / 2;
        // Top row: HEALTHPILL. Bottom row: CHARGEPILL. Skip the column with
        // the stairs (column rx+1 and rx+rw-2) and the beacon (centre).
        int topY = centerY - 2;
        int botY = centerY + 2;
        int placedHp = 0, placedMana = 0;
        for (int dx = 0; dx < rw - 2 && (placedHp < 10 || placedMana < 10); dx++) {
            int x = rx + 1 + dx;
            if (x == rx + rw / 2) continue;
            if (placedHp < 10 && canHoldPill(level, x, topY)) {
                placePill(level, "HEALTHPILL", x, topY);
                placedHp++;
            }
            if (placedMana < 10 && canHoldPill(level, x, botY)) {
                placePill(level, "CHARGEPILL", x, botY);
                placedMana++;
            }
        }
        // Fallback: if 20 slots weren't enough, sprinkle anywhere in the
        // room that's still free. Rare with the default 16x12 rect but
        // keeps the count honest if someone resizes the Landing.
        int safety = 0;
        while ((placedHp < 10 || placedMana < 10) && safety++ < 500) {
            int x = rx + 1 + rng.nextInt(rw - 2);
            int y = ry + 1 + rng.nextInt(rh - 2);
            if (!canHoldPill(level, x, y)) continue;
            if (placedHp < 10) {
                placePill(level, "HEALTHPILL", x, y);
                placedHp++;
            } else if (placedMana < 10) {
                placePill(level, "CHARGEPILL", x, y);
                placedMana++;
            }
        }
    }

    private static boolean canHoldPill(Level level, int x, int y) {
        if (!inBounds(x, y)) return false;
        Tile t = level.tiles[x][y];
        if (t != Tile.FLOOR && t != Tile.FLOOR_WOOD) return false;
        for (Item it : level.items) {
            if (it == null || it.location == null) continue;
            if (it.location.tileX() == x && it.location.tileY() == y) return false;
        }
        return true;
    }

    private static void placePill(Level level, String type, int x, int y) {
        Item item = ItemGenerator.buildItem(type, 1.0);
        if (item == null) return;
        item.location = new Point(x, y);
        level.items.add(item);
    }

    /** Spawn a 3-mob encounter (leader + 2 grunts) at the given anchor
     *  tile, dropping the grunts on the closest free Chebyshev-1 neighbours
     *  so they cluster around the leader. Silent no-op if the spawn type
     *  doesn't resolve. Used by the Walkway level. */
    private static void spawnEncounter(Level level, int ax, int ay,
                                       String leaderType, String gruntType) {
        Mob leader = MobFactory.spawn(leaderType, new Point(ax, ay));
        if (leader != null) level.mobs.add(leader);
        int placed = 0;
        for (int r = 1; r <= 2 && placed < 2; r++) {
            for (int dy = -r; dy <= r && placed < 2; dy++) {
                for (int dx = -r; dx <= r && placed < 2; dx++) {
                    if (dx == 0 && dy == 0) continue;
                    if (Math.max(Math.abs(dx), Math.abs(dy)) != r) continue;
                    int nx = ax + dx, ny = ay + dy;
                    if (!inBounds(nx, ny)) continue;
                    if (level.tiles[nx][ny] != Tile.FLOOR_WOOD
                            && level.tiles[nx][ny] != Tile.FLOOR) continue;
                    if (MobQueries.mobAt(level, new Point(nx, ny)) != null) continue;
                    Mob g = MobFactory.spawn(gruntType, new Point(nx, ny));
                    if (g != null) { level.mobs.add(g); placed++; }
                }
            }
        }
    }

    /** Replace every CHASM tile on the canvas with WALL. Used by the
     *  MIRRORMATCH floor where the surrounding void would otherwise read
     *  as a precipice (and worse, drop the player to whatever level sits
     *  below it). FLOOR / STAIRS / BEACON / existing WALL tiles are left
     *  untouched. */
    private static void fillChasmWithWall(Level level) {
        for (int x = 0; x < level.width; x++) {
            for (int y = 0; y < level.height; y++) {
                if (level.tiles[x][y] == Tile.CHASM) level.tiles[x][y] = Tile.WALL;
            }
        }
    }

    private static boolean inBounds(int x, int y) {
        return x >= 0 && y >= 0 && x < W && y < H;
    }
}
