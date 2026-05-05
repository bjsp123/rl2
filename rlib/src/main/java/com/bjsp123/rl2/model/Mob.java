package com.bjsp123.rl2.model;

/**
 * Every actor in the world — player, NPCs, and (eventually) inanimate scenery — is a Mob.
 *
 * <p>Fields are grouped into four sections so it's easy to see at a glance what's a stat
 * (what the mob IS) vs. what the mob is currently DOING:
 * <ol>
 *   <li><b>Stats</b> — what the mob is: identity, max HP, accuracy/evasion/damage/armor,
 *       attack/move costs, vision/wake radii, base light, AI baseline (behaviour and the
 *       starting attack/flee sets), inventory layout. Mostly immutable per species after
 *       construction; combat memory mutates the attack/flee sets at runtime.</li>
 *   <li><b>Ability flags</b> — species-specific powers: flying, terrifying / terrifiable,
 *       teleport rate, eat-spawn, mushroom-spawn. Off by default.</li>
 *   <li><b>World-interaction modes</b> — properties every mob has but that aren't really
 *       stats: door-closing behaviour, etc.</li>
 *   <li><b>Current state</b> — what the mob is doing right now: position, current HP,
 *       state of mind, status effects (on fire, tame, oily…), turn scheduling, player
 *       progression, plus a transient sub-block of animation timers and scratch fields
 *       that don't need to survive a save/load cycle.</li>
 * </ol>
 */
public class Mob {

    // ════════════════════════════════════════════════════════════════════════
    // ENUMS
    // ════════════════════════════════════════════════════════════════════════

    /** AI archetype that drives a mob's per-turn decisions. */
    public enum Behavior {
        INANIMATE, MOB, PLAYER,
        /**
         * Timid wanderer: picks random destinations while no hostile is in sight. When the
         * mob spots a hostile (currently the player) it flees to the nearest tile the
         * hostile can't see, then sits in {@link StateOfMind#HIDING} for a few turns before
         * resuming random exploration. Used by the mouse.
         */
        EXPLORE_HIDE,
        /**
         * Predator wanderer: explores randomly until it sees a {@code prey}-flagged mob,
         * then paths toward it and attacks on contact (like {@link #MOB} chasing the
         * player, but against prey rather than the player). Used by the cat.
         */
        HUNTER,
        /**
         * "Dumb" ranged variant of {@link #MOB} — same target selection and pathing, but
         * if the mob is in {@link Mob#rangedDistance} and line of sight of its enemy AND
         * not adjacent, it shoots instead of stepping. Otherwise falls through to the
         * normal MOB behaviour (close to melee). Used by the regular mask imps.
         */
        RANGED_MOB_DUMB,
        /**
         * "Stand-off" ranged variant. Tries to stay in range + LOS of its enemy without
         * getting close — kites away if within 2 tiles of the player. Otherwise paths
         * toward LOS-but-not-adjacent. Used by the horrible mask imp.
         */
        RANGED_MOB_STANDOFF
    }

    /** Kind of ranged attack a mob fires. Currently the only option is a magic missile;
     *  future entries (arrow, breath weapon, …) will live here. */
    public enum RangedAttackType {
        MAGIC_MISSILE
    }

    /** Physical substance of a mob (or item) — drives e.g. fire interactions and damage
     *  modifiers. Shared with {@link Item} since both have a material. */
    public enum Material {
        FLESH, STONE, WOOD, METAL, MAGIC
    }

