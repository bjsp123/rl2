package com.bjsp123.rl2.ui;

import com.bjsp123.rl2.logic.BuffSystem;
import com.bjsp123.rl2.logic.TextCatalog;
import com.bjsp123.rl2.model.Buff;
import com.bjsp123.rl2.model.Item;
import com.bjsp123.rl2.model.Item.ItemEffect;
import com.bjsp123.rl2.model.Item.ThrowResult;
import com.bjsp123.rl2.model.Item.UseBehavior;
import com.bjsp123.rl2.model.MinMax;

/**
 * Shared "show everything about an item" formatter - the item-side analogue of
 * {@link MobLore}. Used by both the encyclopedia (item detail panel) and the
 * inventory's item-detail popup so the two surfaces stay aligned.
 *
 * <p>Sections are separated by blank lines and only emitted when they have
 * something to show - a mundane sword skips the use-action and buff sections,
 * a potion skips the equipment / armor section, etc. Each per-level scaling
 * delta is printed inline with its base stat ("Damage: 4-7 plus 1-2 per
 * level") so the reader sees the growth without flipping between sections.
 */
public final class ItemLore {

    private ItemLore() {}

    /** Flavor paragraph(s) for {@code it} - {@link Item#description} followed
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

    /** No-holder variant - shows the item's intrinsic enchantment level
     *  without perk / gear bonuses. Used by encyclopedia-style surfaces
     *  that aren't tied to a specific player. */
    public static String describeDetails(Item it) {
        return describeDetails(it, null);
    }

    /** Mechanical details of {@code it} - category / material, combat numbers
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

        // -- Category / material ---------------------------------------------
        //commented out for now as information is too obvious
        StringBuilder hdr = new StringBuilder();
        /* 
        if (it.inventoryCategory != null) {
            if (it.inventoryCategory.isEquipment()) {
                flag(hdr, TextCatalog.format("item.header.equippable",
                        TextCatalog.vars("category", it.inventoryCategory.name().toLowerCase())));
            }
        }*/
        /* 
        if (it.material != null) {
            flag(hdr, TextCatalog.format("item.header.material",
                    TextCatalog.vars("material", it.material.name().toLowerCase())));
        }*/ 

        // Enchantment level - display the EFFECTIVE level (intrinsic +
        // perk / gear bonuses), only shown above the design baseline of
        // 1. When the holder bumps the effective level above the
        // intrinsic, both numbers are reported so the player can see
        // where the bonus comes from.
        int effLvl = com.bjsp123.rl2.logic.ItemStats.effectiveLevel(it, holder);
        if (effLvl > 0 || it.level > 0) {
            if (effLvl != it.level) {
                flag(hdr, TextCatalog.format("item.header.effectiveLevel",
                        TextCatalog.vars("effective", effLvl, "actual", it.level)));
            } else {
                flag(hdr, TextCatalog.format("item.header.level",
                        TextCatalog.vars("level", effLvl)));
            }
        }
        /* 
        if (it.brand != null && it.brand.name != null && !it.brand.name.isEmpty()) {
            flag(hdr, TextCatalog.format("item.header.brand",
                    TextCatalog.vars("brand", it.brand.name)));
        }
        */
        if (hdr.length() > 0) sb.append(hdr);

        // -- Combat ----------------------------------------------------------
        StringBuilder combat = new StringBuilder();
        if (it.damage.max() > 0) {
            line(combat, TextCatalog.get("lore.items.damage"), range(it.damage),
                    !it.damagePerLevel.isZero(),
                    perLevelRange(it.damagePerLevel));
        }
        if (it.apDamage.max() > 0) {
            line(combat, TextCatalog.get("lore.items.apDamage"), range(it.apDamage),
                    !it.apDamagePerLevel.isZero(),
                    perLevelRange(it.apDamagePerLevel));
        }
        if (it.armor.max() > 0) {
            line(combat, TextCatalog.get("lore.items.armor"), range(it.armor),
                    !it.armorPerLevel.isZero(),
                    perLevelRange(it.armorPerLevel));
        }
        if (it.magicResist.max() > 0) {
            line(combat, TextCatalog.get("lore.items.magicResist"), range(it.magicResist),
                    !it.magicResistPerLevel.isZero(),
                    perLevelRange(it.magicResistPerLevel));
        }
        if (it.accuracy != 0) {
            line(combat, TextCatalog.get("lore.items.accuracy"),
                    (it.accuracy > 0 ? "+" : "") + it.accuracy,
                    false, "");
        }
        if (it.evasion != 0) {
            line(combat, TextCatalog.get("lore.items.evasion"),
                    (it.evasion > 0 ? "+" : "") + it.evasion,
                    false, "");
        }
        if (it.attackSpeed != Item.ATTACK_SPEED_DEFAULT) {
            line(combat, TextCatalog.get("lore.items.attackSpeed"),
                    speedLabel(it.attackSpeed),
                    false, "");
        }
        if (it.moveSpeed != Item.MOVE_SPEED_DEFAULT) {
            line(combat, TextCatalog.get("lore.items.moveSpeed"),
                    speedLabel(it.moveSpeed),
                    false, "");
        }
        if (it.knockbackSquares > 0) {
            line(combat, TextCatalog.get("lore.items.knockback"), it.knockbackSquares + " sq on hit", false, "");
        }
        if (combat.length() > 0) sb.append('\n').append(combat);

