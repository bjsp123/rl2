package com.bjsp123.rl2.model;

public enum Tile {
    FLOOR, FLOOR_WOOD, WALL, DOOR, DOOR_OPEN, CRYSTAL_DOOR_OPEN, CHASM, LAMP, STAIRS_UP, STAIRS_DOWN,
    /** Decorative "special floor" - walkable like FLOOR but rendered with the
     *  special-floor sprite. Adjacent regular-FLOOR cells receive an edge or
     *  corner overlay so the special-floor patch reads as a distinct surface. */
    FLOOR_SPECIAL,
    /** Small decorative statue, drawn over a floor base. Blocks movement but not sight or
     *  light - you can see and shoot past it. {@code _L}/{@code _R} encode the carved
     *  facing of the statue. */
    STATUE_SMALL_L, STATUE_SMALL_R,
    /** Large decorative statue, drawn over a floor base. Blocks movement, sight and light -
     *  reads as a wall-equivalent obstacle. {@code _L}/{@code _R} encode facing. */
    STATUE_LARGE_L, STATUE_LARGE_R,
    /** Stone altar - a 3-wide x 1-tall sprite anchored on this cell, with the
     *  sprite extending one cell east and one west from the anchor. Blocks
     *  movement (you can't walk through it) but not sight (table-height). */
    ALTAR,
    /** Carved throne - 1-wide x 2-tall sprite anchored at the floor cell with
     *  the upper half overhanging into the cell to the north (same convention
     *  as the lamp / large statue). Source art faces west; {@code _R} flips
     *  the sprite at draw time. Blocks movement, not sight. */
    THRONE_L, THRONE_R,
    /** Transparent door  blocking all movement and ranged attacks.
     *  Light and sight pass through freely. Used for stair-up rooms. */
    CRYSTAL_DOOR,
    /** Like CRYSTAL_DOOR but converts to FLOOR when the player steps through.
     *  Monsters can never pass. Shows a pulsing danger symbol overlay. */
    ONETIME_DOOR,
    /** Teleport beacon, inactive. 2-tile-tall sprite anchored at this cell (top
     *  half overhangs into the cell to the north). Blocks movement and
     *  projectiles like a large statue. Flips to {@link #BEACON_ACTIVE} when
     *  the player steps adjacent. Shares one atlas cell with BEACON_ACTIVE -
     *  the distinction is purely behavioural (light + particles). */
    BEACON_INACTIVE,
    /** Active teleport beacon. Emits light like a LAMP and spawns ambient
     *  particle effects. Teleport destinations on the world map are picked
     *  from the set of active beacons. */
    BEACON_ACTIVE;

    /** True for any door state (open, closed, crystal, one-time). */
    public boolean isDoor() {
        return this == DOOR || this == DOOR_OPEN ||this == CRYSTAL_DOOR_OPEN || this == CRYSTAL_DOOR || this == ONETIME_DOOR;
    }

    /** True for any door that is currently closed (blocks passage and projectiles). */
    public boolean isClosedDoor() {
        return this == DOOR || this == CRYSTAL_DOOR || this == ONETIME_DOOR;
    }

    /** True only if no mob can ever cross. CHASM is per-mob; ONETIME_DOOR is
     *  per-mob — both handled by TileQuery.blocksMovementAt. */
    public boolean blocksMovement() {
        return this == WALL || this == LAMP
            || this == STATUE_SMALL_L || this == STATUE_SMALL_R
            || this == STATUE_LARGE_L || this == STATUE_LARGE_R
            || this == ALTAR
            || this == THRONE_L || this == THRONE_R
            || this == BEACON_INACTIVE || this == BEACON_ACTIVE;
    }

    /** True if the tile is a beacon (either state). */
    public boolean isBeacon() {
        return this == BEACON_INACTIVE || this == BEACON_ACTIVE;
    }

    /** True if the tile stops a projectile or wand ray. */
    public boolean blocksProjectile() {
        return isClosedDoor() || blocksMovement();
    }

    public boolean blocksSight() {
        return this == WALL || this == DOOR
            || this == STATUE_LARGE_L || this == STATUE_LARGE_R;
    }

    /** Walkable, "floor-like" - for movement, door placement, surface stitching, etc. */
    public boolean isFloorLike() {
        return this == FLOOR || this == FLOOR_WOOD || this == FLOOR_SPECIAL
            || this == LAMP
            || this == STAIRS_UP || this == STAIRS_DOWN;
    }

    public boolean canHoldItem() {
        return this == FLOOR || this == FLOOR_WOOD || this == FLOOR_SPECIAL;
    }
}
