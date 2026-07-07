package com.bjsp123.rl2.model;

/**
 * Player perk catalog. Perks are character-progression abilities the player chooses
 * with perk points; each perk's behaviour is hard-coded in the system that owns the
 * affected mechanic ({@code MobSystem.attack}, {@code ItemSystem.applyWandImpact},
 * {@code MobSystem.processVisionFor}, etc.) so adding a new one means writing a
 * dedicated handler - same pattern as the {@code Buff.BuffType} enum.
 *
 * <p>Each player carries a {@code Map<Perk, Integer>} of perk levels in
 * {@link Mob#perks}; level 0 / absence means the perk isn't taken. All perks scale
 * with level out to L10; the perk-picker is universal (no class lockouts), but a
 * player's class-row in {@code mobs.csv} starts its signature perks at level 2 via
 * the {@code startingPerks=PERK*N} syntax.
 */
public enum Perk {
    /** Warrior signature. Killing a foe stacks the {@link Buff.BuffType#KILLER}
     *  buff. Stacks-per-kill = {@code 2 + perkLvl} (stack count doubles as the
     *  lifetime, capped at 30; speed effect saturates at 10 stacks); each stack
     *  multiplies attack and move cost by 0.9 (compounding), floored at
     *  {@code BuffSystem.KILLER_MIN_COST}. */
    KILLER("killer"),
    /** Open. Multiplies every other mob's wake / vision radius vs this player
     *  by {@code 1 / (perkLvl + 1)} - at L10 only adjacent mobs can see them.
     *  Additionally, each turn a mob that could notice the player fails to do
     *  so with chance {@code 0.05 * perkLvl}, even in plain sight. */
    STEALTH("stealth"),
    /** Mage signature. Wands gain {@code +perkLvl} effective level. */
    WANDMASTER("wandmaster"),
    /** Open. Adds {@code +perkLvl} effective level to any
     *  {@link Item.UseBehavior#JUMP} item the holder carries (FROG and future
     *  jump-tools). At any level > 0 the player can also leap directly to a
     *  free tile at Chebyshev distance 2, ignoring intervening obstacles
     *  (see MobSystem's move handling). */
    JUMP("jump"),
    /** Warrior signature. Melee knockback contributes
     *  {@code min(5, perkLvl)} tiles. Levels 6-10 instead add
     *  {@code perkLvl - 5} bonus damage when the knocked target is blocked
     *  from completing its flight (slammed into a wall / chasm / mob). */
    KNOCKBACK("knockback"),
    /** Rogue signature. Bombs gain {@code +perkLvl} effective level. */
    BOMB_JACK("bombJack"),
    /** Open. Each level adds {@link com.bjsp123.rl2.logic.GameBalance#HURLER_RANGE_PER_LEVEL}
     *  tiles of throw range and multiplies the throw action cost by 0.85
     *  (compounding). At L10: +20 tiles of range, cost mult ≈ 0.20. */
    HURLER("hurler"),
    /** Mage signature. Each food or potion the holder consumes has a 50%
     *  chance to restore {@code perkLvl / 2} charges (odd levels coinflip a
     *  +1) to every wand they carry (bag + equipped), clamped to each wand's
     *  max charge. */
    MANA_FOUNT("manaFount"),
    /** Rogue signature. Multi-axis bomb protection:
     *  <ul>
     *    <li>Bomb damage taken multiplied by {@code 0.5^perkLvl} (asymptotic).</li>
     *    <li>Bomb-applied buffs and bomb knockback fully ignored at any L >= 1.</li>
     *    <li>Catch (RL-34): an ENEMY bomb landing within 3 tiles has a
     *        {@code 0.25 + 0.05 * perkLvl} chance to be snatched into the
     *        holder's bag instead of detonating.</li>
     *  </ul>
     *  Also gates the surface-step buffs (WET, OILY) the holder would get
     *  from stepping on her own water / oil bombs. */
    BOMB_DODGER("bombDodger"),
    /** Open. Each level lets the holder see through one square of cloud
     *  (smoke) or {@link Item.ItemEffect#GRASS}-grown tree canopy. Applied
     *  inside {@link com.bjsp123.rl2.logic.LevelSystem#updateVisibility}
     *  by treating the first {@code perkLvl} cloud / tree tiles within
     *  Chebyshev range of the holder as transparent. Beyond that range,
     *  those tiles still block sight normally. */
    KEEN_SIGHT("keenSight"),
    /** Open. Grants the reactive WRAITH_DODGE: when about to take damage, the holder
     *  slides to a free adjacent square and negates the whole hit, then goes on cooldown.
     *  Cooldown length = {@code 11 - perkLvl} turns (L1 = 10, L10 = 1). */
    DODGE("dodge"),
    /** Open. Raises maximum HP by 10% per perk level (L10 = +100%). Applied in
     *  {@link com.bjsp123.rl2.logic.MobStats#writeEffectiveStats} before the
     *  proportional healRate scaling, so regeneration tracks the larger pool. */
    DURABLE_BODY("durableBody");

    public String displayName() {
        return com.bjsp123.rl2.logic.TextCatalog.get("perk." + key + ".name");
    }

    public String description() {
        return com.bjsp123.rl2.logic.TextCatalog.get("perk." + key + ".description");
    }

    /** Lowercase / camelCase string key for this perk, matching the
     *  {@code perk.<key>.name} / {@code .description} / {@code .tip}
     *  convention in {@code assets/data/strings.csv}. Baked in at
     *  construction so UI tip triggers compose the right key without a
     *  duplicated enum-to-key switch. */
    public final String key;

    Perk(String key) { this.key = key; }
}
