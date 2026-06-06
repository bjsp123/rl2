package com.bjsp123.rl2.ui;

import com.bjsp123.rl2.logic.BuffSystem;
import com.bjsp123.rl2.logic.TextCatalog;
import com.bjsp123.rl2.model.MinMax;
import com.bjsp123.rl2.model.Mob;
import com.bjsp123.rl2.model.StatBlock;

/**
 * Shared "show everything about a mob" formatter used by both the encyclopedia
 * (creature detail panel) and the character details screen. Reads the live
 * {@link StatBlock} so item / buff contributions are visible, plus the per-level
 * scaling deltas, ability list, and faction tags off the {@link Mob} itself.
 *
 * <p>Sections are separated by blank lines and only emitted when they have
 * something to show - a vanilla mouse won't print a "Ranged" section, a mob
 * with no abilities skips the abilities header. Lives in rgame because it's a
 * presentation concern; rlib's {@link Mob} / {@link StatBlock} stay free of UI
 * strings.
 */
public final class MobLore {

    private MobLore() {}

    /** Flavor paragraph(s) for {@code m} - the unique-mob banner (when the
     *  species is flagged unique in mobs.csv) plus the free-form
     *  {@link Mob#description} blurb. Used as the bright-text portion above
     *  the divider rule on the encyclopedia and look-popup detail panels.
     *  Returns {@code ""} when there is nothing to show. */
    public static String describeFlavor(Mob m) {
        if (m == null) return "";
        StringBuilder sb = new StringBuilder();
        if (m.mobType != null) {
            com.bjsp123.rl2.logic.MobDefinition def =
                    com.bjsp123.rl2.logic.Registries.mob(m.mobType);
            if (def != null && def.unique) {
                sb.append(TextCatalog.get("mob.unique"));
            }
        }
        if (m.description != null && !m.description.isEmpty()) {
            if (sb.length() > 0) sb.append("\n\n");
            sb.append(m.description);
        }
        return sb.toString();
    }

