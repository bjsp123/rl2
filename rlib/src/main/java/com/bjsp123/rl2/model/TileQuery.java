package com.bjsp123.rl2.model;

/** Central home for all tile predicate and spatial query helpers.
 *  Every method with (Level, x, y) handles out-of-bounds safely. */
public final class TileQuery {
    private TileQuery() {}

    /** True for WALL or OOB. */
    public static boolean isWallAt(Level level, int x, int y) {
        if (oob(level, x, y)) return true;
        return level.tiles[x][y] == Tile.WALL;
    }

    /** True for any door tile (open, closed, crystal, onetime). */
    public static boolean isDoorAt(Level level, int x, int y) {
        if (oob(level, x, y)) return false;
        return level.tiles[x][y].isDoor();
    }

    /** True for any closed door tile (blocks sight/projectiles). */
    public static boolean isClosedDoorAt(Level level, int x, int y) {
        if (oob(level, x, y)) return false;
        return level.tiles[x][y].isClosedDoor();
    }

    /** True if the tile blocks sight/light (OOB = true). */
    public static boolean blocksSightAt(Level level, int x, int y) {
        if (oob(level, x, y)) return true;
        return level.tiles[x][y].blocksSight();
    }

    /** True if the tile blocks movement for {@code mob} (OOB = true).
     *  Per-mob axes that {@link Tile#blocksMovement()} (mob-agnostic) can't
     *  decide are unified here: CHASM (impassable unless flying),
     *  closed doors (consult {@link DoorBehavior#passRule()} - wooden lets
     *  anyone through, crystal / one-time gate on PLAYER faction), and
     *  light-hating mobs (can't enter lit tiles). */
    public static boolean blocksMovementAt(Level level, int x, int y, Mob mob) {
        if (oob(level, x, y)) return true;
        // GHOSTLY: passes through all solid terrain (walls, statues, closed
        // doors) and hovers over chasms. Only the map border stops a ghost.
        if (mob != null && mob.isGhostly()) return false;
        Tile t = level.tiles[x][y];
        if (t == Tile.CHASM) return mob == null || !mob.effectiveStats().flying;
        if (t.isClosedDoor()) {
            // Wooden door: anyone may walk through; the actual bump-to-open
            // fires in MobSystem.onMobEnteredTile. Crystal / one-time: only the
            // player party - the avatar (Mob.isPlayer), its pets, and PLAYER-
            // faction allies (see DoorBehavior.PLAYER_ONLY).
            DoorBehavior db = t.doorBehavior();
            if (db == null) return true;
            return !db.passRule().allows(mob);
        }
        if (mob != null && mob.effectiveStats().hatesLight
                && level.lit != null && level.lit[x][y]) return true;
        return t.blocksMovement();
    }

    /** True if the tile stops a projectile or wand ray (OOB = true). */
    public static boolean blocksProjectileAt(Level level, int x, int y) {
        if (oob(level, x, y)) return true;
        return level.tiles[x][y].blocksProjectile();
    }

    /** True for WALL or any door — used for shadow/wall-cell rendering. */
    public static boolean isWallCellAt(Level level, int x, int y) {
        if (oob(level, x, y)) return true;
        Tile t = level.tiles[x][y];
        return t == Tile.WALL || t.isDoor();
    }

    /** Used for autotile stitching: treats unexplored CHASM as a barrier. */
    public static boolean stitchBarrierAt(Level level, int x, int y) {
        if (oob(level, x, y)) return true;
        Tile t = level.tiles[x][y];
        if (t == Tile.WALL) return true;
        if (t == Tile.CHASM && !level.explored[x][y]) return true;
        return false;
    }

    /** True if the door at (x,y) is a sideways (E/W-facing) door — it has a wall to
     *  its south (y-1), meaning the wall runs E/W and the player passes through N/S.
     *  Returns false if (x,y) is not a door. */
    public static boolean isSidewaysDoor(Level level, int x, int y) {
        if (oob(level, x, y)) return false;
        if (!level.tiles[x][y].isDoor()) return false;
        return isWallAt(level, x, y - 1);
    }

    public static boolean isFloorAt(Level level, int x, int y) {
        if (oob(level, x, y)) return false;
        return level.tiles[x][y].isFloorLike();
    }

    private static boolean oob(Level level, int x, int y) {
        return x < 0 || y < 0 || x >= level.width || y >= level.height;
    }

    public static boolean isCrystalDoor(Level level, int x, int y) {
        if (oob(level, x, y)) return false;
        return level.tiles[x][y] == Tile.CRYSTAL_DOOR || level.tiles[x][y] == Tile.ONETIME_DOOR || level.tiles[x][y] == Tile.CRYSTAL_DOOR_OPEN;
    }
}
