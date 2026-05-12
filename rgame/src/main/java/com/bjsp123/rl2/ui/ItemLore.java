package com.bjsp123.rl2.ui;

import com.bjsp123.rl2.logic.BuffSystem;
import com.bjsp123.rl2.model.Buff;
import com.bjsp123.rl2.model.Item;
import com.bjsp123.rl2.model.Item.ItemEffect;
import com.bjsp123.rl2.model.Item.ThrowResult;
import com.bjsp123.rl2.model.Item.UseBehavior;
import com.bjsp123.rl2.model.MinMax;

/**
 * Shared "show everything about an item" formatter — the item-side analogue of
 * {@link MobLore}. Used by both the encyclopedia (item detail panel) and the
 * inventory's item-detail popup so the two surfaces stay aligned.
 *
 * <p>Sections are separated by blank lines and only emitted when they have
 * something to show — a mundane sword skips the use-action and buff sections,
 * a potion skips the equipment / armor section, etc. Each per-level scaling
 * delta is printed inline with its base stat ("Damage: 4-7 plus 1-2 per
 * level") so the reader sees the growth without flipping between sections.
 */
public final class ItemLore {

    private ItemLore() {}

    /** Flavor paragraph(s) for {@code it} — {@link Item#description} followed
     *  (when present) by {@link Item#description2}, separated by a blank line.
     *  Used as the bright-text portion above the divider rule on encyclopedia
     *  and inventory detail panels. Returns {@code ""} when both fields are
     *  empty. The look popup uses {@link #describeFlavorShort} instead so its
     *  silhouette stays compact. */
    public static String describeFlavor(Item it) {
        if (it == null) return "";
        StringBuilder sb = new StringBuilder();
        if (it.description != null && !it.description.isEmpty()) {
            sb.append(it.description);
        }
        if (it.description2 != null && !it.description2.isEmpty()) {
            if (sb.length() > 0) sb.append("\n\n");
            sb.append(it.description2);
        }
        if (it.brand != null && it.brand.description != null && !it.brand.description.isEmpty()) {
            if (sb.length() > 0) sb.append("\n\n");
            sb.append(it.brand.description);
        }
        return sb.toString();
    }

    /** No-holder variant — shows the item's intrinsic enchantment level
     *  without perk / gear bonuses. Used by encyclopedia-style surfaces
     *  that aren't tied to a specific player. */
    public static String describeDetails(Item it) {
        return describeDetails(it, null);
    }

