package com.bjsp123.rl2.logic;

import com.bjsp123.rl2.model.Item;
import com.bjsp123.rl2.model.Buff;
import com.bjsp123.rl2.model.Inventory;
import com.bjsp123.rl2.model.Level;
import com.bjsp123.rl2.model.MinMax;
import com.bjsp123.rl2.model.Mob.Material;
import com.bjsp123.rl2.model.Mob;
import com.bjsp123.rl2.model.Mob.Behavior;
import com.bjsp123.rl2.model.Point;
import com.bjsp123.rl2.model.Mob.StateOfMind;
import com.bjsp123.rl2.model.Level.Surface;
import com.bjsp123.rl2.model.Item.ItemEffect;
import com.bjsp123.rl2.model.Tile;
import com.bjsp123.rl2.model.Level.Vegetation;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;
import java.util.Random;

public class MobSystem {

    /** What mob A wants to do with mob B when they meet. Drives target selection (flee
     *  targets are preferred over attack targets) and collision behaviour (ATTACK -> strike,
     *  FLEE / NOTHING / ALLY -> swap positions). {@code ALLY} is the "we're on the same
     *  side, don't fight, but we're not strangers" answer - used by collision-swap rules
     *  and by code that wants a quick "is this mob friendly?" check. */
    public enum Attitude {
        NOTHING, FLEE, ATTACK, ALLY
    }

    /**
     * Cardinal facing for a mob. Used by renderers that pick a directional sprite per
     * frame; non-directional sprites ignore it. A mob updates its facing when it takes a
     * step that has a non-zero delta in x or y (diagonals pick the axis with the larger
     * delta - see {@link #fromDelta}).
     */
    public enum Direction {
        NORTH, SOUTH, EAST, WEST;

        /** Choose a facing from a step delta. (0, 0) leaves the caller to keep its current
         *  facing. */
        public static Direction fromDelta(int dx, int dy, Direction fallback) {
            if (dx == 0 && dy == 0) return fallback;
            if (Math.abs(dx) >= Math.abs(dy)) return dx > 0 ? EAST : WEST;
            return dy > 0 ? NORTH : SOUTH;
        }
    }

    /** Mechanism through which damage reached a mob. Passed to {@link MobCombat#processAttack}. */
    public enum AttackType {
        /** Hand-to-hand / weapon strike at adjacent range. */
        MELEE,
        /** Innate single-target projectile attack. */
        RANGED,
        /** Item hurled at a target tile. */
        THROWN,
        /** Ranged magical effect (e.g. magic missile). */
        MAGIC,
        /** No attacker - pit trap, drowning, etc. */
        ENVIRONMENTAL
    }

    /** Elemental class of damage. Routes mitigation: {@link Buff.BuffType#PROTECTION}
     *  resists {@link #PHYSICAL}; {@link Buff.BuffType#ANTI_MAGIC} resists
     *  {@link #MAGIC} and {@link #FIRE}; {@link #POISON} and {@link #SHOCK} are
     *  unmitigated by buffs. Independent of {@link AttackType}
     *  (mechanism) - a fire bomb's impact damage is THROWN/PHYSICAL while its DOT is
     *  ENVIRONMENTAL/FIRE. */
    public enum DamageElement {
        PHYSICAL, MAGIC, POISON, FIRE, SHOCK, COLD
    }

    /** True if {@code m} is "wet" - carries the WET buff or stands on a water /
     *  ice tile. Wetness conducts lightning (x2) and aggravates cold (x4, RL-31),
     *  applied centrally in {@link MobCombat#processAttack}; also gates the chilled+wet
     *  freeze in {@code BuffSystem.maybeFreeze}. */
    public static boolean isWet(Level level, Mob m) {
        if (level == null || m == null || m.position == null) return false;
        if (BuffSystem.hasBuff(m, com.bjsp123.rl2.model.Buff.BuffType.WET)) return true;
        int x = m.position.tileX(), y = m.position.tileY();
        if (x < 0 || y < 0 || x >= level.width || y >= level.height) return false;
        Level.Surface s = level.surface[x][y];
        return s == Level.Surface.WATER || s == Level.Surface.ICE;
    }

