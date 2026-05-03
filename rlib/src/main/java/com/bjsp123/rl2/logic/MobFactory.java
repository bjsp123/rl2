package com.bjsp123.rl2.logic;

import com.bjsp123.rl2.model.Mob.CharacterClass;
import com.bjsp123.rl2.model.Item;
import com.bjsp123.rl2.model.MinMax;
import com.bjsp123.rl2.model.Mob.Material;
import com.bjsp123.rl2.model.Mob;
import com.bjsp123.rl2.model.Mob.Behavior;
import com.bjsp123.rl2.model.Mob.MobType;
import com.bjsp123.rl2.model.Point;
import com.bjsp123.rl2.model.Mob.StateOfMind;

public class MobFactory {

    public static Mob player(Point pos, CharacterClass cls) {
        Mob m = new Mob();
        m.mobType  = MobType.PLAYER;
        m.position = pos;
        m.material = Material.FLESH;
        m.behavior = Behavior.PLAYER;
        m.intrinsic.moveCost = GameBalance.PLAYER_MOVE_COST;
        switch (cls) {
            case WARRIOR -> {
                m.intrinsic.maxHp    = GameBalance.WARRIOR_START_HP;
                m.intrinsic.accuracy = GameBalance.WARRIOR_BASE_ATTACK;
                m.intrinsic.evasion  = GameBalance.WARRIOR_BASE_DEFENSE;
                m.intrinsic.damage   = MinMax.of(GameBalance.WARRIOR_BASE_DAMAGE);
                Item sword = ItemFactory.sword();
                Item mail  = ItemFactory.scaleMail();
                m.inventory.bag.add(sword); m.inventory.equip(sword);
                m.inventory.bag.add(mail);  m.inventory.equip(mail);
                m.perks.put(com.bjsp123.rl2.model.Perk.KILLER, 1);
            }
            case ROGUE -> {
                m.intrinsic.maxHp    = GameBalance.ROGUE_START_HP;
                m.intrinsic.accuracy = GameBalance.ROGUE_BASE_ATTACK;
                m.intrinsic.evasion  = GameBalance.ROGUE_BASE_DEFENSE;
                m.intrinsic.damage   = MinMax.of(GameBalance.ROGUE_BASE_DAMAGE);
                Item dagger = ItemFactory.dagger();
                m.inventory.bag.add(dagger); m.inventory.equip(dagger);
                m.perks.put(com.bjsp123.rl2.model.Perk.STEALTH, 1);
                // Rogue's signature kit — five fire bombs and five oil bombs. The HUD
                // action-bar pre-bindings live on the rgame side; this factory just
                // populates the bag.
                for (int i = 0; i < 5; i++) {
                    m.inventory.bag.add(ItemFactory.fireBomb());
                    m.inventory.bag.add(ItemFactory.oilBomb());
                    m.inventory.bag.add(ItemFactory.blastBomb());
                    m.inventory.bag.add(ItemFactory.freezeBomb());
                }
            }
            case MAGE -> {
                m.intrinsic.maxHp    = GameBalance.MAGE_START_HP;
                m.intrinsic.accuracy = GameBalance.MAGE_BASE_ATTACK;
                m.intrinsic.evasion  = GameBalance.MAGE_BASE_DEFENSE;
                m.intrinsic.damage   = MinMax.of(GameBalance.MAGE_BASE_DAMAGE);
                Item dagger = ItemFactory.dagger();
                Item amulet = ItemFactory.amuletOfLight();
                m.inventory.bag.add(dagger); m.inventory.equip(dagger);
                m.inventory.bag.add(amulet); m.inventory.equip(amulet);
                // Mage's signature kit: one of every wand. The bag is the source of truth;
                // HUD bindings are the rgame side's job.
                m.inventory.bag.add(ItemFactory.wandOfWater());
                m.inventory.bag.add(ItemFactory.wandOfOil());
                m.inventory.bag.add(ItemFactory.wandOfVegetation());
                m.inventory.bag.add(ItemFactory.wandOfFungus());
                m.inventory.bag.add(ItemFactory.wandOfFire());
                m.inventory.bag.add(ItemFactory.wandOfDog());
                m.inventory.bag.add(ItemFactory.wandOfDetonation());
                m.inventory.bag.add(ItemFactory.wandOfMagicMissile());
                m.inventory.bag.add(ItemFactory.wandOfBanishment());
                m.perks.put(com.bjsp123.rl2.model.Perk.WANDMASTER, 1);
            }
            default -> throw new IllegalArgumentException("unknown class " + cls);
        }
        m.hp              = m.intrinsic.maxHp;
        m.intrinsic.attackCost      = GameBalance.PLAYER_ATTACK_COST;
        m.intrinsic.visionRadius    = GameBalance.PLAYER_VISION_RADIUS;
        m.characterClass  = cls;
        m.intrinsic.healRate        = GameBalance.PLAYER_HEAL_RATE;
        m.name            = cls.displayName;
        m.doorClosing     = Mob.DoorClosingBehavior.ALWAYS;
        // Player starts awake — without this the new ASLEEP default would route the
        // player through processAiTurn's wake gate, billing moveCost on every drained
        // turn and effectively freezing input after the first action.
        m.stateOfMind     = Mob.StateOfMind.AWAKE;
        // Pre-seed reciprocal hostility — the player starts hostile to every species
        // that ships with PLAYER in its own attackTypes. Previously the player-side
        // attitude was computed dynamically through the player-reflection branch in
        // getAttitudeToMob, but seeding the set up front makes it observable to anything
        // that reads player.attackTypes directly (look-mode annotations, future
        // auto-target heuristics) rather than going through the attitude lookup.
        seedPlayerHostility(m);
        // Every class starts with a single healing potion. Pre-binding it to a HUD
        // quickslot is the rgame side's job — the bag carries the item, not the binding.
        m.inventory.bag.add(ItemFactory.healingPotion());
        return m;
    }