    /** Mechanical details of {@code m}: combat numbers, ranged stats,
     *  perception / lighting, body / movement, knockback, immunities +
     *  special behaviours, abilities, per-level scaling deltas, and faction
     *  tags. Each section is omitted when its values are all defaults / zero.
     *  Rendered in dim text below a horizontal rule beneath
     *  {@link #describeFlavor}. */
    public static String describeDetails(Mob m) {
        if (m == null) return "";
        StatBlock s = m.effectiveStats();
        StringBuilder sb = new StringBuilder();

        // -- Combat ----------------------------------------------------------
        // Stats shown are the EFFECTIVE values at this mob's character level
        // (post-AMOUNT-rule scaling). The `perLevel` template variable is
        // kept as an empty string for now so existing format strings still
        // render; if you want a "+N per level" line back, derive it from the
        // base int and the AMOUNT factor.
        flag(sb, TextCatalog.format("mob.stat.maxHp",   TextCatalog.vars("value", (int) Math.round(s.maxHp), "perLevel", "")));
        flag(sb, TextCatalog.format("mob.stat.attack",  TextCatalog.vars("value", s.accuracy,                  "perLevel", "")));
        flag(sb, TextCatalog.format("mob.stat.defense", TextCatalog.vars("value", s.evasion,                   "perLevel", "")));
        flag(sb, TextCatalog.format("mob.stat.damage",  TextCatalog.vars("range", range(s.damage),             "perLevel", "")));
        flag(sb, TextCatalog.format("mob.stat.armor",   TextCatalog.vars("range", range(s.armor),              "perLevel", "")));
        if (!s.apDamage.isZero())    flag(sb, TextCatalog.format("mob.stat.apDamage", TextCatalog.vars("range", range(s.apDamage), "perLevel", "")));
        if (!s.magicResist.isZero()) flag(sb, TextCatalog.format("mob.stat.magicResist", TextCatalog.vars("range", range(s.magicResist))));
        if (s.knockbackSquares > 0)  flag(sb, TextCatalog.format("mob.stat.knockback", TextCatalog.vars("squares", s.knockbackSquares)));
        if (s.healRate > 0)          flag(sb, TextCatalog.format("mob.stat.healRate", TextCatalog.vars("value", trim(s.healRate))));

        // -- Ranged ----------------------------------------------------------
        if (!s.rangedDamage.isZero() || s.rangedDistance > 0) {
            sb.append('\n');
            flag(sb, TextCatalog.format("mob.ranged.damage", TextCatalog.vars("range", range(s.rangedDamage))));
            if (s.rangedDistance > 0)   flag(sb, TextCatalog.format("mob.ranged.range", TextCatalog.vars("tiles", s.rangedDistance)));
            if (s.rangedRateOfFire > 0) flag(sb, TextCatalog.format("mob.ranged.rate", TextCatalog.vars("turns", s.rangedRateOfFire)));
        }

        // -- Perception + lighting -------------------------------------------
        sb.append('\n');
        flag(sb, TextCatalog.format("mob.senses", TextCatalog.vars("vision", trim(s.visionRadius), "wake", trim(s.wakeRadius))));
        if (s.lightRadius > 0) 
            flag(sb, TextCatalog.get("mob.light"));

        // -- Movement / body -------------------------------------------------
        sb.append('\n');
        if(s.moveCost > 140){
            flag(sb, TextCatalog.get("mob.speed.move.extremelySlow"));
        } else if (s.moveCost > 100){
            flag(sb, TextCatalog.get("mob.speed.move.slow"));
        } else if (s.moveCost < 80) {
            flag(sb, TextCatalog.get("mob.speed.move.extremelyFast"));
        } else if (s.moveCost < 100) {
            flag(sb, TextCatalog.get("mob.speed.move.fast"));
        }

        if(s.attackCost > 140){
            flag(sb, TextCatalog.get("mob.speed.attack.extremelySlow"));
        } else if (s.attackCost > 100){
            flag(sb, TextCatalog.get("mob.speed.attack.slow"));
        } else if (s.attackCost < 80) {
            flag(sb, TextCatalog.get("mob.speed.attack.extremelyFast"));
        } else if (s.attackCost < 100) {
            flag(sb, TextCatalog.get("mob.speed.attack.fast"));
        }

        if(s.size < 2){
            flag(sb, TextCatalog.get("mob.size.tiny"));
        } else if (s.size < 4){
            flag(sb, TextCatalog.get("mob.size.small"));
        } else if (s.size > 7) {
            flag(sb, TextCatalog.get("mob.size.huge"));
        } else if (s.size > 5) {
            flag(sb, TextCatalog.get("mob.size.veryBig"));
        } else if (s.size > 4) {
            flag(sb, TextCatalog.get("mob.size.large"));
        }

        // -- Special behaviours / immunities (each is a one-liner) -----------
        StringBuilder flags = new StringBuilder();
        if (s.flying)             flag(flags, TextCatalog.get("mob.flag.flying"));
        if (s.fireImmune)         flag(flags, TextCatalog.get("mob.flag.fireImmune"));
        if (s.poisonImmune)       flag(flags, TextCatalog.get("mob.flag.poisonImmune"));
        if (!s.canPickUp)         flag(flags, TextCatalog.get("mob.flag.noPickup"));
        if (s.fireSpreadOnAttack) flag(flags, TextCatalog.get("mob.flag.fireSpreadOnAttack"));
        if (s.poisonsOnAttack)    flag(flags, TextCatalog.get("mob.flag.poisonsOnAttack"));
        if (s.terrifying)         flag(flags, TextCatalog.get("mob.flag.terrifying"));
        if (!s.terrifiable)       flag(flags, TextCatalog.get("mob.flag.notTerrifiable"));
        if (m.banishable)         flag(flags, TextCatalog.get("mob.flag.banishable"));
        if (s.fireExplosionRadiusOnDeath > 0)
            flag(flags, TextCatalog.get("mob.flag.fireExplosionOnDeath"));
        // Teleport now lives on the abilities list (kind = TELEPORT) and
        // is rendered by {@link #describeAbility}, so no special-case line
        // is needed here.
        if (s.eatSpawnChance > 0)
            flag(flags, TextCatalog.get("mob.flag.eatSpawn"));
        if (s.mushroomEatSpawnChance > 0)
            flag(flags, TextCatalog.get("mob.flag.mushroomEatSpawn"));
        if (s.turnSpawnChance > 0 && m.turnSpawnType != null)
            flag(flags, TextCatalog.get("mob.flag.turnSpawn"));
        if (flags.length() > 0) {
            sb.append('\n').append(flags);
        }

        // -- Abilities -------------------------------------------------------
        if (m.abilities != null && !m.abilities.isEmpty()) {
            sb.append('\n').append(TextCatalog.get("mob.abilities.header")).append('\n');
            for (Mob.MobAbility a : m.abilities) {
                sb.append("* ").append(describeAbility(a)).append('\n');
            }
        }

        // -- Faction tags (encyclopedia value; trims down to nothing for solo
        //    species so the player stats screen stays clean) ----------------
        StringBuilder fac = new StringBuilder();
        if (m.faction != null && !m.faction.isEmpty()) {
            flag(fac, TextCatalog.format("mob.faction", TextCatalog.vars("value", m.faction)));
        }
        if (m.attackTypes != null && !m.attackTypes.isEmpty()) {
            flag(fac, TextCatalog.format("mob.hostileTo", TextCatalog.vars("value", joinSorted(m.attackTypes))));
        }

        if (!m.enemyFactions.contains("PLAYER")) {
            fac.append(TextCatalog.get("mob.notNecessarilyHostile"));
        }
        if (m.enemyFactions != null && !m.enemyFactions.isEmpty()) {
            flag(fac, TextCatalog.format("mob.enemyOf", TextCatalog.vars("value", joinSorted(m.enemyFactions))));
        }
        if (m.fleeTypes != null && !m.fleeTypes.isEmpty()) {
            flag(fac, TextCatalog.format("mob.flees", TextCatalog.vars("value", joinSorted(m.fleeTypes))));
        }
        if (fac.length() > 0) {
            sb.append('\n').append(fac);
        }

        // -- Carried inventory -----------------------------------------------
        // Equipped gear and bag contents - useful at a glance when the
        // player is debating whether to engage. Skipped for empty
        // inventories so vanilla mobs don't get a "carries: nothing" line.
        StringBuilder inv = new StringBuilder();
        if (m.inventory != null) {
            java.util.List<com.bjsp123.rl2.model.Item> equipped = m.inventory.allEquipped();
            if (!equipped.isEmpty()) {
                flag(inv, TextCatalog.format("mob.equipped", TextCatalog.vars("items", joinItemNames(equipped))));
            }
            if (m.inventory.bag != null && !m.inventory.bag.isEmpty()) {
                flag(inv, TextCatalog.format("mob.carries", TextCatalog.vars("items", joinItemNames(m.inventory.bag))));
            }
        }
        if (inv.length() > 0) {
            sb.append('\n').append(inv);
        }

        return sb.toString().trim();
    }