    /** Mutable accumulator for the per-attack tuning log line. Callers populate it
     *  with their stat-based mitigations (armor, magicResist) before calling
     *  {@link MobCombat#processAttack}; processAttack appends buff mitigations
     *  (PROTECTION / ANTI_MAGIC) and emits one LOW-priority log entry. */
    public static final class DamageBreakdown {
        public final DamageElement element;
        /** The dice-rolled damage before <em>any</em> mitigation. */
        public final int rolled;
        /** Ordered list of "label: -N" deductions, in the order they were applied. */
        public final java.util.List<String> mitigations = new java.util.ArrayList<>();
        /** Knockback distance applied alongside this damage roll. Drives the
         *  ", knocking the {target} back N" suffix on the damageRoll log line.
         *  Zero = no annotation. Set by melee callers in {@link MobCombat#attack}
         *  before {@link MobCombat#processAttack} runs. */
        public int kbSquares = 0;
        /** Mechanism of the attack. Drives the damageRoll log line's voice:
         *  MELEE → "X hits Y for N damage"; RANGED/THROWN/MAGIC → "X's <item>
         *  does N damage to Y"; ENVIRONMENTAL (no attacker) → "Y takes N
         *  damage" (passive). Defaults to null; {@link MobCombat#processAttack} fills
         *  it from its own {@code type} parameter so non-melee callers don't
         *  have to remember to set it. */
        public AttackType type;
        /** Causal chain, used to pull the originating item name for the
         *  "X's <item> does N damage" form on ranged/thrown/magic hits.
         *  Defaults to null; {@link MobCombat#processAttack} fills it from the cause
         *  it computed (synthesised or caller-supplied). */
        public DamageCause cause;

        public DamageBreakdown(DamageElement element, int rolled) {
            this.element = element;
            this.rolled  = rolled;
        }

        /** Append a mitigation entry. Zero/negative amounts are dropped. */
        public DamageBreakdown add(String label, int amount) {
            if (amount > 0) mitigations.add(label + " -" + amount);
            return this;
        }
    }

    /** Causal chain for a single damage event. {@code origin} is the root
     *  attacker (the mob whose action ultimately caused this damage even if
     *  the damage is being applied indirectly via a fire DOT, a wall-slam,
     *  etc.). {@code originItem} is the wand / bomb / weapon that *originated*
     *  the chain (the fire wand that lit the fire, the blast bomb that
     *  knocked the victim into a wall). {@code medium} names the indirect
     *  mechanism: {@code "blow"}, {@code "wall-slam"}, {@code "fall"},
     *  {@code "fire-dot"}, {@code "poison-dot"}, {@code "burst"}, etc.
     *
     *  <p>For direct hits the cause is just {@code (attacker,
     *  attacker.equippedWeapon, "blow")} — equivalent to passing null, since
     *  {@link MobCombat#processAttack(Level, Mob, Mob, int, AttackType, DamageElement,
     *  DamageBreakdown, DamageCause)} fills that default when {@code cause}
     *  is null. Indirect damage paths construct a {@code DamageCause} at
     *  the originating site (fire-tile damage, knockback wall-slam, chasm
     *  fall) so the death screen + log messages can name the root cause. */
    public record DamageCause(Mob origin, Item originItem, String medium) {
        /** Cause for an environmental hit with no attribution
         *  (e.g. chasm fall, ambient fire that's been burning since level
         *  gen). */
        public static final DamageCause NONE = new DamageCause(null, null, null);
    }

    /** Most recent {@link DamageCause} that landed a damaging blow on a
     *  PLAYER-behaviour mob. Updated inside {@link MobCombat#processAttack} whenever
     *  {@code target.behavior == PLAYER && dealt > 0}. Read by the
     *  death-screen path on PlayerScreen to surface the cause as the death
     *  headline. {@code element} and {@code dealt} are stashed alongside so
     *  the headline can phrase the verb ("burned", "shoved", "bled out")
     *  without re-walking the log. Cleared by {@link #resetLastPlayerHit}
     *  when a new run begins. */
    static volatile DamageCause lastPlayerCause;
    static volatile DamageElement lastPlayerElement;
    static volatile int lastPlayerHitDealt;

    public static DamageCause lastPlayerCause()        { return lastPlayerCause; }
    public static DamageElement lastPlayerElement()    { return lastPlayerElement; }
    public static int lastPlayerHitDealt()             { return lastPlayerHitDealt; }
    public static void resetLastPlayerHit() {
        lastPlayerCause = null;
        lastPlayerElement = null;
        lastPlayerHitDealt = 0;
    }

    static final Random RANDOM =
            com.bjsp123.rl2.util.SimRng.register("MobSystem", new Random());

    /** Stacks (= turns) the {@link com.bjsp123.rl2.model.Buff.BuffType#OILY} buff lasts
     *  when a mob steps onto an OIL surface. Stacks count down 1/turn - see
     *  {@link BuffSystem#tickPerTurn}. (Clamped to the OILY stack cap on apply.) */
    public static final int OIL_STEP_BUFF_TURNS = 3;

    /** Stacks the {@link com.bjsp123.rl2.model.Buff.BuffType#WET} buff gets when a mob
     *  treads a WATER surface - 2 (matches the WET stack cap). */
    public static final int WATER_STEP_BUFF_TURNS = 2;

