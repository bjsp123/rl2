package com.bjsp123.rl2.model;

/**
 * Composable bag of every stat that multiple sources can contribute to. The shape of the
 * pipeline is fixed:
 *
 * <pre>
 *     effective = mob.intrinsic
 *               + characterLevelBonus(mob)
 *               + sum ItemStats.contributeInto(equipped slot)
 *               + BuffSystem.contributeInto(active buff)
 * </pre>
 *
 * Each contributor writes into a destination block via {@link #mergeIn}; the merge is
 * stat-aware (sums for ints/MinMax, OR for booleans, max for {@link #lightRadius}).
 * Adding a new stat is "add a field here + extend mergeIn"; adding a new contributor is
 * "write a method that takes a destination StatBlock and writes its contribution into it."
 *
 * <p>Mutable on purpose - every read of a mob's effective stats reuses one cached block,
 * so AI loops doing a thousand {@code hitChance} calls per turn don't allocate. MinMax
 * fields still allocate one record per merge (records are immutable); this is acceptable
 * given young-gen GC characteristics and can be revisited if a profile points here.
 */
public final class StatBlock {

    // -- Combat numbers ------------------------------------------------------
    /** Defaults preserve the old Mob.java field defaults so a species factory that
     *  doesn't override (e.g. a generic mob baseline) still gets sensible values. */
    public int    accuracy = 10;
    public int    evasion  = 5;
    public MinMax damage      = MinMax.ZERO;
    public MinMax armor       = MinMax.ZERO;
    public MinMax apDamage    = MinMax.ZERO;
    public MinMax magicResist = MinMax.ZERO;

    // -- Vital + economy -----------------------------------------------------
    public double maxHp = 10;
    public double healRate = 0;   // HP per turn, applied at the end of the mob's turn after all actions resolve
    public int    attackCost = 100;
    public int    moveCost = 100;

    // -- Ranged attack -------------------------------------------------------
    /** Damage range of a ranged shot. {@code rangedDamage.max() > 0} is the gate that
     *  says "this mob has a ranged attack at all" - every other ranged-* field is
     *  irrelevant when it's zero. Composes by sum so a ring of accuracy could land. */
    public MinMax rangedDamage = MinMax.ZERO;
    /** Standard turns between shots - 1 = every turn, 2 = every other. Composes by sum,
     *  so a "ring of slowness" raising it works directly; a hasted-style faster-shoot
     *  contribution would be a negative addition (or this field gets a multiplicative
     *  modifier later if that's needed). */
    public int rangedRateOfFire;
    /** Game ticks consumed by a ranged attack (the ranged-action analogue of
     *  {@link #attackCost}). Composes by sum. */
    public int rangedCost;
    /** Maximum range in tiles (Chebyshev). The shooter must be within this distance and
     *  have an unobstructed line of sight to the target. Composes by sum so a "ring of
     *  range +2" plugs in directly. */
    public int rangedDistance;

    // -- Perception + lighting -----------------------------------------------
    public double visionRadius = 8;
    public double wakeRadius = 6;
    /** Light emission. Composes by max - the brightest source wins (an amulet of light
     *  doesn't "stack" on top of a wand of true sight). */
    public double lightRadius;

    // -- Body + species abilities --------------------------------------------
    /** Body size, 1..10. Drives sight blocking, oil drip, swap rules. Composes by sum
     *  (a future "potion of growth" could plausibly add to it). Most species set this
     *  intrinsically; modifiers haven't shown up yet but the slot is here. */
    public int size = 4;
    /** Tile radius (Euclidean) of the fire-ball this mob releases on death. {@code 0}
     *  disables. Composes by sum. */
    public int fireExplosionRadiusOnDeath = 0;
    /** Probability of spawning a mob on any kill of a {@link Mob.Material#FLESH} victim.
     *  Composes by sum (with implicit clamp at 1.0 by callers). */
    public double eatSpawnChance = 0;
    /** Probability of spawning a copy on stepping onto a mushroom tile. */
    public double mushroomEatSpawnChance = 0;
    /** Probability per standard turn of spawning a {@link Mob#turnSpawnType} on a free
     *  adjacent tile. Used by ant hills (20% per turn). Composes by sum (callers clamp). */
    public double turnSpawnChance = 0;