    /** Body size on a 1..10 scale. The player is {@link #PLAYER_SIZE} (4); a mouse is
     *  {@value #SIZE_TINY}, a horror is 6, a blob is 7. Used for several gameplay rules:
     *  <ul>
     *    <li>Sight / light blocking — anything strictly larger than the player blocks
     *        line of sight ({@link #BIG_ENOUGH_TO_BLOCK_SIGHT}).</li>
     *    <li>Oily-coat dripping — mobs at {@link #BIG_ENOUGH_TO_DRIP_OIL} or above drag
     *        an oil residue onto each tile they leave.</li>
     *    <li>Pathfinder swap rules — non-hostile mobs are impassable to a smaller-or-
     *        equal-sized AI mob; a strictly larger AI mob steps onto the smaller mob's
     *        tile and exchanges places with it.</li>
     *  </ul>
     */
    public static final int SIZE_TINY    = 1;   // mouse, kitten, bat, loathesome bug
    public static final int PLAYER_SIZE  = 4;   // player default (mid-scale)
    /** Threshold (inclusive) at which a mob is "big" enough to block sight + light. The
     *  rule is "strictly larger than the player," i.e. anything > {@link #PLAYER_SIZE}. */
    public static final int BIG_ENOUGH_TO_BLOCK_SIGHT = PLAYER_SIZE + 1;
    /** Threshold (inclusive) at which an oily mob drips oil onto each tile it leaves. */
    public static final int BIG_ENOUGH_TO_DRIP_OIL    = 3;

    /** Mental state, mostly used by AI to gate sleeping/hiding/awake/follow behaviour. */
    public enum StateOfMind {
        ASLEEP, AWAKE,
        /**
         * Active flight state for {@link Behavior#EXPLORE_HIDE} mobs: they've spotted a
         * threat and are pathing toward {@link Mob#targetPosition}, a tile they believe
         * the threat can't see them from. Transitions to {@link #HIDING} once they reach
         * cover (or back to {@link #AWAKE} if the threat reappears mid-flight).
         */
        SEEKING_HIDING,
        /**
         * "Hunkered down" state used by {@link Behavior#EXPLORE_HIDE} mobs: they've just
         * reached a tile not visible to any hostile and are waiting a few turns before
         * resuming exploration. 
         */
        HIDING,
        /**
         * Companion state — the mob continually paths toward its {@link Mob#owner}.
         * Combat / flee resolution still pre-empts following (via the standard
         * attitude lookup); FOLLOWING is the fallback target source when nothing
         * else demands attention. Used by kittens (owner = parent cat) and tame
         * mobs (owner = player).
         */
        FOLLOWING
    }

    /**
     * Short-term action label — what this mob has decided to do this turn. Lives
     * one level below {@link StateOfMind}: while a mob is {@link StateOfMind#AWAKE}
     * its intent flips between pursuing / shooting / chasing-last-known / wandering
     * etc. depending on what the AI dispatcher picks each tick. Useful for the
     * look panel ("kobold cleaver: chasing last seen") and as a memory hint —
     * the {@link #CHASING_LAST_KNOWN} promotion needs the previous tick's intent
     * to know it should keep walking toward a remembered tile rather than fall
     * straight back to wander.
     *
     * <p>Set by {@code MobSystem}'s AI dispatchers at every return path; each
     * tick overwrites the previous label. Persisted (non-transient) so save/load
     * preserves the chase-vs-wander distinction.
     */
    public enum Intent {
        /** No movement decided this turn — stationary. */
        IDLE,
        /** Pathing to a randomly chosen exploration tile. */
        WANDERING,
        /** Pathing to the live position of an enemy currently in sight. */
        PURSUING,
        /** Pathing to the last-seen tile of an enemy that has dropped out of
         *  sight. Drops to {@link #WANDERING} on arrival; promotes back to
         *  {@link #PURSUING} if the target reappears. */
        CHASING_LAST_KNOWN,
        /** Stationary ranged attack on a visible target. */
        SHOOTING,
        /** Pathing to a retreat tile to keep range from a too-close enemy. */
        KITING,
        /** Pathing away from a threat. Driven by {@link #fleeTypes} or the
         *  {@link com.bjsp123.rl2.model.Buff.BuffType#FRIGHTENED} buff. */
        FLEEING,
        /** Pathing toward {@link #owner}. */
        FOLLOWING_LEADER
    }

    /** Player class chosen at character creation. Drives starting kit and base stats. */
    public enum CharacterClass {
        WARRIOR("Warrior", "Hardy fighter with sword and scale mail."),
        ROGUE  ("Rogue",   "Quick and evasive, lightly armed."),
        MAGE   ("Mage",    "Fragile, but carries the amulet of light.");

        public final String displayName;
        public final String blurb;

        CharacterClass(String displayName, String blurb) {
            this.displayName = displayName;
            this.blurb       = blurb;
        }
    }