    /** Walk every {@link MobType} (other than PLAYER), spawn a throwaway template at the
     *  origin, and copy any species that lists PLAYER in its default {@link Mob#attackTypes}
     *  into the new player's own attackTypes. New hostile species added later are picked
     *  up automatically by the loop. */
    private static void seedPlayerHostility(Mob player) {
        Point dummy = new Point(0, 0);
        for (MobType type : MobType.values()) {
            if (type == MobType.PLAYER) continue;
            Mob template = spawn(type, dummy);
            if (template == null) continue;
            // Hostile species: anything that lists PLAYER as an attack target.
            if (template.attackTypes != null && template.attackTypes.contains(MobType.PLAYER)) {
                player.attackTypes.add(type);
            }
            // Inanimate spawners (ant hills, …) — flagged hostile so the player can bump-
            // attack the structure even though it never attacks back. Read off the
            // turn-spawn flag rather than enumerating species so future spawners get
            // picked up automatically.
            if (template.behavior == Behavior.INANIMATE
                    && template.turnSpawnType != null) {
                player.attackTypes.add(type);
            }
        }
    }

    /** Backwards-compatible default — Warrior. */
    public static Mob player(Point pos) {
        return player(pos, CharacterClass.WARRIOR);
    }

    /** Populate the fields shared by every NPC species: position, material, behavior, basic
     *  combat stats, and the base HP/move-cost/glyph/mobType. Per-species tuning happens in
     *  the caller after this returns. Mobs default to ASLEEP — the AI's wake gate (see
     *  {@link MobSystem}) keeps them dormant until something they care about wanders into
     *  their {@link Mob#wakeRadius}. */
    private static Mob baseMob(MobType type, int maxHp, int moveCost,
                               Point position, Material material, Behavior behavior) {
        Mob m = new Mob();
        m.mobType     = type;
        m.intrinsic.maxHp       = maxHp;
        m.hp          = maxHp;
        m.intrinsic.moveCost    = moveCost;
        m.position    = position;
        m.material    = material;
        m.behavior    = behavior;
        m.stateOfMind = StateOfMind.ASLEEP;
        return m;
    }

    /**
     * Mark {@code m} as omnihostile by populating its {@link Mob#attackTypes} with every
     * {@link MobType} except (a) the mob's own species and (b) any species named in
     * {@code allies}. New species added to {@link MobType} are picked up automatically —
     * factories using this helper don't need to track the roster. Replaces the old
     * {@code attackAll} flag and {@code faction} string with explicit per-mob data.
     */
    private static void addAttackAllExcept(Mob m, MobType... allies) {
        java.util.Set<MobType> excluded = new java.util.HashSet<>();
        if (m.mobType != null) excluded.add(m.mobType);
        if (allies != null) {
            for (MobType t : allies) if (t != null) excluded.add(t);
        }
        for (MobType t : MobType.values()) {
            if (excluded.contains(t)) continue;
            m.attackTypes.add(t);
        }
    }