    /**
     * What mob {@code a} wants to do about mob {@code b}: ATTACK (chase & hit), FLEE (move
     * away), ALLY (won't fight, can swap places), or NOTHING (ignore). Purely one-sided -
     * a dog's attitude toward a cat can be ATTACK while the cat's attitude toward the dog
     * is FLEE. Callers must evaluate both directions when deciding collision behaviour.
     *
     * <p>Resolution order:
     * <ol>
     *   <li>self / null -> NOTHING</li>
     *   <li>owner-based ALLY - pet <-> master, two pets sharing a master</li>
     *   <li>FRIGHTENED buff -> FLEE everything (player exempt)</li>
     *   <li>shared {@link Mob#faction} tag -> ALLY</li>
     *   <li>explicit {@link Mob#fleeTypes} membership -> FLEE (priority over ATTACK)</li>
     *   <li>explicit {@link Mob#attackTypes} membership -> ATTACK</li>
     *   <li>owner-inherited hostility - pet attacks what its master attacks</li>
     *   <li>ally-defense transitivity -> ATTACK anyone hostile to {@code a} or any
     *       mob in {@code a}'s faction ({@link #defendsAlly})</li>
     *   <li>otherwise NOTHING</li>
     * </ol>
     *
     * <p>Same-{@link Behavior} no longer implies ALLY - alliance is purely the
     * shared-faction-tag relationship, with lone-wolf species carrying a null
     * faction.
     */
    /** Shorthand for {@code getAttitudeToMob(a, b) == Attitude.ALLY}. Use
     *  this for the very common "is this friendly fire?" check so call sites
     *  don't re-import the Attitude enum or hand-write the same comparison. */
    public static boolean isAlly(Mob a, Mob b) {
        return getAttitudeToMob(a, b) == Attitude.ALLY;
    }

    public static Attitude getAttitudeToMob(Mob a, Mob b) {
        if (a == null || b == null || a == b) return Attitude.NOTHING;
        // Owner / owned-pet shortcuts. A tame dog should never be treated as
        // hostile by its master, and two pets sharing a master are allies.
        if (a.owner == b || b.owner == a)            return Attitude.ALLY;
        if (a.owner != null && a.owner == b.owner)   return Attitude.ALLY;
        // Frightened mobs flee everything. Player exempt: their intent comes from
        // user input, not AI; the buff is cosmetic on the player side.
        if (!a.isPlayer
                && BuffSystem.hasBuff(a, com.bjsp123.rl2.model.Buff.BuffType.FRIGHTENED)) {
            return Attitude.FLEE;
        }
        // Shared faction wins over attack/flee lists, so a kobold general healing
        // a kobold he's previously fought (combat-memoried into his attackTypes
        // by some weird chain) still reads as an ally.
        if (a.faction != null && a.faction.equals(b.faction)) return Attitude.ALLY;
        // Symmetric rule: declaring b's faction as an enemy resolves to ATTACK
        // without needing every member of that faction in attackTypes. Powers
        // the player-hostility relationship via the PLAYER faction.
        if (b.faction != null && a.enemyFactions.contains(b.faction)) return Attitude.ATTACK;
        if (a.fleeTypes   != null && b.mobType != null && a.fleeTypes  .contains(b.mobType)) return Attitude.FLEE;
        if (a.attackTypes != null && b.mobType != null && a.attackTypes.contains(b.mobType)) return Attitude.ATTACK;
        // Loyalty - a tame mob inherits its owner's hostilities. If the owner wants to
        // attack b's species (either by spec or via combat memory) or b's faction, the pet does too.
        if (a.owner != null && a.owner != b
                && b.mobType != null && a.owner.attackTypes != null
                && a.owner.attackTypes.contains(b.mobType)) {
            return Attitude.ATTACK;
        }
        if (a.owner != null && a.owner != b
                && b.faction != null && a.owner.enemyFactions.contains(b.faction)) {
            return Attitude.ATTACK;
        }
        // Ally-defense transitivity. {@code a} is hostile to anyone whose
        // attackTypes lists a's own species, any species sharing a's faction,
        // or anyone who has declared a's faction an enemy.
        if (defendsAlly(a, b)) return Attitude.ATTACK;
        return Attitude.NOTHING;
    }

    /** True iff {@code b} would treat {@code a} as a hostile target - covers
     *  {@code b.attackTypes} containing {@code a}'s species or any species
     *  sharing {@code a}'s {@link Mob#faction}, and {@code b.enemyFactions}
     *  including {@code a}'s faction. Powers the ally-defense rule in
     *  {@link #getAttitudeToMob}. */
    private static boolean defendsAlly(Mob a, Mob b) {
        if (b == null) return false;
        if (a.faction != null && b.enemyFactions.contains(a.faction)) return true;
        if (b.attackTypes == null || b.attackTypes.isEmpty()) return false;
        if (a.mobType != null && b.attackTypes.contains(a.mobType)) return true;
        if (a.faction != null) {
            for (String t : Registries.mobsInFaction(a.faction)) {
                if (b.attackTypes.contains(t)) return true;
            }
        }
        return false;
    }