    /**
     * Whether (and under what condition) this mob closes a door behind itself when it
     * steps off a door tile. {@link #ALWAYS} = the player; {@link #ONLY_IF_WAS_CLOSED} =
     * mice/cats/ghosts (they leave a door in the state they found it); {@link #NEVER} =
     * everyone else (default).
     */
    public enum DoorClosingBehavior {
        NEVER, ALWAYS, ONLY_IF_WAS_CLOSED
    }

    /**
     * One support-cast ability — a buff to apply on a friendly mob, or a one-shot
     * heal. The kobold general carries two of these (haste + heal); the generic AI
     * loop in {@code MobSystem.tryCastAbilities} iterates a mob's ability list each
     * turn and casts the first one that's both off-cooldown and has a valid target.
     *
     * <p>For buff abilities ({@link #applies} non-null, {@link #healAmount} == 0):
     * a target is "valid" iff it's a non-self ally within vision and does NOT
     * already have {@code applies}. For heal abilities ({@link #applies} == null,
     * {@link #healAmount} > 0): valid iff non-self ally within vision and below
     * max HP. Cooldowns are tracked by applying a dedicated cooldown
     * {@link Buff.BuffType} to the caster — same pattern as
     * {@link Buff.BuffType#RANGED_COOLDOWN}.
     */
    public static final class MobAbility {
        /** Buff to apply on the target. Null for heal abilities. */
        public Buff.BuffType applies;
        /** Buff level / strength of {@link #applies}. */
        public int appliedLevel;
        /** Buff duration (turns) of {@link #applies}. */
        public int appliedDuration;
        /** Amount of HP to restore. {@code > 0} marks this as a heal ability;
         *  buff abilities use 0. */
        public int healAmount;
        /** Buff type used to track this ability's cooldown on the caster. */
        public Buff.BuffType cooldownTracker;
        /** Cooldown length in turns. */
        public int cooldownTurns;

        public MobAbility() {}

        private MobAbility(Buff.BuffType applies, int level, int duration,
                           int healAmount, Buff.BuffType cooldown, int cooldownTurns) {
            this.applies         = applies;
            this.appliedLevel    = level;
            this.appliedDuration = duration;
            this.healAmount      = healAmount;
            this.cooldownTracker = cooldown;
            this.cooldownTurns   = cooldownTurns;
        }

        public static MobAbility buff(Buff.BuffType applies, int level, int duration,
                                      Buff.BuffType cooldown, int cooldownTurns) {
            return new MobAbility(applies, level, duration, 0, cooldown, cooldownTurns);
        }