    /** Dispatcher used by spawn-on-event hooks (see {@link MobHooks}) to materialize a mob
     *  of a given type at a position without forcing callers to know which factory method
     *  to invoke. Returns null for {@link MobType#PLAYER}, which has its own constructor
     *  with a class argument. */
    public static Mob spawn(MobType type, Point pos) {
        if (type == null) return null;
        return switch (type) {
            case SPIDER             -> spider(pos);
            case LOATHESOME_BUG     -> loathesomeBug(pos);
            case BAT                -> bat(pos);
            case SOLDIER_BUG        -> soldierBug(pos);
            case BUG_PRODIGY        -> bugProdigy(pos);
            case BLACK_ANT          -> blackAnt(pos);
            case RED_ANT            -> redAnt(pos);
            case BLACK_ANT_HILL     -> blackAntHill(pos);
            case RED_ANT_HILL       -> redAntHill(pos);
            case MOUSE              -> mouse(pos);
            case BLAZING_FIREMOUSE  -> blazingFiremouse(pos);
            case DOG                -> dog(pos);
            case CAT                -> cat(pos);
            case KITTEN             -> kitten(pos);
            case KOBOLD_FIGHTER     -> koboldFighter(pos);
            case BARBARIAN_PRINCESS -> barbarianPrincess(pos);
            case BLOB               -> blob(pos);
            case KISSYBLOB          -> kissyblob(pos);
            case MASK_IMP           -> maskImp(pos);
            case LARGE_MASK_IMP     -> largeMaskImp(pos);
            case DEVELOPED_MASK_IMP -> developedMaskImp(pos);
            case HORRIBLE_MASK_IMP  -> horribleMaskImp(pos);
            case GHOST              -> ghost(pos);
            case HORROR             -> horror(pos);
            case PLAYER             -> null;
        };
    }

    // ── Insect line ────────────────────────────────────────────────────────

    /** Spider — fast, small, hostile to the player. Low HP but slippery (high evasion). */
    public static Mob spider(Point pos) {
        Mob m = baseMob(MobType.SPIDER,  4, 50, pos, Material.FLESH, Behavior.MOB);
        m.intrinsic.size         = 2;
        m.name         = "needle spider";
        m.description  = "A small, skittering spider. It could well be poisonous.  It's certainly fast moving.";
        m.intrinsic.visionRadius = 7;
        m.intrinsic.wakeRadius   = 5;
        m.intrinsic.accuracy     = 11;
        m.intrinsic.evasion      = 12;
        m.intrinsic.damage       = MinMax.of(1);
        m.intrinsic.poisonsOnAttack = true;
        m.attackTypes.add(MobType.PLAYER);
        return m;
    }

    /** Loathesome bug — small, weak, hostile to the player. Cannon fodder with a face. */
    public static Mob loathesomeBug(Point pos) {
        Mob m = baseMob(MobType.LOATHESOME_BUG,  3, 100, pos, Material.FLESH, Behavior.MOB);
        m.intrinsic.size         = 2;
        m.name         = "roach";
        m.description  = "A loathsome bug. This cockroach-like creature has few redeeming features.  It's not very strong.";
        m.intrinsic.visionRadius = 5;
        m.intrinsic.wakeRadius   = 4;
        m.intrinsic.accuracy     = 7;
        m.intrinsic.evasion      = 5;
        m.intrinsic.damage       = MinMax.of(1);
        m.attackTypes.add(MobType.PLAYER);
        return m;
    }

    /** Bat — small, flying, weak. Easy to hit but evasive in flight. */
    public static Mob bat(Point pos) {
        Mob m = baseMob(MobType.BAT,  3, 80, pos, Material.FLESH, Behavior.MOB);
        m.intrinsic.size         = 2;
        m.name         = "doom bat";
        m.description  = "A large, menacing bat. It flutters around in the shadows, its eyes glowing with malevolent intent.";
        m.intrinsic.flying       = true;
        m.intrinsic.visionRadius = 7;
        m.intrinsic.wakeRadius   = 5;
        m.intrinsic.accuracy     = 9;
        m.intrinsic.evasion      = 14;
        m.intrinsic.damage       = MinMax.of(1);
        m.attackTypes.add(MobType.PLAYER);
        return m;
    }

    /** Soldier bug — small but strong. Front-line bug-line bruiser; decent armor. */
    public static Mob soldierBug(Point pos) {
        Mob m = baseMob(MobType.SOLDIER_BUG,  10, 100, pos, Material.FLESH, Behavior.MOB);
        m.name         = "soldier bug";
        m.description  = "This insect has a glossy carapace and razor-sharp pincers.  It's stronger than it looks.";
        m.intrinsic.size         = 3;
        m.intrinsic.visionRadius = 8;
        m.intrinsic.wakeRadius   = 6;
        m.intrinsic.accuracy     = 12;
        m.intrinsic.evasion      = 7;
        m.intrinsic.damage       = MinMax.of(2);
        m.intrinsic.armor        = MinMax.of(1);
        m.attackTypes.add(MobType.PLAYER);
        return m;
    }