        // -- Light / food ----------------------------------------------------
        StringBuilder bod = new StringBuilder();
        if (it.lightRadius > 0) {
            line(bod, TextCatalog.get("lore.items.light"), trim(it.lightRadius) + " tiles", false, "");
        }
        if (it.foodValue > 0) {
            line(bod, TextCatalog.get("lore.items.food"), ""+it.foodValue/1000, false, "");
        }
        if (bod.length() > 0) sb.append('\n').append(bod);

        // -- Use action ------------------------------------------------------
        if (it.useBehavior != null && it.useBehavior != UseBehavior.NONE) {
            StringBuilder use = new StringBuilder();
            String verb = it.useVerb != null && !it.useVerb.isEmpty()
                    ? it.useVerb : useBehaviorVerb(it.useBehavior);
            switch (it.useBehavior) {
                case EAT -> flag(use, TextCatalog.get("item.use.EAT"));
                case DRINK -> {
                    if (!it.appliesBuff.isEmpty()) {
                        flag(use, TextCatalog.format("item.use.DRINK.buff",
                                TextCatalog.vars("buffs", buffList(it.appliesBuff),
                                        "duration", buffDurationSuffix(it))));
                    }
                }
                case GRANT_PERK -> flag(use, TextCatalog.get("item.use.GRANT_PERK"));
                case WAND -> {
                    if (it.summonsWhenUsed != null) {
                        flag(use, TextCatalog.format("item.use.WAND.summon",
                                TextCatalog.vars("verb", verb,
                                        "mob", it.summonsWhenUsed.toLowerCase())));
                    } 

                    if (it.wandEffect != null) {
                        flag(use, TextCatalog.format("item.use.WAND.effect",
                                TextCatalog.vars("verb", verb,
                                        "effect", wandEffectVerb(it.wandEffect, it))));
                        if (it.tilesAffected > 0) {
                            line(use, TextCatalog.get("lore.items.area"), it.tilesAffected + " tiles",
                                    it.tilesAffectedPerLevel > 0,
                                    perLevelTiles(it.tilesAffectedPerLevel));
                        }
                    } else {
                        flag(use, TextCatalog.format("item.use.WAND.generic",
                                TextCatalog.vars("verb", verb)));
                    }
                }
                case GRAPPLE -> {
                    flag(use, TextCatalog.format("item.use.GRAPPLE",
                            TextCatalog.vars("verb", verb)));
                    if (it.abilityPower > 0f) {
                        line(use, TextCatalog.get("lore.items.maxSize"),
                                Integer.toString((int) it.abilityPower),
                                false, "");
                    }
                }
                case JUMP -> {
                    flag(use, TextCatalog.format("item.use.JUMP",
                            TextCatalog.vars("verb", verb)));
                    if (it.abilityPower > 0f) {
                        line(use, TextCatalog.get("lore.items.range"),
                                ((int) it.abilityPower) + " squares",
                                false, "");
                    }
                }
                case POWERUP -> {
                    if (it.wandEffect != null) {
                        flag(use, TextCatalog.format("item.use.POWERUP",
                                TextCatalog.vars("effect", powerupVerb(it.wandEffect, it))));
                    }
                }
                case APPLYBUFF -> {
                    if (!it.appliesBuff.isEmpty()) {
                        flag(use, TextCatalog.format("item.use.APPLYBUFF",
                                TextCatalog.vars("verb", verb,
                                        "buffs", buffList(it.appliesBuff),
                                        "duration", buffDurationSuffix(it))));
                    }
                }
                case NONE -> { /* unreachable - outer guard */ }
            }
            if (use.length() > 0) sb.append('\n').append(use);
        }

