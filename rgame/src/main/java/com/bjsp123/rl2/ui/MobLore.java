package com.bjsp123.rl2.ui;

import com.bjsp123.rl2.logic.BuffSystem;
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
 * something to show — a vanilla mouse won't print a "Ranged" section, a mob
 * with no abilities skips the abilities header. Lives in rgame because it's a
 * presentation concern; rlib's {@link Mob} / {@link StatBlock} stay free of UI
 * strings.
 */
public final class MobLore {

    private MobLore() {}

    /** Multi-section description of {@code m}. Includes the species blurb,
     *  combat numbers, ranged stats, perception / lighting, body / movement,
     *  knockback, immunities + special behaviours, abilities, per-level scaling
     *  deltas, and faction tags. Each section is omitted entirely when its
     *  values are all defaults / zero. */
    public static String describe(Mob m) {
        if (m == null) return "";
        StatBlock s = m.effectiveStats();
        StringBuilder sb = new StringBuilder();
        // Unique-mob banner — set on the species via the {@code unique} CSV
        // column on mobs.csv. Read from the registry rather than off the
        // {@link Mob} instance because the property is intrinsic to the
        // species, not the instance.
        if (m.mobType != null) {
            com.bjsp123.rl2.logic.MobDefinition def =
                    com.bjsp123.rl2.logic.MobRegistry.get(m.mobType);
            if (def != null && def.unique) {
                sb.append("Unique foe\n\n");
            }
        }
        if (m.description != null && !m.description.isEmpty()) {
            sb.append(m.description).append("\n\n");
        }

        // ── Combat ──────────────────────────────────────────────────────────
        line(sb, "Max HP: ",       Integer.toString((int) Math.round(s.maxHp)), m.hpPerLevel>0, " plus " + m.hpPerLevel + " per level");
        line(sb, "Attack: ",   ""+s.accuracy, m.accuracyPerLevel > 0, " plus " + m.accuracyPerLevel + " per level");
        line(sb, "Defense: ",  ""+s.evasion, m.evasionPerLevel > 0, " plus " + m.evasionPerLevel + " per level");
        line(sb, "Damage: ",   range(s.damage), !m.damagePerLevel.isZero(), " plus " + range(m.damagePerLevel) + " per level");
        line(sb, "Armor: ",    range(s.armor), !m.armorPerLevel.isZero(), " plus " + range(m.armorPerLevel) + " per level");
        if (!s.apDamage.isZero())    line(sb, "AP damage",   range(s.apDamage), !m.apPerLevel.isZero(), " plus " + range(m.apPerLevel) + " per level");
        if (!s.magicResist.isZero()) line(sb, "Magic resistance: ", range(s.magicResist));
        if (s.knockbackSquares > 0)  line(sb, "Knockback: ",   s.knockbackSquares + " sq");
        if (s.healRate > 0)          line(sb, "Heal rate: ",   trim(s.healRate) + " HP/turn");

        // ── Ranged ──────────────────────────────────────────────────────────
        if (!s.rangedDamage.isZero() || s.rangedDistance > 0) {
            sb.append('\n');
            line(sb, "This creature has a ranged attack with damage ",   range(s.rangedDamage));
            if (s.rangedDistance > 0)   line(sb, "Its range is",        s.rangedDistance + " tiles.");
            if (s.rangedRateOfFire > 0) line(sb, "It can fire every", s.rangedRateOfFire + " turns.");
        }

        // ── Perception + lighting ───────────────────────────────────────────
        sb.append('\n');
        line(sb, "This creature can see for ", trim(s.visionRadius) + " tiles in any direction.");
        line(sb, "This creature awakes when a foes is within ",   trim(s.wakeRadius)   + " tiles.");
        if (s.lightRadius > 0) 
            flag(sb, "This creature glows with light!");

        // ── Movement / body ─────────────────────────────────────────────────
        sb.append('\n');
        if(s.moveCost > 140){
            flag(sb, "This creature is extemely sluggish.");
        } else if (s.moveCost > 100){
            flag(sb, "This creature is somewhat slow-moving.");
        } else if (s.moveCost < 80) {
            flag(sb, "This creature moves extremely fast.");
        } else if (s.moveCost < 100) {
            flag(sb, "This creature moves rather swiftly.");
        }

        if(s.attackCost > 140){
            flag(sb, "This creature attacks sluggishly.");
        } else if (s.attackCost > 100){
            flag(sb, "This creature's attacks are rather slow.");
        } else if (s.attackCost < 80) {
            flag(sb, "This creature's attacks are lightning-fast.");
        } else if (s.attackCost < 100) {
            flag(sb, "This creature can attack rather quickly.");
        }

        if(s.size < 2){
            flag(sb, "This creature is tiny.");
        } else if (s.size < 4){
            flag(sb, "This creature is quite small.");
        } else if (s.size > 7) {
            flag(sb, "This creature is huge in size.");
        } else if (s.size > 5) {
            flag(sb, "This creature is very big.");
        } else if (s.size > 4) {
            flag(sb, "This creature is large.");
        }

        // ── Special behaviours / immunities (each is a one-liner) ───────────
        StringBuilder flags = new StringBuilder();
        if (s.flying)             flag(flags, "This is a flying creature.");
        if (s.fireImmune)         flag(flags, "This creature is immune to fire.");
        if (s.fireSpreadOnAttack) flag(flags, "This creature's hits ignite tiles around the target.");
        if (s.poisonsOnAttack)    flag(flags, "This creature's venomous attacks poison the foe..");
        if (s.terrifying)         flag(flags, "This terrifying being frightens nearby susceptible creatures.");
        if (!s.terrifiable)       flag(flags, "This creature does not frighten easily.");
        if (m.banishable)         flag(flags, "This otherwordly being is vulnerable to being Banished.");
        if (s.fireExplosionRadiusOnDeath > 0)
            flag(flags, "On death, this creature explodes in fire.");
        // Teleport now lives on the abilities list (kind = TELEPORT) and
        // is rendered by {@link #describeAbility}, so no special-case line
        // is needed here.
        if (s.eatSpawnChance > 0)
            flag(flags, "By devouring corpses, this creature can spawn further beings.");
        if (s.mushroomEatSpawnChance > 0)
            flag(flags, "Feasting on mushrooms causes this creature to breed explosively.");
        if (s.turnSpawnChance > 0 && m.turnSpawnType != null)
            flag(flags, "Can send out servants to do its bidding.");
        if (flags.length() > 0) {
            sb.append('\n').append(flags);
        }

        // ── Abilities ───────────────────────────────────────────────────────
        if (m.abilities != null && !m.abilities.isEmpty()) {
            sb.append('\n').append("Abilities:\n");
            for (Mob.MobAbility a : m.abilities) {
                sb.append("• ").append(describeAbility(a)).append('\n');
            }
        }

        // ── Faction tags (encyclopedia value; trims down to nothing for solo
        //    species so the player stats screen stays clean) ────────────────
        StringBuilder fac = new StringBuilder();
        if (m.faction != null && !m.faction.isEmpty()) {
            fac.append("Faction: ").append(m.faction).append('\n');
        }
        if (m.attackTypes != null && !m.attackTypes.isEmpty()) {
            fac.append("Hostile to: ").append(joinSorted(m.attackTypes)).append('\n');
        }
        if (m.enemyFactions != null && !m.enemyFactions.isEmpty()) {
            fac.append("Enemy of: ").append(joinSorted(m.enemyFactions)).append('\n');
        }
        if (m.fleeTypes != null && !m.fleeTypes.isEmpty()) {
            fac.append("Flees: ").append(joinSorted(m.fleeTypes)).append('\n');
        }
        if (fac.length() > 0) {
            sb.append('\n').append(fac);
        }

        return sb.toString().trim();
    }

    private static String describeAbility(Mob.MobAbility a) {
        if (a == null) return "";
        String cd = a.cooldownTurns > 0 ? " every " + a.cooldownTurns + " turns" : "";
        return switch (a.kind) {
            case HEAL -> "Heals an ally for " + a.healAmount + " HP" + cd;
            case BUFF -> {
                String name = BuffSystem.displayName(a.applies);
                String dur  = a.appliedDuration > 0 ? " for " + a.appliedDuration + " turns" : "";
                yield "Casts " + name + " on an ally" + dur + cd;
            }
            case TELEPORT -> "Suddenly appears at the side of an enemy" + cd;
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