    /**
     * Record that {@code a} and {@code b} have been in combat - bidirectional. Each side
     * learns to attack the other's species on sight, and drops any fear of it (combat
     * overrides flee). Attack/flee identity is keyed on {@link com.bjsp123.rl2.model.Mob.MobType}
     * so behavior code never has to consult the glyph.
     */
    public static void recordCombatMemory(Level level, Mob a, Mob b, String reason) {
        if (a == null || b == null || a == b) return;
        if (a.mobType == null || b.mobType == null) return;
        if (a.attackTypes == null) a.attackTypes = new java.util.HashSet<>();
        if (a.fleeTypes   == null) a.fleeTypes   = new java.util.HashSet<>();
        if (b.attackTypes == null) b.attackTypes = new java.util.HashSet<>();
        if (b.fleeTypes   == null) b.fleeTypes   = new java.util.HashSet<>();
        // Capture the prior attitude BEFORE we mutate attackTypes so the
        // "becomes hostile" log only fires when the relationship actually
        // changes. A roach whose CSV enemyFactions includes PLAYER was
        // already going to attack; adding ROACH -> WARRIOR to the player's
        // attackTypes set is bookkeeping, not news.
        Attitude aPriorAttitude = getAttitudeToMob(a, b);
        Attitude bPriorAttitude = getAttitudeToMob(b, a);
        boolean aLearned = a.attackTypes.add(b.mobType);
        a.fleeTypes.remove(b.mobType);
        boolean bLearned = b.attackTypes.add(a.mobType);
        b.fleeTypes.remove(a.mobType);

        boolean aBecameHostile = aLearned && aPriorAttitude != Attitude.ATTACK;
        boolean bBecameHostile = bLearned && bPriorAttitude != Attitude.ATTACK;
        if (!aBecameHostile && !bBecameHostile) return;
        boolean aPlayer = a.isPlayer;
        boolean bPlayer = b.isPlayer;
        String aName = nameForLog(level, a);
        String bName = nameForLog(level, b);
        if (!aPlayer && bPlayer && aBecameHostile) {
            EventLog.add(Messages.attitudeTurnsOnPlayer(aName, reason));
        } else if (!bPlayer && aPlayer && bBecameHostile) {
            EventLog.add(Messages.attitudeTurnsOnPlayer(bName, reason));
        } else if (!aPlayer && !bPlayer) {
            if (aBecameHostile) EventLog.add(Messages.attitudeMobOnMob(aName, bName, reason));
            else                EventLog.add(Messages.attitudeMobOnMob(bName, aName, reason));
        }

        // Kin propagation: same-species mobs within their own wake radius of
        // the attacked one also learn to attack the player. A pack of mice
        // turns en masse when one of them is hit, even though they're all
        // factionless individuals. Only fires when the attacker is the
        // player and the victim genuinely became hostile (so we don't
        // re-log for mobs that were already attacking via faction rules).
        if (aPlayer && bBecameHostile) propagateHostilityToKin(level, a, b, reason);
        if (bPlayer && aBecameHostile) propagateHostilityToKin(level, b, a, reason);
    }

    /** Spread the {@code attacker -> attackTypes} entry to every same-species
     *  mob within its own {@code wakeRadius} of the original {@code victim}.
     *  Each newly-hostile kin emits a "becomes hostile" log line, gated by
     *  the same prior-attitude check as {@link #recordCombatMemory} so kin
     *  that were already attacking (via faction or earlier combat memory)
     *  don't re-log. The flee set is also cleared on each affected mob so
     *  a "fled in fright" entry doesn't override the new hostility. */
    private static void propagateHostilityToKin(Level level, Mob attacker, Mob victim, String reason) {
        if (level == null || level.mobs == null) return;
        if (attacker == null || victim == null) return;
        if (attacker.mobType == null || victim.mobType == null) return;
        if (victim.position == null) return;
        int ox = victim.position.tileX(), oy = victim.position.tileY();
        for (Mob m : level.mobs) {
            if (m == attacker || m == victim) continue;
            if (m.mobType == null || !m.mobType.equals(victim.mobType)) continue;
            if (m.position == null || m.hp <= 0) continue;
            int d = Math.max(Math.abs(m.position.tileX() - ox),
                             Math.abs(m.position.tileY() - oy));
            int range = (int) Math.max(1.0, m.effectiveStats().wakeRadius);
            if (d > range) continue;
            if (m.attackTypes == null) m.attackTypes = new java.util.HashSet<>();
            if (m.fleeTypes   == null) m.fleeTypes   = new java.util.HashSet<>();
            Attitude prior = getAttitudeToMob(m, attacker);
            boolean learned = m.attackTypes.add(attacker.mobType);
            m.fleeTypes.remove(attacker.mobType);
            if (learned && prior != Attitude.ATTACK) {
                EventLog.add(Messages.attitudeTurnsOnPlayer(
                        nameForLog(level, m), reason));
            }
        }
    }