    // -- Boolean capabilities ------------------------------------------------
    public boolean flying = false;
    public boolean fireImmune = false;
    /** When the mob takes a damaging blow, ignite a small area around it. OR-merged. */
    public boolean fireSpreadOnAttack = false;
    /** When this mob lands a damaging blow, applies a {@code POISONED} buff to the
     *  target. Buff level = the mob's character level; duration = level x 3 turns.
     *  OR-merged so an item or buff that grants "poisoned weapon" composes with a
     *  species (e.g. spider) that already has it. */
    public boolean poisonsOnAttack = false;
    /** Squares to knock the target back on a successful melee hit. Composes by sum
     *  (weapon + intrinsic both contribute). */
    public int knockbackSquares = 0;

    /** "Especially frightening" - terrifiable observers always flee. OR-merged. */
    public boolean terrifying = false;
    /** Susceptible to terrifying mobs. OR-merged so a "ring of fearlessness" can clear
     *  it (well, not quite - OR-merging a true clears nothing; if anyone needs to
     *  *suppress* terrifiable, that's a separate "fearlessness" boolean). Default true,
     *  factories clear it on terrifying mobs themselves. */
    public boolean terrifiable = true;
    /** Mob will not willingly enter a lit tile. OR-merged. */
    public boolean hatesLight = false;
    /** Mob picks up items it walks over. OR-merged. The intrinsic block defaults
     *  this to true in {@link com.bjsp123.rl2.logic.MobDefinition} (so the merge
     *  starts true for normal species); the per-field default here is false so
     *  item and buff contributions stay merge-neutral unless they explicitly
     *  grant pickup. Ghosts, mice and other "won't carry loot" species set the
     *  CSV cell to FALSE, which overrides the intrinsic default. */
    public boolean canPickUp = false;
    /** Mob is unaffected by POISON-element damage and the POISONED buff. OR-merged. */
    public boolean poisonImmune = false;

    public StatBlock() {}

    /** Reset every field to its merge-identity value (0 / false / {@link MinMax#ZERO}). */
    public StatBlock zero() {
        accuracy = 0;
        evasion  = 0;
        damage      = MinMax.ZERO;
        armor       = MinMax.ZERO;
        apDamage    = MinMax.ZERO;
        magicResist = MinMax.ZERO;
        maxHp = 0;
        healRate = 0;
        attackCost = 0;
        moveCost   = 0;
        rangedDamage    = MinMax.ZERO;
        rangedRateOfFire = 0;
        rangedCost       = 0;
        rangedDistance   = 0;
        size = 0;
        fireExplosionRadiusOnDeath = 0;
        eatSpawnChance = 0;
        mushroomEatSpawnChance = 0;
        turnSpawnChance = 0;
        visionRadius = 0;
        wakeRadius   = 0;
        lightRadius  = 0;
        flying     = false;
        fireImmune = false;
        fireSpreadOnAttack = false;
        poisonsOnAttack    = false;
        knockbackSquares = 0;
        terrifying  = false;
        terrifiable = false;
        hatesLight  = false;
        canPickUp    = false;
        poisonImmune = false;
        return this;
    }

