package com.bjsp123.rl2.model;

/**
 * Player perk catalog. Perks are character-progression abilities the player chooses
 * with perk points; each perk's behaviour is hard-coded in the system that owns the
 * affected mechanic ({@code MobSystem.attack}, {@code ItemSystem.applyWandImpact},
 * {@code MobSystem.processVisionFor}, etc.) so adding a new one means writing a
 * dedicated handler - same pattern as the {@code Buff.BuffType} enum.
 *
 * <p>Each player carries a {@code Map<Perk, Integer>} of perk levels in
 * {@link Mob#perks}; level 0 / absence means the perk isn't taken. Most perks scale
 * trivially with level, but some are flag-style (present-or-absent).
 */
public enum Perk {
    /** Killing a foe applies the {@link Buff.BuffType#KILLER} buff: -20% attack and
     *  move cost for 10 standard turns. Warrior class starting perk. */
    KILLER,
    /** Halves the wake radius and vision radius of every other mob when checking
     *  against this player. Rogue class starting perk. */
    STEALTH,
    /** Wands gain +1 effective level. Mage class starting perk. */
    WANDMASTER,
    /** Player can move to any tile within Chebyshev radius 2 for the cost of one
     *  {@code moveCost}. Selectable. */
    JUMP,
    /** Each level contributes one square of melee-attack knockback. Stacks with
     *  any knockback the equipped weapon already provides. Warrior class
     *  starting perk. */
    KNOCKBACK,
    /** Each level adds +1 effective level to all bombs thrown by this player.
     *  Scales bomb damage and AoE. Rogue class starting perk. */
    BOMB_JACK,
    /** Each level adds {@link com.bjsp123.rl2.logic.GameBalance#HURLER_RANGE_PER_LEVEL} tiles of
     *  throw range and multiplies the throw action cost by 0.75. Selectable. */
    HURLER;

    public String displayName() {
        return com.bjsp123.rl2.logic.TextCatalog.get("perk." + key() + ".name");
    }

    public String description() {
        return com.bjsp123.rl2.logic.TextCatalog.get("perk." + key() + ".description");
    }

    private String key() {
        return switch (this) {
            case KILLER     -> "killer";
            case STEALTH    -> "stealth";
            case WANDMASTER -> "wandmaster";
            case JUMP       -> "jump";
            case KNOCKBACK  -> "knockback";
            case BOMB_JACK  -> "bombJack";
            case HURLER     -> "hurler";
        };
    }
}