    /** Bug prodigy — medium, strong. Slightly faster + stronger than the soldier bug;
     *  the elite of the insect line. Carries a small AP component so heavy armour
     *  doesn't fully shut its bite down. */
    public static Mob bugProdigy(Point pos) {
        Mob m = baseMob(MobType.BUG_PRODIGY,  14, 100, pos, Material.FLESH, Behavior.MOB);
        m.name         = "bug prodigy";
        m.description  = "The Prodigy might once have been a soldier bug, but it has evolved into something disturbingly humanoid.";
        m.intrinsic.size         = 3;
        m.intrinsic.visionRadius = 8;
        m.intrinsic.wakeRadius   = 6;
        m.intrinsic.accuracy     = 13;
        m.intrinsic.evasion      = 8;
        m.intrinsic.damage       = MinMax.of(4);
        m.intrinsic.armor        = MinMax.of(1);
        m.intrinsic.apDamage     = new MinMax(1, 1);
        m.attackTypes.add(MobType.PLAYER);
        return m;
    }

    /** Black ant — small bug, slightly weaker than the soldier bug. Hostile to the player
     *  AND to red ants; ant colonies don't get along across colours. The opposite-colour
     *  hostility uses the explicit {@link Mob#attackTypes} entry, which sits above the
     *  same-{@link Behavior} faction shortcut in {@link MobSystem#getAttitudeToMob}. */
    public static Mob blackAnt(Point pos) {
        Mob m = baseMob(MobType.BLACK_ANT,  7, 100, pos, Material.FLESH, Behavior.MOB);
        m.name         = "black ant";
        m.description  = "A proud ant worker, far superior to its red ant foes.";
        m.intrinsic.size         = 2;
        m.intrinsic.visionRadius = 7;
        m.intrinsic.wakeRadius   = 5;
        m.intrinsic.accuracy     = 10;
        m.intrinsic.evasion      = 6;
        m.intrinsic.damage       = new MinMax(1, 2);
        m.attackTypes.add(MobType.PLAYER);
        m.attackTypes.add(MobType.RED_ANT);
        return m;
    }

    /** Red ant — black ant's mirror. Same stats, opposite hostility. */
    public static Mob redAnt(Point pos) {
        Mob m = baseMob(MobType.RED_ANT,  7, 100, pos, Material.FLESH, Behavior.MOB);
        m.name         = "red ant";
        m.description  = "A proud ant worker, far superior to its black ant foes.";
        m.intrinsic.size         = 2;
        m.intrinsic.visionRadius = 7;
        m.intrinsic.wakeRadius   = 5;
        m.intrinsic.accuracy     = 10;
        m.intrinsic.evasion      = 6;
        m.intrinsic.damage       = new MinMax(1, 2);
        m.attackTypes.add(MobType.PLAYER);
        m.attackTypes.add(MobType.BLACK_ANT);
        return m;
    }

    /** Black ant hill — inanimate, durable, spawns a black ant on a free adjacent tile
     *  20% of standard turns ({@link com.bjsp123.rl2.model.StatBlock#turnSpawnChance} +
     *  {@link Mob#turnSpawnType} drives it from {@link TurnSystem#tickStandardTurn}). */
    public static Mob blackAntHill(Point pos) {
        Mob m = baseMob(MobType.BLACK_ANT_HILL,  40, 0, pos, Material.STONE, Behavior.INANIMATE);
        m.name         = "black ant hill";
        m.description  = "A colony of black ants has broken through into the dungeon here, intent on extracting resources.";
        m.intrinsic.size         = 5;
        m.intrinsic.evasion      = 0;
        m.intrinsic.armor        = new MinMax(4, 6);
        m.intrinsic.turnSpawnChance = 0.20;
        m.turnSpawnType  = MobType.BLACK_ANT;
        return m;
    }

    /** Red ant hill — mirror of {@link #blackAntHill} for the red colony. */
    public static Mob redAntHill(Point pos) {
        Mob m = baseMob(MobType.RED_ANT_HILL,  40, 0, pos, Material.STONE, Behavior.INANIMATE);
        m.name         = "red ant hill";
        m.description  = "A colony of red ants has broken through into the dungeon here, intent on extracting resources.";  
        m.intrinsic.size         = 5;
        m.intrinsic.evasion      = 0;
        m.intrinsic.armor        = new MinMax(4, 6);
        m.intrinsic.turnSpawnChance = 0.20;
        m.turnSpawnType  = MobType.RED_ANT;
        return m;
    }

    // ── Critters ───────────────────────────────────────────────────────────

