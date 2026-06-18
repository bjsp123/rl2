package com.bjsp123.rl2.model;

/**
 * A single buff (or debuff) currently affecting a {@link Mob}. Buffs are applied by
 * items (potions, wands), proximity to other mobs (a terrifying mob applies
 * {@link BuffType#FRIGHTENED} to its terrifiable neighbours) or environmental events
 * (stepping on a fire tile applies {@link BuffType#ON_FIRE}).
 *
 * <p>A buff carries a single magnitude/lifetime number, {@link #stacks}. Stacks count
 * down by 1 each turn (in {@code BuffSystem.tickPerTurn}); the buff is removed when they
 * reach 0. So {@code stacks} doubles as the remaining duration in turns. Magnitude-scaling
 * buffs read the <em>current</em> stack count, so their effect fades as the stacks deplete
 * - e.g. regeneration heals {@code 2 + 3*stacks/2} HP/turn, protection divides physical
 * damage by {@code 2^stacks}. Binary buffs (invisible, frozen, …) ignore the magnitude and
 * just use stacks as a timer. Each type has a maximum via {@code BuffSystem.stackCap}
 * (default 10).
 *
 * <p>If a mob already has a buff of a given type and the same type is applied again, the
 * existing entry takes the <em>greater</em> of the existing and new stacks ("never make a
 * buff weaker by re-applying it"). {@link BuffType#KILLER} is the exception - it adds the
 * incoming stacks onto the existing count (capped).
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
        /** Recharging PHASE_DODGE (wraiths, the DODGE perk). Present means "can't dodge yet". */
        PHASE_DODGE_COOLDOWN,
        /** {@code EXPLORE_HIDE} mob is hunkered in cover; AI keeps it stationary while
         *  the buff is present. Applied when the mob reaches a tile no hostile can see
         *  it from. */
        HIDING,
        /** Killer perk's on-kill buff: multiplies both {@code moveCost} and
         *  {@code attackCost} by {@code 0.9^stacks} (compounding), floored at
         *  {@code BuffSystem.KILLER_MIN_COST} so a long kill streak tops out
         *  rather than reaching near-zero cost. Duration {@code 8 + 2*stacks}
         *  standard turns, refreshed on each kill. */
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
        SHIELDED,
        /** A reanimated kill fighting for the final boss (final-boss floor).
         *  Cosmetic only - the renderer desaturates + blurs the mob's edges;
         *  no stat effect. Used to mark + visually distinguish boss revenants. */
        REVENANT
    }

    public BuffType type;
    /** Combined strength + lifetime. Counts down by 1 each turn and the buff drops at 0,
     *  so it is also the remaining duration in turns. Magnitude-scaling buffs read this
     *  current value (their effect fades as it depletes); binary buffs treat it purely as
     *  a timer. {@code 1} is the minimum live value; the maximum is {@code BuffSystem
     *  .stackCap(type)} (default 10). */
    public int stacks;
    /** Mob that originally applied this buff (e.g. the player who drank a sorcery
     *  potion, or the kissyblob that frightened a kobold). Used by death messages and
     *  history logs for attribution. Transient - references aren't persisted on save. */
    public transient Mob source;

    /** Item that originally caused this buff (the wand of fire that ignited
     *  the player, the bomb that left an oily floor, the potion that was
     *  drunk). Null for buffs whose origin item isn't meaningful (intrinsic
     *  PHASE on a wraith, etc.). Combines with {@link #source} into the
     *  {@code DamageCause} that fire / poison DOT ticks pass to
     *  {@code MobSystem.processAttack}. Transient. */
    public transient Item sourceItem;

    public Buff() {}

    public Buff(BuffType type, int stacks, Mob source) {
        this(type, stacks, source, null);
    }

    public Buff(BuffType type, int stacks, Mob source, Item sourceItem) {
        this.type       = type;
        this.stacks     = Math.max(1, stacks);
        this.source     = source;
        this.sourceItem = sourceItem;
    }
}
