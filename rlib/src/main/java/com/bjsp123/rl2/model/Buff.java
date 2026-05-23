package com.bjsp123.rl2.model;

/**
 * A single buff (or debuff) currently affecting a {@link Mob}. Buffs are applied by
 * items (potions, wands), proximity to other mobs (a terrifying mob applies
 * {@link BuffType#FRIGHTENED} to its terrifiable neighbours) or environmental events
 * (stepping on a fire tile applies {@link BuffType#ON_FIRE}).
 *
 * <p>Both {@link #level} and {@link #durationTicks} ramp the strength of the buff. The
 * exact mapping is per-type and lives in {@code BuffSystem}; e.g. regeneration heals
 * {@code 1 + level/2} HP per standard turn, hasted multiplies {@code moveCost} by
 * {@code 0.8^level}, anti-magic divides incoming non-physical damage by {@code 2^level},
 * etc. Duration counts down every game tick (via {@code BuffSystem.tickEveryGameTick});
 * when it reaches 0 the buff is removed. One standard turn = {@code STANDARD_TURN_TICKS}
 * (100) ticks, so storage at tick granularity gives ~1% precision on a "12 turn" buff.
 *
 * <p>If a mob already has a buff of a given type and the same type is applied again,
 * the existing entry is updated to take the <em>greater</em> of the existing and new
 * values for both level and duration ("never make a buff weaker by re-applying it").
 */
public class Buff {
    /** Each enum value is one stackable status effect a mob can carry. The set is
     *  intentionally closed - adding a new buff type means writing the per-turn /
     *  per-event handler in {@code BuffSystem}, so the enum doubles as the dispatch
     *  table. */
    public enum BuffType {
        /** Increases the effective level of every wand and potion the mob carries.
         *  Source: {@code potionOfSorcery}. */
        SORCERY,
        /** Mob is currently burning. Takes fire damage per turn, ignites flammable
         *  terrain underfoot, drops a {@code fire} VFX. Source: stepping on a fire
         *  tile or being hit by a wand of fire / fire bomb / etc. */
        ON_FIRE,
        /** Mob flees all other mobs while active. Applied when adjacent to a mob with
         *  {@link Mob#terrifying} (and the receiver is {@link Mob#terrifiable}). */
        FRIGHTENED,
        /** Mob is drawn semi-transparent, gains +20 evasion, can't be picked as a
         *  ranged-attack target. Effect ends if the mob attacks. */
        INVISIBLE,
        /** Mob flies, can pass through walls, +20 evasion. */
        GHOSTLY,
        /** Mob flies but doesn't pass through walls. */
        LEVITATING,
        /** Resists non-physical damage (magic missiles, fire). Damage is divided by
         *  {@code 2^level} before application. */
        ANTI_MAGIC,
        /** Resists physical damage (melee, thrown). Damage is divided by
         *  {@code 2^level} before application. */
        PROTECTION,
        /** Heals {@code 1 + level/2} HP per turn. */
        REGENERATION,
        /** Cannot regenerate. Loses {@code 1 + level/2} HP per turn. */
        POISONED,
        /** Move cost multiplied by {@code 0.8^level} (faster at higher levels). */
        HASTED,
        /** Slight accuracy + evasion bonus, plus immunity to {@link #FRIGHTENED}. */
        HOPE,
        /** Reveals every mob on the level to the player regardless of LOS or vision. */
        ESP,
        /** Marks every tile on the level as explored at the start of every
         *  turn while the buff is active. Applied by potion of insight;
         *  effect is idempotent (subsequent ticks re-stamp the same flag). */
        INSIGHT,
        /** Cold-slowed. Adds {@code 50 + level * 10} to both moveCost and attackCost
         *  while active - every action takes longer. Applied by freeze bombs. */
        CHILLED,
        /** Slick with oil. Takes double damage from fire. If size > 2 and not flying,
         *  has a 50% chance per step to leave an OIL surface in the cell it left.
         *  Applied by oil bombs / oil-wand impacts that splash directly onto the mob. */
        OILY,
        /** Soaked through. Takes double damage from lightning attacks. */
        WET,
        /** Encased in ice after being chilled and wet at the same time. Cannot
         *  move or act; taking damage chips two turns off the duration. */
        FROZEN,
        /** Player has run out of food. Heal regen is suppressed while active; the buff
         *  drops as soon as satiety rises above 0 again (drink a potion, eat a pear).
         *  Player-only - NPCs sit at satiety 0 harmlessly. */
        STARVING,
        /** Cooldown buffs - present means "can't fire yet"; duration counts down per
         *  standard turn until the buff drops and the action becomes available again.
         *  Replace the legacy {@code MobCooldowns} fields. They share a single
         *  recharging-glyph icon cell since players don't need to distinguish
         *  them at a glance - each is just "this action is recharging". */
        TELEPORT_COOLDOWN,
        RANGED_COOLDOWN,
        /** Recharging haste-cast ability (kobold general, etc.). Same dispatch
         *  pattern as the other cooldown buffs - present means "can't cast yet". */
        HASTE_COOLDOWN,
        /** Recharging heal-cast ability (kobold general, etc.). */
        HEAL_COOLDOWN,
        /** {@code EXPLORE_HIDE} mob is hunkered in cover; AI keeps it stationary while
         *  the buff is present. Applied when the mob reaches a tile no hostile can see
         *  it from. */
        HIDING,
        /** Killer perk's on-kill buff: reduces both {@code moveCost} and {@code attackCost}
         *  by 20%. Duration 10 standard turns. */
        KILLER,
        /** Open-wound DOT. Per standard turn the mob loses
         *  {@code (level * standardTurnsRemaining) / 2} HP - strong at first
         *  then tapers as the duration counts down. {@code standardTurnsRemaining}
         *  is {@code durationTicks / STANDARD_TURN_TICKS} (truncated). Doesn't
         *  stack with itself (apply takes the max of level / duration via the
         *  standard {@link com.bjsp123.rl2.logic.BuffSystem#apply} merge rule). */
        BLEEDING,
        /** Mob moves at 30% of normal action cost (+20 evasion). Ends instantly when
         *  the mob takes or deals any damage. */
        PHASE,
        /** Complete immunity to all incoming damage. Duration counts down per
         *  standard turn. Does not block self-inflicted healing or stat effects. */
        SHIELDED
    }

    public BuffType type;
    /** Strength of the buff. {@code 1} is baseline; higher values amplify the per-tick
     *  effect of stat-modifying buffs (regen heal amount, anti-magic divisor, etc.). */
    public int level;
    /** Game ticks remaining before the buff is auto-removed. Decremented every game
     *  tick by {@code BuffSystem.tickEveryGameTick}; on hitting 0 the buff is dropped.
     *  One standard turn = {@code TurnSystem.STANDARD_TURN_TICKS} (100) ticks. HUD
     *  displays this rounded up to the nearest turn via
     *  {@code BuffSystem.displayTurns(durationTicks)}. */
    public int durationTicks;
    /** Mob that originally applied this buff (e.g. the player who drank a sorcery
     *  potion, or the kissyblob that frightened a kobold). Used by death messages and
     *  history logs for attribution. Transient - references aren't persisted on save. */
    public transient Mob source;

    public Buff() {}

    public Buff(BuffType type, int level, int durationTicks, Mob source) {
        this.type          = type;
        this.level         = Math.max(1, level);
        this.durationTicks = Math.max(1, durationTicks);
        this.source        = source;
    }
}