    /** Timid rodent — {@link Behavior#EXPLORE_HIDE}. Flees the player + cats + any
     *  terrifying mob (via the {@code terrifiable} default). Eats mushrooms with a 10%
     *  chance to spawn another mouse. */
    public static Mob mouse(Point pos) {
        Mob m = baseMob(MobType.MOUSE,  1, 70, pos, Material.FLESH, Behavior.EXPLORE_HIDE);
        m.intrinsic.size         = 1;
        m.name         = "dungeon mouse";
        m.description  = "A small, skittering mouse. It's timid and tends to flee from you.";
        m.intrinsic.visionRadius = 6;
        m.intrinsic.wakeRadius   = 4;
        m.intrinsic.accuracy     = 4;
        m.intrinsic.evasion      = 15;
        m.intrinsic.damage       = MinMax.of(0);
        m.fleeTypes.add(MobType.PLAYER);
        m.fleeTypes.add(MobType.CAT);
        m.intrinsic.mushroomEatSpawnChance = 0.50;
        m.mushroomEatSpawnType   = MobType.MOUSE;
        m.doorClosing  = Mob.DoorClosingBehavior.ONLY_IF_WAS_CLOSED;
        return m;
    }

    /** Blazing Firemouse — a mouse with the same stats and timid {@link Behavior#EXPLORE_HIDE},
     *  but two tweaks make it a defensive nuisance:
     *  <ul>
     *    <li>Fire-immune: walks through flames untouched.</li>
     *    <li>Reactive fire burst: any damaging hit ignites its tile + four cardinal
     *        neighbours via {@link Mob#fireSpreadOnAttack}, so an over-eager attacker
     *        ends up standing in the fire they just sprayed across the floor.</li>
     *  </ul>
     *  Eats mushrooms with the standard mouse-multiplier roll, but spawns more
     *  firemice — the kindle stays kindled. */
    public static Mob blazingFiremouse(Point pos) {
        Mob m = baseMob(MobType.BLAZING_FIREMOUSE,  1, 70, pos, Material.FLESH,
                Behavior.EXPLORE_HIDE);
        m.intrinsic.size         = 1;
        m.name         = "blazing firemouse";
        m.description  = "A small, skittering mouse with a fiery disposition.";
        m.intrinsic.visionRadius = 6;
        m.intrinsic.wakeRadius   = 4;
        m.intrinsic.accuracy     = 4;
        m.intrinsic.evasion      = 15;
        m.intrinsic.damage       = MinMax.of(0);
        m.fleeTypes.add(MobType.PLAYER);
        m.fleeTypes.add(MobType.CAT);
        m.intrinsic.mushroomEatSpawnChance = 0.50;
        m.mushroomEatSpawnType   = MobType.BLAZING_FIREMOUSE;
        m.doorClosing  = Mob.DoorClosingBehavior.ONLY_IF_WAS_CLOSED;
        m.intrinsic.fireImmune                 = true;
        m.intrinsic.fireSpreadOnAttack         = true;
        m.intrinsic.fireExplosionRadiusOnDeath = 3;
        return m;
    }

    /** Dog — {@link Behavior#HUNTER}. Hunts cats; spawned with no owner (wild). The
     *  wand-of-dog summon path links the new dog to the player at use site, so summoned
     *  dogs follow you while level-spawned dogs roam free. */
    public static Mob dog(Point pos) {
        Mob m = baseMob(MobType.DOG,  8, 120, pos, Material.FLESH, Behavior.HUNTER);
        m.intrinsic.size         = 3;
        m.name         = "hound";
        m.description = "A loyal and fierce dog, trained to hunt and protect.";
        m.intrinsic.accuracy     = 10;
        m.intrinsic.evasion      = 8;
        m.intrinsic.damage       = MinMax.of(3);
        m.stateOfMind  = StateOfMind.AWAKE;   // predators are active on level load
        m.attackTypes.add(MobType.CAT);
        return m;
    }

    /** Kitten — fragile cat companion. {@link Behavior#HUNTER} with {@link MobType#MOUSE}
     *  in attackTypes; otherwise enters {@link StateOfMind#FOLLOWING} on its parent cat
     *  and shadows it. 1 HP, glass-cannon-without-the-cannon. The cat reference is set by
     *  the level populator at spawn time, since {@link Mob#followTarget} is transient and
     *  has to be wired up alongside the cat that triggered the spawn. */
    public static Mob kitten(Point pos) {
        Mob m = baseMob(MobType.KITTEN,  1, 120, pos, Material.FLESH, Behavior.HUNTER);
        m.intrinsic.size         = 1;
        m.name         = "kitten";
        m.description  = "A small kitten that looks up to its parent cat.  You can only hope it survives long enough to grow up.";
        m.intrinsic.visionRadius = 6;
        m.intrinsic.wakeRadius   = 4;
        m.intrinsic.accuracy     = 6;
        m.intrinsic.evasion      = 14;
        m.intrinsic.damage       = MinMax.of(1);
        m.stateOfMind  = StateOfMind.FOLLOWING;
        m.attackTypes.add(MobType.MOUSE);
        // Kittens chase firemice too — same instinct as a regular mouse hunt. The kitten
        // usually doesn't survive contact (the firemouse's reactive fire burst tends to
        // immolate small companions) but they go after them anyway.
        m.attackTypes.add(MobType.BLAZING_FIREMOUSE);
        return m;
    }