    /** Walk the projectile path from {@code from} to {@code to} and return
     *  the position of the first obstacle in the way: a mob (other than
     *  {@code shooter}), or a movement-blocking tile (wall, statue,
     *  altar, throne, lamp). The shooter's own tile is always skipped so
     *  a caster doesn't block their own missile. Used by both wand fire
     *  and item throw so a projectile aimed beyond an obstacle clips to
     *  that obstacle and resolves its effect there - bombs detonate
     *  against walls / statues, magic missiles strike the first body in
     *  the line, etc. Returns {@code to} when the trajectory is clear. */
    public static Point firstMobBlocking(Level level, Point from, Point to, Mob shooter) {
        return MobVisibility.firstMobBlocking(level, from, to, shooter);
    }

    /** True iff a projectile from {@code shooter} aimed at {@code to} actually
     *  lands on {@code to} - i.e. no wall, closed door (incl. CRYSTAL_DOOR),
     *  statue, or intervening mob clips the line first. Used by the basic
     *  ranged-attack AI to gate firing; the SMART-AI throw / wand paths use
     *  this indirectly via their {@code *WillLandUsefully} eval calls. */
    public static boolean projectileLineReaches(Level level, Point from, Point to, Mob shooter) {
        return MobVisibility.projectileLineReaches(level, from, to, shooter);
    }

    /** True iff a ground charge from {@code from} to {@code to} crosses no
     *  movement-blocking tile (wall, closed door, statue, beacon, gem hearth, ...)
     *  strictly between the endpoints. Unlike a JUMP (which blinks over obstacles)
     *  or a projectile (which flies over chasms), a CHARGE runs along the floor and
     *  is stopped by any impassable square in its path. The endpoints themselves
     *  are not tested - the target stands on its own tile and the charger on its.
     *  Bresenham-walks the segment via {@link com.bjsp123.rl2.model.Tile#blocksMovement()}. */
    public static boolean chargePathClear(Level level, Point from, Point to) {
        if (level == null || from == null || to == null) return false;
        int x0 = from.tileX(), y0 = from.tileY();
        int x1 = to.tileX(),   y1 = to.tileY();
        int dx = Math.abs(x1 - x0), dy = Math.abs(y1 - y0);
        int sx = x0 < x1 ? 1 : -1;
        int sy = y0 < y1 ? 1 : -1;
        int err = dx - dy;
        int x = x0, y = y0;
        while (true) {
            int e2 = err << 1;
            if (e2 > -dy) { err -= dy; x += sx; }
            if (e2 <  dx) { err += dx; y += sy; }
            if (x == x1 && y == y1) return true;            // reached the target tile
            if (x < 0 || y < 0 || x >= level.width || y >= level.height) return false;
            if (level.tiles[x][y].blocksMovement()) return false;
        }
    }

    /** Queue a deferred impact resolution onto the level. The {@code resolve}
     *  Runnable should invoke the matching {@code apply*Impact} method (which
     *  in turn decrements {@link Level#pendingImpactCount}). Used by every
     *  animation-gated action (throws, wand fires, knockback chains, animated
     *  tool uses) to defer step 4 of the lifecycle until the visual lands.
     *
     *  <p>On-screen: the rgame Animator pops these into its PendingImpactQueue
     *  at consume() time and fires them when the matching projectile arc
     *  completes. Headless: {@link #drainPendingImpactsImmediate} is called
     *  by the AI loop between mob brains and fires the queued resolves
     *  synchronously. */
    public static void queuePendingImpact(Level level, Runnable resolve) {
        if (level == null || resolve == null) return;
        if (level.pendingImpacts == null) {
            level.pendingImpacts = new java.util.ArrayDeque<>();
        }
        level.pendingImpacts.add(resolve);
        level.pendingImpactCount++;
    }

    /** Fire all queued pending-impact resolves synchronously, in FIFO order.
     *  Called between mob brains in headless mode (and by the rgame Animator
     *  if it ever wants to force-resolve everything immediately, e.g. on
     *  level transition). On-screen, the Animator normally pulls resolves
     *  out of the queue one at a time at arc-completion via
     *  {@link #popNextPendingImpact} so the queue is empty when this is
     *  called. */
    public static void drainPendingImpactsImmediate(Level level) {
        if (level == null || level.pendingImpacts == null) return;
        while (!level.pendingImpacts.isEmpty()) {
            Runnable r = level.pendingImpacts.pollFirst();
            if (r != null) r.run();
        }
    }

