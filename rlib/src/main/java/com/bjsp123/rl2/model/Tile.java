package com.bjsp123.rl2.model;

public enum Tile {
    FLOOR, FLOOR_WOOD, WALL, DOOR, DOOR_OPEN, CHASM, LAMP, STAIRS_UP, STAIRS_DOWN,
    /** Small decorative statue, drawn over a floor base. Blocks movement but not sight or
     *  light — you can see and shoot past it. {@code _L}/{@code _R} encode the carved
     *  facing of the statue. */
    STATUE_SMALL_L, STATUE_SMALL_R,
    /** Large decorative statue, drawn over a floor base. Blocks movement, sight and light —
     *  reads as a wall-equivalent obstacle. {@code _L}/{@code _R} encode facing. */
    STATUE_LARGE_L, STATUE_LARGE_R;

    /** True only if no mob can ever cross. CHASM is per-mob, handled by MobSystem. */
    public boolean blocksMovement() {
        return this == WALL || this == LAMP
            || this == STATUE_SMALL_L || this == STATUE_SMALL_R
            || this == STATUE_LARGE_L || this == STATUE_LARGE_R;
    }

    public boolean blocksSight() {
        return this == WALL || this == DOOR
            || this == STATUE_LARGE_L || this == STATUE_LARGE_R;
    }

    /** Walkable, "floor-like" — for movement, door placement, surface stitching, etc. */
    public boolean isFloorLike() {
        return this == FLOOR || this == FLOOR_WOOD || this == LAMP
            || this == STAIRS_UP || this == STAIRS_DOWN;
    }
}