    /** Cat — {@link Behavior#HUNTER}. Attacks mice, flees dogs. Move cost deliberately
     *  below the mouse's so a cat in open ground overtakes a fleeing mouse. */
    public static Mob cat(Point pos) {
        Mob m = baseMob(MobType.CAT,  6, 60, pos, Material.FLESH, Behavior.HUNTER);
        m.intrinsic.size         = 2;
        m.name         = "cat";
        m.description  = "A sleek and agile cat, always on the prowl for its next meal.  Mice beware.";
        m.intrinsic.accuracy     = 9;
        m.intrinsic.evasion      = 8;
        m.intrinsic.damage       = MinMax.of(2);
        m.attackTypes.add(MobType.MOUSE);
        // Cats also hunt firemice — the cat doesn't know fire-immunity from a hole in the
        // ground. The firemouse's reactive fire burst (and its death explosion) usually
        // turns the chase into a costly mistake, but cats are nothing if not committed.
        m.attackTypes.add(MobType.BLAZING_FIREMOUSE);
        m.fleeTypes.add(MobType.DOG);
        m.doorClosing  = Mob.DoorClosingBehavior.ONLY_IF_WAS_CLOSED;
        m.stateOfMind  = StateOfMind.AWAKE;   // predators wander and explore from spawn
        return m;
    }

    // ── Humanoids ──────────────────────────────────────────────────────────

    /** Kobold fighter — medium, medium strength. Standard hostile humanoid. Carries
     *  a small AP component so its blade still finds gaps in plate. */
    public static Mob koboldFighter(Point pos) {
        Mob m = baseMob(MobType.KOBOLD_FIGHTER,  14, 100, pos, Material.FLESH, Behavior.MOB);
        m.name         = "kobold fighter";
        m.description  = "A small, scaly humanoid wielding a crude blade. It hisses at you with malice.";
        m.intrinsic.size         = 3;
        m.intrinsic.visionRadius = 8;
        m.intrinsic.wakeRadius   = 6;
        m.intrinsic.accuracy     = 11;
        m.intrinsic.evasion      = 8;
        m.intrinsic.damage       = MinMax.of(4);
        m.intrinsic.apDamage     = new MinMax(1, 1);
        m.attackTypes.add(MobType.PLAYER);
        return m;
    }

    /** Barbarian princess — peaceful wanderer who is fearless of terrifying mobs. Uses
     *  {@link Behavior#EXPLORE_HIDE} with empty fleeTypes/attackTypes so she just wanders;
     *  combat memory promotes attackers into her attackTypes if anything strikes her. */
    public static Mob barbarianPrincess(Point pos) {
        Mob m = baseMob(MobType.BARBARIAN_PRINCESS,  18, 180, pos, Material.FLESH,
                Behavior.EXPLORE_HIDE);
        m.name         = "barbarian princess";
        m.description  = "A fierce barbarian princess, fearless and pursuing her own goals";
        m.intrinsic.visionRadius = 8;
        m.intrinsic.wakeRadius   = 6;
        m.intrinsic.accuracy     = 12;
        m.intrinsic.evasion      = 10;
        m.intrinsic.damage       = MinMax.of(4);
        m.intrinsic.armor        = MinMax.of(1);
        m.intrinsic.apDamage     = new MinMax(1, 1);
        m.intrinsic.terrifiable  = false;   // not frightened of anything
        m.stateOfMind  = StateOfMind.AWAKE;  // wander immediately on level load
        return m;
    }

    // ── Blobs (terrifying, omnihostile, share faction "blobs") ─────────────

    /** Blob — slow, large, omnihostile, terrifying. Attacks anything that's not in the
     *  shared "blobs" faction. Buffed to horror-tier in raw stats — same HP, damage,
     *  and armour — but keeps its slow movement so encounters play differently. */
    public static Mob blob(Point pos) {
        Mob m = baseMob(MobType.BLOB,  28, 250, pos, Material.FLESH, Behavior.MOB);
        m.intrinsic.size         = 7;
        m.name         = "blob";
        m.description  = "A large, unpleasant creature. It oozes across the floor, consuming everything in its path.";
        m.intrinsic.visionRadius = 6;
        m.intrinsic.wakeRadius   = 5;
        m.intrinsic.accuracy     = 14;
        m.intrinsic.evasion      = 2;
        m.intrinsic.damage       = MinMax.of(4);
        m.intrinsic.armor        = MinMax.of(2);
        m.intrinsic.terrifying   = true;
        m.intrinsic.terrifiable  = false;
        // Slow regen — half the player's rate. Most mobs don't heal at all; blobs and
        // horrors are the exception so their bulk grinds attrition fights in their favour.
        m.intrinsic.healRate     = GameBalance.PLAYER_HEAL_RATE * 0.5;
        // Hostile to everything except other blobs and kissyblobs (the old "blobs"
        // faction, now expressed by excluding those species from attackTypes).
        addAttackAllExcept(m, MobType.BLOB, MobType.KISSYBLOB);
        return m;
    }