    /** Mechanical details of {@code it} — category / material, combat numbers
     *  (with per-level deltas), light / food, throw and use behaviour, AOE
     *  coverage, applied buffs (and their scaling duration), knockback,
     *  summon / tame side-effects, and the glow / silhouette flags. Each
     *  section is omitted when its values are all defaults / empty. Rendered
     *  in dim text below a horizontal rule beneath {@link #describeFlavor}.
     *
     *  <p>{@code holder}, when non-null, drives the displayed effective
     *  level (so a WANDMASTER's wand reads "+1 effective" higher than
     *  the bare item shows on the floor). Pass {@code null} for floor /
     *  encyclopedia views with no specific user. */
    public static String describeDetails(Item it, com.bjsp123.rl2.model.Mob holder) {
        if (it == null) return "";
        StringBuilder sb = new StringBuilder();

        // ── Category / material ─────────────────────────────────────────────
        StringBuilder hdr = new StringBuilder();
        if (it.inventoryCategory != null) {
            if (it.inventoryCategory.isEquipment()) {
                hdr.append("Equippable as ")
                        .append(it.inventoryCategory.name().toLowerCase())
                        .append('\n');
            }
        }
        if (it.material != null) {
            hdr.append("Made of ").append(it.material.name().toLowerCase()).append('\n');
        }
        // Enchantment level — display the EFFECTIVE level (intrinsic +
        // perk / gear bonuses), only shown above the design baseline of
        // 1. When the holder bumps the effective level above the
        // intrinsic, both numbers are reported so the player can see
        // where the bonus comes from.
        int effLvl = com.bjsp123.rl2.logic.ItemSystem.effectiveLevel(it, holder);
        if (effLvl > 1 || it.level > 1) {
            String tag = "+" + Math.max(0, effLvl - 1);
            if (effLvl > it.level && it.level >= 1) {
                tag += " (intrinsic +" + (it.level - 1) + ")";
            }
            hdr.append("Level: ").append(tag).append('\n');
        }
        if (it.brand != null && it.brand.name != null && !it.brand.name.isEmpty()) {
            hdr.append("Brand: ").append(it.brand.name).append('\n');
        }
        if (hdr.length() > 0) sb.append(hdr);

        // ── Combat ──────────────────────────────────────────────────────────
        StringBuilder combat = new StringBuilder();
        if (it.damage.max() > 0) {
            line(combat, "Damage", range(it.damage),
                    !it.damagePerLevel.isZero(),
                    " plus " + range(it.damagePerLevel) + " per level");
        }
        if (it.apDamage.max() > 0) {
            line(combat, "AP damage", range(it.apDamage),
                    !it.apDamagePerLevel.isZero(),
                    " plus " + range(it.apDamagePerLevel) + " per level");
        }
        if (it.armor.max() > 0) {
            line(combat, "Armor", range(it.armor),
                    !it.armorPerLevel.isZero(),
                    " plus " + range(it.armorPerLevel) + " per level");
        }
        if (it.magicResist.max() > 0) {
            line(combat, "Magic resist", range(it.magicResist),
                    !it.magicResistPerLevel.isZero(),
                    " plus " + range(it.magicResistPerLevel) + " per level");
        }
        if (it.accuracy != 0) {
            line(combat, "Accuracy",
                    (it.accuracy > 0 ? "+" : "") + it.accuracy,
                    false, "");
        }
        if (it.evasion != 0) {
            line(combat, "Evasion",
                    (it.evasion > 0 ? "+" : "") + it.evasion,
                    false, "");
        }
        if (it.attackSpeed != Item.ATTACK_SPEED_DEFAULT) {
            line(combat, "Attack speed",
                    speedLabel(it.attackSpeed),
                    false, "");
        }
        if (it.moveSpeed != Item.MOVE_SPEED_DEFAULT) {
            line(combat, "Move speed",
                    speedLabel(it.moveSpeed),
                    false, "");
        }
        if (it.knockbackSquares > 0) {
            line(combat, "Knockback", it.knockbackSquares + " sq on hit", false, "");
        }
        if (combat.length() > 0) sb.append('\n').append(combat);

        // ── Light / food ────────────────────────────────────────────────────
        StringBuilder bod = new StringBuilder();
        if (it.lightRadius > 0) {
            line(bod, "Shines light over", trim(it.lightRadius) + " tiles", false, "");
        }
        if (it.foodValue > 0) {
            line(bod, "Food value", ""+it.foodValue/1000, false, "");
        }
        if (bod.length() > 0) sb.append('\n').append(bod);

        // ── Use action ──────────────────────────────────────────────────────
        if (it.useBehavior != null && it.useBehavior != UseBehavior.NONE) {
            StringBuilder use = new StringBuilder();
            String verb = it.useVerb != null && !it.useVerb.isEmpty()
                    ? it.useVerb : useBehaviorVerb(it.useBehavior);
            switch (it.useBehavior) {
                case EAT -> flag(use, "Can be eaten to restore satiety.");
                case DRINK -> {
                    if (!it.appliesBuff.isEmpty()) {
                        flag(use, "Drink to gain " + buffList(it.appliesBuff)
                                + buffDurationSuffix(it) + ".");
                    }
                }
                case GRANT_PERK -> flag(use,
                        "Consume to gain XP.");
                case WAND -> {
                    if (it.summonsWhenUsed != null) {
                        flag(use, "Aim and " + verb
                                + " to summon a " + it.summonsWhenUsed.toLowerCase()
                                + " at your side.");
                    } 

                    if (it.wandEffect != null) {
                        flag(use, "Aim and " + verb + " to "
                                + wandEffectVerb(it.wandEffect, it) + ".");
                        if (it.tilesAffected > 0) {
                            line(use, "Area", it.tilesAffected + " tiles",
                                    it.tilesAffectedPerLevel > 0,
                                    " plus " + it.tilesAffectedPerLevel
                                            + " per level");
                        }
                    } else {
                        flag(use, "Aim and " + verb + " to fire it.");
                    }
                }
                case GRAPPLE -> {
                    flag(use, "Aim and " + verb
                            + " to yank the contents of the target tile to your side.");
                    if (it.abilityPower > 0f) {
                        line(use, "Max size",
                                Integer.toString((int) it.abilityPower),
                                false, "");
                    }
                }
                case JUMP -> {
                    flag(use, "Aim and " + verb
                            + " to leap to a chosen tile within range.");
                    if (it.abilityPower > 0f) {
                        line(use, "Range",
                                ((int) it.abilityPower) + " squares",
                                false, "");
                    }
                }
                case POWERUP -> {
                    if (it.wandEffect != null) {
                        flag(use, "Walk over to " + powerupVerb(it.wandEffect, it) + ".");
                    }
                }
                case NONE -> { /* unreachable — outer guard */ }
            }
            if (use.length() > 0) sb.append('\n').append(use);
        }

        // ── Throw behaviour ─────────────────────────────────────────────────
        if (it.throwEffect != null || it.throwResult == ThrowResult.RETURN
                || it.throwResult == ThrowResult.CONSUME) {
            StringBuilder thr = new StringBuilder();
            if (it.throwEffect != null) {
                flag(thr, "Throw it to " + wandEffectVerb(it.throwEffect, it) + ".");
                if (it.throwEffect == ItemEffect.APPLYBUFFS
                        && !it.appliesBuff.isEmpty()) {
                    flag(thr, "Hit mobs gain "
                            + buffList(it.appliesBuff) + buffDurationSuffix(it) + ".");
                }
                if (it.throwEffect == ItemEffect.POISONCLOUD) {
                    if (it.tilesAffected > 0) {
                        line(thr, "Cloud area", it.tilesAffected + " tiles",
                                it.tilesAffectedPerLevel > 0,
                                " plus " + it.tilesAffectedPerLevel + " per level");
                    }
                    if (it.abilityPower > 0f) {
                        line(thr, "Cloud lifetime", ((int) it.abilityPower) + " turns",
                                false, "");
                    }
                }
            }
            switch (it.throwResult) {
                case CONSUME -> flag(thr, "Shatters on impact.");
                case RETURN  -> flag(thr,
                        "Bounces back to the thrower's feet after striking.");
                case NOTHING -> { /* default — no message */ }
            }
            if (!it.tameOnThrow.isEmpty()) {
                flag(thr, "Throwing this at a "
                        + joinLower(it.tameOnThrow)
                        + " tames it.");
            }
            if (thr.length() > 0) sb.append('\n').append(thr);
        }

        // ── Special flags ───────────────────────────────────────────────────
        StringBuilder flags = new StringBuilder();
        if (it.glows) {
            flag(flags, "Glows on the floor with an attention-catching twinkle.");
        }
        if (flags.length() > 0) sb.append('\n').append(flags);

        return sb.toString().trim();
    }

