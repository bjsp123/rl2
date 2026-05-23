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
     *  buff. Stacks-per-kill = {@code ceil(perkLvl/2)}; duration refresh =
     *  {@code 8 + 2 * ceil(perkLvl/2)} turns; each active buff stack multiplies
     *  attack and move cost by 0.85 (compounding). */
    KILLER,
    /** Open. Multiplies every other mob's wake / vision radius vs this player
     *  by {@code 1 / (perkLvl + 1)}. At L10 only adjacent mobs can see them. */
    STEALTH,
    /** Mage signature. Wands gain {@code +perkLvl} effective level. */
    WANDMASTER,
    /** Open. Adds {@code +perkLvl} effective level to any
     *  {@link Item.UseBehavior#JUMP} item the holder carries (FROG and future
     *  jump-tools). Repurposed from the original "always-on perk-driven jump"
     *  design which had no trigger affordance. */
    JUMP,
    /** Warrior signature. Melee knockback contributes
     *  {@code min(5, perkLvl)} tiles. Levels 6-10 instead add
     *  {@code perkLvl - 5} bonus damage when the knocked target is blocked
     *  from completing its flight (slammed into a wall / chasm / mob). */
    KNOCKBACK,
    /** Rogue signature. Bombs gain {@code +perkLvl} effective level. */
    BOMB_JACK,
    /** Open. Each level adds {@link com.bjsp123.rl2.logic.GameBalance#HURLER_RANGE_PER_LEVEL}
     *  tiles of throw range and multiplies the throw action cost by 0.85
     *  (compounding). At L10: +20 tiles of range, cost mult ≈ 0.20. */
    HURLER,
    /** Mage signature. Each food or potion the holder consumes restores
     *  {@code perkLvl} charges to every wand they carry (bag + equipped),
     *  clamped to each wand's max charge. */
    MANA_FOUNT,
    /** Rogue signature. Multi-axis bomb protection:
     *  <ul>
     *    <li>Bomb damage taken multiplied by {@code 0.5^perkLvl} (asymptotic).</li>
     *    <li>Bomb-applied buffs and bomb knockback fully ignored at any L >= 1.</li>
     *    <li>Each thrown bomb has a chance to be preserved in inventory:
     *        {@code min(saveCap, 0.30 + (perkLvl - 1) * 0.12)}, where
     *        {@code saveCap = 0.75} at L<=5 and grows by +0.05 per level
     *        beyond that (capping at 1.00 at L10).</li>
     *  </ul>
     *  Also gates the surface-step buffs (WET, OILY) the holder would get
     *  from stepping on her own water / oil bombs. */
    BOMB_DODGER;

    public String displayName() {
        return com.bjsp123.rl2.logic.TextCatalog.get("perk." + key() + ".name");
    }

    public String description() {
        return com.bjsp123.rl2.logic.TextCatalog.get("perk." + key() + ".description");
    }

    private String key() {
        return switch (this) {
            case KILLER      -> "killer";
            case STEALTH     -> "stealth";
            case WANDMASTER  -> "wandmaster";
            case JUMP        -> "jump";
            case KNOCKBACK   -> "knockback";
            case BOMB_JACK   -> "bombJack";
            case HURLER      -> "hurler";
            case MANA_FOUNT  -> "manaFount";
            case BOMB_DODGER -> "bombDodger";
        };
    }
}
