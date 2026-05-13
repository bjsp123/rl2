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
    BOMB_JACK;

    public String displayName() {
        return switch (this) {
            case KILLER     -> "Killer";
            case STEALTH    -> "Stealth";
            case WANDMASTER -> "Wandmaster";
            case JUMP       -> "Jump";
            case KNOCKBACK  -> "Knockback";
            case BOMB_JACK  -> "Bomb Jack";
        };
    }

    public String description() {
        return switch (this) {
            case KILLER     -> "On kill: -20% attack/move cost for 10 turns.";
            case STEALTH    -> "Enemy wake radius and vision halved against you.";
            case WANDMASTER -> "Wands gain +1 effective level.";
            case JUMP       -> "Move to any tile within 2 squares for one moveCost.";
            case KNOCKBACK  -> "+1 square of melee knockback per level.";
            case BOMB_JACK  -> "+1 bomb effective level per perk level.";
        };
    }
}