    // ── Helpers ─────────────────────────────────────────────────────────────

    private static String useBehaviorVerb(UseBehavior u) {
        return switch (u) {
            case EAT        -> "eat";
            case DRINK      -> "drink";
            case WAND       -> "zap";
            case GRANT_PERK -> "use";
            case GRAPPLE    -> "grapple";
            case JUMP       -> "jump";
            case POWERUP    -> "absorb";
            case NONE       -> "use";
        };
    }


    private static String wandEffectVerb(ItemEffect e, Item it) {
        return switch (e) {
            case DAMAGE      -> "deal " + range(it.damage)
                    + (it.damagePerLevel.isZero() ? ""
                            : " (plus " + range(it.damagePerLevel)
                                    + " per level)")
                    + " damage to the target";
            case FIRE        -> "set the target area ablaze";
            case OIL         -> "spread a layer of oil across the target area";
            case BLAST       -> "create an explosion at the target";
            case FREEZE      -> "freeze the target area";
            case WATER       -> "create a pool of  water at the target";
            case GRASS       -> "grow vegetation at the target";
            case FUNGUS      -> "coax mushrooms up from the target";
            case DETONATION  -> "ignite a fireball at the target";
            case MISSILE     -> "strike a single target for direct damage";
            case BANISHMENT  -> "banish otherworldly beings";
            case LIGHTNING   -> "shock the target and nearby creatures";
            case APPLYBUFFS  -> "apply a magical effect to the target area";
            case POISONCLOUD -> "release a poison cloud at the target";
            case VOID        -> "tear a void at the target — pulls nearby creatures in and crumbles the ground";
            case POLYMORPH   -> "reshape the target area — rerolls floor tiles and transforms nearby creatures into similarly-sized kin";
            case LEVEL_UP, HP_UP, MANA_UP -> "absorb its power";
            default -> "perform a magical action at the target";
        };
    }