        // -- Throw behaviour -------------------------------------------------
        if (it.throwEffect != null || it.throwResult == ThrowResult.RETURN
                || it.throwResult == ThrowResult.CONSUME) {
            StringBuilder thr = new StringBuilder();
            if (it.throwEffect != null) {
                flag(thr, TextCatalog.format("item.throw.effect",
                        TextCatalog.vars("effect", wandEffectVerb(it.throwEffect, it))));
                if (it.throwEffect == ItemEffect.APPLYBUFFS
                        && !it.appliesBuff.isEmpty()) {
                    flag(thr, TextCatalog.format("item.throw.applyBuffs",
                            TextCatalog.vars("buffs", buffList(it.appliesBuff),
                                    "duration", buffDurationSuffix(it))));
                }
                if (it.throwEffect == ItemEffect.POISONCLOUD
                        || it.throwEffect == ItemEffect.SMOKE) {
                    if (it.tilesAffected > 0) {
                        line(thr, TextCatalog.get("lore.items.cloudArea"), it.tilesAffected + " tiles",
                                it.tilesAffectedPerLevel > 0,
                                perLevelTiles(it.tilesAffectedPerLevel));
                    }
                    if (it.abilityPower > 0f) {
                        line(thr, TextCatalog.get("lore.items.cloudLifetime"), ((int) it.abilityPower) + " turns",
                                false, "");
                    }
                }
            }
            switch (it.throwResult) {
                case CONSUME -> flag(thr, TextCatalog.get("item.throw.consume"));
                case RETURN  -> flag(thr, TextCatalog.get("item.throw.return"));
                case NOTHING -> { /* default - no message */ }
            }
            if (!it.tameOnThrow.isEmpty()) {
                flag(thr, TextCatalog.format("item.throw.tame",
                        TextCatalog.vars("mobs", joinLower(it.tameOnThrow))));
            }
            if (thr.length() > 0) sb.append('\n').append(thr);
        }

        // -- Special flags ---------------------------------------------------
        StringBuilder flags = new StringBuilder();
        if (it.glows) {
            flag(flags, TextCatalog.get("item.flag.glows"));
        }
        if (flags.length() > 0) sb.append('\n').append(flags);

        return sb.toString().trim();
    }

    // -- Helpers -------------------------------------------------------------

    private static String useBehaviorVerb(UseBehavior u) {
        return switch (u) {
            case EAT, DRINK, WAND, GRANT_PERK, GRAPPLE, JUMP, POWERUP, APPLYBUFF, TELEPORT, NONE ->
                    TextCatalog.get("item.useVerb." + u.name());
        };
    }


    private static String wandEffectVerb(ItemEffect e, Item it) {
        return switch (e) {
            case DAMAGE -> TextCatalog.format("item.effect.DAMAGE",
                    TextCatalog.vars("damage", range(it.damage),
                            "perLevel", it.damagePerLevel.isZero() ? "" : " (" + perLevelRange(it.damagePerLevel).trim() + ")"));
            case LEVEL_UP, HP_UP, MANA_UP -> TextCatalog.get("item.effect.power.default");
            default -> TextCatalog.getOrDefault("item.effect." + e.name(), "perform a magical action at the target");
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
        return TextCatalog.format("item.buff.duration",
                TextCatalog.vars("turns", (int) it.abilityPower));
    }

    /** User-facing verb describing what a {@link com.bjsp123.rl2.model.Item.UseBehavior#POWERUP}
     *  pickup does. */
    private static String powerupVerb(ItemEffect e, Item it) {
        return switch (e) {
            case LEVEL_UP -> TextCatalog.get("item.effect.power.LEVEL_UP");
            case HP_UP    -> TextCatalog.format("item.effect.power.HP_UP",
                    TextCatalog.vars("percent", (int) Math.round(it.abilityPower * 100)));
            case MANA_UP  -> TextCatalog.get("item.effect.power.MANA_UP");
            default       -> TextCatalog.get("item.effect.power.default");
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

    private static String perLevelRange(MinMax m) {
        return TextCatalog.format("item.stat.perLevel",
                TextCatalog.vars("range", range(m)));
    }

    private static String perLevelTiles(int tiles) {
        return TextCatalog.format("item.stat.tilesPerLevel",
                TextCatalog.vars("tiles", tiles));
    }

    private static String trim(double v) {
        if (v == Math.floor(v)) return Long.toString((long) v);
        return Double.toString(v);
    }

    /** Render a speed multiplier as a percentage delta - {@code 0.6} reads as
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