    /** Pop the next queued pending-impact resolve (FIFO). The rgame Animator
     *  uses this in {@code onItemThrown}/{@code onWandMissileFired}/etc. to
     *  fetch the resolve callback whose visual arc it's about to schedule.
     *  Returns null if the queue is empty (defensive — shouldn't normally
     *  happen since throwItem queues before emitting the ItemThrown event). */
    public static Runnable popNextPendingImpact(Level level) {
        if (level == null || level.pendingImpacts == null) return null;
        return level.pendingImpacts.pollFirst();
    }

    /** Bresenham-walk every tile along the segment from {@code from} to {@code to} and
     *  return true the moment one of them is in {@code level.visible}. Used to decide
     *  whether an in-flight projectile is observable; a missile streaking entirely
     *  through unseen rooms shouldn't pause the world. Both endpoints null-tolerant. */
    public static boolean trajectoryTouchesVisible(Level level, Point from, Point to) {
        return MobVisibility.trajectoryTouchesVisible(level, from, to);
    }

    /** True iff {@code mob} is on a tile the player currently sees. The player counts as
     *  visible to themselves so messages and animations involving the player aren't
     *  redacted to "something". */
    public static boolean isVisibleToPlayer(Level level, Mob mob) {
        return MobVisibility.isVisibleToPlayer(level, mob);
    }

    /** Transition {@code mob} to AWAKE and emit a "wakes up" log entry when
     *  the tile is visible to the player. Centralises the
     *  {@code ASLEEP -> AWAKE} log so every AI dispatcher that checks the
     *  wake gate emits consistent messaging. No-op when {@code mob} is null
     *  or already AWAKE; player and off-screen wakes are silent. {@code
     *  reason} is the parenthetical (e.g. "sensing something nearby",
     *  "damaged by fire") - same wording as the existing Messages helper. */
    public static void wakeMob(Level level, Mob mob, String reason) {
        if (mob == null) return;
        StateOfMind prev = mob.stateOfMind;
        if (prev == StateOfMind.AWAKE) return;
        mob.stateOfMind = StateOfMind.AWAKE;
        if (mob.isPlayer) return;
        if (prev != StateOfMind.ASLEEP) return;
        if (!isVisibleToPlayer(level, mob)) return;
        EventLog.add(Messages.mobWakesUp(nameForLog(level, mob), reason));
    }

    /** Wake every non-player mob whose own {@code wakeRadius} reaches the loud event at
     *  {@code center} - a bomb blast or a damage-dealing wand strike always rouses nearby
     *  sleepers. Mobs already AWAKE are skipped. */
    public static void wakeMobsNear(Level level, Point center, String reason) {
        if (level == null || level.mobs == null || center == null) return;
        int cx = center.tileX(), cy = center.tileY();
        for (Mob m : level.mobs) {
            if (m == null || m.position == null || m.hp <= 0) continue;
            if (m.isPlayer) continue;
            if (m.stateOfMind == StateOfMind.AWAKE) continue;
            int d = Math.max(Math.abs(m.position.tileX() - cx),
                             Math.abs(m.position.tileY() - cy));
            if (d <= Math.max(1.0, m.effectiveStats().wakeRadius)) {
                wakeMob(level, m, reason);
            }
        }
    }

    /** True if the tile at {@code pos} is currently lit + in the player's
     *  FOV. Defensive against null {@code level.visible} (transient field
     *  reset on load) and out-of-bounds positions. */
    public static boolean tileVisibleToPlayer(Level level, Point pos) {
        if (level == null || pos == null || level.visible == null) return false;
        int x = pos.tileX(), y = pos.tileY();
        if (x < 0 || y < 0 || x >= level.width || y >= level.height) return false;
        return level.visible[x][y];
    }

    /** Display name for a mob in the event log. The player is always shown by name; any
     *  other mob the player can't currently see is rendered as "something". */
    public static String nameForLog(Level level, Mob mob) {
        return MobVisibility.nameForLog(level, mob);
    }

    /** Roll a uniform integer in {@code [range.min, range.max]} using the shared combat
     *  RNG. Public so out-of-package call sites (PlayScreen magic-missile resolution,
     *  FireSystem fire damage) can apply magic resist without having to maintain their
     *  own RNG. */
    public static int rollRange(MinMax range) {
        return MobStats.rollRange(range);
    }