    /** Kissyblob — larger, hungrier blob. Spawns a fresh blob on any kill (50% per the
     *  {@link MobHooks#onKill} hook). Same terrifying/faction profile as the blob. */
    public static Mob kissyblob(Point pos) {
        // Combat stats matched to {@link #blob} so kissyblobs read as the same threat
        // tier — the species' distinguishing feature is its on-kill bud spawn, not its
        // raw fighting numbers.
        Mob m = baseMob(MobType.KISSYBLOB,  28, 250, pos, Material.FLESH, Behavior.MOB);
        m.intrinsic.size           = 7;
        m.name           = "kissyblob";
        m.description    = "A large, revolting, indiscriminately predatory creature, capable of spawning further horrible blobs when fed enough flesh.";
        m.intrinsic.visionRadius   = 6;
        m.intrinsic.wakeRadius     = 5;
        m.intrinsic.accuracy       = 14;
        m.intrinsic.evasion        = 2;
        m.intrinsic.damage         = MinMax.of(4);
        m.intrinsic.armor          = MinMax.of(2);
        m.intrinsic.terrifying     = true;
        m.intrinsic.terrifiable    = false;
        // Slow regen — half player's. Same rationale as the blob.
        m.intrinsic.healRate       = GameBalance.PLAYER_HEAL_RATE * 0.5;
        addAttackAllExcept(m, MobType.BLOB, MobType.KISSYBLOB);
        m.intrinsic.eatSpawnChance = 1.0;     // user-spec: spawns a blob on any kill (no roll)
        m.eatSpawnType   = MobType.BLOB;
        return m;
    }

    // ── Mask imps (escalating severity, last two are terrifying) ───────────

    /** Mask imp — small, weak. Bottom of the imp ladder. */
    public static Mob maskImp(Point pos) {
        Mob m = baseMob(MobType.MASK_IMP,  4, 130, pos, Material.FLESH, Behavior.RANGED_MOB_DUMB);
        m.intrinsic.size            = 2;
        m.name            = "mask imp";
        m.description     = "A small, hostile creature hiding behind a rudimentary mask.";
        m.intrinsic.visionRadius    = 7;
        m.intrinsic.wakeRadius      = 5;
        m.intrinsic.accuracy        = 8;
        m.intrinsic.evasion         = 6;
        m.intrinsic.damage          = MinMax.of(1);
        m.intrinsic.magicResist     = new MinMax(1, 2);
        m.attackTypes.add(MobType.PLAYER);
        // Tiny ranged poke — short range, low damage, attack-cost == melee cost.
        m.intrinsic.rangedDamage     = new MinMax(1, 2);
        m.intrinsic.rangedDistance   = 4;
        m.intrinsic.rangedCost       = m.intrinsic.attackCost;
        m.intrinsic.rangedRateOfFire = 1;
        return m;
    }

    /** Large mask imp — small, slightly tougher than the basic imp. */
    public static Mob largeMaskImp(Point pos) {
        Mob m = baseMob(MobType.LARGE_MASK_IMP,  6, 110, pos, Material.FLESH, Behavior.RANGED_MOB_DUMB);
        m.intrinsic.size            = 3;
        m.name            = "large mask imp";
        m.description     = "An imp that hides its identity behind a grotesque mask.  It's slightly larger and stronger than its smaller kin.   ";
        m.intrinsic.visionRadius    = 7;
        m.intrinsic.wakeRadius      = 5;
        m.intrinsic.accuracy        = 9;
        m.intrinsic.evasion         = 7;
        m.intrinsic.damage           = MinMax.of(2);
        m.attackTypes.add(MobType.PLAYER);
        m.intrinsic.rangedDamage     = new MinMax(1, 3);
        m.intrinsic.rangedDistance   = 5;
        m.intrinsic.rangedCost       = m.intrinsic.attackCost;
        m.intrinsic.rangedRateOfFire = 1;
        return m;
    }

