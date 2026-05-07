package com.bjsp123.rl2.logic;

import com.bjsp123.rl2.model.MinMax;
import com.bjsp123.rl2.model.Mob;
import com.bjsp123.rl2.model.Point;
import com.bjsp123.rl2.util.CsvTable;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * One row from {@code assets/data/mobs.csv} parsed into a typed POJO. Carries
 * every gameplay field needed to materialize a {@link Mob} via
 * {@link #apply(Mob, Point)}, plus a few sprite-atlas columns read separately
 * by the rgame-side {@code MobSprites} loader (rlib code never touches them).
 *
 * <p>{@link MobFactory#spawn(String, Point)} fetches a definition from
 * {@link MobRegistry} and calls {@code apply}; the registry replaces the
 * deleted {@code Mob.MobType} enum.
 */
public final class MobDefinition {

    // ── Identity ────────────────────────────────────────────────────────────
    public String type;            // CSV key, e.g. "CAT"
    public String name;
    public String description;
    public Mob.Material material;  // FLESH, STONE, …
    public Mob.Behavior behavior;  // MOB, HUNTER, EXPLORE_HIDE, …

    // ── StatBlock fields ────────────────────────────────────────────────────
    public int    maxHp;
    public int    moveCost;
    public int    attackCost;
    public double healRate;
    public int    accuracy = 10;   // StatBlock default
    public int    evasion  = 5;
    public MinMax damage      = MinMax.ZERO;
    public MinMax armor       = MinMax.ZERO;
    public MinMax apDamage    = MinMax.ZERO;
    public MinMax magicResist = MinMax.ZERO;
    public int    size = 4;
    public double visionRadius;
    public double wakeRadius;
    public MinMax rangedDamage = MinMax.ZERO;
    public int    rangedDistance;
    public int    rangedCost;
    public int    rangedRateOfFire;
    public boolean flying;
    public boolean fireImmune;
    public boolean fireSpreadOnAttack;
    public boolean poisonsOnAttack;
    public boolean terrifying;
    public boolean terrifiable = true;  // StatBlock default
    public double  eatSpawnChance;
    public double  mushroomEatSpawnChance;
    public double  turnSpawnChance;
    public int     fireExplosionRadiusOnDeath;

    // ── Mob-side categorical fields ─────────────────────────────────────────
    public String eatSpawnType;          // mob-type string ref or null
    public String mushroomEatSpawnType;
    public String turnSpawnType;
    public Mob.DoorClosingBehavior doorClosing = Mob.DoorClosingBehavior.NEVER;
    public Mob.StateOfMind         stateOfMind = Mob.StateOfMind.ASLEEP;

    // ── AI sets + shorthand ─────────────────────────────────────────────────
    public Set<String> attackTypes = new HashSet<>();
    public Set<String> fleeTypes   = new HashSet<>();
    /** Faction tag (arbitrary string). Mobs sharing a faction are allies; null
     *  for lone-wolf species. */
    public String faction;
    /** Faction tags this mob is hostile to — see {@link Mob#enemyFactions}. */
    public Set<String> enemyFactions = new HashSet<>();
    /** When true, this species can spawn at most once per run. Generation code
     *  checks {@code World.unique.mobs} and adds the type-name on spawn so
     *  subsequent levels skip it. Used by the elder-* boss variants. */
    public boolean unique;
    /** Drop-quality multiplier on whatever the mob drops (today an opaque integer
     *  parsed from the CSV; consumed by future loot logic). Ordinary mobs use
     *  {@code 1}; unique bosses use {@code 3}. */
    public int dropQuality = 1;
    /** Where in the dungeon this species is meant to appear. Expressed as a
     *  fraction-of-depth window (0 = depth 1, 1 = {@code DUNGEON_DEPTH}); the
     *  populator filters out levels whose depth-fraction falls outside
     *  {@code [powerMin, powerMax]} and weights survivors by closeness to the
     *  midpoint, so a species with {@code 0.3_0.7} peaks at mid-dungeon and
     *  fades away at the band's edges. */
    public double powerMin = 0.3;
    public double powerMax = 0.7;
    /** Cluster size when this mob shows up: when picked, the populator spawns
     *  this many of the same species on adjacent floor tiles. {@code 1_1} (or
     *  {@code 1}) is a solo encounter; ant workers, blob spawns, etc. roll
     *  higher ranges. */
    public MinMax clusterSize = MinMax.of(1);
    /** Optional theme gate. When non-null, the species is only eligible on
     *  levels whose {@code theme} matches; null means "any theme". */
    public com.bjsp123.rl2.model.Level.VisualTheme theme;
    /** Omnihostile shorthand. When set, the mob's {@link #attackTypes} is
     *  populated with every known mob type EXCEPT this list and the mob
     *  itself. */
    public List<String> attackAllExcept = new ArrayList<>();

    // ── Retainers (entourage species spawned alongside this mob) ────────────
    /** How many retainers spawn with each instance of this mob (rolled per
     *  spawn). {@code 0_0} = no retainers; cats roll {@code 0_2} kittens, the
     *  kobold general rolls {@code 2_3} fighters/spearmen/cleavers. */
    public MinMax numRetainers = MinMax.ZERO;
    /** Mob types eligible to be picked as retainers; one entry per retainer
     *  is drawn (with replacement) from this list. Empty when
     *  {@link #numRetainers} is zero. */
    public List<String> retainerTypes = new ArrayList<>();

    // ── Special-case flags (replace hardcoded mob-type checks) ──────────────
    public boolean banishable;
    public int knockbackSquares;

    // ── Per-level scaling deltas (applied by MobProgression on each level up
    //    or pre-roll). MinMax columns use the {@code MIN_MAX} cell format.
    public int    hpPerLevel             = 2;
    public int    accuracyPerLevel       = 1;
    public int    evasionPerLevel        = 1;
    public MinMax damagePerLevel         = new MinMax(1, 2);
    public MinMax apPerLevel             = MinMax.ZERO;
    public MinMax rangedDamagePerLevel   = MinMax.ZERO;
    public int    rangedDistancePerLevel = 0;
    public MinMax armorPerLevel          = new MinMax(0, 1);

    // ── Abilities (single packed cell — only kobold general today) ──────────
    public List<AbilityDef> abilities = new ArrayList<>();

    // ── Initial buffs (e.g. horror's TELEPORT_COOLDOWN seed) ────────────────
    public List<InitialBuff> initialBuffs = new ArrayList<>();

    // ── Starting inventory + perks (player rows) ────────────────────────────
    /** Items added to the bag at construction. Each entry is an item-type
     *  string (a key into {@code items.csv}) or {@code <type>*<count>}. Items
     *  whose definition carries a non-null slot are auto-equipped. Empty for
     *  non-player rows. */
    public List<StartItem> startingInventory = new ArrayList<>();
    /** Perks the mob is born with. Each entry is a Perk enum name (level 1 each). */
    public List<com.bjsp123.rl2.model.Perk> startingPerks = new ArrayList<>();

    /** Default HUD action-bar binds for player rows. Pipe-separated
     *  {@code <slotIndex>:<itemType>} entries — e.g.
     *  {@code 0:FIRE_BOMB|1:OIL_BOMB|2:HEALING_POTION}. Null/empty for non-player
     *  mobs and for player classes with no preset binds. */
    public String actionBar;

    // ── Sprite columns (read by rgame-side MobSprites loader) ───────────────
    public int     spriteCol;
    public int     spriteRow;
    public int     spriteW = 1;
    public int     spriteH = 1;
    public boolean spriteFacingPair;

    // ────────────────────────────────────────────────────────────────────────

    /** One support-ability spec — mirrors {@link com.bjsp123.rl2.model.Mob.MobAbility}'s
     *  shape so {@link #apply} can copy it across one-for-one. {@link #kind}
     *  drives the dispatch; the per-kind fields below are populated only for
     *  the kinds that need them (buff fields for {@code BUFF}, {@code healAmount}
     *  for {@code HEAL}, and just the cooldown fields for {@code TELEPORT}). */
    public static final class AbilityDef {
        public com.bjsp123.rl2.model.Mob.MobAbility.AbilityKind kind
                = com.bjsp123.rl2.model.Mob.MobAbility.AbilityKind.BUFF;
        public com.bjsp123.rl2.model.Buff.BuffType applies;
        public int    buffLevel;
        public int    buffDuration;
        public int    healAmount;
        public com.bjsp123.rl2.model.Buff.BuffType cooldownTracker;
        public int    cooldownTurns;
    }

    /** One pre-applied buff to seed onto the mob at spawn time
     *  (e.g. horror starts with TELEPORT_COOLDOWN duration 20 so its first
     *  jump lands 20 turns after spawn). */
    public static final class InitialBuff {
        public com.bjsp123.rl2.model.Buff.BuffType type;
        public int level;
        public int duration;
    }

    /** One starter-inventory line: an item-type id (CSV row key in items.csv)
     *  and a stack count (defaults to 1). */
    public static final class StartItem {
        public String type;
        public int count;
        public StartItem(String t, int c) {
            type = t; count = c;
        }
    }

    // ────────────────────────────────────────────────────────────────────────
    // PARSE: CSV → list of definitions
    // ────────────────────────────────────────────────────────────────────────

    public static List<MobDefinition> parseAll(String csv) {
        CsvTable table = CsvTable.parse(csv);
        List<MobDefinition> out = new ArrayList<>(table.rows.size());
        for (Map<String, String> row : table.rows) {
            out.add(parseRow(row));
        }
        return out;
    }

    private static MobDefinition parseRow(Map<String, String> row) {
        // Defaults mirror {@link com.bjsp123.rl2.model.StatBlock} so an empty
        // cell behaves exactly like the field had never been touched.
        MobDefinition d = new MobDefinition();
        d.type        = CsvTable.str(row, "type", null);
        d.name        = CsvTable.str(row, "name", null);
        d.description = CsvTable.str(row, "description", null);
        d.material    = CsvTable.enumCell(row, "material", Mob.Material.class, Mob.Material.FLESH);
        d.behavior    = CsvTable.enumCell(row, "behavior", Mob.Behavior.class, Mob.Behavior.MOB);

        d.maxHp       = CsvTable.intCell(row, "maxHp", 10);
        d.moveCost    = CsvTable.intCell(row, "moveCost", 100);
        d.attackCost  = CsvTable.intCell(row, "attackCost", 100);
        d.healRate    = CsvTable.dblCell(row, "healRate", 0);

        d.accuracy    = CsvTable.intCell(row, "accuracy", 10);
        d.evasion     = CsvTable.intCell(row, "evasion", 5);
        d.damage      = CsvTable.minMaxCell(row, "damage", MinMax.ZERO);
        d.armor       = CsvTable.minMaxCell(row, "armor", MinMax.ZERO);
        d.apDamage    = CsvTable.minMaxCell(row, "apDamage", MinMax.ZERO);
        d.magicResist = CsvTable.minMaxCell(row, "magicResist", MinMax.ZERO);

        d.size         = CsvTable.intCell(row, "size", 4);
        d.visionRadius = CsvTable.dblCell(row, "visionRadius", 8);
        d.wakeRadius   = CsvTable.dblCell(row, "wakeRadius", 6);

        d.rangedDamage     = CsvTable.minMaxCell(row, "rangedDamage", MinMax.ZERO);
        d.rangedDistance   = CsvTable.intCell(row, "rangedDistance", 0);
        d.rangedCost       = CsvTable.intCell(row, "rangedCost", 0);
        d.rangedRateOfFire = CsvTable.intCell(row, "rangedRateOfFire", 0);

        d.flying              = CsvTable.boolCell(row, "flying", false);
        d.fireImmune          = CsvTable.boolCell(row, "fireImmune", false);
        d.fireSpreadOnAttack  = CsvTable.boolCell(row, "fireSpreadOnAttack", false);
        d.poisonsOnAttack     = CsvTable.boolCell(row, "poisonsOnAttack", false);
        d.terrifying          = CsvTable.boolCell(row, "terrifying", false);
        d.terrifiable         = CsvTable.boolCell(row, "terrifiable", true);

        d.eatSpawnChance         = CsvTable.dblCell(row, "eatSpawnChance", 0);
        d.mushroomEatSpawnChance = CsvTable.dblCell(row, "mushroomEatSpawnChance", 0);
        d.turnSpawnChance        = CsvTable.dblCell(row, "turnSpawnChance", 0);
        d.fireExplosionRadiusOnDeath   = CsvTable.intCell(row, "fireExplosionRadiusOnDeath", 0);

        d.eatSpawnType         = CsvTable.str(row, "eatSpawnType", null);
        d.mushroomEatSpawnType = CsvTable.str(row, "mushroomEatSpawnType", null);
        d.turnSpawnType        = CsvTable.str(row, "turnSpawnType", null);
        d.doorClosing = CsvTable.enumCell(row, "doorClosing",
                Mob.DoorClosingBehavior.class, Mob.DoorClosingBehavior.NEVER);
        d.stateOfMind = CsvTable.enumCell(row, "stateOfMind",
                Mob.StateOfMind.class, Mob.StateOfMind.ASLEEP);

        d.attackTypes.addAll(CsvTable.listCell(row, "attackTypes"));
        d.fleeTypes  .addAll(CsvTable.listCell(row, "fleeTypes"));
        d.faction         = CsvTable.str(row, "faction", null);
        d.enemyFactions.addAll(CsvTable.listCell(row, "enemyFactions"));
        d.unique          = CsvTable.boolCell(row, "unique", false);
        d.dropQuality     = CsvTable.intCell(row, "dropQuality", 1);
        double[] power    = CsvTable.dblRangeCell(row, "powerLevel", 0.3, 0.7);
        d.powerMin        = power[0];
        d.powerMax        = power[1];
        d.clusterSize     = CsvTable.minMaxCell(row, "clusterSize", MinMax.of(1));
        d.numRetainers    = CsvTable.minMaxCell(row, "numRetainers", MinMax.ZERO);
        d.retainerTypes   = new ArrayList<>(CsvTable.listCell(row, "retainerTypes"));
        d.theme           = CsvTable.enumCell(row, "theme",
                com.bjsp123.rl2.model.Level.VisualTheme.class, null);
        d.attackAllExcept = new ArrayList<>(CsvTable.listCell(row, "attackAllExcept"));

        d.banishable         = CsvTable.boolCell(row, "banishable", false);
        d.knockbackSquares   = CsvTable.intCell(row, "knockbackSquares", 0);

        d.hpPerLevel             = CsvTable.intCell(row, "hpPerLevel", 2);
        d.accuracyPerLevel       = CsvTable.intCell(row, "accuracyPerLevel", 1);
        d.evasionPerLevel        = CsvTable.intCell(row, "evasionPerLevel", 1);
        d.damagePerLevel         = CsvTable.minMaxCell(row, "damagePerLevel", new MinMax(1, 2));
        d.apPerLevel             = CsvTable.minMaxCell(row, "apPerLevel", MinMax.ZERO);
        d.rangedDamagePerLevel   = CsvTable.minMaxCell(row, "rangedDamagePerLevel", MinMax.ZERO);
        d.rangedDistancePerLevel = CsvTable.intCell(row, "rangedDistancePerLevel", 0);
        d.armorPerLevel          = CsvTable.minMaxCell(row, "armorPerLevel", new MinMax(0, 1));

        d.abilities = parseAbilities(CsvTable.str(row, "abilities", null));
        d.initialBuffs = parseInitialBuffs(CsvTable.str(row, "initialBuffs", null));
        d.startingInventory = parseStartingInventory(CsvTable.str(row, "startingInventory", null));
        d.startingPerks = parseStartingPerks(CsvTable.str(row, "startingPerks", null));
        d.actionBar = CsvTable.str(row, "actionBar", null);

        d.spriteCol        = CsvTable.intCell(row, "spriteCol", 0);
        d.spriteRow        = CsvTable.intCell(row, "spriteRow", 0);
        d.spriteW          = CsvTable.intCell(row, "spriteW", 1);
        d.spriteH          = CsvTable.intCell(row, "spriteH", 1);
        d.spriteFacingPair = CsvTable.boolCell(row, "spriteFacingPair", false);

        return d;
    }

    /** Parse the {@code abilities} cell.
     *  Format examples:
     *  <ul>
     *    <li>{@code buff:HASTED:2:8:HASTE_COOLDOWN:6}</li>
     *    <li>{@code heal:15:HEAL_COOLDOWN:6}</li>
     *    <li>{@code teleport:TELEPORT_COOLDOWN:15}</li>
     *  </ul>
     *  Semicolon separates abilities, colon separates fields. */
    private static List<AbilityDef> parseAbilities(String cell) {
        List<AbilityDef> out = new ArrayList<>();
        if (cell == null || cell.isEmpty()) return out;
        for (String entry : cell.split(";")) {
            String e = entry.trim();
            if (e.isEmpty()) continue;
            String[] parts = e.split(":");
            for (int i = 0; i < parts.length; i++) parts[i] = parts[i].trim();
            AbilityDef a = new AbilityDef();
            String kind = parts[0];
            if ("buff".equals(kind)) {
                // buff:<BuffType>:<level>:<duration>:<cooldownBuff>:<cooldownTurns>
                a.kind            = com.bjsp123.rl2.model.Mob.MobAbility.AbilityKind.BUFF;
                a.applies         = com.bjsp123.rl2.model.Buff.BuffType.valueOf(parts[1]);
                a.buffLevel       = Integer.parseInt(parts[2]);
                a.buffDuration    = Integer.parseInt(parts[3]);
                a.cooldownTracker = com.bjsp123.rl2.model.Buff.BuffType.valueOf(parts[4]);
                a.cooldownTurns   = Integer.parseInt(parts[5]);
            } else if ("heal".equals(kind)) {
                // heal:<amount>:<cooldownBuff>:<cooldownTurns>
                a.kind            = com.bjsp123.rl2.model.Mob.MobAbility.AbilityKind.HEAL;
                a.healAmount      = Integer.parseInt(parts[1]);
                a.cooldownTracker = com.bjsp123.rl2.model.Buff.BuffType.valueOf(parts[2]);
                a.cooldownTurns   = Integer.parseInt(parts[3]);
            } else if ("teleport".equals(kind)) {
                // teleport:<cooldownBuff>:<cooldownTurns>
                a.kind            = com.bjsp123.rl2.model.Mob.MobAbility.AbilityKind.TELEPORT;
                a.cooldownTracker = com.bjsp123.rl2.model.Buff.BuffType.valueOf(parts[1]);
                a.cooldownTurns   = Integer.parseInt(parts[2]);
            } else {
                throw new IllegalArgumentException("unknown ability kind: " + kind);
            }
            out.add(a);
        }
        return out;
    }

    /** Parse the {@code startingInventory} cell — pipe-separated item-type
     *  strings, optionally with a {@code *<count>} suffix. e.g.
     *  {@code DAGGER | FIRE_BOMB*5 | OIL_BOMB*5}. Range syntax ({@code *N_M}) is
     *  parsed but only the minimum is used here, since starting inventories
     *  aren't randomized. */
    private static List<StartItem> parseStartingInventory(String cell) {
        List<StartItem> out = new ArrayList<>();
        for (CsvTable.SpawnSpec s : CsvTable.parseSpawnSpecList(cell)) {
            out.add(new StartItem(s.ref, Math.max(1, s.min)));
        }
        return out;
    }

    /** Parse the {@code startingPerks} cell. Format: pipe-separated Perk enum
     *  names. e.g. {@code KILLER | STEALTH}. */
    private static List<com.bjsp123.rl2.model.Perk> parseStartingPerks(String cell) {
        List<com.bjsp123.rl2.model.Perk> out = new ArrayList<>();
        if (cell == null || cell.isEmpty()) return out;
        for (String entry : cell.split("\\|")) {
            String e = entry.trim();
            if (e.isEmpty()) continue;
            out.add(com.bjsp123.rl2.model.Perk.valueOf(e));
        }
        return out;
    }

    /** Parse the {@code initialBuffs} cell. Format:
     *  {@code TELEPORT_COOLDOWN:1:20 ; ANOTHER_BUFF:2:5}. Each entry is
     *  {@code buffType:level:duration}. */
    private static List<InitialBuff> parseInitialBuffs(String cell) {
        List<InitialBuff> out = new ArrayList<>();
        if (cell == null || cell.isEmpty()) return out;
        for (String entry : cell.split(";")) {
            String e = entry.trim();
            if (e.isEmpty()) continue;
            String[] parts = e.split(":");
            for (int i = 0; i < parts.length; i++) parts[i] = parts[i].trim();
            InitialBuff b = new InitialBuff();
            b.type     = com.bjsp123.rl2.model.Buff.BuffType.valueOf(parts[0]);
            b.level    = Integer.parseInt(parts[1]);
            b.duration = Integer.parseInt(parts[2]);
            out.add(b);
        }
        return out;
    }

    // ────────────────────────────────────────────────────────────────────────
    // APPLY: write fields onto a fresh Mob
    // ────────────────────────────────────────────────────────────────────────

    /** Stamp this definition's fields onto a fresh {@link Mob} positioned
     *  at {@code pos}. */
    public void apply(Mob m, Point pos) {
        m.mobType  = type;
        m.position = pos;
        m.material = material;
        m.behavior = behavior;
        m.name        = name;
        m.description = description;
        m.doorClosing = doorClosing;
        m.stateOfMind = stateOfMind;

        // Vital state seeded from the intrinsic max.
        m.intrinsic.maxHp     = maxHp;
        m.hp                  = maxHp;
        m.intrinsic.moveCost  = moveCost;
        m.intrinsic.attackCost = attackCost;
        m.intrinsic.healRate  = healRate;

        m.intrinsic.accuracy    = accuracy;
        m.intrinsic.evasion     = evasion;
        m.intrinsic.damage      = damage;
        m.intrinsic.armor       = armor;
        m.intrinsic.apDamage    = apDamage;
        m.intrinsic.magicResist = magicResist;

        m.intrinsic.size         = size;
        m.intrinsic.visionRadius = visionRadius;
        m.intrinsic.wakeRadius   = wakeRadius;

        m.intrinsic.rangedDamage     = rangedDamage;
        m.intrinsic.rangedDistance   = rangedDistance;
        m.intrinsic.rangedCost       = rangedCost;
        m.intrinsic.rangedRateOfFire = rangedRateOfFire;

        m.intrinsic.flying             = flying;
        m.intrinsic.fireImmune         = fireImmune;
        m.intrinsic.fireSpreadOnAttack = fireSpreadOnAttack;
        m.intrinsic.poisonsOnAttack    = poisonsOnAttack;
        m.intrinsic.terrifying         = terrifying;
        m.intrinsic.terrifiable        = terrifiable;

        m.intrinsic.eatSpawnChance         = eatSpawnChance;
        m.intrinsic.mushroomEatSpawnChance = mushroomEatSpawnChance;
        m.intrinsic.turnSpawnChance        = turnSpawnChance;
        m.intrinsic.fireExplosionRadiusOnDeath = fireExplosionRadiusOnDeath;

        m.eatSpawnType         = eatSpawnType;
        m.mushroomEatSpawnType = mushroomEatSpawnType;
        m.turnSpawnType        = turnSpawnType;

        m.banishable              = banishable;
        m.intrinsic.knockbackSquares = knockbackSquares;

        m.hpPerLevel             = hpPerLevel;
        m.accuracyPerLevel       = accuracyPerLevel;
        m.evasionPerLevel        = evasionPerLevel;
        m.damagePerLevel         = damagePerLevel;
        m.apPerLevel             = apPerLevel;
        m.rangedDamagePerLevel   = rangedDamagePerLevel;
        m.rangedDistancePerLevel = rangedDistancePerLevel;
        m.armorPerLevel          = armorPerLevel;

        // AI sets — copy directly, then expand the attackAllExcept shorthand.
        m.attackTypes.addAll(attackTypes);
        m.fleeTypes  .addAll(fleeTypes);
        m.faction    = faction;
        m.enemyFactions.addAll(enemyFactions);
        if (!attackAllExcept.isEmpty()) {
            Set<String> excluded = new HashSet<>(attackAllExcept);
            if (m.mobType != null) excluded.add(m.mobType);
            for (String t : MobRegistry.knownTypes()) {
                if (!excluded.contains(t)) m.attackTypes.add(t);
            }
        }

        // Abilities.
        for (AbilityDef a : abilities) {
            switch (a.kind) {
                case BUFF -> m.abilities.add(Mob.MobAbility.buff(
                        a.applies, a.buffLevel, a.buffDuration,
                        a.cooldownTracker, a.cooldownTurns));
                case HEAL -> m.abilities.add(Mob.MobAbility.heal(
                        a.healAmount, a.cooldownTracker, a.cooldownTurns));
                case TELEPORT -> m.abilities.add(Mob.MobAbility.teleport(
                        a.cooldownTracker, a.cooldownTurns));
            }
        }

        // Pre-seeded buffs (horror's TELEPORT_COOLDOWN start).
        for (InitialBuff b : initialBuffs) {
            m.buffs.add(new com.bjsp123.rl2.model.Buff(b.type, b.level, b.duration, m));
        }

        // Starting inventory: build via ItemFactory, equip anything with a slot.
        for (StartItem s : startingInventory) {
            for (int i = 0; i < s.count; i++) {
                com.bjsp123.rl2.model.Item it = ItemFactory.build(s.type);
                m.inventory.bag.add(it);
                if (it.isEquippable()) InventorySystem.equip(m.inventory, it);
            }
        }
        // Starting perks: each entry awarded at level 1.
        for (com.bjsp123.rl2.model.Perk perk : startingPerks) {
            m.perks.put(perk, 1);
        }
    }
}