    /** Move every item under the mob's feet from the ground into its bag (until the bag is full).
     *  Returns the number of items actually picked up - callers use this to decide whether
     *  to charge a move tick. */
    public static int pickupAtFeet(Level level, Mob mob) {
        return pickupAtFeet(level, mob, false);
    }

    /**
     * As {@link #pickupAtFeet(Level, Mob)}, but when {@code dropWorstWhenFull}
     * is set, an AI picker (SMART agent / auto-explore) whose bag group is full
     * drops its least valuable item in that group to make room - provided the
     * item underfoot is worth strictly more. Manual players never auto-drop, so
     * they keep full control of what leaves their bag.
     */
    public static int pickupAtFeet(Level level, Mob mob, boolean dropWorstWhenFull) {
        if (!mob.effectiveStats().canPickUp) return 0;
        int x = mob.position.tileX(), y = mob.position.tileY();
        boolean isPlayer = mob.isPlayer;
        String pickerName = mob.name != null ? mob.name : "?";
        int picked = 0;
        // Items bumped out of the bag to make room - placed on the floor AFTER
        // the loop so we don't mutate level.items mid-iteration.
        java.util.List<Item> droppedToFloor = null;
        Iterator<Item> it = level.items.iterator();
        while (it.hasNext()) {
            Item item = it.next();
            if (item.location == null) continue;
            if (item.location.tileX() != x || item.location.tileY() != y) continue;
            // POWERUP items are player-only - they're consumed on touch
            // by the player's own onMobEnteredTile path, never picked up
            // into anyone's bag. Non-player mobs leave them untouched.
            if (item.useBehavior == com.bjsp123.rl2.model.Item.UseBehavior.POWERUP) {
                continue;
            }
            // RL-36: NON-player mobs must not pick up the teleport ORB - thrown,
            // it scatters everyone in its blast to a random level, so a mob
            // grabbing one could fling the player away. The player may pick them up.
            if (!isPlayer && item.scattersOnThrow()) {
                continue;
            }
            // Snapshot the floor position BEFORE addToBag clears it, so the
            // ItemPickedUp event below can carry the source tile for the
            // arc-toward-bottom-right animation.
            Point fromTile = item.location;
            if (!InventorySystem.addToBag(mob.inventory, item)) {
                if (!dropWorstWhenFull) break;
                // Bag group full: bump the least valuable item in that group, but
                // only when the item underfoot is worth strictly more (no thrashing).
                Item worst = InventorySystem.leastValuableInGroup(
                        mob.inventory, item.inventoryCategory);
                if (worst == null || worst.getValue() >= item.getValue()) break;
                if (!InventorySystem.removeEntirely(mob.inventory, worst)) break;
                if (droppedToFloor == null) droppedToFloor = new java.util.ArrayList<>();
                droppedToFloor.add(worst);
                if (isPlayer) {
                    EventLog.add(Messages.itemDropped(pickerName,
                            worst.name != null ? worst.name : worst.type));
                }
                if (!InventorySystem.addToBag(mob.inventory, item)) break;
            }
            item.location = null;
            it.remove();
            picked++;
            if (isPlayer) {
                EventLog.add(Messages.pickupItem(pickerName, item.name));
            } else if (item.name != null && isVisibleToPlayer(level, mob)) {
                EventLog.add(Messages.mobPicksUpItem(nameForLog(level, mob), item.name));
            }
            if (mob.history != null) {
                mob.history.add(com.bjsp123.rl2.model.HistoricalRecord.itemFound(
                        level.currentTurn, level.depth, item.name));
            }
            if (level.events != null) {
                level.events.add(
                        new com.bjsp123.rl2.event.GameEvent.ItemPickedUp(mob, item, fromTile));
            }
            if (mob.isPlayer) {
                mob.runStats.itemsPickedUp++;
                if (item.isGem()) mob.runStats.gemsFound++;
            }
        }
        // Place any bumped items on the floor at the picker's tile (done after
        // iteration to avoid mutating level.items while iterating it).
        if (droppedToFloor != null) {
            for (Item d : droppedToFloor) {
                d.location = new Point(x, y);
                level.items.add(d);
            }
        }
        return picked;
    }

    // eat / drinkPotion moved to ItemSystem - they're item-effect dispatchers, not
    // mob-system primitives. ItemSystem.eat / ItemSystem.drinkPotion are the live
    // entry points; the helpers they need (removeFromInventory, processAttack,
    // applyWandImpact, igniteDisc, paintMixedFloraDisc, paintVegetationDisc,
    // radiusForTileCount) are package-private here.