        public static MobAbility heal(int amount, Buff.BuffType cooldown, int cooldownTurns) {
            return new MobAbility(null, 0, 0, amount, cooldown, cooldownTurns);
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    // 1. STATS — what the mob is (mostly immutable per species)
    // ════════════════════════════════════════════════════════════════════════

    // ── Identity ────────────────────────────────────────────────────────────
    /** Human-readable species name, e.g. "mouse", "cat", "Warrior". Used by the text log. */
    public String name;
    public String description;
    /** Species identifier — string key matching the {@code type} column of a
     *  row in {@code assets/data/mobs.csv}. Used as the key in
     *  {@link #attackTypes} / {@link #fleeTypes} and in spawn-cross-reference
     *  fields. */
    public String mobType;
    public Material material;
    // (was: size — moved to {@link #intrinsic}.)
    /** Player-class for the player; null for everything else. Drives
     *  player-only renderer + starting-kit branches. */
    public CharacterClass characterClass;

    // ── Combat ceiling ──────────────────────────────────────────────────────
    // (was: maxHp, healRate, accuracy, evasion, damage, armor, apDamageMin/Max,
    //  magicResistMin/Max, attackCost, moveCost — all moved to {@link #intrinsic}.)

    // ── Ranged attack ───────────────────────────────────────────────────────
    // (was: rangedDamageMin/Max, rangedRateOfFire, rangedCost, rangedDistance — all
    //  moved to {@link #intrinsic} so future "ring of accuracy" / "ring of range +2"
    //  items plug into the same StatBlock pipeline as melee gear.)
    /** Kind of projectile fired. Defaulted to MAGIC_MISSILE because that's the only
     *  option today; future variants (arrow, breath, …) plug in here. Categorical, not
     *  a stat — stays on Mob. */
    public RangedAttackType rangedAttackType = RangedAttackType.MAGIC_MISSILE;

    // ── Perception + lighting ───────────────────────────────────────────────
    // (was: visionRadius, wakeRadius, baseLightRadius — moved to {@link #intrinsic}
    //  so an amulet of light or future buff of true sight composes through StatBlock.)

    // ── AI baseline ─────────────────────────────────────────────────────────
    public Behavior behavior;
    /** Mob types this one wants to attack. Populated from species defaults at spawn and
     *  mutated by combat memory at runtime — once two mobs have fought, both add each
     *  other's mob-type string here. Omnihostile mobs (blob/kissyblob/horror) seed this
     *  with every species except their allies via the {@code attackAllExcept} CSV
     *  shorthand. Strings, not enums — the canonical roster lives in
     *  {@code assets/data/mobs.csv}. */
    public java.util.Set<String> attackTypes = new java.util.HashSet<>();
    /** Mob types this one wants to flee. Flee targets take priority over attack targets
     *  in AI target selection. Combat memory removes a type from this set once a fight
     *  breaks out — "if A and B have fought before, the answer is always ATTACK." */
    public java.util.Set<String> fleeTypes = new java.util.HashSet<>();
    /** Faction tag (arbitrary string). Two mobs with the same non-null {@code faction}
     *  are allies — wins over {@link #attackTypes} / {@link #fleeTypes} at attitude
     *  resolution time, and drives ally-defense transitivity (this mob attacks anyone
     *  hostile to anything sharing its faction). Null for lone-wolf species. */
    public String faction;
    /** Faction tags this mob is hostile to. A mob whose {@link #faction} matches
     *  any entry resolves to ATTACK in attitude lookups — symmetric with the
     *  shared-faction-is-ally rule. The player carries the {@code PLAYER}
     *  faction tag so any species with {@code PLAYER} in this set is hostile to
     *  the player. */
    public java.util.Set<String> enemyFactions = new java.util.HashSet<>();
    /** Support-cast abilities (haste/heal/etc.) tried each turn before the mob's
     *  normal AI dispatch. See {@link MobAbility} and
     *  {@code MobSystem.tryCastAbilities}. Empty for ordinary mobs. */
    public java.util.List<MobAbility> abilities = new java.util.ArrayList<>();

    // ── Inventory ───────────────────────────────────────────────────────────
    public Inventory inventory = new Inventory();

    // ── Stat block ──────────────────────────────────────────────────────────
    /** Species-and-level-independent base stats — what the mob "is" before items, level
     *  bonuses, or buffs apply. Populated by {@code MobFactory}. Read sites that want the
     *  raw mob without any modifiers should consult this directly; combat/AI sites should
     *  use {@link #effectiveStats()} instead. */
    public StatBlock intrinsic = new StatBlock();
    /** Cached fully-rolled-up effective stats — refreshed lazily by
     *  {@link com.bjsp123.rl2.logic.MobSystem#writeEffectiveStats} when {@link #statsDirty}
     *  is set. Direct field access on this block is safe; callers should still go through
     *  {@link #effectiveStats()} so the cache stays warm across reads. */
    public transient StatBlock effective = new StatBlock();
    /** Set whenever a stat input mutates: equip/unequip, buff apply/remove, character
     *  level-up, intrinsic field change. {@link #effectiveStats()} consumes + clears it. */
    public transient boolean statsDirty = true;

    /** Recomputes {@link #effective} on every call and returns it. The returned block is
     *  the same object every call — callers must not mutate.
     *
     *  <p>The {@link #statsDirty} flag is currently unused; lazy refresh based on it is a
     *  planned optimization but requires wiring every stat-input mutator (Inventory
     *  equip/unequip, BuffSystem apply/remove, direct field tweaks like level-up HP
     *  bumps) to flip the flag. Until those triggers are in place, recomputing each
     *  call is correct and cheap (writeEffectiveStats is a small fixed amount of work). */
    public StatBlock effectiveStats() {
        com.bjsp123.rl2.logic.MobSystem.writeEffectiveStats(this, effective);
        return effective;
    }


    // ════════════════════════════════════════════════════════════════════════
    // 2. ABILITY FLAGS — species-specific powers; default values are "off"
    // ════════════════════════════════════════════════════════════════════════

    // (was: flying, fireImmune — moved to {@link #intrinsic} so they compose with
    //  buff- and item-granted variants like LEVITATING.)

    // (was: fireSpreadOnAttack, fireExplosionRadiusOnDeath, terrifying, terrifiable,
    //  teleportRate, eatSpawnChance, mushroomEatSpawnChance — all moved to
    //  {@link #intrinsic}.)

    /** What species to spawn when {@link com.bjsp123.rl2.model.StatBlock#eatSpawnChance}
     *  fires. Null disables. Mob-type string ref, not a stat. */
    public String eatSpawnType;

    /** What species to spawn from {@link com.bjsp123.rl2.model.StatBlock#mushroomEatSpawnChance}.
     *  Mob-type string ref, not a stat. */
    public String mushroomEatSpawnType;

    /** What species to spawn each standard turn when
     *  {@link com.bjsp123.rl2.model.StatBlock#turnSpawnChance} fires. Used by ant hills.
     *  Mob-type string ref, not a stat. */
    public String turnSpawnType;

    // ── CSV-driven species flags ────────────────────────────────────────────
    /** Mob-type string of the offspring this species spawns alongside itself at
     *  level-population time (cats spawn kittens). The level populator iterates
     *  mobs whose {@code kittenType != null}, so any species opting in just sets
     *  the column. */
    public String kittenType;
    /** When {@code true}, this mob can be one-shot by the wand-of-banishment. */
    public boolean banishable;

    // ════════════════════════════════════════════════════════════════════════
    // 3. WORLD-INTERACTION MODES — universal but not really "stats"
    // ════════════════════════════════════════════════════════════════════════

    public DoorClosingBehavior doorClosing = DoorClosingBehavior.NEVER;

    // ════════════════════════════════════════════════════════════════════════
    // 4. CURRENT STATE — what the mob is doing right now
    // ════════════════════════════════════════════════════════════════════════

    // ── Location ────────────────────────────────────────────────────────────
    public Point position;
    public Point targetPosition;
    /** True when the mob's last horizontal step was east. Persists through purely vertical
     *  steps so directional sprites don't flip on every N/S movement. Logical state
     *  (no rendering knowledge) — the renderer reads this for sprite mirroring. */
    public boolean facingEast;

    // ── Vital signs ─────────────────────────────────────────────────────────
    public double hp;
    /** Default ASLEEP — most species sleep in until something they care about wanders
     *  into their wakeRadius. Factories override (e.g. princess starts AWAKE, kitten
     *  starts FOLLOWING) when a different starting state is needed. */
    public StateOfMind stateOfMind = StateOfMind.ASLEEP;
    /** Per-turn micro-decision label. See {@link Intent}. Set by the AI dispatchers
     *  on every tick; default {@link Intent#IDLE} for spawn / ASLEEP / freshly-loaded
     *  mobs. */
    public Intent intent = Intent.IDLE;

    // ── Status effects ──────────────────────────────────────────────────────
    /** Active buffs and debuffs. Managed exclusively by
     *  {@link com.bjsp123.rl2.logic.BuffSystem} — read it from there rather than mutating
     *  this list directly. Each {@link Buff} carries its type, level, duration, and the
     *  mob that applied it (for attribution / death messages). */
    public java.util.List<Buff> buffs = new java.util.ArrayList<>();
    /** The specific mob this one is loyal to ({@code null} = wild). An owned mob:
     *  <ul>
     *    <li>Follows the owner — paths toward them whenever no combat/flee target is
     *        closer (see {@link com.bjsp123.rl2.logic.MobSystem}'s leader-follow path).</li>
     *    <li>Inherits the owner's hostility — anything in {@code owner.attackTypes} (or
     *        anyone the owner is otherwise actively attacking via combat memory) is
     *        treated as ATTACK by this mob too. Implemented dynamically in
     *        {@link com.bjsp123.rl2.logic.MobSystem#getAttitudeToMob}, so the loyalty
     *        tracks combat memory live without needing a parallel sync.</li>
     *    <li>Counts as ALLY both ways with the owner, and ALLY with any other mob
     *        that shares the same owner (so litter-mates don't fight each other).</li>
     *  </ul>
     *  Two distinct use cases share the field: tame mobs (player as owner — wand-of-
     *  dog summons, future taming) and kittens (their parent cat as owner, wired up
     *  by {@code LevelFactoryPopulate.placeKittens}).
     *
     *  <p>Transient — owner references aren't persisted (re-link on load via a future
     *  rebind hook; players who load mid-run lose their pets, which is acceptable for
     *  now). */
    public transient Mob owner;

    // ── Turn scheduling + counters ──────────────────────────────────────────
    public int ticksTillMove;
    // Cooldowns moved to the buff system: TELEPORT_COOLDOWN, RANGED_COOLDOWN, HIDING.
    // BuffSystem.tickPerTurn drains durations on the standard-turn cadence.
    /** Fullness counter. Ticks down on the standard-turn cadence. When it reaches 0 the
     *  player starves — see {@link com.bjsp123.rl2.logic.TurnSystem}. NPCs sit at 0
     *  harmlessly. */
    public int satiety = com.bjsp123.rl2.logic.GameBalance.STARTING_SATIETY;

    // ── Player progression (mutable; player-only in practice) ───────────────
    public int score;
    /** Cumulative XP. See {@link com.bjsp123.rl2.logic.MobProgression} for the level-up
     *  schedule. */
    public int xp;
    public int characterLevel = 1;
    /** Unspent perk points awarded on level-up. */
    public int perkPoints;

    // ── Per-level scaling deltas (set by MobDefinition.apply from mobs.csv) ─
    // Applied by MobProgression once per character level — both at spawn time
    // (depth-based pre-roll) and on XP-driven level-up.
    public int    hpPerLevel             = 2;
    public int    accuracyPerLevel       = 1;
    public int    evasionPerLevel        = 1;
    public MinMax damagePerLevel         = new MinMax(1, 2);
    public MinMax apPerLevel             = MinMax.ZERO;
    public MinMax rangedDamagePerLevel   = MinMax.ZERO;
    public int    rangedDistancePerLevel = 0;
    public MinMax armorPerLevel          = new MinMax(0, 1);
    /** Player's perk levels — perk → level (≥1 = taken). Absence means perk not taken.
     *  Each entry consumed one perk point at acquisition. */
    public java.util.EnumMap<Perk, Integer> perks = new java.util.EnumMap<>(Perk.class);
    /** Lifetime history — kills, level-ups, item finds — read by the character stats
     *  frame's History tab. */
    public java.util.List<HistoricalRecord> history = new java.util.ArrayList<>();

    // ── Transient runtime — not part of the save format ─────────────────────
    /** True iff the door tile the mob currently stands on was {@link Tile#DOOR} (closed)
     *  at the moment of entry. Read by {@link DoorClosingBehavior#ONLY_IF_WAS_CLOSED}
     *  mobs on the way out so they restore the door's prior state. */
    public transient boolean lastDoorWasClosed;

    // (was: stepFromDx/Dy, stepFrame, stepTotal, animPeakX/Y, animFrame, animPeakFrame,
    //  animEndFrame, deathFrame, plus the death-animation constants — all moved to
    //  {@link #render}.)

    // (was: dying flag — deleted. killMob removes from level.mobs synchronously; the
    //  rgame Animator owns the flicker / fade against a snapshot.)

    // ════════════════════════════════════════════════════════════════════════
    // CONSTRUCTORS + METHODS
    // ════════════════════════════════════════════════════════════════════════

    /** No-arg constructor. {@link com.bjsp123.rl2.logic.MobFactory} sets each field it
     *  needs; defaults live alongside their declarations above. */
    public Mob() {}

    /**
     * Effective light radius right now: the larger of {@link #baseLightRadius} and any
     * equipped amulet's contribution. Computed on demand so the value can never go stale
     * when equipment changes — there's no cache to refresh. Lighting code calls this once
     * per cell per frame, which is cheap (one Math.max, one null check).
     */
    public double lightRadius() {
        return effectiveStats().lightRadius;
    }
}