    /** Developed mask imp — medium-sized, mid-tier imp with a stronger ranged shot. */
    public static Mob developedMaskImp(Point pos) {
        Mob m = baseMob(MobType.DEVELOPED_MASK_IMP,  10, 100, pos, Material.FLESH, Behavior.RANGED_MOB_DUMB);
        m.name            = "developed mask imp";
        m.description     = "An imp of unusual size, hiding itself behind a leering mask.";
        m.intrinsic.visionRadius    = 8;
        m.intrinsic.wakeRadius      = 6;
        m.intrinsic.accuracy        = 11;
        m.intrinsic.evasion         = 8;
        m.intrinsic.damage          = MinMax.of(3);
        m.attackTypes.add(MobType.PLAYER);
        m.intrinsic.rangedDamage     = new MinMax(2, 4);
        m.intrinsic.rangedDistance   = 6;
        m.intrinsic.rangedCost       = m.intrinsic.attackCost;
        m.intrinsic.rangedRateOfFire = 1;
        return m;
    }

    /** Horrible mask imp — large, strong fighter, terrifying. Stand-off ranged
     *  behaviour: kites the player while pelting them with magic missiles. */
    public static Mob horribleMaskImp(Point pos) {
        Mob m = baseMob(MobType.HORRIBLE_MASK_IMP,  18, 100, pos, Material.FLESH, Behavior.RANGED_MOB_STANDOFF);
        m.intrinsic.size            = 5;
        m.name            = "horrible mask imp";
        m.description     = "Far, far bigger than imps are supposed to be, you dread to imagine what might be hidden beneath that ornate mask."; 
        m.intrinsic.visionRadius    = 8;
        m.intrinsic.wakeRadius      = 6;
        m.intrinsic.accuracy        = 14;
        m.intrinsic.evasion         = 6;
        m.intrinsic.damage          = MinMax.of(5);
        m.intrinsic.armor           = MinMax.of(2);
        m.intrinsic.terrifying      = true;
        m.intrinsic.terrifiable     = false;
        m.attackTypes.add(MobType.PLAYER);
        // Top of the imp ladder — strongest ranged attack, longest reach.
        m.intrinsic.rangedDamage     = new MinMax(3, 6);
        m.intrinsic.rangedDistance   = 8;
        m.intrinsic.rangedCost       = m.intrinsic.attackCost;
        m.intrinsic.rangedRateOfFire = 1;
        return m;
    }

    // ── Apex (ghost, horror) ───────────────────────────────────────────────

    /** Ghost — flying, medium size, medium strength, good defence. Hostile to the
     *  player; floats over chasm. */
    public static Mob ghost(Point pos) {
        Mob m = baseMob(MobType.GHOST,  10, 150, pos, Material.MAGIC, Behavior.MOB);
        m.name         = "sheet ghost";
        m.description = "A twisted piece of cloth that adopts a malevolent form, ethereal yet all too real.";
        m.intrinsic.flying       = true;
        m.intrinsic.visionRadius = 8;
        m.intrinsic.wakeRadius   = 6;
        m.intrinsic.accuracy     = 11;
        m.intrinsic.evasion      = 18;
        m.intrinsic.damage       = MinMax.of(3);
        m.attackTypes.add(MobType.PLAYER);
        m.doorClosing  = Mob.DoorClosingBehavior.ONLY_IF_WAS_CLOSED;
        return m;
    }

    /** Horror — large, very strong, terrifying. Once every 20 standard turns, if the
     *  player is in line of sight, teleports next to them. */
    public static Mob horror(Point pos) {
        Mob m = baseMob(MobType.HORROR,  28, GameBalance.PLAYER_MOVE_COST,
                pos, Material.FLESH, Behavior.MOB);
        m.intrinsic.size              = 6;
        m.name              = "stalking horror";
        m.description       = "A towering, nightmarish creature of malice and hunger.  Stories say that if you run away it will simply reappear at your side.";   
        m.intrinsic.visionRadius      = 9;
        m.intrinsic.wakeRadius        = 7;
        m.intrinsic.accuracy          = 14;
        m.intrinsic.evasion           = 4;
        m.intrinsic.damage            = MinMax.of(6);
        m.intrinsic.armor             = MinMax.of(2);
        m.intrinsic.terrifying        = true;
        m.intrinsic.terrifiable       = false;
        // Slow regen — half player's. The horror's threat is a slow grind in close
        // quarters; passive healing makes hit-and-run tactics costlier.
        m.intrinsic.healRate          = GameBalance.PLAYER_HEAL_RATE * 0.5;
        addAttackAllExcept(m);   // hostile to everything that isn't another horror.
        m.intrinsic.teleportRate      = 20;
        // Seed the initial teleport cooldown so the horror waits 20 turns before its
        // first jump — matches the legacy MobCooldowns.teleportTurnsLeft = 20 default.
        m.buffs.add(new com.bjsp123.rl2.model.Buff(
                com.bjsp123.rl2.model.Buff.BuffType.TELEPORT_COOLDOWN, 1, 20, m));
        return m;
    }
}