    /**
     * Apply healing to a mob: bumps {@code mob.hp} up to {@code mob.effectiveStats().maxHp} (capped at
     * {@code amount}) and spawns a green {@code "+N"} floating-text effect at the mob's
     * tile so the player gets visible feedback. No-op for already-full HP. Called from
     * the inventory potion path; new heal sources should funnel through here so the
     * green-text contract stays in one place.
     */
    public static void heal(Level level, Mob mob, int amount) {
        if (mob == null || amount <= 0) return;
        if (mob.hp >= mob.effectiveStats().maxHp) return;
        int actual = (int) Math.min(amount, mob.effectiveStats().maxHp - mob.hp);
        mob.hp = Math.min(mob.effectiveStats().maxHp, mob.hp + actual);
        if (level != null && level.events != null && actual > 0) {
            level.events.add(new com.bjsp123.rl2.event.GameEvent.HealApplied(mob, actual));
        }
    }

    // applyWandImpact and radiusForTileCount moved to ItemSystem.

    /** Ignite every tile in the Euclidean disc of {@code radius} centred on
     *  {@code (cx, cy)}. Cells outside bounds or non-flammable (water/blood/walls/chasm)
     *  shrug off the ignite call inside {@link FireSystem#ignite}. Package-private so
     *  {@link ItemSystem}'s wand / bomb impact paths can reach it. */
    static void igniteDisc(Level level, int cx, int cy, int radius) {
        int r2 = radius * radius;
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dy = -radius; dy <= radius; dy++) {
                if (dx * dx + dy * dy > r2) continue;
                FireSystem.ignite(level, cx + dx, cy + dy);
            }
        }
    }

    /** Ignite a single tile when it carries something flammable - an oil
     *  surface, or grass / mushroom / tree vegetation. Bare floor and stone
     *  don't catch. Used by the wand-of-blast / DETONATION path so a
     *  concussive blast only spreads fire where there's actually something
     *  to burn. */
    public static void igniteIfFlammable(Level level, int x, int y) {
        if (x < 0 || y < 0 || x >= level.width || y >= level.height) return;
        Level.Surface s = level.surface[x][y];
        Level.Vegetation v = level.vegetation[x][y];
        boolean flammable = (s == Level.Surface.OIL)
                || (v == Level.Vegetation.GRASS
                        || v == Level.Vegetation.MUSHROOMS
                        || v == Level.Vegetation.TREES);
        if (flammable) FireSystem.ignite(level, x, y);
    }

    /** Public re-export of the package-private inventory remove for the wand/potion use
     *  paths that live in PlayScreen. */
    public static void removeFromInventoryPublic(Mob mob, Item it) {
        removeFromInventory(mob, it);
    }

    /** Consume one unit of {@code it} - decrements the bag stack (drops the entry if
     *  this was the last one) or unequips it from any slot it occupies. The "consume
     *  one" semantics apply to throw / eat / drink / use callers; {@link Inventory#bag}
     *  entries with {@code count > 1} represent stacks, so we do NOT remove the whole
     *  stack on a single use. */
    static void removeFromInventory(Mob mob, Item it) {
        if (mob == null || it == null) return;
        Inventory inv = mob.inventory;
        boolean wasEquipped = false;
        if (inv.weapon  == it) { inv.weapon  = null; wasEquipped = true; }
        if (inv.offhand == it) { inv.offhand = null; wasEquipped = true; }
        if (inv.armor   == it) { inv.armor   = null; wasEquipped = true; }
        for (int i = 0; i < inv.amulets.length; i++) {
            if (inv.amulets[i] == it) { inv.amulets[i] = null; wasEquipped = true; }
        }
        for (int i = 0; i < inv.gems.length; i++) {
            if (inv.gems[i] == it) { inv.gems[i] = null; wasEquipped = true; }
        }
        if (wasEquipped) return;
        InventorySystem.removeOneFromBag(mob.inventory, it);
    }

    static Point randomFloorPoint(Level level) {
        List<Point> floors = new ArrayList<>();
        for (int x = 0; x < level.width; x++)
            for (int y = 0; y < level.height; y++)
                if (level.tiles[x][y] == Tile.FLOOR) floors.add(new Point(x, y));
        if (floors.isEmpty()) return null;
        return floors.get(RANDOM.nextInt(floors.size()));
    }

    /** A random unoccupied floor tile on {@code level}, or null if none. Landing spot for
     *  mobs / items that fall through a chasm to the level below - chasm falls relocate to a
     *  random floor rather than a fixed arrival point. */
    static Point randomFreeFloor(Level level) {
        if (level == null) return null;
        for (int tries = 0; tries < 40; tries++) {
            Point p = randomFloorPoint(level);
            if (p == null) return null;
            if (MobLifecycle.isFreeFloor(level, p.tileX(), p.tileY())) return p;
        }
        Point p = randomFloorPoint(level);
        return p == null ? null : MobLifecycle.freeFloorNear(level, p);
    }
}
