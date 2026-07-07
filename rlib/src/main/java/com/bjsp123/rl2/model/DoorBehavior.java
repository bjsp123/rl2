package com.bjsp123.rl2.model;

/**
 * Data-driven attributes for a door type. Each canonical instance defines the
 * orthogonal axes a door can vary on — who-can-cross, blocks-sight when
 * closed, blocks-projectile when closed, what happens on cross, what tile
 * the door becomes when opened / re-closed / broken. Adding a new door
 * type means: declare a new {@code public static final DoorBehavior X},
 * add the closed + open Tile enum values, and add one line to
 * {@link Tile#doorBehavior()} mapping the new tiles to the new behaviour.
 * Every consumer (movement gate, sight gate, projectile gate, door-mutation
 * sites) adapts automatically.
 */
public record DoorBehavior(
        boolean   blocksSightWhenClosed,
        boolean   blocksProjectileWhenClosed,
        PassRule  passRule,
        OnCross   onCross,
        Tile      openVariant,
        Tile      closedVariant,
        Tile      brokenVariant) {

    /** Who is allowed to cross this door when closed. The pathfinder and
     *  movement gate both consult this. */
    public enum PassRule {
        /** Anyone walking in bumps the door open (wooden DOOR). */
        ANYONE,
        /** The player and anything loyal to the player (summons, tamed
         *  beasts, charmed allies) can cross; hostile mobs are blocked
         *  (CRYSTAL_DOOR, ONETIME_DOOR). */
        PLAYER_ONLY,
        /** Reserved for future locked doors with no walk-through opening. */
        NONE;

        public boolean allows(Mob mob) {
            return switch (this) {
                case ANYONE      -> true;
                // The player avatar PLUS any player-loyal mob - owned by the
                // player (pets / tamed beasts) or sharing the player's faction
                // (charmed / converted allies). Crystal doors keep enemies out,
                // not your own party. A mob owned by something that ISN'T the
                // player (e.g. a cat's kittens) is not loyal and stays blocked.
                case PLAYER_ONLY -> mob != null && (
                        mob.isPlayer
                        || "PLAYER".equals(mob.faction)
                        || (mob.owner != null && mob.owner.isPlayer));
                case NONE        -> false;
            };
        }
    }

    /** What happens when a mob crosses (or a projectile hits) a closed door. */
    public enum OnCross {
        /** Tile transitions to {@link DoorBehavior#openVariant()}. */
        OPENS,
        /** Tile transitions to {@link DoorBehavior#brokenVariant()};
         *  irreversible (no auto-close). */
        BREAKS,
        /** No state change (would be used for locked doors that only unlock
         *  via a key item). */
        NONE
    }

    // ---- Canonical instances ------------------------------------------------

    /** Wooden door. Anyone can push it open. Blocks sight + projectiles when
     *  closed. Auto-closes per the mover's doorClosing preference. */
    public static final DoorBehavior WOODEN = new DoorBehavior(
            /* blocksSightWhenClosed     */ true,
            /* blocksProjectileWhenClosed*/ true,
            /* passRule                  */ PassRule.ANYONE,
            /* onCross                   */ OnCross.OPENS,
            /* openVariant               */ Tile.DOOR_OPEN,
            /* closedVariant             */ Tile.DOOR,
            /* brokenVariant             */ null);

    /** Crystal door. Lights pass through; mobs and projectiles do not.
     *  The player and player-loyal allies (pets, tamed beasts, charmed/converted
     *  mobs) can step through; hostiles are blocked. Auto-closes per doorClosing. */
    public static final DoorBehavior CRYSTAL = new DoorBehavior(
            /* blocksSightWhenClosed     */ false,
            /* blocksProjectileWhenClosed*/ true,
            /* passRule                  */ PassRule.PLAYER_ONLY,
            /* onCross                   */ OnCross.OPENS,
            /* openVariant               */ Tile.CRYSTAL_DOOR_OPEN,
            /* closedVariant             */ Tile.CRYSTAL_DOOR,
            /* brokenVariant             */ null);

    /** One-time crystal barrier. Player-only; shatters underfoot to plain
     *  FLOOR. No open variant, no auto-close (irreversible). */
    public static final DoorBehavior ONETIME = new DoorBehavior(
            /* blocksSightWhenClosed     */ false,
            /* blocksProjectileWhenClosed*/ true,
            /* passRule                  */ PassRule.PLAYER_ONLY,
            /* onCross                   */ OnCross.BREAKS,
            /* openVariant               */ null,
            /* closedVariant             */ null,
            /* brokenVariant             */ Tile.FLOOR);
}