    /** Render a comma-separated list of item names with " +N" enchantment
     *  badges for items above the design baseline level. Stack counts
     *  ({@code count > 1}) print as "name xN". */
    private static String joinItemNames(java.util.List<com.bjsp123.rl2.model.Item> items) {
        StringBuilder sb = new StringBuilder();
        boolean first = true;
        for (com.bjsp123.rl2.model.Item it : items) {
            if (it == null) continue;
            if (!first) sb.append(", ");
            first = false;
            String name = it.name != null ? it.name
                    : (it.type != null ? it.type.toLowerCase() : "item");
            sb.append(name);
            if (it.level > 1) sb.append(" +").append(it.level - 1);
            if (it.count > 1) sb.append(" x").append(it.count);
        }
        return sb.toString();
    }

    private static String describeAbility(Mob.MobAbility a) {
        if (a == null) return "";
        String cd = a.cooldownTurns > 0 ? TextCatalog.format("mob.ability.cooldown", TextCatalog.vars("turns", a.cooldownTurns)) : "";
        return switch (a.kind) {
            case HEAL -> TextCatalog.format("mob.ability.HEAL", TextCatalog.vars("amount", a.healAmount, "cooldown", cd));
            case BUFF -> {
                String name = BuffSystem.displayName(a.applies);
                String dur  = a.appliedDuration > 0 ? TextCatalog.format("mob.ability.duration", TextCatalog.vars("turns", a.appliedDuration)) : "";
                yield TextCatalog.format("mob.ability.BUFF", TextCatalog.vars("buff", name, "duration", dur, "cooldown", cd));
            }
            case TELEPORT -> TextCatalog.format("mob.ability.TELEPORT", TextCatalog.vars("cooldown", cd));
            case PHASE_DODGE -> TextCatalog.format("mob.ability.PHASE_DODGE", TextCatalog.vars("cooldown", cd));
        };
    }

    private static void line(StringBuilder sb, String label, String value) {
        sb.append(pad(label, 12)).append(value).append('\n');
    }

    private static void line(StringBuilder sb, String label, String value, boolean condition, String value2) {
        sb.append(pad(label, 12)).append(value);

        if (condition) {
            sb.append(value2);
        }

        sb.append('\n');
    }

    private static void perLine(StringBuilder sb, String label, String value) {
        sb.append("  ").append(pad(label, 10)).append(value).append('\n');
    }

    private static void flag(StringBuilder sb, String text) {
        sb.append(text).append('\n');
    }

    /** Pad to a fixed width with spaces so labels align without a tab character
     *  the bitmap font can't render. */
    private static String pad(String s, int width) {
        if (s.length() >= width) return s + " ";
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

    private static String perLevelInt(int value) {
        return " plus " + value + " per level";
    }

    private static String perLevelRange(MinMax value) {
        return " plus " + range(value) + " per level";
    }

    private static String plusRange(MinMax m) {
        if (m == null) return "+0";
        if (m.min() == m.max()) return "+" + m.min();
        return "+" + m.min() + "-" + m.max();
    }

    private static String plusInt(int v) {
        return (v >= 0 ? "+" : "") + v;
    }

    private static String pct(double v) {
        return Math.round(v * 100) + "%";
    }

    private static String trim(double v) {
        if (v == Math.floor(v)) return Long.toString((long) v);
        return Double.toString(v);
    }

    private static String joinSorted(java.util.Collection<String> xs) {
        java.util.List<String> sorted = new java.util.ArrayList<>(xs);
        java.util.Collections.sort(sorted);
        return String.join(", ", sorted);
    }
}