    /** Copy every field from {@code src} into this. */
    public StatBlock copyFrom(StatBlock src) {
        if (src == null) return zero();
        accuracy = src.accuracy;
        evasion  = src.evasion;
        damage      = src.damage;
        armor       = src.armor;
        apDamage    = src.apDamage;
        magicResist = src.magicResist;
        maxHp = src.maxHp;
        healRate = src.healRate;
        attackCost = src.attackCost;
        moveCost   = src.moveCost;
        rangedDamage     = src.rangedDamage;
        rangedRateOfFire = src.rangedRateOfFire;
        rangedCost       = src.rangedCost;
        rangedDistance   = src.rangedDistance;
        size = src.size;
        fireExplosionRadiusOnDeath = src.fireExplosionRadiusOnDeath;
        eatSpawnChance = src.eatSpawnChance;
        mushroomEatSpawnChance = src.mushroomEatSpawnChance;
        turnSpawnChance = src.turnSpawnChance;
        visionRadius = src.visionRadius;
        wakeRadius   = src.wakeRadius;
        lightRadius  = src.lightRadius;
        flying     = src.flying;
        fireImmune = src.fireImmune;
        fireSpreadOnAttack = src.fireSpreadOnAttack;
        poisonsOnAttack    = src.poisonsOnAttack;
        knockbackSquares = src.knockbackSquares;
        terrifying  = src.terrifying;
        terrifiable = src.terrifiable;
        hatesLight  = src.hatesLight;
        canPickUp    = src.canPickUp;
        poisonImmune = src.poisonImmune;
        return this;
    }

    /**
     * Merge {@code other} into this block in place. Composition is stat-aware:
     * <ul>
     *   <li><b>Sum</b>: all integer/double scalars - {@code accuracy}, {@code evasion},
     *       {@code maxHp}, {@code healRate}, {@code attackCost}, {@code moveCost}, the ranged
     *       stats, {@code size}, {@code knockbackSquares}, {@code visionRadius},
     *       {@code wakeRadius}, {@code fireExplosionRadiusOnDeath}, the spawn-chance doubles,
     *       and every {@link MinMax} stat (component-wise via {@link MinMax#plus}).</li>
     *   <li><b>Max</b>: {@code lightRadius}.</li>
     *   <li><b>OR</b>: every boolean capability - {@code flying}, {@code fireImmune},
     *       {@code fireSpreadOnAttack}, {@code poisonsOnAttack}, {@code terrifying},
     *       {@code terrifiable}, {@code hatesLight}, {@code canPickUp}, {@code poisonImmune}.</li>
     * </ul>
     */
    public StatBlock mergeIn(StatBlock other) {
        if (other == null) return this;
        accuracy += other.accuracy;
        evasion  += other.evasion;
        damage      = damage     .plus(other.damage);
        armor       = armor      .plus(other.armor);
        apDamage    = apDamage   .plus(other.apDamage);
        magicResist = magicResist.plus(other.magicResist);
        maxHp += other.maxHp;
        healRate += other.healRate;
        attackCost += other.attackCost;
        moveCost   += other.moveCost;
        rangedDamage      = rangedDamage.plus(other.rangedDamage);
        rangedRateOfFire += other.rangedRateOfFire;
        rangedCost       += other.rangedCost;
        rangedDistance   += other.rangedDistance;
        size += other.size;
        fireExplosionRadiusOnDeath += other.fireExplosionRadiusOnDeath;
        eatSpawnChance += other.eatSpawnChance;
        mushroomEatSpawnChance += other.mushroomEatSpawnChance;
        turnSpawnChance += other.turnSpawnChance;
        visionRadius += other.visionRadius;
        wakeRadius   += other.wakeRadius;
        if (other.lightRadius > lightRadius) lightRadius = other.lightRadius;
        if (other.flying)             flying             = true;
        if (other.fireImmune)         fireImmune         = true;
        if (other.fireSpreadOnAttack) fireSpreadOnAttack = true;
        if (other.poisonsOnAttack)    poisonsOnAttack    = true;
        knockbackSquares += other.knockbackSquares;
        if (other.terrifying)         terrifying         = true;
        if (other.terrifiable)        terrifiable        = true;
        if (other.hatesLight)         hatesLight         = true;
        if (other.canPickUp)          canPickUp          = true;
        if (other.poisonImmune)       poisonImmune       = true;
        return this;
    }
}