    private static String buffList(java.util.List<Buff.BuffType> bs) {
        if (bs == null || bs.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < bs.size(); i++) {
            if (i > 0) sb.append(i == bs.size() - 1 ? " and " : ", ");
            sb.append(BuffSystem.displayName(bs.get(i)));
        }
        return sb.toString();
    }

    private static String buffDurationSuffix(Item it) {
        if (it.abilityPower <= 0f) return "";
        return " for " + ((int) it.abilityPower)
                + " turns (scaling with item level)";
    }

    /** User-facing verb describing what a {@link com.bjsp123.rl2.model.Item.UseBehavior#POWERUP}
     *  pickup does. */
    private static String powerupVerb(ItemEffect e, Item it) {
        return switch (e) {
            case LEVEL_UP -> "gain a character level";
            case HP_UP    -> "restore HP (" + ((int) Math.round(it.abilityPower * 100))
                                + "% of maximum)";
            case MANA_UP  -> "recharge every wand in your bag";
            default       -> "absorb its power";
        };
    }

    private static String joinLower(java.util.List<String> xs) {
        if (xs == null || xs.isEmpty()) return "";
        java.util.List<String> sorted = new java.util.ArrayList<>(xs);
        java.util.Collections.sort(sorted);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < sorted.size(); i++) {
            if (i > 0) sb.append(i == sorted.size() - 1 ? " or " : ", ");
            sb.append(sorted.get(i).toLowerCase());
        }
        return sb.toString();
    }

    private static void line(StringBuilder sb, String label, String value,
                             boolean condition, String value2) {
        sb.append(pad(label, 12)).append(value);
        if (condition) sb.append(value2);
        sb.append('\n');
    }

    private static void flag(StringBuilder sb, String text) {
        sb.append(text).append('\n');
    }

    private static String pad(String s, int width) {
        StringBuilder sb = new StringBuilder(width + 2);
        sb.append(s).append(':');
        while (sb.length() < width + 1) sb.append(' ');
        return sb.toString();
    }

    private static String range(MinMax m) {
        if (m == null) return "0";
        return m.min() == m.max() ? Integer.toString(m.min())
                                  : m.min() + "-" + m.max();
    }

    private static String trim(double v) {
        if (v == Math.floor(v)) return Long.toString((long) v);
        return Double.toString(v);
    }

    /** Render a speed multiplier as a percentage delta — {@code 0.6} reads as
     *  "40% faster", {@code 1.1} as "10% slower". The qualitative direction
     *  is part of the label so the player doesn't have to remember which way
     *  the multiplier runs. {@code 1.0} is the no-change identity and is
     *  filtered out by the caller. */
    private static String speedLabel(double mul) {
        int pct = (int) Math.round(Math.abs(mul - 1.0) * 100.0);
        if (mul < 1.0) return pct + "% faster";
        return pct + "% slower";
    }
}
